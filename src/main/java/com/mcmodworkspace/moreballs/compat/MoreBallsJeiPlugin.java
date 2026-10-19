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
 * 「旋转工艺」的 JEI 插件。
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
 * 它们只用于配方展示（见 {@code #rotation_craft:example_only} 标签，不在创造栏、也无获取途径）。</p>
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
     * <p>用 {@code TagSlotDisplay(#rotation_craft:fragments/half)} 表示「任意半球」，
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
     * <p>{@code #rotation_craft:fragments/half} 标签里**只有一条** {@code rotation_craft:ball_half} ——
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

        // ===== 球堆（作者 2026-10-14 追加的需求）=====
        //
        // 需求：**在背包里把鼠标悬停在球堆上、按查询配方的键，要跳到「球堆内部那颗球」的配方。**
        //
        // JEI 是按「物品 + 子类型」索引配方的，所以想让带组件的球堆查到配方，
        // **必须**把它注册成子类型 —— 这恰好是上面那两条教训的反面：
        // 半球 / 组合球不注册是因为「不想多出条目」，而球堆不注册就**根本查不到配方**。
        //
        // ⚠️⚠️ **子类型只按「球种」算，绝不把耐久算进去**
        //     （作者特意交代「最好不要一次性搞太多条目」）。
        //     球堆内部的耐久千变万化，算进去会炸出几十上百个条目；
        //     而**耐久根本不影响配方** —— 只按球种算既能查到正确的配方，
        //     JEI 里也最多只有 8 个球堆条目（每种球一个）。
        //
        //     所以这里取的是**内部球的物品 id**，不是那颗球本身。
        registration.registerSubtypeInterpreter(ModItems.BALL_CLUSTER.get(), (stack, context) -> {
            ItemStack inner = com.mcmodworkspace.moreballs.BallClusterItem.contents(stack).lowest();
            return inner.isEmpty()
                    ? ""
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(inner.getItem()).toString();
        });
    }

    /**
     * <b>运行期把球堆从 JEI 的物品列表里移除。</b>
     *
     * <h2>这是「C 方案」的后半（作者 2026-10-14 指定「试试 c」）</h2>
     * <p>前面 {@link #registerItemSubtypes} 给球堆注册了子类型，那是
     * 「按 R 能跳到内部那颗球的配方」的<b>硬性前提</b> —— 但副作用是球堆会出现在
     * JEI 的物品列表里，而作者要的是「它只是个减少物品栏占用的措施，不是给玩家直接拿的东西」。</p>
     *
     * <p>所以这里在 JEI 运行期就绪之后把它<b>从列表移除</b>，两件事都要：</p>
     * <ul>
     *   <li><b>注册子类型</b> —— 拿到「悬停按 R 查配方」的能力</li>
     *   <li><b>运行期移除</b> —— 列表里看不见它</li>
     * </ul>
     *
     * <h2>⚠️ 未知项</h2>
     * <p><b>我无法在这里实测「移除之后按 R 还能不能查到配方」</b> ——
     * {@code runClient} 只跑到主菜单，射不出那一箭、也点不到 JEI 界面。
     * 两种结果都有可能：</p>
     * <ol>
     *   <li>移除只影响<b>列表显示</b>，配方索引照旧 ⇒ 两个目的都达到（理想情况）</li>
     *   <li>移除同时也把配方索引摘了 ⇒ 按 R 查不到，那就得在这两者之间取舍</li>
     * </ol>
     * <p>请作者实测后告知走的是哪一条；若是第 2 种，把本方法注释掉即可回到
     * 「列表里有、但按 R 能查配方」的状态（那种状态本身也是可用的）。</p>
     */
    @Override
    public void onRuntimeAvailable(mezz.jei.api.runtime.IJeiRuntime runtime) {
        // ===== ★ 已停用「把球堆从 JEI 列表移除」（作者 2026-10-15 实测反馈）=====
        //
        // 当时写的是「注册子类型 + 运行期移除」两步，并在上面的注释里标注了**未知项**：
        //   移除究竟是「只影响列表显示」还是「同时也把配方索引摘了」——
        //   `runClient` 只到主菜单，我这边无法实测。
        //
        // 作者实测的结果是**后者**：「对准球堆按 r 键并不能唤出对应球的配方」——
        // 移除的同时把配方索引一起摘了 ⇒ `registerItemSubtypes` 白注册，按 R 什么都查不到。
        //
        // ⇒ 按当时写下的预案停用它。代价是 JEI 的物品列表里会多出最多 8 个球堆条目
        //   （每种球一个，见 `registerItemSubtypes` 的子类型解释器），
        //   **换来的是「悬停球堆按 R 跳到那颗球的配方」** —— 后者才是作者要的功能。
        //
        // 下面这段保留着：将来若找到「只隐藏列表、保留配方索引」的口子，把 `false` 改成
        // `true` 就能启用。（用 `if (false)` 而不是直接删，是为了留下可对照的实现。）
        if (false) {
            try {
                runtime.getIngredientManager().removeIngredientsAtRuntime(
                        mezz.jei.api.constants.VanillaTypes.ITEM_STACK,
                        java.util.List.of(new ItemStack(ModItems.BALL_CLUSTER.get())));
            } catch (Throwable ignored) {
                // JEI 版本差异导致口子对不上时安静跳过 —— 顶多是列表里多一个条目，不影响游戏
            }
        }

        // 把 runtime 交给「focus 转发器」—— 它要借 JEI 自己的查询接口去查内部球的配方
        ClusterFocusForwarder.bind(runtime);
    }

    /**
     * <b>注册「球堆 → 内部球」的 focus 转发器</b>（作者 2026-10-16）。
     *
     * <h2>为什么光注册子类型不够（这是上一版写成错的地方）</h2>
     * <p>{@link #registerItemSubtypes} 里当时写着「JEI 是按『物品 + 子类型』索引配方的，
     * 所以想让带组件的球堆查到配方<b>必须</b>把它注册成子类型」—— <b>这个理解是错的</b>，
     * 而且错得很具体：</p>
     * <ul>
     *   <li>子类型只决定「这个物品在 JEI 里算几个条目」，它<b>不会</b>让球堆的配方查询
     *       落到别的物品身上；</li>
     *   <li>JEI 查配方用的是<b>物品自己的 uid</b>（item id + 子类型串），球堆的 uid
     *       前缀永远是 {@code rotation_craft:ball_cluster}，和木球、铁球那些 uid
     *       <b>永远不可能相等</b>。</li>
     * </ul>
     * <p>所以那条需求（作者 2026-10-14 提、2026-10-15 实测失败）一直是空的 ——
     * 作者 2026-10-16 的反馈原话是「只能看用途不能看合成表」。</p>
     *
     * <h2>正确做法</h2>
     * <p>JEI 的官方扩展点 {@link mezz.jei.api.recipe.advanced.IRecipeManagerPlugin}：
     * JEI 在查询时会遍历所有注册的插件并合并结果。这里做的就是
     * <b>把「球堆」的 focus 换成「壳里那颗球」的 focus，再借 JEI 自己的
     * {@code createRecipeLookup / createRecipeCategoryLookup} 去查一遍</b> ——
     * 于是「对着球堆按 R / U」得到的就是内部球的那一套配方。</p>
     *
     * <p>不会递归：转发后的 focus 是内部球本身，{@link ClusterFocusForwarder#innerOf}
     * 对它返回空 ⇒ 插件直接返回空列表，交回给 JEI 原生结果。</p>
     */
    @Override
    public void registerAdvanced(mezz.jei.api.registration.IAdvancedRegistration registration) {
        registration.addRecipeManagerPlugin(new ClusterFocusForwarder());
    }

    /**
     * 把「球堆的 focus」转发成「壳里那颗球的 focus」。
     *
     * <p>三个方法的分工见 {@link #registerAdvanced} 的注释：
     * {@code getRecipeTypes} 决定「这个 focus 涉及哪些配方类型」，
     * {@code getRecipes(type, focus)} 才是真正把配方捞出来的那一步。</p>
     */
    public static final class ClusterFocusForwarder
            implements mezz.jei.api.recipe.advanced.IRecipeManagerPlugin {

        private static volatile mezz.jei.api.runtime.IJeiRuntime runtime;

        static void bind(mezz.jei.api.runtime.IJeiRuntime jeiRuntime) {
            runtime = jeiRuntime;
        }

        /** focus 指向的如果是球堆，返回壳里那颗球；否则返回空 */
        private static ItemStack innerOf(mezz.jei.api.recipe.IFocus<?> focus) {
            Object value = focus.getTypedValue().getIngredient();
            if (value instanceof ItemStack stack
                    && com.mcmodworkspace.moreballs.BallClusterItem.isCluster(stack)) {
                return com.mcmodworkspace.moreballs.BallClusterItem.asBall(stack);
            }
            return ItemStack.EMPTY;
        }

        /** 转发成内部球的 focus；不该管的情况返回 null */
        private static mezz.jei.api.recipe.IFocus<ItemStack> forward(
                mezz.jei.api.recipe.IFocus<?> focus) {
            ItemStack inner = innerOf(focus);
            mezz.jei.api.runtime.IJeiRuntime rt = runtime;
            if (inner.isEmpty() || rt == null) {
                return null;
            }
            return rt.getJeiHelpers().getFocusFactory()
                    .createFocus(focus.getRole(),
                            mezz.jei.api.constants.VanillaTypes.ITEM_STACK, inner);
        }

        @Override
        public <V> java.util.List<mezz.jei.api.recipe.types.IRecipeType<?>> getRecipeTypes(
                mezz.jei.api.recipe.IFocus<V> focus) {
            mezz.jei.api.recipe.IFocus<ItemStack> inner = forward(focus);
            if (inner == null) {
                return java.util.List.of();
            }
            return runtime.getRecipeManager()
                    .createRecipeCategoryLookup()
                    .limitFocus(java.util.List.of(inner))
                    .get()
                    .<mezz.jei.api.recipe.types.IRecipeType<?>>map(
                            category -> category.getRecipeType())
                    .toList();
        }

        @Override
        public <T, V> java.util.List<T> getRecipes(
                mezz.jei.api.recipe.types.IRecipeType<T> recipeType,
                mezz.jei.api.recipe.IFocus<V> focus) {
            mezz.jei.api.recipe.IFocus<ItemStack> inner = forward(focus);
            if (inner == null) {
                return java.util.List.of();
            }
            return runtime.getRecipeManager()
                    .createRecipeLookup(recipeType)
                    .limitFocus(java.util.List.of(inner))
                    .get()
                    .toList();
        }

        @Override
        public <T> java.util.List<T> getRecipes(mezz.jei.api.recipe.types.IRecipeType<T> recipeType) {
            return java.util.List.of();   // 「某类型的全部配方」不归我们管
        }
    }

    // ===== 四个示例物品已在 0.3.3.127 删除（作者指定）=====
    //
    // 它们原本靠 registerExtraIngredients 塞进 JEI 的物品列表，好让「用到它们的配方」
    // 能被解析出来。但配方早就改用**真实物品**了：
    //   · 材料格 → demoComposite(ModItems.BALL_HALF / BALL_QUARTER)（真实碎片 + fragment_source 组件）
    //   · 产物格 → comboResult()（真实的 combo_ball）
    // 所以这四个示例物品已无人引用，连同它们的注册、贴图、标签、语言条目一并移除。
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
