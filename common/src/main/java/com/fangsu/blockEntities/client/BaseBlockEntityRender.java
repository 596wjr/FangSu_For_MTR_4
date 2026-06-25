package com.fangsu.blockEntities.client;

import com.fangsu.Main;
import com.fangsu.MainClient;
import com.fangsu.blockEntities.BaseObjBlockEntity;
import com.fangsu.blockEntities.Scriptable;
import com.fangsu.blocks.BaseObjBlock;
import com.fangsu.mappings.RegistryObject;
import com.fangsu.render.ShadersModHandler;
import com.fangsu.render.sowcer.math.Matrix4f;
import com.fangsu.render.sowcer.math.PoseStackUtil;
import com.fangsu.render.sowcerext.reuse.DrawScheduler;
import com.mojang.blaze3d.vertex.PoseStack;
import org.mtr.mod.block.IBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
//#if MC_VERSION >= 11904
import net.minecraft.world.item.ItemDisplayContext;
//#endif
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;

public class BaseBlockEntityRender<T extends BaseObjBlockEntity> implements BlockEntityRenderer<T> {
    private static final RegistryObject<ItemStack> BARRIER_ITEM_STACK = new RegistryObject<>(() -> new ItemStack(net.minecraft.world.item.Items.BARRIER, 1));

    public BaseBlockEntityRender(BlockEntityRenderDispatcher dispatcher) {
        super();
    }

    @Override
    public void render(@NotNull T blockEntity, float f, @NotNull PoseStack matrices, @NotNull MultiBufferSource multiBufferSource, int light, int overlay) {
        final Level world = blockEntity.getLevel();
        if (world == null) return;

        int lightToUse = blockEntity.fullLight ? LightTexture.pack(15, 15) : light;
        Matrix4f candyPose = new Matrix4f(matrices.last().pose()).copy();

        BaseObjBlockEntity.ObjBlockProperty prop = blockEntity.getProperty();

//        Minecraft.getInstance().getItemRenderer().renderStatic(BARRIER_ITEM_STACK.get(), ItemDisplayContext.GROUND, lightToUse, 0, matrices, multiBufferSource, world, 0);

//        if (prop == null) return;

        try {
            blockEntity.whenRendering();
        } catch (Exception e) {
            Main.LOGGER.error(e.getMessage());
        }

        final BlockPos pos = blockEntity.getBlockPos();
        final org.mtr.mapping.holder.Direction facing = IBlock.getStatePropertySafe(new org.mtr.mapping.holder.World(world), new org.mtr.mapping.holder.BlockPos(pos), new org.mtr.mapping.holder.DirectionProperty(BaseObjBlock.FACING));

        if (blockEntity.isMarkedError()) {
            matrices.pushPose();
            matrices.translate(pos.getX(), pos.getY(), pos.getZ());
            matrices.translate(0.5f, 0.5f, 0.5f);
            PoseStackUtil.rotY(matrices, (float) ((System.currentTimeMillis() % 1000) * (Math.PI * 2 / 1000)));
            //#if MC_VERSION >= 12000
            Minecraft.getInstance().getItemRenderer().renderStatic(BARRIER_ITEM_STACK.get(), ItemDisplayContext.GROUND, lightToUse, 0, matrices, multiBufferSource, world, 0);
            //#elseif MC_VERSION >= 11904
            //$$ Minecraft.getInstance().getItemRenderer().renderStatic(BARRIER_ITEM_STACK.get(), ItemDisplayContext.GROUND, lightToUse, 0, matrices, multiBufferSource, world, 0);
            //#else
            //$$ Minecraft.getInstance().getItemRenderer().renderStatic(BARRIER_ITEM_STACK.get(), net.minecraft.client.renderer.block.model.ItemTransforms.TransformType.GROUND, lightToUse, 0, matrices, multiBufferSource, 0);
            //#endif
            matrices.popPose();
            return;
        }

        candyPose.translate(0.5f, 0f, 0.5f);
        candyPose.translate(blockEntity.translateX, blockEntity.translateY, blockEntity.translateZ);
        candyPose.rotateY(-(float) Math.toRadians(facing.data.toYRot()) + (float) (Math.PI));
        candyPose.rotateX(blockEntity.rotateX);
        candyPose.rotateY(blockEntity.rotateY);
        candyPose.rotateZ(blockEntity.rotateZ);
//        if (prop.model != null) {
//            MainClient.drawScheduler.enqueue(prop.model, candyPose, lightToUse);
//        }
//        if (prop.script != null) {
//            synchronized (blockEntity.scriptContext) {

        if (blockEntity instanceof Scriptable scriptable) {
            scriptable.renderScript();
        }

        if (ShadersModHandler.canUseCustomShader()) {
            blockEntity.scriptContext.scriptResult.commit(MainClient.drawScheduler, candyPose, lightToUse);
        } else {
            blockEntity.scriptContext.scriptResult.renderDirect(multiBufferSource, candyPose, lightToUse);
        }

//            }
//            prop.script.tryCallRenderFunctionAsync(blockEntity.scriptContext);
//        }
        blockEntity.scriptContext.renderFunctionFinished();
    }

    @Override
    public boolean shouldRenderOffScreen(@NotNull T blockEntity) {
        return false;
    }

    @Override
    public boolean shouldRender(@NotNull T blockEntity, @NotNull Vec3 vec3) {
        return true;
    }
}
