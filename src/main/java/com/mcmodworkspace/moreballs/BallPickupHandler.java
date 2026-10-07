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

    @SubscribeEvent
    public static void onPickup(ItemEntityPickupEvent.Pre event) {
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
        // 什么都没装进去（身上没袋子 / 袋满）→ 放行，让球正常进背包
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
