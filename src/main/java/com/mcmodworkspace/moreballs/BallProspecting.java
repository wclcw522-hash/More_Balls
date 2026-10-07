package com.mcmodworkspace.moreballs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「探寻」—— 「雪球？（铁粒）」的特殊能力。
 *
 * <h2>规则（作者指定）</h2>
 * <ol>
 *   <li>球扔出后探测<b>半径 7 格</b>范围</li>
 *   <li>范围内每格<b>金属类矿物</b>每刻提供 1 点热量</li>
 *   <li>球自身热量累计到 <b>100</b> 时立刻破碎并 100% 掉 2 个铁粒</li>
 *   <li>范围内的<b>玩家</b>也会被加热：每穿一件金属护甲 +1/刻，手持金属装备再 +1/刻；
 *       停止被检测后每刻消退 2 点；<b>超过 50 点点燃玩家</b></li>
 *   <li>范围内有<b>铁磁性装备</b>的玩家时，球的飞行路径向「最近且穿得最多」的那位偏移</li>
 * </ol>
 *
 * <h2>对接其他模组：零配置</h2>
 *
 * <h3>金属类矿物 —— 自动识别</h3>
 * <p>判据是「<b>可以用熔炉熔炼得到锭</b>」，所以这里不维护矿物清单，而是<b>运行时反查熔炼配方</b>：
 * 把那格方块做成物品、丢给 {@link RecipeType#SMELTING} 问一次，看产物是不是「锭」。
 * <b>任何模组只要把矿石接进熔炼配方就会被自动认出来。</b></p>
 *
 * <h3>金属装备 —— 按物品注册名判断</h3>
 * <p>护甲没法用熔炼配方反查（铁胸甲熔出来是铁粒、不是锭），所以走注册名关键字：
 * 只要 ID 里带 {@code iron} / {@code chain} / {@code gold} / {@code copper} 等金属词
 * 且带 {@code EQUIPPABLE} 组件，就算金属装备。</p>
 *
 * <h3>铁磁性 —— 关键字 + 可选标签</h3>
 * <p>主判据是注册名关键字（{@link #FERROMAGNETIC_KEYWORDS}），不需要任何配置；
 * {@link #FERROMAGNETIC_TAG} 是留给「命名不合惯例又想被识别」的模组的额外口子，不填也照跑。</p>
 */
public final class BallProspecting {

    private BallProspecting() {
    }

    /** 检测半径（格）：超出这个范围连扫都不扫 */
    public static final int RADIUS = 15;

    /** 偏转半径（格）：进入这个范围才开始真正拽动轨迹（作者指定） */
    public static final int PULL_RADIUS = 7;

    /** 「磁吸」的作用半径（格）：空心铁球飞行时，这个范围内的金属会被拽向它 */
    public static final double MAGNET_RADIUS = 2.5D;

    /**
     * 「磁吸」施加的速度比例 —— 作者指定：给目标一个<b>等于自身速度一半</b>的瞬间速度。
     */
    public static final double MAGNET_SPEED_RATIO = 0.5D;

    /**
     * 融化时必掉的铁粒数量。
     *
     * <p>（球自身的热量上限已经下沉到「融化」词条
     * {@link BallBehavior#DEFAULT_MELT_THRESHOLD}，按球各自配置。）</p>
     */
    public static final int BURST_NUGGETS = 2;

    /** 玩家/生物热量上限：超过就会着火（作者指定 100） */
    public static final float PLAYER_HEAT_IGNITE = 100.0F;

    /** 玩家/生物脱离检测后的热量消退（1 点 / 5 刻 = 0.2 点/刻） */
    public static final float PLAYER_HEAT_DECAY = 0.2F;

    /** 泡在水里时的消退倍率（作者指定：10 倍速） */
    public static final float WATER_DECAY_MULTIPLIER = 10.0F;

    /** 扫描间隔（刻）：半径 7 的立方体有 3375 格，每刻全扫太贵，每 4 刻扫一次、结果复用 */
    public static final int SCAN_INTERVAL = 4;

    /** 磁吸偏航的拉力（每刻加到水平速度上的上限） */
    public static final double MAGNET_PULL = 0.035D;

    /**
     * 铁磁性矿物的<b>数据包标签</b> —— 留给「命名不合惯例又想被识别」的模组的额外口子。
     *
     * <p><b>不填也照样跑</b>：主判据是下面的注册名关键字。</p>
     */
    public static final TagKey<Block> FERROMAGNETIC_TAG = TagKey.create(
            Registries.BLOCK,
            Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "ferromagnetic_ores"));

    /** 金属矿物的补充标签：给「是金属但没接熔炼配方、命名也不合惯例」的方块显式声明 */
    public static final TagKey<Block> EXTRA_METAL_TAG = TagKey.create(
            Registries.BLOCK,
            Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "metal_ores"));

    /**
     * 铁磁性关键字（全小写、按子串匹配方块注册名）—— <b>零配置直接生效的主力</b>。
     *
     * <p>收录依据是现实中确实有铁磁性的金属：铁、钴、镍、钆等及其常见矿石形态。
     * 铜、铝、锡、铅、银、金这些<b>不是</b>铁磁性的，故意不收 —— 否则磁吸就没有意义了。</p>
     */
    private static final String[] FERROMAGNETIC_KEYWORDS = {
            "iron", "cobalt", "nickel", "zinc", "magnetite", "lodestone", "steel",
            "neodymium", "ferrite", "hematite", "goethite", "pyrrhotite",
            "chromium", "platinum", "gadolinium", "manganese"
    };

    /**
     * 金属装备关键字（全小写、按子串匹配物品注册名）。
     *
     * <p>判断玩家/生物身上的护甲、工具、武器算不算「金属」——
     * 范围比铁磁性宽，因为带在身上就该被加热，不管它吸不吸磁铁。</p>
     */
    private static final String[] METAL_EQUIPMENT_KEYWORDS = {
            "iron", "chain", "gold", "golden", "copper", "steel", "bronze",
            "netherite", "silver", "tin", "lead", "aluminum", "aluminium", "titanium", "zinc"
    };

    /**
     * 铁磁性装备关键字 —— 决定「能不能把球吸过去」。
     *
     * <p>比加热用的关键字窄：只有现实里真能被磁铁吸住的金属及其常见工具形态。</p>
     */
    private static final String[] FERROMAGNETIC_EQUIPMENT_KEYWORDS = {
            "iron", "cobalt", "nickel", "steel", "magnetite", "lodestone",
            "neodymium", "ferrite", "gadolinium", "platinum", "chromium"
    };

    /** 方块 → 是否算「金属矿」的缓存（判定要走配方表，不能每刻都查） */
    private static final Map<BlockState, Boolean> METAL_CACHE = new ConcurrentHashMap<>();

    /** 一次矿物扫描的结果 —— 只统计与收集位置，磁吸目标另由 bestMagnetTarget 竞争得出 */
    public record ScanResult(int metalBlocks, List<BlockPos> orePositions) {

        public int heatPerTick() {
            return metalBlocks;
        }
    }

    /**
     * 紧邻六面有几个金属矿 —— 决定这格矿被加热的快慢。
     *
     * <p>作者指定的公式是「每刻积累 {@code 1 + 自身紧邻周围六格矿物数量}」，
     * 所以矿脉越密的地方被烤得越快。</p>
     */
    public static int countNeighborOres(ServerLevel level, BlockPos pos) {
        int count = 0;
        for (Direction dir : Direction.values()) {
            if (isMetalOre(level, level.getBlockState(pos.relative(dir)))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 扫描球周围一圈，收集<b>生效范围内</b>的金属矿物。
     *
     * <h2>为什么扫 15 格、却只收 7 格的</h2>
     * 作者定的规则是「<b>15 格开始计算，7 格开始生效</b>」—— 扫描半径给足 15 格是为了
     * 提前「看到」远处的目标，但真正参与结算（叠加热量、烤矿物、吸附转向）的必须收在
     * 7 格以内。
     *
     * <p>这不只是设定问题：热量是「生效范围内每格矿物每刻 +1」，如果拿 15 格来算，
     * 范围体积是 7 格的 <b>近 10 倍</b>，球自身热量几刻就会顶到阈值当场炸开 ——
     * 表现就是「扔出去什么都不发生」，因为球在转向之前就已经碎了。</p>
     */
    public static ScanResult scan(ServerLevel level, Vec3 center) {
        BlockPos base = BlockPos.containing(center);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        double pullSqr = (double) PULL_RADIUS * PULL_RADIUS;

        int metal = 0;
        List<BlockPos> ores = new ArrayList<>();

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    cursor.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir() || !isMetalOre(level, state)) {
                        continue;
                    }
                    // 只有进入「生效范围」的矿物才算数 —— 15 格扫描只是提前看到
                    if (cursor.distToCenterSqr(center) > pullSqr) {
                        continue;
                    }
                    metal++;
                    ores.add(cursor.immutable());
                }
            }
        }
        return new ScanResult(metal, ores);
    }

    /**
     * 这格方块算不算「金属类矿物」。
     *
     * <p>判定顺序：<b>显式标签</b> → <b>熔炼配方反查</b>（产物是「锭」或「金属块」）。
     * 结果按方块状态缓存。</p>
     *
     * <h2>为什么产物只看「锭」不够</h2>
     * <p>原版<b>只有粗矿有熔炼配方</b>（{@code raw_iron → iron_ingot}），
     * <b>粗矿块根本没有</b> —— {@code raw_iron_block.json} 是 crafting_shapeless
     * （拆成 9 个粗矿），不是 smelting。所以只认「产物是锭」的话，
     * <b>粗矿块会整个漏判</b>：既不在熔炼表里，名字也不合 {@code _ore} 惯例。
     * （作者 2026-10-08 报的正是这个。）</p>
     *
     * <p>于是放宽成「产物是<b>锭</b>或<b>金属储存块</b>」——
     * 后者同时兼容那些「粗矿块直接烧成金属块」的数据包与其它模组。</p>
     */
    public static boolean isMetalOre(ServerLevel level, BlockState state) {
        Boolean cached = METAL_CACHE.get(state);
        if (cached != null) {
            return cached;
        }

        boolean result = state.is(EXTRA_METAL_TAG);
        if (!result) {
            ItemStack asItem = new ItemStack(state.getBlock().asItem());
            if (!asItem.isEmpty() && !asItem.is(Items.AIR)) {
                SingleRecipeInput input = new SingleRecipeInput(asItem);
                result = level.recipeAccess()
                        .getRecipeFor(RecipeType.SMELTING, input, level)
                        .map(holder -> isMetalProduct(holder.value().assemble(input)))
                        .orElse(false);
            }
        }

        METAL_CACHE.put(state, result);
        return result;
    }

    /**
     * 「金属储存块」的判据关键字。
     *
     * <p>刻意带上 {@code _block} 后缀 —— 只写 {@code iron} 会把铁栏杆、铁门、
     * 铁轨这些全都算成「金属矿物」，那个概念就糊了。</p>
     */
    private static final List<String> METAL_BLOCK_KEYWORDS =
            List.of("iron_block", "copper_block", "gold_block", "netherite_block");

    /** 熔炼产物算不算「金属」—— 锭，或者金属储存块 */
    private static boolean isMetalProduct(ItemStack product) {
        if (product.isEmpty()) {
            return false;
        }
        String id = product.getItem().getDescriptionId();
        // 锭：任何模组的 <金属>_ingot 都算
        if (id.contains("ingot")) {
            return true;
        }
        // 金属储存块：粗矿块被烤熟时掉的就是这个
        for (String metal : METAL_BLOCK_KEYWORDS) {
            if (id.contains(metal)) {
                return true;
            }
        }
        return false;
    }

    /** 这格方块是不是铁磁性矿物（标签优先，注册名关键字兜底） */
    public static boolean isFerromagnetic(BlockState state) {
        if (state.is(FERROMAGNETIC_TAG)) {
            return true;
        }
        String path = state.getBlock().builtInRegistryHolder().key().identifier().getPath();
        return containsAny(path, FERROMAGNETIC_KEYWORDS);
    }

    /**
     * 这格方块的熔炼产物 —— 也就是「对应的矿锭」。
     *
     * <p>矿物被烤炸时要掉的是<b>锭</b>而不是原版的粗矿，所以直接拿熔炼配方的产物来用；
     * 不是金属矿（或没有熔炼配方）则返回空。</p>
     */
    public static ItemStack smeltProduct(ServerLevel level, BlockState state) {
        ItemStack asItem = new ItemStack(state.getBlock().asItem());
        if (asItem.isEmpty() || asItem.is(Items.AIR)) {
            return ItemStack.EMPTY;
        }
        SingleRecipeInput input = new SingleRecipeInput(asItem);
        return level.recipeAccess()
                .getRecipeFor(RecipeType.SMELTING, input, level)
                .map(holder -> holder.value().assemble(input))
                .orElse(ItemStack.EMPTY);
    }

    // ===== 玩家侧 =====

    /**
     * 玩家身上有几件「金属装备」—— 四个护甲位 + 主副手，每件算 1。
     *
     * <p>作者给出的例子：全套铁甲 = 4 点/刻，再拿把铁剑 = 5 点/刻。</p>
     */
    public static int countMetalEquipment(LivingEntity entity) {
        int count = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (isMetalEquipment(stack)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 玩家/生物身上有几件「铁磁性装备」—— 磁吸目标只认这一类（铁、锁链、下界合金等）。
     *
     * <p><b>只看物品注册名，不要求能穿戴</b>：护甲、工具、武器一视同仁 ——
     * 铁镐铁铲铁剑都会让球偏过去（作者指定「只要是金属工具就要识别」）。</p>
     */
    public static int countFerromagneticEquipment(LivingEntity entity) {
        int count = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (isFerromagneticEquipment(entity.getItemBySlot(slot))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 这件东西算不算「金属」。
     *
     * <p>判据就是物品注册名里带没带金属词 —— <b>不检查 EQUIPPABLE</b>。
     * 早先我加过那道检查（本意是「只认穿戴的东西」），结果把铁镐、铁铲、铁剑
     * 这些没有该组件的工具全排除掉了，连带着磁吸也一起失效。</p>
     */
    public static boolean isMetalEquipment(ItemStack stack) {
        return !stack.isEmpty() && containsAny(itemPath(stack), METAL_EQUIPMENT_KEYWORDS);
    }

    /** 这件东西算不算「铁磁性」—— 决定它能不能把球吸过去 */
    public static boolean isFerromagneticEquipment(ItemStack stack) {
        return !stack.isEmpty() && containsAny(itemPath(stack), FERROMAGNETIC_EQUIPMENT_KEYWORDS);
    }

    /**
     * 选出磁吸目标：范围内<b>穿/持铁磁装备最多</b>的生物；数量相同时取更近的那个。
     *
     * <p>玩家与生物一视同仁 —— 只拿一把铁剑的僵尸同样会把球吸过去。</p>
     *
     * @return 目标生物，没有则 null
     */
    public static @Nullable LivingEntity findMagneticTarget(List<? extends LivingEntity> candidates, Vec3 center) {
        LivingEntity best = null;
        int bestCount = 0;
        double bestDist = Double.MAX_VALUE;

        for (LivingEntity candidate : candidates) {
            int count = countFerromagneticEquipment(candidate);
            if (count <= 0) {
                continue;
            }
            double dist = candidate.position().distanceToSqr(center);
            if (count > bestCount || (count == bestCount && dist < bestDist)) {
                best = candidate;
                bestCount = count;
                bestDist = dist;
            }
        }
        return best;
    }

    // ===== 工具 =====

    private static String itemPath(ItemStack stack) {
        return stack.getItem().builtInRegistryHolder().key().identifier().getPath();
    }

    private static boolean containsAny(String path, String[] keywords) {
        for (String keyword : keywords) {
            if (path.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 磁吸转向：把速度方向<b>直接指向目标</b>（只改方向、不改大小）。
     *
     * <h2>为什么不用螺旋收敛</h2>
     * 早先做过「以目标为圆心、半径每刻缩短」的螺旋逼近，那套是照着<b>大片矿脉</b>设计的：
     * 它每刻只沿切向挪一小步、半径逐刻收缩，碰上<b>孤零零一格矿物</b>就会出问题 ——
     * 球速 1.5 格/刻，等螺旋收拢时球早就掠过去了，表现就是「单格矿物完全不追」。
     * 现在改成每刻直接把速度方向掰向目标：一旦进入生效范围，无论目标多大都会立刻咬住，
     * 掠过头了也会掉头回来。
     *
     * <p>水平方向改向，竖直分量原样保留（球该落还是落）。</p>
     */
    public static Vec3 homeToward(Vec3 velocity, Vec3 from, Vec3 target) {
        double speed = velocity.horizontalDistance();
        if (speed < 1.0E-4D) {
            return velocity;
        }
        Vec3 toTarget = new Vec3(target.x - from.x, 0.0D, target.z - from.z);
        if (toTarget.lengthSqr() < 1.0E-6D) {
            return velocity; // 已经对准了，别在原地抽搐
        }
        Vec3 direction = toTarget.normalize();
        return new Vec3(direction.x * speed, velocity.y, direction.z * speed);
    }

    /** 磁吸目标：位置 + 优先级（热量积累速度） */
    public record MagnetTarget(Vec3 position, double priority) {
    }

    /**
     * 选出磁吸目标 —— <b>矿物与生物放在同一条赛道上，按「热量积累速度」比大小</b>。
     *
     * <p>作者给出的判据：一块六面全被铁矿包围的矿石，积累速度是 {@code 1 + 6 = 7}，
     * 优先级<b>高于</b>穿整套金属甲再拿金属工具的生物（5 点）。</p>
     *
     * <p>另外只有进入 {@link #PULL_RADIUS} 的候选才参与竞争 ——
     * 检测半径 10 是为了「提前看到」，偏转半径 7 才是「开始动手」。</p>
     */
    public static @Nullable MagnetTarget bestMagnetTarget(ServerLevel level, Vec3 center,
                                                          List<BlockPos> ores,
                                                          List<? extends LivingEntity> entities) {
        MagnetTarget best = null;
        double pullSqr = (double) PULL_RADIUS * PULL_RADIUS;

        for (BlockPos ore : ores) {
            if (ore.distToCenterSqr(center) > pullSqr) {
                continue;
            }
            if (!isFerromagnetic(level.getBlockState(ore))) {
                continue;
            }
            double priority = 1.0D + countNeighborOres(level, ore);
            if (best == null || priority > best.priority()) {
                best = new MagnetTarget(Vec3.atCenterOf(ore), priority);
            }
        }

        for (LivingEntity entity : entities) {
            int count = countFerromagneticEquipment(entity);
            if (count <= 0 || entity.position().distanceToSqr(center) > pullSqr) {
                continue;
            }
            if (best == null || count > best.priority()) {
                best = new MagnetTarget(entity.position(), count);
            }
        }
        return best;
    }

    /** 清空缓存（数据包重载后调用，避免残留过期结论） */
    public static void clearCache() {
        METAL_CACHE.clear();
    }
}
