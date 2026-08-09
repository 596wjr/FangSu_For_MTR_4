package com.fangsu.render.sowcer.batch;

import com.fangsu.render.sowcer.model.VertArrays;
import com.fangsu.render.sowcer.object.VertArray;
import com.fangsu.render.sowcer.shader.ShaderManager;
import com.fangsu.render.sowcer.util.DrawContext;

import java.util.*;

public class BatchManager {

    public HashMap<BatchTuple, Queue<RenderCall>> batches = new HashMap<>();

    // 调试字段（LCD 搁置中，见 deliverables/mtr4-vehicle-lcd-notes.md）
    // private static boolean diagPrinted = false;
    // private static boolean poseDiagPrinted = false;

    public void enqueue(VertArrays model, EnqueueProp enqueueProp, ShaderProp shaderProp) {
        for (VertArray vertArray : model.meshList) {
            enqueue(vertArray, enqueueProp, shaderProp);
        }
    }

    public void enqueue(VertArray vertArray, EnqueueProp enqueueProp, ShaderProp shaderProp) {
        Queue<RenderCall> queue = batches.computeIfAbsent(
                new BatchTuple(vertArray.materialProp, shaderProp),
                (key) -> new LinkedList<>()
        );
        queue.add(new RenderCall(vertArray, enqueueProp));
    }

    public void drawAll(ShaderManager shaderManager, DrawContext drawContext) {
        // 调试日志（LCD 搁置中，见 deliverables/mtr4-vehicle-lcd-notes.md）：
        // 一次性打印 drawAll 时各 batch 的内容（shader 名 + 待绘制 RenderCall 数）
        // if (!diagPrinted) {
        //     diagPrinted = true;
        //     final StringBuilder sb = new StringBuilder("[FangSu] BatchManager.drawAll: ");
        //     for (Map.Entry<BatchTuple, Queue<RenderCall>> e : batches.entrySet()) {
        //         sb.append(e.getKey().materialProp.shaderName)
        //                 .append("(translucent=").append(e.getKey().materialProp.translucent)
        //                 .append(",calls=").append(e.getValue().size()).append(") ");
        //     }
        //     com.fangsu.Main.LOGGER.info(sb.toString());
        //
        //     // 关键诊断：drawAll 时刻的投影矩阵 / 模型视图矩阵 / 相机姿态。
        //     // 判定相机旋转是否在投影矩阵里（1.19.4+ 官方改动）——决定 LCD 的 ModelMat 是否要补 R(cam)
        //     final net.minecraft.client.Camera cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        //     final org.joml.Quaternionf camQ = new org.joml.Quaternionf().rotationYXZ(
        //             (float) Math.toRadians(-cam.getYRot()), (float) Math.toRadians(cam.getXRot()), 0);
        //     final org.joml.Matrix4f rotMat = new org.joml.Matrix4f().rotation(camQ);
        //     com.fangsu.Main.LOGGER.info("[FangSu] diag: cam pos=({}, {}, {}) yaw={} pitch={}",
        //             cam.getPosition().x, cam.getPosition().y, cam.getPosition().z, cam.getYRot(), cam.getXRot());
        //     com.fangsu.Main.LOGGER.info("[FangSu] diag proj=\n{}", com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix());
        //     com.fangsu.Main.LOGGER.info("[FangSu] diag mv=\n{}", com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix());
        //     com.fangsu.Main.LOGGER.info("[FangSu] diag rotMat(rotationYXZ(-yaw,pitch,0))=\n{}", rotMat);
        // }
        drawContext.recordBatches(batches.size());

        pushDebugGroup("SOWCER");
        // shaderManager.unbindShader();

        for (Map.Entry<BatchTuple, Queue<RenderCall>> entry : batches.entrySet()) {
            if (entry.getKey().materialProp.translucent || entry.getKey().materialProp.cutoutHack) continue;
            drawBatch(shaderManager, entry, drawContext);
        }

        for (Map.Entry<BatchTuple, Queue<RenderCall>> entry : batches.entrySet()) {
            if (!entry.getKey().materialProp.cutoutHack) continue;
            drawBatch(shaderManager, entry, drawContext);
        }

        // 调试日志（LCD 搁置中，见 deliverables/mtr4-vehicle-lcd-notes.md）：
        // 一次性打印绘制时的视图矩阵（ModelView uniform）与模型矩阵平移，
        // 对照两者即可判断 LCD 实际渲染到世界/相机空间哪个位置
        // if (!poseDiagPrinted) {
        //     poseDiagPrinted = true;
        //     com.fangsu.Main.LOGGER.info("[FangSu] poseDiag: viewMv={}",
        //             com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix());
        //     for (Map.Entry<BatchTuple, Queue<RenderCall>> entry : batches.entrySet()) {
        //         if (!entry.getKey().materialProp.translucent) continue;
        //         RenderCall rc = entry.getValue().peek();
        //         if (rc != null && rc.enqueueProp.attrState != null && rc.enqueueProp.attrState.matrixModel != null) {
        //             com.fangsu.render.sowcer.math.Vector3f t = rc.enqueueProp.attrState.matrixModel.getTranslationPart();
        //             com.fangsu.Main.LOGGER.info("[FangSu] poseDiag: batch={} modelMat.trans=({}, {}, {})",
        //                     entry.getKey().materialProp.shaderName, t.x(), t.y(), t.z());
        //         }
        //     }
        // }
        for (Map.Entry<BatchTuple, Queue<RenderCall>> entry : batches.entrySet()) {
            if (!entry.getKey().materialProp.translucent) continue;
            drawBatch(shaderManager, entry, drawContext);
        }

        popDebugGroup();

        batches.clear();
    }

    private void drawBatch(ShaderManager shaderManager, Map.Entry<BatchTuple, Queue<RenderCall>> entry, DrawContext drawContext) {
        pushDebugGroup(entry.getKey().materialProp.toString());
        shaderManager.setupShaderBatchState(entry.getKey().materialProp, entry.getKey().shaderProp);
        Queue<RenderCall> queue = entry.getValue();
        while (!queue.isEmpty()) {
            RenderCall renderCall = queue.poll();
            renderCall.draw();
            drawContext.recordDrawCall(renderCall);
        }
        shaderManager.cleanupShaderBatchState(entry.getKey().materialProp, entry.getKey().shaderProp);
        popDebugGroup();
    }

    private static class BatchTuple {

        public MaterialProp materialProp;
        public ShaderProp shaderProp;

        public BatchTuple(MaterialProp materialProp, ShaderProp shaderProp) {
            this.materialProp = materialProp;
            this.shaderProp = shaderProp;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BatchTuple that = (BatchTuple) o;
            return materialProp.equals(that.materialProp) && shaderProp.equals(that.shaderProp);
        }

        @Override
        public int hashCode() {
            return Objects.hash(materialProp, shaderProp);
        }
    }

    public static class RenderCall {

        public VertArray vertArray;
        public EnqueueProp enqueueProp;

        public RenderCall(VertArray vertArray, EnqueueProp enqueueProp) {
            this.vertArray = vertArray;
            this.enqueueProp = enqueueProp;
        }

        public void draw() {
            vertArray.bind();
            if (enqueueProp.attrState != null) enqueueProp.attrState.apply(vertArray);
            enqueueProp.applyToggleableAttr();
            if (vertArray.materialProp.attrState != null) vertArray.materialProp.attrState.apply(vertArray);
            vertArray.draw();
        }
    }

    private void pushDebugGroup(String name) {
    }

    private void popDebugGroup() {
    }
}
