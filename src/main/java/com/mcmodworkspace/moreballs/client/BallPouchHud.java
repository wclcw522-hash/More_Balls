package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.BallAmmoSelection;
import com.mcmodworkspace.moreballs.MoreBalls;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * 收纳袋的 HUD —— 副手栏上方那一个「弹药格」。
 *
 * <h2>作者指定的样子</h2>
 * <p><b>在副手栏位置的正上方，复制一个和副手栏同款的单格</b>，
 * 格子里放两样东西：<b>当前选中弹药的图标</b> + <b>收纳袋里这种球的总剩余数</b>。</p>
 *
 * <pre>
 *      ┌────┐
 *      │ 🟡3│   ← 弹药格（本 HUD，与原版副手栏同款）
 *      └────┘
 *      ┌────┐
 *      │    │   ← 原版副手栏（原版自己画，我们不插手）
 *      └────┘
 * </pre>
 *
 * <p>早先那两个东西都<b>不要了</b>：① 自己复制一份「常驻副手栏」——原版已经有副手栏，
 * 再画一个纯属重复；② 带物品名的大黑框弹药栏 —— 只要图标和数量。</p>
 *
 * <h2>怎么接进 HUD</h2>
 * <p>26.x 的 HUD 是「GUI 层」拼出来的，用 {@link RegisterGuiLayersEvent#registerAboveAll}
 * 把自己的层挂到所有原版层之上 —— 不必知道原版快捷栏那一层叫什么 id，也不用 Mixin 改 {@code Hud}。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID, value = Dist.CLIENT)
public final class BallPouchHud implements GuiLayer {

    /** 借原版副手栏的精灵来画「同一款」的单格（右侧那个，槽口朝右） */
    private static final Identifier SLOT_SPRITE =
            Identifier.withDefaultNamespace("hud/hotbar_offhand_right");

    /** 原版快捷栏尺寸 */
    private static final int HOTBAR_WIDTH = 182;
    private static final int HOTBAR_HEIGHT = 22;

    /** 副手槽离快捷栏的间距（与原版一致） */
    private static final int OFFHAND_GAP = 29;

    /** 单格精灵的尺寸 */
    private static final int SLOT_WIDTH = 29;
    private static final int SLOT_HEIGHT = 24;

    /** 弹药格与副手栏之间的间距 —— 分开一点，两块不粘在一起 */
    private static final int AMMO_SLOT_GAP = 3;

    /** 我方层的 id */
    private static final Identifier LAYER_ID =
            Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "pouch_hud");

    @SubscribeEvent
    public static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER_ID, new BallPouchHud());
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        // HUD 被隐藏时（F1）不画 —— 26.x 的开关在 Hud 上，不在 Options 里
        if (player == null || minecraft.gui.hud.isHidden()) {
            return;
        }

        // 只用弩时才显示 —— 这个格子本来就是给「用弩选弹」服务的
        if (!(player.getMainHandItem().getItem() instanceof CrossbowItem)) {
            return;
        }

        // 没在轮盘里挑过弹就没什么可显示的，格子也不留
        // （选中存的是带组件的样本球 —— 组合球靠组件区分，不能只存物品 id）
        ItemStack ammo = BallAmmoSelection.selected(player);
        if (ammo.isEmpty()) {
            return;
        }

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();

        // ⚠️⚠️ **窗口极小时直接不画**（玩家 2026-10-17 报的联机崩溃：
        //     `Scissor at 226, 504 with size 16x24 is out of bounds for render area
        //      RenderArea[x=0, y=0, width=854, height=480]`）。
        //
        //     那个坐标比渲染区还大 24，查下来不是我们这两处槽位算出来的
        //     （袋子界面 leftPos=339、这里 offhandX=314），更像是**窗口刚被缩小**时
        //     残留的坐标 —— 而 26.2 的 `RenderPass.enableScissor` 对越界**不做 clamp**，
        //     直接把整帧打崩。
        //
        //     我们改不了原版的 clamp，但可以**保证自己不递交任何会越界的几何**：
        //     窗口小到连「副手栏 + 这一格」都放不下时，就别画了 ——
        //     少一个提示格，总比整局崩掉好。
        if (screenHeight < HOTBAR_HEIGHT + SLOT_HEIGHT + AMMO_SLOT_GAP
                || screenWidth < HOTBAR_WIDTH + OFFHAND_GAP + SLOT_WIDTH) {
            return;
        }

        // 原版副手槽的位置：快捷栏左边、贴着底边
        int offhandX = screenWidth / 2 - HOTBAR_WIDTH / 2 - OFFHAND_GAP;
        int offhandTop = screenHeight - HOTBAR_HEIGHT;

        // 弹药格落在副手栏正上方（屏幕上位置的上方，互不重叠）
        int slotTop = offhandTop - SLOT_HEIGHT - AMMO_SLOT_GAP;
        renderAmmoSlot(graphics, minecraft, player, ammo, offhandX, slotTop);
    }

    /** 画那一个弹药格：同款单格 + 弹药图标 + 总数 */
    private void renderAmmoSlot(GuiGraphicsExtractor graphics, Minecraft minecraft,
                                LocalPlayer player, ItemStack ammo, int x, int y) {
        // 一格外观 —— 与副手栏同款精灵
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, x, y, SLOT_WIDTH, SLOT_HEIGHT);

        // 图标：16×16 在 29×24 的格子里居中偏上
        graphics.item(ammo, x + 7, y + 4);

        // 数量：压在图标右下角，跟原版物品格的画法一样。
        //
        // ⚠️⚠️ **优先读服务端同步过来的那个值**，而不是自己数客户端的袋子内容 ——
        //      袋子的内容存在 ItemStack 的组件里，服务端改了组件客户端不会自动知道
        //      （尤其是挂在饰品栏上的袋子：Curios 只认「栈被换过」）。
        //      自己数就会一直显示旧值，要玩家手动动一下袋子才刷新
        //      （作者 2026-10-15 反馈的「剩余弹药显示槽有延迟」）。
        //
        //      `SELECTED_AMMO_COUNT` 是带 `.sync(...)` 的 attachment ⇒ 由框架保证同步。
        //      值 `-1` 表示还没收到过同步（比如刚进游戏），那就退回本地数一次兜底。
        int synced = player.getData(
                com.mcmodworkspace.moreballs.ModAttachments.SELECTED_AMMO_COUNT.get());
        int count = synced >= 0 ? synced : BallAmmoSelection.countOf(player, ammo);
        String amount = String.valueOf(count);
        graphics.text(minecraft.font, amount,
                x + 24 - minecraft.font.width(amount), y + 13, 0xFFFFFFFF, true);
    }
}
