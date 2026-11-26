package com.fangsu.train;

import com.fangsu.train.lcds.MtrLcd;

//#if WITH_JS
import com.fangsu.userScripts.ScriptHolderBase;
import com.fangsu.userScripts.ScriptManager;
import org.graalvm.polyglot.proxy.ProxyObject;
//#endif

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Map;
import java.util.HashMap;

/** 只绘制堆内图像；调用者等绘图完成后再在客户端线程上传。 */
public final class LcdJavaPainter {
    private LcdJavaPainter() {
    }

    /** 保留原 Java 调用签名；独立渲染器使用下面的重载保存实例及跨帧状态。 */
    public static void paint(Graphics2D graphics, LcdPanelConfig config, LcdInfo info,
                             TrainStatus status, String lcdId) {
        paint(graphics, config, info, status,
                LcdManager.getInstance().getLcd(lcdId == null ? "mtr" : lcdId), new HashMap<>());
    }

    public static void paint(Graphics2D graphics, LcdPanelConfig config, LcdInfo info,
                             TrainStatus status, LcdBase painter, Map<String, Object> state) {
        graphics.setComposite(AlphaComposite.Clear);
        graphics.fillRect(0, 0, config.texWidth(), config.texHeight());
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        boolean scriptDrawn = false;
        //#if WITH_JS
        if (info.hasScript()) {
            final ScriptHolderBase holder = LcdScriptSupport.getHolder(info.script());
            if (holder == null) throw new IllegalStateException("LCD 脚本尚未就绪: " + info.script());
            final Graphics2D scriptGraphics = (Graphics2D) graphics.create();
            try {
                ScriptManager.getInstance().requestRunFunctionSync(holder, null, "draw", scriptGraphics, ProxyObject.fromMap(state),
                        status, LcdScriptSupport.toScriptValue(LcdScriptSupport.buildInfo(info)),
                        LcdScriptSupport.toScriptValue(LcdScriptSupport.buildExtraConfig(info)));
            } finally {
                scriptGraphics.dispose();
            }
            if (!(painter instanceof MtrLcd)) return;
            scriptDrawn = true;
        }
        //#endif
        if (painter == null) throw new IllegalArgumentException("未知 LCD id: " + info.id());
        for (LcdPanelConfig.Panel panel : config.panels()) {
            if (scriptDrawn && !MtrLcd.isDisplay(panel.name())) continue;
            final LcdPanelConfig.Layout layout = config.layoutFor(panel);
            // 每个 slot 隔离 Graphics2D 的变换、裁剪和绘图属性，异常交给上层统一冷却。
            final Graphics2D slotGraphics = (Graphics2D) graphics.create();
            try {
                slotGraphics.clipRect(layout.x(), layout.y(), layout.w(), layout.h());
                painter.draw(slotGraphics, status, info, state, panel.name(),
                        layout.x(), layout.y(), layout.w(), layout.h(), () -> {});
            } finally {
                slotGraphics.dispose();
            }
        }
    }
}
