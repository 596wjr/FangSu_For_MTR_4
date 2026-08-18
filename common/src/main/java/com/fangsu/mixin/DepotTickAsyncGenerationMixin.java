package com.fangsu.mixin;

import com.fangsu.utils.DepotPathGenerationManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BiConsumer;

/**
 * 把 {@link Depot#tick()} 里唯一的 {@code SidingPathFinder.findPathTick} 调用替换为
 * 方速独立工作线程全速寻路（{@link DepotPathGenerationManager}），不占模拟线程 tick。
 * <p>
 * 注入目标描述符以 javap 实测为准：TSC 运行期 fastutil 已搬迁为
 * {@code org.mtr.libraries.it.unimi.dsi.fastutil.*}；{@code tick()} 内该调用唯一
 * （offset 24，单点）。升级 TSC 时需 javap 复核描述符与调用点。
 * <p>
 * handler 为实例方法（this = Depot），不调 {@code original.call(...)} 即完整替换；
 * executor 不可用（服务器停机）时回退原版节流路径，功能不静默消失。
 */
@Mixin(value = Depot.class, remap = false)
public abstract class DepotTickAsyncGenerationMixin {

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lorg/mtr/core/path/SidingPathFinder;findPathTick(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;JLjava/lang/Runnable;Ljava/util/function/BiConsumer;)V")
    )
    private void fangsu$handleFindPathTick(ObjectArrayList<PathData> path, ObjectArrayList<?> sidingPathFinders,
                                           long cruisingAltitude, Runnable callbackSuccess, BiConsumer<?, ?> callbackFail,
                                           Operation<Void> original) {
        if (DepotPathGenerationManager.isAvailable()) {
            DepotPathGenerationManager.handleDepotTick((Depot) (Object) this, path, sidingPathFinders,
                    cruisingAltitude, callbackSuccess, callbackFail);
        } else {
            original.call(path, sidingPathFinders, cruisingAltitude, callbackSuccess, callbackFail);
        }
    }
}
