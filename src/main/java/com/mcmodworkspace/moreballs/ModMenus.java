package com.mcmodworkspace.moreballs;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的界面菜单注册。
 *
 * <p>收纳袋的菜单需要把「装着袋子的那个物品堆」本身传到客户端（界面要读它的内容），
 * 所以用 {@link IMenuTypeExtension#create} 走自定义数据包，而不是原版那种
 * 「传几个坐标」的简单工厂。</p>
 */
public final class ModMenus {

    private ModMenus() {
    }

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.MENU, MoreBalls.MOD_ID);

    /** 魔丸收纳袋 */
    public static final DeferredHolder<MenuType<?>, MenuType<BallPouchMenu>> BALL_POUCH =
            MENUS.register("ball_pouch", () -> IMenuTypeExtension.create(
                    (windowId, inventory, data) -> new BallPouchMenu(windowId, inventory, data)));

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
