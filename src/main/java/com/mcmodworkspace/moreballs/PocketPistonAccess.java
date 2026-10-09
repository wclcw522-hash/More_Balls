package com.mcmodworkspace.moreballs;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import java.util.List;

/**
 * 真正触碰 Curios API 的那一层 —— 只为「奇异饰品的袖珍活塞」服务。
 *
 * <p>本类里出现了 {@code top.theillusivec4.curios.*} 的符号，所以它
 * <b>只有装了 Curios 时才能被加载</b>。所有调用点都先过
 * {@link PocketPistonCompat#isLoaded()} 这道判断 —— 为 false 时 JVM 根本不会碰这个类，
 * 于是「没装 Curios 的玩家」不会因为找不到这些接口而报 {@code NoClassDefFoundError}。</p>
 *
 * <p>写法与 {@link BallPouchCurioAccess} 完全一致，不要在这里加任何业务逻辑。</p>
 */
final class PocketPistonAccess {

    private PocketPistonAccess() {
    }

    /** 玩家身上所有饰品栏位里，有没有这一件 */
    static boolean wears(Player player, String itemId) {
        List<SlotResult> found = CuriosApi.getCuriosInventory(player)
                .map(inv -> inv.findCurios(itemId))
                .orElse(List.of());
        for (SlotResult result : found) {
            ItemStack stack = result.stack();
            if (!stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}