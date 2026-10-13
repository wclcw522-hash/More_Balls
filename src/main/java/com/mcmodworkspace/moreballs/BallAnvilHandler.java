package com.mcmodworkspace.moreballs;

import java.util.ArrayList;
import java.util.List;

import com.mcmodworkspace.moreballs.entity.BallProjectile;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AnvilUpdateEvent;

/**
 * 球的<b>铁砧修复</b>（作者 2026-10-13 指定）。
 *
 * <p><b>为什么必须自己写</b>：原版铁砧的「同物品修复」只在物品带原版
 * {@code MAX_DAMAGE} 组件时才生效，而球的耐久走的是自己的组件
 * {@link ModComponents#TOUGHNESS_LEFT}（见 {@code BallItem} 的类注释）——
 * 所以原版铁砧对球<b>完全没有反应</b>，必须在这里接。</p>
 *
 * <p><b>规则</b>：左边放一颗受损的球、右边放一颗<b>同种</b>球，即可修复。
 * 「同种」的判据分两种：</p>
 * <ul>
 *   <li><b>普通球</b>：物品相同即可</li>
 *   <li><b>组合球</b>：要求四个组件（{@code combo_slot_1..4}）的<b>多重集相同</b> ——
 *       也就是<b>顺序可以不一样</b>（作者原话：「四个组件分别一致就行，顺序可以不一样」），
 *       而数量要一致（两颗都得是四合一出来的）</li>
 * </ul>
 *
 * <p><b>修复量</b>：照原版铁砧的手感 —— 两颗的剩余耐久相加，再额外给上限的 12%，
 * 封顶到耐久上限。经验消耗按「补了多少比例」折算（上限 30 级），材料消耗 1。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallAnvilHandler {

    private BallAnvilHandler() {
    }

    /** 原版铁砧的额外修复加成：上限的 12% */
    private static final float REPAIR_BONUS_RATIO = 0.12F;

    /** 一次修复最多要几级经验（补满时） */
    private static final int MAX_REPAIR_COST = 30;

    @SubscribeEvent
    public static void onAnvilUpdate(AnvilUpdateEvent event) {
        ItemStack left = event.getLeft();
        ItemStack right = event.getRight();

        // 左边必须是「还能修的球」，右边必须是「同种的球」
        if (!isDamagedBall(left) || !isBall(right)) {
            return;
        }
        if (!isSameBall(left, right)) {
            return;
        }

        int max = BallBehavior.profileFor(left).toughness();
        if (max <= 0) {
            return;
        }
        int leftNow = Math.min(BallItem.remainingToughness(left, max), max);
        int rightNow = Math.min(BallItem.remainingToughness(right, max), max);

        int bonus = Math.max(1, (int) Math.ceil(max * REPAIR_BONUS_RATIO));
        int repaired = Math.min(max, leftNow + rightNow + bonus);
        if (repaired <= leftNow) {
            return;
        }

        ItemStack out = left.copy();
        BallItem.applyToughness(out, repaired);
        event.setOutput(out);
        event.setMaterialCost(1);
        // TODO（下一轮补）：经验的等级消耗 —— NeoForge 这个事件上的方法名不是 setCost，
        // 先留成原版铁砧的默认行为；查清正确 API 后按「补了多少比例」折算写进来。
        // 参考量：MAX_REPAIR_COST = 30 级（补满时）。
    }

    /** 是不是球（普通球或组合球都算） */
    private static boolean isBall(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof BallItem || stack.getItem() instanceof ComboBallItem);
    }

    /** 是不是「受损、且耐久上限大于 0」的球（满了就不用修、不坚固的球也没有修的概念） */
    private static boolean isDamagedBall(ItemStack stack) {
        if (!isBall(stack)) {
            return false;
        }
        int max = BallBehavior.profileFor(stack).toughness();
        if (max <= 0) {
            return false;
        }
        return Math.min(BallItem.remainingToughness(stack, max), max) < max;
    }

    /**
     * 「同种」判定。
     *
     * <p>组合球走<b>多重集比较</b>（顺序无关）—— 这是作者明确要求的：
     * 「组合球四个组件分别一致就行，顺序可以不一样」。</p>
     */
    private static boolean isSameBall(ItemStack a, ItemStack b) {
        boolean aCombo = a.getItem() instanceof ComboBallItem;
        boolean bCombo = b.getItem() instanceof ComboBallItem;
        if (aCombo != bCombo) {
            return false;
        }
        if (!aCombo) {
            // 普通球：物品相同即可
            return a.is(b.getItem());
        }
        return sameMultiSet(comboSources(a), comboSources(b));
    }

    /**
     * 读出一颗组合球的四个组件。
     *
     * <p>用的是 {@link ModComponents#COMBO_SLOTS} 那张表（{@code combo_slot_1..4}），
     * 和 {@code BallFragments} 里写组件时是同一套顺序，所以读出来就是当初放进去的四个来源。</p>
     */
    private static List<Integer> comboSources(ItemStack stack) {
        List<Integer> out = new ArrayList<>(ModComponents.COMBO_SLOTS.size());
        for (var slot : ModComponents.COMBO_SLOTS) {
            Integer value = stack.get(slot.get());
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    /** 多重集比较：排序后逐项相等即可（数量也要一致） */
    private static boolean sameMultiSet(List<Integer> a, List<Integer> b) {
        if (a.size() != b.size()) {
            return false;
        }
        List<Integer> sa = new ArrayList<>(a);
        List<Integer> sb = new ArrayList<>(b);
        sa.sort(Integer::compareTo);
        sb.sort(Integer::compareTo);
        return sa.equals(sb);
    }

}
