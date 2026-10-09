package com.mcmodworkspace.moreballs;

import net.minecraft.core.registries.Registries;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;

/**
 * 本模组的附魔 —— 全部数据驱动，定义在 {@code data/more_balls/enchantment/}。
 *
 * <p>这里只做两件事：提供 {@link ResourceKey}，以及把等级换算成实际效果。</p>
 *
 * <h2>现有的四条</h2>
 * <ul>
 *   <li><b>强弩</b> {@code powerful_crossbow} —— 每级 +30% 基础发射距离（改的是射程）</li>
 *   <li><b>轻盈</b> {@code lightness} —— 弹药<b>重量减半向上取整</b>，最低 1（1 级）</li>
 *   <li><b>极速</b> {@code swiftness} —— 每级提升弹药<b>初速度</b>（5 级，累积 +70%）</li>
 *   <li><b>空气动力球</b> {@code aerodynamic_ball} —— 未破碎的弹药静止后自动飞回作者</li>
 * </ul>
 *
 * <p>强弩与极速都作用于「出手速度」，但路子不同：强弩走
 * {@link BallProjectile#setLaunchMultiplier} 那条射程换算，极速直接叠在初速度上。
 * 两者是<b>相乘</b>的，同时附上会叠得更远。</p>
 */
public final class ModEnchantments {

    private ModEnchantments() {
    }

    // ===== 强弩 =====

    /** 每级提升的基础发射距离比例（+30%） */
    public static final float RANGE_BONUS_PER_LEVEL = 0.30F;

    /** 强弩最高等级 */
    public static final int MAX_LEVEL = 3;

    /** {@code more_balls:powerful_crossbow} */
    public static final ResourceKey<Enchantment> POWERFUL_CROSSBOW = key("powerful_crossbow");

    // ===== 轻盈 =====

    /** {@code more_balls:lightness} —— 1 级，弹药重量减半向上取整，最低 1 */
    public static final ResourceKey<Enchantment> LIGHTNESS = key("lightness");

    /** 【轻盈】的重量下限（作者指定：最低 1） */
    public static final int LIGHTNESS_MIN_WEIGHT = 1;

    // ===== 极速 =====

    /** {@code more_balls:swiftness} —— 5 级，每级提升弹药初速度 */
    public static final ResourceKey<Enchantment> SWIFTNESS = key("swiftness");

    /**
     * 【极速】每级对<b>初速度</b>的加成（作者指定 10/10/15/15/20）。
     *
     * <p>这是<b>逐级累积</b>的：1 级 +10%、2 级 +20%、3 级 +35%、4 级 +50%、5 级 +70%。</p>
     */
    private static final float[] SWIFTNESS_BONUS_PER_LEVEL = {0.10F, 0.10F, 0.15F, 0.15F, 0.20F};

    // ===== 空气动力球 =====

    /** {@code more_balls:aerodynamic_ball} —— 1 级，弹药静止后自动回归作者 */
    public static final ResourceKey<Enchantment> AERODYNAMIC_BALL = key("aerodynamic_ball");

    /** 【空气动力球】回到作者身边时对作者的物理伤害（作者指定：1 滴血） */
    public static final float AERODYNAMIC_SELF_DAMAGE = 1.0F;

    /**
     * 本模组添加的全部附魔，按创造模式里想展示的顺序排。
     *
     * <p>供创造模式物品栏摆「附魔书」用 —— 原版附魔书靠 {@code stored_enchantments}
     * 组件承载附魔，这里逐个包成 ItemStack 展示。</p>
     */
    public static final List<ResourceKey<Enchantment>> ALL = List.of(
            POWERFUL_CROSSBOW, LIGHTNESS, SWIFTNESS, AERODYNAMIC_BALL);
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
}
