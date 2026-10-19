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
 *   <li><b>时长</b> = 基础时长 × (1 + 目标身上的<b>金属装备数</b>)（金属越多、导热越久）</li>
 *   <li><b>等级</b>上升 = 在现有等级上累加 ⌊护甲值 ÷ 10⌋，封顶五级</li>
 * </ul>
 *
 * <h2>「不受火焰保护，但被抗火免疫」怎么做到</h2>
 * <p>原版的火焰保护是挂在伤害类型的 {@code minecraft:is_fire} 标签上判定的，
 * 而抗火效果同样是查这个标签 —— 两者绑在一起，没法只满足一半。
 * 所以这里用<b>自己的伤害类型</b> {@code rotation_craft:molten_burn}（不进货标签），
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

    /**
     * 护甲值换算等级的**分母**。
     *
     * <p>作者 2026-10-09 指定：<b>每 10 点护甲值提升一级</b>（原来是 15）。</p>
     */
    public static final float ARMOR_PER_LEVEL = 10.0F;

    /** 伤害间隔（刻）—— 每秒结算一次 */
    private static final int DAMAGE_INTERVAL = 20;

    /** 续燃时长（刻）—— 每刻把剩余燃烧时间顶到这么多，保证效果期间一直在烧 */
    private static final int REFRESH_FIRE_TICKS = 60;

    /** 伤害类型 {@code rotation_craft:molten_burn} —— 故意不进 {@code is_fire} 标签 */
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
     * 没有金属就没有导热途径（作者指定「时长 = 基础时长 × (1 + 金属装备数)」，没有金属时该函数直接返回、根本挂不上）。</p>
     *
     * <p>⚠️ 这道金属门槛<b>只覆盖模组自己的两条挂载路径</b> ——
     * 球命中（{@code BallProjectile}）与热辐射（{@code BallHeatHandler}），
     * 它们都经本方法出门。而 {@code /effect give} 这类命令是直接调
     * {@code addEffect} 构造实例的，<b>一个字都不经过本方法</b>，
     * 所以即使目标全身无金属也照样挂得上（只受 {@code MoltenBurnApplication}
     * 的等级重算影响）。</p>
     *
     * <h2>等级怎么算（与上面那道金属门槛是**两道独立判据**）</h2>
     * <p>等级<b>只</b>看 {@link net.minecraft.world.entity.LivingEntity#getArmorValue()}
     * —— 它<b>只统计护甲栏</b>，拿在手上的武器、挂在 Curios 里的金属件都不算数。
     * 每 {@link #ARMOR_PER_LEVEL} 点护甲值提一级，<b>在现有等级上累加</b>，封顶 {@link #MAX_AMPLIFIER} 级。</p>
     *
     * <p>举例（前提：已经过了上面那道金属门槛）：<b>全套铁甲</b>护甲值 15 → ⌊15 / 10⌋ = 1，
     * 每次命中 amplifier +1；<b>下界合金甲</b>护甲值 20 → ⌊20 / 10⌋ = 2，每次命中 amplifier +2。
     * 连续命中继续累加，封顶 {@link #MAX_AMPLIFIER}（= 4，即游戏内 V 级）。</p>
     *
     * <p>封顶来得比直觉快：护甲值 20 的目标<b>第一次命中</b>就落到 amplifier 2（III 级），
     * <b>第二次命中 2 + 2 = 4 便已封顶 V 级</b>；而每次只 +1 的护甲值 10–19，
     * 要到<b>第四次命中</b>（0 → 1 → 2 → 3 → 4）才封顶。</p>
     *
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
        // ===== 时长：基础时长 × (1 + 金属装备数) =====
        //
        // ⚠️ 原来是 baseTicks * metal —— 只穿 1 件金属装等于**没有加成**，
        //    一件都没有时 baseTicks*0 直接归零。作者 2026-10-09 明确：
        //    倍率是 (1 + 件数)，穿一件就是 ×2。
        int duration = baseTicks * (1 + metal);

        // ===== 等级：现有等级 + ⌊护甲值 / 10⌋，封顶 5 级 =====
        //
        // 作者 2026-10-09 明确「**等级提升** ⌊护甲值/10⌋ 的数量」——
        // 是在**已有等级上累加**，不是每次覆盖成同一个值。
        // 原来每次都写 clamp(getArmorValue()+metal)/15，于是一身铁甲永远是同一个等级
        // （反馈「打上去等级还是固定的 2 级」就是这个）。
        //
        // 护甲值只看**护甲栏**（getArmorValue 的语义），加成的金属装备件数已经
        // 单独体现在时长上，不再重复进等级公式 —— 否则铁甲本身会被算两遍。
        int armorTiers = Math.max(0, (int) (target.getArmorValue() / ARMOR_PER_LEVEL));

        MobEffectInstance existing = target.getEffect(ModEffects.MOLTEN_BURN);
        int current = (existing == null) ? 0 : existing.getAmplifier();
        int amplifier = Mth.clamp(current + armorTiers, 0, MAX_AMPLIFIER);

        // 时长只增不减 —— 重复命中不该把已经在烧的时长缩短
        int newDuration = (existing == null) ? duration : Math.max(duration, existing.getDuration());

        target.addEffect(new MobEffectInstance(ModEffects.MOLTEN_BURN, newDuration, amplifier), source);
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
