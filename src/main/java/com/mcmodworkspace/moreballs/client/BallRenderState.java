package com.mcmodworkspace.moreballs.client;

import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;

/**
 * 球的渲染状态 —— 只为多带一个「这个球该画多大」。
 *
 * <p>26.x 的渲染管线改成了「状态分离」：实体先在 {@code extractRenderState} 里被读成一份
 * 纯数据快照，再交给 {@code submit} 画出来 —— {@code submit} 是拿不到实体的。
 * 所以球的尺寸倍率必须在这里跟着快照一起传下去。</p>
 */
public class BallRenderState extends ThrownItemRenderState {

    /** 这个球的渲染尺寸倍率；1.0 就是普通球 */
    public float ballScale = 1.0F;
}
