package com.mcmodworkspace.moreballs.network;

import com.mcmodworkspace.moreballs.BallAmmoSelection;
import com.mcmodworkspace.moreballs.MoreBalls;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * 「在径向菜单里选中某种弹药」。
 *
 * <p>客户端把选中的<b>样本球</b>发上来，服务端写进玩家的
 * {@link com.mcmodworkspace.moreballs.ModAttachments#SELECTED_AMMO}。
 * 之所以要过一趟服务端：这个状态会影响服务端的自动装填逻辑（弩从袋子里取弹），
 * 客户端自己改了不算数。</p>
 *
 * <p><b>为什么传 ItemStack 而不是物品 id 字符串</b>：组合球全都是同一个物品 id，
 * 只有组件不同。传 id 的话服务端收到的是「一颗裸 combo_ball」，
 * 自动装填就分不清玩家到底选了铁-金球还是木-圆石球 ——
 * 作者反馈的「轮盘合并 / 放不到副手」正出在这里。
 * 传整颗样本球（带组件）才带得住这个区别；样本里的耐久会在 select 时被抹掉。</p>
 *
 * <p>传空栈表示「取消选择」。</p>
 */
public record SelectAmmoPayload(List<ItemStack> ammo) implements CustomPacketPayload {

    public static final Type<SelectAmmoPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "select_ammo"));

    /**
     * 用 {@code List<ItemStack>} 而不是裸 {@code ItemStack}：
     * 空列表表示「取消选择」，而 {@code ItemStack} 的 codec <b>拒绝空栈</b>
     * （{@code Item must not be minecraft:air}）。列表没有这个限制。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectAmmoPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()), SelectAmmoPayload::ammo,
                    SelectAmmoPayload::new);

    /** 单元素 = 选中那种球；空列表 = 取消选择 */
    public SelectAmmoPayload(ItemStack ball) {
        this(ball.isEmpty() ? List.of() : List.of(ball));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SelectAmmoPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (payload.ammo().isEmpty()) {
            // ===== 「取消」（作者 2026-10-10 指定）=====
            //
            // 轮盘正上方那一格现在叫「取消」。选中它要做两件事：
            //   ① 把副手那颗弹药收回收纳袋 —— 取消之后它就不该继续挂在副手上
            //      （收不下退背包、再不行掉地上，总之不能凭空消失）
            //   ② 清空选中的弹药种类 —— 自动装填只看这个状态，清空即停止填充
            BallAmmoSelection.clear(player);
            reclaimOffhandAmmo(player);
            return;
        }
        ItemStack ball = payload.ammo().get(0);
        // ===== ★ 球堆（壳）也要接受（作者 2026-10-15：「没法正确的被放置到副手」）=====
        //
        // ⚠️⚠️ 壳**不在** `#rotation_craft:balls` 标签里，`BallAmmo.isBall(壳)` 返回 false ——
        //     原先这里直接 `return`，于是**「在轮盘里选中球堆」这件事压根没生效**：
        //     轮盘看着选中了，服务端的 `SELECTED_AMMO` 却还是空的，
        //     自动装填自然不知道要往副手送什么 ⇒ 表现就是「放不到副手」。
        //
        //     摊开成壳里那颗球来判「是不是球」，判过了照旧把**原始样本**交给 select ——
        //     `BallAmmoSelection.kindOf` 那边已经会认壳并摊开（数量和合并也都按壳内颗数算了）。
        ItemStack kind = com.mcmodworkspace.moreballs.BallClusterItem.isCluster(ball)
                ? com.mcmodworkspace.moreballs.BallClusterItem.asBall(ball)
                : ball;
        if (!com.mcmodworkspace.moreballs.BallAmmo.isBall(kind)) {
            return;
        }
        BallAmmoSelection.select(player, ball);
    }

    /**
     * 把副手那颗球收好 —— 优先回收纳袋的中转槽，退而求其次进背包，实在不行掉地上。
     *
     * <p>与 {@code BallAmmoAutoLoader} 换弹时的收尾同一套逻辑，
     * 复用 {@link com.mcmodworkspace.moreballs.BallPouchHelper#deliverReturned}。</p>
     */
    private static void reclaimOffhandAmmo(ServerPlayer player) {
        ItemStack offhand = player.getOffhandItem();
        if (offhand.isEmpty() || !com.mcmodworkspace.moreballs.BallAmmo.isBall(offhand)) {
            return;   // 副手没球、拿的不是球（火把之类）—— 一律不碰
        }
        ItemStack old = offhand.copy();
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
        com.mcmodworkspace.moreballs.BallPouchHelper.Delivery delivery =
                com.mcmodworkspace.moreballs.BallPouchHelper.deliverReturned(player, old);
        if (delivery == com.mcmodworkspace.moreballs.BallPouchHelper.Delivery.NOWHERE) {
            player.drop(old, false);
        } else if (delivery == com.mcmodworkspace.moreballs.BallPouchHelper.Delivery.INVENTORY_TRANSIT_FULL) {
            player.sendOverlayMessage(
                    net.minecraft.network.chat.Component.translatable("message.rotation_craft.transit_full"));
        }
    }
}
