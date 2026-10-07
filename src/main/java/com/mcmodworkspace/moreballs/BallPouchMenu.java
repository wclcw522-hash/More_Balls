package com.mcmodworkspace.moreballs;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 魔丸收纳袋的界面。
 *
 * <h2>布局</h2>
 * <p>每行 9 格。上面若干行是<b>弹药格</b>（满耐久的球），下面若干行是<b>转运格</b>
 * （用过的球）。行数随等级增长：</p>
 *
 * <table border="1">
 *   <caption>各等级的行数与实际格数</caption>
 *   <tr><th>级</th><th>每段格数</th><th>每段行数</th><th>末行用掉</th></tr>
 *   <tr><td>1 皮革</td><td>9</td><td>1</td><td>9（满）</td></tr>
 *   <tr><td>2 铁</td><td>12</td><td>2</td><td>3（右边 6 格盖住）</td></tr>
 *   <tr><td>3 金</td><td>15</td><td>2</td><td>6（右边 3 格盖住）</td></tr>
 *   <tr><td>4 绿宝石</td><td>18</td><td>2</td><td>9（满）</td></tr>
 *   <tr><td>5 钻石</td><td>21</td><td>3</td><td>3（右边 6 格盖住）</td></tr>
 *   <tr><td>6 黑曜石</td><td>24</td><td>3</td><td>6（右边 3 格盖住）</td></tr>
 *   <tr><td>7 下界合金</td><td>27</td><td>3</td><td>9（满）</td></tr>
 * </table>
 *
 * <p><b>关键规则（作者指定）</b>：行数按实际格数向上取整，所以每段的行里可能<u>用不满</u>；
 * 那些多出来的位置<b>照样占位</b>，但在界面上从右往左用银灰色盖住、并且禁止交互
 * （靠 {@code Slot.isActive()} 返回 false 实现）。这样每一行始终是完整的 9 格宽度。</p>
 *
 * <h2>槽位限制</h2>
 * <p>两段的 {@code mayPlace} 分开：弹药格只收满耐久的球、转运格只收用过的球。</p>
 */
public class BallPouchMenu extends AbstractContainerMenu {

    /** 每行槽位数 */
    public static final int COLUMNS = 9;

    /** 弹药段距顶部的距离 —— 与原版箱子一致（标题栏 17 像素） */
    private static final int AMMO_TOP = 17;

    /**
     * 两段之间的第二个标题区高度（同样是原版标题栏那 17 像素）。
     *
     * <p>作者指定的做法：界面等于「两个箱子 UI 叠起来」—— 上面一个带标题的槽位区、下面再来一个。
     * 所以弹药段之后要先放一条标题区（写「中转区」），再排转运段。
     * 这条标题区顺带就充当了两段之间的分隔线，不必另外画线。</p>
     */
    public static final int SECTION_TITLE_HEIGHT = 17;

    /** 屏幕左右内边距 */
    private static final int SIDE_PADDING = 8;

    /** 原版容器里「槽位区结束」到「玩家背包开始」之间的间隔 */
    private static final int PLAYER_GAP = 14;

    /** 原版容器玩家区的高度（3 行背包 + 快捷栏 + 上下留白） */
    private static final int PLAYER_AREA_HEIGHT = 96;

    private final ItemStack pouch;
    private final BallPouchTier tier;

    /** 弹药段的实际行数 */
    private final int ammoRows;

    /** 转运段的实际行数 */
    private final int transitRows;

    /** 客户端构造：从网络包读回袋子物品堆 */
    public BallPouchMenu(int windowId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(windowId, inventory, ItemStack.STREAM_CODEC.decode(buf));
    }

