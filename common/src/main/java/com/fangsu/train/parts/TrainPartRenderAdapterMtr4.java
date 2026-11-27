package com.fangsu.train.parts;

import com.fangsu.mixin.MtrGraphicsConsumerAccessor;
import com.fangsu.mixin.VehicleDoorPredictionAccessor;
import com.fangsu.render.sowcer.math.Matrix4f;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.mtr.core.data.VehicleCar;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.StoredMatrixTransformations;

import java.util.HashSet;
import java.util.Set;

public final class TrainPartRenderAdapterMtr4 {
    private record SharedBogie(long vehicle, int connection, TrainPartsRegistry.Bogie definition) {}
    private record Frame(GraphicsHolder graphics, PoseStack matrices, Vector3d offset) {}

    private static final Set<SharedBogie> DRAWN_SHARED_BOGIES = new HashSet<>();
    private static Frame frame;

    private TrainPartRenderAdapterMtr4() {}

    public static void renderVehicles(GraphicsHolder graphics, Vector3d offset, Runnable render) {
        MtrGraphicsConsumerAccessor holder = (MtrGraphicsConsumerAccessor) (Object) graphics;
        PoseStack matrices = holder.fangsu$getMatrixStack();
        MultiBufferSource buffers = holder.fangsu$getVertexConsumerProvider();
        if (matrices == null || buffers == null) {
            render.run();
            return;
        }
        frame = new Frame(graphics, matrices, offset);
        try {
            render.run();
            TrainPartRenderer.flush(buffers);
        } finally {
            TrainPartRenderer.discardQueuedParts();
            DRAWN_SHARED_BOGIES.clear();
            frame = null;
        }
    }

    public static boolean bogie(VehicleExtension vehicle, String type, int car, int slot,
                                StoredMatrixTransformations transforms, int light) {
        TrainPartsRegistry.Parts parts = TrainPartsRegistry.get(type);
        if (parts == null || frame == null) return false;
        TrainPartsRegistry.Bogie definition = parts.bogie(car + 1, vehicle.vehicleExtraData.immutableVehicleCars.size(), slot);
        if (definition == null) return false;
        SharedBogie shared = sharedBogie(vehicle, car, slot, definition);
        if (shared != null && DRAWN_SHARED_BOGIES.contains(shared)) return true;

        Matrix4f pose = pose(transforms);
        double progress = ((VehicleDoorPredictionAccessor) vehicle).fangsu$getRailProgress();
        if (!TrainPartRenderer.bogie(vehicle.getId(), definition, pose, progress, vehicle.getReversed(), light)) {
            return false;
        }
        if (shared != null) DRAWN_SHARED_BOGIES.add(shared);
        return true;
    }

    private static SharedBogie sharedBogie(VehicleExtension vehicle, int car, int slot,
                                           TrainPartsRegistry.Bogie definition) {
        if (slot != 0 && slot != 1) return null;
        var cars = vehicle.vehicleExtraData.immutableVehicleCars;
        int connection = car + slot;
        if (connection <= 0 || connection >= cars.size()) return null;
        VehicleCar previous = cars.get(connection - 1);
        VehicleCar next = cars.get(connection);
        // 无车钩间距且两侧槽位都在接头上时，MTR4 会为同一台 Jacobs 转向架调用两次。
        if (!previous.hasOneBogie && previous.getBogie2Position() == previous.getLength() / 2
                && next.getBogie1Position() == -next.getLength() / 2
                && previous.getTotalLength(true, false) == previous.getLength()
                && next.getTotalLength(false, true) == next.getLength()) {
            return new SharedBogie(vehicle.getId(), connection, definition);
        }
        return null;
    }

    public static void pantographs(VehicleExtension vehicle, String type, int car,
                                   StoredMatrixTransformations transforms, int light) {
        TrainPartsRegistry.Parts parts = TrainPartsRegistry.get(type);
        if (parts == null || parts.pantographs().isEmpty() || frame == null) return;
        TrainPartRenderer.pantographs(vehicle.getId(), car + 1, vehicle.vehicleExtraData.immutableVehicleCars.size(), parts, pose(transforms), light);
    }

    private static Matrix4f pose(StoredMatrixTransformations transforms) {
        Frame current = frame;
        try {
            // 与 MTR 车体使用同一套插值后的相机偏移与矩阵栈，涵盖乘车时的相对变换。
            transforms.transform(current.graphics(), current.offset());
            Matrix4f result = new Matrix4f(current.matrices().last().pose()).copy();
            result.rotateX((float) Math.PI);
            return result;
        } finally {
            current.graphics().pop();
        }
    }
}
