package com.fangsu.blockEntities;

import com.fangsu.Main;
import com.fangsu.client.ClientHooks;
import com.fangsu.mappings.rail.RailPoseExtra;
import com.fangsu.network.ModNetwork;
import com.fangsu.render.scripting.util.DynamicModelHolder;
import com.fangsu.render.sowcer.math.Matrices;
import com.fangsu.utils.ResourceUtil;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.fangsu.blocks.ModBlocks.BLOCK_ENTITY_MULTI_DIRECTION_NODE;

/**
 * 万向节点方块实体。
 * <p>
 * NBT 存储（与需求一致）：
 * <ul>
 *   <li>{@code direction} (double) — 当前方向（度，0=E, 90=S, 180=W, 270=N）；未绑定时为默认 0</li>
 *   <li>{@code connected} (bool) — 是否已连接轨道</li>
 *   <li>{@code directionBonded} (bool) — 方向是否已绑定；未绑定(false)时模型持续旋转</li>
 *   <li>{@code offsetX/offsetY/offsetZ} (double) — 锚点平移（格，钳制 ±{@value #MAX_OFFSET}）</li>
 *   <li>{@code pitchDeg} (double) — 俯仰角（度，钳制 ±{@value #MAX_PITCH_DEG}），正 = 沿方向前进时上坡</li>
 *   <li>{@code rollDeg} (double) — 翻滚角（度，钳制 ±{@value #MAX_ROLL_DEG}），正 = 前进方向右手侧抬高</li>
 *   <li>{@code superelevation} (bool) — 外轨超高开关，默认 true；<b>只</b>门控滚转对轨道几何的贡献</li>
 *   <li>{@code rollOffsetM} (double) — 半轨距（米，默认 0.7175，钳制
 *       [{@value #MIN_HALF_GAUGE}, {@value #MAX_HALF_GAUGE}]），滚转抬升系数</li>
 * </ul>
 * <p>
 * <b>硬边界（本类是唯一权威来源）</b>：{@link #MAX_OFFSET} / {@link #MAX_PITCH_DEG} /
 * {@link #MAX_ROLL_DEG} / {@link #MIN_HALF_GAUGE} / {@link #MAX_HALF_GAUGE} /
 * {@link #wrapDirectionDegrees(double)} 是这些字段的<b>真实安全上限</b>，由本类在
 * setter、{@code readC2S}（C2S 载荷）与 {@code load}（NBT）三条入口统一执行。
 * <p>
 * 配置界面（{@code MultiDirectionNodeConfigScreen}）的<b>滑块区间</b>是另一套更窄的常量，
 * 只用于「拖动方便」，不再是硬边界：滑块区间可以小于硬边界，用户用输入框键入超界值时，
 * 真值按这里的硬边界钳制/回绕后落库，滑块则钉在自己的端点上显示。
 * 二者刻意分开，避免「想把值输大一点却被滑块区间悄悄吃掉」。
 * <p>
 * 客户端镜像：界面不重复定义这些数字，而是直接引用本类的常量与静态方法，
 * 因此客户端钳制与服务端钳制永远同源、不会漂移。
 * <p>
 * <b>P4a 阶段边界</b>：{@code pitchDeg/rollDeg/rollOffsetM} 既倾斜节点自身的标记模型，
 * 也经 {@code NodeConnector.readRailPose / readNodePose} 写进 {@code RailPoseExtra}，
 * 由几何内核消费（俯仰 → 三次 Hermite 竖向剖面；滚转 → {@code 半轨距·|sin(roll)|} 中心线抬升）。
 * 轨道<b>截面</b>与车体的视觉倾斜仍属下一步（P4b）。
 * <p>
 * 未绑定时 {@link #whenRendering()} 让模型绕 Y 轴匀速 360° 旋转；绑定后按 {@code direction} 固定。
 * 已连接时默认隐藏模型，仅手持轨道连接器或刷子时显示 node_connected.obj（与原版 MTR 节点行为一致）。
 * 扳手右键打开 FangSu 万向节点配置界面（{@code MultiDirectionNodeConfigScreen}：平移 / 方向 / 轨道编辑）；
 * 刷子右键与 MTR 原版节点一致，打开 MTR 自带的轨道形状修改界面。
 */
public class BlockEntityMultiDirectionNode extends BaseObjBlockEntity implements Syncable {

    private static final String DEFAULT_MODEL = "fangsu:models/obj/node.obj";
    private static final String CONNECTED_MODEL = "fangsu:models/obj/node_connected.obj";

    // ==================== NBT 键 ====================
    private static final String KEY_DIRECTION = "direction";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_DIRECTION_BONDED = "directionBonded";
    /** 节点锚点平移（格，double，钳制到 ±{@value #MAX_OFFSET}）。 */
    private static final String KEY_OFFSET_X = "offsetX";
    private static final String KEY_OFFSET_Y = "offsetY";
    private static final String KEY_OFFSET_Z = "offsetZ";
    /**
     * 节点俯仰角（度，double，钳制到 ±{@value #MAX_PITCH_DEG}）。
     * <p>
     * 约定：节点处轨道切线的<b>竖向坡角</b>，沿节点方向前进时<b>正 = 上坡</b>。
     * 见 {@link #whenRendering()} 的中文注释。
     */
    private static final String KEY_PITCH_DEG = "pitchDeg";
    /**
     * 节点翻滚角（度，double，钳制到 ±{@value #MAX_ROLL_DEG}）。
     * <p>
     * 约定：绕节点前进轴的旋转，<b>正 = 前进方向右手侧抬高</b>（外轨超高 / 翻滚约定，
     * 与 {@code RailPoseExtra.roll1Degrees/roll2Degrees} 及几何内核的 {@code |sin(roll)|} 中心线抬升一致）。
     */
    private static final String KEY_ROLL_DEG = "rollDeg";
    /**
     * 外轨超高开关（bool，默认 true）。
     * <p>
     * <b>只门控滚转</b>对轨道几何的贡献：关闭时翻滚角照常保存、节点模型照常倾斜，
     * 但写进 {@code RailPoseExtra.roll1Degrees/roll2Degrees} 的值强制为 0，
     * 于是几何内核的中心线抬升 {@code 半轨距·|sin(roll)|} 消失（{@code hasRoll()==false}）。
     * <p>
     * 纵坡（pitch）是独立功能，<b>不受本开关影响</b>：俯仰角始终写入
     * {@code RailPoseExtra.pitch1Degrees/pitch2Degrees} 并驱动三次 Hermite 竖向剖面。
     */
    private static final String KEY_SUPERELEVATION = "superelevation";
    /**
     * 半轨距（米，double，默认 {@link RailPoseExtra#DEFAULT_HALF_GAUGE}，钳制到
     * [{@value #MIN_HALF_GAUGE}, {@value #MAX_HALF_GAUGE}]）。
     * <p>
     * 外轨超高的几何量：滚转时中心线抬高 {@code 半轨距·|sin(翻滚角)|}，使内轨保持标高（ANTE 语义）。
     * 默认 0.7175 = 1435 mm 标准轨距的一半。整条轨道只取一个值，由贡献端点提供（见
     * {@code NodeConnector.readRailPose}）。
     */
    private static final String KEY_ROLL_OFFSET_M = "rollOffsetM";

    /**
     * 锚点平移硬上限（格）：几何上把轨道的连接点从方块中心挪出去。
     * <p>
     * 为什么从 1.0 放宽到 2.0：平移只改变「锚点 = 方块坐标 + 偏移」这一项，
     * 几何内核（{@code RailGeometryCore}）对锚点大小没有任何约束，服务端重建又是
     * <b>先校验候选轨道、再原地替换</b>（{@code NodeConnector.refreshNodeRail}），
     * 所以把锚点挪得比一格远只会让轨道端点落到相邻方块范围，不会产生退化轨道或删掉旧轨。
     * 但偏移若再大（例如半条轨道长）就会让端点跑到别的节点/方块语义之外，
     * 既无法解释也无法调试，因此「相邻一格 + 自身一格」= ±2 格是可解释的最远距离。
     * <p>
     * 界面滑块仍只覆盖 ±1.0（拖动方便）；超过 1.0 的值只能通过输入框键入。
     */
    public static final double MAX_OFFSET = 2.0D;

