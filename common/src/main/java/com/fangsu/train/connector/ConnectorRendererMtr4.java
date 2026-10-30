package com.fangsu.train.connector;

import com.fangsu.Main;
import com.fangsu.MainClient;
import org.mtr.core.tool.Vector;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.Init;
import org.mtr.mod.render.*;
import org.mtr.mod.data.IGui;
import java.lang.reflect.Field;
import java.util.*;

/** 复用既有 WrapOperation，私有端点只读；原方法以 shouldRender1=false 完成原生记账。 */
public final class ConnectorRendererMtr4 {
    private static final Map<RenderVehicles.PreviousConnectionPositions,ConnectorSet> IDS=new IdentityHashMap<>();
    private static final Map<ConnectorDef,List<ConnectorGeometry.Mesh>> POOL=new IdentityHashMap<>();
    private static final Map<ConnectorDef,Integer> USED=new IdentityHashMap<>();
    private static final Set<ConnectorDef> ACTIVE=Collections.newSetFromMap(new IdentityHashMap<>());
    private static Field[] fields;
    private static Field poseField, consumerField;
    private static boolean unavailable;
    private static final double[][] CORNERS=new double[8][3];
    static { ConnectorRegistry.onReset(()->{ IDS.clear(); POOL.clear(); USED.clear(); ACTIVE.clear(); unavailable=false; }); }
    public static void beginFrame() { IDS.clear(); USED.clear(); }
    private static void initFields() throws ReflectiveOperationException {
        if(fields!=null) return;
        Field[] f=new Field[4];
        for(int i=0;i<4;i++) { f[i]=RenderVehicles.PreviousConnectionPositions.class.getDeclaredField("position"+(i+1)); f[i].setAccessible(true); }
        fields=f;
    }
    private static void initVertexFields() throws ReflectiveOperationException {
        if(poseField!=null) return;
        Field pose=GraphicsHolder.class.getDeclaredField("matrixStack"),consumer=GraphicsHolder.class.getDeclaredField("vertexConsumer");
        pose.setAccessible(true); consumer.setAccessible(true); poseField=pose; consumerField=consumer;
    }
    /** Identifier/HolderBase 没有重写 toString；必须显式提取资源命名空间和路径。 */
    private static String textureKey(Identifier id) {
        return id == null ? "" : id.getNamespace() + ":" + id.getPath();
    }
    /** 返回成功接管层的位掩码；原版只屏蔽这些层，异常时保持原生绘制。 */
    public static int tryRender(boolean render1,boolean render2,RenderVehicles.PreviousConnectionPositions previous,
                               Identifier outer,Identifier inner,PositionAndRotation position,boolean useOffset,
                               double length,double width,double height,double y,double z,double oscillation,boolean onRoute) {
        ConnectorSet set=ConnectorRegistry.texture(textureKey(outer==null?inner:outer));
        if(set==null && outer==null && inner==null) set=ConnectorRegistry.dimensions(length,width,height,y,z);
        ConnectorSet prev=IDS.get(previous);
        if(render2 && set!=null) IDS.put(previous,set);else IDS.remove(previous);
        if(set==null || unavailable || MainClient.is_nte_loaded) return 0;
        try {
            initFields();
            if(!render1) return 0;
            Vector[] p=new Vector[4];
            for(int i=0;i<4;i++) {p[i]=(Vector)fields[i].get(previous);if(p[i]==null)return 0;}
            if(prev!=null && !prev.signature.equals(set.signature)) {
                ConnectorRegistry.warn("mismatch:"+prev.signature+":"+set.signature,"相邻车厢 connector 配置不一致: "+prev.signature+" / "+set.signature);
                return set.skipMismatch?ConnectorSet.BOTH:0;
            }
            initVertexFields();double angle=-Math.toRadians(oscillation);
            for(int i=0;i<4;i++) {
                CORNERS[i][0]=p[i].x;CORNERS[i][1]=p[i].y;CORNERS[i][2]=p[i].z;
                Vector q=position.transformForwards(new Vector((i<2?-1:1)*width/2,y+IGui.SMALL_OFFSET+(i==1||i==2?height:0),z-length/2).rotateZ(angle),Vector::rotateX,Vector::rotateY,Vector::add);
                CORNERS[i+4][0]=q.x;CORNERS[i+4][1]=q.y;CORNERS[i+4][2]=q.z;
            }
            ClientWorld world=MinecraftClient.getInstance().getWorldMapped();if(world==null)return 0;
            ConnectorGeometry.Mesh[] prepared=new ConnectorGeometry.Mesh[set.definitions.size()];
            double distance=ConnectorGeometry.distance(CORNERS);
            for(int i=0;i<prepared.length;i++) {
                ConnectorDef d=set.definitions.get(i);float[] profile=ConnectorFoldProfile.get(d);if(profile==null)return 0;
                int index=USED.getOrDefault(d,0);USED.put(d,index+1);
                List<ConnectorGeometry.Mesh> pool=POOL.computeIfAbsent(d,k->new ArrayList<>());
                if(index==pool.size())pool.add(new ConnectorGeometry.Mesh(d));
                prepared[i]=pool.get(index);
                if(!ConnectorGeometry.build(d,CORNERS,profile,ConnectorRegistry.nominal(d,distance),prepared[i]))return 0;
            }
            BlockPos pos=Init.newBlockPos(CORNERS[4][0],CORNERS[4][1]+1,CORNERS[4][2]);
            int light=LightmapTextureManager.pack(world.getLightLevel(LightType.getBlockMapped(),pos),world.getLightLevel(LightType.getSkyMapped(),pos));
            int inside=onRoute?GraphicsHolder.getDefaultLight():light;
            for(int i=0;i<prepared.length;i++) {
                ConnectorDef d=set.definitions.get(i);
                if((d.mask()&ConnectorSet.EXTERIOR)!=0)schedule(d,prepared[i],false,useOffset,d.light.equals("interior")?inside:light);
                if((d.mask()&ConnectorSet.INTERIOR)!=0)schedule(d,prepared[i],true,useOffset,d.light.equals("interior")?inside:light);
                if(ACTIVE.add(d))Main.LOGGER.info("[FangSu/Connector] 自定义风挡已接管: id={} side={}",d.id,d.side);
            }
            return set.mask;
        } catch(Exception | LinkageError e) {
            unavailable=true;ConnectorRegistry.warn("mtr4:integration","connector 接入失败，回退原生: "+e.getMessage());return 0;
        }
    }
    private static void schedule(ConnectorDef d,ConnectorGeometry.Mesh mesh,boolean inner,boolean useOffset,int light) {
        MainRenderer.scheduleRender(new Identifier(d.textureFor(inner).toString()),false,
                inner?QueuedRenderLayer.INTERIOR:QueuedRenderLayer.EXTERIOR,(graphics,offset)-> {
                    Vector3d off=useOffset?offset:Vector3d.getZeroMapped();
                    draw(graphics,off,d,mesh,inner,light);
                });
    }
    private static void draw(GraphicsHolder graphics,Vector3d offset,ConnectorDef d,ConnectorGeometry.Mesh mesh,boolean inner,int light) {
        drawFacets(graphics,offset,d,mesh,inner,light);
    }
    /** 复用原生队列绑定的顶点消费者，为每块折面提交独立法线和 UV。 */
    private static void drawFacets(GraphicsHolder graphics,Vector3d offset,ConnectorDef d,ConnectorGeometry.Mesh mesh,boolean inner,int light) {
        try {
            com.mojang.blaze3d.vertex.PoseStack pose=(com.mojang.blaze3d.vertex.PoseStack)poseField.get(graphics);
            com.mojang.blaze3d.vertex.VertexConsumer consumer=(com.mojang.blaze3d.vertex.VertexConsumer)consumerField.get(graphics);
            if(pose==null || consumer==null) return;
            int n=d.points.length; boolean reverse=mesh.reverse^inner;
            for(int j=0;j<mesh.ringCount-1;j++) for(int i=0;i<(d.closed?n:n-1);i++) {
                int next=(i+1)%n, a=reverse?next:i,b=reverse?i:next;
                double va=ConnectorGeometry.v(d,reverse?i+1:i),vb=ConnectorGeometry.v(d,reverse?i:i+1);
                double[] normal=mesh.normals[j]; int ni=i*3;
                float sign=inner?-1:1;
                float nx=(float)normal[ni]*sign,ny=(float)normal[ni+1]*sign,nz=(float)normal[ni+2]*sign;
                facetVertex(consumer,pose,offset,d,mesh,j,a,va,light,false,nx,ny,nz);
                facetVertex(consumer,pose,offset,d,mesh,j+1,a,va,light,true,nx,ny,nz);
                facetVertex(consumer,pose,offset,d,mesh,j+1,b,vb,light,true,nx,ny,nz);
                facetVertex(consumer,pose,offset,d,mesh,j,b,vb,light,false,nx,ny,nz);
            }
        } catch(Exception e) { unavailable=true; ConnectorRegistry.warn("mtr4:uv","折面绘制不可用，下帧回退原生: "+e.getMessage()); }
    }
    private static void facetVertex(com.mojang.blaze3d.vertex.VertexConsumer c,com.mojang.blaze3d.vertex.PoseStack pose,Vector3d offset,
                                     ConnectorDef d,ConnectorGeometry.Mesh mesh,int j,int i,double v,int light,boolean end,float nx,float ny,float nz) {
        double[] p=mesh.rings[j]; int ix=i*3;
        double u=d.perSegment?d.uRange[end?1:0]:ConnectorGeometry.u(d,mesh.axis[j],i);
        c.vertex(pose.last().pose(),(float)(p[ix]-offset.getXMapped()),(float)(p[ix+1]-offset.getYMapped()),(float)(p[ix+2]-offset.getZMapped()))
                .color(255,255,255,255).uv((float)u,(float)v).overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .uv2(light).normal(pose.last().normal(),nx,ny,nz).endVertex();
    }
}
