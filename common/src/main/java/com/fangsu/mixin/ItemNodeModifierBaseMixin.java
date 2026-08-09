package com.fangsu.mixin;

import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import com.fangsu.blocks.BlockMultiDirectionNode;
import com.fangsu.mtr.AngleExtra;
import com.fangsu.util.NodeConnector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.mtr.core.data.Position;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.TwoPositionsBase;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.ItemUsageContext;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.data.RailType;
import org.mtr.mod.item.ItemNodeModifierSelectableBlockBase;
import org.mtr.mod.item.ItemRailModifier;
import org.mtr.mod.item.ItemSignalModifier;
import org.mtr.mod.packet.PacketDeleteData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

/**
 * 让 MTR 轨道连接器（Rail Connector）识别并自动连接"万向节点"。
 * <p>
 * 覆盖点：
 * <ul>
 *   <li>{@code clickCondition} — 允许以万向节点作为连接起点/终点</li>
 *   <li>{@code onStartClick} — 万向节点作为起点时记录 transportMode（避免 BlockNode cast）</li>
 *   <li>{@code onEndClick} — 涉及时万向节点时按手持物品分发：</li>
 *   <li>　　轨道连接器（含限速/单向/站台/侧线/折返）→ 按连接器类型建轨</li>
 *   <li>　　轨道删除器 → 删除两端轨道</li>
 *   <li>　　信号连接器 / 桥梁隧道创建器 → 反射调用原版 {@code onConnect} 复用原版逻辑</li>
 * </ul>
 */
@Mixin(value = org.mtr.mod.item.ItemNodeModifierBase.class, remap = false)
public abstract class ItemNodeModifierBaseMixin {

    /** 是否为连接器（false 时原版走删除轨道逻辑，如 RAIL_REMOVER）。
     * 注意：不能声明为 final，也不能带初始值——final+初始值会被 javac 常量折叠导致恒为 false，
     * final 无初始值则 javac 报"未在构造器中初始化"。非 final 无初始值即可正确 shadow。 */
    @Shadow(remap = false)
    protected boolean isConnector;

    /** 原版 {@code onConnect(World, ItemStack, TransportMode, BlockState, BlockState, BlockPos, BlockPos, Angle, Angle, ServerPlayerEntity)}。 */
    private static final Method ON_CONNECT_METHOD = findOnConnectMethod();

