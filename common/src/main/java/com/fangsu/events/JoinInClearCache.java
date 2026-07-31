package com.fangsu.events;

import com.fangsu.mtr.DrawableRoute;
import com.fangsu.mtr.LcdVehicleRegistry;
import com.fangsu.train.LcdDrawManager;
import com.fangsu.utils.GraphicsTextureHelper;
import net.minecraft.server.level.ServerPlayer;

public class JoinInClearCache {
    public static void clearCache() {
        GraphicsTextureHelper.getInstance().removeDrawGraphicsByPrefix("train_");
        LcdDrawManager.getInstance().reset();
        // 清理后需要重新加载映射并重建初始化标记
        LcdVehicleRegistry.clearInitializedFlag();
        DrawableRoute.clearCache();
    }

    public static void clearCache(ServerPlayer serverPlayer) {
        clearCache();
    }
}
