package com.mcmodworkspace.moreballs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.particles.ParticleTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * 「雪球_金粒」的【点金】—— 点石成金……呃，成矿。
 *
 * <h2>规则（作者指定）</h2>
 * <ul>
 *   <li>命中方块时产生<b>绿色催熟粒子</b></li>
 *   <li>命中面那一片 <b>3×3×1</b> 范围内的<b>石头 / 圆石类方块</b>，每块独立掷
 *       {@link BallBehavior#TRANSMUTE_CHANCE}（10%）</li>
 *   <li>掷中就变成<b>对应种类的矿物</b>：深板岩只能是深板岩矿、地狱岩只能是地狱矿……
 *       石头 / 圆石则出主世界矿</li>
 *   <li>所有矿物<b>权重相同</b>（均匀随机），<b>模组矿物一并参与</b></li>
 *   <li>基岩、强化深板岩这种<b>设计上不可破坏</b>的方块不会被点</li>
 * </ul>
 *
 * <h2>矿物池怎么来的</h2>
 * 不硬编码任何具体矿物 ID —— 开局扫一遍方块注册表，把<b>所有名字以 {@code _ore} 结尾</b>
 * 的方块按前缀分进四个池子（深板岩系 / 地狱系 / 末地系 / 其余视作主世界系）。
 * 这样任何模组加的矿物都自动参与，不需要我们维护名单；原版那几个不以 {@code _ore}
 * 结尾的（下界残骸）单独补进去。
 */
public final class BallTransmute {

    private BallTransmute() {
    }

    /** 石头 / 圆石类的名字关键字（标签之外的兜底，用来接住模组的石头类方块） */
    private static final String[] STONE_KEYWORDS = {
            "stone", "cobble", "rock", "slate", "tuff", "granite", "diorite", "andesite",
            "basalt", "blackstone", "netherrack", "marble", "limestone", "gneiss", "shale",
            "chalk", "scoria", "peridotite", "moon_stone", "moonstone", "bedrock"
    };

    /** 四个矿物池；null 表示还没扫描过 */
    private static List<Block> overworldOres;
    private static List<Block> deepslateOres;
    private static List<Block> netherOres;
    private static List<Block> endOres;

    /**
     * 点金主流程 —— 由 {@code BallProjectile} 命中方块时调用。
     */
    public static void transmute(ServerLevel level, BlockHitResult hit) {
        ensurePools();

        BlockPos center = hit.getBlockPos();
        Direction face = hit.getDirection();

        // 绿色催熟粒子：撒在命中面的外侧，正好贴住被点的那一片
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                center.getX() + 0.5D + face.getStepX() * 0.6D,
                center.getY() + 0.5D + face.getStepY() * 0.6D,
                center.getZ() + 0.5D + face.getStepZ() * 0.6D,
                14, 0.45D, 0.45D, 0.45D, 0.0D);

        // 在「垂直于命中面」的那个平面上，以命中方块为中心取 3×3×1
        Direction.Axis axis = face.getAxis();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                BlockPos pos = switch (axis) {
                    case X -> center.offset(0, a, b);
                    case Y -> center.offset(a, 0, b);
                    case Z -> center.offset(a, b, 0);
                };

                BlockState state = level.getBlockState(pos);
                if (!isStoneLike(state)) {
                    continue;
                }
                if (level.getRandom().nextFloat() >= BallBehavior.TRANSMUTE_CHANCE) {
                    continue;
                }

                Block ore = pickOre(level, state);
                if (ore != null) {
                    level.setBlockAndUpdate(pos, ore.defaultBlockState());
                }
            }
        }
    }

    /**
     * 这块方块能不能被点 —— 石头 / 圆石类，且不是设计上不可破坏的那种。
     */
    private static boolean isStoneLike(BlockState state) {
        // 基岩、强化深板岩这类破坏不了的东西不参与
        if (state.getBlock().defaultDestroyTime() < 0.0F) {
            return false;
        }
        // 标签优先（原版与主流模组都会挂）。
        // 注：26.x 没有 base_stone_end 这个常量，末地石靠下面的名字关键字兜住。
        if (state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)) {
            return true;
        }
        // 名字兜底，接住没挂标签的模组石头
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        for (String keyword : STONE_KEYWORDS) {
            if (path.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 按原方块的种类挑一个矿物池，再均匀随机取一块 */
    private static Block pickOre(ServerLevel level, BlockState from) {
        List<Block> pool = poolFor(from);
        if (pool.isEmpty()) {
            pool = overworldOres; // 这个种类没矿可出就退回主世界池
        }
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get(level.getRandom().nextInt(pool.size()));
    }

    /** 原方块属于哪一类岩石 → 出哪一类矿 */
    private static List<Block> poolFor(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (path.contains("deepslate")) {
            return deepslateOres;
        }
        if (path.contains("nether") || path.contains("blackstone") || path.contains("basalt")) {
            return netherOres;
        }
        if (path.contains("end")) {
            return endOres;
        }
        return overworldOres;
    }

    /**
     * 扫描方块注册表，把矿物按前缀分进四个池子。
     *
     * <p>只扫一次（首次点金时），之后复用。</p>
     */
    private static synchronized void ensurePools() {
        if (overworldOres != null) {
            return;
        }
        List<Block> overworld = new ArrayList<>();
        List<Block> deepslate = new ArrayList<>();
        List<Block> nether = new ArrayList<>();
        List<Block> end = new ArrayList<>();

        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) {
                continue;
            }
            String path = id.getPath();
            if (!path.endsWith("_ore")) {
                continue;
            }
            if (block.defaultDestroyTime() < 0.0F) {
                continue; // 不可破坏的不算
            }

            if (path.startsWith("deepslate_")) {
                deepslate.add(block);
            } else if (path.startsWith("nether_") || path.endsWith("_nether_ore")) {
                nether.add(block);
            } else if (path.startsWith("end_") || path.contains("_end_")) {
                end.add(block);
            } else {
                overworld.add(block);
            }
        }

        // 少数矿物不以 _ore 结尾，手动补进去（下界残骸就是这种）
        addIfAbsent(nether, Blocks.ANCIENT_DEBRIS);

        overworldOres = List.copyOf(overworld);
        deepslateOres = List.copyOf(deepslate);
        netherOres = List.copyOf(nether);
        endOres = List.copyOf(end);
    }

    private static void addIfAbsent(List<Block> pool, Block block) {
        if (!pool.contains(block)) {
            pool.add(block);
        }
    }

    /** 数据包重载后清掉池子，下次点金时重新扫描（模组矿物可能变了） */
    public static synchronized void clearPools() {
        overworldOres = null;
        deepslateOres = null;
        netherOres = null;
        endOres = null;
    }
}
