package com.mcmodworkspace.moreballs;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 【照明】—— 红石雪球的专属词条（作者 2026-10-10 指定的规格）。
 *
 * <h2>判定区：一个倒扣的正四棱锥</h2>
 * <p>以<b>球心为顶点</b>，向下张开四条棱、一直到地面，再把底面四个顶点连起来 ——
 * 这个四棱锥就是判定区。作者给的夹角是「斜边与邻边成 60°」，
 * 换成计算式就是：底面半对角线 = 高度 × tan(30°)。</p>
 *
 * <pre>
 *         ●  ← 球心（顶点）
 *        /|\
 *       / | \
 *      /  |  \
 *     ▙▄▄▄▄▄▟ ← 地面（底面）
 * </pre>
 *
 * <h2>效果：区域内的生物被染色发光</h2>
 * <ul>
 *   <li>每在区内待 <b>1 tick</b>，就叠 <b>1 秒</b>的发光（持续待在区内＝一直亮着）</li>
 *   <li>颜色按阵营区分：<b>友好 → 蓝</b>、<b>中立 → 黄</b>、<b>敌对 / 有仇恨 → 红</b></li>
 *   <li><b>对隐身与穿墙的目标同样有效</b> —— 全程不做视线检测，
 *       只按坐标判是否在锥内，所以躲墙后面、喝了隐身药水都照得出来</li>
 * </ul>
 *
 * <h2>关于「颜色」是怎么实现的</h2>
 * <p>原版的 {@code GLOWING} 效果本身<b>只有一种描边颜色</b>：由实体所属的
 * <b>计分板队伍颜色</b>决定，没队伍就是白色。要让同一片区域里的生物显示三种颜色，
 * 得给每个生物临时塞进一个染色队伍 —— 那会改动玩家的计分板数据，属于侵入式做法。</p>
 *
 * <p>所以这里用两条腿走路：</p>
 * <ul>
 *   <li><b>发光效果</b>：保证「看得见」，也让隐蔽目标现形</li>
 *   <li><b>彩色粒子</b>：按阵营在生物身上裹一层对应颜色的点，把「蓝 / 黄 / 红」这个区分度补上</li>
 * </ul>
 * <p>观感上就是「被照到的东西全身发光，还罩着一层对应颜色的光点」。</p>
 */
public final class BallIlluminate {

    private BallIlluminate() {
    }

    /** 锥底张角的换算系数：底面半对角线 = 高度 × tan(30°) */
    private static final double SPREAD = Math.tan(Math.toRadians(30.0D));

    /** 高度太小时不点火（球几乎贴着地面） */
    private static final double MIN_HEIGHT = 0.75D;

    /** 向下找地面的最大距离 */
    private static final double MAX_GROUND_SEARCH = 48.0D;

    /** 每次叠多少刻的发光（作者指定：1 秒） */
    private static final int GLOW_TICKS = 20;

    /** 友好阵营的颜色（蓝） */
    private static final int COLOR_FRIENDLY = 0x4A90FF;

    /** 中立阵营的颜色（黄） */
    private static final int COLOR_NEUTRAL = 0xFFD93B;

    /** 敌对 / 有仇恨阵营的颜色（红） */
    private static final int COLOR_HOSTILE = 0xFF3B3B;

    /** 每只生物每 tick 撒多少颗染色粒子 */
    private static final int PARTICLES_PER_ENTITY = 2;

