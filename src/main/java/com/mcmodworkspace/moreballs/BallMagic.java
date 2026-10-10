package com.mcmodworkspace.moreballs;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 【魔法】—— 青金石球的词条（作者 2026-10-10 指定）。
 *
 * <h2>效果规格</h2>
 * <ul>
 *   <li><b>命中目标</b>：随机施加一个 <b>1–20 秒、1–5 级</b>的负面效果</li>
 *   <li><b>回收成功</b>：对主人随机施加一个 <b>1–20 秒、1–5 级</b>的正面效果
 *       —— <b>不含本模组的效果</b>（【不灭】太强，抽到就等于白送无敌）</li>
 *   <li><b>0.1% 大奖</b>：给主人挂 <b>60 秒【不灭】</b>，并播放不死图腾的音效与粒子</li>
 * </ul>
 *
 * <h2>效果池怎么来的</h2>
 * <p>不写死列表 —— 直接遍历 {@link BuiltInRegistries#MOB_EFFECT}，
 * 按 {@link MobEffectCategory} 分类。这样：</p>
 * <ul>
 *   <li>原版以后加新效果，这里自动跟上</li>
 *   <li>其它模组加的效果也会被纳入（更「随机」）</li>
 * </ul>
 *
 * <p>但要排掉几类：</p>
 * <table border="1">
 *   <tr><th>排掉的</th><th>为什么</th></tr>
 *   <tr><td><b>瞬时的</b>（瞬间伤害 / 瞬间治疗）</td>
 *       <td>它们没有「持续时间」这个概念，传秒数无意义</td></tr>
 *   <tr><td><b>本模组自己的效果</b></td>
 *       <td>作者明确要求「不包含本 mod 的」——【不灭】是 0.1% 大奖的专属奖励</td></tr>
 *   <tr><td><b>不祥之兆 / 袭击之兆 / 试炼之兆 / 村庄英雄</b></td>
 *       <td>这些是给「事件」用的状态标记，玩家身上挂着会引发袭击之类的连锁反应，
 *           不该由一个球随机塞给人</td></tr>
 * </table>
 */
public final class BallMagic {

    private BallMagic() {
    }

    /** 负面 / 正面效果的时长范围（秒）—— 作者指定 1–20 */
    private static final int MIN_SECONDS = 1;
    private static final int MAX_SECONDS = 20;

    /** 等级范围（1–5 级）—— 作者指定，含两端 */
    private static final int MIN_AMPLIFIER = 0;   // 内部 0 = 游戏内 I 级
    private static final int MAX_AMPLIFIER = 4;   // 内部 4 = 游戏内 V 级

    /**
     * 大奖概率的分母：<b>2 → 50%</b>。
     *
     * <p>⚠️ <b>这是作者 2026-10-10 为了「看一眼大奖特效」临时调高的调试值，
     * 正式概率是 1000（0.1%）。测完必须改回 1000。</b></p>
     *
     * <p>用整数分母而不是浮点比较 —— 浮点 {@code r < 0.001} 在概率恰好相等时
     * 行为不直观，整数写法更好读也更好调。</p>
     */
    private static final int JACKPOT_DENOMINATOR = 2;

    /** 这些效果虽然分类是对的，但不适合由球随机施加（事件状态标记） */
    private static final List<String> EXCLUDED = List.of(
            "bad_omen",
            "raid_omen",
            "trial_omen",
            "hero_of_the_village"
    );

    /**
     * 命中目标：施加一个随机的负面效果。
     *
     * @return 实际施加的效果（没施加则返回 {@code null}，便于调用方决定要不要出粒子）
     */
    public static MobEffectInstance applyToVictim(ServerLevel level, LivingEntity target) {
        MobEffectInstance picked = roll(level.getRandom(), MobEffectCategory.HARMFUL);
        if (picked == null) {
            return null;
        }
        target.addEffect(picked);
        return picked;
    }

    /**
     * 回收成功：对主人施加一个随机的正面效果；<b>有 0.1% 概率改判成大奖</b>。
     *
     * <p>大奖 = 60 秒【不灭】+ 不死图腾的音效与粒子。</p>
     *
     * @return 抽中大奖时为 {@code true}
     */
    public static boolean applyToOwner(ServerLevel level, LivingEntity owner) {
        if (level.getRandom().nextInt(JACKPOT_DENOMINATOR) == 0) {
            grantJackpot(level, owner);
            return true;
        }

        MobEffectInstance picked = roll(level.getRandom(), MobEffectCategory.BENEFICIAL);
        if (picked != null) {
            owner.addEffect(picked);
        }
        return false;
    }

    /**
     * 大奖：60 秒【不灭】+ 不死图腾破碎的音效与粒子。
     *
     * <p>音效与粒子直接借原版不死图腾那一套 —— 玩家对这个组合的印象很明确
     * （「刚刚有人救我一条命」），比自造一个特效更好懂。</p>
     */
    public static void grantJackpot(ServerLevel level, LivingEntity owner) {
        owner.addEffect(new MobEffectInstance(
                ModEffects.IMMORTAL,
                ImmortalEffect.REWARD_TICKS,
                0));

        // 音效：原版不死图腾触发时的那个声音
        level.playSound(null, owner.getX(), owner.getY(), owner.getZ(),
                SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.0F);

        // 粒子：不死图腾那一圈绿色光点
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                owner.getX(), owner.getY() + owner.getBbHeight() * 0.5D, owner.getZ(),
                40, 0.5D, 0.7D, 0.5D, 0.35D);
    }

    /**
     * 按分类掷一个随机效果。
     *
     * <p>时长与等级都是「范围内随机」—— 各自独立掷，不是固定搭配。</p>
     */
    private static MobEffectInstance roll(RandomSource random, MobEffectCategory category) {
        List<Holder<MobEffect>> pool = poolFor(category);
        if (pool.isEmpty()) {
            return null;
        }
        Holder<MobEffect> effect = pool.get(random.nextInt(pool.size()));

        int seconds = MIN_SECONDS + random.nextInt(MAX_SECONDS - MIN_SECONDS + 1);
        int amplifier = MIN_AMPLIFIER + random.nextInt(MAX_AMPLIFIER - MIN_AMPLIFIER + 1);

        // 秒 → 刻
        return new MobEffectInstance(effect, seconds * 20, amplifier);
    }

    /**
     * 取出某个分类下所有「可以随机施加」的效果。
     *
     * <p>每次调用都重新遍历注册表 —— 这个池子会被别的模组动态扩充，
     * 缓存下来反而会在模组加载顺序不同的情况下漏掉东西。
     * 一次遍历几十个条目的开销可以忽略（这方法只在命中/回收时调用一次）。</p>
     */
    private static List<Holder<MobEffect>> poolFor(MobEffectCategory category) {
        List<Holder<MobEffect>> pool = new ArrayList<>();
        Registry<MobEffect> registry = BuiltInRegistries.MOB_EFFECT;

        for (MobEffect effect : registry) {
            if (effect.getCategory() != category) {
                continue;
            }
            if (effect.isInstantaneous()) {
                continue;                                   // 瞬时的没有持续时长
            }
            Identifier id = registry.getKey(effect);
            if (id == null) {
                continue;
            }
            if (MoreBalls.MOD_ID.equals(id.getNamespace())) {
                continue;                                   // 作者要求：排除本模组的效果
            }
            if (EXCLUDED.contains(id.getPath())) {
                continue;                                   // 事件状态标记，不适合随机给
            }
            pool.add(registry.wrapAsHolder(effect));
        }
        return pool;
    }
}