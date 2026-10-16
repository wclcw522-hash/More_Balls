package com.mcmodworkspace.moreballs;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * <b>球堆进切石机 = 只切里面耐久最低的那一颗</b>（作者 2026-10-16 指定）。
 *
 * <h2>为什么需要这一层</h2>
 * <p>球堆是个「壳」，里面装着 N 颗同种、耐久档位不同的球。而切石机的机制是
 * <b>消耗 1 个输入物品 → 产出 1 份产物</b>（由 {@code StonecutterMenu} 直接
 * {@code container.removeItem(0, 1)} 完成），配方侧<b>拿不到玩家、也改不了输入槽</b> ——
 * 所以「只切一颗、剩下的留在堆里」这件事没法写在配方里。</p>
 *
 * <p>于是这里在玩家开着切石机时做一次<b>摊开</b>：输入槽里是球堆就把耐久最低那颗
 * 取到输入槽上（{@link BallClusterItem#takeOne}），剩下的球堆塞回玩家背包。
 * 之后就是<b>普通的切球流程</b>——配方产出的碎片来源正确，也不需要球堆参与任何额外逻辑。</p>
 *
 * <h2>为什么不用 Mixin</h2>
 * <p>{@code StonecutterMenu} 的输入容器是 <b>{@code public final Container container}</b>、
 * 槽位索引也公开（{@link StonecutterMenu#INPUT_SLOT}），槽位本身走标准
 * {@code Slot#set} 就能正确同步 —— 也就是说这件事不需要碰原版代码。
 * 触发时机用官方的 {@code PlayerTickEvent}（只在服务端、且只在开着切石机那一刻跑）。</p>
 *
 * <h2>副作用（已知且可接受）</h2>
 * <p>玩家把球堆放进切石机<b>只是想暂存</b>时，它也会被摊开成一颗球 + 背包里剩下的堆。
 * 球堆本来就能随时右键取一颗、也能用整理键重新收堆，所以这个代价换的是
 * 「球堆终于能切、JEI 里也看得到配方」。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallClusterCutting {

    private BallClusterCutting() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 只有开着切石机才管 —— 其余容器（箱子 / 背包 / 收纳袋）一概不碰
        if (!(player.containerMenu instanceof StonecutterMenu menu)) {
            return;
        }

        ItemStack input = menu.getSlot(StonecutterMenu.INPUT_SLOT).getItem();
        if (!BallClusterItem.isCluster(input)) {
            return;   // 不是球堆（普通球本来就能切）—— 不动
        }
        if (BallClusterItem.contents(input).isEmpty()) {
            return;   // 空壳：没什么可切的
        }

        // 摊开一颗：takeOne 就地取走耐久最低那颗，壳里剩 N-1（取空了会自己摘掉组件）
        ItemStack one = BallClusterItem.takeOne(input);
        if (one.isEmpty()) {
            return;
        }
        ItemStack rest = input.copy();   // takeOne 之后的剩余壳

        // 输入槽换成那一颗球 —— 接下来就是普通的切球流程
        menu.getSlot(StonecutterMenu.INPUT_SLOT).set(one);

        // 剩余的球堆塞回玩家背包；背包满就掉在脚边（绝不静默丢弃）
        if (!BallClusterItem.contents(rest).isEmpty() && !player.getInventory().add(rest)) {
            player.drop(rest, false);
        }
    }
}
