package com.mcmodworkspace.moreballs.mixin;

import com.mcmodworkspace.moreballs.BallAmmo;
import com.mcmodworkspace.moreballs.ModEnchantments;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * 让弩把 {@code #more_balls:balls} 当成合法的副手弹药（原版只认箭与烟花火箭）。
 *
 * <h2>为什么必须用 Mixin</h2>
 * 原版的副手弹药判定硬编码在弩自己的 predicate 里（{@code ARROW_OR_FIREWORK}），
 * NeoForge 的 {@code LivingGetProjectileEvent} 只在「弹药已经被判定为合法之后」才触发，
 * 没法让一个原本不合法的物品变成合法弹药 —— 所以这里没有可用的官方事件。
 *
 * <h2>注入点的取舍（遵守工作区兼容性规范）</h2>
 * <ul>
 *   <li>只做 <b>RETURN 注入 + 改返回值</b>，不碰方法内部指令：与其它模组对同一方法的
 *       改动天然可叠加（大家都是往 predicate 里「加东西」），不会互相覆盖。</li>
 *   <li>两处注入都带 {@code require = 0}：目标方法将来被原版改动导致匹配失败时
 *       <b>安静跳过</b>，而不是让游戏崩在启动阶段。</li>
 *   <li>不使用 {@code @Redirect} / {@code @Overwrite}。</li>
 * </ul>
 *
 * <h2>附魔为什么自动生效</h2>
 * 装填与发射全程走原版弩的代码路径（{@code draw} / {@code performShooting}），
 * 附魔计算（快速装填的装填时长、多重射击的弹药份数、以及弹道与威力）都不经过本模组，
 * 所以「用 balls 当弹药的弩」与普通弩享有完全一致的附魔行为。
 */
@Mixin(CrossbowItem.class)
public abstract class CrossbowItemMixin {

    /**
     * 副手弹药判定：在原版 {@code ARROW_OR_FIREWORK} 之外，再接受 {@code #more_balls:balls}。
     *
     * <p>只扩展「副手」这一条路径。背包里的 balls <b>不会</b>被自动装填 ——
     * 与原版一致：箭可以来自背包，特殊弹药必须手动拿到副手。</p>
     */
    @Inject(
            method = "getSupportedHeldProjectiles()Ljava/util/function/Predicate;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private void moreBalls$acceptBallsAsHeldAmmo(CallbackInfoReturnable<Predicate<ItemStack>> cir) {
        Predicate<ItemStack> base = cir.getReturnValue();
        if (base == null) {
            return;
        }
        // 除了球本身，**也认球堆（壳）** —— 作者 2026-10-15 要求：
        // 「副手持球堆的时候上膛，应当直接扣球堆的球进行装填」。
        // 壳成为合法的副手弹药之后，原版 draw() 会把那一格整个存进 CHARGED_PROJECTILES，
        // 真正的「摊开」留到 createProjectile 那一步做（见下面那个注入）——
        // 这样副手**全程保持是壳**，玩家看到的形态不变。
        cir.setReturnValue(base.or(BallAmmo::isBall)
                .or(com.mcmodworkspace.moreballs.BallClusterItem::isCluster));
    }

    /**
     * 投射物分流：balls 弹药不走原版「非箭即回退成普通箭」的兜底逻辑，
     * 而是交给 {@link BallAmmo} 生成对应实体（雪球承载 / 末影珍珠特判）。
     *
     * <p>箭与烟花火箭不属于 balls 标签，走原版路径不受影响。</p>
     */
    @Inject(
            method = "createProjectile(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/projectile/Projectile;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private void moreBalls$ballProjectile(Level level, LivingEntity shooter, ItemStack heldItem,
                                          ItemStack projectile, boolean isCrit,
                                          CallbackInfoReturnable<Projectile> cir) {
        // ===== 球堆：摊开一颗来建实体，并从**副手那一格**真正扣掉它 =====
        //
        // ⚠️ 两个位置必须分清：
        //    · `projectile` 是从 CHARGED_PROJECTILES 里拿出来的**副本** —— 从它 takeOne
        //      不会影响玩家的背包（那只是弹仓里的记录）
        //    · `shooter.getOffhandItem()` 才是**真身** —— 扣它才会真的少一颗
        //    壳掏空之后 takeOne 会自己摘掉组件（那一格变成空壳），所以副手会自然消失。
        //
        // 作者 2026-10-15：这样副手**全程保持是壳**，不再需要「先填充一次副手」那个中间态。
        ItemStack actual = projectile;
        if (com.mcmodworkspace.moreballs.BallClusterItem.isCluster(projectile)) {
            actual = com.mcmodworkspace.moreballs.BallClusterItem.asBall(projectile);
            if (actual.isEmpty()) {
                return;   // 空壳：交给原版兜底
            }
            if (shooter != null) {
                ItemStack offhand = shooter.getOffhandItem();
                if (com.mcmodworkspace.moreballs.BallClusterItem.isCluster(offhand)) {
                    com.mcmodworkspace.moreballs.BallClusterItem.takeOne(offhand);   // 真的扣一颗
                }
            }
        }

        if (BallAmmo.isBall(actual)) {
            // heldItem 就是弩本身：穿透等附魔等级由它决定
            cir.setReturnValue(BallAmmo.createProjectile(level, shooter, heldItem, actual));
        }
    }

    /**
     * <b>装填前的弹药置换</b>。
     *
     * <p><b>球堆（壳）</b>：从壳里扣一颗<b>耐久最低</b>的球顶上副手，装填完再把壳放回副手 ——
     * 这是作者 2026-10-15 指定的行为：
     * 「副手持球堆的时候，应当从球堆里扣除一颗耐久最低的球上膛，
     *   然后球堆减少对应的球，保持放在副手。」</p>
     *
     * <p><b>散球</b>：副手拿着满耐久的球时，先换成背包里耐久最低的同种球
     * （作者指定：上膛应该优先扣背包里最旧的同类弹药，把崭新的球留在副手备用）。</p>
     *
     * <h2>⚠️ 为什么不能把「壳本身」当弹药</h2>
     * <p>{@code tryLoadProjectiles} → {@code getProjectile}（拿到的是壳）→
     * {@code draw(...)} 返回的 List 里<b>装的就是壳本身</b> ⇒ <b>整个壳进弹仓</b> ⇒
     * 一次发射全没了（作者实测：「球堆放在副手当弹药会错误的直接全部发射出去」）。</p>
     *
     * <p>所以这里先把壳换成「一颗球」交给原版装填，装填结束（{@code RETURN}）再把壳放回副手。
     * 壳的减少量与收纳袋补弹是<b>同一套</b> —— 补弹那边也是往副手送「一颗球」，
     * 而这里走的是直接从壳扣，两者不会各算各的。</p>
     */
    /** 装填期间暂存的壳（装填是同步的，单线程够用） */
    private static final ThreadLocal<ItemStack> PENDING_SHELL =
            ThreadLocal.withInitial(() -> ItemStack.EMPTY);

    @Inject(
            method = "tryLoadProjectiles",
            at = @At("HEAD"),
            require = 0
    )
    private static void moreBalls$preferLowDurabilityAmmo(LivingEntity shooter, ItemStack crossbow,
                                                          CallbackInfoReturnable<Boolean> cir) {
        // ===== ★ 球堆在副手：从壳扣一颗耐久最低的顶上（作者 2026-10-15）=====
        if (shooter instanceof Player player) {
            ItemStack offhand = player.getOffhandItem();
            if (com.mcmodworkspace.moreballs.BallClusterItem.isCluster(offhand)) {
                // takeOne 会**就地**改壳（扣掉耐久最低的那颗）——
                // 所以壳要在这之后 copy，拿到的才是「剩余的壳」
                ItemStack one = com.mcmodworkspace.moreballs.BallClusterItem.takeOne(offhand);
                if (!one.isEmpty()) {
                    PENDING_SHELL.set(offhand.copy());
                    player.setItemInHand(InteractionHand.OFF_HAND, one);
                }
                return;   // 壳这条路径不走「从背包挑最低」那套
            }
        }
        BallAmmo.swapHeldAmmoForLowestDurability(shooter);
    }

    /**
     * <b>装填结束：把壳放回副手</b>（原版已经把顶上来的那颗球消耗进弹仓了）。
     *
     * <p>副手若还有别的东西（原版没消耗掉那颗临时球），就把壳收进背包 —— <b>绝不凭空消失</b>。</p>
     */
    @Inject(
            method = "tryLoadProjectiles",
            at = @At("RETURN"),
            require = 0
    )
    private static void moreBalls$restoreShell(LivingEntity shooter, ItemStack crossbow,
                                               CallbackInfoReturnable<Boolean> cir) {
        ItemStack shell = PENDING_SHELL.get();
        PENDING_SHELL.remove();
        if (shell.isEmpty() || !(shooter instanceof Player player)) {
            return;
        }
        if (player.getOffhandItem().isEmpty()) {
            player.setItemInHand(InteractionHand.OFF_HAND, shell);
        } else if (!player.getInventory().add(shell)) {
            player.drop(shell, false);
        }
    }

    /**
     * 夹住一次发射的生命周期，用来认多重射击的附属弹。
     *
     * <p>原版 {@code performShooting} 会在<b>同一次调用里</b>连着创建好几颗弹药，
     * 所以「进方法置零、出方法清掉」这个区间内的序号就是最可靠的判据：
     * 第 1 颗是主弹药，之后的全是复制品。</p>
     *
     * <p>两个注入都带 {@code require = 0} —— 万一将来原版改了签名，这里是安静跳过，
     * 而 {@code BallAmmo.claimSideProjectile()} 在拿不到序号时会一律返回「不是附属弹」，
     * 退化成「所有球都按主弹药处理」，不会崩。</p>
     */
    @Inject(method = "performShooting", at = @At("HEAD"), require = 0)
    private void moreBalls$beginShootingRound(Level level, LivingEntity shooter, InteractionHand hand,
                                              ItemStack weapon, float velocity, float inaccuracy,
                                              LivingEntity target, CallbackInfo ci) {
        BallAmmo.beginShootingRound();
    }

    @Inject(method = "performShooting", at = @At("RETURN"), require = 0)
    private void moreBalls$endShootingRound(Level level, LivingEntity shooter, InteractionHand hand,
                                            ItemStack weapon, float velocity, float inaccuracy,
                                            LivingEntity target, CallbackInfo ci) {
        BallAmmo.endShootingRound();
    }

    /**
     * <b>【诸葛连弩】与【快速装填】并存时，把装填时长改成确定的「乘算」。</b>
     *
     * <h2>要解决的问题</h2>
     * <p>两个附魔改的是同一个量，运算符却不同（快速装填是 {@code add}、诸葛连弩是 {@code multiply}），
     * 而原版按 {@code ItemEnchantments} 的遍历顺序依次作用 ——
     * 那是个 {@code Reference2IntOpenHashMap}，<b>无序</b>。于是同一把弩可能算出：</p>
     *
     * <pre>
     * 快速装填先算：(1.25 − 0.75) × 0.55 = 0.275 秒  →  5 刻
     * 诸葛连弩先算：(1.25 × 0.55) − 0.75 = −0.0625    →  被夹成 0 刻（瞬发）
     * </pre>
     *
     * <h2>为什么只在这种情况下接管</h2>
     * <p>只有一个附魔时，原版与数据包给出的结果<b>本来就是乘算形式</b>，不用插手：</p>
     *
     * <ul>
     *   <li>只有【快速装填】：{@code 1.25 − 0.25L} ≡ {@code 1.25 × (1 − 0.2L)} —— 完全等价</li>
     *   <li>只有【诸葛连弩】：json 里的 {@code multiply} 就是 {@code 1.25 × (1 − 0.15L)}</li>
     * </ul>
     *
     * <p>所以这一处注入只在<b>两者同时存在</b>时生效，其余情况一概不碰，原版行为零影响。</p>
     *
     * <h2>注入方式</h2>
     * <p>RETURN 注入 + 改返回值，带 {@code require = 0}。
     * 注意 {@code getChargeDuration} 是 {@code public static}，所以这个处理器也必须是 {@code static}。</p>
     */
    @Inject(method = "getChargeDuration", at = @At("RETURN"), cancellable = true, require = 0)
    private static void moreBalls$multiplicativeChargeTime(ItemStack crossbow, LivingEntity user,
                                                          CallbackInfoReturnable<Integer> cir) {
        Level level = user.level();
        int quick = ModEnchantments.quickChargeLevel(level, crossbow);
        if (quick <= 0) {
            return;
        }
        int repeater = ModEnchantments.repeaterCrossbowLevel(level, crossbow);
        if (repeater <= 0) {
            return;
        }
        cir.setReturnValue(ModEnchantments.combinedChargeTicks(quick, repeater));
    }
}
