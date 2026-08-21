package com.fangsu.data;

import com.fangsu.render.sowcer.math.Matrix4f;
import org.mtr.core.data.Lift;

/**
 * MTR4 电梯渲染上下文（渲染线程单帧有效）。因 MTR4 的 RenderLifts 走 scheduled render，
 * 需在构造 ModelLift1 前捕获当前电梯与相机模型矩阵，供拼装模型在 drawScheduler GL 路径使用。
 */
public final class LiftRenderState {

    public static Lift currentLift;
    public static Matrix4f mvMatrix = Matrix4f.IDENTITY;
    public static int light;
    public static float doorValue;
    public static int width = 2;
    public static int depth = 2;
    public static int height = 3;

    private LiftRenderState() {
    }

    public static void reset() {
        currentLift = null;
    }
}
