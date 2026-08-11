package com.fangsu.items;

import com.fangsu.blocks.BlockMultiDirectionNode;
import com.fangsu.client.ClientHooks;
import com.fangsu.mappings.ComponentHelper;
import com.fangsu.utils.RegisterUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.mtr.core.data.Rail;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketUpdateData;
import org.mtr.core.operation.UpdateDataRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨道模型工具——将物品 NBT 中储存的轨道模型列表一键应用到对准的轨道上。
 * <p>
 * 右键节点（MTR 原版 BlockNode 或方速万向节点 BlockMultiDirectionNode）：
 * 读取物品 NBT 中的 "railModelKeys" 列表，找到玩家面对的轨道并应用所有模型样式。
 * <p>
 * 右键空气/其他方块：打开 {@link RailModelSelectScreen} 选择要应用的模型（支持多选）。
 */
public class ItemRailModelTool extends Item {

    /** NBT 键：储存轨道模型 ID 列表（不含 _1/_2 方向后缀） */
    public static final String TAG_MODEL_KEYS = "railModelKeys";

    public ItemRailModelTool() {
        super(com.fangsu.utils.RegisterUtil.tabProps(new Item.Properties().stacksTo(1)));
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        RegisterUtil.addDescTooltip(tooltip, "item.fangsu.rail_model_tool.desc");
    }

    /**
     * 从物品 NBT 读取已储存的模型 ID 列表。
     */
    public static List<String> getModelKeys(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_MODEL_KEYS, Tag.TAG_LIST)) return List.of();
        ListTag list = tag.getList(TAG_MODEL_KEYS, Tag.TAG_STRING);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String s = list.getString(i);
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    /**
     * 将模型 ID 列表写入物品 NBT。空列表会删除该 NBT 键。
     */
    public static void setModelKeys(ItemStack stack, List<String> keys) {
        CompoundTag tag = stack.getOrCreateTag();
        ListTag list = new ListTag();
        for (String key : keys) {
            if (!key.isEmpty()) {
                list.add(StringTag.valueOf(key));
            }
        }
        if (list.isEmpty()) {
            tag.remove(TAG_MODEL_KEYS);
        } else {
            tag.put(TAG_MODEL_KEYS, list);
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!context.getLevel().isClientSide()) return InteractionResult.PASS;

        BlockState state = context.getLevel().getBlockState(context.getClickedPos());
        Block block = state.getBlock();

        if (block instanceof org.mtr.mod.block.BlockNode
                || block instanceof BlockMultiDirectionNode) {
            findFacingRailAndApply(context);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            // 通过 ClientHooks 打开界面：此类会被服务器加载（ModItems 静态注册），
            // 直接引用 net.minecraft.client.Minecraft 会导致服务器端类加载崩溃
            ClientHooks.openRailModelSelectScreen(stack);
            return InteractionResultHolder.success(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    /**
     * 两级查找策略找到玩家面对的轨道，然后应用 NBT 中储存的模型样式列表。
     * <p>
     * 参考 {@code ItemBrush.useOnBlock2}（BlockNode 路径）和
     * {@code BlockEntityMultiDirectionNode.whenUseWithBrush}（万向节点回退路径）。
     */
    private void findFacingRailAndApply(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        List<String> modelKeys = getModelKeys(stack);

        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Block block = level.getBlockState(pos).getBlock();

        // 两级查找策略获取面对的轨道
        Rail rail = null;
        if (block instanceof org.mtr.mod.block.BlockNode) {
            final var pair = MinecraftClientData.getInstance().getFacingRailAndBlockPos(false);
            if (pair != null) rail = pair.left();
        } else if (block instanceof BlockMultiDirectionNode) {
            // Level 1: 视线追踪（准星可能命中相邻的 BlockNode）
            final var pair = MinecraftClientData.getInstance().getFacingRailAndBlockPos(false);
            if (pair != null) rail = pair.left();
            // Level 2: 回退 —— 从 MTR 数据查询连接到此节点的第一条轨道
            if (rail == null) {
                final var connections = MinecraftClientData.getInstance()
                        .positionsToRail.get(Init.blockPosToPosition(
                                new org.mtr.mapping.holder.BlockPos(pos)));
                if (connections != null && !connections.isEmpty()) {
                    rail = connections.values().iterator().next();
                }
            }
        }
        if (rail == null) return;

        // 构建样式列表（MTR4 惯例：非默认样式需加 _1 方向后缀）
        // 空列表 = 默认选择 default_3d
        ObjectArrayList<String> styles = new ObjectArrayList<>();
        if (modelKeys.isEmpty()) {
            styles.add("default_3d");
        } else {
            for (String key : modelKeys) {
                styles.add(key + "_1");
            }
        }

        // 复制轨道并应用新样式，发包同步到服务端
        Rail newRail = Rail.copy(rail, styles);
        InitClient.REGISTRY_CLIENT.sendPacketToServer(
                new PacketUpdateData(new UpdateDataRequest(MinecraftClientData.getInstance()).addRail(newRail)));

        // 给玩家操作反馈
        if (context.getPlayer() != null) {
            context.getPlayer().displayClientMessage(
                    ComponentHelper.translatable(modelKeys.isEmpty()
                            ? "msg.fangsu.rail_model_tool.cleared"
                            : "msg.fangsu.rail_model_tool.applied", String.join(", ", modelKeys)),
                    true);
        }
    }
}
