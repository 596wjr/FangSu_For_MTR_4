package com.fangsu.ticketSystem;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public final class SingleJourneyTicketData {

    private static final String PRICE = "Price";
    private static final String ENTERED = "Entered";
    private static final String ENTRY_ZONE_1 = "EntryZone1";
    private static final String ENTRY_ZONE_2 = "EntryZone2";
    private static final String ENTRY_ZONE_3 = "EntryZone3";

    private SingleJourneyTicketData() {
    }

    /* ========= 初始化（售票机） ========= */

    public static void init(ItemStack stack, int price) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt(PRICE, price);
        tag.putBoolean(ENTERED, false);
    }

    /* ========= 状态 ========= */

    public static boolean hasEntered(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(ENTERED);
    }

    public static void enter(ItemStack stack, int entryZone1, int entryZone2, int entryZone3) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putBoolean(ENTERED, true);
        tag.putInt(ENTRY_ZONE_1, entryZone1);
        tag.putInt(ENTRY_ZONE_2, entryZone2);
        tag.putInt(ENTRY_ZONE_3, entryZone3);
    }

    /* ========= 数据 ========= */

    public static int getPrice(ItemStack stack) {
        if (stack.getTag() != null) {
            return stack.getTag().getInt(PRICE);
        }
        return 0;
    }

    public static int getEntryZone1(ItemStack stack) {
        if (stack.getTag() != null) {
            return stack.getTag().getInt(ENTRY_ZONE_1);
        }
        return 0;
    }

    public static int getEntryZone2(ItemStack stack) {
        if (stack.getTag() != null) {
            return stack.getTag().getInt(ENTRY_ZONE_2);
        }
        return 0;
    }

    public static int getEntryZone3(ItemStack stack) {
        if (stack.getTag() != null) {
            return stack.getTag().getInt(ENTRY_ZONE_3);
        }
        return 0;
    }
}

