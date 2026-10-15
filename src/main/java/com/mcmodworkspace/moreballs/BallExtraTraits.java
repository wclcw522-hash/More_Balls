package com.mcmodworkspace.moreballs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>第十三批的击中类词条 + 组合类词条</b>（作者 2026-10-15 指定）。
 *
 * <h2>里面有什么</h2>
 * <ul>
 *   <li><b>【敛财】</b> 绿宝石球 —— 击中生物时给它挂【腰缠万贯】(10 秒)。
 *       效果本体在 {@link MoneyEffect}，这里只负责施加。</li>
 *   <li><b>【尖刺】</b> 滴水石球 —— 击中 / 碰撞 / 破碎时，以「垂直于速度的面」为基准面，
 *       朝 8 个方向、与速度反向成 60° 角、最高点 2 格，射出 8 枚滴水石锥。</li>
 *   <li><b>【招蜂引蝶 N】</b> 蜜饯球 —— 击中时召唤 N 只仇恨目标的蜜蜂（血量 5）。</li>
 *   <li><b>【封印】</b> 符箓球 —— 封玩家快捷栏 / 封怪物攻击并打掉它手里的球。</li>
 *   <li><b>【滋养】</b> 蜜饯球的 <b>组合类</b>特性词条 —— 回收成功时给主人 3 秒生命恢复。</li>
 * </ul>
 *
 * <h2>26.2 的包名差异（踩过，记下来）</h2>
 * <ul>
 *   <li>蜜蜂是 {@code net.minecraft.world.entity.animal.bee.Bee}（不在 {@code animal} 根下）</li>
 *   <li>箭是 {@code net.minecraft.world.entity.projectile.arrow.Arrow}（多了 {@code arrow} 层）</li>
 *   <li>没有 {@code ThrownTrident} / {@code MobType}（后者整类没了）</li>
 *   <li>{@code ItemCooldowns.addCooldown} 收的是 <b>ItemStack</b>，不是 Item</li>
 * </ul>
 */
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

    /** 【尖刺】：滴水石锥的数量 —— 8 个方向 */
    public static final int SPIKE_COUNT = 8;

    /** 【尖刺】：与「速度反方向」的夹角（作者指定 60°） */
    public static final double SPIKE_ANGLE_DEG = 60.0D;

    /** 【尖刺】：最高点高度（作者指定 2 格） */
    public static final double SPIKE_APEX = 2.0D;

    /** 【尖刺】：锥的基础伤害（作者指定 3，其余按原版结算） */
    public static final float SPIKE_BASE_DAMAGE = 3.0F;

    /** 【封印】：封玩家快捷栏的时长 —— 60 刻 = 3 秒 */
    public static final int SEAL_PLAYER_TICKS = 60;

    /** 【封印】：封怪物攻击的时长 —— 200 刻 = 10 秒 */
    public static final int SEAL_MOB_TICKS = 200;

    // ===== 【敛财】=====

    /**
     * 给被击中者挂【腰缠万贯】。
     *
     * <p>作者指定「最大 1 级」⇒ 一律 {@code amplifier = 0}；
     * 已经有更长的同效果时不缩短它。</p>
     */
    public static void applyMoney(LivingEntity victim) {
        MobEffectInstance now = victim.getEffect(ModEffects.MONEY);
        if (now != null && now.getDuration() >= MONEY_TICKS) {
            return;
        }
        victim.addEffect(new MobEffectInstance(ModEffects.MONEY, MONEY_TICKS, 0, false, true, true));
    }

    // ===== 【招蜂引蝶 N】=====

    /**
     * 召唤 {@code count} 只对被击中者有仇恨的蜜蜂（血量 5）。
     *
     * <p>作者要求「强制转移目标的仇恨到它们身上」—— 所以既让蜜蜂锁定目标，
     * 也主动把目标的当前目标设成蜜蜂，保证立刻生效而不是等挨一下才反击。</p>
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
            double radius = 1.6D;
            bee.snapTo(victim.getX() + Math.cos(angle) * radius,
                    victim.getY() + 1.0D,
                    victim.getZ() + Math.sin(angle) * radius,
                    victim.getYRot(), 0.0F);
            // 生命上限也压到 5 —— 否则「血量 5」只在生成那一刻成立
            var maxHealth = bee.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(BEE_HEALTH);
            }
            bee.setHealth(BEE_HEALTH);
            bee.setTarget(victim);
            bee.setPersistenceRequired();
            level.addFreshEntity(bee);
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
     * <h2>几何（作者给的规格）</h2>
     * <p>基准面 = <b>垂直于速度的面</b>（速度方向即该面法线）。在该面内取 8 个均匀方向，
     * 每个方向与「<b>基准面与最终速度的反方向</b>」成 <b>60°</b> 角射出，
     * 飞行最高点 <b>2 格</b>。整体是个朝来向张开的伞面。</p>
     *
     * <p>载体用原版的箭（能直接吃 {@code setBaseDamage}，其余伤害按原版结算），
     * 外观靠粒子表达；一枚生成失败不影响其余。</p>
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

        for (int i = 0; i < SPIKE_COUNT; i++) {
            double theta = (Math.PI * 2.0D / SPIKE_COUNT) * i;
            Vec3 radial = u.scale(Math.cos(theta)).add(v.scale(Math.sin(theta)));
            Vec3 shoot = dir.scale(-cos).add(radial.scale(sin)).normalize();

            // 初速度按「最高点 2 格」反推，再夹到一个稳定的量级（与球速解耦，表现才一致）
            double speed = Math.sqrt(2.0D * 0.08D * SPIKE_APEX) / Math.max(0.25D, Math.abs(shoot.y));
            speed = Mth.clamp(speed, 0.35D, 1.10D);

            try {
                Arrow arrow = new Arrow(level, position.x, position.y, position.z,
                        ItemStack.EMPTY, ItemStack.EMPTY);
                arrow.setBaseDamage(SPIKE_BASE_DAMAGE);
                if (owner instanceof LivingEntity living) {
                    arrow.setOwner(living);
                }
                arrow.setDeltaMovement(shoot.scale(speed));
                level.addFreshEntity(arrow);
            } catch (Throwable ignored) {
                // 单枚失败不影响整颗球
            }
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
     *   <li>击中<b>非盟友且非自身玩家</b>：随机让该玩家一件快捷栏物品进 3 秒冷却</li>
     *   <li>击中<b>怪物</b>：强制它手里的<b>球</b>全部掉落（原本持有的弓 / 弩 / 剑<b>不掉</b>），
     *       并封它 10 秒攻击</li>
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
        ModAttachments.seal(target, level.getGameTime() + SEAL_MOB_TICKS);
        // 【封印】期间无法攻击 —— 用极重的虚弱表达（原版没有「攻击冷却」这种通用字段）
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, SEAL_MOB_TICKS, 4, false, true, true));
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SEAL_MOB_TICKS, 1, false, true, true));
        level.playSound(null, target.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE,
                SoundSource.NEUTRAL, 0.7F, 1.4F);
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
        player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable(
                "message.more_balls.sealed"));
    }

    /**
     * 打掉怪物手里的<b>球</b>。
     *
     * <p>⚠️ 只掉「球」（含球堆）—— 小白手里的弓、猪灵手里的弩和剑这类<b>原本就持有的</b>
     * 物品一律不动，否则等于把它们的装备扒了（作者明确划的线）。</p>
     */
    private static void dropBallsFromHands(LivingEntity target) {
        for (var hand : net.minecraft.world.InteractionHand.values()) {
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

    // ===== 【滋养】（组合类词条）=====

    /**
     * 【滋养】—— 回收成功时给主人 3 秒生命恢复。
     *
     * <p>⚠️ 这是<b>组合类</b>特性词条：<b>完整球上不生效</b>（挂在球上也不会触发），
     * 只有作为组件合成进组合球之后才生效。判定靠
     * {@code BallBehavior.BallProfile.FLAG_NURTURE} —— 它只在组合球的 profile 里被点亮，
     * 完整球的 profile 在出口处会被抹掉（见 {@code BallBehavior.profileFor}）。</p>
     */
    public static void nurture(LivingEntity owner) {
        owner.addEffect(new MobEffectInstance(MobEffects.REGENERATION, NURTURE_TICKS, 0, false, true, true));
    }
}
