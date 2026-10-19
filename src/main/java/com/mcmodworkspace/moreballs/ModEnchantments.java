package com.mcmodworkspace.moreballs;

import net.minecraft.core.registries.Registries;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;

/**
 * 本模组的附魔 —— 全部数据驱动，定义在 {@code data/rotation_craft/enchantment/}。
 *
 * <p>这里只做两件事：提供 {@link ResourceKey}，以及把等级换算成实际效果。</p>
 *
 * <h2>现有的五条</h2>
 * <ul>
 *   <li><b>强弩</b> {@code powerful_crossbow} —— 每级 +30% 基础发射距离（改的是射程）</li>
 *   <li><b>轻盈</b> {@code lightness} —— 弹药<b>重量减半向上取整</b>，最低 1（1 级）</li>
 *   <li><b>极速</b> {@code swiftness} —— 每级提升弹药<b>初速度</b>（5 级，累积 +70%）</li>
 *   <li><b>空气动力球</b> {@code aerodynamic_ball} —— 未破碎的弹药静止后自动飞回主人</li>
 *   <li><b>诸葛连弩</b> {@code repeater_crossbow} —— 按住右键即可连发；代价<b>三条</b>：
 *       每级 −15% 装填时间、每级削掉 30% 的装填移速惩罚、每级 −10% 最终伤害
 *       （3 级即 −30%）。见 {@link BallRepeaterCrossbow}</li>
 * </ul>
 *
 * <p>强弩与极速都作用于「出手速度」，但路子不同：强弩走
 * {@link BallProjectile#setLaunchMultiplier} 那条射程换算，极速直接叠在初速度上。
 * 两者是<b>相乘</b>的，同时附上会叠得更远。</p>
 *
 * <p>⚠️ 两者都<b>只放大射程</b>，不放大伤害：命中伤害是
 * {@code 基础伤害 × (击中速度 / 实际出手速度)}，而这两个倍率会一起进
 * {@link BallProjectile#shoot} 记下的实际出手速度 —— 出手瞬间比值恒为 1。</p>
 */
public final class ModEnchantments {

    private ModEnchantments() {
    }

    // ===== 强弩 =====

    /** 每级提升的基础发射距离比例（+30%） */
    public static final float RANGE_BONUS_PER_LEVEL = 0.30F;

    /** 强弩最高等级 */
    public static final int MAX_LEVEL = 3;

    /** {@code rotation_craft:powerful_crossbow} */
    public static final ResourceKey<Enchantment> POWERFUL_CROSSBOW = key("powerful_crossbow");

    // ===== 轻盈 =====

    /** {@code rotation_craft:lightness} —— 1 级，弹药重量减半向上取整，最低 1 */
    public static final ResourceKey<Enchantment> LIGHTNESS = key("lightness");

    /** 【轻盈】的重量下限（作者指定：最低 1） */
    public static final int LIGHTNESS_MIN_WEIGHT = 1;

    // ===== 极速 =====

    /** {@code rotation_craft:swiftness} —— 5 级，每级提升弹药初速度 */
    public static final ResourceKey<Enchantment> SWIFTNESS = key("swiftness");

    /**
     * 【极速】每级对<b>初速度</b>的加成（作者指定 10/10/15/15/20）。
     *
     * <p>这是<b>逐级累积</b>的：1 级 +10%、2 级 +20%、3 级 +35%、4 级 +50%、5 级 +70%。</p>
     */
    private static final float[] SWIFTNESS_BONUS_PER_LEVEL = {0.10F, 0.10F, 0.15F, 0.15F, 0.20F};

    // ===== 空气动力球 =====

    /** {@code rotation_craft:aerodynamic_ball} —— 1 级，弹药静止后自动回归主人 */
    public static final ResourceKey<Enchantment> AERODYNAMIC_BALL = key("aerodynamic_ball");

    /** 【空气动力球】回到主人身边时对主人的物理伤害（作者指定：1 滴血） */
    public static final float AERODYNAMIC_SELF_DAMAGE = 1.0F;

