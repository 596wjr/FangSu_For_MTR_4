package com.fangsu.train;

import com.fangsu.mixin.LcdOptimizedModelWrapperInvoker;
import com.fangsu.mixin.LcdGlStateTrackerAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.Vector3f;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.render.batch.MaterialProperties;
import org.mtr.mapping.render.model.Face;
import org.mtr.mapping.render.model.Mesh;
import org.mtr.mapping.render.model.RawMesh;
import org.mtr.mapping.render.model.RawModel;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mapping.render.tool.GlStateTracker;
import org.mtr.mapping.render.vertex.Vertex;
import org.mtr.mapping.render.vertex.VertexAttributeMapping;
import org.mtr.mapping.render.vertex.VertexAttributeSource;
import org.mtr.mapping.render.vertex.VertexAttributeType;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.StoredMatrixTransformations;
import org.mtr.mod.resource.OptimizedModelWrapper;
import org.mtr.mod.resource.RenderStage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 方速持有 LCD 网格；沿用 MTR/JCM 的 RawModel → OptimizedModel → queue 流程。 */
final class LcdModel implements AutoCloseable {
    private static final VertexAttributeMapping MAPPING = new VertexAttributeMapping.Builder()
            .set(VertexAttributeType.POSITION, VertexAttributeSource.VERTEX_BUFFER)
            .set(VertexAttributeType.COLOR, VertexAttributeSource.GLOBAL)
            .set(VertexAttributeType.UV_TEXTURE, VertexAttributeSource.VERTEX_BUFFER)
            .set(VertexAttributeType.UV_OVERLAY, VertexAttributeSource.GLOBAL)
            .set(VertexAttributeType.UV_LIGHTMAP, VertexAttributeSource.GLOBAL)
            .set(VertexAttributeType.NORMAL, VertexAttributeSource.VERTEX_BUFFER)
            .set(VertexAttributeType.MATRIX_MODEL, VertexAttributeSource.GLOBAL)
            .build();

    private final List<VertexArray> vertexArrays = new ArrayList<>();
    private final List<Mesh> buffers = new ArrayList<>();
    private final OptimizedModelWrapper model;
    private boolean closed;

    LcdModel(LcdPanelConfig config, Identifier texture) {
        final RawModel raw = new RawModel();
        for (LcdPanelConfig.Panel panel : config.panels()) {
            final RawMesh mesh = new RawMesh(new MaterialProperties(shaderType(panel.renderType()), texture, 0xFFFFFFFF));
            final LcdPanelConfig.Layout layout = config.layout(panel.name());
            final float u0 = (float) layout.x() / config.texWidth();
            final float u1 = (float) (layout.x() + layout.w()) / config.texWidth();
            final float v0 = (float) layout.y() / config.texHeight();
            final float v1 = (float) (layout.y() + layout.h()) / config.texHeight();
            final float[][] uvs = {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
            for (double[][] quad : panel.quads()) {
                final int base = mesh.vertices.size();
                final double ax = quad[1][0] - quad[0][0], ay = quad[1][1] - quad[0][1], az = quad[1][2] - quad[0][2];
                final double bx = quad[3][0] - quad[0][0], by = quad[3][1] - quad[0][1], bz = quad[3][2] - quad[0][2];
                final double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                final double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length < 1E-6) continue;
                for (int i = 0; i < 4; i++) {
                    final Vertex vertex = new Vertex();
                    // 与 JCM RawMeshBuilderJS 一致：slots 的 Y/Z 取反，UV 不翻转。
                    vertex.position = new Vector3f((float) quad[i][0], (float) -quad[i][1], (float) -quad[i][2]);
                    vertex.normal = new Vector3f((float) (nx / length), (float) (-ny / length), (float) (-nz / length));
                    vertex.u = uvs[i][0];
                    vertex.v = uvs[i][1];
                    vertex.color = 0xFFFFFFFF;
                    mesh.vertices.add(vertex);
                }
                mesh.faces.addAll(Face.triangulate(new int[]{base, base + 1, base + 2, base + 3}));
            }
            raw.append(mesh);
        }
        try {
            // MTR 4.0.5 发布包没有 runWithProtectedState，仅在本层捕获时负责恢复。
            final boolean ownsProtection = !LcdGlStateTrackerAccessor.fangsu$isStateProtected();
            final boolean cullEnabled = ownsProtection && GL11.glIsEnabled(GL11.GL_CULL_FACE);
            final boolean depthMask = ownsProtection && GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            if (ownsProtection) GlStateTracker.capture();
            try {
                raw.iterateRawMeshList(mesh -> {
                    if (mesh.faces.isEmpty()) return;
                    final Mesh uploaded = mesh.upload(MAPPING);
                    buffers.add(uploaded);
                    vertexArrays.add(new VertexArray(uploaded, MAPPING));
                });
            } finally {
                if (ownsProtection) {
                    try {
                        GlStateTracker.restore();
                    } finally {
                        // 旧版 restore 会强制开启剔除和深度写入，补回调用前的实际状态。
                        if (cullEnabled) RenderSystem.enableCull();
                        else RenderSystem.disableCull();
                        RenderSystem.depthMask(depthMask);
                    }
                }
            }
            if (vertexArrays.isEmpty()) throw new IllegalArgumentException("LCD 面板全部为退化四边形");
            model = LcdOptimizedModelWrapperInvoker.fangsu$create(new OptimizedModel(vertexArrays));
        } catch (RuntimeException | Error e) {
            vertexArrays.forEach(VertexArray::close);
            buffers.forEach(Mesh::close);
            throw e;
        }
        // 不调用全局 beginReload/finishReload；LCD 的局部创建不能重置 MTR 的全局渲染队列。
    }

    void queue(StoredMatrixTransformations transformations, int light) {
        final StoredMatrixTransformations snapshot = transformations.copy();
        // 保留原 JCM VehicleResourceMixin 的车厢原点约定，避免旧 slots 整体偏移一米。
        snapshot.add(graphics -> graphics.translate(0, -1, 0));
        MainRenderer.scheduleRender(QueuedRenderLayer.TEXT, (graphics, offset) -> {
            if (closed) return;
            snapshot.transform(graphics, offset);
            try {
                CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.queue(model, graphics, light);
            } finally {
                graphics.pop();
            }
        });
    }

    private static OptimizedModel.ShaderType shaderType(String type) {
        return RenderStage.valueOf(type.toLowerCase(Locale.ROOT)
                .replace("exteriortranslucent", "interior_translucent")
                .replace("interiortranslucent", "interior_translucent")
                .replace("lighttranslucent", "always_on_light")
                .toUpperCase(Locale.ROOT)).shaderType;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        vertexArrays.forEach(VertexArray::close);
        buffers.forEach(Mesh::close);
    }
}
