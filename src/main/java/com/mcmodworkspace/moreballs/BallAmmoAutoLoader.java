package com.mcmodworkspace.moreballs;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 「选中弹药」的自动装填 —— <b>副手的弹药跟着轮盘选的种类走</b>。
 *
 * <h2>作者指定的机制</h2>
 * <ul>
 *   <li>在径向菜单里选中某种球之后，不必副手持弹，弩也能用</li>
 *   <li>弹药直接从收纳袋里取，<b>按耐久从低到高</b>消耗（先把旧的打出去）</li>
 *   <li><b>切换选中弹药时，副手那颗别的种类的球要换成目标种类的</b> ——
 *       副手拿着用旧的空心铁球，切到木球 / 铁球 / 圆石球时，副手就该跟着换成新选的那种
 *       （作者 2026-10-07 明确要求）</li>
 * </ul>
 *
 * <h2>副手各种情况怎么办</h2>
 * <table border="1">
 *   <caption>副手处理规则</caption>
 *   <tr><th>副手拿着</th><th>动作</th></tr>
 *   <tr><td><b>空的</b></td><td>从袋子里抽一颗选中种类的放进去</td></tr>
 *   <tr><td><b>别的种类的球</b></td><td><b>换掉</b>：旧的收回袋子中转槽
 *       （收不下退背包、再不行掉地上），换上选中种类的</td></tr>
 *   <tr><td><b>就是选中种类的球</b></td><td>不动 —— 手上这颗按玩家的来</td></tr>
 *   <tr><td><b>非球物品</b>（火把之类）</td><td>不动</td></tr>
 * </table>
 *
 * <p>换之前会先确认「袋子里确实抽得到目标种类的球」，抽不到就整段不动 ——
 * 免得把玩家副手那颗白白收走。</p>
 *
 * <h2>怎么做到「不持弹也能射」</h2>
 * <p>不去碰弩的类，只是往副手悄悄塞一颗弹，装填与发射全程走原版流程 ——
 * 所以不会跟别的改弩的模组打架。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallAmmoAutoLoader {

    private BallAmmoAutoLoader() {
    }

    /**
     * 【状态】上一刻主手是不是弩 —— 只用来认「**刚刚**从弩切走」这个瞬间。
     *
     * <p>不记这个的话，「主手不是弩」每刻都成立，副手那颗球会被反复收回 ——
     * 而那正是作者 2026-10-15 报的「按整理时副手那颗变成球堆掉进物品栏」。</p>
     */
    private static final java.util.Map<java.util.UUID, Boolean> WAS_HOLDING_CROSSBOW =
            new java.util.concurrent.ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();

        ItemStack mainHand = player.getMainHandItem();

        // ===== ⓪ 主手**从弩切走**的那一刻 → 把副手那颗球收回收纳袋（作者 2026-10-15 要求）=====
        //
        // ⚠️ 顺序很关键：这一条必须排在「主手得是弩」的 return **之前**。
        //    原来的写法是「主手不是弩就 return」，于是副手那颗自动补上去的球
        //    会一直挂在手上 —— 切到镐子、切到剑都还在，玩家得自己拿掉。
        //    作者要的是「主手从持弩状态切换到其它状态时，袋子主动收回副手的球」。
        //
        // ⚠️⚠️ 但**只能在这一个瞬间收**，不能每刻收。原来的判据是单纯的
        //      `!(mainHand.getItem() instanceof CrossbowItem)` —— 那个条件**每刻都成立**
        //      （只要主手不是弩），于是：
        //        副手的球被收走 → 自动补弹下一 tick 又补一颗 → 再被收走 …
        //      玩家看到的是一次「整理 / 切物品」之后，副手那颗变成球堆掉进物品栏、
        //      而副手上同时又多了一颗（作者 2026-10-15 反馈）。
        //      改成「**上一刻是弩、这一刻不是**」才算「切走」。
        //
        //    只收「是球」的副手 —— 副手拿着火把 / 盾牌之类的，一律不碰。
        boolean holdingCrossbow = mainHand.getItem() instanceof CrossbowItem;
        Boolean wasHolding = WAS_HOLDING_CROSSBOW.put(player.getUUID(), holdingCrossbow);
        boolean justSwitchedAway = wasHolding != null && wasHolding && !holdingCrossbow;

        if (!holdingCrossbow) {
            if (justSwitchedAway) {
                recallOffhandBall(player);
            }
            return;
        }

        // ① 主手是弩，但已经装填好了 → 不用管（子弹已经在膛里）
        if (CrossbowItem.isCharged(mainHand)) {
            return;
        }

        // ② ===== 必须装备了收纳袋（作者 2026-10-15 要求）=====
        //
        // ⚠️ 原来这里没有这一条，于是「主手拿着弩 + 轮盘选过弹药」就会往副手塞球 ——
        //    哪怕身上一个袋子都没有。作者反馈的「无论是否装备魔丸收纳袋都会被自动填充」
        //    就是这个。
        //
        //    本机制的定位是「袋子里的弹药自动补到副手」（见类注释第 18-19 行：
        //    「弹药直接从收纳袋里取」）—— 没有袋子，就没有「自动补弹」这件事可言，
        //    副手该由玩家自己管。
        if (BallPouchHelper.allPouchStacks(player).isEmpty()) {
            return;
        }

        // ③ 得在轮盘里选过弹药（存的是带组件的样本球，不是物品 id —— 组合球只能这样区分）
        ItemStack wanted = BallAmmoSelection.selected(player);
        if (wanted.isEmpty()) {
            return;
        }

        // ③ 看副手：
        //    · 拿着非球物品 → 完全不碰
        //    · 拿着同种球、且**耐久不比袋里最旧的那颗更差** → 不用换
        //
        //    ⚠️ 后半句的「耐久」不能省：只比「同种」的话，副手拿着满耐久的球时就直接返回，
        //    袋里那颗刚回收、已经用过的同类球就永远轮不上 —— 而「先把旧的打出去」
        //    正是作者要求的行为（作者反馈的「袋里只有满耐久时，用过的同类球不会替换到副手」）。
        ItemStack offhand = player.getOffhandItem();
        boolean occupied = !offhand.isEmpty();
        if (occupied && !BallAmmo.isBall(offhand)) {
            return;
        }
        if (occupied
                && BallPouchItem.sameKind(offhand, wanted)
                && BallPouchItem.remainingToughness(offhand)
                        <= BallPouchItem.lowestToughnessInPouches(player, wanted)) {
            // 诊断：副手已经是最该用的那颗 → 正常跳过。
            // 排查「补不上来」时需要它，否则分不清是「合理跳过」还是「逻辑没走到」。
            diagnose(player, "副手已是最旧的一颗，跳过", wanted, offhand);
            return;
        }

        // ④ 先抽一颗目标种类的球（抽不到就整段不动，别白换掉副手那颗）
        ItemStack taken = ItemStack.EMPTY;
        for (ItemStack pouch : BallPouchHelper.allPouchStacks(player)) {
            taken = BallPouchItem.takeLowestDurability(pouch, wanted);
            if (!taken.isEmpty()) {
                break;
            }
        }
        if (taken.isEmpty()) {
            diagnose(player, "袋子里取不到这种球", wanted, offhand);
            return;
        }

        // ⑤ 副手原来那颗先收好 —— 收回袋子中转槽，退而求其次进背包，实在不行掉地上。
        //    无论如何不能让它凭空消失：那是玩家的东西（多半还是一颗用过的球）。
        if (occupied) {
            ItemStack old = offhand.copy();
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            BallPouchHelper.Delivery delivery = BallPouchHelper.deliverReturned(player, old);
            if (delivery == BallPouchHelper.Delivery.NOWHERE) {
                player.drop(old, false);
            } else if (delivery == BallPouchHelper.Delivery.INVENTORY_TRANSIT_FULL) {
                // 作者指定：中转区满时，在物品栏上方用一串白字说明这颗球去哪了。
                // 26.2 里这个「物品栏上方」的方法叫 sendOverlayMessage（老的 displayClientMessage(…, true) 已没了）
                player.sendOverlayMessage(
                        Component.translatable("message.more_balls.transit_full"));
            }
        }

        // ⑥ 换上刚抽到的那颗，剩下的交给弩自己装填
        player.setItemInHand(InteractionHand.OFF_HAND, taken);

        // 从袋子里抽走了一颗 ⇒ 袋子内容变了，让客户端跟上。
        // （否则 HUD 上那个弹药数会一直显示旧值 —— 作者 2026-10-15 报的「不实时更新」）
        BallPouchHelper.syncPouchToClient(player);
    }

    /**
     * <b>把副手那颗球收回袋子</b> —— 主手从弩切到别的东西时调用（作者 2026-10-15 要求）。
     *
     * <h2>为什么只在「有袋子」时收</h2>
     * <p>袋子不在身上时「收回」无处可去，只能塞进背包 —— 而背包本来就是玩家自己管的，
     * 那样反而像模组擅自挪了东西。所以没袋子就什么都不做，副手保持原样。</p>
     *
     * <h2>只收「是球」的副手</h2>
     * <p>副手拿着火把 / 盾牌 / 别的物品时一律不碰 —— 那是玩家自己的搭配。</p>
     *
     * <p>收回走 {@link BallPouchHelper#deliverReturned}，和弩射完的回收是同一套：
     * 优先袋子中转槽、退而进背包、实在不行掉地上。绝不会让那颗球凭空消失。</p>
     */
    private static void recallOffhandBall(Player player) {
        ItemStack offhand = player.getOffhandItem();
        if (offhand.isEmpty() || !BallAmmo.isBall(offhand)) {
            return;
        }
        if (BallPouchHelper.allPouchStacks(player).isEmpty()) {
            return;   // 没袋子 —— 不擅自挪玩家的东西
        }
        ItemStack old = offhand.copy();
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        BallPouchHelper.Delivery delivery = BallPouchHelper.deliverReturned(player, old);
        if (delivery == BallPouchHelper.Delivery.NOWHERE) {
            player.drop(old, false);
        } else if (delivery == BallPouchHelper.Delivery.INVENTORY_TRANSIT_FULL) {
            player.sendOverlayMessage(
                    Component.translatable("message.more_balls.transit_full"));
        }
    }

    /**
     * 补弹诊断补弹诊断 —— 只在「本该补弹却没补」的地方调用，并做限流避免每刻刷屏。
     *
     * <p>打出的量：选中的是哪种球、副手现在拿着什么/耐久多少、袋子里同种最低耐久、
     * 身上有几个收纳袋。这四个数一摆，「补不上来」的几种可能（没选、袋子没找到、
     * 同种没匹配上、副手判定跳过）立刻能分开。</p>
     */
    private static long lastDiagnoseTick = -100L;

    private static void diagnose(Player player, String reason, ItemStack wanted, ItemStack offhand) {
        long now = player.level().getGameTime();
        if (now - lastDiagnoseTick < 40L) {   // 2 秒最多一条
            return;
        }
        lastDiagnoseTick = now;
        MoreBalls.LOGGER.debug(
                "[ball] 补弹未发生（{}）：选中={} / 副手={}（耐久 {}）/ 袋中同种最低耐久 {} / 收纳袋 {} 个",
                reason,
                wanted.getHoverName().getString(),
                offhand.isEmpty() ? "空" : offhand.getHoverName().getString(),
                BallPouchItem.remainingToughness(offhand),
                BallPouchItem.lowestToughnessInPouches(player, wanted),
                BallPouchHelper.allPouchStacks(player).size());
    }

    /**
     * 玩家登出时清掉「上一刻是不是持弩」的记录。
     *
     * <p>不清的话它会一直挂在表里（每条只有一个 UUID + boolean，量很小，
     * 但长期开放的服务器上没必要留着）。</p>
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        WAS_HOLDING_CROSSBOW.remove(event.getEntity().getUUID());
    }
}
