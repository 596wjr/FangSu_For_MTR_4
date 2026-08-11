package com.fangsu.mixin;

import com.fangsu.mtr.AngleExtra;
import org.mtr.core.tool.Angle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 为 {@link Angle} 提供"任意精度"实例支持。
 * <p>
 * MTR 的 {@code Angle} 是 16 向 22.5° 枚举；而万向节点的最大半径圆弧需要任意角度。
 * 本 mixin 通过 {@link #fangsu$fromDegrees} 调用 {@code @Invoker("<init>")} 在运行时创建
 * 非枚举实例（{@code ordinal == -1}，即"幻影实例"），并用 {@link #fangsu$setRadians} 填充
 * 预计算三角函数（含 {@code halfTan}）。
 * <p>
 * MTR 的 {@code RailMath} 在构造时会调用 {@code getOpposite() / isParallel() / add() / sub()} 等
 * 方法，这些方法在枚举内部用 {@code switch} 实现——对幻影实例（不在枚举值表中）会崩溃。
 * 因此此处对这些方法做了 {@code @Inject}：当实例是幻影（{@code ordinal() < 0}）时改用算术计算，
 * 否则放行原版实现。
 */
@Mixin(value = Angle.class, remap = false)
public abstract class AngleMixin implements AngleExtra {

    @Shadow(remap = false) @Final
    public float angleDegrees;
    @Shadow(remap = false) @Final @Mutable
    public double angleRadians, sin, cos, tan, halfTan;

    @Invoker(value = "<init>")
    private static Angle create(String name, int ordinal, float angleDegrees) {
        throw new IllegalStateException();
    }

    /** 判断当前实例是否为 mixin 创建的幻影实例（不在枚举值表中）。 */
    private boolean fangsu$isPhantom() {
        final Object self = this;
        for (final Angle a : Angle.values()) {
            if (a == self) {
                return false;
            }
        }
        return true;
    }

    /**
     * 幻影实例缓存：相同角度（float 精度）复用同一实例（缓存本体在 {@link AngleExtra}）。
     * <p>
     * MTR core 的寻路（{@code SidingPathFinder} 中 {@code node.angle == rail.getStartAngle(...)}）
     * 与倾斜角传播（{@code Rail.getUpdatedRailTiltAngles}）都用 {@code ==} 引用比较 Angle，
     * 缓存保证同一角度（同一 float32 键）复用同一实例，比较才能成立；同时避免反复创建实例。
     */
    /** 将度数归一化到 [0,360)（double 精度运算，最后一次性 float 化）。 */
    private static float fangsu$normalizeDegreesTo360(double degrees) {
        double normalized = degrees % 360.0;
        if (normalized < 0) {
            normalized += 360.0;
        }
        return (float) normalized;
    }

    /**
     * 若角度恰好等于某个枚举常量（22.5° 倍数），返回该常量而非幻影实例。
     * <p>
     * 保证"万向节点建出的 22.5° 倍数轨道"与"原版节点建出的轨道"使用同一枚举实例，
     * 使 core 中 {@code ==} 引用比较（寻路 {@code node.angle == rail.getStartAngle(...)}、
     * 倾斜角传播）在两种轨道之间依然成立。
     * <p>
     * 注意：枚举常量字段是构造器 {@code normalizeAngle} 后的 [-180,180) 值（如
     * NEE=337.5° 字段为 -22.5、NWW=202.5° 字段为 -157.5），而本方法输入统一为
     * [0,360)（见 {@link #fangsu$normalizeDegreesTo360}），因此比较前需把负字段
     * 映射回 [0,360) —— 否则 337.5° 会匹配不到 NEE 而错误地创建幻影实例，
     * 导致同一方向出现"枚举/幻影"两个不同实例，寻路 {@code ==} 引用比较断裂。
     */
    private Angle fangsu$snapToEnumIfExact(float degrees) {
        for (final Angle a : Angle.values()) {
            float field = a.angleDegrees;
            if (field < 0) {
                field += 360f;
            }
            if (field == degrees) {
                return a;
            }
        }
        return null;
    }

    /**
     * 创建/复用幻影实例。缓存键 = 输入归一化到 [0,360) 后的 float32 值。
     * <p>
     * 归一化在 double 域完成后再一次性 float 化，避免 float32 加减链（构造器
     * normalizeAngle 那种 while 循环）引入 1 ulp 偏差 —— 否则同一方向经
     * "fromDegrees(123.4)" 与 "getOpposite 传来的 fromDegrees(303.4+180)" 两条
     * 路径会得到不同 float32 键、不同实例，导致寻路 {@code ==} 引用比较断裂。
     */
    @Override
    public Angle fangsu$fromDegrees(double angleDegrees) {
        final float degrees = fangsu$normalizeDegreesTo360(angleDegrees);
        final Angle exact = fangsu$snapToEnumIfExact(degrees);
        if (exact != null) {
            return exact;
        }
        final Angle cached = AngleExtra.PHANTOM_CACHE.get(degrees);
        if (cached != null) {
            return cached;
        }
        final Angle result = create("D" + Float.toString(degrees), -1, degrees);
        // 弧度字段保留原始输入（double 精度），供 getOpposite/add/sub 恢复精确度数
        fangsu$setRadiansInternal(result, Math.toRadians(angleDegrees));
        AngleExtra.PHANTOM_CACHE.put(degrees, result);
        return result;
    }

    @Override
    public Angle fangsu$fromRadians(double angleRadians) {
        final float degrees = fangsu$normalizeDegreesTo360(Math.toDegrees(angleRadians));
        final Angle exact = fangsu$snapToEnumIfExact(degrees);
        if (exact != null) {
            return exact;
        }
        final Angle cached = AngleExtra.PHANTOM_CACHE.get(degrees);
        if (cached != null) {
            return cached;
        }
        final Angle result = create("R" + Float.toString(degrees), -1, degrees);
        fangsu$setRadiansInternal(result, angleRadians);
        AngleExtra.PHANTOM_CACHE.put(degrees, result);
        return result;
    }

    @Override
    public void fangsu$setRadians(double angleRadians) {
        this.angleRadians = angleRadians;
        this.sin = Math.sin(angleRadians);
        this.cos = Math.cos(angleRadians);
        this.tan = Math.tan(angleRadians);
        this.halfTan = Math.tan(angleRadians / 2);
    }

    private static void fangsu$setRadiansInternal(Angle angle, double angleRadians) {
        ((AngleMixin) (Object) angle).fangsu$setRadians(angleRadians);
    }

    private float fangsu$normalize(float v) {
        float n = v % 360;
        if (n < 0) n += 360;
        return n;
    }

    @Inject(method = "getOpposite", at = @At("HEAD"), remap = false, cancellable = true)
    private void fangsu$getOpposite(CallbackInfoReturnable<Angle> cir) {
        if (fangsu$isPhantom()) {
            // 用 angleRadians（double，建轨时保留的原始输入）恢复精确度数再 +180，
            // 避免 float32 字段（[-180,180) 归一化值）的 1 ulp 偏差导致与
            // 建轨路径 fromDegrees(归一化 double) 产生不同实例，寻路 == 比较断裂
            cir.setReturnValue(fangsu$fromDegrees(Math.toDegrees(angleRadians) + 180));
        }
    }

    @Inject(method = "isParallel", at = @At("HEAD"), remap = false, cancellable = true)
    private void fangsu$isParallel(Angle angle, CallbackInfoReturnable<Boolean> cir) {
        if (fangsu$isPhantom() || angle.ordinal() < 0) {
            final float diff = Math.abs(fangsu$normalize(angleDegrees - angle.angleDegrees));
            cir.setReturnValue(diff < 0.001f || Math.abs(diff - 180f) < 0.001f);
        }
    }

    @Inject(method = "add", at = @At("HEAD"), remap = false, cancellable = true)
    private void fangsu$add(Angle angle, CallbackInfoReturnable<Angle> cir) {
        if (fangsu$isPhantom() || angle.ordinal() < 0) {
            cir.setReturnValue(fangsu$fromDegrees(angleDegrees + angle.angleDegrees));
        }
    }

    @Inject(method = "sub", at = @At("HEAD"), remap = false, cancellable = true)
    private void fangsu$sub(Angle angle, CallbackInfoReturnable<Angle> cir) {
        if (fangsu$isPhantom() || angle.ordinal() < 0) {
            // 差值取 [0,360) 作为 angleDegrees 字段（与 fromDegrees 的实例字段语义一致），
            // 但 radians 必须归一化到 [-180,180)：RailMath 用 angleDifference 的符号判断
            // 几何分支（signum(angleForward) == signum(angleDifference)），未归一化的差值
            // （如 216.9° 应为 -143.1°）会导致误判退化分支，轨道静默无法创建。
            // 不走 PHANTOM_CACHE：同键实例可能由 fromDegrees(315°) 创建（radians=+315°），
            // 与 sub 所需的 -45° radians 语义冲突。
            final float diff360 = fangsu$normalizeDegreesTo360(angleDegrees - angle.angleDegrees);
            final Angle exact = fangsu$snapToEnumIfExact(diff360);
            if (exact != null) {
                cir.setReturnValue(exact);
                return;
            }
            final float diff180 = diff360 > 180 ? diff360 - 360 : diff360;
            final Angle result = create("S" + Float.toString(diff360), -1, diff360);
            fangsu$setRadiansInternal(result, Math.toRadians(diff180));
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "getClosest45", at = @At("HEAD"), remap = false, cancellable = true)
    private void fangsu$getClosest45(CallbackInfoReturnable<Angle> cir) {
        if (fangsu$isPhantom()) {
            double snapped = Math.round(angleDegrees / 45.0) * 45.0;
            cir.setReturnValue(fangsu$fromDegrees(normalize(snapped)));
        }
    }

    private static double normalize(double v) {
        v = v % 360.0;
        if (v < 0) v += 360.0;
        return v;
    }
}
