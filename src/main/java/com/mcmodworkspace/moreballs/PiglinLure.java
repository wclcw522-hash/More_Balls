package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.BallProjectile;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 金球的【金光闪闪】—— 猪灵全套行为。
 *
 * <h2>四条规则（作者指定）</h2>
 * <ol>
 *   <li><b>伤害不结仇</b>：金球打猪灵不会让它记恨投掷者</li>
 *   <li><b>被撞击声吸引</b>：金球落在哪儿，附近的猪灵（含<b>猪灵蛮兵</b>）就朝哪儿走，
 *       而且期间不会因为任何别的原因转移注意</li>
 *   <li><b>可被捡起</b>：金球是「可交易的金制品」，被猪灵捡走（蛮兵除外）</li>
 *   <li><b>特殊交易</b>：非幼小猪灵捡起后当场交易；若它<b>没被这颗金球打过</b>，
 *       这次是<b>特殊交易</b>，会一口气给出多次交易产物</li>
 * </ol>
 *
 * <h2>关于交易产物</h2>
 * 原版的猪灵交易表 {@code PiglinAi.getBarterResponseItems} 是 private，外部拿不到，
 * 所以这里自己维护一份等价的标准产物表（数值对齐原版）。
 * 特殊交易的<b>次数</b>按作者给出的权重抽，<b>产物</b>从表里随机取。
 */
public final class PiglinLure {

    private PiglinLure() {
    }

    /** 吸引半径（格）—— 作者说明的是「所有猪灵」，实际给一个够大的范围，免得满世界寻路 */
    public static final double LURE_RADIUS = 32.0D;

    /**
     * 被金球吸引时，每隔多少刻清一次猪灵的攻击目标。
     *
     * <p>每刻都清会和猪脑打架 —— 它每刻重新发现玩家、重新记恨，
     * 于是仇恨在「有 / 无」之间抖，看起来就是抽搐（作者 2026-10-08 报的）。
     * 降频之后既不会分心去打别人，也不会抖。</p>
     */
    public static final int ANGER_CLEAR_INTERVAL = 10;

    /**
     * 单次「欣赏金球」的时长（刻），对齐原版 {@code PiglinAi.admireGoldItem} 的 119 刻。
     *
     * <p>写成这个记忆之后，猪脑的 activity 会切到 ADMIRE_ITEM ——
     * 那一档里没有战斗行为、也没有「重新选目标」，于是 FIGHT 与抽搐都停了。</p>
     */
    public static final long ADMIRE_DURATION = 119L;

    /**
     * 【金光闪闪】窗口期内，多久重设一次 ADMIRING_ITEM（刻）。
     *
     * <p>不能每刻 —— 原版这条 activity 自带音效，每次重新进入都会播，
     * 每刻重设就等于每秒叫 20 声（作者 2026-10-09 反馈「特别的吵」）。</p>
     */
    public static final int ADMIRE_REFRESH_INTERVAL = 20;
    /** 被吸引时多久重设一次导航（刻） */
    public static final int LURE_NAV_INTERVAL = 5;

    /** 被吸引时的行走速度（略快于闲逛，看得出它们很急） */
    private static final double LURE_SPEED = 1.2D;

    /** 特殊交易次数分布（作者指定）：5 次 40 / 4 次 20 / 3 次 10 / 2 次 8 / 1 次 5 */
    private static final int[] TRADE_WEIGHTS = {40, 20, 10, 8, 5};

    /** 特殊交易的产物池 —— 对齐原版猪灵交易的标准产出 */
    private static final List<ItemStack> TRADE_POOL = List.of(
            new ItemStack(Items.SPECTRAL_ARROW, 12),
            new ItemStack(Items.LEATHER, 3),
            new ItemStack(Items.SOUL_SAND, 8),
            new ItemStack(Items.OBSIDIAN, 1),
            new ItemStack(Items.BLAZE_POWDER, 3),
            new ItemStack(Items.ENDER_PEARL, 2),
            new ItemStack(Items.STRING, 6),
            new ItemStack(Items.CRYING_OBSIDIAN, 1),
            new ItemStack(Items.ARROW, 9),
            new ItemStack(Items.IRON_NUGGET, 20),
            new ItemStack(Items.QUARTZ, 8),
            new ItemStack(Items.GLOWSTONE_DUST, 4),
            new ItemStack(Items.MAGMA_CREAM, 2));

