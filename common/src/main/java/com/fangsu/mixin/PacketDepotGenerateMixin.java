package com.fangsu.mixin;

import com.fangsu.utils.PathGenerationStatusManager;
import org.mtr.core.operation.DepotOperationByIds;
import org.mtr.mod.packet.PacketDepotGenerate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在客户端发送「生成车厂主路径」包时登记开始时间——车厂编辑界面「刷新路径」按钮与
 * 「无限循环」复选框两个入口都经 {@code new PacketDepotGenerate(depotOperationByIds)}
 * 构造（原版复选框不写自己的 {@code DEPOT_GENERATION_START_TIME}，无生成中提示，
 * 此处一并修复）。注入只命中单参数构造（{@code String}/{@code PacketBufferReceiver}
 * 构造不受影响）。构造发生在客户端主线程，登记仅为写 ConcurrentHashMap，线程安全。
 * <p>
 * 注意：这里是 C2S 触发记录（只有触发刷新的玩家会记录并收到完成通知）。
 */
@Mixin(value = PacketDepotGenerate.class, remap = false)
public abstract class PacketDepotGenerateMixin {

    // 构造函数 HEAD 位于 super() 之前（this 未初始化），handler 必须是 static，
    // 只访问参数 contentObject，不触碰实例状态
    @Inject(method = "<init>(Lorg/mtr/core/operation/DepotOperationByIds;)V", at = @At("HEAD"))
    private static void fangsu$onGenerationStarted(DepotOperationByIds contentObject, CallbackInfo ci) {
        // contentObject 为 final class（DepotOperationByIds），javac 拒绝直接 cast 到
        // 编译期不可见的接口，需先经 Object；运行时接口由父类 schema 注入，沿继承链分派
        ((DepotOperationByIdsSchemaAccessorMixin) (Object) contentObject).getDepotIds()
                .forEach(PathGenerationStatusManager::onGenerationStarted);
    }
}
