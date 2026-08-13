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
     * 服务端：万向节点方向改变后刷新重建连接到该节点的轨道。
     * 收到客户端 [nodePos, newDirection, otherPos...]，对每个其他端点删除旧轨道并以新方向重建。
     */
    private static void handleNodeRefreshRail(
            FriendlyByteBuf buf,
            NetworkManager.PacketContext ctx
    ) {
        ctx.queue(() -> {
            ServerPlayer player = (ServerPlayer) ctx.getPlayer();
            if (player == null) return;
            //#if MC_VERSION >= 12000
            Level level = player.level();
            //#else
            //$$ Level level = player.level;
            //#endif
            BlockPos nodePos = buf.readBlockPos();
            double newDirection = buf.readDouble();
            int count = buf.readInt();
            java.util.List<BlockPos> others = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                others.add(buf.readBlockPos());
            }

            BlockEntity be = level.getBlockEntity(nodePos);
            if (!(be instanceof com.fangsu.blockEntities.BlockEntityMultiDirectionNode node)) return;
            // 应用新的绑定方向
            node.setDirectionBonded(newDirection);
            node.setConnected(true);

            for (BlockPos otherPos : others) {
                // 解析客户端打包的旧轨道属性，重建时保持限速/单向/类型/样式
                long speedAtNode = buf.readLong();
                long speedAtOther = buf.readLong();
                org.mtr.core.data.Rail.Shape shape = org.mtr.core.data.Rail.Shape.values()[buf.readInt()];
                int flags = buf.readByte();
                int styleCount = buf.readInt();
                java.util.List<String> styles = new java.util.ArrayList<>(styleCount);
                for (int i = 0; i < styleCount; i++) {
                    styles.add(buf.readUtf());
                }
                com.fangsu.util.NodeConnector.RailAttrs attrs = new com.fangsu.util.NodeConnector.RailAttrs(
                        speedAtNode, speedAtOther, shape,
                        (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0,
                        styles);
                com.fangsu.util.NodeConnector.refreshNodeRail(level, nodePos, newDirection, otherPos, attrs);
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
