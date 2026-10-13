package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.BallProjectile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Blaze;
// 26.x 把这些类重组过包结构：Pillager 挪进 monster.illager，
// AbstractArrow 挪进 projectile.arrow —— 老路径编译不过
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 怪物侧的球行为 —— 三条独立的机制。
 *
 * <h2>一、被球打到时会「接球反掷」</h2>
 * 能持武器的怪物被球击中时，按<b>难度</b>掷一次概率（简单 10% / 普通 25% / 困难 50%）
 * 把球接下来：手上有空位就收进球，转身开始蓄力，蓄满再扔回来。
 *
 * <h2>二、自然生成时概率携带球</h2>
 * 生成时主手空着的敌对生物有 {@link #CARRY_CHANCE} 的概率拿一颗球当远程武器。
 * 抽哪一颗由稀有度权重决定（木球最常见、末影珍珠最罕见，空心铁球权重 0 永不出现）。
 *
 * <h2>三、掠夺者改用球当弩弹药</h2>
 * 掠夺者生成时有 {@link #PILLAGER_AMMO_CHANCE} 的概率被打上标记，之后它射出的箭
 * 会在加入世界的那一刻被换成球。这里不碰原版弩的任何代码 —— 掠夺者照常走它自己的
 * 装填与射击流程，我们只在其投射物实体诞生的瞬间做替换。
 *
 * <h2>蓄力与闪避为什么不用 Goal</h2>
 * 怪物没有玩家那套「使用物品计时器」，所以蓄力状态直接挂在实体身上
 * （{@link ModAttachments#BALL_CHARGE_END}），由实体 tick 事件驱动：
 * 面向目标 → 像骷髅那样左右侧移 → 到点投掷。这样不必去动原版 AI 的 goal 列表。
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallMobAI {

    private BallMobAI() {
    }

    /** 生成时携带球的概率（作者指定 10%） */
    public static final float CARRY_CHANCE = 0.10F;
    /** 【烈焰人】生成时携带球的概率（作者 2026-10-12 指定：60%） */
    public static final float BLAZE_CARRY_CHANCE = 0.60F;

    /** 【烈焰人】生成携带的数量范围（含两端）—— 作者指定 1~11 个 */
    public static final int BLAZE_CARRY_MIN = 1;
    public static final int BLAZE_CARRY_MAX = 11;

    /** 掠夺者改用球作弹药的概率（作者指定 20%） */
    public static final float PILLAGER_AMMO_CHANCE = 0.20F;

    // ===== 特性类词条 / 球特性的「触发概率闸门」（作者 2026-10-13 指定）=====
    //
    // 每个「球特性真正生效的那一处」前面都套一道闸门，口径与
    // BallConduction（【导电】）/ BallTransmute（【点金】）/ RedstonePulse（【脉冲】）/
    // BallIlluminate（【照明】）完全一致：`level.getRandom().nextFloat() < 常量`，
    // 且常量 = 1.0F 时行为与加闸门之前一字不差 —— 以后要削弱哪个特性只调那一个数。
    //
    // ⚠️ 这里只放「运行期一次性生效」的触发点。生成期配置（携带球、掠夺者标记）、
    //    AI 状态机（幻翼三阶段、烈焰人球袋、苦力怕引信）一律不进闸门 —— 见各处的说明。

    public static final double PICKUP_RADIUS = 1.5D;

    /**
     * 捡球判定概率的<b>基准值</b> —— 实际判定概率是它 × {@link #PICKUP_TRY_INTERVAL}
     * ＝ <b>每次尝试 4%</b>；判定只每 2 刻做一次，所以手感仍是「平均每刻 2%」。
     *
     * <p>也就是平均约 50 刻（2.5 秒）才会捡起来一次，怪物会在球旁边磨蹭一会儿，
     * 而不是一走近就秒捡。</p>
     */
    public static final float PICKUP_CHANCE = 0.02F;

    /** 拾取尝试的间隔（刻）—— 与 PICKUP_CHANCE 配合，概率按倍数补回 */
    private static final int PICKUP_TRY_INTERVAL = 2;

    /**
     * 怪物主动去找球的搜索半径（格）。
     *
     * <p>比拾取半径（{@value #PICKUP_RADIUS}）大得多 —— 拾取是「贴上才拿」，
     * 这个半径管的是「能看见多远的目标」，两者不是一回事。</p>
     */
    private static final double SEEK_RADIUS = 8.0D;

    /** 走向球时的移动速度倍率 */
    private static final double SEEK_SPEED = 1.0D;

    /** 每几刻扫一次附近有没有球（只为「决定要不要走过去」，拾取判定不受这个限制） */
    private static final int SEEK_SCAN_INTERVAL = 10;

    /** 接球概率：简单难度 */
    public static final float CATCH_CHANCE_EASY = 0.10F;

    /** 接球概率：普通难度 */
    public static final float CATCH_CHANCE_NORMAL = 0.25F;

    /** 接球概率：困难难度 */
    public static final float CATCH_CHANCE_HARD = 0.50F;

    /**
     * 怪物蓄力的等级选择概率（作者指定）：
     * <b>30% 不蓄力直接扔回 / 40% 蓄一层 / 20% 蓄两层 / 剩余 10% 蓄到最大</b>。
     */
    public static final float MOB_CHARGE_NONE_CHANCE = 0.30F;

    /** 蓄一层的概率 */
    public static final float MOB_CHARGE_ONE_CHANCE = 0.40F;

    /** 蓄两层的概率 */
    public static final float MOB_CHARGE_TWO_CHANCE = 0.20F;

    /**
     * 【敌对球 · 嘎枝】的蓄力时长：<b>1 秒</b>（作者指定）。
     *
     * <p>与别的怪物不同 —— 它们按概率掷 0/1/2/满 级，蓄力时长随之变化；
     * 嘎枝是<b>固定蓄 1 秒</b>，然后一次性以双倍满蓄力扔出。</p>
     */
    public static final int CREAKING_CHARGE_TICKS = 20;

    // ===== 【敌对球 · 幻翼】的规格（作者 2026-10-11 指定）=====

    /**
     * 判「这只幻翼在不在俯冲」的方向阈值。
     *
     * <p>速度方向与「指向目标」的夹角余弦要大于它。俯冲时原版把
     * {@code moveTargetPoint} 直接设成玩家位置，所以是**冲着玩家飞**；
     * 绕圈（CIRCLE）时方向是**切向**的，对不上这个阈值。</p>
     */
    public static final double PHANTOM_DIVE_ALIGN = 0.7D;

    /** 找球的半径：<b>以目标玩家为中心的 11×11 区域</b>（作者指定）→ 半边长 5.5 */
    public static final double PHANTOM_BALL_SEARCH_RADIUS = 5.5D;

    /** 判定「已经贴到球上、可以捡了」的距离平方 */
    public static final double PHANTOM_PICKUP_RANGE_SQR = 4.0D;

    // ===== 【幻翼】投弹俯冲的三个阶段（作者 2026-10-11 指定）=====
    //
    //   0 普通 —— 原版那套（绕圈 / 俯冲）；俯冲中没球就去捡
    //   1 爬升 —— **捡到球了**：不攻击、不扔，飞回高空
    //   2 投弹 —— 高度够了：加速大幅提升地俯冲，**全程发光**，
    //             距玩家 10 格时按 0 级蓄力扔出，然后进撤退、飞回天上

    /** 阶段 0：普通（原版行为） */
    public static final int PHANTOM_PHASE_NORMAL = 0;
    /** 阶段 1：捡到球 → 爬升回高空 */
    public static final int PHANTOM_PHASE_CLIMB = 1;
    /** 阶段 2：投弹俯冲 */
    public static final int PHANTOM_PHASE_BOMB = 2;
    /**
     * 阶段 3：**球已经扔出去了**，正在飞回天上 —— **这期间同样不攻击**。
     *
     * <p>⚠️ 作者 2026-10-11 报「怎么扔都是 2 滴血」的根因就在这里：
     * 投弹后如果直接回「阶段 0」，{@code onPhantomAttackPlayer} 的取消**当场失效**，
     * 幻翼接着撞到玩家造成的是**原版近战伤害**（恒定 2），看起来就像「球只打了 2」。
     * 规格是「扔出球之后**不攻击**，飞回天上」—— 所以必须单独占一个阶段。</p>
     */
    public static final int PHANTOM_PHASE_RETREAT = 3;

    /**
     * 幻翼「扑向球」时每刻写入的速度大小（格/刻）。
     *
     * <p>⚠️ 必须**直接写 {@code deltaMovement}**、不能只靠
     * {@code getMoveControl().setWantedPosition}：原版
     * {@code PhantomSweepAttackGoal} 每刻都会重设移动目标，把 MoveControl 的方案盖掉。
     * 而 {@code EntityTickEvent.Post} 跑在原版 {@code aiStep} **之后** ——
     * 在这里写进去的就是当刻的最终速度，不会被覆盖。</p>
     */
    public static final double PHANTOM_BALL_CHASE_SPEED = 0.5D;

    /** 爬升时每刻的竖直速度下限（格/刻）—— 强上抬，别继续往下扎 */
    public static final double PHANTOM_CLIMB_SPEED = 1.0D;

    /**
     * 投弹时把**幻翼自身的速度**叠加到球上的系数（作者 2026-10-11 指定）。
     *
     * <p>球的初速度 = 原本蓄力层数算出来的那套 × {@code (1 + 幻翼速度 × 本系数)}。</p>
     * <p>幻翼俯冲速度常见 1~3 格/刻，取 0.5 → 额外 <b>+50% ~ +150%</b> ——
     * 「俯冲越猛、球打得越狠」，与作者要的「叠加上幻翼速度」一致。</p>
     */
    public static final double PHANTOM_SPEED_BONUS = 0.5D;

    /**
     * 投弹的**提前量**（刻）—— 瞄「玩家再过这么多刻会在的位置」，而不是他现在的位置。
     *
     * <p>幻翼俯冲时球相对玩家是高速接近的，按当前位置瞄会**系统性打偏**（都落在人后面）。
     * 取 8 刻 ≈ 0.4 秒，覆盖 {@value #PHANTOM_BOMB_THROW_DISTANCE} 格投弹距离上的
     * 飞行时间（球速约 1.2 格/刻）。</p>
     */
    public static final double PHANTOM_LEAD_TICKS = 8.0D;

    /**
     * 爬到「比玩家高这么多格」就转入投弹俯冲（作者 2026-10-11 指定：**40 格**）。
     *
     * <p>与 {@link #PHANTOM_CLIMB_TICKS} 配合：先到哪个算哪个。</p>
     */
    public static final double PHANTOM_CLIMB_HEIGHT = 40.0D;

    /**
     * 爬升阶段的时间上限（刻）—— **作者指定 3 秒**，到点无论爬没爬到 {@link #PHANTOM_CLIMB_HEIGHT}
     * 都直接转投弹俯冲。
     *
     * <p>⚠️ 必须有这条兜底：爬升期间原版 {@code PhantomSweepAttackGoal} 每刻还在把速度
     * 朝它的目标点（玩家，在下方）拉 20%，只靠高度判据有可能**永远爬不上去**，
     * 卡在半空出不来。</p>
     */
    public static final int PHANTOM_CLIMB_TICKS = 60;

    /** 投弹俯冲全程的发光时长 / 刷新间隔（刻） */
    public static final int PHANTOM_GLOW_TICKS = 40;
    public static final int PHANTOM_GLOW_REFRESH = 20;

    /** 幻翼 UUID → **进入爬升阶段时的 tickCount**（给「3 秒内冲上 40 格」兜底用） */
    private static final java.util.Map<java.util.UUID, Integer> PHANTOM_CLIMB_START =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 幻翼 UUID → 当前阶段。瞬态数据，死亡时清掉 */
    private static final java.util.Map<java.util.UUID, Integer> PHANTOM_PHASE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 读幻翼当前阶段 */
    public static int phantomPhase(Phantom phantom) {
        return PHANTOM_PHASE.getOrDefault(phantom.getUUID(), PHANTOM_PHASE_NORMAL);
    }

    private static void setPhantomPhase(Phantom phantom, int phase) {
        if (phase == PHANTOM_PHASE_NORMAL) {
            PHANTOM_PHASE.remove(phantom.getUUID());
            PHANTOM_CLIMB_START.remove(phantom.getUUID());
        } else {
            PHANTOM_PHASE.put(phantom.getUUID(), phase);
            if (phase == PHANTOM_PHASE_CLIMB) {
                PHANTOM_CLIMB_START.put(phantom.getUUID(), phantom.tickCount);
            }
        }
    }
    /**
     * 【幻翼】爬升 / 投弹期间**不许攻击玩家**（作者 2026-10-11 指定）。
     *
     * <p>原版俯冲撞到玩家就会造成一次近战伤害（{@code PhantomSweepAttackGoal} 里
     * 判包围盒相交后 {@code doHurtTarget}）。那条逻辑在私有内部类里动不了，
     * 所以在这里把「幻翼打玩家」这一次伤害取消掉 —— 用的是官方事件，不碰原版类。</p>
     */
    @SubscribeEvent
    public static void onPhantomAttackPlayer(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof Phantom phantom)) {
            return;
        }
        // ⚠️⚠️ **只拦幻翼自己的近战，绝不能拦它扔出的球的伤害**。
        //
        //    这一条就是作者 2026-10-11 报「球打到我身上没伤害」的**根因**。
        //    球造成的伤害，伤害源同样是 `mobAttack(幻翼)` ——
        //    所以上面那句 `getSource().getEntity() instanceof Phantom` **照样成立**，
        //    于是球命中的每一次都被下面那道取消吞掉；而球的碎裂发生在
        //    `BallProjectile.onHit` 的 `burst()`（在伤害结算**之后**），
        //    所以现象正是「球会飞、会打中、会碎，就是没伤害」。
        //
        //    `DamageSource` 有两个不同的实体：
        //      · getEntity()        —— **责任方**（这里 = 幻翼本人）
        //      · getDirectEntity()  —— **直接造成者**：
        //            近战时 = 幻翼本人；投射物伤害时 = **那颗球本身**
        //    用后者区分，就能只拦近战、放行球的伤害。
        if (event.getSource().getDirectEntity() instanceof BallProjectile) {
            return;   // 是球打的 → 放行，照常结算伤害
        }
        if (phantomPhase(phantom) != PHANTOM_PHASE_NORMAL) {
            event.setCanceled(true);
        }
    }

    /**
     * 投弹距离（格）—— **作者 2026-10-11 指定：10 格**。
     *
     * <p>这个距离同时解决了一个硬伤：{@code BallProjectile.onHitEntity} 前 2 刻
     * （{@code SPAWN_GRACE_TICKS}）**完全不结算伤害**。而球的出手速度约 1.2 格/刻 ——
     * 10 格要约 <b>8 刻</b>才飞到，**稳稳越过那 2 刻**。</p>
     * <p>（4 格时只有约 3 刻，一旦幻翼自身还在 3 格/刻俯冲、球出生点被拉到脸上，
     * 就会出现「tick=1 命中 → 伤害被无害期吃掉」—— 日志实测过 9 次。）</p>
     */
    public static final double PHANTOM_BOMB_THROW_DISTANCE = 10.0D;

    /**
     * 【幻翼】投弹俯冲每刻的速度乘数（作者 2026-10-11：「**加速度大幅提升**」）。
     *
     * <p>每刻把速度乘这个倍数 —— 复利累积，飞得越久冲得越快。原版
     * {@code PhantomMoveControl} 的 {@code speed} 是私有字段、摸不到，
     * 所以直接在速度上补一刀。</p>
     *
     * <p>⚠️⚠️ <b>这个数必须算过两道衰减才算得准</b>（都是实际读源码确认的行号）：</p>
     * <ol>
     *   <li>{@code LivingEntity.travelFlying} L2561-2564（{@code Phantom.travel} L148-150
     *       就是调它）：{@code setDeltaMovement(·scale(0.91F))} —— <b>×0.91</b></li>
     *   <li>{@code PhantomSweepAttackGoal.tick} L429-430：
     *       {@code delta += (moveTargetPoint - delta) * 0.2} —— 每刻朝原版的目标点
     *       <b>再拉走 20%</b>，即 <b>×0.8</b></li>
     * </ol>
     * <p><b>净保留 ≈ 0.8 × 0.91 ≈ 0.73</b> —— 也就是说，
     * <b>连「维持速度不衰减」都要 {@code > 1/0.73 ≈ 1.37}</b>。</p>
     * <p>原先的 <b>1.03</b> 是净 <b>×0.75</b>（减速）；中间那版 1.30 <b>仍然低于盈亏线</b>，
     * 还是减速。现在取 <b>1.60</b> → 净每刻 ×1.17，约 4~5 刻翻一倍，才叫「极大幅」。</p>
     * <p>⚠️ 光靠乘系数还不够稳 —— 所以投弹阶段**每刻都会重新把方向掰向玩家**
     * （见 {@code phantomBallTick}），否则原版那条 lerp 会把方向也拽回它的目标点。</p>
     */
    public static final double PHANTOM_BOMB_ACCEL = 1.60D;

    /** 投弹俯冲的速度上下限（格/刻）—— 别让它加速到瞬移 */
    public static final double PHANTOM_BOMB_MIN_SPEED = 0.35D;
    public static final double PHANTOM_BOMB_MAX_SPEED = 3.0D;

    // ===== 【敌对球 · 烈焰人】的规格（作者 2026-10-11 指定）=====

    /** 烈焰人的棒子长度（模型单位，`BlazeModel` 里是 `addBox(0,0,0, 2,8,2)`）—— 棒尖在轴心往下 8 */
    public static final float BLAZE_ROD_LENGTH = 8.0F;

    /**
     * 烈焰人十二肢上那颗球的缩放。
     *
     * <p>比头顶展示（{@link MobBallDisplay#HEAD_SCALE}）再小一点 —— 十二颗挤在身上的棒子上。</p>
     */
    public static final float BLAZE_ROD_BALL_SCALE = 0.4F;

    /**
     * 模型坐标 → 世界偏移时那一段「往上抬」的量。
     *
     * <p>来自 {@code LivingEntityRenderer} L91 的 {@code translate(0.0F, -1.501F, 0.0F)}。
     * 用玩家验证过：{@code HumanoidModel} 的脚在模型 y=24，
     * {@code 1.501 - 24/16 = 0.001} ≈ 0 —— 正好落在脚底。</p>
     */
    public static final double MODEL_Y_LIFT = 1.501D;

    /**
     * 怪物投掷时，**瞄准点从眼睛往下压多少**（按目标身高的比例）。
     *
     * <p>⚠️ {@code throwBall} 的 {@code aimDrop} 参数一直是 {@code 0} ——
     * 也就是**瞄着目标的眼睛**，再加上 L1120 那道防空抛物线抬升，
     * 两个都往上，结果就是作者 2026-10-11 报的「**老是从头上飞过去**」。
     * 压到身高的一半左右（约胸口）才打得到人。</p>
     */
    public static final double AIM_DROP_RATIO = 0.5D;

    /**
     * 怪物投掷时通用的「瞄准点压低量」。
     *
     * <p><b>null 安全</b>：{@code target} 为空时 {@code throwBall} 走的是「照视线扔」那条路
     * （{@code aimDrop} 根本用不上），这里返回 0 即可 —— 直接写
     * {@code target.getBbHeight()} 会 NPE。</p>
     */
    private static double mobAimDrop(LivingEntity target) {
        return target == null ? 0.0D : target.getBbHeight() * AIM_DROP_RATIO;
    }

    /**
     * 这只怪**每次尝试**捡球的概率（都是「每次尝试」口径，不含间隔）。
     *
     * <p>⚠️ 通用怪那条写的是 {@code PICKUP_CHANCE * PICKUP_TRY_INTERVAL} ——
     * 那是因为尝试被节流成 {@link #PICKUP_TRY_INTERVAL} 刻一次，
     * 所以把概率翻倍补回来、保持「平均每刻 2%」这个手感。
     * 换成别的地方调用时**别把这个换算漏掉或重复算**。</p>
     *
     * <p>烈焰人单独一套：{@link #BLAZE_PICKUP_CHANCE}（作者 2026-10-11 指定 30%）。</p>
     */
    private static float pickupChance(Mob mob) {
        if (mob instanceof Blaze) {
            return BLAZE_PICKUP_CHANCE;
        }
        return PICKUP_CHANCE * PICKUP_TRY_INTERVAL;
    }

    // ===== 怪物投掷的「抛物线抬升」 =====
    //
    // ⚠️ 这三个数就是「弹道往上抬多少」的全部来源，**要调只调这里**。
    //
    // 原来是一行 `min(0.35, 0.08 + 水平距离 * 0.02)` —— 在 10 格距离上抬
    // **2.8 格**（方向抬约 13°）。而瞄准点本身（眼睛→胸口）加起来才往下压 0.42 格，
    // 完全压不住那 2.8 —— 结果就是作者 2026-10-11 连报两次的
    // 「**y 轴太高、老是从头上飞过去**」。
    //
    // 现在压到「几乎平射、只留一丝弧线」：10 格距离抬约 0.7 格（约 1.3°）。
    //
    // 调法：
    //   · 还是偏高 → 调小 AIM_LIFT_PER_BLOCK（或直接设成 0 = 完全平射）
    //   · 变成偏低 / 打不到远处 → 调大它，每次 0.002 左右加

    /** 抬升的固定项 */
    public static final double AIM_LIFT_BASE = 0.02D;

    /** 抬升随水平距离增长的系数（每格） */
    public static final double AIM_LIFT_PER_BLOCK = 0.005D;

    /** 抬升的上限 */
    public static final double AIM_LIFT_MAX = 0.08D;

    /**
     * 【烈焰人】每次尝试捡球的概率（作者 2026-10-11 指定：<b>30%</b>）。
     *
     * <p>⚠️ 这是「**每次尝试**」的概率 —— 和别的怪物那套口径一致
     * （它们写的是 {@code PICKUP_CHANCE * PICKUP_TRY_INTERVAL = 4%}，也是每次尝试）。
     * 所以这里直接就是 {@code 0.30}，**不要再乘 {@link #PICKUP_TRY_INTERVAL}**。</p>
     *
     * <p>配合 {@link #PICKUP_TRY_INTERVAL}（2 刻一次），
     * 实际相当于平均每刻 15% —— 它要求「飞过去就把球收进来」，捡慢了就白追一趟。</p>
     */
    public static final float BLAZE_PICKUP_CHANCE = 0.30F;

    /**
     * 【烈焰人】的<b>拾取球判定范围</b>（作者 2026-10-11 指定）—— <b>15 × 15 × 5</b>。
     *
     * <p>以烈焰人自身为中心的长方体：水平 {@value #BLAZE_PICKUP_XZ} 格、
     * 垂直 {@value #BLAZE_PICKUP_Y} 格。</p>
     *
     * <p>别的怪物沿用的是 {@link #PICKUP_RADIUS}（1.5 格的球形）—— 那对地上爬的够用，
     * 但烈焰人是在空中飞的，1.5 格基本什么都够不着。</p>
     */
    public static final double BLAZE_PICKUP_XZ = 15.0D;

    /** 见 {@link #BLAZE_PICKUP_XZ} —— 垂直方向 5 格 */
    public static final double BLAZE_PICKUP_Y = 5.0D;

    /** 烈焰人最多持有的球数 */
    public static final int BLAZE_MAX_BALLS = 12;

    /** 一轮最少打几发 —— 球不够就**用烈焰弹补足**到这个数（作者指定 3） */
    public static final int BLAZE_BURST_MIN = 3;

    /** 开火前的蓄力（刻）—— 沿用原版那 60 刻 */
    public static final int BLAZE_CHARGE_TICKS = 60;

    /** 一轮之内两发之间的间隔（刻）—— 沿用原版那 6 刻 */
    public static final int BLAZE_SHOT_INTERVAL = 6;

    /** 一轮打完之后进冷却的时长（刻）—— 沿用原版那 100 刻 */
    public static final int BLAZE_COOLDOWN_TICKS = 100;

    /**
     * 烈焰人扔球时的蓄力等级 = <b>最大蓄力 ÷ 这个数</b>（作者指定：一半）。
     *
     * <p>而且它<b>不蓄力</b> —— 等级定死，不等蓄力条。</p>
     */
    public static final int BLAZE_CHARGE_LEVEL_DIVISOR = 2;


    /** 【敌对球 · 嘎枝】的蓄力等级 = 最大蓄力 × 这个倍数（作者指定两倍） */
    public static final int CREAKING_CHARGE_LEVEL_TIMES = 2;

    /** 蓄力期间的侧移强度（骷髅式闪避） */
    private static final float STRAFE_STRENGTH = 0.4F;

    /** 侧移换向的间隔（刻） */
    private static final int STRAFE_SWITCH_TICKS = 8;

    // ===== 一、接球反掷 =====

    /**
     * 这只怪有没有「手」—— 只有能拿东西的才会接球反击。
     *
     * <p>判据用排除法而不是「检查主手槽」：原版给每种生物都留了主手槽位，
     * 苦力怕的主手槽也永远存在（只是永远空着、也永远不显示），
     * 所以从槽位看不出有没有手，只能按类型排除。</p>
     *
     * <p><b>苦力怕是个例外</b>（作者 2026-10-07 指定）：它现在<b>能接球、能捡球</b>，
     * 只是<b>不主动扔</b> —— 它拿球是为了自爆前 2 刻朝目标抛出去，
     * 那套走 {@code CreeperBallBehavior}，不走这里的投掷流程。</p>
     *
     * <h2>⚠️ 2026-10-11 起改成白名单</h2>
     * <p>原来是「排除法」（排掉史莱姆 / 恶魂 / 烈焰人 / 潜影贝 / 末影人），作者这一轮
     * 把名单重排成 <b>19 种</b>、并且<b>加了烈焰人与史莱姆</b> ——
     * 「没有手」这个判据已经不成立了（烈焰人一圈棒子、史莱姆根本没手，
     * 但作者指定它们要能用球），所以只能改成显式白名单。</p>
     *
     * <p>这一处同时管着三条路：接住飞来的球、捡地上的球、以及「生成时携带球」。</p>
     */
    private static boolean canUseBalls(Mob mob) {
        // ⚠️ 两个「子类陷阱」必须显式排掉 —— 父类在名单里，但它们自己不在：
        //    · 岩浆怪 extends 史莱姆
        //    · 僵尸猪灵 extends 僵尸
        if (mob instanceof MagmaCube || mob instanceof ZombifiedPiglin) {
            return false;
        }
        return mob instanceof AbstractSkeleton   // 骷髅 / 流浪者 / 沼骸 / 焦骸 / 凋灵骷髅
                || mob instanceof Zombie          // 僵尸 / 尸壳 / 溺尸 / 僵尸村民
                || mob instanceof Spider          // 蜘蛛 / 洞穴蜘蛛（洞穴蜘蛛是子类）
                || mob instanceof Piglin          // 猪灵（猪灵蛮兵是 AbstractPiglin 的另一支，不含）
                || mob instanceof Pillager
                || mob instanceof Creeper
                || mob instanceof Phantom
                || mob instanceof Vex
                || mob instanceof Creaking
                || mob instanceof Blaze
                || mob instanceof Slime
                // 【联动口子】作者 2026-10-11 指定：再开一条实体标签。
                // 第三方模组 / 整合包把自己的生物写进 #more_balls:ball_carriers
                // （数据包即可）就自动支持，不用改代码、不用重编译。
                // ⚠️ 这里是**「或」**不是替换 —— 上面那些 instanceof 照旧生效，
                //    所以就算有人拿 replace:true 把标签整个覆盖掉，原版 19 种也不会失效。
                // ⚠️ 26.2 里判实体标签是 **{@code entity.is(tag)}**，不是在 {@code getType()} 上 ——
                //    {@code EntityType} 现在只剩 builtInRegistryHolder() 与 getTags()，
                //    老的 {@code getType().is(tag)} 编译不过（原版 114 处全是 mob.is(...)）。
                || mob.is(ModTags.EntityTypes.BALL_CARRIERS);
    }

    /**
     * 自然生成时的「抽一颗球」—— 在 {@link BallBehavior#randomBall} 外面加一层持有者限制。
     *
     * <p><b>规则（作者 2026-10-13 指定）</b>：潮汐球权重 20（会进随机抽取），
     * 但它<b>只能出现在溺尸手中</b>，且<b>自然生成的耐久必定为 1</b>。</p>
     *
     * <p>为什么不改 {@code BallBehavior.randomBall}：那个方法不知道「是谁在抽」，
     * 掠夺者 / 烈焰人 / 普通怪三条路都调它，所以限制放在这里、由调用方把持有者传进来。</p>
     *
     * <p>⚠️ 判据用的是<b>自家物品 id</b>（{@code more_balls:tidal_ball}），不违反
     * 「不硬编码第三方 id」那条规矩。将来若还有别的「限定持有者」的球，把这里抽成一张小表即可。</p>
     *
     * <p>⚠️ 溺尸的包名是 {@code ...entity.monster.zombie.Drowned}（26.x 把它搬进了子包，
     * 和村民/铁傀儡一样），所以这里直接用全限定名，免得再猜错 import。</p>
     */
    private static ItemStack randomBallFor(Mob mob, RandomSource random) {
        ItemStack ball = BallBehavior.randomBall(random);
        if (ball.isEmpty()) {
            return ball;
        }
        if (ball.is(ModItems.TIDAL_BALL.get())) {
            if (!(mob instanceof net.minecraft.world.entity.monster.zombie.Drowned)) {
                return ItemStack.EMPTY;   // 非溺尸：这一发不发，下次生成再抽
            }
            BallItem.applyToughness(ball, 1);   // 自然生成的潮汐球：耐久必定为 1
        }
        return ball;
    }

    /**
     * 尝试让怪物接住飞来的球 —— 由 {@link BallProjectile} 命中实体时调用。
     *
     * @return 是否接住了（接住则球已被移除，命中伤害不该再结算）
     */
    public static boolean tryCatchBall(Mob mob, BallProjectile ball) {
        if (mob.level().isClientSide() || !mob.isAlive()) {
            return false;
        }

        // 只对「会主动战斗的怪物」生效，牛羊之类不参与
        if (!(mob instanceof Enemy)) {
            return false;
        }
        // 得先过白名单 —— 作者指定：只有名单里的那些才会接球反击（名单见 canUseBalls）。
        // 名单外的生物（比如没长手的那些）之前也在接球，纯属滑稽
        if (!canUseBalls(mob)) {
            return false;
        }

        // 【忠诚】的球**不会被任何生物捡走**（作者 2026-10-13 指定）—— 主动接球这条路的拦截。
        // 顺手捡球那条在 tryPickUpBall 里，挂在金球锁同一个判据上。
        if (isLoyal(ball)) {
            return false;
        }
        // 手上已经拿着东西就腾不出手接
        if (!mob.getMainHandItem().isEmpty()) {
            return false;
        }

        // 【接球】触发概率（当前 100% 必触发；以后调这个常量即可）
        float chance = catchChanceFor(mob.level().getDifficulty());
        if (mob.getRandom().nextFloat() >= chance) {
            return false;
        }

        ItemStack stack = ball.getItem().copy();
        BallItem.stripIntangible(stack);
        BallItem.applyToughness(stack, ball.getToughness());
        if (stack.isEmpty()) {
            return false;
        }
        mob.setItemSlot(EquipmentSlot.MAINHAND, stack);

        // 记住是谁扔的，接着蓄力扔回去。
        // 苦力怕也照设 —— 它本来就会追玩家（原版索敌），这条与它的原版行为一致，
        // 作者明确要「保留原版的苦力怕所有机制，正常索敌」。
        if (ball.getOwner() instanceof LivingEntity owner) {
            mob.setTarget(owner);
        }
        // 这一轮怎么蓄交给 tick 里的统一流程掷概率（30/40/20/10）
        mob.setData(ModAttachments.BALL_CHARGE_END.get(), 0);
        mob.setData(ModAttachments.BALL_CHARGE_LEVEL.get(), -1);

        ball.discard();
        return true;
    }

    /** 各难度下的接球概率 */
    private static float catchChanceFor(Difficulty difficulty) {
        return switch (difficulty) {
            case PEACEFUL, EASY -> CATCH_CHANCE_EASY;
            case NORMAL -> CATCH_CHANCE_NORMAL;
            case HARD -> CATCH_CHANCE_HARD;
        };
    }

    // ===== 捡地上的球 =====

    /**
     * 【金光闪闪】的吸引窗口内，这颗金球是否<b>禁止被捡取</b>。
     *
     * <p>规则（作者 2026-10-08）：金球第一次碰撞后 5 秒内不会被猪灵捡起，
     * 这段时间留出来做「猪灵被吸引」的表演。窗口外照常可捡。</p>
     */
    private static boolean isGoldLureLocked(BallProjectile ball) {
        if (!BallBehavior.isGoldShiny(ball.getItem())) {
            return false;
        }
        return ball.isGoldLuring(ball.level().getGameTime());
    }

    /**
     * 【忠诚】的球永远不会被任何生物捡走（作者 2026-10-13 指定）。
     *
     * <p>判据走 {@code profile}，所以**带潮汐球的组合球同样免疫** ——
     * 和【移形换位】那边一个道理：只要合成时 {@code comboProfile} 写回了 loyalty，
     * 这里就认。两个捡球入口（{@link #tryCatchBall} 与 {@link #tryPickUpBall}）都要过这一关。</p>
     */
    private static boolean isLoyal(BallProjectile ball) {
        return BallBehavior.profileFor(ball.getItem()).loyalty();
    }    /**
     * 顺手把脚边停着的球捡起来 —— 作者指定的规则：范围 1.5 格、<b>每 2 刻判定一次</b>、
     * 每次 <b>4%</b> 概率（= {@link #PICKUP_CHANCE} × {@link #PICKUP_TRY_INTERVAL}）；
     * 前提是怪的手空着。
     *
     * <p>捡到的球会直接进主手，之后走同一套蓄力投掷流程，所以捡起来就能扔。</p>
     */
    private static void tryPickUpBall(Mob mob) {
        if (!(mob instanceof Enemy)) {
            return; // 牛羊之类不参与
        }
        // 捡球同样得先有手 —— 没手的怪连球都拿不住，更别提扔
        if (!canUseBalls(mob)) {
            // 诊断：作者反馈「不能拿球的怪也会向球移动」—— 如果这里被拦住的怪
            // 却在日志里出现，说明有别的路径绕过了本方法（那时再顺着日志去查）。
            MoreBalls.LOGGER.debug("[ball][捡球] {} 没有手，拒绝捡球", mob.getType().toShortString());
            return;
        }

        // ① 先在<b>拾取范围</b>内找球。
        //
        //    ⚠️ **烈焰人是特例**（作者 2026-10-11 指定）：它的范围是以自身为中心的
        //    **15 × 15 × 5** 长方体，不是别的怪物用的 1.5 格球形 ——
        //    它是在空中飞的，1.5 格基本够不着什么东西。
        //
        //    这一步必须每 2 刻都做：拾取的「每次 4%」挂在这里，
        //    要是跟下面的「寻球扫描」一起被限流成 10 刻一次，
        //    实际概率就变成平均 12.5 秒才捡一颗 —— 脚下堆一堆球时看起来就是「压根不捡」。
        //
        //    ⚠️ 两种形态都算「脚下的球」（作者 2026-10-07 指定）：
        //      · 飞行中 / 已停下的**球实体**（BallProjectile）
        //      · 以及**掉落物形态的球**（ItemEntity 里装着 #more_balls:balls 的东西）——
        //        比如被爆炸炸飞、或者碎裂后滚出来的球
        //    两者共用同一套半径、概率、条件，所以先取实体、没有再取掉落物。
        // ⚠️ 先做一次**合并查询**：把「球实体 + 掉落物」一次捞出来，按类型分流。
        //    原来这里连着两次 getEntitiesOfClass（一次 BallProjectile、一次 ItemEntity），
        //    等于把世界的实体列表过了两遍 —— 半边的开销纯属浪费。
        List<BallProjectile> near = new java.util.ArrayList<>();
        List<ItemEntity> droppedNearList = new java.util.ArrayList<>();
        // ⚠️ 烈焰人这个长方体**必须从「中心」往外量**，不能对包围盒 inflate ——
        //    后者得到的是 (生物宽 + 15) × (生物高 + 5)；烈焰人是 0.6 × 1.8，
        //    算出来 15.6 × 6.8，跟作者要的「15×15×5」对不上。
        AABB pickupBox;
        if (mob instanceof Blaze) {
            Vec3 center = mob.getBoundingBox().getCenter();
            pickupBox = new AABB(
                    center.x - BLAZE_PICKUP_XZ / 2.0D,
                    center.y - BLAZE_PICKUP_Y / 2.0D,
                    center.z - BLAZE_PICKUP_XZ / 2.0D,
                    center.x + BLAZE_PICKUP_XZ / 2.0D,
                    center.y + BLAZE_PICKUP_Y / 2.0D,
                    center.z + BLAZE_PICKUP_XZ / 2.0D);
        } else {
            pickupBox = mob.getBoundingBox().inflate(PICKUP_RADIUS);
        }
        for (Entity e : mob.level().getEntitiesOfClass(Entity.class, pickupBox)) {
            if (e instanceof BallProjectile ball && ball.isAlive()) {
                // ⚠️ **别捡自己刚扔出去的那颗**（作者 2026-10-11 报的烈焰人 bug）。
                //    烈焰人的拾取范围是 15×15×5，它扔出去的球正好就在这个范围内 ——
                //    不拦的话下一轮扫描立刻又捡回来：球数永远掉不下去、
                //    齐射变成空转，看起来就是「球在自己手里打转」。
                //    玩家那边本来也有同类规则（OWNER_SELF_PICKUP_DELAY_TICKS）。
                if (ball.getOwner() == mob) {
                    continue;
                }
                near.add(ball);
            } else if (e instanceof ItemEntity item && item.isAlive() && BallAmmo.isBall(item.getItem())) {
                droppedNearList.add(item);
            }
        }
        if (!near.isEmpty()) {
            // 【金光闪闪】作者 2026-10-08 指定：金球在「碰撞后 5 秒」的吸引窗口内
            // **不能被捡起** —— 那段窗口是留给玩家看清「猪灵被吸引过来」的，
            // 球一落地就被捡走的话，这个效果根本看不见。
            BallProjectile candidate = near.get(0);
            if (isGoldLureLocked(candidate) || isLoyal(candidate)) {
                return;
            }
            // 【捡球】触发概率（当前 100% 必触发；以后调这个常量即可）
            if (mob.getRandom().nextFloat() < pickupChance(mob)) {
                pickUpBall(mob, candidate);
            }
            return;
        }

        ItemEntity droppedNear = droppedNearList.isEmpty() ? null : droppedNearList.get(0);
        if (droppedNear != null) {
            // 【捡球】触发概率（当前 100% 必触发；以后调这个常量即可）
            if (mob.getRandom().nextFloat() < pickupChance(mob)) {
                pickUpDroppedBall(mob, droppedNear);
            }
            return;
        }

        // ② 近处没有 → 每 10 刻才扫一次大范围，决定要不要走过去。
        //    这一步只是「找目标」，慢一点无所谓，没必要逐刻遍历实体列表。
        if (mob.tickCount % SEEK_SCAN_INTERVAL != 0) {
            return;
        }

        // 【作者 2026-10-09 指定】要不要为了捡球放弃打人？
        //
        //   算两个距离：怪 → 球、怪 → 当前的攻击目标。
        //   **只有当球明显更近（球距 < 玩家距 / 2）时**才走过去捡；
        //   否则放弃捡球，用原来的方式继续追打目标 ——
        //   不然怪物会为了一个远处的球把玩家晾在一边。
        // ⚠️ **没有攻击目标时直接放弃捡球**（作者 2026-10-09 实测抓到的 bug）。
        //
        //    原来用 `Double.MAX_VALUE` 兜底「没有目标」的距离，结果
        //    `任何球距 < MAX_VALUE / 2` 恒成立 —— 没目标的怪 100% 会去捡球，
        //    而且永远走不到头（日志里的 `目标距 1.797e308 / 2` 就是这个）。
        //
        //    作者的原意是「球比玩家近一半才优先捡球」—— 那前提是**确实有个玩家在追**。
        //    没有目标就没有比较对象，该照原方式闲逛/索敌，不要被球牵着走。
        LivingEntity combatTarget = mob.getTarget();
        if (combatTarget == null || !combatTarget.isAlive()) {
            return;
        }
        double distanceToTarget = mob.distanceTo(combatTarget);

        List<BallProjectile> far = findBalls(mob, SEEK_RADIUS);
        if (!far.isEmpty()) {
            BallProjectile candidate = far.get(0);
            if (mob.distanceTo(candidate) < distanceToTarget / 2.0D) {
                mob.getNavigation().moveTo(candidate, SEEK_SPEED);
            }
            return;
        }
        ItemEntity droppedFar = findDroppedBall(mob, SEEK_RADIUS);
        if (droppedFar != null && mob.distanceTo(droppedFar) < distanceToTarget / 2.0D) {
            mob.getNavigation().moveTo(droppedFar, SEEK_SPEED);
        }
    }

    /**
     * 找附近掉在地上的球物品。
     *
     * <p>判据用 {@link BallAmmo#isBall}（也就是 {@code #more_balls:balls} 标签），
     * 和飞行球那边完全一致 —— 所以原版雪球、末影珍珠这类也算在内。</p>
     */
    private static ItemEntity findDroppedBall(Mob mob, double radius) {
        List<ItemEntity> found = mob.level().getEntitiesOfClass(ItemEntity.class,
                mob.getBoundingBox().inflate(radius),
                e -> e.isAlive() && BallAmmo.isBall(e.getItem()));
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 捡起掉落物形态的球 —— 与捡飞行球同一套条件与结果。
     *
     * <p>唯一不同的是「球从哪儿来」：这边是物品实体，捡走之后要把实体收掉。</p>
     */
    private static void pickUpDroppedBall(Mob mob, ItemEntity dropped) {
        ItemStack stack = dropped.getItem().copy();
        BallItem.stripIntangible(stack);
        if (stack.isEmpty()) {
            return;
        }
        if (!equipBall(mob, stack, null)) {
            return;   // 手里有东西（且不是猪灵交易）→ 不抢
        }
        dropped.discard();
    }

    /**
     * 找附近的球。
     *
     * <p><b>刻意不检查「是否静止」</b>（作者指定）：原先要求 {@code isSettled()}，
     * 而那个状态很严格（速度 &lt; 0.03、脚下有支撑、不在脉冲保护期）——
     * 实测日志里它从头到尾没变成过 {@code true}，于是球明明躺在脚边怪物也「看不见」。
     * 后来放宽成「贴地且速度低」，作者反馈还是捡不起来，索性完全去掉 ——
     * 只要球还活着、在范围内，就能捡。</p>
     */
    private static List<BallProjectile> findBalls(Mob mob, double radius) {
        return mob.level().getEntitiesOfClass(BallProjectile.class,
                mob.getBoundingBox().inflate(radius),
                BallProjectile::isAlive);
    }

    /** 把球拿进手里（含猪灵交易） */
    private static void pickUpBall(Mob mob, BallProjectile ball) {
        ItemStack stack = ball.getItem().copy();
        BallItem.stripIntangible(stack);
        BallItem.applyToughness(stack, ball.getToughness());
        if (stack.isEmpty()) {
            return;
        }
        if (equipBall(mob, stack, ball)) {
            ball.discard();
        }
    }

    /**
     * 把一颗球装进怪的主手 —— <b>飞行球与掉落物球共用这一份</b>。
     *
     * <p>做三件事：处理手里原本拿着的东西、写入主手、清掉蓄力状态；
     * 金球入猪灵手时再补一次交易结算。</p>
     *
     * @param source 这颗球的<b>球实体</b>；掉落物形态没有这个对象，传 {@code null}
     * @return true 表示装好了（调用方据此决定要不要把来源收掉）
     */
    private static boolean equipBall(Mob mob, ItemStack stack, @Nullable BallProjectile source) {
        ItemStack previous = mob.getMainHandItem();

        // ===== 【敌对球 · 烈焰人】手里的球可以「顶加装填」（作者 2026-10-11 指定）=====
        // 它最多持有 12 颗：手上已经拿着**同一种**球时不是拒绝、而是往这一叠里加一颗。
        //
        // ⚠️ 这条必须放在「收起来」之前 —— 否则会把手上那一叠球当成「原本的东西」收走。
        // ===== 【敌对球 · 烈焰人】不走主手，走**球袋** =====
        //
        // 作者 2026-10-11 指定：烈焰人要能同时拿**不同种类**的球。
        // 主手只有一个物品栈、一叠只能装同一种，所以它单独用
        // {@link ModAttachments#BLAZE_BALLS}（一元素一颗，最多 {@link #BLAZE_MAX_BALLS} 颗）。
        //
        // ⚠️ 这一条**必须放在「收起来」之前**，而且**绝不能**掉到那条路去：
        //    早先让主手一叠球走「收起来 + 换上新的」，结果
        //      · 那一叠被塞进暂存、主手换成新的 1 颗 → **持有数从 N 掉到 1** → 只打 3 发；
        //      · 打完那颗后暂存放回来，主手装不下的**直接掉地上**；
        //      · 持有数在 N 和 1 之间来回跳，展示实体每跳一次就整批拆建 → 贴图基本看不见。
        if (mob instanceof Blaze blaze) {
            return blazeAddBall(blaze, stack);
        }

        // 作者 2026-10-11 指定：手上原本拿着的东西**收起来**，不是丢在地上 ——
        // 等这颗球扔出去之后再原样拿出（见 {@link #restoreStashedMainHand}）。
        // 丢在地上会被别的怪或玩家捡走，那等于把它的弓 / 金剑弄丢了。
        //
        // 早先这里是「手上拿着东西就**直接拒绝捡球**」（作者 2026-10-07 报
        // 「小白怎么接球了，还把弓扔了」那次的补法）。现在既然能收起来，
        // 就不必再拒绝了 —— 球扔完，弓会自己回来。
        if (!previous.isEmpty()) {
            stashMainHand(mob, previous);
        }

        mob.setItemSlot(EquipmentSlot.MAINHAND, stack);
        mob.setData(ModAttachments.BALL_CHARGE_END.get(), 0);
        mob.setData(ModAttachments.BALL_CHARGE_LEVEL.get(), -1);

        // 【金光闪闪】：猪灵捡到金球当场交易 ——
        // 没被这颗球打过就是「特殊交易」，一口气给好几次产物（详见 PiglinLure）。
        // 猪灵蛮兵不算 Piglin，这里自然被排除在外：它们只会拿着球，不办事。
        if (BallBehavior.isGoldShiny(stack) && mob instanceof Piglin piglin) {
            // 【金光闪闪】本趟是否生效 —— 由那颗球在第一个 tick 掷定
            if (source != null && source.traitFired(BallProjectile.TRAIT_GOLD_SHINY)) {
                boolean special = !Boolean.TRUE.equals(
                        piglin.getExistingDataOrNull(ModAttachments.GOLD_BALL_HURT.get()));
                PiglinLure.onPiglinPickedUpGoldBall(piglin, source);
                MoreBalls.LOGGER.info("[ball] 猪灵拾起金球并完成交易（特殊={}）", special);
            }
        }
        return true;
    }

    // ===== 主手物品的「收起来 / 拿出来」 =====

    // ===== 展示图标的「滞留清理」 =====

    /** 开服后等多少刻再扫（实体加载完再说）——0.4.0.24 就是没等、拿到 null 崩的 */
    public static final int DISPLAY_SWEEP_DELAY_TICKS = 60;

    /** 与 MobBallDisplay.TAG 同一个字符串 */
    private static final String DISPLAY_TAG = "more_balls:mob_ball_display";

    /** 每个维度只扫一次 */
    private static final java.util.Set<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> DISPLAY_SWEPT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * **清掉上次退出游戏时滞留的展示图标**（作者 2026-10-11 报的 bug）。
     *
     * <p>展示图标是**真实体**，会跟着区块一起存进存档；而
     * {@code MobBallDisplay} 里那张「UUID → 实体 id」的映射**只在内存里**。
     * 于是重进游戏后映射是空的 → 代码按需**新建一批** → 旧的那一批
     * **永远留在世界上**（幻翼头顶那颗球会一直挂着）。</p>
     *
     * <p><b>所有挂了球图标的怪都有这个问题</b>（蜘蛛 / 幻翼 / 苦力怕 / 嘎枝 /
     * 史莱姆 / 烈焰人十二肢），不只是幻翼。</p>
     *
     * <p>进服第一刻统一清一遍 —— 图标本来就是纯显示、下一 tick 会按需重建，
     * 清掉不会有任何损失。</p>
     */
    @SubscribeEvent
    public static void onServerTickSweepDisplays(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        // ⚠️⚠️ **必须等服务器跑一会儿再扫**（0.4.0.24 崩服的原因）：
        //    刚开服那几刻实体还没全加载，`level.getAllEntities()` 会**吐出 null**，
        //    直接調 `e.entityTags()` 就是
        //    `NullPointerException: ... because "e" is null` → **整个 tick 循环崩掉**。
        if (event.getServer().getTickCount() < DISPLAY_SWEEP_DELAY_TICKS) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (!DISPLAY_SWEPT.add(level.dimension())) {
                continue;   // 这个维度已经扫过了
            }
            // ⚠️ **先收集成快照，再统一 discard** ——
            //    绝不能在遍历实体的过程中改实体列表（AGENTS 里「不要在遍历中增删实体」那条）。
            List<Entity> stranded = new java.util.ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                // null 守卫：加载中的区块会给出 null（就是上面那次崩服）
                if (e != null && e.entityTags().contains(DISPLAY_TAG)) {
                    stranded.add(e);
                }
            }
            for (Entity e : stranded) {
                e.discard();
            }
            if (!stranded.isEmpty()) {
                MoreBalls.LOGGER.info("[ball] 清掉 {} 个遗留的展示图标（上个存档留下的）", stranded.size());
            }
        }
    }

    // ===== 【烈焰人】的球袋 =====

    /** 读烈焰人的球袋（每个元素是单独一颗、count 恒为 1） */
    private static List<ItemStack> blazeBag(Blaze blaze) {
        return blaze.getData(ModAttachments.BLAZE_BALLS.get());
    }

    /**
     * 往烈焰人的球袋里放球（作者 2026-10-11 指定：能混装、最多 {@link #BLAZE_MAX_BALLS} 颗）。
     *
     * <p>传入的 stack 有几颗就放几个元素，每颗 count 1 —— 这样每颗球各自带着自己的
     * 耐久与组件，投出去时不会互相干扰。</p>
     *
     * @return false 表示装不下（这时调用方**不要**收回来源，球留在原地，一件不丢）
     */
    private static boolean blazeAddBall(Blaze blaze, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        List<ItemStack> bag = blazeBag(blaze);
        int room = BLAZE_MAX_BALLS - bag.size();
        // 一次收不完就整份拒绝 —— 别收一半、又把来源吞了
        if (stack.getCount() > room) {
            return false;
        }
        List<ItemStack> next = new java.util.ArrayList<>(bag.size() + stack.getCount());
        next.addAll(bag);
        for (int i = 0; i < stack.getCount(); i++) {
            next.add(stack.copyWithCount(1));
        }
        blaze.setData(ModAttachments.BLAZE_BALLS.get(), List.copyOf(next));
        return true;
    }

    /** 从球袋里去掉<b>最前面那一颗</b>（投出去之后调） */
    private static void blazePopBall(Blaze blaze) {
        List<ItemStack> bag = blazeBag(blaze);
        if (bag.isEmpty()) {
            return;
        }
        blaze.setData(ModAttachments.BLAZE_BALLS.get(), List.copyOf(bag.subList(1, bag.size())));
    }

    /** 把球袋里的球全掉出来（烈焰人死的时候） */
    private static void blazeDropBag(Blaze blaze) {
        List<ItemStack> bag = blazeBag(blaze);
        if (bag.isEmpty()) {
            return;
        }
        blaze.setData(ModAttachments.BLAZE_BALLS.get(), List.of());
        if (blaze.level() instanceof ServerLevel level) {
            for (ItemStack item : bag) {
                blaze.spawnAtLocation(level, item.copy(), 0.0F);
            }
        }
    }

    /**
     * 把主手原本拿着的东西收进实体附件（作者 2026-10-11 指定）。
     *
     * <p>同一个怪物身上只会收着一份：主手被球占着的时候，{@code onMobTick} 里
     * 那条 {@code !BallAmmo.isBall(hand)} 会挡住再捡第二颗，所以不存在覆盖的问题。</p>
     */
    private static void stashMainHand(Mob mob, ItemStack previous) {
        if (previous.isEmpty()) {
            return;
        }
        // ⚠️ **不能覆盖已有的暂存**（作者 2026-10-11 报的 bug）。
        //
        //    烈焰人一次能拿 12 颗球，而它捡到**不同种类**的球时会走这条
        //    「把手上那一叠收起来」的路。要是再捡到第三种，暂存被直接覆盖 ——
        //    **前面那一叠球就凭空消失了**。现象正是作者说的
        //    「手里的球互相干扰、还有损耗」。
        //
        //    改成**追加**：已有的留在前面、新的排后面；取出时按顺序还，
        //    主手只能放一件，多出来的掉在脚边（见 restoreStashedMainHand）。
        List<ItemStack> old = mob.getData(ModAttachments.STASHED_MAINHAND.get());
        List<ItemStack> next = new java.util.ArrayList<>(old.size() + 1);
        next.addAll(old);
        next.add(previous.copy());
        mob.setData(ModAttachments.STASHED_MAINHAND.get(), List.copyOf(next));
    }

    /**
     * 球扔出去了 —— 把之前收起来的主手物品拿回来（作者 2026-10-11 指定）。
     *
     * <p>由 {@link #throwBall} 在消耗掉手上那颗球之后调用，所以三个投掷入口
     * （普通怪、苦力怕自爆前那一甩、以及将来新增的）都会走到。</p>
     *
     * <p>拿不回去（主手又被别的东西占了）就掉在脚边 —— 总之不能凭空消失。</p>
     */
    public static void restoreStashedMainHand(Mob mob) {
        List<ItemStack> stash = mob.getData(ModAttachments.STASHED_MAINHAND.get());
        if (stash.isEmpty()) {
            return;
        }
        mob.setData(ModAttachments.STASHED_MAINHAND.get(), List.of());
        // 主手只有一格：第一件（最早收进去的）拿回手里，**其余全部掉在脚边**。
        // ⚠️ 必须把全部还干净 —— 暂存现在是**可追加的列表**（见 stashMainHand），
        //    只顾着取第 0 件就等于把后面几件吞掉了。
        ItemStack first = stash.get(0).copy();
        if (mob.getMainHandItem().isEmpty()) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, first);
        } else if (mob.level() instanceof ServerLevel level) {
            mob.spawnAtLocation(level, first, 0.0F);
        }
        if (stash.size() > 1 && mob.level() instanceof ServerLevel dropLevel) {
            for (int i = 1; i < stash.size(); i++) {
                mob.spawnAtLocation(dropLevel, stash.get(i).copy(), 0.0F);
            }
        }
    }

    /**
     * 怪物死了 —— 把它收着的那件东西掉出来。
     *
     * <p>不收尾的话它会跟着实体一起消失，玩家看到的就变成「猪灵的金剑凭空没了」。</p>
     */
    @SubscribeEvent
    public static void onMobDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) {
            return;
        }
        // 展示图标是**独立实体**，不会跟着生物一起消失 —— 这里主动收掉
        MobBallDisplay.clear(mob);

        // 【烈焰人】球袋里的球掉出来（它不走主手，onMobDeath 里那条「暂存」管不到它）
        if (mob instanceof Blaze blaze) {
            blazeDropBag(blaze);
        }

        // 【敌对球 · 史莱姆】作者指定：死亡时它持有的球**破碎且无掉落物**。
        // 球的「破碎」照原版物品破损那一套：放碎裂音效 + 不留任何东西。
        // ⚠️ 必须**主动清空主手** —— 否则它会按原版的装备掉落概率掉出来。
        if (mob instanceof Slime && BallAmmo.isBall(mob.getMainHandItem())) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            if (mob.level() instanceof ServerLevel level) {
                level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                        SoundEvents.ITEM_BREAK, SoundSource.NEUTRAL, 0.8F, 1.0F);
            }
        }

        List<ItemStack> stash = mob.getData(ModAttachments.STASHED_MAINHAND.get());
        if (stash.isEmpty()) {
            return;
        }
        mob.setData(ModAttachments.STASHED_MAINHAND.get(), List.of());
        if (mob.level() instanceof ServerLevel level) {
            for (ItemStack item : stash) {
                mob.spawnAtLocation(level, item.copy(), 0.0F);
            }
        }
    }

    // ===== 二、生成携带球 / 三、掠夺者弹药标记 =====

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        Mob mob = event.getEntity();
        if (mob.level().isClientSide()) {
            return;
        }
        RandomSource random = mob.getRandom();

        // 掠夺者：按概率改用球作弹药（它们本来会射箭，标记后由下方事件替换）
        if (mob instanceof Pillager) {
            if (random.nextFloat() < PILLAGER_AMMO_CHANCE) {
                mob.setData(ModAttachments.BALL_AMMO_MOB.get(), true);
            }
            return;
        }

        // ===== 【敌对球 · 烈焰人】生成携带（作者 2026-10-12 指定）=====
        // **只给烈焰人**：生成时 60% 概率携带 **1~11 个随机球**（每个各自随机）。
        // 放在下面那几条通用判据之前 —— 它有自己的概率与数量，
        // 不共用 CARRY_CHANCE，也不受「主手必须空着」那条限制（它的球本来就不走主手）。
        if (mob instanceof Blaze blaze) {
            if (random.nextFloat() < BLAZE_CARRY_CHANCE) {
                int count = BLAZE_CARRY_MIN
                        + random.nextInt(BLAZE_CARRY_MAX - BLAZE_CARRY_MIN + 1);
                for (int i = 0; i < count; i++) {
                    ItemStack one = randomBallFor(mob, random);
                    if (!one.isEmpty()) {
                        blazeAddBall(blaze, one);
                    }
                }
            }
            return;
        }

        // 其他敌对生物：主手空着才有机会拿球当远程武器。
        // 白名单这一条不能省 —— 末影人这些不在名单里的拿不了球（作者指定：
        // 末影人不能拾起 / 使用 / 携带球），生成时也别给它发。
        if (!(mob instanceof Enemy) || !canUseBalls(mob) || !mob.getMainHandItem().isEmpty()) {
            return;
        }
        if (random.nextFloat() >= CARRY_CHANCE) {
            return;
        }
        ItemStack ball = randomBallFor(mob, random);
        if (ball.isEmpty()) {
            return;
        }
        mob.setItemSlot(EquipmentSlot.MAINHAND, ball);
    }

    /**
     * 掠夺者射出的箭在加入世界的那一刻换成球。
     *
     * <p>不碰原版弩的装填逻辑：掠夺者照常瞄准、照常蓄力、照常发射，
     * 只是那颗箭实体换成我们的球 —— 初速度与朝向原样继承，所以弹道一致。</p>
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        // ===== 附属弹的兜底识别 =====
        // 主路径是 CrossbowItemMixin 夹 performShooting 的生命周期，但那依赖 Mixin 注入成功，
        // 而注入带 require = 0、匹配失败是静默的（实测好几轮都没生效）。
        // 这里再补一条完全不碰 Mixin 的路：球加入世界时数「这一 tick 这个射手已经投了几颗」，
        // 第二颗起就是多重射击复制出来的附属弹。
        if (!event.getLevel().isClientSide()
                && event.getEntity() instanceof BallProjectile joined
                && !joined.isMultishotSide()) {
            markSideBySameTick(joined, event.getLevel());
        }

        if (!(event.getEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        if (!(arrow.getOwner() instanceof Pillager pillager)) {
            return;
        }
        if (!Boolean.TRUE.equals(pillager.getExistingDataOrNull(ModAttachments.BALL_AMMO_MOB.get()))) {
            return;
        }

        // 【掠夺者弩弹药】触发概率（当前 100% 必触发；以后调这个常量即可）
        ItemStack ammo = randomBallFor(pillager, pillager.getRandom());
        if (ammo.isEmpty()) {
            return;
        }

        BallProjectile ball = new BallProjectile(level, pillager, ammo);
        ball.setPos(arrow.position());
        ball.setDeltaMovement(arrow.getDeltaMovement());
        ball.setToughness(BallItem.remainingToughness(ammo, BallBehavior.profileFor(ammo).toughness()));
        ball.setBounce(BallBehavior.profileFor(ammo).bounce());
        ball.setFromCrossbow(true);
        // 弩弹药的伤害按同一套规则：基础伤害 × 2
        ball.setDamage(BallBehavior.profileFor(ammo).damage() * BallAmmo.AMMO_DAMAGE_MULTIPLIER);

        level.addFreshEntity(ball);
        event.setCanceled(true); // 原版那支箭不要了
    }

    // ===== 蓄力与投掷 =====

    @SubscribeEvent
    public static void onMobTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) {
            return;
        }
        ItemStack hand = mob.getMainHandItem();

        // 兜底：不在白名单里的生物整段都不参与 ——
        // 正常途径它本来就拿不到球（生成不发、捡不到、接不住），但指令或其它模组
        // 硬塞一颗到它手里时，这里还能保证它不会用、不会捡。作者指定：末影人
        // 不能拾起、使用、携带球。
        if (!canUseBalls(mob)) {
            return;
        }

        // ===== 手里的球「显示在哪儿」（作者 2026-10-11）=====
        // 每刻调一次：手里有球就保证图标存在、位置跟得上；没球就收掉。
        updateBallDisplay(mob, hand);

        // 手里没球的时候，每 2 刻掷一次「要不要把脚边的球捡起来」（作者指定：1.5 格 / 每 2 刻 / 每次 4%）。
        //
        // 这里原来写的是 hand.isEmpty()，结果<b>猪灵永远不满足</b>—— 它们天生握着金剑，
        // 于是金球全被空着手的僵尸抢走了（作者反馈的正是这个）。
        // 改成「手里不是球就能捡」：原版猪灵捡金锭时本来也是直接替换手里物品的
        // （金剑换金锭、原物收起来），所以这么做才符合原版行为。
        // 被换下来的东西不会凭空消失 —— 先收进暂存（见 stashMainHand），球扔完再回手里。
        // 【敌对球 · 烈焰人】（作者 2026-10-11 指定）
        // ⚠️ **必须放在下面那个「手里是球就 early return」之前**（修后遍历抓到的 bug）：
        //    烈焰人球袋里还有空位（没满 12 颗）时仍要继续捡，捡完还要继续开火。
        //    放在后面的话，球袋没满的烈焰人会在下面那行就被 return 掉 ——
        //    **永远打不出一颗球**，只有正好 12 颗时才进得了开火分支。
        if (mob instanceof Blaze blaze) {
            // 判「要不要继续捡」看的是**球袋**，不是主手 —— 球袋没满就一直捡
            boolean hasRoom = blazeBag(blaze).size() < BLAZE_MAX_BALLS;
            if (hasRoom && mob.tickCount % PICKUP_TRY_INTERVAL == 0) {
                tryPickUpBall(mob);
            }
            // 球袋里还有球 → 走齐射（哪怕主手是空的：投掷时会把球临时放进主手）
            if (!blazeBag(blaze).isEmpty()) {
                blazeBallTick(blaze);
            }
            return;
        }

        // 【敌对球 · 幻翼】（作者 2026-10-11 指定）
        // ⚠️ 和烈焰人一样，**必须放在下面「手里没球就 return」之前** ——
        //    幻翼「俯冲中没拿球 → 去找球」这条正是手里没球时跑的，
        //    放到后面就永远走不到。
        if (mob instanceof Phantom phantom) {
            phantomBallTick(phantom, hand);
            return;
        }

        if (!BallAmmo.isBall(hand)) {
            // ⚠️ **节流到每 2 刻**（作者 2026-10-09：4 个球就开始卡、12 个球主线程卡死）。
            //
            //    `tryPickUpBall` 里要做两次 `getEntitiesOfClass`，而那个方法
            //    **会遍历世界的实体列表**（范围小也得先过一遍）。
            //    每个手里没球的怪、每 tick 都跑 = 怪数 × 2 次全表遍历 / 刻 ——
            //    几十个怪就是上百次，主线程直接被打满。
            //
            //    节流成 2 刻一次，同时把拾取概率翻倍（`PICKUP_CHANCE * 2`），
            //    **实际期望不变**，但查询次数减半。
            if (mob.tickCount % PICKUP_TRY_INTERVAL == 0) {
                tryPickUpBall(mob);
            }
            return;
        }

        // 苦力怕：能捡球、能接球，但**不主动扔**（作者 2026-10-07 指定）。
        // 它手上那颗球由 CreeperBallBehavior 在自爆前 2 刻朝目标抛出去。
        if (mob instanceof Creeper) {
            return;
        }

        // 【敌对球 · 史莱姆】（作者 2026-10-11 指定）—— **不会扔球**，只会一直拿着。
        // 球在它身体正中心展示（见 updateBallDisplay），死亡时球破碎且无掉落（见 onMobDeath）。
        if (mob instanceof Slime) {
            return;
        }

        // 【敌对球 · 嘎枝】（作者 2026-10-11 指定）—— 投掷节奏与别人完全不同，
        // 单独一条分支：被注视就不扔、没被注视蓄力 1 秒后以双倍满蓄力扔出。
        if (mob instanceof Creaking creaking) {
            creakingBallTick(creaking, hand);
            return;
        }


        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }

        int now = mob.tickCount;
        Integer end = mob.getExistingDataOrNull(ModAttachments.BALL_CHARGE_END.get());

        // 还没决定这一轮怎么蓄（刚接住球、或被塞了一颗）→ 按概率掷等级
        if (end == null || end <= 0) {
            startCharge(mob, hand);
            return;
        }
        if (now >= end) {
            Integer stored = mob.getExistingDataOrNull(ModAttachments.BALL_CHARGE_LEVEL.get());
            int level = (stored == null || stored < 0) ? 0 : stored;
            throwBall(mob, hand, target, level, mobAimDrop(target));
            mob.setData(ModAttachments.BALL_CHARGE_END.get(), 0);
            mob.setData(ModAttachments.BALL_CHARGE_LEVEL.get(), -1);
            return;
        }

        // 蓄力中：死死盯住目标 —— 扔之前一定先转向（作者指定）
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        // 像骷髅拉弓那样左右侧移，别站着让人打
        float direction = ((now / STRAFE_SWITCH_TICKS) % 2 == 0) ? 1.0F : -1.0F;
        mob.getMoveControl().strafe(0.0F, direction * STRAFE_STRENGTH);
    }

    /**
     * 掷一次这一轮要蓄几级 —— 作者指定的分布：
     * 30% 不蓄力、40% 一层、20% 两层、10% 拉满。
     *
     * <p>球自己的上限（{@code chargeLevels}）会兜住结果：上限只有 1 级的球不可能蓄两层。</p>
     */
    private static int rollChargeLevel(Mob mob, int maxLevel) {
        float roll = mob.getRandom().nextFloat();
        if (roll < MOB_CHARGE_NONE_CHANCE) {
            return 0;
        }
        if (roll < MOB_CHARGE_NONE_CHANCE + MOB_CHARGE_ONE_CHANCE) {
            return Math.min(1, maxLevel);
        }
        if (roll < MOB_CHARGE_NONE_CHANCE + MOB_CHARGE_ONE_CHANCE + MOB_CHARGE_TWO_CHANCE) {
            return Math.min(2, maxLevel);
        }
        return maxLevel;
    }

    /**
     * 开始这一轮蓄力。
     *
     * <p>掷到「不蓄力」时把结束刻设成当前刻 —— 下一 tick 立刻投出，不用额外开分支。
     * 用实体自己的 tickCount 记时刻，省得处理世界游戏刻长期运行的溢出。</p>
     */
    private static void startCharge(Mob mob, ItemStack stack) {
        int maxLevel = Math.max(1, BallBehavior.profileFor(stack).chargeLevels());
        int level = rollChargeLevel(mob, maxLevel);
        mob.setData(ModAttachments.BALL_CHARGE_LEVEL.get(), level);
        int ticks = level <= 0 ? 0 : BallCharge.ticksForLevel(level);
        mob.setData(ModAttachments.BALL_CHARGE_END.get(), mob.tickCount + ticks);
    }

    /**
     * 把手上的球扔向目标。
     *
     * <p>初速度按蓄力等级加成、伤害按速度缩放 —— 和玩家投掷走的是同一套公式，
     * 所以怪物扔出来的球手感与玩家一致。</p>
     *
     * <p>投球、消耗手上的球、投掷音效都在这里做完，调用方不用再管。
     * 持球苦力怕自爆前那一甩也是走这里（{@code CreeperBallBehavior}），
     * 所以它扔出来的球与别的怪物没有区别，只是蓄力等级被拉满。</p>
     *
     * @param chargeLevel 这一轮蓄到几级（0 = 没蓄力，直接扔）
     * @param target      {@code null} 表示没目标 —— 那就朝当前视线方向甩出去
     *                    （持球苦力怕蓄力到一半目标跑掉时会用到）
     */
    public static BallProjectile throwBall(Mob mob, ItemStack stack, @Nullable LivingEntity target, int chargeLevel) {
        return throwBall(mob, stack, target, chargeLevel, 0.0D);
    }

    /**
     * 同 {@link #throwBall(Mob, ItemStack, LivingEntity, int)}，但可以指定<b>瞄准点压低多少格</b>。
     *
     * <p>持球苦力怕自爆前那一甩传 1.0 —— 作者指定「瞄准的位置向下一格」。
     * 别的怪物照旧传 0（瞄眼睛），所以这条只影响苦力怕那一路。</p>
     *
     * @param aimDrop 瞄准点相对目标<b>眼睛</b>往下压的格数（0 = 就瞄眼睛）
     */
    public static BallProjectile throwBall(Mob mob, ItemStack stack, @Nullable LivingEntity target,
                                           int chargeLevel, double aimDrop) {
        return throwBall(mob, stack, target, chargeLevel, aimDrop, 0.0D);
    }

    /**
     * 同上一版，但可以再指定<b>投掷起点往身前挪多少格</b>。
     *
     * <p>持球苦力怕自爆前那一甩传 <b>0.7</b>（作者指定「把球投出的位置设定为苦力怕面前」）——
     * 贴着自己的位置起手的话，球和紧接着那次爆炸的爆心几乎重合，
     * 爆炸推力会退化成「纯向上」，把它顶得垂直冲天。</p>
     *
     * @param forwardOffset 起点沿视线方向前移的格数（0 = 就站在原点扔）
     */
    public static BallProjectile throwBall(Mob mob, ItemStack stack, @Nullable LivingEntity target,
                                           int chargeLevel, double aimDrop, double forwardOffset) {
        Level level = mob.level();
        BallBehavior.BallProfile profile = BallBehavior.profileFor(stack);

        // 起点：落在「自己（爆炸中心）→ 目标」这条**连线**上，朝目标方向挪 forwardOffset 格。
        //
        // ⚠️ 高度也必须落在这条线上 —— 只在水平方向前移、高度照眼睛算的话，
        //    球就脱离了连线；紧接着的爆炸推力沿「爆心 → 球」方向推，一偏就偏到底。
        //    整点都在线上，球才会被爆炸**沿着连线**朝目标送出去（作者要求的「拿爆炸当发射药」）。
        // ⚠️ 起点取**身体中部**而不是脚底（作者 2026-10-09 反馈：怪站在一格深的洞里
        //    扔球时球贴地起手，直接撞在坑壁上出不去）。
        //    用身高的 2/3 处，与瞄准点的「上三分之一」保持同一套参照。
        Vec3 origin = new Vec3(mob.getX(),
                mob.getY() + mob.getBbHeight() * 2.0D / 3.0D,
                mob.getZ());
        Vec3 forward;
        if (target != null) {
            Vec3 aim = new Vec3(target.getX(),
                    target.getY() + target.getBbHeight() * 2.0D / 3.0D,
                    target.getZ());
            Vec3 line = aim.subtract(origin);
            forward = line.lengthSqr() < 1.0E-4D ? mob.getLookAngle() : line.normalize();
        } else {
            forward = mob.getLookAngle();
        }
        Vec3 start = origin.add(forward.scale(forwardOffset));

        BallProjectile ball = new BallProjectile(level, mob, stack);
        // ⚠️ 玩家路径（BallThrowHandler）与弩路径（BallAmmo）都设了重量，怪物这条漏了。
        //    不设的话紫水晶球（重量 5）会按默认 4 结算重力，弹道与玩家扔的不一致。
        ball.setWeight(profile.weight());
        ball.setPos(start.x, start.y, start.z);
        ball.setToughness(BallItem.remainingToughness(stack, profile.toughness()));
        ball.setBounce(profile.bounce());
        ball.setFromCrossbow(false);

        double dx;
        double dy;
        double dz;
        if (target != null) {
            dx = target.getX() - ball.getX();
            dy = target.getEyeY() - aimDrop - ball.getY();
            dz = target.getZ() - ball.getZ();
        } else {
            dx = forward.x;
            dy = forward.y;
            dz = forward.z;
        }
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        // 每级 +10% 初速度，与玩家那套一致（初速度数值要先换算成物理射速）。
        //
        // ⚠️ 这里原来硬编码的是 `1.0F + chargeLevel * 0.10F` —— 数值上和
        //    BallCharge.VELOCITY_PER_LEVEL 一致，但那是「碰巧一致」：
        //    哪天常量一调，玩家投掷和怪物投掷就会分家。
        //    现在两边共用同一条公式。
        float baseVelocity = profile.physicalVelocity();
        float velocity = baseVelocity * BallCharge.velocityMultiplier(chargeLevel);
        // ⚠️ 抛物线补偿（作者 2026-10-09：优化怪物投掷精度）。
        //
        //    ⚠️ 系数原先是 `0.08 + 水平 * 0.02`，在 10 格距离上抬 2.8 格 ——
        //    把瞄准点压低量（约 0.4~0.9 格）完全盖过去了，作者连报两次
        //    「y 轴太高、从头上飞过去」。现在三个系数都提成了常量
        //    （见 AIM_LIFT_BASE / AIM_LIFT_PER_BLOCK / AIM_LIFT_MAX），只留一丝弧线。
        double lift = Math.min(AIM_LIFT_MAX, AIM_LIFT_BASE + horizontal * AIM_LIFT_PER_BLOCK);
        ball.shoot(dx, dy + horizontal * lift, dz, velocity, profile.inaccuracy());
        // 伤害同样按「最终速度 / 无蓄力速度」缩放 —— 与玩家投掷一字不差
        ball.setDamage(profile.damage() * (velocity / baseVelocity));

        level.addFreshEntity(ball);
        stack.shrink(1);
        // 手里那颗球投出去了 —— 把之前收起来的主手物品拿回来（作者 2026-10-11 指定）。
        // 判断「空了」而不是无条件还原：手上一叠球的场合还剩着，不用还。
        if (mob.getMainHandItem().isEmpty()) {
            restoreStashedMainHand(mob);
        }
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.SNOWBALL_THROW, SoundSource.NEUTRAL, 0.5F, 1.0F);
        // 把球交回去 —— 调用方有时还要补设属性（比如苦力怕那颗要标「静止后破碎」）
        return ball;
    }

    // ===== 敌对球 · 幻翼 =====

    /**
     * <b>幻翼的两条球机制</b>（作者 2026-10-11 指定）。
     *
     * <h2>一、俯冲中没拿球 → 冲向球并 100% 捡起</h2>
     * <p>正在俯冲、手上没有球，而<b>目标玩家为中心的 11×11 区域</b>内有掉在地上的实体球 ——
     * 就冲向那颗球，贴近后 <b>100% 捡起</b>（不走那套每次 4% 的随机判定）。</p>
     *
     * <h2>二、拿着球俯冲 → 投弹俯冲</h2>
     * <ul>
     *   <li><b>加速度提升</b> —— 每刻把速度再乘 {@link #PHANTOM_BOMB_ACCEL}</li>
     *   <li>距目标 {@link #PHANTOM_BOMB_THROW_DISTANCE} 格时，以 <b>0 级蓄力</b>（不蓄力）
     *       把球扔出去，然后<b>抬升</b></li>
     *   <li><b>不再因任何来源的伤害取消俯冲</b> —— 见下面那段 {@code hurtTime} 的说明</li>
     * </ul>
     *
     * <h2>⚠️「俯冲中」怎么判 —— 为什么不用原版的 {@code attackPhase}</h2>
     * <p>原版的状态存在 {@code Phantom.attackPhase} 里，但那是个 <b>private 字段</b>，
     * 类型 {@code Phantom.AttackPhase} 还是个 <b>private 枚举</b>。
     * 我把 {@code Phantom} 的公开成员全过了一遍（{@code isFlapping} / {@code getPhantomSize} /
     * {@code travel} / {@code getDefaultDimensions} …）—— <b>没有任何读取途径</b>。</p>
     * <p>要读它得同时给「字段」和「枚举类」<b>各开一条 AccessTransformer</b>。
     * 这里改用<b>行为判据</b>：俯冲时原版会把
     * {@code moveTargetPoint} 直接设成玩家位置（{@code PhantomSweepAttackGoal.tick} L495），
     * 所以特征是「<b>速度方向冲着玩家</b>」；绕圈时速度是切向的，对不上
     * {@link #PHANTOM_DIVE_ALIGN}。</p>
     *
     * <h2>⚠️ 伤害为什么能取消俯冲 —— 以及怎么堵住</h2>
     * <p>{@code PhantomSweepAttackGoal.tick} 末段（L502-504）：</p>
     * <pre>
     * } else if (Phantom.this.horizontalCollision || Phantom.this.hurtTime &gt; 0) {
     *     Phantom.this.attackPhase = Phantom.AttackPhase.CIRCLE;   // ← 俯冲被掐掉
     * }
     * </pre>
     * <p>{@code hurtTime} 是 {@code Entity} 的<b>公开字段</b>，所以拿球俯冲期间
     * <b>每刻把它清零</b>就够了 —— <b>不用改原版、不用 Mixin</b>。</p>
     * <p>代价：这期间幻翼不会有「被打到」的红色闪烁。作者要的是「不被打断」，
     * 所以这个代价是有意接受的。</p>
     */
    private static void phantomBallTick(Phantom phantom, ItemStack hand) {
        if (phantom.level().isClientSide()) {
            return;
        }
        LivingEntity target = phantom.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }
        boolean diving = phantomIsDiving(phantom, target);
        // ⚠️ 2026-10-12：原来这里只判 `hand` —— 那是 onMobTick 里捕获的**可能过期**的 ItemStack
