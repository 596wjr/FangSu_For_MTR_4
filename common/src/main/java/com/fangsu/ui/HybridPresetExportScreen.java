package com.fangsu.ui;

import com.fangsu.data.hybrid.HybridCreatorJsonIO;
import com.fangsu.mappings.ComponentHelper;
import com.fangsu.utils.GraphicContext;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/**
 * 「导出预设」屏幕：输入预设名称，导出到游戏目录 hybrid_creator/ 文件夹。
 * 文件名做安全过滤（见 {@link HybridCreatorJsonIO#write(CompoundTag, String)}）。
 */
public class HybridPresetExportScreen extends Screen {

    private final Screen parent;
    private final CompoundTag tasksTag;
    private EditBox nameField;

    public HybridPresetExportScreen(Screen parent, CompoundTag tasksTag) {
        super(ComponentHelper.translatable("ui.fangsu.hybrid_creator.export_preset.title"));
        this.parent = parent;
        this.tasksTag = tasksTag;
    }

    @Override
    protected void init() {
        nameField = new EditBox(minecraft.font, (width - 200) / 2, 64, 200, 20, Component.empty());
        nameField.setMaxLength(48);
        nameField.setHint(ComponentHelper.translatable("ui.fangsu.hybrid_creator.export_preset.name_hint"));
        addRenderableWidget(nameField);
        addRenderableWidget(Button.builder(ComponentHelper.translatable("ui.fangsu.hybrid_creator.export"),
                        button -> exportPreset())
                .bounds((width - 220) / 2, height - 60, 100, 20).build());
        addRenderableWidget(Button.builder(ComponentHelper.translatable("ui.fangsu.block.cancel"),
                button -> minecraft.setScreen(parent)).bounds((width + 20) / 2, height - 60, 100, 20).build());
        setInitialFocus(nameField);
    }

    private void exportPreset() {
        try {
            HybridCreatorJsonIO.write(tasksTag, nameField.getValue());
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(ComponentHelper.translatable(
                        "msg.fangsu.hybrid_creator.export_preset_success", nameField.getValue()), true);
            }
            minecraft.setScreen(parent);
        } catch (java.io.IOException e) {
            com.fangsu.Main.LOGGER.error("[HybridCreator] 导出预设失败", e);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(ComponentHelper.translatable("msg.fangsu.hybrid_creator.io_error"), true);
            }
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        final GraphicContext g = GraphicContext.of(graphics);
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        g.drawCenteredString(minecraft.font, ComponentHelper.translatable("ui.fangsu.hybrid_creator.export_preset.title").getString(), width / 2, 26, 0xFFFFFFFF);
        g.drawCenteredString(minecraft.font, ComponentHelper.translatable("ui.fangsu.hybrid_creator.export_preset.hint").getString(), width / 2, 44, 0xFFAAAAAA);
    }
}
