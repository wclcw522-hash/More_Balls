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

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();

        // ① 主手得是弩，而且还没装填
        ItemStack mainHand = player.getMainHandItem();
        if (!(mainHand.getItem() instanceof CrossbowItem) || CrossbowItem.isCharged(mainHand)) {
            return;
        }

        // ② 得在轮盘里选过弹药（存的是带组件的样本球，不是物品 id —— 组合球只能这样区分）
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
    }

    /**
     * 补弹诊断 —— 只在「本该补弹却没补」的地方调用，并做限流避免每刻刷屏。
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
        MoreBalls.LOGGER.info(
                "[ball] 补弹未发生（{}）：选中={} / 副手={}（耐久 {}）/ 袋中同种最低耐久 {} / 收纳袋 {} 个",
                reason,
                wanted.getHoverName().getString(),
                offhand.isEmpty() ? "空" : offhand.getHoverName().getString(),
                BallPouchItem.remainingToughness(offhand),
                BallPouchItem.lowestToughnessInPouches(player, wanted),
                BallPouchHelper.allPouchStacks(player).size());
    }
}
