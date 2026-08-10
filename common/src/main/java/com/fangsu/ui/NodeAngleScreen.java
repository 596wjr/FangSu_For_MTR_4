package com.fangsu.ui;

import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import com.fangsu.mappings.ComponentHelper;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.Rail;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.generated.lang.TranslationProvider;
import org.mtr.mod.packet.PacketUpdateData;
import org.mtr.mod.packet.PacketUpdateLastRailStyles;
import org.mtr.mod.screen.RailStyleSelectorScreen;

import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 万向节点角度配置界面。
 * <p>
 * 提供 -22.5° / +22.5° 步进按钮、精确角度输入框、"绑定并保存"，以及
 * 轨道形状与功能编辑按钮（与原版 MTR 轨道修改界面一致）。当未选中轨道时
 * 轨道相关按钮不可用（灰色）。
 * 该屏幕为纯客户端界面，最终状态通过 {@link BlockEntityMultiDirectionNode#sendUpdateC2S()}
 * 同步到服务端（服务端 {@code readC2S} 后负责实际的轨道刷新）。
 */
public class NodeAngleScreen extends Screen {

    private final BlockEntityMultiDirectionNode node;
    @Nullable
    private final Rail rail;
    private double angle;

    // 轨道编辑状态
    private Rail.Shape shape;
    private double radius;
    private final double maxRadius;

    private EditBox angleInput;
    // 半径编辑行（仅 TWO_RADII 形状时创建，复刻原版 RailModifierScreen）
    private EditBox radiusInput;
    private Button[] radiusButtons;
    private static final String[] RADIUS_BUTTON_LABELS = {"-10", "-1", "-0.1", "+0.1", "+1", "+10"};
    private static final double[] RADIUS_BUTTON_STEPS = {-10, -1, -0.1, 0.1, 1, 10};

    private final Consumer<Double> onSave;

    public NodeAngleScreen(BlockEntityMultiDirectionNode node, @Nullable Rail rail, Consumer<Double> onSave) {
        super(ComponentHelper.translatable("ui.fangsu.multi_direction_node.title"));
        this.node = node;
        this.rail = rail;
        this.angle = node.getDirectionDegrees();
        this.onSave = onSave;
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

    @Override
    protected void init() {
        super.init();

        final int centerX = this.width / 2;
        final int yBase = this.height / 2 - 60;

        //#if MC_VERSION >= 11903
        angleInput = new EditBox(this.font, centerX - 80, yBase - 10, 160, 20, ComponentHelper.translatable("ui.fangsu.multi_direction_node.angle"));
        angleInput.setValue(String.valueOf((float) angle));
        this.addRenderableWidget(angleInput);

        this.addRenderableWidget(Button.builder(ComponentHelper.translatable("ui.fangsu.multi_direction_node.m22"), b -> step(-22.5)).bounds(centerX - 80, yBase + 20, 77, 20).build());
        this.addRenderableWidget(Button.builder(ComponentHelper.translatable("ui.fangsu.multi_direction_node.p22"), b -> step(22.5)).bounds(centerX + 4, yBase + 20, 77, 20).build());
        this.addRenderableWidget(Button.builder(ComponentHelper.translatable("ui.fangsu.multi_direction_node.bind_and_save"), b -> saveAndClose()).bounds(centerX - 80, yBase + 48, 160, 20).build());

        // ---- 轨道形状与功能按钮（与原版 MTR RailModifierScreen 行为一致） ----
        final int railY = yBase + 76;
        final boolean hasRail = rail != null;

        // 轨道形状切换
        final Component shapeLabel = hasRail && shape == Rail.Shape.TWO_RADII
                ? TranslationProvider.GUI_MTR_RAIL_SHAPE_TWO_RADII.getMutableText().data
                : TranslationProvider.GUI_MTR_RAIL_SHAPE_QUADRATIC.getMutableText().data;
        final Button buttonShape = Button.builder(shapeLabel, b -> toggleRailShape()).bounds(centerX - 80, railY, 160, 20).build();
        buttonShape.active = hasRail;
        this.addRenderableWidget(buttonShape);

        // 编辑样式 / 反转样式
        final Button buttonStyles = Button.builder(TranslationProvider.GUI_MTR_RAIL_STYLES.getMutableText().data, b -> openStyleSelector()).bounds(centerX - 80, railY + 28, 77, 20).build();
        buttonStyles.active = hasRail;
        this.addRenderableWidget(buttonStyles);

        final Button buttonFlip = Button.builder(TranslationProvider.GUI_MTR_FLIP_STYLES.getMutableText().data, b -> flipStyles()).bounds(centerX + 4, railY + 28, 77, 20).build();
        buttonFlip.active = hasRail;
        this.addRenderableWidget(buttonFlip);

        // ---- 半径编辑行（仅"两个半径"形状时创建，复刻原版 RailModifierScreen 的 update 逻辑）----
        final boolean showRadius = hasRail && shape == Rail.Shape.TWO_RADII;
        radiusInput = new EditBox(this.font, centerX - 80, railY + 56, 160, 20, ComponentHelper.translatable("ui.fangsu.multi_direction_node.radius"));
        radiusInput.setValue(String.valueOf(radius));
        radiusInput.setResponder(text -> {
            try {
                updateRadius(Double.parseDouble(text), true);
            } catch (NumberFormatException ignored) {
            }
        });
        radiusInput.visible = showRadius;
        this.addRenderableWidget(radiusInput);

        radiusButtons = new Button[RADIUS_BUTTON_LABELS.length];
        for (int i = 0; i < RADIUS_BUTTON_LABELS.length; i++) {
            final int index = i;
            final Button radiusButton = Button.builder(Component.literal(RADIUS_BUTTON_LABELS[i]), b -> updateRadius(radius + RADIUS_BUTTON_STEPS[index], true))
                    .bounds(centerX - 80 + i * 27, railY + 80, 25, 20)
                    .build();
            radiusButton.visible = showRadius;
            // 减按钮仅在 radius > 0 时可用，加按钮仅在 radius < maxRadius 时可用（与原版一致）
            radiusButton.active = RADIUS_BUTTON_STEPS[i] < 0 ? radius > 0 : radius < maxRadius;
            radiusButtons[i] = radiusButton;
            this.addRenderableWidget(radiusButton);
        }
        //#else
        //$$ angleInput = new EditBox(this.font, centerX - 80, yBase - 10, 160, 20, ComponentHelper.translatable("ui.fangsu.multi_direction_node.angle"));
        //$$ angleInput.setValue(String.valueOf((float) angle));
        //$$ this.addRenderableWidget(angleInput);
        //$$ this.addRenderableWidget(new Button(centerX - 80, yBase + 20, 77, 20, ComponentHelper.translatable("ui.fangsu.multi_direction_node.m22"), b -> step(-22.5)));
        //$$ this.addRenderableWidget(new Button(centerX + 4, yBase + 20, 77, 20, ComponentHelper.translatable("ui.fangsu.multi_direction_node.p22"), b -> step(22.5)));
        //$$ this.addRenderableWidget(new Button(centerX - 80, yBase + 48, 160, 20, ComponentHelper.translatable("ui.fangsu.multi_direction_node.bind_and_save"), b -> saveAndClose()));
        //$$
        //$$ final int railY = yBase + 76;
        //$$ final boolean hasRail = rail != null;
        //$$ final Component shapeLabel = hasRail && shape == Rail.Shape.TWO_RADII
        //$$         ? TranslationProvider.GUI_MTR_RAIL_SHAPE_TWO_RADII.getMutableText().data
        //$$         : TranslationProvider.GUI_MTR_RAIL_SHAPE_QUADRATIC.getMutableText().data;
        //$$ final Button buttonShape = new Button(centerX - 80, railY, 160, 20, shapeLabel, b -> toggleRailShape());
        //$$ buttonShape.active = hasRail;
        //$$ this.addRenderableWidget(buttonShape);
        //$$ final Button buttonStyles = new Button(centerX - 80, railY + 28, 77, 20, TranslationProvider.GUI_MTR_RAIL_STYLES.getMutableText().data, b -> openStyleSelector());
        //$$ buttonStyles.active = hasRail;
        //$$ this.addRenderableWidget(buttonStyles);
        //$$ final Button buttonFlip = new Button(centerX + 4, railY + 28, 77, 20, TranslationProvider.GUI_MTR_FLIP_STYLES.getMutableText().data, b -> flipStyles());
        //$$ buttonFlip.active = hasRail;
        //$$ this.addRenderableWidget(buttonFlip);
        //#endif
    }

    private void step(double delta) {
        angle = normalize(angle + delta);
        angleInput.setValue(String.valueOf((float) angle));
    }

    private void saveAndClose() {
        try {
            angle = normalize(Double.parseDouble(angleInput.getValue()));
        } catch (NumberFormatException ignored) {
        }
        node.setDirectionAndBind(angle);
        if (onSave != null) {
            onSave.accept(angle);
        }
        this.onClose();
    }

    // ==================== 轨道编辑 ====================

    private void toggleRailShape() {
        if (rail == null) return;
        shape = shape == Rail.Shape.QUADRATIC ? Rail.Shape.TWO_RADII : Rail.Shape.QUADRATIC;
        radius = Utilities.clamp(Utilities.round(radius, 2), 0, maxRadius);
        sendRailPacket(Rail.copy(rail, shape, radius));
        // 重建界面以更新形状标签
        this.clearWidgets();
        this.init();
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
        if (minecraft.player != null) {
            InitClient.REGISTRY_CLIENT.sendPacketToServer(
                    new PacketUpdateLastRailStyles(minecraft.player.getUUID(), rail.getTransportMode(), styles)
            );
        }
    }

    private void sendRailPacket(Rail updatedRail) {
        InitClient.REGISTRY_CLIENT.sendPacketToServer(
                new PacketUpdateData(new UpdateDataRequest(MinecraftClientData.getInstance()).addRail(updatedRail))
        );
    }

    /**
     * 更新半径并（可选）发送轨道更新包。复刻原版 RailModifierScreen.update 的 clamp/回写/发包逻辑。
     */
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

    //#if MC_VERSION >= 12000
    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        //#else
        //$$@Override
        //$$public void render(com.mojang.blaze3d.vertex.PoseStack graphics, int mouseX, int mouseY, float partialTick) {
        //$$    super.render(graphics, mouseX, mouseY, partialTick);
        //#endif
        // 半径输入框标签（1.19.4 及以下 Screen.render 为 PoseStack 签名；1.19.2- 无 getX()/getY()，用公开字段 x/y）
        if (radiusInput != null && radiusInput.visible) {
            //#if MC_VERSION >= 12000
            graphics.drawString(this.font, ComponentHelper.translatable("ui.fangsu.multi_direction_node.radius"), radiusInput.getX(), radiusInput.getY() - 10, 0xFFFFFF);
            //#elseif MC_VERSION >= 11903
            //$$this.font.draw(graphics, ComponentHelper.translatable("ui.fangsu.multi_direction_node.radius"), radiusInput.getX(), radiusInput.getY() - 10, 0xFFFFFF);
            //#else
            //$$this.font.draw(graphics, ComponentHelper.translatable("ui.fangsu.multi_direction_node.radius"), radiusInput.x, radiusInput.y - 10, 0xFFFFFF);
            //#endif
        }
    }

    private static double normalize(double v) {
        v = v % 360.0;
        if (v < 0) v += 360.0;
        return v;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
