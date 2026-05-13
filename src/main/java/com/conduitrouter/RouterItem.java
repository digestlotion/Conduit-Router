package com.conduitrouter;

import com.gregtechceu.gtceu.api.block.PipeBlock;
import com.gregtechceu.gtceu.api.pipenet.IPipeNode;
import com.gregtechceu.gtceu.client.model.GTModelProperties;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

public class RouterItem extends Item {

    public RouterItem(Item.Properties properties) {
        super(properties);
    }

    public static List<BlockPos> findPathPos(BlockPos fromPos, BlockPos toPos) {
        if (fromPos.equals(toPos)) return List.of(fromPos);

        List<BlockPos> pathPos = new ArrayList<>();
        BlockPos.MutableBlockPos pointer = fromPos.mutable();

        while (!pointer.equals(toPos)) {
            pathPos.add(pointer.immutable());
            BlockPos delta = toPos.subtract(pointer);
            pointer.move(Direction.getNearest(delta.getX(), delta.getY(), delta.getZ()));
        }

        pathPos.add(pointer.immutable());
        return pathPos.subList(0, Math.min(32, pathPos.size()));
    }

    public static void placePathPos(
        Level level,
        Player player,
        Block block,
        List<BlockPos> pathPos
    ) {
        for (int i = 0; i < pathPos.size(); i++) {
            BlockPos curr = pathPos.get(i);
            BlockState state = level.getBlockState(curr);

            if (state.canBeReplaced()) {
                boolean placed = level.setBlock(curr, block.defaultBlockState(), 3);
                if (placed && !player.isCreative()) {
                    player.getOffhandItem().shrink(1);
                }
            }

            if (!(level.getBlockEntity(curr) instanceof IPipeNode<?, ?> currNode)) continue;

            if (i < pathPos.size() - 1) {
                BlockPos delta = pathPos.get(i + 1).subtract(curr);
                Direction toNext = Direction.getNearest(delta.getX(), delta.getY(), delta.getZ());
                currNode.setConnection(toNext, true, true);
                currNode.setBlocked(toNext, true);
            }
            if (i >= 1) {
                BlockPos delta = pathPos.get(i - 1).subtract(curr);
                Direction toPrev = Direction.getNearest(delta.getX(), delta.getY(), delta.getZ());
                currNode.setConnection(toPrev, true, true);
                currNode.setBlocked(toPrev, false);
            }
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
        Level level,
        Player player,
        InteractionHand hand
    ) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) return InteractionResultHolder.success(stack);
        BlockPos hitPos = BlockPos.containing(
            player.pick(player.getBlockReach(), 1.0f, false).getLocation()
        );
        ItemStack offStack = player.getOffhandItem();

        if (
            !(offStack.getItem() instanceof BlockItem blockItem &&
                blockItem.getBlock() instanceof PipeBlock pipeblock)
        ) return InteractionResultHolder.fail(stack);

        CompoundTag nbt = stack.getOrCreateTag();
        ListTag points = nbt.getList("points", 10);
        if (
            points.isEmpty() ||
            !NbtUtils.readBlockPos(points.getCompound(points.size() - 1)).equals(hitPos)
        ) {
            points.add(NbtUtils.writeBlockPos(hitPos));
            nbt.put("points", points);
            nbt.putString("block", ForgeRegistries.BLOCKS.getKey(pipeblock).toString());
            stack.setTag(nbt);
            return InteractionResultHolder.success(stack);
        }
        LinkedHashSet<BlockPos> pathPosSet = new LinkedHashSet<>();
        for (int i = 0; i < points.size() - 1; i++) {
            pathPosSet.addAll(
                findPathPos(
                    NbtUtils.readBlockPos(points.getCompound(i)),
                    NbtUtils.readBlockPos(points.getCompound(i + 1))
                )
            );
        }
        if (
            !pipeblock.equals(
                ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(nbt.getString("block")))
            )
        ) return InteractionResultHolder.fail(stack);
        List<BlockPos> pathPosList = new ArrayList<>(pathPosSet);
        placePathPos(level, player, pipeblock, pathPosList);
        nbt.remove("points");
        nbt.remove("block");
        return InteractionResultHolder.success(stack);
    }

    private static void renderGhostPipe(
        BlockState pipeState,
        BlockPos pos,
        int connectionMask,
        int blockedMask,
        Vec3 cam,
        Level level,
        PoseStack poseStack,
        MultiBufferSource.BufferSource bufferSource,
        BlockRenderDispatcher dispatcher
    ) {
        ModelData modelData = ModelData.builder()
            .with(GTModelProperties.LEVEL, level)
            .with(GTModelProperties.POS, pos)
            .with(GTModelProperties.PIPE_CONNECTION_MASK, connectionMask)
            .with(GTModelProperties.PIPE_BLOCKED_MASK, blockedMask)
            .build();

        poseStack.pushPose();
        poseStack.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);

        dispatcher.renderSingleBlock(
            pipeState,
            poseStack,
            bufferSource,
            0xF000F0,
            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
            modelData,
            RenderType.cutoutMipped()
        );

        poseStack.popPose();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof RouterItem)) return;
        ItemStack offhand = player.getOffhandItem();
        if (
            !(offhand.getItem() instanceof net.minecraft.world.item.BlockItem bi &&
                bi.getBlock() instanceof PipeBlock<?, ?, ?> pb)
        ) return;
        BlockState pipeState = pb.defaultBlockState();

        PoseStack poseStack = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        Level level = player.level();

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();

        poseStack.pushPose();

        CompoundTag nbt = stack.getOrCreateTag();
        BlockPos hitPos = BlockPos.containing(
            player.pick(player.getBlockReach(), 1.0f, false).getLocation()
        );
        renderGhostPipe(pipeState, hitPos, 0, 0, cam, level, poseStack, bufferSource, dispatcher);

        ListTag points = nbt.getList("points", 10);
        if (!points.isEmpty()) {
            LinkedHashSet<BlockPos> pathPosSet = new LinkedHashSet<>();
            for (int i = 0; i < points.size() - 1; i++) {
                pathPosSet.addAll(
                    findPathPos(
                        NbtUtils.readBlockPos(points.getCompound(i)),
                        NbtUtils.readBlockPos(points.getCompound(i + 1))
                    )
                );
            }
            pathPosSet.addAll(
                findPathPos(NbtUtils.readBlockPos(points.getCompound(points.size() - 1)), hitPos)
            );
            List<BlockPos> fullPath = new ArrayList<>(pathPosSet);

            for (int i = 0; i < fullPath.size(); i++) {
                BlockPos curr = fullPath.get(i);
                int cm = 0;
                int bm = 0;
                if (i < fullPath.size() - 1) {
                    BlockPos delta = fullPath.get(i + 1).subtract(curr);
                    cm |=
                        1 <<
                        Direction.getNearest(delta.getX(), delta.getY(), delta.getZ()).ordinal();
                    bm |=
                        1 <<
                        Direction.getNearest(delta.getX(), delta.getY(), delta.getZ()).ordinal();
                }
                if (i > 0) {
                    BlockPos delta = fullPath.get(i - 1).subtract(curr);
                    cm |=
                        1 <<
                        Direction.getNearest(delta.getX(), delta.getY(), delta.getZ()).ordinal();
                }
                renderGhostPipe(
                    pipeState,
                    curr,
                    cm,
                    bm,
                    cam,
                    level,
                    poseStack,
                    bufferSource,
                    dispatcher
                );
            }
        }

        bufferSource.endBatch(RenderType.cutoutMipped());
        poseStack.popPose();
    }
}
