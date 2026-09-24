package com.fangsu.train;

import com.fangsu.Main;
import com.lx862.mtrscripting.core.ScriptManager;
import com.lx862.mtrscripting.core.api.ClassRule;
import com.lx862.mtrscripting.lib.org.mozilla.javascript.Context;
import com.lx862.mtrscripting.lib.org.mozilla.javascript.NativeJavaClass;
import com.lx862.mtrscripting.lib.org.mozilla.javascript.Scriptable;

/**
 * 把 {@code FangsuLcdManager} 暴露给 JCM 的脚本作用域，并放行 {@code com.fangsu.*}。
 * <p>
 * 用 JCM 的<b>公开</b>扩展点 {@code ScriptManager.parseScriptEvent}
 * （JCM 自己给脚本加 {@code MTRClientData} / {@code TextUtil} 就是走这条），
 * 而不是去混入它的私有 {@code addBuiltInTypes}——后者在版本变动时会静默失效，
 * 表现就是脚本里报 {@code ReferenceError: "FangsuLcdManager" is not defined}。
 * <p>
 * <b>注意</b>：这个类必须待在普通包里。{@code com.fangsu.mixin.*} 是 mixin 包，
 * 普通代码直接引用会触发
 * {@code IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced directly}。
 * 之前的实现把 {@code install} 放在 mixin 类里、还被 {@code JcmLcdScriptBridge} 直接调用，
 * 结果既是加载错误、又因为那个 mixin 从未注册进 mixin 配置而完全没生效——
 * 表现就是脚本报 {@code FangsuLcdManager is not defined}。现在逻辑只在本类，
 * 由 {@link JcmLcdScriptBridge#script()} 在解析脚本前显式调用。
 * <p>
 * 类闸门：脚本要能直接读到 {@link VehicleLcdRenderer}，必须把 {@code com.fangsu.*}
 * 加进白名单（JCM 默认只放行 {@code org.mtr.*} 与 {@code com.lx862.mtrscripting.mod.impl.mtr.*}）。
 * 两个动作都幂等，{@link JcmLcdScriptBridge#script()} 在解析前还会再调一次。
 */
public final class LcdScriptExtension {
    private LcdScriptExtension() {
    }

    private static volatile boolean shutterConfigured = false;
    private static volatile boolean listenerRegistered = false;
    private static int injectLogCount = 0;

    /** 幂等安装：类闸门白名单 + 作用域变量 */
    public static synchronized void install(ScriptManager manager) {
        if (manager == null) return;
        try {
            if (!shutterConfigured) {
                manager.getClassShutter().allowClass(ClassRule.parse("com.fangsu.*"));
                shutterConfigured = true;
            }
            if (!listenerRegistered) {
                manager.parseScriptEvent.register(LcdScriptExtension::injectGlobals);
                listenerRegistered = true;
                Main.LOGGER.info("[FangSu LCD/JCM] 已注册脚本扩展点（FangsuLcdManager + com.fangsu.* 白名单）");
            }
        } catch (Throwable t) {
            Main.LOGGER.error("[FangSu LCD/JCM] 注册脚本扩展点失败", t);
        }
    }

    /** JCM 每次解析脚本时回调，往作用域里放全局对象 */
    private static void injectGlobals(String contextName, Context cx, Scriptable scope) {
        scope.put("FangsuLcdManager", scope, new NativeJavaClass(scope, VehicleLcdRenderer.class));
        // 每解析一个脚本都会回调（方速接入 25 个车型时也只是一条脚本），限频打印
        if (injectLogCount++ < 5) {
            Main.LOGGER.info("[FangSu LCD/JCM] 脚本作用域已注入 FangsuLcdManager (context={})", contextName);
        }
    }
}
