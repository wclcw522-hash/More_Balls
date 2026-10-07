package com.mcmodworkspace.moreballs;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/**
 * 本模组的数据组件。
 *
 * <h2>为什么球不能用原版耐久</h2>
 * MC 有一条硬校验：<b>物品不能同时「有耐久」和「可堆叠」</b> ——
 * {@code Item.Properties.finalizeInitializer} 里会直接抛
 * {@code IllegalStateException: Item cannot have both durability and be stackable}。
 * 也就是说 {@code durability(n)} 把 {@code MAX_STACK_SIZE} 压成 1 是刻意为之，
 * 硬用 {@code stacksTo(64)} 覆盖会让整个世界加载失败。
 *
 * <p>所以球改用<b>自定义组件</b>记录剩余可承受的碰撞次数：</p>
 * <ul>
 *   <li>物品本身走 {@code stacksTo(64)}，不挂 {@code MAX_DAMAGE} / {@code DAMAGE}，
 *       于是不会触发那条校验</li>
 *   <li><b>同耐久的球自动能叠</b>（组件值一样）、<b>不同耐久自动分堆</b>
 *       （物品堆叠的前提正是「id + 全部数据组件完全一致」）</li>
 *   <li>耐久条由 {@code BallDurabilityDecorator} 自己画（原版不会给没有
 *       {@code MAX_DAMAGE} 的物品画条）</li>
 * </ul>
 *
 * <p><b>约定</b>：全新的球<b>不带</b>这个组件（代表满耐久），
 * 一旦消耗过就写入具体数值 —— 这样「刚合成的一批」组件完全一致、能叠成一堆，
 * 而「弹过几次的」自然分到另一堆。</p>
 */
public final class ModComponents {

    private ModComponents() {
    }

    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MoreBalls.MOD_ID);

    /**
     * 剩余可承受的碰撞次数。
     *
     * <p>缺失即代表<b>满耐久</b>（新合成的球就是这样）；消耗过才会写入具体数值。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> TOUGHNESS_LEFT =
            COMPONENTS.registerComponentType("toughness_left", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /**
     * 收纳袋里的东西 —— 弹药段 + 转运段，见 {@link BallPouchContents}。
     *
     * <p>不带这个组件 = 空袋（新合成出来就是这样）。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BallPouchContents>> POUCH_CONTENTS =
            COMPONENTS.registerComponentType("pouch_contents", builder -> builder
                    .persistent(BallPouchContents.CODEC)
                    .networkSynchronized(BallPouchContents.STREAM_CODEC));


    /**
     * 半成品的「来源球」—— 存的是来源球在 {@link BallFragments#sources()} 里的下标。
     *
     * <p>半球与四分之一球都是<b>同一件物品</b>（参考匠魂的「模板 + 组件」做法），
     * 靠这个组件区分「这是铁球的半球还是金球的半球」。不带组件 = 还没填模板的裸物品。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> FRAGMENT_SOURCE =
            COMPONENTS.registerComponentType("fragment_source", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /**
     * 组合球的「来源列表」—— 逗号分隔的来源下标，<b>按摆放位置顺序</b>排列。
     *
     * <h2>顺序的含义（作者 2026-10-07 定）</h2>
     * <p>合成是有形状的，而形状直接决定贴图拼哪一块：</p>
     * <ul>
     *   <li><b>二合一</b>：上下两格。{@code "上,下"} ——
     *       上面的半球取它的 {@code half_top}、下面的取 {@code half_bottom}</li>
     *   <li><b>四合一</b>：2×2。{@code "左上,右上,左下,右下"} ——
     *       依次对应 {@code quarter_1 / _2 / _3 / _4}</li>
     * </ul>
     *
     * <p>所以这个字段<b>不能排序</b>：玩家把铁放上面还是放下面，
     * 出来的都是同一件「铁-金球」物品，但<b>贴图不一样</b> ——
     * 上铁下金取铁的上半 + 金的下半，反过来则相反。
     * 这正是作者「同一种组合只建一件物品，但按位置调用对应贴图」的做法，
     * 也是那 6 张分段贴图存在的理由。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> COMBO_SOURCES =
            COMPONENTS.registerComponentType("combo_sources", builder -> builder
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    // ===== 组合球的「分块来源」（2.6.0 · 匠魂式分层渲染）=====
    //
    // 为什么要把一件事拆成四个 int：
    //   贴图要按「每一块分别选图」再叠起来 —— 左上取 A 球、右上取 B 球……
    //   而物品模型的 `minecraft:select` 只能按**某个组件**整值匹配，
    //   没法「取 combo_sources 这个字符串的第 2 段当条件」。
    //   所以拆成四个 int 组件，每块各有一个可直接当条件的值。
    //
    // COMBO_SOURCES 保留不动：它是**逻辑层**的来源（组合球的词条由它算），
    // 而这四个只是**渲染层**的分块索引。两者由 makeComboBall 一次性同时写入，
    // 所以永远一致。分开的好处是老数据（只有 COMBO_SOURCES）至少逻辑还算得出来。

    /** 组合球左上块的来源下标（对应贴图 {@code <ball>_quarter_1}） */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> COMBO_SLOT_1 =
            COMPONENTS.registerComponentType("combo_slot_1", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /** 组合球右上块的来源下标（对应贴图 {@code <ball>_quarter_2}） */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> COMBO_SLOT_2 =
            COMPONENTS.registerComponentType("combo_slot_2", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /** 组合球左下块的来源下标（对应贴图 {@code <ball>_quarter_3}） */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> COMBO_SLOT_3 =
            COMPONENTS.registerComponentType("combo_slot_3", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /** 组合球右下块的来源下标（对应贴图 {@code <ball>_quarter_4}） */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> COMBO_SLOT_4 =
            COMPONENTS.registerComponentType("combo_slot_4", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /** 四个分块组件的顺序表，方便按象限下标取值（0=左上 … 3=右下） */
    public static final List<DeferredHolder<DataComponentType<?>, DataComponentType<Integer>>> COMBO_SLOTS =
            List.of(COMBO_SLOT_1, COMBO_SLOT_2, COMBO_SLOT_3, COMBO_SLOT_4);

    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }
}
