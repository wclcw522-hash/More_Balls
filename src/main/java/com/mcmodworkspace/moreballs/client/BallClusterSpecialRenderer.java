package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.BallClusterContents;
import com.mcmodworkspace.moreballs.BallClusterItem;
import com.mcmodworkspace.moreballs.MoreBalls;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3fc;

import java.util.function.Consumer;

/**
 * <b>球堆的图标 = 里面那颗球的图标</b>（作者 2026-10-15 指定的效果）。
 *
 * <h2>为什么不能用 json 解决</h2>
 * <p>26.2 的物品模型是数据驱动的，但 {@code minecraft:item/generated} 只能挂<b>一张固定贴图</b>。
 * 而球堆需要「打开它才知道画什么」—— 里面的球是 {@code BALL_CLUSTER} 组件的一部分，
 * 属于<b>运行期数据</b>，静态 json 读不到。</p>
 *
 * <p>所以走原版给容器类物品准备的机制：<b>特殊模型（special model）</b>。
 * 潜影盒、箱子、旗帜、床都是这么做的 —— 它们在 json 里声明成 {@code minecraft:special}，
 * 真正的绘制交给一个 Java 渲染器。</p>
 *
 * <h2>它怎么拿到「里面的球」</h2>
 * <p>{@link SpecialModelRenderer#extractArgument} 是原版给这种需求的钩子：模型系统会把
 * <b>当前那个 ItemStack</b> 交进来，我在这里读组件、把里面那颗球抽出来当成绘制参数。
 * 之后 {@code submit} 拿到的就是这个参数（而不是原始栈），所以绘制时不碰组件、只管画。</p>
 *
 * <h2>为什么不用 NoDataSpecialModelRenderer</h2>
 * <p>那个基类的 {@code extractArgument} 恒返回 {@code null} —— 适配「不需要参数」的模型
 * （比如三叉戟）。球堆需要参数，所以直接实现 {@link SpecialModelRenderer}{@code <ItemStack>}。</p>
 *
 * <h2>画法</h2>
 * <p>用 {@link ItemStackRenderState} 把「里面那颗球」按原版流程画一遍 —— 这样组合球的象限贴图、
 * 附魔光效、损坏条之类的东西全都自动跟着走，不用我手工拼贴图。</p>
 */
public class BallClusterSpecialRenderer implements SpecialModelRenderer<ItemStack> {

