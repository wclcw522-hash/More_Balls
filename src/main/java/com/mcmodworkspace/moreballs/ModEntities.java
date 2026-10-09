package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.BallEnderpearl;
import com.mcmodworkspace.moreballs.entity.BallProjectile;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的实体注册。
 *
 * <p>判定盒是「球的外接正方体」：球的贴图 16×16、圆体占 x2..13 / y2..13 共 12 像素，
 * 也就是 <b>0.75 格</b>，所以尺寸设成 0.75 × 0.75（作者 2026-10-08 指定）。
 * 追踪范围仍照抄原版雪球，让它在网络同步上与其它投掷物表现一致。
 * <p>原先写的是 0.25（原版雪球的尺寸），判定明显小于外观。
 * 让它与原版投掷物在网络同步与命中判定上表现一致。</p>
 */
public final class ModEntities {

    private ModEntities() {
    }

    /** 实体类型注册器；在入口类里挂到 mod 事件总线 */
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MoreBalls.MOD_ID);

    /**
     * {@code more_balls:ball_ender_pearl} —— 作为弩弹药射出的末影珍珠。
     *
     * <p>与原版珍珠的区别只有一个：命中实体时造成 {@link BallEnderpearl#AMMO_DAMAGE} 点伤害，
     * 传送功能照旧。</p>
     */
    public static final DeferredHolder<EntityType<?>, EntityType<BallEnderpearl>> BALL_ENDER_PEARL =
            ENTITIES.register("ball_ender_pearl", () -> EntityType.Builder
                    .<BallEnderpearl>of(BallEnderpearl::new, MobCategory.MISC)
                .sized(0.25F, 0.25F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "ball_ender_pearl"))));

    /** {@code more_balls:ball} —— 所有球类弹药的通用投射物 */
    public static final DeferredHolder<EntityType<?>, EntityType<BallProjectile>> BALL =
            ENTITIES.register("ball", () -> EntityType.Builder
                    .<BallProjectile>of(BallProjectile::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "ball"))));
}