    /** 服务端构造（打开界面时用） */
    public BallPouchMenu(int windowId, Inventory inventory, ItemStack pouch) {
        super(ModMenus.BALL_POUCH.get(), windowId);
        this.pouch = pouch;
        this.tier = BallPouchItem.tier(pouch);

        BallPouchContainer container = new BallPouchContainer(pouch);
        this.ammoRows = rowsFor(this.tier.ammoSlots());
        this.transitRows = rowsFor(this.tier.transitSlots());

        // ① 弹药段 —— 按行占位，超出的格子禁用
        int ammoPositions = this.ammoRows * COLUMNS;
        for (int i = 0; i < ammoPositions; i++) {
            boolean unlocked = i < this.tier.ammoSlots();
            this.addSlot(new Slot(container, i,
                    SIDE_PADDING + (i % COLUMNS) * 18,
                    AMMO_TOP + (i / COLUMNS) * 18) {
                @Override
                public boolean isActive() {
                    return unlocked;
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    return unlocked && BallAmmo.isBall(stack) && BallPouchItem.isPristine(stack);
                }
            });
        }

        // ② 转运段 —— 前面先让出第二个标题区（写「中转区」），它同时就是两段的分隔
        int transitTop = AMMO_TOP + this.ammoRows * 18 + SECTION_TITLE_HEIGHT;
        int transitPositions = this.transitRows * COLUMNS;
        for (int j = 0; j < transitPositions; j++) {
            boolean unlocked = j < this.tier.transitSlots();
            this.addSlot(new Slot(container, BallPouchContainer.AMMO_SLOTS + j,
                    SIDE_PADDING + (j % COLUMNS) * 18,
                    transitTop + (j / COLUMNS) * 18) {
                @Override
                public boolean isActive() {
                    return unlocked;
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    return unlocked && BallAmmo.isBall(stack) && !BallPouchItem.isPristine(stack);
                }
            });
        }

        // ③ 玩家背包（3 行主背包 + 1 行快捷栏）
        int playerTop = transitTop + this.transitRows * 18 + PLAYER_GAP;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                this.addSlot(new Slot(inventory, col + row * COLUMNS + COLUMNS,
                        SIDE_PADDING + col * 18, playerTop + row * 18));
            }
        }
        int hotbarTop = playerTop + 3 * 18 + 4;
        for (int col = 0; col < COLUMNS; col++) {
            this.addSlot(new Slot(inventory, col, SIDE_PADDING + col * 18, hotbarTop));
        }
    }

    /** 格数换算成行数（向上取整） */
    private static int rowsFor(int slots) {
        return (slots + COLUMNS - 1) / COLUMNS;
    }

    /** 袋子自身的槽位总数（含被盖住的位置） */
    public int pouchSlotCount() {
        return (this.ammoRows + this.transitRows) * COLUMNS;
    }

    /**
     * 界面总高度 —— 两个标题区 + 两段槽位区 + 玩家区。
     *
     * <p>行数随等级变化，所以高度也跟着变；每段都补齐到整行，
     * 所以槽位区永远是 9 的整数倍宽度。</p>
     */
    public int guiHeight() {
        return AMMO_TOP
                + this.ammoRows * 18
                + SECTION_TITLE_HEIGHT
                + this.transitRows * 18
                + PLAYER_AREA_HEIGHT;
    }

    /** 弹药段顶部 y */
    public int ammoTop() {
        return AMMO_TOP;
    }

    /** 转运段顶部 y */
    public int transitTop() {
        return AMMO_TOP + this.ammoRows * 18 + SECTION_TITLE_HEIGHT;
    }

    /** 第二个标题区（「中转区」）的顶部 y */
    public int sectionTitleTop() {
        return AMMO_TOP + this.ammoRows * 18;
    }

    /** 弹药段行数 */
    public int ammoRows() {
        return this.ammoRows;
    }

    /** 转运段行数 */
    public int transitRows() {
        return this.transitRows;
    }

    public BallPouchTier tier() {
        return this.tier;
    }

    public ItemStack pouch() {
        return this.pouch;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int pouchSlots = pouchSlotCount();

        if (index < pouchSlots) {
            // 从袋子往玩家背包搬
            if (!this.moveItemStackTo(stack, pouchSlots, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 从玩家背包往袋子里搬 —— 按「满耐久 / 用过」分流到对应的那一段
            if (!BallAmmo.isBall(stack)) {
                return ItemStack.EMPTY;
            }
            boolean toAmmo = BallPouchItem.isPristine(stack);
            int from = toAmmo ? 0 : this.ammoRows * COLUMNS;
            int to = toAmmo ? this.ammoRows * COLUMNS : pouchSlots;
            if (!this.moveItemStackTo(stack, from, to, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        // 袋子还在身上就有效
        return player.getInventory().contains(this.pouch)
                || player.getMainHandItem() == this.pouch
                || player.getOffhandItem() == this.pouch;
    }
}
