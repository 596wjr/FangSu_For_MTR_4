package com.fangsu.ui;

import com.fangsu.Main;
import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import com.fangsu.extraConfig.SliderWidget;
import com.fangsu.mappings.ComponentHelper;
import com.fangsu.util.NodeConnector;
import com.fangsu.utils.GraphicContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 万向节点配置界面（扳手右键打开），取代旧的全屏 {@code NodeAngleScreen}。
 * <p>
 * 界面基于仓库通用的可滚动配置框架 {@link BasicConfigScreen}，但采用<b>双列布局</b>：
 * <ul>
 *   <li>左列 = 平移（X / Y / Z 偏移）</li>
 *   <li>右列 = 旋转（俯仰角 / 方向 / 翻滚角）与「旋转绑定」开关</li>
 *   <li>两列下方 = 轨道编辑（占满两列宽度）：轨道形状 / 半径 / 样式 + 外轨超高开关 + 半轨距</li>
 *   <li>顶部输入模式切换与底部「保存并退出」按钮横跨两列</li>
 * </ul>
 * 双列是为了避免单列纵向堆叠导致的频繁滚动。
 * <p>
 * <b>角度语义</b>（P3 定义，P4a 起作用于轨道几何）：俯仰角（硬边界 ±60°）正 = 沿节点方向前进时上坡；
 * 翻滚角（硬边界 ±45°）正 = 前进方向右手侧抬高（外轨超高约定）。两者都会写进
 * {@code RailPoseExtra}：俯仰驱动几何内核的三次 Hermite 竖向剖面，
 * 翻滚（外轨超高开关开启时）驱动 {@code 半轨距·|sin(roll)|} 的中心线抬升。
 * 节点自身标记模型的倾斜仍由 {@code BlockEntityMultiDirectionNode#applyNodeModelTilt} 负责。
 * 轨道<b>截面</b>与车体的视觉倾斜属于下一步（P4b），本界面不涉及。
 * <p>
 * <b>两套区间</b>：滑块区间（拖动便利，保持旧值 ±1 格 / [0,180] / ±15° / ±20° / [0.5,1.0]）
 * 与硬边界（{@code BlockEntityMultiDirectionNode} 单点定义、服务端强制，分别为 ±2 格 /
 * 回绕 [0,360) / ±60° / ±45° / [0.25,2.0]）是两回事。
 * <p>
 * <b>顶部总开关一刀切</b>：面板顶部的按钮切换<b>整块面板</b>的数字输入方式，两种方式<b>互斥</b>：
 * <ul>
 *   <li><b>滑块模式</b>（默认）：每行<b>只有滑块</b>，数值框与就地输入框都不显示；</li>
 *   <li><b>输入模式</b>：每行<b>只有输入框</b>（占满列宽），不构造滑块。</li>
 * </ul>
 * 两种方式的行高都取 {@link #ROW_HEIGHT}，切换时行高不变、其他行不动，也<b>不改任何数值</b>。
 * 输入模式下回车 / 小键盘回车 / 点到别处提交，{@code Esc} 取消，
 * 同一时刻只允许一行处于编辑态（点另一行的输入框会先把上一行提交掉）。
 * 滑块拖不到的值可以切到输入模式键入；超出滑块区间但未超硬边界的值会被原样保留，
 * 切回滑块模式时滑块钉在端点、真值由滑块自身的数值文本显示（详见 {@link #addSliderInputRow}）。
 * <p>
 * <b>外轨超高开关</b>只门控翻滚对轨道几何的贡献（关 → 几何里的 roll 端点值写 0）；
 * 俯仰是独立功能（纵坡），<b>不</b>受该开关影响。半轨距（米）是同一个几何公式里的系数，
 * 因此开关关闭时它整行灰显不可编辑。
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
public class MultiDirectionNodeConfigScreen extends BasicConfigScreen {

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
    //     真值仍然完整地显示在数值框里（见 {@link #addSliderInputRow}）。
    //
    // 因此本次改动没有放大任何滑块区间（拖动的手感、可点的范围一律不变），
    // 只是把硬边界放宽，并给每行补上可点击的数值框，让超出滑块范围的值可以键入。

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
     * {@link BlockEntityMultiDirectionNode#MAX_ROLL_DEG}（±45°，理由见该常量）。
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
     */
    private static final float HALF_GAUGE_SLIDER_MIN = 0.5f;
    private static final float HALF_GAUGE_SLIDER_MAX = 1.0f;
    private static final float HALF_GAUGE_STEP = 0.005f;

    // ==================== 单行数值行的控件尺寸 ====================
    //
    // 每一行（平移 / 方向 / 俯仰 / 翻滚 / 半轨距）在<b>两种输入方式下都只占一行</b>，
    // 且行高取同一个常量 {@link #ROW_HEIGHT}，所以切换输入方式时布局不跳、其他行也不动：
    //
    //     [ 标签文字 ]
    //     [ 滑块 ........................................... ]   ← 滑块模式：滑块占满行宽，数值框隐藏
    //     [ 输入框 ......................................... ]   ← 输入模式：不构造滑块，输入框占满列宽
    //
    // 注意：滑块模式下数值框与输入框仍然被构造出来（共用同一段行构造代码），但都不可见，
    // 因此滑块可以放心占满整行 —— 隐藏的控件既不绘制、也点不到（见 isValueBoxVisible）。

    /** 行高（像素）：滑块 20 + 行间距 2。常态与编辑态都用它，保证布局不跳。 */
    private static final int ROW_HEIGHT = 22;
    /** 滑块高度（像素）。 */
    private static final int SLIDER_HEIGHT = 20;
    /** 数值框 / 输入框高度（像素）。比滑块矮 2 像素，在 20 像素的行里垂直居中。 */
    private static final int VALUE_BOX_HEIGHT = 18;
    /**
     * 数值区（可点击的数值显示 / 就地出现的输入框）占行宽的比例上限与下限。
     * <p>
     * 上限 60 像素是为了给滑块留出可拖动的宽度；下限 40 像素是为了放得下
     * {@code -0.5000} / {@code 270.0000} 这类最长的值。列宽只有 60 像素（极窄面板）时
     * 数值区取 40、滑块剩 20，滑块变窄但仍能拖 —— 两者都不至于完全不可用。
     */
    private static final int VALUE_BOX_MAX_WIDTH = 60;
    private static final int VALUE_BOX_MIN_WIDTH = 40;
    /** 滑块最小宽度（像素）：极窄列下的保底，再窄就拖不准了。 */
    private static final int SLIDER_MIN_WIDTH = 20;
    /** 数值区与滑块之间的间隙（像素）。 */
    private static final int CONTROL_GAP = 2;

    /** 半径步进按钮（沿用旧 NodeAngleScreen / 原版 RailModifierScreen 的六档）。 */
    private static final String[] RADIUS_BUTTON_LABELS = {"-10", "-1", "-.1", "+.1", "+1", "+10"};
    private static final double[] RADIUS_BUTTON_STEPS = {-10, -1, -0.1, 0.1, 1, 10};

    private final BlockEntityMultiDirectionNode node;
    /** 刷子/视线选中的轨道；为 null 时轨道编辑控件全部灰显（node 未连接或没看向轨道）。 */
    @Nullable
    private final Rail rail;

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

    /**
     * 整块面板的数字输入方式（顶部总开关一刀切）：{@code true} = <b>滑块模式</b>（默认，每行只有滑块）；
     * {@code false} = <b>输入模式</b>（每行只有输入框）。
     * <p>
     * 两种方式<b>互斥</b>、行高相同（{@link #ROW_HEIGHT}）：切换只改控件的可见性 / 构造，
     * <b>不改任何数值</b>，也不移动其他行。数值框与就地输入框只属于输入模式
     * （见 {@link #isValueBoxVisible()}），滑块只属于滑块模式。
     */
    private boolean useSliderInput = true;

    /** 本次构建出来的全部数值输入框，供 {@link #commitAllNumericFields()} 统一提交。 */
    private final List<NumericField> numericFields = new ArrayList<>();

    /**
     * 本次构建出来的全部数值行（键 = 行序号），供 {@link #beginEdit(int)} 按序号找到行、
     * 以及 {@link NumericRow#refreshDisplay()} 在写值后重绘数值框文本
     * （数值框因此永远显示<b>当前真值</b>，包括超出滑块范围的值）。
     */
    private final Map<Integer, NumericRow> valueRows = new LinkedHashMap<>();

    /**
     * 正在编辑的行序号；{@code -1} = 当前没有行处于编辑态。
     * <p>
     * <b>同时只允许一行处于编辑态</b>：点击另一行的数值框时，{@link #beginEdit(int)} 会先
     * {@link #finishEdit()} 把上一行提交掉（写值 + 收起输入框），再切换到新的一行。
     */
    private int editingIndex = -1;

    /** 当前编辑态的输入框；与 {@link #editingIndex} 同步维护，{@code null} = 不在编辑态。 */
    @Nullable
    private NumericField editingField;

    // ---- 轨道编辑状态 ----
    private Rail.Shape shape;
    private double radius;
    private final double maxRadius;
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

        // 整块面板的总开关：两列的所有数值行一起切到另一种输入方式（互斥，见 useSliderInput 的说明）
        addFixedWidget(ComponentHelper.button(left, 34, width, 20, getInputToggleLabel(), btn -> {
            useSliderInput = !useSliderInput;
            requestRebuild();
        }));

        closeButton = addFixedWidget(ComponentHelper.button(left, this.height - 30, width, 20,
                ComponentHelper.translatable("ui.fangsu.block.close_and_save"), btn -> {
                    save();
                    onClose();
                }));
    }

    /**
     * 顶部总开关的标签：显示的是<b>点下去会发生什么</b>，因此滑块模式下写「切换为输入框」、
     * 输入模式下写「切换为滑块」，与当前控件的形态互为印证（状态由整块面板的控件形态直接表达）。
     */
    private Component getInputToggleLabel() {
        return ComponentHelper.translatable(useSliderInput
                ? "ui.fangsu.block.toggle_input"
                : "ui.fangsu.block.toggle_slider");
    }

    /**
     * 数值框（可点击的 {@code [ 1.5 ]}）与就地输入框是否应当可见：<b>只属于输入模式</b>。
     * <p>
     * 滑块模式下这一行<b>只有滑块</b>：数值框既不绘制（{@code visible = false} 时
     * {@code AbstractWidget.render} 会直接跳过，各 MC 版本一致），也点不到
     * （{@link ValueButton#isHovered} 先判 {@code visible}），
     * 于是「点一下就地变输入框」这条入口在滑块模式下完全关闭；输入模式下反过来不构造滑块。
     * 两种模式的行高都是 {@link #ROW_HEIGHT}，控件 y 也相同，因此切换不跳、其他行不动。
     */
    private boolean isValueBoxVisible() {
        return !useSliderInput;
    }

    // ==================== 可滚动内容 ====================

    @Override
    protected void buildScrollableContent(ContentLayout layout) {
        // 重建会销毁全部数值行的控件引用（含可能正在编辑的那一个），先把编辑态收掉，
        // 再丢掉上一批行的引用，避免状态机指向已经不存在的控件
        cancelEdit();
        editingIndex = -1;
        editingField = null;
        numericFields.clear();
        valueRows.clear();
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

        // ---- 右列：节点旋转（方向 + 俯仰 + 翻滚）+ 旋转绑定开关 ----
        // 俯仰 / 翻滚取代原先的两行「预留轴」占位。
        // 标签按概念命名（俯仰角 / 翻滚角）而不是轴字母：这两个量是铁路语义，
        // 不是「绕方块 X/Z 轴转」，用轴字母会误导用户与后续维护者。
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

        // 外轨超高：开启 / 关闭开关（P4a 起真正接线）。它只门控滚转对轨道几何的贡献
        // （关 → RailPoseExtra 的 roll 端点值写 0，内核的 半轨距·|sin(roll)| 中心线抬升消失）；
        // 俯仰（纵坡）是独立功能，无论开关如何都照常生效。
        final Component superelevationLabel = ComponentHelper.translatable(
                "ui.fangsu.multi_direction_node.superelevation",
                ComponentHelper.translatable(superelevation
                        ? "ui.fangsu.multi_direction_node.superelevationOn"
                        : "ui.fangsu.multi_direction_node.superelevationOff"));
        final Button buttonSuperelevation = addButton(leftX, y, fullWidth, 20, superelevationLabel,
                b -> toggleSuperelevation());
        addEntry(buttonSuperelevation, y);
        y += 24;

        // 半轨距（米）：外轨超高抬升中心线的系数，只有开关开启时才可编辑。
        y = addHalfGaugeRow(leftX, y, fullWidth, superelevation);
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

    // ==================== 单行数值行：控件尺寸与文本格式 ====================

    /** 数值框 / 输入框宽度：行宽的 40%，钳在 [{@link #VALUE_BOX_MIN_WIDTH}, {@link #VALUE_BOX_MAX_WIDTH}]。 */
    private int valueBoxWidth(int rowWidth) {
        return Math.max(VALUE_BOX_MIN_WIDTH,
                Math.min(VALUE_BOX_MAX_WIDTH, Math.round(rowWidth * 0.4f)));
    }

    /**
     * 数值区右侧宽度：行宽减去数值区与间隙，保底 {@link #SLIDER_MIN_WIDTH}。
     * <p>
     * 滑块模式下滑块已占满行宽（见 {@link #addSliderInputRow}），本方法<b>不再</b>用于算滑块宽度，
     * 现在唯一的调用点是隐藏数值框 / 数值框按钮的 x —— 它们不可见，位置只求与旧布局一致，
     * 因此保留本方法而不是删掉（删掉会改动那两处不可见控件的坐标计算）。
     */
    private int sliderWidth(int rowWidth, int boxWidth) {
        return Math.max(SLIDER_MIN_WIDTH, rowWidth - boxWidth - CONTROL_GAP);
    }

    /**
     * 一行「标签 + 数值控件」。两种输入方式<b>都</b>只占一行、行高相同，且<b>互斥</b>：
     * <ul>
     *   <li>{@code useSliderInput} = 是（滑块模式）→ <b>只有滑块</b>（数值框隐藏）；</li>
     *   <li>{@code useSliderInput} = 否（输入模式）→ <b>只有输入框</b>（占满列宽，不构造滑块）。</li>
     * </ul>
     * 两者共用同一套提交 / 编辑规则与同一批 setter，因此不会出现「某种输入方式下行为不一样」。
     *
     * @param value     构造该行时的当前值（只用于初始化控件显示文本）
     * @param sliderMin 滑块区间下限（拖动便利，与硬边界无关）
     * @param sliderMax 滑块区间上限（同上）
     * @param step      滑块步进
     * @param setter    与滑块共用的写入函数（内部按硬边界钳制 / 回绕）
     * @param current   读取「已经落库的真值」，提交时用它规范化或回滚输入框文本
     * @param onChanged 与滑块共用的变更回调（写 BE_SYNC + 触发轨道重建）
     */
    private int addAxisRow(int areaLeft, int y, int rowWidth, Component label,
                           float value, float sliderMin, float sliderMax, float step,
                           Consumer<Float> setter, FloatGetter current, Runnable onChanged) {
        return useSliderInput
                ? addSliderInputRow(areaLeft, y, rowWidth, label, value, sliderMin, sliderMax, step, setter, current, onChanged)
                : addInputOnlyRow(areaLeft, y, rowWidth, label, value, setter, current, onChanged);
    }

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

    /**
     * 数字转字符串（最多 4 位小数，去掉无意义的尾随 0）：
     * {@code 1.5 → "1.5"}、{@code 270 → "270"}、{@code 0.7175 → "0.7175"}、{@code -0 → "0"}。
     * <p>
     * 数值框只有 40~60 像素宽，{@code "270.0000"} 这种定长写法会顶到框外，
     * 所以数值框与就地输入框都用这个紧凑格式（提交后的规范化也用它，因此「提交后显示什么」是可预期的）。
     */
    private static String formatCompact(double value) {
        String text = String.format("%.4f", value);
        if (text.indexOf('.') >= 0) {
            text = text.replaceAll("0+$", "");
            if (text.endsWith(".")) {
                text = text.substring(0, text.length() - 1);
            }
        }
        return "-0".equals(text) ? "0" : text;
    }

    /**
     * 一行「标签 + 滑块」，<b>标签一行、控件一行，共 2 行</b>（旧实现的「标签 / 滑块 / 输入框」三行版已删除）。
     * <p>
     * <b>滑块模式在这一行只显示滑块</b>：数值框 {@link ValueButton} 与就地输入框 {@link NumericField}
     * 都<b>不</b>可见（数值框用 {@link #isValueBoxVisible()} 显式置 {@code visible = false}），
     * 因此既不会画出来、也点不到，「点一下变输入框」这条入口在滑块模式下完全关闭。
     * 它们仍然被构造出来，是为了让两种输入方式共用同一段行构造代码与同一套宽度计算，
     * 并且提交 / 取消（{@link #finishEdit()} / {@link #cancelEdit()}）恢复可见性时不必区分模式。
     * <p>
     * <b>行高两种方式相同</b>：{@link #ROW_HEIGHT}，控件 y 也相同，所以切换输入方式时布局<b>不跳</b>，
     * 其他行也不动（{@code entries} 的 {@code baseY} 不随输入方式改变）。
     * <p>
     * <b>越界语义</b>（与旧实现一致，一字未改）：滑块拖不到的值可以切到输入模式键入 ——
     * 键入值在滑块区间内 → 与拖动完全等价（同一个 {@code setter} + 同一个 {@code onChanged}）；
     * 超出滑块区间但未超硬边界 → 真值原样保留，滑块由 {@link SliderWidget#setExternal(float)} 钉在端点，
     * 切回滑块模式时真值由滑块自身的数值文本显示；超出硬边界 → setter 按硬边界钳制（方向回绕）。
     */
    private int addSliderInputRow(int areaLeft, int y, int rowWidth,
                                  Component label, float value,
                                  float sliderMin, float sliderMax, float step,
                                  Consumer<Float> setter, FloatGetter current, Runnable onChanged) {
        addEntry(createTextLabel(areaLeft, y, label, TextLabel.Align.LEFT, 0xFFFFFF, false), y);
        y += 8;

        final int index = numericFields.size();
        final int boxWidth = valueBoxWidth(rowWidth);
        final NumericRow row = new NumericRow(index, current);
        row.rowTop = y;

        // 滑块模式整行只有滑块，所以滑块直接占满行宽（不再给数值区留位置）；
        // 高度与 y 都不变，因此切换输入方式时行高不变、其他行也不动
        final SliderWidget slider = new SliderWidget(areaLeft, y, rowWidth, SLIDER_HEIGHT,
                ComponentHelper.empty(), value, sliderMin, sliderMax, step,
                v -> {
                    setter.accept(v);
                    // 数值框跟随滑块（静默回写，不触发 responder 二次写值）；滑块模式下数值框不可见，
                    // 这里只是保持文本与真值一致，切到输入模式时输入框一开始就是对的
                    row.writeFieldSilently(formatCompact(current.get()));
                    row.refreshDisplay();
                    onChanged.run();
                });
        row.slider = slider;
        addRenderableWidget(slider);
        addEntry(slider, y);

        // 数值框 / 数值框按钮在滑块模式下不可见，这里的 x 沿用旧的「滑块右侧」算法，
        // 只是为了让这两个不可见控件的坐标与旧布局一致（点不到、画不出，不影响滑块）
        final int boxX = areaLeft + sliderWidth(rowWidth, boxWidth) + CONTROL_GAP;
        final NumericField box = new NumericField(boxX, y, boxWidth, index, setter, current, onChanged, row);
        row.box = box;
        box.setInitialValue(value);
        addRenderableWidget(box);
        addEntry(box, y);

        final ValueButton button = new ValueButton(boxX, y, boxWidth, index, row);
        // 滑块模式：数值框不显示（本行只有滑块）。这一行是本次改动的核心 —— 数值框、
        // 高亮、点击编辑入口全部随 visible 一起消失，控件位置 / 行高 / 其他行都不动。
        button.visible = isValueBoxVisible();
        row.button = button;
        addRenderableWidget(button);
        addEntry(button, y);

        row.refreshDisplay();
        registerRow(row);
        return y + ROW_HEIGHT;
    }

    /**
     * 只有输入框的一行（{@code useSliderInput} = 否，输入模式）。除没有滑块外，
     * 一切行为与 {@link #addSliderInputRow} 完全一致（行高同样是 {@link #ROW_HEIGHT}）。
     * <p>
     * 数值框 {@link ValueButton} 仍然在这里构造并<b>可见</b>（{@link #isValueBoxVisible()} = 是）：
     * 输入模式的常态显示的是「点一下就地变输入框」的数值框，点开后才换成 {@link NumericField}，
     * 因此这一行与本次改动无关，保持原样。
     */
    private int addInputOnlyRow(int areaLeft, int y, int rowWidth,
                                Component label, float value,
                                Consumer<Float> setter, FloatGetter current, Runnable onChanged) {
        addEntry(createTextLabel(areaLeft, y, label, TextLabel.Align.LEFT, 0xFFFFFF, false), y);
        y += 8;

        final int index = numericFields.size();
        final int boxWidth = Math.max(VALUE_BOX_MIN_WIDTH, rowWidth);
        final NumericRow row = new NumericRow(index, current);
        row.rowTop = y;

        final NumericField box = new NumericField(areaLeft, y, boxWidth, index, setter, current, onChanged, row);
        row.box = box;
        box.setInitialValue(value);
        addRenderableWidget(box);
        addEntry(box, y);

        final ValueButton button = new ValueButton(areaLeft, y, boxWidth, index, row);
        button.visible = isValueBoxVisible();
        row.button = button;
        addRenderableWidget(button);
        addEntry(button, y);

        row.refreshDisplay();
        registerRow(row);
        return y + ROW_HEIGHT;
    }

    /** 登记一行数值行（并建立「行序号 → 行」的索引，供数值框文本刷新用）。 */
    private void registerRow(NumericRow row) {
        valueRows.put(row.index, row);
        if (row.box != null) {
            numericFields.add(row.box);
        }
    }

    // ==================== 就地编辑状态机（同时只允许一行编辑） ====================

    /**
     * 进入某一行的编辑态：数值框就地换成 {@link NumericField}（同一个位置、同一个高度，布局不跳）。
     * <p>
     * 切换前先 {@link #finishEdit()} 提交上一行 —— 这就是「同时只允许一行处于编辑态」的实现：
     * 点另一个数值框时，上一个输入框的文本立刻落库并收起。
     */
    private void beginEdit(int index) {
        final NumericRow row = valueRows.get(index);
        if (row == null || row.box == null) {
            return;
        }
        // 已经是这一行在编辑：只需把焦点抢回来
        if (editingIndex == index && editingField == row.box) {
            row.box.setFocused(true);
            return;
        }
        finishEdit();
        row.writeFieldSilently(formatCompact(row.current.get()));
        row.button.visible = false;
        row.box.visible = true;
        editingIndex = index;
        editingField = row.box;
        row.box.setFocused(true);
    }

    /**
     * 提交并退出编辑态（回车的落库路径）。
     * <p>
     * {@link NumericField#commit()} 已经把「可解析 → 落库 + 规范化文本 / 不可解析 → 文本回滚」
     * 做完，这里只负责收尾（收起输入框、显示数值框、清掉状态与焦点）。
     * <p>
     * <b>为什么还要在界面层收尾</b>：MC 1.18.2 的 {@code AbstractWidget.setFocused} 是 protected，
     * 且容器的 {@code setFocused} 只改自己的引用字段、<b>不</b>通知旧控件（1.19.4+ 才回调），
     * 所以「点到别处」「点保存」这两个时机不能只依赖 {@code setFocused}，还要由
     * {@link #mouseClicked} 与 {@link #save()} 显式调用 {@link #commitAllNumericFields()}。
     * 三条路径最终都收敛到同一个 {@link NumericField#commit()}。
     */
    private void finishEdit() {
        if (editingIndex < 0) {
            return;
        }
        final NumericRow row = valueRows.get(editingIndex);
        final NumericField field = editingField;
        // 先清状态再提交：commit() 内部可能再次走到 finishEdit（例如 setFocused(false) 的回调），
        // 状态已清空即可直接返回，不会递归
        editingIndex = -1;
        editingField = null;
        if (field != null) {
            field.commit();
            field.visible = false;
        }
        if (row != null && row.button != null) {
            // 恢复数值框时同样过一遍模式判定：输入模式才显示，滑块模式保持隐藏（见 isValueBoxVisible）
            row.button.visible = isValueBoxVisible();
            row.button.setDisplayText(formatCompact(row.current.get()));
        }
    }

    /**
     * 取消编辑（Esc）：<b>一个字节都不写</b>，把输入框文本恢复成当前真值后收起输入框。
     * <p>
     * 与 {@link #finishEdit()}（提交）的区别只在于「不回写数据」；输入过程中
     * {@link NumericField} 的 responder 已经实时写过合法的中间值，而 Esc 的语义是
     * 「放弃本次键入」，因此恢复文本后不触碰 {@code setter} / {@code onChanged}。
     */
    private void cancelEdit() {
        if (editingIndex < 0) {
            return;
        }
        final NumericRow row = valueRows.get(editingIndex);
        final NumericField field = editingField;
        editingIndex = -1;
        editingField = null;
        if (field != null) {
            field.visible = false;
            // 先恢复文本（静默，不写值），再摘掉焦点，顺序反过来会触发一次无用的提交
            if (row != null) {
                row.writeFieldSilently(formatCompact(row.current.get()));
            }
            field.setFocused(false);
        }
        if (row != null && row.button != null) {
            // 同 finishEdit：恢复可见性也要过模式判定，滑块模式下不能把数值框重新显示出来
            row.button.visible = isValueBoxVisible();
            row.button.setDisplayText(formatCompact(row.current.get()));
        }
    }

    /**
     * 提交全部数值输入框的待处理文本（回车之外的兜底：鼠标点到别处、点「保存并退出」、界面重建）。
     * <p>
     * 见 {@link NumericField} 的说明：跨 MC 版本（1.18.2 ~ 1.20.4）的「失去焦点」回调不可靠，
     * 因此由界面显式调用。正常情况下同时最多只有一个框处于编辑态，这里循环是为了兜底。
     */
    private void commitAllNumericFields() {
        for (final NumericField field : numericFields) {
            field.commit();
        }
    }

    /** 读取「当前已落库数值」的取值器（输入框提交 / 回滚时用）。 */
    @FunctionalInterface
    private interface FloatGetter {
        float get();
    }

    /**
     * 解析输入框文本：非数字（{@code "abc"}、空串、{@code "-"}）与<b>非有限值</b>一律返回 {@code null}。
     * <p>
     * 为什么不直接用框架的 {@link BasicConfigScreen#parseFloat(String)}：它只挡
     * {@code NumberFormatException}，而 {@code Float.parseFloat("NaN")} 会成功返回 NaN、
     * {@code "1e999"} 会返回 Infinity。放行的话它们会被 setter 落成 0 或边界值 ——
     * 那正是「非法输入静默写 0」这种要避免的行为。所以这里把「非有限」也归入非法输入。
     */
    @Nullable
    private Float parseFieldValue(String text) {
        final Float parsed = parseFloat(text);
        if (parsed == null || parsed.isNaN() || parsed.isInfinite()) {
            return null;
        }
        return parsed;
    }

    /**
     * 一行数值控件（滑块 + 可点击数值框 + 就地输入框）的容器。
     * <p>
     * 三个控件互相引用（滑块回调要刷新数值框与文本、点击数值框要开输入框、提交要回写滑块），
     * 构造顺序上必有一方先于另一方存在，因此用这个可变容器在闭包里解引用，而不是依赖构造顺序。
     * 滑块缺席（{@code useSliderInput} = 否）时 {@link #slider} 为 {@code null}，对应操作静默跳过。
     */
    private final class NumericRow {

        /** 行序号：{@link #numericFields} 的下标，也是 {@link #valueRows} 的键与编辑态的身份。 */
        private final int index;
        /** 读取「已落库真值」的取值器：数值框文本、提交规范化、回滚都用它。 */
        private final FloatGetter current;
        /** 这一行控件的 baseY（构建时的逻辑 y，不含滚动偏移）：只用于「点击是否在本行内」的判定。 */
        private int rowTop;
        private SliderWidget slider;
        private NumericField box;
        private ValueButton button;
        /** 静默回写标志：为 true 时输入框的 responder 只更新文本、不写值。 */
        private boolean silent;

        NumericRow(int index, FloatGetter current) {
            this.index = index;
            this.current = current;
        }

        /** 静默把文本写进输入框：不触发 responder，因此不会二次写值 / 二次重建轨道。 */
        private void writeFieldSilently(String text) {
            if (box == null || text.equals(box.getValue())) {
                return;
            }
            silent = true;
            try {
                box.setValue(text);
            } finally {
                silent = false;
            }
        }

        /** 输入框 → 滑块：把真值交给滑块显示；越界时由 {@link SliderWidget#setExternal(float)} 钉在端点。 */
        private void applyToSlider(float applied) {
            if (slider != null) {
                slider.setExternal(applied);
            }
        }

        /** 重绘数值框文本（当前真值的紧凑格式），使它与滑块、输入框三者始终一致。 */
        private void refreshDisplay() {
            if (button != null) {
                button.setDisplayText(formatCompact(current.get()));
            }
        }
    }

    /**
     * 可点击的数值显示（输入模式下这一行的「数值」外观）：{@code [ 1.5 ]}。
     * <p>
     * <b>只在输入模式可见</b>：滑块模式下一行只有滑块，本控件由 {@link #isValueBoxVisible()}
     * 置 {@code visible = false} —— 不绘制、悬停不高亮、也点不到（{@link #isHovered} 先判 {@code visible}），
     * 因此「点一下就地变输入框」这条入口在滑块模式下完全关闭。代码本身<b>没有</b>删掉，
     * 输入模式仍然完全依赖它。
     * <p>
     * <b>为什么不用普通文本标签</b>：标签没有任何「可以点」的提示，用户不会想到点它能输入。
     * 这里画成带背景块 + 方括号的「值」，悬停时提亮背景与文字，既与界面既有的灰底风格一致，
     * 又把「这是一块可点的控件」表达清楚。
     * <p>
     * <b>两种 MC 版本的渲染分叉</b>：{@code AbstractWidget} 的渲染方法在 1.19.4+ 是
     * {@code renderWidget(GuiGraphics, int, int, float)}，在 1.18.2 是
     * {@code render(PoseStack, int, int, float)}。两者签名不同、无法用条件编译把「同一个方法名」
     * 分叉出来（条件块的两个分支会被同时编译），因此这里声明两个私有方法各自加条件编译，
     * 用匿名 {@link Renderable} 在运行时按 {@code GraphicContext} 的类型挑一个 ——
     * 界面本身的 {@code render} 就是这么分叉的，这里沿用同一套写法。
     */
    private final class ValueButton extends AbstractWidget {

        private final int rowIndex;
        private final NumericRow row;
        private String displayText = "";

        ValueButton(int x, int y, int width, int rowIndex, NumericRow row) {
            super(x, y, width, VALUE_BOX_HEIGHT, ComponentHelper.empty());
            this.rowIndex = rowIndex;
            this.row = row;
        }

        /** 更新显示文本（由 {@link NumericRow#refreshDisplay()} 调用）。 */
        void setDisplayText(String text) {
            this.displayText = text;
        }

        /**
         * 控件左上角与尺寸：{@code getX() / getY()} 是 1.19.3+ 才有的访问器，
         * 1.18.2 里坐标是 {@code public int x / y} 字段（{@code width / height} 则一直是 protected 字段）。
         * 条件编译的两个分支都会被同时编译，所以坐标读取必须这样分叉 ——
         * 同仓库的 {@link BasicConfigScreen.TextLabel#renderLabel} 与
         * {@link SliderWidget} 内层滑块就是这么写的。
         */
        private int[] bounds() {
            //#if MC_VERSION >= 11903
            return new int[]{this.getX(), this.getY(), this.width, this.height};
            //#else
            //$$ return new int[]{this.x, this.y, this.width, this.height};
            //#endif
        }

        /** 不用 hovered 状态，避免依赖 AbstractWidget 的鼠标追踪（不同版本细节不同）。 */
        private boolean isHovered(int mouseX, int mouseY) {
            final int[] b = bounds();
            return this.visible
                    && mouseX >= b[0] && mouseX < b[0] + b[2]
                    && mouseY >= b[1] && mouseY < b[1] + b[3];
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && isHovered((int) mouseX, (int) mouseY)) {
                // 就地进入编辑态：数值框换成输入框（位置与高度都不变，见 beginEdit）
                beginEdit(rowIndex);
                return true;
            }
            return false;
        }

        /**
         * 数值框不参与 Tab 焦点轮转：它的「编辑」语义是点一下就地变成输入框，
         * 真正的焦点属于输入框。置成不可聚焦也避免抢走输入框的焦点。
         */
        @Override
        public void setFocused(boolean focused) {
            // 1.18.2 的 AbstractWidget#setFocused 是 protected，这里放宽为 public（Java 允许），
            // 因此在两个版本上都能作为覆写编过（同 NumericField#setFocused）。
            super.setFocused(false);
        }

        private void draw(GraphicContext g, int mouseX, int mouseY) {
            final boolean hovered = isHovered(mouseX, mouseY);
            final int[] b = bounds();
            final int left = b[0];
            final int top = b[1];
            final int right = left + b[2];
            final int bottom = top + b[3];
            // 方框：常态浅底 + 细边框，悬停时整体提亮（同色系，不引入新配色）
            g.fill(left, top, right, bottom, hovered ? 0x60FFFFFF : 0x30FFFFFF);
            g.fill(left, top, right, top + 1, 0x80FFFFFF);
            g.fill(left, bottom - 1, right, bottom, 0x80FFFFFF);
            g.fill(left, top, left + 1, bottom, 0x80FFFFFF);
            g.fill(right - 1, top, right, bottom, 0x80FFFFFF);
            // 方括号 + 值：明确「这里是一个可以点的数值」
            final String text = "[" + this.displayText + "]";
            final int textX = left + (b[2] - MultiDirectionNodeConfigScreen.this.font.width(text)) / 2;
            final int textY = top + (b[3] - 8) / 2 + 1;
            g.drawString(MultiDirectionNodeConfigScreen.this.font, text, textX, textY,
                    hovered ? 0xFFFFA0 : 0xFFFFFF, false);
        }

        //#if MC_VERSION >= 12000
        private void renderAt(java.lang.Object graphics, int mouseX, int mouseY) {
            draw(GraphicContext.of((net.minecraft.client.gui.GuiGraphics) graphics), mouseX, mouseY);
        }
        //#else
        //$$ private void renderAt(java.lang.Object graphics, int mouseX, int mouseY) {
        //$$     draw(GraphicContext.of((com.mojang.blaze3d.vertex.PoseStack) graphics), mouseX, mouseY);
        //$$ }
        //#endif

        //#if MC_VERSION >= 12000
        @Override
        protected void renderWidget(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderAt(graphics, mouseX, mouseY);
        }
        //#elseif MC_VERSION >= 11904
        //$$ @Override
        //$$ protected void renderWidget(com.mojang.blaze3d.vertex.PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        //$$     renderAt(poseStack, mouseX, mouseY);
        //$$ }
        //#elseif MC_VERSION >= 11903
        //$$ @Override
        //$$ protected void renderButton(com.mojang.blaze3d.vertex.PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        //$$     renderAt(poseStack, mouseX, mouseY);
        //$$ }
        //#else
        //$$ @Override
        //$$ public void render(com.mojang.blaze3d.vertex.PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        //$$     renderAt(poseStack, mouseX, mouseY);
        //$$ }
        //#endif

        // 旁白：跨版本的方法名不一样，必须分叉 ——
        //   1.19.3+：AbstractWidget#updateWidgetNarration 是 protected abstract，必须实现；
        //   1.18.2：没有 updateWidgetNarration，但 NarratableEntry#updateNarration 是
        //           public abstract，不实现就会「不是抽象类且未实现抽象方法」。
        // 两段都是空实现（本控件不需要旁白），但都要有。
        //#if MC_VERSION >= 11903
        @Override
        protected void updateWidgetNarration(NarrationElementOutput narration) {
        }
        //#else
        //$$ @Override
        //$$ public void updateNarration(NarrationElementOutput narration) {
        //$$ }
        //#endif
    }

    /**
     * 就地数值输入框：<b>回车 / 小键盘回车 / {@code Esc} / 失去焦点</b>是它的退出时机。
     * <p>
     * 行为（本界面选定并在此固定；所有数值行共用这一个实现，因此不存在第二条更新路径）：
     * <ul>
     *   <li>输入过程中只要文本<b>可解析</b>，就立刻走与滑块<b>完全相同</b>的 {@code setter} + {@code onChanged}
     *       （同样的写 BE、同样的 BE_SYNC、同样的轨道重建），并把滑块挪到该值处；</li>
     *   <li>回车 / 失去焦点时提交：可解析 → 按硬边界钳制（方向回绕）后落库，
     *       并把文本重写成<b>真正的落库值</b>（例如键入 999 会显示硬边界值）；
     *       <b>不可解析 → 一个字节都不改数据，只把文本回滚成当前真值</b>。
     *       这就是本界面对非法输入的处理方式：不动数据 + 文本回滚，
     *       <b>不</b>静默写 0 / NaN，也<b>不</b>给输入框染色（染色需要按 MC 版本分叉 {@code setTextColor}，
     *       而回滚在所有目标版本上都是同一份代码）；</li>
     *   <li>{@code Esc} 取消：文本恢复成当前真值并收起输入框，同样不写数据；</li>
     *   <li>输入过程中的非法中间态（例如刚敲下的 {@code "-"}、{@code "1e"}、空串）不会被写值，
     *       因此也不会把 0 / NaN 偷偷写进节点。</li>
     * </ul>
     * 之所以「边输边写 + 提交时规范化」而不是「只在提交时才写」：滑块是实时写值的，
     * 若输入框只在提交时写，界面上就会长期存在「显示值 ≠ 已生效值」的不一致。
     * <p>
     * <b>跨版本</b>：回车与 Esc 由 {@link #keyPressed} 自己拦下（不依赖 {@code EditBox} 的默认处理，
     * 各版本并不一致）；「失去焦点」同时挂了 {@link #setFocused} 与界面层的
     * {@link #commitAllNumericFields()}（鼠标点击 / 保存），因为 1.18.2 的 {@code setFocused}
     * 不会通知旧控件、1.19.4+ 才会。三条路径都只是调用同一个 {@link #commit()}，语义完全一致。
     */
    private final class NumericField extends EditBox {

        private final int rowIndex;
        private final Consumer<Float> setter;
        private final FloatGetter current;
        private final Runnable onChanged;
        private final NumericRow row;

        NumericField(int x, int y, int width, int rowIndex,
                     Consumer<Float> setter, FloatGetter current, Runnable onChanged, NumericRow row) {
            super(MultiDirectionNodeConfigScreen.this.font, x, y, width, VALUE_BOX_HEIGHT,
                    ComponentHelper.empty());
            this.rowIndex = rowIndex;
            this.setter = setter;
            this.current = current;
            this.onChanged = onChanged;
            this.row = row;
            this.setResponder(text -> {
                if (row.silent) {
                    // 程序回写（滑块 → 输入框）不写值，否则会二次重建轨道
                    return;
                }
                final Float parsed = parseFieldValue(text);
                if (parsed == null) {
                    // 非法 / 中间态 / 非有限文本：不写值，等 Enter / Esc / 失去焦点时统一处理
                    return;
                }
                setter.accept(parsed);
                row.applyToSlider(parsed);
                // 只刷新数值框（它此刻是隐藏的），不改输入框文本：边输边改文本会把光标顶到行尾
                row.refreshDisplay();
                onChanged.run();
            });
            // 初始隐藏：常态显示的是可点击的数值框，点一下才把它换成输入框
            this.visible = false;
        }

        /**
         * 输入框里的文本用紧凑格式（同 {@link #formatCompact(double)}）：与数值框的显示格式一致，
         * 且 {@code 270} / {@code -0.5} 这类值不会因为定长 4 位小数而超过 40~60 像素的框宽。
         * 提交时的规范化也走这个方法，因此「提交后显示什么」是可预期的。
         * <p>
         * 注意：不能写成 {@code @Override formatValue(float)} —— {@code EditBox} 里同名的方法是
         * {@code private}（各版本都是），无法覆写；这里只是本类自己的格式化入口。
         */
        private String formatFieldValue(float value) {
            return formatCompact(value);
        }

        /** 初始化显示文本（静默：构造期不发 BE_SYNC、不重建轨道）。 */
        void setInitialValue(float value) {
            row.writeFieldSilently(formatFieldValue(value));
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (this.isFocused() && (keyCode == 257 || keyCode == 335)) {
                // 257 / 335 = 回车 / 小键盘回车。在 super 之前拦下：不同 MC 版本里 EditBox 对回车的
                // 默认处理并不一致（有的只把 responder 再叫一次、有的不处理），自己接管最稳。
                commit();
                return true;
            }
            if (this.isFocused() && keyCode == 256) {
                // 256 = Esc。交给界面统一处理「取消本次编辑」，不留给各版本行为不一的默认实现
                cancelEdit();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            // 可见性由 protected（1.18.2 的 AbstractWidget）放宽为 public（1.19.4+ 的 GuiEventListener
            // 契约）。Java 允许这种放宽，因此同一份代码在两个版本上都能编过。
            final boolean wasFocused = this.isFocused();
            super.setFocused(focused);
            if (wasFocused && !focused) {
                commit();
            }
        }

        /** 提交：可解析 → 落库并规范化文本；不可解析 → 值不变、文本回滚。提交后收起输入框。 */
        void commit() {
            if (this.visible) {
                commitValue();
                // 提交即退出编辑态（「点别处 / 回车」都走这里），数值框重新显示当前真值
                if (editingField == this) {
                    finishEdit();
                }
            }
        }

        /** 纯数据提交（不碰编辑态）：{@link #finishEdit()} 收尾时调用。 */
        private void commitValue() {
            if (row.silent) {
                return;
            }
            final Float parsed = parseFieldValue(this.getValue());
            if (parsed == null) {
                row.writeFieldSilently(formatFieldValue(current.get()));
                return;
            }
            setter.accept(parsed);
            row.applyToSlider(parsed);
            onChanged.run();
            // 文本规范化为「真正落库的值」：可能被硬边界钳制（方向则是回绕）过，不能停在用户键入的原文
            row.writeFieldSilently(formatFieldValue(current.get()));
        }
    }

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

    /** 翻滚角：钳制到硬边界 {@link BlockEntityMultiDirectionNode#MAX_ROLL_DEG}（±45°，理由见该常量）。 */
    private void setRoll(float value) {
        rollDeg = BlockEntityMultiDirectionNode.clampRoll(value);
    }

    /**
     * 半轨距（米）：钳制到硬边界
     * [{@link BlockEntityMultiDirectionNode#MIN_HALF_GAUGE}, {@link BlockEntityMultiDirectionNode#MAX_HALF_GAUGE}]
     * （[0.25, 2.0]），并与 BE 的 {@code clampHalfGauge} 走同一条钳制（NaN / 无穷 → 标准轨距的一半）。
     * 滑块区间仍是 [0.5, 1.0]、步进仍是 0.005 m（一字未改），因此放大后的区间同样只能键入。
     */
    private void setHalfGauge(float value) {
        halfGaugeM = BlockEntityMultiDirectionNode.clampHalfGauge(value);
    }

    /**
     * 翻转外轨超高开关：立即写 BE + 重建轨道，并重建界面。
     * <p>
     * 重建界面是必须的：开关只门控滚转，翻转后「半轨距」一行要在可编辑 / 灰显之间切换，
     * 而控件是在 {@link #buildScrollableContent} 里按当前开关状态构造的。
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

    /**
     * 点击任何位置之前，先把「正在编辑的那一行」的待处理文本提交掉。
     * <p>
     * 这就是「输入框失去焦点即提交」在本界面上的实现方式：不依赖 MC 版本各异的焦点回调
     * （见 {@link #finishEdit()} 的说明），而是用「任何一次鼠标点击」这一在 1.18.2~1.20.4 上
     * 行为完全一致的事件作为提交时机。
     * <p>
     * 点在<b>同一行</b>上时跳过提交：滑块与数值区是同一行，点滑块只是改值 / 继续看这一行，
     * 若在这里提交，输入框会立刻收起（用户会觉得「点一下滑块编辑就没了」）。
     * 判定用构建时记录的 {@link NumericRow#rowTop}（逻辑 y，不含滚动偏移），
     * 不依赖焦点也不依赖各版本不一致的坐标访问器，语义在 1.18.2~1.20.4 上一致。
     * <p>
     * <b>事件顺序（容易写错的地方）</b>：{@code super.mouseClicked} 会把点击派发给子控件，
     * 也就是说「点了某个数值框」的 {@link ValueButton#mouseClicked} → {@link #beginEdit(int)}
     * 是在本方法<b>下半段</b>才发生的。因此进入时的 {@link #editingIndex} 只可能属于「上一次
     * 已经开着的那一行」——提交它、并跳过点击落点那一行，正是「点另一个数值框先提交上一行、
     * 再把新的一行切进编辑态」这个语义。写得简单一点（比如对全部框无条件 commit）会把刚点开的
     * 输入框立刻提交掉、编辑态一闪而过，这里刻意按上面两条规则来。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        final int editingBeforeClick = editingIndex;
        if (editingBeforeClick >= 0) {
            final NumericRow row = valueRows.get(editingBeforeClick);
            // 点在编辑行自己身上（滑块或数值区）→ 不提交；点在别处 → 提交并退出编辑态。
            // 用构建时记录的 rowTop（而非 getY()）：getY() 在 1.18.2 上不存在。
            final boolean insideRow = row != null
                    && mouseY >= row.rowTop - 2
                    && mouseY <= row.rowTop + ROW_HEIGHT;
            if (!insideRow) {
                finishEdit();
            }
        }
        final boolean handled = super.mouseClicked(mouseX, mouseY, button);
        // 派发之后：点中的那一行可能刚刚进入编辑态（上面的 beginEdit），
        // 它与「上一行刚被 finishEdit 提交」两种情况都由 editingIndex 表达，跳过即可
        for (final NumericField field : numericFields) {
            if (field.rowIndex == editingIndex) {
                continue;
            }
            field.commit();
        }
        return handled;
    }

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
