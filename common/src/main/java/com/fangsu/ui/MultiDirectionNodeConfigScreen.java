package com.fangsu.ui;

import com.fangsu.Main;
import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import com.fangsu.mappings.ComponentHelper;
import com.fangsu.util.NodeConnector;
import com.fangsu.utils.GraphicContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketUpdateData;
import org.mtr.mod.packet.PacketUpdateLastRailStyles;
import org.mtr.mod.screen.RailStyleSelectorScreen;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 万向节点配置界面（扳手右键打开），取代旧的全屏 {@code NodeAngleScreen}。
 * <p>
 * 界面基于仓库通用的可滚动配置框架 {@link BasicConfigScreen}，但采用<b>双列布局</b>：
 * <ul>
 *   <li>左列 = 平移（X / Y / Z 偏移）</li>
 *   <li>右列 = 旋转（俯仰角 / 方向）与「旋转绑定」开关</li>
 *   <li>两列下方 = 轨道编辑（占满两列宽度）：逐轨道超高编辑入口 / 轨道形状 / 半径 / 样式</li>
 *   <li>顶部输入模式切换与底部「保存并退出」按钮横跨两列</li>
 * </ul>
 * 双列是为了避免单列纵向堆叠导致的频繁滚动。
 * <p>
 * <b>翻滚角一行在本面板保留；外轨超高开关 / 半轨距两行只在逐轨道界面里编辑。</b>
 * 节点的翻滚角是相连轨道的<b>默认值</b>：没有在 {@link RailTiltConfigScreen} 里按轨道授权过的轨道
 * 自动跟随它（{@code NodeConnector.readRailPose} 把它写进轨道的 {@code roll1Degrees/roll2Degrees}，
 * {@code FangSuRailMath#rollProfileFor} 在轨道<b>没有</b>授权三点剖面时回退到这个两点剖面）；
 * 授权过的轨道以授权值为准，节点值不再参与它的滚转剖面。
 * 因此翻滚角一行与俯仰角一样，改动后必须走 {@link #applyAngles()} → {@link #tryRefreshRails()}
 * 触发一次轨道重建，未授权过的轨道才会拿到新值。
 * 逐轨道超高的三个控制点 + 半轨距在 {@link RailTiltConfigScreen} 里编辑，本面板只保留一个
 * 「编辑」按钮作为入口。
 * <p>
 * <b>角度语义</b>（P3 定义，P4a 起作用于轨道几何）：俯仰角（硬边界 ±60°）正 = 沿节点方向前进时上坡，
 * 会写进 {@code RailPoseExtra} 的纵坡端点、驱动几何内核的三次 Hermite 竖向剖面。
 * 翻滚角（硬边界 ±45°）正 = 前进方向右手侧抬高（外轨超高约定），驱动 {@code 半轨距·|sin(roll)|}
 * 的中心线抬升；逐轨道超高（{@link RailTiltConfigScreen}）优先于它。
 * 节点自身标记模型的倾斜仍由 {@code BlockEntityMultiDirectionNode#applyNodeModelTilt} 负责。
 * <p>
 * <b>两套区间</b>：滑块区间（拖动便利，保持旧值 ±1 格 / [0,180] / ±15° / ±20°）
 * 与硬边界（{@code BlockEntityMultiDirectionNode} 单点定义、服务端强制，分别为 ±2 格 /
 * 回绕 [0,360) / ±60° / ±45°）是两回事。逐轨道超高的两套区间见 {@link RailTiltConfigScreen}。
 * 翻滚角的两套区间与硬边界在这里都是 ±20° / ±45°，与逐轨道倾斜的 ±45° 硬边界一致
 * （{@link BlockEntityMultiDirectionNode#MAX_ROLL_DEG} 与
 * {@code RailPoseExtra#MAX_RAIL_TILT_DEGREES} 同为 45°）。
 * <p>
 * <b>顶部总开关一刀切</b>：面板顶部的按钮切换<b>整块面板</b>的数字输入方式，两种方式<b>互斥</b>：
 * <ul>
 *   <li><b>滑块模式</b>（默认）：每行<b>只有滑块</b>，数值框与就地输入框都不显示；</li>
 *   <li><b>输入模式</b>：每行<b>只有输入框</b>（占满列宽），不构造滑块。</li>
 * </ul>
 * 机制本身在基类 {@link NumericInputConfigScreen} 里，与 {@link RailTiltConfigScreen} 共用同一份实现
 * （两种模式的行高都取 {@link NumericInputConfigScreen#ROW_HEIGHT}，切换时行高不变、其他行不动，
 * 也<b>不改任何数值</b>；同一时刻只允许一行处于编辑态）。
 * <p>
 * <b>写入顺序</b>：{@code BE_SYNC} → {@code NODE_REFRESH_RAIL}。姿态（平移 / 俯仰 / 翻滚 / 开关 / 半轨距）
 * 也随刷新请求一起发送，所以顺序不再影响正确性（见 {@code ModNetwork.handleNodeRefreshRail} 的说明），
 * 这里保持固定顺序只为数据流统一。
 * <p>
 * <b>几何预检</b>：每次改动姿态都会用 {@link NodeConnector#hasValidGeometry} 做一次纯客户端、
 * 不发包的几何预检；预检不通过时显示红字警告并<b>跳过</b>重建请求，避免触发一次注定失败的重建。
 * 预检只看<b>水平</b>姿态（平移 + 方向 + 形状），俯仰 / 翻滚 / 半轨距<b>不</b>参与，
 * 所以它们再极端也不会触发红字或阻断重建（{@link #refreshPoseValidity()} 保持原样）。
 */
public class MultiDirectionNodeConfigScreen extends NumericInputConfigScreen {

    private static final int GAP = 4;

    /**
     * 面板宽度放大系数。两列布局比原单列宽，但不用 2 倍：1.5 倍即可容纳两列控件，
     * 同时避免面板占掉太多屏幕、把世界遮住。
     */
    private static final float PANEL_WIDTH_FACTOR = 1.5f;
    /** 两列之间的间隙（像素）。 */
    private static final int COLUMN_GAP = 6;
    /** 列宽下限：再窄滑块/按钮就点不准了。 */
    private static final int MIN_COLUMN_WIDTH = 60;

    // ==================== 两套区间：滑块区间 vs 硬边界 ====================
    //
    // 本界面刻意区分两个概念，混用它们是「数值框里的值看起来没生效」的根因：
    //
    //   * 硬边界 —— 真正的安全上限，由方块实体
    //     {@link BlockEntityMultiDirectionNode} 单点定义并在服务端强制（setter / readC2S / load
    //     三条入口都会过一遍），客户端这里只是**引用**同一批常量与静态方法来镜像钳制。
    //     超出硬边界的输入会被钳制（方向则是回绕），这是唯一会被真正丢掉信息的边界。
    //   * 滑块区间（*_SLIDER_*）—— 只是「拖动手感」的便利区间，比硬边界窄。
    //     超出滑块区间但未超硬边界的值**照原样保留**：滑块钉在最近的端点，
    //     真值仍然完整地显示在数值框里（见 {@link NumericInputConfigScreen}）。
    //
    // 因此本次改动没有放大任何滑块区间（拖动的手感、可点的范围一律不变），
    // 只是把硬边界放宽，并给每行补上可点击的数值框，让超出滑块范围的值可以键入。
    //
    // 【清理清单 —— 本文件里已经没有调用点的死代码，留给后续清理批次删除】
    // 外轨超高开关 / 半轨距两行留在逐轨道界面（{@link RailTiltConfigScreen}）里之后，
    // 这些成员只剩「被自己引用」：
    //   * 常量 HALF_GAUGE_SLIDER_MIN / HALF_GAUGE_SLIDER_MAX / HALF_GAUGE_STEP；
    //   * 方法 toggleSuperelevation / setHalfGauge / addHalfGaugeRow / formatHalfGauge。
    // 刻意<b>不</b>在本批次删除：它们镜像的 BE 字段（superelevation / halfGaugeM）仍然
    // 参与 BE_SYNC 全量载荷（本界面只是不再改动它们），字段、常量、UI 三者要一起清才安全。
    // 字段 rollDeg 与 ROLL_SLIDER_* / setRoll 都<b>不是</b>死代码：本面板的「翻滚角」一行用它们。
    // 字段 superelevation / halfGaugeM 本身也不是死代码：它们从 BE 读入、原样写回
    // （{@link #applyRailPose()} 与 {@link #save()}），是「不改动节点级回退值」的实现。

    /** 平移步进：1/16 格，与 {@code ObjBlockConfigScreen} 的物件平移一致。 */
    private static final float TRANSLATE_STEP = 0.0625f;
    /** 平移滑块区间（格）。硬边界是 {@link BlockEntityMultiDirectionNode#MAX_OFFSET}（更宽）。 */
    private static final float TRANSLATE_SLIDER_MIN = -1f;
    private static final float TRANSLATE_SLIDER_MAX = 1f;

    /**
     * 方向（Y 旋转）滑块区间（度）。
     * <p>
     * 万向节点支持任意角度，MTR 原版 22.5° 的步进限制在这里不适用，因此步进为 0（连续）。
     * 滑块只用 [0,180] 表示：直线轨道上 180° 与 0° 是同一条线。
     * <p>
     * <b>方向没有硬边界</b>：它是周期量，输入框里键入的值由
     * {@link BlockEntityMultiDirectionNode#wrapDirectionDegrees(double)} <b>回绕</b>到 [0, 360)，
     * 而不是钳制（理由见该方法与 {@link #setDirection}）。
     */
    private static final float DIRECTION_SLIDER_MIN = 0f;
    private static final float DIRECTION_SLIDER_MAX = 180f;
    private static final float DIRECTION_STEP = 0f;

    /**
     * 俯仰角（纵坡）滑块区间（度），步进 0.5°。正值 = 沿节点方向前进时上坡。
     * <p>
     * 滑块保持旧的 ±15°（手感不变）；硬边界是
     * {@link BlockEntityMultiDirectionNode#MAX_PITCH_DEG}（±60°，理由见该常量）。
     */
    private static final float PITCH_SLIDER_MIN = -15f;
    private static final float PITCH_SLIDER_MAX = 15f;
    private static final float PITCH_STEP = 0.5f;

    /**
     * 翻滚角（外轨超高）滑块区间（度），步进 0.5°。正值 = 前进方向右手侧抬高。
     * <p>
     * 滑块保持旧的 ±20°（手感不变）；硬边界是
     * {@link BlockEntityMultiDirectionNode#MAX_ROLL_DEG}（±45°，理由见该常量），
     * 与逐轨道倾斜的硬边界 {@code RailPoseExtra#MAX_RAIL_TILT_DEGREES}（同为 ±45°）一致。
     * 超出滑块区间的值只能从输入框键入（切到输入模式），滑块钉在端点。
     * <p>
     * 本面板的翻滚角是相连轨道的<b>默认值</b>（见类注释）：已按轨道授权的轨道以授权值为准。
     */
    private static final float ROLL_SLIDER_MIN = -20f;
    private static final float ROLL_SLIDER_MAX = 20f;
    private static final float ROLL_STEP = 0.5f;

    /**
     * 半轨距（米）滑块区间与步进。步进 0.005 m = 5 mm：轨距按毫米调整，5 mm 足够精细，
     * 又不会因为太小而拖不准。注意默认值 0.7175 不是 0.005 的整数倍：控件初值原样显示
     * {@code 0.7175}，用户一旦拖动就吸附到 0.005 的网格（例如 0.7200），这是刻意的 ——
     * 默认值要保持「标准轨距的一半」这一精确语义，而手动调整用整齐的毫米网格更方便。
     * <p>
     * 滑块保持旧的 [0.5, 1.0]（步进也一字未动）；硬边界是
     * {@link BlockEntityMultiDirectionNode#MIN_HALF_GAUGE} /
     * {@link BlockEntityMultiDirectionNode#MAX_HALF_GAUGE} 对应的
     * [0.25, 2.0]，同样只能从输入框键入。
     * <p>
     * 【死代码】P4b 起半轨距一行已从本面板移除（逐轨道半轨距在 {@link RailTiltConfigScreen} 里编辑，
     * 区间/步进与这里一致），这三个常量现在只被同为死代码的 {@link #addHalfGaugeRow} /
     * {@link #setHalfGauge(float)} 引用，留给后续清理批次删除。
     */
    private static final float HALF_GAUGE_SLIDER_MIN = 0.5f;
    private static final float HALF_GAUGE_SLIDER_MAX = 1.0f;
    private static final float HALF_GAUGE_STEP = 0.005f;

    // 「单行数值行的控件尺寸」常量（ROW_HEIGHT / SLIDER_HEIGHT / VALUE_BOX_* / SLIDER_MIN_WIDTH /
    // CONTROL_GAP）与整套「滑块 ⇄ 输入框」机制已抽到基类 {@link NumericInputConfigScreen}，
    // 逐轨道超高界面复用同一套，不再有第二份实现。

    /** 半径步进按钮（沿用旧 NodeAngleScreen / 原版 RailModifierScreen 的六档）。 */
    private static final String[] RADIUS_BUTTON_LABELS = {"-10", "-1", "-.1", "+.1", "+1", "+10"};
    private static final double[] RADIUS_BUTTON_STEPS = {-10, -1, -0.1, 0.1, 1, 10};

    private final BlockEntityMultiDirectionNode node;
    /**
     * 本面板当前编辑的那条轨道，也是四个轨道按钮（逐轨道超高编辑 / 形状 / 样式 / 反转）
     * <b>唯一</b>的轨道来源；为 null 时四个按钮一起灰显（node 未连接、且没看向任何轨道）。
     * <p>
     * <b>不是 final</b>：服务端每次应用编辑都会重播轨道，客户端数据里的实例会被换成新的，
     * 因此重建界面时（以及点「逐轨道超高编辑」时）用 {@link #syncRailSnapshot()} 把这里的快照
     * 刷新到新实例 —— 刷新走的仍是同一个 {@link #resolveRail(BlockEntityMultiDirectionNode)}，
     * 不会换来源。
     */
    @Nullable
    private Rail rail;

    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private double direction;
    /**
     * 俯仰角（度，纵坡）。正 = 沿方向前进时上坡。硬边界 ±{@code MAX_PITCH_DEG}（±60°），
     * 滑块区间仍是 ±15°（见 {@link #PITCH_SLIDER_MIN}）。
     * <p>
     * <b>P4a</b>：写进节点后会经 {@code NodeConnector.readRailPose} 进入轨道姿态，
     * 驱动几何内核的三次 Hermite 竖向剖面，因此改动<b>必须</b>触发轨道重建
     * （走 {@link #applyRailPose()} → {@link #tryRefreshRails()}）。
     * 它<b>不</b>参与 {@link #refreshPoseValidity()} 的几何预检（预检只看平移 + 方向 + 形状）。
     */
    private double pitchDeg;
    /**
     * 翻滚角（度，外轨超高）。正 = 前进方向右手侧抬高。重建与预检约束同 {@link #pitchDeg}。
     * 硬边界 ±{@code MAX_ROLL_DEG}（±45°），滑块区间仍是 ±20°（见 {@link #ROLL_SLIDER_MIN}）。
     */
    private double rollDeg;
    /**
     * 外轨超高开关（镜像 BE 的 {@code superelevation}，默认 true）。
     * <p>
     * 只门控<b>滚转</b>对轨道几何的贡献（关 → {@code RailPoseExtra} 的 roll 端点值写 0，
     * 中心线抬升 {@code 半轨距·|sin(roll)|} 消失）；纵坡不受影响。
     * 关闭时「半轨距」一行灰显不可编辑，因为此时它没有任何几何效果。
     */
    private boolean superelevation;
    /** 半轨距（米，镜像 BE 的 {@code rollOffsetM}）：外轨超高抬升中心线的系数。 */
    private double halfGaugeM;
    /**
     * 旋转绑定开关（仅右列，锁定时恒为 true）。
     * <p>
     * 是 → 写方向时用 {@code setDirectionAndBind}（绑定）；否 → 用 {@code setDirectionUnbound}（只写值、不绑定）。
     * 初值取节点当前的 {@code directionBonded}；节点已连接时强制为「是」并锁定（服务端重建轨道依赖绑定方向）。
     */
    private boolean rotationBonded;

    /** 当前编辑的「平移 + 方向」是否无法产出合法轨道（由 {@link #refreshPoseValidity()} 维护）。 */
    private boolean poseInvalid;

    // 「滑块 ⇄ 输入框」总开关与全部数值行状态（useSliderInput / numericFields / valueRows /
    // editingIndex / editingField）已抽到基类 {@link NumericInputConfigScreen}，两者共用同一套机制。

    // ---- 轨道编辑状态 ----
    private Rail.Shape shape;
    private double radius;
    /** 最大纵半径；随 {@link #syncRailSnapshot()} 一起刷新（服务端可能重建过轨道）。 */
    private double maxRadius;
    private EditBox radiusInput;
    private Button[] radiusButtons;

    public MultiDirectionNodeConfigScreen(BlockEntityMultiDirectionNode node) {
        super(ComponentHelper.translatable("ui.fangsu.multi_direction_node.title"));
        this.node = node;
        this.offsetX = node.getOffsetX();
        this.offsetY = node.getOffsetY();
        this.offsetZ = node.getOffsetZ();
        this.direction = node.getDirectionDegrees();
        this.pitchDeg = node.getPitchDegrees();
        this.rollDeg = node.getRollDegrees();
        this.superelevation = node.isSuperelevationEnabled();
        this.halfGaugeM = node.getRollOffsetM();
        // 已连接的节点方向必须保持绑定：开关强制为「是」且不可点击（见 addRotationBindRow）
        this.rotationBonded = node.isDirectionBonded() || node.isConnected();
        this.rail = resolveRail(node);
        applyRailGeometry();
    }

    /**
     * 把 {@code shape / radius / maxRadius} 从当前 {@link #rail} 快照里读出来；
     * {@code rail} 为 null（没看向轨道且节点未连接）时按直线轨（QUADRATIC / 半径 0）处理。
     */
    private void applyRailGeometry() {
        if (rail != null) {
            this.shape = rail.railMath.getShape();
            this.radius = rail.railMath.getVerticalRadius();
            this.maxRadius = rail.railMath.getMaxVerticalRadius();
        } else {
            this.shape = Rail.Shape.QUADRATIC;
            this.radius = 0;
            this.maxRadius = 0;
        }
    }

    /**
     * 重建界面时把轨道快照刷新到客户端数据里的<b>当前实例</b>。
     * <p>
     * 服务端每次应用编辑（形状 / 半径 / 样式 / 逐轨道超高）都会重播轨道，客户端数据里的
     * {@code Rail} 会被换成<b>新实例</b>；本界面若继续持有旧实例，形状 / 样式按钮就会把
     * 过期姿态（例如刚编辑掉的逐轨道超高）写回服务端。因此这里重新解析一次：
     * 只有解析成功且确实换了实例时才刷新，解析失败（数据尚未同步）时保留旧快照，界面不至于整块灰掉。
     * <p>
     * 同一个实例（= 客户端数据没变）时直接返回，避免把用户正在编辑的 shape / radius 覆盖掉。
     */
    private void syncRailSnapshot() {
        final Rail resolved = resolveRail(node);
        if (resolved == null || resolved == rail) {
            return;
        }
        rail = resolved;
        applyRailGeometry();
        Main.debug("[MultiDirectionNode] rail snapshot refreshed at {}", node.getBlockPos());
    }

    /**
     * 解析玩家当前面对的轨道。
     * <p>
     * 旧 {@code NodeAngleScreen} 只在刷子路径里做过这件事，扳手路径传 null 导致轨道编辑永远灰显；
     * 新界面把解析收敛到界面内部，扳手与刷子都能拿到轨道（刷子现在走 MTR 原版界面，见
     * {@code BlockEntityMultiDirectionNode#whenUseWithBrush}）。
     * <p>
     * 顺序：先按视线追踪（与原版 MTR {@code BlockNode.onUse2} 一致），
     * 失败且节点已连接时再从 MTR 客户端数据里取连接到此节点的第一条轨道。
     * 客户端 MTR 数据可能尚未同步，任何异常都只记日志，界面照常打开（轨道编辑灰显）。
     */
    @Nullable
    private static Rail resolveRail(BlockEntityMultiDirectionNode node) {
        try {
            final var railAndBlockPos = MinecraftClientData.getInstance().getFacingRailAndBlockPos(false);
            if (railAndBlockPos != null) {
                return railAndBlockPos.left();
            }
        } catch (Exception e) {
            Main.debug("[MultiDirectionNode] facing rail raycast failed: {}", e.getMessage());
        }
        if (node.isConnected()) {
            try {
                final Map<Position, Rail> connections = MinecraftClientData.getInstance().positionsToRail.get(
                        Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(node.getBlockPos())));
                if (connections != null && !connections.isEmpty()) {
                    return connections.values().iterator().next();
                }
            } catch (Exception e) {
                Main.debug("[MultiDirectionNode] connected rail lookup failed: {}", e.getMessage());
            }
        }
        return null;
    }

    // ==================== 固定控件（不随滚动） ====================

    @Override
    protected void buildFixedWidgets() {
        final int left = getLeftColumnX();
        // 顶部切换与底部保存都横跨两列（宽度 = 两列宽 + 列间距）
        final int width = getColumnsWidth();

        // 整块面板的总开关：两列的所有数值行一起切到另一种输入方式（互斥，机制与标签都在基类里）
        addInputModeToggle(left, 34, width, 20);

        closeButton = addFixedWidget(ComponentHelper.button(left, this.height - 30, width, 20,
                ComponentHelper.translatable("ui.fangsu.block.close_and_save"), btn -> {
                    save();
                    onClose();
                }));
    }

    // ==================== 可滚动内容 ====================

    @Override
    protected void buildScrollableContent(ContentLayout layout) {
        // 重建会销毁全部数值行的控件引用（含可能正在编辑的那一个）：把编辑态收掉、再丢掉上一批行的引用。
        // 这两件事都在基类的 resetNumericRows() 里（见 NumericInputConfigScreen）。
        resetNumericRows();
        // 客户端数据里的轨道可能已被服务端替换成新实例（例如刚从逐轨道超高编辑界面返回），
        // 先同步一次快照，避免形状 / 样式按钮把过期姿态写回服务端
        syncRailSnapshot();
        // 重建界面时同步刷新几何预检结果（输入模式切换、开关翻转、打开界面都会走到这里）
        refreshPoseValidity();

        final int leftX = getLeftColumnX();
        final int rightX = getRightColumnX();
        final int columnWidth = getColumnWidth();
        final int fullWidth = getColumnsWidth();
        final int yTop = layout.y;

        // ---- 两列标题：左列「节点平移」/ 右列「节点旋转」 ----
        addEntry(createTextLabel(leftX + columnWidth / 2, yTop, ComponentHelper.translatable("ui.fangsu.multi_direction_node.translate"), TextLabel.Align.CENTER, 0xFFFFFF, false), yTop);
        addEntry(createTextLabel(rightX + columnWidth / 2, yTop, ComponentHelper.translatable("ui.fangsu.multi_direction_node.rotate"), TextLabel.Align.CENTER, 0xFFFFFF, false), yTop);

        int yLeft = yTop + 12;
        int yRight = yTop + 12;

        // ---- 左列：节点平移（X / Y / Z） ----
        yLeft = addAxisRow(leftX, yLeft, columnWidth, ComponentHelper.translatable("ui.fangsu.multi_direction_node.offset_x"),
                (float) offsetX, TRANSLATE_SLIDER_MIN, TRANSLATE_SLIDER_MAX, TRANSLATE_STEP,
                v -> setOffset(0, v), () -> (float) offsetX, this::applyOffsets);
        yLeft = addAxisRow(leftX, yLeft, columnWidth, ComponentHelper.translatable("ui.fangsu.multi_direction_node.offset_y"),
                (float) offsetY, TRANSLATE_SLIDER_MIN, TRANSLATE_SLIDER_MAX, TRANSLATE_STEP,
                v -> setOffset(1, v), () -> (float) offsetY, this::applyOffsets);
        yLeft = addAxisRow(leftX, yLeft, columnWidth, ComponentHelper.translatable("ui.fangsu.multi_direction_node.offset_z"),
                (float) offsetZ, TRANSLATE_SLIDER_MIN, TRANSLATE_SLIDER_MAX, TRANSLATE_STEP,
                v -> setOffset(2, v), () -> (float) offsetZ, this::applyOffsets);

        // ---- 右列：节点旋转（俯仰 + 方向 + 翻滚）+ 旋转绑定开关 ----
        // 标签按概念命名（俯仰角 / 翻滚角）而不是轴字母：这两个量是铁路语义（纵坡 / 外轨超高），
        // 不是「绕方块 X 轴转」，用轴字母会误导用户与后续维护者。
        // 翻滚角一行在本面板保留：它是相连轨道的<b>默认值</b>，并经 NodeConnector.readRailPose
        // 写进轨道的 roll1Degrees/roll2Degrees（所以走 this::applyAngles 重建轨道）；
        // 已按轨道授权的轨道在 FangSuRailMath.rollProfileFor 里优先用授权三点剖面。
        // 节点的外轨超高开关 / 半轨距仍在 BE 里原样保留、也仍随 BE_SYNC 全量载荷同步，
        // 只是本面板没有它们的编辑入口（在「逐轨道超高」界面里按轨道编辑）。
        yRight = addAxisRow(rightX, yRight, columnWidth, ComponentHelper.translatable("ui.fangsu.multi_direction_node.pitch"),
                (float) pitchDeg, PITCH_SLIDER_MIN, PITCH_SLIDER_MAX, PITCH_STEP,
                v -> setPitch(v), () -> (float) pitchDeg, this::applyAngles);
        yRight = addAxisRow(rightX, yRight, columnWidth, ComponentHelper.translatable("ui.fangsu.multi_direction_node.rotY"),
                (float) direction, DIRECTION_SLIDER_MIN, DIRECTION_SLIDER_MAX, DIRECTION_STEP,
                v -> setDirection(v), () -> (float) direction, this::applyDirection);
        yRight = addAxisRow(rightX, yRight, columnWidth, ComponentHelper.translatable("ui.fangsu.multi_direction_node.roll"),
                (float) rollDeg, ROLL_SLIDER_MIN, ROLL_SLIDER_MAX, ROLL_STEP,
                v -> setRoll(v), () -> (float) rollDeg, this::applyAngles);
        yRight = addRotationBindRow(rightX, yRight, columnWidth);

        // ---- 轨道编辑：两列下方，占满两列宽度 ----
        int y = Math.max(yLeft, yRight) + 4;
        addEntry(createTextLabel(leftX + fullWidth / 2, y, ComponentHelper.translatable("ui.fangsu.multi_direction_node.railSection"), TextLabel.Align.CENTER, 0xFFFFFF, false), y);
        y += 12;

        final boolean hasRail = rail != null;
        if (!hasRail) {
            addEntry(createTextLabel(leftX, y, ComponentHelper.translatable("ui.fangsu.multi_direction_node.noRail"), TextLabel.Align.LEFT, 0xFFAA55, false), y);
            y += 12;
        }

        // 逐轨道超高编辑入口。它是本面板唯一通往「按轨道单独编辑倾斜」的入口；
        // 节点的外轨超高开关 / 半轨距两行已从本面板移除（逐轨道倾斜的三个控制点 + 半轨距
        // 都在那个界面里编辑）。
        // <p>
        // 可点条件与下面三个按钮（轨道形状 / 样式 / 反转）<b>完全同源</b>：都用本面板构造时解析出来的
        // {@link #rail}（见 {@link #resolveRail(BlockEntityMultiDirectionNode)}：先按视线取面对的那条，
        // 取不到且节点已连接时回退到连到本节点的第一条），因此四个按钮要么一起可点、要么一起灰显，
        // 不会再出现「下面三个能点、这一个却因为准星没对准轨道节点而灰着」的矛盾。
        // 点击时同样按这条来源重新解析一次（见 {@link #openRailTiltEditor()}）。
        final Button buttonRailTilt = addButton(leftX, y, fullWidth, 20,
                ComponentHelper.translatable("ui.fangsu.multi_direction_node.editRailTilt"), b -> openRailTiltEditor());
        buttonRailTilt.active = hasRail;
        addEntry(buttonRailTilt, y);
        y += 24;
        // 说明「节点翻滚角 = 默认值」这层关系。刻意拆成两行短句：内容区按面板宽度裁剪
        // （getContentRight），极窄面板下整行宽度只够十来个汉字，长句会被静默截断。
        addEntry(createTextLabel(leftX, y, ComponentHelper.translatable("ui.fangsu.multi_direction_node.rollDefaultHint"),
                TextLabel.Align.LEFT, 0x888888, false), y);
        y += 10;
        addEntry(createTextLabel(leftX, y, ComponentHelper.translatable("ui.fangsu.multi_direction_node.rollAuthoredHint"),
                TextLabel.Align.LEFT, 0x888888, false), y);
        y += 12;

        // 轨道形状切换（QUADRATIC ⇄ TWO_RADII）
        final Component shapeLabel = hasRail && shape == Rail.Shape.TWO_RADII
                ? org.mtr.mod.generated.lang.TranslationProvider.GUI_MTR_RAIL_SHAPE_TWO_RADII.getMutableText().data
                : org.mtr.mod.generated.lang.TranslationProvider.GUI_MTR_RAIL_SHAPE_QUADRATIC.getMutableText().data;
        final Button buttonShape = addButton(leftX, y, fullWidth, 20, shapeLabel, b -> toggleRailShape());
        buttonShape.active = hasRail;
        addEntry(buttonShape, y);
        y += 24;

        // 半径（仅「两个半径」形状时出现，复刻原版 RailModifierScreen 的 clamp/回写逻辑）
        final boolean showRadius = hasRail && shape == Rail.Shape.TWO_RADII;
        if (showRadius) {
            addEntry(createTextLabel(leftX, y, ComponentHelper.translatable("ui.fangsu.multi_direction_node.radius"), TextLabel.Align.LEFT, 0xFFFFFF, false), y);
            y += 10;
            radiusInput = new EditBox(this.font, leftX, y, fullWidth, 20, ComponentHelper.empty());
            radiusInput.setValue(String.valueOf(radius));
            radiusInput.setResponder(text -> {
                try {
                    updateRadius(Double.parseDouble(text.trim()), true);
                } catch (NumberFormatException ignored) {
                }
            });
            addRenderableWidget(radiusInput);
            addEntry(radiusInput, y);
            y += 22;

            // 六个步进按钮排成 3×2：轨道编辑区已占满两列，按钮比旧版单列时更宽
            radiusButtons = new Button[RADIUS_BUTTON_LABELS.length];
            final int buttonWidth = Math.max(16, (fullWidth - 4) / 3);
            for (int i = 0; i < RADIUS_BUTTON_LABELS.length; i++) {
                final int index = i;
                final int buttonX = leftX + (i % 3) * (buttonWidth + 2);
                final int buttonY = y + (i / 3) * 22;
                final Button radiusButton = addButton(buttonX, buttonY, buttonWidth, 20,
                        ComponentHelper.literal(RADIUS_BUTTON_LABELS[i]),
                        b -> updateRadius(radius + RADIUS_BUTTON_STEPS[index], true));
                // 减按钮仅在 radius > 0 时可用，加按钮仅在 radius < maxRadius 时可用（与原版一致）
                radiusButton.active = RADIUS_BUTTON_STEPS[i] < 0 ? radius > 0 : radius < maxRadius;
                radiusButtons[i] = radiusButton;
                addEntry(radiusButton, buttonY);
            }
            y += 46;
        }

        // 编辑样式 / 反转样式（占满两列宽度）
        final Button buttonStyles = addButton(leftX, y, fullWidth, 20,
                org.mtr.mod.generated.lang.TranslationProvider.GUI_MTR_RAIL_STYLES.getMutableText().data, b -> openStyleSelector());
        buttonStyles.active = hasRail;
        addEntry(buttonStyles, y);
        y += 24;

        final Button buttonFlip = addButton(leftX, y, fullWidth, 20,
                org.mtr.mod.generated.lang.TranslationProvider.GUI_MTR_FLIP_STYLES.getMutableText().data, b -> flipStyles());
        buttonFlip.active = hasRail;
        addEntry(buttonFlip, y);
        y += 24;

        // 外轨超高开关与半轨距两行<b>不</b>在本面板恢复（用户只要求恢复翻滚角）：逐轨道倾斜与
        // 半轨距都在「逐轨道超高」界面里按轨道编辑（见 {@link RailTiltConfigScreen}）。
        // 节点级的开关 / 半轨距仍在 BE 里保留并原样同步，供「没有逐轨道超高的轨道」回退使用；
        // 它们现在没有编辑入口，见类注释里的清理清单。
        // 这里刻意不写 BE 的这几个字段：本面板打开时读到的就是当前值，写回是恒等变换，
        // 真正落盘由 {@link #applyRailPose()} / {@link #save()} 负责。
    }

    /**
     * 打开「逐轨道超高」编辑界面，编辑的是<b>本面板那条轨道</b>（{@link #rail}）——
     * 与轨道形状 / 样式 / 反转三个按钮完全同一个来源，四个按钮的可点状态因此永远一致。
     * <p>
     * 点击时先调用一次 {@link #syncRailSnapshot()}：它用的也是 {@link #resolveRail(BlockEntityMultiDirectionNode)}
     * （不会换来源），只是把服务端重播过的新实例换进来，避免抱着过期实例打开界面。
     * 解析不到（{@code rail == null}，此时按钮本身已灰显）时不开界面，只记日志。
     * <p>
     * 打开的是同一个客户端上的新界面（{@code setScreen}），并把本界面作为父界面传下去，
     * 这样新界面的「返回 / 应用」都能回到本面板。
     */
    private void openRailTiltEditor() {
        if (minecraft == null) {
            return;
        }
        syncRailSnapshot();
        if (rail == null) {
            Main.debug("[MultiDirectionNode] rail tilt editor skipped: no rail at {}", node == null ? null : node.getBlockPos());
            return;
        }
        minecraft.setScreen(new RailTiltConfigScreen(rail, this));
    }

    /**
     * 「半轨距」一行：标签 + 米制数值控件（滑块 + 可点击数值框；关闭全局滑块时只有数值框）。
     * <p>
     * 滑块区间 [{@link #HALF_GAUGE_SLIDER_MIN}, {@link #HALF_GAUGE_SLIDER_MAX}]、步进
     * {@link #HALF_GAUGE_STEP}（<b>步进未改</b>，仍是 0.005 m）；硬边界是
     * [{@link BlockEntityMultiDirectionNode#MIN_HALF_GAUGE},
     * {@link BlockEntityMultiDirectionNode#MAX_HALF_GAUGE}]，比滑块区间宽，只能键入。
     * <p>
     * <b>外轨超高开关关闭时整行灰显、不可编辑</b>：半轨距只在外轨超高（滚转抬升）里起作用，
     * 开关关闭时改它没有任何几何效果，留着可点只会误导用户。
     * <p>
     * 灰显用「换成 {@code active = false} 的按钮」，而不是给滑块置 {@code active = false}：
     * {@code SliderWidget.mouseClicked / mouseDragged} 直接转发给内部的 {@code AbstractSliderButton}，
     * 内层仍是 active，外层置 false 既挡不住点击、也不改变内层的绘制颜色；按钮才是真正点不动的控件。
     * 开关翻转会 {@code requestRebuild()}，本行随之在「可编辑」与「灰显」之间切换。
     * <p>
     * 【死代码】P4b 起本面板不再有半轨距一行（{@link #buildScrollableContent} 里已无调用点），
     * 逐轨道半轨距改在 {@link RailTiltConfigScreen} 里编辑；本方法只被 javadoc 引用，留给后续清理批次删除。
     */
    private int addHalfGaugeRow(int areaLeft, int y, int rowWidth, boolean enabled) {
        final Component label = ComponentHelper.translatable("ui.fangsu.multi_direction_node.halfGauge");
        if (!enabled) {
            addEntry(createTextLabel(areaLeft, y, label, TextLabel.Align.LEFT, 0x888888, false), y);
            y += 8;
            final Button disabled = addButton(areaLeft, y, Math.min(rowWidth, 80), 20,
                    ComponentHelper.literal(formatHalfGauge()), b -> {
                    });
            disabled.active = false;
            addEntry(disabled, y);
            return y + ROW_HEIGHT;
        }
        return addAxisRow(areaLeft, y, rowWidth, label, (float) halfGaugeM,
                HALF_GAUGE_SLIDER_MIN, HALF_GAUGE_SLIDER_MAX, HALF_GAUGE_STEP,
                this::setHalfGauge, () -> (float) halfGaugeM, this::applyRailPose);
    }

    /** 灰显态下显示当前半轨距，例如 {@code 0.7175 M}（与滑块模式的数值格式一致，保留 4 位小数）。 */
    private String formatHalfGauge() {
        return String.format("%.4f M", halfGaugeM);
    }

    // ==================== 旋转绑定开关 ====================

    /**
     * 旋转绑定开关（右列）：显示「旋转绑定：是 / 否」。
     * <ul>
     *   <li>是 → 编辑方向会调用 {@code setDirectionAndBind}，方向被绑定（模型固定、相连轨道按该方向重建）；</li>
     *   <li>否 → 只调用 {@code setDirectionUnbound} 写下方向值，{@code directionBonded} 保持 false（模型继续旋转）；</li>
     *   <li>节点<b>已连接</b>时强制为「是」并锁定：服务端重建相连轨道依赖节点绑定方向，此时解绑没有意义。</li>
     * </ul>
     */
    private int addRotationBindRow(int areaLeft, int y, int rowWidth) {
        final Component label = ComponentHelper.translatable(
                "ui.fangsu.multi_direction_node.rotationBind",
                ComponentHelper.translatable(rotationBonded
                        ? "ui.fangsu.multi_direction_node.yes"
                        : "ui.fangsu.multi_direction_node.no"));
        final Button button = addButton(areaLeft, y, rowWidth, 20, label, b -> toggleRotationBind());
        // 已连接 → 锁定为「是」且不可点击（工具提示由标签本身表达）
        button.active = node == null || !node.isConnected();
        addEntry(button, y);
        return y + 24;
    }

    // 「就地编辑状态机」与 FloatGetter / parseFieldValue 也随基类一起抽走了：
    // beginEdit / finishEdit / cancelEdit / commitAllNumericFields 现在都在 NumericInputConfigScreen 里。

    // 数值行控件（NumericRow / ValueButton / NumericField）都在基类 NumericInputConfigScreen 里，
    // 本界面通过 addAxisRow(...) 使用它们。

    // ==================== 节点写入（平移 / 方向 / 角度） ====================

    /**
     * 平移：钳制到硬边界 {@link BlockEntityMultiDirectionNode#MAX_OFFSET}（±2 格）。
     * <p>
     * 滑块区间仍是 ±1 格，所以键入 1.5 会被<b>原样保留</b>（不再被滑块区间吃掉），键入 5 才钳到 2。
     * 钳制只此一处，且与 BE 的 {@code clampOffset} 是同一套数字（客户端镜像）。
     */
    private void setOffset(int axis, float value) {
        final double clamped = BlockEntityMultiDirectionNode.clampOffset(value);
        switch (axis) {
            case 0 -> offsetX = clamped;
            case 1 -> offsetY = clamped;
            default -> offsetZ = clamped;
        }
    }

    /**
     * 方向：<b>回绕</b>而不是钳制。
     * <p>
     * 滑块仍只用 [0,180] 表示，而滑块拖到端点时若取模会立刻跳回另一端、与滑块自身位置不一致，
     * 所以滑块给出的值本来就在区间内，回绕对它是恒等变换（既有手感不变）；
     * 而输入框键入 270 / -90 / 450 这类值时，钳制会把它们错压成 180 / 0 / 180（完全是别的方向），
     * 只有回绕才保真。回绕用的是 BE 的同一个方法 {@code wrapDirectionDegrees}，
     * 因此客户端显示值与服务端存储值一致。
     */
    private void setDirection(float value) {
        direction = BlockEntityMultiDirectionNode.wrapDirectionDegrees(value);
    }

    /** 俯仰角：钳制到硬边界 {@link BlockEntityMultiDirectionNode#MAX_PITCH_DEG}（±60°，理由见该常量）。 */
    private void setPitch(float value) {
        pitchDeg = BlockEntityMultiDirectionNode.clampPitch(value);
    }

    /**
     * 翻滚角：钳制到硬边界 {@link BlockEntityMultiDirectionNode#MAX_ROLL_DEG}（±45°，与逐轨道倾斜的
     * {@code RailPoseExtra#MAX_RAIL_TILT_DEGREES} 一致）。
     * <p>
     * 它是相连轨道的<b>默认值</b>（见类注释）：改动后由 {@link #applyAngles()} 触发一次轨道重建，
     * 未按轨道授权过的轨道才会拿到新值；授权过的轨道以授权三点剖面为准，不受本次改动影响。
     */
    private void setRoll(float value) {
        rollDeg = BlockEntityMultiDirectionNode.clampRoll(value);
    }

    /**
     * 半轨距（米）：钳制到硬边界
     * [{@link BlockEntityMultiDirectionNode#MIN_HALF_GAUGE}, {@link BlockEntityMultiDirectionNode#MAX_HALF_GAUGE}]
     * （[0.25, 2.0]），并与 BE 的 {@code clampHalfGauge} 走同一条钳制（NaN / 无穷 → 标准轨距的一半）。
     * 滑块区间仍是 [0.5, 1.0]、步进仍是 0.005 m（一字未改），因此放大后的区间同样只能键入。
     * <p>
     * 【死代码】P4b 起本面板不再有半轨距一行，本方法只被同为死代码的 {@link #addHalfGaugeRow} 引用。
     */
    private void setHalfGauge(float value) {
        halfGaugeM = BlockEntityMultiDirectionNode.clampHalfGauge(value);
    }

    /**
     * 翻转外轨超高开关：立即写 BE + 重建轨道，并重建界面。
     * <p>
     * 重建界面是必须的：开关只门控滚转，翻转后「半轨距」一行要在可编辑 / 灰显之间切换，
     * 而控件是在 {@link #buildScrollableContent} 里按当前开关状态构造的。
     * <p>
     * 【死代码】P4b 起本面板不再有外轨超高开关一行，本方法已无调用点。
     * 注意：现在「翻转开关」这件事<b>没有</b> UI 入口了，但开关本身仍在 BE 里、仍随载荷同步
     * （见类注释的清理清单），对没有逐轨道超高的轨道照常生效。
     */
    private void toggleSuperelevation() {
        superelevation = !superelevation;
        applyRailPose();
        requestRebuild();
    }

    /**
     * 俯仰 / 翻滚实时写入。现在直接走 {@link #applyRailPose()}（写 BE → BE_SYNC → 预检 → 重建）。
     * <p>
     * <b>P4a</b>：这两个角度会经 {@code NodeConnector.readRailPose} 进入轨道姿态
     * （俯仰 → 内核的三次 Hermite 纵坡剖面；翻滚 → {@code 半轨距·|sin(roll)|} 中心线抬升），
     * 轨道几何真的会变，所以 P3 时期「刻意不重建」的理由已经不成立，本方法必须触发重建。
     * <p>
     * <b>P4c</b>：本面板的俯仰角与翻滚角两行都调用它（翻滚角一行已恢复）；
     * 外轨超高开关 / 半轨距没有编辑入口，是「从 BE 读入、原样写回」的透传值。
     * <p>
     * 与平移 / 方向一致：即时保存（不点「保存并退出」也生效）。
     */
    private void applyAngles() {
        applyRailPose();
    }

    /**
     * 轨道姿态（俯仰 / 翻滚 / 外轨超高开关 / 半轨距）的整体写入路径，与
     * {@link #applyOffsets()} / {@link #applyDirection()} 完全同构：
     * <ol>
     *   <li>写 BE 数据（四个 setter 都是纯数据 + {@code setChanged()}，不发包）；</li>
     *   <li>用 {@code setAnglesAndSync} 发一次 BE_SYNC —— v4 载荷是全量
     *       （方向 + 绑定 + 平移 + 俯仰 / 翻滚 + 开关 + 半轨距），所以开关与半轨距也一起同步过去；</li>
     *   <li>{@link #tryRefreshRails()}：先几何预检再重建。预检只看平移 + 方向 + 形状
     *       （{@code refreshPoseValidity} → {@code NodeConnector.hasValidGeometry}），
     *       俯仰 / 翻滚 / 半轨距<b>不</b>参与，因此这些值再极端也不会触发红字或阻断重建。</li>
     * </ol>
     */
    private void applyRailPose() {
        if (node == null) return;
        // 先写开关与半轨距（纯数据），最后 setAnglesAndSync 发的那一个包才带得上它们（载荷是全量）
        node.setSuperelevation(superelevation);
        node.setRollOffsetM(halfGaugeM);
        node.setAnglesAndSync(pitchDeg, rollDeg);
        tryRefreshRails();
    }

    /**
     * 平移实时写入。顺序：写平移（本地）→ 写方向并发 BE_SYNC → 预检通过才请求重建轨道。
     * <p>
     * {@code writeC2S} 的载荷包含方向 + 绑定标志 + 三个平移分量，所以这里只需要一次 BE_SYNC
     * （{@code setNodeOffset} 只写本地数据，不发包）。
     * <p>
     * <b>平移绝不隐式绑定旋转</b>：开关为「否」时用 {@code setDirectionUnbound} 只写值；
     * 只有开关为「是」才用 {@code setDirectionAndBind}。这就是「拖一下节点就把方向绑死」的修复点。
     * 与 {@code ObjBlockConfigScreen} 一样是即时保存（不点按钮也生效），因此拖动滑块会逐步触发重建；
     * 万向节点相连轨道通常只有 1~2 条，该开销可接受。
     */
    private void applyOffsets() {
        if (node == null) return;
        node.setNodeOffset(offsetX, offsetY, offsetZ);
        writeDirection();
        tryRefreshRails();
    }

    /**
     * 方向实时写入，顺序与旧 {@code NodeAngleScreen#saveAndClose} 一致：
     * 先写 BE 并发 BE_SYNC，再请求重建相连轨道（服务端从刷新包里读方向与姿态）。
     */
    private void applyDirection() {
        if (node == null) return;
        writeDirection();
        tryRefreshRails();
    }

    /**
     * 按「旋转绑定」开关写方向并发一次 BE_SYNC：是 → 绑定；否 → 只写值、保持未绑定。
     * <p>
     * {@code setDirectionAndBind} 只做本地 setChanged（不发包），所以「是」分支补一次 BE_SYNC；
     * {@code setDirectionUnbound} 内部已含 setChanged + sendUpdateC2S。
     */
    private void writeDirection() {
        if (node == null) return;
        if (rotationBonded) {
            node.setDirectionAndBind(direction);
            node.sendUpdateC2S();
        } else {
            // 未绑定：只写方向值，directionBonded 保持不变（C2S 载荷布局不变）
            node.setDirectionUnbound(direction);
        }
    }

    /** 翻转「旋转绑定」开关：切到「否」立即解绑；已连接的节点锁定为「是」。 */
    private void toggleRotationBind() {
        if (node == null) return;
        if (node.isConnected()) {
            // 已连接 → 锁定为「是」，点击不生效
            rotationBonded = true;
            requestRebuild();
            return;
        }
        rotationBonded = !rotationBonded;
        if (rotationBonded) {
            node.setDirectionAndBind(direction);
            node.sendUpdateC2S();
        } else {
            // 解绑但保留当前方向值：只动方向绑定标志（C2S 载荷布局不变）
            node.setRotationBonded(false);
        }
        requestRebuild();
    }

    /**
     * 几何预检 + 条件重建：姿态合法才发送 {@code NODE_REFRESH_RAIL}。
     * <p>
     * 非法姿态下服务端建不出轨道，旧实现会把旧轨道先删掉再失败（轨道消失）；现在服务端本身也是
     * 「先校验后删除」，客户端这一层再挡一次，连删除尝试都不会发生。
     * 预检每帧重算很贵，所以只在姿态变化 / 界面重建时调用 {@link #refreshPoseValidity()}。
     */
    private void tryRefreshRails() {
        if (node == null) return;
        refreshPoseValidity();
        if (poseInvalid) {
            Main.debug("[MultiDirectionNode] refresh skipped: edited pose cannot build a valid rail at {}", node.getBlockPos());
            return;
        }
        node.refreshConnectedRailsIfNeeded();
    }

    /**
     * 关闭前兜底：把方向、平移与轨道姿态整体再写一次，避免切换输入模式等重建过程丢失最后一次输入。
     * <p>
     * 顺序仍是 BE_SYNC → NODE_REFRESH_RAIL；姿态非法时同样跳过重建（旧轨道保持不动）。
     * P4a 起俯仰 / 翻滚 / 外轨超高开关 / 半轨距也在这里落盘（它们都会进入轨道姿态）。
     * P4c 起俯仰与翻滚在本面板有编辑入口（翻滚角一行已恢复）；开关 / 半轨距在本面板<b>没有</b>编辑入口，
     * 这里写回的是打开界面时从 BE 读到的同一个值（透传，保证 BE_SYNC 全量载荷完整），
     * 不会把用户没编辑过的东西改掉。
     */
    private void save() {
        if (node == null) return;
        // 输入框里可能还有未提交的文本（用户没按回车就点了保存）：先统一提交，再整体落盘
        commitAllNumericFields();
        node.setNodeOffset(offsetX, offsetY, offsetZ);
        // P4a：俯仰 / 翻滚 + 外轨超高开关 + 半轨距一起兜底落盘（纯数据）；
        // 全量 BE_SYNC 由下面的 writeDirection() 发出（v4 载荷包含这四个字段）。
        node.setNodeAngles(pitchDeg, rollDeg);
        node.setSuperelevation(superelevation);
        node.setRollOffsetM(halfGaugeM);
        writeDirection();
        tryRefreshRails();
        Main.debug("[MultiDirectionNode] node config saved at {}", node.getBlockPos());
    }

    // ==================== 几何预检（客户端、不发包） ====================

    /**
     * 重新计算「当前编辑的姿态（平移 + 方向）能否产出合法轨道」，结果供红字警告与重建门控使用。
     * <p>
     * 逐个相连端点调用 {@link NodeConnector#hasValidGeometry}（纯计算：只构造候选 {@code Rail}，
     * 不发包、不改世界）；只要有一条端点能建成就认为有效。没有相连轨道、客户端 MTR 数据未同步、
     * 或节点本身为空时按「有效」处理——那些情况下本来也不会发出重建请求，不该误报红色。
     */
    private void refreshPoseValidity() {
        poseInvalid = false;
        if (node == null) return;
        final Level level = node.getLevel();
        if (level == null) return;
        final List<BlockPos> others;
        try {
            others = NodeConnector.findConnectedEndpoints(node.getBlockPos());
        } catch (Exception e) {
            Main.debug("[MultiDirectionNode] pose validation skipped: {}", e.getMessage());
            return;
        }
        if (others.isEmpty()) return;
        final double[] offset = {offsetX, offsetY, offsetZ};
        for (final BlockPos other : others) {
            try {
                if (NodeConnector.hasValidGeometry(level, node.getBlockPos(), offset, direction, other,
                        NodeConnector.getDirectionDegrees(level, other), shape)) {
                    return;
                }
            } catch (Exception e) {
                // 单个端点计算异常（数据半同步）时按有效处理，避免误报
                Main.debug("[MultiDirectionNode] pose validation failed for {}: {}", other, e.getMessage());
                return;
            }
        }
        poseInvalid = true;
        Main.debug("[MultiDirectionNode] edited pose is invalid for every connected endpoint at {}", node.getBlockPos());
    }

    // ==================== 轨道编辑（照旧 NodeAngleScreen 移植） ====================

    private void toggleRailShape() {
        if (rail == null) return;
        shape = shape == Rail.Shape.QUADRATIC ? Rail.Shape.TWO_RADII : Rail.Shape.QUADRATIC;
        radius = Utilities.clamp(Utilities.round(radius, 2), 0, maxRadius);
        sendRailPacket(Rail.copy(rail, shape, radius));
        // 重建界面以更新形状标签、半径行与按钮可用性
        requestRebuild();
    }

    private void openStyleSelector() {
        if (rail == null) return;
        org.mtr.mapping.holder.MinecraftClient.getInstance().openScreen(
                new org.mtr.mapping.holder.Screen(RailStyleSelectorScreen.create(rail))
        );
    }

    private void flipStyles() {
        if (rail == null) return;
        final ObjectArrayList<String> styles = rail.getStyles().stream().map(style -> {
            final boolean isForwards = style.endsWith("_1");
            final boolean isBackwards = style.endsWith("_2");
            if (isForwards || isBackwards) {
                return style.substring(0, style.length() - 1) + (isForwards ? "2" : "1");
            } else {
                return style;
            }
        }).collect(Collectors.toCollection(ObjectArrayList::new));
        sendRailPacket(Rail.copy(rail, styles));
        if (minecraft != null && minecraft.player != null) {
            InitClient.REGISTRY_CLIENT.sendPacketToServer(
                    new PacketUpdateLastRailStyles(minecraft.player.getUUID(), rail.getTransportMode(), styles)
            );
        }
    }

    /**
     * 派发轨道更新包。{@code Rail.copy} 会保留 FangSu 附加姿态（由 RailMixin 负责），
     * 因此这里不需要额外搬运姿态数据。
     */
    private void sendRailPacket(Rail updatedRail) {
        InitClient.REGISTRY_CLIENT.sendPacketToServer(
                new PacketUpdateData(new UpdateDataRequest(MinecraftClientData.getInstance()).addRail(updatedRail))
        );
    }

    /** 更新半径并派发包，复刻原版 RailModifierScreen.update 的 clamp / 回写 / 发包逻辑。 */
    private void updateRadius(double newRadius, boolean sendPacket) {
        if (rail == null) return;
        radius = Utilities.clamp(Utilities.round(newRadius, 2), 0, maxRadius);
        // 同步输入框文本（仅当值不一致时回写，避免触发 responder 死循环）
        if (radiusInput != null) {
            try {
                if (Double.parseDouble(radiusInput.getValue()) != radius) {
                    radiusInput.setValue(String.valueOf(radius));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        // 按钮可用性随边界变化：减按钮 radius > 0，加按钮 radius < maxRadius
        if (radiusButtons != null) {
            for (int i = 0; i < radiusButtons.length; i++) {
                radiusButtons[i].active = RADIUS_BUTTON_STEPS[i] < 0 ? radius > 0 : radius < maxRadius;
            }
        }
        if (sendPacket) {
            sendRailPacket(Rail.copy(rail, shape, radius));
        }
    }

    // ==================== 站台 / 侧线警告 ====================

    /**
     * 本节点是否参与站台 / 侧线轨道。
     * <p>
     * 站台/侧线的几何（停靠点、屏蔽门对齐）仍按整格对齐，节点平移后可能出现视觉错位，
     * 因此界面在 {@code |offset| > 0} 且命中这些轨道时给一行红字警告。
     * 优先看刷子选中的轨道，其次回退到 MTR 客户端数据里连到本节点的所有轨道。
     */
    private boolean hasPlatformOrSidingRail() {
        if (rail != null && (rail.isPlatform() || rail.isSiding())) {
            return true;
        }
        try {
            final Position nodePosition = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(node.getBlockPos()));
            final Map<Position, Rail> connections = MinecraftClientData.getInstance().positionsToRail.get(nodePosition);
            if (connections != null) {
                for (final Rail connected : connections.values()) {
                    if (connected.isPlatform() || connected.isSiding()) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // 客户端 MTR 数据尚未同步：静默跳过，不因为警告而打断界面
        }
        return false;
    }

    /** 平移是否非零。 */
    private boolean hasNonZeroOffset() {
        return offsetX != 0.0D || offsetY != 0.0D || offsetZ != 0.0D;
    }

    // ==================== 渲染 ====================

    // 「点击先把正在编辑的那一行提交掉」的 mouseClicked 覆写在基类 NumericInputConfigScreen 里。

    //#if MC_VERSION >= 12000
    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GraphicContext g = GraphicContext.of(graphics);
        //#else
        //$$ @Override
        //$$ public void render(com.mojang.blaze3d.vertex.PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        //$$     GraphicContext g = GraphicContext.of(poseStack);
        //#endif
        // 标题画在面板顶部（BasicConfigScreen 不画标题，ObjBlockConfigScreen 也是自己画）
        final int titleX = (getPanelLeft() + getPanelRight()) / 2 - this.font.width(this.title.getString()) / 2;
        g.drawString(this.font, this.title, titleX, 2, 0xFFFFFF, false);

        super.render(g.asMinecraft(), mouseX, mouseY, partialTick);

        // 警告画在滚动裁剪区之外（否则会被 getContentRight() 截断），堆叠在保存按钮上方
        int warningY = this.height - 42;
        if (poseInvalid) {
            final Component warning = ComponentHelper.translatable("ui.fangsu.multi_direction_node.invalidGeometry");
            final int warningX = (getPanelLeft() + getPanelRight()) / 2 - this.font.width(warning.getString()) / 2;
            g.drawString(this.font, warning, warningX, warningY, 0xFF5555, false);
            warningY -= 10;
        }
        if (hasNonZeroOffset() && hasPlatformOrSidingRail()) {
            final Component warning = ComponentHelper.translatable("ui.fangsu.multi_direction_node.offset_warning");
            final int warningX = (getPanelLeft() + getPanelRight()) / 2 - this.font.width(warning.getString()) / 2;
            g.drawString(this.font, warning, warningX, warningY, 0xFF5555, false);
        }
    }

    // ==================== 面板布局（双列 + 1.5 倍宽） ====================

    @Override
    protected int getPanelLeft() {
        return GAP;
    }

    /**
     * 面板右边界。<b>总宽度 = 原单列宽度的 1.5 倍</b>。
     * <p>
     * “原单列宽度”取的是本模组既有配置界面的实际尺寸，即
     * {@code ObjBlockConfigScreen#getPanelRight()} 的 {@code width / 5 - GAP}（左边界同为 {@code GAP}），
     * 因此原宽 = {@code width / 5 - 2 * GAP}。乘 {@value #PANEL_WIDTH_FACTOR} 后从
     * {@link #getPanelLeft()} 起算 —— 用 1.5 倍而不是 2 倍，是为了两列各占约 0.72 倍原宽
     * （控件“稍微小一点”），同时不把屏幕挡得太满。
     * <p>
     * 极窄窗口（如 GUI 缩放下只有 320 像素宽）时 1.5 倍仍不足两列使用，此时保底
     * {@code 2 * MIN_COLUMN_WIDTH + COLUMN_GAP}，否则右列会被内容裁剪区切掉。
     */
    @Override
    protected int getPanelRight() {
        final int originalWidth = this.width / 5 - GAP * 2;
        final int scaled = Math.round(originalWidth * PANEL_WIDTH_FACTOR);
        final int minPanelWidth = MIN_COLUMN_WIDTH * 2 + COLUMN_GAP;
        return getPanelLeft() + Math.max(scaled, minPanelWidth);
    }

    @Override
    protected int getPanelTop() {
        return 30;
    }

    @Override
    protected int getPanelBottom() {
        return this.height - 30;
    }

    @Override
    protected int getContentTop() {
        return 58;
    }

    @Override
    protected int getContentBottom() {
        return this.height - 42;
    }

    @Override
    protected int getContentLeft() {
        return getLeftColumnX();
    }

    @Override
    protected int getContentRight() {
        return getPanelRight();
    }

    /** 面板总宽度（两列 + 列间距）。 */
    private int getPanelWidth() {
        return getPanelRight() - getPanelLeft();
    }

    /** 单列宽度：总宽减去列间距后对半分，控件都比原单列布局窄一些以适应两列。 */
    private int getColumnWidth() {
        return Math.max(1, (getPanelWidth() - COLUMN_GAP) / 2);
    }

    /** 左列（平移）起点。 */
    private int getLeftColumnX() {
        return getPanelLeft();
    }

    /** 右列（旋转）起点。 */
    private int getRightColumnX() {
        return getLeftColumnX() + getColumnWidth() + COLUMN_GAP;
    }

    /** 两列合起来的宽度：顶部切换、保存按钮与轨道编辑区都用它铺满。 */
    private int getColumnsWidth() {
        return getColumnWidth() * 2 + COLUMN_GAP;
    }

    @Override
    protected void renderPanelBackground(GraphicContext g) {
        // 背景延伸到面板右边界再留一个 GAP，与原单列实现（fill 到 width/4）保持同样的视觉边距
        g.fill(0, 0, getPanelRight() + GAP, this.height, 0xFF000000);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
