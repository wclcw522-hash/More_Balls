package com.mcmodworkspace.moreballs;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.Level;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

/**
 * <b>切球</b>：一条配方覆盖所有球，产物按输入推导。
 *
 * <h2>为什么要自己写一个</h2>
 * <p>原版 {@link StonecutterRecipe} 的产物是<b>固定</b>的 —— 它没法「按输入决定输出」。
 * 而切球必须这样：木球切出来的半球得记着「我来自木球」，铜球切出来的得记着铜球，
 * 否则合组合球时材质就全错了。</p>
 *
 * <p>于是以前的做法是<b>每颗球手写两个 json</b>（half + quarter），一共 16 个。
 * 每加一颗球都得记得补两份 —— 漏掉就「切不了」（作者 2026-10-08 报的正是新球切不动）。</p>
 *
 * <h2>⚠️ 必须继承 {@link StonecutterRecipe}，不能只实现 {@code SingleItemRecipe}</h2>
 * <p>踩过两次坑，方向正好相反：</p>
 * <ol>
 *   <li><b>直接继承原版</b>时，{@code getSerializer()} 被钉死成
 *       {@code RecipeSerializer<StonecutterRecipe>}，而 {@code RecipeSerializer} 是<b>不变型</b>的
 *       —— 返回自己的 {@code RecipeSerializer<BallCuttingRecipe>} 编译不过。
 *       当时误以为是「不能继承」，改成了 {@code SingleItemRecipe}。</li>
 *   <li><b>改成 {@code SingleItemRecipe} 之后游戏能跑，但 JEI 一开就崩</b>：
 *       JEI 的石切分类在 {@code StoneCuttingRecipeCategory#isHandled} 里会把配方
 *       <b>强转成 {@code StonecutterRecipe}</b>，于是 {@code ClassCastException}
 *       （作者 2026-10-08 报的）。</li>
 * </ol>
 * <p>结论：<b>继承是硬要求</b>（JEI 与配方书都指望这个类型），
 * 泛型那道坎用下面 {@link #SERIALIZER} 的强转绕过去。</p>
 *
 * <h2>怎么让石切台认它</h2>
 * <p>{@link net.minecraft.world.inventory.StonecutterMenu} 按<b>配方类型</b>筛选配方
 * （{@code recipeAccess().stonecutterRecipes().selectByInput(item)}），
 * 继承之后 {@code getType()} 已经是 {@code RecipeType.STONECUTTING}，天然满足。</p>
 *
 * <h2>配方怎么做到「只写一份」</h2>
 * <p>json 的 {@code ingredient} 直接写标签 {@code #more_balls:balls} —— <b>任何</b>球都能切。
 * 产物模板写 {@code ball_half} 或 {@code ball_quarter}，其中 {@code fragment_source}
 * 的占位值会被 {@link #assemble} 换成真实来源。</p>
 *
 * <p>结论：<b>加新球只要进 {@code #more_balls:balls} 标签 + 进
 * {@link BallFragments#sources()}，切割自动就有</b>，不用再写任何 json。</p>
 */
public class BallCuttingRecipe extends StonecutterRecipe {

    public BallCuttingRecipe(Recipe.CommonInfo commonInfo, Ingredient input, ItemStackTemplate result) {
        super(commonInfo, input, result);
    }

    public static final MapCodec<BallCuttingRecipe> MAP_CODEC = simpleMapCodec(BallCuttingRecipe::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, BallCuttingRecipe> STREAM_CODEC =
            simpleStreamCodec(BallCuttingRecipe::new);

    /**
     * 序列化器。
     *
     * <p><b>这里的强转是刻意的，而且运行时安全</b>：父类把 {@code getSerializer()} 钉死成
     * {@code RecipeSerializer<StonecutterRecipe>}，但 {@code RecipeSerializer} 不变型，
     * 直接给 {@code RecipeSerializer<BallCuttingRecipe>} 编译不过。
     * 两个泛型参数在擦除后是同一个对象，而这两个 codec <b>确实</b>产出
     * {@code BallCuttingRecipe}（它是 {@code StonecutterRecipe} 的子类），
     * 所以按父类的类型声明它们不会出问题。</p>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static final RecipeSerializer<StonecutterRecipe> SERIALIZER =
            new RecipeSerializer<>((MapCodec) MAP_CODEC, (StreamCodec) STREAM_CODEC);

    /**
     * 产物 = 模板（半球或四分之一球）+ <b>从输入推出来的来源下标</b>。
     *
     * <p>模板决定切出来的是半球还是四分之一球（json 里写的是哪个就是哪个），
     * 这里只把 {@code fragment_source} 改成正确的值。</p>
     *
     * <p>输入不在 {@link BallFragments#sources()} 里时返回模板原样 ——
     * 理论上不会发生（{@code matches} 已经用标签卡过一道），留着兜底免得出现空产物。</p>
     */
    @Override
    public ItemStack assemble(SingleRecipeInput input) {
        ItemStack product = this.result().create();
        int sourceIndex = BallFragments.indexOfBall(input.item().getItem());
        if (sourceIndex >= 0) {
            product.set(ModComponents.FRAGMENT_SOURCE.get(), sourceIndex);
        }
        return product;
    }

    /**
     * 除了父类的判据，还要把<b>组合球</b>挡在外面。
     *
     * <p>组合球落在 {@code #more_balls:balls/solid} 里，所以标签层面它是「可切」的。
     * 但它不在 {@link BallFragments#sources()} 里 —— 一旦被切，
     * 上面的 {@code assemble} 拿到的 {@code indexOfBall} 是 {@code -1}，
     * 产出的就是**没有来源组件的裸半球**：既合不出球也不显示词条，
     * 等于凭空生成一件废品。</p>
     *
     * <p>用代码排除而不是在标签里抄白名单 —— 后者每加一颗球都要改标签，
     * 而「{@code sources()} 里没有的球不能切」这条规则天然覆盖所有情况。</p>
     */
    @Override
    public boolean matches(SingleRecipeInput input, Level level) {
        if (BallFragments.indexOfBall(input.item().getItem()) < 0) {
            return false;
        }
        return super.matches(input, level);
    }

    @Override
    public RecipeSerializer<StonecutterRecipe> getSerializer() {
        return SERIALIZER;
    }
}
