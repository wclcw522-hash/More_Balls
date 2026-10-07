package com.mcmodworkspace.moreballs;

import net.minecraft.server.level.ServerPlayer;
import com.mcmodworkspace.moreballs.entity.BallProjectile;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.List;
import java.util.Optional;

/**
 * Curios 饰品联动的运行时入口。
 *
 * <h2>优雅降级</h2>
 * <p>整个模组<b>不硬依赖 Curios</b>：没装的时候这个类的每个方法都安全返回，
 * 收纳袋照常能拿在手里右键打开，只是少了「挂在饰品栏」「按键打开」这些便利。</p>
 *
 * <h2>提供的联动</h2>
 * <ul>
 *   <li><b>挂在饰品栏</b> —— 见 {@link BallPouchCurioItem}</li>
 *   <li><b>自动收纳</b> —— 佩戴者经过停着的球时自动收进袋子（{@link #autoCollect}）</li>
 *   <li><b>按键打开</b> —— G 键打开佩戴的袋子（{@link #openWornPouch}）</li>
 * </ul>
 */
public final class BallPouchCurios {

    private BallPouchCurios() {
    }

    /** Curios 的 mod id */
    public static final String CURIOS_ID = "curios";

    /** 自动收纳的范围（格） */
    private static final double COLLECT_RADIUS = 2.0D;

    /** 每几刻扫一次身边的球 —— 逐刻扫太浪费 */
    private static final int COLLECT_INTERVAL_TICKS = 10;

    /** 这个存档里装没装 Curios */
    public static boolean isLoaded() {
        return ModList.get().isLoaded(CURIOS_ID);
    }

    // ===== 找佩戴的袋子 =====

    /**
     * 找玩家<b>饰品栏里</b>的收纳袋。
     *
     * <p>用 Curios 的 API 遍历装备的饰品槽。没装 Curios 时直接返回空。</p>
     */
    public static ItemStack findWornPouch(Player player) {
        if (!isLoaded()) {
            return ItemStack.EMPTY;
        }
        Optional<ItemStack> found = BallPouchCurioAccess.findWorn(player);
        return found.orElse(ItemStack.EMPTY);
    }

    /**
     * 找玩家<b>饰品栏里所有</b>的收纳袋。
     *
     * <p>收纳袋可以戴在背饰 / 腰带 / 护符三个槽上，玩家可能同时戴好几个 ——
     * 统计弹药种类、自动装填找最低耐久的那颗，都得把每个袋子都看一遍。</p>
     *
     * <p>没装 Curios 时返回空列表（优雅降级一律走这一条）。</p>
     */
    public static List<ItemStack> findAllWornPouches(Player player) {
        if (!isLoaded()) {
            return List.of();
        }
        return BallPouchCurioAccess.findAllWorn(player);
    }

    /** 玩家身上（饰品栏 <b>或</b> 背包）有没有装着球的袋子 */
    public static boolean hasWornBall(Player player) {
        ItemStack worn = findWornPouch(player);
        if (!worn.isEmpty() && BallPouchItem.hasAnyBall(worn)) {
            return true;
        }
        return BallPouchHelper.hasSameKind(player, new ItemStack(ModItems.WOODEN_BALL.get()));
    }

    // ===== 自动收纳 =====

    /**
     * 把佩戴者身边停着的球收进袋子 —— 由 {@link BallPouchCurioItem#curioTick} 每刻调用。
     *
     * <p>只有袋子里<b>确实有空位</b>时才收（{@code insert} 会返回 false），
     * 收不下的球原样留在地上，不会被吞掉。</p>
     */
    public static void autoCollect(LivingEntity wearer, ItemStack pouch) {
        // 限流：每 10 刻扫一次足够，逐刻遍历实体列表没必要
        if (wearer.tickCount % COLLECT_INTERVAL_TICKS != 0) {
            return;
        }
        List<BallProjectile> balls = wearer.level().getEntitiesOfClass(
                BallProjectile.class,
                wearer.getBoundingBox().inflate(COLLECT_RADIUS),
                ball -> ball.isAlive() && ball.isSettled());

        for (BallProjectile ball : balls) {
            ItemStack stack = ball.getItem().copy();
            if (stack.isEmpty()) {
                continue;
            }
            BallItem.stripIntangible(stack);
            BallItem.applyToughness(stack, ball.getToughness());

            if (BallPouchItem.insert(pouch, stack) && stack.isEmpty()) {
                ball.discard();
            }
        }
    }

    // ===== 按键打开 =====

    /**
     * 打开饰品栏里那个袋子 —— G 键走这里。
     *
     * @return true 表示确实打开了（饰品栏里有袋子）；false 表示没有可开的袋子
     */
    public static boolean openWornPouch(ServerPlayer player) {
        ItemStack pouch = findWornPouch(player);
        if (pouch.isEmpty()) {
            return false;
        }
        player.openMenu(
                new SimpleMenuProvider(
                        (id, inventory, p) -> new BallPouchMenu(id, inventory, pouch),
                        net.minecraft.network.chat.Component.translatable("container.more_balls.ball_pouch")),
                buffer -> ItemStack.STREAM_CODEC.encode(buffer, pouch));
        return true;
    }
}
