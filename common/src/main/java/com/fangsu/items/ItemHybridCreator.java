package com.fangsu.items;

import com.fangsu.data.hybrid.HybridSliceAction;
import com.fangsu.data.hybrid.HybridSliceTask;
import com.fangsu.mappings.ComponentHelper;
import com.fangsu.network.HybridCreatorPackets;
import org.jetbrains.annotations.NotNull;
import org.mtr.core.data.Rail;
import org.mtr.core.tool.Vector;
import org.mtr.mapping.holder.ActionResult;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.Hand;
import org.mtr.mapping.holder.ItemStack;
import org.mtr.mapping.holder.ItemUsageContext;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.World;
import org.mtr.mod.Init;
import org.mtr.mod.item.ItemNodeModifierSelectableBlockBase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 混合构建器——在轨道上按切片间距重复放置横截面矩阵的构建工具。
 * <p>
 * 右键空气/非节点方块：打开任务列表编辑器屏幕。
 * 依次左键点击轨道两端节点：触发构建（每个任务按 order 依次挂载到 MTR 构建队列）。
 * <p>
 * 继承 {@link ItemNodeModifierSelectableBlockBase}（width=1 → radius=0），自动获得：
 * 查轨回调链、万向节点兼容（{@code ItemNodeModifierBaseMixin} 的
 * {@code instanceof ItemNodeModifierSelectableBlockBase} 分支自动命中本物品）。
 */
public class ItemHybridCreator extends ItemNodeModifierSelectableBlockBase {

    /** NBT 键：任务列表（客户端编辑器写、服务端构建时读） */
    public static final String TAG_TASKS = "tasks";
    /** NBT 键：先点（第一次点击）位置 */
    private static final String TAG_FIRST_POS = "hybrid_first_pos";
    /** NBT 键：后点（第二次点击）位置 */
    private static final String TAG_LAST_POS = "hybrid_last_pos";

    public ItemHybridCreator() {
        super(false, 0, 1, new org.mtr.mapping.holder.ItemSettings());
    }

    @Override
    public void useWithoutResult(World world, @NotNull PlayerEntity user, @NotNull Hand hand) {
        // 右键空气：开编辑器
        if (!world.isClient() && ServerPlayerEntity.isInstance(user)) {
            HybridCreatorPackets.sendOpenScreenS2C(ServerPlayerEntity.cast(user));
        }
    }

    @Override
    public @NotNull ActionResult useOnBlock2(@NotNull ItemUsageContext context) {
        if (clickCondition(context)) {
            // 客户端必须返回 SUCCESS 消费动作：ItemBlockClickingBase 的客户端分支会返回
            // ItemAbstractMapping 默认的 PASS，客户端随后预测调用 use() 并发包到服务端，
            // 触发 useWithoutResult 误开编辑器（ANTE 的 useOn 同样无条件返回 SUCCESS）
            if (context.getWorld().isClient()) {
                return ActionResult.SUCCESS;
            }
            return super.useOnBlock2(context);
        }
        // 点击非节点方块：开编辑器（照 ANTE CompoundCreator.useOn）
        final World world = context.getWorld();
        if (!world.isClient()) {
            final PlayerEntity player = context.getPlayer();
            if (ServerPlayerEntity.isInstance(player)) {
                HybridCreatorPackets.sendOpenScreenS2C(ServerPlayerEntity.cast(player));
            }
        }
        return ActionResult.SUCCESS;
    }

    @Override
    protected void onStartClick(@NotNull ItemUsageContext context, @NotNull CompoundTag compoundTag) {
        super.onStartClick(context, compoundTag);
        // 记录先点位置，供 onConnect 判断展开方向（回调可能是异步线程，存 NBT 最稳）
        compoundTag.putLong(TAG_FIRST_POS, context.getBlockPos().asLong());
    }

    @Override
    protected void onEndClick(@NotNull ItemUsageContext context, @NotNull BlockPos posEnd, @NotNull CompoundTag compoundTag) {
        super.onEndClick(context, posEnd, compoundTag);
        compoundTag.putLong(TAG_LAST_POS, posEnd.asLong());
    }

    @Override
    protected void onConnect(Rail rail, @NotNull ServerPlayerEntity serverPlayerEntity, ItemStack itemStack, int radius, int height) {
        // 直接用 vanilla NBT（mapping holder 的方法集与 vanilla 不同，如 getAllKeys）
        final net.minecraft.nbt.CompoundTag tag = itemStack.data.getOrCreateTag();

        // ── 展开方向：照 ANTE 交换 posStart/posEnd 的语义（展开起点 = 后点端）──
        // rail.railMath.getPosition(0, false) 恒为端点排序小端 A（reversePositions 已内置处理），
        // 后点 == A 时从 A 出发（reverse=false），否则从另一端出发（reverse=true）。
        // 注意：mapping holder 未覆写 equals（引用比较），必须比较 .data
        final Vector posA = rail.railMath.getPosition(0, false);
        final boolean reverse = !Init.newBlockPos(posA.x, posA.y, posA.z).data
                .equals(BlockPos.fromLong(tag.getLong(TAG_LAST_POS)).data);

        if (!tag.contains(TAG_TASKS)) {
            serverPlayerEntity.data.displayClientMessage(ComponentHelper.translatable("msg.fangsu.hybrid_creator.no_tasks_found"), true);
            return;
        }

        // 注意：这里必须是 vanilla 类型（tag 是 vanilla；import 的 mapping holder CompoundTag
        // 只用于 onStartClick/onEndClick 的覆写签名）
        final net.minecraft.nbt.CompoundTag tasksTag = tag.getCompound(TAG_TASKS);
        final List<HybridSliceTask> tasks = new ArrayList<>();
        for (String key : tasksTag.getAllKeys()) {
            final HybridSliceTask task = new HybridSliceTask(tasksTag.getCompound(key));
            // 防御：任务数据损坏（如手工改 NBT）时跳过，避免构建越界；
            // lumps 为 N 组（厚度）平铺 = thickness × 宽 × 高
            if (task.width < 1 || task.height < 1 || task.step <= 0 || task.thickness < 1 || task.lumps.size() != task.thickness * task.width * task.height) {
                com.fangsu.Main.LOGGER.error("[HybridCreator] 跳过非法任务 {}（{}x{} step={} lumps={}）", task.name, task.width, task.height, task.step, task.lumps.size());
                continue;
            }
            tasks.add(task);
        }
        tasks.sort(Comparator.comparingInt(task -> task.order));

        // getServerWorld() 为 MTR mapping 全版本 API（照 ItemBridgeCreator 写法），
        // 不用 vanilla 的 Entity.level()（1.20.2 才引入）/ level 字段（旧版可见性随版本变）
        final ServerWorld serverWorld = serverPlayerEntity.getServerWorld();
        for (HybridSliceTask task : tasks) {
            // 逐个挂载；RailActionModule.tick() 一次只处理队头，按 order 串行执行
            HybridSliceAction.attach(serverWorld, new HybridSliceAction(serverWorld, serverPlayerEntity, rail, task, reverse));
        }
    }
}
