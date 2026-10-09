package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.BallBehavior;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;
import com.mcmodworkspace.moreballs.BallCharge;
import com.mcmodworkspace.moreballs.ModEntities;
import com.mcmodworkspace.moreballs.MoreBalls;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import com.mcmodworkspace.moreballs.ModMenus;
import com.mcmodworkspace.moreballs.client.BallPouchScreen;
import net.neoforged.neoforge.client.event.RegisterSelectItemModelPropertyEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 客户端专属：实体渲染器注册 + 蓄力等级 HUD。
 *
 * <p>用 {@link EventBusSubscriber} 的 {@code value = Dist.CLIENT} 做隔离：
 * FML 只在客户端加载并注册这个类，专用服务端上它根本不会被触碰，
 * 也就不会把客户端专属类拖进服务端的类加载器。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID, value = Dist.CLIENT)
public final class MoreBallsClient {

    /** HUD 文字与物品名的水平间距：物品名画在 hotbar 上方，蓄力条再往上让一格 */
    private static final int HUD_ABOVE_HOTBAR = 72;

    private static final int CHARGE_TEXT_COLOR = 0xFFFFC04D;

    private MoreBallsClient() {
    }

    /**
     * 资源包重载时清掉按贴图算出来的缓存。
     *
     * <p>弩上那套组合球贴图是「读底图 + 逐像素叠象限」在 CPU 上现合成的，
     * 资源包一换，底图与象限贴图都变了，旧结果全错 —— 不清就会一直显示旧资源包的图。</p>
     */
    public static void registerReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(
                Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "combo_charge_cache"),
                new ComboChargeReload());
    }

    private static final class ComboChargeReload extends SimplePreparableReloadListener<Void> {

        @Override
        protected Void prepare(ResourceManager manager, ProfilerFiller profiler) {
            return null;
        }

        @Override
        protected void apply(Void unused, ResourceManager manager, ProfilerFiller profiler) {
            ComboChargeBallRenderer.clearCache();
        }
    }

    /** 球的投射物复用原版 ThrownItemRenderer：按实体携带的物品堆渲染，无需写渲染代码 */
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {        event.registerEntityRenderer(ModEntities.BALL.get(), BallRenderer::new);
        event.registerEntityRenderer(ModEntities.BALL_ENDER_PEARL.get(), ThrownItemRenderer::new);
    }

    /** 把收纳袋的菜单绑到界面上 */
    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.BALL_POUCH.get(), BallPouchScreen::new);
    }

    /**
     * 蓄力等级显示 —— 位置在物品栏上方、物品名称上方（作者指定）。
     *
     * <p>只在「手里正拿着可蓄力的球并且正在蓄力」时出现。</p>
     */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !player.isUsingItem()) {
            return;
        }

        ItemStack stack = player.getUseItem();
        if (!BallBehavior.handles(stack)) {
            return;
        }
        BallBehavior.BallProfile profile = BallBehavior.profileFor(stack);
        int maxLevel = profile.chargeLevels();
        if (maxLevel <= 0) {
            return;
        }

        int level = BallCharge.levelAt(BallCharge.usedTicks(player), maxLevel);
        // 常态不显示：只有真正蓄上力（等级 ≥ 1）才把指示露出来（作者指定）。
        // 免得刚按下右键、还没蓄满一级时先闪一个「蓄力 0/3」出来。
        if (level < 1) {
            return;
        }
        Component label = Component.translatable("hud.more_balls.charge", level, maxLevel);

        GuiGraphicsExtractor graphics = event.getGuiGraphics();
        Font font = minecraft.font;
        int centerX = minecraft.getWindow().getGuiScaledWidth() / 2;
        int y = minecraft.getWindow().getGuiScaledHeight() - HUD_ABOVE_HOTBAR;
        graphics.centeredText(font, label, centerX, y, CHARGE_TEXT_COLOR);
    }

    /**
     * 注册物品模型属性 {@code more_balls:charged_ball}。
     *
     * <p>弩拉满时要根据「装的是不是球」换模型，而原版的 {@code charge_type} 是个封闭枚举
     * （只有箭 / 烟花火箭 / 空），加不了新值 —— 所以自己起一个属性。
     * 弩的模型文件最外层先问它，不是球就整个落回原版那套逻辑。</p>
     */
    /**
     * 注册组合球装填外观用的 {@code minecraft:special} 渲染器。
     *
     * <p><b>由 {@link com.mcmodworkspace.moreballs.MoreBalls} 在 mod 总线上调用</b>，
     * 而不是靠本类的 {@code @EventBusSubscriber} —— 那个挂在 GAME 总线上，
     * 而 {@code RegisterSpecialModelRendererEvent} 是 {@code IModBusEvent}，
     * 挂错总线不会触发，症状是模型静默空白。</p>
     */
    public static void registerSpecialModelRenderers(RegisterSpecialModelRendererEvent event) {
        MoreBalls.LOGGER.info("[ball][弩] ⓪ 注册 special 渲染器 more_balls:combo_charge_ball —— 事件已触发");
        event.register(
                Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "combo_charge_ball"),
                ComboChargeBallRenderer.Unbaked.MAP_CODEC);
    }
    @SubscribeEvent
    public static void onRegisterSelectProperties(RegisterSelectItemModelPropertyEvent event) {
        event.register(Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "charged_ball"),
                ChargedBall.TYPE);
    }

}
