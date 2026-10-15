package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.BallProjectile;
import net.minecraft.core.Holder;
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
 * {@code 命中伤害 = 初始伤害 × 2 × (击中速度 / 实际出手速度)}<br>
 * 除数是<b>实际出手速度</b> —— {@link BallProjectile#shoot} 里记下的 {@code launchSpeed}，
 * 已经含了强弩 / 极速的倍率，<b>不是</b>弩射普通箭的初速
 * {@code CROSSBOW_BASE_VELOCITY}。附魔倍率同时出现在分子（出手初速）与分母里，
 * 出手瞬间比值恒为 1 —— 所以<b>强弩只放大射程，不放大伤害</b>。</p>
 */
public final class BallAmmo {

    private BallAmmo() {
    }

    /**
     * 弩射普通箭的初速 —— 原版 {@code CrossbowItem} 对非烟花弹药使用的 power 值（3.15）。
     *
     * <p>⚠️ 它<b>不是</b>伤害公式里的除数：那条公式除的是<b>实际出手速度</b>
     * （{@code launchSpeed}，含蓄力与附魔倍率）。本文件目前没有任何引用点，
     * 仅作数值记录保留。</p>
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
     * （26.2 的 {@code itemCopies()} 返回 {@code List}，26.3 改成了 {@code Stream}）。
     * <b>本条线是 26.2</b>，而 {@code contains(Item)} 两版都在，所以这里用它逐个比对
     * 标签里的球，移植到 26.3 时这段不用动。</p>
     */
    public static Item chargedBallItem(ItemStack crossbow) {
        if (crossbow == null || crossbow.isEmpty()) {
            return null;
        }
        ChargedProjectiles charged = crossbow.get(DataComponents.CHARGED_PROJECTILES);
        if (charged == null || charged.isEmpty()) {
            return null;
        }
        // 只遍历标签成员，不再遍历**整个物品注册表**。
        //
        // 这个方法挂在 ChargedBall 属性上，物品模型每帧都会问一次 ——
        // 遍历全注册表（几千个物品）× 每帧 × 每把弩，代价相当可观。
        // 标签成员通常只有十几个，而且 builtInRegistryHolder().is() 也顺带省掉了。
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(ModTags.Items.BALLS)) {
            Item item = holder.value();
            if (charged.contains(item)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 这把弩里装填的那颗「球」的**完整栈（带组件）**，没装就返回 null。
     *
     * <p><b>渲染器必须用这个，而不是 {@link #chargedBallItem}。</b>
     * 弩上那颗组合球的贴图靠 {@code combo_slot_1..4} 选象限，
     * 而那些组件在<b>球</b>的栈上 —— 弩上一个都没有。
     * {@code SpecialModelWrapper.update()} 传给渲染器的正是**弩**的物品栈，
     * 所以之前 {@code extractArgument} 读弩读完永远是空的，只能画底图
     * （作者反馈「组合球没有上膛贴图」）。</p>
     */
    public static ItemStack chargedBallStack(ItemStack crossbow) {
        if (crossbow == null || crossbow.isEmpty()) {
            return null;
        }
        ChargedProjectiles charged = crossbow.get(DataComponents.CHARGED_PROJECTILES);
        if (charged == null || charged.isEmpty()) {
            return null;
        }
        // 本条线是 26.2：itemCopies() 返回 List（26.3 改成了 Stream，移植时要按那条线的返回类型改写）
        for (ItemStack projectile : charged.itemCopies()) {
            if (isBall(projectile)) {
                return projectile;
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

        // ===== 【球堆】副手拿着球堆时**什么都不用做**（作者 2026-10-15 修正）=====
        //
        // ⚠️ 这里原先是我加的一段：把壳里最低那颗掏出来放到副手，再走原版装填。
        //    作者反馈那多了一次「额外填充副手」的中间态 —— 副手从壳变成了一颗球。
        //
        //    现在改成：**副手保持是壳**。
        //      · getSupportedHeldProjectiles 的 predicate 认壳（见 CrossbowItemMixin）
        //      · createProjectile 注入里摊开成一颗球来建实体，并从副手那一格真的扣一颗
        //    两处都在 CrossbowItemMixin 里，这里不再需要任何动作。
        if (BallClusterItem.isCluster(offhand)) {
            return;
        }

        // ===== 副手空着时：**什么都不做**（作者 2026-10-15 明确纠正）=====
        //
        // ⚠️⚠️ 这里原先是我自己加的一段：副手一空就从**背包**挑一颗剩余耐久最低的顶上。
        //    作者原话：
        //       「现在收纳袋在无法将目标球放入副手时，会错误的随机拿取背包内的**无关球**装填，
        //         **背包内的球不应该被调动**。」
        //
        //    我当初加它的理由是「原版按槽位顺序抓背包，完全不看耐久」—— 但『优先打旧球』
        //    是我自己认定的需求，**作者从来没要求过，而且明确反对**。撤掉。
        //
        //    副手空就交给原版：原版抓哪颗就是哪颗，**模组不插手玩家的背包**。
        if (offhand.isEmpty()) {
            return;
        }

        if (!isBall(offhand)) {
            return;
        }

        // ===== 【已撤除】「副手满耐久 → 和背包里最旧的同种球交换」=====
        //
        // ⚠️⚠️ 作者 2026-10-15 明确：「**背包内的球不应该被调动**」。
        //
        // 这里原先有整整一段（约 50 行）：副手那颗满耐久时，从**背包**里找同种、剩余耐久
        // 最低的一颗，与副手**对调**。理由是「原版按槽位顺序抓弹药、不看耐久」——
        // 但「优先打旧球」**是我自己认定的需求**，作者从来没要求过，而且现在明确反对。
        //
        // 它和「副手空 → 从背包挑一颗」是**同一类**的东西（都在动玩家的背包）。
        // 上一批我只撤了后者、漏了这一段 —— 作者实测「根本没选过弹药，副手还是被塞」
        // 就是它：本方法由 `CrossbowItemMixin` 在**弩装填时无条件调用**，不看有没有选弹药。
        //
        // ⇒ 模组只从两个地方提供弹药，**玩家的背包一律不碰**：
        //     · 收纳袋 → `BallAmmoAutoLoader`（且要求轮盘选过种类）
        //     · 副手的球堆 → `CrossbowItemMixin` 装填前扣一颗
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

    /**
     * 本轮开始的**墙钟毫秒** —— 用来兜住「Mixin 的出口没跑到」的情况。
     *
     * <p>正常路径是 {@code performShooting} 进出各注入一次，把 ThreadLocal 夹在中间。
     * 但那个方法一旦<b>抛异常</b>（或者出口注入点因为别的模组改了方法结构而没命中），
     * {@code endShootingRound} 就不会执行 —— ThreadLocal 残留在服务端线程上，
     * 之后**每一颗**球领到的序号都大于 0，全被当成多重射击附属弹：
     * 表现是普通投掷的球也会「不可回收 + 落地即碎」。</p>
     *
     * <p>所以除了依赖成对调用，再记一个「本轮所属刻」当保险丝：
     * 超过 {@link #ROUND_MAX_AGE_MS} 毫秒还没被清掉，就认为那一轮早就结束了，直接作废。</p>
     */

    /** 保险丝时长 —— 超时即认为上一轮是残留（一次发射远用不了这么久） */
    private static final long ROUND_MAX_AGE_MS = 1000L;   // 约一秒，一次发射远用不了这么久

    private static final ThreadLocal<Long> SHOT_ROUND_MS = new ThreadLocal<>();

    /** 由 Mixin 在 {@code performShooting} 进入时调用 */
    public static void beginShootingRound() {
        SHOT_INDEX.set(0);
        SHOT_ROUND_MS.set(System.currentTimeMillis());
    }

    /** 由 Mixin 在 {@code performShooting} 返回时调用 */
    public static void endShootingRound() {
        SHOT_INDEX.remove();
        SHOT_ROUND_MS.remove();
    }

    /**
     * 领一个序号：{@code >0} 表示这是多重射击复制出来的附属弹。
     *
     * <p>不在发射流程里（比如玩家手扔、怪物投掷）时永远返回 false —— 那些路径没有多重射击。</p>
     */
    public static boolean claimSideProjectile() {
        Integer index = SHOT_INDEX.get();
        if (index == null) {
            // 【临时诊断】作者 2026-10-15 报「多重射击的附属球卡在墙上不碎」。
            //
            // 这一条能直接定位**mixin 到底有没有生效**：
            //   · 打出来 ⇒ `performShooting` 的那两个注入没命中（都是 require = 0，
            //     原版一改方法结构就静默跳过）⇒ SHOT_INDEX 恒 null
            //     ⇒ **每一颗球都被当主弹** ⇒ 附属弹不碎、不回家（正是当前症状）
            //   · 没打出来 ⇒ 夹住了，问题在别处
            if (DIAG_NULL_INDEX.compareAndSet(false, true)) {
                MoreBalls.LOGGER.info("[ball][诊断] claimSideProjectile: SHOT_INDEX 未初始化"
                        + " ⇒ performShooting 的 mixin 可能没生效（所有球都会被当主弹）");
            }
            return false;
        }
        // 保险丝：上一轮的出口没跑到时，这个值会一直挂着，
        // 于是之后每一颗球都变成「附属弹」。超过一轮的正常时长就直接作废。
        Long roundMs = SHOT_ROUND_MS.get();
        if (roundMs == null
                || System.currentTimeMillis() - roundMs > ROUND_MAX_AGE_MS) {
            endShootingRound();
            return false;
        }
        SHOT_INDEX.set(index + 1);
        if (index > 0) {
            // 每次多重射击都走这里，别用 INFO 刷日志
            MoreBalls.LOGGER.debug("[ball] 同一次发射的第 {} 颗 -> 判定为多重射击附属弹", index + 1);
            // 【临时诊断】确认 side 真的判成了 true（只报一次）
            if (DIAG_SIDE.compareAndSet(false, true)) {
                MoreBalls.LOGGER.info("[ball][诊断] claimSideProjectile: 第 {} 颗判为附属弹 side=true",
                        index + 1);
            }
            return true;
        }
        return false;
    }

    /** 【临时诊断】「mixin 没生效」这条只报一次，免得刷屏 */
    private static final java.util.concurrent.atomic.AtomicBoolean DIAG_NULL_INDEX =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 【临时诊断】「side=true」这条只报一次 */
    private static final java.util.concurrent.atomic.AtomicBoolean DIAG_SIDE =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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

        // 弹药路径伤害：初始伤害 × 2，命中时再由 impactDamage() 乘 (击中速度 / 实际出手速度)。
        // 强弩 / 极速抬高的是「出手速度」本身，分子分母同时变大而约掉 —— 只放大射程，不放大伤害。
        // 【诸葛连弩】作者 2026-10-12 指定：每级降低 10% 最终伤害（3 级 = -30%）。
        // 放在这里就是「最终伤害」——球之后按 impactDamage() 再乘速度比，基数已是削过的。
        ball.setDamage(profile.damage() * AMMO_DAMAGE_MULTIPLIER
                * ModEnchantments.repeaterDamageFactor(
                        ModEnchantments.repeaterCrossbowLevel(level, weapon)));
        ball.setWeight(weight);
        // ⚠️⚠️ 耐久必须取**这颗球的剩余**，不是 profile 的满耐久
        //     （作者 2026-10-15 实测：弩射出去的球卡在墙上不碎）。
        //
        //     手扔那条路（`BallThrowHandler.throwBall`）用的就是 `remainingToughness`；
        //     这里原先写的是 `profile.toughness()` —— 于是**弩射出的球永远按「刚出炉」算**：
        //     潮汐球坚固 300 ⇒ 要撞 300 次才碎 ⇒ 看起来就是「粘在墙上永不破碎」。
        ball.setToughness(BallItem.remainingToughness(ammo, profile.toughness()));
        ball.setBounce(profile.bounce());
        // 弩发射：重量基准取箭矢的重力，使重量 4 的球飞出与箭一致的弹道
        ball.setFromCrossbow(true);

        // 多重射击的附属弹：落定即碎、碎裂无掉落，也不享受【空气动力球】。
        // 序号由 CrossbowItemMixin 在 performShooting 期间维护
        boolean side = claimSideProjectile();
        ball.setMultishotSide(side);

        // 【三军听令】：本次发射出现了多重射击的附属弹 ⇒ 给射手发成就。
        //
        // ⚠️ 原来这个成就**只在 BallMobAI（怪物侧）发**，玩家用弩打出多重射击领不到
        //    —— 作者 2026-10-14 报的「三军听令没触发」就是这个。
        //
        //    side 为 true 代表「同一次 performShooting 里出现了第 2 颗以上的球」，
        //    这正是多重射击生效的判据（见 claimSideProjectile 的注释）。
        //    不放进 BallAchievementWatcher 是因为那是「状态型」成就的 20 刻巡查，
        //    而这是一次性事件 —— 巡查看不到「刚射出去的那一发」。
        if (side && shooter instanceof Player player) {
            ModAdvancements.award(player, ModAdvancements.MULTISHOT);
        }

        // 【空气动力球】：给球打上「静止后自己飞回来」的标记（附属弹不给）。
        // 真正的回归逻辑在 BallProjectile 里
        boolean aerodynamic = ModEnchantments.hasAerodynamicBall(level, weapon);
        if (!side) {
            ball.setReturnToOwner(aerodynamic);
        }

        // 【临时诊断】发射时的耐久 —— 作者报「球飞回来之后没了」，
        // 而「耐久耗光 ⇒ 碎裂、无掉落」正是最可能的解释。
        // 把发射值打出来，跟回收/碎裂那两行一对，就能算出它在路上扣了多少。
        MoreBalls.LOGGER.info("[球堆][诊断] 发射：{} 耐久={} side={} 空气动力={}",
                ammo.getHoverName().getString(),
                BallItem.remainingToughness(ammo, profile.toughness()),
                side, aerodynamic);

        // ===== 穿透等级 = 「球自带的【穿透x】」与「弩的穿透附魔」取较大者 =====
        //
        // ⚠️ 原来只读附魔，于是**钻石球自带的【穿透 3】完全没用** ——
        //    弩一没附魔，PIERCE_LEVEL 就是 0，穿透分支根本不进（作者 2026-10-10 报的）。
        //    球自带的穿透是**球自己的性质**，不该因为发射它的弩没附魔就消失；
        //    反过来附魔更高时也照常叠加，与原版箭的「附魔决定穿透」不冲突。
        int enchantPierce = 0;
        if (level instanceof ServerLevel serverLevel && weapon != null && !weapon.isEmpty()) {
            enchantPierce = EnchantmentHelper.getPiercingCount(serverLevel, weapon, ammo);
        }
        int pierce = Math.max(enchantPierce, profile.penetration());
        if (pierce > 0) {
            ball.setPierceLevel((byte) pierce);
        }

        return ball;
    }    /**
     * 从玩家背包里挑一颗「摊开后剩余耐久最低」的球，并<b>把它从原格取出</b>。
     *
     * <h2>为什么要摊开再比</h2>
     * <p>背包里的候选可能是<b>散球</b>，也可能是<b>球堆（壳）</b>。壳不带
     * {@code TOUGHNESS_LEFT} 组件，直接读会拿到 0 而永远当选。所以统一先走
     * {@link BallClusterItem#asBall}：散球原样返回，壳则摊成里面那颗<b>最低</b>的球。</p>
     *
     * <h2>为什么从壳里掏一颗要写回</h2>
     * <p>壳是 {@code stacksTo(1)} 的容器，掏出最低那颗之后壳本身少了一颗 ——
     * 必须把剩余写回那一格（空了就把组件摘掉）。<b>取走最后一颗时不销毁壳</b>，
     * 留给原版后续流程处理（玩家手里那颗已经是球了）。
     *
     * @return 挑中的那颗球；背包里没有可用的球时返回 {@link ItemStack#EMPTY}
     */
    private static ItemStack lowestInInventory(Player player) {
        Inventory inventory = player.getInventory();
        int bestSlot = -1;
        int bestLeft = Integer.MAX_VALUE;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack candidate = inventory.getItem(slot);
            if (candidate.isEmpty()) {
                continue;
            }
            // 壳：掏出一颗（并把剩余的写回该格）；散球：直接用它
            if (BallClusterItem.isCluster(candidate)) {
                ItemStack probed = BallClusterItem.asBall(candidate);
                if (probed.isEmpty()) {
                    continue;
                }
                int left = BallItem.remainingToughness(probed,
                        BallBehavior.profileFor(probed).toughness());
                if (bestSlot < 0 || left < bestLeft) {
                    bestLeft = left;
                    bestSlot = slot;
                }
                continue;
            }
            if (!isBall(candidate)) {
                continue;
            }
            int left = BallItem.remainingToughness(candidate,
                    BallBehavior.profileFor(candidate).toughness());
            if (bestSlot < 0 || left < bestLeft) {
                bestLeft = left;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) {
            return ItemStack.EMPTY;
        }
        ItemStack chosen = inventory.getItem(bestSlot);
        if (BallClusterItem.isCluster(chosen)) {
            // 从壳里掏一颗 —— takeOne 会把剩余写回同一个 ItemStack（壳是引用）
            return BallClusterItem.takeOne(chosen);
        }
        inventory.setItem(bestSlot, ItemStack.EMPTY);
        return chosen;
    }
}