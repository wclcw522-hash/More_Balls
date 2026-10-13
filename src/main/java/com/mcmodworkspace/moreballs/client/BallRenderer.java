package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.BallBehavior;
import com.mcmodworkspace.moreballs.entity.BallProjectile;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * 球的投射物渲染器 —— 在 {@link ThrownItemRenderer} 基础上支持<b>按球缩放</b>。
 *
 * <p>原版那个渲染器的缩放是构造参数（写死的），而我们的球共用一个实体类型、
 * 却需要不同大小（金球是「一格内切球」，比普通球大得多）。26.x 又改成了状态分离管线，
 * 所以这里走三步：自己的渲染状态 → 抽状态时把尺寸记进去 → 提交时按尺寸缩放。</p>
 *
 * <p>普通球的倍率是 1.0，走的是和以前完全一样的路径。</p>
 */
public class BallRenderer extends ThrownItemRenderer<BallProjectile> {
    /** 环绕层用全亮 —— 它在水下，不该被头顶的方块阴影压暗 */
    private static final int LIGHT_FULL = 0x00F000F0;

    /** 【潮涌】的环绕模型 —— 层定义直接用原版注册好的那个，懒 bake 一次 */
    private TidalSurgeModel surgeModel;

    public BallRenderer(EntityRendererProvider.Context context) {
        super(context, 1.0F, true);
    }

    private TidalSurgeModel surgeModel() {
        if (this.surgeModel == null) {
            this.surgeModel = new TidalSurgeModel(
                    net.minecraft.client.Minecraft.getInstance().getEntityModels()
                            .bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER_SPIN_ATTACK));
        }
        return this.surgeModel;
    }

    @Override
    public ThrownItemRenderState createRenderState() {
        return new BallRenderState();
    }

    @Override
    public void extractRenderState(BallProjectile entity, ThrownItemRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        if (state instanceof BallRenderState ballState) {
            ballState.ballScale = BallBehavior.profileFor(entity.getItem()).entityScale();
            ballState.tidalSurge = entity.isTidalSurge();
            ballState.surgeTicks = entity.tickCount;
        }
    }

    @Override
    public void submit(ThrownItemRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState cameraState) {
        float scale = (state instanceof BallRenderState ballState) ? ballState.ballScale : 1.0F;

        if (scale == 1.0F) {
            super.submit(state, poseStack, collector, cameraState);
            this.submitSurge(state, poseStack, collector, LIGHT_FULL);
            return;
        }

        poseStack.pushPose();
        // 放大是围绕实体原点（脚下）做的，所以往上抬一点，免得大球有半个身子陷进地面
        poseStack.translate(0.0F, (scale - 1.0F) * 0.125F, 0.0F);
        poseStack.scale(scale, scale, scale);
        super.submit(state, poseStack, collector, cameraState);
        // 缩放分支同样要画环绕层（否则只有普通球的潮涌看得见，金球那种就漏了）
        this.submitSurge(state, poseStack, collector, LIGHT_FULL);
        poseStack.popPose();
    }

    /** 【潮涌】的环绕水纹 —— 球在水里时套在外面那一圈（几何与纹理都来自原版激流） */
    private void submitSurge(ThrownItemRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                             int lightCoords) {
        if (!(state instanceof BallRenderState ballState) || !ballState.tidalSurge) {
            return;
        }
        TidalSurgeModel model = this.surgeModel();
        model.setupAnim(ballState);
        // ⚠️ 原版那个模型是给**玩家**用的：EntityModel.MODEL_Y_OFFSET = -1.501，
        //    渲染时会把模型整体下移 1.501 格（从脚底移成「人形」的坐标系）。
        //    球没有那套变换，直接用就会整个沉到脚下 —— 表现就是作者报的「固定向下」。
        //    所以这里把那个偏移**加回来**，再缩到球的大小让它贴合球体。
        poseStack.pushPose();
        poseStack.translate(0.0F, 1.501F, 0.0F);
        poseStack.scale(0.30F, 0.30F, 0.30F);
        collector.submitModel(model, ballState, poseStack, TidalSurgeModel.TEXTURE,
                lightCoords, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0, null);
        poseStack.popPose();
    }
}
