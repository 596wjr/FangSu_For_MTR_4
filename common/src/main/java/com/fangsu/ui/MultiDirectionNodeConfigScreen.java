package com.fangsu.ui;

import com.fangsu.Main;
import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import com.fangsu.extraConfig.SliderWidget;
import com.fangsu.mappings.ComponentHelper;
import com.fangsu.util.NodeConnector;
import com.fangsu.utils.GraphicContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 万向节点配置界面（扳手右键打开），取代旧的全屏 {@code NodeAngleScreen}。
 * <p>
 * 界面基于仓库通用的可滚动配置框架 {@link BasicConfigScreen}，但采用<b>双列布局</b>：
 * <ul>
 *   <li>左列 = 平移（X / Y / Z 偏移）</li>
 *   <li>右列 = 旋转（俯仰角 / 方向 / 翻滚角）与「旋转绑定」开关</li>
 *   <li>两列下方 = 轨道编辑（占满两列宽度）</li>
 *   <li>顶部输入模式切换与底部「保存并退出」按钮横跨两列</li>
 * </ul>
 * 双列是为了避免单列纵向堆叠导致的频繁滚动。
 * <p>
 * <b>角度语义</b>（P3）：俯仰角（±15°）正 = 沿节点方向前进时上坡；翻滚角（±20°）正 = 前进方向
 * 右手侧抬高（外轨超高约定）。两者目前只倾斜节点自身的标记模型，<b>不</b>写入轨道姿态、
 * 也不参与几何预检（几何预检只看水平姿态：平移 + 方向）。
 * <p>
 * <b>写入顺序</b>：{@code BE_SYNC} → {@code NODE_REFRESH_RAIL}。姿态（平移）现在也随刷新请求一起发送，
 * 所以顺序不再影响正确性（见 {@code ModNetwork.handleNodeRefreshRail} 的说明），这里保持固定顺序只为数据流统一。
 * <p>
 * <b>几何预检</b>：每次改动平移或旋转都会用 {@link NodeConnector#hasValidGeometry} 做一次纯客户端、
 * 不发包的几何预检；预检不通过时显示红字警告并<b>跳过</b>重建请求，避免触发一次注定失败的重建。
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

    /** 平移步进：1/16 格，与 {@code ObjBlockConfigScreen} 的物件平移一致。 */
    private static final float TRANSLATE_STEP = 0.0625f;
    private static final float TRANSLATE_MIN = -1f;
    private static final float TRANSLATE_MAX = 1f;

    /**
     * 方向（Y 旋转）取值区间（度）。
     * <p>
     * 万向节点支持任意角度，MTR 原版 22.5° 的步进限制在这里不适用，因此步进为 0（连续）。
     * 只用 [0,180] 表示：直线轨道上 180° 与 0° 是同一条线，
     * 服务端 {@code NodeConnector.refreshNodeRail} 还会再做一次 normalizeDegrees。
     */
    private static final float DIRECTION_MIN = 0f;
    private static final float DIRECTION_MAX = 180f;
    private static final float DIRECTION_STEP = 0f;

    /**
     * 俯仰角（纵坡）取值区间（度），与 {@link BlockEntityMultiDirectionNode#MAX_PITCH_DEG} 保持一致，
     * 步进 0.5°。正值 = 沿节点方向前进时上坡。
     */
    private static final float PITCH_MIN = -15f;
    private static final float PITCH_MAX = 15f;
    private static final float PITCH_STEP = 0.5f;

    /**
     * 翻滚角（外轨超高）取值区间（度），与 {@link BlockEntityMultiDirectionNode#MAX_ROLL_DEG} 保持一致，
     * 步进 0.5°。正值 = 前进方向右手侧抬高。
     */
    private static final float ROLL_MIN = -20f;
    private static final float ROLL_MAX = 20f;
    private static final float ROLL_STEP = 0.5f;

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
     * 俯仰角（度，纵坡）。正 = 沿方向前进时上坡。
     * <p>
     * <b>P3 阶段</b>：只写进节点（BE 数据 + 节点模型倾斜），不会触发轨道重建，
     * 也不参与 {@link #refreshPoseValidity()} 的几何预检。
     */
    private double pitchDeg;
    /** 翻滚角（度，外轨超高）。正 = 前进方向右手侧抬高。阶段约束同 {@link #pitchDeg}。 */
    private double rollDeg;
    /**
     * 旋转绑定开关（仅右列，锁定时恒为 true）。
     * <p>
     * 是 → 写方向时用 {@code setDirectionAndBind}（绑定）；否 → 用 {@code setDirectionUnbound}（只写值、不绑定）。
     * 初值取节点当前的 {@code directionBonded}；节点已连接时强制为「是」并锁定（服务端重建轨道依赖绑定方向）。
     */
    private boolean rotationBonded;

    /** 当前编辑的「平移 + 方向」是否无法产出合法轨道（由 {@link #refreshPoseValidity()} 维护）。 */
    private boolean poseInvalid;

    private boolean useSliderInput = true;

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

        // 滑块 / 输入框切换：与 ObjBlockConfigScreen 的同一个按钮，作用于平移与方向两节
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

    private Component getInputToggleLabel() {
        return ComponentHelper.translatable(useSliderInput
                ? "ui.fangsu.block.toggle_input"
                : "ui.fangsu.block.toggle_slider");
    }

    // ==================== 可滚动内容 ====================

    @Override
    protected void buildScrollableContent(ContentLayout layout) {
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
                (float) offsetX, TRANSLATE_MIN, TRANSLATE_MAX, TRANSLATE_STEP, v -> setOffset(0, v), this::applyOffsets);
        yLeft = addAxisRow(leftX, yLeft, columnWidth, ComponentHelper.translatable("ui.fangsu.multi_direction_node.offset_y"),
                (float) offsetY, TRANSLATE_MIN, TRANSLATE_MAX, TRANSLATE_STEP, v -> setOffset(1, v), this::applyOffsets);
        yLeft = addAxisRow(leftX, yLeft, columnWidth, ComponentHelper.translatable("ui.fangsu.multi_direction_node.offset_z"),
                (float) offsetZ, TRANSLATE_MIN, TRANSLATE_MAX, TRANSLATE_STEP, v -> setOffset(2, v), this::applyOffsets);

        // ---- 右列：节点旋转（方向 + 俯仰 + 翻滚）+ 旋转绑定开关 ----
        // 俯仰 / 翻滚取代原先的两行「预留轴」占位（addReservedRow 已不再用于本界面）。
        // 标签按概念命名（俯仰角 / 翻滚角）而不是轴字母：这两个量是铁路语义，
        // 不是「绕方块 X/Z 轴转」，用轴字母会误导用户与后续维护者。
        yRight = addAxisRow(rightX, yRight, columnWidth, ComponentHelper.translatable("ui.fangsu.multi_direction_node.pitch"),
                (float) pitchDeg, PITCH_MIN, PITCH_MAX, PITCH_STEP, v -> setPitch(v), this::applyAngles);
        yRight = addAxisRow(rightX, yRight, columnWidth, ComponentHelper.translatable("ui.fangsu.multi_direction_node.rotY"),
                (float) direction, DIRECTION_MIN, DIRECTION_MAX, DIRECTION_STEP, v -> setDirection(v), this::applyDirection);
        yRight = addAxisRow(rightX, yRight, columnWidth, ComponentHelper.translatable("ui.fangsu.multi_direction_node.roll"),
                (float) rollDeg, ROLL_MIN, ROLL_MAX, ROLL_STEP, v -> setRoll(v), this::applyAngles);
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

        // 外轨超高：本步骤只占位（active=false）。节点侧的俯仰 / 翻滚编辑已在右列接好，
        // 但「让角度真正作用到轨道截面与车体」属于下一步，所以这里仍然保持灰显不接线。
        final Button buttonSuperelevation = addButton(leftX, y, fullWidth, 20,
                ComponentHelper.translatable("ui.fangsu.multi_direction_node.superelevation"), b -> {
                });
        buttonSuperelevation.active = false;
        addEntry(buttonSuperelevation, y);
    }

    /**
     * 一行「标签 + 数值控件」，按当前输入模式在滑块 / 输入框之间切换，
     * 行构造与 {@code ObjBlockConfigScreen} 的物件平移完全一致。
     */
    private int addAxisRow(int areaLeft, int y, int rowWidth, Component label,
                           float value, float min, float max, float step,
                           Consumer<Float> setter, Runnable onChanged) {
        return useSliderInput
                ? addCompactTwoRow(areaLeft, y, rowWidth, label, value, min, max, step, setter, onChanged, true)
                : addAxisInputTwoRow(areaLeft, y, rowWidth, label, value, min, max, step, setter, onChanged, true);
    }

    /**
     * 预留按钮（当前界面已不再使用）。
     * <p>
     * P3 之前「旋转 X / Z」用本方法摆放两行灰显占位按钮；P3 把它们换成了真实可编辑的
     * 俯仰角 / 翻滚角（见 {@link #addAxisRow}），轨道编辑区的「外轨超高」按钮则仍直接
     * 设 {@code active = false}，不需要这个方法。保留实现是为了后续再出现「预留功能」
     * 时不必重写（语法上与 {@link #addAxisRow} 的行高保持一致）。
     */
    @SuppressWarnings("unused")
    private int addReservedRow(int areaLeft, int y, int rowWidth, Component label) {
        addEntry(createTextLabel(areaLeft, y, label, TextLabel.Align.LEFT, 0x888888, false), y);
        y += 8;
        final int width = useSliderInput ? Math.max(60, rowWidth) : Math.min(rowWidth, 80);
        final Button button = addButton(areaLeft, y, width, 20,
                ComponentHelper.translatable("ui.fangsu.multi_direction_node.reserved"), b -> {
                });
        button.active = false;
        addEntry(button, y);
        return y + 22;
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

    /** 滑块模式的一行（照 {@code ObjBlockConfigScreen#addCompactTwoRow}）。 */
    private int addCompactTwoRow(int areaLeft, int y, int rowWidth,
                                 Component label, float value,
                                 float min, float max, float step,
                                 Consumer<Float> setter, Runnable onChanged,
                                 boolean compact) {
        final int labelHeight = compact ? 8 : 10;
        addEntry(createTextLabel(areaLeft, y, label, TextLabel.Align.LEFT, 0xFFFFFF, false), y);
        y += labelHeight;
        int sliderWidth = Math.max(rowWidth, 60);
        final SliderWidget slider = new SliderWidget(areaLeft, y, sliderWidth, 20,
                ComponentHelper.empty(), value, min, max, step,
                v -> {
                    setter.accept(v);
                    onChanged.run();
                });
        addRenderableWidget(slider);
        addEntry(slider, y);
        return y + 20 + (compact ? 2 : 4);
    }

    /** 输入框模式的一行（照 {@code ObjBlockConfigScreen#addAxisInputTwoRow}）。 */
    private int addAxisInputTwoRow(int areaLeft, int y, int rowWidth,
                                   Component label, float value,
                                   float min, float max, float step,
                                   Consumer<Float> setter, Runnable onChanged,
                                   boolean compact) {
        final int labelHeight = compact ? 8 : 10;
        addEntry(createTextLabel(areaLeft, y, label, TextLabel.Align.LEFT, 0xFFFFFF, false), y);
        y += labelHeight;
        final int inputWidth = Math.min(rowWidth, 80);
        final EditBox box = new EditBox(this.font, areaLeft, y, inputWidth, 20, ComponentHelper.empty());
        box.setValue(formatValue(value));
        box.setResponder(text -> {
            final Float v = parseFloat(text);
            if (v == null) {
                return;
            }
            setter.accept(v);
            onChanged.run();
        });
        addRenderableWidget(box);
        addEntry(box, y);
        return y + 20 + (compact ? 2 : 4);
    }

    // ==================== 节点写入（平移 / 方向） ====================

    private void setOffset(int axis, float value) {
        final double clamped = BlockEntityMultiDirectionNode.clampOffset(value);
        switch (axis) {
            case 0 -> offsetX = clamped;
            case 1 -> offsetY = clamped;
            default -> offsetZ = clamped;
        }
    }

    private void setDirection(float value) {
        // 钳制而不是取模：滑块拖到端点时若取模会立刻跳回另一端，与滑块自身位置不一致
        direction = Mth.clamp(value, DIRECTION_MIN, DIRECTION_MAX);
    }

    /** 俯仰角：钳制到 ±15°（与 BE 的 clampPitch 同一上限，双重保险）。 */
    private void setPitch(float value) {
        pitchDeg = BlockEntityMultiDirectionNode.clampPitch(Mth.clamp(value, PITCH_MIN, PITCH_MAX));
    }

    /** 翻滚角：钳制到 ±20°（与 BE 的 clampRoll 同一上限）。 */
    private void setRoll(float value) {
        rollDeg = BlockEntityMultiDirectionNode.clampRoll(Mth.clamp(value, ROLL_MIN, ROLL_MAX));
    }

    /**
     * 俯仰 / 翻滚实时写入：写数据 + 立即 BE_SYNC，<b>不</b>请求轨道重建。
     * <p>
     * <b>P3 阶段边界</b>：这两个角度还没有写进轨道姿态（{@code RailPoseExtra}），
     * 重建出来的轨道与旧轨逐字节相同，发 {@code NODE_REFRESH_RAIL} 只会白做一次删+建，
     * 所以这里刻意不调用 {@link #tryRefreshRails()}，也就自然不会经过几何预检。
     * 下一步把角度接进轨道姿态时，再在这里补上 {@code tryRefreshRails()}。
     * <p>
     * 与平移 / 方向一致：即时保存（不点「保存并退出」也生效）。
     */
    private void applyAngles() {
        if (node == null) return;
        node.setAnglesAndSync(pitchDeg, rollDeg);
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
     * 关闭前兜底：把方向与平移整体再写一次，避免切换输入模式等重建过程丢失最后一次输入。
     * <p>
     * 顺序仍是 BE_SYNC → NODE_REFRESH_RAIL；姿态非法时同样跳过重建（旧轨道保持不动）。
     */
    private void save() {
        if (node == null) return;
        node.setNodeOffset(offsetX, offsetY, offsetZ);
        // P3：俯仰 / 翻滚一起兜底落盘（不触发轨道重建，理由见 applyAngles）
        node.setAnglesAndSync(pitchDeg, rollDeg);
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
