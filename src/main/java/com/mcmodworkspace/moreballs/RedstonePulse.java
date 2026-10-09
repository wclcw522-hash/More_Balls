package com.mcmodworkspace.moreballs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 【脉冲】—— 红石球的专属词条（作者 2026-10-10 指定的规格）。
 *
 * <h2>效果</h2>
 * <p>红石球<b>命中</b>时（撞实体、撞方块都算），以<b>命中点为球心</b>、半径
 * {@link #RADIUS} 格内的生物判定：</p>
 *
 * <table border="1">
 *   <tr><th>目标是否合格</th><th>条件</th><th>结果</th></tr>
 *   <tr><td>合格</td><td>身着金属装备，<b>或</b>护甲值 &gt; 20</td>
 *       <td>施加【震荡】（<b>3 秒</b>）</td></tr>
 *   <tr><td>升级</td><td>金属装备 ≥ 4 件，<b>或</b>护甲值 ≥ 40</td>
 *       <td>改为【震荡 2】（<b>5 秒</b>）</td></tr>
 *   <tr><td>不合格</td><td>既没金属、护甲又 ≤ 20</td><td>不受影响</td></tr>
 * </table>
 *
 * <p>「金属装备数」直接复用 {@link BallProspecting#countMetalEquipment}，
 * 与【感应】看的是同一套判据 —— 免得同一个词条在不同地方给出不同的数。</p>
 *
 * <h2>为什么挂在 {@code onHit} 而不是 {@code onHitEntity}</h2>
 * <p>作者的规格是「命中时」，没有区分撞到的是生物还是方块。挂在
 * {@code onHitEntity} 里就漏掉了砸地面、砸墙的情形；挂在总入口 {@code onHit}
 * 才能两种都覆盖。位置选在「回归虚化」的早退之后 —— 回家途中的球不该炸开脉冲。</p>
 */
public final class RedstonePulse {

    private RedstonePulse() {
    }

    /** 触发半径（格）—— 作者指定 <b>7</b> */
    public static final double RADIUS = 7.0D;

    /** 【震荡】的持续时长：<b>3 秒</b> = 60 刻 */
    private static final int DURATION_BASE = 60;

    /** 【震荡 2】的持续时长：<b>5 秒</b> = 100 刻 */
    private static final int DURATION_STRONG = 100;

    /** 合格门槛：护甲值**超过**这个数就吃震荡（作者指定 > 20） */
    public static final int ARMOR_TO_QUALIFY = 20;

    /** 升级门槛之一：金属装备**达到**这个件数就吃震荡 2 */
    public static final int METAL_FOR_STRONG = 4;

    /** 升级门槛之一：护甲值**达到**这个数就吃震荡 2 */
    public static final int ARMOR_FOR_STRONG = 40;

    /**
     * 在给定位置引爆一次【脉冲】。
     *
     * @param level  服务端世界
     * @param center 命中点（球心）
     */
    public static void detonate(ServerLevel level, Vec3 center) {
        // 先起粒子 —— 三团扩散球面由 RedstonePulseFx 按时间轴推进
        RedstonePulseFx.start(level, center);

        // ⚠️ AABB 只是**粗筛**，它是个立方体 —— inflate(7) 出来边长 14，
        //    角落离中心 7√3 ≈ 12.1 格，远远超出作者要的「半径 7 格范围内」。
        //    所以下面必须再按**实际距离**精筛一次（2026-10-10 自查时发现并修掉）。
        AABB box = new AABB(center, center).inflate(RADIUS);
        double radiusSqr = RADIUS * RADIUS;
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box);

        for (LivingEntity target : nearby) {
            if (!target.isAlive()) {
                continue;
            }

            // 用**碰撞箱到命中点的最短距离**判定：取实体边缘而不是中心，
            // 这样大个子生物站在边界上不会被漏掉，判定也比「中心点在不在球内」更贴合直觉。
            if (target.getBoundingBox().distanceToSqr(center) > radiusSqr) {
                continue;
            }

            int metal = BallProspecting.countMetalEquipment(target);
            int armor = target.getArmorValue();

            // 不合格：既没有金属装备，护甲值也没超过 20 —— 完全不受影响
            if (metal <= 0 && armor <= ARMOR_TO_QUALIFY) {
                continue;
            }

            // 装备够厚 → 升级成震荡 2
            boolean strong = metal >= METAL_FOR_STRONG || armor >= ARMOR_FOR_STRONG;
            target.addEffect(new MobEffectInstance(
                    ModEffects.SHOCK,
                    strong ? DURATION_STRONG : DURATION_BASE,
                    strong ? 1 : 0));
        }
    }
}
