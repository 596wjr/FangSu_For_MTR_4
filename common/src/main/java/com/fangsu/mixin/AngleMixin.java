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
     * MTR core 的倾斜角传播（{@code Rail.getUpdatedRailTiltAngles}）用 {@code ==} 引用比较
     * Angle，缓存保证同一角度比较成立；同时避免反复创建实例。
     */
    /**
     * 若角度恰好等于某个枚举常量（22.5° 倍数），返回该常量而非幻影实例。
     * <p>
     * 保证"万向节点建出的 22.5° 倍数轨道"与"原版节点建出的轨道"使用同一枚举实例，
     * 使 core 中 {@code ==} 引用比较（倾斜角传播）在两种轨道之间依然成立。
     */
    private Angle fangsu$snapToEnumIfExact(float degrees) {
        for (final Angle a : Angle.values()) {
            if (a.angleDegrees == degrees) {
                return a;
            }
        }
        return null;
    }

    @Override
    public Angle fangsu$fromDegrees(double angleDegrees) {
        final float degrees = (float) angleDegrees;
        final Angle exact = fangsu$snapToEnumIfExact(degrees);
        if (exact != null) {
            return exact;
        }
        final Angle cached = AngleExtra.PHANTOM_CACHE.get(degrees);
        if (cached != null) {
            return cached;
        }
        final Angle result = create("D" + Float.toString(degrees), -1, degrees);
        fangsu$setRadiansInternal(result, Math.toRadians(angleDegrees));
        AngleExtra.PHANTOM_CACHE.put(degrees, result);
        return result;
    }

    @Override
    public Angle fangsu$fromRadians(double angleRadians) {
        final float degrees = (float) Math.toDegrees(angleRadians);
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
            cir.setReturnValue(fangsu$fromDegrees(angleDegrees + 180));
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
            cir.setReturnValue(fangsu$fromDegrees(angleDegrees - angle.angleDegrees));
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
