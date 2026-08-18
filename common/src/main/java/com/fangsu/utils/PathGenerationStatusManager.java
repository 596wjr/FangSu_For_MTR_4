package com.fangsu.utils;

import com.fangsu.Main;
import com.fangsu.client.ClientHooks;
import com.fangsu.mappings.ComponentHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Depot.GeneratedStatus;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Station;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.IGui;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 「刷新线路」状态登记与完成通知（客户端）：触发刷新的玩家在发送生成包时登记
 * 开始时刻，界面轮询显示「刷新线路中（已用时 %s）...」，服务端完成推送合并车厂
 * 数据后检测完成并在触发者聊天栏发送成功/失败通知。
 * <p>
 * 完成判定：服务端 {@code lastGeneratedMillis}（生成完成时刻的服务端墙钟）只在
 * 完成瞬间一次性设置（成功/失败都写），生成过程中保持旧值；客户端车厂副本该字段
 * 的唯一更新途径是完成推送（{@code GENERATION_STATUS_UPDATE → PacketUpdateData →
 * UpdateDataResponse.write}，GET_DATA 轮询对已知车厂只回 ID 不刷新）——因此登记时
 * 记录副本初始值、检测到值变化即视为本次生成完成。注意不能用「服务端时间戳 >=
 * 客户端登记时刻」比较（跨时钟绝对值，远程服务器时钟偏差会让条件永不成立），
 * 这是服务器环境无通知的根因；值变化检测与时钟无关。
 * <p>
 * 聊天消息：客户端包处理在 netty 线程，必须 {@code Minecraft.getInstance().execute}
 * 切回主线程；1.18.2 用 {@code displayClientMessage}，1.19+ 用 {@code sendSystemMessage}
 * （参照 {@link com.fangsu.events.JoinInMessage} 模板）。
 */
public final class PathGenerationStatusManager {

    /** 残留记录（depot 被删/服务端无响应/推送丢失）超过该时长自动清理，防泄漏。 */
    private static final long STALE_CLEANUP_MS = 30 * 60 * 1000L;

    /** 一次刷新的登记记录。 */
    private static final class GenerationRecord {

        /** 客户端墙钟开始时刻（生成中文案 elapsed 用）。 */
        final long startTime;

        /** 登记时客户端副本的 lastGeneratedMillis（服务端时钟；无副本为 0）。 */
        final long initialLastGeneratedMillis;

        GenerationRecord(long startTime, long initialLastGeneratedMillis) {
            this.startTime = startTime;
            this.initialLastGeneratedMillis = initialLastGeneratedMillis;
        }
    }

    /** 触发刷新的 depotId → 登记记录。 */
    private static final ConcurrentHashMap<Long, GenerationRecord> RECORDS = new ConcurrentHashMap<>();

    private PathGenerationStatusManager() {
    }

    /** 登记一次「刷新线路」开始（C2S 包发送处注入；重复点击更新记录）。 */
    public static void onGenerationStarted(long depotId) {
        final Depot depot = MinecraftClientData.getDashboardInstance().depotIdMap.get(depotId);
        final long initialLastGeneratedMillis = depot == null ? 0 : depot.getLastGeneratedMillis();
        RECORDS.put(depotId, new GenerationRecord(System.currentTimeMillis(), initialLastGeneratedMillis));
    }

    /** 该车厂是否处于本次方速登记的刷新中（界面生成中文案判定）。 */
    public static boolean isGenerating(long depotId) {
        return RECORDS.containsKey(depotId);
    }

    public static long getStartTime(long depotId) {
        final GenerationRecord record = RECORDS.get(depotId);
        return record == null ? 0 : record.startTime;
    }

    /** legacy 颜色码：站名/车厂名黄、成功绿、失败红、重置（聊天栏与仪表盘共用）。 */
    private static final String YELLOW = ChatFormatting.YELLOW.toString();
    private static final String GREEN = ChatFormatting.GREEN.toString();
    private static final String RED = ChatFormatting.RED.toString();
    private static final String RESET = ChatFormatting.RESET.toString();

