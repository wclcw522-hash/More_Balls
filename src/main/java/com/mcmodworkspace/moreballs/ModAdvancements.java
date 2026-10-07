package com.mcmodworkspace.moreballs;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 本模组成就的统一发放入口。
 *
 * <h2>为什么统一走这里</h2>
 * <p>这些成就的触发条件大多没法用原版 trigger 表达（「用热量系统把矿物熔炼了」、
 * 「被自己的球砸死」、「多重射击打出球」……），所以 json 那边用的是
 * {@code minecraft:impossible}，真正解锁靠代码里调 {@link #award}。</p>
 *
 * <p>集中在一处的另一个好处是<b>不会漏</b>：所有发放点都长得一样，
 * 加成就时只要在合适的位置补一行。原版只允许「每个 criterion 一次」，
 * 重复调用是无害的（已经拿到的成就不会再触发一遍提示）。</p>
 *
 * <p>注意用 {@link ServerPlayer#getAdvancements()}<b>服务端</b>那一份 ——
 * 客户端自己 award 不算数，而且必须在服务端。</p>
 */
public final class ModAdvancements {

    private ModAdvancements() {
    }

    /** 成就 id 在本模组命名空间下的前缀 */
    private static final String NAMESPACE = MoreBalls.MOD_ID;

    // ===== 成就 id（与 data/more_balls/advancement/<id>.json 一一对应）=====

    public static final String ROOT = "root";
    public static final String RUBBLE = "rubble";
    public static final String IRON_BALL_FROM_BLOCKS = "iron_ball_from_blocks";
    public static final String PIGLIN_TRADE_WITH_BALL = "piglin_trade_with_ball";
    public static final String HEAT_SMELT = "heat_smelt";
    public static final String SNOWBALL_HURT = "snowball_hurt";
    public static final String CROSSBOW_AMMO = "crossbow_ammo";
    public static final String LIGHTNESS = "lightness";
    public static final String AERODYNAMIC_RETURN = "aerodynamic_return";
    public static final String MULTISHOT = "multishot";
    public static final String KILLED_BY_OWN_BALL = "killed_by_own_ball";
    public static final String WISDOM = "wisdom";
    public static final String HURT_BY_MOB_BALL = "hurt_by_mob_ball";
    public static final String COMBO_BALL = "combo_ball";

    /**
     * 给玩家解锁一个成就。
     *
     * <p>找不到成就定义时静默返回 —— 成就 json 写错不该让游戏崩，
     * 而且数据包重载期间短暂查不到是正常的。</p>
     *
     * @param player 必须是 {@link ServerPlayer}；传普通 {@code Player} 或客户端玩家时直接跳过
     * @param id     {@link #ROOT} 这类常量，不带命名空间
     */
    public static void award(Player player, String id) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (!(serverPlayer.level() instanceof ServerLevel level)) {
            return;
        }
        AdvancementHolder holder = level.getServer().getAdvancements()
                .get(Identifier.fromNamespaceAndPath(NAMESPACE, id));
        if (holder == null) {
            // 成就没注册上（json 名字写错 / 数据包没加载）—— 不抛异常，只记一条日志
            MoreBalls.LOGGER.debug("[ball] 找不到成就 {}:{}，跳过发放", NAMESPACE, id);
            return;
        }
        // criterion 名统一叫 unlocked（见各 json）。原版重复发放是幂等的。
        serverPlayer.getAdvancements().award(holder, "unlocked");
    }
}
