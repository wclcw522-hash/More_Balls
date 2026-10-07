package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.shared.core.ModLog;
import com.mcmodworkspace.moreballs.client.MoreBallsClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * [更多球] More_Balls —— 入口类（NeoForge 26.3 版本线）。
 *
 * <p>本文件位于各版本线的 {@code src/} 中，属于「版本特有」代码，不参与跨版本同步。</p>
 */
@Mod(MoreBalls.MOD_ID)
public class MoreBalls {

    public static final String MOD_ID = "more_balls";
    public static final Logger LOGGER = ModLog.of(MOD_ID);

    public MoreBalls(IEventBus modEventBus, ModContainer modContainer) {
        ModItems.register(modEventBus);
        ModRecipes.register(modEventBus);
        ModComponents.register(modEventBus);
        ModAttachments.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        ModMenus.register(modEventBus);
        ModEffects.register(modEventBus);

        // 右键投掷 + 蓄力链路（全部走官方事件，无 Mixin）
        NeoForge.EVENT_BUS.addListener(BallThrowHandler::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(BallThrowHandler::onStartUsing);
        NeoForge.EVENT_BUS.addListener(BallThrowHandler::onStopUsing);

        // 属性表里要引用本模组的物品，必须等注册完成才能取到实例，
        // 所以 BallBehavior.init() 放到 common setup 阶段执行。
        modEventBus.addListener((FMLCommonSetupEvent event) -> BallBehavior.init());

        // 「新球适配自检」不挂在这个阶段 —— 物品标签要等世界数据包加载完才可用。
        // 实测：挂在 FMLLoadCompleteEvent 上时标签仍是空的，自检会误报「球不在标签里」。
        // 所以挪到 BallIntegrationCheck 自己的服务端启动钩子里（见那个类）。

        // 组合球装填到弩上时的外观 —— 客户端专用的 special 渲染器。
        //
        // ⚠️ 必须挂在**这个 mod 总线**上，不能靠 @EventBusSubscriber（那是 GAME 总线）：
        //    RegisterSpecialModelRendererEvent 声明了 implements IModBusEvent，
        //    挂错总线的症状极其隐蔽 —— 模型静默变空白（整把弩消失），日志里
        //    一条报错都没有，只有「模型加载不出来」这一个可见结果。
        //    这里不需要额外的「是不是客户端」判断：该事件只在客户端触发，
        //    而方法引用是延迟解析的 —— 专用服务器永远不会加载到 MoreBallsClient。
        modEventBus.addListener(MoreBallsClient::registerSpecialModelRenderers);

        LOGGER.info("[{}] 初始化完成：分类「球」、标签 #{}:balls、右键投掷（含蓄力）、弩弹药",
                MOD_ID, MOD_ID);
    }
}
