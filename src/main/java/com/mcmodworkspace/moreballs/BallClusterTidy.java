package com.mcmodworkspace.moreballs;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>一键整理</b> —— 把背包里的散球按档收成球堆。
 *
 * <h2>为什么需要它（作者 2026-10-15 要求）</h2>
 * <p>球堆原本只在**回收**时生成（球靠【空气动力球】飞回来的那一刻）。要验证它就得先
 * 打一场球、还得给弩附魔 —— 门槛太高，而且一旦哪里没走到，作者只能靠肉眼在背包里翻，
 * 排不出「机制本身」和「回收链路」谁的问题。</p>
 *
 * <p>所以加一个手动入口：<b>背包界面角落一个小按钮</b>，点一下就把背包里的散球收成堆。
 * 它<b>不依赖任何附魔</b>，一装就能试。</p>
 *
 * <h2>为什么单开一个类而不并进 BallClusterItem</h2>
 * <p>整理涉及「遍历整个背包 + 先收集再放回」的两趟扫描，逻辑量不小；
 * 放在 {@link BallClusterItem} 里会让那个类既管单个壳的读写、又管整个背包的整理。
 * 分开之后，{@link BallClusterItem} 仍然是「一颗球的读写」，这个是「一批球的整理」。</p>
 *
 * <h2>整理规则</h2>
 * <ul>
 *   <li><b>只动「用过的散球」</b> —— 满耐久的球不套壳（它们本来就能叠成一堆），
 *       已经是壳的也跳过</li>
 *   <li>按 {@link BallClusterTier} 的档位：<b>同球种 + 同档位</b>进同一个壳</li>
 *   <li>先找背包里已有的同类壳塞进去，找不到才新建 —— 和回收那条路同一套判据</li>
 * </ul>
 */
public final class BallClusterTidy {

    private BallClusterTidy() {
    }

