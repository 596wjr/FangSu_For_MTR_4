package com.fangsu.mtr;

import com.fangsu.MainClient;
import com.fangsu.customItem.CustomMtrLifts;
import com.fangsu.data.LiftRenderState;
import com.fangsu.render.lift.CustomLiftModel;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.model.ModelLift1;
import org.mtr.mod.resource.RenderStage;

/**
 * MTR4 版拼装电梯渲染器：当当前电梯的 style（MTR 原生样式 id）对应"自定义拼装"模型时，
 * 用拼装好的 CustomLiftModel 走 DrawScheduler GL 路径渲染；否则回退默认几何。
 * style 由 MTR 原生的 LiftStyleSelectorScreen / lift.getStyle() 选择并持久化。
 */
public class ModernTexturedLift extends ModelLift1 {

    public ModernTexturedLift(int height, int width, int depth, boolean isDoubleSided) {
        super(height, width, depth, isDoubleSided);
    }

    @Override
    protected void render(GraphicsHolder graphicsHolder, RenderStage renderStage, int light,
                          float doorLeftX, float doorRightX, float doorLeftZ, float doorRightZ,
                          int currentCar, int trainCars, boolean head1IsFront, boolean renderDetails) {
        if (LiftRenderState.currentLift != null) {
            final String style = LiftRenderState.currentLift.getStyle();
            final CustomMtrLifts.AssembledLiftSelectInfo assembled =
                    CustomMtrLifts.getInstance().getAssembledLiftSelectInfo(style);
            if (assembled != null) {
                // 每个 RenderStage 会调用一次，仅在 INTERIOR 阶段入队一次，避免重复。
                if (renderStage == RenderStage.INTERIOR) {
                    final org.mtr.core.data.LiftDirection dir = LiftRenderState.currentLift.getDirection();
                    final com.fangsu.render.lift.LiftModelAssembler.LiftConditionContext cond =
                            new com.fangsu.render.lift.LiftModelAssembler.LiftConditionContext(
                                    dir == org.mtr.core.data.LiftDirection.UP,
                                    dir == org.mtr.core.data.LiftDirection.DOWN,
                                    dir == org.mtr.core.data.LiftDirection.NONE);
                    final CustomLiftModel customLift =
                            CustomLiftModel.get(assembled.getProperties(), assembled.getModel(), assembled.getTexture());
                    customLift.enqueueGL(MainClient.drawScheduler, LiftRenderState.mvMatrix, light,
                            LiftRenderState.doorValue,
                            LiftRenderState.width, LiftRenderState.depth, LiftRenderState.height, cond);

                    // 自定义 DISPLAY 部位：在指定位置绘制当前楼层号
                    for (com.fangsu.render.lift.LiftModelAssembler.DisplayInfo disp : customLift.getDisplays()) {
                        if (!"FLOOR".equalsIgnoreCase(disp.displayType)) continue;
                        graphicsHolder.push();
                        graphicsHolder.translate(disp.position.x / 16, disp.position.y / 16, disp.position.z / 16);
                        final String floorText = LiftRenderState.currentLift.getCurrentFloor().getNumber();
                        org.mtr.mod.client.IDrawing.drawStringWithFont(graphicsHolder, floorText,
                                org.mtr.mod.data.IGui.HorizontalAlignment.CENTER, org.mtr.mod.data.IGui.VerticalAlignment.BOTTOM,
                                0, 0.3125F, 0.1875F, -1, 18F / 0.1875F, 0xFF0000, false,
                                org.mtr.mapping.mapper.GraphicsHolder.getDefaultLight(),
                                (org.mtr.mod.client.IDrawing.DrawingCallback) null);
                        graphicsHolder.pop();
                    }
                }
                return;
            }
        }
        super.render(graphicsHolder, renderStage, light, doorLeftX, doorRightX, doorLeftZ, doorRightZ,
                currentCar, trainCars, head1IsFront, renderDetails);
    }
}