    /**
     * 球的每刻照明逻辑 —— 由 {@code BallProjectile} 在实体状态时调用。
     *
     * @param level   服务端世界
     * @param ballPos 球心（棱锥的顶点）
     */
    public static void tick(ServerLevel level, Vec3 ballPos) {
        double groundY = findGroundY(level, ballPos);
        if (Double.isNaN(groundY)) {
            return;   // 下面没有可站的地面（悬空在虚空上）
        }
        double height = ballPos.y - groundY;
        if (height < MIN_HEIGHT) {
            return;   // 球几乎贴地了，锥体退化成一条线，没意义
        }
        double halfDiagonal = height * SPREAD;

        // 用锥体外接立方体粗筛，再做精确的锥内判定
        double reach = Math.max(halfDiagonal, 1.0D);
        AABB box = new AABB(
                ballPos.x - reach, groundY - 1.0D, ballPos.z - reach,
                ballPos.x + reach, ballPos.y + 1.0D, ballPos.z + reach);

        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box);
        for (LivingEntity target : nearby) {
            if (!target.isAlive()) {
                continue;
            }
            if (!insidePyramid(ballPos, groundY, halfDiagonal, target)) {
                continue;
            }

            // 叠 1 秒发光。用 setEffect 而不是太频繁的 addEffect：
            // 这里每 tick 都会走到，等价于「只要还在区内就一直亮着，离开后 1 秒熄灭」。
            target.addEffect(new MobEffectInstance(
                    MobEffects.GLOWING, GLOW_TICKS, 0, false, false, false));

            emitFactionParticles(level, target);
        }
    }


    /**
     * 判断目标是否落在棱锥内。
     *
     * <p>做法：先按高度求出这一层的「允许半对角线」，再看目标的水平偏移是否在正方形截面内。
     * 正方形用 {@code max(|dx|, |dz|) <= 半边长} 判，其中半边长 = 半对角线 / √2。</p>
     */
    private static boolean insidePyramid(Vec3 apex, double groundY, double halfDiagonal, LivingEntity target) {
        AABB targetBox = target.getBoundingBox();
        // 用「离顶点最近的那一面」判定，避免高个子生物明明站着却因为中心点偏上而漏判
        double sampleY = Math.min(targetBox.maxY - 1.0E-3D, apex.y);
        if (sampleY < groundY) {
            sampleY = groundY;
        }

        double height = apex.y - groundY;
        if (height <= 0.0D) {
            return false;
        }
        // 这一层的收缩比例：0 在顶点、1 在地面
        double t = (apex.y - sampleY) / height;
        t = Math.max(0.0D, Math.min(1.0D, t));

        double allowedHalfDiagonal = halfDiagonal * t;
        double halfSide = allowedHalfDiagonal / Math.sqrt(2.0D);

        double dx = targetBox.getCenter().x - apex.x;
        double dz = targetBox.getCenter().z - apex.z;
        return Math.max(Math.abs(dx), Math.abs(dz)) <= halfSide;
    }

    /** 按阵营给生物裹一层对应颜色的粒子 */
    private static void emitFactionParticles(ServerLevel level, LivingEntity target) {
        int color = factionColor(target);
        AABB box = target.getBoundingBox();
        var random = target.getRandom();
        for (int i = 0; i < PARTICLES_PER_ENTITY; i++) {
            double x = box.minX + random.nextDouble() * (box.maxX - box.minX);
            double y = box.minY + random.nextDouble() * (box.maxY - box.minY);
            double z = box.minZ + random.nextDouble() * (box.maxZ - box.minZ);
            level.sendParticles(new DustParticleOptions(color, 1.0F), x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /**
     * 阵营 → 颜色。
     *
     * <p>判据（作者指定三类，这里给出具体怎么归）：</p>
     * <ul>
     *   <li><b>红</b>：实现了 {@link Enemy} 的敌对生物，或任何<b>当前有攻击目标</b>的生物
     *       （后者就是「有仇恨的中立生物」，比如被打过的猪）</li>
     *   <li><b>蓝</b>：村民及其变体、铁傀儡、雪傀儡、已驯服的宠物、玩家</li>
     *   <li><b>黄</b>：其余（猪、牛、羊、流浪商人这类中立）</li>
     * </ul>
     */
    public static int factionColor(LivingEntity entity) {
        if (entity instanceof Enemy) {
            return COLOR_HOSTILE;
        }
        if (entity instanceof Mob mob && mob.getTarget() != null && mob.getTarget().isAlive()) {
            return COLOR_HOSTILE;
        }
        if (isFriendly(entity)) {
            return COLOR_FRIENDLY;
        }
        return COLOR_NEUTRAL;
    }

    /** 友好生物 —— 原版没有统一接口，按「会不会主动帮玩家 / 是不是自己人」列出来 */
    private static boolean isFriendly(LivingEntity entity) {
        if (entity instanceof AbstractVillager || entity instanceof IronGolem || entity instanceof SnowGolem) {
            return true;
        }
        if (entity instanceof TamableAnimal tamable && tamable.isTame()) {
            return true;
        }
        return entity instanceof Player;
    }

    /**
     * 从球的位置向下找出「地面」的高度。
     *
     * <p>用原版的射线检测（{@link ClipContext}），命中方块就取命中点的 y。
     * 没命中（悬空在虚空上）返回 {@code NaN}，调用方据此跳过。</p>
     */
    private static double findGroundY(ServerLevel level, Vec3 from) {
        Vec3 to = from.add(0.0D, -MAX_GROUND_SEARCH, 0.0D);
        BlockHitResult hit = level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, (Entity) null));
        if (hit.getType() == HitResult.Type.MISS) {
            return Double.NaN;
        }
        return hit.getLocation().y;
    }
}
