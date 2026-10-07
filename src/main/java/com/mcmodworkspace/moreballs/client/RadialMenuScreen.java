package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.BallAmmoSelection;
import com.mcmodworkspace.moreballs.BallTooltip;
import com.mcmodworkspace.moreballs.network.SelectAmmoPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 长按 R 展开的<b>径向菜单</b> —— 用来从收纳袋里挑一种弹药。
 *
 * <h2>布局（作者指定）</h2>
 * <pre>
 *              ● ●
 *          ●         ●
 *        ●    ┌───┐    ●
 *        ●    │◀ │ ▶│    ●      ← 中心圆：左半 = 上一页、右半 = 下一页
 *        ●    └───┘    ●
 *          ●         ●
 *              ● ●
 * </pre>
 *
 * <ul>
 *   <li><b>一页 8 个弹药位</b>，沿圆周均匀铺开（每 45° 一个）</li>
 *   <li><b>中心留一个圆</b>，圆里用一条竖直中线分成<b>左右两个半圆</b> ——
 *       左半是「上一页」、右半是「下一页」</li>
 *   <li>首页 / 末页对应的那半边画灰、点不动</li>
 * </ul>
 *
 * <h2>每个位置从内到外三层（作者指定）</h2>
 * <ol>
 *   <li><b>靠圆心</b>：球图标（右下角带剩余数量）+ 名称</li>
 *   <li><b>再往外</b>：基础属性（伤害）</li>
 *   <li><b>最外</b>：特性 —— <b>只显示词条名</b>（{@code 【坚固3】} 这种中括号部分）</li>
 * </ol>
 * <p>每层文字都有宽度上限（按该半径处的弧距留余量），太长就截断 —— 一圈 8 份文字，
 * 不限宽必然互相压。</p>
 *
 * <h2>只占一个圆</h2>
 * <p>内容全在半径 {@link #DISC_RADIUS} 的底盘圆里；圆外只轻轻压暗一层，保留正常游戏画面。</p>
 *
 * <h2>交互（作者指定）</h2>
 * <ul>
 *   <li><b>按住 R 显示、松开就关</b> —— 松手时指针停在哪个弹药上就顺手选中它</li>
 *   <li>左键点弹药即选中并关闭；点中心圆只翻页、不关菜单</li>
 * </ul>
 */
public class RadialMenuScreen extends Screen {

    /** 一页的弹药位数（作者指定：8 个） */
    private static final int AMMO_PER_PAGE = 8;

    /** 中心圆的半径 —— 里面是左右两个翻页半圆 */
    private static final int CENTER_RADIUS = 32;

    /** 底盘圆的半径（轮盘只占这一个圆） */
    private static final int DISC_RADIUS = 112;

    /** 图标距圆心的半径 */
    private static final float ICON_RADIUS = 46.0F;

    /** 文字块（整体）中心距圆心的半径 —— 紧贴图标外侧 */
    private static final float TEXT_RADIUS = 84.0F;

    /**
     * 文字块里每行最多占的<b>显示宽度</b> —— 作者指定「每行 10 字」，
     * 也就是 <b>10 个汉字 = 20 个半角位</b>。
     *
     * <p>汉字按 2 位算、数字与字母按 1 位算，混排时才不会被误判：
     * {@code 【基础伤害】3} 是 13 位、{@code 【坚固3】【点金】} 是 17 位，都在预算内。</p>
     */
    private static final int LINE_WIDTH = 20;

    /**
     * 文字块的字号 —— 三行统一用一个字号，整块才能当一坨看。
     *
     * <p>定 0.6 是算过的：一行最多 {@link #LINE_WIDTH} 位（10 个汉字），
     * 按 0.6 缩放约 {@code 10 × 9 × 0.6 = 54} 像素，再算上混排里的数字约 61 像素；
     * 而半径 {@link #TEXT_RADIUS} 处相邻两格的弧距是 {@code 2π × 84 / 8 ≈ 66} 像素
     * —— 塞得下且留了余量。再大一号就会跟邻格压上。</p>
     */
    private static final float TEXT_SCALE = 0.6F;

    /** 三行之间的行距 */
    private static final int LINE_SPACING = 8;

    /** 每个位置的点击判定半径 */
    private static final float HIT_RADIUS = 20.0F;

    /** 圆外：只轻轻压暗，正常画面还看得见 */
    private static final int COLOR_BG = 0x38000000;

    private static final int COLOR_RING = 0xFF5A5A5A;
    private static final int COLOR_HIGHLIGHT = 0xFFFFD166;
    private static final int COLOR_TEXT = 0xFFE8E8E8;
    private static final int COLOR_DISABLED = 0xFF6A6A6A;

    /** 属性与特性用的次级灰 —— 比名称暗一档，层次分明 */
    private static final int COLOR_SUB = 0xFFAFAFAF;

    /** 当前页码（从 0 开始） */
    private int page;

    /** 本页要展示的弹药（已按数量从多到少排好） */
    private final List<ItemStack> kinds;

    public RadialMenuScreen() {
        super(Component.translatable("gui.more_balls.radial_menu"));
        Minecraft minecraft = Minecraft.getInstance();
        this.kinds = minecraft.player == null
                ? new ArrayList<>()
                : BallAmmoSelection.sortedKinds(minecraft.player);
    }

    /** 总页数（至少一页，哪怕一个弹药都没有） */
    private int pageCount() {
        return Math.max(1, (this.kinds.size() + AMMO_PER_PAGE - 1) / AMMO_PER_PAGE);
    }

    /** 这一页的第一个弹药在总列表里的下标 */
    private int pageOffset() {
        return this.page * AMMO_PER_PAGE;
    }

    /** 第 slotIndex 个位置的角度（正上方起，顺时针每 45°） */
    private float angleFor(int slotIndex) {
        return (float) (Math.PI / 2.0D - slotIndex * (2.0D * Math.PI / AMMO_PER_PAGE));
    }

    /** 位置在屏幕上的坐标 */
    private int[] pointAt(float angle, float radius, int centerX, int centerY) {
        return new int[]{
                centerX + Math.round((float) Math.cos(angle) * radius),
                centerY - Math.round((float) Math.sin(angle) * radius)
        };
    }

    // ===== 松手关闭 =====

    /**
     * 每刻看一眼 R 键：<b>一松开就把轮盘收掉</b>（作者指定的交互）。
     *
     * <p>用 GLFW 原始键状态判断 —— 界面打开时 {@code KeyMapping.isDown()} 会变成 false，
     * 拿它当判据会让轮盘一开就被自己关掉。</p>
     */
    @Override
    public void tick() {
        super.tick();
        if (!radialHeld()) {
            confirmAndClose();
        }
    }

    /** R 键现在物理上还按着吗 */
    private boolean radialHeld() {
        Minecraft minecraft = Minecraft.getInstance();
        int code = BallPouchKeys.RADIAL_MENU.getKey().getValue();
        return InputConstants.isKeyDown(minecraft.getWindow(), code);
    }

    /** 松手时的收尾：指针落在哪个弹药上就选中它，然后关闭菜单 */
    public void confirmAndClose() {
        int[] mouse = currentMousePos();
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        // 指针在中心圆里就不算选弹药（那是翻页区），只关掉
        if (!isInsideCircle(mouse[0], mouse[1], centerX, centerY, CENTER_RADIUS)) {
            int slot = hoveredSlot(mouse[0], mouse[1]);
            if (slot >= 0) {
                int ammoIndex = pageOffset() + slot;
                if (ammoIndex < this.kinds.size()) {
                    select(this.kinds.get(ammoIndex));
                }
            }
        }
        this.onClose();
    }

    /**
     * 把选中的弹药发给服务端。
     *
     * <p>发的是<b>整颗样本球</b>而不是物品 id —— 组合球全都是同一个 id，
     * 只有组件不同；传 id 的话服务端收到的是裸 `combo_ball`，
     * 自动装填就分不清选的是哪一种了。</p>
     */
    private void select(ItemStack chosen) {
        ClientPacketDistributor.sendToServer(new SelectAmmoPayload(chosen));
    }

    /** 当前鼠标的 GUI 坐标（屏幕像素 → GUI 缩放） */
    private int[] currentMousePos() {
        Minecraft minecraft = Minecraft.getInstance();
        double scaleX = (double) this.width / minecraft.getWindow().getScreenWidth();
        double scaleY = (double) this.height / minecraft.getWindow().getScreenHeight();
        return new int[]{
                (int) (minecraft.mouseHandler.xpos() * scaleX),
                (int) (minecraft.mouseHandler.ypos() * scaleY)
        };
    }

    // ===== 绘制 =====

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // 圆外：只轻轻压暗，正常画面还看得见
        graphics.fill(0, 0, this.width, this.height, COLOR_BG);

        // ① 8 个弹药位
        for (int i = 0; i < AMMO_PER_PAGE; i++) {
            int flatIndex = pageOffset() + i;
            if (flatIndex >= this.kinds.size()) {
                break;
            }
            renderAmmoSlot(graphics, centerX, centerY, i, this.kinds.get(flatIndex), mouseX, mouseY);
        }

        // ② 中心圆：左右两个半圆就是翻页键
        renderCenter(graphics, centerX, centerY, mouseX, mouseY);

        // ③ 页码提示（压在底盘内底部）
        Component hint = Component.translatable("gui.more_balls.page",
                this.page + 1, pageCount());
        graphics.text(this.font, hint, centerX - this.font.width(hint) / 2,
                centerY + DISC_RADIUS - 14, COLOR_TEXT, true);
    }

    /**
     * 中心区 —— <b>只画一条竖直分隔线</b>，加上左右两个翻页键的文字。
     *
     * <p>作者指定：那些自己拼出来的圆底、半圆底全都不要，<b>只留分割线</b>。
     * 所以翻页区不再有底色块，可用与不可用靠文字颜色区分、悬停靠变金色。</p>
     */
    private void renderCenter(GuiGraphicsExtractor graphics, int centerX, int centerY,
                              int mouseX, int mouseY) {
        boolean canPrev = this.page > 0;
        boolean canNext = this.page < pageCount() - 1;

        boolean overCenter = isInsideCircle(mouseX, mouseY, centerX, centerY, CENTER_RADIUS);
        boolean overLeft = overCenter && mouseX < centerX;
        boolean overRight = overCenter && mouseX >= centerX;

        // 竖直分隔线 —— 把中心区划成左右两半，也就是两个翻页键的分界
        graphics.fill(centerX, centerY - CENTER_RADIUS,
                centerX + 1, centerY + CENTER_RADIUS, COLOR_RING);

        // 两边的文字 —— 各放在自己那半边
        Component prev = Component.translatable("gui.more_balls.prev_page");
        Component next = Component.translatable("gui.more_balls.next_page");
        drawCentered(graphics, prev, centerX - CENTER_RADIUS / 2, centerY - 4,
                canPrev ? (overLeft ? COLOR_HIGHLIGHT : COLOR_TEXT) : COLOR_DISABLED, 0.6F);
        drawCentered(graphics, next, centerX + CENTER_RADIUS / 2, centerY - 4,
                canNext ? (overRight ? COLOR_HIGHLIGHT : COLOR_TEXT) : COLOR_DISABLED, 0.6F);
    }

    /**
     * 画一个弹药位 —— 图标在里、<b>文字整块</b>在外。
     *
     * <p>文字不再是「按半径散开的三层」，而是<b>一坨三行的块</b>（名称 / 基础属性 / 特性），
     * 竖着排在图标外侧、整体居中。这样三行之间、以及与邻格之间都不会互相压。</p>
     */
    private void renderAmmoSlot(GuiGraphicsExtractor graphics, int centerX, int centerY,
                                int slotIndex, ItemStack ammo, int mouseX, int mouseY) {
        float angle = angleFor(slotIndex);
        int[] icon = pointAt(angle, ICON_RADIUS, centerX, centerY);
        boolean hovered = isHovered(icon, mouseX, mouseY);
        int color = hovered ? COLOR_HIGHLIGHT : COLOR_TEXT;

        // 悬停底色
        graphics.fill(icon[0] - 13, icon[1] - 13, icon[0] + 13, icon[1] + 13,
                hovered ? 0x60FFD166 : 0x40202020);

        // ① 图标（靠圆心），数量压在它右下角 —— 跟原版物品格的画法一致
        graphics.item(ammo, icon[0] - 8, icon[1] - 8);
        Minecraft minecraft = Minecraft.getInstance();
        int count = minecraft.player == null ? 0
                : BallAmmoSelection.countOf(minecraft.player, ammo);
        String amount = String.valueOf(count);
        graphics.text(this.font, amount, icon[0] + 8 - this.font.width(amount), icon[1] + 5,
                0xFFFFFFFF, true);

        // ② 三行文字当作一整块，整体居中排在图标外侧
        List<String> lines = describe(ammo);
        int[] block = pointAt(angle, TEXT_RADIUS, centerX, centerY);
        int top = block[1] - (lines.size() - 1) * LINE_SPACING / 2;
        for (int i = 0; i < lines.size(); i++) {
            // 第一行是名字，给它亮一档；后面两行用次级灰
            int lineColor = i == 0 ? color : (hovered ? COLOR_HIGHLIGHT : COLOR_SUB);
            drawCentered(graphics, Component.literal(lines.get(i)),
                    block[0], top + i * LINE_SPACING, lineColor, TEXT_SCALE);
        }
    }

    /**
     * 把一个弹药的说明整理成<b>最多三行</b>：名称 / 基础属性 / 特性。
     * 每行的宽度上限是 {@link #LINE_WIDTH}（10 个汉字宽）。空的那几行直接省掉，
     * 整块按实际行数居中。
     */
    private List<String> describe(ItemStack ammo) {
        List<String> lines = new ArrayList<>();
        lines.add(limit(ammo.getHoverName().getString()));

        String attrs = null;
        StringBuilder traits = new StringBuilder();
        for (BallTooltip.Entry entry : BallTooltip.entries(ammo, false)) {
            if (entry.kind() == BallTooltip.Kind.CHARGE) {
                continue;   // 蓄力在轮盘里不显示 —— 这里的球都是拿弩打出去的
            }
            if (entry.kind() == BallTooltip.Kind.BASIC) {
                if (attrs == null) {
                    attrs = entry.text().getString();        // 基础属性用完整词条（含数值）
                }
            } else {
                if (traits.length() > 0) {
                    traits.append(' ');
                }
                traits.append(BallTooltip.nameOf(entry.text()));   // 特性只留【…】那一段
            }
        }

        if (attrs != null) {
            lines.add(limit(attrs));
        }
        if (traits.length() > 0) {
            lines.add(limit(traits.toString()));
        }
        return lines;
    }

    /** 按显示宽度截断到 {@link #LINE_WIDTH}（汉字算 2、半角算 1），超出就补两个点 */
    private static String limit(String text) {
        if (displayWidth(text) <= LINE_WIDTH) {
            return text;
        }
        int budget = LINE_WIDTH - 2;   // 末尾的「..」也要占位
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (char c : text.toCharArray()) {
            int width = charWidth(c);
            if (used + width > budget) {
                break;
            }
            sb.append(c);
            used += width;
        }
        return sb + "..";
    }

    /** 一行文本占多少显示宽度（汉字 2、半角 1） */
    private static int displayWidth(String text) {
        int width = 0;
        for (char c : text.toCharArray()) {
            width += charWidth(c);
        }
        return width;
    }

    /**
     * 一个字符占几个显示宽度：全角（汉字、CJK 标点、全角符号）算 2，其余算 1。
     *
     * <p>按 Unicode 的 East Asian Width 判定，够用又不依赖字体度量 ——
     * 轮盘上只需要「别超出邻格的弧距」，不需要像素级精确。</p>
     */
    private static int charWidth(char c) {
        boolean fullWidth = c >= 0x1100 && (
                c <= 0x115F                                   // 朝鲜文字母
                        || c == 0x2329 || c == 0x232A         // 〈 〉
                        || (c >= 0x2E80 && c <= 0xA4CF)       // CJK 部首 ~ 彝文（含汉字、【】）
                        || (c >= 0xAC00 && c <= 0xD7A3)       // 朝鲜文音节
                        || (c >= 0xF900 && c <= 0xFAFF)       // CJK 兼容汉字
                        || (c >= 0xFE30 && c <= 0xFE6F)       // CJK 兼容形式
                        || (c >= 0xFF00 && c <= 0xFF60)       // 全角形式
                        || (c >= 0xFFE0 && c <= 0xFFE6));     // 全角符号
        return fullWidth ? 2 : 1;
    }

    /** 以 (x, y) 为中心画一行缩放过的文字 */
    private void drawCentered(GuiGraphicsExtractor graphics, Component text,
                              int x, int y, int color, float scale) {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);
        graphics.text(this.font, text, Math.round(-this.font.width(text) / 2.0F), 0, color, true);
        pose.popMatrix();
    }

    /** 点是不是落在某个圆里 */
    private boolean isInsideCircle(int px, int py, int centerX, int centerY, float radius) {
        float dx = px - centerX;
        float dy = py - centerY;
        return dx * dx + dy * dy <= radius * radius;
    }

    /** 鼠标是不是落在某个弹药位附近 */
    private boolean isHovered(int[] point, int mouseX, int mouseY) {
        float dx = mouseX - point[0];
        float dy = mouseY - point[1];
        return dx * dx + dy * dy <= HIT_RADIUS * HIT_RADIUS;
    }

    /** 找出鼠标落在哪个弹药位上；没有则返回 -1 */
    private int hoveredSlot(int mouseX, int mouseY) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        for (int i = 0; i < AMMO_PER_PAGE; i++) {
            int[] icon = pointAt(angleFor(i), ICON_RADIUS, centerX, centerY);
            if (isHovered(icon, mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    // ===== 鼠标（点选仍然可用，与「松手取当前指向」并存） =====

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x();
        int my = (int) event.y();
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // 中心圆：左半上一页、右半下一页（翻页不关菜单）
        if (isInsideCircle(mx, my, centerX, centerY, CENTER_RADIUS)) {
            if (mx < centerX) {
                if (this.page > 0) {
                    this.page--;
                }
            } else if (this.page < pageCount() - 1) {
                this.page++;
            }
            return true;
        }

        int slot = hoveredSlot(mx, my);
        if (slot < 0) {
            return super.mouseClicked(event, doubleClick);
        }

        int ammoIndex = pageOffset() + slot;
        if (ammoIndex >= 0 && ammoIndex < this.kinds.size()) {
            select(this.kinds.get(ammoIndex));
            this.onClose();   // 选中就关
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;   // 开着轮盘时游戏继续跑
    }
}
