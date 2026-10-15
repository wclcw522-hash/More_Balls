package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.DripstoneShard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>第十三批的击中类 / 组合类词条</b>（作者 2026-10-15 定稿）。
 *
 * <h2>里面有什么</h2>
 * <ul>
 *   <li><b>【敛财】</b> 绿宝石球（「其它特殊」类）—— 击中生物时给它挂【腰缠万贯】（10 秒）。
 *       效果本体在 {@link MoneyEffect}，这里只负责施加。</li>
 *   <li><b>【尖刺】</b> 滴水石球（「其它特殊」类）—— 击中 / 碰撞 / 破碎时，以垂直于速度的面为基准面，
 *       朝 8 个方向、与速度反向成 60° 角、最高点 2 格，射出 8 枚滴水石锥
 *       （{@link DripstoneShard}，基础伤害 3）。</li>
 *   <li><b>【招蜂引蝶 N】</b> 蜜饯球（<b>数值类特性</b>）—— 击中时召唤 N 只仇恨目标的蜜蜂（血量 5），
 *       并把目标的仇恨强制转移到它们身上。</li>
 *   <li><b>【封印】</b> 符箓球（「其它特殊」类）—— 封玩家快捷栏一件 3 秒 / 封怪物 10 秒
 *       （不能攻击、也不能捡球，手里的球全掉出来）。</li>
 *   <li><b>【滋养】</b> 蜜饯球的<b>组合类</b>特性词条 —— 回收成功那一刻按概率给主人 3 秒生命恢复。</li>
 * </ul>
 *
 * <h2>26.2 的包名差异（踩过，记下来）</h2>
 * <ul>
 *   <li>蜜蜂是 {@code net.minecraft.world.entity.animal.bee.Bee}（不在 {@code animal} 根下）</li>
 *   <li>{@code ItemCooldowns.addCooldown} 收的是 <b>ItemStack</b>，不是 Item</li>
 *   <li>实体定位用 {@code snapTo}，没有 {@code moveTo}</li>
 * </ul>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallExtraTraits {

    private BallExtraTraits() {
    }

    // ===== 常量（作者指定的数值）=====

    /** 【敛财】：【腰缠万贯】的时长 —— 200 刻 = 10 秒 */
    public static final int MONEY_TICKS = 200;

    /** 【滋养】：生命恢复的时长 —— 60 刻 = 3 秒 */
    public static final int NURTURE_TICKS = 60;

    /** 【招蜂引蝶】：蜜蜂的血量（作者指定 5） */
    public static final float BEE_HEALTH = 5.0F;

    /** 【招蜂引蝶】：蜜蜂的召唤半径 */
    private static final double BEE_SPAWN_RADIUS = 1.6D;

    /** 【尖刺】：滴水石锥的数量 —— 8 个方向 */
    public static final int SPIKE_COUNT = 8;

    /** 【尖刺】：与「速度反方向」的夹角（作者指定 60°） */
    public static final double SPIKE_ANGLE_DEG = 60.0D;

    /** 【尖刺】：最高点高度（作者指定 2 格） */
    public static final double SPIKE_APEX = 2.0D;

    /**
     * 原版投掷物的默认重力（{@code ThrowableProjectile.getDefaultGravity()} = 0.03）。
     *
     * <p>「最高点 2 格」要反推竖直初速度，用的就是这个数：
     * {@code v = sqrt(2 * g * h)}。</p>
     */
    private static final double THROWN_GRAVITY = 0.03D;

    /** 【尖刺】：初速度的夹取区间 —— 免得某些朝向算出让锥子瞬移的极端值 */
    private static final double SPIKE_MIN_SPEED = 0.15D;
    private static final double SPIKE_MAX_SPEED = 1.10D;

    /** 【封印】：封玩家快捷栏的时长 —— 60 刻 = 3 秒 */
    public static final int SEAL_PLAYER_TICKS = 60;

    /** 【封印】：封怪物攻击的时长 —— 200 刻 = 10 秒 */
    public static final int SEAL_MOB_TICKS = 200;

    /** 这颗球是不是「组合球」（组合类词条只在它身上生效） */
    public static boolean isComboBall(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == ModItems.COMBO_BALL.get();
    }

    // ===== 【敛财】=====

    /**
     * 给被击中者挂【腰缠万贯】。
     *
     * <p>作者指定「最大 1 级」⇒ 一律 {@code amplifier = 0}；
     * 已经有更长的同效果时不缩短它（重复命中只延不缩）。</p>
     */
    public static void applyMoney(LivingEntity victim, LivingEntity source) {
        MobEffectInstance now = victim.getEffect(ModEffects.MONEY);
        if (now != null && now.getDuration() >= MONEY_TICKS) {
            return;
        }
        victim.addEffect(new MobEffectInstance(ModEffects.MONEY, MONEY_TICKS,
                MoneyEffect.MAX_AMPLIFIER, false, true, true), source);
    }

    // ===== 【招蜂引蝶 N】=====

    /**
     * 召唤 {@code count} 只对被击中者有仇恨的蜜蜂（血量 5）。
     *
     * <p>作者要求「这些蜜蜂会强制转移目标的仇恨到他们身上」——
     * 所以既让蜜蜂锁定目标，也主动把目标当前的攻击目标设成蜜蜂，
     * 保证仇恨立刻转移过去，而不是等它挨一下才反应。</p>
     */
    public static void summonBees(ServerLevel level, LivingEntity victim, int count) {
        if (count <= 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            Bee bee = EntityTypes.BEE.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (bee == null) {
                continue;
            }
            double angle = (Math.PI * 2.0D / count) * i;
            bee.snapTo(victim.getX() + Math.cos(angle) * BEE_SPAWN_RADIUS,
                    victim.getY() + 1.0D,
                    victim.getZ() + Math.sin(angle) * BEE_SPAWN_RADIUS,
                    victim.getYRot(), 0.0F);
            // 生命上限也压到 5 —— 否则「血量 5」只在生成那一刻成立
            var maxHealth = bee.getAttribute(Attributes.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(BEE_HEALTH);
            }
            bee.setHealth(BEE_HEALTH);
            bee.setTarget(victim);
            bee.setPersistenceRequired();
            level.addFreshEntity(bee);
            // 仇恨反向转移：让被击中的目标去咬蜜蜂
            if (victim instanceof Mob mob) {
                mob.setTarget(bee);
            }
        }
        level.playSound(null, victim.blockPosition(), SoundEvents.BEE_LOOP,
                SoundSource.NEUTRAL, 1.0F, 1.0F);
    }

    // ===== 【尖刺】=====

    /**
     * 射出一圈滴水石锥。
     *
     * <h2>几何（作者给的规格，逐条对着实现）</h2>
     * <ol>
     *   <li><b>基准面</b> = 垂直于速度的面（速度方向即该面的法线）</li>
     *   <li>在该面内取 <b>8 个均匀方向</b></li>
     *   <li>每枚锥子的射出方向 = 「基准面里那个方向」与「最终速度的<b>反方向</b>」成 <b>60°</b>
     *       —— 也就是朝来向张开的一把伞</li>
     *   <li>飞行<b>最高点 2 格</b>：按 {@code v = sqrt(2gh)} 反推竖直初速度，再折算成整条初速度</li>
     * </ol>
     *
     * <p>载体是真的滴水石锥（{@link DripstoneShard}）—— 外观、抛物线、命中结算都走原版那一套，
     * 基础伤害 3 由实体自己加。</p>
     *
     * @param position 出手点（球当时的位置）
     * @param velocity 球当时的最终速度
     */
    public static void shootSpikes(ServerLevel level, Vec3 position, Vec3 velocity, Entity owner) {
        Vec3 dir = velocity.lengthSqr() < 1.0E-6D
                ? new Vec3(0.0D, -1.0D, 0.0D) : velocity.normalize();

        // 在「垂直于 dir」的平面内取两个正交基
        Vec3 up = Math.abs(dir.y) > 0.99D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 u = dir.cross(up).normalize();
        Vec3 v = dir.cross(u).normalize();

        double angle = Math.toRadians(SPIKE_ANGLE_DEG);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        // 「最高点 2 格」需要的竖直分量
        double apexSpeed = Math.sqrt(2.0D * THROWN_GRAVITY * SPIKE_APEX);

        LivingEntity shooter = owner instanceof LivingEntity living ? living : null;

        for (int i = 0; i < SPIKE_COUNT; i++) {
            double theta = (Math.PI * 2.0D / SPIKE_COUNT) * i;
            Vec3 radial = u.scale(Math.cos(theta)).add(v.scale(Math.sin(theta)));
            Vec3 shoot = dir.scale(-cos).add(radial.scale(sin)).normalize();

            // 以竖直分量为准反推整条初速度；近乎水平的朝向取一个稳定的下限量级
            double speed = Math.abs(shoot.y) < 1.0E-3D
                    ? apexSpeed : apexSpeed / Math.abs(shoot.y);
            speed = Mth.clamp(speed, SPIKE_MIN_SPEED, SPIKE_MAX_SPEED);

            level.addFreshEntity(new DripstoneShard(level, position, shoot.scale(speed), shooter));
        }

        level.sendParticles(ParticleTypes.CRIT,
                position.x, position.y, position.z, 12, 0.3D, 0.3D, 0.3D, 0.05D);
        level.playSound(null, BlockPos.containing(position), SoundEvents.POINTED_DRIPSTONE_BREAK,
                SoundSource.NEUTRAL, 0.8F, 1.0F);
    }

    // ===== 【封印】=====

    /**
     * 【封印】—— 对玩家封快捷栏、对怪物封攻击并打掉它手里的球。
     *
     * <p>作者规格：</p>
     * <ul>
     *   <li>击中<b>非盟友且非自身玩家</b>：随机让该玩家一件快捷栏物品进入一次 3 秒冷却</li>
     *   <li>击中<b>怪物</b>：强制它手里的<b>球</b>全部掉落（原本持有的弓 / 弩 / 剑<b>不掉</b>），
     *       并封它 10 秒攻击 —— 期间无法发起任何攻击、也无法捡球</li>
     * </ul>
     */
    public static void seal(ServerLevel level, LivingEntity target, Entity owner) {
        if (target instanceof Player player) {
            if (owner == player) {
                return;   // 不封自己
            }
            if (owner instanceof LivingEntity shooter && shooter.isAlliedTo(player)) {
                return;   // 盟友不封
            }
            sealHotbar(player);
            return;
        }

        dropBallsFromHands(target);
        // 「不能攻击、也不能捡球」的标记 —— 判定分别在 tick 与 BallMobAI 里
        target.setData(ModAttachments.SEALED_UNTIL.get(),
                level.getGameTime() + SEAL_MOB_TICKS);
        // 先把当前的攻击目标清掉，否则它下一刻还是照打
        if (target instanceof Mob mob) {
            clearAttackTarget(mob);
        }
        level.playSound(null, target.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE,
                SoundSource.NEUTRAL, 0.7F, 1.4F);
    }

    /** 这只怪物现在是否处于【封印】期间 */
    public static boolean isSealed(LivingEntity mob) {
        Long until = mob.getData(ModAttachments.SEALED_UNTIL.get());
        if (until == null || until <= 0L) {
            return false;
        }
        return mob.level().getGameTime() < until;
    }

    /** 让玩家快捷栏里随机一件物品进入 3 秒冷却 */
    private static void sealHotbar(Player player) {
        List<ItemStack> candidates = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                candidates.add(stack);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        ItemStack picked = candidates.get(player.getRandom().nextInt(candidates.size()));
        // 26.2 的 addCooldown 收 ItemStack（不是 Item）
        player.getCooldowns().addCooldown(picked, SEAL_PLAYER_TICKS);
        player.sendOverlayMessage(Component.translatable("message.more_balls.sealed"));
    }

    /**
     * 打掉怪物手里的<b>球</b>。
     *
     * <p>⚠️ 只掉「球」（含球堆）—— 小白手里的弓、猪灵手里的弩和剑这类
     * <b>原本就持有的</b>物品一律不动，否则等于把它们的装备扒了（作者明确划的线）。</p>
     */
    private static void dropBallsFromHands(LivingEntity target) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = target.getItemInHand(hand);
            if (stack.isEmpty()) {
                continue;
            }
            if (!BallAmmo.isBall(stack) && !BallClusterItem.isCluster(stack)) {
                continue;
            }
            ItemStack dropped = stack.copy();
            target.setItemInHand(hand, ItemStack.EMPTY);
            ItemEntity entity = new ItemEntity(target.level(),
                    target.getX(), target.getY() + 1.0D, target.getZ(), dropped);
            entity.setDefaultPickUpDelay();
            target.level().addFreshEntity(entity);
        }
    }

    /** 清掉攻击目标：光清 {@code setTarget} 不够，猪灵这类还会从脑记忆里把目标翻出来 */
    private static void clearAttackTarget(Mob mob) {
        mob.setTarget(null);
        var brain = mob.getBrain();
        if (brain != null) {
            brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
        }
    }

    /**
     * 【封印】期间每刻把它压住：清掉攻击目标、让它没法发起任何攻击。
     *
     * <p>清目标就够了吗？—— 近战与远程的原版 AI（{@code MeleeAttackGoal} /
     * {@code RangedAttackGoal}）都以「有攻击目标」为前提，目标为空时它们什么都不做；
     * 猪灵那类会从脑记忆里重新取目标，所以 {@link #clearAttackTarget} 连记忆一起清。
     * 「不能捡球」是模组自己的判定，写在 {@code BallMobAI} 里（{@link #isSealed}）。</p>
     */
    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) {
            return;
        }
        if (!isSealed(mob)) {
            return;
        }
        // 只清「攻击型」生物的目标，别去动村民 / 动物这类的中立行为
        if (mob instanceof Enemy) {
            clearAttackTarget(mob);
        }
    }

    // ===== 【滋养】（组合类词条）=====

    /**
     * 【滋养】—— 回收成功那一刻按概率给主人挂 3 秒生命恢复。
     *
     * <p>⚠️ 它是<b>组合类</b>特性词条：<b>完整球上不生效</b>，
     * 只有作为组件合成进组合球之后才生效（作者 2026-10-15 定稿的新类别）。
     * 所以这里第一道判据就是「这球是不是组合球」，第二道才是按概率掷骰。</p>
     *
     * <p>概率来自 profile 的 {@code nurture}：完整球是 1.0（携带但不用），
     * 组合球是各份均分后相加的结果（一份 0.25、两份 0.5、四份 1.0）。</p>
     *
     * @return 是否真的挂上了（给日志与自测用）
     */
    public static boolean nurture(LivingEntity owner, ItemStack ball, double chance) {
        if (!isComboBall(ball)) {
            return false;
        }
        if (chance <= 0.0D) {
            return false;
        }
        if (chance < 1.0D && owner.getRandom().nextDouble() >= chance) {
            return false;
        }
        owner.addEffect(new MobEffectInstance(MobEffects.REGENERATION, NURTURE_TICKS, 0, false, true, true));
        return true;
    }
}
