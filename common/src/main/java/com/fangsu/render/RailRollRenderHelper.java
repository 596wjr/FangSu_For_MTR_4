package com.fangsu.render;

import com.fangsu.Main;
import com.fangsu.mappings.rail.RailGeometryCore;
import com.fangsu.mtr.rail.FangSuRailMath;
import com.fangsu.mtr.rail.RailPoseExtraHolder;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.render.StoredMatrixTransformations;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * P4b 外轨超高（滚转）渲染支持：<b>轨道截面倾斜</b>（P4b-1）与<b>车体随轨滚转</b>（P4b-2）。
 * <p>
 * <b>为什么必须在这里做，而不是在几何里做</b>：
 * MTR 4.0.5 的 {@code org.mtr.core.data.RailMath$RenderRail.renderRail} 回调只有
 * <b>10 个 double</b>，语义为
 * <pre>
 * (上一截面·半径1的 x, z, 上一截面·半径2的 x, z,
 *  当前截面·半径2的 x, z, 当前截面·半径1的 x, z,
 *  上一截面的 y, 当前截面的 y)
 * </pre>
 * 也就是「4 个 (x,z) 角点 + <b>只有 2 个 y</b>」。横断面一旦绕前进轴滚转，
 * 四个角点的 y 就各不相同，这个回调在数学上<b>无法表达</b>滚转后的截面
 * （MTR 上游正是把回调扩到 13 个 double 才解决）。
 * 所以滚转只能施加在「10 个 double 已经变成四边形顶点」的那一步，即
 * {@code org.mtr.mod.render.RenderRails.lambda$renderRailStandard$18} 里的
 * {@code IDrawing.drawTexture(GraphicsHolder, 12×double, Vector3d, 4×float, Direction, int, int)}。
 * <p>
 * <b>延迟绘制带来的上下文问题，以及本类的解法</b>：
 * {@code lambda$renderRailStandard$19} 并不直接画，而是把顶点闭包交给
 * {@code MainRenderer.scheduleRender(Identifier, boolean, QueuedRenderLayer, BiConsumer)} 排队，
 * 真正执行 {@code drawTexture} 是在本帧稍后的渲染线程上。因此「在 {@code renderRailStandard}
 * 里挂一个 ThreadLocal」这类朴素做法必须分两步：
 * <ol>
 *   <li>排队是<b>同步</b>的：{@code renderRailStandard} 的方法体里同步调用
 *       {@code renderWithinRenderDistance} → {@code railMath.render} → 上面那个 lambda。
 *       本类由此在该方法上用 {@code @ModifyVariable(argsOnly, index = 1)} 捕获 {@code Rail}
 *       （{@link #captureSectionRail(Rail)}，<b>槽位 1</b>；D8 曾错写成 0），
 *       在 RETURN 上清除（{@link #endRailSection()}）；</li>
 *   <li>{@code scheduleRender} 的 {@code @Redirect} 只在「当前快照存在」时才包装消费者
 *       （{@link #wrapRailQuad(BiConsumer)}）。重定向的 {@code @At} 已经把范围锁死在轨面排队
 *       lambda 内（那里本来就只有这一处 {@code scheduleRender}），所以不需要再比对纹理；
 *       只有带滚转的轨道才包装，其余返回<b>同一个实例</b>（安全不变式）；</li>
 *   <li>队列真正执行时，包装器把快照挂到渲染线程的 {@code ACTIVE_FRAME} 上，再调用原消费者；
 *       此时 {@code lambda$renderRailStandard$18} 里的 {@code drawTexture} 重定向读得到它；</li>
 *   <li>3D 模型路径（FIX 4 / D7）在同一个同步窗口内读同一份快照，给
 *       {@code StoredMatrixTransformations} 追加滚转（{@link #appendRailModelRoll}）。</li>
 * </ol>
 * <b>三个 {@code @Redirect} 的 {@code method} 选择器</b>：上面三个 synthetic lambda 就是它们的
 * 目标，选择器常量见 {@link #RAIL_QUAD_SCHEDULE_TARGET}、{@link #RAIL_QUAD_DRAW_TARGET}
 * 与 {@link #RAIL_MODEL_MATRIX_TARGET}。
 * {@code method} 是<b>必需</b>的（Mixin 0.8.7 对缺 {@code method}/{@code target} 的
 * {@code @Redirect} 直接抛异常），而 synthetic 目标只能写<b>方法名</b>：Mixin 0.8.5 的 AP
 * 解析不了 synthetic lambda 的描述符。可靠性的依据是「名字在目标类内唯一 + 已在真实类文件的
 * ASM 方法表里逐字确认 + 全部受支持 MTR 4.x jar 里名字一致 + 同工程
 * {@code RenderLiftsMixin} 已有同款 name-only synthetic 目标在运行时正常工作」，
 * 而非「AP 没报警」。
 * <p>
 * <b>为什么必须按 {@code renderRailStandard} 划范围</b>：同一条轨道上的<b>单行箭头</b>
 * （{@code renderRailOneWayArrows}）与<b>信号</b>（{@code renderSignalsStandard}）也会各自调用
 * {@code renderWithinRenderDistance} 并各排入一个 4 参数 {@code scheduleRender}
 * （纹理分别是 {@code ONE_WAY_RAIL_ARROW_TEXTURE} 与 {@code WOOL_TEXTURE}，
 * 已用 {@code javap} 核实）。若只按「{@code renderWithinRenderDistance} 正在采样哪条轨道」判定，
 * 箭头与信号会因为「同一条轨道」而被一起旋转 —— 信号机会歪、箭头贴花不再水平。
 * 按 {@code renderRailStandard} 划范围后它们落在范围之外（它们各自创建自己的 lambda，
 * 与 {@code lambda$renderRailStandard$18/19} 不是同一个方法，因此 {@code @At} 天然排除）。
 * <p>
 * <b>滚转符号约定</b>（全工程统一，必须与 {@code RailPoseExtra} / 万向节点一致）：
 * {@code roll > 0} 表示「沿轨道前进方向（{@code Rail.position1 → Rail.position2}）
 * 的<b>右手侧</b>抬升」。实现上把该语义换成「绕轨道<b>参数方向</b>的世界水平单位向量旋转
 * {@code angle}」，其中
 * <pre>
 * angle = -roll * (参数方向 == position1→position2 ? +1 : -1)
 * </pre>
 * 推导：内核的横断面横向偏移正方向为 {@code L = (T.z, -T.x)}，而世界系里的「右」是
 * {@code R = T × U = (-T.z, T.x) = -L}（Minecraft 坐标系下同一套右手叉乘）。
 * 于是参数 {@code value} 处横向偏移为 {@code rho} 的角点绕参数轴右手旋转 {@code a} 后抬升
 * {@code rho·sin(a)}：要「rho<0（右）的一侧」在 roll>0 时抬升，必须 {@code a = -roll}；
 * 参数方向与 position1→position2 相反时右左互换，符号再翻一次。
 * <p>
 * <b>该符号已被独立复核确认（不要再"修"它）</b>：用反射调用真实 4.0.5 {@code RailMath} 采样
 * 四边形 + JOML 数值跑一遍，对 {@code T = +X / +Z / -X} 与一段曲线逐个验证，
 * {@code angle = -roll} 在每种情况下抬升的都是「建轨方向（{@code position1→position2}）的右手侧」。
 * 曾经出现过的「渲染与文档反相、需要把负号去掉」的诊断结论源于错误的角点映射，已作废。
 * <p>
 * <b>角点映射（复核后的正确版本，勿写反）</b>{@code lambda$renderRailStandard$18} 的两组 12-double：
 * <pre>
 * P1 = (上一截面, -railWidth)   P2 = (上一截面, +railWidth)
 * P3 = (当前截面, +railWidth)   P4 = (当前截面, -railWidth)
 * </pre>
 * 即 {@code (x1,x4)} 是 {@code offsetRadius1 = -railWidth} 的角点、{@code (x2,x3)} 是
 * {@code offsetRadius2 = +railWidth} 的角点；配合 {@code +radiusOffset -> L = (T.z, -T.x)}，
 * 「前进方向右手侧」对应 {@code rho<0}，也就是 {@code {P1, P4}} 两个角点。
 * 本类只按<b>角点序号</b>做刚体旋转（不重排、不改绕序），所以映射写错只会影响文字描述与数值分析，
 * 不影响旋转本身。
 * <p>
 * <b>轨面内侧下沉（D4，按设计保留）</b>：内核的滚转语义是「内轨保持标高、中心线抬升
 * {@code 半轨距·|sin roll|}」（{@link RailGeometryCore#rollLift(double)}），而渲染的截面绕
 * <b>抬升后的中心线</b>旋转。TRAIN 轨面的半宽是 {@code railWidth = 1.0}
 * （MTR 4.0.5 {@code lambda$render$5} 对 TRAIN 传 {@code 1.0f}，BOAT 传 {@code 0.5f}，
 * 缆车 / 飞机传 {@code 0.25f}），所以 {@code ρ = ±1.0} 的角点里与滚转同向的那一侧会下沉
 * {@code (1.0 − 半轨距)·|sin roll|}；默认半轨距 0.7175 时 6° 约 3 cm。
 * 这是「内轨保持标高」这个约定本身决定的（内轨在 {@code ρ = ∓半轨距} 处保持原标高，而轨面比它更宽），
 * 不是渲染误差，因此<b>不改几何</b>。
 * <p>
 * <b>非 TRAIN 轨（D6，当前不可达）</b>：缆车（{@code CABLE_CAR}）与飞机（{@code AIRPLANE}）轨面
 * 半宽只有 0.25，小于默认半轨距 0.7175；若把滚转施加到这类轨道上，截面会整体浮在原始标高之上
 * （最低角点仍抬高 {@code (半轨距 − 0.25)·|sin roll|}）。但滚转只能来自万向节点的「外轨超高」，
 * 而万向节点建出的轨道恒为 {@code TransportMode.TRAIN}：{@code NodeConnector.buildRail} 与
 * {@code buildRailForAngles} 的每一个分支都硬编码 TRAIN，客户端 ghost 预览同理。因此该形态
 * 当前不可达，这里只作记录、不加守卫。
 * <p>
 * <b>自定义轨道模型（D7，本次已修 → FIX 4）</b>：样式解析到自定义 {@code RailResource} 时走
 * {@code lambda$renderRailStandard$16}，用 {@code RailResource.render(StoredMatrixTransformations, int)}
 * 画 3D 模型，既不经过 12-double {@code IDrawing.drawTexture}，也不排队 scheduleRender。
 * 修法是在该 lambda 里重定向 {@code new StoredMatrixTransformations(DDD)}，给同一个对象追加
 * 一个滚转消费者（见 {@link #appendRailModelRoll}）。
 * <p>
 * 这条路径在 MTR 默认配置下<b>总会命中</b>：{@code OptimizedRenderer.hasOptimizedRendering()} 恒为 true，
 * {@code Config.getClient().getDefaultRail3D()} 默认为 true（本工作区的
 * {@code fabric/run/config/mtr.json} 也是 {@code true}），于是 TRAIN 轨的 {@code "default"} 样式
 * 被改写成 {@code "default_3d"}，{@code flags[0]} 保持 false。此时是否有程序化轨面取决于
 * {@code RenderState.hasColor}：
 * <ul>
 *   <li>玩家手持轨道相关物品（{@code hasColor} 为 true，即建造 / 预览状态）→ 程序化轨面
 *       <b>照常绘制</b>，P4b-1 的倾斜可见（箭头与信号也只在这个状态下绘制，这正是 D1 的场景）；</li>
 *   <li>平时（{@code hasColor} 为 false）→ 方法在绘制程序化轨面之前就返回，只剩 3D 模型 ——
 *       这正是「抬高但不倾斜」最常被看到的那条路径，现已由 FIX 4 补上滚转。</li>
 * </ul>
 * <p>
 * <b>只读轨道姿态，绝不读方块实体</b>：滚转角一律来自
 * {@code ((RailPoseExtraHolder) rail).getFangSuPose()} / {@code RailGeometryCore}。
 * 节点的「外轨超高」开关在 {@code NodeConnector} 合成 {@code RailPoseExtra} 时就已经把
 * {@code rollDeg} 归零，直接读 node 会绕过该开关。
 */
public final class RailRollRenderHelper {

    /**
     * P4b-1 的注入目标描述符：{@code RenderRails.renderRailStandard} 的 <b>10 参数</b>重载
     * （轨面入口；4 参数重载只是转发）。
     * <p>
     * 放在这里而不是 mixin 类里：mixin 类中的 {@code private static final String} 会被当成
     * 「待合并字段」处理，普通类没有这种不确定性。它是编译期常量，会被 javac 内联进注解值。
     */
    public static final String RENDER_RAIL_STANDARD_DESCRIPTOR =
            "renderRailStandard(Lorg/mtr/mapping/holder/ClientWorld;Lorg/mtr/core/data/Rail;F"
                    + "Lorg/mtr/mod/render/RenderRails$RenderState;FLorg/mtr/mapping/holder/Identifier;FFFF)V";

    /**
     * P4b-1 第一个 {@code @Redirect} 的目标方法选择器：轨面排队 lambda
     * {@code RenderRails.lambda$renderRailStandard$19} 的<b>方法名</b>（不带描述符）。
     * <p>
     * <b>为什么必须有这个选择器（历史教训）</b>：Sponge Mixin 0.8.7 的
     * {@code InjectionInfo.parseSelectors} 在 {@code @Redirect} 既没有 {@code method}
     * 又没有 {@code target} 时<b>直接抛异常</b>
     * （抛点紧跟字符串常量 {@code "%s is missing 'method' or 'target' to specify targets"}，
     * 可在 {@code sponge-mixin-0.15.5+mixin.0.8.7.jar} 里反汇编 {@code InjectionInfo} 看到）。
     * {@code @At} 只在「已选定的方法内」定位调用点，<b>不能</b>替代 {@code method}。
     * 早先那版误以为「不限定方法名的 {@code @Redirect} 是标准写法、描述符足以选定调用点」，
     * 于是游戏在 APPLY 阶段以 {@code InvalidInjectionException} 崩溃。
     * <p>
     * <b>为什么用「只写方法名」而不是完整描述符</b>（已实测，不要改回描述符）：
     * Mixin 0.8.5 的注解处理器<b>解析不了 synthetic lambda 的描述符</b>。
     * 实测矩阵（{@code build/tmp/rollfix/} 下的 ApProbe*Mixin 冒烟编译）：
     * <pre>
     *   @Inject(method = "renderRailStandard")                      → 无告警（但见下方“静默”说明）
     *   @Inject(method = "renderRailStandard(完整描述符)")            → 无告警 ✔ 真实方法可解析
     *   @Inject(method = "lambda$renderRailStandard$19")            → 无告警 ✔ 名字可解析
     *   @Inject(method = "lambda$renderRailStandard$19(完整描述符)")  → 告警 ✘ Cannot find target method
     *   @Redirect(method = "lambda$renderRailStandard$19")          → 无告警 ✔
     * </pre>
     * 也就是说：描述符形式的「名字 + 描述符」联合匹配对 synthetic 目标<b>必然失败</b>并产生构建告警，
     * 而<b>只写名字</b>时 AP 只做存在性解析（同一份 {@code ComputeClassInfo}/名字表），能通过。
     * 注意 AP 对「名字或描述符任一无法构成合法选择器」是<b>静默</b>的（用不存在的名字也不会告警），
     * 所以这里的可用性不靠「没告警」来证明，而靠下面的独立证据。
     * <p>
     * <b>该名字可解析的独立证据</b>（见 {@code build/tmp/rollfix/} 下的探针与日志）：
     * 读取真实 4.0.5+1.20.1 jar 里 {@code RenderRails} 的 ASM 方法表，逐字确认存在
     * {@code lambda$renderRailStandard$19(Lorg/mtr/mod/render/RenderRails$RenderState;
     * Lorg/mtr/mapping/holder/ClientWorld;Lorg/mtr/mapping/holder/Identifier;FFFFF
     * ILorg/mtr/mapping/holder/BlockPos;DDDDDDDDDD)V}（{@code synthetic=true}，access 含 0x1000）。
     * 名字在 {@code RenderRails} 内<b>唯一</b>，且对全部 6 个可用 MTR 4.x jar 逐个核对完全一致。
     * 需要描述符的调用点由 {@code @At} 的 {@code target} 精确定位。
     * <p>
     * <b>「name-only 选择器 + synthetic lambda 目标」在本工程里已被运行时证明可行</b>：
     * 同工程的 {@code com.fangsu.mixin.RenderLiftsMixin} 一直用
     * {@code @Redirect(method = "lambda$render$6", ...)}（只写名字、目标同样是 {@code RenderLifts}
     * 里的 synthetic lambda）且工作正常，说明 Mixin 运行时确实能按名字解析 synthetic 目标方法；
     * AP 解析不了它只是 AP 的能力边界，与运行时无关。
     */
    public static final String RAIL_QUAD_SCHEDULE_TARGET = "lambda$renderRailStandard$19";

    /**
     * P4b-1 第二个 {@code @Redirect} 的目标方法选择器：真正绘制四边形的那条 lambda
     * {@code RenderRails.lambda$renderRailStandard$18} 的<b>方法名</b>（不带描述符）。
     * <p>
     * 它是 4.0.5 里轨面路径唯一的 12-double {@code IDrawing.drawTexture} 调用者
     * （{@code lambda$renderSignalsStandard$20} 与 {@code lambda$renderRailOneWayArrows$13}
     * 也调这个重载，但它们不在本 {@code @Redirect} 的方法范围内）。理由与
     * {@link #RAIL_QUAD_SCHEDULE_TARGET} 完全相同：{@code method} 必需，且 synthetic 目标
     * 只能写名字。真实类文件里的描述符为
     * {@code (DDFDDDDDDDDDFFFFFIILorg/mtr/mapping/mapper/GraphicsHolder;
     * Lorg/mtr/mapping/holder/Vector3d;)V}，同样在 6 个 jar 里一致。
     */
    public static final String RAIL_QUAD_DRAW_TARGET = "lambda$renderRailStandard$18";

    /**
     * FIX 4（D7）第三个 {@code @Redirect} 的目标方法选择器：3D 轨道模型路径
     * {@code RenderRails.lambda$renderRailStandard$16} 的<b>方法名</b>（不带描述符）。
     * <p>
     * 真实 4.0.5 类文件里它的描述符为
     * {@code (Lorg/mtr/mapping/holder/ClientWorld;Lorg/mtr/mod/resource/RailResource;Z[Z
     * Lorg/mtr/mapping/holder/BlockPos;DDDDDDDDDD)V}，并在方法体内
     * （字节码 70–109）构造 {@code new StoredMatrixTransformations((x1+x3)/2,
     * (y1+y2)/2 + railResource.getModelYOffset(), (z1+z3)/2)}。
     * 只写名字的理由同前两个常量：synthetic lambda 的描述符解析不了。
     */
    public static final String RAIL_MODEL_MATRIX_TARGET = "lambda$renderRailStandard$16";

    // ==================== P4b-2（乘车分支）/ P4b-3（风挡）：本车世界 PnR 的捕获点 ====================

    /**
     * 车体滚转（乘车分支）与风挡滚转共用的捕获点：{@code RenderVehicles.lambda$render$14}
     * 的<b>方法名</b>（不带描述符）。
     * <p>
     * <b>为什么必须在这里另抓「本车的世界 PnR」</b>：
     * {@code RenderVehicles.getStoredMatrixTransformations(boolean useOffset, PositionAndRotation, double)}
     * 的第一个参数就是 MTR 源码里的 {@code offsetVector == null}，其 javadoc 原文是
     * “{@code useOffset} {@code true} if the vehicle is not being ridden in”（未乘车时为 {@code true}）。
     * 三个调用点都是 {@code getStoredMatrixTransformations(offsetVector == null, ...)}；而
     * {@code getRenderPositionAndRotation(offsetVector, offsetRotation, ridingCarPnR, renderingPnR, cameraShake)}
     * 在 {@code offsetVector != null}（乘车）时把世界 PnR 换算成<b>相机相对</b>量：
     * 位置 = 世界位置 − 相机位置（再加上相机抖动与「相对被乘车厢」的偏移），偏航 = 车体世界偏航 − 相机偏航。
     * 于是用车体位置做「最近带滚转轨道」几何匹配必然失败 —— 这就是「乘车时车体不倾斜、下车就正常」
     * （BUG 1）的根因。
     * <p>
     * <b>受影响的不是「被乘的那一节」而是「被乘列车的那一整列」</b>：{@code offsetVector} 来自
     * {@code lambda$render$16}（每<b>列</b>车调用一次）里的
     * {@code VehicleRidingMovement.getRidingVehicleCarNumberAndOffset(vehicleId)}，
     * 被捕获进 {@code lambda$render$15} → {@code lambda$render$14}，
     * 于是 {@code useOffset == false}（以及随之而来的相机相对 PnR）对该列车的<b>每一节车厢</b>都成立。
     * 所以修法必须逐车厢拿到该车真正的世界 PnR，而不是只处理被乘的那一节。
     * <p>
     * {@code lambda$render$14} 是 4.0.5 里<b>每节车厢</b>的渲染函数，它在字节码 56–64 处调用
     * {@code RenderVehicles.getRenderPositionAndRotation(...)}，第 4 个实参（局部变量槽位 21，
     * {@code LocalVariableTable} 名为 {@code absoluteVehicleCarPositionAndRotation}）就是该车自己的
     * <b>世界 PnR</b>。本类用 {@code @Redirect} 抓住这一次调用，在调用之前把它记下来，于是同一车随后建立的
     * 车体 {@code StoredMatrixTransformations}（偏移 562）与风挡顶点（偏移 642 起）都能走与「未乘车」
     * 完全相同的几何匹配代码路径，车体与风挡按构造不会给出不同的滚转角。
     * <p>
     * 只写方法名的理由同前三个常量：synthetic lambda 的描述符 AP 解析不了（见
     * {@link #RAIL_QUAD_SCHEDULE_TARGET}）。{@code lambda$render$14} 的名字唯一性与其实参表
     * （先 {@code offsetVector}/{@code offsetRotation}/{@code ridingCarPnR}，再世界 PnR）已用
     * {@code javap -p -c -l} 对真实 4.0.5 jar 逐字核实。
     */
    public static final String CAR_FRAME_TARGET = "lambda$render$14";

    /**
     * {@link #CAR_FRAME_TARGET} 里那个 {@code @Redirect} 的调用点选择器：
     * {@code RenderVehicles.getRenderPositionAndRotation(Vector3d, Double, PositionAndRotation, PositionAndRotation, Vector3d)}。
     * <p>
     * 该重载在 {@code lambda$render$14} 内<b>只出现一次</b>（偏移 64），所以重定向不会误伤别的车厢；
     * 全 jar 另外还有三处调用（{@code RenderVehicles.lambda$render$5} 转向架、{@code RenderVehicles.renderPlayer}
     * 乘车玩家实体、{@code RenderLifts.lambda$render$6} 电梯），它们都不在本重定向的 {@code method}
     * 范围内，因此逐字不受影响（ASM 全 jar 扫描：4 处，已核对）。
     */
    public static final String GET_RENDER_POSITION_AND_ROTATION_TARGET =
            "Lorg/mtr/mod/render/RenderVehicles;getRenderPositionAndRotation(Lorg/mtr/mapping/holder/Vector3d;"
                    + "Ljava/lang/Double;Lorg/mtr/mod/render/PositionAndRotation;Lorg/mtr/mod/render/PositionAndRotation;"
                    + "Lorg/mtr/mapping/holder/Vector3d;)Lorg/mtr/mod/render/PositionAndRotation;";

    /**
     * 「本车厢的两个转向架 PnR」的捕获点：{@link #CAR_FRAME_TARGET} 的<b>方法名</b>
     * （不带描述符，理由同 {@link #RAIL_QUAD_SCHEDULE_TARGET}）。
     * <p>
     * <b>为什么需要它（转向架平均的数据源）</b>：MTR 给车体算出的
     * {@code PositionAndRotation.position} 是「两转向架弦的中点」
     * （{@code PositionAndRotation(ObjectArrayList, VehicleCar, boolean)} 对两转向架位置取
     * {@code Vector.getAverage}，已用 {@code javap -c} 在偏移 108–115 逐字核实），
     * 所以只用这一个点算滚转，车体就只能等到<b>弦中点</b>进入超高段才开始倾斜 ——
     * 比前转向架晚「半个转向架间距」，这就是「要等两个转向架都进去才倾」的来源。
     * 在同一个 lambda 的头部抓到它的第 2 个形参 {@code vehicleCarDetails}
     * （槽位 {@link #BOGIE_SOURCE_INDEX}），沿 {@code right()} → {@code left()} 就能拿到那两个
     * 转向架 PnR（它们就是弦的两个端点），于是车体滚转可以按「两个转向架各自处轨道滚转的平均」
     * 来算：前转向架一进超高段就开始变化。
     * <p>
     * <b>为什么抓形参而不是 {@code iterateWithIndex} 的实参 0</b>：真实 4.0.5 字节码里
     * {@code iterateWithIndex(vehicleCarDetails.right().left(), …)} 的第 0 个实参是<b>内联表达式</b>
     * （偏移 69–79：{@code aload_1; right(); left(); checkcast}），<b>没有对应的局部变量槽位</b>，
     * {@code @ModifyVariable} 抓不到它；而槽位 10 / 11 在 {@code LocalVariableTable} 里的名字是
     * {@code previousGangwayPositionsList} / {@code previousBarrierPositionsList}（风挡 / 挡板连接点），
     * <b>不是</b>转向架列表。
     * <p>
     * <b>为什么不再用 {@code @ModifyArgs}（崩溃根因，结论务必保留）</b>：{@code @ModifyArgs} 会在运行时
     * 生成 {@code org.spongepowered.asm.synthetic.args.Args$N}，为每个实参生成「返回类型 = 该实参声明类型」
     * 的取值方法（方法体是 {@code CHECKCAST <声明类型>}）。{@code iterateWithIndex} 的第 1 个形参
     * {@code RenderVehicles$IndexedConsumer} 是<b>包级私有</b>，生成类在别的包里做 {@code checkcast}
     * 就会抛 {@code IllegalAccessError}。详见
     * {@code com.fangsu.mixin.RenderVehiclesMixin#fangsu$captureBogieSource} 的 javadoc。
     */
    public static final String BOGIE_FRAME_TARGET = "lambda$render$14";

    /**
     * {@link #BOGIE_FRAME_TARGET} 里那个 {@code @ModifyVariable} 的形参槽位：
     * {@code vehicleCarDetails}（{@code ObjectObjectImmutablePair}）。
     * <p>
     * 该 lambda 是 {@code private static}（synthetic），因此<b>形参序号 == 局部变量槽位</b>。
     * 真实 4.0.5 的 {@code LocalVariableTable}（{@code javap -p -c -l}）为：
     * <pre>
     *   0 vehicle(VehicleExtension)   1 vehicleCarDetails(ObjectObjectImmutablePair)
     *   2 offsetVector   3 offsetRotation   4 ridingCarPositionAndRotation   5 cameraShakeOffset
     *   6 carNumber   7 clientPlayerEntity   8 ridingCarNumber   9 canRide
     *  10 previousGangwayPositionsList   11 previousBarrierPositionsList
     *  12 minecraftClient   13 clientWorld   14 millisElapsed(J)   16 previousGangwayMovementPositions
     *  17 vehicleResourceDetails
     * </pre>
     * <b>类型可见性（本修复的关键，必须只命名 public 类型）</b>：
     * {@code org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair} 是
     * <b>public</b> 类（{@code javap -p}：{@code public class …ObjectObjectImmutablePair<K, V> implements
     * Pair<K, V>, Serializable}），{@code left()} / {@code right()} 也是 public 方法。
     * {@code @ModifyVariable} 不会像 {@code @ModifyArgs} 那样生成访问器类，因此这里没有
     * {@code RenderVehicles$IndexedConsumer} 那一类「包级私有类型被别的包命名」的失败面。
     */
    public static final int BOGIE_SOURCE_INDEX = 1;

    /**
     * 风挡 / 挡板滚转的 {@code @ModifyArgs} 目标方法选择器：4.0.5 里创建两个
     * {@code renderConnection} 调用的那条 lambda（{@code VehicleResource.iterateModels} 的回调，
     * {@code lambda$render$12}）的<b>方法名</b>。
     * <p>
     * 全 jar 只有两个 {@code renderConnection} 调用点：{@code javap -p -c} 显示
     * {@code renderConnection} 在整份 4.0.5 jar 里只有两个 {@code invokestatic}，都在
     * {@code lambda$render$12}（偏移 200 与 333，分别是风挡与挡板）；因此把 {@code method}
     * 锁在这里就把「修改面」限死为这两处，其它任何类、任何方法都不受影响
     * （ASM 全 jar 扫描：2 处，见 {@code build/tmp/rollfix3/HookTargetCheck}）。
     */
    public static final String CONNECTION_CALLER_TARGET = "lambda$render$12";

    /**
     * {@code renderConnection} 的完整描述符：19 个形参。
     * <p>
     * 已用 {@code javap -p -c} 对真实 4.0.5 jar 逐字核实，且与 MTR 源码
     * （{@code private static void renderConnection(boolean shouldRender1, boolean shouldRender2,
     * boolean canHaveLight, PreviousConnectionPositions previousConnectionPositions,
     * Identifier innerSide/innerTop/innerBottom/outerSide/outerTop/outerBottom,
     * PositionAndRotation positionAndRotation, boolean useOffset, double vehicleLength, double width,
     * double height, double yOffset, double zOffset, double oscillationAmount, boolean isOnRoute)}）
     * 的参数顺序完全一致。
     */
    public static final String RENDER_CONNECTION_DESCRIPTOR =
            "Lorg/mtr/mod/render/RenderVehicles;renderConnection(ZZZLorg/mtr/mod/render/RenderVehicles$PreviousConnectionPositions;"
                    + "Lorg/mtr/mapping/holder/Identifier;Lorg/mtr/mapping/holder/Identifier;Lorg/mtr/mapping/holder/Identifier;"
                    + "Lorg/mtr/mapping/holder/Identifier;Lorg/mtr/mapping/holder/Identifier;Lorg/mtr/mapping/holder/Identifier;"
                    + "Lorg/mtr/mod/render/PositionAndRotation;ZDDDDDDZ)V";

    /** {@code renderConnection} 形参序号（0 基）：车厢的 PnR。 */
    public static final int CONNECTION_POSITION_AND_ROTATION_ARG = 10;
    /**
     * {@code renderConnection} 形参序号（0 基）：{@code useOffset}（= {@code offsetVector == null}，
     * 为 {@code true} 表示未乘车、{@code positionAndRotation} 是世界 PnR）。
     */
    public static final int CONNECTION_USE_OFFSET_ARG = 11;
    /**
     * {@code renderConnection} 形参序号（0 基）：<b>摆动量（度）</b>，也就是必须加上本车滚转角的那个实参。
     * <p>
     * 计数依据（真实 4.0.5 字节码，且与源码形参表一致）：
     * {@code (0) shouldRender1, (1) shouldRender2, (2) canHaveLight, (3) previousConnectionPositions,
     * (4..9) innerSide/innerTop/innerBottom/outerSide/outerTop/outerBottom 六个纹理,
     * (10) positionAndRotation, (11) useOffset, (12) vehicleLength, (13) width, (14) height,
     * (15) yOffset, (16) zOffset, (17) oscillationAmount, (18) isOnRoute}。
     * {@code renderConnection} 自己的 {@code LocalVariableTable} 里槽位 22 的名字就是 {@code oscillationAmount}
     * （第 18 个形参，前面的 double 各占两个槽位：10→槽位 10，11→槽位 11，12→槽位 12-13 …… 17→槽位 22-23）。
     * <p>
     * {@code @ModifyArgs} 的 {@code Args} 索引用的是<b>形参序号</b>而不是槽位，所以这里是 17。
     */
    public static final int CONNECTION_OSCILLATION_ARG = 17;

    /**
     * 「乘车玩家站进车厢内部」那条方法的完整选择器：{@code VehicleRidingMovement.movePlayer}
     * 的 8 参重载。
     * <p>
     * <b>为什么是它</b>：4.0.5 里玩家在车厢内的世界位置完全由这个方法算出来 ——
     * {@code javap -p -c} 显示它的流程是
     * (1) 用 {@code transformBackwards} 把玩家的输入速度换算到车厢局部系；
     * (2) 用 {@code clampPosition} × 4（玩家碰撞盒的四个角）在
     *     {@code VehicleResourceCache.floors} / {@code doorways} 给出的
     *     <b>轴对齐 AABB</b>（{@code org.mtr.mapping.holder.Box}）里求出局部偏移
     *     {@code (ridingVehicleX, Y, Z)}，其中 {@code ridingVehicleY = max(box.maxY)}，
     *     即"脚下那块 AABB 的顶面"；
     * (3) 把该局部偏移经 {@code carPnR.transformForwards} 换算成世界坐标后
     *     {@code player.updatePosition(...)}。
     * <p>
     * 描述符已用 {@code javap -p} 对真实 4.0.5 jar 逐字核实。同名的
     * {@code movePlayer(DDD)} 是私有的「真正落地」重载，本选择器不会命中它
     * （描述符不同）。
     */
    public static final String RIDING_MOVE_TARGET =
            "movePlayer(JJILorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;"
                    + "Lorg/mtr/mod/client/GangwayMovementPositions;Lorg/mtr/mod/client/GangwayMovementPositions;"
                    + "Lorg/mtr/mod/client/GangwayMovementPositions;Lorg/mtr/mod/render/PositionAndRotation;)V";

    /**
     * {@link #RIDING_MOVE_TARGET} 里那一次「车厢局部 → 世界」变换的调用点选择器：
     * {@code PositionAndRotation.transformForwards(Object, Rotate, Rotate, Translate)}。
     * <p>
     * 该调用在 {@code movePlayer} 里<b>只出现一次</b>（偏移 1025；另外两处是偏移 165 / 561 的
     * {@code transformBackwards}，描述符不同名也不会命中）。它的实参来源已用
     * {@code javap -v} 的 {@code BootstrapMethods} 核实：
     * {@code Rotate} 两个依次是 {@code Vector3d.xRot}（pitch）与 {@code Vector3d.yRot}（yaw），
     * {@code Translate} 是 {@code Vector3d.add}；而 {@code transformForwards} 的字节码是
     * {@code t(r2(r1(v, pitch), yaw), pos)}，即
     * {@code W(v) = pos + Ry(yaw) * Rx(-pitch) * v}（注意 MC 的 {@code Vec3d.xRot(t) = Rx(-t)}）。
     */
    public static final String TRANSFORM_FORWARDS_DESCRIPTOR =
            "Lorg/mtr/mod/render/PositionAndRotation;transformForwards(Ljava/lang/Object;"
                    + "Lorg/mtr/mod/render/PositionAndRotation$Rotate;Lorg/mtr/mod/render/PositionAndRotation$Rotate;"
                    + "Lorg/mtr/mod/render/PositionAndRotation$Translate;)Ljava/lang/Object;";

    /**
     * 采样 / 排队线程上下文：当前正在渲染截面的那条轨道的滚转快照，仅在
     * {@code RenderRails.renderRailStandard} 的同步窗口内有效。
     * <p>
     * 单槽（非栈）是安全的：{@code renderRailStandard} 不会递归调用自己
     * （它同步调用 {@code renderWithinRenderDistance} → {@code railMath.render} → 排队 lambda，
     * 该链上没有任何一处会再次进入 {@code renderRailStandard}），每次调用都会<b>整体覆盖</b>槽位；
     * 即使 RETURN 钩子漏挂，残值也会被下一次调用覆盖，并且会被
     * {@link #wrapRailQuad(BiConsumer)} 的「是否在同步窗口内」判定挡住
     * （窗口判定已经由 {@code @At} 限定在轨面排队 lambda 内，不再需要纹理比对）。
     */
    private static final ThreadLocal<RollFrame> SECTION_FRAME = new ThreadLocal<>();

    /** 渲染线程上下文：当前正在绘制的、带滚转的轨道截面快照。 */
    private static final ThreadLocal<RollFrame> ACTIVE_FRAME = new ThreadLocal<>();

    /** 车体定位用的滚动轨道缓存（每帧在 {@code RenderVehicles.render} 开头重建）。 */
    private static volatile List<RolledRail> frameRolledRails = new ArrayList<>();

    /**
     * 「当前正在渲染的车厢」的世界 PnR（{@link #beginCarFrame}）。
     * <p>
     * 由 {@code RenderVehicles.lambda$render$14} 里 {@code getRenderPositionAndRotation} 调用的
     * {@code @Redirect} 在<b>每节车厢</b>渲染前原地刷新，因此车体（乘车分支）与风挡都能用它反查轨道。
     * 每帧在 {@link #beginVehicleFrame()} 里清空：若捕获钩子失效，槽位恒为 {@code null}，
     * 只会表现为「不倾斜」而不会把上一帧 / 上一节车厢的滚转角误加到本车上。
     */
    private static volatile PositionAndRotation carFramePositionAndRotation = null;
    /** {@link #carFramePositionAndRotation} 是否可用（非 null 且已算出滚转角）。 */
    private static volatile boolean carFrameValid = false;
    /**
     * 本车应当施加的滚转角（度），与车体 {@link #applyTrainRoll} 用的是同一个值。
     * <p>
     * 在 {@link #beginCarFrame(PositionAndRotation)} 里算一次并缓存
     * （= 两个转向架处各自匹配出的<b>世界帧</b>滚转角的平均，数据来自
     * {@link #captureBogieSource}），车体、风挡
     * （{@link #getConnectionRollDegrees}）与乘车玩家（{@link #getCurrentCarRollDegrees()}）
     * 都读它，从而「车体 / 风挡 / 玩家永不给出不同滚转角」（BUG 2 的硬性要求）。
     * <p>
     * <b>为什么可以在 {@code beginCarFrame} 里算</b>：转向架来源由 {@code @ModifyVariable} 在
     * {@code lambda$render$14} 的 <b>HEAD（偏移 0）</b>抓取，早于该 lambda 在偏移 64 调用的
     * {@code getRenderPositionAndRotation}（= 车体 PnR 捕获点），因此轮到
     * {@code beginCarFrame} 时转向架数据已在手。旧实现用的是「偏移 104 的 {@code @ModifyArgs}」，
     * 那时序更晚，所以只能拆成两个方法；换成 HEAD 之后不再需要这个拆分。
     */
    private static volatile double carFrameRollDegrees = 0.0D;

    /**
     * {@link #captureBogieSource} 抓到的<b>本车厢转向架 PnR 列表</b>，以及它所属车厢的
     * 世界 PnR（= 弦中点 PnR，用于<b>实例同一性</b>校验）。
     * <p>
     * 两者每次都整组覆盖，因此不存在「半个旧值」的中间态。{@link #beginCarFrame(PositionAndRotation)}
     * 只在 {@code pendingBogieCarPositionAndRotation == } 本次车体 PnR 时才用这份数据，否则退回
     * 「弦中点单点采样」—— 于是即使钩子时序在未来 MTR 版本里发生变化，也只会退化为修复前的行为，
     * 绝不会把别的车厢 / 上一帧的转向架角度加到本车上。
     * <p>
     * 每帧在 {@link #beginVehicleFrame()} 里清空，与 {@link #carFramePositionAndRotation} 同一套失效语义。
     */
    private static volatile List<?> pendingBogiePositions = null;
    /** {@link #pendingBogiePositions} 所属车厢的世界 PnR（实例同一性校验用，见上）。 */
    private static volatile PositionAndRotation pendingBogieCarPositionAndRotation = null;

    /**
     * 车体中心允许偏离轨道水平中心线的最大距离（米）。
     * <p>
     * MTR 车体的 {@code PositionAndRotation.position} 是「两转向架弦的中点」
     * （{@code PositionAndRotation(ObjectArrayList, VehicleCar, boolean)} 对两点取
     * {@code Vector.getAverage}，已用 {@code javap} 核实），弦中点到圆弧的矢高为
     * {@code L²/(8R)}（{@code L} = 转向架间距）。4.0.5 常见车型 {@code L = 12 m}
     * （{@code bogie1Position/bogie2Position = ∓6}），最长车型 {@code L = 16 m}
     * （{@code ±(length/2 − 4)}，24 m 车）。
     * <p>
     * 取 <b>1.5 m</b>：覆盖 {@code L = 16 m} 时 {@code R ≳ 21 m}、{@code L = 12 m} 时
     * {@code R ≳ 12 m} 的所有曲线（原来的 0.75 m 在 {@code R ≲ 43 m}（L=16）/
     * {@code R ≲ 24 m}（L=12）就失效，表现为「直线段有滚转、紧曲线中段突然变平」），
     * 同时对 MTR 双线的最小线间距（车辆宽 2 格 → 线间距至少 2 m）仍留 ≥ 0.5 m 余量，
     * 配合 {@link #VEHICLE_MATCH_PARALLEL} 的平行度门控，取最近者不会抓到并行邻线。
     * <p>
     * 残留边界：若邻线是<b>带滚转</b>的轨道、间距只有 2 格、且本车恰好位于自身曲线的弦上
     * （矢高朝邻线一侧），本车到邻线中心线的距离理论上可能小于 1.5 m 而被误判。这种几何下
     * 双线车辆本身已经互相侵入，属于可接受的理论边界。
     * <p>
     * <b>本次修复后</b>：车体滚转改为在<b>两个转向架处</b>各匹配一次（见
     * {@link #beginCarFrame(org.mtr.mod.render.PositionAndRotation)}）。转向架就在轨道中心线上（矢高 ≈ 0），所以 1.5 m 这道门
     * 对转向架采样几乎不会触发；它保留下来是为了（a）退化几何与亚格偏移的容差、
     * （b）{@link #rollDegreesAtPoint(PositionAndRotation)} 仍被转向架模型 / 玩家 / 电梯使用，
     * （c）拿不到转向架列表时的回退路径仍按修复前的弦中点采样。门控数值本身<b>刻意不变</b>，
     * 以免改变既有的「急弯中段不变平」（验收项 6.7）与「平行邻线不误伤」（6.8）行为。
     */
    private static final double VEHICLE_MATCH_HORIZONTAL = 1.5D;
    /**
     * 采样点与轨道中心线的最大高度差（米）。用于排除恰好从轨道上方/下方经过的几何。
     * <p>
     * 采到转向架时该值就是转向架中心与轨道中心线的高度差（≈ 0，只剩滚转抬升
     * {@code 半轨距·|sin roll|} 的十几厘米）；作为回退路径的弦中点采样时同样是十几厘米量级。
     */
    private static final double VEHICLE_MATCH_VERTICAL = 1.0D;
    /**
     * 车头方向与轨道切向的最小平行度 {@code |cos|}。0.9 ≈ 25.8°。
     * <p>
     * 这个门控是放宽水平距离之后防误匹配的关键：只有「车头与轨道切向大致平行」才认为车在该轨道上，
     * 于是<b>横穿的轨道</b>（切向与车头近似垂直，{@code |cos| ≈ 0}）会被排除。
     * 至于<b>并行的邻线</b>：车体中心到本线中心线的垂距就是矢高（通常 ≪ 1 m），
     * 到邻线的垂距等于线间距（MTR 双线至少 2 格 ≈ 2 m），取最近者必然选中本线。
     */
    private static final double VEHICLE_MATCH_PARALLEL = 0.9D;
    /** 求切线时沿参数方向取的前后采样步长（米）。 */
    private static final double TANGENT_SAMPLE = 0.5D;
    /** 判定「退化」（长度可忽略）的阈值。 */
    private static final double DEGENERATE = 1.0E-6D;

    // ==================== 钩子存活诊断 ====================
    // 全部 @Xxx 钩子都带 require = 0（解析失败不崩游戏），因此必须有一条可诊断的路径，
    // 否则「钩子没挂上」会表现为「功能静默失效」。这里用一次性 warn 报告，风格与
    // RailMixin 的 [RailPose] 日志一致。
    //
    // <b>D-C：诊断时钟必须由「不可能静默失效」的钩子驱动</b>。旧实现用
    // Math.max(railSectionSamples, frameSamples) 当采样计数，而这两个计数正好只由
    // 两条 require = 0 的钩子推进 —— 于是「所有滚转钩子都没挂上」时两个计数恒为 0，
    // 所有判定都在 < HOOK_PROBE_SAMPLES 处提前 return，日志一片空白（与「不会静默」的说法矛盾）。
    // 现在时钟改由 {@link #probeRenderFrame()} 推进：
    //   - 它挂在 RenderRails.render 的 @At("TAIL") @Inject 上（RenderRailsMixin
    //     fangsu$addMultiDirectionGhostRail），该钩子是本特性里<b>唯一 require = 1</b> 的
    //     （默认值即 1）注入点 —— 解析失败会在 Mixin APPLY 阶段直接报错而不是静默跳过；
    //   - RenderRails.render 是每帧的轨道渲染入口，只要玩家在世界里渲染轨道就会被调用，
    //     因此它是「本帧渲染确实在跑」的可靠证据，即使全部滚转钩子都失效也照常推进。
    // 「客户端是否存在带滚转的轨道」也不再依赖钩子：看门狗每 HOOK_PROBE_SAMPLES 帧独立扫一次
    // 客户端轨道缓存（{@link #hasRolledRailInClientData()}），因此钩子全灭时依然能报出来。

    private static volatile boolean railSectionHookAlive = false;
    private static volatile boolean endRailSectionHookAlive = false;
    private static volatile boolean scheduleHookAlive = false;
    private static volatile boolean drawQuadHookAlive = false;
    private static volatile boolean modelHookAlive = false;
    private static volatile boolean frameHookAlive = false;
    private static volatile boolean carFrameHookAlive = false;
    private static volatile boolean connectionHookAlive = false;
    /**
     * 「本车厢两个转向架 PnR 来源」钩子（{@code @ModifyVariable}，目标 {@link #BOGIE_FRAME_TARGET}）是否触发过。
     * <p>
     * 它失效<b>不会</b>让滚转消失，只会让车体退回「弦中点单点采样」（即修复前的滞后 / 跳变形态），
     * 所以它<b>不</b>参与 {@link #allRollHooksMissing()} 的判定（那条告警描述的是「滚转全灭」），
     * 而是像 {@link #connectionHookAlive} 一样用「前置条件 + 自己的告警」表达。
     */
    private static volatile boolean bogieFrameHookAlive = false;
    /**
     * 「乘车玩家位置随车体滚转」钩子（{@code VehicleRidingMovementMixin}）是否触发过。
     * <p>
     * 它<b>不</b>参与 {@link #allRollHooksMissing()}：它不产生滚转角，只是
     * {@link #currentCarRollDegrees()} 的下游消费者，因此它失效不能作为「滚转数据源全灭」的证据。
     * 判定用它自己的前置条件（{@link #ridingBodyRollSamples} &gt; 0，即乘车分支确实走过）。
     */
    private static volatile boolean ridingPositionHookAlive = false;
    /**
     * {@link #applyTrainRoll} 走「乘车」分支（{@code useOffset == false}）的次数。
     * <p>
     * 这是 {@link #ridingPositionHookAlive} 诊断的<b>前置条件</b>：只有确实以乘车状态渲染过车体，
     * 「乘车玩家位置钩子没触发」才是异常；否则（玩家从不乘车）会误报。
     */
    private static volatile int ridingBodyRollSamples = 0;
    private static volatile int railSectionSamples = 0;
    private static volatile int rolledSectionSamples = 0;
    private static volatile int rollFrameCount = 0;
    private static volatile int modelSamples = 0;
    private static volatile int modelRollCount = 0;
    private static volatile int frameSamples = 0;
    private static volatile int carFrameSamples = 0;
    private static volatile int connectionSamples = 0;
    /** {@link #captureBogieSource} 的触发次数（= 抓到转向架来源的车厢数）。 */
    private static volatile int bogieFrameSamples = 0;
    /** 看门狗帧计数（{@link #probeRenderFrame()}）—— 唯一不依赖任何 require = 0 钩子的时钟。 */
    private static volatile int renderFrameSamples = 0;
    /**
     * 本进程是否已确认「客户端轨道缓存里确实存在带滚转的轨道」。
     * <p>
     * 来源有三：捕获钩子拿到滚转轨道、车体帧缓存收集到滚转轨道、以及看门狗自己扫客户端缓存。
     * 前两者依赖 require = 0 的钩子，后者独立，因此钩子全灭时仍然成立。
     */
    private static volatile boolean rolledRailEvidence = false;
    /** 采样到这么多次（帧 / 轨道截面）后钩子仍未触发，才认为是真的没挂上（避免首帧误报）。 */
    private static final int HOOK_PROBE_SAMPLES = 120;
    private static boolean warnedRailSectionMissing = false;
    private static boolean warnedEndRailSectionMissing = false;
    private static boolean warnedScheduleMissing = false;
    private static boolean warnedDrawQuadMissing = false;
    private static boolean warnedModelMissing = false;
    private static boolean warnedFrameMissing = false;
    private static boolean warnedCarFrameMissing = false;
    private static boolean warnedConnectionMissing = false;
    private static boolean warnedBogieFrameMissing = false;
    private static boolean warnedRidingPositionMissing = false;
    private static boolean warnedAllHooksMissing = false;

    private RailRollRenderHelper() {
    }

    // ==================== P4b-1：轨道截面滚转 ====================
    //
    // 「当前轨道截面」同步窗口由三个钩子共同维护，全部挂在
    //   RenderRails.renderRailStandard(ClientWorld, Rail, float, RenderState, float, Identifier, float×4)
    // 上（该 10 参数重载才是轨面入口，4 参数重载只是转发；箭头 / 信号不会进入它 ——
    // 这正是 D1 修复的关键）：
    //   - captureSectionRail(Rail)  —— @ModifyVariable(argsOnly, index = 1) 取轨道（D8 修复）；
    //   - endRailSection()          —— RETURN 注入，清除槽位。
    // 3D 模型路径（FIX 4 / D7）复用同一个快照，不再需要额外的捕获钩子。

    /**
     * {@code @ModifyVariable(argsOnly, index = 1)} 捕获 {@code renderRailStandard} 的轨道参数。
     * <p>
     * <b>index 必须是 1</b>：该方法是 static（参数序号 == 局部变量槽位），
     * 真实 4.0.5 的 {@code LocalVariableTable} 是
     * {@code 0=clientWorld, 1=rail, 2=yOffset(F), 3=renderState, 4=railWidth(F),
     * 5=defaultTexture(Identifier), 6..9=u1,v1,u2,v2}。
     * 写成 index = 0 会落在 {@code ClientWorld} 上、与 {@code Rail} 类型的处理器不匹配，
     * 注入点永不触发（D8：Symptom 1 的根因）。
     * <p>
     * 处理器只把原值原样返回（{@code argsOnly} 语义上就是「可能改写入参」，这里刻意不改）。
     */
    public static Rail captureSectionRail(Rail rail) {
        railSectionHookAlive = true;
        railSectionSamples++;
        final RollFrame frame = frameFor(rail);
        if (frame == null) {
            SECTION_FRAME.remove();
        } else {
            SECTION_FRAME.set(frame);
            rolledSectionSamples++;
            rolledRailEvidence = true;
        }
        reportMissingHooks();
        return rail;
    }

    /**
     * 同一方法的 RETURN 注入：结束同步窗口并<b>清除</b>槽位（D5：旧实现从不清理 ThreadLocal，
     * 既长期持有 {@link Rail}，也是 D1 误伤箭头 / 信号的根因）。
     * <p>
     * <b>D-C：本方法也有存活探针</b>。它一旦漏挂并不影响画面（{@code SECTION_FRAME} 是
     * 「每次捕获整体覆盖」的单槽，且轨面排队 lambda 有方法级作用域），所以问题只会表现为
     * 「清除得不及时」；但它是 {@code renderRailStandard} 的 RETURN 注入是否解析成功的唯一证据，
     * {@link #reportMissingHooks()} 会在捕获钩子活着而本钩子从未触发时一次性报出来。
     */
    public static void endRailSection() {
        endRailSectionHookAlive = true;
        SECTION_FRAME.remove();
    }

    /**
     * 诊断看门狗（D-C）：由 {@code RenderRails.render} 的 {@code @At("TAIL")} 注入每帧调用。
     * <p>
     * <b>为什么它是可靠的时钟</b>：那个注入点（{@code RenderRailsMixin#fangsu$addMultiDirectionGhostRail}）
     * 是本特性里唯一<b>没有</b> {@code require = 0} 的钩子（{@code @Inject} 的默认 require 为 1），
     * 解析失败会在 Mixin APPLY 阶段直接抛错，不可能静默失效。而 4.0.5 的
     * {@code MainRenderer.render} 每帧无条件依次调用 {@code RenderVehicles.render}、
     * {@code RenderLifts.render} 与 {@code RenderRails.render}（源码
     * {@code MainRenderer.java:112-114}，字节码同序），所以只要玩家在世界里，
     * 本方法的计数就一定推进 —— 「全部滚转钩子失效」时诊断不会再因为计数停摆而失效。
     * <p>
     * 同一条证据也说明 {@link #allRollHooksMissing()} 不会因为「玩家看向天空」而误报：
     * {@code RenderVehicles.render} 同样每帧被调用，它的 HEAD 钩子只要挂上就必然触发，
     * 因此「8 个滚转钩子全灭」只可能是钩子（或整个 RenderVehiclesMixin）没应用，
     * 而不是「恰好没渲染到带滚转的轨道」。
     * <p>
     * 每 {@link #HOOK_PROBE_SAMPLES} 帧独立扫一次客户端轨道缓存（与任何钩子无关），
     * 为「客户端确实存在带滚转的轨道」提供与钩子无关的证据 —— 这正是旧实现缺的那一半。
     */
    public static void probeRenderFrame() {
        final int samples = ++renderFrameSamples;
        if (samples % HOOK_PROBE_SAMPLES == 0 && !rolledRailEvidence && hasRolledRailInClientData()) {
            rolledRailEvidence = true;
        }
        reportMissingHooks();
    }

    /**
     * 与钩子无关的「客户端轨道缓存里是否存在带滚转轨道」探测（D-C 看门狗的证据来源）。
     * 判据与 {@link #beginVehicleFrame()} 收集缓存时用的完全一致。
     */
    private static boolean hasRolledRailInClientData() {
        final MinecraftClientData clientData = MinecraftClientData.getInstance();
        if (clientData == null) {
            return false;
        }
        for (final MinecraftClientData.RailWrapper wrapper : clientData.railWrapperList.values()) {
            if (wrapper == null) {
                continue;
            }
            final Rail rail = wrapper.getRail();
            if (rail == null) {
                continue;
            }
            final RailMath railMath = rail.railMath;
            if (railMath instanceof FangSuRailMath
                    && ((FangSuRailMath) railMath).getFangSuPose().hasRoll()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 包装 {@code MainRenderer.scheduleRender} 的延迟绘制消费者。
     * <p>
     * <b>安全不变式</b>：以下任一情况都<b>原样返回同一个实例</b>（排队、执行、纹理、光照
     * 全部与 MTR 原生逐位一致）：
     * <ul>
     *   <li>不在 {@code renderRailStandard} 的同步窗口内（普通 MTR 轨道、信号、单行箭头、
     *       ghost 预览、其它任何 4 参数排队）；</li>
     *   <li>轨道没有滚转（{@code railMath} 不是 {@link FangSuRailMath}，或姿态
     *       {@code hasRoll()} 为 false）。</li>
     * </ul>
     * <p>
     * <b>不再比对纹理</b>（本次修复删除的 {@code SECTION_TEXTURE} 门控）：本方法只被
     * {@code lambda$renderRailStandard$19} 里那一次 {@code scheduleRender} 调用（
     * {@link #RAIL_QUAD_SCHEDULE_TARGET}）调用，而该 lambda <b>只</b>由 10 参数
     * {@code renderRailStandard} 的 2D 轨面分支创建（已用 javap 核实：{@code InvokeDynamic #8}
     * 就在 10 参数方法体内，信号 / 单行箭头各自用自己的 lambda）。
     * 因此「方法级作用域」已经等价于原来的纹理门控，而少一个 ThreadLocal 槽位与一个
     * {@code @ModifyVariable} 钩子就少一处静默失效点（纹理钩子一旦没挂上，旧实现会把<b>所有</b>
     * 滚转都挡掉）。
     */
    public static BiConsumer<GraphicsHolder, Vector3d> wrapRailQuad(
            BiConsumer<GraphicsHolder, Vector3d> original
    ) {
        scheduleHookAlive = true;
        final RollFrame frame = SECTION_FRAME.get();
        if (frame == null) {
            return original;
        }
        rollFrameCount++;
        return wrapFrame(frame, original);
    }

    /** 把快照挂到渲染线程上下文上再执行原消费者（异常时也必须还原）。 */
    private static BiConsumer<GraphicsHolder, Vector3d> wrapFrame(
            RollFrame frame,
            BiConsumer<GraphicsHolder, Vector3d> original
    ) {
        return (graphicsHolder, offset) -> {
            final RollFrame previous = ACTIVE_FRAME.get();
            ACTIVE_FRAME.set(frame);
            try {
                original.accept(graphicsHolder, offset);
            } finally {
                if (previous == null) {
                    ACTIVE_FRAME.remove();
                } else {
                    ACTIVE_FRAME.set(previous);
                }
            }
        };
    }

    /**
     * 真正的四边形绘制（{@code IDrawing.drawTexture} 的 12-double 重定向）。
     * <p>
     * 4.0.5 的调用点（{@code lambda$renderRailStandard$18}）两次调用同一个四边形、绕序相反
     * （正反两面）：
     * <pre>
     * 第一次：(x1,y1,z1) (x2,y2,z2) (x3,y3,z3) (x4,y4,z4)
     * 第二次：(x2,y1,z2) (x1,y1,z1) (x4,y2,z4) (x3,y2,z3)   // 同一四边形、反向绕序
     * </pre>
     * 其中 {@code (x1,z1)/(x4,z4)} 是 {@code offsetRadius1 = -railWidth} 的角点、
     * {@code (x2,z2)/(x3,z3)} 是 {@code offsetRadius2 = +railWidth} 的角点，
     * 依次对应 {@code 上一截面·半径1 → 上一截面·半径2 → 当前截面·半径2 → 当前截面·半径1}。
     * 旋转是刚体变换，<b>保持原有点顺序即可保持绕序</b>，因此这里<b>不重排 12 个 double</b>，
     * 也不改 u/v、light、color、{@code Direction}、offset。
     */
    public static void drawRolledQuad(
            GraphicsHolder graphicsHolder,
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            double x3, double y3, double z3,
            double x4, double y4, double z4,
            Vector3d offset, float u1, float v1, float u2, float v2,
            Direction facing, int light, int color
    ) {
        drawQuadHookAlive = true;
        final RollFrame frame = ACTIVE_FRAME.get();
        final double[] rotated = frame == null ? null : buildRotatedQuad(frame, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4);
        if (rotated == null) {
            // 无滚转（含所有原版轨道、信号、箭头、ghost 预览）→ 12 个 double 原封不动转发
            IDrawing.drawTexture(
                    graphicsHolder,
                    x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4,
                    offset, u1, v1, u2, v2, facing, light, color
            );
            return;
        }
        IDrawing.drawTexture(
                graphicsHolder,
                rotated[0], rotated[1], rotated[2],
                rotated[3], rotated[4], rotated[5],
                rotated[6], rotated[7], rotated[8],
                rotated[9], rotated[10], rotated[11],
                offset, u1, v1, u2, v2, facing, light, color
        );
    }

    /**
     * 把四边形绕轨道局部前进轴旋转局部滚转角。
     *
     * @return 旋转后的 12 个 double（x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4）；
     *         不需要旋转（无滚转、或几何退化无法确定轴）时返回 {@code null}，调用方原样转发。
     *         <p>
     *         <b>D-B：退化与 NaN 一律按「不旋转」处理</b>。零长度轨道的 {@code parameterAt} 返回
     *         {@code NaN}，而 {@code Math.max/min} 会传播 NaN，于是 {@code hi - lo < DEGENERATE}
     *         为 false、{@code axisLength < DEGENERATE} 也为 false（NaN 的一切比较都是 false），
     *         NaN 就穿透了原来的退化守卫并写进旋转矩阵。现在每个可能为 NaN 的量都先过
     *         {@link Double#isFinite}：
     *         <ul>
     *           <li>参数 / 滚转角不有限 → 无法确定旋转角 → 原样转发；</li>
     *           <li>四边形自身轴长不有限（角点本身是 NaN）→ 无法修复 → 原样转发；
     *               <b>有限但过小</b> → 仍走原来的「退回内核切线」分支（行为不变）；</li>
     *           <li>枢轴点（该参数处的轨道中心线）或最终旋转角不有限 → 原样转发。</li>
     *         </ul>
     *         这些检查只在退化 / 非有限输入时短路，正常滚转轨道的分支与数值逐位不变。
     */
    private static double[] buildRotatedQuad(
            RollFrame frame,
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            double x3, double y3, double z3,
            double x4, double y4, double z4
    ) {
        // 四边形中心落在轨道中心线上 → 用闭式反解拿到局部参数，再取局部滚转角
        final double centerX = (x1 + x2 + x3 + x4) * 0.25D;
        final double centerZ = (z1 + z2 + z3 + z4) * 0.25D;
        final double parameter = frame.core.parameterAt(centerX, centerZ);
        if (!Double.isFinite(parameter)) {
            // 退化轨道（长度 0）反解不出参数 → 拿不到滚转剖面位置，直接原样转发
            return null;
        }
        final double roll = frame.core.getRollRadians(parameter);
        if (!Double.isFinite(roll) || roll == 0.0D) {
            return null;
        }

        // 局部前进轴：优先用四边形自身的「上一截面中点 → 当前截面中点」，最贴合局部几何
        double axisX = (x3 + x4) * 0.5D - (x1 + x2) * 0.5D;
        double axisZ = (z3 + z4) * 0.5D - (z1 + z2) * 0.5D;
        double axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);
        if (!Double.isFinite(axisLength)) {
            // 角点本身含 NaN（例如内核在退化几何上给出非有限坐标）→ 无法归一化，原样转发
            return null;
        }
        if (axisLength < DEGENERATE) {
            // 退化（例如极短轨道的末端采样）→ 退回内核切线
            final double lo = Math.max(0.0D, parameter - TANGENT_SAMPLE);
            final double hi = Math.min(frame.core.getLength(), parameter + TANGENT_SAMPLE);
            if (!Double.isFinite(hi - lo) || hi - lo < DEGENERATE) {
                return null;
            }
            final double[] ahead = frame.core.getPosition(hi, false);
            final double[] behind = frame.core.getPosition(lo, false);
            axisX = ahead[0] - behind[0];
            axisZ = ahead[2] - behind[2];
            axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);
            if (!Double.isFinite(axisLength) || axisLength < DEGENERATE) {
                return null;
            }
        }
        axisX /= axisLength;
        axisZ /= axisLength;

        // 旋转轴心取「该参数处的轨道中心线点」（已含滚转抬升），这样四角绕真正的轨轴旋转，
        // 横向偏移 rho 的角点正好获得 rho·sin(angle) 的抬升，与内核的中心线抬升语义一致。
        final double[] centerline = frame.core.getPosition(parameter, false);
        final double pivotX = centerline[0];
        final double pivotY = centerline[1];
        final double pivotZ = centerline[2];
        if (!Double.isFinite(pivotX) || !Double.isFinite(pivotY) || !Double.isFinite(pivotZ)) {
            return null;
        }

        // 符号：见 railFrameAngle（参数方向与 position1→position2 同向时 angle = -roll，否则 +roll）
        final double angle = railFrameAngle(frame, parameter);
        if (!Double.isFinite(angle)) {
            return null;
        }
        final double cos = Math.cos(angle);
        final double sin = Math.sin(angle);

        final double[] rotated = new double[12];
        rotateAboutAxis(rotated, 0, x1, y1, z1, pivotX, pivotY, pivotZ, axisX, axisZ, cos, sin);
        rotateAboutAxis(rotated, 3, x2, y2, z2, pivotX, pivotY, pivotZ, axisX, axisZ, cos, sin);
        rotateAboutAxis(rotated, 6, x3, y3, z3, pivotX, pivotY, pivotZ, axisX, axisZ, cos, sin);
        rotateAboutAxis(rotated, 9, x4, y4, z4, pivotX, pivotY, pivotZ, axisX, axisZ, cos, sin);
        return rotated;
    }

    /**
     * 绕经过 {@code (cx, cy, cz)}、方向为世界水平单位向量 {@code (ax, 0, az)} 的轴做
     * 右手旋转（Rodrigues 公式），结果写入 {@code out[index..index+2]}。
     */
    private static void rotateAboutAxis(
            double[] out, int index,
            double px, double py, double pz,
            double cx, double cy, double cz,
            double ax, double az, double cos, double sin
    ) {
        final double vx = px - cx;
        final double vy = py - cy;
        final double vz = pz - cz;
        // a = (ax, 0, az)
        final double crossX = -az * vy;
        final double crossY = az * vx - ax * vz;
        final double crossZ = ax * vy;
        final double factor = (ax * vx + az * vz) * (1.0D - cos);
        out[index] = cx + vx * cos + crossX * sin + ax * factor;
        out[index + 1] = cy + vy * cos + crossY * sin;
        out[index + 2] = cz + vz * cos + crossZ * sin + az * factor;
    }

    /**
     * 轨参数系下的滚转角（弧度）—— 全类<b>唯一</b>的滚转符号表达式：
     * <pre>
     * angle = -roll * (参数方向 == position1→position2 ? +1 : -1)
     * </pre>
     * 含义：绕<b>参数方向</b>（= 绘制方向，即四边形自身的「上一截面中点 → 当前截面中点」）
     * 的世界水平轴右手旋转 {@code angle}，抬升 {@code position1→position2 的右手侧}。
     * 推导与独立复核见类注释；轨面（{@link #buildRotatedQuad}）与 3D 模型
     * （{@link #appendRailModelRoll}）都调本方法，因此二者按构造保持同步。
     */
    private static double railFrameAngle(RollFrame frame, double parameter) {
        final double roll = frame.core.getRollRadians(parameter);
        return -roll * (frame.parameterIsPosition1ToPosition2 ? 1.0D : -1.0D);
    }

    /**
     * 轨道在参数处的<b>世界水平单位切向</b>（沿参数增大方向，= 绘制方向），
     * 前后各取 {@link #TANGENT_SAMPLE} 米做差分；区间退化时返回 {@code {0, 0}}
     * （调用方据此判定「匹配不上 / 不旋转」，不会误倾斜）。
     * <p>
     * <b>D-B</b>：非有限结果（参数为 {@code NaN} 时 {@code Math.max/min} 会把 {@code lo/hi}
     * 一起传播成 {@code NaN}，差分也因此是 {@code NaN}）同样归一成 {@code {0, 0}} ——
     * 否则 {@code Math.abs(NaN) < 0.9} 这类比较恒为 false，NaN 会被当成「平行 / 有效」放进旋转。
     */
    private static double[] parameterTangent(RailGeometryCore core, double parameter) {
        final double lo = Math.max(0.0D, parameter - TANGENT_SAMPLE);
        final double hi = Math.min(core.getLength(), parameter + TANGENT_SAMPLE);
        if (!Double.isFinite(hi - lo) || hi - lo < DEGENERATE) {
            return new double[]{0.0D, 0.0D};
        }
        final double[] ahead = core.getPosition(hi, false);
        final double[] behind = core.getPosition(lo, false);
        final double tangentX = ahead[0] - behind[0];
        final double tangentZ = ahead[2] - behind[2];
        final double length = Math.sqrt(tangentX * tangentX + tangentZ * tangentZ);
        if (!Double.isFinite(length) || length < DEGENERATE) {
            return new double[]{0.0D, 0.0D};
        }
        return new double[]{tangentX / length, tangentZ / length};
    }

    // ==================== P4b-3（FIX 4 / D7）：3D 轨道模型滚转 ====================

    /**
     * 给 3D 轨道模型的 {@link StoredMatrixTransformations} 追加滚转（FIX 4 / D7）。
     * <p>
     * <b>为什么需要</b>：{@code defaultRail3D} 默认 true（本工作区 dev config 也是 true），
     * 此时 TRAIN 轨的 {@code "default"} 样式被改写成 {@code "default_3d"}，玩家不手持轨道物品时
     * 程序化轨面（12-double {@code drawTexture} 路径）整个不执行，只剩这条 3D 模型路径 ——
     * 它原本没有任何倾斜路径，于是「抬高了但没倾斜」。
     * <p>
     * <b>枢轴</b>：{@code StoredMatrixTransformations.transform} 先 {@code translate} 到构造参数
     * {@code ((x1+x3)/2, (y1+y2)/2 + modelYOffset, (z1+z3)/2)}（{@code y1/y2} 是内核已经抬升过的
     * 中心线高度），然后才按顺序执行追加的消费者。本方法追加的消费者因此天然绕<b>抬升后的中心线</b>
     * 旋转 —— 与轨面、车体同枢轴；<b>严禁</b>再补任何 Y 平移（会把抬升翻倍）。
     * <p>
     * <b>为什么不是直接 {@code rotateZDegrees}</b>：本钩子挂在
     * {@code StoredMatrixTransformations} 的构造器上，而 MTR 的偏航 / 俯仰 / 摆动消费者是在
     * <b>构造之后</b>（真实字节码 111–127）才 {@code add} 进同一个对象的，所以本消费者在列表里
     * 排在最前 = 在世界系里<b>先</b>执行。此时模型还没被偏航，局部 Z 毫无意义，
     * {@code rotateZDegrees} 会变成绕世界 Z 轴（对东西向轨道就是「俯仰」而不是「滚转」）。
     * 因此这里显式构造「绕世界水平轴 {@code (cosφ, 0, sinφ)} 右手旋转 {@code angle}」：
     * <pre>
     * R_axis(angle) = R_y(-φ) · R_x(angle) · R_y(φ),   φ = 该处轨道切向角
     * </pre>
     * 三个调用按顺序右乘，合成正好是上面的共轭形式。对一个已经被偏航 / 俯仰定向过的模型来说，
     * 这等价于「在该模型局部的前进轴上滚转」：局部 {@code +Z} 与 {@code T} 反向时
     * （MTR 默认 3D 样式）等价于 {@code rotateZDegrees(-angle)}，与镜像样式
     * {@code _2}（局部 {@code +Z} 与 {@code T} 同向）则等价于 {@code rotateZDegrees(+angle)} ——
     * 用世界轴表述后<b>两种样式自动都对</b>，不需要读 {@code flip} 标志。
     * <p>
     * <b>与轨面严格同一表达式</b>：{@code angle} 就是 {@link #railFrameAngle} 的返回值，
     * 旋转轴就是轨面用的那条水平前进轴，所以 3D 模型与程序化轨面按构造同向、同角度。
     * <p>
     * <b>无操作保证</b>：没有活动截面快照（普通 MTR 轨道、其它任何 {@code StoredMatrixTransformations}
     * 构造点 —— 本方法只由 {@link #RAIL_MODEL_MATRIX_TARGET} 那一个 {@code @At("NEW")} 调用）、
     * 滚转为 0、或几何退化时<b>不追加任何消费者</b>，对象的消费者列表与 MTR 原生逐位一致。
     * <p>
     * <b>D-B：退化几何必须一个消费者都不追加</b>。零长度轨道的 {@code parameterAt} 返回 {@code NaN}，
     * 而 {@code fractionAt} 对零长度返回 0，于是<b>滚转角是有限非零的</b>（守住原判据），
     * 切线却是 {@code {NaN, NaN}} —— 原来只测 {@code x == 0 && z == 0}（NaN 的比较恒为 false），
     * NaN 因而穿透并写进 {@code rotateYRadians/rotateXRadians}，把模型矩阵整体污染成 NaN。
     * 现在参数与切向都先过 {@link Double#isFinite}，退化轨道返回前不追加任何消费者。
     *
     * @param x 模型中心的世界 X（构造参数原样传入，用于反解轨道参数）
     * @param y 模型中心的世界 Y（= 抬升后的中心线 + modelYOffset；枢轴由构造器承担，这里只作说明）
     * @param z 模型中心的世界 Z
     */
    public static void appendRailModelRoll(StoredMatrixTransformations storedMatrixTransformations, double x, double y, double z) {
        modelHookAlive = true;
        modelSamples++;
        if (storedMatrixTransformations == null) {
            return;
        }
        final RollFrame frame = SECTION_FRAME.get();
        if (frame == null) {
            return;
        }
        final double parameter = frame.core.parameterAt(x, z);
        if (!Double.isFinite(parameter)) {
            // 退化轨道（长度 0）反解不出参数 → 不追加任何消费者（D-B）
            return;
        }
        final double angle = railFrameAngle(frame, parameter);
        if (angle == 0.0D || !Double.isFinite(angle)) {
            return;
        }
        final double[] tangent = parameterTangent(frame.core, parameter);
        if (!Double.isFinite(tangent[0]) || !Double.isFinite(tangent[1])
                || (tangent[0] == 0.0D && tangent[1] == 0.0D)) {
            return;
        }
        final double axis = Math.atan2(tangent[1], tangent[0]);
        storedMatrixTransformations.add(graphicsHolder -> {
            graphicsHolder.rotateYRadians((float) -axis);
            graphicsHolder.rotateXRadians((float) angle);
            graphicsHolder.rotateYRadians((float) axis);
        });
        modelRollCount++;
        reportMissingHooks();
    }

    /** 由轨道构造滚转快照；没有滚转（或不是 FangSu 几何）时返回 {@code null}。 */
    private static RollFrame frameFor(Rail rail) {
        if (rail == null) {
            return null;
        }
        final RailMath railMath = rail.railMath;
        if (!(railMath instanceof FangSuRailMath)) {
            return null;
        }
        final FangSuRailMath fangSuRailMath = (FangSuRailMath) railMath;
        if (!fangSuRailMath.getFangSuPose().hasRoll()) {
            return null;
        }
        return new RollFrame(fangSuRailMath.getGeometryCore(), parameterIsPosition1ToPosition2(rail));
    }

    /**
     * {@code railMath} 的参数方向是否就是 {@code Rail.position1 → Rail.position2}。
     * MTR 的用例是 {@code reversePositions = position1.compareTo(position2) > 0}，
     * 为真时构造器用 {@code (position2, position1)} 建几何（见 {@code RailMixin}）。
     */
    private static boolean parameterIsPosition1ToPosition2(Rail rail) {
        final RailPoseExtraHolder holder = (RailPoseExtraHolder) (Object) rail;
        return holder.fangsu$getPosition1().compareTo(holder.fangsu$getPosition2()) <= 0;
    }

    // ==================== P4b-2：车体随轨滚转 ====================

    /**
     * 每帧重建「带滚转的轨道」缓存。由 {@code RenderVehicles.render} 头部调用。
     * <p>
     * 车体定位刻意<b>不</b>去复刻 MTR 的 {@code railProgress} / {@code reversed} 内部状态
     * （那些字段是私有的，且随版本变化）：车体的 {@code PositionAndRotation.position} 本来就
     * 是由轨道采样点平均出来的、落在轨道中心线上的点，直接用它反查最近的带滚转轨道即可。
     * 只有带滚转的轨道参与匹配，且要求
     * <ul>
     *   <li>高差 {@code < 1.0 m}；</li>
     *   <li>水平距离 {@code < 1.5 m}（要容纳紧曲线上的「转向架弦中点」矢高，见
     *       {@link #VEHICLE_MATCH_HORIZONTAL}）；</li>
     *   <li>车头方向与轨道切向 {@code |cos| >= 0.9}（排除横穿的轨道，见
     *       {@link #VEHICLE_MATCH_PARALLEL}）。</li>
     * </ul>
     * 因此不会误伤旁边的普通轨道或并行邻线。
     */
    public static void beginVehicleFrame() {
        frameHookAlive = true;
        // 「本车世界 PnR」也按帧清空：捕获钩子若失效，乘车分支只会退化为「不倾斜」，
        // 而不会沿用上一帧记下的车厢把滚转角误加到别的车上。
        carFramePositionAndRotation = null;
        carFrameValid = false;
        carFrameRollDegrees = 0.0D;
        pendingBogiePositions = null;
        pendingBogieCarPositionAndRotation = null;
        final MinecraftClientData clientData = MinecraftClientData.getInstance();
        final List<RolledRail> collected = new ArrayList<>();
        if (clientData != null) {
            for (final MinecraftClientData.RailWrapper wrapper : clientData.railWrapperList.values()) {
                if (wrapper == null) {
                    continue;
                }
                final Rail rail = wrapper.getRail();
                if (rail == null) {
                    continue;
                }
                final RailMath railMath = rail.railMath;
                if (!(railMath instanceof FangSuRailMath)) {
                    continue;
                }
                final FangSuRailMath fangSuRailMath = (FangSuRailMath) railMath;
                if (!fangSuRailMath.getFangSuPose().hasRoll()) {
                    continue;
                }
                collected.add(new RolledRail(
                        fangSuRailMath.getGeometryCore(),
                        parameterIsPosition1ToPosition2(rail)
                ));
            }
        }
        frameRolledRails = collected;
        if (!collected.isEmpty()) {
            // D-C：本帧收集到带滚转的轨道 → 为诊断提供「客户端确实存在带滚转轨道」的证据
            rolledRailEvidence = true;
        }
        frameSamples++;
        reportMissingHooks();
    }

    /**
     * 一次性诊断：任一钩子没挂上时给出可定位的日志（不刷屏）。
     * <p>
     * <b>D-C 的三处修改</b>：
     * <ol>
     *   <li>时钟改用 {@link #renderFrameSamples}（由 {@link #probeRenderFrame()} 推进，
     *       该钩子 require = 1、不可能静默失效），不再用「只由 require = 0 钩子推进」的旧计数 ——
     *       这是「全部钩子失效时一片空白」的根因；</li>
     *   <li>「客户端存在带滚转的轨道」这一前提可以来自与钩子无关的客户端缓存扫描
     *       （{@link #probeRenderFrame()} 里置位的 {@link #rolledRailEvidence}），
     *       因此钩子全灭时依然能判定；</li>
     *   <li>全部滚转钩子都失效时只发<b>一条</b>明确的总告警（逐钩子的告警在这个形态下必然
     *       是 7~8 条噪声，没有诊断价值）；另外 schedule 钩子的判据收窄为「确实走过程序化轨面路径」，
     *       不再在正常的 3D 轨道场景误报。</li>
     * </ol>
     * 可从 RenderRails 侧（截面捕获 / 3D 模型 / 看门狗）与 RenderVehicles 侧（车体帧缓存）调用：
     * 只要有一侧的钩子还活着，另一侧的静默失效就能被报告。
     */
    private static void reportMissingHooks() {
        // 时钟：看门狗优先（它永远在跑），其它计数只作为更早进入诊断的补充
        final int clock = Math.max(renderFrameSamples, Math.max(railSectionSamples, frameSamples));
        if (clock < HOOK_PROBE_SAMPLES) {
            return;
        }
        if (!rolledRailEvidence && rolledSectionSamples == 0 && frameRolledRails.isEmpty()) {
            return;
        }
        // 「所有滚转钩子都没触发」是本特性最严重的静默失效形态：一条总告警 + 立即返回，
        // 不再逐条重复「某钩子未触发」（那时其它 7 条必然同时成立，只会淹没日志）。
        if (allRollHooksMissing()) {
            if (!warnedAllHooksMissing) {
                warnedAllHooksMissing = true;
                Main.LOGGER.warn("[RailRoll] 全部滚转钩子都未触发（看门狗已渲染 " + renderFrameSamples
                        + " 帧，且客户端轨道缓存里确实存在带滚转的轨道）：P4b-1 截面滚转、P4b-2 车体滚转"
                        + "与风挡滚转全部静默失效。请用 javap 复核 RenderRails / RenderVehicles 的方法名与"
                        + "描述符（RAIL_QUAD_SCHEDULE_TARGET / RAIL_QUAD_DRAW_TARGET /"
                        + " RAIL_MODEL_MATRIX_TARGET / CAR_FRAME_TARGET / CONNECTION_CALLER_TARGET /"
                        + " RENDER_RAIL_STANDARD_DESCRIPTOR，以及各个 @At target），并确认两个 mixin"
                        + "仍在 fangsu.mixins.json 的 client 数组里");
            }
            return;
        }
        if (!railSectionHookAlive && !warnedRailSectionMissing) {
            warnedRailSectionMissing = true;
            Main.LOGGER.warn("[RailRoll] 客户端存在带滚转的轨道，但 renderRailStandard 的轨道截面捕获钩子持续 "
                    + clock + " 次采样未触发；P4b-1 未生效"
                    + "（检查 @ModifyVariable 的 index：Rail 在槽位 1，写成 0 会落在 ClientWorld 上而不触发）");
        }
        // D-C：endRailSection 的存活探针。它漏挂本身无害（单槽会被整体覆盖，且轨面排队 lambda
        // 有方法级作用域），但它是 renderRailStandard 的 RETURN 注入解析成功的唯一证据。
        if (railSectionHookAlive && !endRailSectionHookAlive && !warnedEndRailSectionMissing) {
            warnedEndRailSectionMissing = true;
            Main.LOGGER.warn("[RailRoll] renderRailStandard 的轨道截面捕获钩子已触发 " + railSectionSamples
                    + " 次，但同一方法 RETURN 上的 endRailSection 钩子从未触发；SECTION_FRAME 不会被及时清除"
                    + "（无害，但说明该 RETURN 注入未解析成功：请用 javap 复核 RENDER_RAIL_STANDARD_DESCRIPTOR）");
        }
        // D-C：schedule 钩子只在「确实走过程序化轨面路径」时才有理由要求它触发。
        // drawTexture 的 12-double 重定向在包装器<b>下游</b>：它触发了就证明轨面路径确实执行过，
        // 而包装器没触发 —— 这才是真正的「P4b-1 未生效」。正常配置（defaultRail3D=true 且玩家
        // 未手持轨道物品）下轨面整个不绘制，两个钩子都不触发，这里因此不再误报。
        if (!scheduleHookAlive && drawQuadHookAlive && !warnedScheduleMissing) {
            warnedScheduleMissing = true;
            Main.LOGGER.warn("[RailRoll] 程序化轨面已绘制（IDrawing.drawTexture 重定向已触发），"
                    + "但 MainRenderer.scheduleRender 的包装钩子从未触发；P4b-1 未生效"
                    + "（说明轨面排队 lambda 的重定向没解析成功：请用 javap 重新核对 RenderRails 的 "
                    + "lambda$renderRailStandard$1X，并更新 RAIL_QUAD_SCHEDULE_TARGET / RAIL_QUAD_DRAW_TARGET）");
        }
        if (rollFrameCount > 0 && !drawQuadHookAlive && !warnedDrawQuadMissing) {
            warnedDrawQuadMissing = true;
            Main.LOGGER.warn("[RailRoll] 已排入 " + rollFrameCount + " 个滚转截面，但 IDrawing.drawTexture 重定向从未触发；P4b-1 未生效");
        }
        // 3D 模型路径（FIX 4 / D7）：构造器钩子确实在跑（modelSamples > 0）却一次都没追加过滚转 ——
        // 此时若本会话确实渲染过带滚转的轨道，说明快照 / 参数反解那一侧出了问题。
        // 只报一次，并且措辞保留「也可能只是这次没画到带滚转的 3D 轨道」这一情形。
        if (modelSamples >= HOOK_PROBE_SAMPLES && modelRollCount == 0 && !warnedModelMissing) {
            warnedModelMissing = true;
            Main.LOGGER.warn("[RailRoll] 3D 轨道模型的 StoredMatrixTransformations 构造器钩子已触发 "
                    + modelSamples + " 次，但从未追加过滚转。若本会话出现过带滚转的 3D 轨道，"
                    + "请用 javap 重新核对 RenderRails 的 lambda$renderRailStandard$16（"
                    + "RAIL_MODEL_MATRIX_TARGET）与其中的 NEW StoredMatrixTransformations");
        }
        if (!frameHookAlive && !warnedFrameMissing) {
            warnedFrameMissing = true;
            Main.LOGGER.warn("[RailRoll] RenderVehicles.render 帧头钩子未触发，P4b-2 车体滚转的轨道缓存不会刷新");
        }
        // 本车世界 PnR 的捕获钩子：它同时决定「乘车时车体滚转」与「风挡滚转」，失效必须先报出来。
        // 只有在车体帧缓存确实在跑（frameHookAlive）时才判定，避免渲染被整体剔除时误报。
        if (frameHookAlive && !carFrameHookAlive && !warnedCarFrameMissing) {
            warnedCarFrameMissing = true;
            Main.LOGGER.warn("[RailRoll] 车体帧缓存已运行 " + frameSamples + " 次，但 RenderVehicles 的 "
                    + "lambda$render$14 里 getRenderPositionAndRotation 重定向从未触发；"
                    + "乘车时的车体滚转与风挡滚转都不会生效（请用 javap 重新核对 CAR_FRAME_TARGET 与 "
                    + "GET_RENDER_POSITION_AND_ROTATION_TARGET）");
        }
        // 风挡 / 挡板 @ModifyArgs：车体钩子已经在跑（说明本车世界 PnR 拿得到）却一次都没进过 renderConnection，
        // 多半是 lambda$render$12 的编号变了或调用点选择器对不上。
        if (carFrameHookAlive && connectionSamples == 0 && !warnedConnectionMissing) {
            warnedConnectionMissing = true;
            Main.LOGGER.warn("[RailRoll] 车体渲染钩子已触发 " + carFrameSamples + " 次，但 renderConnection 的 "
                    + "@ModifyArgs 从未触发；风挡 / 挡板不会随车体倾斜（请用 javap 重新核对 "
                    + "CONNECTION_CALLER_TARGET、RENDER_CONNECTION_DESCRIPTOR 与 CONNECTION_OSCILLATION_ARG）");
        }
        // 转向架来源 @ModifyVariable：车体 PnR 钩子已经在跑（说明每节车厢都会走到这里）却一次都没抓到
        // vehicleCarDetails，说明 lambda$render$14 的编号或形参槽位对不上。它失效不会让滚转消失，
        // 只会让车体退回「弦中点单点采样」= 修复前的滞后与折角形态，所以单独报一条。
        if (carFrameHookAlive && bogieFrameSamples == 0 && !warnedBogieFrameMissing) {
            warnedBogieFrameMissing = true;
            Main.LOGGER.warn("[RailRoll] 车体渲染钩子已触发 " + carFrameSamples + " 次，但转向架来源的 "
                    + "@ModifyVariable 从未触发；车体滚转退回「弦中点单点采样」，"
                    + "会晚半个转向架间距才倾斜、并在节点处出现折角（请用 javap 重新核对 "
                    + "BOGIE_FRAME_TARGET 与 BOGIE_SOURCE_INDEX）");
        }
        // 乘车玩家位置（P4c）：只有「确实以乘车状态渲染过车体」（ridingBodyRollSamples > 0）时，
        // 「玩家位置随车体滚转」的钩子没触发才算异常 —— 否则玩家从不乘车会误报。
        if (ridingBodyRollSamples > 0 && !ridingPositionHookAlive && !warnedRidingPositionMissing) {
            warnedRidingPositionMissing = true;
            Main.LOGGER.warn("[RailRoll] 已以乘车状态渲染车体 " + ridingBodyRollSamples + " 次，但 "
                    + "VehicleRidingMovement.movePlayer 里的 transformForwards 重定向从未触发；"
                    + "玩家在车厢内仍会站在水平的隐形地板上、不随车体倾斜（请用 javap 重新核对 "
                    + "RIDING_MOVE_TARGET 与 TRANSFORM_FORWARDS_DESCRIPTOR）");
        }
    }

    /**
     * 是否<b>所有</b>滚转相关钩子都从未触发（{@link #probeRenderFrame()} 的看门狗本身不算，
     * 它是 require = 1 的那个，能跑到这里就说明它活着）。
     */
    private static boolean allRollHooksMissing() {
        return !railSectionHookAlive
                && !endRailSectionHookAlive
                && !scheduleHookAlive
                && !drawQuadHookAlive
                && !modelHookAlive
                && !frameHookAlive
                && !carFrameHookAlive
                && !connectionHookAlive;
    }

    /**
     * 记录「当前正在渲染的车厢」的世界 PnR，并据此算出本车应当施加的滚转角。
     * <p>
     * 由 {@code RenderVehicles.lambda$render$14} 内 {@code getRenderPositionAndRotation} 调用的
     * {@code @Redirect} 在<b>该车自己的世界 PnR 被换算成相机相对量之前</b>调用，
     * 因此这里拿到的就是与「未乘车」分支完全同类的 {@link PositionAndRotation}：
     * 位置 = 两转向架弦中点、偏航 = 车体世界偏航。
     * <p>
     * <b>为什么现在能在本方法里一次算完</b>：本车厢的两个转向架 PnR 由
     * {@link #captureBogieSource} 在同一个 lambda 的 <b>HEAD（字节码偏移 0）</b>抓取，
     * 而本方法在偏移 64 才被调用 —— 数据已经就绪。旧实现用的是偏移 104 的 {@code @ModifyArgs}，
     * 时序更晚，只能把计算拆到 {@code beginCarFrame} 里；换成 HEAD 的 {@code @ModifyVariable}
     * 之后这个拆分不再需要（同时也去掉了那个会崩游戏的钩子，见
     * {@link #BOGIE_FRAME_TARGET} 与该钩子的说明）。
     * <p>
     * <b>为什么按转向架采两次</b>：MTR 给车体的 {@code PositionAndRotation} 是
     * 「两转向架弦的中点」（{@code PositionAndRotation(ObjectArrayList, VehicleCar, boolean)}
     * 对两转向架位置取 {@code Vector.getAverage}），而车体是<b>刚体</b>、只能有一个姿态。
     * 只取弦中点处一个剖面值，会有两个可测量的毛病（数值见 {@code build/tmp/rollvec/}）：
     * <ol>
     *   <li><b>滞后半个转向架间距</b>：车体只在弦中点进入超高段后才开始倾斜。前转向架比它早
     *       {@code 半转向架间距} 进入、后转向架比它晚同样距离进入，于是「前半个车身已经骑在倾斜的
     *       轨道上、车体还是平的」，反过来出超高段时车体也晚半个间距回平。这正是用户说的
     *       「要等两个转向架都进去才倾」；</li>
     *   <li><b>折角原样搬到车体上</b>：单点采样时车体的滚转变化率就是该轨道剖面的斜率
     *       （{@code 滚转角 / 该轨道长度}）。坡道越短越像「突然一歪」，剖面在节点处的折角更是
     *       原封不动地出现在车体上。</li>
     * </ol>
     * 本方法改为：对两个转向架 PnR 各跑一次与修复前<b>逐字相同</b>的单点匹配
     * （{@link #rollDegreesAtPoint(PositionAndRotation)}，匹配门控、最近者、符号换算全部不变），
     * 再把两个<b>世界帧</b>角度取平均。刚体车在轨道上的标准姿态正是「两转向架处滚转的平均」，
     * 于是：
     * <ul>
     *   <li>前转向架一进入超高段，车体就开始变化（不再滞后半个间距）；</li>
     *   <li>节点处的折角被摊到「两转向架间距」这一段长度上，变成一个真正的缓坡；</li>
     *   <li>剖面上<b>线性</b>的部分（除节点邻域外的全部位置）两个采样平均后<b>逐位等于</b>中点值，
     *       所以轨道网格 / 3D 钢轨模型与车体在这些位置仍然完全一致；只有节点
     *       {@code ±半个转向架间距} 内两者才有差别，而那里的平均正是刚体车能达到的最好姿态 ——
     *       两个转向架各自站在自己那段轨道的滚转上（修复前的单点采样同样在节点附近与网格不一致，
     *       却额外在线性坡道上整体滞后半个间距）。</li>
     * </ul>
     * <b>只对「本车厢」生效</b>：转向架模型（{@code lambda$render$5}）、乘车玩家实体
     * （{@code renderPlayer}）与电梯（{@code RenderLifts}）传给
     * {@link #applyTrainRoll} 的都是它们自己的 PnR 实例，不是本车厢的弦中点 PnR，
     * 因此仍走原来的单点匹配（与修复前逐位一致）。判定用<b>实例同一性</b>：
     * 未乘车时 {@code getRenderPositionAndRotation} 直接把第 4 个实参原样返回
     * （真实 4.0.5 字节码偏移 0–9：{@code offsetVector == null} → {@code aload_3; areturn}），
     * 所以车体与风挡拿到的就是这里存下的同一个实例。
     * <p>
     * <b>为什么用「实例同一性」而不是「位置比较」</b>：同一帧里不同车厢的 PnR 是不同实例，
     * 位置可能极其接近（编组内相邻车厢），比较位置会有歧义；实例比较是精确且 O(1) 的。
     * 若不匹配（转向架 / 玩家 / 电梯 / 未来 MTR 改动），自动退回单点匹配，不改变任何既有行为。
     * <p>
     * <b>失败模式</b>：{@link #captureBogieSource} 没抓到本车厢的转向架
     * （钩子未挂上 / 列表为空 / 元素类型变了）时，本方法退回<b>修复前的「弦中点单点采样」</b>
     * （{@link #rollDegreesAtPoint(PositionAndRotation)}）；若连本方法的重定向都没挂上，
     * {@code carFramePositionAndRotation} 恒为 null → {@code carFrameValid} 恒为 false →
     * 乘车时车体、风挡与玩家都退化为「滚转角 0」（即现状）。
     * 两种失败都只会少倾斜，绝不会把上一帧 / 上一节车厢的滚转角误加到本车上；
     * {@link #reportMissingHooks()} 会一次性告警。
     */
    public static void beginCarFrame(PositionAndRotation absoluteVehicleCarPositionAndRotation) {
        carFrameHookAlive = true;
        carFrameSamples++;
        carFramePositionAndRotation = absoluteVehicleCarPositionAndRotation;
        carFrameValid = false;
        carFrameRollDegrees = 0.0D;
        if (absoluteVehicleCarPositionAndRotation == null) {
            reportMissingHooks();
            return;
        }
        if (pendingBogieCarPositionAndRotation == absoluteVehicleCarPositionAndRotation) {
            final List<?> bogiePositions = pendingBogiePositions;
            double sum = 0.0D;
            int count = 0;
            if (bogiePositions != null) {
                for (final Object element : bogiePositions) {
                    if (element instanceof PositionAndRotation) {
                        sum += rollDegreesAtPoint((PositionAndRotation) element);
                        count++;
                    }
                }
            }
            if (count > 0) {
                final double averaged = sum / count;
                if (Double.isFinite(averaged)) {
                    carFrameRollDegrees = averaged;
                    carFrameValid = true;
                    reportMissingHooks();
                    return;
                }
            }
        }
        // 拿不到本车厢的转向架（钩子未挂上 / 列表为空 / 元素类型变了）→ 修复前的「弦中点单点采样」
        carFrameRollDegrees = rollDegreesAtPoint(absoluteVehicleCarPositionAndRotation);
        carFrameValid = true;
        reportMissingHooks();
    }

    /**
     * 记录本车厢的<b>两个转向架 PnR 来源</b>（= 车体滚转按转向架平均的数据源）。
     * <p>
     * 由 {@code RenderVehiclesMixin#fangsu$captureBogieSource} 在
     * {@code RenderVehicles.lambda$render$14} 的 <b>HEAD</b> 用
     * {@code @ModifyVariable(argsOnly = true, index = }{@link #BOGIE_SOURCE_INDEX}{@code )}
     * 抓到该 lambda 的第 2 个形参 {@code vehicleCarDetails} 后转交进来。沿 MTR 自己的
     * {@code right()} → {@code left()} / {@code right()} 取出：
     * <ul>
     *   <li>{@code left()}：本车厢的转向架 {@code PositionAndRotation} 列表
     *       （真实 4.0.5 字节码偏移 69–79 就是把它作为 {@code iterateWithIndex} 的第 0 个实参）；</li>
     *   <li>{@code right()}：本车世界 PnR（弦中点），用于实例同一性校验。</li>
     * </ul>
     * 两者<b>整组覆盖</b>，所以不存在「列表是新车、PnR 是旧车」的中间态。真正的滚转角计算在
     * {@link #beginCarFrame(PositionAndRotation)}（偏移 64）里做，那时序晚于本钩子（偏移 0）。
     * <p>
     * 只读、不改任何实参；{@code vehicleCarDetails} 为 {@code null} 或结构不符时把两个字段清空，
     * 于是 {@code beginCarFrame} 会走「弦中点单点采样」，不会让滚转消失。
     */
    public static void captureBogieSource(ObjectObjectImmutablePair<?, ?> vehicleCarDetails) {
        bogieFrameHookAlive = true;
        bogieFrameSamples++;
        pendingBogiePositions = null;
        pendingBogieCarPositionAndRotation = null;
        if (vehicleCarDetails != null && vehicleCarDetails.right() instanceof ObjectObjectImmutablePair) {
            final ObjectObjectImmutablePair<?, ?> carDetails =
                    (ObjectObjectImmutablePair<?, ?>) vehicleCarDetails.right();
            if (carDetails.left() instanceof List) {
                pendingBogiePositions = (List<?>) carDetails.left();
            }
            if (carDetails.right() instanceof PositionAndRotation) {
                pendingBogieCarPositionAndRotation = (PositionAndRotation) carDetails.right();
            }
        }
        reportMissingHooks();
    }

    /**
     * 风挡 / 挡板应当加到 {@code oscillationAmount} 上的滚转角（度）。
     * <p>
     * <b>为什么要加而不是减</b>：{@code renderConnection} 的局部摆动是
     * {@code new Vector(...).rotateZ(-Math.toRadians(oscillationAmount))}（真实 4.0.5 字节码 22–28 与 68–70，
     * 源码为 {@code newOscillationAmount = -Math.toRadians(oscillationAmount)}），而
     * {@code Vector.rotateZ(θ)} 的实现是 {@code (x·cosθ + y·sinθ, y·cosθ − x·sinθ, z)} —— 即
     * {@code Rz(θ)}<sub>右手</sub> 的转置，等价于 {@code Rz(−θ)}<sub>右手</sub>；
     * 代入 {@code θ = −toRadians(osc)} 后风挡局部得到的是 {@code Rz(+osc°)}<sub>右手</sub>。
     * 车体侧 {@code GraphicsHolder.rotateZDegrees(osc + roll)} 是
     * {@code Axis.ZP.rotationDegrees} = {@code Quaternionf.rotationZ}（JOML 标准右手旋转），
     * 同样是绕局部 {@code +Z} 的右手旋转；而风挡的「局部系」与车体模型局部系相差
     * {@code F = diag(−1,−1,1)}（绕 Z 转 π，与任何绕 Z 的旋转可交换），
     * 所以两边同一个物理点对上的充要条件就是 {@code osc' = osc + roll}。
     * 数值证明见 {@code build/tmp/rollfix3/ConnectionRollTest}：{@code osc' = osc + roll} 时
     * 车体与风挡顶点完全重合（误差 0.000e+00），{@code osc − roll} 时差 0.635 m（5°）/ 0.762 m（6°）。
     * <p>
     * 未乘车（{@code useOffset == true}）时 {@code renderConnection} 拿到的 {@code positionAndRotation}
     * 就是该车的世界 PnR（与车体用的<b>同一个实例</b>），因此直接走与车体相同的取值路径
     * （本车厢 → 两转向架平均，见 {@link #carBodyRollDegrees}）；
     * 乘车时它是相机相对量，改用 {@link #beginCarFrame(org.mtr.mod.render.PositionAndRotation)} 算出的同一个值。
     *
     * @param connectionPositionAndRotation {@code renderConnection} 的第 11 个实参（形参序号 10）
     * @param useOffset                     {@code renderConnection} 的第 12 个实参（形参序号 11）
     * @return 要加到 {@code oscillationAmount} 上的度数；不倾斜时为 {@code 0}
     */
    public static double getConnectionRollDegrees(PositionAndRotation connectionPositionAndRotation, boolean useOffset) {
        connectionHookAlive = true;
        connectionSamples++;
        if (useOffset) {
            // 未乘车：renderConnection 收到的就是车体用的那个世界 PnR（同一实例），
            // 因此走与车体完全相同的取值路径（本车厢 → 两转向架平均，否则单点匹配）
            return carBodyRollDegrees(connectionPositionAndRotation);
        }
        // 乘车：renderConnection 收到的是相机相对 PnR，改用本车世界 PnR 预计算出的同一个值
        return currentCarRollDegrees();
    }

    /** 当前车厢的滚转角（度）；捕获钩子未生效时为 {@code 0}（见 {@link #beginCarFrame(org.mtr.mod.render.PositionAndRotation)}）。 */
    private static double currentCarRollDegrees() {
        return carFrameValid ? carFrameRollDegrees : 0.0D;
    }

    /**
     * 「乘车玩家站在车厢内时的世界位置」应当施加的滚转角（度）。
     * <p>
     * <b>与 {@link #applyTrainRoll} 用的是同一份缓存</b>（{@link #beginCarFrame(org.mtr.mod.render.PositionAndRotation)} 里算出的
     * {@code carFrameRollDegrees}），因此车体、风挡与玩家三者按构造不可能给出不同的滚转角。
     * 本方法只读取，不推进任何诊断计数、不修改任何状态。
     * <p>
     * 由 {@code com.fangsu.mixin.VehicleRidingMovementMixin} 在
     * {@code VehicleRidingMovement.movePlayer} 的「车厢局部坐标 → 世界坐标」那一步读取；
     * 该 hook 记录存活状态（{@link #ridingPositionHookAlive}）供一次性诊断使用。
     * <p>
     * 捕获钩子失效（{@code carFrameValid == false}）时返回 {@code 0}，调用方退化为
     * MTR 原生行为（玩家不随车体倾斜），不会误用上一帧 / 上一节车厢的角度。
     */
    public static double getCurrentCarRollDegrees() {
        ridingPositionHookAlive = true;
        return currentCarRollDegrees();
    }

    /**
     * 给车体变换追加滚转（P4b-2）。
     * <p>
     * 追加的消费者在 MTR 自己的
     * {@code translate → rotateYRadians(yaw+π) → rotateXRadians(pitch+π) → rotateZDegrees(Oscillation)}
     * 之后执行，因此处在「局部 {@code +Z} = 车头前进方向、局部 {@code +X} = 前进方向右手侧」的坐标系里，
     * 绕局部 {@code +Z} 旋转正好是绕车体前进轴滚转。
     * <p>
     * <b>枢轴就是车体原点，不加任何平移</b>：MTR 的俯仰同样没有枢轴平移
     * （{@code RenderVehicles.getStoredMatrixTransformations} 只 {@code add} 一个
     * {@code translate(position)} 和一个 {@code rotateY/rotateX/rotateZ}，见 javap）。
     * 而且在这个链里局部 {@code +Y} 映射到世界 {@code -Y}（{@code rotateY(yaw+π)} 与
     * {@code rotateX(pitch+π)} 的两个 π 把 Y/Z 都翻转，yaw = pitch = 0 时
     * {@code (0,1,0) → (0,-1,0)}），所以旧实现里的 {@code translate(0, +1, 0)} 其实是
     * <b>向下</b>平移 1 米，枢轴落在轨面下方 1 米处，滚转时车体还会横向平移
     * {@code 1·sin(roll)}（10° 约 0.17 m，20° 约 0.34 m）。改为绕车体原点滚转后既无横向位移也无竖向位移。
     * <p>
     * 无滚转时不追加任何消费者，返回值与 MTR 原生完全一致。
     * <p>
     * <b>乘车分支（BUG 1 的修复）</b>：{@code useOffset == false} 时传进来的
     * {@code renderingPositionAndRotation} 是 {@code getRenderPositionAndRotation} 换算出的
     * <b>相机相对</b>量（位置与世界位置相差整个相机位置，偏航也减掉了相机偏航），用它做几何匹配必然
     * 匹配不到带滚转的轨道。此时改用 {@link #beginCarFrame(PositionAndRotation)} 记下的本车世界 PnR，
     * 匹配代码与「未乘车」分支完全相同。捕获钩子失效时 {@code carFrameValid} 为 false，
     * 本方法退化为「不追加任何消费者」（与修复前的表现一致），不会误倾斜。
     *
     * @param useOffset {@code getStoredMatrixTransformations} 的第一个参数；MTR 源码里它就是
     *                  {@code offsetVector == null}，javadoc 原文为
     *                  “{@code true} if the vehicle is not being ridden in”
     */
    public static void applyTrainRoll(StoredMatrixTransformations storedMatrixTransformations,
                                      PositionAndRotation positionAndRotation,
                                      boolean useOffset) {
        if (storedMatrixTransformations == null) {
            return;
        }
        final double rollDegrees;
        if (useOffset) {
            // 未乘车：只有「本车厢的弦中点 PnR」（同一实例）走「两转向架平均」；
            // 转向架模型 / 乘车玩家实体 / 电梯传进来的是它们自己的 PnR → 单点匹配（与修复前逐位一致）
            rollDegrees = carBodyRollDegrees(positionAndRotation);
        } else {
            // 乘车分支：记录次数，作为「乘车玩家位置随车体滚转」钩子（VehicleRidingMovementMixin）
            // 一次性诊断的前置条件 —— 只有确实以乘车状态渲染过车体，它没触发才算异常。
            ridingBodyRollSamples++;
            rollDegrees = currentCarRollDegrees();
        }
        if (!Double.isFinite(rollDegrees) || rollDegrees == 0.0D) {
            // 非有限值（理论上已被 rollDegreesAtPoint / currentCarRollDegrees 挡掉）绝不能进旋转，
            // 否则整个车体矩阵变成 NaN（D-B 的同类漏洞）。
            return;
        }
        storedMatrixTransformations.add(graphicsHolder -> graphicsHolder.rotateZDegrees((float) rollDegrees));
    }

    /**
     * 这次调用应当施加的车体滚转角（度）：<b>只有「本车厢的弦中点 PnR」才用两转向架平均</b>。
     * <p>
     * 判定用实例同一性（见 {@link #beginCarFrame(org.mtr.mod.render.PositionAndRotation)} 的说明）：
     * <ul>
     *   <li>{@code positionAndRotation} 就是本车 PnR（未乘车时 {@code getRenderPositionAndRotation}
     *       原样返回的那一个实例）→ 用 {@link #beginCarFrame(org.mtr.mod.render.PositionAndRotation)} 算好的
     *       {@link #carFrameRollDegrees}（= 两个转向架处滚转的平均），车体与风挡都用它；</li>
     *   <li>其它任何 PnR（转向架模型、乘车玩家实体、电梯、以及未来 MTR 的其它调用点）→ 走
     *       {@link #rollDegreesAtPoint(PositionAndRotation)} 单点匹配，行为与本次修复前<b>逐位一致</b>。</li>
     * </ul>
     * 因此本方法不会改变「转向架模型贴着自己脚下的轨道滚转」这一既有行为，
     * 也不会让车体与它自己的两个转向架模型出现不同的滚转。
     */
    private static double carBodyRollDegrees(PositionAndRotation positionAndRotation) {
        if (positionAndRotation != null
                && carFrameValid
                && positionAndRotation == carFramePositionAndRotation) {
            return carFrameRollDegrees;
        }
        return rollDegreesAtPoint(positionAndRotation);
    }

    /**
     * 某个世界点处「最近的带滚转轨道」应当施加的滚转角（度）；匹配不到返回 {@code 0}。
     * 正值 = 沿该点前进方向的右手侧抬升。
     * <p>
     * 本方法就是<b>修复前</b>的整套匹配逻辑，逐字未改：以 {@code positionAndRotation.position}
     * （世界水平点）反解每条候选带滚转轨道的参数（{@code parameterAt} 会夹到 {@code [0, length]}），
     * 依次过「高差 &lt; {@link #VEHICLE_MATCH_VERTICAL}」「水平距离 &lt;
     * {@link #VEHICLE_MATCH_HORIZONTAL}」「前进方向与轨道切向 |cos| ≥
     * {@link #VEHICLE_MATCH_PARALLEL}」三道门，取水平最近者，再读它的
     * {@code getRollRadians} 并换算到世界帧。
     * <p>
     * 它现在被两种调用方使用：车体（对两个转向架各调一次再平均，见
     * {@link #beginCarFrame(org.mtr.mod.render.PositionAndRotation)}）与其它一切（转向架模型 / 玩家 / 电梯，各自单点调用）。
     */
    private static double rollDegreesAtPoint(PositionAndRotation positionAndRotation) {
        final List<RolledRail> candidates = frameRolledRails;
        if (candidates.isEmpty() || positionAndRotation == null) {
            return 0.0D;
        }
        final Vector position = positionAndRotation.position;
        if (position == null) {
            return 0.0D;
        }

        // 车体前进方向的水平投影：车体局部 +Z 在世界系里就是 (sin yaw, 0, cos yaw)
        final double forwardX = Math.sin(positionAndRotation.yaw);
        final double forwardZ = Math.cos(positionAndRotation.yaw);

        RolledRail best = null;
        double bestParameter = 0.0D;
        double bestHorizontal = Double.MAX_VALUE;
        double bestAlignment = 0.0D;
        for (final RolledRail candidate : candidates) {
            final double parameter = candidate.core.parameterAt(position.x, position.z);
            final double[] center = candidate.core.getPosition(parameter, false);
            // D-B：非有限量（退化轨道反解、非有限坐标）一律视为「匹配不上」，
            // 否则 NaN 的每一次比较都返回 false，车体会被加上一个 NaN 滚转角。
            if (!Double.isFinite(parameter) || !Double.isFinite(center[0]) || !Double.isFinite(center[1])
                    || !Double.isFinite(center[2])) {
                continue;
            }
            if (Math.abs(center[1] - position.y) > VEHICLE_MATCH_VERTICAL) {
                continue;
            }
            final double dx = center[0] - position.x;
            final double dz = center[2] - position.z;
            final double horizontal = dx * dx + dz * dz;
            if (horizontal > VEHICLE_MATCH_HORIZONTAL * VEHICLE_MATCH_HORIZONTAL) {
                continue;
            }
            final double[] tangent = candidate.tangent(parameter);
            final double alignment = forwardX * tangent[0] + forwardZ * tangent[1];
            if (!Double.isFinite(alignment) || Math.abs(alignment) < VEHICLE_MATCH_PARALLEL) {
                // 车头与轨道切向近乎垂直（或切向非有限 → 匹配不上）—— 不是本车所在的那条
                continue;
            }
            if (horizontal < bestHorizontal) {
                bestHorizontal = horizontal;
                best = candidate;
                bestParameter = parameter;
                bestAlignment = alignment;
            }
        }
        if (best == null) {
            return 0.0D;
        }

        final double roll = best.core.getRollRadians(bestParameter);
        if (!Double.isFinite(roll) || roll == 0.0D) {
            return 0.0D;
        }

        // 车头是否与「position1 → position2」同向 = (车头对齐参数方向) == (参数方向对齐 position1→position2)
        final boolean forwardIsPosition1ToPosition2 = (bestAlignment >= 0.0D) == best.parameterIsPosition1ToPosition2;

        // 正 roll = 右手侧抬升；rotateZDegrees 正值是右手侧下沉 → 取负号
        return -Math.toDegrees(roll) * (forwardIsPosition1ToPosition2 ? 1.0D : -1.0D);
    }

    // ==================== 内部数据结构 ====================

    /** 一次延迟绘制期间的滚转快照（不可变，可跨线程传递）。 */
    private static final class RollFrame {
        private final RailGeometryCore core;
        private final boolean parameterIsPosition1ToPosition2;

        private RollFrame(RailGeometryCore core, boolean parameterIsPosition1ToPosition2) {
            this.core = core;
            this.parameterIsPosition1ToPosition2 = parameterIsPosition1ToPosition2;
        }
    }

    /** 参与车体定位的带滚转轨道。 */
    private static final class RolledRail {
        private final RailGeometryCore core;
        private final boolean parameterIsPosition1ToPosition2;

        private RolledRail(RailGeometryCore core, boolean parameterIsPosition1ToPosition2) {
            this.core = core;
            this.parameterIsPosition1ToPosition2 = parameterIsPosition1ToPosition2;
        }

        /**
         * 参数处的轨道切向（已归一化的世界水平向量 {@code {x, z}}），
         * 直接复用 {@link #parameterTangent(RailGeometryCore, double)}。
         * 区间退化时返回 {@code {0, 0}}（调用方的平行度检查会因此判为不平行，
         * 等价于「匹配不上」，不会误倾斜）。
         */
        private double[] tangent(double parameter) {
            return parameterTangent(core, parameter);
        }
    }
}
