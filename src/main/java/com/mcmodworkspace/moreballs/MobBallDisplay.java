package com.mcmodworkspace.moreballs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.SlotAccess;
import com.mojang.math.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

import java.util.Map;
import java.util.UUID;

/**
 * <b>怪物手里那颗球的「展示」</b> —— 用原版的 {@code ItemDisplay} 实体挂在生物身上。
 *
 * <h2>为什么需要它</h2>
 * <p>原版只有少数生物会渲染主手物品（僵尸 / 骷髅 / 猪灵 / 掠夺者这些类人形走
 * {@code HumanoidMobRenderer} 的 {@code ItemInHandLayer}）。<b>蜘蛛、洞穴蜘蛛、幻翼、嘎枝
 * 这些模型根本没有「手持物品」那一层</b> —— 球拿在手里是看不见的。</p>
 *
 * <p>所以给它们额外挂一个 {@code ItemDisplay}，每刻跟着生物摆位置。</p>
 *
 * <h2>为什么不用骑乘 / 不用每刻扫实体</h2>
 * <ul>
 *   <li><b>不骑乘</b>：{@code Display} 的骑乘只能坐到生物脚下，位置不对；
 *       而且它那些 {@code setTransformation} 之类的 setter 都是 <b>private</b>
 *       （位置靠 {@code setPos}，那是 {@code Entity} 的公开方法）。</li>
 *   <li><b>不每刻扫实体找图标</b>：{@code getEntitiesOfClass} 半径 64 就是 128³ 的实体查询，
 *       又贵又会在<b>区块边界上漏</b> —— 漏了就新建一个，于是图标一闪一闪、还留残影。
 *       改用「UUID → 实体 id」的记录表（原来 {@code CreeperBallBehavior} 里踩过这个坑）。</li>
 * </ul>
 *
 * <h2>⚠️ 关于缩放</h2>
 * <p>{@code Display.setTransformation} 是 <b>private</b>，本模组的
 * {@code META-INF/accesstransformer.cfg} 把它放开了 —— 不放开就只能让图标保持默认大小，
 * 而作者要求「变小到与掉落物图标差不多大」。</p>
 */
public final class MobBallDisplay {

    private MobBallDisplay() {
    }

    /** 所有展示实体共用的标签（兜底清理时按它找） */
    public static final String TAG = "rotation_craft:mob_ball_display";

    /** 「这个图标属于谁」的标签前缀 —— 用它精确认领，不依赖包围盒 */
    public static final String OWNER_PREFIX = "rotation_craft:ball_display_owner:";

    /**
     * 头顶展示：离头顶多高（格）。
     *
     * <p>与苦力怕那个图标原来的 {@code HEAD_ICON_LIFT = 0.42} 保持一致。</p>
     */
    public static final double HEAD_LIFT = 0.42D;

    /**
     * 头顶展示的缩放 —— <b>作者指定：与掉落物图标差不多大</b>。
     *
     * <p>这是个可调常量：觉得大就调小、觉得小就调大，改这一个数全部生物一起变。</p>
     */
    public static final float HEAD_SCALE = 0.5F;

