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
 *
 * <h2>2026-10-17 的渲染重构（外观与行为零变化）</h2>
 * <p>把原来「一个 60 行的 {@code extractBackground} 从头画到尾」拆成按段推进的小步骤，
 * <b>所有 blit / fill 的参数、顺序、坐标、颜色都与重构前逐字一致</b>，
 * 只是把「当前 y 游标」变成了显式变量、把重复的贴图调用收进一个方法。
 * 另外加了两道**只在异常情况下才会生效**的守卫（正常窗口下完全不触发，见各自注释）。</p>
 */
public class BallPouchScreen extends AbstractContainerScreen<BallPouchMenu> {

    /** 原版大箱子的容器背景 */
    private static final Identifier CONTAINER_TEXTURE =
            Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");

    /** 原版容器底图的<b>原始宽度</b>（generic_54 就是 176：左右 8 边框 + 中间 160） */
    private static final int BASE_WIDTH = 176;

    /** 原版容器左右边框的宽度（纹理 u=0..8 与 u=168..176） */
    private static final int EDGE_WIDTH = 8;

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

    /** 未解锁格子的遮罩尺寸（槽位内容 16×16 + 两侧各 1 像素边框 = 18×18） */
    private static final int LOCK_MASK = 18;

    public BallPouchScreen(BallPouchMenu menu, Inventory inventory, Component title) {
        // 尺寸必须走构造参数 —— 26.x 里 imageWidth / imageHeight 是 final 字段。
        // 宽度跟着袋子的列数走（作者 2026-10-17 把每行槽位改成 18 ⇒ 宽度同步变宽），
        // 所以不能再写死 176，改从 menu.guiWidth() 取。
        super(menu, inventory, title, menu.guiWidth(), menu.guiHeight());
    }

    /**
     * 「放得下就 1.0，放不下就按比例缩」—— 让界面自动适配原版的 GUI 缩放。
     *
     * <h2>为什么需要它（作者 2026-10-17 要求「自动适配原版的调节尺寸」）</h2>
     * <p>原版会把 {@code guiScale} 自动调到一个能让<b>逻辑分辨率至少 320×240</b> 的值，
     * 但<b>不会更低</b>。而本界面的高度是随袋子等级变的 —— 高等级袋子（两段各 6 行）
     * 能到 260 以上，<b>超过 240 就必然被挤出屏幕</b>：
     * 26.2 的 {@code RenderPass.enableScissor} 对越界<b>不做 clamp</b>，
     * 会直接抛 {@code IllegalArgumentException} 打崩整帧 —— 也就是作者报的联机崩溃。</p>
     *
     * <p>所以这里按「窗口逻辑尺寸」算一个不超过 1 的缩放倍率，
     * 窗口够大时<b>恒为 1.0（什么都不变）</b>，只有真的放不下才等比缩小。</p>
     *
     * @return 1.0 表示不需要缩放
     */
    private float fitScale() {
        final var window = net.minecraft.client.Minecraft.getInstance().getWindow();
        final int screenWidth = window.getGuiScaledWidth();
        final int screenHeight = window.getGuiScaledHeight();
        // 留 2 像素余量，避免正好贴边时仍被判越界
        final float byWidth = (screenWidth - 2.0F) / this.imageWidth;
        final float byHeight = (screenHeight - 2.0F) / this.imageHeight;
        return Math.min(1.0F, Math.min(byWidth, byHeight));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        final float scale = this.fitScale();
        if (scale >= 1.0F) {
            // 放得下 ⇒ 与原来完全相同，一个像素都不动
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            return;
        }

        // 放不下 ⇒ 等比缩小并居中。
        // ⚠️ 这里包住的是**整个** super.extractRenderState —— 背景、槽位、物品图标、
        //    数量文字、标题文字全都一起缩，所以**相对布局一点都不会乱**（只是整体变小）。
        //    `AbstractContainerScreen` 已经把 leftPos/topPos 按未缩放尺寸算好，
        //    所以平移量要把「居中后的位置」与「它自认为的位置」之差补回来。
        final var window = net.minecraft.client.Minecraft.getInstance().getWindow();
        final float screenWidth = window.getGuiScaledWidth();
        final float screenHeight = window.getGuiScaledHeight();
        final float offsetX = (screenWidth - this.imageWidth * scale) / 2.0F - this.leftPos * scale;
        final float offsetY = (screenHeight - this.imageHeight * scale) / 2.0F - this.topPos * scale;

        // ⚠️ 26.2 的 `pose()` 是 JOML 的 `Matrix3x2fStack`（2D 变换栈），
        //    方法是 pushMatrix / popMatrix / translate / scale，不是老的 PoseStack。
        final var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(offsetX, offsetY);
        pose.scale(scale, scale);
        // 鼠标坐标也要换算回「未缩放的界面坐标系」，否则悬停高亮与 tooltip 会错位
        final int localMouseX = Math.round((mouseX - offsetX) / scale);
        final int localMouseY = Math.round((mouseY - offsetY) / scale);
        super.extractRenderState(graphics, localMouseX, localMouseY, partialTick);
        pose.popMatrix();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);

