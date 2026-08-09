package com.fangsu.util;

import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import com.fangsu.mtr.AngleExtra;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.packet.PacketUpdateData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 万向节点连接几何与建轨工具。
 * <p>
 * 提供：
 * <ul>
 *   <li>{@link #straightAngle(BlockPos, BlockPos)} — 两端点水平连线角度（直线轨道方向）</li>
 *   <li>{@link #maxRadiusTangentAngle(BlockPos, double, BlockPos)} — 未绑定端点的最大半径圆弧切向角</li>
 *   <li>{@link #getDirectionDegrees(Level, BlockPos)} / {@link #isConnectedAt(Level, BlockPos)} — 读取节点状态</li>
 *   <li>{@link #findConnectedEndpoints(BlockPos)} — 客户端查找连接到节点位置的其他端点</li>
 *   <li>{@link #createAndSendRail} — 服务端用精确角度构建并派发铁轨</li>
 * </ul>
 */
public final class NodeConnector {

    private static final long SPEED_LIMIT = 80;

    private NodeConnector() {
    }

    /**
     * 水平面上两端点的连线角度（度，0=E, 90=S, 180=W, 270=N）。用于直线轨道方向绑定。
     */
    public static double straightAngle(BlockPos a, BlockPos b) {
        final double dx = b.getX() - a.getX();
        final double dz = b.getZ() - a.getZ();
        return normalizeDegrees(Math.toDegrees(Math.atan2(dz, dx)));
    }

    /**
     * 计算"最大半径圆弧"在未绑定端点处的切向角。
     * <p>
     * 几何：固定端点 F 的切向为 fixedAngle，求过 free 点且在该处与方向相切的圆（唯一解），
     * 返回该圆在 free 点处的切向角。这是满足平滑连接的"最大半径"圆弧。
     *
     * @param fixed      已绑定端点位置
     * @param fixedAngle 已绑定端点方向（度）
     * @param free       未绑定端点位置
     * @return free 点处的切向角（度）
     */
    public static double maxRadiusTangentAngle(BlockPos fixed, double fixedAngle, BlockPos free) {
        final double fx = fixed.getX(), fz = fixed.getZ();
        final double px = free.getX(), pz = free.getZ();
        final double rad = Math.toRadians(fixedAngle);

        // 方向单位向量 (cos, sin) 在 (x, z) 平面（X 向东，Z 向南）
        final double dirX = Math.cos(rad);
        final double dirZ = Math.sin(rad);
        // 法线（垂直于方向）
        final double nX = -dirZ;
        final double nZ = dirX;

        final double dX = px - fx;
        final double dZ = pz - fz;
        final double nDotD = nX * dX + nZ * dZ;
        if (Math.abs(nDotD) < 1e-6) {
            // 法线平行于连线 → 共线，退化为直线
            return straightAngle(fixed, free);
        }
        final double dDotD = dX * dX + dZ * dZ;
        final double t = dDotD / (2 * nDotD);
        final double cx = fx + t * nX;
        final double cz = fz + t * nZ;

        // free 端径向 (free - C)；切向 = 径向在水平面内顺时针旋转 90°
        final double rX = px - cx;
        final double rZ = pz - cz;
        double tangX = -rZ;
        double tangZ = rX;

        // 固定端径向 = F - C；固定端切向（顺时针90°）应与给定方向一致，否则翻转 free 端切向以保持旋向一致
        final double fixRadX = fx - cx;
        final double fixRadZ = fz - cz;
        final double fixTangX = -fixRadZ;
        final double fixTangZ = fixRadX;
        final double dot = fixTangX * dirX + fixTangZ * dirZ;
        if (dot >= 0) {
            tangX = -tangX;
            tangZ = -tangZ;
        }

        return normalizeDegrees(Math.toDegrees(Math.atan2(tangZ, tangX)));
    }

    /**
     * 归一化角度到 [0, 360)。
     */
    public static double normalizeDegrees(double deg) {
        deg = deg % 360.0;
        if (deg < 0) deg += 360.0;
        return deg;
    }

    /**
     * 读取节点方向的度数。万向节点取 BE NBT；普通节点取 blockstate 角度。
     */
    public static double getDirectionDegrees(Level level, BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            return node.getDirectionDegrees();
        }
        return BlockNode.getAngle(new org.mtr.mapping.holder.BlockState(level.getBlockState(pos)));
    }

    /**
     * 读取方块位置处的连接状态。万向节点读 BE NBT；普通 MTR 节点读 blockstate。
     */
    public static boolean isConnectedAt(Level level, BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            return node.isConnected();
        }
        final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BlockNode) {
            return state.getValue(BlockNode.IS_CONNECTED.data);
        }
        return false;
    }

    /**
     * 客户端：查找连接到某节点位置的其他端点（读取 MinecraftClientData.positionsToRail）。
     * 仅在客户端调用（数据已本地同步）。
     */
    public static List<BlockPos> findConnectedEndpoints(BlockPos nodePos) {
        final List<BlockPos> result = new ArrayList<>();
        final Position position = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(nodePos));
        final Map<Position, Rail> connections = org.mtr.mod.client.MinecraftClientData.getInstance().positionsToRail.get(position);
        if (connections == null) return result;
        for (final Position other : connections.keySet()) {
            result.add(new BlockPos((int) other.getX(), (int) other.getY(), (int) other.getZ()));
        }
        return result;
    }

    /**
     * 服务端：删除并重建连接 nodePos 与 otherPos 的单条轨道。
     * <p>
     * nodePos 为万向节点且已绑定新方向 newDirection；otherPos 为另一端（万向节点或普通节点）。
     * 删除旧轨道后，以新方向与另一端既有角度重建。
     *
     * @param level        服务端世界
     * @param nodePos      万向节点位置（已绑定新方向）
     * @param newDirection 万向节点新方向（度）
     * @param otherPos     另一端位置
     */
    public static void refreshNodeRail(Level level, BlockPos nodePos, double newDirection, BlockPos otherPos) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
        final org.mtr.mapping.holder.ServerWorld serverWorld = new org.mtr.mapping.holder.ServerWorld(serverLevel);

        // 删除旧轨道（按两端位置 hexId）
        final Position p1 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(nodePos));
        final Position p2 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(otherPos));
        org.mtr.mod.packet.PacketDeleteData.sendDirectlyToServerRailId(
                serverWorld, org.mtr.core.data.TwoPositionsBase.getHexId(p1, p2));

        // 另一端角度
        final double otherAngle = getDirectionDegrees(level, otherPos);
        // 本端为已绑定方向
        createAndSendRail(serverWorld, nodePos, newDirection, otherPos, otherAngle);
    }

    /**
     * 服务端：以两个端点位置 + 两个角度构建一条普通铁轨并派发到服务端数据。
     * <p>
     * 角度用 {@link com.fangsu.mtr.AngleExtra} 生成精确值，避免 22.5° 快照。调用需在服务端线程。
     *
     * @param serverWorld 服务端世界（org.mtr.mapping.holder.ServerWorld）
     * @param pos1        端点 1
     * @param angle1      端点 1 角度（度）
     * @param pos2        端点 2
     * @param angle2      端点 2 角度（度）
     */
    public static void createAndSendRail(
            Object serverWorld,
            BlockPos pos1, double angle1,
            BlockPos pos2, double angle2
    ) {
        final Position p1 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(pos1));
        final Position p2 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(pos2));
        // 复刻 Rail.getAngles 的朝向语义（端点1=离开方向、端点2=进入方向，按连线方向自动 ±180°），
        // 但跳过其内部的 Angle.fromAngle → getQuadrant 22.5° 快照：改用 AngleExtra 生成任意精度
        // Angle（幻影实例），使万向节点建出的轨道摆脱原版 16×22.5° 离散限制。
        // RailMath 几何计算只消费 angle.angleRadians/sin/cos 等连续值，任意角度完全支持。
        final double angleDifference = Math.toDegrees(Math.atan2(p2.getZ() - p1.getZ(), p2.getX() - p1.getX()));
        final double deg1 = normalizeDegrees(angle1 + (Angle.similarFacing((float) angleDifference, (float) angle1) ? 0 : 180));
        final double deg2 = normalizeDegrees(angle2 + (Angle.similarFacing((float) angleDifference, (float) angle2) ? 180 : 0));
        final Angle a1 = AngleExtra.fromDegrees(deg1);
        final Angle a2 = AngleExtra.fromDegrees(deg2);

        final Rail rail = Rail.newRail(
                p1, a1, p2, a2,
                Rail.Shape.QUADRATIC, 0,
                // 使用 MTR 默认轨道贴图样式，否则轨道会以"无模型"渲染
                org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList.of(
                        org.mtr.mod.client.CustomResourceLoader.DEFAULT_RAIL_ID),
                SPEED_LIMIT, SPEED_LIMIT,
                false, false, false, false, false, TransportMode.TRAIN
        );
        com.fangsu.Main.LOGGER.info("[NodeConnector] createAndSendRail {}->{} a1={} a2={} rail={} valid={}", pos1, pos2, angle1, angle2, rail, rail == null ? "n/a" : rail.isValid());
        if (rail == null || !rail.isValid()) {
            return;
        }
        if (serverWorld instanceof org.mtr.mapping.holder.ServerWorld sw) {
            PacketUpdateData.sendDirectlyToServerRail(sw, rail);
            com.fangsu.Main.LOGGER.info("[NodeConnector] sent rail to server hexId={}", rail.getHexId());
        }
    }
}
