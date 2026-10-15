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
        // ⚠️⚠️ 用 NONE，**不能用 GUI** ——
        //
        //     ItemStackRenderState.submit 会**原样**把 pose 传给每一层
        //     （javap: `layers[i].submit(pose, ...)`，中间不做任何变换），
        //     所以走到这里时 pose 已经是 GUI 摆好的坐标系了。
        //     再套一次 GUI 变换 = 双重变换 ⇒ 那颗球被推到画布外，
        //     **只看得见一个小角**（作者 2026-10-15 反馈的正是这个）。
        //
        //     NONE 意味着「不做额外变换」，直接按当前 pose 画 —— 和原版 special 一致。
        ItemStackRenderState state = new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver().updateForTopItem(
                state, inner, ItemDisplayContext.NONE, null, null, 0);
        state.submit(pose, collector, light, overlay, outline);
    }

    @Override
    public void getExtents(Consumer<Vector3fc> out) {
        // 原版的 getExtents 一律**问模型要真实包围盒**（javap 核过 Trident / ShulkerBox /
        // Book / Conduit / CopperGolemStatue —— 没有一个写固定常量）：
        //
        //     PoseStack pose = new PoseStack();
        //     model.root().getExtentsForGui(pose, out);
        //
        // 我这边拿不到「那一刻那个球」的模型（getExtents 是无参的），所以给一个**略大**的范围：
        // GUI 是按这个盒子算缩放的 —— 给大了顶多缩一点，**给小了会被裁掉**。
        // 宁可略大。
        out.accept(new org.joml.Vector3f(-0.6F, -0.6F, -0.6F));
        out.accept(new org.joml.Vector3f(0.6F, 0.6F, 0.6F));
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
