package com.fangsu.mixin;

import com.fangsu.render.RailRollRenderHelper;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.render.PositionAndRotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 乘车玩家「站进车厢内部」时随车体滚转（P4c）。
 * <p>
 * <b>问题</b>：车厢内部的可行走区域在 MTR 4.0.5 里是一组<b>轴对齐 AABB</b>，不是可以整体旋转的刚体。
 * {@code VehicleResourceCache.floors} / {@code doorways} 是
 * {@code ObjectImmutableList<org.mtr.mapping.holder.Box>}（{@code Box} 就是
 * {@code HolderBase<net.minecraft.world.phys.AABB>} 的包装），而 {@code MTR} 自己的
 * {@code org.mtr.mod.render.PositionAndRotation} <b>只有 position / yaw / pitch 三个字段，没有 roll</b>
 * （{@code javap -p} 已核实）。因此 MTR 侧根本不存在「把车厢内部区域按 roll 旋转」的表示能力：
 * 无论怎么改 AABB，{@code movePlayer} 最终只会执行
 * {@code W(v) = pos + Ry(yaw) * Rx(-pitch) * v}，车体滚转完全不在这个式子里。
 * <p>
 * <b>本修复</b>：不试图旋转 AABB，而是把「车厢局部偏移 → 世界坐标」这一步的输出补上滚转，
 * 即在 {@code transformForwards} 之前把局部偏移绕<b>车厢局部 +Z（车头前进方向）</b>旋转现有车体滚转角。
 * 数学上与「车厢内部地板随车体一起滚转」严格等价，且局部偏移本身（{@code ridingVehicleX/Y/Z}，
 * 同时也是发给服务端的量）<b>保持不变</b>，所以 AABB 判定、车门/风挡换乘与同步逻辑逐字不变。
 * <p>
 * <b>为什么正好是同一个角度、同一个符号</b>：车体的滚转是
 * {@code RenderVehicles.getStoredMatrixTransformations} 里最内层（模型局部系）的
 * {@code rotateZDegrees(rollDegrees)}，其物理效果是「绕车体世界前向轴、右手方向旋转 rollDegrees 度」。
 * 模型局部系与 {@code transformForwards} 使用的车厢系相差
 * {@code Ry(π)·Rx(π) = diag(-1,-1,1) = Rz(π)}（绕 Z 转 π，与绕 Z 的旋转可交换），
 * 于是同一个物理旋转在车厢系里仍是 {@code Rz(+rollDegrees)}。
 * 数值核对（{@code build/tmp/interior_probe/RollWalkCheck.java}，覆盖 yaw ∈ {0°,20°,-69°,155°} ×
 * pitch ∈ {0°,±3.4°,±5.2°} × roll ∈ {0°,±5°,±12°,20°}）：
 * 用 {@code Rz(+roll)} 时玩家落点与滚转后车体上同一物理点的位置差 ≤ 7.0e-16 m（机器精度），
 * 用 {@code Rz(-roll)} 时最大差 1.094 m；同时渲染滚转在车厢系里的旋转轴恒为 {@code (0,0,1)}（偏差 2.2e-16）。
 * <p>
 * <b>为什么不能直接调 {@code Vector3d.rotateZ}</b>：MC 的 {@code Vec3d.zRot(t)} 是
 * {@code (x·cos t + y·sin t, y·cos t − x·sin t, z)}，即右手 {@code Rz(t)} 的<b>转置</b>
 * （等于 {@code Rz(-t)}）。这里必须用显式的右手 {@code Rz(+roll)}，故手写三角函数而不是复用 rotateZ。
 * <p>
 * <b>作用面</b>：{@code transformForwards} 在 {@code movePlayer} 的 8 参重载里只出现一次
 * （偏移 1025），全 jar 其余 {@code transformForwards} 调用点（{@code renderConnection} 8 处、
 * {@code RenderVehicleHelper.renderFloorOrDoorway} 4 处）都在别的类/方法里，不受影响。
 * {@code method} 与 {@code @At} 的选择器都写在 {@link RailRollRenderHelper} 常量里；
 * {@code require = 0} 让未来 MTR 改签名时退化为「玩家不随车体倾斜」（即修复前的现状），
 * 并由 {@link RailRollRenderHelper} 的一次性告警暴露。
 * <p>
 * <b>为什么客户端改这个是安全的</b>：{@code VehicleRidingMovement} 位于 {@code org.mtr.mod.client}，
 * 本 mixin 只注册在 {@code fangsu.mixins.json} 的 {@code client} 数组里，服务端不加载；
 * 而发给服务端的 {@code PacketUpdateVehicleRidingEntities} 携带的是
 * {@code ridingVehicleX/Y/Z}（车厢局部量，本修复不改），不是世界坐标。
 * 改的只是客户端由局部量推导出的自身世界位置，与 MTR 原本每帧
 * {@code player.updatePosition(...)} 的机制完全同类，不引入新的同步语义。
 * <p>
 * <b>无滚转时是严格 no-op</b>：{@code getCurrentCarRollDegrees()} 在滚转为 0（普通轨道）或
 * 捕获钩子失效时返回 {@code 0.0}，此时本方法原样转交原有实参、不做任何计算，
 * 与 MTR 原生逐位一致。
 */
