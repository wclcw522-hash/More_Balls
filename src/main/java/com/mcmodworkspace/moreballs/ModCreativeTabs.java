package com.mcmodworkspace.moreballs;

import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式分类注册。
 *
 * <p>「球」这个分类是<b>标签驱动</b>的：内容不是写死的物品清单，而是每次打开时
 * 实时遍历物品注册表、筛出带 {@code #more_balls:balls} 标签的物品。好处是任何
 * 物品 —— 原版的、本模组的、第三方模组的 —— 只要被加进该标签就会自动出现在这里，
 * 不需要改一行 Java 代码。</p>
 *
 * <p>显示名走语言文件：{@code itemGroup.more_balls}
 * （zh_cn =「球」，en_us =「Balls」）。</p>
 */
public final class ModCreativeTabs {

    private ModCreativeTabs() {
    }

    /** 创造模式分类注册器；在入口类里挂到 mod 事件总线 */
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MoreBalls.MOD_ID);

    /** 「球」分类 —— 只收带 {@code #more_balls:balls} 标签的东西 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BALLS =
            TABS.register("balls", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.more_balls"))
                    .icon(() -> new ItemStack(Items.SNOWBALL))
                    .displayItems((parameters, output) -> parameters.holders()
                            .lookupOrThrow(Registries.ITEM)
                            .listElements()
                            .filter(holder -> holder.is(ModTags.Items.BALLS))
                            .forEach(holder -> output.accept(holder.value())))
                    .build());

    // ===== 分类页布局（作者 2026-10-07 定稿）=====
    //
    //  · 「球」一页装下**全部**球 —— 实心 / 空心 / 雪球三类合起来展示，
    //    不再拆成三个子分类页（它们本来就都在 #more_balls:balls 里）。
    //  · 「半成品」一页装下**两种切球**（半球 + 四分之一球）—— 它们是部件、
    //    不是球，所以从「球」那边独立出来，另起一个 fragments 大类。
    //  · 「更多球」页是本模组全部物品的兜底清单。

    /**
     * 「半成品」—— 半球与四分之一球合起来展示。
     *
     * <p>这两件都是「模板 + 组件」：裸物品只是模板，既没有来源、也拼不出贴图。
     * 所以这里摆出**带组件的示例**，用来看词条、对照配方。</p>
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FRAGMENTS_TAB =
            TABS.register("fragments", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.more_balls.fragments"))
                    .icon(() -> BallFragments.makeFragment(ModItems.BALL_HALF.get(), 2))
                    .displayItems((parameters, output) -> {
                        int kinds = BallFragments.SHORT_ID.size();
                        for (int i = 0; i < kinds; i++) {
                            output.accept(BallFragments.makeFragment(ModItems.BALL_HALF.get(), i));
                        }
                        for (int i = 0; i < kinds; i++) {
                            output.accept(BallFragments.makeFragment(ModItems.BALL_QUARTER.get(), i));
                        }
                    })
                    .build());

    /**
     * 「按某个标签填充内容」的通用写法。
     *
     * <p>三个子分类的行为完全一样、只差一个标签，所以抽成一个方法而不是复制三遍。</p>
     */
    private static CreativeModeTab.DisplayItemsGenerator tagDriven(net.minecraft.tags.TagKey<Item> tag) {
        return (parameters, output) -> parameters.holders()
                .lookupOrThrow(Registries.ITEM)
                .listElements()
                .filter(holder -> holder.is(tag))
                .forEach(holder -> output.accept(holder.value()));
    }

    /**
     * 「更多球」—— 本模组的<b>全部</b>物品。
     *
     * <p>上面那个「球」分类是标签驱动的，只会列出球；而模组还加了别的东西
     * （收纳袋、碎石这类材料），它们在「球」页里看不到。这个分类专门兜底：
     * <b>凡是本模组注册的物品一律列在这里</b>，不需要手工维护清单。</p>
     *
     * <p>物品<b>可以同时出现在多个分类里</b>，所以球会既在「球」页、也在这里，
     * 不算重复。</p>
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ALL =
            TABS.register("all", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.more_balls.all"))
                    .icon(() -> new ItemStack(ModItems.BALL_POUCH.get()))
                    .displayItems((parameters, output) -> {
                        // 先摆球 —— 按标签来，和「球」页保持一致的顺序
                        parameters.holders()
                                .lookupOrThrow(Registries.ITEM)
                                .listElements()
                                .filter(holder -> holder.is(ModTags.Items.BALLS))
                                .forEach(holder -> output.accept(holder.value()));

                        // 再把本模组其余物品补上（收纳袋、碎石……）。
                        // 直接遍历自己的注册器，以后加新物品不用回来改这里
                        ModItems.ITEMS.getEntries().forEach(entry -> {
                            Item item = entry.value();
                            if (item.builtInRegistryHolder().is(ModTags.Items.BALLS)) {
                                return;
                            }
                            // 「仅示例」的物品不进创造模式（作者指定）：
                            // 它们只是配方页里的展示样本，不是能拿到手的东西。
                            if (item.builtInRegistryHolder().is(ModTags.Items.EXAMPLE_ONLY)) {
                                return;
                            }
                            output.accept(item);
                        });

                        // ===== 半球 / 四分之一球的「示例实例」（作者指定）=====
                        //
                        // 这两件是「模板 + 组件」（参考匠魂的做法）：
                        // 裸物品只是模板，既没有来源、也拼不出贴图。
                        // 所以这里摆出**带组件的示例**，用来查看词条、对照配方。
                        // 上面的裸物品也保留 —— 它们是「该物品存在」的凭据。
                        //
                        // ⚠️ **组合球的示例不摆**（作者 2026-10-07 指定）：
                        //    它只应出现在 JEI 的配方页里。创造模式列一堆
                        //    「铁-金球」「木-圆石球」既刷屏又没意义 ——
                        //    玩家要哪一颗，自己拿部件合就是了。
                        int kinds = BallFragments.SHORT_ID.size();
                        for (int i = 0; i < kinds; i++) {
                            output.accept(BallFragments.makeFragment(ModItems.BALL_HALF.get(), i));
                        }
                        for (int i = 0; i < kinds; i++) {
                            output.accept(BallFragments.makeFragment(ModItems.BALL_QUARTER.get(), i));
                        }
                    })
                    .build());
}