    /**
     * 俯仰角（纵坡）硬上限（度）。
     * <p>
     * 几何内核把俯仰角直接当作端点切线：{@code slope = tan(pitch)}（见
     * {@code RailGeometryCore} 构造器），三次 Hermite 竖向剖面的位移是 {@code slope · 轨道长度}。
     * 于是 ±90° 是<b>真正的奇点</b>（{@code tan} 发散），内核<b>不做</b>任何保护
     * （{@code isValid()} 只看平面几何，不看斜率），必须在节点侧钳制。
     * <p>
     * 取 60°：{@code tan60° = √3 ≈ 1.732}，竖向位移最多约为水平跨度的 1.73 倍，
     * 剖面仍然有限、单调、可渲染，且离 ±90° 奇点有 30° 余量（即便再乘上方向帧符号也不会跳变）。
     * 现实铁路最大坡度约 4~7%（≈ 2.3~4°），60° 已是纯粹的沙盒玩法值。
     */
    public static final double MAX_PITCH_DEG = 60.0D;

    /**
     * 翻滚角（外轨超高）硬上限（度）。
     * <p>
     * 滚转只在几何里产生一项中心线抬升 {@code 半轨距 · |sin(roll)|}
     * （{@code RailGeometryCore#rollLift}），{@code |sin|} 有界，所以几何上再大的滚转也安全；
     * 限制纯粹来自视觉与语义：超过 45° 后车身/截面已经翻掉一半，
     * 再大则 {@code |sin|} 反而开始变小（「更倾斜反而抬得更少」），语义会自相矛盾。
     * <p>
     * 取 45°（与参考实现的 per-rail tilt 上限一致）：对应抬升
     * {@code 0.7175 · sin45° ≈ 0.507 m}（默认半轨距下），视觉上已经很夸张但仍是有限且单调的最大值区间。
     */
    public static final double MAX_ROLL_DEG = 45.0D;

    /**
     * 半轨距下限（米）。
     * <p>
     * 0.25 m 对应 500 mm 轨距，比任何现实铁路（最窄约 600 mm）都窄，但必须保持为正：
     * {@code RailPoseExtra} 与几何内核都把 {@code halfGauge <= 0} 当作「未设置」而回退到默认值
     * （见 {@code RailGeometryCore} 构造器），所以下限不能取 0。
     */
    public static final double MIN_HALF_GAUGE = 0.25D;

    /**
     * 半轨距上限（米）。
     * <p>
     * 2.0 m 对应 4000 mm 轨距，已远超现实最宽轨距（约 1676 mm），
     * 但抬升仍被 {@code |sin|} 与 45° 滚转上限共同夹住（最大 {@code 2.0 · sin45° ≈ 1.41 m}），
     * 因此不会把截面尺寸算飞。上限存在的意义只是防止误输入产生无意义的巨大截面。
     */
    public static final double MAX_HALF_GAUGE = 2.0D;

    /**
     * C2S 载荷版本（{@code ModNetwork.BE_SYNC}）。
     * <p>
     * <b>布局只追加不修改</b>：v1 = direction/connected/directionBonded，v2 在其后追加 3 个 double（平移），
     * v3 再追加 2 个 double（俯仰角 + 翻滚角），v4 再追加 1 个 boolean + 1 个 double
     * （外轨超高开关 + 半轨距）。
     * 接收侧用"剩余可读字节数"判断版本，因此旧客户端（只写 v1 / v2 / v3）与新客户端可以互通，
     * 不会出现读串位。注意 {@code ModNetwork.handleBeSync} 是把 BlockPos 之后的字节
     * 原样包成一个新 buffer 交给 {@link #readC2S}，所以这里的字节数判断是准确的。
     * <p>
     * 字节数：v1 = 10、v2 = 10 + 24 = 34、v3 = 34 + 16 = 50、v4 = 50 + 1 + 8 = 59（不含 BlockPos）。
     * <p>
     * <b>放宽硬边界不涉及线格式</b>：本次只把 {@link #MAX_OFFSET} / {@link #MAX_PITCH_DEG} /
     * {@link #MAX_ROLL_DEG} / {@link #MIN_HALF_GAUGE} / {@link #MAX_HALF_GAUGE} 的数值放大，
     * 字段个数、顺序、类型都没有变化，所以版本号仍停留在 v4（写版本号只是为了让读者一眼看出布局），
     * 接收侧的字节数探针也一字未动。新增字段才需要追加 v5 段并保持「只追加」。
     */
    private static final int C2S_PAYLOAD_VERSION = 4;

    // ==================== 运行时状态 ====================
    private double direction;
    private boolean connected;
    private boolean directionBonded;

    /** 节点锚点平移（格）。导轨中心线、车辆路径与节点模型都跟随该偏移。 */
    private double offsetX;
    private double offsetY;
    private double offsetZ;

    /**
     * 俯仰角（度）：节点处轨道切线的竖向坡角，正 = 沿方向前进时上坡。
     * <p>
     * <b>P4a</b>：本值现在既影响节点自身的标记模型，也写进
     * {@code RailPoseExtra.pitch1Degrees/pitch2Degrees}，由几何内核的三次 Hermite
     * 竖向剖面（端点高度 + 端点切线 {@code tan(俯仰角)}）消费，从而真正改变轨道几何。
     * 轨道截面与车体的视觉倾斜仍属下一步（P4b）。
     */
    private double pitchDeg;
    /**
     * 翻滚角（度）：绕前进轴旋转，正 = 前进方向右手侧抬高。
     * <p>
     * <b>P4a</b>：在 {@link #superelevation} 开关为开时写进
     * {@code RailPoseExtra.roll1Degrees/roll2Degrees}，由几何内核的中心线抬升
     * {@code 半轨距·|sin(roll)|} 消费；开关为关时对几何贡献 0（见 {@link #superelevation}）。
     */
    private double rollDeg;

    /**
     * 外轨超高开关：只门控滚转对轨道几何的贡献（纵坡不受影响）。
     * <p>
     * 默认 true（老存档没有该键 → 取默认值 true）。关闭后翻滚角仍被保存、节点模型仍倾斜，
     * 但轨道姿态里的 roll 端点值写 0，几何回到无超高的水平截面。
     */
    private boolean superelevation = true;

    /**
     * 半轨距（米）：外轨超高的几何量，滚转时中心线抬高 {@code 半轨距·|sin(roll)|}（ANTE 语义）。
     * <p>
     * 默认 {@link RailPoseExtra#DEFAULT_HALF_GAUGE}（= 0.7175，1435 mm 标准轨距的一半），
     * 存入 NBT 前钳制到 [{@link #MIN_HALF_GAUGE}, {@link #MAX_HALF_GAUGE}]。
     */
    private double rollOffsetM = RailPoseExtra.DEFAULT_HALF_GAUGE;

    // ==================== 刷新重试状态（客户端） ====================
    /** 客户端 MTR 数据未同步时，角度刷新（refreshConnectedRailsIfNeeded）延迟重试的待处理标记。 */
    private boolean pendingRefresh;
    /** 下次重试时间（System.currentTimeMillis 毫秒）。 */
    private long nextRetryTime;
    /** 已重试次数。 */
    private int retryCount;
    /** 最大重试次数（每次间隔 RETRY_INTERVAL_MS，合计约 10 秒窗口）。 */
    private static final int MAX_RETRY = 10;
    /** 重试间隔（毫秒）。 */
    private static final long RETRY_INTERVAL_MS = 1000;

    private DynamicModelHolder modelHolder;
    private DynamicModelHolder connectedModelHolder;
    private boolean modelLoadingFailed = false;

