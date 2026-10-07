package com.mcmodworkspace.moreballs.mixin;

import com.mcmodworkspace.moreballs.BallAmmo;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
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
        cir.setReturnValue(base.or(BallAmmo::isBall));
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
        if (BallAmmo.isBall(projectile)) {
            // heldItem 就是弩本身：穿透等附魔等级由它决定
            cir.setReturnValue(BallAmmo.createProjectile(level, shooter, heldItem, projectile));
        }
    }

    /**
     * 上膛前的弹药置换：副手拿着满耐久的球时，先换成背包里<b>耐久最低的同种球</b>。
     *
     * <p>作者指定：上膛应该优先扣背包里最旧的同类弹药，把崭新的球留在副手备用。
     * 换在装填<b>之前</b>做，后面的原版流程完全不用改。</p>
     */
    @Inject(
            method = "tryLoadProjectiles",
            at = @At("HEAD"),
            require = 0
    )
    private static void moreBalls$preferLowDurabilityAmmo(LivingEntity shooter, ItemStack crossbow,
                                                          CallbackInfoReturnable<Boolean> cir) {
        BallAmmo.swapHeldAmmoForLowestDurability(shooter);
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
}
