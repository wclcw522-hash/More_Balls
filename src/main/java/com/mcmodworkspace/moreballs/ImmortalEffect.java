package com.mcmodworkspace.moreballs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 【不灭】—— 正面效果，持续期间持有者<b>免疫一切伤害</b>（含虚空），并且不会死亡。
 *
 * <h2>作者指定的规格</h2>
 * <ul>
 *   <li>正面 buff</li>
 *   <li>持续时间内不受<b>任何类型</b>的伤害 —— 包括虚空</li>
 *   <li>禁用「死亡」事件</li>
 *   <li>生命值最小为 <b>1</b></li>
 * </ul>
 *
 * <h2>四道防线，缺一不可</h2>
 * <ol>
 *   <li>{@link LivingIncomingDamageEvent} —— <b>在伤害落下来之前</b>取消掉。
 *       这是覆盖面最广的一道：普通伤害、摔落、火焰、窒息、<b>虚空</b>、
 *       甚至 {@code /kill} 走的都是这条路径，全部拦下。</li>
 *   <li>{@link LivingDeathEvent} —— 万一有伤害绕过了第一道（例如某些直接调
 *       {@code setHealth(0)} 的路径），这里再拦一次，并把血量顶回 1。</li>
 *   <li>{@link EntityTickEvent.Post} —— 每刻兜底，把低于 1 的血量抬回来。
 *       有些伤害来源会直接写血量而不过事件，这一道防的就是那种。</li>
 *   <li>{@link LivingHealEvent} —— <b>尝试项</b>：禁用自然回血与治疗效果。
 *       见下面「关于禁回血」的说明。</li>
 * </ol>
 *
 * <h2>关于「把死亡阈值设为 -1」—— <b>未实现</b></h2>
 * <p>原版没有「死亡阈值」这个概念：{@code LivingEntity} 的死亡判定是写死的
 * {@code getHealth() <= 0}，没有一个可配置的字段。要改成 -1 只能 Mixin 进
 * {@code isDeadOrDying()} / {@code tickDeath()} 这类核心方法 —— 那是全实体共用的
 * 热路径，一旦判断写歪，<b>整个世界所有生物都不死</b>，且极难排查。</p>
 *
 * <p>按作者「若实现需要大规模侵入或容易导致崩溃则不进行此项改动」的指示，
 * 这一项<b>不做</b>。上面四道防线已经能达到「持有者不会死、血量恒 ≥ 1」的效果，
 * 不需要动原版的死亡判定。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public class ImmortalEffect extends MobEffect {

    /** 英文 id（作者指定为 {@code immortal}） */
    public static final String EFFECT_ID = "immortal";

    /**
     * 「大奖」的时长：<b>60 秒</b> = 1200 刻。
     *
     * <p>青金石球的【魔法】词条抽中 0.1% 时，给主人挂的就是这么多。</p>
     */
    public static final int REWARD_TICKS = 1200;

    /** 血量下限（作者指定：最小为 1） */
    private static final float MIN_HEALTH = 1.0F;

    public ImmortalEffect() {
        // BENEFICIAL = 蓝框（正面），颜色取不死图腾那种金
        super(MobEffectCategory.BENEFICIAL, 0xF2D24B);
    }

    /**
     * 效果自身的 tick —— 这里什么都不用做。
     *
     * <p>三道防线分别在 {@link #onIncomingDamage}、{@link #onDeath} 与
     * {@link #onEntityTickPost} 上，不依赖这个回调。</p>
     */
    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity mob, int amplifier) {
        return true;
    }

    /**
     * <b>防线一：拦截一切伤害。</b>
     *
     * <p>这是覆盖面最广的一道 —— 虚空伤害（{@code outOfWorld}）走的也是
     * {@code hurtServer} → 这个事件，所以同样拦得住。</p>
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity().hasEffect(ModEffects.IMMORTAL)) {
            event.setCanceled(true);
        }
    }

    /**
     * <b>防线二：禁止死亡。</b>
     *
     * <p>拦下死亡事件并把血量顶回 1。这是「保险的保险」：正常路径上伤害已经被
     * 防线一挡掉了，但有些来源（直接把血量写成 0、或者绕过伤害事件的脚本）
     * 只会在这里露头。</p>
     */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.hasEffect(ModEffects.IMMORTAL)) {
            event.setCanceled(true);
            entity.setHealth(MIN_HEALTH);
        }
    }

    /**
     * <b>防线三：每刻兜底，血量恒 ≥ 1。</b>
     *
     * <p>放在 {@code EntityTickEvent.Post} 上 —— 这时本刻所有的伤害结算、
     * 治疗、状态更新都已经跑完，看到的血量是「最终值」。
     * 放在效果自身的 tick 回调里会偏早，可能被同刻后续的伤害再压下去。</p>
     */
    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || entity.level().isClientSide()) {
            return;
        }
        if (entity.hasEffect(ModEffects.IMMORTAL) && entity.getHealth() < MIN_HEALTH) {
            entity.setHealth(MIN_HEALTH);
        }
    }

    /**
     * <b>防线四（尝试项）：禁用血量回复。</b>
     *
     * <p>作者的原话是「同时禁用血量回复」。做法是取消 {@link LivingHealEvent} ——
     * 这一条<b>只影响自然回复与治疗效果</b>，不动任何原版逻辑，属于低风险改动，
     * 所以按指示实现。</p>
     *
     * <p>注意一个副作用：因为血被锁在 1，又禁了回血，持有【不灭】期间受到的伤害
     * 虽然会被拦下，但<b>血量也不会自己涨回来</b>。这是作者要的「不灭但脆弱」的
     * 手感 —— 效果一结束，血量还是 1。</p>
     */
    @SubscribeEvent
    public static void onHeal(LivingHealEvent event) {
        if (event.getEntity().hasEffect(ModEffects.IMMORTAL)) {
            event.setCanceled(true);
        }
    }
}
