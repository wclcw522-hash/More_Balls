package com.mcmodworkspace.moreballs;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 把收纳袋的组件数据包装成原版 {@link Container}，供 GUI 使用。
 *
 * <p>索引 {@code 0 .. 26} 是弹药段，{@code 27 .. 53} 是转运段 —— <b>恒定为 27 + 27</b>，
 * 与等级无关。等级只决定「前多少个格子是解锁的」，其余的在界面上盖灰禁用
 * （判断在 {@link BallPouchMenu} 里，通过 {@code Slot.isActive()}）。</p>
 *
 * <p>这样界面尺寸恒定＝原版箱子大小，背景一行 {@code blit} 就贴完，
 * 升级也不必重建界面。</p>
 */
public class BallPouchContainer implements Container {

    /** 每段固定格数 */
    public static final int AMMO_SLOTS = BallPouchContents.FIXED_SEGMENT_SIZE;

    /** 总格数 */
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
        // 只要袋子还在玩家身上就一直有效
        return player.getInventory().contains(this.pouch)
                || player.getMainHandItem() == this.pouch
                || player.getOffhandItem() == this.pouch;
    }

    @Override
    public void clearContent() {
        BallPouchItem.setContents(this.pouch, BallPouchContents.EMPTY);
    }
}
