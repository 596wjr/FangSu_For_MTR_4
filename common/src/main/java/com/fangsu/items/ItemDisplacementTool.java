package com.fangsu.items;

import com.fangsu.blocks.BlockMultiDirectionNode;
import com.fangsu.mappings.ComponentHelper;
import com.fangsu.network.DisplacementToolPackets;
import com.fangsu.utils.RegisterUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Vector;
import org.mtr.mod.Init;
import org.mtr.mod.client.MinecraftClientData;

import java.util.List;
import java.util.Map;

/**
 * 轨道位移工具（自 NTE 位移工具移植）。
 * <p>
 * 右键轨道节点（MTR 原版 {@code BlockNode} 或方速万向节点 {@link BlockMultiDirectionNode}）：
 * 选中「终点方向与玩家视线夹角最小」的轨道，将玩家沿轨道传送到其终点，保持玩家相对
 * 轨道起点的方向与位置不变，朝向随轨道累计转角同步旋转。不修改任何轨道数据。
 * <p>
 * 与原版 NTE 实现的差异（崩服风险修复）：
 * <ul>
 *   <li>全部轨道数据读取与位移计算在客户端完成，服务端只做纯 vanilla 校验与传送，
 *       不再于服务端直读 {@code RailwayData} 私有 rails map（原版通过 mixin 绕过锁）</li>
 *   <li>计算后校验坐标有限性并限制世界边界，拦截 NaN/Infinity 传送</li>
 *   <li>服务端对包内容独立校验，恶意/损坏包静默丢弃</li>
 * </ul>
 */
public class ItemDisplacementTool extends Item {

    /** 轨道端点与点击节点中心（块坐标 +0.5）的比对容差 */
    private static final double ENDPOINT_TOLERANCE = 1e-3;

    public ItemDisplacementTool() {
        super(RegisterUtil.tabProps(new Item.Properties().stacksTo(1)));
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        RegisterUtil.addDescTooltip(tooltip, "item.fangsu.displacement_tool.desc");
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        // 服务端零逻辑：所有计算在客户端完成，只发 C2S 传送请求
        if (!context.getLevel().isClientSide()) return InteractionResult.PASS;

        final Player player = context.getPlayer();
        if (player == null) return InteractionResult.SUCCESS;

        final BlockPos pos = context.getClickedPos();
        final BlockState state = context.getLevel().getBlockState(pos);
        final Block block = state.getBlock();
        if (!(block instanceof org.mtr.mod.block.BlockNode || block instanceof BlockMultiDirectionNode)) {
            return InteractionResult.PASS;
        }
        // 异常的玩家朝向直接放弃（选择比较器基于 yaw）
        if (!Float.isFinite(player.getYRot())) return InteractionResult.SUCCESS;

        final Rail rail = findFacingRail(player, pos);
        if (rail == null) return InteractionResult.SUCCESS;

        final double[] target = computeTeleport(rail, pos, player);
        if (target == null) return InteractionResult.SUCCESS;

        // 客户端数值校验：拦截 NaN/Infinity 与越界坐标，防止服务端实体坐标损坏
        final double tx = target[0], ty = target[1], tz = target[2], yaw = target[3];
        final double pitch = target[4];
        if (!Double.isFinite(tx) || !Double.isFinite(ty) || !Double.isFinite(tz) || !Double.isFinite(yaw)
                || !Double.isFinite(pitch) || Math.abs(tx) > 30000000 || Math.abs(tz) > 30000000
                || Math.abs(ty) > 4096 || Math.abs(yaw) > 3600f || Math.abs(pitch) > 3600f) {
            player.displayClientMessage(ComponentHelper.translatable("msg.fangsu.displacement_tool.invalid"), true);
            return InteractionResult.SUCCESS;
        }

        DisplacementToolPackets.sendTeleportC2S(pos, tx, ty, tz, (float) yaw, (float) pitch);
        return InteractionResult.SUCCESS;
    }

