package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.BallProjectile;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>持球苦力怕</b>（作者 2026-10-07 指定的一整套机制）。
 *
 * <h2>规格</h2>
 * <ol>
 *   <li>苦力怕<b>能接球、能捡球</b>，但<b>不主动扔</b>（投掷流程里已把它排除，见 {@code BallMobAI}）</li>
 *   <li>手上的球<b>显示在头顶</b> —— 它没有手臂，主手物品本来是看不见的</li>
 *   <li><b>原版机制全部保留</b>：索敌、追击、引信、收尾一行不动</li>
 *   <li>索敌后点燃的蓄力<b>不因目标远离而取消</b></li>
 *   <li>爆炸前 <b>1 tick</b>，以 <b>0 级蓄力</b>把球朝目标<b>上三分之一处</b>甩出去</li>
 *   <li>该次爆炸：<b>不破坏方块</b>、伤害 10%、<b>冲击 ×5</b></li>
 *   <li>爆炸<b>能把球打飞</b>（原版爆炸不推投射物）</li>
 * </ol>
 *
 * <h2>爆炸改造为什么用事件而不是 Mixin</h2>
 * <p>一开始我用 {@code CreeperMixin} 的 {@code @Redirect} 去替换 {@code explodeCreeper()} 里那一句
 * {@code explode(...)}，<b>连试三轮都没注进去</b>（owner 写错、handler 参数写错、改对了仍然静默失败）——
 * 有 {@code require = 0} 兜着，连报错都没有。</p>
 *
 * <p>换成 {@link ExplosionEvent.Start} 之后问题消失：这是纯 NeoForge 事件，
 * <b>不走 Mixin、不依赖注入匹配</b>。</p>
 *
 * <p><b>做法</b>：拦下原版那一炸（{@code setCanceled(true)}），再用自己的计算器重放一次。
 * 因为 {@code explodeCreeper()} 里除了这一句还有 {@code spawnLingeringCloud()}、{@code discard()} 等收尾，
 * <b>cancel 事件不影响它们</b> —— 原版流程照样走完，这正是作者要求的「原本逻辑不要变」。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class CreeperBallBehavior {

    private CreeperBallBehavior() {
    }

    /** 原版自爆蓄满需要 30 刻 */
    private static final int MAX_SWELL = 30;

    /**
     * 爆炸前这么多刻把球甩出去（作者指定 <b>2</b>：5 → 2 → 1 → 又回 2，正在试手感）。
     *
     * <p>这个数直接决定球离爆心多远：1 时球刚出手就炸、与爆心几乎重合，
     * 爆炸推力会把它顶得偏上；改成 2 会让它有 1 刻的飞行距离。</p>
     */
    private static final int THROW_BEFORE_TICKS = 2;

    /** 对应的蓄力比例阈值：29 / 30 */
    private static final float THROW_AT_SWELLING = (MAX_SWELL - THROW_BEFORE_TICKS) / (float) MAX_SWELL;

    /** 头顶图标离苦力怕头顶多高 */
    private static final double HEAD_ICON_LIFT = 0.42D;

    /**
     * 甩球起点往苦力怕身前挪多少格（作者指定：<b>投出的位置在苦力怕面前</b>）。
     *
     * <p>不只是观感问题：球扔出后 1 tick 就爆炸，若起点贴在身体上，
     * 球与爆心几乎重合 —— 爆炸推力会退化成「纯向上」，把球顶上天。</p>
     */
    private static final double THROW_FORWARD_OFFSET = 0.7D;

    /** 头顶图标实体的标记，便于每刻找回自己那一个 */
    private static final String HEAD_ICON_TAG = "more_balls:creeper_head_icon";

    /**
     * 苦力怕 UUID → 它的头顶图标实体 id。
     *
     * <p>⚠️ 用记录代替「每 tick 扫 64 格找图标」—— 那个扫描有两个毛病：
     * 一是贵（128³ 的实体查询），二是**区块边界上会漏**，漏了就新建一个，
     * 于是头顶的球一闪一闪、还留下残影（作者 2026-10-09 反馈）。</p>
     */
    private static final java.util.Map<java.util.UUID, Integer> HEAD_ICON_IDS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<java.util.UUID, Integer> eldest) {
                    return size() > 256;
                }
            });

    /** 图标标签的前缀，后面接苦力怕的 UUID */
    private static final String HEAD_ICON_OWNER_PREFIX = "more_balls:head_of:";

    /**
     * 给「持球苦力怕」打的标记 —— 用来认「这一炸该不该换成特制参数」。
     *
     * <p>不能靠「主手有没有球」判断：球在爆炸前 1 刻就已经甩出去了，
     * 等到爆炸那一刻手上是空的。所以状态得提前记在实体上。</p>
     */
    public static final String BALL_BLAST_TAG = "more_balls:ball_blast";

    /**
     * 爆炸推球的力度 —— <b>手扔速度的 5 倍</b>（作者 2026-10-07 指定）。
     *
     * <p>「手扔速度」= 这颗球自己的 {@code profile.physicalVelocity()}（初速度 × 0.15）。
     * 用**球自己的**而不是某个固定值，是为了让不同球的手感一致：
     * 重球手扔本来就慢，被炸飞也该相对慢；轻球两边都快。</p>
     */
    private static final double PUSH_HAND_THROW_MULTIPLIER = 5.0D;

    /** 重放爆炸时的递归保护 —— 自己重放的那一次要放它过去 */
    private static boolean replayingBallBlast = false;

    /** 持球苦力怕的爆炸参数 —— 伤害打一折、冲击乘五 */
    private static final ExplosionDamageCalculator BALL_BLAST_CALCULATOR = new ExplosionDamageCalculator() {

        /** 实体伤害 ×0.1 */
        @Override
        public float getEntityDamageAmount(Explosion explosion, Entity entity, float exposure) {
            return super.getEntityDamageAmount(explosion, entity, exposure) * 0.10F;
        }

        /** 冲击 ×5 */
        @Override
        public float getKnockbackMultiplier(Entity entity) {
            return super.getKnockbackMultiplier(entity) * 5.0F;
        }
    };

    // ===== 每刻：引信维持 / 甩球 / 头顶图标 =====

    @SubscribeEvent
    public static void onCreeperTick(EntityTickEvent.Post event) {
        // 【苦力怕死亡 -> 头顶的球立刻消失】（作者 2026-10-09 反馈）
        //
        // 头顶那个球是个**独立的 ItemDisplay 实体**，只在 tick 里被维护 ——
        // 苦力怕一死就不再 tick，图标于是永远留在那儿。
        // 所以这里在「不存活」时主动收掉它。
        if (event.getEntity() instanceof Creeper deadCheck
                && (!deadCheck.isAlive() || deadCheck.isRemoved())) {
            removeHeadIcon(deadCheck);
        }
        if (!(event.getEntity() instanceof Creeper creeper) || creeper.level().isClientSide()) {
            return;
        }
        ItemStack hand = creeper.getMainHandItem();
        boolean holdingBall = BallAmmo.isBall(hand);
        boolean ballBlast = creeper.entityTags().contains(BALL_BLAST_TAG);

        // ===== 末影珍珠苦力怕（作者 2026-10-07 指定）=====
        //
        // 当且仅当**手里是末影珍珠**时走这一条独立流程：
        //   爆炸前扔出珍珠 → 自己进入冻结状态 → 珍珠落地后瞬移过去 → 立刻爆炸。
        // 因为末影珍珠也在 #more_balls:balls 标签里（原版物品算作球），
        // 所以必须**在通用持球逻辑之前**先判出来，否则它会走成普通扔球。
        if (holdingBall && hand.is(Items.ENDER_PEARL)) {
            pearlCreeperTick(creeper);
            return;
        }

        // ⚠️ 球甩出去之后**不能就此收工**：
        //    主手一空就 return，于是再也不按引信，原版 CreeperSwellGoal
        //    立刻因为目标距离把引信掐了 —— 表现就是「扔出球之后蓄力被取消」。
        //    所以甩完球还要继续按引信，直到它真的炸。
        if (!holdingBall) {
            removeHeadIcon(creeper);
            if (!ballBlast) {
                return;
            }
            // 引信万一被水浇灭之类 → 这一轮作废，把标记清掉
            if (creeper.getSwelling(0.0F) <= 0.0F && creeper.getSwellDir() <= 0) {
                creeper.removeTag(BALL_BLAST_TAG);
                return;
            }
            // 同样要「每刻确认稳定为 1」，别让它抖（原因见下面 ① 的说明）
            if (creeper.getSwellDir() != 1) {
                creeper.setSwellDir(1);
            }
            return;
        }

        // 持球期间打标记：爆炸那一刻要按它走特制爆炸
        if (!ballBlast) {
            creeper.addTag(BALL_BLAST_TAG);
        }

        // ① 引信维持（作者指定：索敌后点燃的蓄力不会因为目标远离而取消）。
        //
        //    ⚠️ 必须是「每刻确认它为 1」，不能写成「跌到 0 才按回」——
        //    后者会让引信在 0/1 之间来回抖，而原版 CreeperSwellGoal 的 canUse() 判据里
        //    带 `getSwellDir() > 0`：一抖就会反复 start/stop，而 start() 里有一句
        //    getNavigation().stop() —— 结果就是苦力怕被钉在原地不动。
        //
        //    只动引信，**绝不碰移动**：追击与寻路全归原版那套 AI。
        LivingEntity target = creeper.getTarget();
        boolean chasing = target != null && target.isAlive();
        if (chasing && creeper.getSwelling(0.0F) > 0.0F && creeper.getSwellDir() != 1) {
            creeper.setSwellDir(1);
        }

        // ② 爆炸前 1 刻，把手里的球朝目标甩出去（作者指定）
        if (creeper.getSwelling(0.0F) >= THROW_AT_SWELLING) {
            throwBallOut(creeper);
            removeHeadIcon(creeper);
            return;   // 标记留着，下一 tick 走上面那条「甩完继续按引信」的路
        }

        // ③ 头顶那个球图标（苦力怕没手臂，主手物品本来看不见）
        ensureHeadIcon(creeper, hand);
    }

    /**
     * 把手里的球朝目标甩出去。
     *
     * <p>作者指定的规格：爆炸前 1 tick、<b>0 级蓄力</b>、
     * 瞄目标<b>上三分之一处</b>（从眼睛往下压掉身高的 1/3）。</p>
     *
     * <p>直接复用 {@code BallMobAI.throwBall}（怪物投掷那一套）——
     * 初速度、伤害、音效、消耗手上的球全在里面做完了。</p>
     */
    private static void throwBallOut(Creeper creeper) {
        ItemStack stack = creeper.getMainHandItem();
        if (!BallAmmo.isBall(stack)) {
            return;
        }
        LivingEntity target = creeper.getTarget();

        // 没目标就不甩 —— 投掷点在「爆炸中心 → 目标」的连线上，没有目标就没有那条线，
        // 硬朝视线方向扔只会偏掉（作者反馈过「向第一个球发射」：那时它盯着刚要去捡的球）。
        if (target == null || !target.isAlive()) {
            return;
        }

        // 瞄「上三分之一处」
        double drop = Math.max(0.0D, target.getEyeY()
                - (target.getY() + target.getBbHeight() * 2.0D / 3.0D));

        removeHeadIcon(creeper);
        BallProjectile ball = BallMobAI.throwBall(creeper, stack, target, 0, drop, THROW_FORWARD_OFFSET);
        // 这颗球静止后破碎（作者指定）：它是被爆炸「打」出去的，落点随机，
        // 让它躺在地上可捡就成了「炸一次白送一颗球」。碎掉照常掉材料，只是不留原地。
        ball.setShatterOnSettle(true);
    }

    // ===== 末影珍珠苦力怕 =====

    /**
     * 已扔出末影珍珠、正在等瞬移的苦力怕。
     *
     * <p>打这个标记的苦力怕处于<b>冻结状态</b>：AI 关掉、速度清零，
     * 直到珍珠落地把它带过去、并立即引爆。</p>
     */
    public static final String PEARL_BLAST_TAG = "more_balls:pearl_blast";

    /**
     * 原版苦力怕的爆炸半径。
     *
     * <p>{@code Creeper.explosionRadius} 是 private，取不到，所以按原版数值写死 3.0。
     * 作者指定珍珠那一炸要「原版威力」，指的就是这个。</p>
     */
    private static final float CREEPER_EXPLOSION_RADIUS = 3.0F;

    /**
     * 每只「正在等瞬移」的苦力怕，对应的珍珠最后位置。
     *
     * <p>为什么要记：珍珠落地那一刻原版会把它 {@code discard()} 掉，
     * 之后它的坐标就问不到了 —— 而苦力怕要瞬移的正是那个落点。
     * 所以在珍珠还活着的每一刻把位置抄下来，等它消失时用。</p>
     */
    /**
     * 苦力怕 UUID → 它扔出的末影珍珠要飞向哪儿。
     *
     * <p>⚠️ 这是个**无上界**的 Map —— 每只扔过珍珠的苦力怕都会留一条，
     * 而且只在「珍珠落地」时才移除；苦力怕被提前杀掉就永远留着。
     * 用 LRU 限住（顺带避免区块反复加载时越堆越多）。</p>
     */
    private static final int PEARL_TARGET_CAPACITY = 128;

    private static final Map<UUID, Vec3> PEARL_TARGET =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(32, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Vec3> eldest) {
                    return size() > PEARL_TARGET_CAPACITY;
                }
            });

    /**
     * 末影珍珠苦力怕的每刻逻辑。
     *
     * <p>两条路：还没扔 → 等膨胀到临界点就扔珍珠并冻结；已经扔了 → 冻结着等瞬移
     * （真正的瞬移与引爆由 {@link #onPearlGone} 在珍珠消失时接手）。</p>
     */
    private static void pearlCreeperTick(Creeper creeper) {
        if (!creeper.entityTags().contains(PEARL_BLAST_TAG)) {
            // 还没扔：维持引信（和普通持球苦力怕同一条规矩 —— 每刻确认它稳定为 1）
            LivingEntity target = creeper.getTarget();
            if (target != null && target.isAlive()
                    && creeper.getSwelling(0.0F) > 0.0F && creeper.getSwellDir() != 1) {
                creeper.setSwellDir(1);
            }
            ensureHeadIcon(creeper, creeper.getMainHandItem());

            if (creeper.getSwelling(0.0F) >= THROW_AT_SWELLING) {
                throwPearlOut(creeper);
            }
            return;
        }

        // 已经扔出去了：冻结等瞬移。
        // 反复按引信 —— 冻结期间原版 AI 不会再管引信，不按它就会自己消下去。
        if (creeper.getSwellDir() != 1) {
            creeper.setSwellDir(1);
        }
        creeper.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * 把末影珍珠朝目标扔出去，然后让苦力怕进入冻结状态。
     *
     * <p>用原版 {@code ThrownEnderpearl}，但<b>不能指望它把苦力怕带过去</b> ——
     * 原版那颗珍珠只对玩家生效（{@code isAllowedToTeleportOwner} 里只认 {@code ServerPlayer}），
     * 怪物扔出去的珍珠落地只会消失。所以瞬移由本模组自己算，
     * 这里只需要一颗「会飞、会落地消失」的珍珠，以及记下它的落点。</p>
     */
    private static void throwPearlOut(Creeper creeper) {
        LivingEntity target = creeper.getTarget();
        if (target == null || !target.isAlive()) {
            return;   // 没目标就不扔（和普通持球苦力怕同一条判据）
        }

        removeHeadIcon(creeper);

        ThrownEnderpearl pearl = new ThrownEnderpearl(creeper.level(), creeper,
                new ItemStack(Items.ENDER_PEARL));

        // 瞄目标上三分之一处，和普通扔球的瞄法保持一致
        double aimY = target.getY() + target.getBbHeight() * 2.0D / 3.0D;
        double dx = target.getX() - creeper.getX();
        double dy = aimY - (creeper.getEyeY() + 0.1D);
        double dz = target.getZ() - creeper.getZ();
        pearl.shoot(dx, dy, dz, 1.5F, 1.0F);
        pearl.setPos(creeper.getX(), creeper.getEyeY() + 0.1D, creeper.getZ());
        creeper.level().addFreshEntity(pearl);

        // 珍珠没了（手就空了）
        creeper.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        // 进入冻结状态：关 AI、清速度，等珍珠落地的信号
        creeper.addTag(PEARL_BLAST_TAG);
        PEARL_TARGET.put(creeper.getUUID(), creeper.position());
        creeper.setNoAi(true);
        creeper.setDeltaMovement(Vec3.ZERO);
        // 冻结期间原版 CreeperSwellGoal 不会再点火，所以引信由 pearlCreeperTick 每刻顶着
        creeper.setSwellDir(1);
    }

    /** 珍珠还在飞的时候，持续抄下它的位置 —— 等它消失时苦力怕要瞬移到那儿 */
    @SubscribeEvent
    public static void onPearlTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof ThrownEnderpearl pearl) || pearl.level().isClientSide()) {
            return;
        }
        if (pearl.getOwner() instanceof Creeper creeper
                && creeper.entityTags().contains(PEARL_BLAST_TAG)) {
            PEARL_TARGET.put(creeper.getUUID(), pearl.position());
        }
    }

    /**
     * 珍珠消失（落地 / 撞到东西）→ 苦力怕瞬移过去并<b>立刻爆炸</b>。
     *
     * <p>爆炸规格（作者指定）：<b>原版威力、但不破坏方块</b> ——
     * 也就是 {@link Level.ExplosionInteraction#NONE}。原版苦力怕那一炸在
     * {@link #onExplosionStart} 里被换成了特制参数（伤害 10%、冲击 5 倍），
     * 那是给「持球苦力怕」的；珍珠这一炸要的是原版伤害，所以不走那条路，
     * 直接在这里原地引爆。</p>
     */
    @SubscribeEvent
    public static void onPearlGone(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof ThrownEnderpearl pearl)) {
            return;
        }
        if (!(pearl.level() instanceof ServerLevel level)) {
            return;
        }
        if (!(pearl.getOwner() instanceof Creeper creeper)) {
            return;
        }
        if (!creeper.entityTags().contains(PEARL_BLAST_TAG)) {
            return;
        }

        creeper.removeTag(PEARL_BLAST_TAG);
        // 用「珍珠消失前最后记下的位置」，拿不到就退回珍珠当前坐标
        Vec3 arrival = PEARL_TARGET.remove(creeper.getUUID());
        if (arrival == null) {
            arrival = pearl.position();
        }

        // 瞬移过去
        creeper.teleportTo(arrival.x, arrival.y, arrival.z);
        creeper.setNoAi(false);
        creeper.setDeltaMovement(Vec3.ZERO);

        // 瞬间爆炸：原版威力（半径 3.0 = Creeper 的 explosionRadius 默认值，
        // 那边的字段是 private，取不到，所以这里写死原版数值）、不破坏方块
        level.explode(creeper, arrival.x, arrival.y, arrival.z,
                CREEPER_EXPLOSION_RADIUS, Level.ExplosionInteraction.NONE);

        // 炸完自己就该退场（原版 explodeCreeper 里也是 discard 收尾）
        creeper.discard();
    }

    // ===== 爆炸：拦下原版那一炸，换成特制参数重放 =====

    /**
     * 持球苦力怕的那一炸换成：<b>伤害 10%、冲击 5 倍</b>
     * （不破坏方块由下面的 {@link #onExplosionDetonate} 处理）。
     *
     * <p>为什么不用 Mixin 见类注释 —— 简言之：{@code @Redirect} 试了三轮都静默失败，
     * 换事件一次通。</p>
     */
    /**
     * 【苦力怕死亡 -> 头顶的球立刻消失】（作者 2026-10-09 反馈）
     *
     * <p>头顶那个球是个<b>独立的 {@code ItemDisplay} 实体</b> —— 它不会被苦力怕带着一起死，
     * 而维护它的 {@link #onCreeperTick} 在苦力怕死后就不再跑了，于是图标永远留在原地。</p>
     *
     * <p>用死亡事件收掉，比在 tick 里判断更可靠：自杀式爆炸、{@code /kill}、掉虚空
     * 这几种路径都会走到这里。</p>
     */
    @SubscribeEvent
    public static void onCreeperDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Creeper creeper) {
            var icon = findHeadIcon(creeper);
            removeHeadIcon(creeper);
            MoreBalls.LOGGER.info("[ball] 持球苦力怕死亡 -> 清除头顶图标：找到={}，清除后仍存在={}",
                    String.valueOf(icon), String.valueOf(findHeadIcon(creeper)));
        }
    }

    @SubscribeEvent
    public static void onExplosionStart(ExplosionEvent.Start event) {
        if (replayingBallBlast) {
            return;   // 这是我自己重放的那一次，放它过去
        }
        Explosion explosion = event.getExplosion();
        if (!(explosion.getDirectSourceEntity() instanceof Creeper creeper)) {
            return;
        }
        if (!creeper.entityTags().contains(BALL_BLAST_TAG)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        // 把原版那一炸拦下来。注意：这只挡掉「爆炸本身」，
        // explodeCreeper() 后面的 spawnLingeringCloud / discard 等收尾照常走完。
        event.setCanceled(true);

        float radius = creeper.isPowered() ? 6.0F : 3.0F;
        replayingBallBlast = true;
        try {
            MoreBalls.LOGGER.info("[ball] 持球苦力怕特制爆炸：伤害×0.1、冲击×5（半径 {}）", radius);
            level.explode(creeper, null, BALL_BLAST_CALCULATOR,
                    creeper.getX(), creeper.getY(), creeper.getZ(),
                    radius, false, Level.ExplosionInteraction.MOB);
            pushNearbyBalls(level, creeper, radius, creeper.getTarget());
        } finally {
            replayingBallBlast = false;
        }
    }

    /**
     * 持球苦力怕的爆炸<b>不破坏方块</b>（作者指定）。
     *
     * <p>不写在重放的参数里：原版爆炸的粒子与音效都跟 {@code ExplosionInteraction} 绑在一起，
     * 换成 {@code NONE} 会连观感一起没掉（实测「根本没有爆炸效果」）。
     * 所以在爆炸结算前把「要炸掉的方块」清空 —— 方块保住了，粒子音效照旧。</p>
     */
    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        Explosion explosion = event.getExplosion();
        if (!(explosion.getDirectSourceEntity() instanceof Creeper creeper)) {
            return;
        }
        if (!creeper.entityTags().contains(BALL_BLAST_TAG)) {
            return;
        }
        int cleared = event.getAffectedBlocks().size();
        event.getAffectedBlocks().clear();
        if (cleared > 0) {
            MoreBalls.LOGGER.info("[ball] 持球苦力怕的爆炸已免掉 {} 个方块", cleared);
        }
    }

    // ===== 头顶图标 =====

    /** 找回这只苦力怕头顶那个图标实体（自己打标记，别认错别人的） */
    static Display.ItemDisplay findHeadIcon(Creeper creeper) {
        // 优先按 **UUID 标签**认领（精确）；找不到再退回「就近找」（兼容旧图标）
        String tag = ownerTag(creeper);
        java.util.List<Display.ItemDisplay> nearby = creeper.level()
                .getEntitiesOfClass(Display.ItemDisplay.class, creeper.getBoundingBox().inflate(64.0D));
        for (Display.ItemDisplay icon : nearby) {
            if (icon.entityTags().contains(tag)) {
                return icon;
            }
        }
        return nearby.stream()
                .filter(d -> d.entityTags().contains(HEAD_ICON_TAG))
                .findFirst()
                .orElse(null);
    }

    /**
     * 让苦力怕头顶悬浮一个球图标。
     *
     * <p>用原版的 {@code ItemDisplay}：物品通过公开的 {@code getSlot(0)}（{@link SlotAccess}）塞进去，
     * 位置每刻自己摆 —— 它那些 setter 都是 private，骑乘又只会坐到脚下。</p>
     */
    private static void ensureHeadIcon(Creeper creeper, ItemStack hand) {
        // ⚠️ **死了 / 已被移除就绝不再建**（作者 2026-10-09 反馈「死后还会闪几下」）。
        //
        //    原因：苦力怕死亡有死亡动画，那期间它**仍然是 alive 状态**、
        //    EntityTickEvent 照样触发 → 每刻调到这里 → 图标被删掉又立刻重建 →
        //    看上去就是「闪几下」。死亡事件已经把图标收掉了，这里必须停手。
        if (!creeper.isAlive() || creeper.isRemoved() || creeper.deathTime > 0) {
            return;
        }
        if (!(creeper.level() instanceof ServerLevel level)) {
            return;
        }
        // 先从记录表里拿；拿到就验证还活着、还是不是它
        Display.ItemDisplay icon = headIconFromRecord(creeper);
        if (icon == null) {
            // 26.x 把原版 EntityType 拆成了 EntityTypes（类型）与 EntityTypeIds（id），
            // 常量在 EntityTypes 上，不在 EntityType
            icon = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
            icon.addTag(HEAD_ICON_TAG);
            HEAD_ICON_IDS.put(creeper.getUUID(), icon.getId());
            // ⚠️ 记下它属于哪只苦力怕 —— 死亡时才能**精确**收掉。
            //    只靠「包围盒就近找」是不行的：苦力怕一死包围盒就不可靠了
            //    （死亡动画会改尺寸，之后实体被移除），于是图标留在原地
            //    （作者 2026-10-09 反馈「被击杀的苦力怕头顶的球没有正确消失」）。
            icon.addTag(ownerTag(creeper));
            level.addFreshEntity(icon);
        }

        SlotAccess slot = icon.getSlot(0);
        if (slot != null) {
            slot.set(hand.copyWithCount(1));
        }
        icon.setPos(creeper.getX(),
                creeper.getY() + creeper.getBbHeight() + HEAD_ICON_LIFT,
                creeper.getZ());
    }

    /** 从记录表里取这只苦力怕的图标；已经没了就返回 null 并清掉记录 */
    private static Display.ItemDisplay headIconFromRecord(Creeper creeper) {
        Integer id = HEAD_ICON_IDS.get(creeper.getUUID());
        if (id == null) {
            return null;
        }
        if (!(creeper.level() instanceof ServerLevel level)) {
            return null;
        }
        net.minecraft.world.entity.Entity e = level.getEntity(id);
        if (e instanceof Display.ItemDisplay display && display.isAlive()) {
            return display;
        }
        HEAD_ICON_IDS.remove(creeper.getUUID());
        return null;
    }

    /** 属于这只苦力怕的图标标签 —— 用 UUID 精确认领，不依赖包围盒 */
    private static String ownerTag(Creeper creeper) {
        return HEAD_ICON_OWNER_PREFIX + creeper.getUUID();
    }

    /** 收掉头顶那个图标（球甩出去了、或者手里不再是球） */
    private static void removeHeadIcon(Creeper creeper) {
        // ⚠️ 两路并进：
        //   1. 按 **UUID 标签精确删** —— 不依赖包围盒，死亡后依然有效
        //   2. 老的「就近删」保留作兜底 —— 覆盖「图标是旧版本建的、没有 owner 标签」的情况
        // ① 走记录表 —— 精确、便宜
        Display.ItemDisplay recorded = headIconFromRecord(creeper);
        if (recorded != null) {
            recorded.discard();
        }
        HEAD_ICON_IDS.remove(creeper.getUUID());

        // ② 兜底：按标签在小范围内找一遍（覆盖「记录表丢了但图标还在」的情况）
        String tag = ownerTag(creeper);
        if (creeper.level() instanceof ServerLevel level) {
            for (Display.ItemDisplay icon : level.getEntitiesOfClass(
                    Display.ItemDisplay.class, creeper.getBoundingBox().inflate(8.0D))) {
                if (icon.entityTags().contains(tag)) {
                    icon.discard();
                }
            }
        }
        for (Display.ItemDisplay icon : creeper.level()
                .getEntitiesOfClass(Display.ItemDisplay.class, creeper.getBoundingBox().inflate(2.0D))) {
            if (icon.entityTags().contains(HEAD_ICON_TAG)) {
                icon.discard();
            }
        }
    }

    // ===== 爆炸推球 =====

    /**
     * 爆炸把附近的球打飞（作者指定）。
     *
     * <p>原版爆炸会给生物、掉落物施加击退，但<b>不推投射物</b> ——
     * 所以这里自己扫一遍范围内的球，按「离爆心越近推得越狠」给一个冲量。</p>
     */
    public static void pushNearbyBalls(Level level, Creeper source, float radius) {
        pushNearbyBalls(level, source, radius, source.getTarget());
    }

    public static void pushNearbyBalls(Level level, Creeper source, float radius,
                                       @Nullable LivingEntity aimTarget) {
        double cx = source.getX();
        // ⚠️ 爆心高度**必须与投掷起点一致**（作者 2026-10-09 反馈「轻球会从头顶飞过去」）。
        //
        //    `BallMobAI.throwBall` 已经把投掷起点从「脚底」改成「身体中部」
        //    （`getY() + getBbHeight() * 2/3`），但这里还留在 `getY(0.0625)` 的脚底 ——
        //    于是「爆心 → 球」的相对方向偏上，推力把球顶过了头顶。
        double cy = source.getY() + source.getBbHeight() * 2.0D / 3.0D;
        double cz = source.getZ();
        double reach = radius * 2.0D;
        AABB box = new AABB(cx - reach, cy - reach, cz - reach, cx + reach, cy + reach, cz + reach);

        for (BallProjectile ball : level.getEntitiesOfClass(BallProjectile.class, box)) {
            Vec3 delta = new Vec3(ball.getX() - cx, ball.getY() - cy, ball.getZ() - cz);
            double dist = delta.length();
            if (dist < 0.05D) {
                // 球几乎就压在爆心上：这时「从爆心指向球」的方向是没有意义的（长度接近 0），
                // 早先用的是「向上」兜底 —— 结果就是球被顶得垂直冲天（作者反馈的「球上天了」）。
                // 改成沿它自己的飞行方向推；实在没速度才随机一个水平方向。
                Vec3 velocity = ball.getDeltaMovement();
                if (velocity.lengthSqr() > 1.0E-4D) {
                    delta = velocity.normalize();
                } else {
                    double a = ball.getRandom().nextDouble() * (Math.PI * 2.0D);
                    delta = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
                }
                dist = 1.0D;
            }
            // 力度 = **这颗球手扔速度的 5 倍**（作者指定），再按距爆心线性衰减。
            double handThrow = BallBehavior.profileFor(ball.getItem()).physicalVelocity();
            double strength = PUSH_HAND_THROW_MULTIPLIER * handThrow
                    * (1.0D - Math.min(1.0D, dist / reach));

            // ⚠️ 这里是 **直接设定速度**，不是 push() 累加 ——
            //    球有自己的重量 / 阻力那套物理，叠加式的冲量会被它们很快吃掉，
            //    表现就是「被炸了却飞不远」。
            Vec3 direction;
            if (aimTarget != null) {
                // ⚠️ 方向用「爆心 → 目标」，不是「爆心 → 球」（作者 2026-10-07 报「特别容易偏」）。
                //
                // 后者的问题：球只要有一点点偏离连线（散布、上一 tick 的位移、重力下坠），
                // 这点偏差就会被当成推力方向，于是**越推越偏**，一路放大。
                // 改用目标方向之后，无论球当前在哪，推力永远朝着目标 ——
                // 等于拿爆炸把球「钉」在连线上打出去。
                Vec3 toTarget = new Vec3(aimTarget.getX() - cx,
                        aimTarget.getY() + aimTarget.getBbHeight() / 3.0D - cy,
                        aimTarget.getZ() - cz);
                direction = toTarget.lengthSqr() < 1.0E-4D ? delta.normalize() : toTarget.normalize();
            } else {
                direction = delta.normalize();
            }

            // 垂直分量按方向给，不再额外压制 —— 方向已经由目标决定，
            // 再压一次只会让球朝地面偏（原来那套 maxVy 是给「爆心→球」兜底时加的）
            double vy = direction.y * strength + strength * 0.10D;

            double before = ball.getDeltaMovement().length();
            ball.setDeltaMovement(direction.x * strength, vy, direction.z * strength);
            double after = ball.getDeltaMovement().length();

            // 诊断：速度这块到底有没有被限制，看这两行就知道了。
            // （查过一遍：代码里没有速度钳制，唯一的上限 RETURN_MAX_SPEED 只用于「空气动力球回家」）
            MoreBalls.LOGGER.info("[ball] 爆炸推球：速度 {} → {}（距爆心 {}，强度 {}，波及半径 {}）",
                    before, after, dist, strength, reach);
        }
    }
}
