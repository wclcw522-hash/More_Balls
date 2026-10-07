package com.mcmodworkspace.moreballs;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 「当前选中的弹药」以及玩家身上现存的弹药种类。
 *
 * <h2>为什么整套都用 ItemStack 而不是 Item 做种类键</h2>
 * <p>组合球**全都是同一个物品 id**（`combo_ball`），区别只在数据组件里。
 * 早先这里用 {@code Map<Item, Integer>} 统计、用 {@code new ItemStack(item)} 还原样本，
 * 结果**组件全丢** —— 铁-金球、木-圆石球、四合一球在轮盘里被合并成一个，
 * 选中之后也分不清是哪一种（作者反馈的「r 键轮盘会把所有组合球合并」）。</p>
 *
 * <p>现在统一用「**同 item + 同数据组件，但忽略耐久**」作为种类口径 ——
 * 与 {@link BallPouchItem#sameKind} 完全一致，因为作者定过「取弹不看耐久」。</p>
 *
 * <h2>选中的弹药怎么存</h2>
 * <p>{@link ModAttachments#SELECTED_AMMO} 存的是<b>整颗样本球的快照</b>（ItemStack），
 * 不再是物品 id 字符串 —— 只有这样才带得住组件，组合球才能被区分。</p>
 */
public final class BallAmmoSelection {

    private BallAmmoSelection() {
    }

    /**
     * 一种弹药 —— 样本球 + 数量。
     *
     * @param sample 这种球的一个样本（带组件，但**不含耐久**，所以它代表「种类」而非某一颗）
     * @param count  玩家身上这种球的总数
     */
    public record AmmoKind(ItemStack sample, int count) {
    }

    /**
     * 把一颗球归约成「种类样本」：复制一个，抹掉耐久组件。
     *
     * <p>抹耐久是因为同种球无论用了多少次都算同一种 —— 这样它才能当种类键用。</p>
     *
     * <p>⚠️ <b>顺带做一次组合球的旧数据迁移</b>：轮盘与 HUD 显示的图标就是这里复制出来的样本，
     * 而副本不会走 {@code ComboBallItem.inventoryTick} —— 于是存档里的老组合球
     * 在背包里显示正常、在<b>轮盘里却是透明的</b>（作者反馈过）。
     * 加这一行之后，轮盘画图标前就会把缺失的分块组件补上。</p>
     */
    public static ItemStack kindOf(ItemStack ball) {
        // 迁移是幂等的、且自带「已齐就返回」的快路径，放在这里没有额外负担
        BallFragments.ensureComboSlots(ball);
        ItemStack key = ball.copyWithCount(1);
        key.remove(ModComponents.TOUGHNESS_LEFT.get());
        return key;
    }

    /**
     * 当前选中的弹药样本；没选时返回 {@link ItemStack#EMPTY}。
     *
     * <p>附件里存的是 {@code List<ItemStack>}（空列表 = 没选），
     * 因为 {@code ItemStack} 的 codec 拒绝空栈 —— 见 {@link ModAttachments#SELECTED_AMMO}。</p>
     */
    public static ItemStack selected(Player player) {
        List<ItemStack> value = player.getData(ModAttachments.SELECTED_AMMO);
        return value == null || value.isEmpty() ? ItemStack.EMPTY : value.get(0);
    }

    /** 设置选中的弹药（存整颗样本球的快照，带组件） */
    public static void select(Player player, ItemStack ball) {
        if (ball.isEmpty()) {
            clear(player);
            return;
        }
        player.setData(ModAttachments.SELECTED_AMMO, List.of(kindOf(ball)));
    }

    /** 清除选中 */
    public static void clear(Player player) {
        player.setData(ModAttachments.SELECTED_AMMO, List.of());
    }

    /**
     * 玩家身上所有收纳袋里<b>现存弹药的种类与数量</b> —— 径向菜单就按这个列表来画。
     *
     * <p>返回顺序按「数量从多到少」，相同数量时保持首次出现的顺序，保证菜单稳定。</p>
     */
    public static List<AmmoKind> inventory(Player player) {
        List<AmmoKind> kinds = new ArrayList<>();
        for (ItemStack pouch : BallPouchHelper.allPouchStacks(player)) {
            BallPouchContents contents = BallPouchItem.contents(pouch);
            collect(contents.ammo(), kinds);
            collect(contents.transit(), kinds);
        }
        kinds.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return kinds;
    }

    private static void collect(List<ItemStack> segment, List<AmmoKind> kinds) {
        for (ItemStack ball : segment) {
            if (ball.isEmpty()) {
                continue;
            }
            ItemStack key = kindOf(ball);
            int index = indexOfKind(kinds, key);
            if (index >= 0) {
                AmmoKind old = kinds.get(index);
                kinds.set(index, new AmmoKind(old.sample(), old.count() + ball.getCount()));
            } else {
                kinds.add(new AmmoKind(key, ball.getCount()));
            }
        }
    }

    private static int indexOfKind(List<AmmoKind> kinds, ItemStack key) {
        for (int i = 0; i < kinds.size(); i++) {
            if (ItemStack.isSameItemSameComponents(kinds.get(i).sample(), key)) {
                return i;
            }
        }
        return -1;
    }

    /** 现存的弹药种类（直接用样本球，带组件，不再还原成裸物品） */
    public static List<ItemStack> sortedKinds(Player player) {
        List<ItemStack> result = new ArrayList<>();
        for (AmmoKind kind : inventory(player)) {
            result.add(kind.sample());
        }
        return result;
    }

    /** 某种弹药在身上的总数（按种类比，忽略耐久） */
    public static int countOf(Player player, ItemStack kind) {
        ItemStack key = kindOf(kind);
        for (AmmoKind entry : inventory(player)) {
            if (ItemStack.isSameItemSameComponents(entry.sample(), key)) {
                return entry.count();
            }
        }
        return 0;
    }
}
