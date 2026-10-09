package com.mcmodworkspace.moreballs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mcmodworkspace.moreballs.entity.BallProjectile;

/**
 * 【引雷300】—— 空心铜球的热量攒够阈值后释放一次雷电。
 *
 * <h2>规格（作者 2026-10-07 指定）</h2>
 * <ul>
 *   <li>一次随机释放 <b>4–9 道</b>闪电</li>
 *   <li>闪电<b>起点是该空心铜球</b>，自动寻找目标</li>
 *   <li>优先级：<b>避雷针 = 铜球</b> ＞ <b>身着金属装备的生物</b>
 *       （每件 +1，若它正处于满热量导致的着火则额外 +3）＞
 *       <b>其它铜制方块</b> ＞ <b>无装备生物</b> ＞ <b>所有方块（除铜制）</b></li>
 *   <li>一次雷击的<b>总伤害 60</b>，分散到不同的闪电分支上</li>
 *   <li><b>同一个生物在一次雷击内只能被击中一次</b></li>
 * </ul>
 *
 * <h2>实现要点</h2>
 * <p>闪电实体本身用 {@code setVisualOnly(true)} 生成 —— 也就是说原版那套伤害我们不采用，
 * 而是自己按「总伤 60 ÷ 命中分支数」结算。这样才做得到「总伤固定」这条，
 * 否则 9 道原版闪电各自 5 点、还会互相叠加，总伤完全不可控。</p>
 *
 * <p>原版 {@code LightningBolt} 是「落在一个点上」的，没有「从 A 连到 B」的形态，
 * 所以这里的「分支」表现为：每道闪电落在不同目标身上，视觉上就是一片砸下来的雷。</p>
 */
public final class BallThunder {

    /** 一次雷击的闪电数量 */
    public static final int MIN_BOLTS = 4;
    public static final int MAX_BOLTS = 9;

    /** 一次雷击的**总**伤害（分散到各个分支） */
    public static final float TOTAL_DAMAGE = 60.0F;

    /**
     * <b>每道闪电的最低伤害（作者指定 5）。</b>
     *
     * <p>原来这里是 {@code 60 / 命中数}，<b>没有下限</b> —— 目标一多每道就摊到很小的数，
     * 而 Minecraft 对 <b>小于 1 的伤害直接忽略</b>，于是看起来「引雷根本没打出伤害」
     * （作者 2026-10-09 反馈）。加了这个下限之后，命中越多总伤越高，但每一道都疼。</p>
     */
    public static final float MIN_PER_BRANCH = 5.0F;

    /** 从球出发找目标的半径（格） */
    public static final double RADIUS = 16.0;

    // ===== 优先级分值：数字越大越优先被劈 =====

    /** 避雷针 / 铜球（并列最高，作者指定） */
    private static final int SCORE_CONDUCTOR = 1000;

    /** 身着金属装备的生物：基础 10，每件金属装备 +1 */
    private static final int SCORE_METAL_BASE = 10;

    /** 满热量导致着火的生物：额外 +3（作者指定） */
    private static final int SCORE_ON_FIRE_BONUS = 3;

    /** 其它铜制方块 */
    private static final int SCORE_COPPER_BLOCK = 5;

    /** 没装备的生物 */
    private static final int SCORE_BARE_ENTITY = 1;

    /** 所有方块（除铜制） */
    private static final int SCORE_PLAIN_BLOCK = 0;

    private BallThunder() {
    }

