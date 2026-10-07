package com.mcmodworkspace.moreballs;

import net.minecraft.util.Mth;

/**
 * 「滚动」物理 —— 坚固球弹到快停时，垂直速度归零、改为贴地滚动的计算模型。
 *
 * <h2>为什么要单独建模</h2>
 * 球每次反弹都会丢掉一部分速度（弹射），弹跳高度随之递减。当垂直分量已经小到
 * 差不多只剩贴地滑行时，继续做抛物线既难看也没意义 —— 直接把它拍在地上滚，
 * 手感更自然，视觉上也更符合「球在滚」的直觉。
 *
 * <h2>何时转入滚动：按「还能弹多高」算，而不是一个死板的绝对速度</h2>
 * <p>同样的垂直速度，在重力大的球上只能弹很低、在重力小的球上却能弹很高，
 * 所以固定速度阈值会让轻球显得「一跳就滚」、重球显得「弹不完」。
 * 这里换算成高度再判断：</p>
 *
 * <pre>
 *   这一跳的顶点高度 = vy² / (2 × g)
 *   低于 {@link #MIN_APEX_HEIGHT} 格 → 不再弹，转为贴地滚动
 * </pre>
 *
 * <p>换算成各重量对应的速度门槛（均为 0.1 格高度）：</p>
 * <table border="1">
 *   <caption>转入滚动的速度门槛</caption>
 *   <tr><th>重量</th><th>重力 g</th><th>vy 门槛</th></tr>
 *   <tr><td>0</td><td>0（直线飞行）</td><td>永不转滚</td></tr>
 *   <tr><td>4（雪球基准）</td><td>0.030</td><td>0.078</td></tr>
 *   <tr><td>5（木球）</td><td>0.053</td><td>0.103</td></tr>
 *   <tr><td>10</td><td>0.893</td><td>0.423</td></tr>
 * </table>
 *
 * <p>于是「随手往脚边一丢」也会老老实实弹一下（垂直速度约 0.12，换算高度 0.14 格 &gt; 0.1），
 * 而不是一落地就滑走。</p>
 *
 * <h2>阻力公式（滚动阶段）</h2>
 * <p>物理依据是经典的滑动摩擦 {@code f = μN}：摩擦系数 μ 来自<b>下方方块</b>，
 * 正压力 N 来自球自身的<b>重量</b>。落到代码里就是：</p>
 *
 * <pre>
 *   光滑度 smoothness = clamp((μ - 0.6) / 0.4, 0, 1)      // 石头 = 0，冰 ≈ 0.95
 *   重量系数 weightF  = clamp(重量 / 4, 0.3, 2.5)          // 雪球基准 = 1
 *   每 tick 损耗 loss = (BASE_LOSS - SMOOTH_BONUS × smoothness) × weightF
 *   速度保留         = 1 - clamp(loss, 0.01, 0.6)
 * </pre>
 *
 * <p>代入几个典型场景（基准重量 4、水平初速 1 格/刻）：</p>
 * <table border="1">
 *   <caption>方块与重量的滚动表现</caption>
 *   <tr><th>方块</th><th>摩擦 μ</th><th>重量</th><th>速度保留</th><th>大致滚动距离</th></tr>
 *   <tr><td>石头</td><td>0.6</td><td>4</td><td>0.90</td><td>约 10 格</td></tr>
 *   <tr><td>石头</td><td>0.6</td><td>10</td><td>0.75</td><td>约 4 格</td></tr>
 *   <tr><td>石头</td><td>0.6</td><td>0</td><td>0.97</td><td>约 33 格</td></tr>
 *   <tr><td>冰</td><td>0.98</td><td>4</td><td>0.982</td><td>约 55 格</td></tr>
 *   <tr><td>蓝冰</td><td>0.989</td><td>4</td><td>0.984</td><td>约 62 格</td></tr>
 * </table>
 */
public final class BallRoll {

    private BallRoll() {
    }

    /**
     * 这一跳的顶点高度低于该值（格）就放弃弹跳，转为贴地滚动。
     *
     * <p>取 0.1 格：比一个台阶还矮，玩家肉眼已经看不出是「跳」了，
     * 但又不至于让轻抛的球一落地就滑走。</p>
     */
    public static final double MIN_APEX_HEIGHT = 0.1D;

    /** 「普通方块」的摩擦基准 —— 原版 {@code Block.getFriction()} 的默认值 */
    public static final float NORMAL_FRICTION = 0.6F;

    /** 普通方块 + 基准重量下，每 tick 损失的水平速度比例 */
    public static final double BASE_LOSS = 0.10D;

    /** 完全光滑的表面（μ→1）能把损耗压掉多少 */
    public static final double SMOOTH_BONUS = 0.086D;

    /** 重量系数的取值范围（雪球基准 4 → 1.0） */
    public static final double MIN_WEIGHT_FACTOR = 0.3D;
    public static final double MAX_WEIGHT_FACTOR = 2.5D;

    /** 单 tick 损耗的夹取范围，保证既有最小阻力又不会一 tick 刹停 */
    private static final double MIN_LOSS = 0.01D;
    private static final double MAX_LOSS = 0.6D;

    /**
     * 反弹后该不该转为滚动。
     *
     * @param vy      反弹之后的垂直速度
     * @param gravity 该球当前的重力（用正常重力，不要用滚动状态下的 0）
     * @return true 表示这一跳已经矮到不值得再弹
     */
    public static boolean shouldRoll(double vy, double gravity) {
        if (gravity <= 0.0D) {
            // 无重力（重量 0）的球本来就走直线，没有「弹不起来」这回事
            return false;
        }
        double apex = (vy * vy) / (2.0D * gravity);
        return apex < MIN_APEX_HEIGHT;
    }

    /**
     * 滚动时每 tick 的水平速度保留系数。
     *
     * @param weight        球的重量 0–10
     * @param blockFriction 下方方块的摩擦（{@code Block.getFriction()}）
     * @return 保留系数，恒在 (0, 1) 区间内
     */
    public static double keepFactor(int weight, float blockFriction) {
        double smoothness = Mth.clamp(
                (blockFriction - NORMAL_FRICTION) / (1.0D - NORMAL_FRICTION), 0.0D, 1.0D);
        double weightFactor = Mth.clamp(
                weight / (double) BallWeight.BASELINE, MIN_WEIGHT_FACTOR, MAX_WEIGHT_FACTOR);
        double loss = (BASE_LOSS - SMOOTH_BONUS * smoothness) * weightFactor;
        return 1.0D - Mth.clamp(loss, MIN_LOSS, MAX_LOSS);
    }
}