    /**
     * 允许连接器以万向节点作为起点/终点。
     */
    @Inject(method = "clickCondition", at = @At("HEAD"), remap = false, cancellable = true)
    private void fangsu$clickCondition(ItemUsageContext context, CallbackInfoReturnable<Boolean> cir) {
        final Level level = context.getWorld().data;
        if (level != null && isMultiDirectionNode(level, context.getBlockPos().data)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 万向节点作为起点时记录 transportMode（避开 BlockNode cast）。
     */
    @Inject(method = "onStartClick", at = @At("HEAD"), remap = false, cancellable = true)
    private void fangsu$onStartClick(ItemUsageContext context, CompoundTag compoundTag, CallbackInfo ci) {
        final Level level = context.getWorld().data;
        if (level != null && isMultiDirectionNode(level, context.getBlockPos().data)) {
            compoundTag.putString("transport_mode", TransportMode.TRAIN.toString());
            ci.cancel();
        }
    }

    /**
     * 涉及万向节点时接管 onEndClick，按手持物品分发。
     */
    @Inject(method = "onEndClick", at = @At("HEAD"), remap = false, cancellable = true)
    private void fangsu$onEndClick(ItemUsageContext context, org.mtr.mapping.holder.BlockPos posEnd, CompoundTag compoundTag, CallbackInfo ci) {
        final Level level = context.getWorld().data;
        if (level == null || level.isClientSide) return;
        // 原版要求服务端玩家参与连接
        final org.mtr.mapping.holder.PlayerEntity player = context.getPlayer();
        if (!ServerPlayerEntity.isInstance(player)) return;
        final ServerPlayerEntity serverPlayerEntity = ServerPlayerEntity.cast(player);

        final BlockPos posStart = context.getBlockPos().data;
        final BlockPos endPos = posEnd.data;
        if (posStart.equals(endPos)) return;

        final boolean startIsNode = isMultiDirectionNode(level, posStart);
        final boolean endIsNode = isMultiDirectionNode(level, endPos);
        if (!startIsNode && !endIsNode) {
            return; // 原版逻辑
        }

        final net.minecraft.world.item.Item item = context.getStack().data.getItem();
        final org.mtr.mapping.holder.ServerWorld serverWorld = new org.mtr.mapping.holder.ServerWorld((net.minecraft.server.level.ServerLevel) level);

        // ---- 轨道连接器 / 删除器 ----
        if (item instanceof ItemRailModifier) {
            if (this.isConnector) {
                handleRailConnect(level, serverWorld, posStart, endPos, item, (ServerPlayer) player.data);
            } else {
                // 轨道删除器：删除两端间轨道（connected 重置由 BlockNodeMixin.resetRailNode 处理）
                final Position p1 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(posStart));
                final Position p2 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(endPos));
                PacketDeleteData.sendDirectlyToServerRailId(serverWorld, TwoPositionsBase.getHexId(p1, p2));
                com.fangsu.Main.LOGGER.info("[NodeConnector] rail removed {}->{}", posStart, endPos);
            }
            ci.cancel();
            return;
        }

        // ---- 信号连接器 / 桥梁隧道创建器：复用原版 onConnect（信号修改 / getRail + RailActionModule）----
        // 不改变节点的连接/绑定状态（信号、桥梁只作用于既有轨道）
        if (item instanceof ItemSignalModifier || item instanceof ItemNodeModifierSelectableBlockBase) {
            invokeOriginalOnConnect(item, context, serverPlayerEntity, level.getBlockState(posStart), level.getBlockState(endPos), posStart, endPos);
            ci.cancel();
        }
    }

    /**
     * 轨道连接器建轨：计算并绑定角度后，按连接器类型（限速/单向/站台/侧线/折返）建轨。
     */
    private void handleRailConnect(Level level, org.mtr.mapping.holder.ServerWorld serverWorld, BlockPos posStart, BlockPos endPos, net.minecraft.world.item.Item item, ServerPlayer serverPlayer) {
        final boolean startBonded = isBonded(level, posStart);
        final boolean endBonded = isBonded(level, endPos);
        final double startAngle = nodeAngle(level, posStart);
        final double endAngle = nodeAngle(level, endPos);
        com.fangsu.Main.LOGGER.info("[NodeConnector] angles start={} (bonded={}) end={} (bonded={})", startAngle, startBonded, endAngle, endBonded);

        final double finalStartAngle;
        final double finalEndAngle;

        if (!startBonded && !endBonded) {
            // 两端均未绑定 → 直线
            final double straight = NodeConnector.straightAngle(posStart, endPos);
            finalStartAngle = straight;
            finalEndAngle = straight;
            bindNode(level, posStart, straight);
            bindNode(level, endPos, straight);
        } else if (!startBonded) {
            // 起点未绑定，终点已绑定/普通节点 → 起点取最大半径圆弧切向
            finalStartAngle = NodeConnector.maxRadiusTangentAngle(endPos, endAngle, posStart);
            finalEndAngle = endAngle;
            bindNode(level, posStart, finalStartAngle);
        } else if (!endBonded) {
            // 终点未绑定，起点已绑定/普通节点 → 终点取最大半径圆弧切向
            finalStartAngle = startAngle;
            finalEndAngle = NodeConnector.maxRadiusTangentAngle(posStart, startAngle, endPos);
            bindNode(level, endPos, finalEndAngle);
        } else {
            // 两端均已绑定 → 使用既有角度
            finalStartAngle = startAngle;
            finalEndAngle = endAngle;
        }
        com.fangsu.Main.LOGGER.info("[NodeConnector] final angles start={} end={}", finalStartAngle, finalEndAngle);

        final ItemRailModifierAccessorMixin accessor = (ItemRailModifierAccessorMixin) item;
        final RailType railType = accessor.getRailType();
        final boolean isOneWay = accessor.isOneWay();

        NodeConnector.createAndSendRail(serverWorld, posStart, finalStartAngle, endPos, finalEndAngle, railType, isOneWay, serverPlayer.getUUID());

        markConnected(level, posStart);
        markConnected(level, endPos);
    }

