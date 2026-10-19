package com.mcmodworkspace.moreballs;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * <b>切石机的材料格只收「满耐久球」与「瑕疵球」</b>（作者 2026-10-16 要求）。
 *
 * <h2>它解决什么</h2>
 * <p>切割门槛本来只是「点产物时挡住」（耐久 &lt; 80% 就切不动，见 1.1.1.9）。
 * 作者觉得更好的做法是<b>干脆放不进去</b> —— 放进去再点不动，玩家会以为卡了。</p>
 *
 * <h2>怎么做到「放不进去」而不用改原版</h2>
 * <p>原版 {@link StonecutterMenu} 的输入槽 {@code mayPlace} 是开放的，而且进槽的路径有很多
 * （拖拽、Shift、数字键交换、双击聚合……），逐个拦既不现实也容易漏。
 * 所以这里换个角度：<b>每刻检查一次材料格，不合格的就原样退回去</b> ——
 * 效果上就是「放不进去」（手上立刻多回来），而实现只有一处。</p>
 *
 * <p>退回的去向与别处一致：<b>先塞背包，塞不下掉在脚边</b>，绝不静默销毁。</p>
 *
 * <h2>谁会被退</h2>
 * <p>只针对<b>球与球堆</b>（{@link BallAmmo#isBall} / {@link BallClusterItem#isCluster}）——
 * 别的物品（原版石头、木头那些）不归本模组管，一律不碰。</p>
 *
 * <p>判据统一走 {@link BallItem#canBeCut}：球堆摊开看<b>壳里那颗球</b>
 * （壳里的球都是同一档位，所以「看最低那颗」= 看整堆）。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallCuttingGate {

    private BallCuttingGate() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;   // 掉落物与槽位都是服务端行为
        }
        if (!(player.containerMenu instanceof StonecutterMenu menu)) {
            return;   // 只有开着切石机才管
        }

        Slot inputSlot = menu.getSlot(StonecutterMenu.INPUT_SLOT);
        ItemStack stack = inputSlot.getItem();
        if (stack.isEmpty()) {
            return;
        }

        // 只认「球 / 球堆」；别的物品一律不碰
        ItemStack probe = BallClusterItem.asBall(stack);
        if (probe.isEmpty() || !BallAmmo.isBall(probe)) {
            return;
        }
        if (BallItem.canBeCut(probe)) {
            return;   // 满耐久 / 瑕疵（≥80%）—— 合格，放行
        }

        // 不合格 → 原样退回（先背包，再掉脚边）
        ItemStack back = stack.copy();
        inputSlot.set(ItemStack.EMPTY);
        if (!player.getInventory().add(back)) {
            player.drop(back, false);
        }
        player.sendOverlayMessage(Component.translatable("message.rotation_craft.cut_gate"));
    }
}
