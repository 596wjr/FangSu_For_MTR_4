package com.fangsu.mtr;

import org.mtr.core.data.Position;
import org.mtr.core.tool.Angle;

/**
 * MTR 4.0.5 {@code SidingPathFinder.PositionAndAngle} 的访问器接口。
 * <p>
 * 该类的 {@code position / angle} 字段为 {@code private} 且没有公共访问器，
 * 由 {@link com.fangsu.mixin.PositionAndAngleMixin} 追加
 * {@code fangsu$position / fangsu$angle} 方法实现本接口。
 * 调用方（{@link com.fangsu.mixin.SidingPathFinderMixin}）经双重转型访问。
 */
public interface PathAngleAccessor {

    Position fangsu$position();

    Angle fangsu$angle();
}
