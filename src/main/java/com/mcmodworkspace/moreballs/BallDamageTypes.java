package com.mcmodworkspace.moreballs;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * <b>【穿甲】用的伤害类型</b>（作者 2026-10-16 指定的词条）。
 *
 * <h2>它要满足的语义</h2>
 * <blockquote>
 * 携带本词条的球造成的所有伤害，包括其他词条内未说明伤害类型的伤害，全都变为
 * <b>无视护甲的物理伤害</b>（无视护甲值，但是<b>可以被针对物理伤害的减伤减免或免疫</b>）。
 * </blockquote>
 *
 * <h2>为什么是「新建一个类型」，而不是复用原版的某个类型</h2>
 * <ol>
 *   <li>「无视护甲」在 26.2 <b>只有一个落点</b> ——
 *       {@code LivingEntity.getDamageAfterArmorAbsorb}（第 1977 行）
 *       的 {@code if (!damageSource.is(DamageTypeTags.BYPASSES_ARMOR))}。
 *       整个伤害流程里没有任何别处读护甲值 ⇒ 想绕过护甲，<b>只能改伤害类型</b>。</li>
 *   <li>而 {@code minecraft:bypasses_armor} 是<b>全局标签</b> —— 往里面塞
 *       {@code player_attack} / {@code mob_attack} / {@code thrown} 会让
 *       <b>全世界所有人的普通攻击都无视护甲</b>，绝不能那么干。
 *       所以必须新建一个自有类型，再把它 append 进那个标签。</li>
 * </ol>
 *
 * <h2>「仍受物理减伤减免」是怎么落地的</h2>
 * <p>⚠️ 26.2 <b>没有「物理伤害」这个聚合标签</b>：原版 {@code DamageTypeTags} 里没有
 * {@code IS_PHYSICAL}；NeoForge 虽然提供了 {@code neoforge:is_physical}，但
 * <b>引擎零处读取</b>（只在 {@code Tags} 里声明、在数据生成器里写入），塞进去不产生任何减免。</p>
 *
 * <p>所以这里用<b>语义等价</b>的做法 —— 让这个新类型：</p>
 * <ul>
 *   <li><b>进</b> {@code minecraft:bypasses_armor} ⇒ 护甲值失效；</li>
 *   <li><b>不进</b> {@code bypasses_resistance} ⇒ 抗性提升照常生效；</li>
 *   <li><b>不进</b> {@code bypasses_enchantments} ⇒ 保护附魔照常生效；</li>
 *   <li><b>不进</b> {@code bypasses_invulnerability} ⇒ 该免疫的还免疫。</li>
 * </ul>
 * <p>后三条正是「可以被针对物理伤害的减伤减免或免疫」的实现 ——
 * 原版这两条减免的判据本来也不是「物理白名单」，而是各自的黑名单：
 * 抗性提升看 {@code !is(BYPASSES_RESISTANCE)}，保护附魔看 enchantment JSON 里的
 * {@code bypasses_invulnerability = false}。只要不入黑名单，它们就会正常工作。</p>
 *
 * <h2>⚠️ 它同时修掉了【尖啸】的一个问题</h2>
 * <p>原先【尖啸】用的是 {@code damageSources().sonicBoom(...)}，而
 * {@code minecraft:sonic_boom} <b>同时躺在 {@code bypasses_armor} 与
 * {@code bypasses_enchantments} 两个标签里</b> —— 也就是说它不但无视护甲，
 * 还<b>连保护附魔都一起跳过了</b>。改用本类型之后，音波伤害变成
 * 「无视护甲、但吃保护附魔与抗性」，与【尖啸】的规格一致。</p>
 */
public final class BallDamageTypes {

    private BallDamageTypes() {
    }

    /**
     * {@code more_balls:true_physical} —— <b>真实物理伤害</b>（作者 2026-10-16 命名）。
     *
     * <p>原版没有「物理伤害」这个聚合类型，所以本模组自建一个：
     * 登记为物理（{@code neoforge:is_physical}），但被护甲值无视
     * （{@code minecraft:bypasses_armor}）。</p>
     */
    public static final ResourceKey<DamageType> TRUE_PHYSICAL = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "true_physical"));

    /**
     * 用「真实物理伤害」类型构造一次伤害。
     *
     * <p>归属照旧记在投掷者头上（{@code directEntity} 是那件造成伤害的东西，
     * {@code causingEntity} 是主人）—— 否则击杀不计在主人头上、仇恨也会记错。</p>
     */
    public static DamageSource truePhysical(Level level, Entity direct, Entity owner) {
        var types = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
        var type = types.getOrThrow(TRUE_PHYSICAL);
        if (owner != null && direct != null) {
            return new DamageSource(type, direct, owner);
        }
        if (owner != null) {
            return new DamageSource(type, owner);
        }
        return direct != null ? new DamageSource(type, direct) : new DamageSource(type);
    }
}
