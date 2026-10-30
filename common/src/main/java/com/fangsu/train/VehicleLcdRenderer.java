package com.fangsu.train;

import com.fangsu.Main;
import com.fangsu.mtr.LcdVehicleRegistry;
import com.fangsu.mtr.DrawableRoute;
import com.fangsu.network.HiddenRoutesClient;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.NTETrainWrapper;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleScriptContext;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleWrapper;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleDataCache;
import dev.architectury.event.events.client.ClientTickEvent;
import net.minecraft.client.Minecraft;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.StoredMatrixTransformations;

import java.awt.Graphics2D;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 方速独立管理 LCD 的创建、绘图、提交与释放。
 * 仅从 MTR VehicleResource.queue 接收车厢矩阵，JCM 只提供站序/状态 API。
 * 所有 GPU 操作及实例表访问均在客户端线程；绘图线程只接触 AWT 图像和状态快照。
 */
public final class VehicleLcdRenderer {
    private static final long PAINT_INTERVAL_NS = 50_000_000L;
    private static final long RETRY_INTERVAL_NS = 5_000_000_000L;
    private static final Map<Long, VehicleState> VEHICLES = new HashMap<>();
    private static final Set<String> FAILED_CONFIGS = new HashSet<>();
    private static final AtomicBoolean RELOAD_REQUESTED = new AtomicBoolean();
    private static final ExecutorService PAINTER = Executors.newSingleThreadExecutor(task -> {
        final Thread thread = new Thread(task, "fangsu-lcd-painter");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile boolean reloading;
    private static Object world;
    private static long frame;

    private VehicleLcdRenderer() {
    }

    public static void init() {
        // 退出世界后 RenderVehicles 不再执行，必须另有 tick 负责释放资源。
        ClientTickEvent.CLIENT_POST.register(client -> maintain());
    }

    public static void beginReload() {
        reloading = true;
    }

    public static void requestReload() {
        // 资源回调只发信号，不在重载工作线程销毁正在使用的 GPU 对象。
        RELOAD_REQUESTED.set(true);
        reloading = false;
    }

    private static void maintain() {
        maintain(true);
    }

    private static void maintain(boolean sweep) {
        final Object currentWorld = Minecraft.getInstance().level;
        if (world != currentWorld) {
            clear();
            world = currentWorld;
        }
        if (!reloading && RELOAD_REQUESTED.getAndSet(false)) {
            clear();
            LcdVehicleRegistry.load();
        }
        if (!sweep || currentWorld == null || VEHICLES.isEmpty()) return;
        final Map<Long, VehicleExtension> live = new HashMap<>();
        MinecraftClientData.getInstance().vehicles.forEach(vehicle -> live.put(vehicle.getId(), vehicle));
        VEHICLES.entrySet().removeIf(entry -> {
            final VehicleState state = entry.getValue();
            final VehicleExtension latest = live.get(entry.getKey());
            // MTR 每次同步都会替换 VehicleExtension；同一列车的持久运行数据仍是同一个对象。
            if (latest == null || !state.belongsTo(latest)) {
                state.close();
                return true;
            }
            state.updateVehicle(latest);
            final Set<String> carTypes = new HashSet<>();
            state.vehicle.vehicleExtraData.immutableVehicleCars.forEach(car -> carTypes.add(car.getVehicleId()));
            state.lcds.entrySet().removeIf(lcd -> {
                if (carTypes.contains(lcd.getKey())) return false;
                lcd.getValue().close();
                return true;
            });
            return false;
        });
    }

    public static void beginFrame() {
        maintain(false);
        frame++;
    }

    /** 在车体提交处逐节匹配；混编列车不会套用第一节车的 LCD 配置。 */
    public static void renderCar(VehicleExtension vehicle, int car, StoredMatrixTransformations transform, int light) {
        if (vehicle == null || Minecraft.getInstance().level == null || reloading || RELOAD_REQUESTED.get()) return;
        if (car < 0 || car >= vehicle.vehicleExtraData.immutableVehicleCars.size()) return;
        final String type = vehicle.vehicleExtraData.immutableVehicleCars.get(car).getVehicleId();
        final LcdVehicleRegistry.LcdVehicleEntry entry = LcdVehicleRegistry.match(type);
        if (entry == null || FAILED_CONFIGS.contains(type)) return;
        final VehicleState state = VEHICLES.compute(vehicle.getId(), (id, previous) -> {
            if (previous != null && previous.belongsTo(vehicle)) {
                previous.updateVehicle(vehicle);
                return previous;
            }
            if (previous != null) previous.close();
            return new VehicleState(vehicle);
        });
        try {
            Lcd lcd = state.lcds.get(type);
            if (lcd == null) {
                try {
                    lcd = new Lcd(vehicle.getId(), entry);
                    state.lcds.put(type, lcd);
                } catch (Exception | LinkageError e) {
                    FAILED_CONFIGS.add(type);
                    Main.LOGGER.error("[FangSu LCD] 车型 {} 初始化失败，资源重载后重试", type, e);
                    return;
                }
            }
            if (System.nanoTime() >= state.retryAfter) {
                try {
                    lcd.update(state);
                } catch (Exception | LinkageError e) {
                    state.retryAfter = System.nanoTime() + RETRY_INTERVAL_NS;
                    Main.LOGGER.error("[FangSu LCD] 车辆 {} 状态刷新失败，保留上一帧", vehicle.getId(), e);
                }
            }
            if (lcd.ready) lcd.model.queue(transform, light);
        } catch (Exception | LinkageError e) {
            state.retryAfter = System.nanoTime() + RETRY_INTERVAL_NS;
            Main.LOGGER.error("[FangSu LCD] 车辆 {} 提交失败，稍后重试", vehicle.getId(), e);
        }
    }

    /** 保留完整站序请求，下一站/到站判断仍使用已有 TrainStatus 修复。 */
    public static TrainStatus buildStatus(VehicleWrapper vehicle) {
        return vehicle == null ? null : buildStatus(vehicle.getMtrVehicle());
    }

    private static TrainStatus buildStatus(VehicleExtension vehicle) {
        // 侧线 ID 尚未同步时不发请求，避免 JCM 以空响应占住该车辆的首次请求缓存。
        final VehicleScriptContext.DataFetchMode mode = vehicle.vehicleExtraData.getSidingId() == 0
                ? VehicleScriptContext.DataFetchMode.SKIP : VehicleScriptContext.DataFetchMode.MANDATORY;
        final NTETrainWrapper wrapper = new NTETrainWrapper(mode, vehicle);
        final TrainStatus status = new TrainStatus(wrapper);
        status.updateRoute();
        status.updateDoorState(wrapper.getDoorValue());
        return status;
    }

    private static void clear() {
        VEHICLES.values().forEach(VehicleState::close);
        VEHICLES.clear();
        FAILED_CONFIGS.clear();
        DrawableRoute.clearCache();
    }

    public static int activeCount() {
        return VEHICLES.values().stream().mapToInt(state -> state.lcds.size()).sum();
    }

    public static String debugInfo() {
        return "FangSu LCD: vehicles=" + VEHICLES.size() + ", displays=" + activeCount()
                + ", failedConfigs=" + FAILED_CONFIGS + ", independent MTR queue";
    }

    private static final class VehicleState {
        VehicleExtension vehicle;
        final Map<String, Lcd> lcds = new HashMap<>();
        TrainStatus status;
        long statusFrame = -1;
        long retryAfter;
        long missingRouteSince;
        boolean missingRouteReported;
        long dataSidingId;
        long lastDataRetry;

        VehicleState(VehicleExtension vehicle) {
            this.vehicle = vehicle;
            dataSidingId = vehicle.vehicleExtraData.getSidingId();
            lastDataRetry = System.nanoTime();
        }

        boolean belongsTo(VehicleExtension latest) {
            return vehicle.getId() == latest.getId()
                    && vehicle.persistentVehicleData == latest.persistentVehicleData;
        }

        void updateVehicle(VehicleExtension latest) {
            final long latestSiding = latest.vehicleExtraData.getSidingId();
            if (dataSidingId != latestSiding) {
                dataSidingId = latestSiding;
                VehicleDataCache.clearStopsDataCache(latest.getId());
                lastDataRetry = System.nanoTime();
                statusFrame = -1;
            }
            if (vehicle == latest) return;
            vehicle = latest;
            // 只刷新快照来源，保留模型、纹理、绘图任务和跨帧 state。
            statusFrame = -1;
        }

        TrainStatus status() {
            if (statusFrame != frame) {
                status = buildStatus(vehicle);
                statusFrame = frame;
                final long now = System.nanoTime();
                if (status.currentRoute == null || status.drawableRoute == null) {
                    HiddenRoutesClient.requestMissingRoutes(vehicle.vehicleExtraData.getThisRouteId(),
                            vehicle.vehicleExtraData.getNextRouteId());
                }
                if (dataSidingId != 0 && !status.getWrapper().isStopsDataFetched()
                        && now - lastDataRetry >= RETRY_INTERVAL_NS) {
                    // JCM 的请求缓存会记住尚未返回的请求；低频重试，不能依赖重建 LCD 才重新取数。
                    VehicleDataCache.clearStopsDataCache(vehicle.getId());
                    lastDataRetry = now;
                }
                if (status.currentRoute != null && status.drawableRoute != null) {
                    missingRouteSince = 0;
                    missingRouteReported = false;
                } else if (vehicle.getIsOnRoute() || vehicle.vehicleExtraData.getThisRouteId() != 0
                        || vehicle.vehicleExtraData.getNextRouteId() != 0) {
                    if (missingRouteSince == 0) missingRouteSince = now;
                    if (!missingRouteReported && now - missingRouteSince >= RETRY_INTERVAL_NS) {
                        missingRouteReported = true;
                        // 持续缺失才记录一次，区分车辆同步、站序获取与线路查询的问题。
                        Main.LOGGER.warn("[FangSu LCD] 车辆 {} 线路数据持续缺失：thisRoute={} nextRoute={} stops={} fetched={} routeCache={}",
                                vehicle.getId(), vehicle.vehicleExtraData.getThisRouteId(),
                                vehicle.vehicleExtraData.getNextRouteId(), status.getAllPlatforms().size(),
                                status.getWrapper().isStopsDataFetched(), VehicleDataCache.mtrData.routeIdMap.size());
                    }
                }
            }
            return status;
        }

        void close() {
            lcds.values().forEach(Lcd::close);
            lcds.clear();
        }
    }

    /** 同一列车的同一车型共享纹理，不同车型的几何/脚本状态互相独立。 */
    private static final class Lcd {
        final LcdInfo info;
        final LcdPanelConfig config;
        final LcdTexture texture;
        final LcdModel model;
        final LcdBase painter;
        final Map<String, Object> paintState = new HashMap<>();
        FutureTask<Void> painting;
        long nextPaint;
        boolean ready;
        volatile boolean closed;

        Lcd(long vehicleId, LcdVehicleRegistry.LcdVehicleEntry entry) {
            info = entry.lcdInfo().resolveSlots();
            if (info.slotsInfo() == null) throw new IllegalArgumentException("无法读取 slots: " + entry.vehicleId());
            config = LcdPanelConfig.fromSlots(info.id(), info.slotsInfo());
            if (config.panels().isEmpty()) throw new IllegalArgumentException("slots 中没有可用的面板几何");
            painter = LcdManager.getInstance().getLcd(info.id());
            if (!info.hasScript() && painter == null) throw new IllegalArgumentException("未知 LCD id: " + info.id());
            texture = new LcdTexture(config.texWidth(), config.texHeight());
            try {
                model = new LcdModel(config, texture.identifier());
            } catch (RuntimeException | Error e) {
                texture.close();
                throw e;
            }
            Main.LOGGER.info("[FangSu LCD] 创建车辆 {} 的车型 {}：{} 个面板，{}x{}", vehicleId, entry.vehicleId(),
                    config.panels().size(), config.texWidth(), config.texHeight());
        }

        void update(VehicleState vehicle) throws Exception {
            final long now = System.nanoTime();
            if (painting != null) {
                if (!painting.isDone()) return;
                try {
                    // Future.get 建立内存可见性；工作线程结束后才从图像读取并上传。
                    painting.get();
                    texture.upload();
                    ready = true;
                } catch (Exception e) {
                    nextPaint = now + RETRY_INTERVAL_NS;
                    Main.LOGGER.error("[FangSu LCD] {} 绘图失败，保留上一帧并稍后重试", info.id(), e);
                } finally {
                    painting = null;
                }
            }
            if (now < nextPaint) return;
            final TrainStatus status = vehicle.status();
            nextPaint = now + PAINT_INTERVAL_NS;
            painting = new FutureTask<>(() -> {
                if (closed) return null;
                final Graphics2D graphics = texture.image.createGraphics();
                try {
                    LcdJavaPainter.paint(graphics, config, info, status, painter, paintState);
                } finally {
                    graphics.dispose();
                }
                return null;
            });
            PAINTER.execute(painting);
        }

        void close() {
            if (closed) return;
            closed = true;
            // 不提前把运行中的绘图标记为完成；旧任务只能写旧的堆内图像，永不触及已释放的纹理。
            if (painting != null) painting.cancel(false);
            model.close();
            texture.close();
        }
    }
}
