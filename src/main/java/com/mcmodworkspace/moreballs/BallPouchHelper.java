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
 *   <li>快捷栏最前面的空位</li>
 *   <li>手里（主手 → 副手）</li>
 *   <li>收纳袋的<b>中转槽</b></li>
 *   <li>背包里最前面的位置</li>
 * </ol>
 *
 * <h2>二、空气动力球回收时（自己飞回来）</h2>
 * <p>这时玩家多半没空手接，所以反过来 —— 先试图「收起来」，最后才掉地上：</p>
 * <ol>
 *   <li>收纳袋的<b>中转槽</b></li>
 *   <li>背包最靠前的位置</li>
 *   <li>快捷栏最靠前的位置</li>
 *   <li>变成掉落物</li>
 * </ol>
 *
 * <h2>三、消耗时</h2>
 * <p>同种弹药<b>不看耐久</b>，先取中转槽里的同类，再取背包里的同类。
 * 见 {@link #takeSameKind}。</p>
 */
public final class BallPouchHelper {

    private BallPouchHelper() {
    }

    /** 这是不是一个收纳袋 */
    public static boolean isPouch(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BallPouchItem;
    }

    /**
     * 找玩家身上的收纳袋。
     *
     * <p>背包（含快捷栏与副手）找完再看<b>饰品栏</b> —— 袋子挂在背饰 / 腰带 / 护符上时
     * 同样要能被找到，否则「空气动力球回收的球落进袋子」这条会直接失效
     * （作者反馈过：球没进袋子，就是因为这里只扫了背包）。</p>
     *
     * @param requireEquipped 只认「快捷栏 / 主手 / 副手」这些顺手位置的袋子
     */
    public static ItemStack findPouch(Player player, boolean requireEquipped) {
        Inventory inventory = player.getInventory();
        int limit = requireEquipped ? 9 : inventory.getContainerSize();
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

        // ② 手里（主手 → 副手）
        if (player.getMainHandItem().isEmpty()) {
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ball.copy());
            ball.setCount(0);
            return true;
        }
        if (player.getOffhandItem().isEmpty()) {
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ball.copy());
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
        for (int i = 9; i < inventory.getContainerSize(); i++) {
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

    public static Delivery deliverReturned(Player player, ItemStack ball) {
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
        for (int i = 0; i < inventory.getContainerSize(); i++) {
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

    // ===== 三、消耗 =====

    /**
     * 取一颗<b>同种</b>的球来用 —— 作者指定：不看耐久，先中转槽同类、再背包同类。
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
        for (int i = 0; i < inventory.getContainerSize(); i++) {
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
        for (int i = 0; i < inventory.getContainerSize(); i++) {
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
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isPouch(stack)) {
                out.add(stack);
            }
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
