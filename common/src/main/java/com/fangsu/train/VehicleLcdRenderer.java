package com.fangsu.train;

import com.fangsu.Main;
import com.fangsu.mtr.LcdVehicleRegistry;
import com.lx862.mtrscripting.core.util.GraphicsTexture;
import com.lx862.mtrscripting.core.util.model.RawMeshBuilderJS;
import com.lx862.mtrscripting.core.util.model.RawModelJS;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.NTETrainWrapper;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleScriptContext;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleWrapper;
import org.mtr.mapping.holder.Identifier;

import java.awt.Graphics2D;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车载 LCD 的运行时状态：一列车 = 一张动态纹理 + 一个面板模型。
 * <p>
 * <b>定位</b>：这里只负责「LCD 自己那点东西」——纹理、模型、每帧内容。
 * <b>位置/朝向交给 MTR</b>：调用方把 MTR 自己算好的
 * {@code RenderVehicles.getStoredMatrixTransformations(...)} 结果传进来
 * （见 {@code RenderVehiclesMixin}），而不是方速再拼一套车厢矩阵。
 * <p>
 * 绘制内容分两路，互不影响：
 * <ul>
 *   <li>Java：{@link #paintJava}（默认，见 {@link LcdJavaPainter}）；</li>
 *   <li>脚本（可选）：JCM 车辆脚本调用 {@link #getGraphics} 自己画，见 {@link #jcmCreate} 等。</li>
 * </ul>
 */
public final class VehicleLcdRenderer {
    private VehicleLcdRenderer() {
    }

    private static final Map<Long, Lcd> LCDS = new ConcurrentHashMap<>();

    /** 与 JCM 的 {@code RawModelJS.stringToRenderStage} 对应；interiortranslucent → 自发光半透明 */
    private static final String RENDER_TYPE = "interiortranslucent";

    // ---------------------------------------------------------------- 渲染入口

    /**
     * 把面板模型排进 MTR 的车厢变换里。
     * <p>
     * 直接走 {@code ModelJS.draw(StoredMatrixTransformations, light)}：
     * 它内部会 {@code MainRenderer.scheduleRender(...)} 并在渲染时
     * {@code storedMatrixTransformations.transform(graphicsHolder, offset)}。
     * <b>不</b>用 {@code DynamicModelDrawCall}——后者会额外套一个
     * {@code rotateX(180)}（为脚本坐标系设计的），会把已经是 MTR 坐标系的面板翻过来。
     */
    private static void queueWithMtrTransform(Lcd lcd, org.mtr.mod.render.PositionAndRotation absolute) {
        final com.lx862.mtrscripting.core.util.model.ModelJS model = lcd.uploadedModel();
        if (model == null) return;
        final org.mtr.mod.render.StoredMatrixTransformations mtrTransform =
                org.mtr.mod.render.RenderVehicles.getStoredMatrixTransformations(true, absolute, 0);
        model.draw(mtrTransform, absolute.light);
    }

    /**
     * <b>备用路径</b>：遍历某列车的所有车厢，用 MTR 自己的车厢变换渲染 LCD。
     * <p>
     * 供 {@code RenderVehiclesMixin} 调用。已接入 JCM 脚本链路的车型会在这里跳过
     * （由 JCM 的车辆脚本驱动，见 {@link #jcmRender}），避免重复渲染。
     */
    public static void renderVehicle(org.mtr.mod.data.VehicleExtension vehicleExtension) {
        if (vehicleExtension == null) return;

        final VehicleWrapper wrapper = new NTETrainWrapper(
                VehicleScriptContext.DataFetchMode.SKIP, vehicleExtension);
        final int carCount = wrapper.getCarCount();
        if (carCount <= 0) return;

        boolean anyLcd = false;
        for (int car = 0; car < carCount; car++) {
            final String carVehicleId = wrapper.getVehicleId(car);
            if (carVehicleId == null) continue;
            if (configForVehicleId(carVehicleId) == null) continue;
            anyLcd = true;
            break;
        }
        if (!anyLcd) return;

        // 任一车厢已接入 JCM 脚本 → 交给 JCM 渲染
        for (int car = 0; car < carCount; car++) {
            final String carVehicleId = wrapper.getVehicleId(car);
            if (carVehicleId != null && JcmLcdScriptBridge.isScripted(carVehicleId)) return;
        }

        final Lcd lcd = getOrInit(vehicleExtension.getId(), wrapper);
        if (lcd == null) return;

        lcd.paint(buildStatus(wrapper));
        lcd.texture.upload();
        if (!lcd.uploaded()) return;

        final NTETrainWrapper nteWrapper = wrapper instanceof NTETrainWrapper ? (NTETrainWrapper) wrapper : null;
        if (nteWrapper == null) return;

        for (int car = 0; car < carCount; car++) {
            final com.lx862.mtrscripting.core.util.ScriptVector3f pos = nteWrapper.lastCarPosition[car];
            final com.lx862.mtrscripting.core.util.ScriptVector3f rot = nteWrapper.lastCarRotation[car];
            if (pos == null || rot == null) continue;

            // JCM 的 lastCarRotation = (pitch, yaw + PI, 0)，MTR 要的是裸 pitch + 裸 yaw
            final org.mtr.mod.render.PositionAndRotation absolute = new org.mtr.mod.render.PositionAndRotation(
                    new org.mtr.core.tool.Vector(pos.x(), pos.y(), pos.z()),
                    rot.y() - Math.PI,
                    rot.x());

            queueWithMtrTransform(lcd, absolute);
        }
    }

    // ---------------------------------------------------------------- 脚本路径（JCM 绑定，主路径）

    public static void jcmCreate(VehicleScriptContext ctx, VehicleWrapper vehicle) {
        final Lcd lcd = getOrInit(vehicle.getId(), vehicle);
        if (lcd != null) lcd.ctx = ctx;
    }

    public static void jcmRender(VehicleScriptContext ctx, VehicleWrapper vehicle) {
        final Lcd lcd = LCDS.get(vehicle.getId());
        if (lcd == null) return;
        lcd.ctx = ctx;
        // 交给 MTR 那套绘制（MtrLcd）画满纹理；脚本若想自己画，可在 render 里覆盖
        lcd.paint(buildStatus(vehicle));
        lcd.texture.upload();
        queueJcm(lcd);
    }

    public static void jcmDispose(VehicleScriptContext ctx, VehicleWrapper vehicle) {
        final Lcd lcd = LCDS.remove(vehicle.getId());
        if (lcd != null) lcd.close();
    }

    /** 脚本要画的话从这里拿 Graphics2D */
    public static Graphics2D getGraphics(VehicleWrapper vehicle) {
        final Lcd lcd = LCDS.get(vehicle.getId());
        return lcd == null ? null : lcd.graphics;
    }

    public static LcdPanelConfig getConfig(VehicleWrapper vehicle) {
        final Lcd lcd = LCDS.get(vehicle.getId());
        return lcd == null ? null : lcd.config;
    }

    public static TrainStatus getStatus(VehicleWrapper vehicle) {
        final Lcd lcd = LCDS.get(vehicle.getId());
        return lcd == null ? null : lcd.status;
    }

    public static java.awt.Font loadFont(String name) {
        try {
            final java.awt.Font font = com.lx862.mtrscripting.core.util.ScriptResourceUtil.getSystemFont(
                    name == null || name.isBlank() ? "Noto Sans" : name);
            return font == null ? new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 1) : font;
        } catch (Throwable t) {
            return new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 1);
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 刷新列车状态（MTR4 下必须每帧重建 wrapper 才会更新，沿用旧路径结论）。
     * <p>
     * 必须用 {@code DataFetchMode.MANDATORY}：它会向服务端请求完整站序数据
     * （{@code StopsData}），只有这份数据里 {@code Stop.distance} 才是真实里程；
     * {@code SKIP} 走的是客户端本地拼的 limited stops data，{@code distance} 全是
     * {@code -1}，会导致下一站索引与到站判定都失效。
     */
    public static TrainStatus buildStatus(VehicleWrapper vehicle) {
        if (vehicle == null) return null;
        final NTETrainWrapper wrapper = new NTETrainWrapper(
                VehicleScriptContext.DataFetchMode.MANDATORY, vehicle.getMtrVehicle());
        final TrainStatus status = new TrainStatus(wrapper);
        status.updateRoute();
        status.updateDoorState(wrapper.getDoorValue());
        return status;
    }

    /** 把模型排进该 JCM 脚本实例负责的每节车厢（由 JCM 提供车厢变换） */
    private static void queueJcm(Lcd lcd) {
        if (lcd.ctx == null || !lcd.uploaded()) return;
        for (int car : lcd.ctx.getMyCars()) {
            lcd.ctx.drawCarModel(lcd.uploadedModel(), car, null);
        }
    }

    private static Lcd getOrInit(long vehicleId, VehicleWrapper vehicle) {
        Lcd existing = LCDS.get(vehicleId);
        if (existing != null) return existing;
        final LcdVehicleRegistry.LcdVehicleEntry entry = entryForVehicle(vehicle);
        if (entry == null) return null;
        final LcdPanelConfig config = LcdPanelConfig.fromSlots(entry.lcdInfo().id(),
                entry.lcdInfo().resolveSlots().slotsInfo());
        final Lcd created = new Lcd(vehicleId, config, entry.lcdInfo());
        existing = LCDS.putIfAbsent(vehicleId, created);
        if (existing != null) {
            created.close(); // 并发下别人先建好了，丢弃自己这份
            return existing;
        }
        Main.LOGGER.info("[FangSu LCD] created: vehicle={} panels={} tex={}x{}",
                vehicleId, config.panels().size(), config.texWidth(), config.texHeight());
        return created;
    }

    /** 从车型注册表取该车的 LCD 条目 */
    private static LcdVehicleRegistry.LcdVehicleEntry entryForVehicle(VehicleWrapper vehicle) {
        if (vehicle == null) return null;
        for (int car = 0; car < Math.max(1, vehicle.getCarCount()); car++) {
            final String vehicleId = vehicle.getVehicleId(car);
            if (vehicleId == null) continue;
            final LcdVehicleRegistry.LcdVehicleEntry entry = LcdVehicleRegistry.match(vehicleId);
            if (entry != null) return entry;
        }
        return null;
    }

    /** 按车辆 ID 解析配置（备用渲染路径只有 ID 时用这个） */
    public static LcdPanelConfig configForVehicleId(String vehicleId) {
        if (vehicleId == null) return null;
        final LcdVehicleRegistry.LcdVehicleEntry entry = LcdVehicleRegistry.match(vehicleId);
        if (entry == null) return null;
        return LcdPanelConfig.fromSlots(entry.lcdInfo().id(),
                entry.lcdInfo().resolveSlots().slotsInfo());
    }

    /** 调试用 */
    public static int activeCount() {
        return LCDS.size();
    }

    public static String debugInfo() {
        final StringBuilder sb = new StringBuilder("FangSu LCD: ").append(LCDS.size()).append(" vehicle(s)");
        LCDS.forEach((id, lcd) -> sb.append("\n  - ").append(id)
                .append(" panels=").append(lcd.config.panels().size())
                .append(" tex=").append(lcd.config.texWidth()).append('x').append(lcd.config.texHeight())
                .append(" uploaded=").append(lcd.uploaded()));
        return sb.toString();
    }

    /** 一列车的 LCD 运行时状态 */
    public static final class Lcd {
        public final long vehicleId;
        public final LcdPanelConfig config;
        /** 车型注册表里的原始 LCD 信息（{@code MtrLcd.draw} 需要它取 id 等） */
        public final LcdInfo info;
        public final GraphicsTexture texture;
        public final Graphics2D graphics;
        private final com.lx862.mtrscripting.core.util.model.DynamicModelHolderJS holder;

        VehicleScriptContext ctx;
        TrainStatus status;

        Lcd(long vehicleId, LcdPanelConfig config, LcdInfo info) {
            this.vehicleId = vehicleId;
            this.config = config;
            this.info = info;
            this.texture = new GraphicsTexture(config.texWidth(), config.texHeight());
            this.graphics = texture.graphics;
            this.holder = new com.lx862.mtrscripting.core.util.model.DynamicModelHolderJS();
            // 注意：MTR 的 Identifier 没有可用的 toString()（拿到的是对象引用串），
            // 必须用 getNamespace()/getPath() 拼，否则 ResourceLocation 会因非法字符抛异常
            final RawModelJS rawModel = buildModel(config, new Identifier(
                    texture.identifier.getNamespace() + ":" + texture.identifier.getPath()));
            if (rawModel != null) {
                holder.uploadLater(rawModel);
            } else {
                Main.LOGGER.warn("[FangSu LCD] vehicle {} 没有解析出面板几何，不会渲染", vehicleId);
            }
        }

        /** 重新按 slots 画内容（每帧调用） */
        void paint(TrainStatus status) {
            this.status = status;
            LcdJavaPainter.paint(graphics, config, info, status, info == null ? null : info.id());
        }

        public boolean uploaded() {
            return holder.getUploadedModel() != null;
        }

        /** 已上传的面板模型（渲染线程排队用） */
        public com.lx862.mtrscripting.core.util.model.ModelJS uploadedModel() {
            return holder.getUploadedModel();
        }

        public TrainStatus status() {
            return status;
        }

        void close() {
            try {
                holder.close();
            } catch (Exception ignored) {
            }
            try {
                texture.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 依据配置生成面片模型。
     * <p>
     * 几何<b>完全由 slots.json 的 {@code pos} + {@code offsets} 决定</b>（与旧路径
     * {@code DisplayHelper} 一致）：
     * <ul>
     *   <li>{@code pos} 是「若干组顶点」的数组，每组 4 个顶点为一个四边形；</li>
     *   <li>{@code offsets} 是平移量列表，每个 offset 都要把该组四边形再复制一份；</li>
     *   <li>UV 取该 slot 的 {@code texArea} 归一化坐标。</li>
     * </ul>
     * 顶点坐标就是车厢局部坐标，{@code RawMeshBuilderJS.vertex(x,y,z)} 内部会做
     * {@code (x,-y,-z)}，与 MTR 的 OBJ 加载约定一致，所以这里直接填原值。
     */
    private static RawModelJS buildModel(LcdPanelConfig config, Identifier texture) {
        final RawModelJS rawModel = new RawModelJS();
        int quadCount = 0;

        // 每个 slot 单独一个 builder：渲染阶段取自 slots[i].renderType（缺省 light），
        // 不同 slot 可能不同，合并到一个 builder 会丢掉这个信息。
        for (LcdPanelConfig.Panel panel : config.panels()) {
            final RawMeshBuilderJS builder = new RawMeshBuilderJS(4, panel.renderType(), texture);
            builder.color(255, 255, 255, 255);
            final int n = addPanelQuads(builder, panel, config);
            if (n == 0) continue;
            rawModel.append(builder.getMesh());
            quadCount += n;
        }

        if (quadCount == 0) {
            Main.LOGGER.warn("[FangSu LCD] {} 的 slots 没有可用几何（pos 为空）", config.id());
            return null;
        }

        rawModel.triangulate();
        // 不调用 generateNormals()：它内部会 new Vertex(old) 拷贝，
        // 而 MTR 的 Vertex 拷贝构造器对 position/normal 都做 Utilities.copy，
        // 任一处为 null 就会抛 NPE。法线在下面按四边形顺序算好。
        Main.LOGGER.info("[FangSu LCD] {} 面板网格: {} 个四边形（renderType 取自 slots）",
                config.id(), quadCount);
        return rawModel;
    }

    /**
     * 按 slots 的 pos × offsets 铺一个 slot 的所有四边形。
     * <p>
     * UV 与 {@code DisplayHelper} 一致：
     * 顶点 0 → (u0,v0)、1 → (u0,v1)、2 → (u1,v1)、3 → (u1,v0)，
     * 即「先沿 v 方向一条边、再沿 u 方向一条边」的四边形顺序。
     *
     * @return 生成的四边形数量
     */
    private static int addPanelQuads(RawMeshBuilderJS builder, LcdPanelConfig.Panel panel,
                                     LcdPanelConfig config) {
        final List<double[][]> quads = panel.quads();
        if (quads == null || quads.isEmpty()) return 0;

        final LcdPanelConfig.Layout layout = config.layout(panel.name());
        final double u0 = (double) layout.x() / config.texWidth();
        final double u1 = (double) (layout.x() + layout.w()) / config.texWidth();
        final double v0 = (double) layout.y() / config.texHeight();
        final double v1 = (double) (layout.y() + layout.h()) / config.texHeight();

        final double[][] uvs = {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
        int count = 0;

        for (double[][] quad : quads) {
            if (quad.length < 4) continue;

            // 法线由四边形的两条边叉乘得到（MTR 的坐标系：y 取负、z 取负）
            final double[] n = quadNormal(quad);

            for (int i = 0; i < 4; i++) {
                builder.vertex(quad[i][0], quad[i][1], quad[i][2])
                        .normal((float) n[0], (float) n[1], (float) n[2])
                        .uv((float) uvs[i][0], (float) uvs[i][1])
                        .endVertex();
            }
            count++;
        }
        return count;
    }

    /** 四边形外法线：(v1-v0) × (v3-v0)，归一化 */
    private static double[] quadNormal(double[][] q) {
        final double ax = q[1][0] - q[0][0], ay = q[1][1] - q[0][1], az = q[1][2] - q[0][2];
        final double bx = q[3][0] - q[0][0], by = q[3][1] - q[0][1], bz = q[3][2] - q[0][2];
        double nx = ay * bz - az * by;
        double ny = az * bx - ax * bz;
        double nz = ax * by - ay * bx;
        final double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6) return new double[]{0, 1, 0}; // 退化面片：给个朝上的法线兜底
        nx /= len;
        ny /= len;
        nz /= len;
        return new double[]{nx, ny, nz};
    }

    // 兼容旧调用名（JCM 桥接脚本用）------------------------------------------

    public static void createForTrain(VehicleScriptContext ctx, VehicleWrapper vehicle) {
        jcmCreate(ctx, vehicle);
    }

    public static void renderForTrain(VehicleScriptContext ctx, VehicleWrapper vehicle) {
        jcmRender(ctx, vehicle);
    }

    public static void disposeForTrain(VehicleScriptContext ctx, VehicleWrapper vehicle) {
        jcmDispose(ctx, vehicle);
    }
}
