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
import com.fangsu.utils.GraphicsTextureHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.RenderVehicles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

@Mixin(value = RenderVehicles.class, remap = false)
public class RenderVehiclesMixin {

    @Inject(method = "render(JLorg/mtr/mapping/holder/Vector3d;)V", at = @At(value = "INVOKE", target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArraySet;forEach(Ljava/util/function/Consumer;)V"))
    private static void fangsu$executeScript(long millisElapsed, Vector3d cameraShakeOffset, CallbackInfo ci) {
        try {
            final LcdDrawManager drawManager = LcdDrawManager.getInstance();
            final GraphicsTextureHelper gtHelper = GraphicsTextureHelper.getInstance();

            for (VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
                final long vehicleId = vehicle.getId();
                final var cars = vehicle.vehicleExtraData.immutableVehicleCars;
                if (cars.isEmpty()) continue;

                final String vid = cars.get(0).getVehicleId();
                final LcdVehicleEntry lcdEntry = LcdVehicleRegistry.match(vid);
                if (lcdEntry == null) continue;

                // 初始化 LCD 纹理（仅首次）
                if (!LcdVehicleRegistry.isVehicleInitialized(vehicleId)) {
                    LcdVehicleRegistry.markVehicleInitialized(vehicleId);

                    final LcdInfo resolvedInfo = lcdEntry.lcdInfo().resolveSlots();
                    drawManager.putLcdInfo(vehicleId, resolvedInfo);
                    drawManager.putTrainStatus(vehicleId, new TrainStatus());

                    final LcdBase lcd = LcdManager.getInstance().getLcd(resolvedInfo.id());
                    if (lcd == null) continue;
                    drawManager.putLcd(vehicleId, lcd);

                    // 添加 renderType 使 LCD 渲染在 translucent 层，避免被列车外皮遮挡
                    resolvedInfo.slotsInfo().addProperty("renderType", "interiortranslucent");
                    final DisplayHelper dhB = new DisplayHelper(resolvedInfo.slotsInfo());
                    drawManager.putDh(vehicleId, dhB.create());

                    final var tsa = resolvedInfo.slotsInfo().getAsJsonArray("texSize");
                    gtHelper.addDrawGraphicWithGt("train_lcd_" + vehicleId,
                            new GraphicsTextureHelper.DrawInfo("train_lcd_tex_" + vehicleId, tsa.get(0).getAsInt(), tsa.get(1).getAsInt(), false, true),
                            (gt) -> {
                                final TrainStatus ts = drawManager.getTrainStatusForVehicle(vehicleId);
                                final LcdInfo info = drawManager.getLcdInfo(vehicleId);
                                final LcdBase li = drawManager.getLcdForVehicle(vehicleId);
                                if (ts == null || info == null || li == null) return;
                                final JsonArray slots = info.slotsInfo().getAsJsonArray("slots");
                                final Map<String, Object> st = new HashMap<>();
                                for (JsonElement se : slots) {
                                    final JsonObject so = se.getAsJsonObject();
                                    li.draw(gt.graphics, ts, info, st, so.get("name").getAsString(),
                                            so.getAsJsonArray("texArea").get(0).getAsInt(),
                                            so.getAsJsonArray("texArea").get(1).getAsInt(),
                                            so.getAsJsonArray("texArea").get(2).getAsInt(),
                                            so.getAsJsonArray("texArea").get(3).getAsInt(), () -> {});
                                }
                            });
                    Main.LOGGER.info("[FangSu LCD] Init {}", vehicleId);
                }

                // 获取车辆位置并渲染 LCD（传入摄像机位置以计算相对坐标）
                renderLcdCar(vehicle, vehicleId, cameraShakeOffset);
            }
        } catch (Exception e) {
            Main.LOGGER.error("[FangSu LCD] Error", e);
        }
    }

    private static int _debugCnt = 0;

    /** 计算车辆位置并渲染 LCD */
    private static void renderLcdCar(VehicleExtension vehicle, long vehicleId, Vector3d cameraOffset) {
        final LcdDrawManager drawManager = LcdDrawManager.getInstance();
        final DisplayHelper dh = drawManager.getDhForVehicle(vehicleId);
        if (dh == null) { if (_debugCnt < 5) { Main.LOGGER.info("[LCD] dh null {}", vehicleId); _debugCnt++; } return; }

        final GraphicsTexture tex = GraphicsTextureHelper.getInstance().getGraphics("train_lcd_" + vehicleId);
        if (tex == null) { if (_debugCnt < 5) { Main.LOGGER.info("[LCD] tex null {}", vehicleId); _debugCnt++; } return; }

        dh.changeSharedGt(tex);
        final var model = dh.getUploadedModelOrNull();
        if (model == null) { if (_debugCnt < 5) { Main.LOGGER.info("[LCD] model null {}", vehicleId); _debugCnt++; } return; }
        model.replaceAllTexture(tex.identifier);

        try {
            final var trainWrapper = new com.lx862.mtrscripting.mod.impl.mtr.vehicle.NTETrainWrapper(
                    com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleScriptContext.DataFetchMode.SKIP, vehicle);

            for (int i = 0; i < trainWrapper.getCarCount(); i++) {
                final var carPos = trainWrapper.lastCarPosition[i];
                final var carRot = trainWrapper.lastCarRotation[i];
                if (carPos == null || carRot == null) {
                    if (_debugCnt < 10) Main.LOGGER.info("[LCD] car {} null pos/rot", i);
                    continue;
                }

                // 复制当前 ModelView 矩阵（视图矩阵），然后乘上模型变换
                // ⚠ 必须用 copy()，Matrix4f(org.joml.Matrix4f) 构造函数是直接引用！
                final Matrix4f mvMatrix = new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix()).copy();
                mvMatrix.translate(carPos.x(), carPos.y(), carPos.z());
                mvMatrix.rotateY(carRot.y());

                new AbstractDrawCalls.ClusterDrawCall(model, Matrix4f.IDENTITY)
                        .commit(MainClient.drawScheduler, mvMatrix, 0x00F000F0);
            }
        } catch (Exception e) {
            Main.LOGGER.warn("[FangSu LCD] renderLcdCar error: {}", e.getMessage());
        }
    }
}