package com.fangsu.mixin;

import com.fangsu.render.MtrTranslucentPass;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.mtr.mapping.render.batch.BatchManager;
import org.mtr.mapping.render.batch.MaterialProperties;
import org.mtr.mapping.render.shader.ShaderManager;
import org.mtr.mapping.render.tool.GlStateTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mixin(value = BatchManager.class, remap = false)
public abstract class MtrOptimizedTranslucencyMixin {
    @Unique
    private static boolean fangsu$drawingDeferred;

    @WrapOperation(method = "drawAll", at = @At(value = "INVOKE", ordinal = 2,
            target = "Lorg/mtr/mapping/render/batch/BatchManager;drawBatch(Ljava/util/Map;Lorg/mtr/mapping/render/shader/ShaderManager;)V"))
    private void fangsu$deferTranslucentBatch(Map<MaterialProperties, List<Object>> batches,
                                             ShaderManager shaders, Operation<Void> original) {
        if (!MtrTranslucentPass.isCollectingOptimized()) {
            original.call(batches, shaders);
        } else if (!batches.isEmpty()) {
            // 转移当帧批次，保留已上传的 VAO 和每次调用的模型矩阵。
            var deferred = new LinkedHashMap<MaterialProperties, List<Object>>();
            Comparator<Object> farFirst = Comparator.comparingDouble(
                    (Object call) -> fangsu$distanceSquared(call)).reversed();
            for (var calls : batches.values()) calls.sort(farFirst);
            batches.entrySet().stream()
                    .sorted(Comparator.comparingDouble((Map.Entry<MaterialProperties, List<Object>> entry) ->
                            entry.getValue().isEmpty() ? 0 : fangsu$distanceSquared(entry.getValue().get(0))).reversed())
                    .forEach(entry -> deferred.put(entry.getKey(), entry.getValue()));
            batches.clear();
            MtrTranslucentPass.defer(() -> {
                boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
                boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
                boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
                int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
                int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
                int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
                int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
                int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
                int texture = RenderSystem.getShaderTexture(0);
                int lightmap = RenderSystem.getShaderTexture(2);
                int overlay = RenderSystem.getShaderTexture(1);
                boolean ownsProtection = !LcdGlStateTrackerAccessor.fangsu$isStateProtected();
                if (ownsProtection) GlStateTracker.capture();
                fangsu$drawingDeferred = true;
                try {
                    original.call(deferred, shaders);
                } finally {
                    fangsu$drawingDeferred = false;
                    if (ownsProtection) GlStateTracker.restore();
                    if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
                    if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
                    if (depthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
                    RenderSystem.depthFunc(depthFunc);
                    RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
                    RenderSystem.setShaderTexture(0, texture);
                    RenderSystem.setShaderTexture(1, overlay);
                    RenderSystem.setShaderTexture(2, lightmap);
                }
            });
        }
    }

    @Unique
    private static double fangsu$distanceSquared(Object call) {
        var transform = ((MtrOptimizedRenderCallAccessor) call).fangsu$getVertexAttributeState().matrix4f;
        if (transform == null) return 0;
        //#if MC_VERSION >= 11903
        var matrix = transform.data;
        double x = matrix.m30(), y = matrix.m31(), z = matrix.m32();
        //#else
        //$$ var center = new com.mojang.math.Vector4f(0, 0, 0, 1);
        //$$ center.transform(transform.data);
        //$$ double x = center.x(), y = center.y(), z = center.z();
        //#endif
        return x * x + y * y + z * z;
    }

    @WrapOperation(method = "lambda$drawBatch$2", at = @At(value = "INVOKE",
            target = "Lorg/mtr/mapping/render/shader/ShaderManager;setupShaderBatchState(Lorg/mtr/mapping/render/batch/MaterialProperties;)V"))
    private static void fangsu$disableDepthWrites(ShaderManager shaders, MaterialProperties material, Operation<Void> original) {
        original.call(shaders, material);
        if (fangsu$drawingDeferred) RenderSystem.depthMask(false);
    }
}
