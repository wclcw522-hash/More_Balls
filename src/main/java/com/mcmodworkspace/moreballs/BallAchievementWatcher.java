package com.mcmodworkspace.moreballs;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 「状态型」成就的巡查 —— 每 20 刻扫一次玩家，满足条件就发。
 *
 * <h2>为什么这两条不用事件</h2>
 * <ul>
 *   <li><b>进不去，怎么想都进不去吧！</b>（首次把球装到弩上）——
 *       装弹有好几条路（手动上膛、自动装填、轮盘选弹），逐个挂钩子容易漏，
 *       直接看「主手弩里是不是装着球」最准。</li>
 *   <li><b>身轻如燕</b>（首次为弩附魔轻盈）—— 附魔途径太多（附魔台 / 铁砧 /
 *       创造模式 / 指令 / 其它模组），没有一条统一的事件能覆盖全部。
 *       所以同样改成「看背包里有没有一把带轻盈的弩」。</li>
 * </ul>
 *
 * <p>两条判据都是「看一眼就有答案」的，而且玩家一旦达成就会永久保留那个状态
 * （装上弹／附完魔不会自己变回去），所以周期巡查不会漏 —— 顶多晚 1 秒发出来。</p>
 *
 * <p>巡查间隔 20 刻（1 秒）：这点开销远低于每刻遍历背包，而且成就本来也不需要
 * 精确到刻。原版 {@code award} 是幂等的，重复调用无害。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallAchievementWatcher {

    private BallAchievementWatcher() {
    }

    /** 巡查间隔（刻） */
    private static final int SCAN_INTERVAL = 20;

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % SCAN_INTERVAL != 0) {
            return;
        }

        // ① 主手弩里装着球 → 「进不去，怎么想都进不去吧！」
        ItemStack mainHand = player.getMainHandItem();
        if (BallAmmo.holdsChargedBall(mainHand)) {
            ModAdvancements.award(player, ModAdvancements.CROSSBOW_AMMO);
        }

        // ② 背包里（含快捷栏 / 副手 / 护甲槽）任意一把弩带【轻盈】→「身轻如燕」
        if (hasLightnessCrossbow(player)) {
            ModAdvancements.award(player, ModAdvancements.LIGHTNESS);
        }
    }

    /**
     * 找找身上有没有「附了轻盈的弩」。
     *
     * <p>只看附魔这个组件，不关心它是怎么附上去的 —— 附魔台、铁砧、
     * 创造模式直接给、其它模组的附魔手段，全都一视同仁。</p>
     */
    private static boolean hasLightnessCrossbow(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.CrossbowItem)) {
                continue;
            }
            ItemEnchantments enchantments = stack.getOrDefault(
                    DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            // 本模组的【轻盈】是数据驱动附魔，按 ResourceKey 查
            if (enchantments.keySet().stream()
                    .anyMatch(holder -> holder.is(ModEnchantments.LIGHTNESS))) {
                return true;
            }
        }
        return false;
    }
}
