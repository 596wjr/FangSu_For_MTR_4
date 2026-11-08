package com.fangsu.mixin;

import org.mtr.mapping.render.vertex.VertexAttributeState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "org.mtr.mapping.render.batch.BatchManager$RenderCall", remap = false)
public interface MtrOptimizedRenderCallAccessor {
    @Accessor("vertexAttributeState")
    VertexAttributeState fangsu$getVertexAttributeState();
}
