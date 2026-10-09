package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.BallProjectile;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 金球的【金光闪闪】—— 猪灵全套行为。
 *
 * <h2>四条规则（作者指定）</h2>
 * <ol>
 *   <li><b>伤害不结仇</b>：金球打猪灵不会让它记恨投掷者</li>
 *   <li><b>被撞击声吸引</b>：金球落在哪儿，附近的猪灵（含<b>猪灵蛮兵</b>）就朝哪儿走，
 *       而且期间不会因为任何别的原因转移注意</li>
 *   <li><b>可被捡起</b>：金球是「可交易的金制品」，被猪灵捡走（蛮兵除外）</li>
 *   <li><b>特殊交易</b>：非幼小猪灵捡起后当场交易；若它<b>没被这颗金球打过</b>，
 *       这次是<b>特殊交易</b>，会一口气给出多次交易产物</li>
 * </ol>
 *
 * <h2>关于交易产物</h2>
 * 原版的猪灵交易表 {@code PiglinAi.getBarterResponseItems} 是 private，外部拿不到，
 * 所以这里自己维护一份等价的标准产物表（数值对齐原版）。
 * 特殊交易的<b>次数</b>按作者给出的权重抽，<b>产物</b>从表里随机取。
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class PiglinLure {

    private PiglinLure() {
    }

    /** 吸引半径（格）—— 作者说明的是「所有猪灵」，实际给一个够大的范围，免得满世界寻路 */
    public static final double LURE_RADIUS = 32.0D;

    /**
     * 被金球吸引时，每隔多少刻清一次猪灵的攻击目标。
     *
     * <p>每刻都清会和猪脑打架 —— 它每刻重新发现玩家、重新记恨，
     * 于是仇恨在「有 / 无」之间抖，看起来就是抽搐（作者 2026-10-08 报的）。
     * 降频之后既不会分心去打别人，也不会抖。</p>
     */
    public static final int ANGER_CLEAR_INTERVAL = 10;

    /**
     * 单次「欣赏金球」的时长（刻），对齐原版 {@code PiglinAi.admireGoldItem} 的 119 刻。
     *
     * <p>写成这个记忆之后，猪脑的 activity 会切到 ADMIRE_ITEM ——
     * 那一档里没有战斗行为、也没有「重新选目标」，于是 FIGHT 与抽搐都停了。</p>
     */
    public static final long ADMIRE_DURATION = 119L;

    /**
     * 【金光闪闪】窗口期内，多久重设一次 ADMIRING_ITEM（刻）。
     *
     * <p><b>2026-10-09 改回「每刻」。</b></p>
     *
     * <p>先前为了避免音效刷屏改成了 20 刻 —— 那个改动解决了一半、制造了另一半：
     * 见 {@link #ensureBait} 的说明，原版的传感器会**每刻**擦掉 ADMIRING_ITEM，
     * 所以 20 刻的间隔意味着中间 19 刻 activity 已经切回 FIGHT / CORE ——
     * 猪灵又开始攻击、又开始抽搐（作者报的「吸引好像又失效了」）。</p>
     *
     * <p>现在有了隐形金锭当饵，activity 会稳定停在 ADMIRE_ITEM、不会反复「重新进入」，
     * 于是音效本来就不会重复播 —— 每刻重设只是刷新记忆的过期时间，是安全的。</p>
     */
    public static final int ADMIRE_REFRESH_INTERVAL = 1;
    /**
     * 球 UUID → 它的「隐形金锭」实体 id。
     *
     * <p>见 {@link #ensureBait} 的说明 —— 那是让猪灵稳定停在 ADMIRE_ITEM 的关键。</p>
     */
    private static final Map<UUID, Integer> BAIT_IDS = new ConcurrentHashMap<>();

    /**
     * 待清理的球 UUID —— <b>延迟一 tick 再清</b>。
     *
     * <p>⚠️ 不能直接在 {@code EntityLeaveLevelEvent} 里 discard 饵（2026-10-09 实测崩服）：
     * 那个事件**会在原版 {@code PersistentEntitySectionManager.updateChunkStatus}
     * 遍历某个区块的实体列表时触发**（区块卸载要移除实体）——
     * 我们此时再 discard 掉另一个实体，就等于**在遍历中途改列表**，
     * 直接抛 {@code ConcurrentModificationException}，整个世界 tick 崩掉。</p>
     *
     * <p>所以事件里**只登记**，真正的 discard 挪到 {@code ServerTickEvent}——
     * 那时不在任何实体遍历里，改列表是安全的。</p>
     */
    private static final Set<UUID> PENDING_CLEAR = ConcurrentHashMap.newKeySet();

    /** 被吸引时多久重设一次导航（刻） */
    public static final int LURE_NAV_INTERVAL = 5;

    /** 被吸引时的行走速度（略快于闲逛，看得出它们很急） */
    private static final double LURE_SPEED = 1.2D;

    /**
     * 被吸引的猪灵 → 静音截止的游戏刻。
     *
     * <p>{@link #lure} 每刻刷新；{@code onPlaySound} 据此判断「现在是不是吸引期间」。</p>
     */
    private static final Map<UUID, Long> LURED_UNTIL = new ConcurrentHashMap<>();

    /**
     * 静音标记的有效期（刻）。
     *
     * <p>{@code lure} 是每刻调用的，这个宽限只是为了在「球刚好消失的那一瞬」不立刻恢复吵闹，
     * 同时让 {@link #LURED_UNTIL} 里的过期项能被自动忽略。</p>
     */
    private static final int LURE_SILENCE_GRACE = 20;

    /** 走到离球多近算「到了」（格）—— 太近会挤成一团，2 格刚好 */
    private static final int LURE_CLOSE_ENOUGH = 2;

    /** 特殊交易次数分布（作者指定）：5 次 40 / 4 次 20 / 3 次 10 / 2 次 8 / 1 次 5 */
    private static final int[] TRADE_WEIGHTS = {40, 20, 10, 8, 5};

    /**
     * 特殊交易的产物池 —— 对齐原版猪灵交易的标准产出。
     *
     * <p>⚠️ <b>这里必须是「每次现构造」，不能提成 static final 字段</b>（2026-10-09 踩到的）。</p>
     *
     * <p>本类挂了 {@code @EventBusSubscriber}，于是 FML 会在 <b>mod 构造阶段</b>就加载它。
     * 而 {@code new ItemStack(...)} 需要注册表已就绪 —— 在构造阶段还没有。
     * 一旦把它写成静态字段，类初始化就会抛 {@code ExceptionInInitializerError}，
     * 表现为启动直接崩在 {@code Failed to wait for future Mod Construction}。</p>
     *
     * <p>交易一次只调一次、产物也就 13 种，现构造的代价可以忽略。</p>
     */
    private static List<ItemStack> tradePool() {
        return List.of(
            new ItemStack(Items.SPECTRAL_ARROW, 12),
            new ItemStack(Items.LEATHER, 3),
            new ItemStack(Items.SOUL_SAND, 8),
            new ItemStack(Items.OBSIDIAN, 1),
            new ItemStack(Items.BLAZE_POWDER, 3),
            new ItemStack(Items.ENDER_PEARL, 2),
            new ItemStack(Items.STRING, 6),
            new ItemStack(Items.CRYING_OBSIDIAN, 1),
            new ItemStack(Items.ARROW, 9),
            new ItemStack(Items.IRON_NUGGET, 20),
            new ItemStack(Items.QUARTZ, 8),
            new ItemStack(Items.GLOWSTONE_DUST, 4),
            new ItemStack(Items.MAGMA_CREAM, 2));
    }

    /** 金球撞击/落地的声音 —— 猪灵就是被这个吸引来的 */
    private static final float LURE_SOUND_VOLUME = 1.0F;

    // ===== 一、伤害不结仇 =====

    /**
     * 把猪灵因这次金球伤害而起的怒气抹掉。
     *
     * <p>猪灵的仇恨走 Brain 的记忆模块（{@code ANGRY_AT} / {@code ATTACK_TARGET}），
     * 光清 {@code setTarget} 不够 —— 那只是抢它的当前目标，记忆还在，过一刻又会回来。</p>
     */
    public static void forgetAnger(AbstractPiglin piglin) {
        // 清记忆模块本身就是幂等的，不需要先查有没有
        piglin.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
        piglin.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        piglin.setTarget(null);
    }

    // ===== 二、被金球吸引 =====

    /**
     * 金球躺在地上时的每一刻调用：把附近的猪灵全喊过来。
     *
     * <h2>为什么不擦 ATTACK_TARGET</h2>
     * <p>原来这里每刻（后来改成每 10 刻）扫掉猪脑的 {@code ATTACK_TARGET}，想让它「专心朝球走」。
     * 但那是在跟原版机制正面对撞 —— 猪脑的 {@code StartAttacking} 每刻检查
     * 「{@code ATTACK_TARGET} 不存在就重新选一个」，而它对玩家的判据
     * （玩家没穿金装备）几乎永远成立。于是每 10 刻完成一次
     * 「重新锁定玩家 → 被我们擦掉 → 再锁定」的循环，表现就是猪灵原地抽搐
     * （作者 2026-10-08 报的）。同时 FIGHT activity 里那条
     * {@code SetWalkTargetFromAttackTargetIfTargetOutOfReach} 每刻把导航终点钉回玩家，
     * 我们那句 {@code moveTo(球)} 根本轮不到执行。</p>
     *
     * <h2>正确做法：压住「选目标」这件事本身</h2>
     * <p>给猪灵写上 {@code ADMIRING_ITEM} 记忆即可。原版的 activity 优先级是
     * {@code [ADMIRE_ITEM, FIGHT, AVOID, CELEBRATE, RIDE, IDLE]}，
     * 而 {@code ADMIRE_ITEM} 的行为表里<b>没有任何战斗行为、也没有 StartAttacking</b> ——
     * 一旦它生效，FIGHT 与「重新选目标」整段都不跑，寻路也交给我们的 {@code moveTo}。
     * 这正是原版「猪灵被金锭吸引」用的机制，不新增 Goal、不碰 Mixin。</p>
     */
    public static void lure(ServerLevel level, BallProjectile ball) {
        Vec3 pos = ball.position();

        // 先把「饵」摆好 —— 这是让 activity 稳定停在 ADMIRE_ITEM 的前提，见 ensureBait
        ensureBait(level, ball);

        // ⚠️ 不能隔刻做。
        //
        // 原版 PiglinAi 的 ADMIRE_ITEM activity 里挂着 StopAdmiringIfItemTooFarAway，
        // 它的实现是「最近可见的『喜爱的物品』(只认 ItemEntity) 不在 9 格内 → 擦掉
        // ADMIRING_ITEM」；我们的球是投射物实体、永远进不了那个 sensor，
        // 所以它**每刻都会把记忆擦掉**。
        // 于是只有「每刻重新设一次」才能把 activity 稳在 ADMIRE_ITEM ——
        // 隔刻设的话会变成「设 → 被擦 → 设 → 被擦」的一刻一跳（作者报的抽搐的加强版）。
        List<AbstractPiglin> piglins = level.getEntitiesOfClass(AbstractPiglin.class,
                new net.minecraft.world.phys.AABB(pos, pos).inflate(LURE_RADIUS),
                piglin -> piglin.isAlive() && !piglin.isBaby());
        if (piglins.isEmpty()) {
            return;
        }

        boolean clearAnger = level.getGameTime() % ANGER_CLEAR_INTERVAL == 0L;
        long silenceUntil = level.getGameTime() + LURE_SILENCE_GRACE;
        for (AbstractPiglin piglin : piglins) {
            Brain<?> brain = piglin.getBrain();
            // 登记「这只正在被吸引」—— 静音监听靠它判断
            LURED_UNTIL.put(piglin.getUUID(), silenceUntil);

            // ① 压住战斗意图（**每刻**刷新）。
            //
            // ⚠️ 2026-10-09 改回每刻。先前为了避免音效刷屏改成 20 刻，
            //    那个改动解决了一半、制造了另一半（作者报的「吸引好像又失效了」）：
            //    原版传感器每刻都会擦掉 ADMIRING_ITEM（见 ensureBait 的说明），
            //    所以 20 刻的间隔意味着中间 19 刻 activity 已经切回 FIGHT / CORE ——
            //    猪灵又开始攻击、又开始抽搐。
            //    现在有了隐形金锭当饵，activity 稳定在 ADMIRE_ITEM、不会反复「重新进入」，
            //    音效本来就不会重复播，每刻重设只是刷新记忆的过期时间。
            //
            //    这一条**对蛮兵无效**（PiglinBruteAi 根本没有 ADMIRE_ITEM 这个 activity），
            //    蛮兵靠的是下面的 ② 与 ③。
            if (level.getGameTime() % ADMIRE_REFRESH_INTERVAL == 0L) {
                brain.setMemoryWithExpiry(MemoryModuleType.ADMIRING_ITEM, true, ADMIRE_DURATION);
            }

            // ② 每刻清掉攻击意图 —— 这就是作者要的「直接取消仇恨」。
            //
            //    作者反馈「被吸引还是有攻击意图，拿弩的猪灵会正常攻击」：
            //    远程攻击走的是 charge/attack 那套，ADMIRE_ITEM 压不住它，
            //    因为它并不经过 FIGHT 的近战分支。真正决定「能不能攻击」的是
            //    brain 里的 ATTACK_TARGET —— 只要它每刻都是空的，
            //    远程与近战就都找不到可打的目标。
            //    注意**不要**调 setTarget：AbstractPiglin 覆写了 getTarget() 读脑里的
            //    ATTACK_TARGET，而 Mob.setTarget 只写自己的字段，对猪灵系是纯空操作。
            brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
            if (clearAnger) {
                brain.eraseMemory(MemoryModuleType.ANGRY_AT);
                brain.eraseMemory(MemoryModuleType.HURT_BY);
            }

            // ③ 导航：**写 Brain 的 WALK_TARGET**，而不是直接驱动 Navigation。
            //
            // ⚠️ 2026-10-09 修：原来是 piglin.getNavigation().moveTo(...) ——
            //    对用 Brain 的生物（猪灵、蛮兵）**基本没用**：
            //    Brain 的 MoveToTargetSink（挂在 CORE）每刻读 WALK_TARGET 记忆来驱动导航，
            //    我们直接改导航只是「抢方向盘」，下一 tick 就被它按记忆重新覆盖回去。
            //    后果就是作者报的「蛮兵根本没被吸引」。
            //
            //    写 WALK_TARGET 才是正路：brain 自己会走过去，也不用每刻盯。
            //    每 LURE_NAV_INTERVAL 刻重设一次，是防止到达后被 IDLE 的漫步行为改掉。
            if (piglin.tickCount % LURE_NAV_INTERVAL == 0) {
                brain.setMemory(MemoryModuleType.WALK_TARGET,
                        new WalkTarget(pos, (float) LURE_SPEED, LURE_CLOSE_ENOUGH));
            }
        }

        // 偶尔响一声，让玩家听得出来「它们被吸引过来了」
        if (level.getGameTime() % 40L == 0L) {
            level.playSound(null, pos.x, pos.y, pos.z,
                    SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.NEUTRAL,
                    LURE_SOUND_VOLUME, 0.8F);
        }
    }

    /**
     * 保证球的位置上有一个「<b>隐形金锭</b>」—— 猪灵的 sensor 只认 {@code ItemEntity}。
     *
     * <h2>为什么必须这么做</h2>
     * <p>原版 {@code PiglinAi} 的 ADMIRE_ITEM 行为表里挂着 {@code StopAdmiringIfItemTooFarAway(9)}：</p>
     * <pre>
     * Optional&lt;ItemEntity&gt; nearest = brain.get(NEAREST_VISIBLE_WANTED_ITEM);
     * if (nearest.isPresent() &amp;&amp; nearest.get().closerThan(body, 9)) return false;  // 不擦
     * admiring.erase();                                                            // 擦掉
     * </pre>
     * <p>而 {@code NEAREST_VISIBLE_WANTED_ITEM} 这个记忆**只可能由 {@code ItemEntity} 填上** ——
     * 我们的球是投射物，永远进不了那个传感器。于是它**每刻都把 ADMIRING_ITEM 擦掉**。</p>
     *
     * <p>擦掉之后 activity 就切回 FIGHT / CORE，而 {@code StartAdmiringItemIfSeen}
     * 挂在 CORE 里、每刻又把它设回来 —— 两边拉锯。表现就是作者报的：
     * <b>猪灵反复抽搐、一直叫、还试图攻击玩家</b>。</p>
     *
     * <h2>饵的作用</h2>
     * <p>在球的位置放一个金锭，原版那套机制就<b>原封不动地跑起来</b>：</p>
     * <ul>
     *   <li>{@code StartAdmiringItemIfSeen}（CORE）看到它 → 自己设 ADMIRING_ITEM</li>
     *   <li>{@code StopAdmiringIfItemTooFarAway} 发现 9 格内有它 → <b>不擦</b></li>
     *   <li>{@code GoToWantedItem} 让它自己朝金锭走 —— 连导航都不用我们推</li>
     * </ul>
     * <p><b>蛮兵是例外</b>：{@code PiglinBruteAi} 只注册了 CORE / IDLE / FIGHT 三个 activity，
     * <b>根本没有 ADMIRE_ITEM</b>，也没有 {@code StartAdmiringItemIfSeen} —— 饵对它无效。
     * 蛮兵靠的是 {@code lure()} 里的另外两条：每刻擦掉 {@code ATTACK_TARGET}（它就不会进 FIGHT），
     * 以及给它写 {@code WALK_TARGET}（Brain 自己会走过去）。</p>
     * <p>activity 一旦稳定，就既不会抽搐、也不会反复「重新进入」而重复播音效。
     * 这也是 AGENTS 里那条「原版也有的功能 → 照原版实现」的做法：不去正面对撞 Brain，
     * 而是补齐它本来就依赖的那个条件。</p>
     *
     * <h2>饵的属性</h2>
     * <ul>
     *   <li><b>隐形</b> —— 玩家看不见</li>
     *   <li><b>无重力</b> —— 悬在球的位置，不落地</li>
     *   <li><b>不可拾取</b>（{@code pickUpDelay} 拉满）—— 玩家/漏斗都拿不走</li>
     *   <li>不设无限寿命 —— 让原版 5 分钟的过期逻辑当兜底，球没了也不会留下永久实体</li>
     * </ul>
     */
    private static void ensureBait(ServerLevel level, BallProjectile ball) {
        Vec3 pos = ball.position();
        Integer id = BAIT_IDS.get(ball.getUUID());
        if (id != null) {
            Entity existing = level.getEntity(id);
            if (existing instanceof ItemEntity bait && bait.isAlive()) {
                // 跟着球走：球在飞，饵也得飞
                bait.setPos(pos.x, pos.y, pos.z);
                return;
            }
            // 记录失效（实体没了 / 过期了）→ 重新做
            BAIT_IDS.remove(ball.getUUID());
        }

        ItemEntity bait = new ItemEntity(level, pos.x, pos.y, pos.z, new ItemStack(Items.GOLD_INGOT));
        bait.setInvisible(true);
        bait.setNoGravity(true);
        bait.setPickUpDelay(Integer.MAX_VALUE);
        level.addFreshEntity(bait);
        BAIT_IDS.put(ball.getUUID(), bait.getId());
    }

    /**
     * 球消失（被捡走 / 破碎 / 掉出世界）时**登记**要清掉的饵。
     *
     * <p>不收的话那颗隐形金锭会留在原地继续吸引猪灵 ——
     * 最直接的后果是「猪灵捡起金球之后，还站在原地欣赏一堆空气」。</p>
     *
     * <p>⚠️ <b>这里绝对不能直接 discard</b>（2026-10-09 实测把服务器 tick 崩掉）：
     * 本事件会在原版 {@code updateChunkStatus} 遍历区块实体列表时触发，
     * 此时再移除另一个实体就会抛 {@code ConcurrentModificationException}。
     * 所以只登记 UUID，真正的清理交给下面的 {@link #onServerTickCleanup}。</p>
     */
    @SubscribeEvent
    public static void onBallGone(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof BallProjectile ball) || ball.level().isClientSide()) {
            return;
        }
        PENDING_CLEAR.add(ball.getUUID());
    }

    /**
     * 吸引期间给蛮兵静音（作者 2026-10-09 指定：<b>只在吸引期间静音</b>）。
     *
     * <h2>为什么需要这个</h2>
     * <p>蛮兵的原版音效有两处会一直响：</p>
     * <ul>
     *   <li>{@code getAmbientSound()} → {@code PIGLIN_BRUTE_AMBIENT} —— 常态环境音，与愤怒无关</li>
     *   <li>{@code playAngrySound()} → {@code PIGLIN_BRUTE_ANGRY} —— 由
     *       {@code PiglinBruteAi.maybePlayActivitySound} 在 {@code activity == FIGHT} 时按 1.25%/刻 播</li>
     * </ul>
     *
     * <p>而蛮兵**压不进非战斗 activity**：{@code PiglinBruteAi} 只注册了 CORE / IDLE / FIGHT，
     * 没有 {@code ADMIRE_ITEM}；而且 {@code findNearestValidAttackTarget} 会通过
     * {@code NEAREST_VISIBLE_ATTACKABLE_PLAYER}（sensor 每刻重填，外部清不掉）找到玩家，
     * 于是 {@code ATTACK_TARGET} 每刻被设、activity 每刻切 FIGHT ——
     * {@code customServerAiStep} 里 {@code brain.tick → updateActivity → maybePlayActivitySound}
     * 全在同一 tick 内，我们的擦除（{@code EntityTickEvent.Post}）赶不上。</p>
     *
     * <p>所以不走「压 activity」那条路，直接在**声音播出去之前拦掉** ——
     * 用 NeoForge 官方的 {@link PlayLevelSoundEvent.AtEntity}（{@code ICancellableEvent}），
     * 不碰原版代码、不需要 Mixin。</p>
     *
     * <p><b>只在吸引期间生效</b>：标记由 {@link #lure} 每刻刷新，球走了 / 没了就自动失效，
     * 蛮兵恢复原版叫声。</p>
     */
    @SubscribeEvent
    public static void onPlaySound(PlayLevelSoundEvent event) {
        if (event.getLevel().isClientSide()) {
            return;   // 判断依据（LURED_UNTIL）只在服务端维护；服务端拦掉就不会广播给客户端
        }

        SoundEvent sound = event.getSound().value();
        if (sound != SoundEvents.PIGLIN_BRUTE_ANGRY && sound != SoundEvents.PIGLIN_BRUTE_AMBIENT) {
            return;   // 只管这两种，别的声音一律放行（避免每次播放都去查实体）
        }

        // ⚠️ 必须同时处理两种子类型（2026-10-09 踩到：第一版只监听 AtEntity，完全没生效）。
        //
        //    蛮兵那两处音效都是走 makeSound(...) → LivingEntity.makeSound
        //    → Entity.playSound(sound, vol, pitch) → level().playSound(null, x, y, z, …)
        //    —— **位置版本**，触发的是 AtPosition，压根到不了 AtEntity 那一支。
        if (event instanceof PlayLevelSoundEvent.AtEntity atEntity) {
            if (atEntity.getEntity() instanceof AbstractPiglin piglin && isBeingLured(event, piglin)) {
                event.setCanceled(true);
            }
            return;
        }

        if (event instanceof PlayLevelSoundEvent.AtPosition atPosition) {
            // 位置版本拿不到「是谁发的」，只能按坐标认领：附近有正在被吸引的猪灵就拦掉。
            // 只对上面那两种声音做这一步，代价可以忽略。
            Vec3 pos = atPosition.getPosition();
            AABB box = new AABB(pos.x - 4.0D, pos.y - 4.0D, pos.z - 4.0D,
                    pos.x + 4.0D, pos.y + 4.0D, pos.z + 4.0D);
            for (AbstractPiglin piglin : event.getLevel().getEntitiesOfClass(AbstractPiglin.class, box)) {
                if (isBeingLured(event, piglin)) {
                    event.setCanceled(true);
                    return;
                }
            }
        }
    }

    /** 这只猪灵此刻是否处于「被金球吸引」期间 */
    private static boolean isBeingLured(PlayLevelSoundEvent event, AbstractPiglin piglin) {
        Long until = LURED_UNTIL.get(piglin.getUUID());
        return until != null && until >= event.getLevel().getGameTime();
    }

    /** 真正清掉饵的地方 —— 在 ServerTick 阶段，不在任何实体遍历里 */
    @SubscribeEvent
    public static void onServerTickCleanup(ServerTickEvent.Post event) {
        // 顺带清掉过期的静音标记，免得 LURED_UNTIL 无限膨胀
        long now = event.getServer().overworld() == null ? 0L : event.getServer().overworld().getGameTime();
        LURED_UNTIL.entrySet().removeIf(e -> e.getValue() < now);

        if (PENDING_CLEAR.isEmpty()) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (java.util.Iterator<UUID> it = PENDING_CLEAR.iterator(); it.hasNext(); ) {
                UUID ballId = it.next();
                it.remove();
                Integer baitId = BAIT_IDS.remove(ballId);
                if (baitId == null) {
                    continue;
                }
                Entity bait = level.getEntity(baitId);
                if (bait != null) {
                    bait.discard();
                }
            }
        }
    }

    // ===== 三、被捡起后的特殊交易 =====

    /**
     * 猪灵捡到金球之后的处理。
     *
     * <p>只有<b>普通猪灵</b>（非蛮兵、非幼小）会交易；蛮兵只会拿着球不办事。</p>
     *
     * @param ball 被捡起的那颗金球实体
     */
    public static void onPiglinPickedUpGoldBall(Piglin piglin, @Nullable BallProjectile ball) {
        if (piglin.isBaby()) {
            return; // 幼小猪灵不交易
        }

        boolean hurtByThisBall = Boolean.TRUE.equals(
                piglin.getExistingDataOrNull(ModAttachments.GOLD_BALL_HURT.get()));
        if (!hurtByThisBall) {
            // 没被这颗金球打过 → 特殊交易
            dropTradeGoods(piglin, rollTradeCount(piglin.getRandom().nextFloat()));
        } else {
            // 被它打过 → 普通的单次回礼
            dropTradeGoods(piglin, 1);
        }

        // 交易完把标记清掉，免得影响下一次
        piglin.setData(ModAttachments.GOLD_BALL_HURT.get(), false);

        // 触发「金光闪闪」成就 —— 记在投掷者头上（球上留着 owner）
        awardGoldShiny(ball);

        // 「对不起……你在听吗？」：金球砸中猪灵、并且**用同一只猪灵**触发了交易。
        // 到这里就说明两个条件都成立 —— 这次调用本身就是「同一只猪灵完成交易」，
        // 而球上留着 owner 就能确认「是玩家用金球砸出来的那颗」。
        if (ball != null && ball.getOwner() instanceof Player thrower) {
            ModAdvancements.award(thrower, ModAdvancements.PIGLIN_TRADE_WITH_BALL);
        }
    }

    /**
     * 触发原版的「金光闪闪」成就。
     *
     * <p>这个成就（{@code minecraft:nether/distract_piglin}）原版的触发条件是
     * 「投掷物被成年猪灵捡起，且道具属于 {@code #minecraft:piglin_loved}」。
     * 我们的球是自定义投射物、被自定义逻辑捡起，原版那条事件链不会走到，
     * 所以这里手动解锁。</p>
     *
     * <p><b>注意</b>：手动 {@code award} 不会替我们校验条件，所以判据得自己保证 ——
     * 调用点已经限定在「非幼小的猪灵捡到金球」这一刻，与原版的场景一致
     * （金球本身也加进了 {@code #minecraft:piglin_loved} 标签）。</p>
     */
    /**
     * 触发原版的「金光闪闪」成就。
     *
     * @param ball 被捡起的那颗金球实体；<b>掉落物形态的球没有实体</b>，此时传 {@code null}，
     *             成就就不发 —— 那颗球上没留投掷者信息，本来就无从归属。
     */
    private static void awardGoldShiny(@Nullable BallProjectile ball) {
        if (ball == null) {
            return;
        }
        if (!(ball.getOwner() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        AdvancementHolder holder = level.getServer().getAdvancements()
                .get(Identifier.withDefaultNamespace("nether/distract_piglin"));
        if (holder != null) {
            player.getAdvancements().award(holder, "distract_piglin");
        }
    }

    /**
     * 按作者给出的权重抽这次特殊交易给几次。
     *
     * <p>40% → 5 次、20% → 4 次、10% → 3 次、8% → 2 次、5% → 1 次。</p>
     */
    public static int rollTradeCount(float roll) {
        int total = 0;
        for (int weight : TRADE_WEIGHTS) {
            total += weight;
        }
        float scaled = roll * total;
        for (int i = 0; i < TRADE_WEIGHTS.length; i++) {
            scaled -= TRADE_WEIGHTS[i];
            if (scaled < 0.0F) {
                return TRADE_WEIGHTS.length - i; // 5 / 4 / 3 / 2 / 1
            }
        }
        return 1;
    }

    /** 把 {@code count} 次交易产物丢在猪灵脚边 */
    private static void dropTradeGoods(AbstractPiglin piglin, int count) {
        if (!(piglin.level() instanceof ServerLevel level)) {
            return;
        }
        List<ItemStack> pool = tradePool();
        for (int i = 0; i < count; i++) {
            ItemStack goods = pool.get(level.getRandom().nextInt(pool.size())).copy();
            piglin.spawnAtLocation(level, goods, 0.3F);
        }
        level.playSound(null, piglin.getX(), piglin.getY(), piglin.getZ(),
                SoundEvents.PIGLIN_ADMIRING_ITEM, SoundSource.NEUTRAL, 1.0F, 1.0F);
    }

    /** 金球被猪灵拿在手上时，它算「拿着喜爱之物」—— 别让它继续战斗 */
    public static void holdGoldBallPeacefully(Piglin piglin) {
        forgetAnger(piglin);
        piglin.setItemSlot(EquipmentSlot.MAINHAND, piglin.getMainHandItem());
    }
}
