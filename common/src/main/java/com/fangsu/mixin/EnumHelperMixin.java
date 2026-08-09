package com.fangsu.mixin;

import com.fangsu.mtr.AngleExtra;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.EnumHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 让 MTR core 的枚举反序列化恢复"幻影角度"实例。
 * <p>
 * 万向节点建出的任意角度轨道，其 {@code angle1/angle2} 是 {@link AngleMixin} 创建的幻影实例
 * （名字形如 {@code "D123.456"} / {@code "R2.153"}，D=度、R=弧度）。core 生成类（RailSchema）
 * 写入时调 {@code angle.toString()} 正常写出，但读取走 {@link EnumHelper#valueOf}，按名字
 * {@code Enum.valueOf} 找不到常量时会静默回退默认值——导致服务端重启/跨端同步后任意角度丢失。
 * <p>
 * 本 mixin 覆写 {@code valueOf}（目标为接口，故本 mixin 也必须是接口）：解析失败且枚举类型是
 * {@link Angle} 时，尝试把 {@code D/R} 前缀名字解析回精确角度并构造幻影实例；其余情况保持原版行为。
 * 所有生成类（RailSchema、UpdateDataRequest 等）的反序列化都收敛到这一个入口，覆盖全部链路。
 */
@Mixin(value = EnumHelper.class, remap = false)
public interface EnumHelperMixin {

    /**
     * @author fangsu
     * @reason 支持幻影 Angle 名字（"D123.456"/"R2.153"）的反序列化恢复
     */
    @Overwrite
    static <T extends Enum<T>> T valueOf(T defaultValue, String name) {
        try {
            return Enum.valueOf(defaultValue.getDeclaringClass(), name);
        } catch (Exception e) {
            // 幻影角度恢复：名字以 D/R 开头，其余部分为数值（D=度数, R=弧度）
            if (defaultValue != null && defaultValue.getDeclaringClass() == Angle.class && name != null) {
                final String trimmed = name.trim();
                if (trimmed.length() > 1) {
                    final char prefix = trimmed.charAt(0);
                    if (prefix == 'D' || prefix == 'R') {
                        try {
                            final double value = Double.parseDouble(trimmed.substring(1));
                            @SuppressWarnings("unchecked")
                            final T result = (T) (prefix == 'D' ? AngleExtra.fromDegrees(value) : AngleExtra.fromRadians(value));
                            return result;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            return defaultValue;
        }
    }
}
