package com.fangsu.ticketSystem;

import com.fangsu.items.TicketItem;
import com.fangsu.mappings.ComponentHelper;
import com.fangsu.mixin.InitAccessorMixin;
import com.fangsu.mixin.MainAccessorMixin;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.mtr.core.Main;
import org.mtr.core.data.Position;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;
import org.mtr.mod.Init;

public class MtrTicketSystem {
    //TODO 交通卡系统

    public static final String BALANCE_OBJECTIVE = "mtr_balance";
    protected static final String ENTRY_ZONE_1_OBJECTIVE = "mtr_entry_zone_1";
    protected static final String ENTRY_ZONE_2_OBJECTIVE = "mtr_entry_zone_2";
    protected static final String ENTRY_ZONE_3_OBJECTIVE = "mtr_entry_zone_3";

    private static final int BASE_FARE = 2;
    private static final int ZONE_FARE = 1;
    private static final int EVASION_FINE = 500;

    /* ===================== 公共入口 ===================== */

    public static boolean enter(Level world, String dispName, int zone1, int zone2, int zone3, Player player) {
        addObjectivesIfMissing(world);

        Score balance = getScore(world, player, BALANCE_OBJECTIVE);
        Score entryZone1 = getScore(world, player, ENTRY_ZONE_1_OBJECTIVE);
        Score entryZone2 = getScore(world, player, ENTRY_ZONE_2_OBJECTIVE);
        Score entryZone3 = getScore(world, player, ENTRY_ZONE_3_OBJECTIVE);

        // 已入闸（三个 zone 都不为 0 才认为已进站，与 MTR 原版一致）
        if (entryZone1.getScore() != 0 && entryZone2.getScore() != 0 && entryZone3.getScore() != 0) {
            player.displayClientMessage(ComponentHelper.translatable("gui.mtr.already_entered"), true);
            return false;
        }

        // 余额不足
        if (balance.getScore() < 0) {
            player.displayClientMessage(ComponentHelper.translatable("gui.mtr.insufficient_balance", balance.getScore()), true);
            return false;
        }

        entryZone1.setScore(encodeZone(zone1));
        entryZone2.setScore(encodeZone(zone2));
        entryZone3.setScore(encodeZone(zone3));
        player.displayClientMessage(
                ComponentHelper.translatable(
                        "gui.mtr.enter_barrier",
                        dispName.replace('|', ' '),
                        balance.getScore()
                ),
                true
        );
        return true;
    }

    public static boolean exit(Level world, String dispName, int zone1, int zone2, int zone3, Player player) {

        addObjectivesIfMissing(world);

        Score balance = getScore(world, player, BALANCE_OBJECTIVE);
        Score entryZone1 = getScore(world, player, ENTRY_ZONE_1_OBJECTIVE);
        Score entryZone2 = getScore(world, player, ENTRY_ZONE_2_OBJECTIVE);
        Score entryZone3 = getScore(world, player, ENTRY_ZONE_3_OBJECTIVE);

        int entry1 = entryZone1.getScore();
        int entry2 = entryZone2.getScore();
        int entry3 = entryZone3.getScore();
        boolean entered = entry1 != 0 && entry2 != 0 && entry3 != 0;
        int fare;

        if (!entered) {
            // 逃票
            fare = EVASION_FINE;
        } else {
            fare = calcFare(zone1, zone2, zone3, decodeZone(entry1), decodeZone(entry2), decodeZone(entry3));
            if (isConcessionary(player)) {
                fare = (int) Math.ceil(fare / 2F);
            }
        }

        entryZone1.setScore(0);
        entryZone2.setScore(0);
        entryZone3.setScore(0);
        balance.add(-fare);

        player.displayClientMessage(
                ComponentHelper.translatable(
                        "gui.mtr.exit_barrier",
                        dispName.replace('|', ' '),
                        fare,
                        balance.getScore()
                ),
                true
        );
        return true;
    }

    /* ===================== 内部工具 ===================== */

    /**
     * 通过 Mixin 访问器获取 MTR4 服务端 Simulator，按位置查找车站。
     */
    protected static Station getStation(Level world, BlockPos pos) {
        try {
            final Main main = InitAccessorMixin.getMain();
            final String dimensionId = Init.getWorldId(new org.mtr.mapping.holder.World(world));
            final Position position = new Position(pos.getX(), pos.getY(), pos.getZ());

            for (final Simulator simulator : ((MainAccessorMixin) main).getSimulators()) {
                if (simulator.dimension.equals(dimensionId)) {
                    for (final Station station : simulator.stations) {
                        if (station.inArea(position)) {
                            return station;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static void addObjectivesIfMissing(Level world) {
        try {
            world.getScoreboard().addObjective(
                    BALANCE_OBJECTIVE,
                    ObjectiveCriteria.DUMMY,
                    Component.literal("Balance"),
                    ObjectiveCriteria.RenderType.INTEGER
            );
        } catch (Exception ignored) {
        }

        try {
            world.getScoreboard().addObjective(
                    ENTRY_ZONE_1_OBJECTIVE,
                    ObjectiveCriteria.DUMMY,
                    Component.literal("Entry Zone 1"),
                    ObjectiveCriteria.RenderType.INTEGER
            );
        } catch (Exception ignored) {
        }

        try {
            world.getScoreboard().addObjective(
                    ENTRY_ZONE_2_OBJECTIVE,
                    ObjectiveCriteria.DUMMY,
                    Component.literal("Entry Zone 2"),
                    ObjectiveCriteria.RenderType.INTEGER
            );
        } catch (Exception ignored) {
        }

        try {
            world.getScoreboard().addObjective(
                    ENTRY_ZONE_3_OBJECTIVE,
                    ObjectiveCriteria.DUMMY,
                    Component.literal("Entry Zone 3"),
                    ObjectiveCriteria.RenderType.INTEGER
            );
        } catch (Exception ignored) {
        }
    }

    public static Score getScore(Level world, Player player, String name) {
        return world.getScoreboard().getOrCreatePlayerScore(
                player.getGameProfile().getName(),
                world.getScoreboard().getObjective(name)
        );
    }

    private static boolean isConcessionary(Player player) {
        return player.isCreative();
    }

    private static int encodeZone(int zone) {
        return zone >= 0 ? zone + 1 : zone;
    }

    private static int decodeZone(int zone) {
        return zone > 0 ? zone - 1 : zone;
    }

    public static int calcFare(int exitZone1, int exitZone2, int exitZone3, int entryZone1, int entryZone2, int entryZone3) {
        return BASE_FARE + ZONE_FARE * (
                Math.abs(exitZone1 - entryZone1) +
                Math.abs(exitZone2 - entryZone2) +
                Math.abs(exitZone3 - entryZone3)
        );
    }

    /**
     * 简化版：仅用 zone1 计算票价（用于售票机 UI 预估显示）
     */
    public static int calcFare(int currentZone, int targetZone) {
        int distance = Math.abs(currentZone - targetZone);
        return BASE_FARE + ZONE_FARE * distance;
    }
}
