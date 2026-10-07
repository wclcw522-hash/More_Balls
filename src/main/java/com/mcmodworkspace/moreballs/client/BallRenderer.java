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

    public BallRenderer(EntityRendererProvider.Context context) {
        super(context, 1.0F, true);
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
        }
    }

    @Override
    public void submit(ThrownItemRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState cameraState) {
        float scale = (state instanceof BallRenderState ballState) ? ballState.ballScale : 1.0F;

        if (scale == 1.0F) {
            super.submit(state, poseStack, collector, cameraState);
            return;
        }

        poseStack.pushPose();
        // 放大是围绕实体原点（脚下）做的，所以往上抬一点，免得大球有半个身子陷进地面
        poseStack.translate(0.0F, (scale - 1.0F) * 0.125F, 0.0F);
        poseStack.scale(scale, scale, scale);
        super.submit(state, poseStack, collector, cameraState);
        poseStack.popPose();
    }
}
