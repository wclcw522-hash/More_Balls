package com.mcmodworkspace.moreballs.client;

import java.util.List;
import java.util.Arrays;
import com.mcmodworkspace.moreballs.BallFragments;
import com.mcmodworkspace.moreballs.ModComponents;
import com.mcmodworkspace.moreballs.MoreBalls;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.serialization.MapCodec;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * 组合球装填到弩上时，那把弩的外观。
 *
 * <h2>为什么必须写渲染器</h2>
 * <p>组合球的小球是由物品组件决定的（四个象限各自一种材质），json 没法「按数据选图再叠四层，
 * 同时还要正确的手持姿态」，所以只能走 {@code minecraft:special} 这条路 ——
 * 渲染器在运行时能读到组件，也能拿到当前视角。</p>
 *
 * <h2>几何尺寸照抄原版的物品模型</h2>
 * <p>{@code net.minecraft.client.renderer.ItemModelGenerator} 生成普通物品贴图时用的是：</p>
 * <pre>{@code
 * private static final float MIN_Z = 7.5F;
 * private static final float MAX_Z = 8.5F;
 * Vector3f from = new Vector3f(0.0F, 0.0F, 7.5F);
 * Vector3f to   = new Vector3f(16.0F, 16.0F, 8.5F);
 * }</pre>
 * <p>也就是说物品模型的几何空间是 <b>0..16</b>、贴图平面落在 <b>z = 7.5</b>。
 * 所以这里也按 0..16 提交顶点，平面取 7.4（比 7.5 靠前一点点，压在弩身之上），
 * 并在提交前统一 {@code scale(1/16)} 换算到物品空间。</p>
 *
 * <h2>贴图管线</h2>
 * <p>用 {@link RenderTypes#itemCutout(Identifier)} —— 原版渲染物品模型走的是
 * {@code ITEM_CUTOUT} 管线（见 {@code ItemFeatureRenderer} 里
 * {@code material.itemRenderType()}）。用实体管线（{@code entityCutout}）画物品会明显偏暗，
 * 因为两者的光照处理不同。</p>
 */
@OnlyIn(Dist.CLIENT)
public class ComboChargeBallRenderer implements SpecialModelRenderer<int[]> {

    /** 模型空间 → 物品空间。原版物品几何是 0..16 */
    private static final float MODEL_SCALE = 1.0F / 16.0F;

    /** 底图（满弦空弩）所在的平面。原版物品贴图在 7.5，这里取 7.4 压在前面 */
    private static final float BASE_Z = 7.4F;

    /** 小球所在的平面，比底图再靠前一点，确保球在最上层不被盖住 */
    private static final float PIECE_Z = 7.3F;

    /** 某个象限没有内容（二合一球的下半就是这样） */
    private static final int NO_PIECE = -1;

    private static final float MIN = 0.0F;
    private static final float MAX = 16.0F;

    private static Identifier tex(String path) {
        return Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "textures/item/" + path + ".png");
    }

    /** 满弦的空弩 */
    private static final Identifier BASE_TEXTURE = tex("charge_base_crossbow");

    private static Identifier pieceTexture(int source, int quadrant) {
        return tex("charge_piece_" + source + "_" + quadrant);
    }

    /**
     * 从物品上读四个象限的来源下标。
     *
     * <p>任何一个缺失就返回 null —— 那时只画底图（一把满弦的空弩），
     * 不会凭猜测画出一个错的球。</p>
     */
    @Override
    public int @Nullable [] extractArgument(ItemStack stack) {
        int kinds = BallFragments.sources().size();
        Integer[] raw = {
                stack.get(ModComponents.COMBO_SLOT_1.get()),
                stack.get(ModComponents.COMBO_SLOT_2.get()),
                stack.get(ModComponents.COMBO_SLOT_3.get()),
                stack.get(ModComponents.COMBO_SLOT_4.get())
        };

        boolean anySlot = false;
        for (Integer v : raw) {
            if (v != null) {
                anySlot = true;
                break;
            }
        }

        int[] slots = new int[4];
        if (anySlot) {
            for (int i = 0; i < 4; i++) {
                Integer v = raw[i];
                slots[i] = (v == null) ? NO_PIECE : ((v >= 0 && v < kinds) ? v : 0);
            }
            diag("② extractArgument()：读 combo_slot 组件 -> {}", Arrays.toString(slots));
            return slots;
        }

        List<Integer> indexes = BallFragments.parseIndexes(
                stack.getOrDefault(ModComponents.COMBO_SOURCES.get(), ""));
        if (indexes.isEmpty()) {
            diag("② extractArgument()：slot 组件与 combo_sources 都为空 -> null，只画底图");
            return null;
        }
        for (int q = 0; q < 4; q++) {
            int v = BallFragments.slotValue(indexes, q);
            slots[q] = (v >= 0 && v < kinds) ? v : NO_PIECE;
        }
        diag("② extractArgument()：从 combo_sources={} 现算 -> {}", indexes, Arrays.toString(slots));
        return slots;
    }

    @Override
    public void submit(int @Nullable [] slots, PoseStack poseStack,
                       SubmitNodeCollector collector, int lightCoords, int overlayCoords,
                       boolean hasFoil, int outlineColor) {
        diag("③ submit() 被调用：slots={} light={} overlay={}", slots, lightCoords, overlayCoords);

        poseStack.pushPose();
        poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);

        // 底图：满弦空弩。special 的 base 模型只提供手持姿态与纹理依赖、并不参与绘制，
        // 所以这张图必须由这里画出来。
        drawQuad(poseStack, collector, BASE_TEXTURE, BASE_Z, lightCoords, overlayCoords);

        // 四个象限的小球，盖在底图之上。NO_PIECE（-1）表示该象限没内容，跳过不画 ——
        // 二合一球就是这样：上半两个象限有材质，下半没有。
        if (slots != null) {
            for (int q = 0; q < 4; q++) {
                if (slots[q] == NO_PIECE) {
                    continue;
                }
                drawQuad(poseStack, collector, pieceTexture(slots[q], q + 1),
                        PIECE_Z, lightCoords, overlayCoords);
            }
        }

        poseStack.popPose();
    }

    /** 提交一张覆盖整个物品格的 16×16 平面 */
    private static void drawQuad(PoseStack poseStack, SubmitNodeCollector collector,
                                 Identifier texture, float z, int light, int overlay) {
        RenderType type = RenderTypes.entityCutout(texture, false);
        collector.submitCustomGeometry(poseStack, type, (pose, buffer) -> {
            // 逆时针，法线朝 +Z
            vertex(buffer, pose, MAX, MIN, 1.0F, 1.0F, z, light, overlay);
            vertex(buffer, pose, MIN, MIN, 0.0F, 1.0F, z, light, overlay);
            vertex(buffer, pose, MIN, MAX, 0.0F, 0.0F, z, light, overlay);
            vertex(buffer, pose, MAX, MAX, 1.0F, 0.0F, z, light, overlay);
        });
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose,
                               float x, float y, float u, float v,
                               float z, int light, int overlay) {
        buffer.addVertex(pose, x, y, z)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }

    /**
     * 诊断用的调用计数。
     *
     * <p>{@code extractArgument} 与 {@code submit} 是**每帧**都会走的，
     * 直接打日志会把 latest.log 刷爆，所以只记录最开始几次 ——
     * 定位「有没有走到这一步」几次就够，之后静默。</p>
     */
    private static final java.util.concurrent.atomic.AtomicInteger DIAG_COUNT =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final int DIAG_LIMIT = 5;

    private static void diag(String message, Object... args) {
        if (DIAG_COUNT.incrementAndGet() <= DIAG_LIMIT) {
            MoreBalls.LOGGER.info("[ball][弩] " + message, args);
        }
    }

    /** 无状态，共用一个实例 */
    public static final ComboChargeBallRenderer INSTANCE = new ComboChargeBallRenderer();

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        output.accept(new Vector3f(MIN, MIN, BASE_Z));
        output.accept(new Vector3f(MAX, MAX, BASE_Z));
    }

    /** 注册用的 Unbaked —— 结构照原版 EndCubeSpecialRenderer / ConduitSpecialRenderer 的写法 */
    public record Unbaked() implements SpecialModelRenderer.Unbaked<int[]> {

        public static final Unbaked INSTANCE = new Unbaked();

        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(INSTANCE);

        @Override
        public @Nullable SpecialModelRenderer<int[]> bake(SpecialModelRenderer.BakingContext context) {
            // 这一行只会在模型加载时出现一次。它出现了 = 注册生效；
            // 没出现 = 事件没挂对总线（模型会静默变空白，弩整个消失）。
            MoreBalls.LOGGER.info("[ball][弩] ① Unbaked.bake() 被调用 —— special 渲染器注册生效");
            return ComboChargeBallRenderer.INSTANCE;
        }

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked<int[]>> type() {
            return MAP_CODEC;
        }
    }
}
