package com.mcmodworkspace.moreballs;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 【变形】用的「球 → 方块」映射（作者 2026-10-07 重新定的规格）。
 *
 * <h2>规格</h2>
 * <p>耐久耗尽时，<b>随机变成组成该球的原材料之一</b>：</p>
 * <ul>
 *   <li>普通球 —— 只有一种材质，就变它自己那个方块（金球 → 金块）</li>
 *   <li>组合球 —— 有几个组成部分就有几个候选：
 *       「木-金-圆石-铁球」会随机变成<b>木板 / 金块 / 圆石 / 铁块</b>之一</li>
 * </ul>
 * <p>该位置放不下时（被方块占了、或超出世界高度），退而求其次
 * <b>随机掉对应的物品形式</b>，而不是固定掉某一种。</p>
 *
 * <h2>为什么不用 BallProfile.morphBlock</h2>
 * <p>{@code BallProfile} 里那个字段只能存<b>一个</b>方块，表达不了「多个候选随机」。
 * 组合球的候选是从组件的来源列表推出来的，所以这里单独算。
 * {@code BallProfile.morphBlock} 现在只承担一个职责：<b>表示「这颗球有变形特质」</b>
 * （组合球会填上第一个候选当占位，真正变形时不用它）。</p>
 */
public final class BallMorph {

    private BallMorph() {
    }

    /**
     * 单颗球对应的方块。
     *
     * <p>空心铁球也是铁，所以同样变铁块 —— 它是铁锭质感，变铁块最自然。</p>
     *
     * <p>⚠️ <b>适用范围：只覆盖这 5 种球</b> —— 木球、圆石球、铁球、空心铁球、金球。
     * {@link BallFragments#sources()} 里后来追加的球（紫水晶 / 铜 / 空心铜 / 红石 /
     * 钻石 / 青金石……）<b>一律返回 {@code null}</b>，本方法不认识它们。</p>
     *
     * <p>后果：{@link #candidatesOf} 遇到 {@code null} 会<b>静默跳过</b>那一条候选，
     * 所以含这些球的普通球与组合球，能变出的方块种类会少于它的组成部分个数
     * （极端情况下候选为空，{@link #canMorph} 就是 {@code false}、压根不变形）。
     * 这是当前的真实行为，不是笔误。</p>
     */
    public static Block blockOf(Item ball) {
        if (ball == ModItems.WOODEN_BALL.get()) {
            return Blocks.OAK_PLANKS;
        }
        if (ball == ModItems.COBBLESTONE_BALL.get()) {
            return Blocks.COBBLESTONE;
        }
        if (ball == ModItems.IRON_BALL.get() || ball == ModItems.HOLLOW_IRON_BALL.get()) {
            return Blocks.IRON_BLOCK;
        }
        if (ball == ModItems.GOLD_BALL.get()) {
            return Blocks.GOLD_BLOCK;
        }
        return null;
    }

    /**
     * 这颗球变形时的<b>全部候选方块</b>。
     *
     * <p>组合球按组件的来源列表展开；普通球只有一个。无法识别的球返回空列表。</p>
     *
     * <p>⚠️ 来源列表里的每一颗球都要过 {@link #blockOf}，而那个方法只认 5 种球 ——
     * 认不出的返回 {@code null}，这里直接把 Null 的那一项<b>丢掉</b>（不是替换成别的方块）。
     * 于是「像球有多少个组件就有多少候选」这句话，只对<b>全部组件都在那 5 种之内</b>的组合成立；
     * 含紫水晶 / 铜 / 红石 / 钻石 / 青金石球的组合，候选会比组件数少。</p>
     */
    public static List<Block> candidatesOf(ItemStack ball) {
        if (ball.getItem() == ModItems.COMBO_BALL.get()) {
            List<Integer> indexes = BallFragments.parseIndexes(
                    ball.getOrDefault(ModComponents.COMBO_SOURCES.get(), ""));
            List<Block> out = new ArrayList<>(indexes.size());
            for (int index : indexes) {
                Block block = blockOf(BallFragments.sources().get(index));
                if (block != null) {
                    out.add(block);
                }
            }
            return out;
        }
        Block single = blockOf(ball.getItem());
        return single == null ? List.of() : List.of(single);
    }

    /** 从候选里随机挑一个；没有候选时返回 {@code null} */
    public static Block randomBlock(ItemStack ball, RandomSource random) {
        List<Block> candidates = candidatesOf(ball);
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    /** 这颗球有没有变形特质 */
    public static boolean canMorph(ItemStack ball) {
        return !candidatesOf(ball).isEmpty();
    }
}
