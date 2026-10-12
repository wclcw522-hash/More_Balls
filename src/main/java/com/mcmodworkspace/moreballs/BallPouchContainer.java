package com.mcmodworkspace.moreballs;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 把收纳袋的组件数据包装成原版 {@link Container}，供 GUI 使用。
 *
 * <p>索引 {@code 0 .. 53} 是弹药段，{@code 54 .. 107} 是转运段 —— 两段各 54 格，
 * 与等级无关。等级只决定「前多少个格子是解锁的」，其余的在界面上盖灰禁用
 * （判断在 {@link BallPouchMenu} 里，通过 {@code Slot.isActive()}）。</p>
 *
 * <p>⚠️ <b>容器</b>格数恒定（108），但<b>界面尺寸随等级变</b>：{@link BallPouchMenu}
 * 按各等级的实际格数排行，高度由 {@code guiHeight()} 算出，所以背景不是
 * 「一行 {@code blit} 就贴完」。升级不必重建容器，但界面行数会变。</p>
 */
public class BallPouchContainer implements Container {

    /** 每段固定格数 */
    public static final int AMMO_SLOTS = BallPouchContents.FIXED_SEGMENT_SIZE;

    /**
     * 总格数 —— 弹药段 + 转运段。
     *
     * <p>两段共用 {@link BallPouchContents#FIXED_SEGMENT_SIZE}（54）作为<b>存储</b>上限，
     * 实际可用格数由 {@link BallPouchTier} 决定（转运段是弹药段的两倍）。</p>
     */
    public static final int TOTAL_SLOTS = AMMO_SLOTS * 2;

    /** 装着袋子的那个物品堆（注意：是袋子的 ItemStack，不是袋子里面的东西） */
    private final ItemStack pouch;

    public BallPouchContainer(ItemStack pouch) {
        this.pouch = pouch;
    }

    /** 这个索引是不是弹药段 */
    public boolean isAmmoSlot(int index) {
        return index < AMMO_SLOTS;
    }

    @Override
    public int getContainerSize() {
        return TOTAL_SLOTS;
    }

    @Override
    public boolean isEmpty() {
        return BallPouchItem.contents(this.pouch).isEmpty();
    }

    @Override
    public ItemStack getItem(int index) {
        BallPouchContents contents = BallPouchItem.contents(this.pouch);
        List<ItemStack> segment = isAmmoSlot(index) ? contents.ammo() : contents.transit();
        int i = isAmmoSlot(index) ? index : index - AMMO_SLOTS;
        return i < segment.size() ? segment.get(i) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int index, int amount) {
        ItemStack current = getItem(index);
        if (current.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = current.split(amount);
        setItem(index, current);
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        ItemStack current = getItem(index);
        setItem(index, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        BallPouchContents contents = BallPouchItem.contents(this.pouch);
        boolean ammo = isAmmoSlot(index);
        int i = ammo ? index : index - AMMO_SLOTS;

        List<ItemStack> segment = new ArrayList<>(ammo ? contents.ammo() : contents.transit());
        while (segment.size() <= i) {
            segment.add(ItemStack.EMPTY);   // 索引超出时先补齐
        }
        segment.set(i, stack);

        BallPouchItem.setContents(this.pouch, ammo
                ? new BallPouchContents(segment, contents.transit())
                : new BallPouchContents(contents.ammo(), segment));
    }

    @Override
    public void setChanged() {
        // 改动已经即时写回组件了，这里不用再做别的
    }

    @Override
    public boolean stillValid(Player player) {
        // 袋子还在身上就有效 —— 口径统一走 allPouchStacks（背包 / 快捷栏 / 饰品栏都算），
        // 与 BallPouchMenu.stillValid 保持一致。
        // 原先这里只查背包与主副手，挂在饰品栏（背饰 / 腰带 / 护符）上的袋子会被判成
        // 「袋子不在了」，虽然影响面比菜单那边小，但同样是漏了饰品栏。
        for (ItemStack stack : BallPouchHelper.allPouchStacks(player)) {
            if (stack == this.pouch || ItemStack.isSameItemSameComponents(stack, this.pouch)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void clearContent() {
        BallPouchItem.setContents(this.pouch, BallPouchContents.EMPTY);
    }
}
