package com.mcmodworkspace.moreballs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 【钻石球】的分层掉落（作者 2026-10-10 指定的规格）。
 *
 * <h2>为什么不能直接用 {@code BallDrop} 列表</h2>
 * <p>标准的 {@code BallDrop} 是「每条<b>独立</b>掷一次概率」，几条互不影响 ——
 * 铁球的四层、圆石球的一层都是这么写的。</p>
 *
 * <p>而作者给的钻石球掉落是<b>递进且互斥</b>的：</p>
 * <ol>
 *   <li>60% 掉 4–6 个钻石</li>
 *   <li><b>另外</b> 20% 掉 1–3 个钻石</li>
 *   <li><b>若前面一颗钻石都没掉</b>，才有 80% 掉 8–16 个煤炭</li>
 * </ol>
 *
 * <p>第三条依赖前两条的<b>结果</b>（「没有掉落任何钻石」），
 * 这不是独立掷能表达的 —— 所以单独写在这里，由 {@code burst()} 特判调用。</p>
 *
 * <p>另外这条规则是<b>整颗球</b>级别的：组合球抽到钻石球当来源时，
 * 也整体走这一套（而不是把三种结果再各抽一次）。</p>
 */
public final class DiamondBallDrops {

    private DiamondBallDrops() {
    }

    /** 第一段：掉 4–6 个钻石的概率 */
    private static final float DIAMOND_MAIN_CHANCE = 0.60F;

    /** 第一段的数量区间 */
    private static final int DIAMOND_MAIN_MIN = 4;
    private static final int DIAMOND_MAIN_MAX = 6;

    /** 第二段：再掉 1–3 个钻石的概率 */
    private static final float DIAMOND_EXTRA_CHANCE = 0.20F;

    /** 第二段的数量区间 */
    private static final int DIAMOND_EXTRA_MIN = 1;
    private static final int DIAMOND_EXTRA_MAX = 3;

    /** 第三段：前面一颗钻石都没掉时，掉煤炭的概率 */
    private static final float COAL_CHANCE = 0.80F;

    /** 第三段的数量区间 */
    private static final int COAL_MIN = 8;
    private static final int COAL_MAX = 16;

    /**
     * 掷一次钻石球的掉落。
     *
     * @param level  服务端世界
     * @param ball   要掉落的那颗球（用它的位置与 {@code spawnAtLocation}）
     * @param random 随机源 —— 用球自己的，保证多人各算各的
     */
    public static void roll(ServerLevel level, Entity ball, RandomSource random) {
        boolean gotDiamond = false;

        // 第一段
        if (random.nextFloat() < DIAMOND_MAIN_CHANCE) {
            spawn(level, ball, random, DIAMOND_MAIN_MIN, DIAMOND_MAIN_MAX);
            gotDiamond = true;
        }

        // 第二段（与第一段独立，两段都中就是两份钻石）
        if (random.nextFloat() < DIAMOND_EXTRA_CHANCE) {
            spawn(level, ball, random, DIAMOND_EXTRA_MIN, DIAMOND_EXTRA_MAX);
            gotDiamond = true;
        }

        // 第三段：只有前面一颗都没掉才轮到煤炭
        if (!gotDiamond && random.nextFloat() < COAL_CHANCE) {
            int count = COAL_MIN + random.nextInt(COAL_MAX - COAL_MIN + 1);
            ball.spawnAtLocation(level, new ItemStack(Items.COAL, count), 0.1F);
        }
    }

    /** 掉一段钻石 */
    private static void spawn(ServerLevel level, Entity ball, RandomSource random, int min, int max) {
        int count = min + random.nextInt(max - min + 1);
        ball.spawnAtLocation(level, new ItemStack(Items.DIAMOND, count), 0.1F);
    }
}
