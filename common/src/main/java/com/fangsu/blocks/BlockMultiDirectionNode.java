package com.fangsu.blocks;

import com.fangsu.blockEntities.BaseObjBlockEntity;
import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import com.fangsu.items.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mtr.mod.Init;
import org.mtr.mod.Items;
import org.mtr.mod.packet.PacketDeleteData;

/**
 * 万向节点方块。
 * <p>
 * 与 mtr 本体的轨道节点不同，万向节点未绑定时模型持续旋转，可通过扳手/刷子或
 * 轨道连接器指定并绑定方向。角度与连接状态保存在 {@link BlockEntityMultiDirectionNode} 的 NBT 中
 * （direction / connected / directionBonded）。
 */
public class BlockMultiDirectionNode extends BaseObjBlock {

    public BlockMultiDirectionNode() {
        super();
    }

    @Nullable
    @Override
    public BaseObjBlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new BlockEntityMultiDirectionNode(pos, state);
    }

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState blockState) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public @NotNull InteractionResult use(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player, @NotNull InteractionHand hand, @NotNull BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        BaseObjBlockEntity be = (BaseObjBlockEntity) level.getBlockEntity(pos);
        if (stack.is(ModItems.ITEM_WRENCH.get())) {
            if (be != null) {
                return be.useWithWrench(state, level, pos, player, hand, hit);
            }
        } else if (stack.is(Items.BRUSH.get().data)) {
            if (be != null) {
                return be.whenUseWithBrush(level, pos, player, hand, hit);
            }
        } else if (stack.getItem() instanceof com.fangsu.items.ItemRailModelTool) {
            // 轨道模型工具 — 物品 useOn 已处理，这里显式放行避免进入 whenUseWithOther
            return InteractionResult.PASS;
        } else if (be != null) {
            return be.whenUseWithOther(level, pos, player, hand, hit);
        }
        return InteractionResult.PASS;
    }

    /**
     * 破坏方块时，服务端删除连接到此节点的所有轨道（与原版 {@link org.mtr.mod.block.BlockNode#onBreak2} 行为一致）。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !movedByPiston && !state.is(newState.getBlock())) {
            PacketDeleteData.sendDirectlyToServerRailNodePosition(
                    new org.mtr.mapping.holder.ServerWorld((net.minecraft.server.level.ServerLevel) level),
                    Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(pos))
            );
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