    /**
     * 反射调用原版 {@code onConnect}，使信号连接器 / 桥梁隧道创建器按原版流程工作。
     * 角度用精确值（AngleExtra），不影响原版逻辑（信号/桥梁仅消费两端位置）。
     */
    private static void invokeOriginalOnConnect(
            net.minecraft.world.item.Item item,
            ItemUsageContext context,
            ServerPlayerEntity player,
            net.minecraft.world.level.block.state.BlockState stateStart,
            net.minecraft.world.level.block.state.BlockState stateEnd,
            BlockPos posStart, BlockPos posEnd
    ) {
        if (ON_CONNECT_METHOD == null) return;
        try {
            final double startAngle = NodeConnector.getDirectionDegrees(context.getWorld().data, posStart);
            final double endAngle = NodeConnector.getDirectionDegrees(context.getWorld().data, posEnd);
            ON_CONNECT_METHOD.setAccessible(true);
            ON_CONNECT_METHOD.invoke(item,
                    context.getWorld(),
                    context.getStack(),
                    TransportMode.TRAIN,
                    new org.mtr.mapping.holder.BlockState(stateStart),
                    new org.mtr.mapping.holder.BlockState(stateEnd),
                    new org.mtr.mapping.holder.BlockPos(posStart),
                    new org.mtr.mapping.holder.BlockPos(posEnd),
                    AngleExtra.fromDegrees(startAngle),
                    AngleExtra.fromDegrees(endAngle),
                    player
            );
            com.fangsu.Main.LOGGER.info("[NodeConnector] onConnect invoked for {} {}->{}", item, posStart, posEnd);
        } catch (ReflectiveOperationException e) {
            com.fangsu.Main.LOGGER.error("[NodeConnector] failed to invoke onConnect for {} {}->{}", item, posStart, posEnd, e);
        }
    }

    private static Method findOnConnectMethod() {
        try {
            return org.mtr.mod.item.ItemNodeModifierBase.class.getDeclaredMethod(
                    "onConnect",
                    org.mtr.mapping.holder.World.class,
                    org.mtr.mapping.holder.ItemStack.class,
                    TransportMode.class,
                    org.mtr.mapping.holder.BlockState.class,
                    org.mtr.mapping.holder.BlockState.class,
                    org.mtr.mapping.holder.BlockPos.class,
                    org.mtr.mapping.holder.BlockPos.class,
                    org.mtr.core.tool.Angle.class,
                    org.mtr.core.tool.Angle.class,
                    ServerPlayerEntity.class
            );
        } catch (NoSuchMethodException e) {
            com.fangsu.Main.LOGGER.error("[NodeConnector] onConnect method not found", e);
            return null;
        }
    }

    private static boolean isMultiDirectionNode(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof BlockMultiDirectionNode;
    }

    private static boolean isBonded(Level level, BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            return node.isDirectionBonded();
        }
        return true; // 普通节点视为已绑定
    }

    private static double nodeAngle(Level level, BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            return node.getDirectionDegrees();
        }
        return BlockNode.getAngle(new org.mtr.mapping.holder.BlockState(level.getBlockState(pos)));
    }

    private static void bindNode(Level level, BlockPos pos, double angle) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            node.setDirectionBonded(angle);
        }
    }

    /**
     * 标记节点已连接。
     * 万向节点写 BE NBT，普通 MTR 节点写 blockstate IS_CONNECTED=true。
     */
    private static void markConnected(Level level, BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            node.setConnected(true);
        } else {
            // 普通 MTR 节点：设置 blockstate IS_CONNECTED=true
            final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof BlockNode) {
                level.setBlock(pos, state.setValue(BlockNode.IS_CONNECTED.data, true), Block.UPDATE_ALL);
            }
        }
    }
}