    /**
     * <b>「空气动力球」回收时打给主人的那一下，用它自己的伤害类型。</b>
     *
     * <h2>为什么要单独开一个伤害类型</h2>
     * <p>目的是<b>不产生击退</b>。原版 {@code LivingEntity.hurtServer} 里写着：</p>
     *
     * <pre>
     * if (!source.is(DamageTypeTags.NO_KNOCKBACK)) {
     *     this.dealDefaultKnockback(source, damage, blocked);
     * }
     * </pre>
     *
     * <p>而 {@code dealDefaultKnockback → knockback()} 一定会做两件事：</p>
     *
     * <pre>
     * this.setDeltaMovement(
     *     deltaMovement.x / 2.0 - deltaVector.x,                            // ← 水平速度减半
     *     this.onGround() ? Math.min(0.4, deltaMovement.y / 2.0 + power) : deltaMovement.y,
     *     deltaMovement.z / 2.0 - deltaVector.z);
     * </pre>
     *
     * <p><b>水平速度减半就是「疾跑被打断」的原因</b>；那个向上的分量就是推人的手感。
     * 所以只要让这一下落在 {@code minecraft:no_knockback} 标签里，整段就跳过了
     * （见 {@code data/minecraft/tags/damage_type/no_knockback.json}）。</p>
     *
     * <h2>为什么不用 LivingKnockBackEvent</h2>
     * <p>那个事件（{@code LivingEntity.knockback} 开头会 fire，cancel 掉就 return）
     * <b>不带 DamageSource</b> —— 实测它的 class 里既没有 {@code DamageSource} 也没有
     * {@code getSource}，拿不到「是不是这一下」的信息。要用它就得加一个「正在结算回收伤害」
     * 的旗标来配对，比开一个伤害类型更绕。</p>
     *
     * <p>这个做法与本项目已有的 {@code rotation_craft:molten_burn} 完全一致
     * （自有伤害类型 + 往原版 damage_type 标签里追加）。</p>
     */
    public static final ResourceKey<DamageType> RETURN_DAMAGE_TYPE = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "ball_return"));

    // ===== 诸葛连弩 =====

    /** {@code rotation_craft:repeater_crossbow} —— 3 级，按住右键连发 */
    public static final ResourceKey<Enchantment> REPEATER_CROSSBOW = key("repeater_crossbow");

    /** 【诸葛连弩】最高等级 */
    public static final int REPEATER_MAX_LEVEL = 3;

    /** 【诸葛连弩】每级削掉多少比例的装填移速惩罚（30%） */
    public static final float REPEATER_PENALTY_PER_LEVEL = 0.30F;

    // ---- 装填时长：与【快速装填】并存时的「乘算」----

    /**
     * <b>原版弩的基础装填时长（秒）。</b>
     *
     * <p>出处是 {@code CrossbowItem.getChargeDuration}：</p>
     *
     * <pre>
     * float duration = EnchantmentHelper.modifyCrossbowChargingTime(crossbow, user, 1.25F);
     * return Mth.floor(duration * 20.0F);
     * </pre>
     *
     * <p>那个 {@code 1.25F} 原版没有公开访问器，只能在这里记一份。
     * 它只在「两个附魔并存」时才会被用到（见 {@link #combinedChargeTicks}）。</p>
     */
    public static final float VANILLA_CHARGE_SECONDS = 1.25F;

    /**
     * <b>原版【快速装填】每级削掉的比例。</b>
     *
     * <p>原版 {@code quick_charge} 的写法是 {@code add -0.25/级}，而基础值是 1.25 ——
     * 而 {@code 1.25 − 0.25L} 跟 {@code 1.25 × (1 − 0.2L)} 是<b>完全等价</b>的。
     * 所以它完全可以当成「每级 ×0.8 的倍率」来看，这正是下面乘算公式的依据。</p>
     */
    public static final float QUICK_CHARGE_PER_LEVEL = 0.2F;

    /**
     * <b>【诸葛连弩】每级对装填时长的削减比例。</b>
     *
     * <p>⚠️ 必须与 {@code data/rotation_craft/enchantment/repeater_crossbow.json} 里
     * {@code crossbow_charge_time} 的 {@code factor.per_level_above_first}（{@code -0.15}）
     * 保持一致 —— <b>改一边就要改另一边</b>。</p>
     */
    public static final float REPEATER_CHARGE_PER_LEVEL = 0.15F;

    /**
     * 装填刻数的硬下限。
     *
     * <p>纯属防御：乘算公式在满配下的最低值是 {@code floor(1.25 × 0.4 × 0.55 × 20) = 5}，
     * 正常够不到这个下限。留着是防将来有人把某个系数调成 0。</p>
     */
    public static final int MIN_CHARGE_TICKS = 1;

    /** 原版 {@code minecraft:quick_charge} */
    public static final ResourceKey<Enchantment> QUICK_CHARGE = ResourceKey.create(
            Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath("minecraft", "quick_charge"));

    /**
     * 本模组添加的全部附魔，按创造模式里想展示的顺序排。
     *
     * <p>供创造模式物品栏摆「附魔书」用 —— 原版附魔书靠 {@code stored_enchantments}
     * 组件承载附魔，这里逐个包成 ItemStack 展示。</p>
     */
    public static final List<ResourceKey<Enchantment>> ALL = List.of(
            POWERFUL_CROSSBOW, LIGHTNESS, SWIFTNESS, AERODYNAMIC_BALL, REPEATER_CROSSBOW);
    private static ResourceKey<Enchantment> key(String path) {
        return ResourceKey.create(Registries.ENCHANTMENT,
                Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, path));
    }

    /**
     * 读取武器上某个附魔的等级。
     *
     * @return 0 表示没附这个魔；附魔数据缺失时同样返回 0（不会抛异常）
     */
    public static int levelOf(Level level, ItemStack weapon, ResourceKey<Enchantment> enchantment) {
        if (level == null || weapon == null || weapon.isEmpty()) {
            return 0;
        }
        return level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(enchantment)
                .map(holder -> weapon.getEnchantments().getLevel(holder))
                .orElse(0);
    }

    /** 读取武器上的「强弩」等级 */
    public static int levelOn(Level level, ItemStack weapon) {
        return levelOf(level, weapon, POWERFUL_CROSSBOW);
    }

    /**
     * 附魔等级 → 发射力度倍率（作用于初速度）。
     *
     * @return 无附魔时为 1.0；三级时为 √1.9 ≈ 1.378
     */
    public static float launchMultiplier(int enchantLevel) {
        if (enchantLevel <= 0) {
            return 1.0F;
        }
        int clamped = Math.min(enchantLevel, MAX_LEVEL);
        return (float) Math.sqrt(1.0D + RANGE_BONUS_PER_LEVEL * clamped);
    }

    // ===== 轻盈的效果 =====

    /** 武器上有没有「轻盈」 */
    public static boolean hasLightness(Level level, ItemStack weapon) {
        return levelOf(level, weapon, LIGHTNESS) > 0;
    }

    /**
     * 【轻盈】把重量减半、<b>向上取整</b>，且不低于 {@value #LIGHTNESS_MIN_WEIGHT}。
     *
     * <p>例：5 → 3、2 → 1、1 → 1。</p>
     */
    public static int lightnessWeight(int weight) {
        int halved = (weight + 1) / 2; // 整数除法天然向下取整，加 1 就变成向上取整
        return Math.max(LIGHTNESS_MIN_WEIGHT, halved);
    }

    // ===== 极速的效果 =====

    /** 武器上「极速」的等级 */
    public static int swiftnessLevel(Level level, ItemStack weapon) {
        return levelOf(level, weapon, SWIFTNESS);
    }

    /**
     * 【极速】等级 → 初速度倍率。
     *
     * @return 无附魔 1.0；五级 1.70
     */
    public static float swiftnessMultiplier(int enchantLevel) {
        if (enchantLevel <= 0) {
            return 1.0F;
        }
        float total = 0.0F;
        int levels = Math.min(enchantLevel, SWIFTNESS_BONUS_PER_LEVEL.length);
        for (int i = 0; i < levels; i++) {
            total += SWIFTNESS_BONUS_PER_LEVEL[i];
        }
        return 1.0F + total;
    }

    // ===== 空气动力球的效果 =====

    /** 武器上有没有「空气动力球」 */
    public static boolean hasAerodynamicBall(Level level, ItemStack weapon) {
        return levelOf(level, weapon, AERODYNAMIC_BALL) > 0;
    }

    /**
     * 回收伤害的 {@link DamageSource} —— <b>归属仍然是主人</b>，
     * 所以「被自己的球砸死」这类击杀照记在他头上，成就判定不受影响。
     */
    public static DamageSource returnDamage(Level level, Entity owner) {
        var types = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource(types.getOrThrow(RETURN_DAMAGE_TYPE), owner);
    }

    // ===== 诸葛连弩的效果 =====

    /** 武器上「诸葛连弩」的等级（0 = 没附） */
    public static int repeaterCrossbowLevel(Level level, ItemStack weapon) {
        return levelOf(level, weapon, REPEATER_CROSSBOW);
    }

    /** 等级 → 装填期间的移动速度倍率。 */
    public static float repeaterUseSpeedMultiplier(float vanilla, int enchantLevel) {
        if (enchantLevel <= 0) {
            return vanilla;
        }
        int clamped = Math.min(enchantLevel, REPEATER_MAX_LEVEL);
        float penalty = 1.0F - vanilla;
        float left = penalty * (1.0F - REPEATER_PENALTY_PER_LEVEL * clamped);
        return Math.min(1.0F, 1.0F - left);
    }

    // ===== 装填时长：乘算 =====

    /** 武器上原版【快速装填】的等级（0 = 没附） */
    public static int quickChargeLevel(Level level, ItemStack weapon) {
        return levelOf(level, weapon, QUICK_CHARGE);
    }

    /** 【诸葛连弩】等级 → 装填时间倍率（1 − 0.15L）；无附魔时为 1.0 */
    public static float repeaterChargeFactor(int enchantLevel) {
        int clamped = Math.max(0, Math.min(enchantLevel, REPEATER_MAX_LEVEL));
        return 1.0F - REPEATER_CHARGE_PER_LEVEL * clamped;
    }

    /** 【诸葛连弩】每级削减的**最终伤害**比例（作者 2026-10-12 指定：10%） */
    public static final float REPEATER_DAMAGE_PER_LEVEL = 0.10F;

    /**
     * 【诸葛连弩】等级 → **最终伤害倍率**（1 - 0.10L）；无附魔时为 1.0。
     *
     * <p>作者 2026-10-12 指定：每级降低 10% 最终伤害 —— 3 级就是 -30%。
     * 与 REPEATER_CHARGE_PER_LEVEL（装填时间）、REPEATER_PENALTY_PER_LEVEL（装填移速惩罚）
     * 同属这一条附魔的三条代价，数值各自独立，改一个不影响另外两个。</p>
     */
    public static float repeaterDamageFactor(int enchantLevel) {
        int clamped = Math.max(0, Math.min(enchantLevel, REPEATER_MAX_LEVEL));
        return 1.0F - REPEATER_DAMAGE_PER_LEVEL * clamped;
    }

    /**
     * <b>【诸葛连弩】与【快速装填】并存时的装填刻数 —— 乘算。</b>
     *
     * <h2>为什么必须有这个方法</h2>
     * <p>两个附魔改的是同一个量，但用的运算不同、又都由原版按「附魔在物品上的顺序」依次作用：</p>
     *
     * <pre>
     * 【快速装填】是 add：       time += -0.25 × L
     * 【诸葛连弩】是 multiply：  time *= 1 − 0.15 × L
     * </pre>
     *
     * <p>而 {@code EnchantmentHelper.runIterationOnItem} 遍历的是
     * {@code ItemEnchantments} 底层的 {@code Reference2IntOpenHashMap} —— <b>无序</b>。
     * 于是同一把武器可能算出两种结果：</p>
     *
     * <pre>
     * 快速装填先算：(1.25 − 0.75) × 0.55 = 0.275 秒
     * 诸葛连弩先算：(1.25 × 0.55) − 0.75 = −0.0625 → 被原版 Math.max(0, ·) 夹成 0
     * </pre>
     *
     * <p><b>乘算就没有这个问题</b>——乘法可交换，顺序无关：</p>
     *
     * <pre>
     * 装填时长 = 1.25 × (1 − 0.2 × 快速装填级) × (1 − 0.15 × 诸葛连弩级)
     * </pre>
     *
     * <p>满配（两边各 3 级）= {@code 1.25 × 0.4 × 0.55 = 0.275 秒 → 5 刻}，
     * 既不会出现 0 刻瞬发，也不会因附魔顺序而变。</p>
     *
     * @param quickLevel    原版【快速装填】等级
     * @param repeaterLevel 【诸葛连弩】等级
     */
    public static int combinedChargeTicks(int quickLevel, int repeaterLevel) {
        float factor = (1.0F - QUICK_CHARGE_PER_LEVEL * quickLevel) * repeaterChargeFactor(repeaterLevel);
        return Math.max(MIN_CHARGE_TICKS, Mth.floor(VANILLA_CHARGE_SECONDS * factor * 20.0F));
    }
}
