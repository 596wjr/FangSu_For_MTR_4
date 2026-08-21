package com.fangsu.mixin;

import com.fangsu.data.LiftRenderState;
import com.fangsu.mtr.ModernTexturedLift;
import com.fangsu.render.sowcer.math.Matrix4f;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.mtr.core.data.Lift;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.model.ModelLift1;
import org.mtr.mod.render.RenderLifts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MTR4 电梯渲染覆盖：
 * <ul>
 *   <li>把 {@code new ModelLift1(...)} 替换为 {@link ModernTexturedLift}。</li>
 *   <li>在渲染语句里 {@code lift.getStyle()} 调用处捕获当前电梯（@Redirect 实例方法回调会拿到 receiver
 *       作为首参），据此计算相机模型矩阵存入 {@link LiftRenderState}，供拼装模型在 DrawScheduler 路径使用。</li>
 * </ul>
 */
@Mixin(value = RenderLifts.class, remap = false)
public class RenderLiftsMixin {

    @Redirect(method = "lambda$render$6", at = @At(value = "NEW", target = "Lorg/mtr/mod/model/ModelLift1;"))
    private static ModelLift1 fangsu$createLiftModel(int height, int width, int depth, boolean isDoubleSided) {
        return new ModernTexturedLift(height, width, depth, isDoubleSided);
    }

    @Redirect(method = "lambda$render$6", at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Lift;getStyle()Ljava/lang/String;"))
    private static String fangsu$captureState(Lift lift) {
        try {
            LiftRenderState.currentLift = lift;
            LiftRenderState.width = (int) Math.round(lift.getWidth());
            LiftRenderState.depth = (int) Math.round(lift.getDepth());
            LiftRenderState.height = (int) Math.round(lift.getHeight());
            LiftRenderState.doorValue = lift.getDoorValue() / 0.75F;

            final Vector pos = lift.getPosition((p1, p2) -> new ObjectArrayList<>());
            final double yaw = (-Math.PI / 2D) - lift.getAngle().angleRadians;

            final Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            final org.joml.Quaternionf camRot = new org.joml.Quaternionf().rotationYXZ(
                    (float) Math.toRadians(-camera.getYRot()),
                    (float) Math.toRadians(camera.getXRot()),
                    0);
            final Matrix4f mv = new Matrix4f(new org.joml.Matrix4f().rotation(camRot));
            mv.translate((float) (pos.x - camera.getPosition().x),
                    (float) (pos.y - camera.getPosition().y),
                    (float) (pos.z - camera.getPosition().z));
            mv.rotateY((float) (yaw + Math.PI));
            LiftRenderState.mvMatrix = mv;
        } catch (Exception e) {
            LiftRenderState.currentLift = null;
        }
        return lift.getStyle();
    }
}