    // ==================== 异步加载（照 BlockEntityRotatingRail） ====================
    private static final ExecutorService LOADING_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fangsu-node-loading-async");
        t.setDaemon(true);
        return t;
    });

    private CompletableFuture<Void> loadingFuture;
    private CompletableFuture<Void> renderingFuture;

    public BlockEntityMultiDirectionNode(BlockPos pos, BlockState state) {
        super(BLOCK_ENTITY_MULTI_DIRECTION_NODE.get(), pos, state);
    }

    // ==================== 供连接器/角度界面读取的公开接口 ====================

    /**
     * 当前方向（度）。
     * <p>
     * 三条写入入口（setter / {@link #readC2S} / {@link #load}）都经
     * {@link #wrapDirectionDegrees(double)} 回绕，因此这里读到的值恒在 {@code [0, 360)}；
     * 界面把它直接显示在输入框里，所以显示值与存储值一致。
     */
    public double getDirectionDegrees() {
        return direction;
    }

    /** 是否已连接轨道。 */
    public boolean isConnected() {
        return connected;
    }

    /** 方向是否已绑定。 */
    public boolean isDirectionBonded() {
        return directionBonded;
    }

    /**
     * 方向角硬边界：<b>回绕</b>到 {@code [0, 360)}，而不是钳制。
     * <p>
     * 方向在几何上是一个<b>周期量</b>：所有消费方都只用它的 {@code cos/sin}（{@code frameSign}、
     * {@code angleDifference}、{@code applyNodeModelTilt} 的偏航），建轨时还会再过一遍
     * {@code NodeConnector.normalizeDegrees}，所以 370° 与 10° 是同一个方向、不构成「越界危险值」。
     * 因此这里不设「上限」，输入多少度就按多少度取模；用户键入 450 得到 90，而不是被砍成某个端点值。
     * <p>
     * 与平移/俯仰/翻滚的钳制不同，这一点是刻意的：钳制会让「多转一圈」这种合法输入静默变成别的方向，
     * 而回绕是语义等价变换，不丢信息。
     * <p>
     * NaN / 无穷 → 0（无效输入归零，与 {@link #clampAngle(double, double)} 同风格）；
     * {@code -0.0} 会被归一成 {@code +0.0}，避免 NBT / 序列化里出现 {@code "-0.0"} 这种字符串差异。
     */
    public static double wrapDirectionDegrees(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        double degrees = value % 360.0D;
        if (degrees < 0.0D) {
            degrees += 360.0D;
        }
        // -0.0 % 360 == -0.0 且不满足 < 0，这里显式归一成 +0.0
        return degrees == 0.0D ? 0.0D : degrees;
    }

    /** 设置方向并绑定（directionBonded=true）。服务端/客户端均可调用。方向按 {@link #wrapDirectionDegrees(double)} 回绕。 */
    public void setDirectionAndBind(double degrees) {
        this.direction = wrapDirectionDegrees(degrees);
        this.directionBonded = true;
        this.setChanged();
        this.syncToPeer();
    }

    /** 设置连接状态（对接 MTR IS_CONNECTED 逻辑或连接器 mixin）。 */
    public void setConnected(boolean connected) {
        this.connected = connected;
        this.setChanged();
        this.syncToPeer();
    }

    /**
     * 设置已绑定方向（供连接器在连线后写入），不改变 connected。
     * 方向按 {@link #wrapDirectionDegrees(double)} 回绕到 [0, 360)。
     */
    public void setDirectionBonded(double degrees) {
        this.direction = wrapDirectionDegrees(degrees);
        this.directionBonded = true;
        this.setChanged();
        this.syncToPeer();
    }

    /**
     * 只写方向、<b>不</b>改变绑定状态（界面「旋转绑定：否」使用）。
     * <p>
     * 方向值可以照常写下来（相连轨道重建时要用它算几何），但 {@code directionBonded} 保持原值，
     * 模型继续绕 Y 轴旋转，表示用户并未把方向固定下来。与 {@link #setDirectionAndBind(double)}
     * 的唯一区别就是不动绑定标志；{@link #writeC2S} 的载荷布局完全不变。
     * 方向同样按 {@link #wrapDirectionDegrees(double)} 回绕到 [0, 360)。
     */
    public void setDirectionUnbound(double degrees) {
        this.direction = wrapDirectionDegrees(degrees);
        this.setChanged();
        this.sendUpdateC2S();
    }

    /**
     * 单独写入绑定标志（需要解绑时使用），方向值不变。
     * <p>
     * 「旋转绑定」开关由「是」切到「否」时调用，让服务端与其它客户端立即看到解绑状态。
     */
    public void setRotationBonded(boolean bonded) {
        this.directionBonded = bonded;
        this.setChanged();
        this.sendUpdateC2S();
    }

    // ==================== 锚点平移（P2：节点平移） ====================

    /** 锚点 X 平移（格）。 */
    public double getOffsetX() {
        return offsetX;
    }

    /** 锚点 Y 平移（格）。 */
    public double getOffsetY() {
        return offsetY;
    }

    /** 锚点 Z 平移（格）。 */
    public double getOffsetZ() {
        return offsetZ;
    }

    /** 是否设置了非零平移。 */
    public boolean hasOffset() {
        return offsetX != 0.0D || offsetY != 0.0D || offsetZ != 0.0D;
    }

    /**
     * 写入三个分量的平移（自动钳制到 ±{@value #MAX_OFFSET}），只改数据并 {@code setChanged()}，
     * <b>不</b>发包。需要立刻同步到服务端时用 {@link #setOffsetAndSync(double, double, double)}，
     * 或在写完后调用一次 {@link #sendUpdateC2S()}（{@code writeC2S} 的载荷已包含平移全量）。
     */
    public void setNodeOffset(double x, double y, double z) {
        final double newX = clampOffset(x);
        final double newY = clampOffset(y);
        final double newZ = clampOffset(z);
        if (newX == offsetX && newY == offsetY && newZ == offsetZ) {
            return;
        }
        this.offsetX = newX;
        this.offsetY = newY;
        this.offsetZ = newZ;
        this.setChanged();
        this.syncToPeer();
    }

    /**
     * 从界面保存平移：先写本地数据，再把 {@link #writeC2S} 载荷同步到服务端。
     * <p>
     * 旧实现要求这个 C2S 包必须先于 {@code NODE_REFRESH_RAIL} 到达服务端，因为服务端重建轨道时
     * 是从服务端方块实体读偏移的；这一「跨包顺序」依赖正是轨道漂移 bug 的根因。
     * 现在平移随刷新请求一起发送（见 {@link #refreshConnectedRailsIfNeeded()}），顺序不再影响正确性，
     * 调用方仍按 BE_SYNC → NODE_REFRESH_RAIL 的固定顺序发送，只是为了让数据流保持统一。
     */
    public void setOffsetAndSync(double x, double y, double z) {
        setNodeOffset(x, y, z);
        if (level != null && level.isClientSide) {
            sendUpdateC2S();
        }
    }

    /**
     * 配置界面的平移写入入口：一次完成「写数据 + setChanged + BE_SYNC + 重建相连轨道」。
     * <p>
     * 参数来源不变，但重建请求里已经带上了最新平移，服务端不再依赖 BE_SYNC 的到达顺序。
     * 界面是即时保存的（不点按钮也生效），所以拖动滑块会逐步触发重建；
     * 万向节点相连轨道通常只有 1~2 条，该开销可接受。
     */
    public void applyOffsetAndSync(double x, double y, double z) {
        setNodeOffset(x, y, z);
        if (level == null || !level.isClientSide) {
            return;
        }
        sendUpdateC2S();
        refreshConnectedRailsIfNeeded();
    }

    /** 把平移分量钳制到 ±{@link #MAX_OFFSET}，并消除 NaN / 无穷。 */
    public static double clampOffset(double value) {
        if (Double.isNaN(value)) {
            return 0.0D;
        }
        if (value > MAX_OFFSET) {
            return MAX_OFFSET;
        }
        if (value < -MAX_OFFSET) {
            return -MAX_OFFSET;
        }
        return value;
    }

    // ==================== 俯仰角 / 翻滚角（P3 节点侧 → P4a 作用到轨道几何） ====================

    /**
     * 节点俯仰角（度，纵坡）。
     * <p>
     * <b>约定</b>：节点处轨道切线的竖向坡角，沿节点方向前进时<b>正 = 上坡</b>（爬升）。
     * 该符号被原样写进 {@code RailPoseExtra.pitch1Degrees/pitch2Degrees}，喂给几何内核的
     * 三次 Hermite 竖向剖面（端点切线 = {@code tan(俯仰角)}）。
     */
    public double getPitchDegrees() {
        return pitchDeg;
    }

    /**
     * 节点翻滚角（度，外轨超高）。
     * <p>
     * <b>约定</b>：绕节点前进轴的旋转，<b>正 = 前进方向右手侧抬高</b>。
     * 该符号被原样写进 {@code RailPoseExtra.roll1Degrees/roll2Degrees}（外轨超高开关开启时），
     * 由几何内核按 {@code 半轨距·|sin(roll)|} 抬升中心线。
     */
    public double getRollDegrees() {
        return rollDeg;
    }

    /** 是否设置了非零俯仰 / 翻滚（节点模型据此决定是否额外倾斜）。 */
    public boolean hasTilt() {
        return pitchDeg != 0.0D || rollDeg != 0.0D;
    }

    /**
     * 一次性写入俯仰角 + 翻滚角（自动钳制），只改数据并 {@code setChanged()}，<b>不</b>发包。
     * <p>
     * 与 {@link #setNodeOffset(double, double, double)} 保持同一风格：钳制后与旧值完全相同则直接返回，
     * 避免拖动滑块时每帧都触发一次方块实体更新。需要同步到服务端时调用
     * {@link #setAnglesAndSync(double, double)} 或写完补一次 {@link #sendUpdateC2S()}。
     */
    public void setNodeAngles(double pitch, double roll) {
        final double newPitch = clampPitch(pitch);
        final double newRoll = clampRoll(roll);
        if (newPitch == pitchDeg && newRoll == rollDeg) {
            return;
        }
        this.pitchDeg = newPitch;
        this.rollDeg = newRoll;
        this.setChanged();
        this.syncToPeer();
    }

    /**
     * 俯仰 / 翻滚写入入口：写数据 + {@code setChanged()} + 立即 BE_SYNC（<b>不发</b>轨道重建包）。
     * <p>
     * <b>P4a</b>：两个角度现在会写进轨道姿态（{@code NodeConnector.readRailPose}），轨道几何因此真的会变，
     * 所以调用方在写完本方法后<b>必须</b>触发一次重建。配置界面刻意<b>不</b>在这里重建，而是走
     * {@code MultiDirectionNodeConfigScreen#tryRefreshRails()}：
     * 那条路径会先做几何预检（只看平移 + 方向 + 形状），姿态非法时跳过重建。
     * 若在这里直接调 {@link #refreshConnectedRailsIfNeeded()}，界面就会绕过预检、多发一次注定失败的重建包。
     * <p>
     * {@link #writeC2S} 的载荷是<b>全量</b>（方向 + 绑定 + 平移 + 俯仰 / 翻滚 + 超高开关 + 半轨距），
     * 所以调用方应先把开关 / 半轨距等其它字段写好，最后调本方法，一次包就带齐全部改动。
     */
    public void setAnglesAndSync(double pitch, double roll) {
        setNodeAngles(pitch, roll);
        if (level != null && level.isClientSide) {
            sendUpdateC2S();
        }
    }

    /** 把俯仰角钳制到 ±{@link #MAX_PITCH_DEG}，并消除 NaN / 无穷（NaN / Inf → 0）。 */
    public static double clampPitch(double value) {
        return clampAngle(value, MAX_PITCH_DEG);
    }

    /** 把翻滚角钳制到 ±{@link #MAX_ROLL_DEG}，并消除 NaN / 无穷（NaN / Inf → 0）。 */
    public static double clampRoll(double value) {
        return clampAngle(value, MAX_ROLL_DEG);
    }

    /**
     * 通用角度钳制：NaN / 无穷归零，其余钳制到 ±{@code limit}。
     * <p>
     * 与 {@link #clampOffset(double)} 同样的写法（不用 {@code Math.min/max} 是为了让 NaN 分支显式可读）。
     * 注意 {@code Double.isInfinite} 必须在比较之前判掉：{@code +Inf > limit} 成立会返回 limit，
     * 虽然也安全，但语义上无穷更应该视作「无效输入」而清零。
     */
    private static double clampAngle(double value, double limit) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        if (value > limit) {
            return limit;
        }
        if (value < -limit) {
            return -limit;
        }
        return value;
    }

    // ==================== 外轨超高开关 + 半轨距（P4a：作用到轨道几何） ====================

    /**
     * 外轨超高开关是否开启。
     * <p>
     * <b>只门控滚转</b>：关闭时 {@code NodeConnector.readRailPose} 把该端点的
     * {@code roll*Degrees} 写成 0，几何内核的中心线抬升 {@code 半轨距·|sin(roll)|} 随之消失。
     * 纵坡（pitch）不受影响，照常驱动 Hermite 剖面。
     */
    public boolean isSuperelevationEnabled() {
        return superelevation;
    }

    /** 半轨距（米）：外轨超高抬升中心线时用的 {@code 半轨距·|sin(roll)|} 系数。 */
    public double getRollOffsetM() {
        return rollOffsetM;
    }

    /**
     * 写入外轨超高开关，只改数据并 {@code setChanged()}，<b>不</b>发包也不重建。
     * <p>
     * 开关会改变轨道几何（门控滚转贡献），所以调用方写完必须触发一次重建；
     * 配置界面把它与俯仰 / 翻滚 / 半轨距一起写好，再调 {@link #setAnglesAndSync(double, double)}
     * 发一次全量 BE_SYNC（{@link #writeC2S} 载荷含开关），最后走几何预检 + 重建。
     */
    public void setSuperelevation(boolean enabled) {
        if (this.superelevation == enabled) {
            return;
        }
        this.superelevation = enabled;
        this.setChanged();
        this.syncToPeer();
    }

    /**
     * 写入半轨距（米，自动钳制到 [{@value #MIN_HALF_GAUGE}, {@value #MAX_HALF_GAUGE}]），
     * 只改数据并 {@code setChanged()}，<b>不</b>发包也不重建。
     */
    public void setRollOffsetM(double metres) {
        final double clamped = clampHalfGauge(metres);
        if (clamped == rollOffsetM) {
            return;
        }
        this.rollOffsetM = clamped;
        this.setChanged();
        this.syncToPeer();
    }

    /**
     * 半轨距钳制：NaN / 无穷归默认值，其余钳制到 [{@value #MIN_HALF_GAUGE}, {@value #MAX_HALF_GAUGE}]。
     * <p>
     * 与 {@link #clampAngle(double, double)} 不同，非法输入这里退回
     * {@link RailPoseExtra#DEFAULT_HALF_GAUGE} 而不是 0：半轨距是「轨距的一半」这种物理尺寸，
     * 0 会让 {@code RailPoseExtra} 的构造器回退到默认值、与节点存储值不一致，语义上更混乱。
     */
    public static double clampHalfGauge(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return RailPoseExtra.DEFAULT_HALF_GAUGE;
        }
        if (value < MIN_HALF_GAUGE) {
            return MIN_HALF_GAUGE;
        }
        if (value > MAX_HALF_GAUGE) {
            return MAX_HALF_GAUGE;
        }
        return value;
    }

    /**
     * 当方向改变且已连接轨道时，刷新重建连接到本节点的轨道。
     * <p>
     * 该方法由客户端角度界面在确认新角度后调用：先收集连接到本节点的其他端点，
     * 再通过专用 C2S 包发送给服务端执行删除+重建。
     * <p>
     * 客户端 MTR 数据（{@code MinecraftClientData.positionsToRail}）可能尚未同步到位，
     * 此时不静默放弃，而是安排延迟重试（见 {@link #scheduleRetry} 与 {@link #whenRendering}）——
     * 否则服务端图里的轨道角度将永远滞留建轨值，寻路与曲线都不会更新。
     */
    public void refreshConnectedRailsIfNeeded() {
        if (level == null || !level.isClientSide) return;
        // 仅在已连接或可能存在轨道时刷新
        final java.util.List<net.minecraft.core.BlockPos> others = com.fangsu.util.NodeConnector.findConnectedEndpoints(worldPosition);
        if (others.isEmpty()) {
            // 客户端 MTR 数据未同步（找不到本节点端点）→ 延迟重试，不静默放弃
            Main.LOGGER.debug("[MultiDirectionNode] refresh skipped: no endpoints in client data at {}", worldPosition);
            scheduleRetry();
            return;
        }

        // 从客户端 MTR 数据读取连接到本节点的轨道，只保留数据已同步的端点；
        // 每个端点打包限速/形状/类型/样式属性，服务端据此按原属性重建（角度调整后外观与功能不丢失）
        final org.mtr.core.data.Position nodePosition = org.mtr.mod.Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(worldPosition));
        final var connections = org.mtr.mod.client.MinecraftClientData.getInstance().positionsToRail.get(nodePosition);
        final java.util.List<net.minecraft.core.BlockPos> connected = new java.util.ArrayList<>();
        if (connections != null) {
            for (net.minecraft.core.BlockPos o : others) {
                if (connections.containsKey(org.mtr.mod.Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(o)))) {
                    connected.add(o);
                }
            }
        }
        if (connected.isEmpty()) {
            // 端点过滤后为空（数据同步不全）→ 延迟重试，不静默放弃
            Main.LOGGER.debug("[MultiDirectionNode] refresh skipped: no synced endpoints at {}", worldPosition);
            scheduleRetry();
            return;
        }

        final net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(Unpooled.buffer());
        // ---- NODE_REFRESH_RAIL 载荷布局（客户端/服务端永远同版本，无需版本探测）----
        //   BlockPos nodePos
        //   double   direction
        //   double   offsetX / offsetY / offsetZ
        //   double   pitchDeg / rollDeg          ← P3 新增，紧跟平移之后
        //   boolean  superelevation              ← P4a 新增：外轨超高开关（只门控滚转）
        //   double   rollOffsetM                 ← P4a 新增：半轨距（米），滚转抬升系数
        //   boolean  directionBonded
        //   int      count
        //   count × { BlockPos otherPos, long speedAtNode, long speedAtOther, int shape,
        //             byte flags, int styleCount, styleCount × String }
        // 读侧：ModNetwork.handleNodeRefreshRail（字段顺序必须逐字对应）。
        buf.writeBlockPos(worldPosition);
        buf.writeDouble(direction);
        // 姿态（平移）随刷新请求一起发送，紧跟在 direction 之后。
        // 旧实现让平移走另一个 BE_SYNC 包，服务端重建轨道时从 BE 读偏移，
        // 于是重建结果取决于「两个独立包的到达/应用顺序」——这就是「拖了节点但轨道留在原地」的根因。
        // 现在刷新包里自带偏移，服务端用客户端刚编辑好的值重建，不再存在跨包竞态。
        buf.writeDouble(offsetX);
        buf.writeDouble(offsetY);
        buf.writeDouble(offsetZ);
        // P3：俯仰 / 翻滚也随刷新请求同行，服务端据此写进方块实体（节点模型倾斜）。
        // P4a：这两个角度现在会经 NodeConnector.readRailPose 进入轨道姿态（俯仰 → Hermite 纵坡剖面，
        // 翻滚 → 半轨距·|sin| 中心线抬升），所以刷新包里必须带上它们，否则服务端几何与界面不一致。
        buf.writeDouble(pitchDeg);
        buf.writeDouble(rollDeg);
        // P4a：外轨超高开关与半轨距同样随刷新请求同行、同样进入轨道姿态，理由同上。
        // 开关只门控滚转：服务端 readRailPose 会在开关为关时把 roll 端点值写成 0。
        buf.writeBoolean(superelevation);
        buf.writeDouble(rollOffsetM);
        // 方向是否绑定：旋转绑定开关为「否」时，服务端只按新方向重建几何，不得把方向绑定。
        buf.writeBoolean(directionBonded);
        buf.writeInt(connected.size());
        for (net.minecraft.core.BlockPos o : connected) {
            buf.writeBlockPos(o);
            final org.mtr.core.data.Rail rail = connections.get(org.mtr.mod.Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(o)));
            // 限速：m/ms × 3600 → km/h；按端点位置对号入座（单向轨 0 限速端跟随位置，core 内部处理 reversePositions）
            buf.writeLong(Math.round(rail.getSpeedLimitMetersPerMillisecond(nodePosition) * 3600));
            buf.writeLong(Math.round(rail.getSpeedLimitMetersPerMillisecond(org.mtr.mod.Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(o))) * 3600));
            buf.writeInt(rail.railMath.getShape().ordinal());
            final int flags = (rail.isPlatform() ? 1 : 0) | (rail.isSiding() ? 2 : 0) | (rail.canTurnBack() ? 4 : 0)
                    | (rail.canAccelerate() ? 8 : 0) | (rail.canConnectRemotely() ? 16 : 0);
            buf.writeByte(flags);
            final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList<String> styles = rail.getStyles();
            buf.writeInt(styles.size());
            for (String style : styles) {
                buf.writeUtf(style);
            }
        }
        dev.architectury.networking.NetworkManager.sendToServer(com.fangsu.network.ModNetwork.NODE_REFRESH_RAIL, buf);
        // 已成功发出刷新包，清除待重试状态
        pendingRefresh = false;
        retryCount = 0;
    }

    /**
     * 安排延迟重试：客户端 MTR 数据未同步时每 {@value #RETRY_INTERVAL_MS} ms 重试一次，
     * 最多 {@value #MAX_RETRY} 次，仍失败则放弃并打 warn 日志（用户可再次保存触发）。
     * 重试在 {@link #whenRendering}（客户端渲染主线程，每帧调用）中执行，网络发送天然在主线程。
     */
    private void scheduleRetry() {
        if (level == null || !level.isClientSide) return;
        if (retryCount >= MAX_RETRY) {
            pendingRefresh = false;
            retryCount = 0;
            Main.LOGGER.warn("[MultiDirectionNode] refresh retry exhausted at {}, rail angles NOT updated on server", worldPosition);
            return;
        }
        pendingRefresh = true;
        retryCount++;
        nextRetryTime = System.currentTimeMillis() + RETRY_INTERVAL_MS;
    }

    // ==================== 网络同步 ====================

    /** 客户端→服务端：发送当前方向/绑定状态。 */
    public void sendUpdateC2S() {
        if (level != null && level.isClientSide) {
            if (level.hasChunk(getBlockPos().getX() >> 4, getBlockPos().getZ() >> 4)) {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeBlockPos(getBlockPos());
                writeC2S(buf);
                NetworkManager.sendToServer(ModNetwork.BE_SYNC, buf);
            }
        }
        this.setChanged();
    }

    /** 服务端→客户端（在服务端 readC2S 后主动推送方块更新）。 */
    private void syncToPeer() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    public void writeC2S(FriendlyByteBuf buf) {
        // ---- v1 段（布局冻结，不可增删改）----
        buf.writeDouble(direction);
        buf.writeBoolean(connected);
        buf.writeBoolean(directionBonded);
        // ---- v2 段（追加字段）----
        // 追加而非插入：旧发送方只写 v1，接收方按剩余字节数判断，双方都不读串位。
        buf.writeDouble(offsetX);
        buf.writeDouble(offsetY);
        buf.writeDouble(offsetZ);
        // ---- v3 段（P3 追加字段）----
        // 与 v2 同样只在尾部追加：v1 / v2 发送方的负载总长分别比 v3 少 40 / 16 字节
        // （v1 / v2 / v3 依次为 10 / 34 / 50 字节），
        // 接收侧两段检查（>= 24、>= 16）依次失败，两个角度保持原值（不静默清零）。
        buf.writeDouble(pitchDeg);
        buf.writeDouble(rollDeg);
        // ---- v4 段（P4a 追加字段）----
        // 同样只在尾部追加：v1 / v2 / v3 发送方的负载总长分别比 v4 少 49 / 25 / 9 字节
        // （v1 / v2 / v3 / v4 依次为 10 / 34 / 50 / 59 字节），
        // 接收侧逐段检查（>= 24、>= 16、>= 9）依次失败，新增的两个字段保持原值（不静默清零）。
        buf.writeBoolean(superelevation);
        buf.writeDouble(rollOffsetM);
        // 版本号本身不写进流（写了会让旧接收方把版本字节当成 direction 的首字节）；
        // C2S_PAYLOAD_VERSION 只在代码内标记当前布局，真实判据是接收侧的字节数检查。
    }

    @Override
    public void readC2S(FriendlyByteBuf buf) {
        // 方向是周期量：这里回绕而不是钳制（硬边界说明见 wrapDirectionDegrees）
        this.direction = wrapDirectionDegrees(buf.readDouble());
        this.connected = buf.readBoolean();
        this.directionBonded = buf.readBoolean();
        // v2：3 个 double = 24 字节。v1 发送方这里剩余 0 字节，直接跳过，偏移保持原值（不静默清零）。
        if (buf.readableBytes() >= 24) {
            this.offsetX = clampOffset(buf.readDouble());
            this.offsetY = clampOffset(buf.readDouble());
            this.offsetZ = clampOffset(buf.readDouble());
        }
        // v3：再 2 个 double = 16 字节。v1 发送方读到这里是 0 字节、v2 发送方是 0 字节（v2 已被上面消费完），
        // 都直接跳过；只有 v3 发送方才读写俯仰 / 翻滚。判据同样是剩余字节数，不依赖包内版本号。
        if (buf.readableBytes() >= 16) {
            this.pitchDeg = clampPitch(buf.readDouble());
            this.rollDeg = clampRoll(buf.readDouble());
        }
        // v4：再 1 个 boolean + 1 个 double = 9 字节。v1 / v2 / v3 发送方读到这里都是 0 字节，
        // 直接跳过；只有 v4 发送方才读写外轨超高开关与半轨距。判据同样是剩余字节数。
        if (buf.readableBytes() >= 9) {
            this.superelevation = buf.readBoolean();
            this.rollOffsetM = clampHalfGauge(buf.readDouble());
        }
        this.setChanged();
        syncToPeer();
    }

    // ==================== NBT 持久化 ====================

    @Override
    public void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        if (markedError) return;
        tag.putDouble(KEY_DIRECTION, direction);
        tag.putBoolean(KEY_CONNECTED, connected);
        tag.putBoolean(KEY_DIRECTION_BONDED, directionBonded);
        // 与 direction 不同，平移分量总是写：客户端区块加载时要靠它渲染偏移后的模型。
        // 全零写入的开销可忽略，换来的是"读侧不需要判空"。
        tag.putDouble(KEY_OFFSET_X, offsetX);
        tag.putDouble(KEY_OFFSET_Y, offsetY);
        tag.putDouble(KEY_OFFSET_Z, offsetZ);
        // 同理总是写俯仰 / 翻滚：客户端区块加载后节点模型要靠它倾斜
        tag.putDouble(KEY_PITCH_DEG, pitchDeg);
        tag.putDouble(KEY_ROLL_DEG, rollDeg);
        // P4a：外轨超高开关与半轨距也总是写 —— 客户端区块加载后轨道几何与界面都要靠它们，
        // 且「总是写」让读侧不必判空（与平移/角度一致）。
        tag.putBoolean(KEY_SUPERELEVATION, superelevation);
        tag.putDouble(KEY_ROLL_OFFSET_M, rollOffsetM);
    }

    @Override
    public void load(@NotNull CompoundTag tag) {
        super.load(tag);
        markedError = false;
        this.direction = wrapDirectionDegrees(tag.getDouble(KEY_DIRECTION));
        this.connected = tag.getBoolean(KEY_CONNECTED);
        this.directionBonded = tag.getBoolean(KEY_DIRECTION_BONDED);
        // 老存档没有这三个键 → getDouble 返回 0（原版行为）
        this.offsetX = clampOffset(tag.getDouble(KEY_OFFSET_X));
        this.offsetY = clampOffset(tag.getDouble(KEY_OFFSET_Y));
        this.offsetZ = clampOffset(tag.getDouble(KEY_OFFSET_Z));
        // 老存档（P3 之前）没有这两个键 → getDouble 返回 0，再经钳制仍是 0
        this.pitchDeg = clampPitch(tag.getDouble(KEY_PITCH_DEG));
        this.rollDeg = clampRoll(tag.getDouble(KEY_ROLL_DEG));
        // P4a：外轨超高开关<b>默认 true</b>，而 CompoundTag.getBoolean 对缺失键返回 false，
        // 直接用会把所有老存档静默改成「关闭超高」，所以先用 contains 判断再取值。
        this.superelevation = !tag.contains(KEY_SUPERELEVATION) || tag.getBoolean(KEY_SUPERELEVATION);
        // 半轨距缺失（P4a 之前的存档）→ 退回标准轨距的一半，而不是 0（0 会被 RailPoseExtra 兜成默认值）
        this.rollOffsetM = tag.contains(KEY_ROLL_OFFSET_M)
                ? clampHalfGauge(tag.getDouble(KEY_ROLL_OFFSET_M))
                : RailPoseExtra.DEFAULT_HALF_GAUGE;
        triggerAsyncLoading();
    }

    // ==================== 生命周期 ====================

    protected final void triggerAsyncLoading() {
        cancelPendingAsyncLoading();
        loadingFuture = CompletableFuture.runAsync(() -> {
            try {
                this.whenLoading();
            } catch (Exception e) {
                Main.LOGGER.error("Failed to load block entity {} at {}", getClass().getSimpleName(), getBlockPos(), e);
            }
        }, LOADING_EXECUTOR);
    }

    protected final void cancelPendingAsyncLoading() {
        if (loadingFuture != null && !loadingFuture.isDone()) {
            loadingFuture.cancel(true);
        }
        loadingFuture = null;
    }

    protected final boolean isAsyncLoadingDone() {
        return loadingFuture == null || loadingFuture.isDone();
    }

    /**
     * 渲染就绪条件：本实例的模型异步加载已完成。
     * 异步渲染的调度已统一由 {@link BaseObjBlockEntity} 提供，原先各自复制的
     * tryBeginRendering/finishRendering 已删除（与基类 final 方法冲突，且从未被调用）。
     */
    @Override
    protected boolean isRenderReady() {
        return isAsyncLoadingDone();
    }

    public void whenLoading() {
        // 仅客户端加载模型
        if (level == null || !level.isClientSide) return;
        ensureModelReady();
    }

    /**
     * 确保模型已加载（同步加载 + uploadLater，照 BlockEntityRotatingRail 的 ensureModelReady 模式）。
     * 同时加载默认节点模型和已连接节点模型。
     */
    private void ensureModelReady() {
        // 默认模型（node.obj）
        if (modelHolder == null || modelHolder.getUploadedModel() == null) {
            Main.LOGGER.info("[MultiDirectionNode] ensureModelReady (default) at {}", worldPosition);
            try {
                final com.fangsu.render.sowcerext.model.RawModel rawModel = ResourceUtil.loadModel(new ResourceLocation(DEFAULT_MODEL), false);
                if (rawModel != null) {
                    if (modelHolder == null) {
                        modelHolder = new DynamicModelHolder();
                    }
                    modelHolder.uploadLater(rawModel);
                    modelLoadingFailed = false;
                    Main.LOGGER.info("[MultiDirectionNode] default model queued for upload at {}", worldPosition);
                }
            } catch (Exception e) {
                Main.LOGGER.warn("[MultiDirectionNode] Failed to load model {}: {}", DEFAULT_MODEL, e.getMessage(), e);
                modelLoadingFailed = true;
            }
        }

        // 已连接模型（node_connected.obj）
        if (connectedModelHolder == null || connectedModelHolder.getUploadedModel() == null) {
            Main.LOGGER.info("[MultiDirectionNode] ensureModelReady (connected) at {}", worldPosition);
            try {
                final com.fangsu.render.sowcerext.model.RawModel rawModel = ResourceUtil.loadModel(new ResourceLocation(CONNECTED_MODEL), false);
                if (rawModel != null) {
                    if (connectedModelHolder == null) {
                        connectedModelHolder = new DynamicModelHolder();
                    }
                    connectedModelHolder.uploadLater(rawModel);
                    Main.LOGGER.info("[MultiDirectionNode] connected model queued for upload at {}", worldPosition);
                }
            } catch (Exception e) {
                Main.LOGGER.warn("[MultiDirectionNode] Failed to load model {}: {}", CONNECTED_MODEL, e.getMessage(), e);
            }
        }
    }

    /**
     * 判断玩家手中是否持有轨道相关物品（轨道连接器、刷子、轨道节点方块）。
     * 与原版 MTR {@code RenderRails.isHoldingRailRelated} 行为一致。
     */
    private static boolean isHoldingRailRelated(net.minecraft.world.entity.player.Player player) {
        return isRailRelatedItem(player.getMainHandItem())
                || isRailRelatedItem(player.getOffhandItem());
    }

    private static boolean isRailRelatedItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        final Item item = stack.getItem();
        return item instanceof org.mtr.mod.item.ItemNodeModifierBase
                || item instanceof org.mtr.mod.item.ItemBrush
                || Block.byItem(item) instanceof org.mtr.mod.block.BlockNode
                || Block.byItem(item) instanceof com.fangsu.blocks.BlockMultiDirectionNode;
    }

    /**
     * 节点模型的倾斜约定（P3 起；P4a 把同一批角度接进轨道几何，约定必须严丝合缝地沿用）。
     * <p>
     * <b>角度定义</b>：
     * <ul>
     *   <li>{@code pitchDeg}（俯仰 / 纵坡）= 节点处轨道切线的竖向坡角，
     *       <b>正 = 沿节点方向前进时上坡（爬升）</b>；范围 ±{@value #MAX_PITCH_DEG}°。</li>
     *   <li>{@code rollDeg}（翻滚 / 外轨超高）= 绕节点<b>前进轴</b>的旋转，
     *       <b>正 = 前进方向的右手侧抬高</b>；范围 ±{@value #MAX_ROLL_DEG}°。
     *       与 {@code RailPoseExtra.roll1Degrees/roll2Degrees} 以及几何内核
     *       {@code rollLift = 半轨距·|sin(roll)|} 的约定一致。</li>
     * </ul>
     * <b>局部坐标与朝向</b>：{@code node.obj} 是沿局部 X 轴拉长的横杆（局部 X 长 1 格、Z 厚 0.25 格），
     * 而本类给模型施加的偏航是 {@code rotation = -direction + π/2}。把局部 +X / +Z 过一遍该偏航：
     * <pre>
     *   yaw(rotation) · (1,0,0) = (sin θ, 0, −cos θ)   // θ = direction（0=E、90=S、180=W、270=N）
     *   yaw(rotation) · (0,0,1) = (cos θ, 0,  sin θ)   // = 节点前进方向（轨道切线）d
     * </pre>
     * 其中 θ = {@code direction}（0=E、90=S）。对 θ=0 得 yaw·(0,0,1) = (1,0,0) → 东 = 前进方向，
     * yaw·(1,0,0) = (0,0,−1) → 北 = 东向的<b>左侧</b>；也就是说<b>局部 +Z 才是前进轴、局部 +X 指左侧</b>
     * （世界右手侧 = (−sin θ, 0, cos θ) = d × 上 = −(局部 +X 的世界像)）。
     * 所以翻滚只能绕局部 Z（前进轴）旋转，俯仰只能绕局部 X（横向轴）旋转 —— 这正是下面两次旋转轴的来源。
     * <p>
     * <b>施加顺序</b>（对 {@code Matrices} 依次调用；{@code rotateX/Y/Z} 是右乘 = 在「当前模型坐标系」里内旋，
     * 因此先偏航定朝向，之后的两次旋转都发生在朝向坐标系内）：
     * <pre>
     *   translate(offset)          // 节点平移 g = (offsetX, offsetY, offsetZ)
     *   rotateY(yaw)               // 偏航 = 方向：R_y(ψ)，ψ = −direction + π/2
     *   rotateZ(−rollRad)          // 翻滚：R_z(ρ)，ρ = −rollDeg（正 rollDeg = 右手侧抬高）
     *   rotateX(−pitchRad)         // 俯仰：R_x(φ)，φ = −pitchDeg
     * </pre>
     * 整体合成 {@code M = T(g) · R_y(ψ) · R_z(ρ) · R_x(φ)} = {@code T · R_yaw · R_roll · R_pitch}，
     * 即世界语义下<b>先 yaw、再 roll、最后 pitch</b>。为什么这两个符号能对上定义，逐个验算：
     * <pre>
     * 前进方向（切线）t = R_y(ψ)·(0,0,1)                          = (cos θ, 0,  sin θ)   // θ = direction
     * 1) 翻滚 R_z(ρ)：R_z(ρ)·(1,0,0) = (cos ρ, sin ρ, 0)，即局部 +X（= 前进方向的左侧）
     *    在 ρ &gt; 0 时被抬起；局部 +Z 是转轴、不动
     *    → 世界语义下 ρ &gt; 0 抬升的是前进方向的<b>左侧</b>，与「正 = 右手侧抬高」相反
     *    → 故取 ρ = −rollDeg，正 rollDeg = 右手侧抬高 ✔ 与 rollDeg 定义一致
     *      （JOML 数值验算：θ=0/45/90/180/270、roll=+10° 时 R_z(+ρ) 一律抬左、
     *        R_z(−ρ) 一律抬右，且与 railFrameAngle 的 angle = −roll 同侧）
     * 2) 俯仰 R_x(φ)：R_x(φ)·(0,0,1) = (0, −sin φ, cos φ)（局部 ±X 是转轴）
     *    前进方向 t 的 Y 分量 = −sin φ
     *    → 只有 φ &lt; 0 才让前进方向抬头 = 节点朝前进方向抬头 = 上坡
     *    → 故取 φ = −pitchDeg，正 pitchDeg = 上坡 ✔
     * </pre>
     * 顺带说明：前进方向的右手侧 = −(局部 +X 的世界像) = 局部 −X，所以翻滚「抬高右手侧」
     * 等价于「抬高模型的 −X 面」，下一步在轨道截面上做外轨超高时用的就是同一条右手侧法向。
     * <p>
     * <b>P4a</b>：以上倾斜只作用于节点自己的标记模型（视觉）；轨道<b>几何</b>上的等效效果由
     * {@code NodeConnector.readRailPose} 把同一批角度写进 {@code RailPoseExtra} 实现 ——
     * 俯仰角喂给内核的三次 Hermite 竖向剖面，滚转角（外轨超高开关开启时）喂给
     * {@code 半轨距·|sin(roll)|} 中心线抬升。轨道截面与车体的视觉倾斜仍是下一步（P4b）。
     */
    private void applyNodeModelTilt(Matrices mat) {
        if (!hasTilt()) {
            // 无倾斜时不发旋转调用，保持与 P2 逐字节相同的矩阵（便于回归对比）
            return;
        }
        // 翻滚：约定「正 = 前进方向右手侧抬高」（railFrameAngle / RailRollRenderHelper、
        // RailPoseExtra 与界面文案「翻滚角（右侧抬高为正，度）」都按这条约定）。
        // 局部 +X 是前进方向的左侧，rotateZ(+roll) 抬的却是它 → 必须取 −rollDeg 才抬右手侧。
        // 该符号已用 JOML 数值验算（θ=0/45/90/180/270，roll=+10°），请勿改回正号。
        mat.rotateZ((float) -Math.toRadians(rollDeg));
        mat.rotateX((float) -Math.toRadians(pitchDeg));
    }

    @Override
    public void whenRendering() {
        // 客户端 MTR 数据就绪后的延迟重试：数据未同步时角度刷新顺延到数据到位后执行
        if (pendingRefresh && System.currentTimeMillis() >= nextRetryTime) {
            refreshConnectedRailsIfNeeded();
        }
        ObjBlockScriptContext ctx = this.scriptContext;
        if (ctx == null) return;
        // 若尚未加载/未上传成功，确保加载
        ensureModelReady();

        // 已连接时：默认隐藏模型，仅手持轨道连接器/刷子/节点方块时显示 node_connected.obj
        if (connected) {
            if (level != null && level.isClientSide) {
                // 通过 ClientHooks 获取本地玩家：此类会被服务器加载（ModBlocks 静态注册），
                // 直接引用 net.minecraft.client.Minecraft 会导致服务器端类加载崩溃
                final Player player = ClientHooks.getLocalPlayer();
                if (player != null && isHoldingRailRelated(player)) {
                    // 手持轨道相关物品 → 显示已连接模型
                    final DynamicModelHolder holder = connectedModelHolder;
                    if (holder != null && holder.getUploadedModel() != null) {
                        // 原点平移已由 BaseBlockEntityRender 统一施加（candyPose.translate(0.5, 0, 0.5)），
                        // 这里不能再平移半格，否则模型会再偏移半格到方块角落；node_connected.obj 本身以原点为中心。
                        // 节点平移只叠加用户设置的 offset（不加 0.5）。
                        Matrices mat = new Matrices();
                        mat.translate(offsetX, offsetY, offsetZ);
                        // 已连接时方向必定已绑定，按固定方向渲染（与 MTR renderNode rotateYDegrees(-angle) 对齐）
                        final double rotation = -Math.toRadians(direction) + Math.PI / 2;
                        mat.rotateY((float) rotation);
                        // P3：俯仰 / 翻滚（约定与推导见 applyNodeModelTilt），偏航之后在内旋坐标系里施加
                        applyNodeModelTilt(mat);
                        ctx.drawModel(holder, mat);
                    }
                }
                // 未手持轨道相关物品 → 不渲染（默认隐藏）
            }
            return;
        }

        // 未连接：渲染默认 node.obj 模型（旋转/固定）
        final DynamicModelHolder holder = modelHolder;
        if (holder == null || holder.getUploadedModel() == null) {
            return;
        }

        // 原点平移已由 BaseBlockEntityRender 统一施加（candyPose.translate(0.5, 0, 0.5)），
        // 这里不能再平移半格：否则叠加后模型会整体偏移半格，落在方块的角落而不是中心。
        // node.obj / node_connected.obj 的几何本身以原点为旋转中心（X -0.5..0.5，Y 0..1），
        // 因此直接在原点处绕 Y 轴旋转即可保证模型始终居于方块中央。
        Matrices mat = new Matrices();
        // 节点平移：只叠加用户设置的 offset（不含 0.5，那部分由 BaseBlockEntityRender 负责）
        mat.translate(offsetX, offsetY, offsetZ);

        final double rotation;
        if (!directionBonded) {
            // 未绑定：绕 Y 轴匀速 360° 旋转（2 秒一圈）
            rotation = (System.currentTimeMillis() % 2000) / 2000.0 * (Math.PI * 2);
        } else {
            // 与 MTR 节点显示一致：Angle 为顺时针罗盘角，rotateY 取负（renderNode 用 rotateYDegrees(-angle)）
            rotation = -Math.toRadians(direction) + Math.PI / 2;
        }
        mat.rotateY((float) rotation);
        // P3：俯仰 / 翻滚（约定与推导见 applyNodeModelTilt）。未绑定时也跟着一起倾斜：
        // 角度是节点数据而非方向数据，解绑只影响「绕 Y 轴是否继续旋转」。
        applyNodeModelTilt(mat);

        ctx.drawModel(holder, mat);
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        // 仅客户端需要模型：服务端同步加载 OBJ（文件 IO + 解析）会阻塞主线程，
        // 放置大量节点（如 20×20×20）时导致服务器严重卡顿
        if (level == null || !level.isClientSide) return;
        ensureModelReady();
    }

    public void whenDisposing() {
        if (modelHolder != null) {
            modelHolder.close();
            modelHolder = null;
        }
        if (connectedModelHolder != null) {
            connectedModelHolder.close();
            connectedModelHolder = null;
        }
    }

    @Override
    public void setRemoved() {
        if (!disposed) {
            whenDisposing();
            cancelPendingAsyncLoading();
        }
        super.setRemoved();
    }

    // ==================== 交互 ====================

    @Override
    public InteractionResult useWithWrench(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player, @NotNull InteractionHand hand, @NotNull BlockHitResult hit) {
        if (level.isClientSide) {
            // 扳手右键：打开 FangSu 万向节点配置界面（平移 / 方向 / 轨道编辑）
            ClientHooks.openMultiDirectionNodeConfig(this);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * 刷子右键：与 MTR 原版 {@code org.mtr.mod.block.BlockNode#onUse2}（客户端持刷子分支）保持一致，
     * 打开 MTR 自带的轨道形状/功能修改界面（{@code RailShapeModifierScreen}）。
     * <p>
     * 原版逻辑（4.0.5 字节码）：
     * <pre>
     * if (world.isClient() &amp;&amp; player.isHolding(Items.BRUSH.get())) {
     *     final ObjectObjectImmutablePair&lt;Rail, BlockPos&gt; pair =
     *             MinecraftClientData.getInstance().getFacingRailAndBlockPos(false);
     *     if (pair == null) return ActionResult.FAIL;
     *     ClientPacketHelper.openRailShapeModifierScreen(pair.left().getHexId());
     *     return ActionResult.SUCCESS;
     * }
     * return ActionResult.FAIL;
     * </pre>
     * 注意 {@code Rail.getHexId()} 是 4.0.5 里 {@code TwoPositionsBase} 的 public 方法
     * （{@code javap org.mtr.core.data.TwoPositionsBase} 可验证），传的是轨道 id 而不是轨道对象，
     * 因为界面是在客户端另开屏、由服务端数据驱动。
     */
    @Override
    public InteractionResult whenUseWithBrush(Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            // 视线追踪面对的轨道（与原版完全一致）
            final var railAndBlockPos = org.mtr.mod.client.MinecraftClientData.getInstance().getFacingRailAndBlockPos(false);
            if (railAndBlockPos != null) {
                org.mtr.mod.packet.ClientPacketHelper.openRailShapeModifierScreen(railAndBlockPos.left().getHexId());
                return InteractionResult.SUCCESS;
            }
            // ---- 以下为 FangSu 便利回退，MTR 原版没有这段 ----
            // 已连接时，本节点的判定盒（0.1~0.9 的薄片，见 setShape）可能先被点击命中，
            // 使原版的视线追踪拿不到轨道；此时直接从 MTR 客户端数据里取连接到此节点的第一条轨道。
            if (connected) {
                final var connections = org.mtr.mod.client.MinecraftClientData.getInstance()
                        .positionsToRail.get(org.mtr.mod.Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(pos)));
                if (connections != null && !connections.isEmpty()) {
                    org.mtr.mod.packet.ClientPacketHelper.openRailShapeModifierScreen(connections.values().iterator().next().getHexId());
                    return InteractionResult.SUCCESS;
                }
            }
        }
        // 与 MTR 原版一致：未命中轨道时返回 FAIL
        return InteractionResult.FAIL;
    }

    // ==================== BaseObjBlockEntity 抽象方法 ====================

    @Override
    public String getMainModelKey() {
        return "multiDirectionNode";
    }

    @Override
    public VoxelShape setCollisionShape(BlockState state) {
        // 与原版 MTR 节点一致：不参与碰撞
        return Shapes.empty();
    }

    @Override
    public VoxelShape setShape(BlockState state) {
        // 已连接时形状极薄，与原版 MTR 节点一致，避免阻挡玩家视线追踪轨道
        final VoxelShape base = connected
                ? Shapes.box(0.1, 0, 0.1, 0.9, 0.0625, 0.9)
                : Shapes.block();
        return shiftIntoBlock(base);
    }

    /**
     * 把点击判定盒按节点平移整体挪动，并钳制在宿主方块内（{@code [0,1]}）。
     * <p>
     * 逐轴取「不越出方块」的最大可用位移：整块形状（未连接时的 {@code Shapes.block()}）本身
     * 已经占满方块，任何方向都挪不动，因此保持不变；连接后的薄片可以跟随 ±0.5 以内的偏移。
     * 这样点击判定与视觉上的节点位置一致，又不会把 AABB 伸进相邻方块。
     */
    private VoxelShape shiftIntoBlock(VoxelShape base) {
        if (!hasOffset()) {
            return base;
        }
        final net.minecraft.world.phys.AABB bounds = base.bounds();
        final double dx = clampShift(offsetX, bounds.minX, bounds.maxX);
        final double dy = clampShift(offsetY, bounds.minY, bounds.maxY);
        final double dz = clampShift(offsetZ, bounds.minZ, bounds.maxZ);
        if (dx == 0.0D && dy == 0.0D && dz == 0.0D) {
            return base;
        }
        return base.move(dx, dy, dz);
    }

    /** 单轴可用位移：{@code [min, max]} 是形状在当前轴上的范围，结果保证形状仍落在 [0,1] 内。 */
    private static double clampShift(double requested, double min, double max) {
        final double lower = -min;
        final double upper = 1.0D - max;
        if (requested < lower) {
            return lower;
        }
        if (requested > upper) {
            return upper;
        }
        return requested;
    }
}
