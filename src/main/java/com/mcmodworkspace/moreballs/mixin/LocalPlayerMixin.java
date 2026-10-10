package com.mcmodworkspace.moreballs.mixin;

import com.mcmodworkspace.moreballs.ModEnchantments;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【诸葛连弩】的「减少装填移动速度惩罚」—— <b>客户端专用</b>。
 *
 * <h2>原版的「装填减速」到底在哪</h2>
 * <p>它不是硬编码的，而是走「使用中那件物品」的 <b>{@code minecraft:use_effects} 数据组件</b>：</p>
 *
 * <pre>
 * LocalPlayer.modifyInput()
 *   → if (isUsingItem()) newInput = newInput.scale(itemUseSpeedMultiplier())
 *        → itemUseSpeedMultiplier()
 *             = useItem.getOrDefault(USE_EFFECTS, UseEffects.DEFAULT).speedMultiplier()
 * </pre>
 *
 * <p>而 {@code UseEffects.DEFAULT.speedMultiplier()} 是 <b>0.2</b>，
 * 原版弩与弓都<b>没有</b>覆写这个组件 —— 所以装填期间移动速度只有 20%，
 * 也就是「80% 的移动速度惩罚」。</p>
 *
 * <h2>为什么必须用 Mixin</h2>
 * <p>这个倍率是从 <b>ItemStack 的数据组件</b>上读的，而附魔的效果组件里
 * <b>没有</b>「改 use_effects」这一类 —— {@code EnchantmentEffectComponents} 全部枚举过，
 * 弩相关的只有 {@code CROSSBOW_CHARGE_TIME} 与 {@code CROSSBOW_CHARGING_SOUNDS}。
 * 想按「这件武器附了什么魔」去改它，没有官方扩展点。</p>
 *
 * <p>（另一条思路是每刻往手上的弩写 {@code use_effects} 组件 —— 但那会持续触发
 * 物品同步、且服务端改组件对客户端本地输入不一定及时，代价远大于收益。）</p>
 *
 * <h2>遵守的约定</h2>
 * <ul>
 *   <li>只做 <b>RETURN 注入 + 改返回值</b>，不碰方法内部指令</li>
 *   <li>带 {@code require = 0}：原版将来改了这个方法，这里是<b>安静跳过</b>，
 *       退化成「没有减速减免」，而不是崩游戏</li>
 *   <li>不使用 {@code @Redirect} / {@code @Overwrite}</li>
 *   <li>放在 {@code more_balls.mixins.json} 的 <b>{@code client}</b> 段 ——
 *       {@code LocalPlayer} 是客户端类，专用服务器上不能加载这个 mixin</li>
 *   <li><b>只改有【诸葛连弩】的弩</b>；没附魔的武器返回值原样透传，原版行为不受影响</li>
 * </ul>
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    @Inject(
            method = "itemUseSpeedMultiplier()F",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private void moreBalls$repeaterReloadSpeed(CallbackInfoReturnable<Float> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        ItemStack useItem = self.getUseItem();
        if (useItem.isEmpty() || !(useItem.getItem() instanceof CrossbowItem)) {
            return;
        }
        int level = ModEnchantments.repeaterCrossbowLevel(self.level(), useItem);
        if (level <= 0) {
            return;
        }
        // 以原版给出的值为基准往上抬 —— 将来原版改了 0.2 这个数，这里也跟着变，不会写死
        cir.setReturnValue(ModEnchantments.repeaterUseSpeedMultiplier(cir.getReturnValueF(), level));
    }
}
