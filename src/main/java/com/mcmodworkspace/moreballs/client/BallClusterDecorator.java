package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.BallClusterContents;
import com.mcmodworkspace.moreballs.BallClusterItem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * <b>球堆图标角落那个数量。</b>
 *
 * <h2>为什么需要自己画</h2>
 * <p>原版只给「真的可堆叠」的物品画堆叠数字 —— 它读的是 {@code ItemStack#getCount()}。
 * 而球堆是 {@code stacksTo(1)} 的（壳内部虽然装着一批球，但那是数据组件，不是物品堆的数量），
 * 于是原版<b>不会</b>给它画任何数字，那一格看起来就是个孤零零的壳。</p>
 *
 * <h2>数字从哪来</h2>
 * <p>{@link BallClusterContents#size()} —— 也就是球列的长度，<b>现算的</b>。
 * 所以不存在「取出一颗之后忘了把数字改小」这种失同步的可能：数字本身就是内容。</p>
 *
 * <h2>为什么坐标要照抄原版</h2>
 * <p>见 {@code GuiGraphicsExtractor#itemCount}（26.2 源码 L894-899）：
 * <pre>{@code
 * this.text(font, amount, x + 19 - 2 - font.width(amount), y + 6 + 3, -1, true);
 * }</pre>
 * 这里逐字照搬 —— 右对齐、白色（{@code -1}）、带阴影。
 * 于是球堆角落的数字和旁边真·可堆叠物品的数字<b>落在完全相同的像素上</b>，
 * 玩家看不出区别。</p>
 *
 * <h2>调用时机</h2>
 * <p>NeoForge 的 {@code IItemDecorator} 在 {@code GuiGraphicsExtractor#itemDecorations}
 * 里、<b>原版画完数量和耐久条之后</b>被调用（见该方法的最后一行），
 * 所以不会被原版的绘制盖掉。</p>
 */
public final class BallClusterDecorator implements IItemDecorator {

    /**
     * 装饰器的裁剪框尺寸 —— 与 26.2 的 {@code itemDecorations} 一致（16 宽 × 24 高）。
     *
     * <p>⚠️ 这个尺寸**必须**和原版一致：崩溃报告里那句
     * {@code Scissor ... size 16x24 ... is out of bounds} 就是这个框。
     * 越界判断用它，才能挡在真正出事的位置之前。</p>
     */
    private static final int DECORATION_WIDTH = 16;
    private static final int DECORATION_HEIGHT = 24;

    /** 无状态，全局一个就够 */
    public static final BallClusterDecorator INSTANCE = new BallClusterDecorator();

    private BallClusterDecorator() {
    }

    @Override
    public boolean render(GuiGraphicsExtractor guiGraphics, Font font, ItemStack stack, int x, int y) {
        BallClusterContents contents = BallClusterItem.contents(stack);
        if (contents.isEmpty()) {
            return false;
        }

        // ⚠️⚠️ **槽位落在屏幕外时必须直接返回**（作者 2026-10-17 报的联机恶性崩溃）。
        //
        //    crash-report 关键行：
        //      Scissor at 226, 504 with size 16x24 is out of bounds for
        //      render area RenderArea[x=0, y=0, width=854, height=480]
        //
        //    **`16x24` 就是这里** —— 26.2 的 `GuiGraphicsExtractor#itemDecorations`
        //    会给每个装饰器开一个 `(x, y, 16, 24)` 的裁剪框，而 `x, y` 是**框架给过来的
        //    槽位屏幕坐标**。高等级收纳袋的界面很高（能到 260+），小窗口（480 高）里
        //    槽位会被排到屏幕下方之外 —— 坐标于是变成 `y=504` 这种，
        //    那个 16x24 的 scissor 就越界了。而 26.2 的 `RenderPass.enableScissor`
        //    **不做 clamp，直接抛 IllegalArgumentException 打崩整帧**。
        //
        //    作者线索「1.0.1.9 联机时没有这个 bug」也印证了：那时候这个装饰器还不存在，
        //    原版虽然同样把槽位画在屏幕外，但**不会为此额外开 16x24 的 scissor**，所以不崩。
        //
        //    所以这里是**根因所在的那一处**：越界的格子，我们一个像素都不画。
        //    （槽位本身画在屏幕外是原版容器的既有行为，不归我们管，也不会崩。）
        final var window = net.minecraft.client.Minecraft.getInstance().getWindow();
        if (x < 0 || y < 0
                || x + DECORATION_WIDTH > window.getGuiScaledWidth()
                || y + DECORATION_HEIGHT > window.getGuiScaledHeight()) {
            return false;
        }

        String amount = String.valueOf(contents.size());
        // 与 GuiGraphicsExtractor#itemCount 完全相同的落点（26.2 源码 L897）
        guiGraphics.text(font, amount, x + 19 - 2 - font.width(amount), y + 6 + 3, -1, true);
        return false;   // 没改 RenderState，不需要为后面的装饰器重置
    }
}
