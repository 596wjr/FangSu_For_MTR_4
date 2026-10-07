package com.fangsu.mixin;

import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import com.fangsu.blocks.BlockMultiDirectionNode;
import com.fangsu.render.RailRollRenderHelper;
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
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.item.ItemBlockClickingBase;
import org.mtr.mod.item.ItemRailModifier;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.RenderRails;
import org.mtr.mod.render.StoredMatrixTransformations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

/**
 * 为万向节点添加 ghost rail 预览功能。
 * <p>
 * MTR 原版 ghost rail 仅在终点方块为 {@link BlockNode} 时才渲染（{@code instanceof BlockNode} 硬编码检查）。
 * 本 mixin 在 {@link RenderRails#render()} 末尾注入，检测到万向节点参与时，
 * 使用 {@link MainRenderer#scheduleRender} + {@link RailMath#render} 补充渲染预览线。
 * <p>
 * 预览线使用黄色（{@code 0xFFFFFF00}），与 MTR 原版白色 ghost rail 区分。
 * <p>
 * <b>P4b-1（外轨超高·轨道截面滚转）</b>也挂在本 mixin 上：在
 * {@code RenderRails.renderRailStandard}（轨面入口，10 参数重载）上用
 * {@code @ModifyVariable(argsOnly, index = 1)} 捕获该截面的 {@link Rail}，在 RETURN 上清除，
 * 再用三个<b>已显式限定 {@code method}</b>的 {@code @Redirect} 分别包装延迟绘制消费者、
 * 旋转 {@code IDrawing.drawTexture} 的 4 个四边形角点、以及给 3D 轨道模型的
 * {@code StoredMatrixTransformations} 追加滚转（FIX 4 / D7）。详见
 * {@link RailRollRenderHelper} 与下方 P4b-1 小节。
 *
 * @see RenderRails#render() 原版 ghost rail 代码（#106-#141）
 */
@Mixin(value = RenderRails.class, remap = false)
public class RenderRailsMixin {