    /**
     * 释放一次雷击。
     *
     * @param source 作为闪电起点的空心铜球
     */
    public static void release(ServerLevel level, BallProjectile source) {
        RandomSource random = level.getRandom();
        int boltCount = MIN_BOLTS + random.nextInt(MAX_BOLTS - MIN_BOLTS + 1);

        List<Candidate> candidates = collect(level, source);
        Vec3 origin = source.position();

        // 同一个实体一次雷击只中一次 —— 粒度是**所有实体**，不只是生物
        // （作者指定：这个「只中一次」要覆盖全部实体，掉落物、箭、船、别的球都算）
        Set<UUID> alreadyHit = new HashSet<>();
        // 实际被劈到的实体，用来最后分摊那 60 点
        List<Entity> struck = new ArrayList<>();

        for (int i = 0; i < boltCount; i++) {
            Candidate target = pickOne(candidates, random);
            LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
            if (bolt == null) {
                continue;
            }

            Vec3 at = target == null ? randomAround(origin, random) : target.position();
            bolt.setPos(at.x, at.y, at.z);
            // 原版的伤害我们自己接管，避免 9 道各自结算把总伤顶到几十上百
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);

            if (target != null && target.entity() != null) {
                UUID id = target.entity().getUUID();
                if (alreadyHit.add(id)) {
                    struck.add(target.entity());
                }
            }
        }

