package com.mcmodworkspace.moreballs.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * 【潮涌】的环绕纹理模型 —— 球在水里时套在球外面的那圈旋转水纹。
 *
 * <p><b>几何完全照原版 {@code SpinAttackEffectModel}（玩家用激流三叉戟时身边那圈）</b>：
 * 两个 16×32×16 的盒子，缩放 0.75 / 1.5，各自每刻绕 Y 轴反向转 {@code -(45 + 5n)} 度 ——
 * 外层慢、内层快，叠起来就是原版那圈水纹。层定义直接用原版注册好的
 * {@link net.minecraft.client.model.geom.ModelLayers#PLAYER_SPIN_ATTACK}，
 * 纹理也用原版的 {@code trident_riptide.png}，<b>一个新资源都不用建</b>。</p>
 *
 * <p>为什么不直接 new 原版那个模型：它的泛型绑死在 {@code AvatarRenderState} 上，
 * 而球的渲染管线给它的是 {@link BallRenderState}。所以这里只把「壳」换成球的，
 * 几何与转速一个字不改。</p>
 */
public class TidalSurgeModel extends EntityModel<BallRenderState> {

    /** 原版激流的环绕纹理（玩家身边那圈水纹用的就是它） */
    public static final Identifier TEXTURE =
            Identifier.withDefaultNamespace("textures/entity/trident/trident_riptide.png");

    private static final int BOX_COUNT = 2;
    private final ModelPart[] boxes = new ModelPart[BOX_COUNT];

    public TidalSurgeModel(ModelPart root) {
        super(root);
        for (int i = 0; i < BOX_COUNT; i++) {
            this.boxes[i] = root.getChild("box" + i);
        }
    }

    @Override
    public void setupAnim(BallRenderState state) {
        super.setupAnim(state);
        for (int i = 0; i < this.boxes.length; i++) {
            float angle = state.ageInTicks * -(45 + (i + 1) * 5);
            this.boxes[i].yRot = Mth.wrapDegrees(angle) * (float) (Math.PI / 180.0);
        }
    }
}