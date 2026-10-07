package com.mcmodworkspace.moreballs;

import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Ingredient;
import java.util.List;

import com.mojang.serialization.MapCodec;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;

/**
 * 组合球的两条合成配方（作者 2026-10-07 指定）。
 *
 * <h2>为什么要自定义配方</h2>
 * <p>原版 {@code crafting_shaped} 的产物是写死在 json 里的，
 * <b>没法把「哪颗球放在哪一格」带进产物的数据组件</b>。
 * 而作者要求的正是「按摆放位置决定贴图」—— 上铁下金 与 上金下铁 出来的贴图不一样，
 * 所以必须由代码读格位、写组件。这就是 {@link CustomRecipe} 的用武之地。</p>
 *
 * <h2>两条配方</h2>
 * <ul>
 *   <li>{@link Vertical} —— <b>2 个半球上下摆</b>（1×2）。
 *       上面那格取它的 {@code half_top}、下面那格取 {@code half_bottom}</li>
 *   <li>{@link Square} —— <b>4 个四分之一球摆成 2×2</b>。
 *       依次对应 {@code quarter_1 / _2 / _3 / _4}</li>
 * </ul>
 *
 * <h2>「顺序随意」的含义</h2>
 * <p>任意来源的半球都能放任意格，配方<b>不限定哪种球放哪</b>；
 * 但「哪一格」决定它在产物里占哪一段贴图。
 * 所以同一个「铁+金」组合，铁放上面得到「铁-金球」、放下面的得到「金-铁球」——
 * <b>是同一件物品</b>（不会重复注册），名字与贴图跟着位置走。</p>
 *
 * <h2>为什么用无状态单例</h2>
 * <p>这两条配方没有任何可配置参数（形状、材料种类都是固定的），
 * 所以照原版 {@code RepairItemRecipe} 的写法做成 {@code MapCodec.unit(INSTANCE)}，
 * 编解码退化成「常量」，既不用存 json 字段也不用写数据文件。</p>
 */
public final class BallComboRecipe {

    /**
     * JEI 里那两个「成品」格子（二合一 / 四合一共用）。
     *
     * <p>带上一组真实的来源组件 —— 组合球的贴图是按组件选象限拼出来的，
     * 不带组件的裸模板虽然现在 fallback 会显示木球，但示例摆一组真来源更直观，
     * 也能让人看清「合出来的是个什么样子的球」。</p>
     *
     * <p>来源固定成「木 + 铁」（下标 0、2）：两个示例用的是同一组，
     * 这样它们在 JEI 里看起来是同一件成品、只是摆法不同 —— 事实也确实如此。</p>
     */
    private static SlotDisplay comboResult() {
        ItemStackTemplate template = new ItemStackTemplate(
                ModItems.COMBO_BALL.get(), 1,
                DataComponentPatch.builder()
                        .set(ModComponents.COMBO_SOURCES.get(), "0,2")
                        .build());
        return new SlotDisplay.ItemStackSlotDisplay(template);
    }

    private BallComboRecipe() {
    }

    /** 2 个半球，上下摆（1 宽 × 2 高） */
    public static final class Vertical extends CustomRecipe {

        public static final Vertical INSTANCE = new Vertical();
        public static final MapCodec<Vertical> MAP_CODEC = MapCodec.unit(INSTANCE);
        public static final StreamCodec<RegistryFriendlyByteBuf, Vertical> STREAM_CODEC =
                StreamCodec.unit(INSTANCE);
        public static final RecipeSerializer<Vertical> SERIALIZER =
                new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

        @Override
        public boolean matches(CraftingInput input, Level level) {
            // 只认 1×2 的竖排：横排两格不是作者要求的形状
            if (input.width() != 1 || input.height() != 2) {
                MoreBalls.LOGGER.info("[ball][combo] Vertical.matches 尺寸不符 {}x{}", input.width(), input.height());
                return false;
            }
            boolean ok = isFragment(input.getItem(0)) && isFragment(input.getItem(1));
            MoreBalls.LOGGER.info("[ball][combo] Vertical.matches 结果={} 格0={} 格1={}", ok,
                    input.getItem(0), input.getItem(1));
            return ok;
        }

        @Override
        public ItemStack assemble(CraftingInput input) {
            // CraftingInput 的索引是 x + y * width，1 宽时就是从上到下
            return BallFragments.makeComboBall(List.of(
                    BallFragmentItem.sourceIndex(input.getItem(0)),
                    BallFragmentItem.sourceIndex(input.getItem(1))));
        }

        /**
         * 给 JEI 与配方书看的外观。
         *
         * <p>自定义配方默认没有 display，JEI 里就查不到它 —— 而作者明确要求
         * 「在 JEI 与创造模式里能看到配方」。所以这里手工给一个「长什么样」的描述：
         * 借原版的 {@code ShapedCraftingRecipeDisplay} 摆出竖排两格的形状。</p>
         *
         * <p>产物只是<b>示例</b>（裸模板，不带组件）—— 真正的产物取决于玩家放了哪两颗半球，
         * 而这一点是静态 display 表达不了的，只能靠「形状 + 示例」说明玩法。</p>
         */
        @Override
        public List<RecipeDisplay> display() {
            // ⚠️ 用 **TagSlotDisplay** 而不是 ItemSlotDisplay（作者 2026-10-07 指定）：
            //    JEI 里要显示成「任意半球」，而不是某一个具体的半球。
            //    配方本身也不挑具体来源 —— 任何带 fragment_source 组件的半球都行，
            //    所以展示用标签才是准确的。
            // 展示用「（示例）半球」与「（示例）组合球（二合一）」——
            // 这两件是专门为配方展示注册的，不进创造、没有配方，只在这里露面。
            SlotDisplay half = new SlotDisplay.TagSlotDisplay(ModTags.Items.FRAGMENTS_HALF);
            SlotDisplay result = new SlotDisplay.ItemSlotDisplay(ModItems.EXAMPLE_COMBO_VERTICAL.get());
            return List.of(new ShapedCraftingRecipeDisplay(
                    1, 2,
                    List.of(half, half),
                    result,
                    new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)));
        }

