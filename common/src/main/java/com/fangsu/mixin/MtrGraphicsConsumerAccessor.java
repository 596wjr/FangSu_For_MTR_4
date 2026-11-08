package com.fangsu.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GraphicsHolder.class, remap = false)
public interface MtrGraphicsConsumerAccessor {
    @Accessor("vertexConsumer")
    void fangsu$setVertexConsumer(VertexConsumer consumer);
}
