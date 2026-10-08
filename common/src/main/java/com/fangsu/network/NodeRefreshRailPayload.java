package com.fangsu.network;

import com.fangsu.util.RailTiltCarry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code NODE_REFRESH_RAIL} 载荷的编解码。
 * <p>
 * <b>为什么要有这个类</b>：本包<b>不做版本探测</b>（客户端与服务端永远运行同一份 FangSu 构建），
 * 写侧与读侧的字段顺序必须逐字对应，而 <b>javac 抓不到读写不对称</b>——把写侧放在客户端、
 * 读侧放在服务端时，顺序写错只是运行期读到垃圾值。把写与读放进<b>同一个类、同一段字段顺序</b>，
 * 不对称就不再是一个「需要小心」的事情，而是根本写不出来。
 * <p>
 * <b>载荷布局</b>（写侧 {@link #write} 与读侧 {@link #read} 逐行对应）：
 * <pre>
 *   BlockPos nodePos
 *   double   direction
 *   double   offsetX / offsetY / offsetZ     ← 节点平移随刷新请求同行（跨包竞态根因的修复）
 *   double   pitchDeg / rollDeg              ← 节点俯仰 / 翻滚（进轨道姿态）
 *   boolean  superelevation                  ← 外轨超高开关（只门控滚转贡献）
 *   double   rollOffsetM                     ← 节点半轨距（米）
 *   boolean  directionBonded                 ← 「旋转绑定：否」时只重建几何、不绑定方向
 *   int      count
 *   count × {
 *       BlockPos otherPos
 *       long     speedAtNode / speedAtOther  ← 限速（km/h，按端点位置对号入座）
 *       int      shapeOrdinal                ← Rail.Shape 的序数
 *       byte     flags                       ← bit0 站台 / bit1 侧线 / bit2 折返 / bit3 加速 / bit4 远程连接
 *       int      styleCount
 *       styleCount × String style
 *       boolean  hasRailTilt                 ← 逐轨道超高：该轨道是否被作者授权过（false = 未授权）
 *       double   tiltStartDegrees            ← 以下 5 个字段只在 hasRailTilt 时有意义，
 *       double   tiltMiddleDegrees             但一律按定长写出（读写对称不依赖条件分支）
 *       double   tiltEndDegrees
 *       double   tiltMiddleFraction
 *       double   halfGaugeM
 *   }
 * </pre>
 * 前 11 个字段与「每条轨道的 otherPos…styles」与引入逐轨道超高之前<b>逐字节相同</b>，
 * 新字段一律追加在每条轨道的末尾（见 {@link RailEntry#tilt()}）。
 */
public final class NodeRefreshRailPayload {

    private NodeRefreshRailPayload() {
    }

    /**
     * 一条被刷新轨道的完整随行数据。
     *
     * @param otherPos     另一端位置
     * @param speedAtNode  本节点端的限速（km/h）
     * @param speedAtOther 另一端的限速（km/h）
     * @param shapeOrdinal 轨道形状在 {@code org.mtr.core.data.Rail.Shape} 里的序数
     * @param flags        站台 / 侧线 / 折返 / 加速 / 远程连接 打包位
     * @param styles       轨道样式 id 列表
     * @param tilt         作者授权的逐轨道超高；未授权时为 {@link RailTiltCarry#NONE}
     */
    public record RailEntry(
            BlockPos otherPos,
            long speedAtNode,
            long speedAtOther,
            int shapeOrdinal,
            int flags,
            List<String> styles,
            RailTiltCarry tilt
    ) {
    }

    /**
     * 整个刷新请求。
     *
     * @param nodePos         万向节点位置
     * @param direction       新方向（度）
     * @param offsetX/Y/Z     节点平移（格）
     * @param pitchDeg        节点俯仰角（度）
     * @param rollDeg         节点翻滚角（度）
     * @param superelevation  外轨超高开关（只门控滚转贡献）
     * @param rollOffsetM     半轨距（米）
     * @param directionBonded 是否绑定方向
     * @param rails           相连轨道
     */
    public record Payload(
            BlockPos nodePos,
            double direction,
            double offsetX,
            double offsetY,
            double offsetZ,
            double pitchDeg,
            double rollDeg,
            boolean superelevation,
            double rollOffsetM,
            boolean directionBonded,
            List<RailEntry> rails
    ) {
    }

    /** 写出载荷。字段顺序见类注释，必须与 {@link #read} 逐行对应。 */
    public static void write(FriendlyByteBuf buf, Payload payload) {
        buf.writeBlockPos(payload.nodePos());
        buf.writeDouble(payload.direction());
        buf.writeDouble(payload.offsetX());
        buf.writeDouble(payload.offsetY());
        buf.writeDouble(payload.offsetZ());
        buf.writeDouble(payload.pitchDeg());
        buf.writeDouble(payload.rollDeg());
        buf.writeBoolean(payload.superelevation());
        buf.writeDouble(payload.rollOffsetM());
        buf.writeBoolean(payload.directionBonded());
        buf.writeInt(payload.rails().size());
        for (final RailEntry entry : payload.rails()) {
            buf.writeBlockPos(entry.otherPos());
            buf.writeLong(entry.speedAtNode());
            buf.writeLong(entry.speedAtOther());
            buf.writeInt(entry.shapeOrdinal());
            buf.writeByte(entry.flags());
            buf.writeInt(entry.styles().size());
            for (final String style : entry.styles()) {
                buf.writeUtf(style);
            }
            // ---- 逐轨道超高：追加在每条轨道末尾 ----
            final RailTiltCarry tilt = entry.tilt() == null ? RailTiltCarry.NONE : entry.tilt();
            buf.writeBoolean(tilt.hasRailTilt());
            buf.writeDouble(tilt.startDegrees());
            buf.writeDouble(tilt.middleDegrees());
            buf.writeDouble(tilt.endDegrees());
            buf.writeDouble(tilt.middleFraction());
            buf.writeDouble(tilt.halfGauge());
        }
    }

    /**
     * 读出载荷。必须在主线程排队<b>之前</b>调用（排队回调可能在网络缓冲区释放之后才执行）。
     * 字段顺序与 {@link #write} 逐行对应。
     */
    public static Payload read(FriendlyByteBuf buf) {
        final BlockPos nodePos = buf.readBlockPos();
        final double direction = buf.readDouble();
        final double offsetX = buf.readDouble();
        final double offsetY = buf.readDouble();
        final double offsetZ = buf.readDouble();
        final double pitchDeg = buf.readDouble();
        final double rollDeg = buf.readDouble();
        final boolean superelevation = buf.readBoolean();
        final double rollOffsetM = buf.readDouble();
        final boolean directionBonded = buf.readBoolean();
        final int count = buf.readInt();
        final List<RailEntry> rails = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            final BlockPos otherPos = buf.readBlockPos();
            final long speedAtNode = buf.readLong();
            final long speedAtOther = buf.readLong();
            final int shapeOrdinal = buf.readInt();
            final int flags = buf.readByte();
            final int styleCount = buf.readInt();
            final List<String> styles = new ArrayList<>(Math.max(0, styleCount));
            for (int j = 0; j < styleCount; j++) {
                styles.add(buf.readUtf());
            }
            // ---- 逐轨道超高：与写侧同序 ----
            final boolean hasRailTilt = buf.readBoolean();
            final double tiltStartDegrees = buf.readDouble();
            final double tiltMiddleDegrees = buf.readDouble();
            final double tiltEndDegrees = buf.readDouble();
            final double tiltMiddleFraction = buf.readDouble();
            final double halfGaugeM = buf.readDouble();
            rails.add(new RailEntry(otherPos, speedAtNode, speedAtOther, shapeOrdinal, flags, styles,
                    new RailTiltCarry(hasRailTilt, tiltStartDegrees, tiltMiddleDegrees, tiltEndDegrees,
                            tiltMiddleFraction, halfGaugeM)));
        }
        return new Payload(nodePos, direction, offsetX, offsetY, offsetZ, pitchDeg, rollDeg,
                superelevation, rollOffsetM, directionBonded, rails);
    }
}
