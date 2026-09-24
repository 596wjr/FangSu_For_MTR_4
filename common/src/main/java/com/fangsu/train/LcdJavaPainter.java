package com.fangsu.train;

import com.fangsu.Main;
import com.fangsu.train.lcds.MtrLcd;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;

/**
 * LCD 纹理绘制入口：把每个 slot 交给 MTR 那套绘图实现（默认 {@link MtrLcd}）。
 * <p>
 * 与旧路径（{@code RenderVehiclesMixin}）完全一致：
 * <pre>
 *   li.draw(g, trainStatus, lcdInfo, state, slotName, texAreaX, texAreaY, texAreaW, texAreaH, callback)
 * </pre>
 * 其中 {@code slotName} 取自 slots.json 的 {@code name}（如 {@code lcd_door_left}），
 * {@link MtrLcd} 用它判断左右侧与是否反转；{@code texArea} 决定画在哪块纹理区域。
 */
public final class LcdJavaPainter {
    private LcdJavaPainter() {
    }

    public static void paint(Graphics2D g, LcdPanelConfig config, LcdInfo info,
                             TrainStatus status, String lcdId) {
        if (g == null || config == null) return;

        // 整张清成透明，避免上一帧残留
        final AffineTransform identity = g.getTransform();
        g.setTransform(identity);
        g.setBackground(new Color(0, 0, 0, 0));
        g.clearRect(0, 0, config.texWidth(), config.texHeight());

        final LcdBase lcd = LcdManager.getInstance().getLcd(lcdId == null ? "mtr" : lcdId);
        if (lcd == null) {
            Main.LOGGER.warn("[FangSu LCD] 没有 id 为 \"{}\" 的 LCD 实现，纹理留空", lcdId);
            return;
        }

        for (LcdPanelConfig.Panel panel : config.panels()) {
            final LcdPanelConfig.Layout layout = config.layout(panel.name());
            try {
                lcd.draw(g, status, info, new java.util.HashMap<>(),
                        panel.name(),
                        layout.x(), layout.y(), layout.w(), layout.h(),
                        () -> {
                        });
            } catch (Throwable t) {
                Main.LOGGER.error("[FangSu LCD] 绘制 slot {} 失败", panel.name(), t);
            }
        }
        g.setTransform(identity);
    }
}