    /** 金球撞击/落地的声音 —— 猪灵就是被这个吸引来的 */
    private static final float LURE_SOUND_VOLUME = 1.0F;

    // ===== 一、伤害不结仇 =====

    /**
     * 把猪灵因这次金球伤害而起的怒气抹掉。
     *
     * <p>猪灵的仇恨走 Brain 的记忆模块（{@code ANGRY_AT} / {@code ATTACK_TARGET}），
     * 光清 {@code setTarget} 不够 —— 那只是抢它的当前目标，记忆还在，过一刻又会回来。</p>
     */
    public static void forgetAnger(AbstractPiglin piglin) {
        // 清记忆模块本身就是幂等的，不需要先查有没有
        piglin.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
        piglin.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        piglin.setTarget(null);
    }

    // ===== 二、被金球吸引 =====

    /**
     * 金球躺在地上时的每一刻调用：把附近的猪灵全喊过来。
     *
     * <h2>为什么不擦 ATTACK_TARGET</h2>
     * <p>原来这里每刻（后来改成每 10 刻）扫掉猪脑的 {@code ATTACK_TARGET}，想让它「专心朝球走」。
     * 但那是在跟原版机制正面对撞 —— 猪脑的 {@code StartAttacking} 每刻检查
     * 「{@code ATTACK_TARGET} 不存在就重新选一个」，而它对玩家的判据
     * （玩家没穿金装备）几乎永远成立。于是每 10 刻完成一次
     * 「重新锁定玩家 → 被我们擦掉 → 再锁定」的循环，表现就是猪灵原地抽搐
     * （作者 2026-10-08 报的）。同时 FIGHT activity 里那条
     * {@code SetWalkTargetFromAttackTargetIfTargetOutOfReach} 每刻把导航终点钉回玩家，
     * 我们那句 {@code moveTo(球)} 根本轮不到执行。</p>
     *
     * <h2>正确做法：压住「选目标」这件事本身</h2>
     * <p>给猪灵写上 {@code ADMIRING_ITEM} 记忆即可。原版的 activity 优先级是
     * {@code [ADMIRE_ITEM, FIGHT, AVOID, CELEBRATE, RIDE, IDLE]}，
     * 而 {@code ADMIRE_ITEM} 的行为表里<b>没有任何战斗行为、也没有 StartAttacking</b> ——
     * 一旦它生效，FIGHT 与「重新选目标」整段都不跑，寻路也交给我们的 {@code moveTo}。
     * 这正是原版「猪灵被金锭吸引」用的机制，不新增 Goal、不碰 Mixin。</p>
     */
    public static void lure(ServerLevel level, Vec3 pos) {
        // ⚠️ 不能隔刻做。
        //
        // 原版 PiglinAi 的 ADMIRE_ITEM activity 里挂着 StopAdmiringIfItemTooFarAway，
        // 它的实现是「最近可见的『喜爱的物品』(只认 ItemEntity) 不在 9 格内 → 擦掉
        // ADMIRING_ITEM」；我们的球是投射物实体、永远进不了那个 sensor，
        // 所以它**每刻都会把记忆擦掉**。
        // 于是只有「每刻重新设一次」才能把 activity 稳在 ADMIRE_ITEM ——
        // 隔刻设的话会变成「设 → 被擦 → 设 → 被擦」的一刻一跳（作者报的抽搐的加强版）。
        List<AbstractPiglin> piglins = level.getEntitiesOfClass(AbstractPiglin.class,
                new net.minecraft.world.phys.AABB(pos, pos).inflate(LURE_RADIUS),
                piglin -> piglin.isAlive() && !piglin.isBaby());
        if (piglins.isEmpty()) {
            return;
        }

        boolean clearAnger = level.getGameTime() % ANGER_CLEAR_INTERVAL == 0L;
        for (AbstractPiglin piglin : piglins) {
            Brain<?> brain = piglin.getBrain();

            // ① 压住战斗意图（每 20 刻刷新一次，见下面为什么不能每刻刷）。
            //
            // ⚠️ 原版 ADMIRE_ITEM 这条 activity 自己挂着播 PIGLIN_ADMIRING_ITEM 的音效，
            //    而 Brain 每次**重新进入** activity 都会再播一次。
            //    先前是每刻重设 → 每刻都算「重新进入」→ 附近的猪灵每秒叫 20 声，
            //    作者反馈「被吸引之后反复发出叫声特别的吵」。
            //    改成 20 刻一次：既能压住与 Brain 的抢写（行为表本身不会在这一秒内翻盘），
            //    叫声也降到一秒一次、且是这一群猪灵在同一刻齐叫，听感上是一声。
            if (level.getGameTime() % ADMIRE_REFRESH_INTERVAL == 0L) {
                brain.setMemoryWithExpiry(MemoryModuleType.ADMIRING_ITEM, true, ADMIRE_DURATION);
            }

            // ② 每刻清掉攻击意图 —— 这就是作者要的「直接取消仇恨」。
            //
            //    作者反馈「被吸引还是有攻击意图，拿弩的猪灵会正常攻击」：
            //    远程攻击走的是 charge/attack 那套，ADMIRE_ITEM 压不住它，
            //    因为它并不经过 FIGHT 的近战分支。真正决定「能不能攻击」的是
            //    brain 里的 ATTACK_TARGET —— 只要它每刻都是空的，
            //    远程与近战就都找不到可打的目标。
            //    注意**不要**调 setTarget：AbstractPiglin 覆写了 getTarget() 读脑里的
            //    ATTACK_TARGET，而 Mob.setTarget 只写自己的字段，对猪灵系是纯空操作。
            brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
            if (clearAnger) {
                brain.eraseMemory(MemoryModuleType.ANGRY_AT);
                brain.eraseMemory(MemoryModuleType.HURT_BY);
            }

            // ③ 导航按固定周期重设 —— isDone() 每次都重算路径，卡墙时会逐刻全量寻路
            if (piglin.tickCount % LURE_NAV_INTERVAL == 0 && piglin.getNavigation().isDone()) {
                piglin.getNavigation().moveTo(pos.x, pos.y, pos.z, LURE_SPEED);
            }
        }

        // 偶尔响一声，让玩家听得出来「它们被吸引过来了」
        if (level.getGameTime() % 40L == 0L) {
            level.playSound(null, pos.x, pos.y, pos.z,
                    SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.NEUTRAL,
                    LURE_SOUND_VOLUME, 0.8F);
        }
    }