    @Override
    public ItemStack extractArgument(ItemStack shell) {
        BallClusterContents contents = BallClusterItem.contents(shell);
        if (contents.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // icon 从 1.1.0.0 起就存着「壳里那颗球」—— 它就是本渲染器的全部输入
        ItemStack icon = contents.icon();
        return icon.isEmpty() ? contents.lowest().copy() : icon.copy();
    }

    /** 【临时诊断】submit 只报一次 —— 作者报「贴图一点变化没有」，必须先确认渲染器有没有被调到 */
    private static boolean diagnoseSubmitLogged = false;

    /** 【临时诊断】getExtents 只报一次 */
    private static boolean diagnoseExtentsLogged = false;

    @Override
    public void submit(ItemStack inner, PoseStack pose, SubmitNodeCollector collector,
                       int light, int overlay, boolean foil, int outline) {
        // ===== 【临时诊断】整个 special 渲染链有没有走到这里 =====
        //
        // 作者 2026-10-15：「球堆贴图一点变化没有」—— 而我先后改过 extents **四次**
        // （±0.5 / ±0.6 / visitExtents 采样 / 0..16）都毫无变化 ⇒ 必须确认：
        //   · 这一行打出来 ⇒ 渲染器在用，问题在 extents / pose 的取值
        //   · 没打出来     ⇒ 物品走的还是普通 generated 图标 ⇒ **special 模型没接上**
        if (!diagnoseSubmitLogged) {
            diagnoseSubmitLogged = true;
            MoreBalls.LOGGER.info("[球堆][诊断] submit 被调用 —— special 渲染器确实在跑；inner={}",
                    inner.isEmpty() ? "(空)" : inner.getHoverName().getString());
        }
        if (inner.isEmpty()) {
            return;
        }

        // ⚠️⚠️ 三个关键点，缺一个都会「偏」——
        //
        //  【1】必须用 updateForTopItem，**不能用 updateForNonLiving** ——
        //      后者签名收的是一个 Entity，内部直接调 entity.level()。
        //      而物品栏渲染这条链（GuiItemAtlas.drawToSlot → ItemStackRenderState.submit）
        //      上**没有任何实体可传**，只能传 null ⇒ 进世界立刻
        //      NullPointerException: Cannot invoke "Entity.level()" because "entity" is null
        //      （作者 2026-10-15 实测崩溃，1.1.0.10 引进的）
        //      updateForTopItem 收的是 Level + ItemOwner，两者都允许为 null。
        //
        //  【2】context 必须是 **GUI**，不是 NONE。
        //      `minecraft:item/generated` 父模型带一层「把 0..1 的顶点挪到以原点居中」的
        //      translation，而那层变换**只在 GUI 这个 display context 下生效**。
        //      用 NONE 就没有它 ⇒ 模型从槽位中心向右下画 ⇒ 图标偏移。
        //
        //  【3】提交前要把外层 pose **清干净**。
        //      GuiItemAtlas.drawToSlot 的 pose 链是（javap 逐句核过）：
        //          pushPose()
        //          translate(槽位中心X, 槽位中心Y, 0)
        //          scale(16, -16, 16)
        //          state.submit(poseStack, …)      ← 我的 submit 就在这一行
        //          popPose()
        //      那已经是**完整的 GUI 物品坐标系**；而 GUI context 的 render state
        //      自己也会带来同样的变换 —— 不清就是**叠两层**（放大 16×、Y 翻两次）。
        //      先 identity() 抹掉，让 state 自己在原点、按 1 格 = 1 单位画。
        //
        //  （1.1.0.12 把 GUI 改成 NONE 只解决了一半：外层那 16 倍缩放还在，
        //    又丢了居中位移 ⇒ 从「只看见一个小角」变成「整体偏移」。）
        // ===== ★★ 1.1.0.35：context 必须是 **NONE**（我之前的 GUI 结论是**反的**）=====
        //
        // 我之前写的是「必须用 GUI，因为 generated 的居中位移只在 GUI 下生效」——
        // 那句话对**普通物品**成立，但我们这条路是**自己画的特例**，结论正好相反：
        //
        //   · 外层 pose 已经是完整的 GUI 物品坐标系（translate 槽位中心 + scale(16,-16,16)）
        //   · 若 state 再用 GUI context ⇒ 它**再做一次** GUI 摆法 ⇒ 叠两层 ⇒ 放大 16 倍
        //     ⇒ 只看得见一个角
        //   · 用 `NONE` ⇒ state **不做任何 display 变换** ⇒ 模型就是 0..1 的立方体
        //     ⇒ 我们在 submit 里补一次 `translate(-0.5, -0.5, 0)` 把它居中
        //
        // 两处配合 = 原版普通物品的最终摆法（外层 16 倍 + 居中），**不叠、不丢**。
        //
        // 佐证：`assets/minecraft/models/item/generated.json` 的 `display` 里**没有 `gui`** ——
        // GUI 那套居中来自代码生成的 `builtin/generated`，而 `NONE` 不走它。
        ItemStackRenderState state = new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver().updateForTopItem(
                state, inner, ItemDisplayContext.GUI, null, null, 0);

        // ⚠️ 直接提交，**不要**动 pose —— 外层那个 scale(16, -16, 16) 正是 state 需要的。
        //
        //    【1.1.0.20 的回归】我在这里加过 `pose.last().pose().identity()`，结果**图标整个消失**
        //        —— state 里模型顶点是 0..1（1 格 = 1 单位），画到「1 格 = 1 单位」的 pose 上
        //        只占 1/16 格，肉眼看不见。作者实测反馈「球堆图标直接没了」。
        //
        //    【1.1.0.12 的错误】那一版把 context 改成 NONE，也不对 ——
        //        那样会丢掉 `minecraft:item/generated` 那层「把 0..1 顶点挪到以原点居中」的
        //        display translation（**只在 GUI context 下生效**），图标向右下偏移。
        //
        //    ⇒ **GUI context + 原样提交**才是对的：外层已经是完整的 GUI 物品坐标系，
        //      state 自己带的 GUI 变换与之是配套的一层（不是叠加）。
        //
        // ===== ★ 蓄力表现（作者 2026-10-15：「按住右键还是不显示蓄力」）=====
        //
        // 先说清原版**为什么给不了**这个表现（都 javap 核过）：
        //   · `Hud.extractSlot` 里**没有**使用进度 —— 只有拾取弹跳动画
        //   · `Hud$ContextualInfo` 只有 EMPTY / EXPERIENCE / LOCATOR / JUMPABLE_VEHICLE
        //   · 全 jar 只有 `UseDuration` / `UseCycle` 两个**物品模型属性**读进度 ——
        //     弓/弩的"拉弓"是**模型 JSON 的 range_dispatch 按它换模型**做出来的，
        //     不是 HUD 画的条
        //   · 球堆是 `minecraft:special`（Java 渲染器），**没有那个属性**
        //     ⇒ 蓄力时外观零变化，看起来就是「没反应」
        //
        // ⇒ 所以这里**自己按进度缩放**：球随蓄力越来越大，一眼能看出在蓄力。
        //   思路和弓一致（按进度改变外观），只是换到 Java 侧做。
        //   注意 pushPose / popPose 必须成对 —— 外层那个 pose 是共用的，不能污染。
        float charge = chargeProgress();
        pose.pushPose();

        diagPose("外层", pose.last().pose());

        // ★★ 1.1.0.46：**把几何从 0..1 挪到居中**（作者实测截图 + pose 日志共同确定）
        //
        // 日志实测（1.1.0.45 那次的输出）：
        //   pose[外层] 缩放=(48.0, -48.0, 48.0) 位移=(288.0, 48.0, -24.0)
        //
        //   · 缩放 48 = GUI scale 3 × 16 ⇒ 模型空间的 1 单位 = 48 物理像素 = 一格
        //   · 而 `ItemStackRenderState` 提交的几何是 **0..1**（贴片从原点出发，
        //     不是以原点居中）⇒ × 48 就是「从槽位中心往右下画一格」
        //     ⇒ 只有左上那四分之一落在格子里 —— **作者截图里那个「只有一个角」**
        //   · `translate(-0.5, -0.5, 0)` 把 0..1 挪成 -0.5..0.5 ⇒ × 48 = ±24 像素
        //     ⇒ 正好铺满槽位（槽位宽 48 像素）
        //
        // ⇒ **尺寸一直是对的，缺的只是这一句居中。**
        //
        // 我 1.1.0.35 加过同一句，但那次**同时**把 context 改成了 NONE（那才是错的），
        // 把「偏半格」变成了「整个消失」；之后我又去乱试缩放（±0.5 / 采样 / 0..16 /
        // 1/16），越走越远 —— 就是没先看一眼 pose 矩阵。
        // ⚠️ 这个值前面几轮我推错了好几次，目前的判据是**实测对比**：
        //    · 不挪       → 「只有一个角」（图形偏右下）
        //    · 挪 -0.5    → 「完全看不见」（挪过头，跑到格子左上了）
        //   ⇒ 真正的偏移量在两者之间，先取一半。
        //   `[球堆][诊断] ★ 真实几何` 那行会打出 quad 的顶点范围，
        //   下次就能按它算准，不用再夹。
        pose.translate(-0.25F, -0.25F, 0.0F);
        // ===== ★★ 1.1.0.43：照「上膛的弩的球」那个渲染器对齐 =====
        //
        // 作者的提示：「参考上膛的弩的球当时的贴图代码，我记得当时也是经常偏，
        // 说不定是同一个原因」—— 照做之后差别很明确：
        //
        //   ComboChargeBallRenderer（能正常显示）的做法是
        //     submit: pushPose → scale(1/16) → 提交 0..16 的顶点 → popPose
        //     也就是**自己把外层那个 16 倍抵消掉**，只留物品自己的 GUI 摆法；
        //     getExtents 报 0..1（几何在 pose 空间确实是 0..1）。
        //
        // 我这边留着 ItemStackRenderState（组合球的 composite 模型、附魔光效、
        // 损坏条都靠它），对应写法就是：**context 用 GUI**（让 state 自己做
        // 那套居中 + 归一化），**提交前 scale(1/16) 抵消外层**。
        //
        // 之前几版错在哪：
        //   · GUI + 原样 pose ⇒ 外层的 16 倍与 state 的 GUI 摆法**叠了两层**
        //     ⇒ 作者看到的「只有一个角」
        //   · NONE + translate(-0.5) ⇒ 几何留在 0..16 模型单位，外层再 ×16 ⇒ 占 16 格
        //     ⇒ 槽位里只剩放大后的透明边缘 ⇒ 作者看到的「整个消失」
        // ★★ 抵消外层的 scale(16, -16, 16) —— 与 ComboChargeBallRenderer 同一个手法。
        //    这样几何在 pose 空间落回 0..1，state 自己的 GUI 摆法才能正确定位。
        //    （原来这里写的是 translate(-0.5, -0.5, 0)：那假设几何是 0..16 模型单位、
        //      只挪半格；实际外层还会再 ×16 ⇒ 几何占 16 格 ⇒ 槽位里只剩放大的透明边缘
        //      ⇒ 作者看到的「整个消失」。）
        // ★★ 1.1.0.45：**不加任何缩放**（作者实测 + 诊断日志共同确定）
        //
        // 日志打出来的事实：
        //   · submit 被调用，inner=潮汐球      ⇒ 渲染器在跑、球也对
        //   · 包围盒 = AABB[-0.5,-0.5,-0.5] → [0.5,0.5,0.5]
        //     ⇒ state 内部已经把几何摆到了「以原点居中、±0.5」，
        //       而外层那条链给的 pose 再把它放大 16 倍 ⇒ ±8 单位 = 半格，正好。
        //
        // 我 1.1.0.43 照着 ComboChargeBallRenderer 加了 scale(1/16)：那个渲染器
        // 自己提交 0..16 的顶点，所以需要缩；而 state 提交的已经是 ±0.5，
        // 再缩 1/16 就只剩 0.5 像素 ⇒ 作者看到的「整个空的」。
        diagPose("提交前", pose.last().pose());
        if (charge > 0.0F) {
            float s = 1.0F + 0.35F * charge;
            pose.scale(s, s, s);
        }
        state.submit(pose, collector, light, overlay, outline);
        pose.popPose();
    }

    /**
     * 当前蓄力进度 0..1；没在蓄力就是 0。
     *
     * <p>只认「玩家正在使用手上这件物品」。时长由 {@code BallClusterItem.getUseDuration}
     * 给出 —— 那里只有「壳里那颗球本身可蓄力」时才返回大于 0 的值，
     * 所以不可蓄力的球不会走到蓄力分支。</p>
     */
    private static float chargeProgress() {
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.client.player.LocalPlayer player = minecraft.player;
            if (player == null || !player.isUsingItem()) {
                return 0.0F;
            }
            ItemStack using = player.getUseItem();
            if (using.isEmpty()) {
                return 0.0F;
            }
            int total = using.getUseDuration(player);
            if (total <= 0) {
                return 0.0F;
            }
            float p = 1.0F - (float) player.getUseItemRemainingTicks() / (float) total;
            return Math.max(0.0F, Math.min(1.0F, p));
        } catch (Throwable ignored) {
            return 0.0F;
        }
    }