@Mixin(value = VehicleRidingMovement.class, remap = false)
public class VehicleRidingMovementMixin {

    /**
     * 把「车厢局部偏移」绕车厢局部 +Z 旋转本车滚转角后再交给 MTR 原有的
     * {@code transformForwards}。
     * <p>
     * 形参即被重定向调用的实参：接收者 {@code positionAndRotation}（本车的世界 PnR，
     * 其 yaw / pitch 与车体渲染用的是同一份）、待变换的 {@code value}
     * （此处必为 {@link Vector3d}，即 {@code (ridingVehicleX, ridingVehicleY, ridingVehicleZ)}）、
     * 以及 MTR 自己的两个 {@code Rotate} 与一个 {@code Translate}。
     * <p>
     * 直接转交（而不是自己实现变换）是关键：旋转函数、角度与平移全部仍由 MTR 提供，
     * 本方法只是在输入侧插入一个绕 Z 的旋转。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = RailRollRenderHelper.RIDING_MOVE_TARGET,
            at = @At(
                    value = "INVOKE",
                    target = RailRollRenderHelper.TRANSFORM_FORWARDS_DESCRIPTOR
            ),
            require = 0,
            remap = false
    )
    private static Object fangsu$rollRidingPlayerPosition(
            PositionAndRotation positionAndRotation,
            Object value,
            PositionAndRotation.Rotate pitchRotate,
            PositionAndRotation.Rotate yawRotate,
            PositionAndRotation.Translate translate
    ) {
        final double rollDegrees = RailRollRenderHelper.getCurrentCarRollDegrees();
        if (value instanceof Vector3d && Double.isFinite(rollDegrees) && rollDegrees != 0.0D) {
            final Vector3d local = (Vector3d) value;
            final double radians = Math.toRadians(rollDegrees);
            final double cos = Math.cos(radians);
            final double sin = Math.sin(radians);
            final double localX = local.getXMapped();
            final double localY = local.getYMapped();
            // 右手 Rz(+roll)：局部 +X 向 +Y 转动。车厢系是右手系（+Z = 车头前进方向），
            // 因此这正是「绕车头前进轴、与车体 rotateZDegrees(rollDegrees) 同一个物理滚转」。
            return positionAndRotation.transformForwards(
                    new Vector3d(
                            localX * cos - localY * sin,
                            localX * sin + localY * cos,
                            local.getZMapped()
                    ),
                    pitchRotate, yawRotate, translate
            );
        }
        // 无滚转 / 非 Vector3d / 非有限角：原样转交，与 MTR 原生逐位一致
        return positionAndRotation.transformForwards(value, pitchRotate, yawRotate, translate);
    }
}