    /**
     * 整理玩家的主背包（含快捷栏）。
     *
     * <h2>为什么分两趟</h2>
     * <p>第一趟只做「把散球从格子里拿出来、收进一个临时列表」，
     * 第二趟才「往壳里放」。写成一趟（边走边塞）的话，
     * 放进去的壳本身也是 {@code ItemStack}，遍历会把它算进去，
     * 位置和计数都容易乱 —— 这在袋子那 6 处改动里已经踩过一次。</p>
     *
     * @return 被收进壳的球数（0 表示没什么可整理的）
     */
    public static int tidy(Player player) {
        var inventory = player.getInventory();

        // ===== 第一趟：把「用过的球」统统取出来 =====
        //
        // ⚠️ 这里**不跳过已有的壳** —— 老的实现见到壳就 `continue`，于是：
        //   · 壳里那些球永远参与不了重新分档
        //   · 同球种但档位不同的球分成好几个壳，看着就是「没堆叠」
        //   现在的做法是**把壳整个摊开**（掏空并清掉那一格），
        //   里面的球和背包里的散球一起进 loose，第二趟统一按档重新堆。
        //   这才是「一键整理」该有的语义：把所有球归位，而不是只扫散球。
        List<ItemStack> loose = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty() || !BallAmmo.isBall(slot)) {
                continue;   // 空格 / 不是球（壳不是球，见下面单独处理）
            }
            if (!BallClusterTier.of(slot).isClustered()) {
                continue;   // 满耐久 / 没耐久概念 —— 它们本来就能叠成一堆，不动
            }
            loose.add(slot.copy());
            inventory.setItem(i, ItemStack.EMPTY);
        }
        // 再把已有的壳摊开：里面的球全部取出来，壳那一格腾空（下方会重新建）
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty() || !BallClusterItem.isCluster(slot)) {
                continue;
            }
            for (ItemStack one = BallClusterItem.takeOne(slot);
                 !one.isEmpty();
                 one = BallClusterItem.takeOne(slot)) {
                loose.add(one);
            }
            inventory.setItem(i, ItemStack.EMPTY);
        }
        if (loose.isEmpty()) {
            return 0;
        }

        // ===== 顺带：收纳袋的转运段也就地整理一遍 =====
        //
        // ⚠️ 作者 2026-10-15 反馈「整理键仅对当前背包生效」。
        //    袋子那两段原先完全没被碰过 —— 而转运段正是**装用过的球**的那一段，
        //    它里面的散球同样会因为耐久不同而一颗一格。
        //
        //    这里不把袋子里的球搬到背包再搬回去（那会打乱两段的分工），
        //    而是**就地收成壳** —— 复用 BallPouchItem 里回收流程用的同一段逻辑，
        //    保证「进袋的球」和「整理后的球」有完全一样的形态。
        for (ItemStack pouch : BallPouchHelper.allPouchStacks(player)) {
            BallPouchContents pouchNow = BallPouchItem.contents(pouch);
            // ⚠️ 两处必须做对，否则这段是**静默无效**的：
            //   a. 先复制成可变 List —— clusterizeSegment 内部用 set() 改元素，
            //      而 record 里那个 List 不保证可变，直接传进去会在运行时抛异常
            //   b. 改完必须 setContents 写回 —— List 只是记录里的一个字段，
            //      就地改元素不会让物品的组件跟着变
            List<ItemStack> transitNow = new ArrayList<>(pouchNow.transit());
            if (BallPouchItem.clusterizeSegment(transitNow)) {
                BallPouchItem.setContents(pouch,
                        new BallPouchContents(pouchNow.ammo(), transitNow));
            }
        }

        // ===== 第二趟：逐颗塞进壳 =====
        int moved = 0;
        for (ItemStack ball : loose) {
            if (putIntoSomeShell(inventory, ball)) {
                moved++;
            } else {
                // 理论上不会 —— 上面刚腾出格子，但真出意外也别让球凭空消失
                if (!inventory.add(ball)) {
                    player.drop(ball, false);
                }
            }
        }
        // 【临时诊断】整理链的关键数。
        //
        // 作者 2026-10-15 报「按整理会让一个球以球堆的方式进物品栏最靠前那格」，
        // 而我读完整个 tidy 的结论是：**它只遍历 getInventory()（0..35，不含副手）**，
        // 副手那颗球不可能被它直接搬走。所以要看清楚到底哪一步多出了东西：
        //   · loose 有几颗（这是唯一会被重新装箱的来源）
        //   · 副手在整理后是什么（本该原样不动）
        MoreBalls.LOGGER.info("[球堆][诊断] 整理：loose={} 颗 / 副手={} / 收纳袋={} 个",
                loose.size(),
                player.getOffhandItem().isEmpty()
                        ? "空" : player.getOffhandItem().getHoverName().getString(),
                BallPouchHelper.allPouchStacks(player).size());

        // 整理动过袋子（转运段就地收壳）和背包 ⇒ 让客户端把最新内容拉过去。
        // 不同步的话 HUD 上那个弹药数会一直显示旧值（作者 2026-10-15 反馈）。
        BallPouchHelper.syncPouchToClient(player);

        return moved;
    }

    /**
     * 把一颗球放进背包里已有的同类壳；没有就新建一个壳。
     *
     * <p>「同类」= {@link BallClusterContents#matches} —— 同球种 + 同档位。</p>
     *
     * <p>⚠️ 比对时用的是 {@link BallClusterItem#asBall 摊开后的那颗球}，不是壳本身 ——
     * 拿壳去 matches 会因为 item 不同而永远失配，结果就是「一个壳只装一颗」。
     * 这处在袋子那边踩过一次，别重复踩。</p>
     *
     * @return true 表示它进了某个壳；false 表示哪儿都放不下（由调用方兜底）
     */
    private static boolean putIntoSomeShell(net.minecraft.world.entity.player.Inventory inventory,
                                            ItemStack ball) {
        // ① 先找已有的同类壳
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (!BallClusterItem.isCluster(slot)) {
                continue;
            }
            BallClusterContents existing = BallClusterItem.contents(slot);
            ItemStack probe = BallClusterItem.asBall(slot);
            if (probe.isEmpty() || !existing.hasRoom() || !existing.matches(ball)) {
                continue;
            }

            // ⚠️⚠️ **必须核对 withAdded 到底加进去没有**（作者 2026-10-15 报「整理时莫名消失一部分球」）。
            //
            //     `withAdded` 在 `balls.size() >= MAX_BALLS` 或 `!matches(ball)` 时会
            //     **静默返回 this**（球根本没进壳）。而它内部的判据和上面这两行是**重复的** ——
            //     重复就意味着两处可能不一致，一旦不一致，球就会在这条缝里被丢掉。
            //
            //     所以这里改成：加不进去就 `continue` 找下一个壳，**绝不把它当成功**。
            //     （`BallClusterContents` 是 record，`withAdded` 失败时返回同一个引用，
            //       用 `==` 判断可靠。）
            BallClusterContents after = existing.withAdded(ball);
            if (after == existing) {
                continue;
            }
            BallClusterItem.setContents(slot, after);
            return true;
        }

        // ② 没有就新建一个壳
        ItemStack shell = new ItemStack(ModItems.BALL_CLUSTER.get());
        BallClusterItem.setContents(shell, BallClusterContents.EMPTY.withAdded(ball));
        return inventory.add(shell);
    }
}