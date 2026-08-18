package com.fangsu.utils;

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
 * 开始时间，界面轮询显示「刷新线路中（已用时 %s）...」，服务端回包合并车厂数据后
 * 检测完成并在触发者聊天栏发送成功/失败通知。
 * <p>
 * 完成判定复用原版判定：车厂 {@code getLastGeneratedMillis()}（生成完成时刻的服务端
 * 墙钟）晚于登记时刻即视为本次生成完成；{@code lastGeneratedMillis == 0} 表示从未
 * 生成（TSC 默认值），不误判。注意远程服务器与客户端时钟偏差可能让判定失效——
 * 与原版 {@code DEPOT_GENERATION_START_TIME} 同款假设（原版也是靠这个时间戳区分
 * 生成中/已完成），30 分钟残留清理兜底，最坏情况只是通知不出现，不误报。
 * <p>
 * 聊天消息：客户端包处理在 netty 线程，必须 {@code Minecraft.getInstance().execute}
 * 切回主线程；1.18.2 用 {@code displayClientMessage}，1.19+ 用 {@code sendSystemMessage}
 * （参照 {@link com.fangsu.events.JoinInMessage} 模板）。
 */
public final class PathGenerationStatusManager {

    /** 残留记录（depot 被删/服务端无响应/时钟偏差）超过该时长自动清理，防泄漏。 */
    private static final long STALE_CLEANUP_MS = 30 * 60 * 1000L;

    /** 触发刷新的 depotId → 客户端墙钟开始时刻。 */
    private static final ConcurrentHashMap<Long, Long> START_TIMES = new ConcurrentHashMap<>();

    private PathGenerationStatusManager() {
    }

    /** 登记一次「刷新线路」开始（C2S 包发送处注入；重复点击只更新开始时刻）。 */
    public static void onGenerationStarted(long depotId) {
        START_TIMES.put(depotId, System.currentTimeMillis());
    }

    /** 该车厂是否处于本次方速登记的刷新中（界面生成中文案判定）。 */
    public static boolean isGenerating(long depotId) {
        return START_TIMES.containsKey(depotId);
    }

    public static long getStartTime(long depotId) {
        return START_TIMES.getOrDefault(depotId, 0L);
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
     * 车厂 {@code lastGeneratedMillis} 更新过 → 按生成状态发成功/失败聊天通知并移除记录。
     */
    public static void checkGenerationFinished(Data data) {
        if (START_TIMES.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        START_TIMES.forEach((depotId, startTime) -> {
            final Depot depot = data.depotIdMap.get(depotId);
            if (depot != null) {
                final long lastGeneratedMillis = depot.getLastGeneratedMillis();
                if (lastGeneratedMillis >= startTime && lastGeneratedMillis > 0) {
                    START_TIMES.remove(depotId);
                    notifyResult(depot);
                    return;
                }
            }
            if (now - startTime > STALE_CLEANUP_MS) {
                START_TIMES.remove(depotId);
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
