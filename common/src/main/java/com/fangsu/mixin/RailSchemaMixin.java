package com.fangsu.mixin;

import com.fangsu.mappings.rail.RailPoseExtra;
import com.fangsu.mtr.rail.RailPoseExtraHolder;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.generated.data.RailSchema;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.tool.Angle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 FangSu 的「轨道附加姿态」写进 MTR 的轨道序列化结构（MTR 4.0.5）。
 * <p>
 * 关键事实（已用 javap 对真实 4.0.5 jar 逐条核对，见
 * {@code .tmp_probe/MTR405_API_RECON_REPORT.md} 第 4 节）：
 * <ul>
 *   <li>{@code org.mtr.core.data.Rail} <b>不重写</b> {@code updateData} / {@code serializeData}，
 *       所以注入 {@code org.mtr.core.generated.data.RailSchema} 就覆盖了 {@code Rail}；</li>
 *   <li>{@code position1/angle1/position2/angle2/shape/verticalRadius/...} 这些主字段是在
 *       <b>{@code RailSchema(ReaderBase)} 构造器里</b>用 {@code getChild/getString/getDouble/...}
 *       读出来的（字节码偏移 26..255），而 {@code updateData(ReaderBase)} <b>只</b>读
 *       {@code styles} / {@code signalColors} / {@code stylesMigratedLegacy}。</li>
 * </ul>
 * 因此附加键<b>必须</b>在这个构造器的 TAIL 读：这一处同时覆盖磁盘加载
 * （{@code new Rail(JsonReader(...))}）、{@code PacketUpdateData} 的 JSON 载荷
 * （服务端对 {@code UpdateDataRequest.addRail} 的反序列化）以及 {@code ReaderBase.merge}。
 * 若只注入 {@code updateData}，磁盘上的姿态会在每个加载路径上被丢掉。
 * <p>
 * <b>反面：不要同时注入 {@code updateData}</b>。{@code updateData} 是"局部更新"入口
 * （4.0.5 里只读 styles / signalColors / stylesMigratedLegacy），它收到的 Reader 往往<b>不含</b>
 * 附加键；在那里读会得到默认值并把已有的姿态清零。
 * <p>
 * 写侧只写单键 {@link RailPoseExtra#KEY}，并且只在<b>非默认</b>时写：
 * 姿态为默认（全零）的轨道序列化结果与原版逐字节相同，老存档不会被"污染"。
 */
@Mixin(value = RailSchema.class, remap = false)
public abstract class RailSchemaMixin implements RailPoseExtraHolder {

    /**
     * 端点几何信息（{@code position1/position2/angle1/angle2/shape/verticalRadius}）。
     * <p>
     * 这些字段声明在<b>本 mixin 的目标类 {@code RailSchema}</b> 上（已用 javap 对真实 4.0.5 jar 核实），
     * 而<b>不在子类 {@code Rail}</code> 上。因此影子必须打在这里：
     * 之前把影子放在 {@code RailMixin}（目标 {@code Rail}）时，Mixin 注解处理器会报
     * <pre>Cannot find target for @Shadow field in org.mtr.core.data.Rail</pre>
     * 运行期这些影子字段同样有解析不到的风险（{@code RailMixin} 需要它们来重建 {@code railMath}，
     * 故通过 {@link RailPoseExtraHolder} 的访问器跨 mixin 取值）。
     * <p>
     * 只读，不需要 {@code @Mutable}：{@code @Final} 表示"不要动目标的 ACC_FINAL"。
     */
    @Shadow(remap = false)
    @Final
    protected Position position1;

    @Shadow(remap = false)
    @Final
    protected Position position2;

    @Shadow(remap = false)
    @Final
    protected Angle angle1;

    @Shadow(remap = false)
    @Final
    protected Angle angle2;

    @Shadow(remap = false)
    @Final
    protected Rail.Shape shape;

    @Shadow(remap = false)
    @Final
    protected double verticalRadius;

    /**
     * 附加姿态。故意<b>不给初始化器</b>：Mixin 对 {@code @Unique} 字段的初始化器支持与
     * 目标类构造器形态有关，而本类有两个构造器（设备侧全参构造 + {@code ReaderBase} 构造），
     * 用 null 表示"未设置"最稳妥（见 {@link #getFangSuPose()}）。
     */
    @Unique
    private RailPoseExtra fangsu$pose;

    /**
     * 读侧：必须挂在 {@code RailSchema(ReaderBase)} 构造器的 TAIL。
     * 该构造器是 {@code protected}，Mixin 注入不受访问修饰符限制。
     */
    @Inject(method = "<init>(Lorg/mtr/core/serializer/ReaderBase;)V", at = @At("TAIL"), remap = false)
    private void fangsu$readPose(ReaderBase readerBase, CallbackInfo ci) {
        this.fangsu$pose = RailPoseExtra.decode(readerBase.getString(RailPoseExtra.KEY, ""));
    }

    /** 写侧：只在非默认时写单个字符串键，保证默认轨道的存档与原版完全一致。 */
    @Inject(method = "serializeData", at = @At("TAIL"), remap = false)
    private void fangsu$writePose(WriterBase writerBase, CallbackInfo ci) {
        final RailPoseExtra pose = getFangSuPose();
        if (!pose.isDefault()) {
            writerBase.writeString(RailPoseExtra.KEY, pose.encode());
        }
    }

    @Override
    public RailPoseExtra getFangSuPose() {
        final RailPoseExtra pose = this.fangsu$pose;
        return pose == null ? RailPoseExtra.DEFAULT : pose;
    }

    @Override
    public void setFangSuPose(RailPoseExtra pose) {
        this.fangsu$pose = pose == null ? RailPoseExtra.DEFAULT : pose;
    }

    // ---- 端点几何信息：读取上面影子的字段，供 RailMixin 重建 railMath 使用 ----

    @Override
    public Position fangsu$getPosition1() {
        return position1;
    }

    @Override
    public Position fangsu$getPosition2() {
        return position2;
    }

    @Override
    public Angle fangsu$getAngle1() {
        return angle1;
    }

    @Override
    public Angle fangsu$getAngle2() {
        return angle2;
    }

    @Override
    public Rail.Shape fangsu$getShape() {
        return shape;
    }

    @Override
    public double fangsu$getVerticalRadius() {
        return verticalRadius;
    }
}
