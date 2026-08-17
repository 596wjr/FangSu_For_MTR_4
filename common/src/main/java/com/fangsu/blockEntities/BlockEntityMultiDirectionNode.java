package com.fangsu.blockEntities;

import com.fangsu.Main;
import com.fangsu.client.ClientHooks;
import com.fangsu.network.ModNetwork;
import com.fangsu.render.scripting.util.DynamicModelHolder;
import com.fangsu.render.sowcer.math.Matrices;
import com.fangsu.utils.ResourceUtil;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.fangsu.blocks.ModBlocks.BLOCK_ENTITY_MULTI_DIRECTION_NODE;

/**
 * 万向节点方块实体。
 * <p>
 * NBT 存储（与需求一致）：
 * <ul>
 *   <li>{@code direction} (double) — 当前方向（度，0=E, 90=S, 180=W, 270=N）；未绑定时为默认 0</li>
 *   <li>{@code connected} (bool) — 是否已连接轨道</li>
 *   <li>{@code directionBonded} (bool) — 方向是否已绑定；未绑定(false)时模型持续旋转</li>
 * </ul>
 * <p>
 * 未绑定时 {@link #whenRendering()} 让模型绕 Y 轴匀速 360° 旋转；绑定后按 {@code direction} 固定。
 * 已连接时默认隐藏模型，仅手持轨道连接器或刷子时显示 node_connected.obj（与原版 MTR 节点行为一致）。
 * 扳手右键打开角度配置界面，刷子右键在已连接时打开轨道形状/功能界面（与原版节点一致），
 * 未连接时打开角度配置界面。
 * （见 {@link com.fangsu.util.NodeConnector#refreshConnectedRails}）。
 */
public class BlockEntityMultiDirectionNode extends BaseObjBlockEntity implements Syncable {

    private static final String DEFAULT_MODEL = "fangsu:models/obj/node.obj";
    private static final String CONNECTED_MODEL = "fangsu:models/obj/node_connected.obj";

    // ==================== NBT 键 ====================
    private static final String KEY_DIRECTION = "direction";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_DIRECTION_BONDED = "directionBonded";

    // ==================== 运行时状态 ====================
    private double direction;
    private boolean connected;
    private boolean directionBonded;

    // ==================== 刷新重试状态（客户端） ====================
    /** 客户端 MTR 数据未同步时，角度刷新（refreshConnectedRailsIfNeeded）延迟重试的待处理标记。 */
    private boolean pendingRefresh;
    /** 下次重试时间（System.currentTimeMillis 毫秒）。 */
    private long nextRetryTime;
    /** 已重试次数。 */
    private int retryCount;
    /** 最大重试次数（每次间隔 RETRY_INTERVAL_MS，合计约 10 秒窗口）。 */
    private static final int MAX_RETRY = 10;
    /** 重试间隔（毫秒）。 */
    private static final long RETRY_INTERVAL_MS = 1000;

    private DynamicModelHolder modelHolder;
    private DynamicModelHolder connectedModelHolder;
    private boolean modelLoadingFailed = false;

