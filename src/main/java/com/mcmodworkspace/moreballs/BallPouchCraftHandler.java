package com.mcmodworkspace.moreballs;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 收纳袋升级时把里面的球带过去。
 *
 * <h2>为什么需要这个</h2>
 * <p>升级配方是<b>有形状</b>的（外圈材料 + 正中上一级袋子），而原版的有形状合成
 * <b>不会保留输入物品的数据组件</b> —— 结果就是一个全新的空袋子，里面的球全没了。
 * （原版那个会保留组件的 {@code crafting_transmute} 是无形状的，形状对不上作者给出的九宫格。）</p>
 *
 * <p>所以在这里手动搬运：合成出更高级的袋子时，从合成格里找输入的那个低级袋子，
 * 把它的内容物原样写到结果上。</p>
 *
 * <h2>触发时机</h2>
 * <p>{@code ItemCraftedEvent} 在 {@code CraftingResultSlot.onTake} 里触发，
 * 此时合成格的输入还在（原版要在这之后才消耗材料），所以读得到。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallPouchCraftHandler {

    private BallPouchCraftHandler() {
    }

    @SubscribeEvent
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        ItemStack crafted = event.getCrafting();
        if (crafted.isEmpty() || !BallPouchItem.isPouch(crafted)) {
            return;
        }

        // 在合成格里找「另一个收纳袋」—— 那就是被升级的那个
        Container grid = event.getInventory();
        if (grid == null) {
            return;
        }
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack input = grid.getItem(i);
            if (input.isEmpty() || input == crafted || !BallPouchItem.isPouch(input)) {
                continue;
            }
            // 找到了：把它的内容物搬到新的袋子上
            BallPouchItem.setContents(crafted, BallPouchItem.contents(input));
            MoreBalls.LOGGER.debug("[pouch] 升级时搬运内容物：{} -> {}",
                    BallPouchItem.tier(input), BallPouchItem.tier(crafted));
            return;
        }
    }
}