    // ===== 三、被捡起后的特殊交易 =====

    /**
     * 猪灵捡到金球之后的处理。
     *
     * <p>只有<b>普通猪灵</b>（非蛮兵、非幼小）会交易；蛮兵只会拿着球不办事。</p>
     *
     * @param ball 被捡起的那颗金球实体
     */
    public static void onPiglinPickedUpGoldBall(Piglin piglin, @Nullable BallProjectile ball) {
        if (piglin.isBaby()) {
            return; // 幼小猪灵不交易
        }

        boolean hurtByThisBall = Boolean.TRUE.equals(
                piglin.getExistingDataOrNull(ModAttachments.GOLD_BALL_HURT.get()));
        if (!hurtByThisBall) {
            // 没被这颗金球打过 → 特殊交易
            dropTradeGoods(piglin, rollTradeCount(piglin.getRandom().nextFloat()));
        } else {
            // 被它打过 → 普通的单次回礼
            dropTradeGoods(piglin, 1);
        }

        // 交易完把标记清掉，免得影响下一次
        piglin.setData(ModAttachments.GOLD_BALL_HURT.get(), false);

        // 触发「金光闪闪」成就 —— 记在投掷者头上（球上留着 owner）
        awardGoldShiny(ball);

        // 「对不起……你在听吗？」：金球砸中猪灵、并且**用同一只猪灵**触发了交易。
        // 到这里就说明两个条件都成立 —— 这次调用本身就是「同一只猪灵完成交易」，
        // 而球上留着 owner 就能确认「是玩家用金球砸出来的那颗」。
        if (ball != null && ball.getOwner() instanceof Player thrower) {
            ModAdvancements.award(thrower, ModAdvancements.PIGLIN_TRADE_WITH_BALL);
        }
    }

