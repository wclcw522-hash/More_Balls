package com.mcmodworkspace.moreballs.compat;

import com.mcmodworkspace.moreballs.BallComboRecipe;
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

    @Override
    public void registerVanillaCategoryExtensions(IVanillaCategoryExtensionRegistration registration) {
        MoreBalls.LOGGER.info("[ball][JEI] 注册组合球配方扩展：Vertical(1x2) + Square(2x2)");
        // 二合一：1 宽 2 高，两格都是示例半球
        registration.getCraftingCategory().addExtension(
                BallComboRecipe.Vertical.class,
                comboExtension(1, 2, ModItems.EXAMPLE_HALF.get()));
        // 四合一：2×2 四格，都是示例四分之一球
        registration.getCraftingCategory().addExtension(
                BallComboRecipe.Square.class,
                comboExtension(2, 2, ModItems.EXAMPLE_QUARTER.get()));
    }

    /**
     * 造一个「所有格子都用同一种材料」的配方扩展。
     *
     * <p>{@code getIngredients} 返回的列表长度必须是 {@code width * height}，
     * 顺序按行左到右、上到下 —— JEI 会拿它铺到合成网格里。</p>
     */
    private static <R extends CraftingRecipe> ICraftingCategoryExtension<R> comboExtension(
            int width, int height, Item ingredient) {
        return new ICraftingCategoryExtension<>() {
            @Override
            public List<SlotDisplay> getIngredients(RecipeHolder<R> holder) {
                SlotDisplay slot = new SlotDisplay.ItemSlotDisplay(ingredient.builtInRegistryHolder());
                return Collections.nCopies(width * height, slot);
            }

            @Override
            public int getWidth(RecipeHolder<R> holder) {
                return width;
            }

            @Override
            public int getHeight(RecipeHolder<R> holder) {
                return height;
            }
        };
    }
}
