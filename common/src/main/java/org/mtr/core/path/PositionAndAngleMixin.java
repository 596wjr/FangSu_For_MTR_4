package org.mtr.core.path;

import com.fangsu.mtr.PathAngleAccessor;
import org.mtr.core.data.Position;
import org.mtr.core.tool.Angle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 为 MTR 4.0.5 的 {@code SidingPathFinder.PositionAndAngle} 追加字段访问器与构造工厂。
 * <p>
 * 该类的 {@code position / angle} 字段是 {@code private}、无公共访问器、构造器也是
 * {@code private}（仅在 {@code SidingPathFinder} 内部经合成桥接访问）。
 * {@link com.fangsu.mixin.SidingPathFinderMixin} 重写寻路时需要读写这些字段并新建节点，
 * 因此本 mixin：
 * <ul>
 *   <li>实现 {@link PathAngleAccessor}（追加公共访问器，目标类无同名方法 → mixin 追加）；</li>
 *   <li>用 {@code @Invoker("<init>")} 生成构造工厂（Mixin 支持对 private 构造器生成访问器）。</li>
 * </ul>
 */
@Mixin(value = SidingPathFinder.PositionAndAngle.class, remap = false)
public abstract class PositionAndAngleMixin implements PathAngleAccessor {

    @Shadow(remap = false)
    private Position position;

    @Shadow(remap = false)
    private Angle angle;

    @Override
    public Position fangsu$position() {
        return this.position;
    }

    @Override
    public Angle fangsu$angle() {
        return this.angle;
    }

    /** 构造工厂（Mixin 生成调用目标私有构造器的访问器）。 */
    @Invoker("<init>")
    static SidingPathFinder.PositionAndAngle fangsu$create(Position position, Angle angle) {
        throw new AssertionError();
    }
}
