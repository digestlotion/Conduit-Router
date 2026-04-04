package com.conduitrouter;

import com.conduitrouter.network.OpenDirectionGuiPacket;

import com.gregtechceu.gtceu.api.block.PipeBlock;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.IEnvironmentalHazardCleaner;
import com.gregtechceu.gtceu.api.machine.feature.IEnvironmentalHazardEmitter;
import com.gregtechceu.gtceu.api.pipenet.IPipeNode;
import com.gregtechceu.gtceu.common.blockentity.*;
import com.gregtechceu.gtceu.utils.GTTransferUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import org.jetbrains.annotations.Nullable;

import java.util.*;

@Mod.EventBusSubscriber
public class conduitrouteritem extends Item {

    public conduitrouteritem(Item.Properties properties) {
        super(properties);
    }

    // Utilities
    public static BlockPos getTargetPos(Player player, Level level, boolean isOffset) {
        double reach = player.getBlockReach();
        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 viewVec = player.getViewVector(1.0F);
        Vec3 endPos = eyePos.add(viewVec.scale(reach));

        BlockHitResult hitResult = level.clip(new ClipContext(eyePos, endPos,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));

        if (hitResult.getType() == HitResult.Type.BLOCK) {
            if (!isOffset || player.isShiftKeyDown()) return hitResult.getBlockPos();
            return hitResult.getBlockPos().relative(hitResult.getDirection());
        } else {
            return BlockPos.containing(endPos);
        }
    }

    public static Direction getDir(BlockPos a, BlockPos b) {
        return Direction.fromDelta(b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ());
    }

    public static BlockPos restrictToAxis(BlockPos origin, BlockPos target, Direction.Axis axis) {
        return switch (axis) {
            case X -> new BlockPos(target.getX(), origin.getY(), origin.getZ());
            case Y -> new BlockPos(origin.getX(), target.getY(), origin.getZ());
            case Z -> new BlockPos(origin.getX(), origin.getY(), target.getZ());
        };
    }

    // NBT helpers
    public static boolean isAxisMode(CompoundTag nbt) {
        return nbt != null && nbt.contains("CurrentAxis");
    }

    public static Direction.Axis getCurrentAxis(CompoundTag nbt) {
        return Direction.Axis.values()[nbt.getInt("CurrentAxis")];
    }

    // Returns all points in the path
    public static List<BlockPos> getAllPoints(CompoundTag nbt) {
        List<BlockPos> points = new ArrayList<>();
        points.add(NbtUtils.readBlockPos(nbt.getCompound("Start")));
        ListTag corners = nbt.getList("Corners", 10);
        for (int i = 0; i < corners.size(); i++) {
            points.add(NbtUtils.readBlockPos(corners.getCompound(i)));
        }
        return points;
    }

    // Builds the full path including the live segment, respecting the axis mode restrictions
    public static List<BlockPos> buildFullPath(CompoundTag nbt, BlockPos liveTarget, Player player) {
        List<BlockPos> points = getAllPoints(nbt);
        Direction.Axis currentAxis = getCurrentAxis(nbt);

        // The live segment goes from the last fixed point to the current target
        BlockPos lastPoint = points.get(points.size() - 1);
        BlockPos fixedLive = restrictToAxis(lastPoint, liveTarget, currentAxis);

        List<BlockPos> fullPath = new ArrayList<>();

        // Segment between fixed points
        for (int i = 0; i < points.size() - 1; i++) {
            List<BlockPos> seg = buildAxisPath(points.get(i), points.get(i + 1));
            if (i > 0 && !seg.isEmpty()) seg = seg.subList(1, seg.size());
            fullPath.addAll(seg);
        }

        // Live segment from last fixed point to current target
        List<BlockPos> liveSeg = buildAxisPath(lastPoint, fixedLive);
        if (!liveSeg.isEmpty() && !fullPath.isEmpty()) liveSeg = liveSeg.subList(1, liveSeg.size());
        fullPath.addAll(liveSeg);

        int limit = getLimit(player);
        return fullPath.subList(0, Math.min(fullPath.size(), limit));
    }

    private static void clearAxisMode(CompoundTag nbt, ItemStack stack) {
        nbt.remove("Start"); nbt.remove("StartDir"); nbt.remove("Selected");
        nbt.remove("CurrentAxis"); nbt.remove("Corners");
        stack.setTag(nbt);
    }

