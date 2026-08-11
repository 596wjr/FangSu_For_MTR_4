package com.fangsu.mixin;

import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import com.fangsu.blocks.BlockMultiDirectionNode;
import com.fangsu.util.NodeConnector;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.item.ItemBlockClickingBase;
import org.mtr.mod.item.ItemRailModifier;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.RenderRails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 为万向节点添加 ghost rail 预览功能。
 * <p>
 * MTR 原版 ghost rail 仅在终点方块为 {@link BlockNode} 时才渲染（{@code instanceof BlockNode} 硬编码检查）。
 * 本 mixin 在 {@link RenderRails#render()} 末尾注入，检测到万向节点参与时，
 * 使用 {@link MainRenderer#scheduleRender} + {@link RailMath#render} 补充渲染预览线。
 * <p>
 * 预览线使用黄色（{@code 0xFFFFFF00}），与 MTR 原版白色 ghost rail 区分。
 *
 * @see RenderRails#render() 原版 ghost rail 代码（#106-#141）
 */
@Mixin(value = RenderRails.class, remap = false)
public class RenderRailsMixin {

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private static void fangsu$addMultiDirectionGhostRail(CallbackInfo ci) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // 检查主手是否为轨道连接器（与原版 ghost rail 检测方式一致）
        net.minecraft.world.item.ItemStack itemStack = mc.player.getMainHandItem();
        net.minecraft.world.item.Item item = itemStack.getItem();
        if (!(item instanceof ItemRailModifier)) {
            itemStack = mc.player.getOffhandItem();
            item = itemStack.getItem();
            if (!(item instanceof ItemRailModifier)) return;
        }

        // 检查是否有已存储的第一点击位置
        final CompoundTag tag = itemStack.getOrCreateTag();
        if (!tag.contains(ItemBlockClickingBase.TAG_POS)) return;

        final long packedEnd = tag.getLong(ItemBlockClickingBase.TAG_POS);
        final BlockPos posEnd = new BlockPos(BlockPos.getX(packedEnd), BlockPos.getY(packedEnd), BlockPos.getZ(packedEnd));
        final BlockState blockStateEnd = mc.level.getBlockState(posEnd);

        // 仅处理万向节点作为第一点击位置的情况（MTR 已自行处理普通 BlockNode）
        if (!(blockStateEnd.getBlock() instanceof BlockMultiDirectionNode)) return;

        // 获取准星瞄准位置（模仿原版 ghost rail 的 HitResult 获取方式）
        final HitResult hitResult = mc.hitResult;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) return;
        final net.minecraft.world.phys.Vec3 hitPos = hitResult.getLocation();
        final BlockPos posStart = new BlockPos(
                (int) Math.floor(hitPos.x),
                (int) Math.floor(hitPos.y),
                (int) Math.floor(hitPos.z)
        );

        // 防止在同一个方块上自连
        if (posStart.equals(posEnd)) return;

        final BlockState blockStateStart = mc.level.getBlockState(posStart);

        // 转换为 MTR wrapper 类型（提前构造，供角度计算和轨创建使用）
        final org.mtr.mapping.holder.BlockPos mtrPosStart = new org.mtr.mapping.holder.BlockPos(posStart);
        final org.mtr.mapping.holder.BlockPos mtrPosEnd = new org.mtr.mapping.holder.BlockPos(posEnd);
        final org.mtr.mapping.holder.BlockState mtrStateStart = new org.mtr.mapping.holder.BlockState(blockStateStart);
        final org.mtr.mapping.holder.BlockState mtrStateEnd = new org.mtr.mapping.holder.BlockState(blockStateEnd);

        // 计算预览角度（与 onEndClick 逻辑一致，使 ghost rail 准确反映实际连接效果）
        final float[] previewAngles = computePreviewAngles(mc.level, posStart, posEnd, blockStateStart, blockStateEnd);
        final float angleStart = previewAngles[0];
        final float angleEnd = previewAngles[1];

        // 万向节点仅支持 TRAIN 运输模式
        final TransportMode transportMode = TransportMode.TRAIN;

        // 计算轨道角度（snap 到 MTR 22.5° 网格）
        // Init.blockPosToPosition 接受 MTR BlockPos，返回 MTR Position
        final ObjectObjectImmutablePair<Angle, Angle> angles = Rail.getAngles(
                Init.blockPosToPosition(mtrPosStart), angleStart,
                Init.blockPosToPosition(mtrPosEnd), angleEnd
        );

        // 创建预览用 Rail 对象
        final Rail rail = ((ItemRailModifier) item).createRail(
                mc.player.getUUID(), transportMode,
                mtrStateStart, mtrStateEnd,
                mtrPosStart, mtrPosEnd,
                angles.left(), angles.right()
        );

        if (rail != null && rail.railMath.getLength() > 0) {
            MainRenderer.scheduleRender(QueuedRenderLayer.LINES, (graphicsHolder, offset) -> {
                rail.railMath.render(
                        (x1, z1, x2, z2, x3, z3, x4, z4, y1, y2) ->
                                drawGhostSegment(graphicsHolder, offset, x1, z1, y1, x3, z3, y2),
                        0.5F, 0, 0);
            });
        }
    }

    /**
     * 计算 ghost rail 预览用的起点/终点角度（与 {@code ItemNodeModifierBaseMixin.fangsu$onEndClick} 逻辑一致）。
     * <ul>
     *   <li>两端均未绑定 → 直线角度（{@link NodeConnector#straightAngle}）</li>
     *   <li>一端未绑定 → 未绑定端取最大半径圆弧切向（{@link NodeConnector#maxRadiusTangentAngle}）</li>
     *   <li>两端已绑定 → 使用既有角度</li>
     * </ul>
     *
     * @return float[]{startAngle, endAngle}
     */
    private static float[] computePreviewAngles(
            net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos posStart,
            net.minecraft.core.BlockPos posEnd,
            BlockState stateStart,
            BlockState stateEnd
    ) {
        final boolean startBonded = isPreviewBonded(level, posStart, stateStart);
        final boolean endBonded = isPreviewBonded(level, posEnd, stateEnd);
        final float startAngle = getNodeAngle(level, posStart, stateStart);
        final float endAngle = getNodeAngle(level, posEnd, stateEnd);

        // 与 ItemNodeModifierBaseMixin.handleRailConnect 的自适应逻辑保持一致：
        // 普通节点没有绑定语义（blockstate 角度只是第一条轨道的历史方向），一律视为可自适应，
        // 否则预览与实际建轨结果不一致（实际会退化或产生畸形曲线）。
        final boolean startFixed = stateStart.getBlock() instanceof BlockMultiDirectionNode && startBonded;
        final boolean endFixed = stateEnd.getBlock() instanceof BlockMultiDirectionNode && endBonded;

        if (!startFixed && !endFixed) {
            // 两端均无固定角度 → 直线
            final float straight = (float) NodeConnector.straightAngle(posStart, posEnd);
            return new float[]{straight, straight};
        } else if (!startFixed) {
            // 起点无固定角度，终点固定 → 起点取最大半径圆弧切向
            return new float[]{(float) NodeConnector.maxRadiusTangentAngle(posEnd, endAngle, posStart), endAngle};
        } else if (!endFixed) {
            // 终点无固定角度，起点固定 → 终点取最大半径圆弧切向
            return new float[]{startAngle, (float) NodeConnector.maxRadiusTangentAngle(posStart, startAngle, posEnd)};
        } else {
            // 两端均已绑定 → 使用既有角度
            return new float[]{startAngle, endAngle};
        }
    }

    /**
     * 判断某端点在预览中是否视为"已绑定"。
     * 万向节点读取 BE 的 directionBonded；普通节点和任意非节点方块视为已绑定。
     */
    private static boolean isPreviewBonded(
            net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos pos,
            BlockState state
    ) {
        if (state.getBlock() instanceof BlockMultiDirectionNode) {
            final BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BlockEntityMultiDirectionNode node) {
                return node.isDirectionBonded();
            }
            return false;
        }
        return true; // 普通 BlockNode 或非节点方块视作已绑定
    }

    /**
     * 获取节点的角度（度）。
     * 万向节点从 BE 读取；普通 {@link BlockNode} 从 blockstate 读取；其他方快回退到玩家朝向。
     */
    private static float getNodeAngle(
            net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos pos,
            BlockState state
    ) {
        if (state.getBlock() instanceof BlockMultiDirectionNode) {
            final BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BlockEntityMultiDirectionNode node) {
                return (float) node.getDirectionDegrees();
            }
            return 0;
        }
        if (state.getBlock() instanceof BlockNode) {
            return BlockNode.getAngle(new org.mtr.mapping.holder.BlockState(state));
        }
        // 准星不在节点上 → 回退为玩家朝向（与原版 ghost rail 行为一致）
        return Minecraft.getInstance().player.getYRot() + 90;
    }

    /**
     * 绘制 ghost rail 的一段线段。
     * 参数命名与原版 MTR {@code RenderRail.renderRail()} 一致。
     */
    private static void drawGhostSegment(
            GraphicsHolder graphicsHolder, Vector3d offset,
            double x1, double z1, double y1,
            double x3, double z3, double y2
    ) {
        graphicsHolder.drawLineInWorld(
                (float) (x1 - offset.getXMapped()),
                (float) (y1 - offset.getYMapped() + 0.0625),
                (float) (z1 - offset.getZMapped()),
                (float) (x3 - offset.getXMapped()),
                (float) (y2 - offset.getYMapped() + 0.0625),
                (float) (z3 - offset.getZMapped()),
                0xFFFFFF00  // 黄色预览线，与 MTR 原版白色 ghost rail 区分
        );
    }

    // ==================== 预留：MTR 新版 RailMath.RenderRail 接口（13 参数） ====================
    // MTR 4 某版本后 RenderRail 签名从 10 参数变为 13 参数（新增 y3, y4, radius1, radius2），
    // 当前 MTR 4.0.5 仍为 10 参数版本。若未来升级 MTR 后编译报错，取消下面注释并替换上面的
    // rail.railMath.render() 调用块。
    //
    // rail.railMath.render(
    //         (RailMath.RenderRail) (x1, z1, x2, z2, x3, z3, x4, z4, y1, y2, y3, y4, radius1, radius2) ->
    //                 drawGhostSegmentNew(graphicsHolder, offset, x1, z1, y1, x3, z3, y3),
    //         0.5F, 0, 0);
    //
    // private static void drawGhostSegmentNew(
    //         GraphicsHolder graphicsHolder, Vector3d offset,
    //         double x1, double z1, double y1,
    //         double x3, double z3, double y3
    // ) {
    //     graphicsHolder.drawLineInWorld(
    //             (float) (x1 - offset.getXMapped()),
    //             (float) (y1 - offset.getYMapped() + 0.0625),
    //             (float) (z1 - offset.getZMapped()),
    //             (float) (x3 - offset.getXMapped()),
    //             (float) (y3 - offset.getYMapped() + 0.0625),
    //             (float) (z3 - offset.getZMapped()),
    //             0xFFFFFF00
    //     );
    // }
}
