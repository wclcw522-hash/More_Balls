package com.mcmodworkspace.moreballs;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 状态效果注册。
 *
 * <p>注册器在入口类里挂到 mod 事件总线（与物品、组件等走同一套流程）。</p>
 */
public final class ModEffects {

    private ModEffects() {
    }

    /** 效果注册器 */
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, MoreBalls.MOD_ID);

    /**
     * 【熔融物烧伤】—— 空心铁球的【熔融】词条命中目标时挂上它。
     *
     * <p>英文 id 作者指定为 {@code molten_burn}。</p>
     */
    public static final DeferredHolder<MobEffect, MoltenBurnEffect> MOLTEN_BURN =
            EFFECTS.register(MoltenBurnEffect.EFFECT_ID, MoltenBurnEffect::new);

    /**
     * 【震荡】—— 红石球的【脉冲】命中时施加的「晕头转向」debuff。
     *
     * <p>英文 id 作者指定为 {@code shock}；五级，每 20/17/13/8/2 刻强制随机转向一次，
     * 且无视一切效果免疫。详见 {@link ShockEffect}。</p>
     */
    public static final DeferredHolder<MobEffect, ShockEffect> SHOCK =
            EFFECTS.register(ShockEffect.EFFECT_ID, ShockEffect::new);

    public static void register(IEventBus bus) {
        EFFECTS.register(bus);
    }
}
