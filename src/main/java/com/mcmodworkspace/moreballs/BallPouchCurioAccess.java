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

    /**
     * <b>把饰品栏里的收纳袋原样写回槽位</b> —— 用来触发 Curios 的槽位同步。
     *
     * <h2>为什么需要它</h2>
     * <p>袋子的内容存在 {@code ItemStack} 的<b>组件</b>里（{@code POUCH_CONTENTS}）。
     * 我们改内容时是直接对那个 {@code ItemStack} 调 {@code set(component, …)} ——
     * Curios 完全不知道「这一格变了」，它只在<b>栈被换过</b>（{@code setStackInSlot}）
     * 时才认为需要同步。</p>
     *
     * <p>后果很具体：服务端改了组件、客户端那份还是旧的 ⇒ HUD 上那个弹药数
     * <b>不实时更新</b>，要玩家手动动一下袋子里的球才会刷新（作者 2026-10-15 反馈）。</p>
     *
     * <p>所以改完之后调一次本方法：内容不变，只是把栈「换」一遍，
     * 让 Curios 把这一格重新同步给客户端。</p>
     */
    static void forceSync(Player player) {
        Optional<ICuriosItemHandler> handlerOpt = CuriosApi.getCuriosInventory(player);
        if (handlerOpt.isEmpty()) {
            return;
        }
        for (top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler stacks
                : handlerOpt.get().getCurios().values()) {
            top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler inv = stacks.getStacks();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (BallPouchItem.isPouch(stack)) {
                    // 传一份 copy：Curios 的同步判据是「栈有没有被换过」，
                    // 传同一个引用可能被短路掉。
                    inv.setStackInSlot(i, stack.copy());
                }
            }
        }
    }
}
