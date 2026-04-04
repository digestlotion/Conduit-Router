package com.conduitrouter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.gregtechceu.gtceu.api.block.PipeBlock;
import com.gregtechceu.gtceu.client.model.GTModelProperties;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = conduitrouter.MOD_ID, value = Dist.CLIENT)
public class render {

    private static int[] getMasks(List<BlockPos> path, int i, Direction startDir, Direction endDir) {
        int connectionMask = 0;
        int blockedMask = 0;
        BlockPos curr = path.get(i);

        Direction toward = i < path.size() - 1 ? conduitrouteritem.getDir(curr, path.get(i + 1)) : null;
        Direction fromward = i > 0 ? conduitrouteritem.getDir(curr, path.get(i - 1)) : null;

        if (fromward != null) connectionMask |= (1 << fromward.get3DDataValue());
        if (toward != null) connectionMask |= (1 << toward.get3DDataValue());

        if (i == 0 && startDir != null) {
            connectionMask |= (1 << startDir.get3DDataValue());
            if (toward != null) blockedMask |= (1 << toward.get3DDataValue());
            for (Direction d : Direction.values()) {
                if (d == toward) continue;
                blockedMask &= ~(1 << d.get3DDataValue());
            }
        } else if (i == path.size() - 1 && endDir != null) {
            connectionMask |= (1 << endDir.get3DDataValue());
            blockedMask |= (1 << endDir.get3DDataValue());
            for (Direction d : Direction.values()) {
                if (d == endDir) continue;
                blockedMask &= ~(1 << d.get3DDataValue());
            }
        } else {
            if (toward != null) blockedMask |= (1 << toward.get3DDataValue());
            if (fromward != null) blockedMask &= ~(1 << fromward.get3DDataValue());
        }

        return new int[]{ connectionMask, blockedMask };
    }

    private static void renderGhostPipe(BlockState pipeState, BlockPos pos, int connectionMask, int blockedMask,
                                        Level level, PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                        BlockRenderDispatcher dispatcher) {
        ModelData modelData = ModelData.builder()
                .with(GTModelProperties.LEVEL, level)
                .with(GTModelProperties.POS, pos)
                .with(GTModelProperties.PIPE_CONNECTION_MASK, connectionMask)
                .with(GTModelProperties.PIPE_BLOCKED_MASK, blockedMask)
                .build();

        poseStack.pushPose();
        poseStack.translate(pos.getX(), pos.getY(), pos.getZ());

        dispatcher.renderSingleBlock(pipeState, poseStack, bufferSource,
                0xF000F0,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                modelData,
                RenderType.translucent());

        poseStack.popPose();
    }

    private static void renderPath(List<BlockPos> path, Direction startDir, Direction endDir,
                                   BlockState pipeState, Level level, PoseStack poseStack,
                                   MultiBufferSource.BufferSource bufferSource, BlockRenderDispatcher dispatcher) {
        for (int i = 0; i < path.size(); i++) {
            int[] masks = getMasks(path, i, startDir, endDir);
            renderGhostPipe(pipeState, path.get(i), masks[0], masks[1],
                    level, poseStack, bufferSource, dispatcher);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof conduitrouteritem)) return;

        ItemStack offhand = player.getOffhandItem();
        if (!(offhand.getItem() instanceof net.minecraft.world.item.BlockItem bi &&
                bi.getBlock() instanceof PipeBlock<?, ?, ?> pb)) return;
        BlockState pipeState = pb.defaultBlockState();

        PoseStack poseStack = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        Level level = player.level();

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();

        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);

        CompoundTag nbt = stack.getTag();
        boolean hasStart = nbt != null && nbt.contains("Start");
        boolean isAxisMode = conduitrouteritem.isAxisMode(nbt);

        // targetPos without offset
        BlockPos targetPos = conduitrouteritem.getTargetPos(player, level, false);
        BlockPos offsetPos = conduitrouteritem.getTargetPos(player, level, false);

        if (!hasStart) {
            // without Start
            Direction hoverDir = player.isShiftKeyDown() ? null : conduitrouteritem.getDir(targetPos, offsetPos);
            int mask = hoverDir != null ? (1 << hoverDir.get3DDataValue()) : 0;
            renderGhostPipe(pipeState, targetPos, mask, 0, level, poseStack, bufferSource, dispatcher);

        } else if (isAxisMode) {
            Direction startDir = nbt.contains("StartDir")
                    ? Direction.from3DDataValue(nbt.getInt("StartDir")) : null;

            // Confirmed path
            List<BlockPos> confirmed = conduitrouteritem.buildConfirmedPath(nbt);

            // live path
            List<BlockPos> allPoints = conduitrouteritem.getAllPoints(nbt);
            BlockPos lastPoint = allPoints.get(allPoints.size() - 1);
            Direction.Axis currentAxis = conduitrouteritem.getCurrentAxis(nbt);
            BlockPos fixedLive = conduitrouteritem.restrictToAxis(lastPoint, targetPos, currentAxis);
            List<BlockPos> liveSeg = conduitrouteritem.buildAxisPath(lastPoint, fixedLive);

            // Combine with render
            List<BlockPos> fullPath = new ArrayList<>(confirmed);
            if (!liveSeg.isEmpty() && !confirmed.isEmpty())
                liveSeg = liveSeg.subList(1, liveSeg.size());
            fullPath.addAll(liveSeg);

            if (!fullPath.isEmpty()) {
                renderPath(fullPath, startDir, null, pipeState, level, poseStack, bufferSource, dispatcher);
            }

        } else {
            // normal mode
            BlockPos start = NbtUtils.readBlockPos(nbt.getCompound("Start"));
            Direction startDir = nbt.contains("StartDir")
                    ? Direction.from3DDataValue(nbt.getInt("StartDir")) : null;
            Direction endDir = conduitrouteritem.getDir(targetPos, offsetPos);
            List<BlockPos> path = conduitrouteritem.findPath(start, targetPos, player);
            renderPath(path, startDir, endDir, pipeState, level, poseStack, bufferSource, dispatcher);
        }

        bufferSource.endBatch(RenderType.translucent());
        poseStack.popPose();
    }
}