    // ==================== P4b-1：轨道截面滚转 ====================
    //
    // 数据流（MTR 4.0.5 的真实调用链，已对真实 jar 的字节码核实）：
    //   RenderRails.render()
    //     → lambda$render$5（每帧每条轨道，同步）
    //       → renderRailStandard(ClientWorld, Rail, float, RenderState, float, Identifier, float×4) 【同步】
    //         → renderWithinRenderDistance(rail, consumer, interval, -railWidth, +railWidth)        【同步】
    //           → rail.railMath.render(callback, ...)                                                【同步】
    //             → 回调给出 10 个 double（4 个 (x,z) 角点 + 只有 2 个 y，无法表达滚转截面）
    //             → lambda$renderRailStandard$19：把顶点闭包交给 MainRenderer.scheduleRender        【同步排队】
    //               → 本帧稍后（渲染线程）执行 lambda$renderRailStandard$18
    //                 → IDrawing.drawTexture(12 个 double)                                         【真正的绘制】
    //       → renderRailOneWayArrows / renderSignalsStandard：同样各自调 renderWithinRenderDistance
    //         并各排入一个 4 参数 scheduleRender（纹理分别是箭头 / WOOL），但<b>不</b>经过
    //         renderRailStandard 的方法体。
    //
    // 因此需要两个不同线程上的「上下文」：
    //   1) 在 renderRailStandard 的方法体内建立「当前轨道截面」快照（@ModifyVariable 写一格
    //      ThreadLocal、RETURN 清除）—— 该方法第 4 个参数是包级私有的 RenderRails$RenderState，
    //      跨包无法写进 handler 签名，所以轨道只能用 @ModifyVariable 取（argsOnly + index 就是
    //      参数序号；本方法全是 1 槽类型，参数序号与局部变量槽位一致，Rail 在 index 1、D8 已修）；
    //   2) 排队那一刻（仍在同一同步窗口内）把轨道滚转快照包装进 BiConsumer —— 重定向本身
    //      已锁死在轨面排队 lambda 里，于是箭头 / 信号的排队天然被排除（D1 修复的核心）；
    //   3) 真正的 drawTexture 重定向读渲染线程上的快照并做旋转；
    //   4) 3D 模型路径（FIX 4 / D7）另有一个 @Redirect，在同一个同步窗口内给
    //      StoredMatrixTransformations 追加滚转。
    //
    // @Inject / @ModifyVariable / 三个 @Redirect <b>全部</b>写出 method。
    //
    // 关于 @Redirect 的历史教训（曾导致游戏在 mixin APPLY 阶段崩溃）：
    // Mixin 0.8.7 的 InjectionInfo.parseSelectors 在 @Redirect 既没有 method、也没有 target 时
    // 会直接抛 InvalidInjectionException（"%s is missing 'method' or 'target' to specify targets"），
    // 而不是「按 @At 自动扫描全部方法」。@At 只在已选定的方法内定位调用点，不能替代 method。
    // 因此每个重定向都必须写出目标方法选择器（见 RailRollRenderHelper 的那三个常量）：
    //   - MainRenderer.scheduleRender 的 4 参数重载在 4.x 里有 3 个调用点
    //     （轨面 lambda$renderRailStandard$19、信号 lambda$renderSignalsStandard$21、
    //     单行箭头 lambda$renderRailOneWayArrows$14，已用 javap 逐个核实）→ 只重定向轨面那个；
    //   - IDrawing.drawTexture 的 12-double 重载被 lambda$renderRailStandard$18、
    //     lambda$renderSignalsStandard$20、lambda$renderRailOneWayArrows$13 调用
    //     → 只重定向轨面那个；
    //   - StoredMatrixTransformations 的 (DDD) 构造器在 lambda$renderRailStandard$16（3D 模型路径）
    //     里只出现一次 → 只重定向那一处。
    // 目标选择器刻意<b>只写方法名</b>：Mixin 0.8.5 的 AP 无法解析 synthetic lambda 的描述符
    // （写成「名字+描述符」必然报 Cannot find target method），详见 helper 里三个常量的注释。
    // 「是否真的旋转」另有 ACTIVE_FRAME / SECTION_FRAME 把关，普通 MTR 轨道、信号与单行箭头
    // 都逐位走原生路径。
    // 上面这些滚转钩子全部带 require = 0：解析失败不崩游戏，由 RailRollRenderHelper 的一次性 warn 报告。
    // 例外是下面那个 ghost rail 预览钩子（@Inject @At("TAIL")，require 用 Mixin 默认值 1）：
    // 它是本类里唯一「解析失败就会在 APPLY 阶段报错」的注入点，因此被 D-C 的诊断看门狗当作时钟
    // （见 fangsu$addMultiDirectionGhostRail 与 RailRollRenderHelper#probeRenderFrame）。

    /**
     * 结束「当前轨道截面」同步窗口并<b>清除</b> ThreadLocal（D5：旧实现从不清理，既长期持有
     * {@link Rail}，也是 D1 误伤箭头 / 信号的根因）。
     * <p>
     * 处理器只接 {@code CallbackInfo}：Mixin 0.8.5 的 {@code CallbackInjector} 只接受
     * 「完整参数」或「仅 CallbackInfo」两种签名（见其 {@code checkDescriptor}，不接受只写前缀），
     * 而本方法第 4 个参数是包级私有的 {@code RenderRails$RenderState}，无法命名。
     * <p>
     * 快照是「每次调用整体覆盖」的单槽而不是栈，所以即使本钩子漏挂也不会累积越界残值。
     */
    @Inject(method = RailRollRenderHelper.RENDER_RAIL_STANDARD_DESCRIPTOR, at = @At("RETURN"), require = 0, remap = false)
    private static void fangsu$endRailSection(CallbackInfo ci) {
        RailRollRenderHelper.endRailSection();
    }

