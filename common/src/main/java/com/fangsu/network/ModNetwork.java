package com.fangsu.network;

import com.fangsu.Main;
import net.minecraft.core.Registry;
import com.fangsu.blockEntities.BlockEntityScreendoorCentralControl;
import com.fangsu.blockEntities.BlockEntityTicketBarrier;
import com.fangsu.blockEntities.Syncable;
import com.fangsu.items.TicketItem;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
//#if MC_VERSION >= 11903
import net.minecraft.core.registries.BuiltInRegistries;
//#endif
//#if MC_VERSION >= 12000
import net.minecraft.core.registries.Registries;
//#endif
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public class ModNetwork {
    private static final int EMERALD_VALUE = 11;

    public static final ResourceLocation BE_SYNC =
            new ResourceLocation("fangsu", "be_sync");
    public static final ResourceLocation TICKET_MACHINE_SYNC =
            new ResourceLocation("fangsu", "ticket_machine_sync");
    public static final ResourceLocation CENTRAL_CONTROL_SYNC =
            new ResourceLocation("fangsu", "central_control_sync");
    public static final ResourceLocation TICKET_BARRIER_SYNC =
            new ResourceLocation("fangsu", "ticket_barrier_sync");
    public static final ResourceLocation NODE_REFRESH_RAIL =
            new ResourceLocation("fangsu", "node_refresh_rail");

    public static void init() {
        NetworkManager.registerReceiver(
                NetworkManager.Side.C2S,
                BE_SYNC,
                ModNetwork::handleBeSync
        );
        NetworkManager.registerReceiver(
                NetworkManager.Side.C2S,
                NODE_REFRESH_RAIL,
                ModNetwork::handleNodeRefreshRail
        );
        // 逐轨道超高编辑（三个倾斜控制点 + 半轨距）：载荷与校验都在 RailTiltPackets 里，
        // 注册位置紧挨 NODE_REFRESH_RAIL（同属「轨道几何编辑」这一类 C2S 通道）。
        RailTiltPackets.registerServer();
        NetworkManager.registerReceiver(
                NetworkManager.Side.C2S,
                TICKET_MACHINE_SYNC,
                ModNetwork::ticketMachineSync
        );
        NetworkManager.registerReceiver(
                NetworkManager.Side.C2S,
                CENTRAL_CONTROL_SYNC,
                ModNetwork::handleCentralControlSync
        );
        NetworkManager.registerReceiver(
                NetworkManager.Side.C2S,
                TICKET_BARRIER_SYNC,
                ModNetwork::handleTicketBarrierSync
        );
        HybridCreatorPackets.registerServer();
        DisplacementToolPackets.registerServer();
        HiddenRoutesPackets.registerServer();
    }

    /**
     * 服务端：万向节点方向/平移改变后刷新重建连接到该节点的轨道。
     * <p>
     * 载荷（客户端 {@code BlockEntityMultiDirectionNode#refreshConnectedRailsIfNeeded} 写入）
     * 的字段布局、写侧与读侧的对应关系全部集中在
     * {@link NodeRefreshRailPayload}：写与读是同一个类的两个静态方法，因此不可能出现
     * 「顺序写错但 javac 编译通过」的读写不对称。本方法只负责把解出来的
     * {@link NodeRefreshRailPayload.Payload} 落到方块实体与轨道上。
     * <p>
     * 姿态（平移 / 俯仰 / 翻滚 / 开关 / 半轨距）之所以放进刷新包，而不是继续依赖另一个 BE_SYNC 包：
     * 服务端重建轨道时读的是服务端方块实体里的值，两个独立包的到达/应用顺序不定，就会出现
     * 「节点已经拖走、轨道留在原地」。现在客户端把刚编辑好的值直接随请求发来，跨包竞态不复存在。
     * <p>
     * <b>P4b</b>：每条轨道还随行携带「作者授权逐轨道超高」（{@link NodeRefreshRailPayload.RailEntry#tilt()}）。
     * 旧实现重建时只写 {@code readRailPose} 派生的姿态（该姿态不含逐轨道超高），
     * 于是任何一次节点重建都会把作者编辑过的三点剖面与半轨距整份抹掉（数据丢失）。
     * 现在由 {@link com.fangsu.util.RailTiltCarry#mergeInto} 合并：新派生的平移/俯仰/节点滚转保留，
     * 授权值也保留；未授权轨道照旧完全跟随节点值。
     * <p>
     * 本包<b>不做版本探测</b>：客户端与服务端永远运行同一份 FangSu 构建。
     */
    private static void handleNodeRefreshRail(
            FriendlyByteBuf buf,
            NetworkManager.PacketContext ctx
    ) {
        // 全部载荷在主线程排队之前读完：排队回调可能在网络缓冲区释放之后才执行
        final NodeRefreshRailPayload.Payload payload = NodeRefreshRailPayload.read(buf);

        ctx.queue(() -> {
            ServerPlayer player = (ServerPlayer) ctx.getPlayer();
            if (player == null) return;
            //#if MC_VERSION >= 12000
            Level level = player.level();
            //#else
            //$$ Level level = player.level;
            //#endif
            final BlockPos nodePos = payload.nodePos();
            final double newDirection = payload.direction();

            BlockEntity be = level.getBlockEntity(nodePos);
            if (!(be instanceof com.fangsu.blockEntities.BlockEntityMultiDirectionNode node)) {
                // 客户端发了刷新包但服务端对应位置没有万向节点（方块被拆/数据不一致），打日志便于排查
                Main.LOGGER.warn("[NodeConnector] handleNodeRefreshRail: no MultiDirectionNode BE at {}", nodePos);
                return;
            }
            // 先落姿态：必须在重建循环之前写入，后面 refreshNodeRail 才会按新偏移算几何
            // （setNodeOffset 内部已做 ±MAX_OFFSET 钳制，并 setChanged + 方块更新）
            node.setNodeOffset(payload.offsetX(), payload.offsetY(), payload.offsetZ());
            // P3：俯仰 / 翻滚同样先落盘（按 BlockEntityMultiDirectionNode 的硬边界钳制，
            // 即 MAX_PITCH_DEG / MAX_ROLL_DEG；服务端钳制只此一份，见该类的「硬边界」说明）。
            // P4a 起它们会经 readRailPose 进入轨道姿态，
            // 所以必须在重建循环之前写入，重建才会按新纵坡 / 超高算几何。
            node.setNodeAngles(payload.pitchDeg(), payload.rollDeg());
            // P4a：外轨超高开关与半轨距（半轨距内部按 MIN_HALF_GAUGE / MAX_HALF_GAUGE 钳制、
            // NaN/Inf → 默认）。
            // 开关为「关」时，随后 readRailPose 会把 roll 端点值写成 0，几何不再有中心线抬升。
            node.setSuperelevation(payload.superelevation());
            node.setRollOffsetM(payload.rollOffsetM());
            // 应用方向：绑定开关为「是」才绑定；为「否」时只写值，保持未绑定语义
            if (payload.directionBonded()) {
                node.setDirectionBonded(newDirection);
            } else {
                node.setDirectionUnbound(newDirection);
            }
            node.setConnected(true);
            // 通知所有客户端最新的方向/绑定/平移状态
            node.setChanged();
            level.sendBlockUpdated(nodePos, node.getBlockState(), node.getBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);

            for (final NodeRefreshRailPayload.RailEntry entry : payload.rails()) {
                final BlockPos otherPos = entry.otherPos();
                // 解析客户端打包的旧轨道属性，重建时保持限速/单向/类型/样式
                final com.fangsu.util.NodeConnector.RailAttrs attrs = new com.fangsu.util.NodeConnector.RailAttrs(
                        entry.speedAtNode(), entry.speedAtOther(),
                        org.mtr.core.data.Rail.Shape.values()[entry.shapeOrdinal()],
                        (entry.flags() & 1) != 0, (entry.flags() & 2) != 0, (entry.flags() & 4) != 0,
                        (entry.flags() & 8) != 0, (entry.flags() & 16) != 0,
                        entry.styles());
                // 逐轨道超高随行数据：未授权时是 RailTiltCarry.NONE，服务端据此保持节点派生值
                final com.fangsu.util.RailTiltCarry tiltCarry = entry.tilt();
                // refreshNodeRail 先校验候选几何、再做写操作：姿态非法时返回 false 且旧轨道原样保留
                final boolean refreshed =
                        com.fangsu.util.NodeConnector.refreshNodeRail(level, nodePos, newDirection, otherPos, attrs, tiltCarry);
                if (!refreshed) {
                    Main.LOGGER.warn("[NodeConnector] handleNodeRefreshRail: rail {}->{} NOT rebuilt (invalid pose/geometry), old rail kept",
                            nodePos, otherPos);
                    continue;
                }
                // 重建成功后再把两端标回「已连接」。刷新路径现在**不删除旧轨**（见
                // NodeConnector.refreshNodeRail：同 hexId 的 UPDATE_DATA 在 core 里是原地替换），
                // 因此不会触发 MTR 的 resetRailNode 复位；这里保留一次显式标记，
                // 让「轨道存在 → 节点已连接」这条不变式在本方法内自明（服务端仍是 connected 的权威来源）。
                // 只在刷新成功时标记：失败分支没有做任何写操作，无需也不应改动状态。
                com.fangsu.util.NodeConnector.markConnected(level, nodePos);
                com.fangsu.util.NodeConnector.markConnected(level, otherPos);
            }
        });
    }

    private static void handleBeSync(
            FriendlyByteBuf buf,
            NetworkManager.PacketContext ctx
    ) {
        BlockPos pos = buf.readBlockPos();
        byte[] payload = new byte[buf.readableBytes()];
        buf.readBytes(payload);


        ctx.queue(() -> {
            ServerPlayer player = (ServerPlayer) ctx.getPlayer();
            if (player == null) return;

            //#if MC_VERSION >= 12000
            Level level = player.level();
            //#else
            //$$ Level level = player.level;
            //#endif
            BlockEntity be = level.getBlockEntity(pos);

            if (be instanceof Syncable syncable) {
                FriendlyByteBuf safeBuf =
                        new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload));

                syncable.readC2S(safeBuf);
//                be.setChanged();
//
//                level.sendBlockUpdated(
//                        pos,
//                        be.getBlockState(),
//                        be.getBlockState(),
//                        3
//                );
            }
        });
    }

    private static void ticketMachineSync(
            FriendlyByteBuf buf,
            NetworkManager.PacketContext context
    ) {
        ResourceLocation itemLocation = buf.readResourceLocation();
        int price = buf.readVarInt();
        int count = buf.readVarInt();

        context.queue(() -> {
            Main.LOGGER.info("1");
            ServerPlayer player = (ServerPlayer) context.getPlayer();
            if (player == null) return;

            // -------- 基础校验 --------
            if (price <= 0 || count <= 0 || count > 64) return;
            Main.LOGGER.info(itemLocation.toString());
            //#if MC_VERSION >= 11903
            Item item = BuiltInRegistries.ITEM.get(itemLocation);
            //#else
            //$$ Item item = net.minecraft.core.Registry.ITEM.get(itemLocation);
            //#endif
            if (!(item instanceof TicketItem ticketItem)) return;
            Main.LOGGER.info("2");

            int totalPrice = price * count;

            // -------- 创造模式：直接给 --------
            if (player.isCreative()) {
                ItemStack stack = ticketItem.createTicket(price);
                Main.LOGGER.info("giving {} stack {} for {}", count, stack, player);
                for (int i = 0; i < count; i++)
                    player.getInventory().add(stack.copy());
                return;
            }

            // -------- 计算绿宝石 --------
            int emeraldCost = totalPrice / EMERALD_VALUE;
            if (emeraldCost * EMERALD_VALUE < totalPrice) {
                emeraldCost++; // 不找零，向上取整
            }

            Inventory inv = player.getInventory();

            if (countItem(inv, Items.EMERALD) < emeraldCost) {
                return; // 钱不够
            }

            // -------- 扣钱 --------
            removeItem(inv, Items.EMERALD, emeraldCost);

            // -------- 给票 --------
            ItemStack stack = ticketItem.createTicket(price);
            Main.LOGGER.info("giving {} stack {} for {}", count, stack, player);
            for (int i = 0; i < count; i++)
                player.getInventory().add(stack.copy());
        });
    }

    private static int countItem(Inventory inv, Item item) {
        int count = 0;
        for (ItemStack stack : inv.items) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void removeItem(Inventory inv, Item item, int amount) {
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (!stack.is(item)) continue;

            int remove = Math.min(stack.getCount(), amount);
            stack.shrink(remove);
            amount -= remove;

            if (stack.isEmpty()) {
                inv.items.set(i, ItemStack.EMPTY);
            }

            if (amount <= 0) {
                inv.setChanged();
                return;
            }
        }
        inv.setChanged();
    }

    private static void handleTicketBarrierSync(
            FriendlyByteBuf buf,
            NetworkManager.PacketContext ctx
    ) {
        BlockPos pos = buf.readBlockPos();

        ctx.queue(() -> {
            ServerPlayer player = (ServerPlayer) ctx.getPlayer();
            if (player == null) return;

            //#if MC_VERSION >= 12000
            Level level = player.level();
            //#else
            //$$ Level level = player.level;
            //#endif
            BlockEntity be = level.getBlockEntity(pos);

            if (be instanceof BlockEntityTicketBarrier barrier) {
                barrier.handleServerInteraction(level, player);
            }
        });
    }

    private static void handleCentralControlSync(
            FriendlyByteBuf buf,
            NetworkManager.PacketContext ctx
    ) {
        BlockPos pos = buf.readBlockPos();
        byte[] payload = new byte[buf.readableBytes()];
        buf.readBytes(payload);

        ctx.queue(() -> {
            ServerPlayer player = (ServerPlayer) ctx.getPlayer();
            if (player == null) return;

            //#if MC_VERSION >= 12000
            Level level = player.level();
            //#else
            //$$ Level level = player.level;
            //#endif
            BlockEntity be = level.getBlockEntity(pos);

            if (be instanceof BlockEntityScreendoorCentralControl ctrl) {
                FriendlyByteBuf safeBuf =
                        new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload));
                ctrl.readSync(safeBuf);
            }
        });
    }
}
