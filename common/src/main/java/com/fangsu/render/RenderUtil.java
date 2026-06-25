package com.fangsu.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.mtr.mod.data.VehicleExtension;

public class RenderUtil {
    public static boolean shouldSkipRenderTrain(VehicleExtension train) {
        final Player player = Minecraft.getInstance().player;
        if (player != null) {
            return train.getId() == player.getVehicle().getId();
        }
        return false;
    }
}
