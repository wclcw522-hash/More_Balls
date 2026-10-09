package com.mcmodworkspace.moreballs;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * 半球 / 四分之一球 —— <b>半成品</b>，不是球。
 *
 * <h2>作者指定的规则（2026-10-07）</h2>
 * <ul>
 *   <li>用切石机从完整球上切下来：一颗球出 <b>2 个半球</b>或 <b>4 个四分之一球</b></li>
 *   <li><b>不能投掷、不能当弩弹药</b> —— 这就是它<b>不是</b> {@link BallItem}、
 *       且不进 {@code #more_balls:balls} 标签的原因</li>
 *   <li><b>数值词条</b>（坚固、感应、融化、熔融、磁吸、点金）按 1/2、1/4 缩放，
 *       <b>暂时不取整</b> —— 所以显示出来是 {@code 4}、{@code 2.5} 这种</li>
 *   <li><b>无数值特性</b>（变形、磁性、金光闪闪）由半球<b>直接继承</b>；
 *       四分之一球把它们<b>藏起来</b>（只不显示，数据仍在 —— 四合一还要数份数）</li>
 * </ul>
 *
 * <h2>为什么是「一件物品 + 组件」</h2>
 * <p>参考匠魂：那里所有镐子是同一件物品、材质由组件决定。这里照搬 ——
 * 半球/四分之一球各只有<b>一个</b>物品 id，靠
 * {@link ModComponents#FRAGMENT_SOURCE} 记「从哪颗球切的」。
 * 于是 5 种来源只占 1 个 id，而不是注册 5 个。</p>
 */
public class BallFragmentItem extends Item {

    /** 切成了几份：半球 = 2、四分之一球 = 4 */
    private final int parts;

    public BallFragmentItem(Properties properties, int parts) {
        super(properties);
        this.parts = parts;
    }

    /** 切成了几份 */
    public int parts() {
        return this.parts;
    }

    /** 它保留了多少比例的词条（半球 0.5、四分之一球 0.25） */
    public double fraction() {
        return 1.0D / this.parts;
    }

    /** 「来源球」在 {@link BallFragments#sources()} 里的下标；{@code -1} = 还没填模板 */
    public static int sourceIndex(ItemStack stack) {
        return stack.getOrDefault(ModComponents.FRAGMENT_SOURCE.get(), -1);
    }

    /** 它是从哪颗球上切下来的；没填组件时返回 {@code null} */
    public static Item sourceBall(ItemStack stack) {
        int idx = sourceIndex(stack);
        List<Item> sources = BallFragments.sources();
        return idx >= 0 && idx < sources.size() ? sources.get(idx) : null;
    }

    /** 词条快照 —— 数值已按分数缩放，<b>不取整</b> */
    public BallFragments.Fragment fragment(ItemStack stack) {
        Item source = sourceBall(stack);
        return source == null
                ? BallFragments.Fragment.EMPTY
                : BallFragments.scaled(source, this.fraction());
    }

    /**
     * 显示名 —— 「铁」+「半球」=「铁半球」。
     *
     * <p>球名（铁 / 金 / 空心铁）也走 lang，拼装模板同样走 lang，
     * 这样英文环境能正常显示，而不是硬编码中文。</p>
     */
    @Override
    public Component getName(ItemStack stack) {
        int idx = sourceIndex(stack);
        if (idx < 0) {
            return super.getName(stack);
        }
        // idx 来自物品组件，可能被 /give 或数据包写坏 —— 夹到合法区间，
        // 否则渲染线程会在 tooltip 里抛 IndexOutOfBounds。
        int safe = Math.max(0, Math.min(idx, BallFragments.SHORT_ID.size() - 1));
        return Component.translatable(
                this.parts == 2
                        ? "item.more_balls.ball_half.format"
                        : "item.more_balls.ball_quarter.format",
                Component.translatable("ball.more_balls.short." + BallFragments.SHORT_ID.get(safe)));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        if (sourceBall(stack) == null) {
            return;
        }
        BallFragments.Fragment f = this.fragment(stack);

        // 明确标出「半成品」：免得玩家拿它去右键、发现扔不出去
        tooltip.accept(Component.translatable("tooltip.more_balls.fragment")
                .withStyle(ChatFormatting.GRAY));

        entry(tooltip, "tooltip.more_balls.entry.tough", f.toughness());
        entry(tooltip, "tooltip.more_balls.entry.sense", f.sense());
        entry(tooltip, "tooltip.more_balls.entry.melt", f.melt());
        entry(tooltip, "tooltip.more_balls.entry.molten", f.molten());
        entry(tooltip, "tooltip.more_balls.entry.magnet", f.magnet());
        entry(tooltip, "tooltip.more_balls.entry.transmute", f.transmute());

        // 无数值特质：半球直接继承（作者指定）；
        // 四分之一球「把特质藏起来」—— 只是不显示，数据上仍然带着
        if (this.parts == 2) {
            if (f.morph()) {
                tooltip.accept(Component.translatable("tooltip.more_balls.entry.morph")
                        .withStyle(ChatFormatting.GRAY));
            }
            if (f.magnetic()) {
                tooltip.accept(Component.translatable("tooltip.more_balls.entry.magnetic")
                        .withStyle(ChatFormatting.GRAY));
            }
            if (f.glint()) {
                // 复用金球那条既有词条键（金光闪闪），不另造一个
                tooltip.accept(Component.translatable("tooltip.more_balls.entry.gold_shiny")
                        .withStyle(ChatFormatting.GRAY));
            }
        }
    }

    /** 数值词条：0（或以下）表示没有这条，有才显示 */
    private static void entry(Consumer<Component> tooltip, String key, double value) {
        if (value <= 0.0D) {
            return;
        }
        tooltip.accept(Component.translatable(key, trim(value)).withStyle(ChatFormatting.GRAY));
    }

    /**
     * 半成品的数值是小数量（4 / 2.5 / 1.25），要好看但不许取整。
     *
     * <p>整数就不带小数点（{@code 4.0 → "4"}），否则最多保留两位（{@code 1.25}、{@code 0.13}）。</p>
     */
    private static String trim(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
