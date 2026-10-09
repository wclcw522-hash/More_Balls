package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.BallProjectile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.entity.monster.Blaze;
// 26.x 把这些类重组过包结构：Pillager 挪进 monster.illager，
// AbstractArrow 挪进 projectile.arrow —— 老路径编译不过
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
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

    /** 掠夺者改用球作弹药的概率（作者指定 20%） */
    public static final float PILLAGER_AMMO_CHANCE = 0.20F;

    /** 顺手捡起脚边球的范围（格）—— 作者指定 1.5 */
    public static final double PICKUP_RADIUS = 1.5D;

    /**
     * 捡球判定概率 —— 作者指定：<b>每刻判定一次，每次 2%</b>。
     *
     * <p>也就是平均约 50 刻（2.5 秒）才会捡起来一次，怪物会在球旁边磨蹭一会儿，
     * 而不是一走近就秒捡。</p>
     */
    public static final float PICKUP_CHANCE = 0.02F;

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
     * 只是<b>不主动扔</b> —— 它拿球是为了自爆前 5 刻朝目标抛出去，
     * 那套走 {@code CreeperBallBehavior}，不走这里的投掷流程。
     * 球会渲染在它头顶（它没有手臂，主手物品看不见）。</p>
     */
    private static boolean hasHands(Mob mob) {
        return !(mob instanceof Slime)    // 史莱姆 / 岩浆怪（后者是子类）
                && !(mob instanceof Ghast)    // 恶魂：只有触手
                && !(mob instanceof Blaze)    // 烈焰人：一圈棒子
                && !(mob instanceof Shulker)  // 潜影贝：壳里没手
                // 末影人（作者 2026-10-07 指定）：<b>不能拾起、使用、携带球</b>。
                // 理由上和上面几个同类 —— 它没有能拿东西的手（原版它搬方块用的是
                // 另一套「空手扛起来」的行为，跟「手持物品」不是一回事）。
                // 放在这里一处即可同时堵住三条路：接住飞来的球、捡地上的球，
                // 以及下面 onFinalizeSpawn 的「生成时携带」。
                && !(mob instanceof EnderMan);
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
        // 得先有手 —— 作者指定：只有僵尸这类手里能拿工具的才会接球反击。
        // 苦力怕、史莱姆之流根本没长手，之前它们也在接球，纯属滑稽
        if (!hasHands(mob)) {
            return false;
        }
        // 手上已经拿着东西就腾不出手接
        if (!mob.getMainHandItem().isEmpty()) {
            return false;
        }

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
     * 顺手把脚边停着的球捡起来 —— 作者指定的规则：范围 1.5 格、<b>每刻判定一次</b>、
     * 每次 <b>2%</b> 概率；前提是怪的手空着。
     *
     * <p>捡到的球会直接进主手，之后走同一套蓄力投掷流程，所以捡起来就能扔。</p>
     */
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
    private static void tryPickUpBall(Mob mob) {
        if (!(mob instanceof Enemy)) {
            return; // 牛羊之类不参与
        }
        // 捡球同样得先有手 —— 没手的怪连球都拿不住，更别提扔
        if (!hasHands(mob)) {
            return;
        }

        // ① 先在<b>拾取半径</b>内找球 —— 范围只有 1.5 格，每刻找一遍也不贵。
        //
        //    这一步必须每刻都做：拾取的 2% 概率挂在这里，
        //    要是跟下面的「寻球扫描」一起被限流成 10 刻一次，
        //    实际概率就变成平均 12.5 秒才捡一颗 —— 脚下堆一堆球时看起来就是「压根不捡」。
        //
        //    ⚠️ 两种形态都算「脚下的球」（作者 2026-10-07 指定）：
        //      · 飞行中 / 已停下的**球实体**（BallProjectile）
        //      · 以及**掉落物形态的球**（ItemEntity 里装着 #more_balls:balls 的东西）——
        //        比如被爆炸炸飞、或者碎裂后滚出来的球
        //    两者共用同一套半径、概率、条件，所以先取实体、没有再取掉落物。
        List<BallProjectile> near = findBalls(mob, PICKUP_RADIUS);
        if (!near.isEmpty()) {
            // 【金光闪闪】作者 2026-10-08 指定：金球在「碰撞后 5 秒」的吸引窗口内
            // **不能被捡起** —— 那段窗口是留给玩家看清「猪灵被吸引过来」的，
            // 球一落地就被捡走的话，这个效果根本看不见。
            BallProjectile candidate = near.get(0);
            if (isGoldLureLocked(candidate)) {
                return;
            }
            if (mob.getRandom().nextFloat() < PICKUP_CHANCE) {
                pickUpBall(mob, candidate);
            }
            return;
        }

        ItemEntity droppedNear = findDroppedBall(mob, PICKUP_RADIUS);
        if (droppedNear != null) {
            if (mob.getRandom().nextFloat() < PICKUP_CHANCE) {
                pickUpDroppedBall(mob, droppedNear);
            }
            return;
        }

        // ② 近处没有 → 每 10 刻才扫一次大范围，决定要不要走过去。
        //    这一步只是「找目标」，慢一点无所谓，没必要逐刻遍历实体列表。
        if (mob.tickCount % SEEK_SCAN_INTERVAL != 0) {
            return;
        }
        List<BallProjectile> far = findBalls(mob, SEEK_RADIUS);
        if (!far.isEmpty()) {
            mob.getNavigation().moveTo(far.get(0), SEEK_SPEED);
            return;
        }
        ItemEntity droppedFar = findDroppedBall(mob, SEEK_RADIUS);
        if (droppedFar != null) {
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
        // 手里原本拿着的（比如猪灵的金剑）掉在脚边，别让它凭空消失。
        // 原版猪灵是把原物收进背包，我们简化成掉落 —— 效果上等价，实现上干净。
        ItemStack previous = mob.getMainHandItem();

        // 【金光闪闪】的猪灵交易是**唯一**允许「丢掉手上东西去接球」的情形 ——
        // 它本来就是这么设计的（金剑落地、金球入手）。
        boolean piglinTrade = BallBehavior.isGoldShiny(stack) && mob instanceof Piglin;
        // 其它怪物手上拿着东西就别抢它的。特别是骷髅：它拿着弓，
        // 之前这里漏了判断，于是它会跑来捡球、把弓丢在脚边
        //（作者 2026-10-07 报「小白怎么接球了，还把弓扔了」）。
        // 另外 tryPickUpBall 的说明里本来就写着「前提是怪的手空着」，这条就是它。
        if (!previous.isEmpty() && !piglinTrade) {
            return false;
        }

        if (!previous.isEmpty() && mob.level() instanceof ServerLevel level) {
            mob.spawnAtLocation(level, previous.copy(), 0.0F);
        }

        mob.setItemSlot(EquipmentSlot.MAINHAND, stack);
        mob.setData(ModAttachments.BALL_CHARGE_END.get(), 0);
        mob.setData(ModAttachments.BALL_CHARGE_LEVEL.get(), -1);

        // 【金光闪闪】：猪灵捡到金球当场交易 ——
        // 没被这颗球打过就是「特殊交易」，一口气给好几次产物（详见 PiglinLure）。
        // 猪灵蛮兵不算 Piglin，这里自然被排除在外：它们只会拿着球，不办事。
        if (BallBehavior.isGoldShiny(stack) && mob instanceof Piglin piglin) {
            boolean special = !Boolean.TRUE.equals(
                    piglin.getExistingDataOrNull(ModAttachments.GOLD_BALL_HURT.get()));
            PiglinLure.onPiglinPickedUpGoldBall(piglin, source);
            MoreBalls.LOGGER.info("[ball] 猪灵拾起金球并完成交易（特殊={}）", special);
        }
        return true;
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

        // 其他敌对生物：主手空着才有机会拿球当远程武器。
        // 「有没有手」这一条不能省 —— 末影人、史莱姆这些拿不了球（作者指定：
        // 末影人不能拾起 / 使用 / 携带球），生成时也别给它发。
        if (!(mob instanceof Enemy) || !hasHands(mob) || !mob.getMainHandItem().isEmpty()) {
            return;
        }
        if (random.nextFloat() >= CARRY_CHANCE) {
            return;
        }
        ItemStack ball = BallBehavior.randomBall(random);
        if (!ball.isEmpty()) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, ball);
        }
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

        ItemStack ammo = BallBehavior.randomBall(pillager.getRandom());
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

        // 兜底：拿不了球的生物（末影人 / 史莱姆 / 恶魂 / 烈焰人 / 潜影贝）整段都不参与 ——
        // 正常途径它本来就拿不到球（生成不发、捡不到、接不住），但指令或其它模组
        // 硬塞一颗到它手里时，这里还能保证它不会用、不会捡。作者指定：末影人
        // 不能拾起、使用、携带球。
        if (!hasHands(mob)) {
            return;
        }

        // 手里没球的时候，每刻掷一次「要不要把脚边的球捡起来」（作者指定：1.5 格 / 每刻 / 2%）。
        //
        // 这里原来写的是 hand.isEmpty()，结果<b>猪灵永远不满足</b>—— 它们天生握着金剑，
        // 于是金球全被空着手的僵尸抢走了（作者反馈的正是这个）。
        // 改成「手里不是球就能捡」：原版猪灵捡金锭时本来也是直接替换手里物品的
        // （金剑换金锭、原物收起来），所以这么做才符合原版行为。
        // 被换下来的东西不会凭空消失，会掉在脚边（见 pickUpBall）。
        if (!BallAmmo.isBall(hand)) {
            tryPickUpBall(mob);
            return;
        }

        // 苦力怕：能捡球、能接球，但**不主动扔**（作者 2026-10-07 指定）。
        // 它手上那颗球由 CreeperBallBehavior 在自爆前 5 刻朝目标抛出去。
        if (mob instanceof Creeper) {
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
            throwBall(mob, hand, target, level);
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
        //    原来的系数是固定的 0.1，对近距离够用、远距离会明显打低。
        //    改成按水平距离线性增长 —— 与玩家那条路径的抬升思路一致，
        //    但怪物没有蓄力瞄准，所以抬得更稳一点。
        double lift = Math.min(0.35D, 0.08D + horizontal * 0.02D);
        ball.shoot(dx, dy + horizontal * lift, dz, velocity, profile.inaccuracy());
        // 伤害同样按「最终速度 / 无蓄力速度」缩放 —— 与玩家投掷一字不差
        ball.setDamage(profile.damage() * (velocity / baseVelocity));

        level.addFreshEntity(ball);
        stack.shrink(1);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.SNOWBALL_THROW, SoundSource.NEUTRAL, 0.5F, 1.0F);
        // 把球交回去 —— 调用方有时还要补设属性（比如苦力怕那颗要标「静止后破碎」）
        return ball;
    }

    // ===== 附加弹兜底识别 =====

    /** 某个射手最近一次投弹是哪一刻、连着投了几颗 */
    private record ShotStamp(long gameTime, int count) {
    }

    /** 兜底识别用的记录表；用强引用，过期条目手动清（弱引用会被 GC 无声清掉，踩过） */
    /**
     * 射手 UUID → 上一次发射的时间戳（用于「同一次齐射」兜底识别）。
     *
     * <p>⚠️ 这是个**无上界**的 Map，每个开过弩的实体都会留下一条记录、永不清理。
     * 用 LRU 限住，并改成并发安全的实现（怪物 tick 走服务端线程，但主手投掷
     * 有可能在别的线程被触发，原版 HashMap 不安全）。</p>
     */
    private static final int RECENT_SHOTS_CAPACITY = 256;

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
     * <p><b>取 1 刻＝同一个游戏刻</b>，这是作者定的：之前取 5 刻是为了兼容
     * 「原版可能分刻创建那几颗」，结果把玩家的手动连射也误伤了 ——
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
     * <h2>窗口为什么是 5 刻而不是「同一刻」</h2>
     * 一开始按「同一游戏刻」判定，实测不生效 —— 很可能原版发射那几颗之间隔着刻，
     * 同刻计数永远只看到一颗。放宽到一个短窗口就能盖住一次发射的全部弹药，
     * 而弩的射速远慢于 5 刻，不会把两次独立发射误并到一起。</p>
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
        // 这条兜底原本只按「同一个射手在 5 刻内又出现一颗球」判定，
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
