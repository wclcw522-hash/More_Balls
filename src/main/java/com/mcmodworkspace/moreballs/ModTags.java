package com.mcmodworkspace.moreballs;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

/**
 * 「旋转工艺」的标签定义 —— 本模组「球」这个大类的数据层身份。
 *
 * <p>标签是跨模组的契约：创造模式分类、配方、进度、以及其它模组的联动判定
 * 一律匹配 {@code #rotation_craft:balls}，而不是匹配具体物品 ID。这样才能做到
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

        /** {@code #rotation_craft:balls} —— 全部球类物品 */
        public static final TagKey<Item> BALLS =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "balls"));

        // ===== 创造模式子分类用的三个标签（作者 2026-10-07 指定）=====
        //
        // 做成标签而不是写死清单：以后新加一颗球，只要往对应标签里加一行，
        // 它就会自动出现在那个子分类里，不用回来改 Java。

        /** 实心球 —— 木 / 圆石 / 铁 / 金 / 紫水晶 / 铜 / 红石 / 钻石 / 青金石 + 组合球 + 原版末影珍珠 */
        public static final TagKey<Item> BALLS_SOLID =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "balls/solid"));

        /** 空心球 —— 空心铁 / 空心铜 */
        public static final TagKey<Item> BALLS_HOLLOW =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "balls/hollow"));

        /** 雪球系 —— 原版雪球 + 五种雪球 */
        public static final TagKey<Item> BALLS_SNOWBALL =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "balls/snowball"));

        // ===== 半成品（2.6.0 作者指定：从 balls 的子分类里独立出来）=====
        //
        // 半球与四分之一球**不是球**，它们是「部件」。所以它们既不在 #rotation_craft:balls 里，
        // 也不该挂在 balls 的子分类下面 —— 现在是 fragments 这个独立大类下的两个小类。

        /** 全部半成品（半球 + 四分之一球） */

        /** 半球 */
        public static final TagKey<Item> FRAGMENTS_HALF =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "fragments/half"));

        /** 四分之一球 */
        public static final TagKey<Item> FRAGMENTS_QUARTER =
                TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "fragments/quarter"));


    }

    /**
     * <b>实体类型标签</b>（作者 2026-10-11 指定）。
     *
     * <p>这里只放一个「可持球生物」—— 给<b>联动</b>用的口子。</p>
     */
    public static final class EntityTypes {

        private EntityTypes() {
        }

        /**
         * <b>可持球生物</b> —— 会捡球 / 接球 / 用球的生物。
         *
         * <h2>为什么要单独开一个标签</h2>
         * <p>能不能用球的判据在 {@code BallMobAI.canUseBalls} 里，那里是
         * <b>{@code instanceof} 硬判原版类</b>的。那样写的好处是<b>继承自动覆盖</b>
         * （任何 {@code extends Zombie} 的模组僵尸自动能捡），但坏处是
         * <b>全新继承链的模组生物必须改代码</b> —— 得 {@code compileOnly} 依赖那个模组、
         * 还得判 {@code ModList.isLoaded()}</p>
         *
         * <p>所以再开一条标签：<b>第三方模组 / 整合包只要把自己的生物写进这个标签
         * （数据包即可），零改代码、零重编译就自动支持。</b></p>
         *
         * <p>⚠️ <b>两条路是「或」的关系</b>，不是替换 —— 原版那 19 种照旧走
         * {@code instanceof}，所以就算有人拿 {@code replace: true} 把标签整个覆盖掉，
         * 原版那些也不会失效。</p>
         *
         * <p>⚠️ <b>标签只管「能不能捡球」，管不了「专属机制」</b>（蜘蛛的缓慢、
         * 嘎枝的注视、史莱姆的继承、烈焰人八肢、幻翼俯冲都是代码行为）。
         * 不过那几个机制判的也是 {@code instanceof} 原版基类，
         * 所以<b>继承了它们的模组生物照样自动拿到</b>。</p>
         */
        public static final TagKey<EntityType<?>> BALL_CARRIERS = TagKey.create(
                Registries.ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "ball_carriers"));
    }
}
