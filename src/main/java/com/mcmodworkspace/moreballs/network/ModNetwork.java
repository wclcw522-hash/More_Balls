package com.mcmodworkspace.moreballs.network;

import com.mcmodworkspace.moreballs.MoreBalls;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络包注册。
 *
 * <p>目前只有一个：客户端按 G 键时请求打开饰品栏里的收纳袋。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class ModNetwork {

    private ModNetwork() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        // G 键：请求打开饰品栏里的收纳袋
        registrar.playToServer(
                OpenPouchPayload.TYPE,
                OpenPouchPayload.STREAM_CODEC,
                OpenPouchPayload::handle);

        // 径向菜单：选中某种弹药
        registrar.playToServer(
                SelectAmmoPayload.TYPE,
                SelectAmmoPayload.STREAM_CODEC,
                SelectAmmoPayload::handle);
    }
}
