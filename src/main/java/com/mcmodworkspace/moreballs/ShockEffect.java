package com.mcmodworkspace.moreballs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【震荡】—— 五级的「晕头转向」debuff（作者 2026-10-10 指定的规格）。
 *
 * <h2>效果本身</h2>
 * <ul>
 *   <li>持有人在持续时间内，每隔固定刻数被<b>强制随机转向一次</b></li>
 *   <li>转向同时作用于<b>视角方向</b>与<b>运动方向</b> —— 两边都扭</li>
 *   <li>五级的间隔分别是 <b>20 / 17 / 13 / 8 / 2</b> 刻（越高越频繁）</li>
 * </ul>
 *
 * <h2>「锁定」—— 前若干个百分比的时长里，只能被本效果扭</h2>
 * <p>作者 2026-10-13 追加：<b>持续时间的前百分之几</b>里，持有者的<b>朝向与移动方向都锁死</b>，
 * 期间只有本效果自己到点的那一下能改它的朝向 —— 也就是说，这段窗口里
 * 生物转不了头、也改不了要走的方向，玩家也压不住被扭过去的视角。</p>
 * <p>占比按等级走：一级 <b>15%</b>、二级 <b>30%</b>、三级 <b>45%</b>、四级 <b>60%</b>、
 * 五级 <b>75%</b>（见 {@link #LOCK_FRACTION}）。窗口之外只保留原有的「到点扭一下」，
 * 不再锁。</p>
 *
 * <h2>射弹方向：整段效果期间都按「正前方」发射</h2>
 * <p>作者 2026-10-13 追加：<b>生物</b>在受影响期间发射的射弹，方向一律取<b>它当前的朝向</b>
 * （水平与垂直都跟），而不是原版那种「朝目标重新修正」的方向 —— 小白蓄力时被扭歪了视角，
 * 箭仍按原计划按时射出，但飞向歪掉之后的方向（见 {@link #onProjectileJoin}）。</p>
 * <p>玩家不在其列：玩家本来就只能朝视线前方发射，锁视角已经把方向一并锁住了。
 * 另外，模组里那种自带追踪的弹幕也不受影响 —— 它们本来就不看发射时的朝向。</p>
 *
 * <h2>「无视药水效果免疫」怎么做到</h2>
 * <p>查 {@code LivingEntity.addEffect} 的实现：它只调
 * {@code CommonHooks.canMobEffectBeApplied}，而那里面<b>只 post 一个
 * {@code MobEffectEvent.Applicable} 事件</b>，并不会去查 {@code canBeAffected}。
 * 免疫真正的来源是 {@link LivingEntity#canBeAffected}（按
 * {@code IMMUNE_TO_INFESTED} / {@code IGNORES_POISON_AND_REGEN} 这些标签判），
 * 而它只在<b>调用方</b>（{@code /effect} 命令、药水）被查。</p>
 *
 * <p>所以「无视免疫」用官方途径即可：本类在
 * {@code MobEffectEvent.Applicable} 上把【震荡】的结果强制设成
 * {@link net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable.Result#APPLY}，
 * 于是任何生物、任何来源都挡不住它，也不需要碰原版代码。</p>
 *
 * <h2>转向为什么分两条路</h2>
 * <ul>
 *   <li><b>运动方向</b>在服务端就能改（{@code setDeltaMovement}），改完置
 *       {@code hurtMarked} 让客户端同步速度</li>
 *   <li><b>视角方向</b>属于客户端状态，服务端必须发包才能改 —— 玩家用
 *       {@code ServerPlayer#forceSetRotation} 发一次**纯朝向包**（{@code ClientboundPlayerRotationPacket}）。
 *       ⚠️ **别用 {@code connection.teleport}** —— 它会设上 {@code awaitingPositionFromClient}，
 *       而那期间右键方块会被 {@code handleUseItemOn} 的 else 分支误报「建筑高度上限」
 *       （作者 2026-10-14 报的串台 bug）；
 *       非玩家生物直接用 {@code setYRot}</li>
 * </ul>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public class ShockEffect extends MobEffect {

    /** 英文 id（作者指定为 {@code shock}） */
    public static final String EFFECT_ID = "shock";

    /**
     * 最大等级：五级。
     *
     * <p>内部 amplifier 从 0 起算，所以上限是 {@code 4}。</p>
     */
    public static final int MAX_AMPLIFIER = 4;

    /**
     * 每一级的「转向间隔」（刻），作者指定：<b>20 / 17 / 13 / 8 / 2</b>。
     *
     * <p>数值越高转向越频繁 —— 五级每 2 刻扭一次，基本等于一直在打转。</p>
     */
    private static final int[] INTERVAL_TICKS = {20, 17, 13, 8, 2};

    /**
     * 每一级「锁死朝向与移动方向」占持续时间的比例（作者 2026-10-13 指定）：
     * <b>15% / 30% / 45% / 60% / 75%</b>。
     *
     * <p>窗口从效果挂上那一刻起算；窗口里除本效果到点扭的那一下之外，
     * 持有者改不动自己的朝向，也改不动要去的方向。</p>
     */
    private static final float[] LOCK_FRACTION = {0.15F, 0.30F, 0.45F, 0.60F, 0.75F};

    /** 单次转向的最大水平偏移（度）—— 取 ±60°，即左右各可扭 60° */
    private static final float YAW_SWING = 120.0F;

    /** 单次转向的最大俯仰偏移（度）—— 取 ±30°，即上下各可扭 30° */
    private static final float PITCH_SWING = 60.0F;

    /** 生物在锁定窗口里被压回去时，行走目标点取多远 —— 只要方向对，距离无所谓 */
    private static final double LOCK_WANDER_DISTANCE = 3.0D;

    /**
     * 每只受影响实体的「锁定值」。
     *
     * @param yaw           本效果最近一次扭完之后朝向的水平角
     * @param pitch         同上，俯仰角
     * @param totalDuration 效果<b>本次</b>的总时长（刻）—— 用来算「前 X%」的边界。
     *                      效果被刷新 / 重挂时跟着更新
     */
    private record Lock(float yaw, float pitch, int totalDuration) {
    }

    /**
     * 受影响实体 → 锁定值。
     *
     * <p>原版 {@code MobEffectInstance} 存不了自定义字段，所以另开一张表。
     * 效果到期时在 {@link #onExpired} 里清掉，不会越攒越多。</p>
     */
    private static final Map<UUID, Lock> LOCKS = new ConcurrentHashMap<>();

    public ShockEffect() {
        // HARMFUL = 红框（debuff），颜色取暗红，与「熔融物烧伤」的暖色区分开
        super(MobEffectCategory.HARMFUL, 0x8B1A1A);
    }

    /** 这一级每隔多少刻扭一次 */
    public static int intervalFor(int amplifier) {
        return INTERVAL_TICKS[Mth.clamp(amplifier, 0, INTERVAL_TICKS.length - 1)];
    }

    /** 这一级的「锁定占比」（0–1） */
    public static float lockFractionFor(int amplifier) {
        return LOCK_FRACTION[Mth.clamp(amplifier, 0, LOCK_FRACTION.length - 1)];
    }

    /**
     * 效果自身的 tick 节拍判定 —— <b>⚠️ 这里是空转的，实际不产生任何作用</b>。
     *
     * <p>它只决定「原版要不要调 {@link #applyEffectTick}」，而那个回调里什么都不做
     * （只 {@code return true} 维持效果存在），所以本方法算得再准也影响不到转向节拍。</p>
     *
     * <p><b>真正的节拍在 {@link #onEntityTickPost} 里</b>：那里每刻用
     * {@code instance.getDuration() % intervalFor(amplifier)} 取模判断该不该扭。
     * 也就是说间隔由<span>效果剩余时长</span>驱动、而不是由实体 tick 数驱动 ——
     * 两处即使间隔数值相同也互不干涉；效果被刷新或被 {@code /effect} 重挂时，
     * 相位会随剩余时长重新对齐（这正是想要的：重挂即立刻扭一下）。</p>
     *
     * <p>本方法保持现在的实现只为不动行为（原版契约要求有这个判定入口），
     * 不影响实际表现。</p>
     */
    @Override
    public boolean shouldApplyEffectTickThisTick(int tickCount, int amplifier) {
        return tickCount % intervalFor(amplifier) == 0;
    }

    /**
     * 效果自身的 tick —— <b>这里故意什么都不做</b>，只返回 {@code true} 维持效果存在。
     *
     * <p>⚠️ 为什么不在这个回调里真的去扭：{@code LivingEntity.tick()} 的顺序是
     * {@code tickEffects()} → {@code aiStep()}，也就是效果回调跑在<b>AI 之前</b>。
     * 对生物来说，AI 的 {@code MoveControl} 随后就会把速度重写成「朝目标走」，
     * 我们改的那一下**立刻被覆盖** —— 表现就是「对非玩家生物无效」
     * （玩家不受影响，因为玩家速度由客户端驱动）。</p>
     *
     * <p>所以真正的转向挪到 {@link #onEntityTickPost}，在实体 tick <b>结束之后</b>做。</p>
     */
    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity mob, int amplifier) {
        return true;
    }

    /**
     * 每刻（实体 tick 之后）检查【震荡】并执行转向 / 维持锁定。
     *
     * <p>放在 {@code EntityTickEvent.Post} 是关键：这时 AI 已经跑完，
     * 我们设的速度/朝向会一直保留到下一刻的移动结算 —— 生物这才真的会歪。</p>
     *
     * <p>处理分两种情形：</p>
     * <ul>
     *   <li><b>到点该扭了</b>：扭一下，并把扭完的朝向记进 {@link #LOCKS}</li>
     *   <li><b>还没到点、且仍在锁定窗口内</b>：把朝向与行走目标<b>压回</b>记下的值 ——
     *       这就是「这段时长里只有本效果能改方向」的实现方式</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity mob) || mob.level().isClientSide()) {
            return;
        }
        MobEffectInstance instance = mob.getEffect(ModEffects.SHOCK);
        if (instance == null) {
            return;
        }

        int remaining = instance.getDuration();
        int interval = intervalFor(instance.getAmplifier());
        Lock previous = LOCKS.get(mob.getUUID());
        // 效果被刷新 / 重挂（剩余时长变大）时，把「总时长」跟着更新，窗口边界才不会算错
        int totalDuration = previous == null ? remaining : Math.max(previous.totalDuration(), remaining);

        if (remaining % interval == 0) {
            // —— 到点扭一下，并把结果记成新的锁定值 ——
            applyShock(mob);
            LOCKS.put(mob.getUUID(), new Lock(mob.getYRot(), mob.getXRot(), totalDuration));
            return;
        }

        if (previous == null) {
            // 没记录（比如刚挂上、还没轮到第一个转向刻）—— 就以当前朝向为基准先锁住
            LOCKS.put(mob.getUUID(), new Lock(mob.getYRot(), mob.getXRot(), totalDuration));
            return;
        }

        // —— 还没到点：若仍在锁定窗口内，压回记下的朝向 ——
        int elapsed = totalDuration - remaining;
        float fraction = lockFractionFor(instance.getAmplifier());
        if (elapsed >= totalDuration * fraction) {
            return;   // 窗口已过，放它自由行动
        }
        restoreLock(mob, previous);
    }

    /**
     * 把持有者的朝向与移动方向<b>压回</b>锁定的值。
     *
     * <p>玩家与非玩家分头处理：玩家的朝向是客户端状态、必须发包（坐标不变，只改朝向）；
     * 生物的朝向在服务端改即可，另外还要把 {@code MoveControl} 的行走目标
     * 重新指回锁定方向 —— 只改朝向不改目标点的话，下一刻 AI 就会照自己的目标拐走。</p>
     */
    private static void restoreLock(LivingEntity mob, Lock lock) {
        if (mob instanceof ServerPlayer player) {
            player.setYRot(lock.yaw());
            player.setXRot(lock.pitch());
            player.forceSetRotation(lock.yaw(), false, lock.pitch(), false);
            return;
        }
        mob.setYRot(lock.yaw());
        mob.setXRot(lock.pitch());
        mob.setYHeadRot(lock.yaw());
        if (mob instanceof Mob creature) {
            creature.yBodyRot = lock.yaw();
            double radians = Math.toRadians(lock.yaw());
            creature.getMoveControl().setWantedPosition(
                    creature.getX() - Math.sin(radians) * LOCK_WANDER_DISTANCE,
                    creature.getY(),
                    creature.getZ() + Math.cos(radians) * LOCK_WANDER_DISTANCE,
                    0.6D);
        }
    }

    /**
     * <b>射弹方向</b>：受影响期间，生物射出去的射弹一律按它<b>当前的朝向</b>飞
     * （水平与垂直都跟），速率保持原样 —— 也就是「照原计划射出去，只是方向被扭了」。
     *
     * <p>为什么必须在实体生成时改写：原版生物（骷髅、烈焰人、女巫…）射弹时是
     * <b>朝目标重新算方向</b>的，光把它的视角扭歪没用 —— 箭照样飞向玩家。
     * 在这里把速度方向替换成持有者的视线方向，才能让它真的歪着射出去。</p>
     *
     * <p>不处理的：<b>玩家</b>射的（玩家本来只能朝视线前方发射，锁视角已经把方向锁住了）、
     * 以及自带追踪的弹幕（它们不看发射瞬间的朝向，本来就不受影响）。</p>
     */
    @SubscribeEvent
    public static void onProjectileJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Projectile projectile)) {
            return;
        }
        Entity shooter = projectile.getOwner();
        // 判据用「生物」（LivingEntity）而**不是 Mob**：有些模组的怪物 / Boss 并不继承
        // Mob，拿 Mob 当筛子会把它们整个漏掉（作者 2026-10-13 指出）。
        // 玩家要排除 —— 玩家不是「生物射弹」那条语义，而且它的弹道本来就只能朝视线前方，
        // 锁视角已经把方向一并锁住了。
        if (!(shooter instanceof LivingEntity creature)
                || shooter instanceof net.minecraft.world.entity.player.Player) {
            return;
        }
        if (creature.getEffect(ModEffects.SHOCK) == null) {
            return;
        }
        Vec3 velocity = projectile.getDeltaMovement();
        double speed = velocity.length();
        if (speed < 1.0E-4D) {
            return;   // 还没给初速的（或静态生成的），不碰
        }
        projectile.setDeltaMovement(creature.getLookAngle().scale(speed));
    }

    /**
     * 效果到期 —— 清掉锁定记录，避免这张表越攒越多。
     *
     * <p>「被刷新」走的是 {@code MobEffectEvent.Added} 那条路，不在这里清；
     * 刷新时 {@link #onEntityTickPost} 会把总时长跟着更新。</p>
     */
    @SubscribeEvent
    public static void onExpired(MobEffectEvent.Expired event) {
        if (event.getEffectInstance().is(ModEffects.SHOCK)) {
            LOCKS.remove(event.getEntity().getUUID());
        }
    }

    /**
     * <b>无视一切免疫</b> —— 把【震荡】的结果强制设成 {@code APPLY}。
     *
     * <p>见类注释「无视药水效果免疫怎么做到」：{@code addEffect} 最终走的是
     * {@code CommonHooks.canMobEffectBeApplied}，而那里面只 post 这一个事件。
     * 所以在这里返回 APPLY，就等于「谁都挡不住」——不需要碰原版代码。</p>
     */
    @SubscribeEvent
    public static void onApplicable(MobEffectEvent.Applicable event) {
        if (event.getEffectInstance().is(ModEffects.SHOCK)) {
            event.setResult(MobEffectEvent.Applicable.Result.APPLY);
        }
    }

    /**
     * 施加一次随机转向 —— 视角与运动方向一起扭。
     *
     * <p>⚠️ 2026-10-12 审计订正：全项目搜 {@code applyShock} <b>只有本类自己调用</b>（{@code onEntityTickPost}），
     * 【脉冲】那边只负责挂效果、并没有在挂上时补扭一下 —— 原来那句「为了让【脉冲】等地方立刻扭一下」
     * 的理由在现代码里已经不成立。方法保持 {@code public static} 以备外部复用。</p>
     */
    public static void applyShock(LivingEntity mob) {
        if (mob instanceof ServerPlayer player) {
            applyToPlayer(player);
        } else if (mob instanceof Mob creature) {
            applyToMob(creature);
        } else {
            // 既不是玩家、也不是 Mob 的生物 —— **模组 Boss 常常如此**（不继承 Mob 也能是正经 Boss），
            // 也可能是盔甲架那类。没有 MoveControl 可改，就直接把它的**速度方向**绕 Y 轴随机转一下：
            // 与玩家那条同款，区别只在于不用发包（没有客户端要同步它的朝向）。
            Vec3 velocity = mob.getDeltaMovement();
            if (velocity.lengthSqr() > 1.0E-6D) {
                float radians = (mob.getRandom().nextFloat() - 0.5F) * 2.0F * (float) Math.PI;
                mob.setDeltaMovement(velocity.yRot(radians));
            }
            float yaw = mob.getYRot() + (mob.getRandom().nextFloat() - 0.5F) * YAW_SWING;
            mob.setYRot(yaw);
            mob.setYHeadRot(yaw);
        }
    }

    /** 玩家：改速度 + 发包强制扭视角 */
    private static void applyToPlayer(ServerPlayer player) {
        Vec3 velocity = player.getDeltaMovement();
        if (velocity.lengthSqr() > 1.0E-6D) {
            // 绕 Y 轴随机旋转一个角度。注意 Vec3#yRot 收的是**弧度**（内部走 Mth.cos/sin）
            float radians = (player.getRandom().nextFloat() - 0.5F) * 2.0F * (float) Math.PI;
            player.setDeltaMovement(velocity.yRot(radians));
            // 改了速度要标脏，否则客户端还按旧速度插值，看起来是「瞬移」而不是「转向」
            player.hurtMarked = true;
        }

        float yaw = player.getYRot() + (player.getRandom().nextFloat() - 0.5F) * YAW_SWING;
        float pitch = Mth.clamp(
                player.getXRot() + (player.getRandom().nextFloat() - 0.5F) * PITCH_SWING,
                -90.0F, 90.0F);
        player.setYRot(yaw);
        player.setXRot(pitch);
        // 服务端改朝向必须发包，客户端才会真的扭头。
        // ⚠️ 用 forceSetRotation（纯朝向包）而不是 connection.teleport ——
        //    后者会把 awaitingPositionFromClient 设上，那期间右键方块会被
        //    handleUseItemOn 的 else 分支误报「建筑高度上限」（作者 2026-10-14 报的串台 bug）。
        player.forceSetRotation(yaw, false, pitch, false);
    }

    /**
     * 非玩家生物：**必须在 AI 那一层下手**。
     *
     * <p>⚠️ 这里是作者 2026-10-10 反馈「【震荡】对非玩家生物无效」的根因所在：
     * 生物的位移是 AI 驱动的 —— 无论 {@code setDeltaMovement} 还是 {@code push}，
     * 下一刻 {@code MoveControl} 都会重新算一遍并覆盖掉，改速度等于白改。</p>
     *
     * <p>正确做法是改它的<b>行走目标</b>：把「想去的点」随机挪到另一个方向，
     * {@code MoveControl} 下一 tick 就会照着这个新目标走 —— 表现上就是真的朝歪方向迈步。
     * 同时把身体和头的朝向也扭过去，视觉上才「晕」。</p>
     */
    private static void applyToMob(Mob creature) {
        RandomSource random = creature.getRandom();
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double distance = 3.0D;
        creature.getMoveControl().setWantedPosition(
                creature.getX() + Math.cos(angle) * distance,
                creature.getY(),
                creature.getZ() + Math.sin(angle) * distance,
                0.6D);

        float yaw = (float) Math.toDegrees(angle);
        creature.setYRot(yaw);
        creature.setYHeadRot(yaw);
        creature.yBodyRot = yaw;
    }
}