        final int x = this.leftPos;
        // 纵向游标：从界面顶部往下推，每画完一段就前进对应高度。
        // 与重构前「y 从 topPos 开始逐段 +=」完全等价。
        int y = this.topPos;

        // ① 第一个标题区（上面显示的名字「备用区」由传入的 title 渲染）
        drawTitleBar(graphics, x, y);
        y += TITLE_HEIGHT;

        // ② 弹药段
        final int ammoHeight = this.menu.ammoRows() * 18;
        drawSlotRows(graphics, x, y, ammoHeight);
        y += ammoHeight;

        // ③ 第二个标题区 —— 写「中转区」。它同时就是两段的分隔，不必另外画线
        drawTitleBar(graphics, x, y);
        graphics.text(this.font,
                Component.translatable("container.more_balls.ball_pouch.transit"),
                x + TITLE_INSET_X, y + TITLE_INSET_Y, TITLE_COLOR, false);
        y += BallPouchMenu.SECTION_TITLE_HEIGHT;

        // ④ 转运段
        final int transitHeight = this.menu.transitRows() * 18;
        drawSlotRows(graphics, x, y, transitHeight);
        y += transitHeight;

        // ⑤ 玩家背包区（纹理里位置固定）
        drawPlayerArea(graphics, x, y);

