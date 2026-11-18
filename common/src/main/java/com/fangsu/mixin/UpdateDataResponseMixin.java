package com.fangsu.mixin;

import com.fangsu.mtr.StationOpening;
import com.fangsu.network.HiddenRoutesClient;
import com.fangsu.utils.PathGenerationStatusManager;
import net.minecraft.client.Minecraft;
import org.mtr.core.data.Data;
import org.mtr.core.data.Route;
import org.mtr.core.data.Station;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.client.MinecraftClientData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;

/**
 * 路径生成完成回执：服务端生成完成后通过 {@code PacketUpdateData} 把更新的车厂
 * 对象回传，客户端 {@code UpdateDataResponse.write()} 把 depot 合并进
 * {@code depotIdMap}（remove+add 替换为新对象），此时
 * {@code getLastGeneratedMillis()/getLastGeneratedStatus()} 即为本次生成结果——
 * 在 {@code write()} 尾部统一检查，覆盖 SUCCESSFUL / NO_SIDINGS /
 * TWO_PLATFORMS_REQUIRED / PATH_NOT_FOUND 全部完成路径（TSC 每次生成都会更新
 * lastGeneratedMillis，无「生成中但没回包」的死角）。
 * <p>
 * {@code write()} 仅客户端执行（服务端回包走 {@code Utilities.getJsonObjectFromData}
 * 序列化，不经过本方法），且 {@code PacketUpdateData.update()} 里
 * {@code minecraftClientData} 与 {@code dashboardInstance} 各写一次——检查幂等，
 * 完成后即移除记录。客户端包处理在 netty 线程，聊天通知由管理器内部
 * {@code Minecraft.getInstance().execute(...)} 切回主线程。
 */
@Mixin(value = UpdateDataResponse.class, remap = false)
public abstract class UpdateDataResponseMixin {

    @Shadow
    @Final
    private Data data;

    @Shadow abstract ObjectArrayList<Station> getStations();
    @Shadow abstract ObjectArrayList<Route> getRoutes();

    @Inject(method = "write", at = @At("HEAD"))
    private void fangsu$refreshSupplementalRoutes(CallbackInfo ci) {
        if (data != MinecraftClientData.getInstance()) return;
        final var changedStations = new HashSet<Long>();
        for (final var station : getStations()) {
            final var previous = data.stationIdMap.get(station.getId());
            if (previous == null || StationOpening.isDeferred(previous) != StationOpening.isDeferred(station)) {
                changedStations.add(station.getId());
            }
        }
        final var changedRoutes = new HashSet<Long>();
        for (final var route : getRoutes()) changedRoutes.add(route.getId());
        if (!changedStations.isEmpty() || !changedRoutes.isEmpty()) {
            Minecraft.getInstance().execute(() -> HiddenRoutesClient.refreshRoutes(changedRoutes, changedStations));
        }
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void fangsu$checkGenerationFinished(CallbackInfo ci) {
        com.fangsu.mtr.DrawableRoute.clearCache();
        PathGenerationStatusManager.checkGenerationFinished(data);
    }
}
