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
 * MTR 删除轨道后会调用 {@code resetRailNode(serverWorld, blockPos)}，但原版仅处理
 * {@code instanceof BlockNode}。本 mixin 在末尾补充对 {@link BlockEntityMultiDirectionNode} 的处理。
 * <p>
 * <b>调用范围与时机的两个要点</b>（正是「单轨节点平移/旋转后变成未连接」的根因）：
 * <ol>
 *   <li>它只对「删除后 {@code positionsToRail} 里已经没有轨道」的端点调用
 *       （{@code DeleteDataRequest#delete} 的 {@code railNodePositionsToUpdate} 过滤），
 *       所以<b>只有一条轨道</b>的节点在旧轨被删掉的瞬间恰好命中这个条件；</li>
 *   <li>整条回调链是<b>延迟</b>执行的（{@code Init.sendMessageC2S → minecraftServer.execute}），
 *       落地时间晚于同一 tick 里重建轨道的 {@code markConnected}，会把刚写好的
 *       {@code connected} 覆盖成 false。</li>
 * </ol>
 * 因此任何「删旧轨 → 立刻建新轨」的路径都会误伤单轨节点。轨道刷新已改成原地替换、不再删除
 * （见 {@code NodeConnector#refreshNodeRail}），本 mixin 只服务于真正的删除路径。
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
