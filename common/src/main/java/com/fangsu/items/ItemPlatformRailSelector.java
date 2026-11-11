package com.fangsu.items;

import com.fangsu.utils.RegisterUtil;
import net.minecraft.network.chat.Component;
import org.mtr.core.data.Rail;
import org.mtr.mapping.holder.*;
import org.mtr.mod.item.ItemNodeModifierSelectableBlockBase;
import java.util.List;

/** 与混合构建器共用节点显示规则；客户端只选两个端点，服务端预览时核对直接相连的轨道。 */
public final class ItemPlatformRailSelector extends ItemNodeModifierSelectableBlockBase {
    public ItemPlatformRailSelector(){super(false,0,1,new ItemSettings());}
    //#if MC_VERSION < 11903
    //$$ @Override public void fillItemCategory(net.minecraft.world.item.CreativeModeTab tab,net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> items){
    //$$     if(tab==com.fangsu.creativeTabs.ModCreativeTabs.FANGSU_MAIN||tab==net.minecraft.world.item.CreativeModeTab.TAB_SEARCH)items.add(new net.minecraft.world.item.ItemStack(this));
    //$$ }
    //#endif
    @Override public ActionResult useOnBlock2(ItemUsageContext context) {
        if(context.getWorld().isClient()&&context.getHand().data==net.minecraft.world.InteractionHand.MAIN_HAND)
            com.fangsu.platform.CurvedPlatformClient.click(context.getPlayer()==null?null:context.getPlayer().data,context.getBlockPos().data);
        return ActionResult.SUCCESS;
    }
    @Override public void useWithoutResult(World world,PlayerEntity player,Hand hand) {
        if(world.isClient()&&hand.data==net.minecraft.world.InteractionHand.MAIN_HAND&&player.isSneaking())com.fangsu.platform.CurvedPlatformClient.clearSelection(player.data);
    }
    @Override public void appendTooltipHelper(ItemStack stack,World world,List<Component> tooltip,TooltipContext flag) {
        RegisterUtil.addDescTooltip(tooltip,"item.fangsu.platform_rail_selector.desc");
    }
    @Override protected void onConnect(Rail rail,ServerPlayerEntity player,ItemStack stack,int radius,int height) { }
}
