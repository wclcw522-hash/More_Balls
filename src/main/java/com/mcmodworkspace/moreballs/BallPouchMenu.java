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
 * <p><b>上表只列弹药段</b>；转运段格数是它的两倍（18 / 24 / 30 / 36 / 42 / 48 / 54），
 * 行数也相应翻倍（2 / 3 / 4 / 4 / 5 / 6 / 6），所以界面总高随等级变化。</p>
 *
 * <p><b>关键规则（作者指定）</b>：行数按实际格数向上取整，所以每段的行里可能<u>用不满</u>；
 * 那些多出来的位置<b>照样占位</b>，但在界面上从右往左用银灰色盖住、并且禁止交互
 * （靠 {@code Slot.isActive()} 返回 false 实现）。这样每一行始终是完整的 9 格宽度。</p>
 *
 * <h2>槽位限制</h2>
 * <p>两段的 {@code mayPlace} 分开：弹药格只收满耐久的球、转运格只收用过的球。</p>
 */
public class BallPouchMenu extends AbstractContainerMenu {

    /**
     * <b>袋子自身每行的槽位数</b> —— 作者 2026-10-17 指定改成 <b>18</b>。
     *
     * <p>⚠️⚠️ 这个**不能**和玩家背包的列数共用一个常量（原来两者共用一个 `COLUMNS = 9`）：
     * 玩家背包与快捷栏是**原版固定 9 列**，袋子如果改成 18 列、玩家那两段也跟着变，
     * 整个背包区就会排错。所以拆成两个常量：</p>
     * <ul>
     *   <li>{@link #POUCH_COLUMNS} = 袋子（弹药段 / 转运段）的列数 —— 作者要的 18；</li>
     *   <li>{@link #PLAYER_COLUMNS} = 玩家背包 / 快捷栏的列数 —— 原版固定 9，永远不动。</li>
     * </ul>
     */
    public static final int POUCH_COLUMNS = 15;

    /** 玩家背包 / 快捷栏的列数 —— 原版固定 9，**不要**跟着袋子改 */
    public static final int PLAYER_COLUMNS = 9;

    /** 每行槽位数（历史名，保留给外部引用；等价于 {@link #POUCH_COLUMNS}） */
    public static final int COLUMNS = POUCH_COLUMNS;

    /**
     * 玩家背包那 9 列相对袋子左边缘的额外缩进 —— 让它在变宽的界面里<b>居中</b>。
     *
     * <p>袋子 15 列宽 284，而玩家背包是原版固定 9 列（占 176 的版式，去掉外框后 168）。
     * 把 168 居中放到 284 里 ⇒ 左边留 (284-168)/2 = 58，玩家第一格内容于是落在
     * 58 + (8-4) = <b>62</b>。相对 {@link #SIDE_PADDING}=8 的额外缩进就是 <b>54</b>。</p>
     *
     * <p>底图 {@code ball_pouch.png} 里玩家区就是按这个位置居中画的，两边必须一致。</p>
     */
    private static final int PLAYER_INSET = 54;

    /** 单格步长（原版槽位间距） */
    private static final int SLOT_STEP = 18;

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

    /**
     * 客户端构造：从网络包读回袋子物品堆。
     *
     * <h2>⚠️ 为什么要读一个「版本标记字节」并判空（作者 2026-10-16 报的联机崩溃）</h2>
     * <p>NeoForge 的 {@code ServerPlayer.openMenu(provider, extraDataWriter)} 在
     * <b>额外数据为空</b>时会退回原版 {@code ClientboundOpenScreenPacket} ——
     * 那条路径下客户端工厂拿到的是 <b>{@code null}</b>（见 {@code IContainerFactory.create}
     * 的默认实现），这里一句 {@code decode(buf)} 就是 NPE。</p>
     *
     * <p>而且客户端处理失败时 NeoForge 走的是 {@code context.disconnect(...)}
     * —— 表现就是玩家被踢回主菜单、且<b>不产生带堆栈的崩溃报告</b>。
     * 这与作者看到的「联机时只有非主机玩家出事、主机没事」正好吻合。</p>
     *
     * <p>所以两侧都上保险：<b>写侧</b>先写一个版本标记字节（保证额外数据永远非空），
     * <b>读侧</b>先判空再读标记，读不到就退回空栈而不是崩。</p>
     */
    public BallPouchMenu(int windowId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(windowId, inventory, decodePouch(buf));
    }

    /** 收纳袋菜单额外数据的版本标记（写侧第一个字节，读侧先消费掉） */
    public static final byte POUCH_MENU_DATA_VERSION = 1;

