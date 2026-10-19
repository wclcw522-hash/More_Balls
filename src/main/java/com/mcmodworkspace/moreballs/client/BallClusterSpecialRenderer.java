package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.BallClusterContents;
import com.mcmodworkspace.moreballs.BallClusterItem;
import com.mcmodworkspace.moreballs.MoreBalls;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.function.Consumer;

/**
 * <b>球堆的图标 = 里面那颗球的图标</b>（作者 2026-10-15 指定的效果）。
 *
 * <h2>为什么不能用 json 解决</h2>
 * <p>26.2 的物品模型是数据驱动的，但 {@code minecraft:item/generated} 只能挂<b>一张固定贴图</b>。
 * 球堆需要「打开它才知道画什么」—— 里面的球是 {@code BALL_CLUSTER} 组件的一部分，
 * 属于<b>运行期数据</b>，静态 json 读不到。所以走 {@code minecraft:special} + Java 渲染器。</p>
 *
 * <h2>★★ 画法：**完全照 {@code ComboChargeBallRenderer}**（作者指定的方向）</h2>
 *
 * <p>之前几版我都在用 {@code ItemStackRenderState}（想着「组合球的 composite 模型它自己能处理」），
 * 结果一路踩坑：display context 变换、extents 口径、居中位移……推了五六轮全错。
 * 作者一句话点中了要害：<b>「你当时做组合球咋渲染的现在学一下」</b>。</p>
 *
 * <p>上膛那颗球的渲染器（一直显示正常）的做法是：</p>
 * <ul>
 *   <li><b>不碰 {@code ItemStackRenderState}</b> —— 也就没有 display context 那一层未知数</li>
 *   <li>自己读贴图，然后 <b>{@code submitCustomGeometry} 手写顶点</b></li>
 *   <li>{@code submit} 里 {@code pushPose → scale(1/16) → 提交 0..16 的顶点 → popPose}</li>
 *   <li>{@code getExtents} 报 <b>0..1</b>（= 几何在 pose 空间里的实际范围）</li>
 * </ul>
 *
 * <p>本类现在与它<b>逐字对齐</b>（常量、顶点顺序、UV 约定、包围盒口径全部照抄），
 * **唯一的差别是「画哪张图」**：它画合成出来的弩上那颗球，我画壳里那颗球的贴图。</p>
 *
 * <h2>贴图怎么找</h2>
 * <p>约定：<b>{@code rotation_craft:textures/item/<物品路径>.png}</b>。
 * 查过 {@code models/item/*.json} —— 木球 / 铁球 / 圆石球 / 钻石球…… 全是
 * {@code item/generated} + {@code layer0: rotation_craft:item/<同名>}，一一对应。</p>
 *
 * <p>⚠️ <b>组合球（{@code combo_ball}）没有单一贴图</b> —— 它是 {@code composite} +
 * 四个 {@code select}（按 {@code combo_slot_N} 组件选 {@code combo_piece_<来源>_<象限>}）
 * 拼出来的，所以这里查不到时返回 {@code null}（不画）。球堆里出现组合球是少数情况；
 * 要支持它得把象限合成也搬过来，那部分在 {@code ComboChargeBallRenderer} 里已有现成实现。</p>
 */
public class BallClusterSpecialRenderer implements SpecialModelRenderer<ItemStack> {

    // ===== 与 ComboChargeBallRenderer 逐字一致的几何常量 =====

    /** 模型空间 1 单位 = 1/16 格；提交前统一乘它 */
    private static final float MODEL_SCALE = 1.0F / 16.0F;

    /** 贴片在模型空间的范围（原版 {@code item/generated} 就是 0..16） */
    private static final float MIN = 0.0F;
    private static final float MAX = 16.0F;

    /** 正 / 背面所在的 z（照原版 {@code ItemModelGenerator}：from z=7.5、to z=8.5） */
    private static final float PLANE_Z_FRONT = 8.5F;
    private static final float PLANE_Z_BACK = 7.5F;