    /**
     * 两级查找策略找到玩家面对的轨道。
     * <p>
     * Level 1：MTR 现成对准 API（内部为角度比较逻辑并排除 CABLE），但准星可能命中
     * 相邻节点，返回的轨道必须通过端向量连通性验证才可接受。
     * Level 2：回退 —— 从 MTR 数据直接查点击节点的连接轨道，重实现角度比较
     * （MTR4 的 positionsToRail 为双向索引，点击任意端都能查到）。
     */
    private static Rail findFacingRail(Player player, BlockPos clickedPos) {
        final MinecraftClientData data = MinecraftClientData.getInstance();
        // Level 1：视线追踪
        final var pair = data.getFacingRailAndBlockPos(false);
        if (pair != null) {
            final Rail rail = pair.left();
            if (rail != null && connectsTo(rail, clickedPos)) {
                return rail;
            }
        }
        // Level 2：数据查询回退
        final Map<Position, Rail> connections = data.positionsToRail.get(
                Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(clickedPos)));
        if (connections == null || connections.isEmpty()) return null;
        Rail best = null;
        double bestDiff = Double.MAX_VALUE;
        final float yaw = player.getYRot();
        try {
            for (Map.Entry<Position, Rail> entry : connections.entrySet()) {
                final Position other = entry.getKey();
                final double diff = Mth.degreesDifferenceAbs(
                        (float) -Math.toDegrees(Math.atan2(other.getX() - clickedPos.getX(),
                                other.getZ() - clickedPos.getZ())), yaw);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = entry.getValue();
                }
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // 轨道数据更新中，放弃本次操作（不崩）
            return null;
        }
        return best;
    }

    /** 轨道是否连接到点击节点（端向量与块中心比对） */
    private static boolean connectsTo(Rail rail, BlockPos clickedPos) {
        final Vector[] endpoints = getEndpoints(rail);
        if (endpoints == null) return false;
        // 轨道端点 y 为整数块坐标（railMath 端点 = (x+0.5, y, z+0.5)，y 不加 0.5）
        final double cx = clickedPos.getX() + 0.5, cy = clickedPos.getY(), cz = clickedPos.getZ() + 0.5;
        return near(endpoints[0], cx, cy, cz) || near(endpoints[1], cx, cy, cz);
    }

    /**
     * 计算传送目标。返回 [tx, ty, tz, yaw, pitch]；
     * 轨道未连接到点击端、或轨道数据异常时返回 null。
     */
    private static double[] computeTeleport(Rail rail, BlockPos clickedPos, Player player) {
        final Vector[] endpoints = getEndpoints(rail);
        if (endpoints == null) return null;
        final Vector startVec = endpoints[0], endVec = endpoints[1];
        // 轨道端点 y 为整数块坐标（railMath 端点 = (x+0.5, y, z+0.5)，y 不加 0.5）
        final double cx = clickedPos.getX() + 0.5, cy = clickedPos.getY(), cz = clickedPos.getZ() + 0.5;

        // 端向量判定点击端（position1/position2 是 protected，无法直接读取）
        final boolean reversed;
        if (near(startVec, cx, cy, cz)) {
            reversed = false;
        } else if (near(endVec, cx, cy, cz)) {
            reversed = true;
        } else {
            return null; // 轨道不连接点击端（准星命中相邻节点）
        }
        final Vector clickedCenter = reversed ? endVec : startVec;
        final Vector farCenter = reversed ? startVec : endVec;

        // 玩家相对轨道起点的偏移（Vector 无 subtract，手写；与原版一致取插值位置）
        final net.minecraft.world.phys.Vec3 playerPos = player.getPosition(1);
        final Vector diff = new Vector(playerPos.x - clickedCenter.x,
                playerPos.y - clickedCenter.y,
                playerPos.z - clickedCenter.z);

        // 轨道累计转角 = 终点反方向角 - 起点角（与原版语义一致）
        final Angle clickedAngle = rail.getStartAngle(
                Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(clickedPos)));
        // farCenter 端点 = (x+0.5, y, z+0.5)，还原为整数块坐标；y 本身是整数，不能再减 0.5
        final Position farPos = new Position(
                (long) Math.floor(farCenter.x - 0.5), (long) Math.floor(farCenter.y),
                (long) Math.floor(farCenter.z - 0.5));
        final Angle farAngle = rail.getStartAngle(farPos);
        if (clickedAngle == null || farAngle == null) return null;
        final double rot = farAngle.getOpposite().angleRadians - clickedAngle.angleRadians;
        if (!Double.isFinite(rot)) return null;

        // 偏移旋转进轨道局部坐标系，加到终点（rotateY 符号与 1.20.1 Vec3.yRot 一致）
        final Vector rotated = diff.rotateY(-rot);
        final double tx = rotated.x + farCenter.x;
        final double ty = rotated.y + farCenter.y;
        final double tz = rotated.z + farCenter.z;
        final double yaw = player.getYRot() + Math.toDegrees(rot);
        return new double[]{tx, ty, tz, yaw, player.getXRot()};
    }

    /** 取轨道两端（沿 railMath 正向：字典序小的端点 → 大的一端）向量，数据异常返回 null */
    private static Vector[] getEndpoints(Rail rail) {
        try {
            final double length = rail.railMath.getLength();
            if (!Double.isFinite(length) || length < 1e-6) return null; // 退化轨道
            final Vector start = rail.railMath.getPosition(0, false);
            final Vector end = rail.railMath.getPosition(length, false);
            if (start == null || end == null) return null;
            if (!Double.isFinite(start.x) || !Double.isFinite(start.y) || !Double.isFinite(start.z)
                    || !Double.isFinite(end.x) || !Double.isFinite(end.y) || !Double.isFinite(end.z)) return null;
            return new Vector[]{start, end};
        } catch (RuntimeException ignored) {
            // 轨道数据异常时放弃本次操作（不崩）
            return null;
        }
    }

    private static boolean near(Vector v, double x, double y, double z) {
        return Math.abs(v.x - x) < ENDPOINT_TOLERANCE
                && Math.abs(v.y - y) < ENDPOINT_TOLERANCE
                && Math.abs(v.z - z) < ENDPOINT_TOLERANCE;
    }
}
