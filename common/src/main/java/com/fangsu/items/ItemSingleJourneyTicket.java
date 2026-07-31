package com.fangsu.items;

import com.fangsu.mappings.ComponentHelper;
import com.fangsu.ticketSystem.FareInfo;
import com.fangsu.ticketSystem.FareType;
import com.fangsu.ticketSystem.SingleJourneyTicketData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

public class ItemSingleJourneyTicket extends Item implements TicketItem {
    public ItemSingleJourneyTicket() {
        super(com.fangsu.utils.RegisterUtil.tabProps(new Item.Properties().stacksTo(1)));
    }

    @Override
    public boolean enter(Level world, Player player, ItemStack stack, FareInfo info) {
        if (SingleJourneyTicketData.hasEntered(stack)) {
            player.displayClientMessage(ComponentHelper.translatable("ui.fangsu.ticket.error"), true);
            return false;
        }

        switch (info.type()) {
            case MTR, CUSTOM -> {
                SingleJourneyTicketData.enter(stack, info.value(), info.value2(), info.value3());
                String name = info.displayName() == null || info.displayName().isEmpty() ? ComponentHelper.translatable("block.fangsu.ticket_barrier").getString() : info.displayName();
                player.displayClientMessage(ComponentHelper.translatable("msg.fangsu.ticket.enter1", name.replace("|", " ")), true);
                return true;
            }
            case FARE_ONCE -> {
                if (SingleJourneyTicketData.getPrice(stack) >= info.value()) {
                    stack.shrink(1);
                    player.displayClientMessage(ComponentHelper.translatable("ui.fangsu.ticket.success"), true);
                    return true;
                }
                player.displayClientMessage(ComponentHelper.translatable("ui.fangsu.ticket.error"), true);
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private static final int BASE_FARE = 2;
    private static final int ZONE_FARE = 1;

    @Override
    public boolean exit(Level world, Player player, ItemStack stack, FareInfo info) {
        if (!SingleJourneyTicketData.hasEntered(stack)) {
            player.displayClientMessage(ComponentHelper.translatable("ui.fangsu.ticket.error"), true);
            return false;
        }

        if (info.type() == FareType.MTR || info.type() == FareType.CUSTOM) {
            int entryZone1 = SingleJourneyTicketData.getEntryZone1(stack);
            int entryZone2 = SingleJourneyTicketData.getEntryZone2(stack);
            int entryZone3 = SingleJourneyTicketData.getEntryZone3(stack);
            int exitZone1 = info.value();
            int exitZone2 = info.value2();
            int exitZone3 = info.value3();
            int fare = BASE_FARE + ZONE_FARE * (
                    Math.abs(entryZone1 - exitZone1) +
                    Math.abs(entryZone2 - exitZone2) +
                    Math.abs(entryZone3 - exitZone3)
            );
            if (SingleJourneyTicketData.getPrice(stack) < fare) {
                player.displayClientMessage(ComponentHelper.translatable("ui.fangsu.ticket.error"), true);
                return false;
            }
            stack.shrink(1);
            if (info.type() == FareType.MTR) {
                String name = info.displayName() == null || info.displayName().isEmpty() ? ComponentHelper.translatable("block.fangsu.ticket_barrier").getString() : info.displayName();
                player.displayClientMessage(ComponentHelper.translatable("ui.fangsu.ticket.exit1", name.replace("|", " "), fare), true);
            } else player.displayClientMessage(ComponentHelper.translatable("ui.fangsu.ticket.success"), true);
            return true;
        }

        return false;
    }

    @Override
    public ItemStack createTicket(int price) {
        ItemStack stack = new ItemStack(this);
        SingleJourneyTicketData.init(stack, price);
        return stack;
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Level level,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return;

        tooltip.add(
                ComponentHelper.translatable(
                        "ui.fangsu.ticket.value",
                        SingleJourneyTicketData.getPrice(stack)
                )
        );

        if (SingleJourneyTicketData.hasEntered(stack) && level != null) {
            tooltip.add(
                    ComponentHelper.translatable(
                            "ui.fangsu.ticket.entered"

                    )
            );
        } else {
            tooltip.add(
                    ComponentHelper.translatable(
                            "ui.fangsu.ticket.not_entered"
                    )
            );
        }
    }
}