    /**
     * 仪表盘（EditDepotScreen）状态文本：生成中/成功/失败均按通知格式
     * 「[黄]车厂名 [状态]」，失败带原因行与 A/B 段行；多行以 {@code |} 分隔
     * （原版 render 按 {@code \\|} 分行绘制，文案中不能含 {@code |}）。
     * 颜色用 § 码内嵌：原版绘制颜色固定 ARGB_WHITE，§ 码可覆盖之。
     * 状态未知（从未生成）返回 original 保留原版文本。
     */
    public static String getDashboardText(Depot depot, String original) {
        final long depotId = depot.getId();
        if (isGenerating(depotId)) {
            // 车厂名黄；§r 重置后再拼刷新中文案（否则 §e 会延续整行）
            return YELLOW + depot.getName() + " " + RESET
                    + ComponentHelper.translatable("gui.fangsu.depot.refreshing",
                            formatElapsed(System.currentTimeMillis() - getStartTime(depotId))).getString();
        }
        final GeneratedStatus status = depot.getLastGeneratedStatus();
        if (status == GeneratedStatus.NONE) {
            return original; // 从未生成：原版返回空串
        }
        final StringBuilder sb = new StringBuilder(YELLOW).append(depot.getName()).append(" ");
        if (status == GeneratedStatus.SUCCESSFUL) {
            return sb.append(GREEN)
                    .append(ComponentHelper.translatable("msg.fangsu.depot.refresh_success").getString())
                    .toString();
        }
        sb.append(RED).append(ComponentHelper.translatable("msg.fangsu.depot.refresh_failed").getString())
                .append("|").append(RED).append(ComponentHelper.translatable(reasonKey(status)).getString());
        if (status == GeneratedStatus.PATH_NOT_FOUND) {
            // 失败段两端平台 id → 站名（聊天栏与仪表盘同款）
            depot.getFailedPlatformIds((startId, endId) -> sb.append("|").append(betweenLine(stationName(startId), stationName(endId))),
                    failedSidingCount -> {
                        // 侧线无法接入主路径：无起止平台，保持原因行即可
                    });
        }
        return sb.toString();
    }

    /** 失败原因 key（NONE/SUCCESSFUL 之外的兜底）。 */
    private static String reasonKey(GeneratedStatus status) {
        switch (status) {
            case NO_SIDINGS:
                return "gui.fangsu.depot.fail.no_sidings";
            case TWO_PLATFORMS_REQUIRED:
                return "gui.fangsu.depot.fail.two_platforms";
            case PATH_NOT_FOUND:
                return "gui.fangsu.depot.fail.path_not_found";
            default:
                return "gui.fangsu.depot.fail.generic";
        }
    }

    /**
     * 「&gt; 在 A 与 B 之间找不到路径」行（§ 码着色：整行红、A/B 黄）。
     * 参数带 § 码的 literal 经 translatable %s 替换后保留样式码，行首
     * {@code §c} 使翻译文本部分回到红色。
     */
    private static String betweenLine(String a, String b) {
        return RED + "> " + ComponentHelper.translatable("msg.fangsu.depot.fail.between",
                ComponentHelper.literal(YELLOW + a + RED),
                ComponentHelper.literal(YELLOW + b + RED)).getString();
    }

