package com.fangsu.ticketSystem;

import com.fangsu.items.TicketItem;
import com.fangsu.mappings.ComponentHelper;
import com.fangsu.mixin.InitAccessorMixin;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
//#if MC_VERSION < 12002
import net.minecraft.world.scores.Score;
//#else
//$$ import net.minecraft.network.chat.numbers.BlankFormat;
//$$ import net.minecraft.world.scores.ScoreAccess;
//$$ import net.minecraft.world.scores.ScoreHolder;
//#endif
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.mtr.core.Main;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;
import org.mtr.mod.Init;

import java.lang.reflect.Field;

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

        var balance = getScore(world, player, BALANCE_OBJECTIVE);
        var entryZone1 = getScore(world, player, ENTRY_ZONE_1_OBJECTIVE);
        var entryZone2 = getScore(world, player, ENTRY_ZONE_2_OBJECTIVE);
        var entryZone3 = getScore(world, player, ENTRY_ZONE_3_OBJECTIVE);

        // 已入闸（三个 zone 都不为 0 才认为已进站，与 MTR 原版一致）
        if (getScoreValue(entryZone1) != 0 && getScoreValue(entryZone2) != 0 && getScoreValue(entryZone3) != 0) {
            player.displayClientMessage(ComponentHelper.translatable("gui.mtr.already_entered"), true);
            return false;
        }

        // 余额不足
        if (getScoreValue(balance) < 0) {
            player.displayClientMessage(ComponentHelper.translatable("gui.mtr.insufficient_balance", getScoreValue(balance)), true);
            return false;
        }

        setScoreValue(entryZone1, encodeZone(zone1));
        setScoreValue(entryZone2, encodeZone(zone2));
        setScoreValue(entryZone3, encodeZone(zone3));
        player.displayClientMessage(
                ComponentHelper.translatable(
                        "gui.mtr.enter_barrier",
                        dispName.replace('|', ' '),
                        getScoreValue(balance)
                ),
                true
        );
        return true;
    }

    public static boolean exit(Level world, String dispName, int zone1, int zone2, int zone3, Player player) {

        addObjectivesIfMissing(world);

        var balance = getScore(world, player, BALANCE_OBJECTIVE);
        var entryZone1 = getScore(world, player, ENTRY_ZONE_1_OBJECTIVE);
        var entryZone2 = getScore(world, player, ENTRY_ZONE_2_OBJECTIVE);
        var entryZone3 = getScore(world, player, ENTRY_ZONE_3_OBJECTIVE);

        int entry1 = getScoreValue(entryZone1);
        int entry2 = getScoreValue(entryZone2);
        int entry3 = getScoreValue(entryZone3);
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

        setScoreValue(entryZone1, 0);
        setScoreValue(entryZone2, 0);
        setScoreValue(entryZone3, 0);
        addScoreValue(balance, -fare);

        player.displayClientMessage(
                ComponentHelper.translatable(
                        "gui.mtr.exit_barrier",
                        dispName.replace('|', ' '),
                        fare,
                        getScoreValue(balance)
                ),
                true
        );
        return true;
    }

    /* ===================== 内部工具 ===================== */

    /**
     * Main.simulators / Data.stations 的字段类型是 fastutil 集合（ObjectImmutableList / ObjectArraySet）。
     * 编译期引用的 MTR jar（Modrinth maven）中 fastutil 被 relocate 为 org.mtr.libraries.it.unimi.dsi.fastutil.*，
     * 而运行时加载的 MTR/TSC（Modrinth 发布物或独立 TSC jar）中是原版 it.unimi.dsi.fastutil.* ——
     * mixin accessor 的返回类型只能匹配其中一种，另一种环境下注入失败。因此改用反射按 Object 访问，
     * 以 Iterable 遍历，可同时兼容两种包名。
     */
    private static final Field SIMULATORS_FIELD;
    private static final Field STATIONS_FIELD;

    static {
        Field simulatorsField = null;
        Field stationsField = null;
        try {
            simulatorsField = Main.class.getDeclaredField("simulators");
            simulatorsField.setAccessible(true);
            stationsField = Data.class.getField("stations");
        } catch (Exception e) {
            com.fangsu.Main.LOGGER.warn("Failed to init MTR station fields: {}", e.toString());
        }
        SIMULATORS_FIELD = simulatorsField;
        STATIONS_FIELD = stationsField;
    }

    /**
     * 通过 Mixin 访问器获取 MTR4 服务端 Simulator，按位置查找车站。
     */
    protected static Station getStation(Level world, BlockPos pos) {
        try {
            final Main main = InitAccessorMixin.getMain();
            final String dimensionId = Init.getWorldId(new org.mtr.mapping.holder.World(world));
            final Position position = new Position(pos.getX(), pos.getY(), pos.getZ());

            for (final Object simulatorObj : (Iterable<?>) SIMULATORS_FIELD.get(main)) {
                final Simulator simulator = (Simulator) simulatorObj;
                if (simulator.dimension.equals(dimensionId)) {
                    for (final Object stationObj : (Iterable<?>) STATIONS_FIELD.get(simulator)) {
                        final Station station = (Station) stationObj;
                        if (station.inArea(position)) {
                            return station;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 记录异常便于排查 mixin 注入失败或 MTR API 变动等问题，避免静默失败
            com.fangsu.Main.LOGGER.warn("Failed to get MTR station at {}: {}", pos, e.toString());
        }
        return null;
    }

    public static void addObjectivesIfMissing(Level world) {
        try {
            world.getScoreboard().addObjective(
                    BALANCE_OBJECTIVE,
                    ObjectiveCriteria.DUMMY,
                    ComponentHelper.literal("Balance"),
                    ObjectiveCriteria.RenderType.INTEGER
                    //#if MC_VERSION < 12002
                    // 1.20.2+ 的 addObjective 多两个参数（NumberFormat 等）
                    //#else
                    //$$ , true, BlankFormat.INSTANCE
                    //#endif
            );
        } catch (Exception ignored) {
        }

        try {
            world.getScoreboard().addObjective(
                    ENTRY_ZONE_1_OBJECTIVE,
                    ObjectiveCriteria.DUMMY,
                    ComponentHelper.literal("Entry Zone 1"),
                    ObjectiveCriteria.RenderType.INTEGER
                    //#if MC_VERSION < 12002
                    // 1.20.2+ 的 addObjective 多两个参数（NumberFormat 等）
                    //#else
                    //$$ , true, BlankFormat.INSTANCE
                    //#endif
            );
        } catch (Exception ignored) {
        }

        try {
            world.getScoreboard().addObjective(
                    ENTRY_ZONE_2_OBJECTIVE,
                    ObjectiveCriteria.DUMMY,
                    ComponentHelper.literal("Entry Zone 2"),
                    ObjectiveCriteria.RenderType.INTEGER
                    //#if MC_VERSION < 12002
                    // 1.20.2+ 的 addObjective 多两个参数（NumberFormat 等）
                    //#else
                    //$$ , true, BlankFormat.INSTANCE
                    //#endif
            );
        } catch (Exception ignored) {
        }

        try {
            world.getScoreboard().addObjective(
                    ENTRY_ZONE_3_OBJECTIVE,
                    ObjectiveCriteria.DUMMY,
                    ComponentHelper.literal("Entry Zone 3"),
                    ObjectiveCriteria.RenderType.INTEGER
                    //#if MC_VERSION < 12002
                    // 1.20.2+ 的 addObjective 多两个参数（NumberFormat 等）
                    //#else
                    //$$ , true, BlankFormat.INSTANCE
                    //#endif
            );
        } catch (Exception ignored) {
        }
    }

    //#if MC_VERSION < 12002
    public static Score getScore(Level world, Player player, String name) {
        return world.getScoreboard().getOrCreatePlayerScore(
                player.getGameProfile().getName(),
                world.getScoreboard().getObjective(name)
        );
    }
    //#else
    //$$ public static ScoreAccess getScore(Level world, Player player, String name) {
    //$$     return world.getScoreboard().getOrCreatePlayerScore(
    //$$             ScoreHolder.forNameOnly(player.getGameProfile().getName()),
    //$$             world.getScoreboard().getObjective(name)
    //$$     );
    //$$ }
    //#endif

    /** 读取/写入/增减积分（1.20.2+ 用 ScoreAccess，旧版用 Score），屏蔽版本差异 */
    //#if MC_VERSION < 12002
    protected static int getScoreValue(Score score) {
        return score.getScore();
    }

    protected static void setScoreValue(Score score, int value) {
        score.setScore(value);
    }

    protected static void addScoreValue(Score score, int delta) {
        score.add(delta);
    }
    //#else
    //$$ protected static int getScoreValue(ScoreAccess score) {
    //$$     return score.get();
    //$$ }
    //$$
    //$$ protected static void setScoreValue(ScoreAccess score, int value) {
    //$$     score.set(value);
    //$$ }
    //$$
    //$$ protected static void addScoreValue(ScoreAccess score, int delta) {
    //$$     score.add(delta);
    //$$ }
    //#endif

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
