package com.fangsu.train;

import com.fangsu.Main;
import com.fangsu.render.sowcer.math.Vector3f;
import com.fangsu.scripting.DisplayHelper;
import com.fangsu.utils.GraphicsTextureHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.NTETrainWrapper;
import org.mtr.mod.data.VehicleExtension;

import java.util.HashMap;
import java.util.Map;

public class LcdDrawManager {
    private final Map<Long, LcdBase> lcdForVehicle;
    private final Map<Long, TrainStatus> trainStatusForVehicle;
    private final Map<Long, DisplayHelper> dhForVehicle;

    /** vehicleId -> LcdInfo (lazy loaded) */
    private final Map<Long, LcdInfo> lcdInfoForVehicle;

    /** 每节车厢的世界坐标位置（由 RenderVehiclesMixin 更新） */
    private final Map<Long, Vector3f[]> carWorldPositions;

    /** 每节车厢的世界坐标旋转（由 RenderVehiclesMixin 更新） */
    private final Map<Long, float[]> carWorldYaw;

    private static final LcdDrawManager INSTANCE = new LcdDrawManager();
    public static LcdDrawManager getInstance() {return INSTANCE;}

    private LcdDrawManager() {
        lcdForVehicle = new HashMap<>();
        trainStatusForVehicle = new HashMap<>();
        dhForVehicle = new HashMap<>();
        lcdInfoForVehicle = new HashMap<>();
        carWorldPositions = new HashMap<>();
        carWorldYaw = new HashMap<>();
    }

    public void reset(){
        lcdForVehicle.clear();
        trainStatusForVehicle.clear();
        lcdInfoForVehicle.clear();
        carWorldPositions.clear();
        carWorldYaw.clear();
        dhForVehicle.forEach((id, dh)->{dh.close();});
        dhForVehicle.clear();
    }

    /**
     * 为指定列车设置 LCD 配置信息。
     */
    public void putLcdInfo(long vehicleId, LcdInfo lcdInfo) {
        lcdInfoForVehicle.put(vehicleId, lcdInfo);
    }

    public LcdInfo getLcdInfo(long vehicleId) {
        return lcdInfoForVehicle.get(vehicleId);
    }

    public void putLcd(long vehicleId, LcdBase lcd) {
        lcdForVehicle.put(vehicleId, lcd);
    }

    public LcdBase getLcdForVehicle(long vehicleId) {return lcdForVehicle.get(vehicleId);}

    public void putTrainStatus(long vehicleId, TrainStatus status) {
        trainStatusForVehicle.put(vehicleId, status);
    }

    public TrainStatus getTrainStatusForVehicle(long vehicleId) {return trainStatusForVehicle.get(vehicleId);}

    public void putDh(long vehicleId, DisplayHelper dh) {
        dhForVehicle.put(vehicleId, dh);
    }

    public DisplayHelper getDhForVehicle(long vehicleId) {return dhForVehicle.get(vehicleId);}

    /**
     * 检查某列车是否已注册 LCD。
     */
    public boolean hasLcd(long vehicleId) {
        return lcdInfoForVehicle.containsKey(vehicleId);
    }

    /** 存储某车辆各车厢的世界坐标 */
    public void putCarWorldPositions(long vehicleId, Vector3f[] positions) {
        carWorldPositions.put(vehicleId, positions);
    }

    public Vector3f[] getCarWorldPositions(long vehicleId) {
        return carWorldPositions.get(vehicleId);
    }

    /** 存储某车辆各车厢的朝向 */
    public void putCarWorldYaw(long vehicleId, float[] yaw) {
        carWorldYaw.put(vehicleId, yaw);
    }

    public float[] getCarWorldYaw(long vehicleId) {
        return carWorldYaw.get(vehicleId);
    }

    /**
     * 根据车辆 ID 移除所有相关数据。
     */
    public void removeVehicle(long vehicleId) {
        lcdForVehicle.remove(vehicleId);
        trainStatusForVehicle.remove(vehicleId);
        lcdInfoForVehicle.remove(vehicleId);
        carWorldPositions.remove(vehicleId);
        carWorldYaw.remove(vehicleId);
        DisplayHelper dh = dhForVehicle.remove(vehicleId);
        if (dh != null) {
            dh.close();
        }
        GraphicsTextureHelper.getInstance().removeDrawGraphic("train_lcd_" + vehicleId);
    }
}
