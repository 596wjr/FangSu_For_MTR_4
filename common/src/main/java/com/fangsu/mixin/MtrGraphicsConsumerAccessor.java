package com.fangsu.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GraphicsHolder.class, remap = false)
public interface MtrGraphicsConsumerAccessor {
    @Accessor("vertexConsumer")
    void fangsu$setVertexConsumer(VertexConsumer consumer);

    @Accessor("matrixStack")
    PoseStack fangsu$getMatrixStack();

    @Accessor("vertexConsumerProvider")
    MultiBufferSource fangsu$getVertexConsumerProvider();
}