    /**
     * 生物 UUID → 它的展示实体 id。
     *
     * <p>带 LRU 上限，防止长期运行时无限增长。</p>
     */
    private static final Map<UUID, Integer> IDS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Integer> eldest) {
                    return size() > 256;
                }
            });

    /** 属于这只生物的图标标签 —— 用 UUID 精确认领 */
    public static String ownerTag(UUID uuid) {
        return OWNER_PREFIX + uuid;
    }

    /**
     * 让球挂在生物头顶（已经挂了就只更新位置与内容）。
     *
     * @param lift  离头顶多高；不同生物可以不一样
     * @param scale 缩放；{@link #HEAD_SCALE} 是「与掉落物图标差不多大」
     */
    public static void ensure(Mob mob, ItemStack stack, double lift, float scale) {
        // 死了 / 已被移除就绝不再建 —— 生物死亡有死亡动画，那期间它仍然是 alive、
        // tick 照样触发，每刻建了又删看上去就是「死后还闪几下」。
        if (!mob.isAlive() || mob.isRemoved() || mob.deathTime > 0) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }

        Display.ItemDisplay icon = fromRecord(mob, level);
        if (icon == null) {
            icon = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
            icon.addTag(TAG);
            icon.addTag(ownerTag(mob.getUUID()));
            IDS.put(mob.getUUID(), icon.getId());
            level.addFreshEntity(icon);
        }

        SlotAccess slot = icon.getSlot(0);
        if (slot != null) {
            slot.set(stack.copyWithCount(1));
        }
        // ⚠️ Display 的那几个 setter 都是 private，位置只能靠 Entity.setPos 摆
        icon.setPos(mob.getX(),
                mob.getY() + mob.getBbHeight() + lift,
                mob.getZ());
        applyLook(icon, scale);
    }

    /**
     * 生物 UUID → 它的<b>多个</b>展示实体 id（烈焰人八肢那种）。
     *
     * <p>与 {@link #IDS} 分开存 —— 单槽位（头顶/体内）与多槽位是两套互不干扰的账。</p>
     */
    private static final Map<UUID, List<Integer>> MULTI_IDS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, List<Integer>> eldest) {
                    return size() > 64;
                }
            });

    /**
     * 把 N 颗球分别挂在生物的 N 个位置上（每个位置一个独立的展示实体）。
     *
     * <p><b>位置由调用方算好、传世界坐标</b> —— 因为不同生物「肢体在哪」的算法
     * 完全不一样（烈焰人那八根棒子要复刻 {@code BlazeModel.setupAnim} 的逐帧角度），
     * 放在这里只会把渲染知识塞进一个通用类。</p>
     *
     * <p>⚠️ 位置必须<b>每刻</b>重算并传进来 —— 烈焰人那八根棒子是<b>绕着身体转</b>的。</p>
     */
    public static void ensureMulti(Mob mob, List<ItemStack> balls, List<Vec3> positions, float scale) {
        if (!mob.isAlive() || mob.isRemoved() || mob.deathTime > 0) {
            clearMulti(mob);
            return;
        }
        if (!(mob.level() instanceof ServerLevel level) || positions.isEmpty()) {
            clearMulti(mob);
            return;
        }

        List<Integer> ids = MULTI_IDS.get(mob.getUUID());
        // 数量对不上（第一次建 / 球数变了）→ 整批拆掉重建，逻辑最简单
        if (ids == null || ids.size() != positions.size()) {
            clearMulti(mob);
            List<Integer> fresh = new ArrayList<>(positions.size());
            for (int i = 0; i < positions.size(); i++) {
                Display.ItemDisplay icon = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
                icon.addTag(TAG);
                icon.addTag(ownerTag(mob.getUUID()));
                fresh.add(icon.getId());
                level.addFreshEntity(icon);
            }
            ids = fresh;
            MULTI_IDS.put(mob.getUUID(), ids);
        }

        for (int i = 0; i < ids.size(); i++) {
            Entity e = level.getEntity(ids.get(i));
            if (!(e instanceof Display.ItemDisplay icon)) {
                continue;
            }
            SlotAccess slot = icon.getSlot(0);
            if (slot != null) {
                slot.set(balls.get(i).copyWithCount(1));
            }
            Vec3 p = positions.get(i);
            icon.setPos(p.x, p.y, p.z);
            applyLook(icon, scale);
        }
    }

    /**
     * 摆好一个展示实体的「朝向 + 缩放」。
     *
     * <p>⚠️ <b>{@code setBillboardConstraints} 必须设</b>（作者 2026-10-11 报的
     * 「环绕的贴图卡成侧面转不过来」）：</p>
     * <p>原版 {@code Display} 的 billboard 默认是 <b>{@code FIXED}（固定朝向）</b> ——
     * 见 {@code Display.readAdditionalSaveData} 里的
     * {@code .orElse(Display.BillboardConstraints.FIXED)}。不设的话图标就是一个
     * <b>固定世界朝向</b>的薄片，从侧面（尤其是它绕着火人转的时候）看过去只剩一条边。</p>
     * <p>设成 {@code CENTER} = <b>永远正对镜头</b>，绕着转也不会变成侧边。</p>
     */
    private static void applyLook(Display.ItemDisplay icon, float scale) {
        icon.setTransformation(new Transformation(
                null, new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        icon.setBillboardConstraints(Display.BillboardConstraints.CENTER);
    }

    /** 收掉这只生物的多槽位展示图标 */
    public static void clearMulti(Mob mob) {
        List<Integer> ids = MULTI_IDS.remove(mob.getUUID());
        if (ids == null || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        for (Integer id : ids) {
            if (level.getEntity(id) instanceof Display.ItemDisplay icon) {
                icon.discard();
            }
        }
    }

    /** 收掉这只生物的展示图标 */
    public static void clear(Mob mob) {
        clearMulti(mob);
        Display.ItemDisplay recorded = fromRecord(mob, mob.level() instanceof ServerLevel level ? level : null);
        if (recorded != null) {
            recorded.discard();
        }
        IDS.remove(mob.getUUID());
    }

    /**
     * 生物已经不存活 / 被移除了 —— 收掉它的展示图标。
     *
     * <p>图标是<b>独立实体</b>，不会跟着生物一起死；不在 tick 里主动收就会永远留在原地。</p>
     */
    public static void clearIfDead(Mob mob) {
        if (!mob.isAlive() || mob.isRemoved()) {
            clear(mob);
        }
    }

    /** 从记录表里取；已经没了就返回 null 并清掉记录 */
    private static Display.ItemDisplay fromRecord(Mob mob, ServerLevel level) {
        Integer id = IDS.get(mob.getUUID());
        if (id == null) {
            return null;
        }
        if (level == null) {
            IDS.remove(mob.getUUID());
            return null;
        }
        Entity e = level.getEntity(id);
        if (e instanceof Display.ItemDisplay display && display.isAlive()) {
            return display;
        }
        IDS.remove(mob.getUUID());
        return null;
    }
}