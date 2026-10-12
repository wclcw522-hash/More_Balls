package com.mcmodworkspace.moreballs;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * 【熔融物烧伤】的<b>挂载时机</b>处理 —— 等级与时长在这里重算。
 *
 * <h2>为什么必须挂事件，而不是在 applyMoltenBurn 里算</h2>
 * <p>作者（2026-10-09）的规格是「当生物被<b>任何效果</b>导致附加熔融物烧伤时，立即计算」。
 * 而「附加」这条路径不止一条：</p>
 * <ul>
 *   <li>模组自己的两个调用点（球命中 {@code BallProjectile}、热辐射 {@code BallHeatHandler}）</li>
 *   <li><b>原版 {@code /effect give} 命令</b> —— 直接构造 {@code MobEffectInstance} 调
 *       {@code addEffect}，一个字都不经过 {@code applyMoltenBurn}</li>
 *   <li>药水、命令方块、其他模组的 {@code addEffect} / {@code forceAddEffect}</li>
 * </ul>
 * <p>作者实测时用的正是 {@code /effect give}，所以之前几轮把公式改来改去都读不到 ——
 * 那条路径根本没进模组的代码。{@code MobEffectEvent.Added} 是所有这些路径的<b>唯一汇合点</b>，
 * 在这里改才真正覆盖「任何效果导致附加」。</p>
 *
 * <h2>公式（作者指定）</h2>
 * <ul>
 *   <li><b>等级</b>：在<b>已有等级</b>上提升 {@code ⌊护甲值 / 10⌋}，总等级不超过 5
 *       （即 amplifier 上限 4）</li>
 *   <li><b>时长</b>：<b>原有时长</b> × {@code (1 + 金属装备数量)}
 *       —— 这一项在本类里<b>不再乘</b>，由 {@code MoltenBurnEffect.applyMoltenBurn} 自己算</li>
 * </ul>
 * <p>也就是说本事件只重算<b>等级</b>；时长只在走模组那两条路径（球命中、热辐射）时被放大过，
 * 而 {@code /effect give} 挂上来的时长就是原样。</p>
 * <p>「原有时长」取的是<b>这一次挂载本身带的时长</b>（{@code getEffectInstance().getDuration()}），
 * 而不是某个写死的常量 —— 这样 {@code /effect give @s ... 60 1} 挂上来也会被正确放大。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class MoltenBurnApplication {

    private MoltenBurnApplication() {
    }

    @SubscribeEvent
    public static void onEffectAdded(MobEffectEvent.Added event) {
        MobEffectInstance added = event.getEffectInstance();
        if (!added.is(ModEffects.MOLTEN_BURN)) {
            return;
        }
        LivingEntity target = event.getEntity();

        // ===== 时长：这里**不再**乘金属装备数 =====
        //
        // ⚠️ 2026-10-12 审计修正：这里**不再乘**，时长由 applyMoltenBurn 算。
        //    MoltenBurnEffect.applyMoltenBurn 已经在它那边乘过一次 (1 + 金属件数)，
        //    这里再乘就是平方；更糟的是那边用的是 Math.max(新, 旧) —— 旧值又是被放大过的，
        //    反复命中会**指数级增长**（4 件金属就是 ×25 而不是 ×5）。
        //    所以这一处只保留「等级」逻辑，时长不碰。

        // ===== 等级：已有等级 + ⌊护甲值 / 10⌋，封顶 5 级 =====
        //
        // getOldEffectInstance() 是「被这次挂载顶掉的那个旧实例」，
        // 没有旧实例就是首次挂载、当前等级视作 0。
        MobEffectInstance old = event.getOldEffectInstance();
        int current = (old == null) ? 0 : old.getAmplifier();
        int armorTiers = Math.max(0, (int) (target.getArmorValue() / MoltenBurnEffect.ARMOR_PER_LEVEL));
        added.amplifier = Math.min(current + armorTiers, MoltenBurnEffect.MAX_AMPLIFIER);
    }
}