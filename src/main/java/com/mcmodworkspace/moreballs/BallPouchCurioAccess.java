package com.mcmodworkspace.moreballs;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 真正触碰 Curios API 的那一层。
 *
 * <h2>为什么单独拆一个类</h2>
 * <p>这个类里出现了 {@code top.theillusivec4.curios.*} 的符号，所以它
 * <b>只有装了 Curios 时才能被加载</b>。所有调用点都先过
 * {@link BallPouchCurios#isLoaded()} 这道判断 —— 为 false 时 JVM 根本不会碰这个类，
 * 于是「没装 Curios 的玩家」不会因为找不到这些接口而报 {@code NoClassDefFoundError}。</p>
 *
 * <p>这就是模组兼容性规范里那条「第三方模组一律 compileOnly + 运行时判断」的落地写法：
 * 把外部符号集中关在一个类里，用之前先确认对方在不在。</p>
 */
final class BallPouchCurioAccess {

    private BallPouchCurioAccess() {
    }

    /** 在玩家的饰品栏里找第一个收纳袋 */
    static Optional<ItemStack> findWorn(Player player) {
        Optional<ICuriosItemHandler> handler = CuriosApi.getCuriosInventory(player);
        if (handler.isEmpty()) {
            return Optional.empty();
        }
        return handler.get()
                .findFirstCurio(BallPouchItem::isPouch)
                .map(SlotResult::stack);
    }

    /**
     * 玩家的饰品栏里<b>所有</b>收纳袋。
     *
     * <p>收纳袋可以戴在背饰 / 腰带 / 护符三个槽上，所以可能同时戴着好几个 ——
     * 统计弹药、自动装填时不能只看第一个。</p>
     */
    static List<ItemStack> findAllWorn(Player player) {
        Optional<ICuriosItemHandler> handler = CuriosApi.getCuriosInventory(player);
        if (handler.isEmpty()) {
            return List.of();
        }
        List<ItemStack> out = new ArrayList<>();
        for (SlotResult result : handler.get().findCurios(BallPouchItem::isPouch)) {
            out.add(result.stack());
        }
        return out;
    }
}