    /**
     * 由 {@link com.fangsu.mixin.UpdateDataResponseMixin} 在客户端数据合并后调用：
     * 车厂副本 {@code lastGeneratedMillis} 与登记时初始值不同（且非 0，排除从未生成）
     * → 本次生成完成，按生成状态发成功/失败聊天通知并移除记录。值变化检测与
     * 客户端/服务端时钟偏差无关（完成推送是副本该字段的唯一更新途径）。
     */
    public static void checkGenerationFinished(Data data) {
        if (RECORDS.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        RECORDS.forEach((depotId, record) -> {
            final Depot depot = data.depotIdMap.get(depotId);
            if (depot != null) {
                final long lastGeneratedMillis = depot.getLastGeneratedMillis();
                if (lastGeneratedMillis > 0 && lastGeneratedMillis != record.initialLastGeneratedMillis) {
                    RECORDS.remove(depotId);
                    Main.debug("Depot path generation finished for depot {} (status {})",
                            depotId, depot.getLastGeneratedStatus());
                    notifyResult(depot);
                    return;
                }
            }
            if (now - record.startTime > STALE_CLEANUP_MS) {
                RECORDS.remove(depotId);
            }
        });
    }

    /** 已用时格式化：最多两个单位（如「10分30秒」/ "1 h 05 m"），界面与通知共用。 */
    public static String formatElapsed(long elapsedMs) {
        final long seconds = Math.max(0, elapsedMs) / 1000;
        final long hours = seconds / 3600;
        final long minutes = (seconds % 3600) / 60;
        final long secs = seconds % 60;
        if (hours > 0) {
            return ComponentHelper.translatable("gui.fangsu.time.hours", hours).getString()
                    + ComponentHelper.translatable("gui.fangsu.time.minutes", minutes).getString();
        }
        if (minutes > 0) {
            return ComponentHelper.translatable("gui.fangsu.time.minutes", minutes).getString()
                    + ComponentHelper.translatable("gui.fangsu.time.seconds", secs).getString();
        }
        return ComponentHelper.translatable("gui.fangsu.time.seconds", secs).getString();
    }

    /**
     * 通知结构（多行，触发者聊天栏）：
     * <pre>
     * 成功：[黄]车厂名 [绿]线路刷新成功！
     * 失败：[黄]车厂名 [红]线路刷新失败：
     *       [红]路径未找到
     *       [红]> 在 [黄]A [红]与 [黄]B [红] 之间找不到路径
     * </pre>
     * 子组件各自 withStyle 后 append 组装（append 保留子组件自身样式）；translatable
     * 的 %s 参数可传带样式的 Component，嵌入时保留其样式。
     */
    private static void notifyResult(Depot depot) {
        final MutableComponent message = ComponentHelper.empty()
                .append(ComponentHelper.literal(depot.getName()).withStyle(ChatFormatting.YELLOW))
                .append(ComponentHelper.literal(" ").withStyle(ChatFormatting.YELLOW));
        final GeneratedStatus status = depot.getLastGeneratedStatus();
        if (status == GeneratedStatus.SUCCESSFUL) {
            message.append(ComponentHelper.translatable("msg.fangsu.depot.refresh_success").withStyle(ChatFormatting.GREEN));
        } else {
            message.append(ComponentHelper.translatable("msg.fangsu.depot.refresh_failed").withStyle(ChatFormatting.RED));
            final String reasonKey;
            switch (status) {
                case NO_SIDINGS:
                    reasonKey = "gui.fangsu.depot.fail.no_sidings";
                    break;
                case TWO_PLATFORMS_REQUIRED:
                    reasonKey = "gui.fangsu.depot.fail.two_platforms";
                    break;
                case PATH_NOT_FOUND:
                    reasonKey = "gui.fangsu.depot.fail.path_not_found";
                    break;
                default:
                    reasonKey = "gui.fangsu.depot.fail.generic";
                    break;
            }
            message.append(ComponentHelper.literal("\n"))
                    .append(ComponentHelper.translatable(reasonKey).withStyle(ChatFormatting.RED));
            if (status == GeneratedStatus.PATH_NOT_FOUND) {
                // 失败段详情：TSC 记录 lastGeneratedFailedStartId/EndId（平台 id）时回调
                depot.getFailedPlatformIds((startId, endId) -> message.append(ComponentHelper.literal("\n"))
                                .append(ComponentHelper.literal("> ").withStyle(ChatFormatting.RED))
                                .append(ComponentHelper.translatable("msg.fangsu.depot.fail.between",
                                        ComponentHelper.literal(stationName(startId)).withStyle(ChatFormatting.YELLOW),
                                        ComponentHelper.literal(stationName(endId)).withStyle(ChatFormatting.YELLOW)).withStyle(ChatFormatting.RED)),
                        failedSidingCount -> {
                            // 侧线无法接入主路径：无起止平台，保持原因行即可
                        });
            }
        }
        sendChatMessage(message);
    }

    /** 平台 id → 站名（与 mtr4 EditDepotScreen.getStation 同款，未命名站由 formatStationName 兜底）。 */
    private static String stationName(long platformId) {
        final Platform platform = MinecraftClientData.getDashboardInstance().platformIdMap.get(platformId);
        final Station station = platform == null ? null : platform.area;
        return station == null ? "" : IGui.formatStationName(station.getName());
    }

    private static void sendChatMessage(Component message) {
        // 客户端包处理在 netty 线程，聊天消息必须在主线程发送
        Minecraft.getInstance().execute(() -> {
            final Player player = ClientHooks.getLocalPlayer();
            if (player == null) {
                return;
            }
            //#if MC_VERSION >= 11900
            player.sendSystemMessage(message);
            //#else
            //$$ player.displayClientMessage(message, false);
            //#endif
        });
    }
}
