package com.fangsu.network;

import com.fangsu.Main;
import com.fangsu.mappings.rail.RailGeometryCore;
import com.fangsu.mappings.rail.RailPoseExtra;
import com.fangsu.mappings.rail.RailRollProfile;
import com.fangsu.mtr.rail.FangSuRailMath;
import com.fangsu.mtr.rail.RailPoseExtraHolder;
import com.fangsu.mixin.InitAccessorMixin;
import com.fangsu.mixin.MainAccessorMixin;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TwoPositionsBase;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.mod.Init;
import org.mtr.mod.packet.PacketUpdateData;

/**
 * 逐轨道超高（外轨超高 / 逐轨道倾斜）C2S 通道。
 * <p>
 * 客户端把「玩家正在编辑的那条轨道」的倾斜控制点发给服务端，服务端<b>校验后</b>写进轨道姿态
 * （{@link RailPoseExtra}）并<b>显式重播</b>，使所有客户端都能看到。
 * <p>
 * 为什么必须有这条独立通道，而不是复用 MTR 自己的 {@code PacketUpdateData}：
 * <ul>
 *   <li>那是「整条轨道替换」，服务端不校验任何字段（MTR 的既有设计），把轨道几何/限速/样式
 *       的写权限直接交给客户端；本通道只允许改三个倾斜角 + 半轨距，服务端自己算出中间控制点位置，
 *       其余字段一概不动；</li>
 *   <li>需要服务端权威地做有限性、范围、距离与权限校验，并把非法请求记日志丢弃。</li>
 * </ul>
 * <p>
 * <b>载荷（客户端写的顺序即服务端读的顺序，逐字段对应）</b>：
 * <pre>
 *   BlockPos p1             端点 1（轨道两端之一，顺序无关）
 *   BlockPos p2             端点 2
 *   boolean  setTilt        true = 写入三个控制点；false = 清除逐轨道超高（回到节点派生滚转）
 *   boolean  clearTilt      true = 显式清除（与 setTilt=false 同义，留字段给「清除并保持半轨距」语义）
 *   double   startDegrees   起点控制点（度，客户端已夹到 ±45）
 *   double   middleDegrees  中间控制点（度）
 *   double   endDegrees     终点控制点（度）
 *   float    halfGauge      半轨距（米，客户端已夹到 [0.25, 2.0]；非有限值 = 沿用当前值）
 * </pre>
 * 轨道身份用 {@link TwoPositionsBase#getHexId(Position, Position)}（确定性、与端点顺序无关），
 * 服务端<b>自己</b>用收到的两个位置重算 hexId 再查表，因此客户端无法用伪造的 id 命中别的轨道。
 * <p>
 * 本通道<b>不做版本探测</b>：客户端与服务端永远运行同一份 FangSu 构建（与
 * {@code ModNetwork.handleNodeRefreshRail} 同一约定）。
 */
public final class RailTiltPackets {

    /** C2S：编辑某条轨道的逐轨道超高 */
    public static final ResourceLocation RAIL_TILT_EDIT = new ResourceLocation("fangsu", "rail_tilt_edit");

    /** 半轨距下限（米）：再窄就不是「一条线」了，也避免抬升量小到看不见。 */
    public static final double MIN_HALF_GAUGE = 0.25D;
    /** 半轨距上限（米）：4 m 轨距，远超任何现实轨道。 */
    public static final double MAX_HALF_GAUGE = 2.0D;
    /** 玩家到轨道端点的最大允许距离（格）：建轨交互距离 5 格，这里留足余量以覆盖长轨道的远端。 */
    private static final double MAX_EDIT_DISTANCE = 32.0D;
    /** 服务端写权限等级：与 MTR 自带的方块编辑一致（1 = 普通玩家，2 = OP/作弊）。 */
    private static final int REQUIRED_PERMISSION_LEVEL = 2;

    private RailTiltPackets() {
    }

