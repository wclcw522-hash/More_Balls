package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.BallFragments;
import com.mcmodworkspace.moreballs.BallAmmo;
import com.mcmodworkspace.moreballs.ModComponents;
import com.mcmodworkspace.moreballs.MoreBalls;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.serialization.MapCodec;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * 组合球装填到弩上时的外观。
 *
 * <h2>做法：运行时合成一张贴图，只画一个面</h2>
 * <p>组合球的小球由物品组件决定（四个象限各自一种材质），外观没法预先画好。
 * 但<b>也不需要叠层渲染</b> —— 那样会带来一堆麻烦（层间 z-fighting、被底图盖住、
 * 每层都要单独选管线）。</p>
 *
 * <p>这里用的是更直接的办法：</p>
 * <ol>
 *   <li>把「满弦空弩」底图与四个象限的贴图，在 CPU 上<b>合成成一张 16×16 的图</b></li>
 *   <li>用 {@link DynamicTexture} 上传成运行时纹理，按象限组合<b>缓存</b></li>
 *   <li>渲染时只提交<b>一个</b> 16×16 的面 —— 没有层叠、没有 z 冲突</li>
 * </ol>
 *
 * <h2>几何：照原版物品模型</h2>
 * <p>{@code net.minecraft.client.renderer.ItemModelGenerator} 生成普通物品贴图用的是
 * {@code from=(0,0,7.5)} / {@code to=(16,16,8.5)} —— 也就是<b>几何空间 0..16、贴图平面 z=7.5</b>。
 * 本渲染器同样按 0..16 提交顶点，平面取 7.4（比 7.5 靠前一点点），
 * 并在提交前统一 {@code scale(1/16)} 换算到物品空间。</p>
 */
// ⚠️ 这里**不要**加 @OnlyIn(Dist.CLIENT)。
//
// NeoForge 26.x 移除了 @OnlyIn 的运行时成员剥离行为，保留注解只会在每次启动时打印
//   [ERROR] @OnlyIn used on class ...  （作者 2026-10-08 看到的「报错几十遍」就是它）
// 客户端专用性已经由「注册时挂在客户端事件上」保证了，不需要这个注解。
public class ComboChargeBallRenderer implements SpecialModelRenderer<int[]> {

    /** 模型空间 → 物品空间（原版物品几何是 0..16） */
    private static final float MODEL_SCALE = 1.0F / 16.0F;

    /** 贴图平面。原版物品贴图在 z=7.5，这里取 7.4，压在弩身前面 */
    /**
     * 正面的 z（原版 2D 贴片的正面在 8.5 —— {@code from=(0,0,7.5)} / {@code to=(16,16,8.5)}）。
     *
     * <p>⚠️ 原来写的是 {@code 7.4}，那个值<b>小于背面的 7.5</b>，也就是贴在贴片<b>之后</b>，
     * 会被弩身挡住 —— 注释里「压在弩身前面」的说法是反的。</p>
     */
    private static final float PLANE_Z_FRONT = 8.5F;

    /** 背面的 z（略微靠后，避免与正面共面导致 z-fighting） */
    private static final float PLANE_Z_BACK = 7.5F;   // 照原版：from z=7.5F

    /** 侧壁的 z 范围 —— 照原版 bakeSideFaces 的 from.z / to.z */
    private static final float SIDE_Z_FROM = 7.5F;
    private static final float SIDE_Z_TO = 8.5F;

    private static final float MIN = 0.0F;
    private static final float MAX = 16.0F;

    /** 某个象限没有内容（二合一球的下半就是这样） */
    private static final int NO_PIECE = -1;

