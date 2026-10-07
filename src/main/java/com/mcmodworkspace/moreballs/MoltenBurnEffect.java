package com.mcmodworkspace.moreballs;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * 【熔融物烧伤】—— 五级的持续灼烧效果（作者 2026-10-07 指定的规格）。
 *
 * <h2>效果本身</h2>
 * <ul>
 *   <li>持续时间内让拥有者<b>一直烧着</b></li>
 *   <li>每秒结算一次伤害，五级分别是 <b>2 / 4 / 7 / 10 / 15</b> 点</li>
 *   <li>伤害是<b>火焰伤害，但不受火焰保护减免</b>；抗火效果与天生火焰免疫照样挡得住</li>
 * </ul>
 *
 * <h2>挂上去的时候怎么算（作者指定）</h2>
 * <ul>
 *   <li><b>时长</b> = 基础时长 × 目标身上的<b>金属装备数</b>（金属越多、导热越久）</li>
 *   <li><b>等级</b>上升 = 目标的<b>护甲值 ÷ 15</b>，向下取整，不超过五级</li>
 * </ul>
 *
 * <h2>「不受火焰保护，但被抗火免疫」怎么做到</h2>
 * <p>原版的火焰保护是挂在伤害类型的 {@code minecraft:is_fire} 标签上判定的，
 * 而抗火效果同样是查这个标签 —— 两者绑在一起，没法只满足一半。
 * 所以这里用<b>自己的伤害类型</b> {@code more_balls:molten_burn}（不进货标签），
 * 于是火焰保护自然不减免；抗火与火焰免疫则在
 * {@link #hurt} 里<b>手动判定一次</b>，把那半边的语义补齐。</p>
 */
public class MoltenBurnEffect extends MobEffect {

    /** 英文 id（作者指定） */
    public static final String EFFECT_ID = "molten_burn";

    /**
     * 最大等级：五级。
     *
     * <p>内部 amplifier 从 0 起算，所以上限是 {@code 4}。</p>
     */
    public static final int MAX_AMPLIFIER = 4;

    /** 每一级的「每秒真实火焰伤害」（作者指定：2 / 4 / 7 / 10 / 15） */
    private static final float[] DAMAGE_PER_SECOND = {2.0F, 4.0F, 7.0F, 10.0F, 15.0F};

    /** 护甲值换算等级的分母：等级上升 = 护甲值 ÷ 15，向下取整（作者指定） */
    public static final float ARMOR_PER_LEVEL = 15.0F;

    /** 伤害间隔（刻）—— 每秒结算一次 */
    private static final int DAMAGE_INTERVAL = 20;

    /** 续燃时长（刻）—— 每刻把剩余燃烧时间顶到这么多，保证效果期间一直在烧 */
    private static final int REFRESH_FIRE_TICKS = 60;

    /** 伤害类型 {@code more_balls:molten_burn} —— 故意不进 {@code is_fire} 标签 */
    public static final ResourceKey<DamageType> DAMAGE_TYPE = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, EFFECT_ID));

    public MoltenBurnEffect() {
        // 亮红色 —— 这个颜色同时也是粒子色的来源（作者指定亮红色药水粒子）
        super(MobEffectCategory.HARMFUL, 0xFFFF2A00);
    }

    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
        // 一直烧着 —— 顶起剩余燃烧时间，别让它自然灭
        entity.setRemainingFireTicks(Math.max(entity.getRemainingFireTicks(), REFRESH_FIRE_TICKS));

        if (entity.tickCount % DAMAGE_INTERVAL == 0) {
            hurt(level, entity, amplifier, null);
        }
        return true;
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;   // 每刻都要跑：着火状态得持续续上
    }

    // ===== 挂效果 =====

    /**
     * 把【熔融物烧伤】挂到目标身上 —— <b>时长与等级都按目标当场的情况算</b>。
     *
     * <p>身上一件金属装备都没有的目标<b>不会</b>被点着：这套设定里热量是靠金属导过去的，
     * 没有金属就没有导热途径（作者指定「时长 = 基础时长 × 金属装备数」，乘 0 就是 0）。</p>
     *
     * @param baseTicks 基础时长（刻）—— 空心铁球那颗是 {@link BallBehavior#MOLTEN_BURN_TICKS}
     * @return 是否真的挂上了
     */
    public static boolean applyMoltenBurn(ServerLevel level, LivingEntity target,
                                          @Nullable LivingEntity source, int baseTicks) {
        int metal = BallProspecting.countMetalEquipment(target);
        if (metal <= 0) {
            return false;
        }
        int duration = baseTicks * metal;
        int amplifier = Math.min(MAX_AMPLIFIER, (int) (target.getArmorValue() / ARMOR_PER_LEVEL));
        target.addEffect(new MobEffectInstance(ModEffects.MOLTEN_BURN, duration, amplifier), source);
        return true;
    }

    // ===== 结算伤害 =====

    /**
     * 结算一次灼烧伤害。
     *
     * <p>抗火效果与天生火焰免疫（烈焰人、岩浆怪这类 {@code fireImmune} 的实体）会把这一下
     * 完全挡掉；除此之外没有减免 —— 火焰保护附魔不在判定链上。</p>
     */
    public static void hurt(ServerLevel level, LivingEntity target, int amplifier,
                            @Nullable LivingEntity source) {
        if (target.fireImmune() || target.hasEffect(MobEffects.FIRE_RESISTANCE)) {
            return;
        }
        float amount = DAMAGE_PER_SECOND[Mth.clamp(amplifier, 0, DAMAGE_PER_SECOND.length - 1)];
        var types = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
        DamageSource damage = source == null
                ? new DamageSource(types.getOrThrow(DAMAGE_TYPE))
                : new DamageSource(types.getOrThrow(DAMAGE_TYPE), source);
        target.hurtServer(level, damage, amount);
    }
}
