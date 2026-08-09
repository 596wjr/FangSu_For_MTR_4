package com.fangsu.mtr;

import org.mtr.core.tool.Angle;

import java.util.concurrent.ConcurrentHashMap;

public interface AngleExtra {

    /**
     * 幻影角度实例缓存（键 = float 精度角度值）。
     * <p>
     * {@link AngleMixin} 的 {@code fangsu$fromDegrees / fangsu$fromRadians} 对相同角度复用同一实例，
     * 使 MTR core 中基于 {@code ==} 引用比较的角度判断（如倾斜角传播）对幻影实例依然成立。
     * 放本接口（而非 mixin 类）是为了不依赖 mixin 静态字段的合并行为。
     */
    ConcurrentHashMap<Float, Angle> PHANTOM_CACHE = new ConcurrentHashMap<>();

    Angle fangsu$fromDegrees(double degrees);

    Angle fangsu$fromRadians(double radians);

    void fangsu$setRadians(double radians);

    static Angle fromDegrees(double degrees) {
        Angle result = ((AngleExtra) (Object) Angle.S).fangsu$fromDegrees(degrees);
        return result;
    }

    static Angle fromRadians(double radians) {
        Angle result = ((AngleExtra) (Object) Angle.S).fangsu$fromRadians(radians);
        return result;
    }
}