    // ==================== 异步加载（照 BlockEntityRotatingRail） ====================
    private static final ExecutorService LOADING_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fangsu-node-loading-async");
        t.setDaemon(true);
        return t;
    });

    private CompletableFuture<Void> loadingFuture;
    private CompletableFuture<Void> renderingFuture;

    public BlockEntityMultiDirectionNode(BlockPos pos, BlockState state) {
        super(BLOCK_ENTITY_MULTI_DIRECTION_NODE.get(), pos, state);
    }

    // ==================== 供连接器/角度界面读取的公开接口 ====================

    /** 当前方向（度）。 */
    public double getDirectionDegrees() {
        return direction;
    }

    /** 是否已连接轨道。 */
    public boolean isConnected() {
        return connected;
    }

    /** 方向是否已绑定。 */
    public boolean isDirectionBonded() {
        return directionBonded;
    }

    /** 设置方向并绑定（directionBonded=true）。服务端/客户端均可调用。 */
    public void setDirectionAndBind(double degrees) {
        this.direction = degrees;
        this.directionBonded = true;
        this.setChanged();
        this.syncToPeer();
    }

    /** 设置连接状态（对接 MTR IS_CONNECTED 逻辑或连接器 mixin）。 */
    public void setConnected(boolean connected) {
        this.connected = connected;
        this.setChanged();
        this.syncToPeer();
    }

    /** 设置已绑定方向（供连接器在连线后写入），不改变 connected。 */
    public void setDirectionBonded(double degrees) {
        this.direction = degrees;
        this.directionBonded = true;
        this.setChanged();
        this.syncToPeer();
    }

    /**
     * 当方向改变且已连接轨道时，刷新重建连接到本节点的轨道。
     * <p>
     * 该方法由客户端角度界面在确认新角度后调用：先收集连接到本节点的其他端点，
     * 再通过专用 C2S 包发送给服务端执行删除+重建。
     * <p>
     * 客户端 MTR 数据（{@code MinecraftClientData.positionsToRail}）可能尚未同步到位，
     * 此时不静默放弃，而是安排延迟重试（见 {@link #scheduleRetry} 与 {@link #whenRendering}）——
     * 否则服务端图里的轨道角度将永远滞留建轨值，寻路与曲线都不会更新。
     */
    public void refreshConnectedRailsIfNeeded() {
        if (level == null || !level.isClientSide) return;
        // 仅在已连接或可能存在轨道时刷新
        final java.util.List<net.minecraft.core.BlockPos> others = com.fangsu.util.NodeConnector.findConnectedEndpoints(worldPosition);
        if (others.isEmpty()) {
            // 客户端 MTR 数据未同步（找不到本节点端点）→ 延迟重试，不静默放弃
            Main.LOGGER.debug("[MultiDirectionNode] refresh skipped: no endpoints in client data at {}", worldPosition);
            scheduleRetry();
            return;
        }

        // 从客户端 MTR 数据读取连接到本节点的轨道，只保留数据已同步的端点；
        // 每个端点打包限速/形状/类型/样式属性，服务端据此按原属性重建（角度调整后外观与功能不丢失）
        final org.mtr.core.data.Position nodePosition = org.mtr.mod.Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(worldPosition));
        final var connections = org.mtr.mod.client.MinecraftClientData.getInstance().positionsToRail.get(nodePosition);
        final java.util.List<net.minecraft.core.BlockPos> connected = new java.util.ArrayList<>();
        if (connections != null) {
            for (net.minecraft.core.BlockPos o : others) {
                if (connections.containsKey(org.mtr.mod.Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(o)))) {
                    connected.add(o);
                }
            }
        }
        if (connected.isEmpty()) {
            // 端点过滤后为空（数据同步不全）→ 延迟重试，不静默放弃
            Main.LOGGER.debug("[MultiDirectionNode] refresh skipped: no synced endpoints at {}", worldPosition);
            scheduleRetry();
            return;
        }

        final net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(Unpooled.buffer());
        buf.writeBlockPos(worldPosition);
        buf.writeDouble(direction);
        buf.writeInt(connected.size());
        for (net.minecraft.core.BlockPos o : connected) {
            buf.writeBlockPos(o);
            final org.mtr.core.data.Rail rail = connections.get(org.mtr.mod.Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(o)));
            // 限速：m/ms × 3600 → km/h；按端点位置对号入座（单向轨 0 限速端跟随位置，core 内部处理 reversePositions）
            buf.writeLong(Math.round(rail.getSpeedLimitMetersPerMillisecond(nodePosition) * 3600));
            buf.writeLong(Math.round(rail.getSpeedLimitMetersPerMillisecond(org.mtr.mod.Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(o))) * 3600));
            buf.writeInt(rail.railMath.getShape().ordinal());
            final int flags = (rail.isPlatform() ? 1 : 0) | (rail.isSiding() ? 2 : 0) | (rail.canTurnBack() ? 4 : 0)
                    | (rail.canAccelerate() ? 8 : 0) | (rail.canConnectRemotely() ? 16 : 0);
            buf.writeByte(flags);
            final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList<String> styles = rail.getStyles();
            buf.writeInt(styles.size());
            for (String style : styles) {
                buf.writeUtf(style);
            }
        }
        dev.architectury.networking.NetworkManager.sendToServer(com.fangsu.network.ModNetwork.NODE_REFRESH_RAIL, buf);
        // 已成功发出刷新包，清除待重试状态
        pendingRefresh = false;
        retryCount = 0;
    }

    /**
     * 安排延迟重试：客户端 MTR 数据未同步时每 {@value #RETRY_INTERVAL_MS} ms 重试一次，
     * 最多 {@value #MAX_RETRY} 次，仍失败则放弃并打 warn 日志（用户可再次保存触发）。
     * 重试在 {@link #whenRendering}（客户端渲染主线程，每帧调用）中执行，网络发送天然在主线程。
     */
    private void scheduleRetry() {
        if (level == null || !level.isClientSide) return;
        if (retryCount >= MAX_RETRY) {
            pendingRefresh = false;
            retryCount = 0;
            Main.LOGGER.warn("[MultiDirectionNode] refresh retry exhausted at {}, rail angles NOT updated on server", worldPosition);
            return;
        }
        pendingRefresh = true;
        retryCount++;
        nextRetryTime = System.currentTimeMillis() + RETRY_INTERVAL_MS;
    }

    // ==================== 网络同步 ====================

    /** 客户端→服务端：发送当前方向/绑定状态。 */
    public void sendUpdateC2S() {
        if (level != null && level.isClientSide) {
            if (level.hasChunk(getBlockPos().getX() >> 4, getBlockPos().getZ() >> 4)) {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeBlockPos(getBlockPos());
                writeC2S(buf);
                NetworkManager.sendToServer(ModNetwork.BE_SYNC, buf);
            }
        }
        this.setChanged();
    }

    /** 服务端→客户端（在服务端 readC2S 后主动推送方块更新）。 */
    private void syncToPeer() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    public void writeC2S(FriendlyByteBuf buf) {
        buf.writeDouble(direction);
        buf.writeBoolean(connected);
        buf.writeBoolean(directionBonded);
    }

    @Override
    public void readC2S(FriendlyByteBuf buf) {
        this.direction = buf.readDouble();
        this.connected = buf.readBoolean();
        this.directionBonded = buf.readBoolean();
        this.setChanged();
        syncToPeer();
    }

    // ==================== NBT 持久化 ====================

    @Override
    public void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        if (markedError) return;
        tag.putDouble(KEY_DIRECTION, direction);
        tag.putBoolean(KEY_CONNECTED, connected);
        tag.putBoolean(KEY_DIRECTION_BONDED, directionBonded);
    }

    @Override
    public void load(@NotNull CompoundTag tag) {
        super.load(tag);
        markedError = false;
        this.direction = tag.getDouble(KEY_DIRECTION);
        this.connected = tag.getBoolean(KEY_CONNECTED);
        this.directionBonded = tag.getBoolean(KEY_DIRECTION_BONDED);
        triggerAsyncLoading();
    }

    // ==================== 生命周期 ====================

    protected final void triggerAsyncLoading() {
        cancelPendingAsyncLoading();
        loadingFuture = CompletableFuture.runAsync(() -> {
            try {
                this.whenLoading();
            } catch (Exception e) {
                Main.LOGGER.error("Failed to load block entity {} at {}", getClass().getSimpleName(), getBlockPos(), e);
            }
        }, LOADING_EXECUTOR);
    }

    protected final void cancelPendingAsyncLoading() {
        if (loadingFuture != null && !loadingFuture.isDone()) {
            loadingFuture.cancel(true);
        }
        loadingFuture = null;
    }

    protected final boolean isAsyncLoadingDone() {
        return loadingFuture == null || loadingFuture.isDone();
    }

    public final boolean tryBeginRendering() {
        if (!isAsyncLoadingDone()) return false;
        if (renderingFuture != null && !renderingFuture.isDone()) return false;
        renderingFuture = new CompletableFuture<>();
        return true;
    }

    public final void finishRendering() {
        if (renderingFuture != null && !renderingFuture.isDone()) {
            renderingFuture.complete(null);
        }
    }

    public void whenLoading() {
        // 仅客户端加载模型
        if (level == null || !level.isClientSide) return;
        ensureModelReady();
    }

    /**
     * 确保模型已加载（同步加载 + uploadLater，照 BlockEntityRotatingRail 的 ensureModelReady 模式）。
     * 同时加载默认节点模型和已连接节点模型。
     */
    private void ensureModelReady() {
        // 默认模型（node.obj）
        if (modelHolder == null || modelHolder.getUploadedModel() == null) {
            Main.LOGGER.info("[MultiDirectionNode] ensureModelReady (default) at {}", worldPosition);
            try {
                final com.fangsu.render.sowcerext.model.RawModel rawModel = ResourceUtil.loadModel(new ResourceLocation(DEFAULT_MODEL), false);
                if (rawModel != null) {
                    if (modelHolder == null) {
                        modelHolder = new DynamicModelHolder();
                    }
                    modelHolder.uploadLater(rawModel);
                    modelLoadingFailed = false;
                    Main.LOGGER.info("[MultiDirectionNode] default model queued for upload at {}", worldPosition);
                }
            } catch (Exception e) {
                Main.LOGGER.warn("[MultiDirectionNode] Failed to load model {}: {}", DEFAULT_MODEL, e.getMessage(), e);
                modelLoadingFailed = true;
            }
        }

        // 已连接模型（node_connected.obj）
        if (connectedModelHolder == null || connectedModelHolder.getUploadedModel() == null) {
            Main.LOGGER.info("[MultiDirectionNode] ensureModelReady (connected) at {}", worldPosition);
            try {
                final com.fangsu.render.sowcerext.model.RawModel rawModel = ResourceUtil.loadModel(new ResourceLocation(CONNECTED_MODEL), false);
                if (rawModel != null) {
                    if (connectedModelHolder == null) {
                        connectedModelHolder = new DynamicModelHolder();
                    }
                    connectedModelHolder.uploadLater(rawModel);
                    Main.LOGGER.info("[MultiDirectionNode] connected model queued for upload at {}", worldPosition);
                }
            } catch (Exception e) {
                Main.LOGGER.warn("[MultiDirectionNode] Failed to load model {}: {}", CONNECTED_MODEL, e.getMessage(), e);
            }
        }
    }

    /**
     * 判断玩家手中是否持有轨道相关物品（轨道连接器、刷子、轨道节点方块）。
     * 与原版 MTR {@code RenderRails.isHoldingRailRelated} 行为一致。
     */
    private static boolean isHoldingRailRelated(net.minecraft.world.entity.player.Player player) {
        return isRailRelatedItem(player.getMainHandItem())
                || isRailRelatedItem(player.getOffhandItem());
    }

    private static boolean isRailRelatedItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        final Item item = stack.getItem();
        return item instanceof org.mtr.mod.item.ItemNodeModifierBase
                || item instanceof org.mtr.mod.item.ItemBrush
                || Block.byItem(item) instanceof org.mtr.mod.block.BlockNode
                || Block.byItem(item) instanceof com.fangsu.blocks.BlockMultiDirectionNode;
    }

    @Override
    public void whenRendering() {
        // 客户端 MTR 数据就绪后的延迟重试：数据未同步时角度刷新顺延到数据到位后执行
        if (pendingRefresh && System.currentTimeMillis() >= nextRetryTime) {
            refreshConnectedRailsIfNeeded();
        }
        ObjBlockScriptContext ctx = this.scriptContext;
        if (ctx == null) return;
        // 若尚未加载/未上传成功，确保加载
        ensureModelReady();

        // 已连接时：默认隐藏模型，仅手持轨道连接器/刷子/节点方块时显示 node_connected.obj
        if (connected) {
            if (level != null && level.isClientSide) {
                // 通过 ClientHooks 获取本地玩家：此类会被服务器加载（ModBlocks 静态注册），
                // 直接引用 net.minecraft.client.Minecraft 会导致服务器端类加载崩溃
                final Player player = ClientHooks.getLocalPlayer();
                if (player != null && isHoldingRailRelated(player)) {
                    // 手持轨道相关物品 → 显示已连接模型
                    final DynamicModelHolder holder = connectedModelHolder;
                    if (holder != null && holder.getUploadedModel() != null) {
                        Matrices mat = new Matrices();
                        mat.translate(0.5f, 0f, 0.5f);
                        // 已连接时方向必定已绑定，按固定方向渲染（与 MTR renderNode rotateYDegrees(-angle) 对齐）
                        final double rotation = -Math.toRadians(direction) + Math.PI / 2;
                        mat.rotateY((float) rotation);
                        ctx.drawModel(holder, mat);
                    }
                }
                // 未手持轨道相关物品 → 不渲染（默认隐藏）
            }
            return;
        }

        // 未连接：渲染默认 node.obj 模型（旋转/固定）
        final DynamicModelHolder holder = modelHolder;
        if (holder == null || holder.getUploadedModel() == null) {
            return;
        }

        Matrices mat = new Matrices();
        // 平移使模型居中于方块中心
        mat.translate(0.5f, 0f, 0.5f);

        final double rotation;
        if (!directionBonded) {
            // 未绑定：绕 Y 轴匀速 360° 旋转（2 秒一圈）
            rotation = (System.currentTimeMillis() % 2000) / 2000.0 * (Math.PI * 2);
        } else {
            // 与 MTR 节点显示一致：Angle 为顺时针罗盘角，rotateY 取负（renderNode 用 rotateYDegrees(-angle)）
            rotation = -Math.toRadians(direction) + Math.PI / 2;
        }
        mat.rotateY((float) rotation);

        ctx.drawModel(holder, mat);
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        // 仅客户端需要模型：服务端同步加载 OBJ（文件 IO + 解析）会阻塞主线程，
        // 放置大量节点（如 20×20×20）时导致服务器严重卡顿
        if (level == null || !level.isClientSide) return;
        ensureModelReady();
    }

    public void whenDisposing() {
        if (modelHolder != null) {
            modelHolder.close();
            modelHolder = null;
        }
        if (connectedModelHolder != null) {
            connectedModelHolder.close();
            connectedModelHolder = null;
        }
    }

    @Override
    public void setRemoved() {
        if (!disposed) {
            whenDisposing();
            cancelPendingAsyncLoading();
        }
        super.setRemoved();
    }

    // ==================== 交互 ====================

    @Override
    public InteractionResult useWithWrench(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player, @NotNull InteractionHand hand, @NotNull BlockHitResult hit) {
        if (level.isClientSide) {
            ClientHooks.openNodeAngleScreen(this);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResult whenUseWithBrush(Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            // 尝试获取与此节点连接的轨道：先用视线追踪，失败则从 MTR 数据直接查询
            org.mtr.core.data.Rail rail = null;
            final var railAndBlockPos = org.mtr.mod.client.MinecraftClientData.getInstance().getFacingRailAndBlockPos(false);
            if (railAndBlockPos != null) {
                rail = railAndBlockPos.left();
            }
            if (rail == null && connected) {
                // 视线未命中但节点已连接 → 从 MTR 数据中查找连接到本节点的第一条轨道
                final var connections = org.mtr.mod.client.MinecraftClientData.getInstance()
                        .positionsToRail.get(org.mtr.mod.Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(pos)));
                if (connections != null && !connections.isEmpty()) {
                    rail = connections.values().iterator().next();
                }
            }
            ClientHooks.openNodeAngleScreen(this, rail);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // ==================== BaseObjBlockEntity 抽象方法 ====================

    @Override
    public String getMainModelKey() {
        return "multiDirectionNode";
    }

    @Override
    public VoxelShape setCollisionShape(BlockState state) {
        return Shapes.empty();
    }

    @Override
    public VoxelShape setShape(BlockState state) {
        // 已连接时形状极薄，与原版 MTR 节点一致，避免阻挡玩家视线追踪轨道
        if (connected) {
            return Shapes.box(0.1, 0, 0.1, 0.9, 0.0625, 0.9);
        }
        return Shapes.block();
    }
}
