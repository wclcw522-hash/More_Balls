package com.mcmodworkspace.moreballs.network;

import com.mcmodworkspace.moreballs.BallClusterTidy;
import com.mcmodworkspace.moreballs.MoreBalls;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * <b>「一键整理」</b> —— 客户端请求服务端把背包里的散球收成球堆。
 *
 * <h2>为什么必须过一趟服务端</h2>
 * <p>整理要改的是玩家的<b>背包内容</b>，那是服务端权威的数据 —— 客户端自己改了不算数，
 * 下次同步就被覆盖回去。所以按钮只负责「发一个请求」，真正干活的是
 * {@link BallClusterTidy#tidy}。</p>
 *
 * <h2>为什么没有字段</h2>
 * <p>整理的范围就是「当前玩家的整个背包」，不需要客户端传任何参数 ——
 * 传位置反而危险（客户端可以谎报）。所以用单位码表 {@code StreamCodec.unit}，
 * 包体是空的。</p>
 *
 * <p>参考同类：{@code SelectAmmoPayload}（有字段的那种写法）。</p>
 */
public record TidyBallsPayload() implements CustomPacketPayload {

    /** 无状态，全局一个就够 —— 发的时候直接用它 */
    public static final TidyBallsPayload INSTANCE = new TidyBallsPayload();

    public static final Type<TidyBallsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "tidy_balls"));

    /** 空包体 —— {@code StreamCodec.unit} 可以按值直接编解码单例 */
    public static final StreamCodec<RegistryFriendlyByteBuf, TidyBallsPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 服务端处理：整理背包，按结果给一句反馈。
     *
     * <p>整理本来就是「玩家主动点的按钮」，所以成功时回一句「收好了 N 颗」是合适的；
     * 没什么可整理时<b>不吭声</b> —— 免得玩家连点几次被刷屏。</p>
     */
    public static void handle(TidyBallsPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        int moved = BallClusterTidy.tidy(player);
        if (moved > 0) {
            player.sendOverlayMessage(Component.translatable("message.more_balls.tidy_done", moved));
        }
    }
}
