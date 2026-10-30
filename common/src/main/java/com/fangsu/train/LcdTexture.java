package com.fangsu.train;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.mtr.mapping.holder.Identifier;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.UUID;

/** LCD 独占纹理；构造、上传和释放均由客户端线程执行，不排入 JCM 的异步上传队列。 */
final class LcdTexture implements AutoCloseable {
    final BufferedImage image;
    private final ResourceLocation location;
    private final DynamicTexture texture;
    private boolean closed;

    LcdTexture(int width, int height) {
        final int maxSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        if (width <= 0 || height <= 0 || width > maxSize || height > maxSize) {
            throw new IllegalArgumentException("LCD 纹理尺寸无效: " + width + "x" + height + " (GPU 上限 " + maxSize + ")");
        }
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        location = new ResourceLocation("fangsu", "dynamic/lcd/" + UUID.randomUUID());
        final int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            texture = new DynamicTexture(new NativeImage(width, height, false));
        } finally {
            GlStateManager._bindTexture(previousTexture);
        }
        try {
            Minecraft.getInstance().getTextureManager().register(location, texture);
        } catch (RuntimeException | Error e) {
            texture.close();
            throw e;
        }
    }

    Identifier identifier() {
        return new Identifier(location.getNamespace(), location.getPath());
    }

    void upload() {
        if (closed) return;
        final NativeImage pixels = texture.getPixels();
        if (pixels == null) return;
        final int[] source = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final int argb = source[y * image.getWidth() + x];
                // AWT ARGB → NativeImage ABGR，不改全局纹理 swizzle 状态。
                pixels.setPixelRGBA(x, y, (argb & 0xFF00FF00) | ((argb >>> 16) & 0xFF) | ((argb & 0xFF) << 16));
            }
        }
        final int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            texture.upload();
        } finally {
            GlStateManager._bindTexture(previousTexture);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Minecraft.getInstance().getTextureManager().release(location);
    }
}
