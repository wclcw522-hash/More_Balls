package com.mcmodworkspace.moreballs.entity;

import com.mcmodworkspace.moreballs.ModEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * <b>滴水石锥</b> —— 滴水石球的【尖刺】射出去的那些锥子（作者 2026-10-15 指定）。
 *
 * <h2>规格（作者原话）</h2>
 * <blockquote>
 * 滴水石球在击中、碰撞和破碎时，会以垂直于速度的面为基准面，向基准面的 8 个方向
 * 以基准面与最终速度反方向夹角 60 度的角度、2 格的最高点发射 8 枚最小的滴水石锥，
 * 该滴水石锥基础伤害为 3，其余伤害计算遵循原版。
 * </blockquote>
 * 几何在 {@code BallExtraTraits.shootSpikes} 里算，本类只管「飞出去、打中、消失」。
 *
 * <h2>为什么直接拿原版投掷物当基类</h2>
 * <ul>
 *   <li>它自带抛物线（默认重力 0.03、空气阻力 0.99）、命中射线、入水减速 —— 都是原版行为，
 *       「其余伤害计算遵循原版」这条要的正是这个</li>
 *   <li>它携带一个物品栈，<b>客户端可以直接用原版投掷物渲染器画出来</b> ——
 *       外观就是「一滴水石锥」，不用我们写任何模型与渲染代码</li>
 *   <li>伤害走 {@code damageSources().thrown(...)}，护甲、附魔、抗性提升照常参与结算</li>
 * </ul>
 *
 * <p>作者确认过的两条：<b>命中即消失、不落地变成方块</b>；
 * 归属（第二个参数）记在射手头上。</p>
 */
public class DripstoneShard extends ThrowableItemProjectile {

    /** 基础伤害（作者指定 3） */
    public static final float BASE_DAMAGE = 3.0F;

    /**
     * 生存时间（刻）—— 飞够这么久还没命中就自己消失。
     *
     * <p>作者没给这个数：纯兜底，免得没打中东西的锥子一直躺在世界里。
     * 一秒半足够飞完全程并落下。</p>
     */
    private static final int LIFETIME_TICKS = 30;

    public DripstoneShard(EntityType<? extends DripstoneShard> type, Level level) {
        super(type, level);
    }

    /**
     * 由【尖刺】构造并射出去。
     *
     * @param position 出手点
     * @param velocity 初速度向量（方向 + 速率已在外面算好）
     * @param owner    射手（会被记进伤害归属，可以为 null）
     */
    public DripstoneShard(Level level, Vec3 position, Vec3 velocity, LivingEntity owner) {
        super(ModEntities.DRIPSTONE_SHARD.get(), position.x, position.y, position.z, level,
                new ItemStack(Items.POINTED_DRIPSTONE));
        this.setOwner(owner);
        this.setDeltaMovement(velocity);
    }

    /** 外观物品 —— 客户端渲染器画的就是它 */
    @Override
    protected Item getDefaultItem() {
        return Items.POINTED_DRIPSTONE;
    }

    /**
     * 命中生物：结算 3 点基础伤害。
     *
     * <p>不伤害射手自己 —— 锥子是从球的位置朝四面炸开的，球的主人很可能就站在旁边。</p>
     *
     * <h2>🌟 无视无敌帧（作者 2026-10-16 要求）</h2>
     * <p>原版 {@code LivingEntity.hurtServer} 在 {@code invulnerableTime > 0} 时会把
     * 「伤害不超过上一次」的攻击<b>直接丢掉</b> —— 这就是无敌帧。而【尖刺】一次射出
     * <b>8 枚</b>锥子，它们几乎必然在<b>同一刻</b>命中同一个目标：不清零的话只有第一枚算数，
     * 另外七枚全被吃掉。</p>
     *
     * <p>做法是在出手前把目标的 {@code invulnerableTime} 清零。
     * <b>它只影响这一下</b> —— 命中成功后原版自己会把无敌时间重设回 20，
     * 所以不会给别的伤害开后门（不会出现「别人也能连打」的副作用）。</p>
     */
    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        Entity entity = hitResult.getEntity();
        if (entity == this.getOwner()) {
            return;
        }
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        DamageSource source = this.damageSources().thrown(this, this.getOwner());

        if (!(entity instanceof LivingEntity living)) {
            entity.hurtServer(serverLevel, source, BASE_DAMAGE);
            return;
        }

        // ===== 🌟 无视无敌帧（作者 2026-10-16）=====
        //
        // 原版 hurtServer 在 invulnerableTime > 0 时会把「伤害不超过上一次」的攻击**直接丢掉**，
        // 而【尖刺】一次射出的 8 枚锥子几乎必然**同一刻**命中同一个目标 ——
        // 第一枚过后无敌被设成 20，后面几枚就全废。
        //
        // 规则（三条都必要）：
        //   ① **只在目标真的带着无敌帧时才动它** —— 本来就没无敌就完全不碰，零副作用；
        //   ② 清零必须发生在**出手之前**；
        //   ③ 这一下没造成伤害就把原值**原样还回去**。
        //
        // ⚠️ 为什么不能写成「先正常打、成功后再清零」：那样在目标带着剩余无敌帧时，
        //    这一下会**先被无敌帧挡掉**（连伤害都不会产生），石锥根本打不进去 ——
        //    与「不受无敌帧影响」正好相反。
        //
        // ⚠️ 也**不能**在成功后清零：原版成功受击后会把无敌重设回 20，把它抹掉等于
        //    把目标这段时间的保护整体取消 ⇒ 下一 tick 起**别人的**攻击也能连打，
        //    那才是真正的「开后门」。
        if (living.invulnerableTime > 0) {
            int saved = living.invulnerableTime;
            living.invulnerableTime = 0;
            if (!living.hurtServer(serverLevel, source, BASE_DAMAGE)) {
                living.invulnerableTime = saved;   // 没打中 → 原样还回去
            }
        } else {
            living.hurtServer(serverLevel, source, BASE_DAMAGE);   // 本来就没无敌 → 不碰
        }
    }

    /** 命中（生物或方块都一样）：播石头破碎音，然后消失 */
    @Override
    protected void onHit(HitResult hitResult) {
        super.onHit(hitResult);
        if (this.level().isClientSide()) {
            return;
        }
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.POINTED_DRIPSTONE_BREAK, SoundSource.NEUTRAL, 0.7F, 1.2F);
        this.discard();
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide() && this.tickCount > LIFETIME_TICKS) {
            this.discard();
        }
    }
}
