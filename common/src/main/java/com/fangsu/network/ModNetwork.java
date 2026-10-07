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
     * 载荷（客户端 {@code BlockEntityMultiDirectionNode#refreshConnectedRailsIfNeeded} 写入）：
     * <pre>
     *   BlockPos nodePos
     *   double   newDirection
     *   double   offsetX / offsetY / offsetZ   ← 姿态随刷新请求同行
     *   double   pitchDeg / rollDeg            ← P3 新增：俯仰 / 翻滚（正上坡 / 右手侧抬高）
     *   boolean  directionBonded               ← 「旋转绑定：否」时只重建几何、不绑定方向
     *   int      count
     *   count × { BlockPos otherPos, long speedAtNode, long speedAtOther, int shape,
     *             byte flags, int styleCount, styleCount × String }
     * </pre>
     * 姿态（平移）之所以放进刷新包，而不是继续依赖另一个 BE_SYNC 包：服务端重建轨道时读的是
     * 服务端方块实体里的偏移，两个独立包的到达/应用顺序不定，就会出现「节点已经拖走、轨道留在原地」。
     * 现在客户端把刚编辑好的偏移直接随请求发来，跨包竞态不复存在。
     * <p>
     * 本包<b>不做版本探测</b>：客户端与服务端永远运行同一份 FangSu 构建，字段顺序必须与写侧逐字对应。
     * 俯仰 / 翻滚在 P3 只是被写进服务端方块实体（供节点模型倾斜），还不参与建轨几何。
     */
    private static void handleNodeRefreshRail(
            FriendlyByteBuf buf,
            NetworkManager.PacketContext ctx
    ) {
        // 全部载荷在主线程排队之前读完：排队回调可能在网络缓冲区释放之后才执行
        final BlockPos nodePos = buf.readBlockPos();
        final double newDirection = buf.readDouble();
        // 姿态与刷新请求同行（布局见方法注释）
        final double offsetX = buf.readDouble();
        final double offsetY = buf.readDouble();
        final double offsetZ = buf.readDouble();
        // P3：俯仰 / 翻滚（读侧顺序必须与写侧一致）
        final double pitchDeg = buf.readDouble();
        final double rollDeg = buf.readDouble();
        final boolean directionBonded = buf.readBoolean();
        final int count = buf.readInt();
        final java.util.List<BlockPos> others = new java.util.ArrayList<>();
        final java.util.List<com.fangsu.util.NodeConnector.RailAttrs> attrsList = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            others.add(buf.readBlockPos());
            // 解析客户端打包的旧轨道属性，重建时保持限速/单向/类型/样式
            long speedAtNode = buf.readLong();
            long speedAtOther = buf.readLong();
            org.mtr.core.data.Rail.Shape shape = org.mtr.core.data.Rail.Shape.values()[buf.readInt()];
            int flags = buf.readByte();
            int styleCount = buf.readInt();
            java.util.List<String> styles = new java.util.ArrayList<>(styleCount);
            for (int j = 0; j < styleCount; j++) {
                styles.add(buf.readUtf());
            }
            attrsList.add(new com.fangsu.util.NodeConnector.RailAttrs(
                    speedAtNode, speedAtOther, shape,
                    (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0,
                    styles));
        }

        ctx.queue(() -> {
            ServerPlayer player = (ServerPlayer) ctx.getPlayer();
            if (player == null) return;
            //#if MC_VERSION >= 12000
            Level level = player.level();
            //#else
            //$$ Level level = player.level;
            //#endif

            BlockEntity be = level.getBlockEntity(nodePos);
            if (!(be instanceof com.fangsu.blockEntities.BlockEntityMultiDirectionNode node)) {
                // 客户端发了刷新包但服务端对应位置没有万向节点（方块被拆/数据不一致），打日志便于排查
                Main.LOGGER.warn("[NodeConnector] handleNodeRefreshRail: no MultiDirectionNode BE at {}", nodePos);
                return;
            }
            // 先落姿态：必须在重建循环之前写入，后面 refreshNodeRail 才会按新偏移算几何
            // （setNodeOffset 内部已做 ±MAX_OFFSET 钳制，并 setChanged + 方块更新）
            node.setNodeOffset(offsetX, offsetY, offsetZ);
            // P3：俯仰 / 翻滚同样先落盘（钳制 ±15° / ±20°）。本阶段它们不参与几何，
            // 只是让服务端 BE 与客户端编辑结果一致、节点模型在别的客户端也倾斜。
            node.setNodeAngles(pitchDeg, rollDeg);
            // 应用方向：绑定开关为「是」才绑定；为「否」时只写值，保持未绑定语义
            if (directionBonded) {
                node.setDirectionBonded(newDirection);
            } else {
                node.setDirectionUnbound(newDirection);
            }
            node.setConnected(true);
            // 通知所有客户端最新的方向/绑定/平移状态
            node.setChanged();
            level.sendBlockUpdated(nodePos, node.getBlockState(), node.getBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);

            for (int i = 0; i < others.size(); i++) {
                final BlockPos otherPos = others.get(i);
                // refreshNodeRail 现在「先校验后删除」：姿态非法时返回 false 且保留旧轨道
                final boolean refreshed =
                        com.fangsu.util.NodeConnector.refreshNodeRail(level, nodePos, newDirection, otherPos, attrsList.get(i));
                if (!refreshed) {
                    Main.LOGGER.warn("[NodeConnector] handleNodeRefreshRail: rail {}->{} NOT rebuilt (invalid pose/geometry), old rail kept",
                            nodePos, otherPos);
                    continue;
                }
                // 重建成功后再把两端标回「已连接」：删除旧轨时 MTR 的 PacketDeleteData 会对端点调用
                // BlockNode.resetRailNode，而 BlockNodeMixin 会把万向节点的 connected 清成 false
                // （javap 已核对调用链）。上面的 setConnected(true) 在删除之前，会被这一步覆盖，
                // 于是节点错误地显示为未连接（模型重新出现、「旋转绑定」从锁定变为可改）。
                // 只在刷新成功时标记：失败分支没有删除动作（validate-before-delete），无需也不应改动状态。
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
