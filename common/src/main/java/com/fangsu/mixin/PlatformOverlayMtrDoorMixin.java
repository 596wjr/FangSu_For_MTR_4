package com.fangsu.mixin;

import com.fangsu.platform.PlatformMtrDoorPlacement;
import org.mtr.mapping.holder.ItemUsageContext;
import org.mtr.mapping.holder.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 保留 MTR4 原有多格门构建流程，仅把可覆盖顶皮纳入可用位置。 */
@Mixin(value=org.mtr.mod.item.ItemPSDAPGBase.class,remap=false)
public abstract class PlatformOverlayMtrDoorMixin {
    @Inject(method="blocksNotReplaceable",at=@At("HEAD"),cancellable=true,remap=false)
    private static void fangsu$coverable(ItemUsageContext context,int width,int height,Block blacklist,CallbackInfoReturnable<Boolean> result){
        Boolean blocked=PlatformMtrDoorPlacement.blocked(context.data,width,height,blacklist==null?null:blacklist.data,false);if(blocked!=null)result.setReturnValue(blocked);
    }
}