        /**
         * <b>必须重写成 false</b>：{@code CustomRecipe} 默认返回 true，
         * 而「特殊配方」JEI 与配方书都不显示 —— 那正是「JEI 里看不到组合球示例」的原因。
         */
        @Override
        public boolean isSpecial() {
            return false;
        }

        /**
         * 摆法信息：1×2 竖排，两格都是半球。
         *
         * <p>{@code CustomRecipe} 默认返回 {@code NOT_PLACEABLE}，JEI 就没法把配方画到
         * 网格上。这里按 {@link #matches} 实际认的槽位顺序给出材料 ——
         * 索引 0 是上面那格、1 是下面那格（1 宽网格里 y 就是索引）。</p>
         */
        @Override
        public PlacementInfo placementInfo() {
            // 用具体物品即可：Ingredient.of(Item) 不检查组件，
            // 所以带 fragment_source 的半球照样匹配得上（见 isFragment 的实际判据）。
            Ingredient half = Ingredient.of(ModItems.BALL_HALF.get());
            return PlacementInfo.create(List.of(half, half));
        }

        @Override
        public RecipeSerializer<? extends CustomRecipe> getSerializer() {
            return SERIALIZER;
        }

        private static boolean isFragment(ItemStack stack) {
            boolean itemOk = stack.getItem() == ModItems.BALL_HALF.get();
            int idx = BallFragmentItem.sourceIndex(stack);
            if (!itemOk || idx < 0) {
                MoreBalls.LOGGER.info("[ball][combo] isFragment(半球) 失败: item={} sourceIndex={}", stack.getItem(), idx);
            }
            return itemOk && idx >= 0;
        }
    }

    /** 4 个四分之一球，2×2 摆放 */
    public static final class Square extends CustomRecipe {

        public static final Square INSTANCE = new Square();
        public static final MapCodec<Square> MAP_CODEC = MapCodec.unit(INSTANCE);
        public static final StreamCodec<RegistryFriendlyByteBuf, Square> STREAM_CODEC =
                StreamCodec.unit(INSTANCE);
        public static final RecipeSerializer<Square> SERIALIZER =
                new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

        @Override
        public boolean matches(CraftingInput input, Level level) {
            if (input.width() != 2 || input.height() != 2) {
                MoreBalls.LOGGER.info("[ball][combo] Square.matches 尺寸不符 {}x{}", input.width(), input.height());
                return false;
            }
            for (int i = 0; i < 4; i++) {
                if (!isFragment(input.getItem(i))) {
                    MoreBalls.LOGGER.info("[ball][combo] Square.matches 格{}不是碎片: {}", i, input.getItem(i));
                    return false;
                }
            }
            MoreBalls.LOGGER.info("[ball][combo] Square.matches 通过");
            return true;
        }

        @Override
        public ItemStack assemble(CraftingInput input) {
            // 索引 x + y*width、宽度 2 → 0=左上 1=右上 2=左下 3=右下，
            // 与贴图的 quarter_1..4 一一对应
            return BallFragments.makeComboBall(List.of(
                    BallFragmentItem.sourceIndex(input.getItem(0)),
                    BallFragmentItem.sourceIndex(input.getItem(1)),
                    BallFragmentItem.sourceIndex(input.getItem(2)),
                    BallFragmentItem.sourceIndex(input.getItem(3))));
        }

        /** 给 JEI 与配方书看的外观 —— 2×2 四格（同 {@link Vertical#display()} 的理由） */
        @Override
        public List<RecipeDisplay> display() {
            // 同「二合一」：用标签表示「任意四分之一球」。
            // 两个示例（二合一 / 四合一）就是这样在 JEI 里分开显示的 ——
            // 成品都是 combo_ball，区别在摆法：上下两格 vs 2×2。
            SlotDisplay quarter = new SlotDisplay.TagSlotDisplay(ModTags.Items.FRAGMENTS_QUARTER);
            SlotDisplay result = new SlotDisplay.ItemSlotDisplay(ModItems.EXAMPLE_COMBO_SQUARE.get());
            return List.of(new ShapedCraftingRecipeDisplay(
                    2, 2,
                    List.of(quarter, quarter, quarter, quarter),
                    result,
                    new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)));
        }

        /** 同 {@link Vertical#isSpecial()} —— 不重写的话 JEI 不显示。 */
        @Override
        public boolean isSpecial() {
            return false;
        }

        /** 摆法信息：2×2，四格都是四分之一球（索引 0=左上 1=右上 2=左下 3=右下）。 */
        @Override
        public PlacementInfo placementInfo() {
            Ingredient quarter = Ingredient.of(ModItems.BALL_QUARTER.get());
            return PlacementInfo.create(List.of(quarter, quarter, quarter, quarter));
        }

        @Override
        public RecipeSerializer<? extends CustomRecipe> getSerializer() {
            return SERIALIZER;
        }

        private static boolean isFragment(ItemStack stack) {
            boolean itemOk = stack.getItem() == ModItems.BALL_QUARTER.get();
            int idx = BallFragmentItem.sourceIndex(stack);
            if (!itemOk || idx < 0) {
                MoreBalls.LOGGER.info("[ball][combo] isFragment(四分之一球) 失败: item={} sourceIndex={}", stack.getItem(), idx);
            }
            return itemOk && idx >= 0;
        }
    }
}
