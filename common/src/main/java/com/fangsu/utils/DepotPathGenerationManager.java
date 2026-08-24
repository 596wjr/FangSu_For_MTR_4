package com.fangsu.utils;

import com.fangsu.Main;
import com.fangsu.mixin.SidingPathFinderAccessorMixin;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.SavedRailBase;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

/**
 * 车厂主路径生成的异步调度器：把 TSC 原版「每模拟 tick 最多 5ms」的节流寻路
 * （{@code SidingPathFinder.findPathTick → Utilities.loopUntilTimeout(..., 5)}）
 * 改为方速独立线程全速执行，模拟线程只负责提交任务与回执完成回调。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>单线程 daemon 工作线程串行执行所有车厂任务，不占模拟线程 tick；</li>
 *   <li>工作线程只碰任务快照（finder 队列副本 + 本地结果路径），不跨线程写车厂状态；
 *       完成回执在下次 {@link Depot#tick()}（模拟线程）里 apply，等价原版回调所在线程，
 *       写 {@code depot.path}、{@code PathData.writePathCache}、发 S2C 的语义不变；</li>
 *   <li>重按刷新时 {@code generateMainRoute} 会重建 finder 队列，快照首元素引用不同即
 *       判定为新一代任务，旧任务 discarded 后其回执不落盘；</li>
 *   <li>寻路读 {@code data.positionsToRail} 等网络图 map，与模拟线程并发修改的窗口与
 *       MTR3 原版独立线程寻路一致：fastutil 无 fail-fast，可能漏路径或（罕见）抛
 *       {@code ConcurrentModificationException}——关闭车厂 UI 时服务端 {@code data.sync}
 *       重建网络图即会触发。此类瞬时异常在 {@link Task#run} 中对同段有界重试
 *       （{@link #MAX_SEGMENT_RETRIES} × {@link #RETRY_SLEEP_MS}）而非直接判失败，
 *       避免「刷新时关闭 UI 有概率导致刷新直接失败」；仅重试耗尽（网络被持续修改）
 *       或超时才按失败回执。</li>
 * </ul>
 * 侧线级寻路（{@code Siding.tick}）仍走原版 5ms 节流，侧线路径短通常 1~3 tick 完成，
 * 非主路径生成瓶颈，不在本类范围。
 */
public final class DepotPathGenerationManager {

    /** 单任务超时上限：防寻路死循环拖住工作线程（30 分钟），超时按失败回执。 */
    private static final long TASK_TIMEOUT_MS = 30 * 60 * 1000L;

    /** 单段寻路遇到瞬时并发异常（如关闭车厂 UI 触发 {@code data.sync} 重建网络图）时的重试次数上限。 */
    private static final int MAX_SEGMENT_RETRIES = 6;

