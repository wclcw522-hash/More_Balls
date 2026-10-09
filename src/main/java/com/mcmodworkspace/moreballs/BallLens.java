package com.mcmodworkspace.moreballs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 【透镜】—— 钻石球的专属词条（作者 2026-10-10 指定的规格）。
 *
 * <h2>触发条件（三个同时满足）</h2>
 * <ul>
 *   <li>钻石球是<b>实体状态</b>（由调用方保证）</li>
 *   <li><b>白天</b> —— {@code level.isDay()}</li>
 *   <li><b>晴天</b> —— 不下雨也不打雷</li>
 * </ul>
 *
 * <h2>作用</h2>
 * <p>球下方一片 5×5 的方块与其中的生物，每 {@link #TICK_INTERVAL} 刻积 {@value #HEAT_PER_STEP} 点热。
 * <b>即使球已经静止也照常积累</b> —— 这是作者特意点出来的：静止的钻石球照样是一块聚焦镜。</p>
 *
 * <p>普通方块到 {@value #FLAME_PARTICLE_HEAT} 点开始冒火焰粒子、到 {@value #IGNITE_HEAT} 点
 * <b>真的烧起来</b>（放一格原版火焰）。<b>金属与矿物方块不冒火也不点燃</b> ——
 * 它们只负责把热吃进去（这部分行为交给既有的热量系统）。</p>
 *
 * <h2>「上下各一格」怎么理解</h2>
 * <p>作者的原话是「下方 5*5*2（上下各一格）」。这里按<b>球的水平面为中心取 5×5，
 * 垂直取两层：球所在那一层与它下面一层</b>实现。这样既对得上「5×5×2」的 50 格，
 * 也符合「照在球下方」的语义。</p>
 */
public final class BallLens {

    private BallLens() {
    }

    /** 多久积一次热（刻）—— 作者指定 5 刻 1 点 */
    private static final int TICK_INTERVAL = 5;

    /** 每次积多少热 —— 作者指定 1 点 */
    private static final float HEAT_PER_STEP = 1.0F;

    /** 达到这个热量开始冒火焰粒子（作者指定 50） */
    public static final float FLAME_PARTICLE_HEAT = 50.0F;

    /** 达到这个热量真的烧起来（作者指定 100） */
    public static final float IGNITE_HEAT = 100.0F;

    /** 水平半径：5×5 就是 2 格 */
    private static final int HORIZONTAL_RADIUS = 2;

    /** 生物每次积多少热 —— 与方块同步（作者：生物也「同步」增加热量） */
    private static final float LIVING_HEAT_PER_STEP = 1.0F;

    /**
     * 每刻调用一次（由 {@code BallProjectile} 在实体状态时调）。
     *
     * <p><b>不判 {@code isSettled()}</b> —— 作者明确「即使钻石球静止」也要积热。</p>
     */
    public static void tick(ServerLevel level, Vec3 ballPos) {
        // 条件① 白天。
        //
        // ⚠️ 26.2 的 {@code Level} 上**没有 isDay()、也没有 getDayTime()**
        //    （只剩 isRaining / isThundering / isBrightOutside / getOverworldClockTime）。
        //    这里用原版现成的 {@code isBrightOutside()}，它的实现是
        //        {@code !dimensionType().hasFixedTime() && skyDarken < 4}
        //    —— 既排除下界/末地（固定时间、没有昼夜），也排除夜里与阴天变暗，
        //    正好对上作者要的「白天 + 晴天」。
        if (!level.isBrightOutside()) {
            return;
        }
        // 条件② 晴天（不下雨、不打雷）
        if (level.isRaining() || level.isThundering()) {
            return;
        }
        // 节流：每 5 刻一次
        if (level.getGameTime() % TICK_INTERVAL != 0L) {
            return;
        }

        BlockPos center = BlockPos.containing(ballPos);
        ProspectingHeatData data = ProspectingHeatData.get(level);
        long now = level.getGameTime();

        // ===== ① 方块：球所在层 + 它下面一层，各取 5×5 =====
        for (int dy = 0; dy >= -1; dy--) {
            for (int dx = -HORIZONTAL_RADIUS; dx <= HORIZONTAL_RADIUS; dx++) {
                for (int dz = -HORIZONTAL_RADIUS; dz <= HORIZONTAL_RADIUS; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (!state.getFluidState().isEmpty()) {
                        continue;   // 水那一类不参与（液体的 getBlockState 也拿得到，但烤水没意义）
                    }

                    data.addHeat(pos, HEAT_PER_STEP, now, "lens");

                    // 金属与矿物方块只积热，不冒火也不点燃（作者指定）
                    if (BallProspecting.isMetalOre(level, state) || BallProspecting.isHeatAbsorbing(level, state)) {
                        continue;
                    }

                    float heat = data.getHeat(pos);
                    if (heat >= IGNITE_HEAT) {
                        lightFire(level, pos, state);
                    } else if (heat >= FLAME_PARTICLE_HEAT) {
                        // 冒火焰粒子：从方块顶面往上飘
                        level.sendParticles(ParticleTypes.FLAME,
                                pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D,
                                1, 0.25D, 0.05D, 0.25D, 0.01D);
                    }
                }
            }
        }

        // ===== ② 生物：同一片区域里的也要「同步」升温 =====
        AABB area = new AABB(
                center.getX() - HORIZONTAL_RADIUS, center.getY() - 1.0D, center.getZ() - HORIZONTAL_RADIUS,
                center.getX() + HORIZONTAL_RADIUS + 1.0D, center.getY() + 1.0D, center.getZ() + HORIZONTAL_RADIUS + 1.0D);
        List<LivingEntity> beings = level.getEntitiesOfClass(LivingEntity.class, area);
        for (LivingEntity living : beings) {
            if (!living.isAlive()) {
                continue;
            }
            float heat = living.getData(ModAttachments.PROSPECTING_HEAT.get());
            float next = Math.min(heat + LIVING_HEAT_PER_STEP, BallHeatHandler.MAX_LIVING_HEAT);
            living.setData(ModAttachments.PROSPECTING_HEAT.get(), next);
            // ⚠️ 必须刷新「最后加热刻」—— 否则既有系统会立刻把它当成「没在被加热」，
            //    按降温处理，等于白加热（玩家热量那条链路靠这个时间戳判断升温还是降温）。
            living.setData(ModAttachments.LAST_HEATED_TICK.get(), living.tickCount);
        }
    }

    /**
     * 把这一格点着。
     *
     * <p>用原版的 {@link BaseFireBlock}：它在<b>上方那一格</b>放火（方块本身不烧），
     * 并且会按原版规则判断那里能不能放火（不可燃 / 有水 / 有碰撞箱就放不下）。</p>
     */
    private static void lightFire(ServerLevel level, BlockPos pos, BlockState state) {
        BlockPos above = pos.above();
        if (!level.getBlockState(above).isAir()) {
            return;
        }
        // 原版的火焰方块要放在「有支撑」的位置；照抄 BaseFireBlock 的放置规则
        if (!BaseFireBlock.canBePlacedAt(level, above, net.minecraft.core.Direction.UP)) {
            return;
        }
        level.setBlockAndUpdate(above, Blocks.FIRE.defaultBlockState());
    }
}
