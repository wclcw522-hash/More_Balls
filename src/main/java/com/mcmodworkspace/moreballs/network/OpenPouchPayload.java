package com.mcmodworkspace.moreballs.network;

import com.mcmodworkspace.moreballs.BallPouchCurios;
import com.mcmodworkspace.moreballs.MoreBalls;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 「打开饰品栏里的收纳袋」这个请求。
 *
 * <p>客户端按 G 键时发过来，服务端收到后打开对应玩家的袋子界面。</p>
 *
 * <h2>为什么按键不能直接在客户端开界面</h2>
 * <p>容器菜单是服务端权威的：真正的内容物在玩家身上那个 {@code ItemStack} 里，
 * 只有服务端能安全地读写并同步给客户端。客户端直接开一个本地菜单会出现
 * 「界面能拖、东西不动」或者两边数据打架。所以按键只负责<b>发请求</b>，
 * 由服务端调 {@code openMenu}。</p>
 *
 * <p>这个包不带任何数据 —— 服务端从玩家身上自己找袋子。</p>
 */
public record OpenPouchPayload() implements CustomPacketPayload {

    public static final OpenPouchPayload INSTANCE = new OpenPouchPayload();

    public static final Type<OpenPouchPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "open_pouch"));

    /** 空包，编解码都是空操作 */
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenPouchPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端处理：打开玩家饰品栏里的收纳袋 */
    public static void handle(OpenPouchPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        // 没装 Curios、或者饰品栏里没有袋子时什么也不做，静默失败即可
        BallPouchCurios.openWornPouch(player);
    }
}
