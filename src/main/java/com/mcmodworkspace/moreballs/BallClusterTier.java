package com.mcmodworkspace.moreballs;

import net.minecraft.world.item.ItemStack;

/**
 * 「球堆」的耐久档位 —— 作者 2026-10-14 指定的划分。
 *
 * <h2>为什么要分档</h2>
 * <p>球的耐久记在 {@link ModComponents#TOUGHNESS_LEFT} 组件上，而 MC 的堆叠判定要求
 * <b>组件完全一致</b>（{@link ModComponents} 的类注释里写了这条硬校验）。于是「挨过 3 次」
 * 和「挨过 4 次」的球必然分属两堆 —— 打出去一组、收回来满地都是各自占一格的球。</p>
 *
 * <p>分档就是为了把这种<b>没有意义的尾数差异</b>归到一起，让它们能装进同一个球堆。</p>
 *
 * <h2>⚠️ 档位是「算出来的」，不是存下来的</h2>
 * <p>本枚举<b>不</b>作为字段写进任何组件 —— 需要档位时拿球的当前耐久现算。
 * 这样就不存在「改了耐久忘了改档位」这种失同步的状态。
 * （球堆图标角落那个数量也是同一个思路，见 {@code BallClusterDecorator}。）</p>
 *
 * <h2>五档划分（作者指定）</h2>
 * <ul>
 *   <li>{@link #PRISTINE} —— <b>满耐久</b>。作者明确：<b>满耐久的球不进球堆</b>，
 *       它本来就能和同批新球叠成一堆，不需要壳</li>
 *   <li>{@link #FLAWED} —— <b>80% 以上、不满</b>。作者注明「就是能切球的」——
 *       正好与切割门槛（{@code BallItem.canBeCut} 的 {@code CUT_TOUGHNESS_RATIO = 0.80}）对齐，
 *       玩家看一眼就知道哪堆还能上切石机</li>
 *   <li>{@link #DAMAGED} —— <b>50% ~ 80%</b></li>
 *   <li>{@link #BROKEN} —— <b>20% ~ 50%</b></li>
 *   <li>{@link #CRUMBLING} —— <b>20% 以下</b>（快碎了）</li>
 * </ul>
 *
 * <p>边界按「左闭右开」处理：正好 80% 算 {@link #FLAWED}（能切），
 * 正好 20% 算 {@link #BROKEN}。这样「能不能切」这条判据与 {@code canBeCut} 完全一致。</p>
 */
public enum BallClusterTier {

    /** 满耐久 —— **不进球堆** */
    PRISTINE("完整", 1.0D),

    /** 80% 以上、不满 —— 还能上切石机 */
    FLAWED("瑕疵", 0.80D),

    /** 50% ~ 80% */
    DAMAGED("破损", 0.50D),

    /** 20% ~ 50% */
    BROKEN("残缺", 0.20D),

    /** 20% 以下 */
    CRUMBLING("将碎", 0.0D);

    /** 中文短名 —— 拼球堆显示名用（「瑕疵的钻石球」这种） */
    private final String label;

    /**
     * 这一档的<b>下界</b>比例。
     *
     * <p>判档就是「找第一个 {@code ratio >= lowerRatio} 的档」，所以列表顺序
     * 必须<b>从高到低</b>，{@link #values()} 的顺序天然满足。</p>
     */
    private final double lowerRatio;

    BallClusterTier(String label, double lowerRatio) {
        this.label = label;
        this.lowerRatio = lowerRatio;
    }

    /** 中文短名（「瑕疵」「破损」……） */
    public String label() {
        return this.label;
    }

    /**
     * 这一档<b>要不要</b>进球堆。
     *
     * <p>只有满耐久那档不用 —— 作者指定。其余四档都要壳。</p>
     */
    public boolean isClustered() {
        return this != PRISTINE;
    }

    /**
     * 按「剩余 / 上限」的比例定档。
     *
     * @param ratio 0.0 ~ 1.0（调用方负责夹紧，本方法也兜一层）
     * @return 命中的档位；比例 ≤ 0 时给 {@link #CRUMBLING}
     */
    public static BallClusterTier fromRatio(double ratio) {
        double r = Math.max(0.0D, Math.min(1.0D, ratio));
        for (BallClusterTier tier : values()) {
            if (r >= tier.lowerRatio) {
                return tier;
            }
        }
        return CRUMBLING;
    }

    /**
     * 这颗球现在属于哪一档。
     *
     * <p>上限取 {@code BallBehavior.profileFor(stack).toughness()} ——
     * 与 {@code BallItem.maxToughness} 同一口径（那里是 private，这里自己算一遍，
     * 免得为了读一个数去放宽可见性）。</p>
     *
     * <p>两种边界情况直接当满耐久：</p>
     * <ul>
     *   <li><b>没有耐久概念的球</b>（不坚固 = 0、永久坚固 = -1）—— 它们本来就不磨损，
     *       谈不上分档</li>
     *   <li><b>没带 {@code TOUGHNESS_LEFT} 组件</b> —— 按本模组约定就代表满耐久
     *       （见 {@link ModComponents} 第 33 行那条约定）</li>
     * </ul>
     */
    public static BallClusterTier of(ItemStack stack) {
        int max = Math.max(0, BallBehavior.profileFor(stack).toughness());
        if (max <= 0) {
            return PRISTINE;
        }
        Integer left = stack.get(ModComponents.TOUGHNESS_LEFT.get());
        if (left == null) {
            return PRISTINE;   // 没带组件 = 满耐久
        }
        return fromRatio(left / (double) max);
    }

    /**
     * 这一档的球堆，能不能接收这颗球。
     *
     * <p>即「档位相同」。球种另算（那是 {@code BallClusterContents} 的事）。</p>
     */
    public boolean accepts(ItemStack stack) {
        return of(stack) == this;
    }
}