    /** 服务端注册（由 {@link ModNetwork#init} 调用，注册位置紧挨 NODE_REFRESH_RAIL）。 */
    public static void registerServer() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, RAIL_TILT_EDIT, RailTiltPackets::handleRailTiltEdit);
    }

    private static void handleRailTiltEdit(FriendlyByteBuf buf, NetworkManager.PacketContext ctx) {
        // 载荷必须在主线程排队<b>之前</b>读完：排队回调可能在网络缓冲区释放之后才执行
        final BlockPos p1 = buf.readBlockPos();
        final BlockPos p2 = buf.readBlockPos();
        final boolean setTilt = buf.readBoolean();
        final boolean clearTilt = buf.readBoolean();
        final double startDegrees = buf.readDouble();
        final double middleDegrees = buf.readDouble();
        final double endDegrees = buf.readDouble();
        final float halfGauge = buf.readFloat();

        ctx.queue(() -> {
            final ServerPlayer player = (ServerPlayer) ctx.getPlayer();
            if (player == null) {
                return;
            }
            //#if MC_VERSION >= 12000
            final Level level = player.level();
            //#else
            //$$ final Level level = player.level;
            //#endif
            if (level == null || p1 == null || p2 == null || p1.equals(p2)) {
                logIgnore("载荷不全或两端点相同", p1, p2);
                return;
            }
            // 写权限：没有权限的玩家不能改世界数据（C2S 校验，历史遗留的「无服务端校验」不再扩散）
            if (!player.hasPermissions(REQUIRED_PERMISSION_LEVEL)) {
                logIgnore("发送者没有写权限", p1, p2);
                return;
            }
            // 距离：轨道两端至少有一端在玩家附近，避免「隔半个世界改别人轨道」
            if (minDistanceToRail(player, p1, p2) > MAX_EDIT_DISTANCE) {
                logIgnore("发送者距离轨道过远", p1, p2);
                return;
            }

            final Position position1 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(p1));
            final Position position2 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(p2));
            // 服务端自己算 hexId（顺序无关）：不信任客户端可能伪造的 id
            final String hexId = TwoPositionsBase.getHexId(position1, position2);

            // 倾斜角校验：setTilt 时三个角必须有限并夹到 ±45；clearTilt（或 setTilt=false）时清零。
            // 用「非法就整条丢弃」而不是「夹取」，是因为客户端已经夹过一次，再收到越界值只可能是
            // 被改过的包；静默夹取会让攻击者以为请求生效。
            final double[] tiltDegrees;
            if (setTilt && !clearTilt) {
                if (!Double.isFinite(startDegrees) || !Double.isFinite(middleDegrees) || !Double.isFinite(endDegrees)) {
                    logIgnore("倾斜角非有限值", p1, p2);
                    return;
                }
                final double limit = RailPoseExtra.MAX_RAIL_TILT_DEGREES;
                if (Math.abs(startDegrees) > limit || Math.abs(middleDegrees) > limit || Math.abs(endDegrees) > limit) {
                    logIgnore("倾斜角超出 ±" + limit + " 度", p1, p2);
                    return;
                }
                tiltDegrees = new double[]{startDegrees, middleDegrees, endDegrees};
            } else {
                tiltDegrees = null;
            }
            // 半轨距：非有限值 = 「沿用当前值」；有限值必须落在 [0.25, 2.0]
            if (Float.isFinite(halfGauge) && (halfGauge < MIN_HALF_GAUGE || halfGauge > MAX_HALF_GAUGE)) {
                logIgnore("半轨距超出 [" + MIN_HALF_GAUGE + ", " + MAX_HALF_GAUGE + "]", p1, p2);
                return;
            }
            final double requestedHalfGauge = Float.isFinite(halfGauge) ? halfGauge : Double.NaN;

            // 维度匹配（与 HiddenRoutesPackets 同款）：遍历 TSC 的 Simulator 按 dimension 定位
            final String dimensionId = Init.getWorldId(new org.mtr.mapping.holder.World(level));
            for (final Simulator simulator : ((MainAccessorMixin) InitAccessorMixin.getMain()).getSimulators()) {
                if (!simulator.dimension.equals(dimensionId)) {
                    continue;
                }
                // 全部读写都在模拟器线程内完成（与 HiddenRoutesPackets 一致）：
                // railIdMap 与轨道姿态都归 Simulator 所有，主线程直接写会与模拟 tick 竞争。
                // ServerLevel 只是重播时构造 MTR 的 ServerWorld 包装要用，已经在主线程取好。
                // 失败原因用 holder 带回主线程再记日志：模拟器线程只碰数据、不碰日志。
                final FailureReason reason = new FailureReason();
                simulator.run(() -> applyRailTilt(simulator, hexId, tiltDegrees, requestedHalfGauge, p1, p2, level, reason));
                if (reason.text != null) {
                    logIgnore(reason.text, p1, p2);
                }
                return;
            }
            logIgnore("找不到该维度对应的 Simulator", p1, p2);
        });
    }

    /** 模拟器线程 → 主线程的失败原因载体（{@code null} 表示成功）。 */
    private static final class FailureReason {
        private String text;
    }

    /**
     * 模拟器线程内：按 hexId 找到轨道、合并姿态、落盘并<b>显式重播</b>。
     * <p>
     * 只覆盖「逐轨道超高 + 半轨距」，两端平移 / 俯仰 / 节点派生滚转原样保留
     * （见 {@link RailPoseExtra#withRailTilt}），所以不会把客户端可能过期的节点几何写回服务端。
     *
     * @param level 发送者所在的服务端世界，只为构造 MTR 的 {@code ServerWorld} 包装（重播用）
     * @param reason 失败原因回填（成功时保持 {@code null}）；日志由主线程记录
     */
    private static void applyRailTilt(Simulator simulator, String hexId, double[] tiltDegrees, double requestedHalfGauge, BlockPos p1, BlockPos p2, Level level, FailureReason reason) {
        final Rail rail = simulator.railIdMap.get(hexId);
        if (rail == null) {
            reason.text = "该轨道不在服务端数据里（hexId=" + hexId + "）";
            return;
        }
        final RailPoseExtraHolder holder = (RailPoseExtraHolder) (Object) rail;
        final RailPoseExtra current = holder.getFangSuPose();
        final double halfGauge = Double.isFinite(requestedHalfGauge) ? requestedHalfGauge : current.halfGauge;
        // 注意顺序：先算中间控制点位置（要用 rail 当前几何），再改姿态
        final double middleFraction = tiltDegrees == null
                ? RailPoseExtra.DEFAULT_RAIL_TILT_MIDDLE_FRACTION
                : middleBreakpointFraction(rail, current);
        final RailPoseExtra updated = tiltDegrees == null
                ? current.withRailTilt(null, null, null, middleFraction, halfGauge)
                : current.withRailTilt(tiltDegrees[0], tiltDegrees[1], tiltDegrees[2], middleFraction, halfGauge);
        RailPoseExtraHolder.apply(rail, updated);

        // 显式重播：MTR 的周期性 get_data 轮询<b>不检测变化</b>（服务端会把已在客户端
        // existingRailIds 里的轨道跳过），所以每次写都必须主动广播，否则其他玩家永远看不到。
        // 重播走 MTR 自己的 UPDATE_DATA（responseType = ALL，全服广播），与建轨/刷新路径一致；
        // 此刻姿态已落在轨道对象上，广播出去的载荷自然带着新的逐轨道超高。
        if (level instanceof ServerLevel serverLevel) {
            PacketUpdateData.sendDirectlyToServerRail(new org.mtr.mapping.holder.ServerWorld(serverLevel), rail);
        } else {
            reason.text = "姿态已写入但无法重播：发送者世界不是 ServerLevel";
        }
    }

    /**
     * 中间控制点应落在的归一化位置。
     * <p>
     * 曲线轨取两半径接缝 {@code count1 / length}（与 MTR 上游
     * {@code middlePoint = count1 == 0 || count2 == 0 ? length / 2 : count1} 一致），
     * 退化时取中点；<b>不照抄 MAGIC 的硬编码 0.5</b>。
     * <p>
     * <b>不变式：返回值（也就是最终存进 {@link RailPoseExtra#railTiltMiddleFraction} 的值）
     * 永远以 {@code position1 → position2} 为 0 → 1。</b>渲染端 {@code FangSuRailMath#rollProfileFor}
     * 就按这个约定消费（参数 0 端不是 position1 时把剖面整体镜像），界面也按「从起点算起的百分比」
     * 显示它。几何接缝本身是在 <b>railMath 参数空间</b> 里算的，参数 0 端在
     * {@code position1.compareTo(position2) > 0} 时是 position2，所以两处取帧必须显式换算：
     * 交给 {@link FangSuRailMath#middleBreakpointFraction(com.fangsu.mappings.rail.RailGeometryCore, boolean)}
     * 并传入端点顺序，由它统一做「参数空间 → position1 → position2」的镜像。
     * <p>
     * 轨道可能还没装附加姿态（第一次编辑超高之前 {@code railMath} 就是 MTR 原生实例，
     * 其 {@code getLength1/getLength2} 是包私有），此时用与 {@code FangSuRailMath} 相同的参数
     * 现建一个几何内核来取接缝位置 —— 内核是纯数学、与线程无关，取到的就是真实几何。
     */
    private static double middleBreakpointFraction(Rail rail, RailPoseExtra current) {
        if (rail.railMath instanceof FangSuRailMath) {
            // 该实例自己记着构造时的端点顺序（FangSuRailMath#firstIsPosition1），
            // 直接复用同一份判断，避免这里再按 compareTo 推一次而口径不一致
            return FangSuRailMath.middleBreakpointFraction(rail.railMath);
        }
        final RailPoseExtraHolder holder = (RailPoseExtraHolder) (Object) rail;
        final Position position1 = holder.fangsu$getPosition1();
        final Position position2 = holder.fangsu$getPosition2();
        if (position1 == null || position2 == null) {
            return RailPoseExtra.DEFAULT_RAIL_TILT_MIDDLE_FRACTION;
        }
        final boolean firstIsPosition1 = position1.compareTo(position2) <= 0;
        final Position firstPosition = firstIsPosition1 ? position1 : position2;
        final Position secondPosition = firstIsPosition1 ? position2 : position1;
        final Angle firstAngle = firstIsPosition1 ? holder.fangsu$getAngle1() : holder.fangsu$getAngle2();
        final Angle secondAngle = firstIsPosition1 ? holder.fangsu$getAngle2() : holder.fangsu$getAngle1();
        if (firstAngle == null || secondAngle == null) {
            return RailPoseExtra.DEFAULT_RAIL_TILT_MIDDLE_FRACTION;
        }
        // 与 FangSuRailMath 完全相同的锚点/参数（此时还没有逐轨道倾斜，剖面取空）
        final double[] anchor1 = {
                firstPosition.getX() + (firstIsPosition1 ? current.offsetX1 : current.offsetX2),
                firstPosition.getY() + (firstIsPosition1 ? current.offsetY1 : current.offsetY2),
                firstPosition.getZ() + (firstIsPosition1 ? current.offsetZ1 : current.offsetZ2)
        };
        final double[] anchor2 = {
                secondPosition.getX() + (firstIsPosition1 ? current.offsetX2 : current.offsetX1),
                secondPosition.getY() + (firstIsPosition1 ? current.offsetY2 : current.offsetY1),
                secondPosition.getZ() + (firstIsPosition1 ? current.offsetZ2 : current.offsetZ1)
        };
        final RailGeometryCore core = new RailGeometryCore(
                anchor1, anchor2,
                firstAngle.angleRadians, secondAngle.angleRadians,
                FangSuRailMath.shapeMode(holder.fangsu$getShape()),
                holder.fangsu$getVerticalRadius(),
                0.0D, 0.0D,
                RailRollProfile.NONE, current.halfGauge
        );
        // 注意必须带上 firstIsPosition1：内核是按 railMath 参数顺序建的，接缝位置也在参数空间里
        return FangSuRailMath.middleBreakpointFraction(core, firstIsPosition1);
    }

    /** 玩家到轨道任一端点的最小水平距离（格）。 */
    private static double minDistanceToRail(ServerPlayer player, BlockPos p1, BlockPos p2) {
        final double first = horizontalDistance(player, p1);
        final double second = horizontalDistance(player, p2);
        return Math.min(first, second);
    }

    private static double horizontalDistance(ServerPlayer player, BlockPos pos) {
        final double dx = pos.getX() + 0.5D - player.getX();
        final double dz = pos.getZ() + 0.5D - player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 非法请求一律忽略并记 debug 日志（不是 warn：改包/作弊会刷屏，正常玩家看不到）。 */
    private static void logIgnore(String reason, BlockPos p1, BlockPos p2) {
        Main.debug("[RailTilt] 忽略轨道超高编辑请求 {} -> {}：{}", p1, p2, reason);
    }

    /** 客户端：把一条轨道的逐轨道超高写入请求发给服务端。 */
    public static void sendRailTiltC2S(BlockPos p1, BlockPos p2, double startDegrees, double middleDegrees, double endDegrees, double halfGauge) {
        writeAndSend(p1, p2, true, false, startDegrees, middleDegrees, endDegrees, halfGauge);
    }

    /** 客户端：清除一条轨道的逐轨道超高（回到节点派生滚转）。 */
    public static void sendClearRailTiltC2S(BlockPos p1, BlockPos p2, double halfGauge) {
        writeAndSend(p1, p2, false, true, 0.0D, 0.0D, 0.0D, halfGauge);
    }

    private static void writeAndSend(BlockPos p1, BlockPos p2, boolean setTilt, boolean clearTilt, double startDegrees, double middleDegrees, double endDegrees, double halfGauge) {
        final double limit = RailPoseExtra.MAX_RAIL_TILT_DEGREES;
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBlockPos(p1);
        buf.writeBlockPos(p2);
        buf.writeBoolean(setTilt);
        buf.writeBoolean(clearTilt);
        buf.writeDouble(clampDegrees(startDegrees, limit));
        buf.writeDouble(clampDegrees(middleDegrees, limit));
        buf.writeDouble(clampDegrees(endDegrees, limit));
        buf.writeFloat((float) (Double.isFinite(halfGauge)
                ? Math.max(MIN_HALF_GAUGE, Math.min(MAX_HALF_GAUGE, halfGauge))
                : Double.NaN));
        NetworkManager.sendToServer(RAIL_TILT_EDIT, buf);
    }

    /** 客户端侧的夹取：非有限值归零，其余夹到 ±limit。服务端仍会再校验一次。 */
    private static double clampDegrees(double degrees, double limit) {
        if (!Double.isFinite(degrees)) {
            return 0.0D;
        }
        return Math.max(-limit, Math.min(limit, degrees));
    }
}
