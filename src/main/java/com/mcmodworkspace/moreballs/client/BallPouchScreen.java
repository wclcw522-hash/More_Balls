package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.BallPouchMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 魔丸收纳袋的界面。
 *
 * <h2>结构：两个箱子界面叠起来</h2>
 * <p>作者指定的做法 —— 界面等于「上面一个带标题的容器、下面再来一个带标题的容器」，底下接玩家物品栏：</p>
 *
 * <pre>
 * ┌──────────────────────┐
 * │  备用区               │ ← 第一个标题区（17 像素）
 * ├──────────────────────┤
 * │ [弹药格 若干行]        │
 * ├──────────────────────┤
 * │  中转区               │ ← 第二个标题区，它同时就是两段之间的分隔
 * ├──────────────────────┤
 * │ [转运格 若干行]        │
 * ├──────────────────────┤
 * │  玩家物品栏            │
 * └──────────────────────┘
 * </pre>
 *
 * <p>因为多了第二个标题区，两段之间<b>不需要另画分隔线</b> —— 那条标题栏本身就是分界。</p>
 *
 * <h2>背景全部来自原版纹理</h2>
 * <p>贴的是原版大箱子那张 {@code textures/gui/container/generic_54.png}：
 * 标题区取纹理最顶上那 17 像素（实心）、槽位区取 v=17 起、玩家背包取 v=126 起。
 * 行数随等级变，所以按段取、每段高度按实际行数算。</p>
 *
 * <h2>用不到的格子盖灰</h2>
 * <p>每段行数按格数向上取整，末行常常用不满（如 2 级 12 格 = 第 2 行只占 3 格）。
 * 那些位置在 {@link BallPouchMenu} 里标成「不激活」，这里从右往左涂成银灰色。</p>
 */
public class BallPouchScreen extends AbstractContainerScreen<BallPouchMenu> {

    /** 原版大箱子的容器背景 */
    private static final Identifier CONTAINER_TEXTURE =
            Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");

    /** 纹理总尺寸（blit 用它归一化 u / v） */
    private static final int TEXTURE_SIZE = 256;

    /** 纹理里标题区的高度 */
    private static final int TITLE_HEIGHT = 17;

    /** 纹理里槽位区的起始 v */
    private static final int SLOTS_V = 17;

    /** 纹理里玩家背包区的起始 v 与高度 */
    private static final int PLAYER_V = 126;
    private static final int PLAYER_H = 96;

    /** 盖住未解锁格子用的银灰色（与容器边框同色系） */
    private static final int LOCKED_COLOR = 0xFFC6C6C6;

    /** 标题文字颜色 */
    private static final int TITLE_COLOR = 0xFF404040;

    /** 标题文字距左 / 距顶的位置（与 AbstractContainerScreen 一致） */
    private static final int TITLE_INSET_X = 8;
    private static final int TITLE_INSET_Y = 6;

    public BallPouchScreen(BallPouchMenu menu, Inventory inventory, Component title) {
        // 尺寸必须走构造参数 —— 26.x 里 imageWidth / imageHeight 是 final 字段
        super(menu, inventory, title, 176, menu.guiHeight());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);

        int x = this.leftPos;
        int y = this.topPos;

        // ① 第一个标题区 —— 上面显示的名字（「备用区」）由传入的 title 渲染
        blitTitleBar(graphics, x, y);
        y += TITLE_HEIGHT;

        // ② 弹药段
        int ammoH = this.menu.ammoRows() * 18;
        blitSlotRows(graphics, x, y, ammoH);
        y += ammoH;

        // ③ 第二个标题区 —— 写「中转区」。它同时就是两段的分隔，不必另外画线
        blitTitleBar(graphics, x, y);
        graphics.text(this.font,
                Component.translatable("container.more_balls.ball_pouch.transit"),
                x + TITLE_INSET_X, y + TITLE_INSET_Y, TITLE_COLOR, false);
        y += BallPouchMenu.SECTION_TITLE_HEIGHT;

        // ④ 转运段
        int transitH = this.menu.transitRows() * 18;
        blitSlotRows(graphics, x, y, transitH);
        y += transitH;

        // ⑤ 玩家背包区（纹理里位置固定）
        graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_TEXTURE,
                x, y, 0.0F, (float) PLAYER_V, this.imageWidth, PLAYER_H,
                TEXTURE_SIZE, TEXTURE_SIZE);

        // ⑥ 把未解锁的格子盖成银灰色（索引靠后的那些，也就是从右往左）
        //
        // 盖的是「槽位框」那一整块：原版槽位内容区 16×16、外面还有 1 像素边框，
        // 所以从 slot.x - 1 起盖 18×18 正好铺满，相邻槽位之间不留缝。
        for (Slot slot : this.menu.slots) {
            if (slot.isActive() || slot.x < 0) {
                continue;   // 激活的不盖；x < 0 的是玩家背包那种不显示的
            }
            int sx = x + slot.x - 1;
            int sy = this.topPos + slot.y - 1;
            graphics.fill(sx, sy, sx + 18, sy + 18, LOCKED_COLOR);
        }
    }

    /** 贴一条标题栏（纹理最顶上那 17 像素，实心区） */
    private void blitTitleBar(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_TEXTURE,
                x, y, 0.0F, 0.0F, this.imageWidth, TITLE_HEIGHT, TEXTURE_SIZE, TEXTURE_SIZE);
    }

    /** 从纹理的槽位区取 {@code height} 像素高的一段贴到 (x, y) */
    private void blitSlotRows(GuiGraphicsExtractor graphics, int x, int y, int height) {
        if (height <= 0) {
            return;
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_TEXTURE,
                x, y, 0.0F, (float) SLOTS_V, this.imageWidth, height,
                TEXTURE_SIZE, TEXTURE_SIZE);
    }
}
