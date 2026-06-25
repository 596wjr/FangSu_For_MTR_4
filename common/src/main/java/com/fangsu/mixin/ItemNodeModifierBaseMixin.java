package com.fangsu.mixin;

import com.fangsu.blocks.BlockMultiDirectionNode;
import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.mapping.holder.*;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.item.ItemNodeModifierBase;
import org.mtr.mod.item.ItemRailModifier;
import org.mtr.mod.packet.PacketUpdateData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ItemNodeModifierBase.class, remap = false)
public abstract class ItemNodeModifierBaseMixin {

    @Final
    @Shadow(remap = false)
    protected boolean isConnector;

    @Shadow(remap = false)
    protected static final String TAG_TRANSPORT_MODE = "transport_mode";

    /**
     * Make clickCondition accept our multi_direction_node block.
     */
    @Inject(method = "clickCondition", at = @At("RETURN"), cancellable = true, remap = false)
    private void fangsu$clickCondition(ItemUsageContext context, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            BlockState state = context.getWorld().getBlockState(context.getBlockPos());
            if (state.getBlock().data instanceof BlockMultiDirectionNode) {
                cir.setReturnValue(true);
            }
        }
    }

    /**
     * Handle onStartClick for our node (store a default transport mode).
     */
    @Inject(method = "onStartClick", at = @At("HEAD"), remap = false)
    private void fangsu$onStartClick(ItemUsageContext context, CompoundTag compoundTag, CallbackInfo ci) {
        BlockState state = context.getWorld().getBlockState(context.getBlockPos());
        if (state.getBlock().data instanceof BlockMultiDirectionNode) {
            compoundTag.putString(TAG_TRANSPORT_MODE, TransportMode.TRAIN.toString());
        }
    }

    /**
     * Handle onEndClick when our multi_direction_node is involved.
     * We intercept and perform our own connection logic, then cancel the original method.
     */
    @Inject(method = "onEndClick", at = @At("HEAD"), cancellable = true, remap = false)
    private void fangsu$onEndClick(ItemUsageContext context, BlockPos posEnd, CompoundTag compoundTag, CallbackInfo ci) {
        final World world = context.getWorld();
        final BlockPos posStart = context.getBlockPos();
        final BlockState stateStart = world.getBlockState(posStart);
        final BlockState stateEnd = world.getBlockState(posEnd);
        final PlayerEntity player = context.getPlayer();

        final boolean startIsOurs = stateStart.getBlock().data instanceof BlockMultiDirectionNode;
        final boolean endIsOurs = stateEnd.getBlock().data instanceof BlockMultiDirectionNode;

        // Not our node — let original method handle it
        if (!startIsOurs && !endIsOurs) {
            return;
        }

        if (!ServerPlayerEntity.isInstance(player)) {
            ci.cancel();
            return;
        }

        final ServerPlayerEntity serverPlayer = ServerPlayerEntity.cast(player);

        if (isConnector) {
            if (!posStart.equals(posEnd)) {
                fangsu$handleConnect(world, stateStart, stateEnd, posStart, posEnd, startIsOurs, endIsOurs, serverPlayer);
            }
        } else {
            // For rail remover — just cancel, let user use the brush/other tools for removal
            serverPlayer.sendMessage(
                    org.mtr.mod.generated.lang.TranslationProvider.GUI_MTR_INVALID_ORIENTATION.getText(),
                    true
            );
        }

        compoundTag.remove(TAG_TRANSPORT_MODE);
        ci.cancel();
    }

    /**
     * Custom connection logic for multi_direction_node.
     */
    @Unique
    private void fangsu$handleConnect(World world, BlockState stateStart, BlockState stateEnd,
                                       BlockPos posStart, BlockPos posEnd,
                                       boolean startIsOurs, boolean endIsOurs,
                                       ServerPlayerEntity player) {
        // Read angles
        final float angleStart = fangsu$getNodeAngle(world, posStart, stateStart, startIsOurs);
        final float angleEnd = fangsu$getNodeAngle(world, posEnd, stateEnd, endIsOurs);
        final boolean boundStart = startIsOurs ? fangsu$isNodeBound(world, posStart) : true;
        final boolean boundEnd = endIsOurs ? fangsu$isNodeBound(world, posEnd) : true;

        // Calculate final angles based on bound state
        final float finalAngleStart;
        final float finalAngleEnd;

        if (!boundStart && !boundEnd) {
            // Both unbound → calculate angle for a straight track: angle from posStart to posEnd
            final double dx = posEnd.getX() - posStart.getX();
            final double dz = posEnd.getZ() - posStart.getZ();
            float dirAngle = (float) Math.toDegrees(Math.atan2(-dx, dz)); // Minecraft angle convention
            dirAngle = ((dirAngle % 360) + 360) % 360;
            finalAngleStart = dirAngle;
            finalAngleEnd = dirAngle;
        } else if (!boundStart) {
            // Start unbound, end bound → keep end's angle, calculate start for smooth curve
            finalAngleEnd = angleEnd;
            final double dx = posEnd.getX() - posStart.getX();
            final double dz = posEnd.getZ() - posStart.getZ();
            float dirAngle = (float) Math.toDegrees(Math.atan2(-dx, dz));
            finalAngleStart = ((dirAngle % 360) + 360) % 360;
        } else if (!boundEnd) {
            // Start bound, end unbound → keep start's angle, calculate end for smooth curve
            finalAngleStart = angleStart;
            final double dx = posEnd.getX() - posStart.getX();
            final double dz = posEnd.getZ() - posStart.getZ();
            float dirAngle = (float) Math.toDegrees(Math.atan2(-dx, dz));
            finalAngleEnd = ((dirAngle % 360) + 360) % 360;
        } else {
            // Both bound — use their stored angles
            finalAngleStart = angleStart;
            finalAngleEnd = angleEnd;
        }

        // Normalize angles via MTR's Rail.getAngles
        final var angles = Rail.getAngles(
                Init.blockPosToPosition(posStart), finalAngleStart,
                Init.blockPosToPosition(posEnd), finalAngleEnd
        );

        // Create the rail via ItemRailModifier.createRail
        if (!(ItemNodeModifierBase.class.cast(this) instanceof ItemRailModifier railModifier)) {
            player.sendMessage(
                    org.mtr.mod.generated.lang.TranslationProvider.GUI_MTR_INVALID_ORIENTATION.getText(),
                    true
            );
            return;
        }

        final TransportMode transportMode = TransportMode.TRAIN;
        final Rail rail = railModifier.createRail(
                player.getUuid(), transportMode,
                stateStart, stateEnd, posStart, posEnd,
                angles.left(), angles.right()
        );

        if (rail == null || !rail.isValid()) {
            player.sendMessage(
                    org.mtr.mod.generated.lang.TranslationProvider.GUI_MTR_INVALID_ORIENTATION.getText(),
                    true
            );
            return;
        }

        // Update connected state
        if (startIsOurs) {
            fangsu$setNodeConnected(world, posStart, true);
            fangsu$bindNodeAngle(world, posStart, finalAngleStart);
        } else {
            world.setBlockState(posStart, stateStart.with(new Property<>(BlockNode.IS_CONNECTED.data), true));
        }
        if (endIsOurs) {
            fangsu$setNodeConnected(world, posEnd, true);
            fangsu$bindNodeAngle(world, posEnd, finalAngleEnd);
        } else {
            world.setBlockState(posEnd, stateEnd.with(new Property<>(BlockNode.IS_CONNECTED.data), true));
        }

        // Send rail data to server
        PacketUpdateData.sendDirectlyToServerRail(ServerWorld.cast(world), rail);
    }

    // ========== Helper methods for our block entity ==========

    @Unique
    private static float fangsu$getNodeAngle(World world, BlockPos pos, BlockState state, boolean isOurs) {
        if (isOurs) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be!=null&& be.data instanceof BlockEntityMultiDirectionNode nodeBE) {
                return nodeBE.getAngle();
            }
            return 0;
        }
        return BlockNode.getAngle(state);
    }

    @Unique
    private static boolean fangsu$isNodeBound(World world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be!=null&& be.data instanceof BlockEntityMultiDirectionNode nodeBE) {
            return nodeBE.isBound();
        }
        return true;
    }

    @Unique
    private static void fangsu$setNodeConnected(World world, BlockPos pos, boolean connected) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be!=null&& be.data instanceof BlockEntityMultiDirectionNode nodeBE) {
            nodeBE.setConnected(connected);
        }
    }

    @Unique
    private static void fangsu$bindNodeAngle(World world, BlockPos pos, float angle) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be!=null&& be.data instanceof BlockEntityMultiDirectionNode nodeBE) {
            nodeBE.setAngleAndBind(angle);
        }
    }
}
