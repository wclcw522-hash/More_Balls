package com.mcmodworkspace.moreballs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 【诸葛连弩】的连发 —— <b>按住右键不放即可持续射击</b>。
 *
 * <h2>改了什么行为</h2>
 * <table border="1">
 *   <caption>有 / 没有这个附魔的差别</caption>
 *   <tr><th></th><th>原版</th><th>附了【诸葛连弩】</th></tr>
 *   <tr><td>按住右键</td><td>开始装填</td><td>开始装填</td></tr>
 *   <tr><td>装填完成</td><td><b>不动</b>，等你松手</td><td><b>立刻发射</b></td></tr>
 *   <tr><td>发射之后</td><td>使用状态结束</td><td><b>立刻重新装填下一发</b></td></tr>
 * </table>
 *
 * <p>所以按住不放 = 一发接一发地自动射击，装填间隔由附魔等级决定（每级 −15%）。</p>
 *
 * <h2>为什么发射后要「重开一次装填周期」</h2>
 * <p>这是整段逻辑里唯一不显然的地方，也是写错就出 bug 的地方：</p>
 *
 * <pre>
 * CrossbowItem.onUseTick 里的判据是
 *     tickPercent = (使用总时长 − 剩余刻数) / getChargeDuration(...)  &gt;= 1.0
 * </pre>
 *
 * <p>这个「已使用刻数」在一次使用周期里是<b>单调递增</b>的。第一次装填完成后它就一直 ≥ 1.0，
 * 于是发射之后<b>下一个 tick 就会立刻把球补上</b> —— 装填时间变成 0，成了无间隔机枪，
 * 附魔那句「每级减少 15% 装填时间」也就完全失去意义。</p>
 *
 * <p>这里用 {@code stopUsingItem() + startUsingItem()} 把使用周期重置，
 * 让「已使用刻数」归零，于是每一发都老老实实等一次（被附魔缩短过的）装填时间。
 * 好处是<b>完全复用原版的装填逻辑</b> —— 装填时长、音效、附魔计算全不用自己算。</p>
 *
 * <h2>只对有附魔的弩生效</h2>
 * <p>没附【诸葛连弩】的弩，这段代码一次都不会动手（前两个 return 就挡住了），
 * 所以原版弩的「按住装填、松手发射」行为<b>原样保留</b>。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallRepeaterCrossbow {

    private BallRepeaterCrossbow() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();

        // 只在服务端做 —— 发射是服务端行为，客户端那边由实体同步跟上
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        // ① 玩家得正按着右键（使用状态是服务端权威的，客户端松手会发包过来结束它）
        if (!player.isUsingItem()) {
            return;
        }

        // ② 用的得是弩
        ItemStack stack = player.getUseItem();
        if (!(stack.getItem() instanceof CrossbowItem crossbow)) {
            return;
        }

        // ③ 得有【诸葛连弩】
        if (ModEnchantments.repeaterCrossbowLevel(player.level(), stack) <= 0) {
            return;
        }

        // ④ 得已经装填好了 —— 没好的话这一 tick 什么都不用做，等原版装填逻辑走完
        if (!CrossbowItem.isCharged(stack)) {
            return;
        }

        InteractionHand hand = player.getUsedItemHand();
        if (!ItemStack.isSameItem(stack, player.getItemInHand(hand))) {
            return;   // 手换了（换手 / 丢出去）—— 这次不管
        }

        // ⑤ 发射。直接借原版的 use()：
        //    · 它内部会用正确的 shootingPower（箭 3.15 / 烟花 1.6），不用我们自己判断弹药类型
        //    · 它调的 performShooting() 会自己把 CHARGED_PROJECTILES 清空
        //      （见 CrossbowItem L175），所以不会重复发射
        crossbow.use(serverLevel, player, hand);

        // ⑥ 重开一次装填周期 —— 见类注释「为什么发射后要重开」
        //    先在停之前确认还有弹药，没弹药就别再起新一轮，
        //    否则会留下一个「装填中但永远装不上」的使用状态（原版按下时会直接 FAIL、不会进入使用状态）
        boolean hasMoreAmmo = !player.getProjectile(stack).isEmpty();
        player.stopUsingItem();
        if (hasMoreAmmo) {
            player.startUsingItem(hand);
        }
    }
}
