package com.mcmodworkspace.moreballs;

import net.minecraft.util.Mth;

/**
 * 「重量」概念 —— 决定球出手后<b>下坠得多快</b>。
 *
 * <p>重量取 0–10 的整数，影响两条路径（右键投掷、弩发射）的抛物线，
 * 落实方式是覆写投射物的默认重力（{@code getDefaultGravity}）——
 * 空气阻力保持原版 0.99 不变，所以只改「往下掉多快」，不改「往前飞多快」。</p>
 *
 * <h2>三个锚点（作者给出定）</h2>
 * <ul>
 *   <li><b>重量 4 = 基准</b>：等同原版雪球（手扔重力 0.03）</li>
 *   <li><b>重量 0</b>：重力 0 —— 出手后直线前进，完全不坠</li>
 *   <li><b>重量 10</b>：平坦地形水平手扔射程 <b>2 格</b></li>
 * </ul>
 *
 * <h2>基准随发射方式切换</h2>
 * <p>作者要求求「用弩发射的抛物线等同箭矢」，所以弩射的基准取箭的重力 0.05
 * （手扔的基准仍是雪球的 0.03）。重量只在基准之上做缩放，所以同一个重量
 * 在两种发射方式下的手感关系保持一致。</p>
 *
 * <h2>为什么 4→10 用几何插值</h2>
 * <p>从基准到「射程 2 格」需要重力放大近 30 倍。若在 6 个刻度上线性摊开，
 * 中间档位会挤成一团（重量 5 直接从 13.8 格掉到 5.4 格，手感断裂）；
 * 改为按比例增长后，每一格的落差是均匀的，射程从 13.8 平滑压到 2 格。</p>
 */
public final class BallWeight {

    private BallWeight() {
    }

    /** 重量取值区间（含端点） */
    public static final int MIN = 0;
    public static final int MAX = 10;

    /** 基准重量：原版雪球 */
    public static final int BASELINE = 4;

    /** 手扔基准重力 —— 原版雪球（ThrowableProjectile 的默认值） */
    public static final double SNOWBALL_GRAVITY = 0.03;

    /** 弩射基准重力 —— 原版箭（AbstractArrow 的默认值） */
    public static final double ARROW_GRAVITY = 0.05;

    /**
     * 重量 10 相对基准的重力倍数。
     *
     * <p>由「平坦地形水平手扔射程 = 2 格」反解得到：按 MC 的 tick 顺序
     * （先减重力、再乘阻力、再加位移，落地取射线交点）做数值模拟，
     * 起点高度 1.5、出手初速 1.5、阻力 0.99，解出重力 ≈ 0.8928，
     * 除以基准 0.03 即 29.76。</p>
     */
    public static final double HEAVY_MULTIPLIER = 29.76;

    /**
     * 重量 → 重力倍数（相对当前发射方式的基准）。
     *
     * @return 0 → 0（直线）；4 → 1（基准）；10 → {@link #HEAVY_MULTIPLIER}
     */
    public static double gravityFactor(int weight) {
        int w = Mth.clamp(weight, MIN, MAX);
        if (w == BASELINE) {
            return 1.0;
        }
        if (w < BASELINE) {
            return w / (double) BASELINE;
        }
        return Math.pow(HEAVY_MULTIPLIER, (w - BASELINE) / (double) (MAX - BASELINE));
    }

    /**
     * 实际重力值。
     *
     * @param weight       重量（0–10，超出范围会被夹到边界）
     * @param fromCrossbow true = 弩发射（基准看箭矢），false = 右键手扔（基准看雪球）
     */
    public static double gravityFor(int weight, boolean fromCrossbow) {
        return (fromCrossbow ? ARROW_GRAVITY : SNOWBALL_GRAVITY) * gravityFactor(weight);
    }
}
