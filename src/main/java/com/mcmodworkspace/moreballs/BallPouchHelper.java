package com.mcmodworkspace.moreballs;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 「球该怎么进出玩家的袋子」这件事的统一入口。
 *
 * <p>作者指定的两条顺序<b>方向相反</b>，这里就是它们的唯一实现处 ——
 * 别再各处各写一份，不然迟早对不上。</p>
 *
 * <h2>一、右键拾起时（玩家主动捡）</h2>
 * <p>优先给玩家「最顺手」的位置：</p>
 * <ol>
 *   <li><b>主手/副手拎着收纳袋 → 直接进袋</b>（优先级最高，见 {@link #holdingPouch}）</li>
 *   <li>快捷栏最前面的空位</li>
 *   <li>手里（主手 → 副手）</li>
 *   <li>收纳袋的<b>中转槽</b></li>
 *   <li>背包里最前面的位置</li>
 * </ol>
 *
 * <h2>二、空气动力球回收时（自己飞回来）</h2>
 * <p>这时玩家多半没空手接，所以反过来 —— 先试图「收起来」，最后才掉地上：</p>
 * <ol>
 *   <li>收纳袋里<b>对应的那一段</b>（满耐久 → 备用区，用过的 → 中转区）</li>
 *   <li>背包 {@code 0..35}（含快捷栏）</li>
 *   <li>变成掉落物（返回 {@link Delivery#NOWHERE}）</li>
 * </ol>
 *
 * <h2>三、消耗时</h2>
 * <p>同种弹药<b>不看耐久</b>，顺序是：袋子中转槽的同类 → 背包里的同类 →
 * 最后才退到袋子的弹药段。见 {@link #takeSameKind}。</p>
 */
public final class BallPouchHelper {

    /**
     * 原版背包的槽位数（快捷栏 9 + 主背包 27）。
     *
     * <p>⚠️ 2026-10-12 审计修正：这里原先用 {@code inventory.getContainerSize()}，
     * 但在 26.x 它返回的是 **43**（36 个背包位 + 7 个装备槽）——
     * 于是「找空位放球」把球写进了脚/腿/胸/头/身体/鞍这些**装备槽**。
     * 现在统一写死 36，只覆盖 0..35。</p>
     */
    public static final int INVENTORY_SIZE = 36;

    private BallPouchHelper() {
    }

    /** 这是不是一个收纳袋 */
    public static boolean isPouch(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BallPouchItem;
    }

    /**
     * 找玩家身上的收纳袋。
     *
     * <p>背包扫的是 {@code 0..35}（含快捷栏 <b>0..8</b>；主手/副手与装备槽不在这个区间），
     * 扫完再看<b>饰品栏</b> —— 袋子挂在背饰 / 腰带 / 护符上时
     * 同样要能被找到，否则「空气动力球回收的球落进袋子」这条会直接失效
     * （作者反馈过：球没进袋子，就是因为这里只扫了背包）。</p>
     *
     * @param requireEquipped 只认快捷栏（{@code 0..8}）里的袋子
     */
    public static ItemStack findPouch(Player player, boolean requireEquipped) {
        Inventory inventory = player.getInventory();
        int limit = requireEquipped ? 9 : 36;
        for (int i = 0; i < limit; i++) {
            ItemStack stack = inventory.getItem(i);
            if (isPouch(stack)) {
                return stack;
            }
        }

        // 背包里没有就问饰品栏（没装 Curios 时返回空列表，自动跳过）
        for (ItemStack worn : BallPouchCurios.findAllWornPouches(player)) {
            if (isPouch(worn)) {
                return worn;
            }
        }
        return ItemStack.EMPTY;
    }

    // ===== 一、右键拾起 =====

    /**
     * 收纳袋是不是正拿在手上（主手或副手）。
     *
     * <p>作者 2026-10-07 指定：<b>手持收纳袋时捡球直接进袋</b> ——
     * 手里拎着袋子还要球先跑去占快捷栏，那是反直觉的。</p>
     */
    public static boolean holdingPouch(Player player) {
        return isPouch(player.getMainHandItem()) || isPouch(player.getOffhandItem());
    }

    /**
     * 手里那个袋子（主手优先）；没拿袋子时返回空栈。
     */
    public static ItemStack pouchInHand(Player player) {
        if (isPouch(player.getMainHandItem())) {
            return player.getMainHandItem();
        }
        if (isPouch(player.getOffhandItem())) {
            return player.getOffhandItem();
        }
        return ItemStack.EMPTY;
    }

    /**
     * 玩家主动捡起一颗球时的去向。
     *
     * <p>作者 2026-10-07 指定：<b>主手或副手拿着收纳袋时，捡起的球直接放进袋子里</b>。
     * 这一条优先级最高 —— 拎着袋子就是「我要收球」的意思。</p>
     *
     * <p>没拿袋子时才走原来的顺序：快捷栏空位 → 手里 → 袋中转槽 → 背包。
     * 手里那两个格子此时必定不是袋子（否则上面那条就命中了），所以不用重复判。</p>
     *
     * @return true 表示已经安置好了（调用方不用再管）
     */
    public static boolean giveToPlayer(Player player, ItemStack ball) {
        // ⓪ 手里拎着收纳袋 → 直接进袋（作者指定）
        if (holdingPouch(player)) {
            ItemStack pouch = pouchInHand(player);
            // 满耐久的进弹药段、用过的进转运段，分流规则和别处一致
            if (BallPouchItem.insert(pouch, ball)) {
                return true;
            }
            // 袋里满了也别硬塞，落到下面按常规位置安置，
            // 免得球因为「拿着袋子」反而无处可去
        }

        // ① 快捷栏最前面的空位
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, ball.copy());
                ball.setCount(0);
                return true;
            }
        }

        // ② 主手（**到此为止 —— 副手一律不碰**；作者 2026-10-15 选定）
        //
        // ⚠️⚠️ 原来这里还有一站「副手空 → 把球放进副手」，已撤掉。理由：
        //
        //     副手在这套机制里是**装填位**。唯一该往那儿放东西的是
        //     `BallAmmoAutoLoader`（主手是弩 + 装备了收纳袋 + 轮盘里选过种类 + 副手空
        //     ⇒ 从袋子里抽**选中那个种类**的球补上）。
        //
        //     回收流程（本方法）也往副手塞，玩家就会看到
        //     「**没在轮盘选过弹药，副手却被塞了球**」—— 而且塞进去的正是**刚飞回来的那颗**
        //     （它可能根本不是当前选中的种类）。
        //
        //     正确顺序是：**回收 → 进袋子 → 再由自动补弹按「选中的种类」决定要不要上副手**。
        //     也就是说：副手上出现球，唯一的来源就是「在轮盘里选过那种球」。
        if (player.getMainHandItem().isEmpty()) {
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ball.copy());
            ball.setCount(0);
            return true;
        }

        // ③ 收纳袋的中转槽 —— 身上可能不止一个袋子（背饰 / 腰带 / 护符各一个），挨个试
        for (ItemStack pouch : allPouchStacks(player)) {
            if (BallPouchItem.insertToTransit(pouch, ball)) {
                return true;
            }
        }

        // ④ 背包里最前面的位置
        for (int i = 9; i < 36; i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, ball.copy());
                ball.setCount(0);
                return true;
            }
        }
        return false;
    }

    // ===== 二、空气动力球回收 =====

    /**
     * 回收的球飞回来时的去向 —— 先收起来，最后才掉地上。
     *
     * <h2>按「球是不是用过的」分流到袋子的对应段（作者 2026-10-07 纠正）</h2>
     * <p>袋子里本来就是两段，各管各的：</p>
     * <ul>
     *   <li><b>满耐久的球 → 备用区</b>（弹药段）</li>
     *   <li><b>耐久不满的球 → 中转区</b>（转运段）</li>
     * </ul>
     * <p>之前这里无条件先试中转槽，于是<b>换弹时被换下来的满耐久球也进了中转区</b> —— 作者反馈过。</p>
     *
     * <h2>中转区满了要吭声</h2>
     * <p>一颗「用过的球」本该进中转区，却因为满了落到背包 ——
     * 这时返回 {@link Delivery#INVENTORY_TRANSIT_FULL}，让调用方给玩家一句提示，
     * 免得玩家事后不知道那颗球去哪儿了。</p>
     */
    public enum Delivery {
        /** 进了<b>备用区</b>（满耐久的球正该去那儿） */
        AMMO,
        /** 进了<b>中转区</b>（用过的球正该去那儿） */
        TRANSIT,
        /** 进了背包 —— 对应那一段满了、或者身上压根没戴袋子，属正常去向，不提示 */
        INVENTORY,
        /** <b>中转区满了</b>，本该进中转区的「用过的球」改放进背包 —— <b>要提醒玩家</b> */
        INVENTORY_TRANSIT_FULL,
        /** 哪儿都放不下，交给调用方掉成掉落物 */
        NOWHERE
    }

    /**
     * 回收交付 —— 外面包一层，只为了在**所有 return 路径**上都补一次客户端同步。
     *
     * <p>袋子的内容是 ItemStack 组件，服务端改了客户端不会自动知道；
     * 不同步的话 HUD 上那个弹药数就一直显示旧值（作者 2026-10-15 反馈
     * 「剩余弹药显示槽有延迟」）。</p>
     */
    public static Delivery deliverReturned(Player player, ItemStack ball) {
        Delivery delivery = deliverReturned0(player, ball);
        syncPouchToClient(player);
        return delivery;
    }

    private static Delivery deliverReturned0(Player player, ItemStack ball) {
        List<ItemStack> pouches = allPouchStacks(player);
        // 满耐久 → 备用区；用过的 → 中转区
        boolean pristine = BallPouchItem.isPristine(ball);

        // ① 塞进袋子里对应的那一段 —— 挨个袋子试（背饰 / 腰带 / 护符可能各戴了一个）
        for (ItemStack pouch : pouches) {
            boolean inserted = pristine
                    ? BallPouchItem.insertToAmmo(pouch, ball)
                    : BallPouchItem.insertToTransit(pouch, ball);
            if (inserted) {
                return pristine ? Delivery.AMMO : Delivery.TRANSIT;
            }
        }

        // ② 背包最靠前的位置（含快捷栏 —— 原版背包就是 0..35）
        Inventory inventory = player.getInventory();

        // ===== ②-0 ★ 先并进背包里**已有的同类壳**（作者 2026-10-15 反馈）=====
        //
        // 「球自己飞回来的情况下还是不会主动并入球堆」—— 根因就在这段：
        // 原先这里**只找空格**，从不看背包里有没有同类的壳，于是飞回来的球
        // 自己新建一个壳占一格，已有的壳原地不动。
        //
        // ⚠️ 比对时用的是 `asBall(ball)`（**壳里那颗球**），不是 ball 本身 ——
        //    传进来的 ball 在回收链上已经 clusterize 过，是个**壳**；
        //    拿壳去 matches 会因为 item 不同而永远失配（这个坑在袋子那边踩过）。
        //
        // ⚠️ `withAdded` 会静默失败（满 64 / 不匹配），必须核对返回值 ——
        //    加不进去就当没成，继续试下一个（这是 1.1.0.21 修「丢球」时立的规矩）。
        if (BallClusterItem.isCluster(ball)) {
            ItemStack probe = BallClusterItem.asBall(ball);
            if (!probe.isEmpty()) {
                for (int i = 0; i < 36; i++) {
                    ItemStack slot = inventory.getItem(i);
                    if (!BallClusterItem.isCluster(slot)) {
                        continue;
                    }
                    BallClusterContents existing = BallClusterItem.contents(slot);
                    if (!existing.hasRoom() || !existing.matches(probe)) {
                        continue;
                    }
                    BallClusterContents after = existing.withAdded(probe);
                    if (after == existing) {
                        continue;   // 没加进去 → 试下一个壳
                    }
                    BallClusterItem.setContents(slot, after);
                    ball.setCount(0);
                    return pristine || pouches.isEmpty()
                            ? Delivery.INVENTORY
                            : Delivery.INVENTORY_TRANSIT_FULL;
                }
            }
        }

        for (int i = 0; i < 36; i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, ball.copy());
                ball.setCount(0);
                // 用过的球没能进中转区 → 那就是中转区满了（没戴袋子不算）
                return pristine || pouches.isEmpty()
                        ? Delivery.INVENTORY
                        : Delivery.INVENTORY_TRANSIT_FULL;
            }
        }
        // ③ 实在没地方 → 交给调用方掉成掉落物
        return Delivery.NOWHERE;
    }

    /**
     * <b>让客户端拿到最新的袋子内容</b> —— 改完袋子后必须调一次。
     *
     * <h2>为什么</h2>
     * <p>袋子的内容存在 {@code ItemStack} 的组件里，服务端改了组件<b>不会自动</b>同步给客户端。
     * 于是 {@code BallPouchHud}（副手栏上方那个弹药格）读到的还是旧值 ——
     * 作者 2026-10-15 反馈的「剩余弹药显示槽有延迟，要手动改一下袋子里的球才刷新」
     * 就是这个。</p>
     *
     * <h2>两条路</h2>
     * <ul>
     *   <li>背包 / 快捷栏里的袋子 —— 走原版菜单广播（它只把真正变了的槽发出去）</li>
     *   <li>饰品栏（Curios）里的袋子 —— Curios 只在「栈被换过」时才同步，
     *       所以显式写回一次，见 {@link BallPouchCurios#forceSync}</li>
     * </ul>
     */
    public static void syncPouchToClient(Player player) {
        if (player == null || player.level().isClientSide()) {
            return;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.inventoryMenu.broadcastChanges();

            // 再把「选中弹药的当前数量」写进同步 attachment —— HUD 直接读那一个数。
            //
            // 只靠上面那句广播不够：**挂在饰品栏上的袋子**走的是 Curios 自己的同步，
            // 时机不由我们控制（作者实测「更新依旧不及时」）。而这个 attachment 带
            // `.sync(...)`，由框架保证发到客户端。
            ItemStack selected = BallAmmoSelection.selected(player);
            int count = selected.isEmpty() ? 0 : BallAmmoSelection.countOf(player, selected);
            serverPlayer.setData(ModAttachments.SELECTED_AMMO_COUNT.get(), count);
        }
        BallPouchCurios.forceSync(player);
    }

    // ===== 三、消耗 =====

    /**
     * 取一颗<b>同种</b>的球来用 —— 作者指定：不看耐久，顺序为中转槽同类 → 背包同类 →
     * 袋子弹药段。
     *
     * @param wanted 想要哪种球（只比对物品种类，不比耐久）
     * @return 取到的球；没有则返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack takeSameKind(Player player, ItemStack wanted) {
        if (wanted.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Inventory inventory = player.getInventory();

        // ① 收纳袋的中转槽里找同类 —— 每个袋子都看一遍
        for (ItemStack pouch : allPouchStacks(player)) {
            ItemStack fromTransit = BallPouchItem.takeSameKindFromTransit(pouch, wanted);
            if (!fromTransit.isEmpty()) {
                return fromTransit;
            }
        }

        // ② 背包里找同类（从前往后）
        for (int i = 0; i < 36; i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty() || !BallPouchItem.sameKind(slot, wanted)) {
                continue;
            }
            ItemStack taken = slot.copyWithCount(1);
            slot.shrink(1);
            if (slot.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
            return taken;
        }

        // ③ 再退一步：收纳袋的弹药段（满耐久那些）
        for (ItemStack pouch : allPouchStacks(player)) {
            ItemStack fromAmmo = BallPouchItem.takeSameKindFromAmmo(pouch, wanted);
            if (!fromAmmo.isEmpty()) {
                return fromAmmo;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 玩家身上（袋子或背包）有没有这种球 */
    public static boolean hasSameKind(Player player, ItemStack wanted) {
        for (ItemStack pouch : allPouchStacks(player)) {
            if (BallPouchItem.hasSameKindInTransit(pouch, wanted)
                    || BallPouchItem.hasSameKindInAmmo(pouch, wanted)) {
                return true;
            }
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (BallPouchItem.sameKind(inventory.getItem(i), wanted)) {
                return true;
            }
        }
        return false;
    }

    /** 所有收纳袋里的球（两段合起来，供界面统计用） */
    public static List<ItemStack> allBalls(Player player) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack pouch : allPouchStacks(player)) {
            BallPouchContents contents = BallPouchItem.contents(pouch);
            out.addAll(contents.ammo());
            out.addAll(contents.transit());
        }
        return out;
    }

    /**
     * 玩家身上<b>所有</b>收纳袋的物品堆 —— 背包、快捷栏、饰品栏都算。
     *
     * <p>跟 {@link #findPouch} 的区别：那个只挑「最优先的一个」，
     * 这个把所有袋子都列出来。径向菜单统计弹药种类、自动装填找最低耐久的那颗，
     * 都需要看遍所有袋子，免得漏掉放在第二个袋子里的球。</p>
     */
    public static List<ItemStack> allPouchStacks(Player player) {
        List<ItemStack> out = new ArrayList<>();

        // 背包 + 快捷栏
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isPouch(stack)) {
                out.add(stack);
            }
        }

        // 副手 —— ⚠️ 它**不在** getItem(0..35) 里（副手槽是 40），以前这里漏了。
        // 后果很具体：副手拎着袋子打开界面时，菜单与容器的 stillValid 找不到袋子，
        // 每刻都判成「袋子不在了」→ 服务端立刻 closeContainer() → 界面刚开就关
        // （作者 2026-10-16 报的「界面突然关闭、吞球」的其中一条路径）。
        ItemStack offhand = player.getOffhandItem();
        if (isPouch(offhand)) {
            out.add(offhand);
        }

        // 饰品栏（装了 Curios 才有；没装时返回空列表，自动跳过）
        // 背饰 / 腰带 / 护符可以各戴一个袋子，所以这里要把每个都算上
        for (ItemStack worn : BallPouchCurios.findAllWornPouches(player)) {
            if (!containsSameStack(out, worn)) {
                out.add(worn);
            }
        }

        return out;
    }

    /** 列表里是否已经有同一个物品堆（按引用比，避免重复统计饰品栏那个） */
    private static boolean containsSameStack(List<ItemStack> list, ItemStack target) {
        for (ItemStack stack : list) {
            if (stack == target) {
                return true;
            }
        }
        return false;
    }
}
