package com.fangsu.network;

import com.fangsu.items.ItemServiceRail;
import com.fangsu.mtr.rail.ServiceRail;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import com.fangsu.mixin.InitAccessorMixin;
import com.fangsu.mixin.MainAccessorMixin;
import com.fangsu.mixin.ServiceRailSidingAccess;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.TwoPositionsBase;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mod.Init;
import org.mtr.mod.packet.PacketUpdateData;

import java.util.Set;

public final class ServiceRailPackets {
    private static final ResourceLocation UPDATE_TOOL = new ResourceLocation("fangsu", "service_rail_tool");
    private static final ResourceLocation UPDATE_TRACK = new ResourceLocation("fangsu", "service_rail_track");

    private ServiceRailPackets() {
    }

    public static void registerServer() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, UPDATE_TRACK, (buffer, context) -> {
            final BlockPos start = buffer.readBlockPos();
            final BlockPos end = buffer.readBlockPos();
            final ServiceRail settings = ServiceRail.decode(buffer.readUtf(64));
            if (start.equals(end) || settings == null || settings.kind() != ServiceRail.Kind.BYPASS) {
                return;
            }
            context.queue(() -> {
                if (!(context.getPlayer() instanceof ServerPlayer player) || !player.mayBuild()) {
                    return;
                }
                //#if MC_VERSION >= 12000
                final var level = player.level();
                //#else
                //$$ final var level = player.level;
                //#endif
                if (level instanceof ServerLevel serverLevel) {
                    applyTrack(serverLevel, start, end, settings);
                }
            });
        });
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, UPDATE_TOOL, (buffer, context) -> {
            final int handIndex = buffer.readUnsignedByte();
            final ServiceRail settings = ServiceRail.decode(buffer.readUtf(64));
            if (handIndex >= InteractionHand.values().length || settings == null) {
                return;
            }
            final InteractionHand hand = InteractionHand.values()[handIndex];
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    final var stack = player.getItemInHand(hand);
                    if (stack.getItem() instanceof ItemServiceRail tool && tool.kind() == settings.kind()) {
                        stack.getOrCreateTag().putString(ServiceRail.KEY, settings.encode());
                    }
                }
            });
        });
    }

    public static void updateTool(InteractionHand hand, ServiceRail settings) {
        final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeByte(hand.ordinal());
        buffer.writeUtf(settings.encode());
        NetworkManager.sendToServer(UPDATE_TOOL, buffer);
    }

    public static void updateTrack(BlockPos start, BlockPos end, ServiceRail settings) {
        final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBlockPos(start);
        buffer.writeBlockPos(end);
        buffer.writeUtf(settings.encode());
        NetworkManager.sendToServer(UPDATE_TRACK, buffer);
    }

    private static void applyTrack(ServerLevel level, BlockPos start, BlockPos end, ServiceRail settings) {
        final String dimension = Init.getWorldId(new org.mtr.mapping.holder.World(level));
        final var first = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(start));
        final var second = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(end));
        final String id = TwoPositionsBase.getHexId(first, second);
        for (final var simulator : ((MainAccessorMixin) InitAccessorMixin.getMain()).getSimulators()) {
            if (!simulator.dimension.equals(dimension)) {
                continue;
            }
            simulator.run(() -> {
                final var rail = simulator.railIdMap.get(id);
                final var previous = ServiceRail.get(rail);
                if (previous == null || previous.kind() != ServiceRail.Kind.BYPASS || previous.equals(settings)) {
                    return;
                }
                ServiceRail.set(rail, settings);
                PacketUpdateData.sendDirectlyToServerRail(new ServerWorld(level), rail);
                if (previous.value() != settings.value()) {
                    regenerate(simulator, Set.of(id));
                }
            });
            return;
        }
    }

    public static void regenerate(Simulator simulator, Set<String> rails) {
        final var depots = new ObjectArrayList<Depot>();
        for (final var depot : simulator.depots) {
            if (containsRail(depot.getPath(), rails) || depot.savedRails.stream().anyMatch(siding -> {
                final var access = (ServiceRailSidingAccess) (Object) siding;
                return containsRail(access.fangsu$getOutboundPath(), rails) || containsRail(access.fangsu$getInboundPath(), rails);
            })) {
                depots.add(depot);
            }
        }
        if (!depots.isEmpty()) {
            Depot.generateDepots(simulator, depots);
        }
    }

    private static boolean containsRail(Iterable<PathData> path, Set<String> ids) {
        for (final var segment : path) {
            if (ids.contains(TwoPositionsBase.getHexId(segment.getOrderedPosition1(), segment.getOrderedPosition2()))) {
                return true;
            }
        }
        return false;
    }
}
