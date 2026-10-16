package com.mcmodworkspace.moreballs.entity;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

/**
 * <b>被召唤的蜜蜂</b>（{@code more_balls:summoned_bee}）—— 【招蜂引蝶 x】召唤出来的战斗宠物
 * （作者 2026-10-16 指定）。
 *
 * <h2>为什么是「继承 Bee」而不是把 {@code Bee.java} 抄一份</h2>
 * <ol>
 *   <li><b>飞行不是通用能力</b>：蜜蜂的移动是父类构造函数里配的私有内部类
 *       （{@code BeeMoveControl} + {@code FlyingPathNavigation}）。从零写就得把那套抄一遍，
 *       否则新蜂只会贴地爬。</li>
 *   <li><b>渲染零成本</b>：继承之后客户端可以直接用原版 {@code BeeRenderer}
 *       （它的构造是公开的，泛型绑的也是 {@code Bee}），模型与贴图全是原版的 ——
 *       <b>不需要任何新美术素材</b>。从零做生物就得自己出模型和贴图。</li>
 *   <li>名字也白拿：实体类型自带名字（{@code entity.more_balls.summoned_bee} =
 *       「被召唤的蜜蜂」），不必用 {@code setCustomName}。</li>
 * </ol>
 *
 * <h2>与父类的三处差别（就是本类存在的理由）</h2>
 * <ol>
 *   <li><b>可以无限攻击</b> —— 覆写 {@link #doHurtTarget}，砍掉父类那两句
 *       {@code setHasStung(true)} / {@code stopBeingAngry()}。
 *       原版蜜蜂蜇中一次就「废」了：不再攻击、还会在 1200 刻后自灭。</li>
 *   <li><b>不仇恨召唤者</b> —— 覆写 {@link #canAttack} 挡住召唤者。
 *       挡在这一层而不是每刻纠偏：原版 {@code Mob.setTarget} 内部会走
 *       {@code asValidTarget → canAttack}，所以「被打就反击把主人当目标」这类路径
 *       在<b>设置的那一刻</b>就被拒掉。</li>
 *   <li><b>8 秒后直接消失、无掉落</b> —— 见 {@link #tick()} 与 {@link #poof()}。</li>
 * </ol>
 *
 * <h2>AI：只留两个 goal</h2>
 * <p>父类的 goal 全是私有内部类，子类配不到；而且采蜜、回巢、随季节回巢这些行为对
 * 「只活 8 秒的战斗宠物」毫无意义。所以这里只加
 * {@link FloatGoal}（防溺水）与 {@link MeleeAttackGoal}（冲过去咬）——
 * 后者配合父类自带的飞行移动控制，就能正常飞到目标身上。</p>
 *
 * <p>也正因为改用通用的 {@code MeleeAttackGoal}，本实体<b>不依赖原版那套中立怪愤怒机制</b>
 * （{@code persistentAngerTarget} / {@code setTimeToRemainAngry} 都不需要）——
 * 目标由召唤方直接 {@code setTarget} 维持即可。</p>
 */
public class SummonedBee extends Bee {

    /** 存活时长：<b>8 秒</b>（作者 2026-10-16 指定） */
    public static final int LIFETIME_TICKS = 8 * 20;

    /** 血量（作者指定 3 点） */
    public static final double HEALTH = 3.0D;

    private static final String TAG_SUMMONER = "more_balls:summoner";
    private static final String TAG_LIFE = "more_balls:life";

    /** 召唤者（球的主人）的 UUID —— 这只蜜蜂绝不打他 */
    private UUID summonerId;

    /** 剩余存活刻数 */
    private int life = LIFETIME_TICKS;

    public SummonedBee(EntityType<? extends SummonedBee> type, Level level) {
        super(type, level);
        // 8 秒的临时战斗单位：别被原版的「自然清除」（远离玩家就消失）提前收走，
        // 那样会出现「刚召唤就没了」的假象，与本类自己的 8 秒寿命打架。
        this.setPersistenceRequired();
    }

    /**
     * 属性。
     *
     * <p>⚠️ 没有直接复用 {@code Bee.createAttributes()}：那是原版蜜蜂的 10 点血、
     * 以及一堆本实体用不到的属性。这里从 {@code Mob.createMobAttributes()} 起手，
     * 只留用得上的几项，血量按作者指定给 3。</p>
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, HEALTH)
                .add(Attributes.ATTACK_DAMAGE, 2.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.FLYING_SPEED, 0.6D)
                .add(Attributes.FOLLOW_RANGE, 48.0D);
    }

    /** 召唤者 —— 由召唤方（{@code BallExtraTraits.summonBees}）在生成时设 */
    public void setSummoner(Entity summoner) {
        this.summonerId = (summoner == null) ? null : summoner.getUUID();
    }

    /** 这个生物是不是我的召唤者 */
    public boolean isSummoner(LivingEntity other) {
        return this.summonerId != null && this.summonerId.equals(other.getUUID());
    }

    // ===== AI =====

