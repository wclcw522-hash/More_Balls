package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.BallProjectile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.spider.CaveSpider;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * <b>「敌对球」特效</b> —— 某些敌对生物捡球后会携带的特殊机制（作者 2026-10-11 指定）。
 *
 * <p>这个类集中放「球打出去之后会发生什么」，与 {@link BallMobAI}（谁拿得动球、
 * 怎么捡、怎么扔）分开：那边管<b>怎么用球</b>，这边管<b>球打中人之后的效果</b>。</p>
 *
 * <h2>⚠️ 静态初始化</h2>
 * <p>本类带 {@code @EventBusSubscriber}，会在 <b>mod 构造阶段</b>就被加载 ——
 * 所以静态字段里<b>不能</b>构造 {@code ItemStack} / {@code Item} / {@code Block}
 * 这类要查注册表的东西。这里只有基本类型常量，是安全的。</p>
 *
 * <h2>目前的清单</h2>
 * <ul>
 *   <li><b>蜘蛛</b> —— 它扔出的球击中任意生物后，给目标挂<b>缓慢 IV（5 秒）</b></li>
 *   <li><b>洞穴蜘蛛</b> —— 在蜘蛛的基础上再加<b>中毒 II（5 秒）</b></li>
 * </ul>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class MobBallSpecials {

    private MobBallSpecials() {
    }

    /** 蜘蛛系命中效果的时长：<b>5 秒</b>（作者指定 00:05） */
    public static final int SPIDER_EFFECT_TICKS = 100;

    /**
     * 缓慢 <b>IV</b>。
     *
     * <p>⚠️ 两点：① 26.2 里「缓慢」的常量叫 {@code MobEffects.SLOWNESS}（<b>不是</b>
     * {@code MOVEMENT_SLOWDOWN}，那个名字在这个版本不存在）；② 原版
     * {@code MobEffectInstance} 的 amplifier 是 <b>0 起算</b>的 ——
     * 「IV 级」对应 amplifier {@code 3}，不是 4。</p>
     */
    public static final int SPIDER_SLOW_AMPLIFIER = 3;

    /** 中毒 <b>II</b> —— amplifier 同理，II 级对应 {@code 1} */
    public static final int CAVE_SPIDER_POISON_AMPLIFIER = 1;

    /**
     * 球命中生物时调用（由 {@code BallProjectile.onHitEntity} 打过来）。
     *
     * <p>只认「投掷者是蜘蛛系」这一条 —— 玩家自己扔的球、别的怪扔的球都不受影响。</p>
     */
    public static void onBallHitCreature(BallProjectile ball, ServerLevel level, Entity victim) {
        if (!(victim instanceof LivingEntity target)) {
            return;
        }
        if (!(ball.getOwner() instanceof Spider spider)) {
            return;
        }
        // 缓慢 IV（5 秒）
        target.addEffect(new MobEffectInstance(
                MobEffects.SLOWNESS,
                SPIDER_EFFECT_TICKS,
                SPIDER_SLOW_AMPLIFIER,
                false,   // ambient
                true     // visible —— 要有粒子，玩家得看得出是中招了
        ), spider);

        // 洞穴蜘蛛：在蜘蛛的基础上再加中毒 II（5 秒）
        if (spider instanceof CaveSpider) {
            target.addEffect(new MobEffectInstance(
                    MobEffects.POISON,
                    SPIDER_EFFECT_TICKS,
                    CAVE_SPIDER_POISON_AMPLIFIER,
                    false,
                    true
            ), spider);
        }
    }

    // ===== 二、史莱姆：拿着球时的近战继承 =====

    /**
     * <b>史莱姆拿着球时的近战</b>（作者 2026-10-11 指定）。
     *
     * <p>规则：<b>攻击力数值与球的基础伤害「相加」</b>，并且把球上
     * <b>「能落在近战上」</b>的特性词条照常施加给被打的目标。</p>
     *
     * <h2>为什么挂在 {@code LivingIncomingDamageEvent} 上</h2>
     * <p>这是<b>伤害真正结算之前</b>的事件，{@code setAmount} 加进去的数值
     * 会照常走护甲减免、无敌帧那一套 —— 而不是绕过它们直接扣血。
     * 挂在「结算之后」的事件上就等于无视护甲，那是错的。</p>
     *
     * <h2>哪些词条落在近战上</h2>
     * <ul>
     *   <li><b>【熔融】</b> —— 给目标挂「熔融物烧伤」</li>
     *   <li><b>【魔法】</b> —— 给目标挂一个随机负面效果</li>
     * </ul>
     * <p><b>【引雷】落不到近战上</b>：它的实现是 {@code BallThunder.release(level, ball)}，
     * 必须要一个<b>球实体</b>才能算落点与雷击；史莱姆手上那颗是物品，没有实体可传。
     * 剩下那些（穿透 / 破坏王 / 弹性 / 磁性 / 变形 / 照明 / 透镜 / 重量 / 初速度 …）
     * 全是<b>飞行时才成立</b>的，近战没有对应含义，按作者 2026-10-11 的裁定不做。</p>
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof Slime slime)) {
            return;
        }
        LivingEntity victim = event.getEntity();
        // 自己打自己（摔落 / 反伤之类）不算
        if (slime == victim) {
            return;
        }
        ItemStack hand = slime.getMainHandItem();
        if (!BallAmmo.isBall(hand)) {
            return;
        }

        BallBehavior.BallProfile profile = BallBehavior.profileFor(hand);

        // ① 攻击力数值：与原有的相加
        float bonus = profile.damage();
        if (bonus > 0.0F) {
            event.setAmount(event.getAmount() + bonus);
        }

        // ② 能落在近战上的特性词条
        if (victim.level() instanceof ServerLevel level) {
            applyMeleeTraits(level, slime, victim, profile);
        }
    }

    /**
     * 把球上「能落在近战上」的词条施加给目标。
     *
     * <p>⚠️ <b>【熔融】的判据放宽了</b>：球那条路径要求
     * {@code heat >= moltenThreshold}（得先在矿脉附近飞一会儿把矿石烤热）。
     * 史莱姆手上那颗球<b>没有热量这个概念</b>，所以这里只看「这颗球带不带熔融词条」。
     * 不这么放宽的话，那颗空心铁球（阈值 1000）在史莱姆手里永远不生效。</p>
     */
    private static void applyMeleeTraits(ServerLevel level, LivingEntity attacker,
                                         LivingEntity victim, BallBehavior.BallProfile profile) {
        // 【熔融】→ 熔融物烧伤
        if (profile.hasMolten()) {
            MoltenBurnEffect.applyMoltenBurn(level, victim, attacker, BallBehavior.MOLTEN_BURN_TICKS);
        }
        // 【魔法】→ 随机负面效果
        if (profile.hasFlag(BallBehavior.BallProfile.FLAG_MAGIC)) {
            BallMagic.applyToVictim(level, victim);
        }
    }
}