package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.MoreBalls;
import com.mcmodworkspace.moreballs.network.TidyBallsPayload;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * <b>背包界面角落那个「一键整理」小按钮</b>（作者 2026-10-15 要求）。
 *
 * <h2>它解决什么问题</h2>
 * <p>球堆原本只在**回收**时生成（球靠【空气动力球】飞回来的那一刻）。要验证它就得先打一场球、
 * 还得给弩附魔 —— 门槛太高，而且一旦回收链路哪里没走到，玩家只能靠肉眼在背包里翻，
 * 分不清是「机制本身不行」还是「哪一步没接上」。</p>
 *
 * <p>所以给一个手动入口：<b>点一下就把背包里的散球按档收成球堆</b>。
 * 不依赖任何附魔，装好就能试。</p>
 *
 * <h2>为什么用事件而不是 mixin</h2>
 * <p>NeoForge 的 {@code ScreenEvent.Init.Post} 正好是「某个界面刚初始化完、可以往里加控件」
 * 的时机 —— 不用去 mixin 原版的背包界面，也就不会有版本间的匹配风险
 * （工作区兼容性规范：能用官方事件就别用 mixin）。</p>
 *
 * <h2>按钮的尺寸与外观</h2>
 * <p>作者要求「很小」，所以用 12×12 而不是原版默认的 20 高。外观走**原版按钮精灵**
 * （{@code widget/button}，由 {@link Button} 自己画，不另配贴图）——
 * 这样它看起来就是原版 UI 的一部分，不突兀。</p>
 *
 * <p>位置贴背包面板左侧外沿；如果面板已经贴到屏幕左边（放不下），就退到面板内左上角，
 * 免得按钮跑到屏幕外面去。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID, value = Dist.CLIENT)
public final class BallTidyButton {

    /** 按钮边长 —— 作者要「很小」，原版默认是 20 高 */
    private static final int SIZE = 12;

    /** 按钮与背包面板之间的空隙 */
    private static final int GAP = 2;

    private BallTidyButton() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;   // 只管带容器面板的界面（背包 / 创造背包 / 各种箱子）
        }

        // 贴在面板左侧外沿；面板贴到屏幕左边缘时退进面板内
        int x = containerScreen.getGuiLeft() - SIZE - GAP;
        if (x < 0) {
            x = containerScreen.getGuiLeft() + GAP;
        }
        int y = containerScreen.getGuiTop() + GAP;

        Button button = Button.builder(
                        Component.translatable("button.more_balls.tidy"),
                        pressed -> ClientPacketDistributor.sendToServer(TidyBallsPayload.INSTANCE))
                .bounds(x, y, SIZE, SIZE)
                .build();

        event.addListener(button);
    }
}
