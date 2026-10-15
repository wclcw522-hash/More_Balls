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

        // ===== 第一趟：把「用过的散球」统统取出来 =====
        List<ItemStack> loose = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty()
                    || BallClusterItem.isCluster(slot)              // 已经是壳
                    || !BallAmmo.isBall(slot)                       // 不是球
                    || !BallClusterTier.of(slot).isClustered()) {   // 满耐久 / 没耐久概念
                continue;
            }
            loose.add(slot.copy());
            inventory.setItem(i, ItemStack.EMPTY);
        }
        if (loose.isEmpty()) {
            return 0;
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
            BallClusterItem.setContents(slot, existing.withAdded(ball));
            return true;
        }

        // ② 没有就新建一个壳
        ItemStack shell = new ItemStack(ModItems.BALL_CLUSTER.get());
        BallClusterItem.setContents(shell, BallClusterContents.EMPTY.withAdded(ball));
        return inventory.add(shell);
    }
}