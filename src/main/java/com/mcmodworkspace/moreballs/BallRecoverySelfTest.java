package com.mcmodworkspace.moreballs;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * <b>【临时诊断】回收链自检</b> —— 服务端启动时跑一遍，把「回收进袋子」这条链
 * 从头到尾走一次，结果直接打进日志。
 *
 * <h2>为什么要写这个</h2>
 * <p>作者 2026-10-15 报：「之前是回收到收纳袋里错误的进备用区，现在是不进备用区了，
 * 但是干脆回收不了了，球飞回来剩余弹药没有正确增加。」</p>
 *
 * <p>这条链全是数据操作（套壳 → 判段 → 并进壳），<b>不需要玩家、不需要世界</b>，
 * 所以可以在这里直接跑。以前这类问题只能靠作者反复实测 + 我肉眼读代码，
 * 一轮要花很久；写成自检之后，跑一次 {@code runServer} 就能定位断在哪一环。</p>
 *
 * <h2>它检查什么</h2>
 * <ol>
 *   <li>造一颗「用过的木球」（带 {@code TOUGHNESS_LEFT} 组件）</li>
 *   <li>过 {@link BallClusterItem#clusterize} 套壳 —— 看壳里几颗</li>
 *   <li>{@link BallPouchItem#isPristine} 对壳返回什么（决定进备用区还是转运段）</li>
 *   <li><b>空袋子</b>里塞这个壳 —— 走 {@code fillClustered} 的「新建壳」那条</li>
 *   <li><b>袋里已有同类壳</b>时再塞一个 —— 走「并进已有壳」那条（回收时的实际路径）</li>
 * </ol>
 *
 * <h2>⚠️ 为什么能碰 ItemStack（而 BallIntegrationCheck 不能）</h2>
 * <p>那边挂在 {@code FMLCommonSetupEvent} 上时物品标签与 {@code DataComponents} 都没绑好，
 * {@code new ItemStack(...)} 会抛 {@code Components not bound yet}。
 * 本类挂在 {@link net.neoforged.neoforge.event.server.ServerAboutToStartEvent},
 * 那时注册表已经绑定完毕（同一个钩子上的 BallIntegrationCheck 能正常读物品标签）。
 * 保险起见整段仍包在 try/catch 里 —— 最坏情况只是打一条日志，不会影响启动。</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallRecoverySelfTest {

    private BallRecoverySelfTest() {
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerAboutToStart(
            net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) {
        try {
            run();
        } catch (Throwable t) {
            MoreBalls.LOGGER.warn("[球堆][自检] 回收链自检抛异常（不影响启动）", t);
        }
    }

    private static void log(String message, Object... args) {
        MoreBalls.LOGGER.info("[球堆][自检] " + message, args);
    }

    private static void run() {
        log("===== 回收链自检开始 =====");

        // ---------- ① 造一颗用过的木球 ----------
        ItemStack fresh = new ItemStack(ModItems.WOODEN_BALL.get());
        int max = BallBehavior.profileFor(fresh).toughness();
        log("① 木球：满耐久 {}，带组件={}", max, fresh.has(ModComponents.TOUGHNESS_LEFT.get()));
        if (max <= 0) {
            log("★ 木球没有耐久概念 ⇒ 后面的套壳逻辑不适用，自检结束");
            return;
        }
        int used = Math.max(1, max / 2);
        ItemStack worn = fresh.copy();
        BallItem.applyToughness(worn, used);
        log("   用过之后：剩余={}，档位={}（isClustered={}）",
                worn.get(ModComponents.TOUGHNESS_LEFT.get()),
                BallClusterTier.of(worn), BallClusterTier.of(worn).isClustered());

        // ---------- ② 套壳 ----------
        ItemStack shell = BallClusterItem.clusterize(worn.copy());
        boolean isShell = BallClusterItem.isCluster(shell);
        int innerCount = isShell ? BallClusterItem.contents(shell).size() : -1;
        log("② clusterize 后：是壳={}，壳里 {} 颗，壳 count={}", isShell, innerCount, shell.getCount());
        if (!isShell) {
            log("★ 没有套成壳 ⇒ 回收链在第一步就断了（BallClusterTier 判成满耐久？）");
            return;
        }

        // ---------- ③ isPristine 对壳的判断 ----------
        boolean prist = BallPouchItem.isPristine(shell);
        log("③ isPristine(壳) = {}  ⇒ 走 {}", prist, prist ? "备用区(ammo)" : "转运段(transit)");

        // ---------- ④ 空袋子：走「新建壳」那条 ----------
        ItemStack pouchA = new ItemStack(ModItems.BALL_POUCH.get());
        BallPouchTier tier = BallPouchItem.tier(pouchA);
        log("④ 袋子档位={}（ammo={} transit={}）", tier, tier.ammoSlots(), tier.transitSlots());

        ItemStack srcA = shell.copy();
        boolean okA = BallPouchItem.insertToTransit(pouchA, srcA);
        BallPouchContents cA = BallPouchItem.contents(pouchA);
        log("   空袋子插入 -> {}，源栈剩余 count={}", okA, srcA.getCount());
        log("   段内：非空格={}，总颗数={}", countSlots(cA.transit()), countBalls(cA.transit()));

        // ---------- ⑤ 袋里已有同类壳：走「并进已有壳」那条（回收的实际路径）----------
        ItemStack pouchB = pouchA.copy();
        ItemStack shell2 = BallClusterItem.clusterize(worn.copy());
        ItemStack srcB = shell2.copy();
        boolean okB = BallPouchItem.insertToTransit(pouchB, srcB);
        BallPouchContents cB = BallPouchItem.contents(pouchB);
        log("⑤ 已有同类壳时再插入 -> {}，源栈剩余 count={}", okB, srcB.getCount());
        log("   段内：非空格={}，总颗数={}（应当比上一行 +1，且格数不变）",
                countSlots(cB.transit()), countBalls(cB.transit()));

        // ---------- ⑥ 直接比一次「并入」的核心调用 ----------
        BallClusterContents existing = null;
        for (ItemStack s : BallPouchItem.contents(pouchB).transit()) {
            if (BallClusterItem.isCluster(s)) {
                existing = BallClusterItem.contents(s);
                break;
            }
        }
        if (existing == null) {
            log("⑥ 袋子转运段里找不到壳 —— 说明第 ⑤ 步根本没把壳放进去");
            log("===== 回收链自检结束 =====");
            return;
        }
        ItemStack probe = BallClusterItem.asBall(shell2);
        BallClusterContents after = existing.withAdded(probe);
        log("⑥ 核心比对：existing.size={} matches(probe)={} hasRoom={} withAdded 是否返回新对象={}",
                existing.size(), existing.matches(probe), existing.hasRoom(), after != existing);
        log("   probe：{}，耐久组件={}", probe.getHoverName().getString(),
                probe.get(ModComponents.TOUGHNESS_LEFT.get()));

        log("===== 回收链自检结束 =====");
    }

    /** 段里有多少个非空格 */
    private static int countSlots(List<ItemStack> segment) {
        int n = 0;
        for (ItemStack s : segment) {
            if (!s.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 段里总共有多少颗球（壳按内部颗数算） */
    private static int countBalls(List<ItemStack> segment) {
        int n = 0;
        for (ItemStack s : segment) {
            if (s.isEmpty()) {
                continue;
            }
            n += BallClusterItem.isCluster(s) ? BallClusterItem.contents(s).size() : s.getCount();
        }
        return n;
    }

    /**
     * 第二段自检：用<b>假玩家</b>跑完整的 {@link BallPouchHelper#deliverReturned}。
     *
     * <p>挂在 {@code ServerStartedEvent} 上 —— 那时世界已经加载好，
     * {@code FakePlayerFactory} 才拿得到玩家。</p>
     *
     * <p>这一段覆盖上一段测不到的部分：{@code allPouchStacks} 能不能找到袋子、
     * 分段判断、以及最终的 {@code Delivery} 返回码。</p>
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerStarted(
            net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        try {
            runWithFakePlayer(event.getServer());
        } catch (Throwable t) {
            MoreBalls.LOGGER.warn("[球堆][自检] 假玩家回收自检抛异常（不影响启动）", t);
        }
    }

    private static void runWithFakePlayer(net.minecraft.server.MinecraftServer server) {
        log("===== 假玩家回收自检开始 =====");
        net.minecraft.server.level.ServerLevel level = server.overworld();
        net.neoforged.neoforge.common.util.FakePlayer player =
                net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);

        ItemStack fresh = new ItemStack(ModItems.WOODEN_BALL.get());
        int max = BallBehavior.profileFor(fresh).toughness();
        ItemStack worn = fresh.copy();
        BallItem.applyToughness(worn, Math.max(1, max / 2));

        // 把袋子放进假玩家背包第 0 格
        ItemStack pouch = new ItemStack(ModItems.BALL_POUCH.get());
        player.getInventory().setItem(0, pouch);
        log("背包放了袋子：allPouchStacks 找到 {} 个", BallPouchHelper.allPouchStacks(player).size());

        // ---------- 第一次：用过的球 ----------
        ItemStack src1 = BallClusterItem.clusterize(worn.copy());
        BallPouchHelper.Delivery d1 = BallPouchHelper.deliverReturned(player, src1);
        BallPouchContents c1 = BallPouchItem.contents(pouch);
        log("第一次回收（用过的球）-> {}，源栈剩余={}，ammo 颗数={} transit 颗数={}",
                d1, src1.getCount(), countBalls(c1.ammo()), countBalls(c1.transit()));

        // ---------- 第二次：再来一颗同样的，看会不会并进同一个壳 ----------
        ItemStack src2 = BallClusterItem.clusterize(worn.copy());
        BallPouchHelper.Delivery d2 = BallPouchHelper.deliverReturned(player, src2);
        BallPouchContents c2 = BallPouchItem.contents(pouch);
        log("第二次回收（同种用过球）-> {}，源栈剩余={}，ammo 颗数={} transit 颗数={} transit 格数={}",
                d2, src2.getCount(), countBalls(c2.ammo()), countBalls(c2.transit()),
                countSlots(c2.transit()));

        // ---------- 第三次：满耐久的球（不该套壳，应当进 ammo）----------
        ItemStack pristine = BallClusterItem.clusterize(fresh.copy());
        log("满耐久球 clusterize 后：是壳={}", BallClusterItem.isCluster(pristine));
        ItemStack src3 = pristine.copy();
        BallPouchHelper.Delivery d3 = BallPouchHelper.deliverReturned(player, src3);
        BallPouchContents c3 = BallPouchItem.contents(pouch);
        log("第三次回收（满耐久球）-> {}，源栈剩余={}，ammo 颗数={} transit 颗数={}",
                d3, src3.getCount(), countBalls(c3.ammo()), countBalls(c3.transit()));

        log("===== 假玩家回收自检结束 =====");
    }
}
