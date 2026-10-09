package com.mcmodworkspace.moreballs;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 【脉冲】的粒子表现 —— <b>三团扩散的球面</b>（作者 2026-10-10 指定的规格）。
 *
 * <h2>时间轴</h2>
 * <p>命中之后 3 秒内，三团球面各自从 0 开始扩大，先涨到半径 7，再涨到半径 10：</p>
 *
 * <table border="1">
 *   <tr><th>团</th><th>→ 半径 7</th><th>→ 半径 10</th><th>合计</th></tr>
 *   <tr><td>A</td><td>0.5 秒</td><td>再用 2.5 秒</td><td>3.0 秒</td></tr>
 *   <tr><td>B</td><td>1.0 秒</td><td>再用 2.0 秒</td><td>3.0 秒</td></tr>
 *   <tr><td>C</td><td>2.0 秒</td><td>再用 1.0 秒</td><td>3.0 秒</td></tr>
 * </table>
 *
 * <h2>关于「初始不透明度 40%」</h2>
 * <p>这一条<b>没法按字面实现</b>：原版的 {@link DustParticleOptions} 只接受
 * <b>RGB24</b>（构造完还会走 {@code ARGB.vector3fFromRGB24} 丢掉 alpha），
 * 也就是说原版红色粒子<b>根本没有透明度参数</b>。</p>
 *
 * <p>所以这里用两个可调的维度去<b>近似</b>那种「半透明 → 逐渐消散」的观感：</p>
 * <ul>
 *   <li><b>亮度</b>：起始按 40% 亮度出（视觉上就偏暗、偏透），之后随时间递减到接近黑</li>
 *   <li><b>密度</b>：起始每团只撒少量点，越到后面越稀，最后几帧几乎只剩零星几点</li>
 * </ul>
 * <p>两者叠加起来，肉眼看到的效果就是「一开始淡红的一层球壳慢慢涨开、越来越淡直到消失」。</p>
 *
 * <h2>为什么是服务端驱动的</h2>
 * <p>用 {@code ServerLevel#sendParticles} 发，客户端只负责显示 ——
 * 这样多人游戏里每个玩家看到的球面位置完全一致，也不用各自算时间轴。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class RedstonePulseFx {

    private RedstonePulseFx() {
    }

    /** 一个球面团的时间轴（单位：刻） */
    private record Wave(int expandToNearTicks, int totalTicks) {
    }

    /**
     * 三个团的时间轴。
     *
     * <p>{@code expandToNearTicks} 是「涨到半径 7」用时，{@code totalTicks} 是整条命；
     * 中间那段（{@code totalTicks - expandToNearTicks}）就是「7 → 10」的用时。</p>
     */
    private static final Wave[] WAVES = {
            new Wave(10, 60),   // 0.5s → 7，再 2.5s → 10
            new Wave(20, 60),   // 1.0s → 7，再 2.0s → 10
            new Wave(40, 60),   // 2.0s → 7，再 1.0s → 10
    };

    /** 第一阶段的目标半径（格） */
    private static final double RADIUS_NEAR = 7.0D;

    /** 第二阶段的目标半径（格） */
    private static final double RADIUS_FAR = 10.0D;

    /** 每隔几刻撒一批粒子 —— 逐刻撒会糊成一团光斑，看不出是球壳 */
    private static final int EMIT_INTERVAL = 2;

    /** 每团每批撒多少个点 */
    private static final int POINTS_PER_BATCH = 18;

    /** 起始亮度系数（对应作者说的「初始不透明度 40%」） */
    private static final float START_BRIGHTNESS = 0.4F;

    /** 结束亮度系数（几乎看不见，等价于消散） */
    private static final float END_BRIGHTNESS = 0.04F;

    /** 亮红的几个变体 —— 作者要「各类亮红色粒子」，所以每批随机挑一种 */
    private static final int[] RED_VARIANTS = {
            0xFF2020,   // 正红
            0xFF4020,   // 偏橙的亮红
            0xFF1040,   // 偏品红的亮红
            0xE01010,   // 深一档的红
    };

    /** 正在播的脉冲 */
    private record Active(ServerLevel level, Vec3 center, long startTick) {
    }

    private static final List<Active> ACTIVE = new ArrayList<>();

    /** 命中时调用 —— 登记一次脉冲，之后由 {@link #onServerTick} 推进 */
    public static void start(ServerLevel level, Vec3 center) {
        ACTIVE.add(new Active(level, center, level.getGameTime()));
    }

    /** 最长的一条时间轴（刻） */
    private static int maxTicks() {
        int max = 0;
        for (Wave wave : WAVES) {
            max = Math.max(max, wave.totalTicks());
        }
        return max;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        int lifetime = maxTicks();
        // 逐项推进；超过寿命的直接摘掉。
        // 用显式索引遍历 + 倒序删除，避免在 for-each 里改同一个 List。
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Active pulse = ACTIVE.get(i);
            long age = pulse.level().getGameTime() - pulse.startTick();
            if (age < 0L || age > lifetime) {
                ACTIVE.remove(i);
                continue;
            }
            if (age % EMIT_INTERVAL == 0L) {
                emit(pulse, (int) age);
            }
        }
    }

    /** 按时间轴把三个球面这一帧该有的点撒出去 */
    private static void emit(Active pulse, int age) {
        ServerLevel level = pulse.level();
        Vec3 center = pulse.center();
        RandomSource random = level.getRandom();

        for (Wave wave : WAVES) {
            if (age > wave.totalTicks()) {
                continue;   // 这一团已经收工
            }

            double radius = radiusAt(wave, age);
            float brightness = brightnessAt(wave, age);

            // 亮度越低撒得越稀 —— 用密度补上「没有 alpha 通道」这件事
            int count = Math.max(1, Math.round(POINTS_PER_BATCH * (0.35F + brightness)));

            for (int i = 0; i < count; i++) {
                Vec3 point = pointOnSphere(random).scale(radius).add(center);
                int color = dim(RED_VARIANTS[random.nextInt(RED_VARIANTS.length)], brightness);

                // 主体：亮红尘埃（能调颜色，所以承担「各类亮红」那部分）
                level.sendParticles(new DustParticleOptions(color, 1.0F),
                        point.x, point.y, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);

                // 点缀：偶尔掺一颗暴击星，让球壳边缘有细碎的闪光
                if (random.nextFloat() < 0.25F) {
                    level.sendParticles(ParticleTypes.CRIT,
                            point.x, point.y, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
        }
    }

    /** 这一团在 {@code age} 刻的半径：先线性涨到 7，再线性涨到 10 */
    private static double radiusAt(Wave wave, int age) {
        if (age <= wave.expandToNearTicks()) {
            double t = wave.expandToNearTicks() <= 0 ? 1.0D : (double) age / wave.expandToNearTicks();
            return RADIUS_NEAR * Mth.clamp(t, 0.0D, 1.0D);
        }
        int farSpan = wave.totalTicks() - wave.expandToNearTicks();
        double t = farSpan <= 0 ? 1.0D : (double) (age - wave.expandToNearTicks()) / farSpan;
        return Mth.lerp(Mth.clamp(t, 0.0D, 1.0D), RADIUS_NEAR, RADIUS_FAR);
    }

    /** 这一团在 {@code age} 刻的亮度系数：起始 0.4 → 结束 0.04 */
    private static float brightnessAt(Wave wave, int age) {
        float t = wave.totalTicks() <= 0 ? 1.0F : (float) age / wave.totalTicks();
        return Mth.lerp(Mth.clamp(t, 0.0F, 1.0F), START_BRIGHTNESS, END_BRIGHTNESS);
    }

    /** 按亮度系数把颜色压暗（近似「降低不透明度」） */
    private static int dim(int rgb, float brightness) {
        int r = (int) (((rgb >> 16) & 0xFF) * brightness);
        int g = (int) (((rgb >> 8) & 0xFF) * brightness);
        int b = (int) ((rgb & 0xFF) * brightness);
        return (Mth.clamp(r, 0, 255) << 16) | (Mth.clamp(g, 0, 255) << 8) | Mth.clamp(b, 0, 255);
    }

    /**
     * 球面上均匀取一点（斐波那契球）。
     *
     * <p>用随机角度取点会在两极堆积、赤道稀疏，看起来像个坏掉的甜甜圈；
     * 斐波那契那种「按黄金角旋转 + 等分高度」的取法能得到视觉上均匀的球壳。</p>
     *
     * <p>这里不用索引循环，改成「先随机一个高度、再随机一个方位角」——
     * 虽然严格来说赤道附近会略密一点点，但每帧都在换位置，
     * 看过去就是均匀的一层壳，代价是省掉了一个额外的索引状态。</p>
     */
    private static Vec3 pointOnSphere(RandomSource random) {
        double y = random.nextDouble() * 2.0D - 1.0D;          // -1..1
        double ring = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
        double theta = random.nextDouble() * Math.PI * 2.0D;
        return new Vec3(Math.cos(theta) * ring, y, Math.sin(theta) * ring);
    }
}
