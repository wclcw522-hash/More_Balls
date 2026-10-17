package com.mcmodworkspace.moreballs;

import net.minecraft.world.item.Item;

/**
 * <b>「带饰品能力的收纳袋」的工厂</b> —— 单独一个类，是为了把 Curios 的类引用隔离出去。
 *
 * <h2>为什么要这么绕</h2>
 * <p>装不装 Curios，收纳袋要注册成不同的类：装了用 {@link BallPouchCurioItem}
 * （它 {@code implements ICurioItem}），没装用普通的 {@link BallPouchItem}。
 * 代码里自然想写成三元判断：</p>
 *
 * <pre>{@code
 * prop -> BallPouchCurios.isLoaded()
 *         ? new BallPouchCurioItem(prop.stacksTo(1), tier)   // ← 就是这一句害的
 *         : new BallPouchItem(prop.stacksTo(1), tier)
 * }</pre>
 *
 * <p><b>但那样会崩。</b>作者 2026-10-17 关掉其它 mod 后游戏起不来，crash-report 是：</p>
 *
 * <pre>
 * java.lang.NoClassDefFoundError: top/theillusivec4/curios/api/type/capability/ICurioItem
 *     at com.mcmodworkspace.moreballs.MoreBalls.&lt;init&gt;(MoreBalls.java:24)
 * </pre>
 *
 * <p>原因不是「运行时真的走了那条分支」，而是 <b>JVM 的字节码验证器</b>：
 * {@code new BallPouchCurioItem(...)} 的结果要赋给 {@code Item}，验证器就必须确认
 * 「{@code BallPouchCurioItem} 是 {@code Item} 的子类」—— 这一确认就要**加载那个类**，
 * 而它 {@code implements ICurioItem}，Curios 不在 ⇒ {@code NoClassDefFoundError}。
 * **运行时的三元判断在这种「类型解析」面前完全无效。**</p>
 *
 * <h2>所以</h2>
 * <p>{@code ModItems} 里只调本工厂的 {@link #create}，而它的**返回类型是 {@code Item}** ——
 * 验证器看这里只涉及 {@code Item}，不会去碰 {@link BallPouchCurioItem}。
 * 真正的 {@code new} 藏在<b>本类的方法体</b>里，而本类只有在
 * {@code BallPouchCurios.isLoaded()} 为真时才被调用 ⇒ 没装 Curios 时它永远不会被加载。</p>
 */
final class BallPouchCurioFactory {

    private BallPouchCurioFactory() {
    }

    /**
     * 造一件「带饰品能力」的收纳袋。
     *
     * <p>⚠️ 这个方法**只能**在 {@code BallPouchCurios.isLoaded()} 为真时调用 ——
     * 否则本类一被加载就要求 Curios 在场。</p>
     *
     * @param props 物品属性（已经 {@code stacksTo(1)} 处理过）
     * @param tier  收纳袋等级
     * @return 带饰品能力的收纳袋
     */
    static Item create(Item.Properties props, BallPouchTier tier) {
        return new BallPouchCurioItem(props, tier);
    }
}
