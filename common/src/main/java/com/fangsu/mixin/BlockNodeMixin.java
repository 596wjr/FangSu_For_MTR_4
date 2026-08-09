package com.fangsu.mixin;

import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mod.block.BlockNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让 MTR 的 {@link BlockNode#resetRailNode} 也能重置万向节点的 connected 状态。
 * <p>
 * MTR 在删除轨道后对所有受影响端点调用 {@code resetRailNode(serverWorld, blockPos)}，
 * 但原版仅处理 {@code instanceof BlockNode}。本 mixin 在末尾补充对
 * {@link BlockEntityMultiDirectionNode} 的处理。
 */
@Mixin(value = BlockNode.class, remap = false)
public class BlockNodeMixin {

    @Inject(method = "resetRailNode", at = @At("TAIL"), remap = false)
    private static void fangsu$resetMultiDirectionNode(ServerWorld serverWorld, BlockPos blockPos, CallbackInfo ci) {
        final net.minecraft.server.level.ServerLevel level = serverWorld.data;
        final net.minecraft.core.BlockPos pos = blockPos.data;
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            node.setConnected(false);
        }
    }
}
