package com.mcmodworkspace.moreballs;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * 「更多球」的标签定义 —— 本模组「球」这个大类的数据层身份。
 *
 * <p>标签是跨模组的契约：创造模式分类、配方、进度、以及其它模组的联动判定
 * 一律匹配 {@code #more_balls:balls}，而不是匹配具体物品 ID。这样才能做到
 * 「原版物品、本模组物品、甚至其它模组的物品」都能被同一套逻辑接住。</p>
 *
 * <p>注意：本文件属于「版本特有」代码 —— 26.x 起 MC 把
 * {@code ResourceLocation} 更名为 {@code Identifier}，与 1.21.x 版本线的写法不通用，
 * 因此留在版本线的 {@code src/} 中，不下沉到共享层。</p>
 */
public final class ModTags {

    private ModTags() {
    }

    /** 物品标签集合 */
    public static final class Items {

        private Items() {
        }

        /** {@code #more_balls:balls} —— 全部球类物品 */
        public static final TagKey<Item> BALLS =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "balls"));

        // ===== 创造模式子分类用的三个标签（作者 2026-10-07 指定）=====
        //
        // 做成标签而不是写死清单：以后新加一颗球，只要往对应标签里加一行，
        // 它就会自动出现在那个子分类里，不用回来改 Java。

        /** 实心球 —— 木 / 圆石 / 铁 / 金 / 紫水晶 / 铜 + 组合球 + 原版末影珍珠 */
        public static final TagKey<Item> BALLS_SOLID =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "balls/solid"));

        /** 空心球 —— 空心铁 / 空心铜 */
        public static final TagKey<Item> BALLS_HOLLOW =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "balls/hollow"));

        /** 雪球系 —— 原版雪球 + 四种雪球 */
        public static final TagKey<Item> BALLS_SNOWBALL =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "balls/snowball"));

        // ===== 半成品（2.6.0 作者指定：从 balls 的子分类里独立出来）=====
        //
        // 半球与四分之一球**不是球**，它们是「部件」。所以它们既不在 #more_balls:balls 里，
        // 也不该挂在 balls 的子分类下面 —— 现在是 fragments 这个独立大类下的两个小类。

        /** 全部半成品（半球 + 四分之一球） */
        public static final TagKey<Item> FRAGMENTS =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "fragments"));

        /** 半球 */
        public static final TagKey<Item> FRAGMENTS_HALF =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "fragments/half"));

        /** 四分之一球 */
        public static final TagKey<Item> FRAGMENTS_QUARTER =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "fragments/quarter"));

        /**
         * 仅用于配方展示的物品 —— 不进创造分类、也没有任何配方。
         *
         * <p>见 {@code BallCuttingRecipe} 与 {@code BallComboRecipe} 的 {@code display()}：
         * 那两处要给玩家看「输入什么 → 得到什么」，用的就是这几个展示样本。
         * 创造模式那边靠这个标签把它们排除掉（{@code ModCreativeTabs} 的「更多球」页）。</p>
         */
        public static final TagKey<Item> EXAMPLE_ONLY =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "example_only"));
    }
}
