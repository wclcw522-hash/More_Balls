package com.mcmodworkspace.moreballs;

import net.minecraft.util.TriState;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

/**
 * <b>掉落物形态的球，被玩家捡起时优先进入收纳袋</b>（作者 2026-10-07 指定）。
 *
 * <h2>要解决什么</h2>
 * <p>默认情况下玩家走近掉落物会自动捡进背包 —— 但球本来就该进袋子：
 * 手里拎着袋子时尤其如此（那时候捡球不先占快捷栏才顺手）。原版没有
 * 「捡到别处去」的钩子，所以这里拦 {@link ItemEntityPickupEvent.Pre}：
 * 先把球塞进身上的袋子，塞成功了就告诉原版「这次捡拾由我接管」
 * （{@code setCanPickup(FALSE)}），物品实体再由我们自己收尾。</p>
 *
 * <h2>「优先」的含义</h2>
 * <p>袋子塞不下时<b>不拦</b>，让原版照常把球放进背包 —— 所以是「优先入袋」，
 * 不是「只能入袋」。这样玩家不会遇到「袋满了球就捡不起来」。</p>
 *
 * <h2>边界</h2>
 * <ul>
 *   <li><b>只认本模组的球</b>：走 {@link BallAmmo#isBall}（也就是 {@code #more_balls:balls}
 *       标签），原版雪球、末影珍珠这类同样在标签里的东西也一并算是球</li>
 *   <li><b>袋子可以是任何位置</b>：主手 / 副手 / 背包 / 饰品栏都算 ——
 *       球进哪一段由 {@link BallPouchItem#insert} 按耐久决定</li>
 * </ul>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallPickupHandler {

    private BallPickupHandler() {
    }

    // ===== Q 丢球之后的「自动拾取冷却」（作者 2026-10-11 指定）=====

    /**
     * 作者指定：<b>用 Q 丢出球之后 5 秒内不再自动拾取</b>。
     *
     * <p>要解决的问题：收纳袋会在球落地后立刻把它吸回去，于是
     * <b>球根本丢不出来</b> —— 想在地上留颗球给怪物捡都做不到。</p>
     *
     * <p>实现放在**拾取的入口**（本类的 {@code ItemEntityPickupEvent.Pre}）
     * 与球实体的右键接球两处，都是「拦一次就整段时间不生效」，
     * 不需要每刻轮询。</p>
     */
    public static final int DROP_LOCK_TICKS = 100;

    /** 玩家 UUID → 自动拾取恢复的游戏刻 */
    private static final java.util.Map<java.util.UUID, Long> DROP_LOCK =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Q 丢出的是球 → 记一段「别再自动捡回来」的冷却 */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onItemToss(net.neoforged.neoforge.event.entity.item.ItemTossEvent event) {
        if (event.getPlayer().level().isClientSide()) {
            return;
        }
        ItemStack tossed = event.getEntity().getItem();
        if (!BallAmmo.isBall(tossed)) {
            return;
        }
        long until = event.getPlayer().level().getGameTime() + DROP_LOCK_TICKS;
        DROP_LOCK.put(event.getPlayer().getUUID(), until);
        // 顺带把原版那 40 刻的拾取延迟也拉到同一时长 —— 双保险，
        // 免得本模组没拦到的路径（别的 mod、漏斗等）又把它吸走。
        event.getEntity().setPickUpDelay(DROP_LOCK_TICKS);

    }

    /** 这个玩家现在是不是处在「刚丢过球」的自动拾取冷却里 */
    public static boolean isDropLocked(Player player) {
        Long until = DROP_LOCK.get(player.getUUID());
        if (until == null) {
            return false;
        }
        if (player.level().getGameTime() >= until) {
            DROP_LOCK.remove(player.getUUID());
            return false;
        }
        return true;
    }

    @SubscribeEvent
    public static void onPickup(ItemEntityPickupEvent.Pre event) {
        // ⚠️ **先判「刚 Q 丢过球」**（作者 2026-10-11 指定：丢出后 5 秒关闭自动拾取）。
        //    必须放在最前面 —— 放后面的话，收纳袋那边已经把这个球安排进袋子了，
        //    再拦也来不及（球已经不在世界里了）。
        if (event.getPlayer() instanceof Player p && isDropLocked(p)) {
            // ⚠️ 这个事件**不可取消** —— NeoForge 给的是 setCanPickup（不是 setCanceled）
            event.setCanPickup(net.minecraft.util.TriState.FALSE);
            return;
        }
        ItemEntity itemEntity = event.getItemEntity();
        ItemStack stack = itemEntity.getItem();
        if (!BallAmmo.isBall(stack)) {
            return;   // 不是球，不插手
        }

        Player player = event.getPlayer();

        // 手里的袋子优先（手里拎着袋子就是「我要收球」的意思），
        // 然后才是身上其它位置的袋子。挨个试到的第一个能装下的就收。
        ItemStack handPouch = BallPouchHelper.pouchInHand(player);
        if (!handPouch.isEmpty() && BallPouchItem.insert(handPouch, stack)) {
            finishPickup(event, itemEntity, stack);
            return;
        }
        for (ItemStack pouch : BallPouchHelper.allPouchStacks(player)) {
            if (pouch == handPouch) {
                continue;   // 上面已经试过了
            }
            if (BallPouchItem.insert(pouch, stack)) {
                finishPickup(event, itemEntity, stack);
                return;
            }
        }
        // 什么都没装进去（身上没袋子 / 袋满）→ 不再直接放行！
        //
        // ⚠️ 作者 2026-10-15：「球要在收回或捡起的时候自动进入球堆，而不是要等到手动整理」。
        //    原先这里直接 return，让原版把球塞进背包 —— 而用过的球耐久各不相同，
        //    组件不一致就**必然一颗一格**，打完一局背包又被吃满，只能靠「一键整理」手动收。
        //
        //    现在改成：**用过的球就地封成壳再交给玩家**。落点规则复用 deliverReturned
        //    （袋子里转槽 → 背包 → 掉脚下），和弩射完的回收完全一致。
        //    满耐久的球照旧放行 —— 它们本来就能和同批新球叠成一堆，套壳是画蛇添足。
        if (BallClusterTier.of(stack).isClustered()) {
            ItemStack shell = BallClusterItem.clusterize(stack.copy());
            if (BallClusterItem.isCluster(shell)) {
                event.setCanPickup(TriState.FALSE);
                BallPouchHelper.Delivery delivery = BallPouchHelper.deliverReturned(player, shell);
                if (delivery == BallPouchHelper.Delivery.NOWHERE) {
                    player.drop(shell, false);   // 实在放不下就掉在脚下，绝不凭空消失
                }
                itemEntity.discard();
                return;
            }
        }
        // 满耐久 / 没耐久概念 → 放行，让球正常进背包（它们本来就叠得起来）
    }

    /**
     * 收尾：告诉原版「这份不用进背包」，并在球被全部收走时把物品实体收掉。
     *
     * <p>{@code insert} 会就地消耗传入的栈，所以它空了就说明一颗不剩。</p>
     */
    private static void finishPickup(ItemEntityPickupEvent.Pre event, ItemEntity itemEntity, ItemStack stack) {
        event.setCanPickup(TriState.FALSE);
        if (stack.isEmpty()) {
            itemEntity.discard();
        }
    }
}
