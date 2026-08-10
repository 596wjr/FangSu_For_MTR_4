package com.fangsu.mixin;

import com.fangsu.Main;
import com.fangsu.MainClient;
import com.fangsu.mtr.LcdVehicleRegistry;
import com.fangsu.mtr.LcdVehicleRegistry.LcdVehicleEntry;
import com.fangsu.render.scripting.AbstractDrawCalls;
import com.fangsu.render.sowcer.math.Matrix4f;
import com.fangsu.scripting.DisplayHelper;
import com.fangsu.scripting.GraphicsTexture;
import com.fangsu.train.*;
import com.fangsu.userScripts.ScriptHolderBase;
import com.fangsu.userScripts.ScriptManager;
import com.fangsu.utils.GraphicsTextureHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.RenderVehicles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Mixin(value = RenderVehicles.class, remap = false)
public class RenderVehiclesMixin {

    @Inject(method = "render(JLorg/mtr/mapping/holder/Vector3d;)V", at = @At(value = "INVOKE", target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArraySet;forEach(Ljava/util/function/Consumer;)V"))
    private static void fangsu$executeScript(long millisElapsed, Vector3d cameraShakeOffset, CallbackInfo ci) {
        try {
            final LcdDrawManager drawManager = LcdDrawManager.getInstance();
            final GraphicsTextureHelper gtHelper = GraphicsTextureHelper.getInstance();

            final ObjectArraySet<VehicleExtension> vehicles = MinecraftClientData.getInstance().vehicles;

            // 清扫已消失的车辆：释放其 LCD 纹理与 DisplayHelper（修复 removeVehicle 无调用方导致的泄漏）
            final Set<Long> currentIds = new HashSet<>();
            for (VehicleExtension v : vehicles) {
                currentIds.add(v.getId());
            }
            for (long seenId : drawManager.getSeenVehicleIds()) {
                if (!currentIds.contains(seenId)) {
                    drawManager.removeVehicle(seenId);
                    LcdVehicleRegistry.unmarkVehicleInitialized(seenId);
                    // 调试日志（LCD 搁置中，见 deliverables/mtr4-vehicle-lcd-notes.md）
                    // Main.LOGGER.info("[FangSu LCD] Removed vehicle {}", seenId);
                }
            }
            drawManager.setSeenVehicleIds(currentIds);

            for (VehicleExtension vehicle : vehicles) {
                final long vehicleId = vehicle.getId();
                final var cars = vehicle.vehicleExtraData.immutableVehicleCars;
                if (cars.isEmpty()) continue;

                final String vid = cars.get(0).getVehicleId();
                final LcdVehicleEntry lcdEntry = LcdVehicleRegistry.match(vid);
                if (lcdEntry == null) continue;

                // 每帧刷新车辆引用，防止绘制函数闭包捕获旧 VehicleExtension 实例
                drawManager.putVehicle(vehicleId, vehicle);

                // 初始化 LCD 纹理（仅首次）
                if (!LcdVehicleRegistry.isVehicleInitialized(vehicleId)) {
                    LcdVehicleRegistry.markVehicleInitialized(vehicleId);

                    final LcdInfo resolvedInfo = lcdEntry.lcdInfo().resolveSlots();
                    drawManager.putLcdInfo(vehicleId, resolvedInfo);
                    drawManager.putState(vehicleId, new HashMap<>());

                    // JS 脚本 LCD：lcd.script 存在时优先走脚本绘制路径（参照 JCM 给列车挂 JS 的方式）
                    final boolean scriptLcd = resolvedInfo.hasScript();
                    final ScriptHolderBase scriptHolder = scriptLcd ? LcdScriptSupport.getHolder(resolvedInfo.script()) : null;
                    final Map<String, Object> scriptInfo = scriptLcd ? LcdScriptSupport.buildInfo(resolvedInfo) : null;
                    final Map<String, Object> scriptExtra = scriptLcd ? LcdScriptSupport.buildExtraConfig(resolvedInfo) : null;

                    if (!scriptLcd) {
                        final LcdBase lcd = LcdManager.getInstance().getLcd(resolvedInfo.id());
                        if (lcd == null) {
                            // 调试日志（LCD 搁置中，见 deliverables/mtr4-vehicle-lcd-notes.md）
                            // Main.LOGGER.warn("[FangSu LCD] LCD not found for id: {}", resolvedInfo.id());
                            continue;
                        }
                        drawManager.putLcd(vehicleId, lcd);
                    }

                    // 添加 renderType 使 LCD 渲染在 translucent 层，避免被列车外皮遮挡
                    resolvedInfo.slotsInfo().addProperty("renderType", "interiortranslucent");
                    final DisplayHelper dhB = new DisplayHelper(resolvedInfo.slotsInfo());
                    drawManager.putDh(vehicleId, dhB.create());

                    final var tsa = resolvedInfo.slotsInfo().getAsJsonArray("texSize");
                    gtHelper.addDrawGraphicWithGt("train_lcd_" + vehicleId,
                            new GraphicsTextureHelper.DrawInfo("train_lcd_tex_" + vehicleId, tsa.get(0).getAsInt(), tsa.get(1).getAsInt(), false, true),
                            (gt) -> fangsu$draw(vehicleId, gt, scriptLcd, scriptHolder, scriptInfo, scriptExtra, resolvedInfo));
                    // 调试日志（LCD 搁置中，见 deliverables/mtr4-vehicle-lcd-notes.md）
                    // Main.LOGGER.info("[FangSu LCD] Init {}", vehicleId);
                }

                // 获取车辆位置并渲染 LCD（传入摄像机位置以计算相对坐标）
                renderLcdCar(vehicle, vehicleId, cameraShakeOffset);
            }
        } catch (Exception e) {
            Main.LOGGER.error("[FangSu LCD] Error", e);
        }
    }

    /**
     * 绘制 LCD 纹理（在 GraphicsTextureHelper 的绘制线程内执行）。
     * 每帧重建 NTETrainWrapper 并刷新 TrainStatus（修复 trainStatus 从不更新的问题），
     * 再按 JS 脚本 / Java 实现分派。
     */
    @Unique
    private static void fangsu$draw(long vehicleId, GraphicsTexture gt, boolean scriptLcd,
                                    ScriptHolderBase scriptHolder, Map<String, Object> scriptInfo,
                                    Map<String, Object> scriptExtra, LcdInfo resolvedInfo) {
        final LcdDrawManager drawManager = LcdDrawManager.getInstance();
        final VehicleExtension vehicle = drawManager.getVehicle(vehicleId);
        if (vehicle == null) return;

        // 每帧重建 wrapper（构造器即填充 lastCarPosition/posAndRotations；stopsData 本地同步无需网络）
        // 调试日志（LCD 搁置中，见 deliverables/mtr4-vehicle-lcd-notes.md）：
        // wrapper 构造耗时异常时打日志（正常应 <50ms）
        // final long wrapperT0 = System.nanoTime();
        final com.lx862.mtrscripting.mod.impl.mtr.vehicle.NTETrainWrapper wrapper =
                new com.lx862.mtrscripting.mod.impl.mtr.vehicle.NTETrainWrapper(
                        com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleScriptContext.DataFetchMode.SKIP, vehicle);
        // final long wrapperElapsed = System.nanoTime() - wrapperT0;
        // if (wrapperElapsed > 50_000_000L) {
        //     Main.LOGGER.warn("[FangSu LCD] NTETrainWrapper construction took {} ms", wrapperElapsed / 1_000_000L);
        // }
        final TrainStatus ts = new TrainStatus(wrapper);
        ts.updateRoute();
        ts.updateDoorState(wrapper.getDoorValue());
        drawManager.putTrainStatus(vehicleId, ts);

        if (scriptLcd) {
            // 脚本路径：每帧整张纹理重绘，无线路数据时由脚本自行处理
            if (scriptHolder == null) return; // ScriptManager 未初始化：保持纹理为空
            final Map<String, Object> st = drawManager.getState(vehicleId);
            if (st == null) return;
            ScriptManager.getInstance().requestRunFunctionSync(scriptHolder, gt::upload, "draw",
                    gt.graphics, st, ts, scriptInfo, scriptExtra);
            return;
        }

        // Java 实现路径：逐 slot 绘制
        final LcdBase li = drawManager.getLcdForVehicle(vehicleId);
        if (li == null) return;
        final JsonArray slots = resolvedInfo.slotsInfo().getAsJsonArray("slots");
        for (JsonElement se : slots) {
            final JsonObject so = se.getAsJsonObject();
            li.draw(gt.graphics, ts, resolvedInfo, new HashMap<>(),
                    so.get("name").getAsString(),
                    so.getAsJsonArray("texArea").get(0).getAsInt(),
                    so.getAsJsonArray("texArea").get(1).getAsInt(),
                    so.getAsJsonArray("texArea").get(2).getAsInt(),
                    so.getAsJsonArray("texArea").get(3).getAsInt(), () -> {});
        }
    }

    // 调试字段（LCD 搁置中，见 deliverables/mtr4-vehicle-lcd-notes.md）
    // private static int _debugCnt = 0;
    // private static int _posDebugCnt = 0;
    // private static int _meshDebugCnt = 0;

    /** 计算车辆位置并渲染 LCD */
    private static void renderLcdCar(VehicleExtension vehicle, long vehicleId, Vector3d cameraOffset) {
        final LcdDrawManager drawManager = LcdDrawManager.getInstance();
        final DisplayHelper dh = drawManager.getDhForVehicle(vehicleId);
        // 调试日志（LCD 搁置中，见 deliverables/mtr4-vehicle-lcd-notes.md）
        // if (dh == null) { if (_debugCnt < 5) { Main.LOGGER.info("[LCD] dh null {}", vehicleId); _debugCnt++; } return; }
        if (dh == null) return;

        final GraphicsTexture tex = GraphicsTextureHelper.getInstance().getGraphics("train_lcd_" + vehicleId);
        // 调试日志（LCD 搁置中）
        // if (tex == null) { if (_debugCnt < 5) { Main.LOGGER.info("[LCD] tex null {}", vehicleId); _debugCnt++; } return; }
        if (tex == null) return;

        dh.changeSharedGt(tex);
        final var model = dh.getUploadedModelOrNull();
        if (model == null) {
            // 调试日志（LCD 搁置中）：区分是模型未上传完成还是渲染引用缺失（_debugCnt 限频）
            // if (_debugCnt < 10) {
            //     Main.LOGGER.info("[LCD] model null {} (modelRef={}, uploaded={})", vehicleId,
            //             dh.model != null ? "y" : "n",
            //             dh.model != null && dh.model.getUploadedModel() != null ? "y" : "n");
            //     _debugCnt++;
            // }
            return;
        }
        model.replaceAllTexture(tex.identifier);

        // 调试日志（LCD 搁置中）：确认 LCD 模型网格是否真的构建（顶点数据存在于上传后的 VertArrays）
        // if (_meshDebugCnt < 3) {
        //     Main.LOGGER.info("[LCD] model mesh: translucentRaw={} opaqueRaw={} uploadedTranslucent={} uploadedOpaque={}",
        //             model.translucentParts.meshList.size(), model.opaqueParts.meshList.size(),
        //             model.uploadedTranslucentParts.meshList.size(), model.uploadedOpaqueParts.meshList.size());
        //     _meshDebugCnt++;
        // }

        try {
            final var trainWrapper = new com.lx862.mtrscripting.mod.impl.mtr.vehicle.NTETrainWrapper(
                    com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleScriptContext.DataFetchMode.SKIP, vehicle);

            // 调试日志（LCD 搁置中）：确认 SKIP 模式 wrapper 是否填充了车厢位置数据（限频 5 次）
            // if (_posDebugCnt < 5) {
            //     int nonNull = 0;
            //     for (int i = 0; i < trainWrapper.getCarCount(); i++) {
            //         if (trainWrapper.lastCarPosition[i] != null && trainWrapper.lastCarRotation[i] != null) nonNull++;
            //     }
            //     Main.LOGGER.info("[LCD] cars={} pos/rot non-null {}/{}", vehicleId, nonNull, trainWrapper.getCarCount());
            //     _posDebugCnt++;
            // }

            for (int i = 0; i < trainWrapper.getCarCount(); i++) {
                final var carPos = trainWrapper.lastCarPosition[i];
                final var carRot = trainWrapper.lastCarRotation[i];
                // 调试日志（LCD 搁置中）
                // if (carPos == null || carRot == null) {
                //     if (_debugCnt < 10) Main.LOGGER.info("[LCD] car {} null pos/rot", i);
                //     continue;
                // }
                if (carPos == null || carRot == null) continue;
                // 调试日志（LCD 搁置中）：JCM wrapper 的车厢坐标原值 + 相机位置/朝向
                // （与 poseDiag 的 viewMv/modelMat.trans 对照）
                // if (_posDebugCnt < 8) {
                //     final net.minecraft.client.Camera diagCam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
                //     Main.LOGGER.info("[LCD] car{} pos=({}, {}, {}) rot=({}, {}, {}) | cam pos=({}, {}, {}) yaw={} pitch={}", i,
                //             carPos.x(), carPos.y(), carPos.z(), carRot.x(), carRot.y(), carRot.z(),
                //             diagCam.getPosition().x, diagCam.getPosition().y, diagCam.getPosition().z,
                //             diagCam.getYRot(), diagCam.getXRot());
                // }

                // 模型矩阵（ModelMat 顶点属性）构建：
                //   ModelMat = R(相机旋转) × T(相对相机) × R_y(yaw+PI) × R_x(pitch+PI)
                // 依据（实测 + 源码对照）：
                // 1. 实测（21:54 诊断）：drawAll 时投影矩阵 = 纯透视（无旋转项）、ModelViewMat =
                //    identity —— 自定义 sowcer 管线里相机旋转不来自投影也不来自视图矩阵，
                //    必须由 ModelMat 自己提供；
                // 2. 相机旋转来源：MC 1.20.1 方块/实体渲染 poseStack 在 renderLevel 中
                //    mulPose(camera.rotation())（方速方块 candyPose = 该 poseStack.last().pose()
                //    在同一 drawAll 下渲染正常即佐证），LCD 的 ModelMat 需前置 R(cam)，
                //    结构 = R(cam) × T(rel) × R_car，与 candyPose 同构；
                // 3. 相机旋转公式与 MC Camera.setup 一致：rotationYXZ(-yaw, pitch, 0)
                //    （roll 恒 0；21:54 diag rotMat 与手算 Ry(-yaw)×Rx(pitch) 逐元素核对一致）；
                // 4. 车厢旋转与 MTR 4 一致：RenderVehicles.java:412-413 用 rotateYRadians(yaw+PI) ×
                //    rotateXRadians(pitch+PI)。JCM wrapper 的 lastCarRotation = (pitch, yaw+PI, 0)，
                //    pitch 是裸值须补 +PI——上版缺此 180° 导致面板翻转，与 R(cam) 叠加后全错。
                // 此前 bug 链：T(世界) → 加 R(q) 但未减相机 → T(rel) 特定视角正确 →
                // R(q)×T(rel)×R_y×R_x(裸pitch) 全错 → 本版：R(q) × T(rel) × R_y(yaw+PI) × R_x(pitch+PI)
                final net.minecraft.client.Camera camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
                // 相机旋转四元数：1.19.3+ 用 org.joml（该版本移除了 com.mojang.math 包）；
                // 1.18.2/1.19.2 用 com.mojang.math.Quaternion（仅静态 fromYXZ；1.19.2 尚未改名 Quaternionf）
                //#if MC_VERSION >= 11903
                final org.joml.Quaternionf camRot = new org.joml.Quaternionf().rotationYXZ(
                        (float) Math.toRadians(-camera.getYRot()),
                        (float) Math.toRadians(camera.getXRot()),
                        0);
                //#else
                //$$ final com.mojang.math.Quaternion camRot = com.mojang.math.Quaternion.fromYXZ(
                //$$         (float) Math.toRadians(-camera.getYRot()),
                //$$         (float) Math.toRadians(camera.getXRot()),
                //$$         0);
                //#endif
                // 1.19.3+ 的 MC 直接使用 org.joml.Matrix4f；1.19.2- 为独立类（1.18.2 有 Quaternion 构造器）
                final Matrix4f mvMatrix = new Matrix4f(
                        //#if MC_VERSION >= 11903
                        new org.joml.Matrix4f().rotation(camRot));
                        //#else
                        //$$ new com.mojang.math.Matrix4f(camRot));
                        //#endif
                mvMatrix.translate((float) (carPos.x() - camera.getPosition().x),
                        (float) (carPos.y() - camera.getPosition().y),
                        (float) (carPos.z() - camera.getPosition().z));
                mvMatrix.rotateY(carRot.y());
                mvMatrix.rotateX(carRot.x() + (float) Math.PI);

                // 调试日志（LCD 搁置中，见 deliverables/mtr4-vehicle-lcd-notes.md）：
                // 打印实际提交的 ModelMat（与 BatchManager.diag 的 proj/mv 对照，
                // 判断是否双倍旋转或缺失相机旋转）
                // if (_posDebugCnt < 8) {
                //     Main.LOGGER.info("[LCD] car{} mvMatrix=\n{}", i, mvMatrix.asMoj());
                // }

                new AbstractDrawCalls.ClusterDrawCall(model, Matrix4f.IDENTITY)
                        .commit(MainClient.drawScheduler, mvMatrix, 0x00F000F0);
            }
        } catch (Exception e) {
            Main.LOGGER.warn("[FangSu LCD] renderLcdCar error: {}", e.getMessage());
        }
    }
}
