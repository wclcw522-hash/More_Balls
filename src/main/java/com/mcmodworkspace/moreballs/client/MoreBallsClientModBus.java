package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.MoreBalls;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;

/**
 * <b>客户端专属、挂在 MOD 总线上的两个注册</b> —— 单独一个类，只为了一件事：
 * <b>让专用服务端根本看不到它们</b>。
 *
 * <h2>为什么必须单独一个类（2026-10-15 实测踩到的坑）</h2>
 *
 * <p>原来这两行写在 {@link MoreBalls} 的构造里：</p>
 *
 * <pre>
 * import com.mcmodworkspace.moreballs.client.MoreBallsClient;
 * modEventBus.addListener(MoreBallsClient::registerSpecialModelRenderers);
 * modEventBus.addListener(MoreBallsClient::registerReloadListeners);
 * </pre>
 *
 * <p>当时的注释断言「方法引用是延迟解析的，专用服务器永远不会加载到 MoreBallsClient」——
 * <b>那句话是错的</b>。方法引用会触发目标类的解析与校验，而 {@code MoreBallsClient}
 * 的成员签名里带着 {@code LocalPlayer} / {@code GuiGraphicsExtractor} / {@code Font}
 * 这些客户端专属类型 ⇒ 专用服务端启动时：</p>
 *
 * <pre>
 * java.lang.NoClassDefFoundError: net/minecraft/client/player/LocalPlayer
 *   at net.minecraft.world.item...  ← 由 MoreBalls 构造触发
 * Caused by: Attempted to load class net.minecraft.client.player.LocalPlayer
 *            which is not present on the dedicated server
 * ⇒ Failed to create mod instance. ModID: rotation_craft
 * </pre>
 *
 * <p>也就是说：<b>这一线的模组在专用服务端上根本起不来</b>（实测 {@code runServer} 直接 FAILED）。</p>
 *
 * <h2>怎么修的</h2>
 *
 * <p>把这两个注册搬进本类，并标注 {@code value = Dist.CLIENT} ——
 * FML 只在客户端加载这个类，服务端连碰都不会碰。
 * 本类只是**转发**到 {@link MoreBallsClient} 里原有的实现，避免把代码搬来搬去。</p>
 *
 * <p>⚠️ <b>总线不用手写</b>：26.2 的 {@code @EventBusSubscriber} 已经没有 {@code bus} 参数了
 * （写了会「找不到符号」），FML 会按事件类型自己判断 —— 这两个事件都是
 * {@code IModBusEvent}，会自动落到 mod 总线上。挂错总线的症状极其隐蔽：
 * 模型静默变空白、日志里一条报错都没有，所以这里特意写清楚。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID, value = Dist.CLIENT)
public final class MoreBallsClientModBus {

    private MoreBallsClientModBus() {
    }

    /** 资源包重载时清掉「按贴图算出来的组合球缓存」 */
    @SubscribeEvent
    public static void registerReloadListeners(AddClientReloadListenersEvent event) {
        MoreBallsClient.registerReloadListeners(event);
    }

    /** 组合球装填到弩上时的 special 渲染器 */
    @SubscribeEvent
    public static void registerSpecialModelRenderers(RegisterSpecialModelRendererEvent event) {
        MoreBallsClient.registerSpecialModelRenderers(event);
    }
}