    // Path building
    private static void moveAlongAxis(List<BlockPos> path, BlockPos.MutableBlockPos cursor,
                                      int target, char axis) {
        int curr = switch (axis) {
            case 'x' -> cursor.getX();
            case 'y' -> cursor.getY();
            case 'z' -> cursor.getZ();
            default -> 0;
        };
        if (curr == target) return;
        int step = Integer.compare(target, curr);
        while (curr != target) {
            switch (axis) {
                case 'x' -> cursor.move(step, 0, 0);
                case 'y' -> cursor.move(0, step, 0);
                case 'z' -> cursor.move(0, 0, step);
            }
            curr = switch (axis) {
                case 'x' -> cursor.getX();
                case 'y' -> cursor.getY();
                case 'z' -> cursor.getZ();
                default -> 0;
            };
            path.add(cursor.immutable());
        }
    }

    public static List<BlockPos> findPath(BlockPos start, BlockPos end, Player player) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = start.mutable();
        path.add(cursor.immutable());

        int dx = end.getX() - start.getX();
        int dy = end.getY() - start.getY();
        int dz = end.getZ() - start.getZ();

        Vec3 look = player.getLookAngle();
        double ax = Math.abs(look.x);
        double ay = Math.abs(look.y);
        double az = Math.abs(look.z);

        char facingAxis;
        if (ax > ay && ax > az) facingAxis = 'x';
        else if (az > ax && az > ay) facingAxis = 'z';
        else facingAxis = 'y';

        List<Character> axes = new ArrayList<>();
        if (dx != 0) axes.add('x');
        if (dy != 0) axes.add('y');
        if (dz != 0) axes.add('z');

        if (axes.isEmpty()) return path;

        axes.sort((a, b) -> {
            if (a == facingAxis) return 1;
            if (b == facingAxis) return -1;
            int distA = a == 'x' ? Math.abs(dx) : (a == 'y' ? Math.abs(dy) : Math.abs(dz));
            int distB = b == 'x' ? Math.abs(dx) : (b == 'y' ? Math.abs(dy) : Math.abs(dz));
            return Integer.compare(distB, distA);
        });

        for (char axis : axes) {
            int target = switch (axis) {
                case 'x' -> end.getX();
                case 'y' -> end.getY();
                case 'z' -> end.getZ();
                default -> 0;
            };
            moveAlongAxis(path, cursor, target, axis);
        }

