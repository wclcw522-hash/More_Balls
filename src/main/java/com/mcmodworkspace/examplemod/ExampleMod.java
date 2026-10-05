package com.mcmodworkspace.examplemod;

import com.mcmodworkspace.shared.core.ModLog;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import org.slf4j.Logger;

/**
 * Example NeoForge Mod —— 入口类（NeoForge 26.2 版本线）。
 *
 * <p>本文件位于各版本线的 {@code src/} 中，属于「版本特有」代码，不参与跨版本同步。
 * 因为它引用的 {@code net.neoforged.fml} API 在各版本间签名不保证一致。</p>
 *
 * <p>公共业务逻辑请一律写在 {@code .shared/shared-code} 下，由 syncSharedCode 任务
 * 同步进来，从而做到「一处修改、全版本生效」。</p>
 */
@Mod(ExampleMod.MOD_ID)
public class ExampleMod {

    public static final String MOD_ID = "examplemod";
    public static final Logger LOGGER = ModLog.of(MOD_ID);

    /**
     * NeoForge 会在构造时注入事件总线，因此无需再用
     * {@code FMLJavaModLoadingContext.get().getModEventBus()} 这类旧写法。
     */
    public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[{}] 初始化完成，运行于 NeoForge 26.2 版本线", MOD_ID);
    }
}