        // 总伤 60 平摊给真正被劈到的**生物**；一个都没打中就纯看特效。
        // 非生物实体（掉落物 / 箭 / 船……）没有受伤这回事，只参与去重、不参与分摊。
        List<LivingEntity> victims = new ArrayList<>();
        for (Entity entity : struck) {
            if (entity instanceof LivingEntity living && living.isAlive()) {
                victims.add(living);
            }
        }
        if (!victims.isEmpty()) {
            float per = Math.max(MIN_PER_BRANCH, TOTAL_DAMAGE / victims.size());
            Entity owner = source.getOwner();
            for (LivingEntity victim : victims) {
                victim.hurtServer(level, level.damageSources().source(
                        net.minecraft.world.damagesource.DamageTypes.LIGHTNING_BOLT, owner), per);
            }
        }
    }

    // ===== 目标收集与评分 =====

    /**
     * 一个候选目标。
     *
     * @param entity 被劈的实体；为 {@code null} 表示这是个「方块」候选（闪电砸在方块上）
     * @param pos    落点
     * @param score  优先级分值
     */
    private record Candidate(Entity entity, Vec3 pos, int score) {
        Vec3 position() {
            return pos;
        }
    }

    /** 扫一遍周围，把所有候选连同分值收好 */
    private static List<Candidate> collect(ServerLevel level, BallProjectile source) {
        List<Candidate> out = new ArrayList<>();
        AABB box = source.getBoundingBox().inflate(RADIUS);
        Entity owner = source.getOwner();

        // ① 所有实体 —— 作者指定「只中一次」的粒度是**所有实体**，
        //    所以候选池也得涵盖非生物实体（箭 / 船 / 矿车 / 别的投射物……），
        //    它们得分和「无装备生物」同级，只参与去重与落点、不参与伤害分摊。
        //
        //    ⚠️ **掉落物除外**（作者指定）：闪电不去追地上的物品，
        //    否则一场雷会把周围的掉落物全当靶子。
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box)) {
            if (entity == source || entity == owner || entity.isRemoved()) {
                continue;
            }
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity) {
                continue;
            }
            if (entity instanceof LivingEntity living && (!living.isAlive() || living.isSpectator())) {
                continue;
            }
            // 闪电本体不进候选，不然会自己劈自己
            if (entity instanceof LightningBolt) {
                continue;
            }
            out.add(new Candidate(entity, entity.position(), scoreEntity(entity, source)));
        }

        // ② 别的导电球（作者指定：铜球与避雷针并列最高）
        //    已经在 ① 里收过了，这里只是把它的分值提到最高
        for (BallProjectile other : level.getEntitiesOfClass(BallProjectile.class, box)) {
            if (other == source || other.isRemoved()) {
                continue;
            }
            if (!BallBehavior.profileFor(other.getItem()).conduction()) {
                continue;
            }
            out.removeIf(c -> c.entity() == other);
            out.add(new Candidate(other, other.position(), SCORE_CONDUCTOR));
        }

        // ③ 方块：避雷针与铜制方块。扫一个立方体范围，只取够得着的少数几个，
        //    不然 33³ 全扫一遍太贵
        BlockPos center = source.blockPosition();
        int r = 6;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            BlockState state = level.getBlockState(pos);
            int score = scoreBlock(state);
            if (score < 0) {
                continue;
            }
            out.add(new Candidate(null, Vec3.atCenterOf(pos), score));
        }

        return out;
    }

    /**
     * 实体的分值。
     *
     * <p>金属装备与着火的加成只对**生物**有意义；非生物实体按「无装备」同级处理
     * （作者给出的优先级表里没有这一类，但「其它方块」是最低级，
     * 实体总归比空气值得劈一点，所以取无装备生物那一档）。</p>
     */
    private static int scoreEntity(Entity entity, BallProjectile source) {
        if (!(entity instanceof LivingEntity living)) {
            return SCORE_BARE_ENTITY;
        }
        int metal = BallProspecting.countMetalEquipment(living);
        if (metal > 0) {
            int score = SCORE_METAL_BASE + metal;
            // 作者指定：处于「满热量导致的着火」再加 3。
            // 判定用本模组的热量 attachment —— 被感应球烤着火的才算，
            // 普通火把点着的不算加成分。
            Float heat = living.getData(ModAttachments.PROSPECTING_HEAT.get());
            if (living.isOnFire() && heat != null && heat > BallProspecting.PLAYER_HEAT_IGNITE) {
                score += SCORE_ON_FIRE_BONUS;
            }
            return score;
        }
        return SCORE_BARE_ENTITY;
    }

    /** 方块的分值；返回负数表示这个方块不是候选 */
    private static int scoreBlock(BlockState state) {
        String path = blockPath(state);
        // 避雷针：26.2 里 Blocks.LIGHTNING_ROD 是 WeatheringCopperCollection 而不是 Block，
        // 直接比对取不到；按方块 id 判断反而更稳，也不受氧化/变体影响
        if ("lightning_rod".equals(path)) {
            return SCORE_CONDUCTOR;
        }
        if (isCopperBlock(path)) {
            return SCORE_COPPER_BLOCK;
        }
        // 其它方块也在候选里，但分值最低（作者指定）
        return state.isAir() ? -1 : SCORE_PLAIN_BLOCK;
    }

    /**
     * 是不是铜制方块。
     *
     * <p>用方块 id 的名字前缀判断 —— 原版铜系成员很多（铜块 / 切制铜 / 铜格栅 /
     * 铜灯 / 铜门 / 铜栏杆 / 各种氧化阶段……），逐个枚举容易漏，
     * 而它们的 id 一律以 {@code copper} 或氧化前缀开头。</p>
     */
    private static boolean isCopperBlock(String path) {
        return path.contains("copper");
    }

    /** 方块 id 的路径部分（如 minecraft:copper_block → copper_block） */
    private static String blockPath(BlockState state) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).getPath();
    }

    /** 按分值加权随机挑一个候选；没有候选时返回 {@code null} */
    private static Candidate pickOne(List<Candidate> candidates, RandomSource random) {
        if (candidates.isEmpty()) {
            return null;
        }
        // 每个候选至少 1 点权重，这样低优先级的也不会永远轮不上
        int total = 0;
        for (Candidate c : candidates) {
            total += Math.max(1, c.score());
        }
        int roll = random.nextInt(total);
        for (Candidate c : candidates) {
            roll -= Math.max(1, c.score());
            if (roll < 0) {
                return c;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /** 没有任何候选时，让闪电落在起点附近，纯观赏 */
    private static Vec3 randomAround(Vec3 origin, RandomSource random) {
        return new Vec3(
                origin.x + (random.nextDouble() - 0.5) * 6.0,
                origin.y + random.nextDouble() * 2.0,
                origin.z + (random.nextDouble() - 0.5) * 6.0);
    }
}
