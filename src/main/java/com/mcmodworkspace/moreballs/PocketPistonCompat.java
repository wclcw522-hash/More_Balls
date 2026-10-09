package com.mcmodworkspace.moreballs;

import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

/**
 * 「奇异饰品 · 袖珍活塞」联动的<b>预留接口</b>。
 *
 * <h2>当前状态：只有判定，没有启用</h2>
 * <p>作者（2026-10-09）决定<b>先留接口、暂不实现</b> —— 因为奇异饰品目前只更新到 26.1.2，
 * 而本条版本线是 26.2，等它跟进之后再把效果接上。</p>
 *
 * <p>所以 {@link #isFullChargeLocked(Player)} <b>恒返回 false</b>，
 * 游戏里没有任何行为变化。接上去的时候只要改这一个方法即可。</p>
 *
 * <h2>将来要实现的效果（作者指定）</h2>
 * <ul>
 *   <li><b>锁定蓄力等级为最大</b>：戴着袖珍活塞时，投掷永远按 {@code chargeLevels} 满级出手</li>
 *   <li><b>锁定右键蓄力功能</b>：不再走 {@link BallCharge} 的时间曲线</li>
 *   <li><b>解锁按住右键连续投掷</b>：按住右键时按<b>原版雪球那样的冷却</b>连续出手
 *       （即复用 {@code BallProfile.cooldownTicks()} 那条冷却队列，不额外定频率）</li>
 * </ul>
 *
 * <h2>接上去时要改的地方（共三处）</h2>
 * <ol>
 *   <li>{@link BallThrowHandler#onStopUsing} —— 取 level 时先问 {@link #isFullChargeLocked}，
 *       为真就直接用满级，不再调 {@code BallCharge.levelAt}</li>
 *   <li>{@link BallThrowHandler#onStartUsing} —— 戴着时把 {@code USE_DURATION} 缩到最短，
 *       让「按住」不再表示蓄力</li>
 *   <li>{@code BallThrowHandler} 增加一个 {@code PlayerTickEvent} 监听 —— 按住右键且戴着时，
 *       在冷却结束后继续投掷</li>
 * </ol>
 *
 * <h2>不硬依赖</h2>
 * <p>整个模组不依赖 Curios、也不依赖奇异饰品：没装时本类每个方法都安全返回。
 * 判定走的是 Curios 的饰品查询 API，不去引用奇异饰品的任何类 ——
 * 那样才能做到「它更新到 26.2 也不用改我们的代码」。</p>
 */
public final class PocketPistonCompat {

    private PocketPistonCompat() {
    }

    /** 奇异饰品的 mod id */
    public static final String ARTIFACTS_ID = "artifacts";

    /**
     * 袖珍活塞的注册名。
     *
     * <p>按项目规则本该优先用标签而不是硬编码 id，但奇异饰品没有为「单件饰品」提供标签
     * （只有 {@code #artifacts:artifacts} 这种全量集合），所以这里只能写 id。
     * 用 {@link #isLoaded()} 兜底，它改名也只是联动失效、不会崩。</p>
     */
    public static final String POCKET_PISTON_ID = "artifacts:pocket_piston";

    /** 这个存档里奇异的饰品是否在场（同时要求 Curios） */
    public static boolean isLoaded() {
        return ModList.get().isLoaded(ARTIFACTS_ID) && ModList.get().isLoaded("curios");
    }

    /** 玩家此刻有没有戴着袖珍活塞 */
    public static boolean wearing(Player player) {
        if (player == null || !isLoaded()) {
            return false;
        }
        return PocketPistonAccess.wears(player, POCKET_PISTON_ID);
    }

    /**
     * 是否要「锁定蓄力等级为最大」。
     *
     * <p><b>⚠️ 当前恒返回 false —— 接口已留、效果未启用。</b>
     * 等奇异饰品更新到 26.2 之后，把这里改成 {@code return wearing(player);} 即可，
     * 其余三处接点照 {@link PocketPistonCompat 类注释} 里列的位置补上。</p>
     */
    public static boolean isFullChargeLocked(Player player) {
        return false;
    }
}