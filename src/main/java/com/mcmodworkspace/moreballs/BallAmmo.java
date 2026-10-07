package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.BallProjectile;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;

/**
 * 「球」弹药的投射物工厂 —— 决定一件 balls 物品被弩射出去时变成什么实体。
 *
 * <p>只认标签 {@code #more_balls:balls}，所以任何进标签的物品都能当弩弹药，无需改代码。</p>
 *
 * <p><b>伤害公式（弹药路径）</b>：<br>
 * {@code 最终伤害 = 初始伤害 × (最终速度 / 初始速度) × 2}<br>
 * 其中「初始速度」是弩射普通箭的初速（{@code CROSSBOW_BASE_VELOCITY}）。强弩附魔会提高
 * 出手速度，从而同时放大射程与伤害。</p>
 */
public final class BallAmmo {

    private BallAmmo() {
    }

    /**
     * 弩射普通箭的初速 —— 原版 {@code CrossbowItem} 对非烟花弹药使用的 power 值（3.15）。
     * 用作弹药伤害公式里的「初始速度」基准。
     */
    public static final float CROSSBOW_BASE_VELOCITY = 3.15F;

    /** 弹药路径的伤害倍率（作者指定：×2） */
    public static final float AMMO_DAMAGE_MULTIPLIER = 2.0F;

    /** 是否为本模组认可的「球」类弹药 */
    public static boolean isBall(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(ModTags.Items.BALLS);
    }

    /**
     * 这把弩里装填的「球」是哪种，没装就返回 {@code null}。
     *
     * <p>给客户端渲染用（弩拉满时要换成对应弹药的模型）。判断依据是弩上的
     * 「已装填弹药」组件 —— 那是同步到客户端的数据，所以客户端也读得到，
     * 不需要任何服务端状态。</p>
     *
     * <p><b>注意这个写法是刻意选的</b>：{@code ChargedProjectiles} 的遍历接口在版本间变过
     * （26.2 的 {@code itemCopies()} 返回 {@code List}、26.3 返回 {@code Stream}），
     * 为了让这条线一起兼容两条版本，这里改用两版都有的 {@code contains(Item)}
     * 逐个比对标签里的球。</p>
     */
    public static Item chargedBallItem(ItemStack crossbow) {
        if (crossbow == null || crossbow.isEmpty()) {
            return null;
        }
        ChargedProjectiles charged = crossbow.get(DataComponents.CHARGED_PROJECTILES);
        if (charged == null || charged.isEmpty()) {
            return null;
        }
        for (Item item : BuiltInRegistries.ITEM) {
            if (charged.contains(item) && item.builtInRegistryHolder().is(ModTags.Items.BALLS)) {
                return item;
            }
        }
        return null;
    }

    /** 这把弩里装的是不是「球」 */
    public static boolean holdsChargedBall(ItemStack crossbow) {
        return chargedBallItem(crossbow) != null;
    }

    /**
     * 上膛前把副手那颗满耐久的球，换成背包里<b>同种但耐久最低</b>的那颗。
     *
     * <p>作者指定：副手拿着满耐久的球时，上膛应该优先扣背包里耐久最低的同种弹药
     * —— 让崭新的球留在副手备用，先把手上的旧球打光。</p>
     *
     * <p>做法是<b>交换</b>而不是复制：副手那颗被放回背包原位置，背包那颗顶上副手，
     * 后续的原版装填流程一个字都不用改，扣的自然就是顶上来的那颗。</p>
     */
    public static void swapHeldAmmoForLowestDurability(LivingEntity shooter) {
        if (!(shooter instanceof Player player)) {
            return;
        }
        ItemStack offhand = player.getOffhandItem();
        if (!isBall(offhand)) {
            return;
        }

        BallBehavior.BallProfile offhandProfile = BallBehavior.profileFor(offhand);
        // 没有耐久概念的球（不坚固 / 无法破坏）没什么可挑的
        if (offhandProfile.toughness() <= 0) {
            return;
        }
        // 只在副手那颗「还是满耐久」时才动手 —— 已经用旧的球本来就该先打出去
        if (BallItem.remainingToughness(offhand, offhandProfile.toughness())
                < offhandProfile.toughness()) {
            return;
        }

        Inventory inventory = player.getInventory();
        int bestSlot = -1;
        int bestLeft = Integer.MAX_VALUE;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack candidate = inventory.getItem(slot);
            // 用 sameKind 而不是比 item：组合球全是同一个 item，只比 item 会把
            // 「铁-金球」当成「木-圆石球」的同种（那边同类问题在 2.5.0 修过一轮）
            if (candidate.isEmpty() || !BallPouchItem.sameKind(candidate, offhand)) {
                continue;
            }
            if (candidate == offhand) {
                continue; // 副手那颗自己（遍历会算上它，换自己没意义）
            }
            int left = BallItem.remainingToughness(candidate,
                    BallBehavior.profileFor(candidate).toughness());
            // bestSlot < 0 的兜底不能省：万一 left 恰好等于 bestLeft 的初始值，
            // 只写 left < bestLeft 会导致一个都选不中（同类陷阱见 BallPouchItem 的说明）
            if (bestSlot < 0 || left < bestLeft) {
                bestLeft = left;
                bestSlot = slot;
            }
        }

        if (bestSlot < 0) {
            return; // 背包里没有同种的，照旧用副手这颗
        }

