package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.shared.core.ModLog;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * [更多球] More_Balls —— 入口类（NeoForge 26.2 版本线）。
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

        // 战利品箱里生成球（作者 2026-10-18）—— LootTableLoadEvent 是 mod 总线事件，
        // 而 26.2 的 @EventBusSubscriber 已没有 bus 属性，所以在这里显式挂。
        modEventBus.addListener(BallLootTables::onLootTableLoad);

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

        // ===== 组合球的 special 渲染器 + 资源包重载缓存 =====
        //
        // ⚠️⚠️ 这两件事**不在这里注册** —— 它们在客户端专属的
        //     `client/MoreBallsClientModBus`（标注 `value = Dist.CLIENT` + `bus = Bus.MOD`）。
        //
        //     原来这里是 `modEventBus.addListener(MoreBallsClient::registerSpecialModelRenderers)`，
        //     注释还断言「方法引用是延迟解析的，专用服务器永远不会加载到 MoreBallsClient」——
        //     **那句话是错的**：方法引用会触发目标类的解析与校验，而 MoreBallsClient 的
        //     成员签名里带着 LocalPlayer / GuiGraphicsExtractor / Font 等客户端专属类型
        //     ⇒ **专用服务端启动时直接**
        //
        //         java.lang.NoClassDefFoundError: net/minecraft/client/player/LocalPlayer
        //         Caused by: Attempted to load class … which is not present on the dedicated server
        //
        //     **这一线在专用服务端上根本起不来**（作者 2026-10-15，runServer 实测 FAILED）。
        //     修法见 `MoreBallsClientModBus` 的类注释。
        //
        //     顺带：那两条注册必须挂在 **mod 总线**上 —— 它们的事件都声明了
        //     `implements IModBusEvent`，挂错总线的症状极其隐蔽（模型静默变空白、日志无报错）。

        LOGGER.info("[{}] 初始化完成：分类「球」、标签 #{}:balls、右键投掷（含蓄力）、弩弹药",
                MOD_ID, MOD_ID);
    }
}
