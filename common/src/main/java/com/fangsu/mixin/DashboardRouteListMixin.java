package com.fangsu.mixin;

import com.fangsu.ui.DashboardRoutePanel;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.mtr.mod.screen.DashboardList;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DashboardList.class, remap = false)
public abstract class DashboardRouteListMixin {
    @Inject(method = "render(Lorg/mtr/mapping/mapper/GraphicsHolder;)V", at = @At("HEAD"), cancellable = true)
    private void fangsu$renderRoutes(GraphicsHolder graphics, CallbackInfo ci) {
        final var client = Minecraft.getInstance();
        if (!(client.screen instanceof DashboardRoutePanel.Host host)) return;
        final var window = client.getWindow();
        final int mouseX = (int) (client.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth());
        final int mouseY = (int) (client.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight());
        final var drawing = new GuiDrawing(graphics);
        final var canvas = new DashboardRoutePanel.Canvas() {
            public void fill(int left, int top, int right, int bottom, int color) {
                drawing.beginDrawingRectangle();
                drawing.drawRectangle(left, top, right, bottom, color);
                drawing.finishDrawingRectangle();
            }

            public void text(Component label, int x, int y, int color) {
                final var styled = label.copy().withStyle(style -> style.getColor() == null ? style.withColor(color & 0xFFFFFF) : style);
                graphics.drawText(new MutableText(styled), x, y, color, false, GraphicsHolder.getDefaultLight());
            }

            public void icon(DashboardRoutePanel.Icon icon, int x, int y, boolean hovered) {
                final String suffix = hovered ? "_highlighted.png" : ".png";
                final var texture = new org.mtr.mapping.holder.Identifier("textures/gui/sprites/mtr/icon_" + icon.textureName() + suffix);
                drawing.beginDrawingTexture(texture);
                drawing.drawTexture(x, y, x + 20, y + 20, 0, 0, 1, 1);
                drawing.finishDrawingTexture();
            }
        };
        if (host.fangsu$drawRoutePanel(canvas, mouseX, mouseY)) ci.cancel();
    }
}
