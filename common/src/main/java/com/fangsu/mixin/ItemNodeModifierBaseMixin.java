package com.fangsu.mixin;

import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import com.fangsu.blocks.BlockMultiDirectionNode;
import com.fangsu.util.NodeConnector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.mtr.core.data.TransportMode;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.ItemUsageContext;
import org.mtr.mod.block.BlockNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 MTR 轨道连接器（Rail Connector）识别并自动连接"万向节点"。
 * <p>
 * 覆盖点：
 * <ul>
 *   <li>{@code clickCondition} — 允许以万向节点作为连接起点/终点</li>
 *   <li>{@code onStartClick} — 万向节点作为起点时记录 transportMode（避免 BlockNode cast）</li>
 *   <li>{@code onEndClick} — 涉及时万向节点时按规则（直线 / 最大半径圆弧）计算并绑定方向后建轨</li>
 * </ul>
 */
@Mixin(value = org.mtr.mod.item.ItemNodeModifierBase.class, remap = false)
public abstract class ItemNodeModifierBaseMixin {

    /**
     * 允许连接器以万向节点作为起点/终点。
     */
    @Inject(method = "clickCondition", at = @At("HEAD"), remap = false, cancellable = true)
    private void fangsu$clickCondition(ItemUsageContext context, CallbackInfoReturnable<Boolean> cir) {
        final Level level = context.getWorld().data;
        if (level != null && isMultiDirectionNode(level, context.getBlockPos().data)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 万向节点作为起点时记录 transportMode（避开 BlockNode cast）。
     */
    @Inject(method = "onStartClick", at = @At("HEAD"), remap = false, cancellable = true)
    private void fangsu$onStartClick(ItemUsageContext context, CompoundTag compoundTag, CallbackInfo ci) {
        final Level level = context.getWorld().data;
        if (level != null && isMultiDirectionNode(level, context.getBlockPos().data)) {
            compoundTag.putString("transport_mode", TransportMode.TRAIN.toString());
            ci.cancel();
        }
    }

    /**
     * 涉及万向节点时接管建轨。
     */
    @Inject(method = "onEndClick", at = @At("HEAD"), remap = false, cancellable = true)
    private void fangsu$onEndClick(ItemUsageContext context, org.mtr.mapping.holder.BlockPos posEnd, CompoundTag compoundTag, CallbackInfo ci) {
        final Level level = context.getWorld().data;
        if (level == null || level.isClientSide) return;

        final BlockPos posStart = context.getBlockPos().data;
        final BlockPos endPos = posEnd.data;
        if (posStart.equals(endPos)) return;

        final boolean startIsNode = isMultiDirectionNode(level, posStart);
        final boolean endIsNode = isMultiDirectionNode(level, endPos);
        com.fangsu.Main.LOGGER.info("[NodeConnector] onEndClick start={} end={} startIsNode={} endIsNode={}", posStart, endPos, startIsNode, endIsNode);
        if (!startIsNode && !endIsNode) {
            return; // 原版逻辑
        }

        final boolean startBonded = isBonded(level, posStart);
        final boolean endBonded = isBonded(level, endPos);
        final double startAngle = nodeAngle(level, posStart);
        final double endAngle = nodeAngle(level, endPos);
        com.fangsu.Main.LOGGER.info("[NodeConnector] angles start={} (bonded={}) end={} (bonded={})", startAngle, startBonded, endAngle, endBonded);

        final double finalStartAngle;
        final double finalEndAngle;

        if (!startBonded && !endBonded) {
            // 两端均未绑定 → 直线
            final double straight = NodeConnector.straightAngle(posStart, endPos);
            finalStartAngle = straight;
            finalEndAngle = straight;
            bindNode(level, posStart, straight);
            bindNode(level, endPos, straight);
        } else if (!startBonded) {
            // 起点未绑定，终点已绑定/普通节点 → 起点取最大半径圆弧切向
            finalStartAngle = NodeConnector.maxRadiusTangentAngle(endPos, endAngle, posStart);
            finalEndAngle = endAngle;
            bindNode(level, posStart, finalStartAngle);
        } else if (!endBonded) {
            // 终点未绑定，起点已绑定/普通节点 → 终点取最大半径圆弧切向
            finalStartAngle = startAngle;
            finalEndAngle = NodeConnector.maxRadiusTangentAngle(posStart, startAngle, endPos);
            bindNode(level, endPos, finalEndAngle);
        } else {
            // 两端均已绑定 → 使用既有角度
            finalStartAngle = startAngle;
            finalEndAngle = endAngle;
        }
        com.fangsu.Main.LOGGER.info("[NodeConnector] final angles start={} end={}", finalStartAngle, finalEndAngle);

        final Object serverWorld = new org.mtr.mapping.holder.ServerWorld((net.minecraft.server.level.ServerLevel) level);
        NodeConnector.createAndSendRail(serverWorld, posStart, finalStartAngle, endPos, finalEndAngle);

        markConnected(level, posStart);
        markConnected(level, endPos);

        ci.cancel();
    }

    private static boolean isMultiDirectionNode(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof BlockMultiDirectionNode;
    }

    private static boolean isBonded(Level level, BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            return node.isDirectionBonded();
        }
        return true; // 普通节点视为已绑定
    }

    private static double nodeAngle(Level level, BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            return node.getDirectionDegrees();
        }
        return BlockNode.getAngle(new org.mtr.mapping.holder.BlockState(level.getBlockState(pos)));
    }

    private static void bindNode(Level level, BlockPos pos, double angle) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            node.setDirectionBonded(angle);
        }
    }

    /**
     * 标记节点已连接。
     * 万向节点写 BE NBT，普通 MTR 节点写 blockstate IS_CONNECTED=true。
     */
    private static void markConnected(Level level, BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            node.setConnected(true);
        } else {
            // 普通 MTR 节点：设置 blockstate IS_CONNECTED=true
            final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof BlockNode) {
                level.setBlock(pos, state.setValue(BlockNode.IS_CONNECTED.data, true), Block.UPDATE_ALL);
            }
        }
    }
}