        int limit = getLimit(player);
        return path.subList(0, Math.min(path.size(), limit));
    }

    public static List<BlockPos> buildAxisPath(BlockPos origin, BlockPos target) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = origin.mutable();
        path.add(cursor.immutable());

        int dx = target.getX() - origin.getX();
        int dy = target.getY() - origin.getY();
        int dz = target.getZ() - origin.getZ();

        if (dx != 0) {
            int step = Integer.compare(dx, 0);
            int t = target.getX(); int c = cursor.getX();
            while (c != t) { cursor.move(step, 0, 0); c += step; path.add(cursor.immutable()); }
        } else if (dy != 0) {
            int step = Integer.compare(dy, 0);
            int t = target.getY(); int c = cursor.getY();
            while (c != t) { cursor.move(0, step, 0); c += step; path.add(cursor.immutable()); }
        } else if (dz != 0) {
            int step = Integer.compare(dz, 0);
            int t = target.getZ(); int c = cursor.getZ();
            while (c != t) { cursor.move(0, 0, step); c += step; path.add(cursor.immutable()); }
        }

        return path;
    }

    private static int getLimit(Player player) {
        return Math.min(config.CONFIG.placementLimit.get(),
                player.isCreative() ? config.CONFIG.placementLimit.get() : player.getOffhandItem().getCount());
    }

    public static List<BlockPos> buildConfirmedPath(CompoundTag nbt) {
        List<BlockPos> points = getAllPoints(nbt);
        List<BlockPos> fullPath = new ArrayList<>();

        for (int i = 0; i < points.size() - 1; i++) {
            List<BlockPos> seg = buildAxisPath(points.get(i), points.get(i + 1));
            if (i > 0 && !seg.isEmpty()) seg = seg.subList(1, seg.size());
            fullPath.addAll(seg);
        }
        return fullPath;
    }

    // Connection logic
    private void connectToHandler(Level level, BlockPos pos, IPipeNode<?, ?> node) {
        if (!config.CONFIG.autoconnect.get()) return;
        for (Direction d : Direction.values()) {
            if (!(level.getBlockEntity(pos.relative(d)) instanceof IPipeNode<?, ?>)) {
                if (node instanceof ItemPipeBlockEntity &&
                        GTTransferUtils.hasAdjacentItemHandler(level, pos, d) &&
                        !GTTransferUtils.hasAdjacentFluidHandler(level, pos, d)) {
                    node.setConnection(d, true, false);
                    node.setBlocked(d, true);
                }
                if (node instanceof FluidPipeBlockEntity &&
                        GTTransferUtils.hasAdjacentFluidHandler(level, pos, d)) {
                    node.setConnection(d, true, false);
                    node.setBlocked(d, true);
                }
                if (node instanceof CableBlockEntity &&
                        GTCapabilityHelper.getEnergyContainer(level, pos.relative(d), d.getOpposite()) != null) {
                    node.setConnection(d, true, false);
                }
                if (node instanceof LaserPipeBlockEntity &&
                        GTCapabilityHelper.getLaser(level, pos.relative(d), d.getOpposite()) != null) {
                    node.setConnection(d, true, false);
                }
                if (node instanceof DuctPipeBlockEntity &&
                        (GTCapabilityHelper.getHazardContainer(level, pos.relative(d), d.getOpposite()) != null ||
                                (level.getBlockEntity(pos.relative(d)) instanceof IMachineBlockEntity mbe &&
                                        (mbe.getMetaMachine() instanceof IEnvironmentalHazardCleaner ||
                                                mbe.getMetaMachine() instanceof IEnvironmentalHazardEmitter)))) {
                    node.setConnection(d, true, false);
                }
            }
        }
    }

    // Place path
    boolean placePath(Level level, ServerPlayer player, BlockPos start, BlockPos end,
                      CompoundTag nbt, ItemStack offhandStack,
                      @Nullable Direction startDir, @Nullable Direction endDir,
                      List<BlockPos> path) {
        String selectedId = nbt.getString("Selected");
        ResourceLocation resourceId = ResourceLocation.tryParse(selectedId);
        if (resourceId == null) return false;
        Block selectedBlock = ForgeRegistries.BLOCKS.getValue(resourceId);
        if (selectedBlock == null) return false;

        List<BlockPos> placedPath = new ArrayList<>();
        for (BlockPos pos : path) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.canBeReplaced()) {
                if (level.setBlock(pos, selectedBlock.defaultBlockState(), Block.UPDATE_ALL)) {
                    if (!player.isCreative()) offhandStack.shrink(1);
                    placedPath.add(pos);
                }
            } else if (state.getBlock() == selectedBlock) {
                placedPath.add(pos);
            }
        }

        for (int i = 0; i < placedPath.size(); i++) {
            BlockPos curr = placedPath.get(i);
            if (!(level.getBlockEntity(curr) instanceof IPipeNode<?, ?> currNode)) continue;

            if (i < placedPath.size() - 1) {
                BlockPos next = placedPath.get(i + 1);
                if (curr.distManhattan(next) == 1) {
                    Direction toNext = getDir(curr, next);
                    currNode.setConnection(toNext, true, false);
                    if (currNode.canHaveBlockedFaces()) currNode.setBlocked(toNext, true);
                    if (level.getBlockEntity(next) instanceof IPipeNode<?, ?> nextNode) {
                        nextNode.setConnection(toNext.getOpposite(), true, false);
                        if (nextNode.canHaveBlockedFaces()) nextNode.setBlocked(toNext.getOpposite(), true);
                    }
                }
            }

            if (currNode.canHaveBlockedFaces()) {
                Direction toPrev = i > 0 && curr.distManhattan(placedPath.get(i - 1)) == 1
                        ? getDir(curr, placedPath.get(i - 1)) : null;
                Direction toNext2 = i < placedPath.size() - 1 && curr.distManhattan(placedPath.get(i + 1)) == 1
                        ? getDir(curr, placedPath.get(i + 1)) : null;
                for (Direction d : Direction.values()) {
                    if (d == toPrev || d == toNext2) currNode.setBlocked(d, false);
                }
            }

            if (i == 0 && startDir != null) {
                currNode.setConnection(startDir, true, false);
                if (currNode.canHaveBlockedFaces()) currNode.setBlocked(startDir, true);
            }
            if (i == placedPath.size() - 1 && endDir != null) {
                currNode.setConnection(endDir, true, false);
                if (currNode.canHaveBlockedFaces()) currNode.setBlocked(endDir, true);
            }

            connectToHandler(level, curr, currNode);
        }

        for (BlockPos pos : placedPath) {
            if (level.getBlockEntity(pos) instanceof IPipeNode<?, ?> node) {
                node.scheduleRenderUpdate();
                node.scheduleNeighborShapeUpdate();
                node.notifyBlockUpdate();
            }
        }

        return true;
    }

    // use() — principal logic
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        CompoundTag nbt = stack.getOrCreateTag();

        if (level.isClientSide()) return InteractionResultHolder.pass(stack);
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResultHolder.fail(stack);
        if (!(player.getOffhandItem().getItem() instanceof BlockItem blockItem &&
                blockItem.getBlock() instanceof PipeBlock pipeblock))
            return InteractionResultHolder.fail(stack);

        BlockPos targetPos = getTargetPos(player, level, false);
        BlockPos offsetPos = getTargetPos(player, level, false);

        // SHIFT + CLICK
        if (player.isShiftKeyDown()) {

            // Shift + without Start → opens GUI
            if (!nbt.contains("Start")) {
                conduitrouter.NETWORK.sendTo(
                        new OpenDirectionGuiPacket(OpenDirectionGuiPacket.Phase.INITIAL),
                        serverPlayer.connection.connection,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
                return InteractionResultHolder.success(stack);
            }

            // Shift + axis mode with corners → place path
            if (isAxisMode(nbt) && !nbt.getList("Corners", 10).isEmpty()) {
                Direction startDir = nbt.contains("StartDir")
                        ? Direction.from3DDataValue(nbt.getInt("StartDir")) : null;

                List<BlockPos> path = buildFullPath(nbt, targetPos, player);
                if (!path.isEmpty()) {
                    BlockPos startPos = NbtUtils.readBlockPos(nbt.getCompound("Start"));
                    placePath(level, serverPlayer, startPos, targetPos, nbt,
                            player.getOffhandItem(), startDir, null, path);
                }

                clearAxisMode(nbt, stack);
                return InteractionResultHolder.success(stack);
            }

            // Shift + axis mode without corners → cancel
            if (isAxisMode(nbt)) {
                clearAxisMode(nbt, stack);
                return InteractionResultHolder.success(stack);
            }

            // Shift + normal mode with Start → cancel
            nbt.remove("Start"); nbt.remove("StartDir"); nbt.remove("Selected");
            stack.setTag(nbt);
            return InteractionResultHolder.success(stack);
        }

        // NORMAL CLICK
        // Without Start → saves Start and opens GUI
        if (!nbt.contains("Start")) {
            nbt.put("Start", NbtUtils.writeBlockPos(targetPos));
            Direction startDir = getDir(targetPos, offsetPos);
            if (startDir != null) nbt.putInt("StartDir", startDir.get3DDataValue());
            nbt.putString("Selected", ForgeRegistries.BLOCKS.getKey(pipeblock).toString());
            stack.setTag(nbt);
            return InteractionResultHolder.success(stack);
        }

        // Axis mode → add corner and determine next axis
        if (isAxisMode(nbt)) {
            List<BlockPos> allPoints = getAllPoints(nbt);
            BlockPos lastPoint = allPoints.get(allPoints.size() - 1);
            Direction.Axis currentAxis = getCurrentAxis(nbt);

            // restrict target to current axis
            BlockPos corner = restrictToAxis(lastPoint, targetPos, currentAxis);

            // save corner
            ListTag corners = nbt.getList("Corners", 10);
            corners.add(NbtUtils.writeBlockPos(corner));
            nbt.put("Corners", corners);

            Vec3 look = player.getLookAngle();
            double ax = Math.abs(look.x);
            double ay = Math.abs(look.y);
            double az = Math.abs(look.z);

            Direction.Axis nextAxis;
            if (currentAxis != Direction.Axis.X && ax >= ay && ax >= az) nextAxis = Direction.Axis.X;
            else if (currentAxis != Direction.Axis.Y && ay >= ax && ay >= az) nextAxis = Direction.Axis.Y;
            else if (currentAxis != Direction.Axis.Z) nextAxis = Direction.Axis.Z;
            else nextAxis = Direction.Axis.X; // fallback

            nbt.putInt("CurrentAxis", nextAxis.ordinal());
            stack.setTag(nbt);
            return InteractionResultHolder.success(stack);
        }

        BlockPos startPos = NbtUtils.readBlockPos(nbt.getCompound("Start"));
        Direction startDir = nbt.contains("StartDir")
                ? Direction.from3DDataValue(nbt.getInt("StartDir")) : null;
        Direction endDir = getDir(targetPos, offsetPos);

        List<BlockPos> path = findPath(startPos, targetPos, player);
        placePath(level, serverPlayer, startPos, targetPos, nbt,
                player.getOffhandItem(), startDir, endDir, path);

        nbt.remove("Start"); nbt.remove("StartDir"); nbt.remove("Selected");
        stack.setTag(nbt);
        return InteractionResultHolder.success(stack);
    }

    // Right click block — cancels placement
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        if (mainHand.getItem() instanceof conduitrouteritem &&
                offHand.getItem() instanceof BlockItem bi && bi.getBlock() instanceof PipeBlock) {
            event.setCanceled(true);
        }
    }

    // Tooltip
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltipComponents,
                                TooltipFlag isAdvanced) {
        tooltipComponents.add(Component.literal("§7Hold pipe in your offhand"));
        tooltipComponents.add(Component.literal("§7Right-click: place in L-shape"));
        tooltipComponents.add(Component.literal("§7Shift+Right-click: start axis mode"));
        tooltipComponents.add(Component.literal("§7  → Right-click: add corner freely"));
        tooltipComponents.add(Component.literal("§7  → Shift+Right-click: place all"));
    }
}