    private static Identifier resource(String path) {
        return Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, path);
    }

    /** 底图：满弦的空弩 */
    private static final Identifier BASE_TEXTURE = resource("textures/item/charge_base_crossbow.png");

    /** 四分之一的象限贴图（q = 1..4，左上 / 右上 / 左下 / 右下） */
    private static Identifier pieceTexture(int source, int quadrant) {
        return resource("textures/item/charge_piece_" + source + "_" + quadrant + ".png");
    }

    /**
     * <b>二分之一的半球贴图</b>（{@code half_top} / {@code half_bottom}）。
     *
     * <p>二合一球的两块各占<b>一整个半圆</b>（见 {@code BallFragments.slotValue}：
     * 二合一时象限 0/1 归第一块、2/3 归第二块）。所以它该贴的是一整张半球图，
     * 而不是拼两个象限 —— 素材包里那 16 张 {@code charge_piece_N_half_*.png}
     * 就是为此准备的，但<b>之前代码一次都没引用过</b>，
     * 于是二合一球在弩上只显示「半个半圆」，看起来像是只剩一层
     * （作者反馈的「弩的贴图只剩一层」）。</p>
     *
     * @param top true = 上半、false = 下半
     */
    private static Identifier halfTexture(int source, boolean top) {
        return resource("textures/item/charge_piece_" + source + "_half_"
                + (top ? "top" : "bottom") + ".png");
    }

    /**
     * 合成结果缓存：象限组合 → 已注册的动态纹理 id。
     *
     * <p>同一组来源只合成一次。玩家能遇到的组合数很少，缓存不会失控。</p>
     */
    /**
     * 合成结果缓存 —— 键是四个象限的下标，值是注册好的动态纹理 id。
     *
     * <p><b>用 LRU 而不是 HashMap</b>：组合球的象限组合是
     * 8 种球的四象限排列，理论上千种，每种都会注册一张 DynamicTexture
     * （显存 + 纹理管理器条目）。HashMap 只增不减，玩久了会一直堆。
     * 封顶 {@link #COMPOSED_CAPACITY} 张，超了淘汰最久没用的那张。</p>
     *
     * <p>{@code null} 值表示「这张合成过、失败了」——负缓存，避免每次都重跑
     * 一遍读图 + 逐像素合成（读图失败通常是资源包问题，短时间内不会好）。
     * 资源包重载时由 {@link #clearCache()} 整体作废。</p>
     */
    /**
     * 合成图的**像素副本**，按同一个缓存键存。
     *
     * <p>侧壁必须扫「合成后」的轮廓（底图 + 球），但合成图是注册在
     * {@code TextureManager} 的<b>动态纹理</b>——{@code ResourceManager} 读不到它
     * （两套系统），所以 {@code drawSideFaces} 里那句 {@code readTexture(texture)}
     * 恒返回 null，侧壁一次都没画出来过。这里把像素留一份给侧壁用。</p>
     */
    private static final Map<String, NativeImage> COMPOSED_PIXELS =
            new java.util.LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, NativeImage> eldest) {
                    if (size() > COMPOSED_CAPACITY) {
                        eldest.getValue().close();
                        return true;
                    }
                    return false;
                }
            };

    private static final Map<String, Identifier> COMPOSED =
            new java.util.LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
                    return size() > COMPOSED_CAPACITY;
                }
            };

    /** 缓存上限 —— 一张 16×16 的 RGBA 只有 1 KB，显存占用可忽略，这里限制的是纹理管理器条目数 */
    private static final int COMPOSED_CAPACITY = 64;

    /** 合成失败时占位用的「负缓存」标记 */
    private static final Identifier FAILED = Identifier.fromNamespaceAndPath("minecraft", "missingno");

    /**
     * 作废全部合成缓存 —— 资源包重载后必须调用。
     *
     * <p>缓存里存的是「按当前资源包的贴图合成出来的结果」，
     * 资源包一换、底图或象限贴图变了，旧结果就全错了。</p>
     */
    public static void clearCache() {
        COMPOSED.clear();
    }

    // ===== 取参数 =====

    /**
     * 读四个象限的来源下标，{@code -1} 表示该象限留空。
     *
     * <p>两级取数：先读四个 {@code combo_slot_N} 组件；读不到就退回 {@code combo_sources}
     * 字符串组件现算 —— 旧存档里的球只有后者，而补组件的 {@code ensureComboSlots}
     * 只在物品待在背包里时才跑，装在弩上不会触发。</p>
     *
     * @return 四个象限的来源下标；连来源数据都没有时返回 null（此时只显示底图）
     */
    @Override
    public int @Nullable [] extractArgument(ItemStack stack) {
        // ⚠️ 传进来的是**弩**（SpecialModelWrapper.update 传的是被渲染的物品栈），
        //    而组合球的四个象限组件在**装填的那颗球**上 —— 先把球从弩里拆出来再读。
        //    不拆的话这里恒返回 null，弩上永远只有底图。
        stack = BallAmmo.chargedBallStack(stack);
        if (stack == null || stack.isEmpty()) {
            diag("② extractArgument() 弩上没有球 -> null（只画底图）");
            return null;
        }
        int kinds = BallFragments.sources().size();
        Integer[] raw = {
                stack.get(ModComponents.COMBO_SLOT_1.get()),
                stack.get(ModComponents.COMBO_SLOT_2.get()),
                stack.get(ModComponents.COMBO_SLOT_3.get()),
                stack.get(ModComponents.COMBO_SLOT_4.get())
        };

        int[] slots = new int[4];
        boolean anySlot = false;
        for (Integer v : raw) {
            if (v != null) {
                anySlot = true;
                break;
            }
        }
        if (anySlot) {
            for (int i = 0; i < 4; i++) {
                Integer v = raw[i];
                slots[i] = (v == null) ? NO_PIECE : ((v >= 0 && v < kinds) ? v : 0);
            }
            diag("② extractArgument() 读 combo_slot -> {}", Arrays.toString(slots));
            return slots;
        }

        List<Integer> indexes = BallFragments.parseIndexes(
                stack.getOrDefault(ModComponents.COMBO_SOURCES.get(), ""));
        if (indexes.isEmpty()) {
            diag("② extractArgument() 组件全空 -> null（只画底图）");
            return null;
        }
        for (int q = 0; q < 4; q++) {
            int v = BallFragments.slotValue(indexes, q);
            slots[q] = (v >= 0 && v < kinds) ? v : NO_PIECE;
        }
        diag("② extractArgument() 从 combo_sources={} 现算 -> {}", indexes, Arrays.toString(slots));
        return slots;
    }

    // ===== 渲染 =====

    @Override
    public void submit(int @Nullable [] slots, PoseStack poseStack,
                       SubmitNodeCollector collector, int lightCoords, int overlayCoords,
                       boolean hasFoil, int outlineColor) {
        diag("③ submit() 被调用：slots={}", Arrays.toString(slots));

        Identifier texture = composedTexture(slots);
        if (texture == null) {
            // 走到这里说明连底图都合成不出来 —— 那是读图失败，readTexture 里已经报过了
            diag("③ submit() 合成失败 -> 什么都不画");
            return;
        }

        poseStack.pushPose();
        poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
        drawQuad(poseStack, collector, texture, lightCoords, overlayCoords);
        // 补侧壁 —— special 不渲染 base，不补的话弩就是一片纸
        drawSideFaces(poseStack, collector, texture, composedPixels(slots), lightCoords, overlayCoords);
        poseStack.popPose();

        diag("③ submit() 已提交一个面：texture={} 顶点={}~{} z={} scale={}（双面）",
                texture, MIN, MAX, PLANE_Z_FRONT, MODEL_SCALE);
    }

    /**
     * 诊断计数 —— {@code extractArgument} 与 {@code submit} 每帧都会走，
     * 直接打日志会把 latest.log 刷爆，所以只记录最开始几次。
     */
    private static final java.util.concurrent.atomic.AtomicInteger DIAG =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final int DIAG_LIMIT = 8;

    private static void diag(String message, Object... args) {
        if (DIAG.incrementAndGet() <= DIAG_LIMIT) {
            MoreBalls.LOGGER.info("[ball][弩] " + message, args);
        }
    }

    /**
     * 提交一个覆盖整格的 16×16 平面 —— <b>双面</b>。
     *
     * <h2>⚠️ 绕序必须与 MC 的 FaceInfo 一致，否则整面被剔除</h2>
     * <p>{@code ITEM_CUTOUT} 管线<b>没有</b> {@code withCull(false)}，走的是默认
     * <b>背面剔除开启</b>。MC 对「正面朝 +Z」的硬约定是
     * {@code FaceInfo.SOUTH} 的顺序：</p>
     * <pre>
     *   (MIN,MAX) → (MIN,MIN) → (MAX,MIN) → (MAX,MAX)     左上 → 左下 → 右下 → 右上
     * </pre>
     * <p>而这里原来写的是 <b>它的逆序</b>
     * （{@code (MAX,MIN)→(MIN,MIN)→(MIN,MAX)→(MAX,MAX)}）——
     * 那等价于 {@code Direction.NORTH} 的绕序，法线朝 <b>-Z</b>。
     * 于是第一人称手持（手性为正的上下文）时整个面被判为背面、直接丢弃，
     * 表现就是「弩整格看不见」（作者 2026-10-09 报的）。注释写「法线朝 +Z」是错的。</p>
     *
     * <h2>为什么要画两面</h2>
     * <p>原版 2D 物品几何本来就是双面的 —— {@code ItemModelGenerator.bakeExtrudedSprite()}
     * 会同时 bake {@code Direction.SOUTH} 与 {@code Direction.NORTH} 两面，
     * 正是为了规避物品在不同 display context 下的手性翻转（拿在手上和放在地上，
     * 同一个模型看到的是不同的一面）。只画一面就必然有一半场合是空白。</p>
     *
     * <h2>z 值</h2>
     * <p>原版 2D 贴片的实体范围是 {@code from=(0,0,7.5)} / {@code to=(16,16,8.5)}：
     * <b>正面在 8.5</b>、7.5 是背面。原来的 {@code PLANE_Z = 7.4} 落在贴片<b>之后</b>，
     * 也会被弩身挡住。</p>
     */
    private static void drawQuad(PoseStack poseStack, SubmitNodeCollector collector,
                                 Identifier texture, int light, int overlay) {
        // 物品要用物品管线（原版 ItemFeatureRenderer 走的就是 ITEM_CUTOUT）。
        RenderType type = RenderTypes.itemCutout(texture);
        collector.submitCustomGeometry(poseStack, type, (pose, buffer) -> {
            // ---- 正面（+Z）：FaceInfo.SOUTH 的绕序 ----
            vertex(buffer, pose, MIN, MAX, 0.0F, 0.0F, PLANE_Z_FRONT, 1.0F, light, overlay);
            vertex(buffer, pose, MIN, MIN, 0.0F, 1.0F, PLANE_Z_FRONT, 1.0F, light, overlay);
            vertex(buffer, pose, MAX, MIN, 1.0F, 1.0F, PLANE_Z_FRONT, 1.0F, light, overlay);
            vertex(buffer, pose, MAX, MAX, 1.0F, 0.0F, PLANE_Z_FRONT, 1.0F, light, overlay);

            // ---- 背面（-Z）：FaceInfo.NORTH 的绕序，UV 左右镜像 ----
            vertex(buffer, pose, MAX, MAX, 0.0F, 0.0F, PLANE_Z_BACK, -1.0F, light, overlay);
            vertex(buffer, pose, MAX, MIN, 0.0F, 1.0F, PLANE_Z_BACK, -1.0F, light, overlay);
            vertex(buffer, pose, MIN, MIN, 1.0F, 1.0F, PLANE_Z_BACK, -1.0F, light, overlay);
            vertex(buffer, pose, MIN, MAX, 1.0F, 0.0F, PLANE_Z_BACK, -1.0F, light, overlay);
        });
    }

    /**
     * 给平面补<b>侧壁</b>，让它看起来是一块有厚度的挤出板而不是一片纸。
     *
     * <h2>实现完全照抄原版 {@code ItemModelGenerator.bakeSideFaces}</h2>
     * <p>原版 2D 物品走 {@code bakeExtrudedSprite()} + {@code bakeSideFaces()}，
     * 而 {@code minecraft:special} 不渲染 base 的几何 —— 所以这里把原版那段搬过来。</p>
     *
     * <p><b>照着抄的四个关键点</b>（之前自己瞎写，四处全错）：</p>
     * <ol>
     *   <li><b>逐像素一条边一个四边形</b>，不做合并 —— 合并之后 UV 没法沿边正确展开，
     *       看起来就是「侧面被拉长」</li>
     *   <li><b>y 用 {@code 16 - y}</b> 翻转（翻的是像素的<b>上边缘</b>）。
     *       之前写 {@code 15 - py}，整体错开一格</li>
     *   <li><b>UV 内缩 0.1</b>，且<b>垂直边的 v 要反向</b>（原版 if/else 那两行）</li>
     *   <li><b>z 是 7.5 ~ 8.5</b>（之前写 7.4，底面因此缺一段）</li>
     * </ol>
     */
    private static void drawSideFaces(PoseStack poseStack, SubmitNodeCollector collector,
                                      Identifier texture, NativeImage img, int light, int overlay) {
        // ⚠️ 这里**不能**自己去 readTexture(texture)：那张图是注册在 TextureManager 的
        //    动态纹理，ResourceManager 读不到（两套系统），readTexture 恒返回 null。
        //    像素由 bake() 通过 COMPOSED_PIXELS 传进来。
        if (img == null || img.isClosed()) {
            return;
        }
        RenderType type = RenderTypes.itemCutout(texture);
        float zFrom = SIDE_Z_FROM;
        float zTo = SIDE_Z_TO;
        int w = img.getWidth();
        int h = img.getHeight();
        collector.submitCustomGeometry(poseStack, type, (pose, buffer) -> {
            int quads = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!isOpaque(img, x, y)) {
                        continue;
                    }
                    for (SideDir dir : SideDir.values()) {
                        // 该方向的邻居是透明（或越界）→ 这条边外露
                        int nx = x + dir.stepX;
                        int ny = y + dir.stepY;
                        if (isOpaque(img, nx, ny)) {
                            continue;
                        }

                        // ===== UV：照原版 u/v 的取法 =====
                        float u0 = x + 0.1F;
                        float u1 = x + 1.0F - 0.1F;
                        float v0;
                        float v1;
                        if (dir.horizontal) {
                            v0 = y + 0.1F;
                            v1 = y + 1.0F - 0.1F;
                        } else {
                            v0 = y + 1.0F - 0.1F;
                            v1 = y + 0.1F;
                        }
                        u0 /= w;
                        u1 /= w;
                        v0 /= h;
                        v1 /= h;

                        // ===== 端点：照原版 switch 那四段 =====
                        float startX = x;
                        float startY = y;
                        float endX = x;
                        float endY = y;
                        switch (dir) {
                            case UP -> endX++;
                            case DOWN -> {
                                endX++;
                                startY++;
                                endY++;
                            }
                            case LEFT -> endY++;
                            case RIGHT -> {
                                startX++;
                                endX++;
                                endY++;
                            }
                        }

                        // ===== 换算到 3D：缩放 + y 翻转（16 - y） =====
                        float xScale = 16.0F / w;
                        float yScale = 16.0F / h;
                        startX *= xScale;
                        endX *= xScale;
                        startY *= yScale;
                        endY *= yScale;
                        startY = 16.0F - startY;
                        endY = 16.0F - endY;

                        // ===== 按方向摆 from / to（照原版 switch） =====
                        float fx0;
                        float fy0;
                        float fx1;
                        float fy1;
                        switch (dir) {
                            case UP -> {
                                fx0 = startX; fy0 = startY; fx1 = endX; fy1 = startY;
                            }
                            case DOWN -> {
                                fx0 = startX; fy0 = endY; fx1 = endX; fy1 = endY;
                            }
                            case LEFT -> {
                                fx0 = startX; fy0 = startY; fx1 = startX; fy1 = endY;
                            }
                            default -> {
                                fx0 = endX; fy0 = startY; fx1 = endX; fy1 = endY;
                            }
                        }

                        // ===== 四顶点（照原版 from/to 构成的矩形） =====
                        //   from 与 to 在对角线上，另外两点由它们组合出来
                        vertexRaw(buffer, pose, fx0, fy0, zFrom, u0, v0, dir.nx, dir.ny, 0, light, overlay);
                        vertexRaw(buffer, pose, fx0, fy0, zTo, u0, v0, dir.nx, dir.ny, 0, light, overlay);
                        vertexRaw(buffer, pose, fx1, fy1, zTo, u1, v1, dir.nx, dir.ny, 0, light, overlay);
                        vertexRaw(buffer, pose, fx1, fy1, zFrom, u1, v1, dir.nx, dir.ny, 0, light, overlay);
                        quads++;
                    }
                }
            }
            if (SIDE_DIAG.getAndIncrement() < 1) {
                MoreBalls.LOGGER.info("[ball][弩] 侧壁四边形 {} 个（图 {}x{}，z {}~{}）",
                        quads, w, h, zFrom, zTo);
            }
        });
    }

    /**
     * 这个像素**是否不透明**。
     *
     * <p>⚠️ 不能用 {@code getLuminanceOrAlpha} —— 它返回「亮度<b>或</b> alpha」，
     * 对<b>深色像素</b>（弩的贴图里大量深棕与近黑）会返回 0，于是被判成「透明」跳过。
     * 这里只认 alpha 通道。越界一律算透明（图边缘向外就是空气，那条边当然外露）。</p>
     */
    private static boolean isOpaque(NativeImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
            return false;
        }
        return (img.getPixel(x, y) >> 24) != 0;
    }

    /** 侧壁诊断计数（只打前几次） */
    private static final java.util.concurrent.atomic.AtomicInteger SIDE_DIAG =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 侧壁四方向 —— 照原版 SideDirection 的语义（水平 = 上下两条边） */
    private enum SideDir {
        UP(0, -1, true, 0, -1),
        DOWN(0, 1, true, 0, 1),
        LEFT(-1, 0, false, -1, 0),
        RIGHT(1, 0, false, 1, 0);

        final int stepX;
        final int stepY;
        final boolean horizontal;
        final float nx;
        final float ny;

        SideDir(int stepX, int stepY, boolean horizontal, float nx, float ny) {
            this.stepX = stepX;
            this.stepY = stepY;
            this.horizontal = horizontal;
            this.nx = nx;
            this.ny = ny;
        }
    }

    /** 带完整法线向量的顶点（侧壁用） */
    private static void vertexRaw(VertexConsumer buffer, PoseStack.Pose pose,
                                  float x, float y, float z, float u, float v,
                                  float nx, float ny, float nz, int light, int overlay) {
        buffer.addVertex(pose, x, y, z)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
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

    // ===== 合成与缓存 =====

    /**
     * 拿到「这颗组合球装填后」的贴图 id —— 没合成过就现合成。
     *
     * @param slots 四个象限的来源下标；{@code null} 表示没读到数据，此时只合成底图
     */
    private static @Nullable Identifier composedTexture(int @Nullable [] slots) {
        int[] key = (slots == null) ? new int[] { NO_PIECE, NO_PIECE, NO_PIECE, NO_PIECE } : slots;
        String cacheKey = Arrays.toString(key);
        if (COMPOSED.containsKey(cacheKey)) {
            Identifier cached = COMPOSED.get(cacheKey);
            // 负缓存（合成失败过）直接返回 null，不要再读一遍贴图
            return (cached == FAILED) ? null : cached;
        }
        Identifier baked = bake(slots);
        // 失败也要记下来 —— 否则每帧都会重跑一次「读图 + 逐像素合成」
        COMPOSED.put(cacheKey, (baked != null) ? baked : FAILED);
        return baked;
    }

    /**
     * 取这一次合成结果的<b>像素副本</b>（侧壁扫轮廓要用）。
     *
     * <p>键与 {@link #composedTexture} 一致；没合成过或合成失败时返回 null。</p>
     */
    private static @Nullable NativeImage composedPixels(int @Nullable [] slots) {
        int[] key = (slots == null) ? new int[] { NO_PIECE, NO_PIECE, NO_PIECE, NO_PIECE } : slots;
        NativeImage img = COMPOSED_PIXELS.get(Arrays.toString(key));
        // 每次都返回一份可读的副本副本没必要 —— 调用方只读不写，直接给同一个引用
        return (img == null || img.isClosed()) ? null : img;
    }

    /**
     * 把底图与四个象限在 CPU 上合成成一张 16×16 的图，注册成动态纹理。
     *
     * <p>逐像素对着 RGBA 做 source-over，透明像素不覆盖底下的内容。</p>
     */
    private static @Nullable Identifier bake(int @Nullable [] slots) {
        NativeImage canvas = readTexture(BASE_TEXTURE);
        if (canvas == null) {
            MoreBalls.LOGGER.warn("[ball][弩] ★ 底图 {} 读不出来，弩的外观将不可见", BASE_TEXTURE);
            return null;
        }

        if (slots != null) {
            // 二合一还是四合一？判据是「上半两象限的来源是不是同一个」——
            // 二合一时 0/1 同源、2/3 同源；四合一时四格互不相同（同种球会例外，见下）。
            boolean twoPart = slots[0] == slots[1] && slots[2] == slots[3]
                    && slots[0] != NO_PIECE && slots[2] != NO_PIECE;
            if (twoPart && slots[0] != slots[2]) {
                // ===== 二合一：两块各占一整个半圆，用整张半球贴图 =====
                NativeImage topPiece = readTexture(halfTexture(slots[0], true));
                if (topPiece != null) {
                    overlay(canvas, topPiece);
                    topPiece.close();
                }
                NativeImage bottomPiece = readTexture(halfTexture(slots[2], false));
                if (bottomPiece != null) {
                    overlay(canvas, bottomPiece);
                    bottomPiece.close();
                }
            } else {
                // ===== 四合一（或同种球合成）：逐象限拼 =====
                for (int q = 0; q < 4; q++) {
                    if (slots[q] == NO_PIECE) {
                        continue;
                    }
                    NativeImage piece = readTexture(pieceTexture(slots[q], q + 1));
                    if (piece == null) {
                        continue;
                    }
                    overlay(canvas, piece);
                    piece.close();
                }
            }
        }

        // 每颗球一份贴图；名字带上尺寸与本图指纹，避免撞名
        Identifier id = resource("combo_charge/" + Integer.toHexString(canvas.hashCode())
                + "_" + COMPOSED.size());
        String label = "more_balls combo charge " + Arrays.toString(slots);
        // 先留一份像素给侧壁用（canvas 交给 DynamicTexture 之后不能再碰）
        NativeImage pixels = new NativeImage(NativeImage.Format.RGBA, canvas.getWidth(), canvas.getHeight(), false);
        for (int py = 0; py < canvas.getHeight(); py++) {
            for (int px = 0; px < canvas.getWidth(); px++) {
                pixels.setPixel(px, py, canvas.getPixel(px, py));
            }
        }
        COMPOSED_PIXELS.put(Arrays.toString(slots == null ? new int[] { NO_PIECE, NO_PIECE, NO_PIECE, NO_PIECE } : slots), pixels);

        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> label, canvas));
        MoreBalls.LOGGER.info("[ball][弩] ④ 合成装填贴图 {} <- slots={}（{}）", id, Arrays.toString(slots),
                (slots == null) ? "只画底图" : "已叠象限/半球");
        return id;
    }

    /** 读一张贴图为可写像素缓冲；失败返回 null */
    private static @Nullable NativeImage readTexture(Identifier id) {
        try {
            Resource resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(id);
            try (InputStream in = resource.open()) {
                return NativeImage.read(in);
            }
        } catch (IOException | RuntimeException e) {
            MoreBalls.LOGGER.warn("[ball][弩] ★ 读取贴图失败 {}：{}", id, e.toString());
            return null;
        }
    }

    /**
     * 把 {@code top} 按 alpha 叠到 {@code base} 上（source-over）。
     *
     * <p>两张图都是 16×16，直接逐像素处理。{@link NativeImage} 的坐标原点是左上角，
     * 与贴图一致，不需要翻转。</p>
     */
    private static void overlay(NativeImage base, NativeImage top) {
        int w = Math.min(base.getWidth(), top.getWidth());
        int h = Math.min(base.getHeight(), top.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int src = top.getPixel(x, y);
                int sa = (src >>> 24) & 0xFF;
                if (sa == 0) {
                    continue;   // 全透明 → 保留底下的内容
                }
                if (sa == 255) {
                    base.setPixel(x, y, src);
                    continue;
                }
                int dst = base.getPixel(x, y);
                double a = sa / 255.0;
                int r = (int) (((src >> 16) & 0xFF) * a + ((dst >> 16) & 0xFF) * (1 - a));
                int g = (int) (((src >> 8) & 0xFF) * a + ((dst >> 8) & 0xFF) * (1 - a));
                int b = (int) ((src & 0xFF) * a + (dst & 0xFF) * (1 - a));
                int da = (dst >>> 24) & 0xFF;
                int outA = (int) (sa + da * (1 - a));
                base.setPixel(x, y, (outA << 24) | (r << 16) | (g << 8) | b);
            }
        }
    }

    /** 无状态，共用一个实例 */
    public static final ComboChargeBallRenderer INSTANCE = new ComboChargeBallRenderer();

    /**
     * 包围盒 —— 必须与 {@link #submit} 里**实际提交的几何**处在同一空间。
     *
     * <p>{@code submit} 会给 poseStack 乘 {@link #MODEL_SCALE}（1/16），
     * 所以屏幕上的几何实际是 0..1；而原来这里返回的是模型空间的 0..16 ——
     * 两者差 16 倍，包围盒比真实外观大出一大截（物品展示框 / GUI 里的缩放会跟着错）。</p>
     */
    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        // 包围盒报的是**提交时 poseStack 所在的空间**，而 submit() 里
        // 几何是在 scale(MODEL_SCALE) **之后**提交的 —— 也就是已经落到 0..1。
        // 所以这里报 0..1，与几何一致。
        float s = MODEL_SCALE;
        output.accept(new Vector3f(MIN * s, MIN * s, MIN * s));
        output.accept(new Vector3f(MAX * s, MAX * s, MAX * s));
    }

    /** 注册用的 Unbaked */
    public record Unbaked() implements SpecialModelRenderer.Unbaked<int[]> {

        public static final Unbaked INSTANCE = new Unbaked();

        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(INSTANCE);

        @Override
        public @Nullable SpecialModelRenderer<int[]> bake(SpecialModelRenderer.BakingContext context) {
            MoreBalls.LOGGER.info("[ball][弩] ① Unbaked.bake() 被调用 —— special 渲染器注册生效");
            return ComboChargeBallRenderer.INSTANCE;
        }

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked<int[]>> type() {
            return MAP_CODEC;
        }
    }
}
