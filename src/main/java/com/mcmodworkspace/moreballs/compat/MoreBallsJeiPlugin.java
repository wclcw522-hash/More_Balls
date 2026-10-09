package com.mcmodworkspace.moreballs.compat;

import com.mcmodworkspace.moreballs.BallComboRecipe;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.gui.ingredient.ICraftingGridHelper;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import com.mcmodworkspace.moreballs.BallFragments;
import java.util.ArrayList;
import net.minecraft.world.item.ItemStack;
import mezz.jei.api.registration.IExtraIngredientRegistration;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.ItemStackTemplate;
import com.mcmodworkspace.moreballs.ModComponents;
import mezz.jei.api.registration.ISubtypeRegistration;
import com.mcmodworkspace.moreballs.ModTags;
import com.mcmodworkspace.moreballs.ModItems;
import com.mcmodworkspace.moreballs.MoreBalls;
import java.util.Collections;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.SlotDisplay;

/**
 * 「更多球」的 JEI 插件。
 *
 * <h2>为什么必须有这个类</h2>
 * <p>组合球的合成是自定义配方（{@link BallComboRecipe}），而 JEI 的工作台分类是这样筛选配方的：
 * </p>
 * <pre>{@code
 * // mezz.jei.library.plugins.vanilla.crafting.CraftingRecipeCategory
 * public boolean isHandled(RecipeHolder<CraftingRecipe> recipe) {
 *     return this.extendableHelper.getOptionalRecipeExtension(recipe).isPresent();
 * }
 * }</pre>
 * <p>也就是说 —— <b>只有「注册过配方扩展」的配方类才会被 JEI 显示</b>。原版 {@code ShapedRecipe}
 * 之类自带扩展，而自定义配方不自带，于是 JEI 会把它整个丢掉，表现就是「点上去点不动、
 * 跟没配方一样」。所以这里按 JEI 官方的做法，给两个自定义配方类补上扩展。</p>
 *
 * <h2>为什么输入格用「示例」物品</h2>
 * <p>配方本身不挑具体来源（任何带 {@code fragment_source} 组件的半球/四分之一球都行），
 * 所以展示时用一组<b>专用的示例物品</b>来表示「任意半球 / 任意四分之一球」，
 * 它们只用于配方展示（见 {@code #more_balls:example_only} 标签，不在创造栏、也无获取途径）。</p>
 *
 * <h2>优雅降级</h2>
 * <p>本类只依赖 JEI 的 API（{@code compileOnly}），并且靠 {@code @JeiPlugin} 注解被 JEI 发现 ——
 * <b>没装 JEI 时这个类根本不会被加载</b>，模组照常单独运行。</p>
 */