    /** 从额外数据里读回袋子；数据缺失时退回空栈（宁可是空袋子，也不要 NPE 掉线） */
    private static ItemStack decodePouch(RegistryFriendlyByteBuf buf) {
        if (buf == null || buf.readableBytes() <= 0) {
            MoreBalls.LOGGER.warn("[pouch] 打开菜单时没有收到袋子数据 —— "
                    + "退回空袋子。若频繁出现，请检查 ServerPlayer.openMenu 的额外数据是否为空。");
            return ItemStack.EMPTY;
        }
        buf.readByte();   // 版本标记（当前恒为 1；将来协议变了可以在这里分流）
        return ItemStack.STREAM_CODEC.decode(buf);
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
        int ammoPositions = this.ammoRows * POUCH_COLUMNS;
        for (int i = 0; i < ammoPositions; i++) {
            boolean unlocked = i < this.tier.ammoSlots();
            this.addSlot(new Slot(container, i,
                    SIDE_PADDING + (i % POUCH_COLUMNS) * 18,
                    AMMO_TOP + (i / POUCH_COLUMNS) * 18) {
                @Override
                public boolean isActive() {
                    return unlocked;
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    // 弹药段只收**满耐久的散球**。
                    // 【球堆】不认 —— 壳里装的全是「用过」的球，本来就该待转运段。
                    return unlocked && BallAmmo.isBall(stack) && BallPouchItem.isPristine(stack);
                }
            });
        }

        // ② 转运段 —— 前面先让出第二个标题区（写「中转区」），它同时就是两段的分隔
        int transitTop = AMMO_TOP + this.ammoRows * 18 + SECTION_TITLE_HEIGHT;
        int transitPositions = this.transitRows * POUCH_COLUMNS;
        for (int j = 0; j < transitPositions; j++) {
            boolean unlocked = j < this.tier.transitSlots();
            this.addSlot(new Slot(container, BallPouchContainer.AMMO_SLOTS + j,
                    SIDE_PADDING + (j % POUCH_COLUMNS) * 18,
                    transitTop + (j / POUCH_COLUMNS) * 18) {
                @Override
                public boolean isActive() {
                    return unlocked;
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    // 【球堆】转运段**要收壳**（作者 2026-10-15）。
                    //
                    // 转运段现在存的就是球堆 —— 用过的球按档合并成一格。
                    // 不认壳的话，玩家手动从背包把壳拖进来会被拒，
                    // 而壳正是「用过的球」在袋子里的形态。
                    if (com.mcmodworkspace.moreballs.BallClusterItem.isCluster(stack)) {
                        return unlocked;
                    }
                    return unlocked && BallAmmo.isBall(stack) && !BallPouchItem.isPristine(stack);
                }
            });
        }

        // ③ 玩家背包（3 行主背包 + 1 行快捷栏）
        int playerTop = transitTop + this.transitRows * 18 + PLAYER_GAP;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < PLAYER_COLUMNS; col++) {
                this.addSlot(new Slot(inventory, col + row * PLAYER_COLUMNS + PLAYER_COLUMNS,
                        SIDE_PADDING + PLAYER_INSET + col * 18, playerTop + row * 18));
            }
        }
        int hotbarTop = playerTop + 3 * 18 + 4;
        for (int col = 0; col < PLAYER_COLUMNS; col++) {
            this.addSlot(new Slot(inventory, col, SIDE_PADDING + PLAYER_INSET + col * SLOT_STEP, hotbarTop));
        }
    }

    /** 格数换算成行数（向上取整） */
    /**
     * 界面<b>宽度</b> —— 跟着袋子的列数走（作者 2026-10-17 把每行槽位改成 18，宽度必须同步）。
     *
     * <p>公式与原版容器一致：左右各留 {@link #SIDE_PADDING} 的内边距，
     * 中间是「列数 × 单格步长」。玩家背包 / 快捷栏是原版固定 9 列、左对齐，
     * 所以界面变宽后它们仍贴在左边，与改之前的位置完全一致。</p>
     */
    public int guiWidth() {
        // 与原版容器一致的宽度：左右内边距 + (列数-1) 个步长 + 最后一格的 16 像素宽
        // 9 列 ⇒ 16 + 8*18 + 16 = 176（与回退前的硬编码值完全一致）
        return SIDE_PADDING * 2 + (POUCH_COLUMNS - 1) * SLOT_STEP + 16;
    }

    private static int rowsFor(int slots) {
        return (slots + POUCH_COLUMNS - 1) / POUCH_COLUMNS;
    }

    /** 袋子自身的槽位总数（含被盖住的位置） */
    public int pouchSlotCount() {
        return (this.ammoRows + this.transitRows) * POUCH_COLUMNS;
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

    /**
     * <b>右键把光标上的球放进球堆</b>（作者 2026-10-18 要求）。
     *
     * <p>原来只能「右键球堆 → 把最低耐久的那颗拿到光标上」（取出），反向放不进去。
     * 这里补上反向：光标上拿着一颗球、右键点某个球堆，若同球种且还有空位，就收进去。</p>
     *
     * <h2>为什么要在 clicked 里拦</h2>
     * <p>原版右键对槽位只有「拆一半 / 放一个」两种语义，不认识球堆这种「壳里装一串」的东西 ——
     * 壳是 {@code stacksTo(1)} 的，原版只会把光标整摞塞进空槽，塞不进已占用的槽。
     * 所以这一层必须自己写。</p>
     *
     * <p>拦下之后**不调 super**，直接返回 —— 否则原版还会再按自己的规则动一次光标上的物品。</p>
     */
    @Override
    public void clicked(int slotId, int button, net.minecraft.world.inventory.ContainerInput containerInput, Player player) {
        // ⏳ 临时诊断：先确认这个 override 到底有没有被调用（无条件打点）
        MoreBalls.LOGGER.info("[pouch][clicked-enter] slot={} button={} input={} carried={}",
                slotId, button, containerInput, this.getCarried());
        // ⏳ 临时诊断（定位「右键放不进球堆」，验证后删）
        if (button == 1 && containerInput == net.minecraft.world.inventory.ContainerInput.PICKUP) {
            final ItemStack dbgCarried = this.getCarried();
            final ItemStack dbgTarget = (slotId >= 0 && slotId < this.slots.size())
                    ? this.slots.get(slotId).getItem() : ItemStack.EMPTY;
            MoreBalls.LOGGER.info("[pouch][clicked] slot={} carried={} isBall={} target={} isCluster={}",
                    slotId, dbgCarried, BallAmmo.isBall(dbgCarried), dbgTarget,
                    !dbgTarget.isEmpty() && BallClusterItem.isCluster(dbgTarget));
            if (!dbgTarget.isEmpty() && BallClusterItem.isCluster(dbgTarget)) {
                final BallClusterContents dbg = BallClusterItem.contents(dbgTarget);
                MoreBalls.LOGGER.info("[pouch][clicked] hasRoom={} matches={}", dbg.hasRoom(), dbg.matches(dbgCarried));
            }
        }

        // 只接「右键 + 拾取类点击」
        if (button == 1 && containerInput == net.minecraft.world.inventory.ContainerInput.PICKUP) {
            final ItemStack carried = this.getCarried();
            if (!carried.isEmpty() && BallAmmo.isBall(carried)) {
                // slotId 越界（点空白区）时原版约定为 -1 / -999
                if (slotId >= 0 && slotId < this.slots.size()) {
                    final ItemStack target = this.slots.get(slotId).getItem();
                    if (!target.isEmpty() && BallClusterItem.isCluster(target)) {
                        final BallClusterContents existing = BallClusterItem.contents(target);
                        // ⚠️ 同球种 + 还有空位才收；withAdded 失败会静默返回同一引用，所以要比引用
                        if (existing.hasRoom() && existing.matches(carried)) {
                            final BallClusterContents after = existing.withAdded(carried);
                            if (after != existing) {
                                BallClusterItem.setContents(target, after);
                                carried.shrink(1);            // 光标上少一颗
                                this.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
                                this.broadcastChanges();
                                return;                        // 拦下，不交给原版
                            }
                        }
                    }
                }
            }
        }
        super.clicked(slotId, button, containerInput, player);
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
            //
            // 【球堆】壳（作者 2026-10-15）：壳里装的全是**用过的**球 → 直接进**转运段**。
            // 不认壳的话 shift 搬运搬不动它，玩家只能手动一格一格拖。
            if (com.mcmodworkspace.moreballs.BallClusterItem.isCluster(stack)) {
                int transitFrom = this.ammoRows * POUCH_COLUMNS;
                if (!this.moveItemStackTo(stack, transitFrom, pouchSlots, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!BallAmmo.isBall(stack)) {
                    return ItemStack.EMPTY;
                }
                boolean toAmmo = BallPouchItem.isPristine(stack);
                int from = toAmmo ? 0 : this.ammoRows * POUCH_COLUMNS;
                int to = toAmmo ? this.ammoRows * POUCH_COLUMNS : pouchSlots;
                if (!this.moveItemStackTo(stack, from, to, false)) {
                    return ItemStack.EMPTY;
                }
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
        // 袋子还在身上就有效 —— 口径统一走 allPouchStacks（背包 / 快捷栏 / 副手 / 饰品栏都算）。
        // 原先这里只查背包与主副手，从饰品栏（G 键）打开时每刻都被判「袋子不在了」，
        // 于是界面刚开就被服务端 closeContainer() 关掉。
        //
        // ⚠️ 判据用「**同一个物品**」而不是「同一份组件」（作者 2026-10-16 报的
        //    「界面突然关闭、吞掉关闭瞬间放进去的球」）：
        //    袋子的内容就存在组件里，玩家往里放一颗球组件必然变 ——
        //    用 isSameItemSameComponents 比，会在**放下去的那一瞬间**把界面判成失效，
        //    服务端随即关界面，客户端那一格刚放进的球就跟着没了。
        //    「界面该不该关」看的是「袋子还在不在身上」，与它的内容无关。
        for (ItemStack stack : BallPouchHelper.allPouchStacks(player)) {
            if (stack == this.pouch || ItemStack.isSameItem(stack, this.pouch)) {
                return true;
            }
        }
        return false;
    }
}
