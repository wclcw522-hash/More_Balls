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
            BallAmmoSelection.clear(player);
            return;
        }
        ItemStack ball = payload.ammo().get(0);
        // 只接受真正的球
        if (!com.mcmodworkspace.moreballs.BallAmmo.isBall(ball)) {
            return;
        }
        BallAmmoSelection.select(player, ball);
    }
}