    @Override
    protected void registerGoals() {
        // ⚠️⚠️ 必须**先调 super**！父类就是在这个方法里给它的内部字段赋值的
        //     （beePollinateGoal、goToHiveGoal 之类）。不调的话那些字段保持 null，
        //     而父类的 hurtServer() 里有 `this.beePollinateGoal.stopPollinating()` ——
        //     **这只蜜蜂第一次挨打就会 NPE 崩服**，而且编译期完全看不出来。
        super.registerGoals();

        // 再把父类那套与「只活 8 秒的战斗宠物」无关的 AI 全部摘掉
        // （采蜜、回巢、认巢、种作物、游荡…留着只会让它到处乱跑）。
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();

        // 只留两个：
        //   FloatGoal —— 防溺水（掉水里自己浮上来）
        //   MeleeAttackGoal —— 冲过去咬。它配合父类构造函数里配好的
        //     BeeMoveControl + FlyingPathNavigation，就能让蜜蜂飞过去，不需要额外写飞行逻辑。
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.4D, true));

        // 目标选择器一概不加：目标完全由召唤方维持，
        // 没有 TargetGoal 也就没人会在中途把目标清掉。
    }

    // ===== 差别 ①：无限攻击 =====

    /**
     * 攻击 —— 与父类的<b>唯一</b>区别是结尾不调
     * {@code setHasStung(true)} / {@code stopBeingAngry()}。
     *
     * <p>那两句正是「蜇一次就再也打不动、并且 1200 刻后自己死」的来源
     * （父类的 {@code BeeAttackGoal} 要求 {@code isAngry() && !hasStung()}）。</p>
     *
     * <p>伤害、中毒、音效全部照父类来 —— 中毒时长同样按难度取
     * （普通 10 秒 / 困难 18 秒）。</p>
     */
    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        DamageSource source = this.damageSources().sting(this);
        boolean hurt = target.hurtServer(level, source,
                (int) this.getAttributeValue(Attributes.ATTACK_DAMAGE));
        if (hurt) {
            EnchantmentHelper.doPostAttackEffects(level, target, source);
            if (target instanceof LivingEntity living) {
                living.setStingerCount(living.getStingerCount() + 1);
                int poisonSeconds = 0;
                if (level.getDifficulty() == net.minecraft.world.Difficulty.NORMAL) {
                    poisonSeconds = 10;
                } else if (level.getDifficulty() == net.minecraft.world.Difficulty.HARD) {
                    poisonSeconds = 18;
                }
                if (poisonSeconds > 0) {
                    living.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                            net.minecraft.world.effect.MobEffects.POISON, poisonSeconds * 20, 0), this);
                }
            }
            this.playSound(SoundEvents.BEE_STING, 1.0F, 1.0F);
        }
        return hurt;
    }

    // ===== 差别 ②：不仇恨召唤者 =====

    @Override
    public boolean canAttack(LivingEntity target) {
        if (this.isSummoner(target)) {
            return false;   // 主人永远不能成为目标
        }
        return super.canAttack(target);
    }

    // ===== 差别 ③：8 秒后直接消失（无掉落）+ 烟雾 =====

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            return;
        }
        if (--this.life <= 0) {
            this.poof();
            return;
        }
        this.forceAggroOnMe();
    }

    /**
     * <b>仇恨强制转移</b>（作者 2026-10-15 的规格；2026-10-16 补上「持续」这一层）。
     *
     * <p>⚠️ 原先只在召唤那一刻 {@code victim.setTarget(bee)} 设一次 —— <b>那不够</b>：
     * 怪的目标会被它自己的 AI 很快改掉（看见玩家就转头回去），
     * 结果就是「强制转移」根本没生效。</p>
     *
     * <p>所以改成在寿命内<b>每刻把它钉回来</b>：只要我还活着、被击中者还活着，
     * 而它当前的目标不是我，就重新设成我。这才是「强制」。</p>
     *
     * <p>例外：<b>召唤者不在此列</b> —— 这只蜜蜂是它的宠物，不拉着自己的主人来挨打
     * （而且玩家本来也不接受被强制设目标）。</p>
     */
    private void forceAggroOnMe() {
        if (!(this.getTarget() instanceof Mob mob) || !mob.isAlive()) {
            return;   // 被打中者不是怪（比如玩家），或者已经死了 → 没什么可转移的
        }
        if (this.isSummoner(mob)) {
            return;   // 主人豁免
        }
        if (mob.getTarget() != this) {
            mob.setTarget(this);
        }
    }

    /**
     * 到点消失：一团烟雾，然后<b>直接移除</b>。
     *
     * <p>用 {@code discard()} 而不是致命伤害 —— 作者 2026-10-16 明确「无掉落」。
     * 所以不会有死亡动画、不会有掉落物、也不给经验。</p>
     */
    private void poof() {
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.LARGE_SMOKE,
                    this.getX(), this.getY() + 0.4D, this.getZ(),
                    10, 0.25D, 0.25D, 0.25D, 0.01D);
            server.sendParticles(ParticleTypes.CLOUD,
                    this.getX(), this.getY() + 0.4D, this.getZ(),
                    6, 0.2D, 0.2D, 0.2D, 0.02D);
        }
        this.discard();
    }

    /** 由召唤方调用：重置寿命计时（生成时用） */
    public void resetLife() {
        this.life = LIFETIME_TICKS;
    }

    // ===== 存读档 =====

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable(TAG_SUMMONER, UUIDUtil.CODEC, this.summonerId);
        output.putInt(TAG_LIFE, this.life);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.summonerId = input.read(TAG_SUMMONER, UUIDUtil.CODEC).orElse(null);
        this.life = input.getIntOr(TAG_LIFE, LIFETIME_TICKS);
    }
}