    /** 每次重试前的等待时长：让并发的网络图重建（{@code data.sync}）完成后再读同段。 */
    private static final long RETRY_SLEEP_MS = 30L;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "fangsu-depot-pathgen");
        thread.setDaemon(true);
        return thread;
    });

    /** 进行中的任务，按车厂对象为键（多维度多 Simulator 场景互不冲突）。 */
    private static final ConcurrentHashMap<Depot, Task> TASKS = new ConcurrentHashMap<>();

    private DepotPathGenerationManager() {
    }

    /** 工作线程池是否可用；executor 被关闭（服务器停机）时调用方应走原版节流路径。 */
    public static boolean isAvailable() {
        return !WORKER.isShutdown();
    }

    /**
     * 由 {@code Depot.tick()} 注入点（{@link com.fangsu.mixin.DepotTickAsyncGenerationMixin}）
     * 每次 tick 调用：无任务则提交，任务完成则在模拟线程 apply 回调。
     */
    public static void handleDepotTick(Depot depot, ObjectArrayList<PathData> path, ObjectArrayList<?> sidingPathFinders,
                                       long cruisingAltitude, Runnable callbackSuccess, BiConsumer<?, ?> callbackFail) {
        if (sidingPathFinders.isEmpty()) {
            return;
        }
        Task task = TASKS.get(depot);
        if (task == null || task.isDiscarded()
                || task.firstFinder != sidingPathFinders.get(0)) {
            // 新任务 / 重按刷新后的新一代（generateMainRoute 重建 finder，首元素引用不同）。
            // 注意：不能读 task.snapshot（worker 线程全速 remove 中的可变队列，读它存在
            // 「worker 已 remove 空、尚未置 finished」的竞态窗口，会 IndexOutOfBounds 崩服）
            if (task != null) {
                task.discard();
            }
            task = new Task(sidingPathFinders, cruisingAltitude, callbackSuccess, callbackFail);
            TASKS.put(depot, task);
            try {
                WORKER.submit(task::run);
            } catch (RejectedExecutionException e) {
                // executor 正在关闭：跳过本次提交，finders 队列仍由原版消费
                TASKS.remove(depot);
                return;
            }
        }
        // 回执：任务已完成 → 在模拟线程（原版回调所在线程）apply
        task = TASKS.get(depot);
        if (task != null && task.isFinished()) {
            if (!task.isDiscarded()) {
                path.clear();
                path.addAll(task.getResultPath());
                sidingPathFinders.clear();
                if (task.isSuccess()) {
                    task.getSuccessCallback().run();
                } else {
                    task.getFailCallback().accept(task.getFailStart(), task.getFailEnd());
                }
            }
            TASKS.remove(depot);
        }
    }

    /** 单个车厂的寻路任务：worker 线程全速执行，回执状态用 volatile 发布给模拟线程。 */
    private static final class Task {

        /** finder 队列快照：worker 只碰快照，模拟线程的队列由原版语义继续消费/重建。 */
        final ObjectArrayList<?> snapshot;
        /** 提交时刻快照首元素（不可变引用）：模拟线程据此检测重按刷新，绝不并发读可变快照。 */
        final Object firstFinder;
        private final long cruisingAltitude;
        private final Runnable successCallback;
        private final BiConsumer<Object, Object> failCallback;
        /** 本地累积结果路径：完成后由模拟线程回填进 depot.path。 */
        private final ObjectArrayList<PathData> resultPath = new ObjectArrayList<>();
        private final long startTime = System.currentTimeMillis();
        private volatile boolean finished;
        private volatile boolean success;
        private volatile boolean discarded;
        private volatile SavedRailBase<?, ?> failStart;
        private volatile SavedRailBase<?, ?> failEnd;

        Task(ObjectArrayList<?> snapshot, long cruisingAltitude, Runnable successCallback, BiConsumer<?, ?> failCallback) {
            this.snapshot = new ObjectArrayList<>(snapshot);
            this.firstFinder = this.snapshot.get(0);
            this.cruisingAltitude = cruisingAltitude;
            this.successCallback = successCallback;
            // 捕获通配符无法直接传参，raw cast 后按 Object 存储（运行时类型擦除，accept 桥接语义不变）
            @SuppressWarnings("unchecked")
            final BiConsumer<Object, Object> castedFailCallback = (BiConsumer<Object, Object>) failCallback;
            this.failCallback = castedFailCallback;
        }

        /** worker 线程入口：等价 findPathTick 的循环体，但无 5ms 节流，全速逐段寻路。 */
        void run() {
            while (!snapshot.isEmpty()) {
                if (discarded) {
                    return; // 新一代任务已接管，静默放弃
                }
                if (System.currentTimeMillis() - startTime > TASK_TIMEOUT_MS) {
                    Main.LOGGER.warn("[方速] 车厂寻路超时（>{} 分钟），按失败回执", TASK_TIMEOUT_MS / 60000);
                    fail((SidingPathFinder<?, ?, ?, ?>) snapshot.get(0));
                    return;
                }
                final SidingPathFinder<?, ?, ?, ?> finder = (SidingPathFinder<?, ?, ?, ?>) snapshot.get(0);
                // tick() 读 data.positionsToRail 等共享网络图 map，与模拟/网络线程并发重建
                // （典型触发：关闭车厂 UI 时 onClose→saveData 发 PacketUpdateData，服务端
                // UpdateDataRequest.update 调 data.sync 重建 positionsToRail）冲突会抛瞬时
                // ConcurrentModificationException。这种异常是瞬时的，重试同段（有界）而非
                // 直接判失败，避免「刷新线路时关闭 UI 有概率导致刷新直接失败」。
                // 说明：findPath() 在 getConnections 抛异常前只部分写入 globalBlacklist（写入的
                // 键值与重试时的 elapsedTime 相同），重试不会破坏 A* 状态，最多让个别节点提前
                // 视为已访问，不影响正确性；仅当重试耗尽（网络图被持续修改）或超时才按失败回执。
                // 不能声明 final：赋值发生在 while(true) 循环体内，javac 会误判
                // 「可能在 loop 中重复赋值」（虽然实际只 break 时赋一次）
                ObjectArrayList<PathData> tempPath;
                int retries = 0;
                while (true) {
                    try {
                        // SidingPathFinder 为 final class，javac 拒绝直接 cast 到编译期不可见的接口，
                        // 需先经 Object；运行时接口由 mixin 注入，cast 与调用均正常
                        tempPath = ((SidingPathFinderAccessorMixin) (Object) finder).fangsu$invokeTick(cruisingAltitude);
                        break;
                    } catch (Throwable t) {
                        retries++;
                        if (discarded) {
                            return; // 新一代任务已接管，静默放弃（不写回执）
                        }
                        if (retries > MAX_SEGMENT_RETRIES) {
                            // 寻路期间网络图被并发修改等且重试仍失败：视为该段失败（与 MTR3 原版异常→回 0 语义一致）
                            Main.LOGGER.warn("[方速] 车厂寻路段持续异常（可能寻路期间网络被修改）: {} -> {}", finder.startSavedRail, finder.endSavedRail, t);
                            fail(finder);
                            return;
                        }
                        try {
                            // 短暂让出，给并发的网络图重建（data.sync）让出窗口
                            Thread.sleep(RETRY_SLEEP_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            fail(finder);
                            return;
                        }
                    }
                }
                if (tempPath == null) {
                    continue; // 该段仍在迭代，全速旋转至出结果
                }
                if (tempPath.size() < 2) {
                    fail(finder);
                    return;
                }
                if (SidingPathFinder.overlappingPaths(resultPath, tempPath)) {
                    tempPath.remove(0);
                }
                resultPath.addAll(tempPath);
                snapshot.remove(0);
            }
            success();
        }

        private void success() {
            success = true;
            finished = true; // volatile 写：resultPath 对模拟线程可见（happens-before）
        }

        private void fail(SidingPathFinder<?, ?, ?, ?> finder) {
            failStart = finder.startSavedRail;
            failEnd = finder.endSavedRail;
            finished = true;
        }

        void discard() {
            discarded = true;
        }

        boolean isDiscarded() {
            return discarded;
        }

        boolean isFinished() {
            return finished;
        }

        boolean isSuccess() {
            return success;
        }

        ObjectArrayList<PathData> getResultPath() {
            return resultPath;
        }

        Runnable getSuccessCallback() {
            return successCallback;
        }

        BiConsumer<Object, Object> getFailCallback() {
            return failCallback;
        }

        SavedRailBase<?, ?> getFailStart() {
            return failStart;
        }

        SavedRailBase<?, ?> getFailEnd() {
            return failEnd;
        }
    }
}
