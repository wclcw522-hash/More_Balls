package com.mcmodworkspace.moreballs;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * <b>球堆放进取石机 = 摊成「一叠 N 颗」，可以连续点击产物槽依次拿取</b>
 * （作者 2026-10-16 指定，第二轮修改）。
 *
 * <h2>为什么是「摊成一叠」而不是「切一颗」</h2>
 * <p>原版切石机的消耗写在 {@code resultSlot.onTake} 里，就一句
 * {@code inputSlot.remove(1)} —— <b>一次减「1 个物品」</b>。所以它天然支持
 * 「输入槽里放一叠材料 → 选中产物 → 连着点，依次把产物全取出来」。</p>
 *
 * <p>球堆的问题是：它自己 {@code count} 恒为 1（一格里装 N 颗球），
 * 不摊开的话点一次就被整个消耗掉了 —— 也就是作者第一轮看到的
 * 「一次一个填进去，没法连着拿」。</p>
 *
 * <p>所以这里在玩家开着切石机、输入槽里是球堆时，把它<b>摊成「一叠同种球」</b>：
 * 用壳里<b>耐久最低</b>那颗当模板（与「右键取一颗」同一个顺序：最低的先上场），
 * 数量 = 壳里颗数。之后全程走原版流程，连点产物槽就能依次取完。</p>
 *
 * <h2>为什么不用 Mixin</h2>
 * <p>不需要碰原版代码就能做到 —— {@code StonecutterMenu} 的槽位索引公开
 * （{@link StonecutterMenu#INPUT_SLOT}），槽位走标准 {@code Slot#set} 会正确同步；
 * 触发时机用官方的 {@code PlayerTickEvent}（只服务端、且只在开着切石机那一刻跑）。</p>
 *
 * <h2>已知的两点代价（都在可接受范围内）</h2>
 * <ul>
 *   <li><b>耐久会被统一成「最低那颗」</b>：壳里的球同档位但具体耐久可能差几点，
 *       摊成一叠只能取一个值。取最低值是刻意的 —— 和切/取的顺序一致，
 *       而且进切石机的球本来就是要被消耗掉的。</li>
 *   <li><b>没切完就取回时拿到的是「一叠同种球」</b>而不是球堆：球堆能随时右键收回、
 *       也能用整理键重新收堆。</li>
 * </ul>
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

        BallClusterContents contents = BallClusterItem.contents(input);
        int count = contents.balls().size();
        if (count <= 0) {
            return;   // 空壳：没什么可切的
        }

        // 摊成一叠：模板 = 壳里耐久最低的那颗（`lowest()` 也是 `takeOne` 取的同一颗）
        ItemStack stack = contents.lowest().copy();
        stack.setCount(count);   // MAX_BALLS = 64，正好等于原版一叠的上限
        menu.getSlot(StonecutterMenu.INPUT_SLOT).set(stack);
    }
}
