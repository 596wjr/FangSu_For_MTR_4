package com.fangsu.items;

import com.fangsu.client.ClientHooks;
import com.fangsu.mtr.rail.ServiceRail;
import com.fangsu.blocks.BlockMultiDirectionNode;
import net.minecraft.world.item.ItemStack;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;
import org.mtr.mapping.holder.ActionResult;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.Hand;
import org.mtr.mapping.holder.ItemSettings;
import org.mtr.mapping.holder.ItemUsageContext;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.Property;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.World;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.data.RailType;
import org.mtr.mod.generated.lang.TranslationProvider;
import org.mtr.mod.item.ItemRailModifier;
import org.mtr.mod.packet.PacketUpdateData;

public final class ItemServiceRail extends ItemRailModifier {
    public ItemServiceRail(ServiceRail.Kind kind) {
        super(true, false, false, false, RailType.IRON, new ItemSettings());
        this.kind = kind;
    }

    private final ServiceRail.Kind kind;

    public ServiceRail.Kind kind() {
        return kind;
    }

    public ServiceRail settings(ItemStack stack) {
        final var tag = stack.getTag();
        final ServiceRail settings = tag == null ? null : ServiceRail.decode(tag.getString(ServiceRail.KEY));
        return settings == null || settings.kind() != kind ? kind.defaults() : settings;
    }

    @Override
    public ActionResult useOnBlock2(ItemUsageContext context) {
        final var block = context.getWorld().getBlockState(context.getBlockPos()).getBlock().data;
        if (block instanceof BlockMultiDirectionNode || block instanceof BlockNode node && node.transportMode == TransportMode.TRAIN) {
            // 绕过 ItemRailModifier 的快捷铺点，始终使用两点连轨。
            return context.getWorld().isClient() ? ActionResult.SUCCESS : connectNode(context);
        }
        if (block instanceof BlockNode) {
            return ActionResult.FAIL;
        }
        if (context.getWorld().isClient()) {
            ClientHooks.OPEN_SERVICE_RAIL_TOOL.accept(context.getHand().data, context.getStack().data);
        }
        return ActionResult.SUCCESS;
    }

    private ActionResult connectNode(ItemUsageContext context) {
        final var tag = context.getStack().getOrCreateTag();
        if (tag.contains(TAG_POS)) {
            onEndClick(context, BlockPos.fromLong(tag.getLong(TAG_POS)), tag);
            tag.remove(TAG_POS);
        } else {
            tag.putLong(TAG_POS, context.getBlockPos().asLong());
            onStartClick(context, tag);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public void useWithoutResult(World world, PlayerEntity player, Hand hand) {
        if (world.isClient()) {
            ClientHooks.OPEN_SERVICE_RAIL_TOOL.accept(hand.data, player.data.getItemInHand(hand.data));
        }
    }

    @Override
    protected void onConnect(World world, org.mtr.mapping.holder.ItemStack stack, TransportMode transportMode,
                             BlockState start, BlockState end, BlockPos posStart, BlockPos posEnd,
                             Angle facingStart, Angle facingEnd, ServerPlayerEntity player) {
        if (player == null || transportMode != TransportMode.TRAIN || !(end.getBlock().data instanceof BlockNode node)
                || node.transportMode != TransportMode.TRAIN) {
            return;
        }
        final Rail rail = createRail(player.getUuid(), transportMode, start, end, posStart, posEnd, facingStart, facingEnd);
        if (rail == null) {
            player.sendMessage(TranslationProvider.GUI_MTR_INVALID_ORIENTATION.getText(), true);
            return;
        }
        ServiceRail.set(rail, settings(stack.data));
        world.setBlockState(posStart, start.with(new Property<>(BlockNode.IS_CONNECTED.data), true));
        world.setBlockState(posEnd, end.with(new Property<>(BlockNode.IS_CONNECTED.data), true));
        PacketUpdateData.sendDirectlyToServerRail(ServerWorld.cast(world), rail);
    }

    //#if MC_VERSION < 11903
    //$$ @Override
    //$$ public void fillItemCategory(net.minecraft.world.item.CreativeModeTab tab, net.minecraft.core.NonNullList<ItemStack> items) {
    //$$     if (tab == com.fangsu.creativeTabs.ModCreativeTabs.FANGSU_MAIN || tab == net.minecraft.world.item.CreativeModeTab.TAB_SEARCH) {
    //$$         items.add(new ItemStack(this));
    //$$     }
    //$$ }
    //#endif
}
