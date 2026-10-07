package com.mcmodworkspace.moreballs;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的配方序列化器。
 *
 * <h2>为什么只需要序列化器、不需要配方类型</h2>
 * <p>组合球的配方继承 {@code CustomRecipe} → {@code CraftingRecipe}，
 * 而 {@code CraftingRecipe} 已经把 {@code getType()} 默认指向原版的
 * {@code RecipeType.CRAFTING}，所以分类是现成的，不用自己注册一个类型。</p>
 *
 * <p>两条配方本身都是<b>无状态单例</b>（形状与材料种类固定），
 * 所以序列化器用的也是常量 codec（见 {@link BallComboRecipe}），
 * 既不用写 json 数据文件，也不会有任何需要持久化的字段。</p>
 */
public final class ModRecipes {

    private ModRecipes() {
    }

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, MoreBalls.MOD_ID);

    /** 2 个半球上下摆 → 组合球 */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<BallComboRecipe.Vertical>>
            BALL_COMBO_VERTICAL = SERIALIZERS.register(
                    "ball_combo_vertical", () -> BallComboRecipe.Vertical.SERIALIZER);

    /** 4 个四分之一球 2×2 → 组合球 */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<BallComboRecipe.Square>>
            BALL_COMBO_SQUARE = SERIALIZERS.register(
                    "ball_combo_square", () -> BallComboRecipe.Square.SERIALIZER);

    /**
     * 切球：一条配方覆盖所有球（作者 2026-10-08 指定改成机制化）。
     *
     * <p>和上面两条不同，这条有 json 数据文件（half 与 quarter 各一份），
     * 因为产物模板要从数据里来；但<b>材料是标签 {@code #more_balls:balls}</b>，
     * 所以加新球不用再补配方。</p>
     */
    // 类型按父类 StonecutterRecipe 声明 —— 见 BallCuttingRecipe.SERIALIZER 上的说明，
    // 那里用了一次刻意且运行时安全的强转来绕开 RecipeSerializer 的不变型限制。
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<StonecutterRecipe>>
            BALL_CUTTING_SERIALIZER = SERIALIZERS.register(
                    "ball_cutting", () -> BallCuttingRecipe.SERIALIZER);
    public static void register(IEventBus modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
