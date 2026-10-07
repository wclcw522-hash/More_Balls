package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.BallProjectile;
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
     * <p>「直到金球被捡起为止不会因为任何原因转移目标」——
     * 靠的就是每刻覆写它们的攻击目标与寻路终点。</p>
     */
    public static void lure(ServerLevel level, Vec3 pos) {
        List<AbstractPiglin> piglins = level.getEntitiesOfClass(AbstractPiglin.class,
                new net.minecraft.world.phys.AABB(pos, pos).inflate(LURE_RADIUS),
                piglin -> piglin.isAlive() && !piglin.isBaby());
        if (piglins.isEmpty()) {
            return;
        }

        for (AbstractPiglin piglin : piglins) {
            // 不让它因为别的事分心 —— 作者的要求是「任何原因都不转移目标」。
            // eraseMemory 对没有的记忆也无害，不必先判断。
            piglin.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            piglin.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            piglin.setTarget(null);

            if (piglin.getNavigation().isDone()) {
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
