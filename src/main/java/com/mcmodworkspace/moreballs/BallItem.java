package com.mcmodworkspace.moreballs;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * 球类物品 —— 所有 {@code #more_balls:balls} 里的自定义球都用这个类。
 *
 * <h2>「不可回收」是怎么来的</h2>
 * 弩在装填/发射时会往<b>被消耗的弹药</b>上打一个 {@code minecraft:intangible_projectile} 标记，
 * 含义是「这东西是射出去的、别当普通物品回收」，会把物品显示成
 * {@code item.intangible}（中文「不可回收」）。
 *
 * <p>而两个物品要叠成一堆，必须 <b>id 与全部数据组件完全一致</b> ——
 * 多出这一个组件，捡回来的球就再也叠不进普通球堆了。</p>
 *
 * <p>原版箭躲过这一劫是因为 {@code AbstractArrow} 在自己的构造里主动把它移除了。
 * 我们的球<b>可以被捡回来</b>，所以同样必须洗掉，见 {@link #stripIntangible(ItemStack)}。</p>
 *
 * <h2>全局耐久（自定义组件 + 原版耐久条）</h2>
 * 球的耐久是「还能承受几次碰撞」，记在 {@link ModComponents#TOUGHNESS_LEFT} 上，
 * <b>不用</b>原版的 {@code MAX_DAMAGE} / {@code DAMAGE} —— 因为 MC 禁止
 * 「有耐久 + 可堆叠」共存（详见 {@link ModComponents} 的说明）。
 *
 * <p>但<b>耐久条的渲染完全复用原版那套</b>：原版画条前会问物品三个问题
 * （{@link #isBarVisible} / {@link #getBarWidth} / {@link #getBarColor}），
 * 只要覆写它们、把答案换成我们自定义组件里的数值即可 —— 外观和原版一模一样，
 * 不需要自己写任何渲染代码。</p>
 */
public class BallItem extends Item {

    /** 耐久条配色，与原版一致 */
    private static final int COLOR_HIGH = 0xFF00E000;
    private static final int COLOR_MID = 0xFFFFD000;
    private static final int COLOR_LOW = 0xFFFF3000;

    /** 原版耐久条的满宽 */
    private static final int BAR_FULL_WIDTH = 13;

    public BallItem(Properties properties) {
        super(properties);
    }

    /**
     * 洗掉弩留下的 {@code intangible_projectile}（「不可回收」）标记。
     *
     * @return 是否确实清掉了东西
     */
    public static boolean stripIntangible(ItemStack stack) {
        if (stack.has(DataComponents.INTANGIBLE_PROJECTILE)) {
            stack.remove(DataComponents.INTANGIBLE_PROJECTILE);
            return true;
        }
        return false;
    }

    /**
     * 从物品读出「还能承受几次碰撞」。
     *
     * <p>有自定义组件就以组件为准（这就是「全局耐久」：扔出去弹掉几点，
     * 捡回来继续用同一份耐久）；<b>没有组件即为满耐久</b>，
     * 回落到 {@link BallBehavior} 里配置的值。</p>
     */
    public static int remainingToughness(ItemStack stack, int fallback) {
        Integer left = stack.get(ModComponents.TOUGHNESS_LEFT.get());
        return left != null ? Math.max(0, left) : fallback;
    }

    /**
     * 把实体的剩余耐久写回物品（捡回时用），直接修改并返回同一份物品堆。
     *
     * <p>写满耐久时会把组件<b>移除</b>，让「刚合成的球」与「捡回来的满耐久球」
     * 保持完全一致的组件、可以叠成一堆。</p>
     */
    public static ItemStack applyToughness(ItemStack stack, int remaining) {
        int value = Math.max(0, remaining);
        int max = maxToughness(stack);
        if (max > 0 && value >= max) {
            stack.remove(ModComponents.TOUGHNESS_LEFT.get());
        } else {
            stack.set(ModComponents.TOUGHNESS_LEFT.get(), value);
        }
        return stack;
    }

    /** 坚固值即耐久上限；永久坚固（-1）与不坚固（0）都返回 0，表示没有耐久条 */
    private static int maxToughness(ItemStack stack) {
        return Math.max(0, BallBehavior.profileFor(stack).toughness());
    }

    // ===== 名称与物品介绍 =====

    /**
     * 雪球命名格式开关（保留旧格式以便随时切回）。
     *
     * <p>{@code true}（默认）= <b>新格式「雪球_材料」</b>：生存与创造都显示完整名字。<br>
     * {@code false} = <b>旧格式「雪球？（材料）」</b>：创造模式显示全名，生存只显示「雪球？」。</p>
     *
     * <p>两套翻译键都留在语言文件里（新格式用基础键，旧格式用 {@code .old} 与
     * {@code .old.short}），翻这个开关就能来回切。</p>
     */
    public static boolean snowballNewNaming = true;

    /**
     * 物品名称。
     *
     * <p>新格式直接走基础翻译键（<b>「雪球_碎石」</b>），所以生存与创造完全一致；
     * 旧格式则沿用「创造看全名、生存看短线」的老规矩。</p>
     *
     * <p>只在客户端能查创造模式状态 —— 服务端没有 {@code Minecraft} 类，
     * 用 try/catch 兜住，取不到就当生存模式。</p>
     */
    @Override
    public Component getName(ItemStack stack) {
        String base = this.getDescriptionId();

        if (!snowballNewNaming) {
            String key = base + (isClientCreative() ? ".old" : ".old.short");
            Component legacy = Component.translatable(key);
            // 没配旧命名的球（木球之类）会回落到普通名字，不会显示成裸翻译键
            if (!legacy.getString().equals(key)) {
                return legacy;
            }
        }
        return super.getName(stack);
    }

    /** 客户端是否为创造模式；服务端或取不到玩家时一律返回 false */
    private static boolean isClientCreative() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.player != null && minecraft.player.isCreative();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 物品介绍 —— 全部走「【词条】说明」的格式。
     *
     * <h2>显示规则（作者指定）</h2>
     * <ul>
     *   <li><b>常显</b>：【基础伤害】、【坚固x】、【感应】、【探寻】这些——伤害是玩家关心的
     *       核心数值，坚固 / 感应 / 探寻是特殊词条，一律不藏</li>
     *   <li><b>按住 Shift 才显示</b>：【重量】、【弹性】这类机制细节</li>
     *   <li>没有的性质直接不显示（例如不坚固的球不会出现【坚固】行）</li>
     * </ul>
     *
     * <p>词条全部由 {@link BallBehavior.BallProfile} 驱动 —— 以后给某个球加上
     * {@code .withSense(n)} 或 {@code .withToughness(n)}，介绍会自动跟着长出来，
     * 不需要在每个球上单独写文案。</p>
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        boolean shift = isShiftDown();

        // 词条本身在 BallTooltip 里生成 —— 径向菜单读的是同一份，两处不会说法不一致
        for (BallTooltip.Entry entry : BallTooltip.entries(stack, shift)) {
            tooltip.accept(entry.text());
            // 按住 Shift 时，词条后面追加它的说明（缩进一格，视觉上从属于上一条）
            if (shift && entry.detail() != null) {
                tooltip.accept(Component.literal("  ").append(entry.detail()));
            }
        }

        // 末尾提示一句怎么展开被折叠的数值（用更暗的灰跟词条区分开）。
        // 只要没按 Shift 就提示 —— 重量与初速度是每个球都有的，一定有东西被折叠着。
        if (!shift) {
            tooltip.accept(Component.translatable("tooltip.more_balls.hint.shift")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * 是否按住 Shift。
     *
     * <p>26.x 里 {@code Screen} 已经不带 {@code hasShiftDown()} 了，改问 {@link Minecraft}
     * 本身。只在客户端有 {@code Minecraft} 类，所以用 try/catch 兜住 —— 取不到就当没按。</p>
     */
    private static boolean isShiftDown() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft != null && minecraft.hasShiftDown();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ===== 以下是原版耐久条框架的三个钩子 =====
    // 原版渲染物品耐久条时不看 MAX_DAMAGE 组件，而是直接问物品：
    // 「要不要画？」「画多宽？」「什么颜色？」—— 覆盖它们就能白拿原版的画法与外观。

    @Override
    public boolean isBarVisible(ItemStack stack) {
        int max = maxToughness(stack);
        return max > 0 && remainingToughness(stack, max) < max;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int max = maxToughness(stack);
        if (max <= 0) {
            return 0;
        }
        int left = Math.min(remainingToughness(stack, max), max);
        return Math.round(BAR_FULL_WIDTH * left / (float) max);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        int max = maxToughness(stack);
        if (max <= 0) {
            return COLOR_HIGH;
        }
        float ratio = Math.min(remainingToughness(stack, max), max) / (float) max;
        if (ratio > 0.5F) {
            return COLOR_HIGH;
        }
        return ratio > 0.25F ? COLOR_MID : COLOR_LOW;
    }
}
