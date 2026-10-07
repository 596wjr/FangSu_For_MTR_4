package com.fangsu.util;

import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import com.fangsu.mappings.rail.RailPoseExtra;
import com.fangsu.mtr.AngleExtra;
import com.fangsu.mtr.rail.RailPoseExtraHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.data.RailType;
import org.mtr.mod.packet.PacketUpdateData;
import org.mtr.mod.packet.PacketUpdateLastRailStyles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 万向节点连接几何与建轨工具。
 * <p>
 * 提供：
 * <ul>
 *   <li>{@link #straightAngle(BlockPos, BlockPos)} — 两端点水平连线角度（直线轨道方向）</li>
 *   <li>{@link #maxRadiusTangentAngle(BlockPos, double, BlockPos)} — 未绑定端点的最大半径圆弧切向角</li>
 *   <li>{@link #getDirectionDegrees(Level, BlockPos)} / {@link #isConnectedAt(Level, BlockPos)} — 读取节点状态</li>
 *   <li>{@link #findConnectedEndpoints(BlockPos)} — 客户端查找连接到节点位置的其他端点</li>
 *   <li>{@link #createAndSendRail} — 服务端按连接器类型（限速/单向/站台/侧线/折返）构建并派发铁轨</li>
 *   <li>{@link #refreshNodeRail} — 服务端先校验候选几何、再删除旧轨道并按旧轨道属性（限速/单向/类型/样式）重建</li>
 *   <li>{@link #hasValidGeometry} — 客户端安全（不发包、不改世界）的几何预检，供配置界面红字警告使用</li>
 * </ul>
 */
public final class NodeConnector {

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
     * 双精度版本：用<b>节点锚点</b>（方块坐标 + 节点平移）而不是方块坐标算连线角度。
     * <p>
     * 节点平移是亚格量，整数版本会把 0.25 格的偏移直接丢掉，导致建轨时算出的"直线方向"
     * 与实际绘制出来的轨道方向不一致。偏移为 0 时两者结果完全相同。
     *
     * @param offsetA 端点 a 的锚点偏移 {@code {x, y, z}}（y 不参与水平角度）
     * @param offsetB 端点 b 的锚点偏移 {@code {x, y, z}}
     */
    public static double straightAngle(BlockPos a, double[] offsetA, BlockPos b, double[] offsetB) {
        final double dx = (b.getX() + offsetB[0]) - (a.getX() + offsetA[0]);
        final double dz = (b.getZ() + offsetB[2]) - (a.getZ() + offsetA[2]);
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
     * 双精度版本的最大半径圆弧切向角：把两个端点换成<b>节点锚点</b>（方块坐标 + 节点平移）。
     * <p>
     * 与整数版本的区别仅在固定点/自由点各加了自己的水平偏移；偏移为 0 时结果完全相同。
     * 退化（法线与连线平行）时同样降级为 {@link #straightAngle(BlockPos, double[], BlockPos, double[])}。
     *
     * @param offsetFixed 固定点锚点偏移 {@code {x, y, z}}
     * @param offsetFree  自由点锚点偏移 {@code {x, y, z}}
     */
    public static double maxRadiusTangentAngle(BlockPos fixed, double[] offsetFixed, double fixedAngle, BlockPos free, double[] offsetFree) {
        final double fx = fixed.getX() + offsetFixed[0];
        final double fz = fixed.getZ() + offsetFixed[2];
        final double px = free.getX() + offsetFree[0];
        final double pz = free.getZ() + offsetFree[2];
        final double rad = Math.toRadians(fixedAngle);

        final double dirX = Math.cos(rad);
        final double dirZ = Math.sin(rad);
        final double nX = -dirZ;
        final double nZ = dirX;

        final double dX = px - fx;
        final double dZ = pz - fz;
        final double nDotD = nX * dX + nZ * dZ;
        if (Math.abs(nDotD) < 1e-6) {
            return straightAngle(fixed, offsetFixed, free, offsetFree);
        }
        final double dDotD = dX * dX + dZ * dZ;
        final double t = dDotD / (2 * nDotD);
        final double cx = fx + t * nX;
        final double cz = fz + t * nZ;

        final double rX = px - cx;
        final double rZ = pz - cz;
        double tangX = -rZ;
        double tangZ = rX;

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

    // ==================== 节点锚点平移（P2） ====================

    /**
     * 读取某方块位置处的节点锚点平移 {@code {x, y, z}}（格）。
     * 万向节点取方块实体里的值；普通 MTR 节点或任意非节点方块返回全 0（原版行为）。
     */
    public static double[] readNodeOffset(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return new double[]{0.0D, 0.0D, 0.0D};
        }
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            return new double[]{node.getOffsetX(), node.getOffsetY(), node.getOffsetZ()};
        }
        return new double[]{0.0D, 0.0D, 0.0D};
    }

    /**
     * 读取某方块位置处的「单端附加姿态」：只有端点 1 带平移，端点 2 为零。
     * 调用方需要自行决定这个姿态属于轨道的哪一端（见 {@link #readRailPose}）。
     */
    public static RailPoseExtra readNodePose(Level level, BlockPos pos) {
        final double[] offset = readNodeOffset(level, pos);
        return new RailPoseExtra(
                offset[0], offset[1], offset[2],
                0.0D, 0.0D, 0.0D,
                RailPoseExtra.DEFAULT_HALF_GAUGE,
                0.0D, 0.0D,
                0.0D, 0.0D
        );
    }

    /**
     * 合成一条轨道的附加姿态：{@code offset1} 属于 {@code Rail.position1}（= 建轨时的 pos1），
     * {@code offset2} 属于 {@code Rail.position2}。
     * <p>
     * 这正是 {@link Rail#newRail} / {@code newPlatformRail} 等工厂的参数顺序，因此可以直接与
     * {@code RailPoseExtra} 的端点语义对应，无需关心 MTR 内部的 {@code reversePositions}
     * （那段对调只影响 {@code railMath} 的参数化方向，由 {@code FangSuRailMath} 处理）。
     * <p>
     * <b>阶段边界（P3）</b>：这里只搬运平移，俯仰 / 滚转字段<b>固定传 0</b> ——
     * 万向节点 BE 虽然已经存储了 {@code pitchDeg/rollDeg}（供节点模型倾斜），
     * 但把它们写进轨道姿态（外轨超高 / 纵坡在轨道截面与车体上生效）是下一步的事。
     * 在这条线上填非零值之前，{@code RailPoseExtra} 与轨道几何必须保持 P2 的行为。
     */
    public static RailPoseExtra readRailPose(Level level, BlockPos pos1, BlockPos pos2) {
        final double[] offset1 = readNodeOffset(level, pos1);
        final double[] offset2 = readNodeOffset(level, pos2);
        return new RailPoseExtra(
                offset1[0], offset1[1], offset1[2],
                offset2[0], offset2[1], offset2[2],
                RailPoseExtra.DEFAULT_HALF_GAUGE,
                0.0D, 0.0D,
                0.0D, 0.0D
        );
    }

    /** 把附加姿态写入轨道并重建几何（姿态为默认时等价于还原成 MTR 原生 RailMath）。 */
    public static void applyPose(Rail rail, RailPoseExtra pose) {
        RailPoseExtraHolder.apply(rail, pose);
    }

    /**
     * 把某个端点标记为「已连接」：万向节点写 BE 的 {@code connected}，普通 MTR 节点写 blockstate
     * 的 {@code IS_CONNECTED}。
     * <p>
     * <b>为什么刷新轨道后必须再调一次</b>：MTR 删除轨道时，{@code PacketDeleteData} 会对所有受影响端点
     * 调用 {@code BlockNode.resetRailNode}（已用 javap 核对到
     * {@code PacketDeleteData.lambda$runServerInbound$0 → BlockNode.resetRailNode}），
     * 而本项目的 {@code BlockNodeMixin} 把万向节点的 {@code connected} 也一并清成 false。
     * 轨道刷新是「先删旧轨、再建新轨」，所以若只在删除**之前**置 true，删除时的复位会把它覆盖掉，
     * 结果是：节点被错误地显示为未连接 —— 模型重新出现（旋转或固定），
     * 界面里「旋转绑定」也会因为 {@code isConnected()} 为假而从锁定变为可改。
     * 因此每成功重建一条轨道后，都要把这条轨道两端的端点重新标为已连接。
     */
    public static void markConnected(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            node.setConnected(true);
            return;
        }
        // 普通 MTR 节点：设置 blockstate IS_CONNECTED=true（与连接器建轨后的处理一致）
        final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BlockNode) {
            level.setBlock(pos, state.setValue(BlockNode.IS_CONNECTED.data, true),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
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
     * 服务端：以两个端点位置 + 两个角度，按连接器类型构建一条铁轨并派发到服务端数据。
     * <p>
     * 角度用 {@link AngleExtra} 生成精确值，避免 22.5° 快照。调用需在服务端线程。
     *
     * @param serverWorld 服务端世界（org.mtr.mapping.holder.ServerWorld）
     * @param pos1        端点 1
     * @param angle1      端点 1 角度（度）
     * @param pos2        端点 2
     * @param angle2      端点 2 角度（度）
     * @param railType    连接器类型（限速/站台/侧线/折返等）
     * @param isOneWay    是否单向（反向限速 0，渲染单向箭头）
     * @param uuid        玩家 UUID，用于应用玩家最后使用的轨道样式（null 时用默认样式）
     * @return 轨道是否创建成功（false = RailMath 几何不成立，如两端绑定方向与连线冲突）
     */
    public static boolean createAndSendRail(
            org.mtr.mapping.holder.ServerWorld serverWorld,
            BlockPos pos1, double angle1,
            BlockPos pos2, double angle2,
            RailType railType, boolean isOneWay, UUID uuid
    ) {
        final Position p1 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(pos1));
        final Position p2 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(pos2));
        // 复刻 Rail.getAngles 的朝向语义（端点1=离开方向、端点2=进入方向，按连线方向自动 ±180°），
        // 但跳过其内部的 Angle.fromAngle → getQuadrant 22.5° 快照：改用 AngleExtra 生成任意精度
        // Angle（幻影实例），使万向节点建出的轨道摆脱原版 16×22.5° 离散限制。
        // RailMath 几何计算只消费 angle.angleRadians/sin/cos 等连续值，任意角度完全支持。
        // 连线方向按「锚点 = 方块坐标 + 节点平移」算：节点平移是亚格量，用整数坐标会丢掉偏移。
        final double[] offset1 = readNodeOffset(serverWorld.data, pos1);
        final double[] offset2 = readNodeOffset(serverWorld.data, pos2);
        final double anchorDx = (p2.getX() + offset2[0]) - (p1.getX() + offset1[0]);
        final double anchorDz = (p2.getZ() + offset2[2]) - (p1.getZ() + offset1[2]);
        final double angleDifference = Math.toDegrees(Math.atan2(anchorDz, anchorDx));
        final double deg1 = normalizeDegrees(angle1 + (Angle.similarFacing((float) angleDifference, (float) angle1) ? 0 : 180));
        final double deg2 = normalizeDegrees(angle2 + (Angle.similarFacing((float) angleDifference, (float) angle2) ? 180 : 0));
        final Angle a1 = AngleExtra.fromDegrees(deg1);
        final Angle a2 = AngleExtra.fromDegrees(deg2);

        final Rail rail = buildRail(p1, a1, p2, a2, new ObjectArrayList<>(), railType.railShape, railType, isOneWay);
        if (rail == null || !rail.isValid()) {
            return false;
        }
        // 附加姿态（节点平移）：必须在 copy 之前写入，RailMixin 已在 Rail.copy 的 RETURN 上补姿态，
        // 所以复制出来的 styledRail 会自带；这里再对最终对象写一次，防止 SERVER_CACHE 内部走别的路径。
        final RailPoseExtra pose = readRailPose(serverWorld.data, pos1, pos2);
        RailPoseExtraHolder.apply(rail, pose);
        // 应用玩家最后使用的轨道样式：空样式会以"无模型"渲染（RenderRails 对空 styles 不绘制），
        // getRailWithLastStyles 会补上默认样式（CustomResourceLoader.DEFAULT_RAIL_ID），与原版
        // ItemRailModifier.createRail 行为一致。
        final Rail styledRail;
        if (uuid == null) {
            styledRail = Rail.copy(rail, ObjectArrayList.of(org.mtr.mod.client.CustomResourceLoader.DEFAULT_RAIL_ID));
        } else {
            styledRail = PacketUpdateLastRailStyles.SERVER_CACHE.getRailWithLastStyles(uuid, rail);
        }
        if (styledRail != null) {
            RailPoseExtraHolder.apply(styledRail, pose);
        }
        com.fangsu.Main.LOGGER.info("[NodeConnector] createAndSendRail {}->{} a1={} a2={} type={} oneWay={} rail={} valid={}", pos1, pos2, angle1, angle2, railType, isOneWay, styledRail, styledRail == null ? "n/a" : styledRail.isValid());
        PacketUpdateData.sendDirectlyToServerRail(serverWorld, styledRail);
        com.fangsu.Main.LOGGER.info("[NodeConnector] sent rail to server hexId={}", styledRail.getHexId());
        return true;
    }

    /**
     * 按连接器类型构建轨道（照抄原版 {@code ItemRailModifier.createRail} 的 TRAIN 分支）。
     * <ul>
     *   <li>PLATFORM/SIDING/TURN_BACK → 对应工厂方法（限速、站台/侧线/折返语义由 core 内部处理）</li>
     *   <li>其他 → {@link Rail#newRail}，单向时反向限速 0，RUNWAY 允许远程连接</li>
     * </ul>
     *
     * @param styles 轨道样式列表（空列表即可，发送前由调用方经 getRailWithLastStyles 补默认样式）
     */
    private static Rail buildRail(Position p1, Angle a1, Position p2, Angle a2, ObjectArrayList<String> styles, Rail.Shape shape, RailType railType, boolean isOneWay) {
        final boolean isPlatform = railType == RailType.PLATFORM;
        final boolean isSiding = railType == RailType.SIDING;
        final boolean canTurnBack = railType == RailType.TURN_BACK;
        if (isPlatform) {
            return Rail.newPlatformRail(p1, a1, p2, a2, shape, 0, styles, TransportMode.TRAIN);
        }
        if (isSiding) {
            return Rail.newSidingRail(p1, a1, p2, a2, shape, 0, styles, TransportMode.TRAIN);
        }
        if (canTurnBack) {
            return Rail.newTurnBackRail(p1, a1, p2, a2, shape, 0, styles, TransportMode.TRAIN);
        }
        return Rail.newRail(
                p1, a1, p2, a2,
                shape, 0, styles,
                isOneWay ? 0 : railType.speedLimit, railType.speedLimit,
                false, false, railType.canAccelerate,
                railType == RailType.RUNWAY, railType.hasSignal,
                TransportMode.TRAIN
        );
    }

    /**
     * 刷新重建一条轨道所需的属性（客户端从旧轨道读取，经网络包传到服务端）。
     */
    public record RailAttrs(
            long speedLimitAtNode,
            long speedLimitAtOther,
            Rail.Shape shape,
            boolean isPlatform,
            boolean isSiding,
            boolean canTurnBack,
            boolean canAccelerate,
            boolean canConnectRemotely,
            List<String> styles
    ) {
    }

    /**
     * 服务端：删除并重建连接 nodePos 与 otherPos 的单条轨道。
     * <p>
     * nodePos 为万向节点且已绑定新方向 newDirection；otherPos 为另一端（万向节点或普通节点）。
     * 删除旧轨道后，以新方向与另一端既有角度，按旧轨道属性（限速/单向/类型/样式）重建，
     * 保证角度调整后轨道外观与功能不丢失。
     * <p>
     * 另一端角度语义与 {@code com.fangsu.mixin.ItemNodeModifierBaseMixin#handleRailConnect} 一致：
     * 万向节点取绑定角度、普通节点 blockstate 角度即其绑定方向，均固定使用；
     * 固定角度组合在 RailMath 几何不成立（退化）时，若另一端是普通节点（无绑定意图），
     * 降级为该端取最大半径圆弧切向（与本端新方向平滑衔接），保证重建不静默失败。
     *
     * @param level        服务端世界
     * @param nodePos      万向节点位置（已绑定新方向）
     * @param newDirection 万向节点新方向（度）
     * @param otherPos     另一端位置
     * @param attrs        旧轨道属性（速度按端点位置对号入座，单向轨的 0 限速端跟随位置）
     * @return true = 已删除旧轨道并派发新轨道；false = 所有候选几何都非法，<b>旧轨道原样保留</b>
     */
    public static boolean refreshNodeRail(Level level, BlockPos nodePos, double newDirection, BlockPos otherPos, RailAttrs attrs) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return false;
        final org.mtr.mapping.holder.ServerWorld serverWorld = new org.mtr.mapping.holder.ServerWorld(serverLevel);

        final Position p1 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(nodePos));
        final Position p2 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(otherPos));

        final ObjectArrayList<String> styles = new ObjectArrayList<>(attrs.styles());
        // 角度必须用「锚点 = 方块坐标 + 节点平移」计算：节点平移是亚格量，
        // 用整数方块坐标算出来的 near/far 判向会在偏移较大时翻转，重建出的轨道方向与用户绑定不符。
        // 平移现在由 NODE_REFRESH_RAIL 一并送达（见 ModNetwork.handleNodeRefreshRail），
        // 服务端 BE 已是最新值，这里读到的就是用户在界面上刚编辑的姿态。
        final double[] nodeOffset = readNodeOffset(level, nodePos);
        final double[] otherOffset = readNodeOffset(level, otherPos);
        final double anchorDx = (p2.getX() + otherOffset[0]) - (p1.getX() + nodeOffset[0]);
        final double anchorDz = (p2.getZ() + otherOffset[2]) - (p1.getZ() + nodeOffset[2]);
        final double angleDifference = Math.toDegrees(Math.atan2(anchorDz, anchorDx));
        final double deg1 = normalizeDegrees(newDirection + (Angle.similarFacing((float) angleDifference, (float) newDirection) ? 0 : 180));

        // 另一端角度：万向节点取绑定角度；普通节点 blockstate 角度即其绑定方向（与原版节点语义一致）
        final boolean otherIsMultiDirectionNode = isMultiDirectionNode(level, otherPos);
        final double otherAngle = getDirectionDegrees(level, otherPos);
        final double deg2 = normalizeDegrees(otherAngle + (Angle.similarFacing((float) angleDifference, (float) otherAngle) ? 180 : 0));

        final RailPoseExtra pose = readRailPose(serverWorld.data, nodePos, otherPos);

        // ---- 先构建并校验候选轨道，全部非法就原样保留旧轨道 ----
        // 旧实现是「先按 hexId 删旧轨、再试几何」：几何非法时旧轨已经没了，
        // 轨道凭空消失且无法恢复（日志只留一句 invalid geometry）。删除必须推迟到候选通过校验之后。
        Rail candidate = buildRailForAngles(p1, p2, deg1, deg2, attrs, styles);
        if (candidate == null && !otherIsMultiDirectionNode) {
            // 另一端是普通节点（无绑定意图）时按旧行为降级：取最大半径圆弧切向与本端平滑衔接
            final double degraded = maxRadiusTangentAngle(nodePos, nodeOffset, newDirection, otherPos, otherOffset);
            final double deg2b = normalizeDegrees(degraded + (Angle.similarFacing((float) angleDifference, (float) degraded) ? 180 : 0));
            candidate = buildRailForAngles(p1, p2, deg1, deg2b, attrs, styles);
        }
        if (candidate == null) {
            com.fangsu.Main.LOGGER.warn("[NodeConnector] refreshNodeRail failed (invalid geometry) {}->{}: existing rail kept", nodePos, otherPos);
            return false;
        }
        // 附加姿态必须在派发前写入；写入后几何仍须有效，否则同样放弃（绝不先删后建）
        RailPoseExtraHolder.apply(candidate, pose);
        if (!candidate.isValid()) {
            com.fangsu.Main.LOGGER.warn("[NodeConnector] refreshNodeRail failed (invalid geometry after pose) {}->{}: existing rail kept", nodePos, otherPos);
            return false;
        }

        // 候选轨道通过全部校验，此刻才真正删除旧轨道并派发新轨道
        org.mtr.mod.packet.PacketDeleteData.sendDirectlyToServerRailId(
                serverWorld, org.mtr.core.data.TwoPositionsBase.getHexId(p1, p2));
        PacketUpdateData.sendDirectlyToServerRail(serverWorld, candidate);
        com.fangsu.Main.LOGGER.info("[NodeConnector] refreshed rail {}->{} hexId={}", nodePos, otherPos, candidate.getHexId());
        return true;
    }

    /**
     * 按两角度构建一条候选轨道，<b>不派发、不删除任何东西</b>。
     * <p>
     * 服务端重建（{@link #refreshNodeRail}）与客户端几何预检（{@link #hasValidGeometry}）
     * 共用这段构建逻辑，保证界面看到的「是否合法」与服务端实际会走的分支一致。
     *
     * @return 合法轨道；{@code null} = RailMath 几何不成立
     */
    @Nullable
    private static Rail buildRailForAngles(
            Position p1, Position p2,
            double deg1, double deg2,
            RailAttrs attrs, ObjectArrayList<String> styles
    ) {
        final Angle a1 = AngleExtra.fromDegrees(deg1);
        final Angle a2 = AngleExtra.fromDegrees(deg2);
        final Rail rail;
        if (attrs.isPlatform()) {
            rail = Rail.newPlatformRail(p1, a1, p2, a2, attrs.shape(), 0, styles, TransportMode.TRAIN);
        } else if (attrs.isSiding()) {
            rail = Rail.newSidingRail(p1, a1, p2, a2, attrs.shape(), 0, styles, TransportMode.TRAIN);
        } else if (attrs.canTurnBack()) {
            rail = Rail.newTurnBackRail(p1, a1, p2, a2, attrs.shape(), 0, styles, TransportMode.TRAIN);
        } else {
            // 普通轨：限速按端点位置对号入座（单向轨 0 限速端跟随位置），hasSignal 由 RUNWAY 标志推断
            rail = Rail.newRail(
                    p1, a1, p2, a2,
                    attrs.shape(), 0, styles,
                    attrs.speedLimitAtNode(), attrs.speedLimitAtOther(),
                    false, false, attrs.canAccelerate(),
                    attrs.canConnectRemotely(), !attrs.canConnectRemotely(),
                    TransportMode.TRAIN
            );
        }
        return rail != null && rail.isValid() ? rail : null;
    }

    // ==================== 客户端几何预检（界面红字警告） ====================

    /**
     * 客户端安全、<b>不发包也不修改世界</b>的几何预检：按服务端 {@link #refreshNodeRail} 相同的顺序
     * 构造候选轨道，只回答「当前编辑的姿态 + 方向能否产出至少一条合法轨道」。
     * <p>
     * 实现参考 {@code RenderRailsMixin} 的 ghost rail 预览：只用 {@code Init.blockPosToPosition}
     * 与 {@code Rail.newRail(...)} 构造核心对象，不接触任何服务端数据、不派发任何包。
     * 配置界面据此显示红色警告，并在姿态非法时<b>跳过</b>重建请求，避免触发一次注定失败的删除+重建。
     *
     * @param level        世界（只读：读另一端节点的平移与方向）
     * @param nodePos      万向节点位置
     * @param nodeOffset   界面里正在编辑的节点平移 {@code {x, y, z}}
     * @param newDirection 界面里正在编辑的方向（度）
     * @param otherPos     另一端位置
     * @param otherAngle   另一端当前方向（度）
     * @param shape        轨道形状（应与服务端重建时使用的形状一致）
     */
    public static boolean hasValidGeometry(Level level, BlockPos nodePos, double[] nodeOffset,
                                           double newDirection, BlockPos otherPos, double otherAngle,
                                           Rail.Shape shape) {
        return hasValidGeometry(level, nodePos, nodeOffset, newDirection, otherPos, otherAngle, attrsForShape(shape));
    }

    /**
     * {@link #hasValidGeometry(Level, BlockPos, double[], double, BlockPos, double, Rail.Shape)} 的
     * 完整属性版本：形状之外的站台/侧线/折返标记也一并按候选属性构建（与服务端分支一一对应）。
     */
    public static boolean hasValidGeometry(Level level, BlockPos nodePos, double[] nodeOffset,
                                           double newDirection, BlockPos otherPos, double otherAngle,
                                           RailAttrs attrs) {
        if (level == null || nodePos == null || otherPos == null || nodeOffset == null) {
            // 参数不全时按「有效」处理：宁可不显示警告，也不要误报红色
            return true;
        }
        final Position p1 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(nodePos));
        final Position p2 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(otherPos));
        final double[] otherOffset = readNodeOffset(level, otherPos);
        final double anchorDx = (p2.getX() + otherOffset[0]) - (p1.getX() + nodeOffset[0]);
        final double anchorDz = (p2.getZ() + otherOffset[2]) - (p1.getZ() + nodeOffset[2]);
        final double angleDifference = Math.toDegrees(Math.atan2(anchorDz, anchorDx));
        final double deg1 = normalizeDegrees(newDirection + (Angle.similarFacing((float) angleDifference, (float) newDirection) ? 0 : 180));
        final double deg2 = normalizeDegrees(otherAngle + (Angle.similarFacing((float) angleDifference, (float) otherAngle) ? 180 : 0));

        final ObjectArrayList<String> styles = new ObjectArrayList<>();
        if (buildRailForAngles(p1, p2, deg1, deg2, attrs, styles) != null) {
            return true;
        }
        // 与服务端一致：另一端是普通节点时允许降级为「最大半径圆弧切向」
        if (!isMultiDirectionNode(level, otherPos)) {
            final double degraded = maxRadiusTangentAngle(nodePos, nodeOffset, newDirection, otherPos, otherOffset);
            final double deg2b = normalizeDegrees(degraded + (Angle.similarFacing((float) angleDifference, (float) degraded) ? 180 : 0));
            return buildRailForAngles(p1, p2, deg1, deg2b, attrs, styles) != null;
        }
        return false;
    }

    /**
     * 只关心形状时的候选属性：站台/侧线/折返标记全 false，几何计算与这些标记无关。
     * <p>
     * <b>限速必须给正值，不能给 0</b>。MTR 4.0.5 的 {@code Rail.isValid()} 并不只看几何，它的实际语义是
     * （javap 真实 jar 逐指令核对）：
     * <pre>
     * return (speedLimit1 &gt; 0 || speedLimit2 &gt; 0) &amp;&amp; railMath.isValid() &amp;&amp; !(isPlatform &amp;&amp; isSiding);
     * </pre>
     * 也就是说「两端限速都为 0 的轨道一律被判为不合法」。而 {@code RailMath.isValid()} 是包私有、
     * {@code com.fangsu.*} 无法调用，所以客户端预检绕不开 {@code Rail.isValid()}。
     * 早先这里给的是 {@code 0L, 0L}，导致预检对<b>任何</b>姿态都返回不合法 ——
     * 界面因此恒显红字并跳过重建（实测现象：连刚绑定好的合法方向也会红字且不重建）。
     * <p>
     * 用正限速把 isValid() 的限速条件解除后，剩下的就是
     * {@code railMath.isValid() &amp;&amp; !(isPlatform &amp;&amp; isSiding)}；而本方法两个标记都是 false，
     * 于是判据恰好退化为「纯几何是否成立」——这正是预检想要的。
     * （真实轨道不会是 0/0：平台/侧线 80/80 与 80/40，折返 80/80，普通轨取 RailType 限速，
     *   单向轨也总有一端为正，所以服务端 {@code refreshNodeRail} 用旧轨真实属性做校验不受此影响。）
     */
    private static RailAttrs attrsForShape(Rail.Shape shape) {
        return new RailAttrs(1L, 1L, shape, false, false, false, false, false, List.of());
    }

    /** 该位置是否为 FangSu 万向节点（服务端降级分支与客户端预检共用同一判据）。 */
    private static boolean isMultiDirectionNode(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof com.fangsu.blocks.BlockMultiDirectionNode;
    }
}
