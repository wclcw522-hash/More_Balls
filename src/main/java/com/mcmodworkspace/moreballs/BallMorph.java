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
     * <p>空心铁球也是铁，所以同样变铁块 —— 它是铁锭质感，变铁块最自然；
     * 空心铜球按同一思路，跟铜球一样变铜块。</p>
     *
     * <p><b>适用范围：{@link BallFragments#sources()} 里的全部 11 颗球</b> ——
     * 木球、圆石球、铁球、金球、空心铁球、紫水晶球、铜球、空心铜球、红石球、钻石球、青金石球；
     * 每颗都映射到它自身材质的原版方块。只有 {@code sources()} 之外的球（雪球系）返回 {@code null}。</p>
     *
     * <p>{@link #candidatesOf} 遇到 {@code null} 会<b>静默跳过</b>那一条候选 ——
     * 那是「来源球不在这张表里」时的兜底路径；补全之后 sources() 的球不再产生 null，
     * 组合球的候选数就等于它的组成部分个数。</p>
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
        if (ball == ModItems.AMETHYST_BALL.get()) {
            return Blocks.AMETHYST_BLOCK;
        }
        // ⚠️ 铜球暂不参与【变形】：26.2 的 {@code Blocks.COPPER_BLOCK} 是
        //    WeatheringCopperCollection<Block>（按氧化程度打包的一组方块），
        //    不是单个 Block，没法直接当候选方块用。要支持它得先确定
        //    「用哪一档氧化程度」，属于设计决策 —— 留待作者指定。
        if (ball == ModItems.REDSTONE_BALL.get()) {
            return Blocks.REDSTONE_BLOCK;
        }
        if (ball == ModItems.DIAMOND_BALL.get()) {
            return Blocks.DIAMOND_BLOCK;
        }
        if (ball == ModItems.LAPIS_BALL.get()) {
            return Blocks.LAPIS_BLOCK;
        }
        return null;
    }

    /**
     * 这颗球变形时的<b>全部候选方块</b>。
     *
     * <p>组合球按组件的来源列表展开；普通球只有一个。无法识别的球返回空列表。</p>
     *
     * <p>来源列表里的每一颗球都要过 {@link #blockOf}；认不出的返回 {@code null}，
     * 这里直接把 Null 的那一项<b>丢掉</b>（不是替换成别的方块）。
     * {@code blockOf} 现已覆盖 {@link BallFragments#sources()} 里 11 颗中的 9 颗
     * （铜球与空心铜球除外，见 {@link #blockOf} 里的说明），所以那句话对当前来源球基本成立；
     * 「丢掉 null」这条兜底只剩下 {@code sources()} 之外的球（雪球系）会走到。</p>
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