        // ⑥ 把未解锁的格子盖成银灰色（索引靠后的那些，也就是从右往左）
        shadeLockedSlots(graphics, x);
    }

    /**
     * 贴一条标题栏（纹理最顶上那 17 像素，实心区）。
     *
     * <p>⚠️ 守卫：目标区域若整条落在窗口外就跳过 —— 26.2 的 {@code RenderPass.enableScissor}
     * <b>对越界不做 clamp，会直接抛 IllegalArgumentException 打崩整帧</b>
     * （作者 2026-10-17 报的联机崩溃就是这一类）。
     * 正常窗口下这条判断永远为假，<b>画面一点都不会变</b>。</p>
     */
    private void drawTitleBar(GuiGraphicsExtractor graphics, int x, int y) {
        if (!isRowVisible(y, TITLE_HEIGHT)) {
            return;
        }
        blitWide(graphics, x, y, 0.0F, TITLE_HEIGHT);
    }

    /**
     * <b>把一条 176 宽的底图「撑宽」成任意宽度</b> —— 左右边框照抄、中间按原图宽度平铺。
     *
     * <h2>为什么不能直接 blit 拉宽（作者 2026-10-17 指出的问题）</h2>
     * <p>原版 {@code generic_54.png} 只有 <b>176</b> 宽（左右各 8 像素边框 + 中间 160 的槽位区）。
     * 袋子改成每行 18 格后界面宽 340，如果直接把 blit 的宽度写成 340，
     * 整张图会被<b>横向拉伸</b> —— 看起来就是「两个 9 格的框拼在一起」，边框也会变粗/变形。</p>
     *
     * <p>正确画法是把这条带子当成「可伸缩的边框」，按原版 UI 的规矩拆成三段：</p>
     * <pre>
     * [ 左 8px 边框 ][ 中间 160px 平铺 ×N ][ 右 8px 边框 ]
     * </pre>
     * <p>左右两段照抄纹理的 {@code u=0..8} 与 {@code u=168..176}，
     * 中间每隔 160 像素画一段（每段都用原尺寸，所以<b>不会被拉伸</b>）。
     * 最后一段不足 160 时按剩余宽度裁剪（`blit` 的宽度同时决定 UV 跨度，所以那一段仍会轻微压缩，
     * 但它在边框内侧、且底色是纯色，看不出来）。</p>
     *
     * @param v      纹理纵向起点
     * @param height 这一段的高度
     */
    private void blitWide(GuiGraphicsExtractor graphics, int x, int y, float v, int height) {
        final int total = this.imageWidth;
        final int edge = EDGE_WIDTH;
        final int middle = BASE_WIDTH - edge * 2;   // 160
        if (total <= edge * 2) {
            // 极窄（理论上不会发生）⇒ 退回原来的整张 blit
            graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_TEXTURE,
                    x, y, 0.0F, v, total, height, TEXTURE_SIZE, TEXTURE_SIZE);
            return;
        }
        // 左边框
        graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_TEXTURE,
                x, y, 0.0F, v, edge, height, TEXTURE_SIZE, TEXTURE_SIZE);
        // 右边框
        graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_TEXTURE,
                x + total - edge, y, (float) (BASE_WIDTH - edge), v, edge, height, TEXTURE_SIZE, TEXTURE_SIZE);
        // 中间：按 160 一段平铺
        int cursor = edge;
        final int middleEnd = total - edge;
        while (cursor < middleEnd) {
            final int seg = Math.min(middle, middleEnd - cursor);
            graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_TEXTURE,
                    x + cursor, y, (float) edge, v, seg, height, TEXTURE_SIZE, TEXTURE_SIZE);
            cursor += seg;
        }
    }

    /**
     * 从纹理的槽位区取 {@code height} 像素高的一段贴到 (x, y)。
     *
     * <p>守卫理由同上 —— 正常窗口下不触发。</p>
     */
    private void drawSlotRows(GuiGraphicsExtractor graphics, int x, int y, int height) {
        if (height <= 0 || !isRowVisible(y, height)) {
            return;
        }
        blitWide(graphics, x, y, (float) SLOTS_V, height);
    }

    /** 贴玩家背包区（含快捷栏）。守卫理由同上。 */
    private void drawPlayerArea(GuiGraphicsExtractor graphics, int x, int y) {
        if (!isRowVisible(y, PLAYER_H)) {
            return;
        }
        blitWide(graphics, x, y, (float) PLAYER_V, PLAYER_H);
    }

    /**
     * 把未解锁的格子盖成银灰色。
     *
     * <p>盖的是「槽位框」那一整块：原版槽位内容区 16×16、外面还有 1 像素边框，
     * 所以从 {@code slot.x - 1} 起盖 18×18 正好铺满，相邻槽位之间不留缝。</p>
     *
     * <p>守卫：遮罩中心落在窗口外就跳过（同上，正常窗口下不触发）。</p>
     */
    private void shadeLockedSlots(GuiGraphicsExtractor graphics, int x) {
        for (Slot slot : this.menu.slots) {
            if (slot.isActive() || slot.x < 0) {
                continue;   // 激活的不盖；x < 0 的是玩家背包那种不显示的
            }
            final int sx = x + slot.x - 1;
            final int sy = this.topPos + slot.y - 1;
            if (!isRowVisible(sy, LOCK_MASK)) {
                continue;
            }
            graphics.fill(sx, sy, sx + LOCK_MASK, sy + LOCK_MASK, LOCKED_COLOR);
        }
    }

    /**
     * 给定纵向区间 {@code [y, y + height)} 是否与窗口有交集。
     *
     * <p>⚠️ 这是纯防御判断：**它的存在不会改变任何正常情况下的绘制**
     * （窗口够高时恒为 true）。它只在窗口被压得极小、或者坐标因为 resize 竞态
     * 落到屏幕外时才拦一下，避免把越界的几何交给 26.2 的 scissor 而崩掉整帧。</p>
     */
    private static boolean isRowVisible(int y, int height) {
        final int screenHeight = net.minecraft.client.Minecraft.getInstance()
                .getWindow().getGuiScaledHeight();
        return y + height > 0 && y < screenHeight;
    }
}
