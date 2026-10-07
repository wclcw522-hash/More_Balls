package com.mcmodworkspace.moreballs;

/**
 * 魔丸收纳袋的等级。
 *
 * <p>作者指定的成长曲线：<b>18 格起步，每升一级 +3 弹药格 +3 转运格，共 7 级</b>。
 * 两行格子各有分工：</p>
 * <ul>
 *   <li><b>弹药格</b> —— 只放<b>满耐久</b>的球（全新的、没弹过的）</li>
 *   <li><b>转运格</b> —— 只放<b>用过</b>的球（耐久已消耗的）</li>
 * </ul>
 *
 * <p>每行 9 格，所以满级时是 3 行弹药 + 3 行转运。</p>
 *
 * <h2>升级路线</h2>
 * <p>皮革 → 铁 → 金 → 绿宝石 → 钻石 → 黑曜石 → 下界合金。
 * 前六级在合成台升级，<b>末级走锻造台</b>（下界合金锭 + 黑曜石袋 + 锻造模板）。</p>
 */
public enum BallPouchTier {

    /** 1 级：皮革袋 —— 9 + 9 = 18 格 */
    LEATHER(9, "ball_pouch"),

    /** 2 级：铁袋 */
    IRON(12, "iron_ball_pouch"),

    /** 3 级：金袋 */
    GOLD(15, "gold_ball_pouch"),

    /** 4 级：绿宝石袋 */
    EMERALD(18, "emerald_ball_pouch"),

    /** 5 级：钻石袋 */
    DIAMOND(21, "diamond_ball_pouch"),

    /** 6 级：黑曜石袋 —— 也是升下界合金用的材料 */
    OBSIDIAN(24, "obsidian_ball_pouch"),

    /** 7 级：下界合金袋（满级）—— 27 + 27 = 54 格 */
    NETHERITE(27, "netherite_ball_pouch");

    /** 每级的槽位数（弹药格与转运格<b>同数</b>） */
    private final int slotsPerRow;

    /**
     * 这一级的<b>物品 id</b>（同时也就是物品注册名、模型名、纹理名）。
     *
     * <p>注意这里必须是完整的物品名（{@code ball_pouch} / {@code iron_ball_pouch} …），
     * 不是「leather / iron」那种材质短名 —— 注册时直接拿它当 id，
     * 用短名会注册出 {@code more_balls:leather} 这种物品，
     * 和按 {@code ball_pouch} 命名的那一整套资源对不上，
     * 结果就是<b>没模型、没贴图、没名字，配方也解析失败</b>（踩过）。</p>
     */
    private final String id;

    BallPouchTier(int slotsPerRow, String id) {
        this.slotsPerRow = slotsPerRow;
        this.id = id;
    }

    /** 弹药格数量 */
    public int ammoSlots() {
        return this.slotsPerRow;
    }

    /** 转运格数量 */
    public int transitSlots() {
        return this.slotsPerRow;
    }

    /** 总槽位数 */
    public int totalSlots() {
        return this.slotsPerRow * 2;
    }

    /** 资源名后缀 */
    public String id() {
        return this.id;
    }

    /** 是不是满级（满级之后没有下一级了） */
    public boolean isMax() {
        return this == NETHERITE;
    }

    /** 下一级；已满级返回自身 */
    public BallPouchTier next() {
        return isMax() ? this : values()[ordinal() + 1];
    }

    /**
     * 这一级是不是靠<b>锻造台</b>升上去的。
     *
     * <p>只有下界合金（末级）走锻造台 —— 其余都在合成台。</p>
     */
    public boolean viaSmithing() {
        return this == NETHERITE;
    }
}