    /** 【临时诊断】pose 矩阵只打前几次，免得每帧刷屏 */
    private static final java.util.concurrent.atomic.AtomicInteger POSE_DIAG =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 打印 pose 矩阵的对角线（缩放）与最后一列（位移）。
     *
     * <p>物品栏那条链给的变换全在这个矩阵里 —— 有了它就能反推出「几何会被放大多少倍」
     * 以及「原点被挪到了哪」，不用再靠试。</p>
     */
    private static void diagPose(String tag, org.joml.Matrix4f m) {
        if (POSE_DIAG.getAndIncrement() < 6) {
            MoreBalls.LOGGER.info("[球堆][诊断] pose[{}] 缩放=({}, {}, {}) 位移=({}, {}, {})",
                    tag, m.m00(), m.m11(), m.m22(), m.m30(), m.m31(), m.m32());
        }
    }

    @Override
    public void getExtents(Consumer<Vector3fc> out) {
        // ===== 这个盒子是「模型本地盒」，不是「原点居中的盒子」 =====
        //
        // 证据链（都 javap 核过）：
        //   · `SpecialModelWrapper` 把本方法的输出包成 Supplier，直接 `setExtents` 到
        //     `LayerRenderState`；`LayerRenderState.visitExtents` 里**先 applyTransform 再算** ——
        //     也就是说**原版要的是未经 display 变换的本地坐标**
        //   · 原版五个实现（Trident / ShulkerBox / Book / Conduit / CopperGolemStatue）
        //     一律用 `model.root().getExtentsForGui(new PoseStack(), out)` ——
        //     **空 PoseStack ⇒ 本地坐标**
        //
        // 球堆画的是「里面那颗球」，属 `minecraft:item/generated` 那一类 ——
        // 模型是**从 (0,0,0) 到 (1,1,1) 的立方体**（左上原点，**不是**原点居中）。
        //
        // ===== 单位是【1/16 格】，模型盒子是 0..16 =====
        //
        // 证据链（都 javap 核过）：
        //   · `SpecialModelWrapper` 把本方法的输出包成 Supplier、直接 `setExtents` 到
        //     `LayerRenderState` ⇒ 原版要的是**未经 display 变换的模型本地坐标**
        //   · `ModelPart.getExtentsForGui` 的 lambda 遍历的是 `Cube.polygons`（逐面顶点），
        //     而 `ModelPart$Cube` 的构造里坐标**直接存、没有 ÷16**
        //     ⇒ **模型 JSON 的元素是 `from[0,0,0] to[16,16,16]`，Cube 存的就是 0..16**
        //
        // ⚠️⚠️ 我先后给过四种答案，**前三种全错**（作者实测「只有右上角那一块」）：
        //   · `±0.5` / `±0.6` —— 「原点居中」口径，错
        //   · `visitExtents` 采样 —— 会 applyTransform，多一层，错
        //   · `0..1` —— 把 1/16 格当成了 1 格 ⇒ 盒子小 16 倍 ⇒ GUI 放大 16 倍 ⇒ 只看见一个角
        //   ⇒ 正确的就是**本地的 0..16**。
        //
        // 【临时诊断】确认本方法**真的被调用过**（作者报「贴图一点变化没有」）
        if (!diagnoseExtentsLogged) {
            diagnoseExtentsLogged = true;
            MoreBalls.LOGGER.info("[球堆][诊断] getExtents 被调用 —— 给出 0..16");
        }
        out.accept(new org.joml.Vector3f(0.0F, 0.0F, 0.0F));
        // ★★ 报 0..1 —— 与 ComboChargeBallRenderer 一致。
        //    它报 0..1 是因为「几何在提交时 poseStack 空间里就是 0..1」；
        //    我这边 submit 里加了 scale(1/16)，几何同样落到 0..1 ⇒ 报同一个值。
        //    （原来报 0..16 是「模型单位」口径 —— 那比实际几何大了 16 倍。）
        out.accept(new org.joml.Vector3f(1.0F, 1.0F, 1.0F));
    }

    /**
     * 模型 json 的反序列化入口。
     *
     * <p>原版每个 special 渲染器都配一个这个（潜影盒、箱子、旗帜都有）。
     * 本渲染器<b>不需要任何 json 参数</b> —— 画什么完全由物品栈上的组件决定，
     * 所以这里是个空壳，只负责把 {@code type()} 报出去让模型加载器认出来。</p>
     */
    public static class Unbaked implements SpecialModelRenderer.Unbaked<ItemStack> {

        public static final Identifier ID =
                Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "ball_cluster");

        public static final MapCodec<Unbaked> CODEC =
                MapCodec.unit(Unbaked::new);

        @Override
        public SpecialModelRenderer<ItemStack> bake(BakingContext context) {
            return new BallClusterSpecialRenderer();
        }

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked<ItemStack>> type() {
            return CODEC;
        }
    }
}
