package com.fangsu.mixin;

import com.fangsu.utils.PathGenerationStatusManager;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.mtr.core.data.Depot;
import org.mtr.mod.screen.EditDepotScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 车厂编辑界面状态文本：原版生成中用英文「Started path generation %s ago...」且
 * zh_CN 缺 key 显示原始 key；成功/失败文本全为白色（渲染固定 ARGB_WHITE）。
 * 统一改为通知同款格式（多行 {@code |} 分隔、§ 码着色）：
 * <pre>
 * 生成中：[黄]车厂名 刷新线路中（已用时 %s）...
 * 成功：  [黄]车厂名 [绿]线路刷新成功！
 * 失败：  [黄]车厂名 [红]线路刷新失败：| [红]原因 | [红]> 在 [黄]A [红]与 [黄]B [红] 之间找不到路径
 * </pre>
 * <p>
 * 注：返回串在 {@code render()} 里按 {@code "\\|"} 分割逐行绘制、颜色参数固定
 * ARGB_WHITE，故多行用 {@code |} 分隔、颜色用 § 码内嵌（font 渲染会解析）；
 * 文案中不能含 {@code |}。状态未知（从未生成）时由
 * {@link PathGenerationStatusManager#getDashboardText} 返回 original 保留原版。
 */
@Mixin(value = EditDepotScreen.class, remap = false)
public abstract class EditDepotScreenMixin {

    @ModifyReturnValue(method = "getSuccessfulSegmentsText", at = @At("RETURN"))
    private static String fangsu$dashboardText(String original, Depot depot) {
        return PathGenerationStatusManager.getDashboardText(depot, original);
    }
}
