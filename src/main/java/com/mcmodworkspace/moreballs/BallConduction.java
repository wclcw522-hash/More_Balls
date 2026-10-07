package com.mcmodworkspace.moreballs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import com.mcmodworkspace.moreballs.entity.BallProjectile;

/**
 * 【导电】—— 铜球处于实体状态时，像避雷针一样让自然闪电优先击中自己。
 *
 * <h2>先纠正一个常见误解</h2>
 * <p>原版避雷针其实<b>不吸引</b>闪电 —— 它的作用是「被劈到时输出红石信号、自身氧化」，
 * 闪电落点本身是在下雨区块里随机挑的。所以「让闪电优先劈自己」这件事
 * <b>没有原版机制可以直接复用</b>，必须自己接管落点。</p>
 *
 * <h2>做法</h2>
 * <p>监听闪电<b>实体加入世界</b>的那一刻，在它落点附近找最近的导电球（{@link #RADIUS} 格内），
 * 找到就把它挪到球身上，并让球消耗 1 点耐久。用事件而不是 Mixin，符合工作区
 * 「优先用官方事件、别注入他人核心类」的兼容性要求。</p>
 *
 * <p>选最近的而不是第一颗：同一片雷雨区可能有好多颗铜球，总得有个确定的归属。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallConduction {

    /** 导电球的引雷半径（格）—— 作者指定 32 */
    public static final double RADIUS = 32.0;

    private BallConduction() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof LightningBolt bolt)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;   // 客户端那边不用管
        }

        BallProjectile best = null;
        double bestDistance = RADIUS;
        for (BallProjectile ball : level.getEntitiesOfClass(BallProjectile.class,
                bolt.getBoundingBox().inflate(RADIUS))) {
            if (!isConductive(ball)) {
                continue;
            }
            double distance = ball.position().distanceTo(bolt.position());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = ball;
            }
        }
        if (best == null) {
            return;
        }

        // 把落点搬到球身上，然后扣它 1 点耐久
        bolt.setPos(best.getX(), best.getY(), best.getZ());
        best.onStruckByLightning();
    }

    /** 这颗实体状态的球是不是【导电】的 */
    private static boolean isConductive(BallProjectile ball) {
        if (ball.isRemoved()) {
            return false;
        }
        ItemStack stack = ball.getItem();
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        // 只认本模组的球；其它东西（含别家模组的投射物）不参与
        if (!(item instanceof BallItem)) {
            return false;
        }
        return BallBehavior.profileFor(stack).conduction();
    }
}
