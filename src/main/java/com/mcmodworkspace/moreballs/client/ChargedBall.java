package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.BallAmmo;
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