    /**
     * 捕获当前正在渲染截面的 {@link Rail}（参数序号 / 槽位 1）。
     * <p>
     * {@code renderRailStandard} 是 <b>static</b>，所以「参数序号 == 局部变量槽位」。
     * 真实 4.0.5 jar 的 {@code LocalVariableTable}（{@code javap -p -c -l}）为
     * <pre>
     *   0 clientWorld  Lorg/mtr/mapping/holder/ClientWorld;
     *   1 rail         Lorg/mtr/core/data/Rail;
     *   2 yOffset      F
     *   3 renderState  Lorg/mtr/mod/render/RenderRails$RenderState;
     *   4 railWidth    F
     *   5 defaultTexture Lorg/mtr/mapping/holder/Identifier;
     *   6..9 u1 v1 u2 v2  F
     * </pre>
     * 因此 {@link Rail} 在 <b>index = 1</b>（而 {@code RenderState} 是包级私有类型，无法命名，
     * 这正是必须用 {@code @ModifyVariable} 而不是普通参数的原因）。
     * <p>
     * <b>D8（本次修复）</b>：本钩子曾经写成 {@code index = 0}（那是 {@code clientWorld}）。
     * {@code @ModifyVariable} 的处理器类型是 {@code Rail}、与槽位 0 的 {@code ClientWorld} 不匹配，
     * 于是该注入点<b>一次都不会触发</b>；{@code require = 0} 又把失败藏成静默，
     * 导致 {@code SECTION_FRAME} 恒为 null —— 四边形旋转那段代码从未执行，
     * 外轨超高只表现为「内核把中心线整体抬高、截面完全不倾斜」（Symptom 1 的根因）。
     * 修改此处前请先用 {@code javap} 复核槽位表。
     * <p>
     * 处理器只把原值原样返回（{@code argsOnly} 语义上就是「可能改写入参」，这里刻意不改）。
     */
    @ModifyVariable(
            method = RailRollRenderHelper.RENDER_RAIL_STANDARD_DESCRIPTOR,
            at = @At("HEAD"),
            argsOnly = true,
            index = 1,
            require = 0,
            remap = false
    )
    private static Rail fangsu$captureSectionRail(Rail rail) {
        return RailRollRenderHelper.captureSectionRail(rail);
    }

