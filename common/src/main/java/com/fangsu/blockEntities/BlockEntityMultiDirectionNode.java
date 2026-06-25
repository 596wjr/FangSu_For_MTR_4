package com.fangsu.blockEntities;

import com.fangsu.mappings.ComponentHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.fangsu.blocks.ModBlocks.BLOCK_ENTITY_MULTI_DIRECTION_NODE;

public class BlockEntityMultiDirectionNode extends BlockEntity {

    private static final String TAG_ANGLE = "angle";
    private static final String TAG_BOUND = "isBound";
    private static final String TAG_CONNECTED = "isConnected";

    private float angle = 0;
    private boolean isBound = false;
    private boolean isConnected = false;

    public BlockEntityMultiDirectionNode(BlockPos blockPos, BlockState blockState) {
        super(BLOCK_ENTITY_MULTI_DIRECTION_NODE.get(), blockPos, blockState);
    }

    // ========== NBT ==========

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putFloat(TAG_ANGLE, angle);
        tag.putBoolean(TAG_BOUND, isBound);
        tag.putBoolean(TAG_CONNECTED, isConnected);
    }

    @Override
    public void load(@NotNull CompoundTag tag) {
        super.load(tag);
        angle = tag.contains(TAG_ANGLE) ? tag.getFloat(TAG_ANGLE) : 0;
        isBound = tag.contains(TAG_BOUND) && tag.getBoolean(TAG_BOUND);
        isConnected = tag.contains(TAG_CONNECTED) && tag.getBoolean(TAG_CONNECTED);
    }

    // ========== Network Sync ==========

    @Override
    public @NotNull CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        tag.putFloat(TAG_ANGLE, angle);
        tag.putBoolean(TAG_BOUND, isBound);
        tag.putBoolean(TAG_CONNECTED, isConnected);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ========== Getters ==========

    public float getAngle() {
        return angle;
    }

    public boolean isBound() {
        return isBound;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void setConnected(boolean connected) {
        this.isConnected = connected;
        sync();
    }

    public void setAngleAndBind(float newAngle) {
        this.angle = ((newAngle % 360) + 360) % 360;
        this.isBound = true;
        sync();
    }

    // ========== Brush Interaction ==========

    public InteractionResult whenUseWithBrush(Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        float playerYaw = player.getYRot();
        float snappedAngle = ((playerYaw % 360) + 360) % 360;

        setAngleAndBind(snappedAngle);

        player.displayClientMessage(
                ComponentHelper.translatable("msg.fangsu.multi_direction_node.angle_set",
                        String.format("%.1f", snappedAngle)),
                true
        );
        return InteractionResult.SUCCESS;
    }
}
