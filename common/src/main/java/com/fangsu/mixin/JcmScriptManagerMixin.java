package com.fangsu.mixin;

import com.lx862.mtrscripting.core.ScriptManager;
import com.lx862.mtrscripting.lib.org.mozilla.javascript.Context;
import com.lx862.mtrscripting.lib.org.mozilla.javascript.Scriptable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=ScriptManager.class,remap=false)
public class JcmScriptManagerMixin {
    @Inject(method = "addBuiltInTypes", at = @At("TAIL"))
    private void addExtraBuiltInTypes(
            String contextName, Context cx, Scriptable scope, CallbackInfo ci
    )throws RuntimeException {

    }
}
