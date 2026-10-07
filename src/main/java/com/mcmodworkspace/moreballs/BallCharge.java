package com.mcmodworkspace.moreballs;

import net.minecraft.world.entity.LivingEntity;

/**
 * 「蓄力」概念 —— 手投掷球时按住右键不放即可蓄力，蓄得越久初速越高。
 *
 * <h2>时间曲线（作者指定）</h2>
 * <ul>
 *   <li>第 1 级需要 <b>1 秒</b>（20 刻）</li>
 *   <li>之后每一级的所需时间在上一级基础上 <b>+20%</b>：
 *       1s → 1.2s → 1.44s → 1.728s …</li>
 * </ul>
 * <p>累计到各级的时刻为 20 / 44 / 73 / 107 … 刻。</p>
 *
 * <h2>速度收益</h2>
 * <p>每蓄力一级，出手初速度 <b>+10%</b>。由于伤害按速度等比缩放
 * （见 {@link BallThrowHandler}），蓄力同时抬高射程与伤害。</p>
 */
public final class BallCharge {

    private BallCharge() {
    }

    /** 第一级蓄力所需刻数：1 秒 */
    public static final int BASE_TICKS = 20;

    /** 每一级所需时间相对上一级的增长比例（+20%） */
    public static final double TIME_GROWTH = 1.2D;

    /** 每级蓄力带来的初速度增幅（+10%） */
    public static final float VELOCITY_PER_LEVEL = 0.10F;

    /** 使用状态的总时长：设得足够长，实际何时出手由玩家松开右键决定 */
    public static final int USE_DURATION = 72000;

    /** 蓄到第 {@code level} 级所需的累计刻数（level 为 0 时返回 0） */
    public static int ticksForLevel(int level) {
        double total = 0.0D;
        double need = BASE_TICKS;
        for (int i = 0; i < level; i++) {
            total += need;
            need *= TIME_GROWTH;
        }
        return (int) Math.round(total);
    }

    /**
     * 已经按住多少刻 —— 蓄力等级的唯一合法计时口径。
     *
     * <p><b>这里不能用 {@code LivingEntity#getTicksUsingItem()}</b>：它的实现是
     * {@code 物品自己的 getUseDuration() − 剩余刻数}，而球类都是普通物品
     * （默认使用时长为 0），我们在 Start 事件里把使用时长改成了 {@link #USE_DURATION}，
     * 于是那个减法会得到负数、蓄力等级永远卡在 0 级。</p>
     *
     * <p>改为直接按「我方设定的总时长 − 剩余刻数」计算，两端一致。</p>
     */
    public static int usedTicks(LivingEntity entity) {
        return Math.max(0, USE_DURATION - entity.getUseItemRemainingTicks());
    }

    /**
     * 已按住多少刻，对应已经蓄到第几级。
     *
     * @param ticksUsed 已使用刻数（应当来自 {@link #usedTicks(LivingEntity)}）
     * @param maxLevel  该球允许的最大蓄力等级
     * @return 0 表示还没蓄满第一级
     */
    public static int levelAt(int ticksUsed, int maxLevel) {
        int level = 0;
        for (int i = 1; i <= maxLevel; i++) {
            if (ticksUsed >= ticksForLevel(i)) {
                level = i;
            } else {
                break;
            }
        }
        return level;
    }

    /** 蓄力等级带来的初速度倍率（1.0 = 无蓄力） */
    public static float velocityMultiplier(int level) {
        return 1.0F + VELOCITY_PER_LEVEL * Math.max(0, level);
    }
}
