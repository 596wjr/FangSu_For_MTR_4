package com.fangsu.mixin;

import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复：列车渲染沿 22.5° 快照几何行驶的问题（万向节点精确轨道不生效）。
 * <p>
 * 根因（MTR 4.0.5 实际字节码）：
 * <ul>
 *   <li>渲染时 {@code Vehicle.getPosition} → {@code PathData.getPosition(double)}
 *       直接消费 {@code rail} 字段，<b>不重查</b>数据；</li>
 *   <li>{@code rail} 字段在客户端仅由收包时的预填调用填充：
 *       {@code PacketUpdateVehiclesLifts.runClientInbound} 与
 *       {@code PacketUpdateData.update} 均以 {@code new MinecraftClientData()}
 *       （空数据实例）调用 {@code PathData.writePathCache}；</li>
 *   <li>空数据必然 tryGet miss → {@code rail = defaultRail()}（22.5° 快照轨，
 *       内部走 {@code Rail.getAngles} → {@code Angle.fromAngle}）。</li>
 * </ul>
 * 原版轨道角度本就是 22.5° 倍数，快照无损；万向节点轨道为任意精度角度，
 * 快照后几何偏离 → 列车持续沿「快照到 22.5 后方向」的轨道行驶。
 * <p>
 * 修复：{@code writePathCache} 收到空数据实例（{@code positionsToRail} 为空，
 * 即客户端收包预填调用）时，改用客户端真实数据（{@code MinecraftClientData.getInstance()}，
 * 含全部轨道）重查，命中后 {@code rail} 即为精确轨道。
 * 服务端调用（{@code Siding.writePathCache}，传入 Simulator 数据非空）不进入该分支，
 * 且服务端进程无客户端类时返回 null 兜底，不受影响。
 */
@Mixin(value = PathData.class, remap = false)
public abstract class PathDataMixin {

    @Shadow(remap = false)
    private Rail rail;

    @Shadow(remap = false)
    public abstract Position getOrderedPosition1();

    @Shadow(remap = false)
    public abstract Position getOrderedPosition2();

    @Inject(method = "writePathCache(Lorg/mtr/core/data/Data;)V", at = @At("HEAD"), remap = false, cancellable = true)
    private void fangsu$writePathCache(Data data, CallbackInfo ci) {
        if (data.positionsToRail.isEmpty()) {
            // 空数据实例（客户端收包预填调用）→ 用客户端真实数据重查
            final Data realData = fangsu$getClientData();
            if (realData != null && realData != data) {
                // positionsToRail 双向写入，ordered1/ordered2 与 start/end 等价
                final Rail realRail = Data.tryGet(realData.positionsToRail, getOrderedPosition1(), getOrderedPosition2());
                if (realRail != null) {
                    rail = realRail;
                    ci.cancel();
                }
            }
        }
    }

    /** 客户端进程返回全局数据单例；服务端进程无该类或类加载失败时返回 null。 */
    private static Data fangsu$getClientData() {
        try {
            return org.mtr.mod.client.MinecraftClientData.getInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
