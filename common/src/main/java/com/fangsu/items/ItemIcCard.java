package com.fangsu.items;

import com.fangsu.mappings.ComponentHelper;
import com.fangsu.ticketSystem.FareInfo;
import com.fangsu.ticketSystem.FareType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class ItemIcCard extends Item implements TicketItem {

    private static final String BALANCE = "Balance";
    private static final String ENTERED = "Entered";
    private static final String ENTRY_ZONE_1 = "EntryZone1";
    private static final String ENTRY_ZONE_2 = "EntryZone2";
    private static final String ENTRY_ZONE_3 = "EntryZone3";

    private static final int BASE_FARE = 2;
    private static final int ZONE_FARE = 1;

    public ItemIcCard() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public boolean enter(Level world, Player player, ItemStack stack, FareInfo info) {
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.contains(BALANCE)) tag.putInt(BALANCE, 100);
        if (tag.getBoolean(ENTERED)) {
            player.displayClientMessage(ComponentHelper.translatable("gui.mtr.already_entered"), true);
            return false;
        }

        tag.putBoolean(ENTERED, true);
        tag.putInt(ENTRY_ZONE_1, info.value());
        tag.putInt(ENTRY_ZONE_2, info.value2());
        tag.putInt(ENTRY_ZONE_3, info.value3());
        String name = info.displayName() == null || info.displayName().isEmpty() ? ComponentHelper.translatable("block.fangsu.ticket_barrier").getString() : info.displayName();
        int balance = tag.getInt(BALANCE);
        player.displayClientMessage(ComponentHelper.translatable("msg.fangsu.ticket.enter", name, balance), true);
        return true;
    }

    @Override
    public boolean exit(Level world, Player player, ItemStack stack, FareInfo info) {
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.getBoolean(ENTERED)) {
            player.displayClientMessage(ComponentHelper.translatable("msg.fangsu.ticket.notEntered"), true);
            return false;
        }

        if (!tag.contains(BALANCE)) tag.putInt(BALANCE, 100);
        int balance = tag.getInt(BALANCE);
        int fare = computeFare(tag, info);
        if (balance < fare) {
            player.displayClientMessage(ComponentHelper.translatable("gui.mtr.insufficient_balance", balance), true);
            return false;
        }

        tag.putInt(BALANCE, balance - fare);
        tag.putBoolean(ENTERED, false);
        tag.putInt(ENTRY_ZONE_1, 0);
        tag.putInt(ENTRY_ZONE_2, 0);
        tag.putInt(ENTRY_ZONE_3, 0);
        String name = info.displayName() == null || info.displayName().isEmpty() ? ComponentHelper.translatable("block.fangsu.ticket_barrier").getString() : info.displayName();
        player.displayClientMessage(ComponentHelper.translatable("msg.fangsu.ticket.exit", name, fare, balance - fare), true);
        return true;
    }

    @Override
    public ItemStack createTicket(int price) {
        return null;
    }

    private int computeFare(CompoundTag tag, FareInfo info) {
        if (info.type() == FareType.FARE_ONCE) {
            return Math.max(0, info.value());
        }
        int entryZone1 = tag.getInt(ENTRY_ZONE_1);
        int entryZone2 = tag.getInt(ENTRY_ZONE_2);
        int entryZone3 = tag.getInt(ENTRY_ZONE_3);
        int exitZone1 = info.value();
        int exitZone2 = info.value2();
        int exitZone3 = info.value3();
        return BASE_FARE + ZONE_FARE * (
                Math.abs(entryZone1 - exitZone1) +
                Math.abs(entryZone2 - exitZone2) +
                Math.abs(entryZone3 - exitZone3)
        );
    }
}