@JeiPlugin
public class MoreBallsJeiPlugin implements IModPlugin {

    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "jei_plugin");
    }

    /**
     * JEI 里「一颗半球」的展示槽 —— 与<b>切球（石切机）配方</b>同样的观感。
     *
     * <p>用 {@code TagSlotDisplay(#more_balls:fragments/half)} 表示「任意半球」，
     * 而不是列出某几种具体材质。{@code TagSlotDisplay} 会把标签内<b>全部</b>物品
     * 解析进同一个槽，JEI 的 {@code CycleTicker} 便会轮流显示它们 ——
     * 这正是「轮替显示」的机制（作者 2026-10-09 明确要这个效果）。</p>
     *
     * <p>⚠️ 一个历史坑：{@code TagSlotDisplay} 解析出来的是<b>裸的 ball_half</b>
     * （没有 {@code fragment_source} 组件），而 {@code BallComboRecipe.isFragment}
     * 要求组件存在。所以「照 JEI 摆出来合不出东西」是另一回事 ——
     * 那个已由 {@code isFragment} 接受裸半球解决，不要靠换掉这里的 SlotDisplay 去修。</p>
     */

    private static SlotDisplay halfStack() {
        return demoComposite(ModItems.BALL_HALF.get());
    }

    /** 同 {@link #halfStack()}，四分之一球版本 */
    /**
     * 造一个「8 种材质都能轮替显示」的槽。
     *
     * <h2>为什么不用 TagSlotDisplay</h2>
     * <p>{@code #more_balls:fragments/half} 标签里**只有一条** {@code more_balls:ball_half} ——
     * 材质是靠 {@code fragment_source} 组件区分的，8 种材质共用同一个物品 id。
     * 所以 {@code TagSlotDisplay} 只解析出**一个**裸模板，槽里就一个候选，
     * JEI 的 {@code CycleTicker} 物理上没法轮替（作者反复反馈「没有轮换显示」的原因）。</p>
     *
     * <h2>为什么 Composite 可以</h2>
     * <p>JEI 对 {@code SlotDisplay.Composite} 注册了 <b>universal</b> 解释器，
     * 它会把每个 child 解析出的结果**全部**塞进同一个槽 —— 8 个 child 就是 8 个候选，
     * 于是每秒轮换一次。而且全程不碰 {@code registerItemSubtypes}，
     * 不会让 JEI 物品列表里冒出一排「木半球 / 铁半球 / 金半球」。</p>
     */
    private static SlotDisplay demoComposite(net.minecraft.world.item.Item fragment) {
        List<SlotDisplay> parts = new ArrayList<>();
        int kinds = BallFragments.sources().size();
        for (int source = 0; source < kinds; source++) {
            parts.add(new SlotDisplay.ItemStackSlotDisplay(new ItemStackTemplate(
                    fragment, 1,
                    DataComponentPatch.builder()
                            .set(ModComponents.FRAGMENT_SOURCE.get(), source)
                            .build())));
        }
        return new SlotDisplay.Composite(List.copyOf(parts));
    }
    private static SlotDisplay quarterStack() {
        return demoComposite(ModItems.BALL_QUARTER.get());
    }



    /**
     * 让「带组件的半球 / 四分之一球 / 组合球」在 JEI 里成为**独立条目**。
     *
     * <p>JEI 默认按物品 id 区分子类型，所以带 {@code fragment_source} 的半球会被
     * 合并成同一个裸模板（日志里 JEI 自己就会提示 "14 duplicate items … need a subtype
     * interpreter"）。注册之后「铁半球」「金四分之一球」「铁-金组合球」各自可查，
     * 才符合作者「在 JEI 里放带组件的示例实例用来查词条」的设计。</p>
     */
    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        // ⚠️ 这里**不给半球 / 四分之一球注册子类型**。
        //
        // 注册之后 JEI 会把「带 fragment_source 的每一种」都当成独立条目，
        // 于是物品列表里冒出一整排「木半球 / 铁半球 / 金半球 …」
        // （作者 2026-10-09 反馈「多出来了一堆各材质半球四分之一球」）。
        // 配方槽里已经用带组件的实例把来源讲清楚了，不需要再来一遍。
        // ⚠️ 这里**不给 combo_ball 注册子类型**。
        //
        // 注册之后 JEI 会把「带组件的那一份」当成独立条目，而配方的
        // SlotDisplay 用的是裸模板 —— 于是「从成品反查配方」查到的 uid
        // 和注册进去的对不上，两个组合球配方在「用途」页里全部消失。
        // 组合球只有八种来源，合并显示反而是想要的效果。
    }

    /**
     * 把四个**通用示例物品**塞进 JEI 的物品列表。
     *
     * <p>它们被 {@code #more_balls:example_only} 挡在创造模式物品组之外（本来是内部用的），
     * 而 JEI 的槽位解析<b>只认物品列表里存在的东西</b> —— 不注册的话，
     * 用到它们的配方会整条解析成空、点都点不开（2026-10-08 踩过一次）。
     * 现在它们正式承担「配方示范」的职责，所以必须露面。</p>
     */
    @Override
    public void registerExtraIngredients(IExtraIngredientRegistration registration) {
        registration.addExtraItemStacks(List.of(
                new ItemStack(ModItems.EXAMPLE_HALF.get()),
                new ItemStack(ModItems.EXAMPLE_QUARTER.get()),
                new ItemStack(ModItems.EXAMPLE_COMBO_VERTICAL.get()),
                new ItemStack(ModItems.EXAMPLE_COMBO_SQUARE.get())));
    }
    @Override
    public void registerVanillaCategoryExtensions(IVanillaCategoryExtensionRegistration registration) {
        MoreBalls.LOGGER.info("[ball][JEI] 注册组合球配方扩展：Vertical(1x2) + Square(2x2)");
        // 二合一：1 宽 2 高，两格都是示例半球
        registration.getCraftingCategory().addExtension(
                BallComboRecipe.Vertical.class,
                comboExtension(1, 2, halfStack()));
        // 四合一：2×2 四格，都是示例四分之一球
        registration.getCraftingCategory().addExtension(
                BallComboRecipe.Square.class,
                comboExtension(2, 2, quarterStack()));
    }

    /**
     * 造一个「所有格子都用同一种材料」的配方扩展。
     *
     * <p>{@code getIngredients} 返回的列表长度必须是 {@code width * height}，
     * 顺序按行左到右、上到下 —— JEI 会拿它铺到合成网格里。</p>
     */
    private static <R extends CraftingRecipe> ICraftingCategoryExtension<R> comboExtension(
            int width, int height, SlotDisplay ingredient) {
        return new ICraftingCategoryExtension<>() {
            @Override
            public List<SlotDisplay> getIngredients(RecipeHolder<R> holder) {
                return Collections.nCopies(width * height, ingredient);
            }

            @Override
            public int getWidth(RecipeHolder<R> holder) {
                return width;
            }

            @Override
            public int getHeight(RecipeHolder<R> holder) {
                return height;
            }

            /**
             * JEI 判断「这条配方要不要显示」。
             *
             * <p>默认实现恒为 true —— 这里覆写只是为了把结果打进日志，
             * 方便确认 JEI 到底有没有走到这一步（作者反馈「配方点不开」时靠它定位）。</p>
             */
            /**
             * 把配方画到 JEI 的网格上。
             *
             * <p>⚠️ <b>这个方法是必须的。</b>{@code ICraftingCategoryExtension.setRecipe}
             * 的默认实现是<b>空方法</b>，而 {@code CraftingRecipeCategory.setRecipe} 只转调它 ——
             * 不覆写的话，JEI 里这条配方就是**空网格**（作者反馈「点不开 / 看不到」的根子）。</p>
             */
            @Override
            public void setRecipe(RecipeHolder<R> holder, IRecipeLayoutBuilder builder,
                                  ICraftingGridHelper gridHelper, IFocusGroup focuses) {
                List<RecipeDisplay> displays = holder.value().display();
                ShapedCraftingRecipeDisplay shaped = displays.stream()
                        .filter(ShapedCraftingRecipeDisplay.class::isInstance)
                        .map(ShapedCraftingRecipeDisplay.class::cast)
                        .findFirst()
                        .orElse(null);
                if (shaped == null) {
                    return;
                }
                gridHelper.createAndSetIngredientsFromDisplays(
                        builder, shaped.ingredients(), width, height);
                gridHelper.createAndSetOutputs(builder, shaped.result());
            }
            @Override
            public boolean isHandled(RecipeHolder<R> holder) {
                MoreBalls.LOGGER.info("[ball][JEI] isHandled({}) -> true", holder.id());
                return true;
            }
        };
    }
}
