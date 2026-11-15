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
        fangsu$forEachMarker((start, end, settings, x, y) ->
                graphics.drawText(settings.number(), x + 3, y - 4, 0xFFC0C0C0, false, GraphicsHolder.getDefaultLight()));
    }

    @Inject(method = "mouseClicked2", at = @At("HEAD"), cancellable = true)
    private void fangsu$editBypass(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 0 || !MinecraftClientData.hasPermission() || !((WidgetMap) (Object) this).isMouseOver2(mouseX, mouseY)) {
            return;
        }
        fangsu$forEachMarker((start, end, settings, x, y) -> {
            if (!cir.isCancelled() && Math.abs(mouseX - x) <= 10 && Math.abs(mouseY - y) <= 7) {
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
            final int screenX = x + (int) Math.round(((start.getX() + end.getX()) / 2D - centerX) * scale + width / 2D);
            final int screenY = y + (int) Math.round(((start.getZ() + end.getZ()) / 2D - centerY) * scale + height / 2D);
            if (screenX >= x && screenX < x + width - 10 && screenY >= y && screenY < y + height) {
                consumer.accept(new BlockPos((int) start.getX(), (int) start.getY(), (int) start.getZ()),
                        new BlockPos((int) end.getX(), (int) end.getY(), (int) end.getZ()), settings, screenX, screenY);
            }
        }
    }

    public interface MarkerConsumer {
        void accept(BlockPos start, BlockPos end, ServiceRail settings, int screenX, int screenY);
    }
}
