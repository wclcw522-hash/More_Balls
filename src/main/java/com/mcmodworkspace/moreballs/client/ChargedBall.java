package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.BallAmmo;
import com.mcmodworkspace.moreballs.ModItems;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperty;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 物品模型属性 {@code more_balls:charged_ball} —— 弩里装的是哪一颗球。
 *
 * <h2>为什么要自己写一个属性</h2>
 * 原版弩的模型用 {@code minecraft:charge_type} 分三种情况：装箭 / 装烟花火箭 / 空。
 * 那个属性的取值是 {@code CrossbowItem.ChargeType} 枚举（只有 {@code NONE / ARROW / ROCKET}），
 * <b>封闭的，加不了新值</b>，所以装上球的弩在原版眼里就是「空」，显示的也是空弩模型。
 *
 * <p>于是这里另起一个属性，取值是<b>弹药物品的 id 字符串</b>：
 * 装的是木球就返回 {@code "more_balls:wooden_ball"}，没装球则返回 {@code "none"}。
 * 模型文件（{@code assets/minecraft/items/crossbow.json}）里<b>每种球各有一个分支</b>，
 * 各自指向那张「弩上插着这颗球」的贴图；不是球就整个落回原版那套逻辑 ——
 * 箭和烟花火箭的显示分毫不受影响。</p>
 *
 * <p>好处是不必再写特殊渲染器：每种球的图标在模型里就是静态的，代价是<b>新增球种时要往
 * json 里补一个分支</b>。装了别家模组往 {@code #more_balls:balls} 里塞物品也不会出错 ——
 * 没有对应分支就落到 fallback，照原版空弩显示。</p>
 */
public record ChargedBall() implements SelectItemModelProperty<String> {

    /**
     * <b>⚠️ 暂时静默「装填组合球的专属弩贴图」这个功能（作者 2026-10-09 指定）。</b>
     *
     * <p>为 {@code true} 时，装填的是组合球也一律按「没装球」上报 ——
     * 于是物品模型的 select 匹配不到 {@code more_balls:combo_ball} 那个分支，
     * 自动落到 fallback（完整的原版弩逻辑），外观与普通弩一致。</p>
     *
     * <h2>为什么要静默</h2>
     * <p>那条分支用的是 {@code minecraft:special} + 自绘几何（见
     * {@code ComboChargeBallRenderer}），渲染结果一直不稳定。在修好之前先让它在游戏里
     * <b>完全看不出存在</b>，比留一个半成品好。</p>
     *
     * <h2>恢复方法</h2>
     * <p>把这里改回 {@code false} 即可 —— <b>其余代码一个字都不用动</b>：
     * {@code crossbow.json} 的 combo case、{@code ComboChargeBallRenderer}、
     * {@code BallAmmo.chargedBallStack} 全都原样保留着。</p>
     */
    public static final boolean SILENCED = false;   // 0.3.3.82 曾在 GitHub 上短暂置为 true（静默版），本地已恢复正常

    /** 没装球（或装的不是球） */
    public static final String NONE = "none";

    /** 属性类型；在 {@code MoreBallsClient} 里注册到 NeoForge 的事件上 */
    public static final SelectItemModelProperty.Type<ChargedBall, String> TYPE =
            SelectItemModelProperty.Type.create(MapCodec.unit(new ChargedBall()), Codec.STRING);

    @Override
    public String get(ItemStack stack, ClientLevel level, LivingEntity entity, int seed,
                      ItemDisplayContext context) {
        Item ball = BallAmmo.chargedBallItem(stack);
        if (ball == null) {
            return NONE;
        }
        // 静默期间：组合球也按「没装球」上报 —— 见 SILENCED 的说明。
        // 其它球不受影响，照旧返回各自的 id，走它们自己的静态贴图分支。
        if (SILENCED && ball == ModItems.COMBO_BALL.get()) {
            return NONE;
        }
        return BuiltInRegistries.ITEM.getKey(ball).toString();
    }

    @Override
    public Codec<String> valueCodec() {
        return Codec.STRING;
    }

    @Override
    public SelectItemModelProperty.Type<ChargedBall, String> type() {
        return TYPE;
    }
}
