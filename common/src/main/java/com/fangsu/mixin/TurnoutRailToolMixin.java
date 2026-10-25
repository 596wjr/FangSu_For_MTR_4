package com.fangsu.mixin;
import com.fangsu.turnout.TurnoutTools;
import org.mtr.mod.render.RenderRails;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** MTR4 原生节点显示沿用同一手持规则。 */
@Mixin(value=RenderRails.class,remap=false)
public class TurnoutRailToolMixin {
    @Inject(method="isHoldingRailRelated",at=@At("RETURN"),cancellable=true,remap=false)
    private static void fangsu$markerNodes(ClientPlayerEntity player,CallbackInfoReturnable<Boolean> callback){if(player!=null&&TurnoutTools.showNodes(player.data))callback.setReturnValue(true);}
}
