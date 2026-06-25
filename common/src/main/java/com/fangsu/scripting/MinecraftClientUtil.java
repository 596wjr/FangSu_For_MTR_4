package com.fangsu.scripting;

import com.fangsu.render.sowcer.math.Vector3f;
import com.mojang.text2speech.Narrator;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.RenderCall;
import net.minecraft.client.renderer.LevelRenderer;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Station;
import org.mtr.mod.client.MinecraftClientData;


import java.util.HashMap;
import java.util.Map;

public class MinecraftClientUtil {

    public static boolean worldIsRaining() {
        return Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.isRaining();
    }

    public static boolean worldIsRainingAt(Vector3f pos) {
        return Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.isRainingAt(pos.toBlockPos());
    }

    public static int worldDayTime() {
        return Minecraft.getInstance().level != null
                ? (int) Minecraft.getInstance().level.getDayTime() : 0;
    }

    public static void narrate(String message) {
        Minecraft.getInstance().execute(() -> {
            Narrator.getNarrator().say(message, true);
        });
    }

    public static void displayMessage(String message, boolean actionBar) {
        final Player player = Minecraft.getInstance().player;
        if (player != null) {
            Minecraft.getInstance().execute(() -> {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(message), actionBar);
            });
        }
    }

    public static void execute(Runnable runnable) {
        Minecraft.getInstance().execute(runnable);
    }

    public static void recordRenderCall(RenderCall renderCall) {
        RenderSystem.recordRenderCall(renderCall);
    }

    public static boolean isOnRenderThreadOrInit() {
        return RenderSystem.isOnRenderThreadOrInit();
    }

    public static void levelEvent(int p_109534_, Vector3f p_109535_, int p_109536_) {
        final Player player = Minecraft.getInstance().player;
        if (player != null) {
            Minecraft.getInstance().execute(() -> {
                Minecraft.getInstance().level.levelEvent(player, p_109534_, p_109535_.toBlockPos(), p_109536_);
            });
        }
    }

    // TODO: MTR4 信号系统待适配，当前返回 0（无占用）
    public static int getOccupiedAspect(Vector3f vPos, float facing, int aspects) {
        return 0;
    }

    public static Station getStationAt(Vector3f pos) {
        final int x = (int) Math.floor(pos.x());
        final int z = (int) Math.floor(pos.z());
        long closestDist = Long.MAX_VALUE;
        Station closestStation = null;
        for (final Station station : MinecraftClientData.getInstance().stations) {
            if (x >= station.getMinX() && x <= station.getMaxX() && z >= station.getMinZ() && z <= station.getMaxZ()) {
                final long cx = (station.getMinX() + station.getMaxX()) / 2;
                final long cz = (station.getMinZ() + station.getMaxZ()) / 2;
                final long dist = Math.abs(cx - x) + Math.abs(cz - z);
                if (dist < closestDist) {
                    closestDist = dist;
                    closestStation = station;
                }
            }
        }
        return closestStation;
    }

    public static Platform getPlatformAt(Vector3f pos, int radius, int lower, int upper) {
        final Station station = getStationAt(pos);
        if (station == null) return null;
        final BlockPos blockPos = pos.toBlockPos();
        Platform closestPlatform = null;
        long closestDistance = Long.MAX_VALUE;
        for (final Platform platform : station.savedRails) {
            final Position midPos = platform.getMidPosition();
            final long distance = Math.abs(midPos.getX() - blockPos.getX()) + Math.abs(midPos.getZ() - blockPos.getZ());
            if (distance < closestDistance) {
                closestDistance = distance;
                closestPlatform = platform;
            }
        }
        return closestPlatform;
    }

    public static Vector3f getCameraPos() {
        //#if MC_VERSION >= 11903
        return new Vector3f(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().toVector3f());
        //#else
        //$$ net.minecraft.world.phys.Vec3 pos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        //$$ return new Vector3f((float) pos.x, (float) pos.y, (float) pos.z);
        //#endif
    }

    public static float getCameraDistance(Vector3f from) {
        Vector3f cameraPos = getCameraPos();
        return cameraPos.distance(from);
    }

    public static Level getLevel() {
        return Minecraft.getInstance().level;
    }

    static double getAverage(double a, double b) {
        return (a + b) / 2;
    }

    static double asin(double value) {
        return Math.asin(value);
    }

    public static int packLightTexture(int p_109886_, int p_109887_) {
        return p_109886_ << 4 | p_109887_ << 20;
    }

    public static int getLightColor(Vector3f pos) {
        return LevelRenderer.getLightColor(getLevel(), pos.toBlockPos());
    }

    public static void reloadResourcePacks() {
        execute(Minecraft.getInstance()::reloadResourcePacks);
    }

    public static void markRendererAllChanged() {
        Minecraft.getInstance().levelRenderer.allChanged();
    }
}