//    （历史上就出现过「日志说手里=空、其实拿着球」）。一旦它过期，下面的阶段判定
//    会把爬升/投弹**每刻重置回普通**，幻翼就永远走不到投弹那一步。
//    改成「手上真的拿着球就算」：与传入的快照取或。
        boolean hasBall = BallAmmo.isBall(hand) || BallAmmo.isBall(phantom.getMainHandItem());
        int phase = phantomPhase(phantom);

        // 球没了（掉了 / 被拿走）→ 阶段作废，回普通。
        // ⚠️ **撤退阶段（3）不在其列** —— 它就是「球已经扔出去了」的状态，本来就没球。
        if ((phase == PHANTOM_PHASE_CLIMB || phase == PHANTOM_PHASE_BOMB) && !hasBall) {
            setPhantomPhase(phantom, PHANTOM_PHASE_NORMAL);
            phase = PHANTOM_PHASE_NORMAL;
        }

        // ===== 阶段 2：投弹俯冲（作者 2026-10-11 指定）=====
        //  · 加速度大幅提升
        //  · **全程发光**
        //  · 距玩家 10 格 → 按 0 级蓄力扔出
        //  · 扔完回普通 → 它会自己飞回天上（不攻击，见 onPhantomAttackPlayer）
        if (phase == PHANTOM_PHASE_BOMB) {
            if (phantom.tickCount % PHANTOM_GLOW_REFRESH == 0) {
                phantom.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.GLOWING, PHANTOM_GLOW_TICKS, 0, false, false));
            }
            // ⚠️⚠️ **必须自己把方向掰向玩家**（作者 2026-10-11 报「投弹俯冲之后无法扔出球」）。
            //
            //    爬升阶段结束时速度是**朝上**的；这里只乘一个系数的话，
            //    只会让「朝上」越来越快 —— 幻翼永远飞不到玩家 10 格内，也就永远扔不出球。
            //    （`EntityTickEvent.Post` 跑在原版 aiStep 之后，写进去就是当刻最终速度。）
            Vec3 toPlayer = target.position()
                    .add(0.0D, target.getBbHeight() * 0.5D, 0.0D)
                    .subtract(phantom.position());
            if (toPlayer.lengthSqr() > 1.0E-4D) {
                double speed = Math.min(PHANTOM_BOMB_MAX_SPEED,
                        Math.max(phantom.getDeltaMovement().length() * PHANTOM_BOMB_ACCEL,
                                PHANTOM_BOMB_MIN_SPEED));
                phantom.setDeltaMovement(toPlayer.normalize().scale(speed));
            }
            if (phantom.distanceTo(target) <= PHANTOM_BOMB_THROW_DISTANCE) {
                // 作者 2026-10-12 指定：**不要蓄力等级**，按 0 级蓄力扔出。
                // 伤害来源只保留「叠加的幻翼速度」（见下面 factor 那段），
                // 所以原来那句按 chargeLevels() 取等级的做法去掉了。
                BallProjectile thrown = throwBall(phantom, hand, target, 0, mobAimDrop(target));
                // ===== 作者 2026-10-11 指定：球要「叠加上幻翼速度」并且「瞄得更准」=====
                //
                // ⚠️ 不改 throwBall 的签名 —— 它**已经把球返回回来了**，
                //    直接在这颗球上改速度即可（投掷时算好的那套蓄力仍在，这里是**叠加**）。
                //
                //   · 速度：乘以 (1 + 幻翼速度 × PHANTOM_SPEED_BONUS)
                //   · 准度：不瞄玩家**现在**的位置，改瞄「再过 PHANTOM_LEAD_TICKS 刻的位置」。
                //     俯冲时球是高速接近目标的，按当前位置瞄会**系统性地落在人后面**。
                if (thrown != null) {
                    Vec3 aimAt = target.position()
                            .add(0.0D, target.getBbHeight() * 0.5D, 0.0D)
                            .add(target.getDeltaMovement().scale(PHANTOM_LEAD_TICKS));
                    Vec3 dir = aimAt.subtract(thrown.position());
                    if (dir.lengthSqr() > 1.0E-4D) {
                        double factor = 1.0D
                                + phantom.getDeltaMovement().length() * PHANTOM_SPEED_BONUS;
                        double boosted = thrown.getDeltaMovement().length() * factor;
                        thrown.setDeltaMovement(dir.normalize().scale(boosted));
                        // ⚠️ **伤害也要跟着涨**：throwBall 里那句
                        //    `setDamage(profile.damage() * (velocity / baseVelocity))`
                        //    是按**加成之前**的速度算的 —— 不补这一下，速度白加、伤害还是原样。
                        thrown.setDamage(thrown.getDamage() * (float) factor);
                    }
                }
                // ⚠️ 扔完进**撤退阶段**、不是直接回普通 ——
                //    回普通的话「不攻击」的取消当场失效，幻翼撞上来那一下（恒定 2 点）
                //    会被误当成球打的伤害（作者 2026-10-11 报的「怎么扔都是 2 滴血」）。
                setPhantomPhase(phantom, PHANTOM_PHASE_RETREAT);
            }
            return;
        }

        // ===== 阶段 1 / 3：飞回高空（1 = 捡到球了；3 = 球已经扔出去了）=====
        // 两者**都不攻击**（见 onPhantomAttackPlayer），区别只在爬够高度之后去哪：
        //   1 → 转投弹俯冲；3 → 回普通（这一趟结束了）
        if (phase == PHANTOM_PHASE_CLIMB || phase == PHANTOM_PHASE_RETREAT) {
            boolean retreating = phase == PHANTOM_PHASE_RETREAT;
            // 强上抬：竖直速度拉到下限，水平压一半（别一边爬一边撞玩家）
            Vec3 climb = phantom.getDeltaMovement();
            phantom.setDeltaMovement(climb.x * 0.5D,
                    Math.max(climb.y, PHANTOM_CLIMB_SPEED), climb.z * 0.5D);
            // 爬到 40 格 **或** 3 秒到了 → 转入下一阶段（先到哪个算哪个）
            //
            // ⚠️ 3 秒兜底不能省：爬升期间原版 PhantomSweepAttackGoal 每刻还在把速度
            //    朝玩家（在下方）拉 20%，万一压不过它，只靠高度判据会卡在半空出不来。
            Integer startedAt = PHANTOM_CLIMB_START.get(phantom.getUUID());
            boolean high = phantom.getY() > target.getY() + PHANTOM_CLIMB_HEIGHT;
            boolean timeout = startedAt != null
                    && phantom.tickCount - startedAt >= PHANTOM_CLIMB_TICKS;
            if (high || timeout) {
                setPhantomPhase(phantom, retreating ? PHANTOM_PHASE_NORMAL : PHANTOM_PHASE_BOMB);
            }
            return;
        }

        // ===== 一、俯冲中没拿球 → 去找球 =====
        if (!BallAmmo.isBall(hand)) {
            if (!diving) {
                return;
            }
            // ⚠️ 节流到每 2 刻 —— `getEntitiesOfClass` 会**遍历世界的实体列表**，
            //    而俯冲中的幻翼每刻都会走到这里。与「拾取」那条同一套理由
            //    （作者 2026-10-09 反馈过：4 个球就开始卡）。俯冲只持续几秒，2 刻的粒度够用。
            if (phantom.tickCount % PICKUP_TRY_INTERVAL != 0) {
                return;
            }
            Entity found = findBallNear(phantom, target);

            if (found == null) {
                return;
            }
            // ⚠️⚠️ **每刻直接把速度指向球**（作者 2026-10-11 报「没有正确的去捡球」）。
            //
            //    原来这里只有 `getMoveControl().setWantedPosition(...)` —— 但原版
            //    `PhantomSweepAttackGoal` 每刻都会重设移动目标，把 MoveControl 的方案盖掉，
            //    所以幻翼依旧一头扎向玩家、永远不会靠近那颗球。
            //
            //    `EntityTickEvent.Post` 跑在原版 `aiStep` **之后** ——
            //    在这里写 deltaMovement 就是当刻的最终速度，**不会被覆盖**。
            Vec3 toBall = found.position().add(0.0D, 0.5D, 0.0D).subtract(phantom.position());
            if (toBall.lengthSqr() > 1.0E-4D) {
                phantom.setDeltaMovement(toBall.normalize().scale(PHANTOM_BALL_CHASE_SPEED));
            }
            // 贴上就 100% 捡起 —— 不走那套随机判定
            if (phantom.distanceToSqr(found) <= PHANTOM_PICKUP_RANGE_SQR) {
                // 两种形态各有各的拾取入口
                if (found instanceof BallProjectile ball) {
                    pickUpBall(phantom, ball);
                } else if (found instanceof ItemEntity item) {
                    pickUpDroppedBall(phantom, item);
                }
                // ⚠️ 真捡到了才转爬升（作者 2026-10-11 指定）：
                //    这一趟**不攻击、不扔球**，先飞回高空，下一次才是「投弹俯冲」。
                //    以前这里没有状态机 —— 捡完这颗球的下一 tick 就直接走
                //    「够近就扔」那条，于是表现为「捡了球就扔」。
                if (BallAmmo.isBall(phantom.getMainHandItem())) {
                    setPhantomPhase(phantom, PHANTOM_PHASE_CLIMB);
                }
            }
            return;
        }

        // 手上有球、但还没进阶段（例如指令硬塞的）→ 直接按投弹俯冲处理
        setPhantomPhase(phantom, PHANTOM_PHASE_BOMB);
    }

    /**
     * 这只幻翼在不在俯冲 —— 见 {@link #phantomBallTick} 里那段说明。
     *
     * <p>判据只有一条：<b>实际速度方向与「指向目标」的方向基本一致</b>。</p>
     */
    private static boolean phantomIsDiving(Phantom phantom, LivingEntity target) {
        Vec3 vel = phantom.getDeltaMovement();
        if (vel.lengthSqr() < 1.0E-4D) {
            return false;
        }
        Vec3 toTarget = new Vec3(
                target.getX() - phantom.getX(),
                target.getY(0.5) - phantom.getY(0.5),
                target.getZ() - phantom.getZ());
        if (toTarget.lengthSqr() < 1.0E-4D) {
            return true;   // 已经重叠了，算它冲到了
        }
        return vel.normalize().dot(toTarget.normalize()) > PHANTOM_DIVE_ALIGN;
    }

    /**
     * 在<b>目标玩家为中心的 11×11 区域</b>里找一颗掉在地上的<b>实体球</b>。
     *
     * <p>球实体（{@code BallProjectile}）与掉落物形态（{@code ItemEntity}）<b>都算</b>，
     * 口径与 {@link #tryPickUpBall} 一致（见下面那段 2026-10-11 的说明）。</p>
     */
    private static @Nullable Entity findBallNear(Phantom phantom, LivingEntity target) {
        AABB area = new AABB(
                target.getX() - PHANTOM_BALL_SEARCH_RADIUS, target.getY() - PHANTOM_BALL_SEARCH_RADIUS,
                target.getZ() - PHANTOM_BALL_SEARCH_RADIUS,
                target.getX() + PHANTOM_BALL_SEARCH_RADIUS, target.getY() + PHANTOM_BALL_SEARCH_RADIUS,
                target.getZ() + PHANTOM_BALL_SEARCH_RADIUS);
        // ⚠️⚠️ **两种形态都要认**（作者 2026-10-11 报「幻翼功能未实现」的根因）。
        //
        //    这里原来只查 `BallProjectile` —— 而模组里其它所有捡球路径
        //    （见 tryPickUpBall 的注释）都是「**球实体 + 掉落物**」两种一起认。
        //    唯独幻翼这条漏了掉落物，于是地上那种「被打飞 / 碎裂滚出来」的球
        //    它永远看不见 —— 表现就是作者说的「还是一直对玩家俯冲」。
        List<Entity> found = phantom.level().getEntitiesOfClass(
                Entity.class, area, e -> isPickableBall(e));
        if (found.isEmpty()) {
            return null;
        }
        // 挑离幻翼最近的那个 —— 少飞冤枉路
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity ball : found) {
            double d = phantom.distanceToSqr(ball);
            if (d < bestDist) {
                bestDist = d;
                best = ball;
            }
        }
        return best;
    }

    /**
     * 这个实体是不是「幻翼可以去捡的球」——
     * <b>球实体与掉落物形态都算</b>，与 {@link #tryPickUpBall} 的口径保持一致。
     */
    private static boolean isPickableBall(Entity e) {
        if (e instanceof BallProjectile ball) {
            return ball.isAlive();
        }
        return e instanceof ItemEntity item && item.isAlive() && BallAmmo.isBall(item.getItem());
    }

    // ===== 敌对球 · 烈焰人 =====

    /**
     * <b>拦掉烈焰人自己的烈焰弹</b>（作者 2026-10-11 指定）。
     *
     * <p>原版那套是 <b>{@code Blaze.BlazeAttackGoal}</b> —— 一个 <b>私有内部类</b>，
     * 一轮固定 <b>3 发</b>、间隔 6 刻、之后冷却 100 刻，节奏全在私有字段
     * {@code attackStep} / {@code attackTime} 里（见 {@code Blaze.java} L190-254）。</p>
     *
     * <p>而作者要的三档里有两条它做不到：<b>球多于 3 时要「发完为止」</b>、
     * <b>攒满 12 颗要「立刻取消攻击冷却」</b> —— 都得动那两个私有字段。</p>
     *
     * <p>所以这里换条路：<b>拦掉它自己发的烈焰弹，改由 {@link #blazeBallTick} 排一轮</b>。
     * 用的是 NeoForge 官方事件（同掠夺者换弩弹药那条路），
     * <b>不开 Mixin、不动原版类、不加 AccessTransformer</b>。</p>
     */
    @SubscribeEvent
    public static void onBlazeFireball(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof SmallFireball fireball)) {
            return;
        }
        if (!(fireball.getOwner() instanceof Blaze blaze)) {
            return;
        }
        // 拿球期间：原版的烈焰弹一律不发，齐射改由 blazeBallTick 驱动
        // 判据是**球袋里有没有球** —— 主手只在投掷那一瞬间才会被临时放上一颗
        if (!blazeBag(blaze).isEmpty()) {
            event.setCanceled(true);
        }
    }

    /**
     * <b>烈焰人的齐射</b>（作者 2026-10-11 指定）。
     *
     * <p>球数决定这一轮怎么打：</p>
     * <table border="1">
     *   <tr><th>球数</th><th>怎么打</th></tr>
     *   <tr><td><b>&lt; 3</b></td><td>先把球扔完，再用<b>烈焰弹补足到 3 发</b></td></tr>
     *   <tr><td><b>&gt; 3</b></td><td>不再补烈焰弹，<b>一直扔到球用完</b></td></tr>
     *   <tr><td><b>= 12</b></td><td><b>不进蓄力</b>，立刻开扔，12 颗依次发完</td></tr>
     * </table>
     *
     * <p>扔球<b>不蓄力</b>，蓄力等级固定为最大蓄力的一半。</p>
     *
     * <h2>状态存在哪</h2>
     * <p>沿用那两条现成的附件：{@code BALL_CHARGE_LEVEL} 记「本轮还剩几发」
     * （-1 = 不在齐射里）、{@code BALL_CHARGE_END} 记「下一发在哪一刻」。
     * 烈焰人这条路与其他怪物完全不会同时走到，所以复用是安全的。</p>
     */
    private static void blazeBallTick(Blaze blaze) {
        if (blaze.level().isClientSide()) {
            return;
        }
        LivingEntity target = blaze.getTarget();
        boolean canFire = target != null && target.isAlive()
                && blaze.getSensing().hasLineOfSight(target);
        if (!canFire) {
            return;   // 没视线就等（「在可以发射的时机」）
        }

        int now = blaze.tickCount;
        Integer left = blaze.getExistingDataOrNull(ModAttachments.BALL_CHARGE_LEVEL.get());
        Integer end = blaze.getExistingDataOrNull(ModAttachments.BALL_CHARGE_END.get());

        // ⚠️ 「到点了吗」必须放在**开始新一轮之前**（修后遍历抓到的 bug）：
        //    一轮打完时把 left 设成 -1、end 设成 now + 冷却；如果先判「不在齐射里」，
        //    就会**立刻开下一轮** —— 那 100 刻冷却等于不存在。
        //    放在最前面同时管住两件事：一轮之内的 6 刻间隔，和一轮之后的冷却。
        // ⚠️⚠️ **攒满 12 颗时无视这道冷却**（作者 2026-10-11 报的 bug）。
        //
        //    原来这里是无条件 `return` —— 而「攒满 12 颗就结束当前攻击冷却、
        //    立刻开火」那条恰恰要靠**穿过这道门**才能走到（下面的
        //    `delay = balls >= BLAZE_MAX_BALLS ? 0 : BLAZE_CHARGE_TICKS`）。
        //    门一关，满 12 也照样老老实实等完 100 刻冷却 ——
        //    现象就是作者说的「在攻击间隔里攒满 12 球没有立即发起攻击」。
        //    （那时它也没进入着火形态，因为原版那套攻击还没被驱动。）
        int bagCount = blazeBag(blaze).size();
        if (end != null && now < end && bagCount < BLAZE_MAX_BALLS) {
            return;
        }

        // 不在齐射里（含刚冷却完）→ 定下这一轮
        if (left == null || left < 0) {
            int balls = blazeBag(blaze).size();
            // < 3 → 用烈焰弹补足到 3 发；>= 3 → 有多少打多少（发完为止）
            int shots = balls >= BLAZE_BURST_MIN ? balls : BLAZE_BURST_MIN;
            // 攒满 12 颗时**不进蓄力**，立刻开火（作者指定：满 12 结束当前攻击冷却，然后轮流发射）
            int delay = balls >= BLAZE_MAX_BALLS ? 0 : BLAZE_CHARGE_TICKS;
            blaze.setData(ModAttachments.BALL_CHARGE_LEVEL.get(), shots);
            blaze.setData(ModAttachments.BALL_CHARGE_END.get(), now + delay);
            left = shots;
            if (delay > 0) {
                return;
            }
        }

        int remaining = Math.max(0, blaze.getExistingDataOrNull(ModAttachments.BALL_CHARGE_LEVEL.get()));

        // 开这一发：球袋里有球就先扔球，没有才发一颗烈焰弹补足
        List<ItemStack> bag = blazeBag(blaze);
        if (!bag.isEmpty()) {
            // 取球袋最前面那颗，**临时**放进主手 —— throwBall 要的就是主手那一份
            ItemStack ammo = bag.get(0).copy();
            blaze.setItemSlot(EquipmentSlot.MAINHAND, ammo);
            int maxLevel = Math.max(1, BallBehavior.profileFor(ammo).chargeLevels());
            // ⚠️ 瞄准点必须**压低到胸口**（作者 2026-10-11：叫「老是从头上飞过去」）。
            //    原来走的是 4 参数重载、aimDrop = 0 —— 瞄的是眼睛，
            //    再叠加 throwBall 里那道抛物线抬升，两处都往上，必然过顶。
            throwBall(blaze, ammo, target, maxLevel / BLAZE_CHARGE_LEVEL_DIVISOR, mobAimDrop(target));
            // 投出去了 —— 从球袋里去掉这一颗（throwBall 只会 shrink 主手那份，球袋得自己收）
            blazePopBall(blaze);
        } else if (target != null) {
            fireSmallFireballAt(blaze, target);
        }

        remaining--;
        blaze.setData(ModAttachments.BALL_CHARGE_LEVEL.get(), remaining);
        // 一轮打完 → 进冷却；否则按原版的 6 刻间隔继续
        blaze.setData(ModAttachments.BALL_CHARGE_END.get(),
                now + (remaining <= 0 ? BLAZE_COOLDOWN_TICKS : BLAZE_SHOT_INTERVAL));
        if (remaining <= 0) {
            blaze.setData(ModAttachments.BALL_CHARGE_LEVEL.get(), -1);
        }
    }

    /**
     * 替烈焰人补一发烈焰弹（球不够 3 发时用）。
     *
     * <p>瞄法与散布<b>照抄原版</b> {@code Blaze$BlazeAttackGoal.tick()} L231-242 ——
     * 那里用的是 {@code getRandom().triangle(偏移, 2.297 * 四次方根(距离²) * 0.5)}。
     * 照抄是为了让「补的那几发」和原版手感一致。</p>
     */
    private static void fireSmallFireballAt(Blaze blaze, LivingEntity target) {
        if (!(blaze.level() instanceof ServerLevel level)) {
            return;
        }
        double xd = target.getX() - blaze.getX();
        double yd = target.getY(0.5) - blaze.getY(0.5);
        double zd = target.getZ() - blaze.getZ();
        double distance = blaze.distanceToSqr(target);
        double sqd = Math.sqrt(Math.sqrt(distance)) * 0.5;
        Vec3 direction = new Vec3(
                blaze.getRandom().triangle(xd, 2.297 * sqd),
                yd,
                blaze.getRandom().triangle(zd, 2.297 * sqd));
        SmallFireball fireball = new SmallFireball(level, blaze, direction.normalize());
        fireball.setPos(fireball.getX(), blaze.getY(0.5) + 0.5, fireball.getZ());
        level.addFreshEntity(fireball);
    }

    // ===== 敌对球 · 嘎枝 =====

    /**
     * <b>嘎枝的投掷节奏</b>（作者 2026-10-11 指定）。
     *
     * <p>规则只有两条：</p>
     * <ul>
     *   <li><b>被玩家注视时不做投掷动作</b> —— 冻结着，蓄力进度也一并清零</li>
     *   <li><b>没被注视</b>时蓄力 <b>1 秒</b>，然后以<b>最大蓄力的两倍</b>蓄力值扔向目标</li>
     * </ul>
     *
     * <h2>「被注视」怎么判</h2>
     * <p><b>直接用原版的状态</b>：{@code Creaking.canMove()} 返回的就是「没被注视」。
     * 它由 {@code Creaking.checkCanMove()} 每刻算出来 —— 里面正是
     * {@code isLookingAtMe(player, 0.5, …)} 那一套判定，并且已经考虑了
     * 「玩家是否戴着伪装」（{@code PLAYER_NOT_WEARING_DISGUISE_ITEM_FOR_TARGET}）。</p>
     *
     * <p>所以这里自己再判一次视线是<b>多余且有风险</b>的：既可能和原版口径不一致，
     * 又要为 {@code isLookingAtMe} 再开一条 AccessTransformer。直接用 {@code canMove()} 最稳。</p>
     */
    private static void creakingBallTick(Creaking creaking, ItemStack hand) {
        // 被注视 → 冻结。把蓄力进度清掉，解冻后重新从 0 蓄满 1 秒
        if (!creaking.canMove()) {
            creaking.setData(ModAttachments.BALL_CHARGE_END.get(), 0);
            creaking.setData(ModAttachments.BALL_CHARGE_LEVEL.get(), -1);
            return;
        }

        LivingEntity target = creaking.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }

        int now = creaking.tickCount;
        Integer end = creaking.getExistingDataOrNull(ModAttachments.BALL_CHARGE_END.get());

        // 还没开始蓄 → 定下这一轮：固定 1 秒、等级 = 最大蓄力 × 2
        if (end == null || end <= 0) {
            int maxLevel = Math.max(1, BallBehavior.profileFor(hand).chargeLevels());
            creaking.setData(ModAttachments.BALL_CHARGE_LEVEL.get(), maxLevel * CREAKING_CHARGE_LEVEL_TIMES);
            creaking.setData(ModAttachments.BALL_CHARGE_END.get(), now + CREAKING_CHARGE_TICKS);
            return;
        }

        // 蓄满了 → 扔出去
        if (now >= end) {
            Integer stored = creaking.getExistingDataOrNull(ModAttachments.BALL_CHARGE_LEVEL.get());
            int level = (stored == null || stored < 0) ? 0 : stored;
            throwBall(creaking, hand, target, level, mobAimDrop(target));
            creaking.setData(ModAttachments.BALL_CHARGE_END.get(), 0);
            creaking.setData(ModAttachments.BALL_CHARGE_LEVEL.get(), -1);
            return;
        }

        // 蓄力中：盯住目标（扔之前一定先转向）
        creaking.getLookControl().setLookAt(target, 30.0F, 30.0F);
    }

    // ===== 手里的球显示在哪儿 =====

    /**
     * <b>手里的球「显示在哪儿」</b>（作者 2026-10-11 指定）。
     *
     * <p>原版只有一部分生物会把手持物品画出来 —— 那些走 {@code HumanoidMobRenderer}
     * （僵尸 / 骷髅 / 猪灵 / 掠夺者）或自己挂了 {@code ItemInHandLayer}（恼鬼）的。
     * <b>蜘蛛、洞穴蜘蛛、幻翼、嘎枝的渲染器都没有这一层</b>，球拿在手里根本看不见。</p>
     *
     * <p>所以这几类额外挂一个缩小版的 {@code ItemDisplay} 在头顶 ——
     * 作者指定「大小与掉落物图标差不多大」。</p>
     *
     * <p>有专属位置的由各自负责，这里不管：烈焰人（十二个肢体）、史莱姆（头顶）、
     * 苦力怕（它自己那套头顶图标）。</p>
     */
    private static void updateBallDisplay(Mob mob, ItemStack hand) {
        // ⚠️ **烈焰人必须最先判、而且不能用主手判**（作者 2026-10-11）：
        //    它的球存在**球袋**里，主手大部分时间是空的 ——
        //    放下面的话会被「手里没球就 clear」直接清掉，十二肢上永远看不到东西。
        if (mob instanceof Blaze blaze) {
            blazeBallDisplay(blaze);
            return;
        }
        if (!BallAmmo.isBall(hand)) {
            MobBallDisplay.clear(mob);
            return;
        }
        // 【敌对球 · 史莱姆】原来在「身体内部正中心」，作者 2026-10-11 改成**也挂头顶** ——
        // 所以这里不再单独开分支，它跟蜘蛛/幻翼/嘎枝一样走下面的通用头顶展示。
        if (specialDisplay(mob)) {
            return;
        }
        MobBallDisplay.ensure(mob, hand, MobBallDisplay.HEAD_LIFT, MobBallDisplay.HEAD_SCALE);
    }

    /**
     * <b>烈焰人的十二肢展示</b>（作者 2026-10-11 指定）。
     *
     * <p>手上每一颗球都挂在对应那根棒子的<b>棒尖</b>上。</p>
     *
     * <h2>十二根棒子，一根一颗</h2>
     * <p>{@code BlazeModel} 里一共建了 <b>12</b> 根（{@code part0-11}，三圈：
     * 半径 9 / 7 / 5，模型 y ≈ -1.5 / +2.5 / +11）。作者 2026-10-11 把
     * 持有上限改成 <b>12</b> 之后，正好<b>一根一颗全用满</b>。</p>
     *
     * <p>这些棒子在 {@code setupAnim} 里<b>每帧绕身体旋转</b>（角度含 {@code ageInTicks}，
     * y 还有 cos 抖动）—— 球既然「放在棒子上」，就跟着一起动，否则会和棒子脱节。</p>
     *
     * @see #blazeRodTip
     */
    private static void blazeBallDisplay(Blaze blaze) {
        // 直接照**球袋**画：有几个元素就占几根棒子，元素 i ↔ 第 i 根。
        // 「12 个手各一个 1 格槽位」就是这个列表本身 —— 每颗球各自带自己的类型与耐久。
        List<ItemStack> balls = blazeBag(blaze);
        if (balls.isEmpty()) {
            MobBallDisplay.clearMulti(blaze);
            return;
        }
        List<Vec3> positions = new java.util.ArrayList<>(balls.size());
        for (int i = 0; i < balls.size(); i++) {
            positions.add(blazeRodTip(blaze, i));
        }
        MobBallDisplay.ensureMulti(blaze, balls, positions, BLAZE_ROD_BALL_SCALE);
    }

    /**
     * 第 {@code index} 根棒子的<b>棒尖</b>的世界坐标。
     *
     * <h2>模型坐标怎么来</h2>
     * <p><b>逐行复刻 {@code BlazeModel.setupAnim}（L70-101）</b> —— 用的是同一套角度与
     * 半径，只是把 {@code ageInTicks} 换成服务端的 {@code tickCount}（差半刻，纯观感）。
     * ⚠️ 别自己重新推一套公式：那十二根是<b>动画态</b>的位置，和 {@code createBodyLayer}
     * 里建的<b>静止态</b>不是一回事。</p>
     *
     * <h2>模型坐标 → 世界坐标</h2>
     * <p>棒是一个 {@code addBox(0,0,0, 2,8,2)}，从轴心<b>往下</b>伸 8 单位，
     * 所以棒尖在「轴心 + 8」。换算照 {@code LivingEntityRenderer} 的变换链（L87-91）：</p>
     * <pre>
     * poseStack.scale(-1.0F, -1.0F, 1.0F);          // X、Y 翻转
     * poseStack.translate(0.0F, -1.501F, 0.0F);
     * ⇒ world = ( -mx/16 , 1.501 - my/16 , mz/16 ) + 生物坐标
     * </pre>
     * <p><b>验证</b>：{@code HumanoidModel} 的脚在模型 y=24 → {@code 1.501 - 1.5 = 0.001}，
     * 正好落在脚底 ✓。</p>
     */
    private static Vec3 blazeRodTip(Blaze blaze, int index) {
        float t = blaze.tickCount;
        float mx;
        float my;
        float mz;

        if (index < 4) {
            // 第一圈：半径 9，从 0 起每根 +90°
            float angle = t * (float) Math.PI * -0.1F + index * (float) (Math.PI / 2);
            my = -2.0F + Mth.cos((index * 2 + t) * 0.25F);
            mx = Mth.cos(angle) * 9.0F;
            mz = Mth.sin(angle) * 9.0F;
        } else if (index < 8) {
            // 第二圈：半径 7，从 45° 起每根 +90°
            float angle = (float) (Math.PI / 4) + t * (float) Math.PI * 0.03F
                    + (index - 4) * (float) (Math.PI / 2);
            my = 2.0F + Mth.cos((index * 2 + t) * 0.25F);
            mx = Mth.cos(angle) * 7.0F;
            mz = Mth.sin(angle) * 7.0F;
        } else {
            // 第三圈：半径 5 —— index 8~11，持有 12 颗时会被用到
            float angle = 0.47123894F + t * (float) Math.PI * -0.05F
                    + (index - 8) * (float) (Math.PI / 2);
            my = 11.0F + Mth.cos((index * 1.5F + t) * 0.5F);
            mx = Mth.cos(angle) * 5.0F;
            mz = Mth.sin(angle) * 5.0F;
        }

        double tipY = my + BLAZE_ROD_LENGTH;
        return new Vec3(
                blaze.getX() - mx / 16.0D,
                blaze.getY() + MODEL_Y_LIFT - tipY / 16.0D,
                blaze.getZ() + mz / 16.0D);
    }

    /**
     * 这只生物是否<b>不需要</b>通用的头顶展示 —— 要么原版本来就画得出来，
     * 要么它有自己专属的展示位置。
     */
    private static boolean specialDisplay(Mob mob) {
        // 原版渲染器自己会画手持物品的
        if (mob instanceof AbstractSkeleton       // 骷髅 / 流浪者 / 沼骸 / 焦骸 / 凋灵骷髅
                || mob instanceof Zombie          // 僵尸 / 尸壳 / 溺尸 / 僵尸村民
                || mob instanceof Piglin
                || mob instanceof Pillager
                || mob instanceof Vex) {
            return true;
        }
        // 有专属展示位置的
        // · 苦力怕 —— 它自己那套头顶图标（CreeperBallBehavior）
        // · 烈焰人 —— 十二根棒子各挂一个（已实现在 updateBallDisplay 的烈焰人分支里；
        //   这里保留它只是为了「万一别的地方问起来」口径一致 —— 那条分支先 return，走不到这）
        // · 史莱姆 —— 已改成头顶展示（作者 2026-10-11），所以不在这里排除
        return mob instanceof Creeper || mob instanceof Blaze;
    }

    // ===== 附加弹兜底识别 =====

    /** 某个射手最近一次投弹是哪一刻、连着投了几颗 */
    private record ShotStamp(long gameTime, int count) {
    }

    private static final int RECENT_SHOTS_CAPACITY = 256;

    /**
     * 兜底识别用的记录表；用强引用，过期条目手动清（弱引用会被 GC 无声清掉，踩过）。
     *
     * <p>射手 UUID → 上一次发射的时间戳（用于「同一次齐射」兜底识别）。
     * ⚠️ 这是个**无上界**的 Map，每个开过弩的实体都会留下一条记录、永不清理。
     * 用 LRU 限住，并改成并发安全的实现（怪物 tick 走服务端线程，但主手投掷
     * 有可能在别的线程被触发，原版 HashMap 不安全）。</p>
     */
    private static final Map<UUID, ShotStamp> RECENT_SHOTS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, ShotStamp> eldest) {
                    return size() > RECENT_SHOTS_CAPACITY;
                }
            });

    /**
     * 判定「同一次发射」的时间窗口（刻）。
     *
     * <p><b>取 0 刻＝只有同一个游戏刻才算同一次发射</b>，这是作者定的：
     * 中间试过放宽到 5 刻（怕「原版分刻创建那几颗」），结果把玩家的手动连射也误伤了 ——
     * 手动连射大约每 3 刻一发，全部落进窗口里，一整个连射被当成同一次发射，
     * 除了第一颗之外全被判定成附属弹（日志里计数一路涨到 45 就是这么来的）。</p>
     *
     * <p>多重射击那几颗本来就由 {@code CrossbowItemMixin} 夹 {@code performShooting}
     * 的 ThreadLocal 序号处理（那是精确的）；这条路只是兜底，
     * 而兜底宁可漏判也不该误判。</p>
     */
    private static final int SAME_VOLLEY_TICKS = 0;

    /**
     * 按「同一射手连着投出的第几颗」判断附属弹。
     *
     * <p>第一颗是主弹药，之后的是多重射击的复制品。</p>
     *
     * <h2>窗口为什么是「同一刻」</h2>
     * 中间曾放宽到 5 刻的短窗口，结果把玩家的手动连射也误并成一次发射；
     * 现在回到「只有同一游戏刻才算同一次发射」（{@link #SAME_VOLLEY_TICKS} = 0）。
     * 这条路只是兜底，宁可漏判、也不该误判。</p>
     */
    private static void markSideBySameTick(BallProjectile ball, Level level) {
        Entity owner = ball.getOwner();
        if (owner == null) {
            return;
        }
        long now = level.getGameTime();
        UUID id = owner.getUUID();
        ShotStamp last = RECENT_SHOTS.get(id);

        // ⚠️ 只处理**弩射出来的**球。
        //
        // 这条兜底原本只按「同一个射手在同一刻又出现一颗球」判定，
        // 完全不看来源 —— 于是玩家**连着右键扔两颗球**时，第二颗就被打成
        // 「多重射击附属弹」，而附属弹的规则是「落定即碎、碎裂无掉落、且跳过 morph」。
        // 表现就是：金球扔出去不等变金块，直接碎（作者 2026-10-09 报的）。
        // 手扔的球不会经过 AbstractArrow 的发射流程，本来就不该有多重射击。
        if (!ball.isFromCrossbow()) {
            return;
        }

        if (last != null && now - last.gameTime() <= SAME_VOLLEY_TICKS) {
            RECENT_SHOTS.put(id, new ShotStamp(now, last.count() + 1));
            ball.setMultishotSide(true);
            MoreBalls.LOGGER.debug("[ball] 兜底识别：同一次发射的第 {} 颗 -> 判定为附属弹", last.count() + 1);
            // 「三军听令！」：用多重射击第一次打出球。
            // 走到这里就说明同一把弩在一次发射里射出了不止一颗 —— 多重射击生效了。
            // 记在**射手**头上（玩家才拿成就），怪物射的不算。
            if (owner instanceof Player shooter) {
                ModAdvancements.award(shooter, ModAdvancements.MULTISHOT);
            }
            return;
        }

        if (RECENT_SHOTS.size() > 256) {
            RECENT_SHOTS.entrySet().removeIf(entry -> now - entry.getValue().gameTime() > SAME_VOLLEY_TICKS);
        }
        RECENT_SHOTS.put(id, new ShotStamp(now, 1));
    }
}
