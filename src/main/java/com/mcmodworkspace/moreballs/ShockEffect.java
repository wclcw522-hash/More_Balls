package com.mcmodworkspace.moreballs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * 【震荡】—— 五级的「晕头转向」debuff（作者 2026-10-10 指定的规格）。
 *
 * <h2>效果本身</h2>
 * <ul>
 *   <li>持有人在持续时间内，每隔固定刻数被<b>强制随机转向一次</b></li>
 *   <li>转向同时作用于<b>视角方向</b>与<b>运动方向</b> —— 两边都扭</li>
 *   <li>五级的间隔分别是 <b>20 / 17 / 13 / 8 / 2</b> 刻（越高越频繁）</li>
 * </ul>
 *
 * <h2>「无视药水效果免疫」怎么做到</h2>
 * <p>查 {@code LivingEntity.addEffect} 的实现：它只调
 * {@code CommonHooks.canMobEffectBeApplied}，而那里面<b>只 post 一个
 * {@code MobEffectEvent.Applicable} 事件</b>，并不会去查 {@code canBeAffected}。
 * 免疫真正的来源是 {@link LivingEntity#canBeAffected}（按
 * {@code IMMUNE_TO_INFESTED} / {@code IGNORES_POISON_AND_REGEN} 这些标签判），
 * 而它只在<b>调用方</b>（{@code /effect} 命令、药水）被查。</p>
 *
 * <p>所以「无视免疫」用官方途径即可：本类在
 * {@code MobEffectEvent.Applicable} 上把【震荡】的结果强制设成
 * {@link net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable.Result#APPLY}，
 * 于是任何生物、任何来源都挡不住它，也不需要碰原版代码。</p>
 *
 * <h2>转向为什么分两条路</h2>
 * <ul>
 *   <li><b>运动方向</b>在服务端就能改（{@code setDeltaMovement}），改完置
 *       {@code hurtMarked} 让客户端同步速度</li>
 *   <li><b>视角方向</b>属于客户端状态，服务端必须发包才能改 —— 玩家用
 *       {@code connection.teleport} 带朝向发一次位置包（坐标不变，只改 yaw/pitch）；
 *       非玩家生物直接用 {@code setYRot}</li>
 * </ul>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public class ShockEffect extends MobEffect {

    /** 英文 id（作者指定为 {@code shock}） */
    public static final String EFFECT_ID = "shock";

    /**
     * 最大等级：五级。
     *
     * <p>内部 amplifier 从 0 起算，所以上限是 {@code 4}。</p>
     */
    public static final int MAX_AMPLIFIER = 4;

    /**
     * 每一级的「转向间隔」（刻），作者指定：<b>20 / 17 / 13 / 8 / 2</b>。
     *
     * <p>数值越高转向越频繁 —— 五级每 2 刻扭一次，基本等于一直在打转。</p>
     */
    private static final int[] INTERVAL_TICKS = {20, 17, 13, 8, 2};

    /** 单次转向的最大水平偏移（度）—— 取 ±60°，即左右各可扭 60° */
    private static final float YAW_SWING = 120.0F;

    /** 单次转向的最大俯仰偏移（度）—— 取 ±30°，即上下各可扭 30° */
    private static final float PITCH_SWING = 60.0F;

    public ShockEffect() {
        // HARMFUL = 红框（debuff），颜色取暗红，与「熔融物烧伤」的暖色区分开
        super(MobEffectCategory.HARMFUL, 0x8B1A1A);
    }

    /** 这一级每隔多少刻扭一次 */
    public static int intervalFor(int amplifier) {
        return INTERVAL_TICKS[Mth.clamp(amplifier, 0, INTERVAL_TICKS.length - 1)];
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int tickCount, int amplifier) {
        return tickCount % intervalFor(amplifier) == 0;
    }

    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity mob, int amplifier) {
        applyShock(mob);
        return true;
    }

    /**
     * <b>无视一切免疫</b> —— 把【震荡】的结果强制设成 {@code APPLY}。
     *
     * <p>见类注释「无视药水效果免疫怎么做到」：{@code addEffect} 最终走的是
     * {@code CommonHooks.canMobEffectBeApplied}，而那里面只 post 这一个事件。
     * 所以在这里返回 APPLY，就等于「谁都挡不住」——不需要碰原版代码。</p>
     */
    @SubscribeEvent
    public static void onApplicable(MobEffectEvent.Applicable event) {
        if (event.getEffectInstance().is(ModEffects.SHOCK)) {
            event.setResult(MobEffectEvent.Applicable.Result.APPLY);
        }
    }

    /**
     * 施加一次随机转向 —— 视角与运动方向一起扭。
     *
     * <p>抽出来做成 public static，是为了让【脉冲】等地方能在<b>挂上效果的瞬间</b>
     * 也立刻扭一下（不然要等满一个间隔才看得出反应）。</p>
     */
    public static void applyShock(LivingEntity mob) {
        // ===== ① 运动方向 =====
        Vec3 velocity = mob.getDeltaMovement();
        if (velocity.lengthSqr() > 1.0E-6D) {
            // 绕 Y 轴随机旋转一个角度。注意 Vec3#yRot 收的是**弧度**（内部走 Mth.cos/sin）
            float radians = (mob.getRandom().nextFloat() - 0.5F) * 2.0F * (float) Math.PI;
            mob.setDeltaMovement(velocity.yRot(radians));
            // 改了速度要标脏，否则客户端还按旧速度插值，看起来是「瞬移」而不是「转向」
            mob.hurtMarked = true;
        }

        // ===== ② 视角方向 =====
        if (mob instanceof ServerPlayer player) {
            float yaw = player.getYRot() + (player.getRandom().nextFloat() - 0.5F) * YAW_SWING;
            float pitch = Mth.clamp(
                    player.getXRot() + (player.getRandom().nextFloat() - 0.5F) * PITCH_SWING,
                    -90.0F, 90.0F);
            player.setYRot(yaw);
            player.setXRot(pitch);
            // 服务端改朝向必须发包，客户端才会真的扭头。
            // 坐标原样传回去，所以只会转视角、不会把人挪走。
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), yaw, pitch);
        } else {
            // 非玩家生物：没有客户端视角，扭身体朝向即可（同时带上头部朝向，看着更自然）
            float yaw = mob.getYRot() + (mob.getRandom().nextFloat() - 0.5F) * YAW_SWING;
            mob.setYRot(yaw);
            mob.setYHeadRot(yaw);
        }
    }
}
