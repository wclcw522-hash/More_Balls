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

    /**
     * 【球堆】图标角落那个数量 —— 见 {@link BallClusterDecorator}。
     *
     * <p>球堆是 {@code stacksTo(1)} 的，原版不会给它画堆叠数字（那个只读
     * {@code ItemStack#getCount()}），所以得自己挂一个 {@code IItemDecorator}。
     * 数字取的是球列长度，现算的。</p>
     */
    @SubscribeEvent
    public static void onRegisterItemDecorations(
            net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent event) {
        event.register(com.mcmodworkspace.moreballs.ModItems.BALL_CLUSTER.get(),
                BallClusterDecorator.INSTANCE);
    }

    /** 球的投射物复用原版 ThrownItemRenderer：按实体携带的物品堆渲染，无需写渲染代码 */
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.BALL.get(), BallRenderer::new);
        event.registerEntityRenderer(ModEntities.BALL_ENDER_PEARL.get(), ThrownItemRenderer::new);
    }

    /** 把收纳袋的菜单绑到界面上 */
    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.BALL_POUCH.get(), BallPouchScreen::new);
    }

    /** 【临时诊断】球堆模型只在第一帧探一次 */
    private static boolean clusterModelDiagnosed = false;

    /**
     * 【临时诊断】球堆的 `minecraft:special` 模型**到底有没有接上**。
     *
     * <p>作者 2026-10-15：「球堆贴图一点变化没有」—— 而我先后改过 4 次 `getExtents` 都毫无变化。
     * 这一探能一次性判定（**主菜单阶段就能跑，不需要进世界**）：</p>
     *
     * <pre>
     * state 非空 + extents 打出 (0,0,0)/(16,16,16)  ⇒ 我的渲染器在跑 ⇒ 问题只在 pose ⇒ Plan B 就是答案
     * extents 打出别的值 / 一个顶点都没有          ⇒ 走的是原版兜底 ⇒ special 没接上，要回头查 JSON
     * 抛异常                                      ⇒ 模型压根没解析出来
     * </pre>
     *
     * <p>用**空栈**就够 —— `getExtents` 的输出由模型本身决定，与物品内容无关；
     * `extractArgument` 对空壳会返回空栈，那只会让 `submit` early return，不影响这里的判断。</p>
     */
    @SubscribeEvent
    public static void diagnoseClusterModelOnFirstFrame(
            net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (clusterModelDiagnosed) {
            return;
        }
        clusterModelDiagnosed = true;
        try {
            Minecraft mc = Minecraft.getInstance();
            // ⚠️⚠️ 必须先等「进了世界」—— 主菜单阶段**注册表还没绑定**，
            //     `new ItemStack(...)` 会直接抛
            //     `NullPointerException: Components not bound yet`
            //     （ItemStack 构造里要 `holder.components()`）。
            //     这也顺带说明：物品栈在进世界前根本构造不出来 ⇒
            //     球堆的模型解析与渲染必然发生在进世界之后 ⇒ **主菜单阶段无法探测**。
            if (mc.level == null) {
                clusterModelDiagnosed = false;   // 还没到时候 —— 下一 tick 再看
                return;
            }
            // 分别用 GUI 与 NONE 解析一遍，把「实际包围盒」打出来 ——
            // 这是唯一能不靠肉眼判断「几何到底多大、落在哪」的办法。
            // 8 个 extents 顶点 + 包围盒放在一起看，就能反推出外层那个 scale(16)
            // 到底把几何放大了多少倍。
            for (net.minecraft.world.item.ItemDisplayContext ctx
                    : new net.minecraft.world.item.ItemDisplayContext[] {
                            net.minecraft.world.item.ItemDisplayContext.GUI,
                            net.minecraft.world.item.ItemDisplayContext.NONE }) {
                net.minecraft.client.renderer.item.ItemStackRenderState st =
                        new net.minecraft.client.renderer.item.ItemStackRenderState();
                mc.getItemModelResolver().updateForTopItem(
                        st,
                        new ItemStack(com.mcmodworkspace.moreballs.ModItems.BALL_CLUSTER.get()),
                        ctx, null, null, 0);
                MoreBalls.LOGGER.info("[球堆][诊断] ctx={} isEmpty={} oversized={} 包围盒={}",
                        ctx, st.isEmpty(), st.isOversizedInGui(), st.getModelBoundingBox());
                st.visitExtents(v -> MoreBalls.LOGGER.info(
                        "[球堆][诊断]   extents 顶点 ({}, {}, {})", v.x(), v.y(), v.z()));
            }
        } catch (Throwable t) {
            MoreBalls.LOGGER.warn("[球堆][诊断] 探测失败", t);
        }
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

        // ===== ★ 球堆：手上是**壳**，能不能蓄力要看**壳里那颗球**（作者 2026-10-15）=====
        //
        // ⚠️⚠️ 这里原先直接问 `BallBehavior.handles(stack)` —— 而壳**不在** `#more_balls:balls`
        //    标签里，`handles` 对它返回 false ⇒ **本方法当场 return** ⇒
        //    那条「蓄力 x / y」在手持球堆蓄力时**永远不显示**。
        //
        //    作者原话：「拿着球堆蓄力时那行"蓄力x/x"还是没有显示」—— 说的就是这一处。
        //    （我 1.1.0.21 给壳补 `getUseDuration` 是另一条路，跟这行字无关。）
        //
        //    `asBall` 把壳摊成里面**耐久最低**的那颗球（散球原样返回），
        //    后面的 profile / 等级计算就都跟着正确了。
        ItemStack probe = com.mcmodworkspace.moreballs.BallClusterItem.isCluster(stack)
                ? com.mcmodworkspace.moreballs.BallClusterItem.asBall(stack)
                : stack;
        if (!BallBehavior.handles(probe)) {
            return;
        }
        BallBehavior.BallProfile profile = BallBehavior.profileFor(probe);
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

        // 【球堆】图标 = 里面那颗球（作者 2026-10-15 指定）
        //
        // 与上面那条同理：必须由 MoreBalls 在 **mod 总线**上调用本方法，
        // 靠 @EventBusSubscriber（GAME 总线）挂不上 —— 症状会是没有报错的静默空白图标。
        event.register(
                BallClusterSpecialRenderer.Unbaked.ID,
                BallClusterSpecialRenderer.Unbaked.CODEC);
    }
    @SubscribeEvent
    public static void onRegisterSelectProperties(RegisterSelectItemModelPropertyEvent event) {
        event.register(Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "charged_ball"),
                ChargedBall.TYPE);
    }

}
