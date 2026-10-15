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

    @Override
    public void submit(ItemStack inner, PoseStack pose, SubmitNodeCollector collector,
                       int light, int overlay, boolean foil, int outline) {
        if (inner.isEmpty()) {
            return;
        }

        // 走原版流程画那一颗球：组合球的象限、附魔光效、损坏条全部自动带上
        //
        // ⚠️⚠️ 必须用 updateForTopItem，**不能用 updateForNonLiving** ——
        //
        //     后者签名收的是一个 Entity，内部直接调 entity.level()。
        //     而物品栏渲染这条链（GuiItemAtlas.drawToSlot → ItemStackRenderState.submit）
        //     上**没有任何实体可传**，只能传 null ⇒ 进世界立刻
        //     NullPointerException: Cannot invoke "Entity.level()" because "entity" is null
        //
        //     （作者 2026-10-15 实测崩溃，1.1.0.10 引进的）
        //
        //     updateForTopItem 收的是 Level + ItemOwner，两者都允许为 null ——
        //     它正是原版 GUI 渲染物品时用的那个入口。
        ItemStackRenderState state = new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver().updateForTopItem(
                state, inner, ItemDisplayContext.GUI, null, null, 0);
        state.submit(pose, collector, light, overlay, outline);
    }

    @Override
    public void getExtents(Consumer<Vector3fc> out) {
        // 物品图标的标准包围盒 —— 半格见方。原版物品模型也在这个范围内，
        // 给大了会让物品栏里的图标被判定成"过大"而缩放异常
        out.accept(new org.joml.Vector3f(-0.5F, -0.5F, -0.5F));
        out.accept(new org.joml.Vector3f(0.5F, 0.5F, 0.5F));
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
