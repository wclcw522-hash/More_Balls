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
 * <h2>动画为什么要在客户端另外处理</h2>
 * <p>服务端重开装填周期只改了服务端的 {@code useItemRemaining}，而拉弓动画取的是
 * <b>客户端自己那份</b>（那个字段不参与实体同步）。所以客户端还要再做一次同样的事，
 * 否则动画会定在拉满那一帧 —— 细节见 {@link #restartPullAnimation}。</p>
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

        // 客户端只负责「把拉弓动画重开一轮」，发射在服务端
        if (player.level().isClientSide()) {
            restartPullAnimation(player);
            return;
        }

        if (player.level() instanceof ServerLevel serverLevel) {
            fireOneShot(serverLevel, player);
        }
    }

    // ===== 服务端：真正发射 =====

    private static void fireOneShot(ServerLevel serverLevel, Player player) {
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

    // ===== 客户端：让拉弓动画循环 =====

    /**
     * <b>把「拉弓」动画重开一轮</b> —— 拉满了就从头再拉，而不是停在拉满那一帧。
     *
     * <h2>为什么服务端重开周期不够</h2>
     * <p>弩的拉弓动画（{@code crossbow_pulling_0/1/2}）由物品模型属性
     * {@code minecraft:crossbow/pull} 驱动，实现在 {@code CrossbowPull}:</p>
     *
     * <pre>
     * if (CrossbowItem.isCharged(itemStack)) return 0.0F;
     * int chargeDuration = CrossbowItem.getChargeDuration(itemStack, entity);
     * return (float)UseDuration.useDuration(itemStack, entity) / chargeDuration;
     *      //  UseDuration.useDuration = getUseDuration(72000) − useItemRemainingTicks()
     * </pre>
     *
     * <p>也就是说进度取的是<b>本端自己的 {@code useItemRemaining}</b>，而那个字段
     * <b>不参与实体同步</b> —— 两端各自 Tick 自己的那份。</p>
     *
     * <p>服务端那边 {@code stopUsingItem() + startUsingItem()} 确实把它的
     * {@code useItemRemaining} 归了零；但这两下在<b>同一个 tick 里</b>完成，
     * 同步给客户端的「正在使用」标志自始至终都是 {@code true}，
     * 于是 {@code LocalPlayer.onSyncedDataUpdated} 里那段</p>
     *
     * <pre>
     * if (serverUsingItem &amp;&amp; !this.startedUsingItem)      this.startUsingItem(serverUsingHand);
     * else if (!serverUsingItem &amp;&amp; this.startedUsingItem) this.stopUsingItem();
     * </pre>
     *
     * <p><b>一次都不会触发</b> —— 客户端的 {@code useItemRemaining} 只减不归零，
     * 进度一路涨到 1 以上，动画就永远定在拉满那一帧。</p>
     *
     * <h2>所以这里在客户端自己重开</h2>
     * <p>客户端这两个字段（{@code startedUsingItem} / {@code useItemRemaining}）
     * 本来就是客户端本地的，直接调用公开的 {@code stopUsingItem()} + {@code startUsingItem()}
     * 就能把进度归零，动画自然从 {@code pulling_0} 重新走一遍。</p>
     *
     * <p>⚠️ <b>判据不能用「已装填」</b>：客户端收到 {@code CHARGED_PROJECTILES} 时，
     * 服务端往往已经把弹打出去了（包已经清空），这个条件在客户端几乎永远不成立。
     * 这里改用「<b>已拉满</b>」（{@code 已用刻数 ≥ 装填刻数}）—— 那正是动画要折返的那一刻。</p>
     *
     * <p>两端周期等长（都是 {@code getChargeDuration}），所以重置点天然对齐，
     * 只会差开始那一下的 1 刻。</p>
     */
    private static void restartPullAnimation(Player player) {
        if (!player.isUsingItem()) {
            return;
        }
        ItemStack stack = player.getUseItem();
        if (!(stack.getItem() instanceof CrossbowItem)) {
            return;
        }
        if (ModEnchantments.repeaterCrossbowLevel(player.level(), stack) <= 0) {
            return;
        }

        int charge = CrossbowItem.getChargeDuration(stack, player);
        if (charge <= 0) {
            return;   // 防御：装填长为 0 时下面那个比较会恒真，变成每刻都在重开
        }
        int elapsed = stack.getUseDuration(player) - player.getUseItemRemainingTicks();
        if (elapsed < charge) {
            return;   // 还没拉满，让它继续拉
        }

        InteractionHand hand = player.getUsedItemHand();
        player.stopUsingItem();
        player.startUsingItem(hand);
    }
}