        ItemStack lower = inventory.getItem(bestSlot);
        inventory.setItem(bestSlot, offhand);
        player.setItemInHand(InteractionHand.OFF_HAND, lower);
    }

    /**
     * 本次发射已经创建到第几颗弹药（{@code null} = 不在发射流程里）。
     *
     * <p>原版 {@code performShooting} 一次发射会在<b>同一个方法调用</b>里连着创建好几颗弹药，
     * 所以用「调用期间记序号」来认附属弹最准确：第一次创建的是主弹药，之后的全是复制品。</p>
     *
     * <h2>为什么这次用 ThreadLocal 而不是 Map</h2>
     * 前两版吃过亏：先用 Mixin 读 {@code shootProjectile} 的 index（注入静默失败），
     * 再改用「同刻计数」的 {@code WeakHashMap<UUID,...>} —— 而 {@code getUUID()} 每次返回新对象，
     * 弱引用 key 被 GC 一收整条记录就没了。这次把状态的<b>生命周期交给 Mixin 的两个注入点夹住</b>
     * （进 {@code performShooting} 时置零、出方法时清掉），不依赖任何会被回收的东西。</p>
     */
    private static final ThreadLocal<Integer> SHOT_INDEX = new ThreadLocal<>();

    /** 由 Mixin 在 {@code performShooting} 进入时调用 */
    public static void beginShootingRound() {
        SHOT_INDEX.set(0);
    }

    /** 由 Mixin 在 {@code performShooting} 返回时调用 */
    public static void endShootingRound() {
        SHOT_INDEX.remove();
    }

    /**
     * 领一个序号：{@code >0} 表示这是多重射击复制出来的附属弹。
     *
     * <p>不在发射流程里（比如玩家手扔、怪物投掷）时永远返回 false —— 那些路径没有多重射击。</p>
     */
    public static boolean claimSideProjectile() {
        Integer index = SHOT_INDEX.get();
        if (index == null) {
            return false;
        }
        SHOT_INDEX.set(index + 1);
        if (index > 0) {
            MoreBalls.LOGGER.info("[ball] 同一次发射的第 {} 颗 -> 判定为多重射击附属弹", index + 1);
            return true;
        }
        return false;
    }

    /**
     * 为一件 balls 弹药创建对应的投射物实体。
     *
     * @param level   开火所在世界
     * @param shooter 射手
     * @param weapon  发射武器（弩），用于读取穿透等附魔等级
     * @param ammo    被装填的弹药堆（决定实体外观、伤害、重量、坚固度）
     * @return 尚未加入世界的投射物实体
     */
    public static Projectile createProjectile(Level level, LivingEntity shooter, ItemStack weapon, ItemStack ammo) {
        // 原版物品：射出去的还是它原来的东西（雪球碎裂 / 末影珍珠传送）
        BallBehavior.VanillaProjectileFactory vanilla = BallBehavior.vanillaProjectileFor(ammo);
        if (vanilla != null) {
            return vanilla.create(level, shooter, ammo);
        }

        BallBehavior.BallProfile profile = BallBehavior.profileFor(ammo);

        BallProjectile ball = new BallProjectile(level, shooter, ammo);

        // 强弩附魔：每级让基础发射距离 +30%，换算成初速度倍率
        int boostLevel = ModEnchantments.levelOn(level, weapon);
        float multiplier = ModEnchantments.launchMultiplier(boostLevel);

        // 【极速】：在强弩之上再按等级抬高初速度，两者相乘，同时附上会叠得更远
        multiplier *= ModEnchantments.swiftnessMultiplier(
                ModEnchantments.swiftnessLevel(level, weapon));
        ball.setLaunchMultiplier(multiplier);

        // 【轻盈】：把弹药重量减半（向上取整，最低 1）——
        // 重量决定下坠，减半之后同一条弹道会平得多
        int weight = profile.weight();
        if (ModEnchantments.hasLightness(level, weapon)) {
            weight = ModEnchantments.lightnessWeight(weight);
        }

        // 弹药路径伤害：初始伤害 × (最终速度/初始速度) × 2。
        // 强弩会抬高出手速度，因此同时放大射程与伤害。
        ball.setDamage(profile.damage() * AMMO_DAMAGE_MULTIPLIER);
        ball.setWeight(weight);
        ball.setToughness(profile.toughness());
        ball.setBounce(profile.bounce());
        // 弩发射：重量基准取箭矢的重力，使重量 4 的球飞出与箭一致的弹道
        ball.setFromCrossbow(true);

        // 多重射击的附属弹：落定即碎、碎裂无掉落，也不享受【空气动力球】。
        // 序号由 CrossbowItemMixin 在 performShooting 期间维护
        boolean side = claimSideProjectile();
        ball.setMultishotSide(side);

        // 【空气动力球】：给球打上「静止后自己飞回来」的标记（附属弹不给）。
        // 真正的回归逻辑在 BallProjectile 里
        if (!side) {
            ball.setReturnToOwner(ModEnchantments.hasAerodynamicBall(level, weapon));
        }

        // 穿透附魔与原版箭同源：等级从发射武器上取
        if (level instanceof ServerLevel serverLevel && weapon != null && !weapon.isEmpty()) {
            ball.setPierceLevel((byte) EnchantmentHelper.getPiercingCount(serverLevel, weapon, ammo));
        }

        return ball;
    }
}
