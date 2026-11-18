package com.fangsu.mixin;

import com.fangsu.mtr.rail.RailPoseExtraHolder;
import com.fangsu.mtr.RouteMapSelection;
import com.fangsu.mtr.ServiceStop;
import com.fangsu.mtr.rail.ServiceRail;
import com.fangsu.network.ServiceRailPackets;
import com.fangsu.ui.ServiceRailScreen;
import net.minecraft.core.BlockPos;
import org.mtr.core.data.TransportMode;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.screen.WidgetMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WidgetMap.class, remap = false)
public abstract class ServiceRailMapMixin implements RouteMapSelection {
    @Unique private java.util.function.Predicate<ServiceStop.Bypass> fangsu$selection;

    @Override
    public void fangsu$selectBypass(java.util.function.Predicate<ServiceStop.Bypass> selection) {
        fangsu$selection = selection;
    }
    @Shadow private double scale;
    @Shadow private double centerX;
    @Shadow private double centerY;
    @Shadow private boolean showStations;
    @Shadow @Final private TransportMode transportMode;

    @Inject(method = "render", at = @At("RETURN"))
    private void fangsu$renderBypass(GraphicsHolder graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        final var widget = (WidgetMap) (Object) this;
        final var drawing = new GuiDrawing(graphics);
        fangsu$forEachMarker((start, end, settings, marker) -> {
            drawing.beginDrawingRectangle();
            drawing.drawRectangle(Math.max(widget.getX2(), marker.left()), Math.max(widget.getY2(), marker.top()),
                    Math.min(widget.getX2() + widget.getWidth(), marker.right()),
                    Math.min(widget.getY2() + widget.getHeight(), marker.bottom()), 0xFFFFFFFF);
            drawing.finishDrawingRectangle();
            if (scale >= 8) {
                final int textX = (marker.left() + marker.right()) / 2;
                final int textY = (marker.top() + marker.bottom()) / 2 - 4;
                final int textWidth = GraphicsHolder.getTextWidth(settings.number());
                if (textX - textWidth / 2 >= widget.getX2() && textX + (textWidth + 1) / 2 <= widget.getX2() + widget.getWidth()
                        && textY >= widget.getY2() && textY + 9 <= widget.getY2() + widget.getHeight()) {
                    graphics.drawText(settings.number(), textX - textWidth / 2, textY, 0xFFFFFFFF, true, GraphicsHolder.getDefaultLight());
                }
            }
        });
    }

    @Inject(method = "mouseClicked2", at = @At("HEAD"), cancellable = true)
    private void fangsu$editBypass(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 0 || !MinecraftClientData.hasPermission() || !((WidgetMap) (Object) this).isMouseOver2(mouseX, mouseY)) {
            return;
        }
        fangsu$forEachMarker((start, end, settings, marker) -> {
            if (!cir.isCancelled() && marker.contains(mouseX, mouseY)) {
                final var selection = fangsu$selection;
                if (selection != null) {
                    if (!selection.test(new ServiceStop.Bypass(start, end))) return;
                } else {
                    ServiceRailScreen.openTrack(settings, value -> ServiceRailPackets.updateTrack(start, end, value));
                }
                cir.setReturnValue(true);
            }
        });
    }

    @Unique
    private void fangsu$forEachMarker(MarkerConsumer consumer) {
        if (!showStations || transportMode != TransportMode.TRAIN) {
            return;
        }
        final var widget = (WidgetMap) (Object) this;
        final int x = widget.getX2();
        final int y = widget.getY2();
        final int width = widget.getWidth();
        final int height = widget.getHeight();
        for (final var rail : MinecraftClientData.getDashboardInstance().rails) {
            final var settings = ServiceRail.get(rail);
            if (settings == null || settings.kind() != ServiceRail.Kind.BYPASS) {
                continue;
            }
            final var holder = (RailPoseExtraHolder) (Object) rail;
            final var start = holder.fangsu$getPosition1();
            final var end = holder.fangsu$getPosition2();
            final double blockX = (start.getX() + end.getX()) / 2;
            final double blockZ = (start.getZ() + end.getZ()) / 2;
            final int left = (int) Math.floor(x + (blockX - centerX) * scale + width / 2D);
            final int top = (int) Math.floor(y + (blockZ - centerY) * scale + height / 2D);
            final int right = Math.max(left + 1, (int) Math.floor(x + (blockX + 1 - centerX) * scale + width / 2D));
            final int bottom = Math.max(top + 1, (int) Math.floor(y + (blockZ + 1 - centerY) * scale + height / 2D));
            if (right > x && left < x + width && bottom > y && top < y + height) {
                consumer.accept(new BlockPos((int) start.getX(), (int) start.getY(), (int) start.getZ()),
                        new BlockPos((int) end.getX(), (int) end.getY(), (int) end.getZ()), settings,
                        new Marker(left, top, right, bottom));
            }
        }
    }

    public interface MarkerConsumer {
        void accept(BlockPos start, BlockPos end, ServiceRail settings, Marker marker);
    }

    public record Marker(int left, int top, int right, int bottom) {
        public boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }
}
