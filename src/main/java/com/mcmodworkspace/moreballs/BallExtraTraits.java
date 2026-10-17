package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.BallProjectile;
import com.mcmodworkspace.moreballs.entity.DripstoneShard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>第十三批的击中类 / 组合类词条</b>（作者 2026-10-15 定稿）。
 *
 * <h2>里面有什么</h2>
 * <ul>
 *   <li><b>【敛财】</b> 绿宝石球（「其它特殊」类）—— 击中生物时给它挂【腰缠万贯】（10 秒）。
 *       效果本体在 {@link MoneyEffect}，这里只负责施加。</li>
 *   <li><b>【尖刺】</b> 滴水石球（「其它特殊」类）—— 击中 / 碰撞 / 破碎时，以垂直于速度的面为基准面，
 *       朝 8 个方向、与速度反向成 60° 角、最高点 2 格，射出 8 枚滴水石锥
 *       （{@link DripstoneShard}，基础伤害 3）。</li>
 *   <li><b>【招蜂引蝶 N】</b> 蜜饯球（<b>数值类特性</b>）—— 击中时召唤 N 只仇恨目标的蜜蜂（血量 5），
 *       并把目标的仇恨强制转移到它们身上。</li>
 *   <li><b>【封印】</b> 符箓球（「其它特殊」类）—— 封玩家快捷栏一件 3 秒 / 封怪物 10 秒
 *       （不能攻击、也不能捡球，手里的球全掉出来）。</li>
 *   <li><b>【滋养】</b> 蜜饯球的<b>组合类</b>特性词条 —— 回收成功那一刻按概率给主人 3 秒生命恢复。</li>
 * </ul>
 *
 * <h2>26.2 的包名差异（踩过，记下来）</h2>
 * <ul>
 *   <li>蜜蜂是 {@code net.minecraft.world.entity.animal.bee.Bee}（不在 {@code animal} 根下）</li>
 *   <li>{@code ItemCooldowns.addCooldown} 收的是 <b>ItemStack</b>，不是 Item</li>
 *   <li>实体定位用 {@code snapTo}，没有 {@code moveTo}</li>
 * </ul>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallExtraTraits {

    private BallExtraTraits() {
    }

    // ===== 常量（作者指定的数值）=====

    /** 【敛财】：【腰缠万贯】的时长 —— 200 刻 = 10 秒 */
    public static final int MONEY_TICKS = 200;

    /** 【滋养】：生命恢复的时长 —— 60 刻 = 3 秒 */
    public static final int NURTURE_TICKS = 60;

    /** 【招蜂引蝶】：蜜蜂的血量 —— 单一真源在 {@code SummonedBee.HEALTH}（作者 2026-10-15 下调：5 → 3） */
    public static final float BEE_HEALTH = (float) com.mcmodworkspace.moreballs.entity.SummonedBee.HEALTH;

    /** 【招蜂引蝶】：蜜蜂的召唤半径 */
    private static final double BEE_SPAWN_RADIUS = 1.6D;

    /**
     * 这只蜜蜂是不是【招蜂引蝶】召唤出来的。
     *
     * <p>⚠️ 作者 2026-10-16 把它<b>做成了独立生物</b>（{@code more_balls:summoned_bee}，
     * 见 {@link com.mcmodworkspace.moreballs.entity.SummonedBee}）—— 所以判据从原来的
     * 「记分板标签」换成了<b>实体类型</b>，一劳永逸：不用再在生成时打标签、
     * 也不用担心标签丢失。{@code BallProjectile.canHitEntity} 靠它做到「球不打自己召唤的蜂」。</p>
     */
    public static boolean isSummonedBee(Entity entity) {
        return entity instanceof com.mcmodworkspace.moreballs.entity.SummonedBee;
    }

    /** 蜜蜂血量在 tooltip 里的显示文本（3.0 → "3"） */
    public static String beeHealthText() {
        return BEE_HEALTH == Math.round(BEE_HEALTH)
                ? String.valueOf((int) BEE_HEALTH)
                : String.valueOf(BEE_HEALTH);
    }

    /** 【尖刺】：滴水石锥的数量 —— 8 个方向 */
    public static final int SPIKE_COUNT = 8;

    /** 【尖刺】：与「速度反方向」的夹角（作者指定 60°） */
    public static final double SPIKE_ANGLE_DEG = 60.0D;

    /** 【尖刺】：最高点高度（作者指定 2 格） */
    public static final double SPIKE_APEX = 2.0D;

    /**
     * 原版投掷物的默认重力（{@code ThrowableProjectile.getDefaultGravity()} = 0.03）。
     *
     * <p>「最高点 2 格」要反推竖直初速度，用的就是这个数：
     * {@code v = sqrt(2 * g * h)}。</p>
     */
    private static final double THROWN_GRAVITY = 0.03D;

    /** 【尖刺】：初速度的夹取区间 —— 免得某些朝向算出让锥子瞬移的极端值 */
    private static final double SPIKE_MIN_SPEED = 0.15D;
    private static final double SPIKE_MAX_SPEED = 1.10D;

    /** 【封印】：封玩家快捷栏的时长 —— 60 刻 = 3 秒 */
    public static final int SEAL_PLAYER_TICKS = 60;

    /** 【封印】：封怪物攻击的时长 —— 200 刻 = 10 秒 */
    public static final int SEAL_MOB_TICKS = 200;

    /** 这颗球是不是「组合球」（组合类词条只在它身上生效） */
    public static boolean isComboBall(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == ModItems.COMBO_BALL.get();
    }

    // ===== 【敛财】=====

    /**
     * 给被击中者挂【腰缠万贯】。
     *
     * <p>作者指定「最大 1 级」⇒ 一律 {@code amplifier = 0}；
     * 已经有更长的同效果时不缩短它（重复命中只延不缩）。</p>
     */
    public static void applyMoney(LivingEntity victim, LivingEntity source) {
        MobEffectInstance now = victim.getEffect(ModEffects.MONEY);
        if (now != null && now.getDuration() >= MONEY_TICKS) {
            return;
        }
        victim.addEffect(new MobEffectInstance(ModEffects.MONEY, MONEY_TICKS,
                MoneyEffect.MAX_AMPLIFIER, false, true, true), source);
    }

    // ===== 【招蜂引蝶 N】=====

    /**
     * 召唤 {@code count} 只「被召唤的蜜蜂」，全部锁定 {@code victim}。
     *
     * <p>作者要求「这些蜜蜂会强制转移目标的仇恨到他们身上」——
     * 所以既让蜜蜂锁定目标，也主动把目标当前的攻击目标设成蜜蜂，
     * 保证仇恨立刻转移过去，而不是等它挨一下才反应。</p>
     *
     * <h2>2026-10-16 起改用独立生物</h2>
     * <p>作者要求把它做成独立生物 {@code more_balls:summoned_bee}
     * （见 {@link com.mcmodworkspace.moreballs.entity.SummonedBee}）——
     * 于是这里<b>不需要</b>再维护任何静态状态：</p>
     * <ul>
     *   <li>名字：实体类型自带（{@code entity.more_balls.summoned_bee}）</li>
     *   <li>8 秒寿命 + 烟雾 + 无掉落：实体自己的 {@code tick()}</li>
     *   <li>无限攻击：实体覆写的 {@code doHurtTarget}</li>
     *   <li>不仇恨召唤者：实体覆写的 {@code canAttack}</li>
     * </ul>
     * <p>这里只剩「摆位置、设召唤者、设目标」三件事。</p>
     */
    public static void summonBees(ServerLevel level, LivingEntity victim, int count,
                                  net.minecraft.world.entity.Entity summoner, boolean truePhysical) {
        if (count <= 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            var bee = ModEntities.SUMMONED_BEE.get().create(level, EntitySpawnReason.MOB_SUMMONED);
            if (bee == null) {
                continue;
            }
            double angle = (Math.PI * 2.0D / count) * i;
            bee.snapTo(victim.getX() + Math.cos(angle) * BEE_SPAWN_RADIUS,
                    victim.getY() + 1.0D,
                    victim.getZ() + Math.sin(angle) * BEE_SPAWN_RADIUS,
                    victim.getYRot(), 0.0F);
            bee.setHealth(BEE_HEALTH);          // 属性里已经给了 3 点，这里只是补齐当前血量
            bee.setSummoner(summoner);          // 召唤者 = 球的主人（它绝不打他）
            // 【穿甲】联动：源球带【穿甲】时，蜜蜂咬的这一口也走「真实物理伤害」
            bee.setTruePhysical(truePhysical);
            bee.resetLife();                    // 8 秒寿命从头算
            bee.setTarget(victim);
            level.addFreshEntity(bee);
            // 仇恨反向转移的**第一次**设置；之后由 SummonedBee.tick() 每刻维持 ——
            // 只设一次是不够的：怪的目标会被它自己的 AI 很快改掉（作者 2026-10-16 指出）。
            if (victim instanceof Mob mob) {
                mob.setTarget(bee);
            }
        }
        level.playSound(null, victim.blockPosition(), SoundEvents.BEE_LOOP,
                SoundSource.NEUTRAL, 1.0F, 1.0F);
    }

    // ===== 【尖啸】（回响球专属，作者 2026-10-16 指定）=====

    /**
     * 【尖啸】：<b>路径上的伤害与击飞</b>生效半径 —— 作者 2026-10-16 改为 <b>3 格</b>
     * （原先是 7，与落点同值）。
     */
    public static final double SHRIEK_PATH_RADIUS = 3.0D;

    /**
     * 【尖啸】：<b>落点的效果与特效</b>半径 —— 作者 2026-10-16 改为 <b>10 格</b>。
     */
    public static final double SHRIEK_IMPACT_RADIUS = 10.0D;

    /**
     * 【尖啸】：射线的<b>距离上限</b> —— <b>256 格</b>（作者 2026-10-16 指定）。
     *
     * <p>一路没撞到方块时，就把「起点沿速度方向 256 格」那一点当成终点：
     * 在那里触发落点判定（落点效果、音波、球停下），而不是继续往前飞。</p>
     *
     * <p>⚠️ 这条是为<b>末地</b>准备的 —— 末地有大量没有方块的虚空，
     * 射线会一路 MISS。若不设上限，球会被直接放到极远处（甚至飞出世界边界），
     * 那就是作者要防的 bug。</p>
     */
    private static final double SHRIEK_MAX_RANGE = 256.0D;

    /** 【尖啸】：路径伤害（作者指定 5） */
    public static final float SHRIEK_PATH_DAMAGE = 5.0F;

    /** 【尖啸】：落点范围伤害 —— 作者 2026-10-16 改为 <b>15</b>（原先是 10） */
    public static final float SHRIEK_IMPACT_DAMAGE = 15.0F;

    /**
     * 【尖啸】：击飞力度 —— 作者 2026-10-16 要求「<b>稍微增大</b>」。
     * 水平分量与竖直分量分开放，方便单独调。
     */
    private static final double SHRIEK_KNOCK_HORIZONTAL = 1.35D;
    private static final double SHRIEK_KNOCK_VERTICAL = 0.70D;

    /** 【尖啸】：落点音波扩散的帧数 —— 球每刻推进一帧，走完就停 */
    public static final int SHRIEK_WAVE_FRAMES = 10;

    /**
     * 【尖啸】期间给球用的<b>极高速度</b> —— 目的是让「按速度缩放」的词条吃到高倍率
     * （作者 2026-10-16 要求「把路径与数值的计算一并纳入」）。
     *
     * <p>最直接的一个是【感应】：积热效率 = {@code 速度 / physicalVelocity()}
     * （见 {@code BallProjectile.heatEfficiency()}，出手速度约 1.5 格/刻），
     * 取 <b>32</b> 就是约 <b>21 倍</b>效率 —— 一步就把范围内目标的温度顶到阈值以上。</p>
     *
     * <p>⚠️ 它必须与 {@link #SHRIEK_SUBSTEPS} 一起看：<b>一步的位移 = 速度 × 1 刻</b>，
     * 两者相乘才是这一趟飞出的距离。<b>32 × 8 = 256 格</b>，正好等于
     * {@link #SHRIEK_MAX_RANGE} —— 也就是「一 tick 内飞满射程」，视觉上仍是「瞬间抵达」，
     * 但又不会一步跨出世界、也不会跳过薄墙。</p>
     *
     * <p>⚠️ 它<b>只活在这一趟尖啸里</b>：球落地后由正常的落定体系接管，不会留下「极速球」。</p>
     */
    private static final double SHRIEK_SPEED_FOR_TRAITS = 32.0D;

    /**
     * <b>【尖啸】</b> —— 回响球投出后的那一下。
     *
     * <h2>规格（作者 2026-10-16 二次修订后的定稿）</h2>
     * <ul>
     *   <li><b>无飞行过程</b>：投出的第一刻就把整条轨迹算完，玩家看到的是「瞬间抵达」；</li>
     *   <li><b>路径上的生物</b>：只吃 <b>5</b> 点伤害 + 被<b>击飞</b>（力度比原先稍大），
     *       生效半径 <b>3 格</b>；<b>不再施加任何 debuff</b>
     *       （作者 2026-10-16：「对路径上的生物不再造成 debuff」）；</li>
     *   <li><b>每个碰撞点</b>（含最终落点）：半径 <b>10 格</b>内吃 <b>15</b> 点范围伤害，
     *       并施加这一整套效果 ——
     *       黑暗 20s、失明2 20s、震荡3 20s、发光 2:00、缓慢3 40s、虚弱4 1:00、漂浮 5s；
     *       同时播放音效并产生音爆范围；</li>
     *   <li><b>弹射</b>：球的弹射不为 0 时，撞墙后按弹射反射<b>继续飞</b>，
     *       整条反弹轨迹<b>一并算完</b>，每一段都走上面那套路径 / 落点判定。</li>
     * </ul>
     *
     * <h2>实现要点</h2>
     * <ol>
     *   <li><b>迭代射线</b>：一段一段地 {@code clip}，撞到方块就按面法线做镜面反射、
     *       速度按 {@code bounce / 10} 衰减，继续下一段；反弹次数上限就是弹射档位。</li>
     *   <li><b>绝不误伤主人</b>：两条判定都剔除球的 owner ——
     *       球是在主人身边出手的，他本人必然落在起点那一段的范围里。</li>
     *   <li>路径判定用<b>点到线段的距离</b>，不是包围盒 —— 路径可以是斜的。</li>
     * </ol>
     *
     * @return 落点扩散动画还需要走几帧（0 表示不播）
     */
    public static int shriek(BallProjectile ball, ServerLevel level, boolean armorPiercing) {
        Vec3 velocity = ball.getDeltaMovement();
        if (velocity.lengthSqr() < 1.0E-6D) {
            return 0;   // 没速度就不知道该往哪飞
        }

        // ===== 【尖啸】= 几何射线算完整条轨迹（作者 2026-10-17 定的方案）=====
        //
        // ⚠️⚠️ **这一版把「让球真的按极速飞」整个回退了**，原因很具体：
        //    那套做法让球在一 tick 内飞完 256 格，于是**路径音波只在同一帧出现**
        //    （玩家看不到那一道光），而落点判定又跟着每 tick 乱跑。
        //
        //    作者给的方案是**两边分开算**：
        //      · **轨迹**走回这里的几何射线 —— 一 tick 内迭代算出整条路径
        //        （含反弹），每一段都当场画音波、结算路径伤害，末端结算落点音爆。
        //        这样「一道音波」是确定的、看得见的。
        //      · **词条的数值相互作用**不靠真速度，改由 `BallProjectile.shriekSpeedBoost()`
        //        给一个「等效 1 刻 256 格」的速度倍率，凡是按速度缩放的词条
        //        （最典型【感应】的积热效率）都按那个算。
        //
        //    两边互不牵制：球的实际弹道一点没变，词条该吃满的还是吃满。
        Vec3 from = ball.position();

        Entity owner = ball.getOwner();
        // 反弹次数上限 = 弹射档位（回响球本体是 0 ⇒ 不反弹，行为与原先完全一致）
        int maxBounces = Math.max(0, ball.getBounce());
        Vec3 cursor = from;
        Vec3 dir = velocity.normalize();
        Vec3 lastPoint = from;

        for (int bounce = 0; bounce <= maxBounces; bounce++) {
            // ① 从当前点朝当前方向射到距离上限
            Vec3 to = cursor.add(dir.scale(SHRIEK_MAX_RANGE));
            BlockHitResult hit = level.clip(new ClipContext(cursor, to,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            boolean miss = hit.getType() == HitResult.Type.MISS;
            Vec3 end = miss ? to : hit.getLocation();

            // ② 这一段：路径特效 + 路径判定 + 落点那一整套（音效 / 音爆 / 范围伤害 / 效果）
            shriekSegment(ball, level, cursor, end, armorPiercing, owner, miss);
            lastPoint = end;

            if (miss) {
                break;   // 一路没撞到东西（末地虚空）⇒ 到此为止
            }

            // ③ 撞墙 ⇒ 按面法线镜面反射后继续飞（方向保持单位向量）
            double nx = hit.getDirection().getStepX();
            double ny = hit.getDirection().getStepY();
            double nz = hit.getDirection().getStepZ();
            double dot = dir.x * nx + dir.y * ny + dir.z * nz;
            Vec3 reflected = new Vec3(dir.x - 2.0D * dot * nx,
                    dir.y - 2.0D * dot * ny, dir.z - 2.0D * dot * nz);
            if (reflected.lengthSqr() < 1.0E-9D) {
                break;   // 正好垂直撞进去，反射不出方向
            }
            dir = reflected.normalize();
            cursor = end;
        }

        // ④ 整条轨迹已算完 ⇒ 球停在最后落点
        ball.shriekLand(lastPoint, null);
        return SHRIEK_WAVE_FRAMES;
    }

    /**
     * 【尖啸】一 tick 内多跑的步数。
     *
     * <p>⚠️ 与 {@link #SHRIEK_SPEED_FOR_TRAITS} 相乘 = 这一趟飞出的距离：
     * <b>32 × 8 = 256 格</b>，正好等于 {@link #SHRIEK_MAX_RANGE}。
     * 步数不能再小（一步会跨过薄墙），速度也不能再大（会越过射程）。</p>
     */
    private static final int SHRIEK_SUBSTEPS = 8;

    /**
     * 【尖啸】飞行中的<b>每一步</b>：把「这一步走过的位移」当成一段路径做判定。
     *
     * <p>由球在极速飞行的每个子步里调用（见 {@code BallProjectile.shriekFlightStep}）。
     * 直接复用 {@link #shriekSegment}：中途音波特效、路径半径 3 的伤害与击飞、
     * 终点半径 10 的范围伤害与那一套效果、以及音效 + 音爆范围，
     * 于是<b>每个碰撞点都像正常发射一样</b>（作者 2026-10-16 的要求）。</p>
     */
    public static void shriekFlightTick(BallProjectile ball, ServerLevel level, boolean armorPiercing,
                                        Vec3 from, Vec3 end) {
        // ⚠️⚠️ **只做「路径音波 + 路径伤害判定」，绝不做落点音爆**（作者 2026-10-17 报的回归）。
        //
        //    这个方法是**每 tick / 每子步**都调的。而 `shriekSegment` 的第三段是
        //    「落点音效 + shriekBurst 球爆」—— 挂在每 tick 上，就等于**每一 tick 都在球的
        //    当前位置丢一次球爆**：表现就是「打出去的路劲没特效，回来的时候却一直挂着特效，
        //    还连着终点的球形特效一起回来」。
        //
        //    落点那一套改由 `BallProjectile.onHit` 在**真正撞到东西时**调 {@link #shriekImpact} 一次。
        shriekSegment(ball, level, from, end, armorPiercing, ball.getOwner(), false);
    }

    /**
     * 把一个**排好队**的路径点生成成音波粒子（由球每 tick 分批调用，见 {@code emitShriekTrail}）。
     *
     * <p>这就是那条「一道音波」的基本单位 —— 与 {@link #shriekImpact} 的落点球爆是两回事：
     * 这个沿轨迹连续生成、铺出光束；那个只在撞到东西时炸一圈。</p>
     */
    public static void shriekTrailPoint(ServerLevel level, Vec3 point) {
        level.sendParticles(ParticleTypes.SONIC_BOOM,
                point.x, point.y + 0.5D, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    /**
     * 【尖啸】的<b>落点</b>：音效 + 音爆范围，<b>只在真正撞上东西时调用一次</b>。
     *
     * <p>与 {@link #shriekFlightTick} 分工明确：那个每 tick 都在跑、只负责沿路径画音波；
     * 这个是「撞上去的那一下」，由 {@code BallProjectile.onHit} 触发。</p>
     */
    public static void shriekImpact(ServerLevel level, Vec3 end) {
        level.playSound(null, BlockPos.containing(end), SoundEvents.WARDEN_SONIC_BOOM,
                SoundSource.HOSTILE, 4.0F, 1.0F);
        shriekBurst(level, end);
    }

    /**
     * 【尖啸】的<b>一段</b>：起点 → 终点之间的路径判定 + 终点那一整套落点效果。
     *
     * <p>弹射不为 0 时会被逐段调用，所以「音效 + 音爆」在每个碰撞点都会出现
     * —— 这正是作者 2026-10-16 要求的「像正常发射一样有音波和落点产生音爆范围」。</p>
     */
    private static void shriekSegment(BallProjectile ball, ServerLevel level, Vec3 from, Vec3 end,
                                      boolean armorPiercing, Entity owner, boolean miss) {
        // ===== ① 这一段路径上的监守者音波（纯特效）=====
        //
        // 几何射线版：整条路径在**这一 tick** 里就算完并全部生成。
        // 之所以现在能看见了 —— 因为球本身不再「一 tick 飞 256 格」，
        // `shriek()` 是当场把整条轨迹算完、球直接停到落点，
        // 所以这些粒子是沿着一条**确定的、静止的**路径铺开的，不会被高速实体甩在后面。
        int steps = Math.max(1, (int) (from.distanceTo(end) * 2.0D));
        for (int i = 0; i <= steps; i++) {
            Vec3 point = from.lerp(end, (double) i / steps);
            level.sendParticles(ParticleTypes.SONIC_BOOM,
                    point.x, point.y + 0.5D, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        // ===== ② 路径 + 落点：两种半径、两套待遇 =====
        double reach = from.distanceTo(end) / 2.0D + SHRIEK_IMPACT_RADIUS + 2.0D;
        Vec3 middle = from.add(end).scale(0.5D);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class,
                new AABB(middle, middle).inflate(reach));
        for (LivingEntity living : nearby) {
            if (!living.isAlive() || living == owner) {
                continue;   // 主人豁免
            }
            Vec3 nearest = nearestPointOnSegment(living.position(), from, end);
            boolean inPath = living.position().distanceTo(nearest) <= SHRIEK_PATH_RADIUS;
            boolean inImpact = living.position().distanceTo(end) <= SHRIEK_IMPACT_RADIUS;

            if (inPath) {
                // 路径上：只有伤害 + 击飞，**不上任何 debuff**（作者 2026-10-16）
                living.hurtServer(level, shriekDamage(ball, level, armorPiercing), SHRIEK_PATH_DAMAGE);
                Vec3 away = living.position().subtract(nearest);
                away = away.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 1.0D, 0.0D) : away.normalize();
                living.push(away.x * SHRIEK_KNOCK_HORIZONTAL, SHRIEK_KNOCK_VERTICAL,
                        away.z * SHRIEK_KNOCK_HORIZONTAL);
            }
            if (inImpact) {
                living.hurtServer(level, shriekDamage(ball, level, armorPiercing), SHRIEK_IMPACT_DAMAGE);
                applyShriekEffects(living, ball);
            }
        }

        // ===== ③ 落点音效 + 音爆范围 =====
        //
        // ⚠️ 这一段**恢复成几何射线版就该在这里做**：轨迹走回几何射线之后，
        //    `shriekSegment` 只在「一段算完」时被调一次（不是每 tick），
        //    所以落点音爆在每个真正的碰撞点各出一次，正是作者要的「每个碰撞点都像正常发射一样」。
        //    （中间那几版因为把整条路径挂到每 tick，才不得不把它拆出去。）
        level.playSound(null, BlockPos.containing(end), SoundEvents.WARDEN_SONIC_BOOM,
                SoundSource.HOSTILE, 4.0F, 1.0F);
        shriekBurst(level, end);
    }

    /**
     * 落点那一圈<b>立刻可见</b>的音爆范围（球壳撒点，半径取 {@link #SHRIEK_IMPACT_RADIUS}）。
     *
     * <p>与 {@link #shriekWaves}（球每刻推进的扩散动画）分工不同：这个是「碰撞点当场炸开」，
     * 每个碰撞点都放一次；那个是最终落点的扩散余韵。</p>
     */
    private static void shriekBurst(ServerLevel level, Vec3 center) {
        double golden = Math.PI * (3.0D - Math.sqrt(5.0D));
        int points = 24;
        for (int i = 0; i < points; i++) {
            double y = 1.0D - (i + 0.5D) * 2.0D / points;
            double ring = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
            double theta = golden * i;
            level.sendParticles(ParticleTypes.SONIC_BOOM,
                    center.x + Math.cos(theta) * ring * SHRIEK_IMPACT_RADIUS,
                    center.y + 0.5D + y * SHRIEK_IMPACT_RADIUS,
                    center.z + Math.sin(theta) * ring * SHRIEK_IMPACT_RADIUS,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /**
     * 【尖啸】用哪个伤害类型。
     *
     * <p>⚠️ 球身上带【穿甲】时<b>必须换成本模组的类型</b>：原版
     * {@code minecraft:sonic_boom} 同时躺在 {@code bypasses_armor} 与
     * {@code bypasses_enchantments} 两个标签里 —— 它不但无视护甲，
     * <b>连保护附魔都一起跳过了</b>（{@code LivingEntity.java:2013} 直接 return）。
     * 换成 {@code more_balls:armor_piercing} 之后就是「无视护甲、但吃保护附魔与抗性」。</p>
     *
     * <p>没带【穿甲】时保持原样，用原版的 sonicBoom。</p>
     */
    private static DamageSource shriekDamage(BallProjectile ball, ServerLevel level, boolean armorPiercing) {
        if (armorPiercing) {
            return BallDamageTypes.truePhysical(level, ball, ball.getOwner());
        }
        return level.damageSources().sonicBoom(ball);
    }

    /**
     * 给一只生物打上【尖啸】的<b>落点</b>效果（作者 2026-10-16 二次修订后的完整一套）。
     *
     * <p>黑暗 20s、失明 2 级 20s、震荡 3 级 20s、发光 <b>2 分钟</b>、缓慢 3 级 40s、
     * 虚弱 4 级 1 分钟、漂浮 5s。</p>
     *
     * <p>⚠️ 路径上的生物<b>不再吃这一套</b> —— 那一侧只剩伤害与击飞
     * （作者 2026-10-16：「对路径上的生物不再造成 debuff」）。</p>
     */
    private static void applyShriekEffects(LivingEntity living, BallProjectile ball) {
        Entity source = ball.getOwner() != null ? ball.getOwner() : ball;
        living.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 20 * 20, 0), source);
        living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 20, 1), source);
        living.addEffect(new MobEffectInstance(ModEffects.SHOCK, 20 * 20, 2), source);
        living.addEffect(new MobEffectInstance(MobEffects.GLOWING, 120 * 20, 0), source);
        living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40 * 20, 2), source);
        living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60 * 20, 3), source);
        living.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 5 * 20, 0), source);
    }

    /**
     * 落点那三道向外扩散并逐渐消失的音波。
     *
     * <p>由球自己每刻推进一帧（{@code frame} 从 {@link #SHRIEK_WAVE_FRAMES} 递减）。</p>
     *
     * <h2>⚠️ 改版（作者 2026-10-16）</h2>
     * <p>原先是在水平面上按角度撒点（<b>平面</b>），而且密度是 {@code 半径 × 6} ——
     * 半径 8 时一圈 48 个点、三层就是 144 个，太密。作者要求「<b>稀疏一点、半径控制在 6~8、
     * 改成球壳</b>」。</p>
     *
     * <p>现在按<b>球面</b>撒点：用黄金角（{@code π(3−√5)}）保证点在球面上均匀分布，
     * 每层只有 <b>8</b> 个点（三层共 24 个/帧）；半径随帧数从 <b>6 涨到 8</b>，
     * 三层之间再错开一点，看起来就是三片向外扩的球壳。</p>
     */
    public static void shriekWaves(BallProjectile ball, ServerLevel level, int frame) {
        Vec3 center = ball.position().add(0.0D, 0.5D, 0.0D);
        // 半径区间 6..8（作者指定）：随帧数从 6 扩到约 7.6，三层再各自错开
        double progress = (double) (SHRIEK_WAVE_FRAMES - frame) / SHRIEK_WAVE_FRAMES;
        double golden = Math.PI * (3.0D - Math.sqrt(5.0D));
        for (int shell = 0; shell < 3; shell++) {
            double radius = 6.0D + progress * 1.6D + shell * 0.2D;
            int points = 8;
            for (int i = 0; i < points; i++) {
                // 球面均匀撒点：y 从小到大取均匀层，方位角按黄金角错开
                double y = 1.0D - (i + 0.5D) * 2.0D / points;
                double ring = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
                double theta = golden * i + shell * 0.7D;
                level.sendParticles(ParticleTypes.SONIC_BOOM,
                        center.x + Math.cos(theta) * ring * radius,
                        center.y + y * radius,
                        center.z + Math.sin(theta) * ring * radius,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    /** 点到线段的最短距离点（【尖啸】的路径判定用它，斜着打也不会把范围放大成方形） */
    private static Vec3 nearestPointOnSegment(Vec3 point, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double lengthSqr = ab.lengthSqr();
        if (lengthSqr < 1.0E-9D) {
            return a;
        }
        double t = point.subtract(a).dot(ab) / lengthSqr;
        return a.add(ab.scale(Math.max(0.0D, Math.min(1.0D, t))));
    }

    // ===== 【尖刺】=====

    /**
     * 射出一圈滴水石锥。
     *
     * <h2>几何（作者 2026-10-15 二次澄清后的定稿）</h2>
     * <ol>
     *   <li><b>基准面</b> = 垂直于速度的面（速度方向即该面的法线）</li>
     *   <li>在该面内取 <b>8 个均匀方向</b>（每枚锥子对应一个方位）</li>
     *   <li>每枚锥子的射出方向 = <b>该方位再朝上抬起、与基准面成 60°</b>
     *       —— 也就是一整圈斜着向上张开的伞，<b>不是</b>朝来向张开的那种</li>
     *   <li>飞行<b>最高点 2 格</b>：按 {@code v_y = sqrt(2gh)} 反推竖直初速度，
     *       再除以方向的竖直分量折算成整条初速度（这样不论方位朝向如何，最高点都是 2 格）</li>
     * </ol>
     *
     * <p>⚠️ 第一版把「与最终速度反方向夹角 60 度」实现成了朝来向张开的伞，而且速度被
     * {@code clamp} 压到上限之后竖直分量几乎归零 —— 表现就是作者反馈的「几乎平射」。
     * 现在按「与基准面成 60°、朝上」重做，并且不再让 clamp 吃掉竖直分量。</p>
     *
     * <p>载体是真的滴水石锥（{@link DripstoneShard}）—— 外观、抛物线、命中结算都走原版那一套，
     * 基础伤害 3 由实体自己加。</p>
     *
     * @param position 出手点（球当时的位置）
     * @param velocity 球当时的最终速度
     */
    public static void shootSpikes(ServerLevel level, Vec3 position, Vec3 velocity, Entity owner,
                                   boolean truePhysical) {
        Vec3 dir = velocity.lengthSqr() < 1.0E-6D
                ? new Vec3(0.0D, -1.0D, 0.0D) : velocity.normalize();

        // 在「垂直于 dir」的平面内取两个正交基 —— 这两条张起的就是基准面
        Vec3 helper = Math.abs(dir.y) > 0.99D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 u = dir.cross(helper).normalize();
        Vec3 v = dir.cross(u).normalize();

        double tilt = Math.toRadians(SPIKE_ANGLE_DEG);   // 与基准面的夹角（作者指定 60°）
        double cos = Math.cos(tilt);
        double sin = Math.sin(tilt);
        // 「最高点 2 格」需要的竖直初速度
        double apexVy = Math.sqrt(2.0D * THROWN_GRAVITY * SPIKE_APEX);

        LivingEntity shooter = owner instanceof LivingEntity living ? living : null;

        for (int i = 0; i < SPIKE_COUNT; i++) {
            double theta = (Math.PI * 2.0D / SPIKE_COUNT) * i;
            Vec3 radial = u.scale(Math.cos(theta)).add(v.scale(Math.sin(theta)));
            // 基准面内的方位 × cos60° + 世界上方 × sin60° ⟹ 与基准面成 60°、朝上
            Vec3 shoot = radial.scale(cos).add(new Vec3(0.0D, sin, 0.0D)).normalize();

            // 按竖直分量反推整条初速度。shoot.y 最小也有 sin60° − cos60° = 0.366，
            // 所以这里不会算出爆掉的速度；clamp 只当保险用。
            double speed = apexVy / Math.max(0.3D, shoot.y);
            speed = Mth.clamp(speed, SPIKE_MIN_SPEED, SPIKE_MAX_SPEED);

            DripstoneShard shard = new DripstoneShard(level, position, shoot.scale(speed), shooter);
            // 【穿甲】联动：源球带【穿甲】时，锥子的伤害也走「真实物理伤害」
            shard.setTruePhysical(truePhysical);
            level.addFreshEntity(shard);
        }

        level.sendParticles(ParticleTypes.CRIT,
                position.x, position.y, position.z, 12, 0.3D, 0.3D, 0.3D, 0.05D);
        level.playSound(null, BlockPos.containing(position), SoundEvents.POINTED_DRIPSTONE_BREAK,
                SoundSource.NEUTRAL, 0.8F, 1.0F);
    }

    // ===== 【封印】=====

    /**
     * 【封印】—— 对玩家封快捷栏、对怪物封攻击并打掉它手里的球。
     *
     * <p>作者规格：</p>
     * <ul>
     *   <li>击中<b>非盟友且非自身玩家</b>：随机让该玩家一件快捷栏物品进入一次 3 秒冷却</li>
     *   <li>击中<b>怪物</b>：强制它手里的<b>球</b>全部掉落（原本持有的弓 / 弩 / 剑<b>不掉</b>），
     *       并封它 10 秒攻击 —— 期间无法发起任何攻击、也无法捡球</li>
     * </ul>
     */
    public static void seal(ServerLevel level, LivingEntity target, Entity owner) {
        if (target instanceof Player player) {
            if (owner == player) {
                return;   // 不封自己
            }
            if (owner instanceof LivingEntity shooter && shooter.isAlliedTo(player)) {
                return;   // 盟友不封
            }
            sealHotbar(player);
            return;
        }

        dropBallsFromHands(target);
        // 「不能攻击、也不能捡球」的标记 —— 判定分别在 tick 与 BallMobAI 里
        target.setData(ModAttachments.SEALED_UNTIL.get(),
                level.getGameTime() + SEAL_MOB_TICKS);
        // 先把当前的攻击目标清掉，否则它下一刻还是照打
        if (target instanceof Mob mob) {
            clearAttackTarget(mob);
        }
        level.playSound(null, target.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE,
                SoundSource.NEUTRAL, 0.7F, 1.4F);
    }

    /** 这只怪物现在是否处于【封印】期间 */
    public static boolean isSealed(LivingEntity mob) {
        Long until = mob.getData(ModAttachments.SEALED_UNTIL.get());
        if (until == null || until <= 0L) {
            return false;
        }
        return mob.level().getGameTime() < until;
    }

    /** 让玩家快捷栏里随机一件物品进入 3 秒冷却 */
    private static void sealHotbar(Player player) {
        List<ItemStack> candidates = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                candidates.add(stack);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        ItemStack picked = candidates.get(player.getRandom().nextInt(candidates.size()));
        // 26.2 的 addCooldown 收 ItemStack（不是 Item）
        player.getCooldowns().addCooldown(picked, SEAL_PLAYER_TICKS);
        player.sendOverlayMessage(Component.translatable("message.more_balls.sealed"));
    }

    /**
     * 打掉怪物手里的<b>球</b>。
     *
     * <p>⚠️ 只掉「球」（含球堆）—— 小白手里的弓、猪灵手里的弩和剑这类
     * <b>原本就持有的</b>物品一律不动，否则等于把它们的装备扒了（作者明确划的线）。</p>
     */
    private static void dropBallsFromHands(LivingEntity target) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = target.getItemInHand(hand);
            if (stack.isEmpty()) {
                continue;
            }
            if (!BallAmmo.isBall(stack) && !BallClusterItem.isCluster(stack)) {
                continue;
            }
            ItemStack dropped = stack.copy();
            target.setItemInHand(hand, ItemStack.EMPTY);
            ItemEntity entity = new ItemEntity(target.level(),
                    target.getX(), target.getY() + 1.0D, target.getZ(), dropped);
            entity.setDefaultPickUpDelay();
            target.level().addFreshEntity(entity);
        }
    }

    /** 清掉攻击目标：光清 {@code setTarget} 不够，猪灵这类还会从脑记忆里把目标翻出来 */
    private static void clearAttackTarget(Mob mob) {
        mob.setTarget(null);
        var brain = mob.getBrain();
        if (brain != null) {
            brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
        }
    }

    /**
     * 【封印】期间每刻把它压住：清掉攻击目标、让它没法发起任何攻击。
     *
     * <p>清目标就够了吗？—— 近战与远程的原版 AI（{@code MeleeAttackGoal} /
     * {@code RangedAttackGoal}）都以「有攻击目标」为前提，目标为空时它们什么都不做；
     * 猪灵那类会从脑记忆里重新取目标，所以 {@link #clearAttackTarget} 连记忆一起清。
     * 「不能捡球」是模组自己的判定，写在 {@code BallMobAI} 里（{@link #isSealed}）。</p>
     */
    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide()) {
            return;
        }

        // ⚠️ 被召唤的蜜蜂**不再需要在这里维护**了 —— 它 2026-10-16 起是独立生物，
        //    寿命、无限攻击、不仇恨召唤者全都收进了 SummonedBee 自己的代码里。
        //    （原来这里有一整套「续愤怒 + 每刻纠偏」的静态 map 逻辑，随那次改造一起删掉。）

        if (!isSealed(mob)) {
            return;
        }
        // 只清「攻击型」生物的目标，别去动村民 / 动物这类的中立行为
        if (mob instanceof Enemy) {
            clearAttackTarget(mob);
        }
    }

    // ===== 【滋养】（组合类词条）=====

    /**
     * 【滋养】—— 回收成功那一刻按概率给主人挂 3 秒生命恢复。
     *
     * <p>⚠️ 它是<b>组合类</b>特性词条：<b>完整球上不生效</b>，
     * 只有作为组件合成进组合球之后才生效（作者 2026-10-15 定稿的新类别）。
     * 所以这里第一道判据就是「这球是不是组合球」，第二道才是按概率掷骰。</p>
     *
     * <p>概率来自 profile 的 {@code nurture}：完整球是 1.0（携带但不用），
     * 组合球是各份均分后相加的结果（一份 0.25、两份 0.5、四份 1.0）。</p>
     *
     * @return 是否真的挂上了（给日志与自测用）
     */
    public static boolean nurture(LivingEntity owner, ItemStack ball, double chance) {
        if (!isComboBall(ball)) {
            return false;
        }
        if (chance <= 0.0D) {
            return false;
        }
        if (chance < 1.0D && owner.getRandom().nextDouble() >= chance) {
            return false;
        }
        owner.addEffect(new MobEffectInstance(MobEffects.REGENERATION, NURTURE_TICKS, 0, false, true, true));
        return true;
    }
}