    /**
     * 拦截延迟绘制消费者，给带滚转的轨道挂上渲染线程上下文。
     * <p>
     * 是否真的滚转由 {@link RailRollRenderHelper#wrapRailQuad(BiConsumer)}
     * 依据「当前是否处在轨面截面窗口内」判定（{@code @At} 已把重定向锁死在轨面排队 lambda
     * {@code lambda$renderRailStandard$19} 内，信号 / 单行箭头走它们自己的 lambda，因此不需要
     * 再比对纹理 —— 原先的 {@code SECTION_TEXTURE} 纹理门控已随本次修复删除）；
     * 不在窗口内时它会返回<b>原消费者实例</b>，排队、执行、纹理、光照与 MTR 原生逐位一致。
     * <p>
     * {@code method} 必须显式写出（这是本 mixin 上一版崩溃的直接原因）：Mixin 0.8.7 的
     * {@code InjectionInfo.parseSelectors} 对「既无 {@code method} 又无 {@code target}」的
     * {@code @Redirect} 直接抛 {@code InvalidInjectionException}，游戏在 APPLY 阶段即崩。
     * {@code @At} 只负责在<b>已选定的方法内</b>定位调用点，不能代替 {@code method}。
     * 这里用 {@link RailRollRenderHelper#RAIL_QUAD_SCHEDULE_TARGET} 锁定
     * {@code lambda$renderRailStandard$19}（只写名字：AP 解析不了 synthetic 描述符），
     * 并用 {@code require = 0} 让未来 MTR 重排 lambda 编号时退化为「不倾斜」而不是崩游戏。
     */
    @Redirect(
            method = RailRollRenderHelper.RAIL_QUAD_SCHEDULE_TARGET,
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/mtr/mod/render/MainRenderer;scheduleRender(Lorg/mtr/mapping/holder/Identifier;ZLorg/mtr/mod/render/QueuedRenderLayer;Ljava/util/function/BiConsumer;)V"
            ),
            require = 0,
            remap = false
    )
    private static void fangsu$scheduleRolledRailQuad(
            Identifier identifier, boolean flag,
            QueuedRenderLayer renderLayer,
            BiConsumer<GraphicsHolder, Vector3d> consumer
    ) {
        MainRenderer.scheduleRender(identifier, flag, renderLayer, RailRollRenderHelper.wrapRailQuad(consumer));
    }

    /**
     * 四边形顶点生成点：按局部滚转角绕轨道前进轴旋转 4 个角点。
     * <p>
     * 目标重载是 {@code IDrawing.drawTexture(GraphicsHolder, double x1,y1,z1, x2,y2,z2,
     * x3,y3,z3, x4,y4,z4, Vector3d offset, float u1,v1,u2,v2, Direction, int light, int color)}
     * —— 即 4.0.5 里轨面路径使用的 12-double 重载
     * （同重载也被信号 / 单行箭头调用，但它们不会进入 {@code ACTIVE_FRAME} 窗口）。
     * <p>
     * 与上面那个 {@code @Redirect} 同理，{@code method} 必须显式写出；这里用
     * {@link RailRollRenderHelper#RAIL_QUAD_DRAW_TARGET} 锁定
     * {@code lambda$renderRailStandard$18}（同样只写名字）。
     * 只有 {@code ACTIVE_FRAME} 已挂载（= 正在执行带滚转轨道的延迟绘制消费者）时才变换，
     * 其余调用（含信号、单行箭头、ghost 预览、普通 MTR 轨道）原参数转发。
     * 旋转是刚体变换，保持原有的角点顺序（也就是保持绕序），
     * u/v、light、color、Direction、offset 全部原样透传。
     */
    @Redirect(
            method = RailRollRenderHelper.RAIL_QUAD_DRAW_TARGET,
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/mtr/mod/client/IDrawing;drawTexture(Lorg/mtr/mapping/mapper/GraphicsHolder;DDDDDDDDDDDDLorg/mtr/mapping/holder/Vector3d;FFFFLorg/mtr/mapping/holder/Direction;II)V"
            ),
            require = 0,
            remap = false
    )
    private static void fangsu$drawRolledRailQuad(
            GraphicsHolder graphicsHolder,
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            double x3, double y3, double z3,
            double x4, double y4, double z4,
            Vector3d offset, float u1, float v1, float u2, float v2,
            Direction facing, int light, int color
    ) {
        RailRollRenderHelper.drawRolledQuad(
                graphicsHolder,
                x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4,
                offset, u1, v1, u2, v2, facing, light, color
        );
    }

    /**
     * FIX 4（D7）：3D 轨道模型路径的滚转。
     * <p>
     * <b>为什么必须补这条路</b>：{@code defaultRail3D} 默认为 true（本工作区的 dev config 也是 true），
     * 此时 TRAIN 轨的 {@code "default"} 样式被改写成 {@code "default_3d"}，
     * 只要玩家没手持轨道相关物品（{@code RenderState.hasColor == false}），
     * 程序化轨面（上面那条 12-double {@code drawTexture} 路径）<b>整个不执行</b>，
     * 玩家看到的只有 {@code lambda$renderRailStandard$16} 里的 3D 模型 ——
     * 而它原本没有任何倾斜路径，于是「抬高了但没倾斜」。
     * <p>
     * <b>目标点</b>（已用 {@code javap -p -c -l} 对真实 4.0.5 jar 核实）：
     * {@code lambda$renderRailStandard$16(ClientWorld, RailResource, boolean, boolean[], BlockPos,
     * double×10)} 在字节码 <b>70–109</b> 构造
     * {@code new StoredMatrixTransformations((x1+x3)/2, (y1+y2)/2 + railResource.getModelYOffset(),
     * (z1+z3)/2)}，在 <b>111–127</b> 调 {@code add(Consumer)}（偏航 / 俯仰 / 摆动），
     * 在 <b>130–135</b> 调 {@code RailResource.render(StoredMatrixTransformations, int)}。
     * 因此这里用 {@code @At("NEW")} 重定向该构造器，把（可能为空的）滚转追加进同一个对象。
     * <p>
     * <b>枢轴</b>：{@code StoredMatrixTransformations.transform} 先 {@code translate} 到该构造参数
     * （即被内核抬升过的中心线），再按顺序执行追加的消费者，所以滚转天然绕<b>抬升后的中心线</b>
     * 旋转，与轨面（ribbon）和车体的枢轴一致 —— 不需要、也不允许再加任何 Y 平移（会重复抬升）。
     * <p>
     * <b>关于追加位置</b>：本重定向的处理器在构造器返回后立刻 {@code add}，此时 MTR 的
     * 偏航 / 俯仰消费者还没加进来，所以滚转在列表里排在最前 = 在世界系里<b>先</b>施加。
     * 处理器内因此不是绕局部 Z 转，而是用
     * {@code R_y(-φ)·R_x(θ)·R_y(φ)} 显式构造「绕世界水平轴 (cosφ, 0, sinφ) 旋转 θ」
     * （φ = 该处轨道切向角），对整个已定向的模型做刚体滚转 —— 物理效果等价、且与镜像样式
     * {@code _2}（其模型局部前进轴反向）无关。详见
     * {@link RailRollRenderHelper#appendRailModelRoll(StoredMatrixTransformations, double, double, double)}。
     * <p>
     * {@code method} 同样必须显式写出（见本类上方关于 Mixin 0.8.7 的历史教训），
     * synthetic lambda 只能写方法名，{@code require = 0} 让未来 MTR 改编号时退化为「不倾斜」。
     */
    @Redirect(
            method = RailRollRenderHelper.RAIL_MODEL_MATRIX_TARGET,
            at = @At(
                    value = "NEW",
                    target = "(DDD)Lorg/mtr/mod/render/StoredMatrixTransformations;"
            ),
            require = 0,
            remap = false
    )
    private static StoredMatrixTransformations fangsu$rollRailModel(double x, double y, double z) {
        final StoredMatrixTransformations storedMatrixTransformations = new StoredMatrixTransformations(x, y, z);
        // 没有活动截面快照 / 无滚转 / 几何退化时不追加任何消费者 —— 普通轨道逐位走原生路径
        RailRollRenderHelper.appendRailModelRoll(storedMatrixTransformations, x, y, z);
        return storedMatrixTransformations;
    }

    /**
     * ghost rail 预览 + <b>P4b 诊断看门狗（D-C）</b>。
     * <p>
     * <b>为什么把看门狗挂在这里</b>：本注入点是整个 P4b 特性里<b>唯一没有 require = 0</b>
     * 的钩子（{@code @Inject} 的 require 默认为 1）—— 一旦解析不了，Mixin 会在 APPLY 阶段直接报错，
     * 不可能静默失效；而 {@code RenderRails.render} 是每帧的轨道渲染入口，只要玩家在世界里渲染轨道
     * 就会被调用。因此它满足「必定会执行」这个前提，可以给
     * {@link RailRollRenderHelper#probeRenderFrame()} 当诊断时钟 —— 旧实现用两条 require = 0
     * 的钩子自己的采样计数当时钟，于是「所有滚转钩子都没挂上」时计数恒为 0，日志一片空白。
     * <p>
     * 该调用只做「帧计数 + 每 120 帧扫一次客户端轨道缓存 + 一次性 warn」，无逐帧分配、无刷屏。
     */
    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private static void fangsu$addMultiDirectionGhostRail(CallbackInfo ci) {
        RailRollRenderHelper.probeRenderFrame();
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
            // 预览也要带上节点平移：实际建轨走 NodeConnector.createAndSendRail → readRailPose，
            // 这里用同一份数据，ghost rail 才不会与真正建出来的轨道错开。
            NodeConnector.applyPose(rail, NodeConnector.readRailPose(mc.level, posStart, posEnd));
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

        // 节点平移（P2）：角度按「方块坐标 + 节点偏移」的锚点算，与实际建轨（ItemNodeModifierBaseMixin
        // → NodeConnector.straightAngle/maxRadiusTangentAngle 的双精度重载）保持同一套几何。
        final double[] startOffset = NodeConnector.readNodeOffset(level, posStart);
        final double[] endOffset = NodeConnector.readNodeOffset(level, posEnd);

        // 与 ItemNodeModifierBaseMixin.handleRailConnect 的角度语义保持一致：
        // 万向节点已绑定、或普通节点（blockstate 角度即其绑定方向，与原版节点一致）都视为固定；
        // 未绑定的万向节点端取最大半径圆弧切向自适应。预览采用固定角度优先
        // （与建轨的首选候选一致；实际建轨在几何不成立时会降级，预览近似即可）。
        final boolean startFixed = startBonded;
        final boolean endFixed = endBonded;

        if (!startFixed && !endFixed) {
            // 两端均无固定角度 → 直线
            final float straight = (float) NodeConnector.straightAngle(posStart, startOffset, posEnd, endOffset);
            return new float[]{straight, straight};
        } else if (!startFixed) {
            // 起点无固定角度，终点固定 → 起点取最大半径圆弧切向
            return new float[]{(float) NodeConnector.maxRadiusTangentAngle(posEnd, endOffset, endAngle, posStart, startOffset), endAngle};
        } else if (!endFixed) {
            // 终点无固定角度，起点固定 → 终点取最大半径圆弧切向
            return new float[]{startAngle, (float) NodeConnector.maxRadiusTangentAngle(posStart, startOffset, startAngle, posEnd, endOffset)};
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