    /**
     * 组合球合成结果的缓存 —— 象限组合 → 注册好的动态纹理 id。
     *
     * <p>封顶淘汰最久没用的那张：一张 16×16 的图只有 1 KB，真正要节制的是
     * 注册进纹理管理器的 {@code DynamicTexture} 条目数（与弩那边同一个理由）。</p>
     */
    private static final java.util.Map<String, Identifier> COMBO_TEXTURES =
            new java.util.LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Identifier> eldest) {
                    return size() > 64;
                }
            };

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
    public void submit(ItemStack inner, PoseStack poseStack, SubmitNodeCollector collector,
                       int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
        if (inner == null || inner.isEmpty()) {
            return;
        }
        Identifier texture = textureFor(inner);
        if (texture == null) {
            return;   // 既不是普通球、也合成不出组合球 → 不画
        }

        float charge = chargeProgress();

        poseStack.pushPose();
        // ★ 与 ComboChargeBallRenderer 完全同一个手法：先在**模型空间**里摆顶点（0..16），
        //   提交前统一乘 1/16 —— 于是屏幕上几何正好 0..1（= 1 格），不需要任何离心位移。
        poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);

        // 蓄力表现：球随蓄力变大（作者 2026-10-15：「按住右键还是不显示蓄力」）
        if (charge > 0.0F) {
            float s = 1.0F + 0.35F * charge;
            poseStack.scale(s, s, s);
        }

        drawQuad(poseStack, collector, texture, lightCoords, overlayCoords);
        poseStack.popPose();
    }

    /**
     * 把「壳里那颗球」的贴图 id 找出来。
     *
     * <p>两条路：</p>
     * <ul>
     *   <li><b>普通球</b> —— 约定 {@code rotation_craft:textures/item/<物品路径>.png}
     *       （查过 {@code models/item/*.json}，所有普通球都是
     *       {@code item/generated} + {@code layer0: rotation_craft:item/<同名>}）</li>
     *   <li><b>组合球</b> —— 没有单一贴图，见 {@link #composedComboTexture}</li>
     * </ul>
     *
     * @return 找不到时返回 {@code null}（调用方不画）
     */
    private static Identifier textureFor(ItemStack ball) {
        if (ball.is(com.mcmodworkspace.moreballs.ModItems.COMBO_BALL.get())) {
            return composedComboTexture(ball);
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(ball.getItem());
        if (!MoreBalls.MOD_ID.equals(id.getNamespace())) {
            return null;
        }
        Identifier texture = Identifier.fromNamespaceAndPath(id.getNamespace(),
                "textures/item/" + id.getPath() + ".png");
        // 确认文件真的在 —— 否则渲染管线会拿到 missingno 那张紫黑格
        if (net.minecraft.client.Minecraft.getInstance().getResourceManager()
                .getResource(texture).isEmpty()) {
            return null;
        }
        return texture;
    }

    /**
     * <b>组合球的图标</b> —— 四个象限各取一张贴图叠起来。
     *
     * <h2>为什么不能按普通球那样找一张图</h2>
     * <p>组合球没有单一贴图：它的物品模型是 {@code composite} + 四个 {@code select}
     * （按 {@code combo_slot_1..4} 组件选 {@code combo_piece_<来源>_<象限>} 模型），
     * 而那些 {@code combo_piece} 模型指向的是 <b>{@code <球名>_quarter_<1..4>.png}</b>
     * —— 查过 {@code models/item/combo_piece_*.json} 确认的
     * （例如 {@code combo_piece_0_1.json} 的 layer0 是 {@code rotation_craft:item/wooden_ball_quarter_1}）。</p>
     *
     * <p>{@code combo_slot_N} 里存的下标正好是 {@link BallFragments#sources()} 里的位置，
     * 所以「象限 → 来源球 → 那张 quadrant 贴图」是一条直线。</p>
     *
     * <h2>缓存</h2>
     * <p>按象限组合缓存（与弩那边同一个思路）：一张 16×16 只有 1 KB，
     * 真正要节制的是注册进纹理管理器的 {@code DynamicTexture} 条目数，所以封顶。</p>
     */
    private static Identifier composedComboTexture(ItemStack ball) {
        int[] slots = new int[4];
        boolean any = false;
        for (int q = 0; q < 4; q++) {
            Integer value = ball.get(
                    com.mcmodworkspace.moreballs.ModComponents.COMBO_SLOTS.get(q).get());
            slots[q] = (value == null) ? -1 : value;
            if (slots[q] >= 0) {
                any = true;
            }
        }
        if (!any) {
            return null;
        }

        String cacheKey = java.util.Arrays.toString(slots);
        Identifier cached = COMBO_TEXTURES.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        java.util.List<net.minecraft.world.item.Item> sources =
                com.mcmodworkspace.moreballs.BallFragments.sources();
        // ⚠️⚠️ 第三参必须是 **true**（= calloc 清零）。
        //    这里原来写的是 false —— 那是「不初始化」：拿到的是**没清零的内存**，
        //    里面残留着显存/堆上的旧像素数据（作者 2026-10-16 截图反馈的
        //    「球堆贴图后面混进去了什么东西」就是它，一团红紫蓝绿的杂色）。
        //    下面 overlay() 只覆盖**非透明**像素，所以那些垃圾像素会原样留在底上。
        com.mojang.blaze3d.platform.NativeImage canvas =
                new com.mojang.blaze3d.platform.NativeImage(
                        com.mojang.blaze3d.platform.NativeImage.Format.RGBA, 16, 16, true);
        for (int q = 0; q < 4; q++) {
            int index = slots[q];
            if (index < 0 || index >= sources.size()) {
                continue;
            }
            Identifier sourceId = BuiltInRegistries.ITEM.getKey(sources.get(index));
            // ⚠️⚠️ **namespace 必须固定用 rotation_craft，不能用 sourceId.getNamespace()**
            //     （作者 2026-10-17 报的「球堆里右下角史莱姆球不渲染」就是它）。
            //
            //     **史莱姆球与末影珍珠是原版物品**，物品 id 是 `minecraft:slime_ball` /
            //     `minecraft:ender_pearl` —— 拿它们的 namespace 拼出来就是
            //     `minecraft:textures/item/slime_ball_quarter_4.png`，
            //     **那个文件根本不存在**（碎片贴图都在本模组的 rotation_craft 下）。
            //     readTexture 返回 null ⇒ 那一格被跳过 ⇒ 球堆上缺一块。
            //
            //     所以这里只取物品的 **path**（slime_ball / ender_pearl / wooden_ball …），
            //     namespace 一律 rotation_craft —— 与生成碎片贴图时的做法一致。
            Identifier pieceId = Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID,
                    "textures/item/" + sourceId.getPath() + "_quarter_" + (q + 1) + ".png");
            com.mojang.blaze3d.platform.NativeImage piece =
                    ComboChargeBallRenderer.readTexture(pieceId);
            if (piece != null) {
                // 复用弩那边的 source-over：透明像素不盖掉下面已画好的象限
                ComboChargeBallRenderer.overlay(canvas, piece);
                piece.close();
            }
        }

        Identifier id = Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID,
                "ball_cluster_combo/" + Integer.toHexString(java.util.Arrays.hashCode(slots)));
        net.minecraft.client.Minecraft.getInstance().getTextureManager().register(id,
                new net.minecraft.client.renderer.texture.DynamicTexture(
                        () -> "rotation_craft ball cluster " + cacheKey, canvas));
        COMBO_TEXTURES.put(cacheKey, id);
        return id;
    }

    /**
     * 提交一个双面贴片 —— <b>照抄 {@code ComboChargeBallRenderer.drawQuad}</b>。
     *
     * <p>顶点顺序、UV 映射、背面为什么要「顶点倒序但 UV 与正面一致」，
     * 那边的注释里都论证过了（作者 2026-10-09 报的「掉落物转 180° 有种转了又没转的诡异感」）。
     * 这里不重新发明，直接用同一套。</p>
     */
    private static void drawQuad(PoseStack poseStack, SubmitNodeCollector collector,
                                 Identifier texture, int light, int overlay) {
        // 物品要走物品管线（原版 ItemFeatureRenderer 走的也是 ITEM_CUTOUT）
        RenderType type = RenderTypes.itemCutout(texture);
        collector.submitCustomGeometry(poseStack, type, (pose, buffer) -> {
            // ---- 正面（+Z）----
            vertex(buffer, pose, MIN, MAX, 0.0F, 0.0F, PLANE_Z_FRONT, 1.0F, light, overlay);
            vertex(buffer, pose, MIN, MIN, 0.0F, 1.0F, PLANE_Z_FRONT, 1.0F, light, overlay);
            vertex(buffer, pose, MAX, MIN, 1.0F, 1.0F, PLANE_Z_FRONT, 1.0F, light, overlay);
            vertex(buffer, pose, MAX, MAX, 1.0F, 0.0F, PLANE_Z_FRONT, 1.0F, light, overlay);

            // ---- 背面（-Z）：顶点倒序，UV 与正面完全一致 ----
            vertex(buffer, pose, MAX, MAX, 1.0F, 0.0F, PLANE_Z_BACK, -1.0F, light, overlay);
            vertex(buffer, pose, MAX, MIN, 1.0F, 1.0F, PLANE_Z_BACK, -1.0F, light, overlay);
            vertex(buffer, pose, MIN, MIN, 0.0F, 1.0F, PLANE_Z_BACK, -1.0F, light, overlay);
            vertex(buffer, pose, MIN, MAX, 0.0F, 0.0F, PLANE_Z_BACK, -1.0F, light, overlay);
        });
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose,
                               float x, float y, float u, float v,
                               float z, float normalZ, int light, int overlay) {
        buffer.addVertex(pose, x, y, z)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, 0.0F, 0.0F, normalZ);
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

    /**
     * 包围盒 —— <b>必须与 {@link #submit} 里实际提交的几何处在同一空间</b>。
     *
     * <p>{@code submit} 会给 {@code PoseStack} 乘 {@link #MODEL_SCALE}（1/16），
     * 所以屏幕上的几何实际是 0..1。这里就报 0..1，与 {@code ComboChargeBallRenderer} 一致。</p>
     *
     * <p>（我原来报过 0..16 —— 那是「模型单位」口径，比真实外观大 16 倍；
     * 也报过 ±0.5 / ±0.6，那是「原点居中」口径。两个都错，错在**单位**。）</p>
     */
    @Override
    public void getExtents(Consumer<Vector3fc> out) {
        out.accept(new Vector3f(MIN * MODEL_SCALE, MIN * MODEL_SCALE, MIN * MODEL_SCALE));
        out.accept(new Vector3f(MAX * MODEL_SCALE, MAX * MODEL_SCALE, MAX * MODEL_SCALE));
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