    /**
     * 触发原版的「金光闪闪」成就。
     *
     * <p>这个成就（{@code minecraft:nether/distract_piglin}）原版的触发条件是
     * 「投掷物被成年猪灵捡起，且道具属于 {@code #minecraft:piglin_loved}」。
     * 我们的球是自定义投射物、被自定义逻辑捡起，原版那条事件链不会走到，
     * 所以这里手动解锁。</p>
     *
     * <p><b>注意</b>：手动 {@code award} 不会替我们校验条件，所以判据得自己保证 ——
     * 调用点已经限定在「非幼小的猪灵捡到金球」这一刻，与原版的场景一致
     * （金球本身也加进了 {@code #minecraft:piglin_loved} 标签）。</p>
     */
    /**
     * 触发原版的「金光闪闪」成就。
     *
     * @param ball 被捡起的那颗金球实体；<b>掉落物形态的球没有实体</b>，此时传 {@code null}，
     *             成就就不发 —— 那颗球上没留投掷者信息，本来就无从归属。
     */
    private static void awardGoldShiny(@Nullable BallProjectile ball) {
        if (ball == null) {
            return;
        }
        if (!(ball.getOwner() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        AdvancementHolder holder = level.getServer().getAdvancements()
                .get(Identifier.withDefaultNamespace("nether/distract_piglin"));
        if (holder != null) {
            player.getAdvancements().award(holder, "distract_piglin");
        }
    }

    /**
     * 按作者给出的权重抽这次特殊交易给几次。
     *
     * <p>40% → 5 次、20% → 4 次、10% → 3 次、8% → 2 次、5% → 1 次。</p>
     */
    public static int rollTradeCount(float roll) {
        int total = 0;
        for (int weight : TRADE_WEIGHTS) {
            total += weight;
        }
        float scaled = roll * total;
        for (int i = 0; i < TRADE_WEIGHTS.length; i++) {
            scaled -= TRADE_WEIGHTS[i];
            if (scaled < 0.0F) {
                return TRADE_WEIGHTS.length - i; // 5 / 4 / 3 / 2 / 1
            }
        }
        return 1;
    }

    /** 把 {@code count} 次交易产物丢在猪灵脚边 */
    private static void dropTradeGoods(AbstractPiglin piglin, int count) {
        if (!(piglin.level() instanceof ServerLevel level)) {
            return;
        }
        for (int i = 0; i < count; i++) {
            ItemStack goods = TRADE_POOL.get(level.getRandom().nextInt(TRADE_POOL.size())).copy();
            piglin.spawnAtLocation(level, goods, 0.3F);
        }
        level.playSound(null, piglin.getX(), piglin.getY(), piglin.getZ(),
                SoundEvents.PIGLIN_ADMIRING_ITEM, SoundSource.NEUTRAL, 1.0F, 1.0F);
    }

    /** 金球被猪灵拿在手上时，它算「拿着喜爱之物」—— 别让它继续战斗 */
    public static void holdGoldBallPeacefully(Piglin piglin) {
        forgetAnger(piglin);
        piglin.setItemSlot(EquipmentSlot.MAINHAND, piglin.getMainHandItem());
    }
}
