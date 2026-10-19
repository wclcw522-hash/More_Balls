package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.BallProjectile;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 右键投掷 —— 任何带 {@code #rotation_craft:balls} 标签的物品都能像雪球一样扔出去。
 *
 * <h2>两条出手路径</h2>
 * <ul>
 *   <li><b>可蓄力的球</b>（{@code chargeLevels > 0}，默认 3）：右键按下进入蓄力状态，
 *       松开时按已蓄级数出手。等级由 {@link BallCharge} 的时间曲线决定</li>
 *   <li><b>不能蓄力的球</b>（{@code chargeLevels == 0}）：按下即出手，与原版雪球一致</li>
 * </ul>
 *
 * <p>整条链路走 NeoForge 官方事件（{@code RightClickItem} + {@code LivingEntityUseItemEvent}），
 * <b>不需要 Mixin 去改任何原版物品类</b>。</p>
 *
 * <h2>伤害公式（投掷路径）</h2>
 * <p>{@code 最终伤害 = 初始伤害 × (最终速度 / 无蓄力投掷速度)}——
 * 蓄力每级 +10% 初速，伤害随之等比上升。</p>
 */
public final class BallThrowHandler {

    private BallThrowHandler() {
    }

    /** 右键按下：可蓄力的球进入蓄力状态，其余立即出手 */
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        if (!BallBehavior.handles(stack)) {
            return;
        }
        Player player = event.getEntity();
        BallBehavior.BallProfile profile = BallBehavior.profileFor(stack);

        if (profile.chargeLevels() > 0) {
            // 开始蓄力；真正的出手在松开右键时（onStopUsing）
            player.startUsingItem(event.getHand());
            event.setCancellationResult(InteractionResult.CONSUME);
            event.setCanceled(true);
            return;
        }

        throwBall(player, stack, 0);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    /**
     * 蓄力条的长度：给一个足够长的使用时长，让玩家自己决定何时松手。
     * 若不改时长，原版物品默认 0 会让使用状态立刻结束。
     *
     * <p><b>球堆（壳）也走这里</b>（作者 2026-10-15）—— 手持壳蓄力时，手上的物品是壳，
     * 但「能不能蓄力」由<b>壳里那颗球</b>决定，所以先 {@link BallClusterItem#asBall 摊开}
     * 拿一颗来读 profile。{@code asBall} 返回的是副本，探测不会动到壳。</p>
     */
    public static void onStartUsing(LivingEntityUseItemEvent.Start event) {
        ItemStack stack = event.getItem();

        // ===== 球堆：能不能蓄力看里面那颗球 =====
        if (BallClusterItem.isCluster(stack)) {
            ItemStack probe = BallClusterItem.asBall(stack);
            if (probe.isEmpty()) {
                return;
            }
            if (BallBehavior.profileFor(probe).chargeLevels() <= 0) {
                return;
            }
            event.setDuration(BallCharge.USE_DURATION);
            return;
        }

        if (!BallBehavior.handles(stack)) {
            return;
        }
        if (BallBehavior.profileFor(stack).chargeLevels() <= 0) {
            return;
        }
        // ── 接点 ②：「按住右键不再表示蓄力」挂在这里 ──
        //    戴着袖珍活塞时应把时长压到最短，让「按住」纯粹变成「连续投掷」的开关。
        //        event.setDuration(PocketPistonCompat.isFullChargeLocked(
        //                (Player) event.getEntity()) ? 0 : BallCharge.USE_DURATION);
        event.setDuration(BallCharge.USE_DURATION);
    }

    /** 松开右键：按已蓄级数出手 */
    public static void onStopUsing(LivingEntityUseItemEvent.Stop event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack stack = event.getItem();

        // ===== 球堆：取一颗出来，用**那颗球**的 profile 按蓄力等级出手（作者 2026-10-15）=====
        //
        // ⚠️ 拿的是 `player.getItemInHand(hand)` 而不是 `event.getItem()` ——
        //    后者不保证是玩家背包里那一格的同一个引用，而我要**就地扣掉一颗**。
        //    手上那一格才是真身。
        if (BallClusterItem.isCluster(stack)) {
            InteractionHand hand = event.getHand();
            ItemStack shell = player.getItemInHand(hand);
            if (!BallClusterItem.isCluster(shell)) {
                return;
            }
            BallBehavior.BallProfile probeProfile =
                    BallBehavior.profileFor(BallClusterItem.asBall(shell));
            if (probeProfile.chargeLevels() <= 0) {
                return;
            }
            int level = BallCharge.levelAt(BallCharge.usedTicks(player), probeProfile.chargeLevels());

            BallClusterItem.TakeResult r = BallClusterItem.takeOneWithDecluster(shell);
            if (r.taken().isEmpty()) {
                return;
            }
            throwBall(player, r.taken(), level);
            // 壳只剩一颗 → 那一颗顶上手上这一格（不留空球堆）
            if (r.hasLeftover()) {
                player.setItemInHand(hand, r.leftover());
            } else if (BallClusterItem.contents(shell).isEmpty()) {
                // ⚠️ **兜底：壳被掏空了就把这一格清空**（作者 2026-10-17 报的「透明物品」）。
                //    `takeOne` 现在自己会 `setCount(0)`，但这里再判一次：
                //    只要内容真的空了，就必须显式写空 —— 否则万一哪条路径漏了，
                //    手上就会留下一个「不是球堆、也画不出来」的透明占位。
                player.setItemInHand(hand, ItemStack.EMPTY);
            }
            return;
        }

        if (!BallBehavior.handles(stack)) {
            return;
        }
        BallBehavior.BallProfile profile = BallBehavior.profileFor(stack);
        if (profile.chargeLevels() <= 0) {
            return;
        }

        // ── 接点 ①：「奇异饰品 · 袖珍活塞」的「锁定蓄力等级为最大」挂在这里 ──
        //    当前 PocketPistonCompat.isFullChargeLocked 恒返回 false（接口已留、未启用），
        //    所以下面这行行为不变。将来改成：
        //        int level = PocketPistonCompat.isFullChargeLocked(player)
        //                ? profile.chargeLevels()
        //                : BallCharge.levelAt(BallCharge.usedTicks(player), profile.chargeLevels());
        int level = BallCharge.levelAt(BallCharge.usedTicks(player), profile.chargeLevels());
        throwBall(player, stack, level);
    }

    // ── 接点 ③：「按住右键连续投掷」需要新增一个监听器 ──
    //
    //  将来在 MoreBalls 的 mod 总线里注册一个 PlayerTickEvent.Post（或
    //  EntityTickEvent.Post 判 Player）监听，逻辑是：
    //      戴着袖珍活塞 + 玩家正在使用球 + 冷却已结束 → throwBall(player, stack, 满级)
    //  「间隔跟原版雪球一样」= 复用 throwBall 末尾那套 player.getCooldowns() 冷却队列，
    //  不要另外定一个频率常量。雪球本身没有冷却，所以这里实际是「球自己的
    //  BallProfile.cooldownTicks()」，为 0 时按每刻一次限流。
    //
    //  当前不实现 —— 等奇异饰品更新到 26.2（作者 2026-10-09 指定）。

    /**
     * 实际出手。
     *
     * @param chargeLevel 蓄力等级（0 = 无蓄力）
     */
    /**
     * 把一颗球扔出去（供球堆复用，作者 2026-10-15）。
     *
     * <p>原先是 private —— 球堆不是「球」（{@code BallBehavior.handles} 对它返回 false），
     * 所以走不到 {@code onRightClickItem} 那条事件，只能自己调这里。
     * 投掷逻辑只有一份，不另写。
     */
    public static void throwBall(Player player, ItemStack stack, int chargeLevel) {
        Level level = player.level();
        BallBehavior.BallProfile profile = BallBehavior.profileFor(stack);

        // profile.velocity() 是【初速度】数值（基准 10 = 雪球），换算成实际射速再投
        float baseVelocity = profile.physicalVelocity();
        // 蓄力：每级 +10% 初速
        float velocity = baseVelocity * BallCharge.velocityMultiplier(chargeLevel);
        // 伤害公式（投掷路径）：初始伤害 × (最终速度 / 无蓄力投掷速度)
        float damage = profile.damage() * (velocity / baseVelocity);

        // 投掷音效（照抄原版雪球：两端都播，客户端本地即时反馈）
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SNOWBALL_THROW, SoundSource.NEUTRAL,
                0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));

        if (!level.isClientSide()) {
            BallProjectile ball = new BallProjectile(level, player, stack.copyWithCount(1));
            ball.setDamage(damage);
            ball.setWeight(profile.weight());
            // 全局耐久：能承受几次碰撞直接取物品的剩余耐久，而不是每次投掷都重置
            ball.setToughness(BallItem.remainingToughness(stack, profile.toughness()));
            ball.setBounce(profile.bounce());
            // 手扔：重量基准看雪球（重量的定义正是从手扔射程标定出来的）
            ball.setFromCrossbow(false);
            ball.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F,
                    velocity, profile.inaccuracy());
            level.addFreshEntity(ball);
        }

        player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
        stack.consume(1, player);

        // 默认 profile 的冷却为 0，即完全不进冷却队列
        if (profile.cooldownTicks() > 0) {
            player.getCooldowns().addCooldown(stack, profile.cooldownTicks());
        }
    }
}
