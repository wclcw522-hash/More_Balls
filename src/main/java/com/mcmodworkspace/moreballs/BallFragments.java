package com.mcmodworkspace.moreballs;

import net.minecraft.core.registries.BuiltInRegistries;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mcmodworkspace.moreballs.BallBehavior.BallProfile;
import com.mcmodworkspace.moreballs.BallBehavior.BallSound;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 半球 / 四分之一球 / 组合球的词条运算（作者 2026-10-07 指定的整套规则）。
 *
 * <h2>规格</h2>
 * <ul>
 *   <li>切石机把完整球切成 <b>2 个半球</b>或 <b>4 个四分之一球</b></li>
 *   <li>半球与四分之一球<b>不能投掷、不能当弩弹药</b> —— 它们不进 {@code #more_balls:balls} 标签</li>
 *   <li><b>数值词条</b>（坚固、感应、融化、熔融、磁吸、点金）在半成品上按 1/2、1/4 缩放，
 *       <b>暂时不取整</b> —— 所以这里一律用 {@code double} 运算，不能落回 int</li>
 *   <li><b>无数值特性</b>（变形、磁性、金光闪闪）由半球<b>直接继承</b>；
 *       <b>四分之一球把它们藏起来</b>，到四合一才重新判断</li>
 *   <li><b>二合一</b>：数值与特质<b>相叠加</b>，数值<b>向下取整</b></li>
 *   <li><b>四合一</b>：数值取四份之和后向下取整；同种特质要<b>存在 ≥2 份才显示并生效</b>，
 *       否则视为失去该特质</li>
 *   <li><b>三种雪球不参与</b>（作者指定）</li>
 * </ul>
 *
 * <h2>为什么半成品的词条不进 BallProfile</h2>
 * <p>{@link BallProfile} 的数值字段都是 {@code int}，而作者要求求半成品「暂时不用取整」。
 * 所以半成品另走一条路：物品上只存「来源球 id + 分数」，词条每次由
 * {@link #scaled} 现算 —— 既保住了小数，也不污染球本身的数据结构。</p>
 */
public final class BallFragments {

    private BallFragments() {
    }

    /**
     * 参与切割的球 —— 「所有类型的雪球不参与」（作者指定）。
     *
     * <p>这里的顺序同时也是：组合球取名的顺序、组合球 id 里各段的先后、
     * 以及「同一种组合只做一件物品」时用来归一化的排序键。
     * 换句话说 <b>铁半球 + 金半球</b> 与 <b>金半球 + 铁半球</b>
     * 都会归一化成同一种「铁-金球」，不会各做一份。</p>
     *
     * <p>⚠️ <b>不能做成静态 final 字段</b>：{@code DeferredItem#get()} 在注册完成前是 null，
     * 而类加载可能早于注册 —— 那样会直接静态初始化失败。所以这里惰性求值，
     * 第一次真正用到时（那时注册早就完成了）才去取。</p>
     */
    private static List<Item> sourcesCache;

    public static List<Item> sources() {
        if (sourcesCache == null) {
            sourcesCache = List.of(
                    ModItems.WOODEN_BALL.get(),
                    ModItems.COBBLESTONE_BALL.get(),
                    ModItems.IRON_BALL.get(),
                    ModItems.GOLD_BALL.get(),
                    ModItems.HOLLOW_IRON_BALL.get(),
                    // ⚠️ 2.6.0 追加的三颗**必须排在后面**：
                    //    前 5 个下标是既有组合球组件里写死的历史值，一动老球就整体错位。
                    ModItems.AMETHYST_BALL.get(),
                    ModItems.COPPER_BALL.get(),
                    ModItems.HOLLOW_COPPER_BALL.get());
        }
        return sourcesCache;
    }

    /**
     * {@code #more_balls:balls} 标签里的<b>全部</b>物品。
     *
     * <p>遍历注册表筛标签，而不是读 json —— 这样拿到的是「当前实际生效的集合」，
     * 含其它模组通过数据包加进来的东西。给启动自检用（见 {@link BallIntegrationCheck}）。</p>
     *
     * <p>只遍历一次、只在启动时调，代价可以忽略。</p>
     */
    public static List<Item> ballsInTag() {
        List<Item> found = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            // 用 Holder.is(TagKey) 而不是 new ItemStack(item).is(...)：
            // 这个方法可能在 DataComponents 绑定之前被调用（启动自检），
            // 那时构造 ItemStack 会抛 "Components not bound yet"。
            if (item.builtInRegistryHolder().is(ModTags.Items.BALLS)) {
                found.add(item);
            }
        }
        return found;
    }
    /**
     * 这颗球在 {@link #sources()} 里的下标；不是碎片来源球则返回 -1。
     *
     * <p>给切球配方用 —— 从输入球反查「该写进 fragment_source 的值」。
     * 用 {@code List.indexOf} 而不是自己维护一张 Map：sources() 本身就有序，
     * 多一张表反而要两边同步。</p>
     */
    public static int indexOfBall(Item ball) {
        return sources().indexOf(ball);
    }
    /** 组合球<b>显示名</b>里用的短名，与 {@link #sources()} 同序（铁-金球里的「铁」「金」） */
    public static final List<String> SHORT_NAME =
            List.of("木", "圆石", "铁", "金", "空心铁", "紫水晶", "铜", "空心铜");

    /** 组合球<b>内部 id</b> 里用的短名，与 {@link #sources()} 同序（{@code iron_gold_ball} 里的 iron/gold） */
    public static final List<String> SHORT_ID =
            List.of("wooden", "cobblestone", "iron", "gold", "hollow_iron",
                    "amethyst", "copper", "hollow_copper");

    /** 这颗球在 {@link #sources()} 里的下标；不属于其中就返回 -1（雪球会走到这里） */
    public static int indexOf(Item ball) {
        return sources().indexOf(ball);
    }

    // ===== 词条快照 =====

    /**
     * 一个「碎片」的词条快照 —— 全部用 {@code double}。
     *
     * <p>{@code 0} 一律表示<b>没有这条词条</b>（对应 {@code BallProfile} 里那些
     * {@code NOT_XXX} 哨兵值，这里统一归零好做加法）。</p>
     *
     * @param toughness 坚固（可反弹次数）
     * @param sense     感应等级
     * @param melt      融化阈值
     * @param molten    熔融阈值
     * @param magnet    磁吸半径
     * @param transmute 点金概率
     * @param morph     【变形】特质（无数值，看有没有）
     * @param magnetic  【磁性】特质（无数值，看有没有）
     * @param glint     【金光闪闪】特质（无数值，金球专属）
     */
    /**
     * 一颗球的全部词条。
     *
     * <p>数值项走「相加」，特质项走「出现过 / 四合一要 ≥2 份」。</p>
     *
     * <p>⚠️ <b>特质项必须与 {@link BallProfile} 上的词条一一对应</b>。
     * 早先这里只有 {@code morph / magnetic / glint} 三个，于是
     * {@code wisdom / kindness / conduction / thunder / shock} 五个词条
     * 在合成时被整个丢掉 —— 表现就是「紫水晶球 + 别的球合出来的组合球
     * 没有【智慧】【善良】」（作者 2026-10-08 报的）。加新词条时这里和
     * {@link #of(BallProfile, boolean)}、{@code comboProfile} 三处要一起改。</p>
     */
    /**
     * 坚固值里的「永不碎裂」在**加法阶段**的替身。
     *
     * <p>{@code TOUGH_FOREVER} 是 {@code -1}，直接参与算术会算出 {@code -2}
     * （一个比「永不碎裂」还强的怪值）。合成时先抬成这个足够大的数，
     * 落回 profile 时再按阈值还原成哨兵。</p>
     */
    public static final double TOUGH_FOREVER_MARK = 100000.0D;

    public record Fragment(double toughness, double sense, double melt, double molten,
                           double magnet, double transmute,
                           boolean morph, boolean magnetic, boolean glint,
                           boolean wisdom, boolean kindness, boolean conduction,
                           boolean thunder, boolean shock) {

        public static final Fragment EMPTY =
                new Fragment(0, 0, 0, 0, 0, 0, false, false, false, false, false, false, false, false);

        /** 按分数缩放一份词条（半球 0.5、四分之一 0.25） */
        public Fragment scaled(double factor) {
            return new Fragment(toughness * factor, sense * factor, melt * factor,
                    molten * factor, magnet * factor, transmute * factor,
                    morph, magnetic, glint,
                    wisdom, kindness, conduction, thunder, shock);
        }

        /** 叠加 —— 数值相加，特质取「出现过」 */
        public Fragment plus(Fragment other) {
            return new Fragment(toughness + other.toughness, sense + other.sense,
                    melt + other.melt, molten + other.molten,
                    magnet + other.magnet, transmute + other.transmute,
                    morph || other.morph, magnetic || other.magnetic, glint || other.glint,
                    wisdom || other.wisdom, kindness || other.kindness,
                    conduction || other.conduction, thunder || other.thunder,
                    shock || other.shock);
        }
    }

    /**
     * 取某颗球的词条并<b>按分数缩放</b>（半成品用）。
     *
     * <p>特质（morph / magnetic / glint）<b>不缩放也不清除</b> ——
     * 四分之一球「把特质藏起来」是<b>展示层</b>的事（见 {@code BallTooltip}），
     * 数据上仍然带着，否则四合一就没法判断「同种特质有几份」了。</p>
     */
    public static Fragment scaled(Item ball, double factor) {
        return of(ball).scaled(factor);
    }

    /**
     * 把某颗球的 profile 读成 {@link Fragment}。
     *
     * <p>【金光闪闪】不在 {@link BallProfile} 上（它是金球专属、由物品本身决定的行为），
     * 所以这里单独看物品。</p>
     */
    public static Fragment of(Item ball) {
        return of(BallBehavior.profileFor(new ItemStack(ball)), ball == ModItems.GOLD_BALL.get());
    }

    /**
     * 把一份 {@link BallProfile} 读成 {@link Fragment}。
     *
     * <p>{@code NOT_XXX} 那些哨兵值统一归零 —— 这样「有没有这条词条」在加法里
     * 自然就表现成 0，不用到处判哨兵。</p>
     */
    public static Fragment of(BallProfile p, boolean glint) {
        return new Fragment(
                // ⚠️ 坚固是**哨兵值**语义：NOT_TOUGH=0、TOUGH_FOREVER=-1。
                //    直接参与加法会算出 -2（比「永不碎」还强）这种怪值。
                //    这里把 TOUGH_FOREVER 抬成一个远大于任何限次的数，
                //    落回 profile 时再按阈值还原成哨兵（见 toughFromFragment）。
                p.toughness() == BallBehavior.NOT_TOUGH ? 0
                        : (p.toughness() == BallBehavior.TOUGH_FOREVER
                                ? TOUGH_FOREVER_MARK : p.toughness()),
                p.sense() == BallBehavior.NOT_SENSE ? 0 : p.sense(),
                p.hasMelt() ? p.meltThreshold() : 0,
                p.hasMolten() ? p.moltenThreshold() : 0,
                p.hasMagnet() ? p.magnetRadius() : 0,
                p.transmuteChance(),
                p.morphBlock() != null,
                p.magnetic(),
                glint,
                p.wisdom(),
                p.kindness(),
                p.conduction(),
                p.hasThunder(),
                p.shockDamage());
    }

    // ===== 合成球 =====

    /**
     * 把若干份碎片合成为最终词条（作者指定的两套规则）。
     *
     * <p><b>二合一</b>：数值与特质<b>相叠加</b>，数值<b>向下取整</b> —— 走
     * {@link #combinePair}。</p>
     * <p><b>四合一</b>：数值取四份之和后向下取整；同种特质要<b>存在 ≥2 份才显示并生效</b> ——
     * 走 {@link #combineQuad}。</p>
     */
    public static Fragment combinePair(Fragment a, Fragment b) {
        Fragment sum = a.plus(b);
        // 二合一：特质直接继承（任一份有就算有），数值向下取整
        return floor(sum);
    }

    /**
     * 四合一。
     *
     * <p>与二合一的两处不同：</p>
     * <ul>
     *   <li>特质要<b>出现 ≥2 份</b>才保留 —— 只出现一次视为失去（作者指定）</li>
     *   <li>数值仍是四份之和，再向下取整</li>
     * </ul>
     */
    public static Fragment combineQuad(Fragment a, Fragment b, Fragment c, Fragment d) {
        Fragment sum = a.plus(b).plus(c).plus(d);
        int morphs = (a.morph() ? 1 : 0) + (b.morph() ? 1 : 0) + (c.morph() ? 1 : 0) + (d.morph() ? 1 : 0);
        int magnets = (a.magnetic() ? 1 : 0) + (b.magnetic() ? 1 : 0) + (c.magnetic() ? 1 : 0) + (d.magnetic() ? 1 : 0);
        int glints = (a.glint() ? 1 : 0) + (b.glint() ? 1 : 0) + (c.glint() ? 1 : 0) + (d.glint() ? 1 : 0);
        int wisdoms = (a.wisdom() ? 1 : 0) + (b.wisdom() ? 1 : 0) + (c.wisdom() ? 1 : 0) + (d.wisdom() ? 1 : 0);
        int kindnesses = (a.kindness() ? 1 : 0) + (b.kindness() ? 1 : 0) + (c.kindness() ? 1 : 0) + (d.kindness() ? 1 : 0);
        int conductions = (a.conduction() ? 1 : 0) + (b.conduction() ? 1 : 0) + (c.conduction() ? 1 : 0) + (d.conduction() ? 1 : 0);
        int thunders = (a.thunder() ? 1 : 0) + (b.thunder() ? 1 : 0) + (c.thunder() ? 1 : 0) + (d.thunder() ? 1 : 0);
        int shocks = (a.shock() ? 1 : 0) + (b.shock() ? 1 : 0) + (c.shock() ? 1 : 0) + (d.shock() ? 1 : 0);
        return new Fragment(
                Math.floor(sum.toughness()), Math.floor(sum.sense()),
                Math.floor(sum.melt()), Math.floor(sum.molten()),
                Math.floor(sum.magnet()), sum.transmute(),   // 同上：概率保持小数
                morphs >= QUAD_TRAIT_THRESHOLD,
                magnets >= QUAD_TRAIT_THRESHOLD,
                glints >= QUAD_TRAIT_THRESHOLD,
                wisdoms >= QUAD_TRAIT_THRESHOLD,
                kindnesses >= QUAD_TRAIT_THRESHOLD,
                conductions >= QUAD_TRAIT_THRESHOLD,
                thunders >= QUAD_TRAIT_THRESHOLD,
                shocks >= QUAD_TRAIT_THRESHOLD);
    }

    /** 四合一时，「同种特质」要出现这么多份才算留住（作者指定：两个及以上） */
    public static final int QUAD_TRAIT_THRESHOLD = 2;

    // ===== 组合球的动态 profile =====

    /**
     * 由「<b>按摆放位置顺序</b>排好的来源下标」算出一颗组合球的完整行为参数。
     *
     * <p>这是「一件物品装下所有组合」的关键：组合球本身只有 {@code combo_ball} 一个 id，
     * 它该有什么词条、多疼、多重，全部由这个组件现算 —— 算完注册进
     * {@link BallBehavior}，球的所有既有机制（投掷、弩、碎裂、热量…）就都能直接用。</p>
     *
     * <h2>哪些词条走合成规则、哪些走平均</h2>
     * <ul>
     *   <li><b>六个数值词条 + 三个特质</b>：按作者定的规则合成
     *       （二合一叠加取整；四合一数值取和取整、特质要 ≥2 份）</li>
     *   <li><b>伤害 / 重量 / 弹射 / 蓄力</b>：取<b>算术平均</b> ——
     *       这些是「球本身的物理属性」，不是词条，取平均才不会炸掉数值</li>
     *   <li><b>音效 / 变形方块</b>：取<b>第一份有值的</b>（它们是引用型，平均没有意义）</li>
     *   <li><b>稀有度设 0</b>：组合球不该混进「怪物携带 / 掠夺者弹药」的随机抽取里</li>
     *   <li><b>掉落清空</b>：组合球碎了就碎了，不继承各来源的掉落表</li>
     * </ul>
     *
     * <p>重量决定出手速度，所以这里改重量之后，{@code withWeight} 会自己把初速度重算掉 ——
     * 不需要也不应该手动去算 velocity。</p>
     *
     * @param indexes 来源球在 {@link #sources()} 里的下标，<b>按位置顺序</b>（不要排序！）
     */
    public static BallProfile comboProfile(List<Integer> indexes) {
        List<Item> balls = sources();
        List<BallProfile> parts = new ArrayList<>(indexes.size());
        List<Fragment> frags = new ArrayList<>(indexes.size());
        for (int i : indexes) {
            Item ball = balls.get(i);
            parts.add(BallBehavior.profileFor(new ItemStack(ball)));
            frags.add(of(ball));
        }
        if (parts.isEmpty()) {
            return BallBehavior.profileFor(new ItemStack(balls.get(0)));
        }

        // ⚠️ 必须先校验份数。组件值是可以被 /give、数据包或旧存档写坏的，
        //    而这里原来直接 `size() <= 2 ? get(0),get(1) : get(0..3)` ——
        //    1 份会 get(1) 越界、3 份会 get(3) 越界、5 份以上静默只取前四。
        //    这个方法在 profileFor 的热路径上（tooltip / 渲染 / 每刻 AI），
        //    抛异常就是渲染线程崩。
        if (frags.size() != 2 && frags.size() != 4) {
            MoreBalls.LOGGER.warn("[ball] 组合球来源份数异常（{} 份，只支持 2 或 4）-> 退化为第一份：{}",
                    frags.size(), indexes);
            return BallBehavior.profileFor(new ItemStack(balls.get(indexes.get(0))));
        }
        Fragment merged = frags.size() == 2
                ? combinePair(frags.get(0), frags.get(1))
                : combineQuad(frags.get(0), frags.get(1), frags.get(2), frags.get(3));

        double n = parts.size();
        double damage = 0.0D;
        double weight = 0.0D;
        double bounce = 0.0D;
        double charge = 0.0D;
        double inaccuracy = 0.0D;
        // ⚠️ 初值必须是 0，不能是 1.0 —— entityScale 是「相对基准球的倍率」，
        //    用 1.0 当种子之后，任何**小于 1** 的来源都会被它盖掉（Math.max 取不到）。
        float scale = 0.0F;
        Block morph = null;
        BallSound sound = null;
        for (BallProfile p : parts) {
            damage += p.damage();
            weight += p.weight();
            bounce += p.bounce();
            charge += p.chargeLevels();
            inaccuracy += p.inaccuracy();
            scale = Math.max(scale, p.entityScale());
            if (morph == null) {
                morph = p.morphBlock();
            }
            if (sound == null) {
                sound = p.sound();
            }
        }

        // 拿第一份当底子再逐项覆盖：这样所有「没列出来的字段」都能保持合法默认值，
        // 不用去猜每个字段的构造函数位置
        BallProfile base = parts.get(0);
        BallProfile out = base
                .withDamage((float) (damage / n))
                .withWeight((int) Math.round(weight / n))
                .withBounce((int) Math.round(bounce / n))
                .withChargeLevels((int) Math.round(charge / n))
                .withInaccuracy((float) (inaccuracy / n))
                .withToughness((int) merged.toughness())
                .withSense((int) merged.sense())
                .withEntityScale(scale)
                .withRarity(0)
                .withDrops(List.of());
        if (sound != null) {
            out = out.withSound(sound);
        }
        // 【变形】只有在这颗球的材质里**真的有** ≥2 份带变形特质时才生效
        // —— 四合一的份数判定由 combineQuad 算好，就写在 merged.morph() 里。
        //
        // ⚠️ 这里原来只判 `morph != null`（第一份有值就写），把 combineQuad 的
        //    「≥2 份」判定整个绕过去了 —— 表现就是「两个带同样特性的四分之一球
        //    合出来的组合球没有那个特性」（作者 2026-10-08 报的）。
        if (merged.morph() && morph != null) {
            out = out.withMorph(morph);
        }
        // （BallProfile 只提供 withXxx，没有 withoutXxx，所以清空就是写回 NOT_XXX）
        out = out.withMelt(merged.melt() > 0 ? (int) merged.melt() : BallBehavior.NOT_MELT);
        out = out.withMolten(merged.molten() > 0 ? (int) merged.molten() : BallBehavior.NOT_MOLTEN);
        out = out.withMagnet(merged.magnet() > 0 ? merged.magnet() : BallBehavior.NOT_MAGNET);
        out = out.withTransmute((float) merged.transmute()).withMagnetic(merged.magnetic());
        // ===== 其余特质同样按「四合一 ≥2 份」落到输出 =====
        //
        // ⚠️ 这一段以前**完全不存在** —— wisdom / kindness / conduction / thunder / shock
        //    五个词条虽然在各来源球上是对的，但组合时没有任何一行把它们写回 profile，
        //    于是「紫水晶球 + 别的球」合出来的组合球没有【智慧】【善良】，
        //    弩上的【智慧】不锁玩家、【善良】也不弹玩家（作者 2026-10-08 报的）。
        out = out
                .withWisdom(merged.wisdom())
                .withKindness(merged.kindness())
                .withConduction(merged.conduction())
                // 引雷是阈值型：够份数就沿用底子的阈值，不够就写回「没有」哨兵
                // ⚠️ 阈值必须取「**带引雷那一份**」的，不能取 base（第一格那颗球）。
                //    空心铜球是唯一带引雷的球，如果它不在第一格，base.thunderThreshold()
                //    就是 0 → 写回 NOT_THUNDER → 引雷整个消失，且**只跟摆放方向有关**。
                .withThunder(merged.thunder() ? thunderThresholdFrom(parts) : BallBehavior.NOT_THUNDER)
                .withShockDamage(merged.shock())
                // ⚠️ 【金光闪闪】**不参与合成**（作者 2026-10-09 指定）：
                //    这个特性只在**完整的一颗金球**上生效，一旦被切成半球/四分之一球
                //    就失效，也不该通过组合球重新获得。所以这里显式写回 false，
                //    而不是沿用 merged.glint()。
                .withGlint(false);
        return out;
    }

    /** 数值向下取整（特质原样透传） */
    /**
     * 从各来源里找「带引雷那一份」的阈值。
     *
     * <p>【引雷】是阈值型词条 —— 份数够就保留，但阈值本身要沿用真正带它的那颗球的，
     * 不能取第一格（那颗球很可能根本没有引雷）。</p>
     */
    private static int thunderThresholdFrom(List<BallProfile> parts) {
        int best = BallBehavior.NOT_THUNDER;
        for (BallProfile p : parts) {
            if (p.hasThunder()) {
                best = Math.max(best, p.thunderThreshold());
            }
        }
        return best;
    }
    private static Fragment floor(Fragment f) {
        return new Fragment(
                Math.floor(f.toughness()), Math.floor(f.sense()),
                Math.floor(f.melt()), Math.floor(f.molten()),
                Math.floor(f.magnet()), f.transmute(),   // 点金是概率，不能 floor（0.1 -> 0 会整个抹掉）
                f.morph(), f.magnetic(), f.glint(),
                f.wisdom(), f.kindness(), f.conduction(), f.thunder(), f.shock());
    }

    // ===== 组件 ↔ 下标列表 =====

    /** 把 {@code "2,3"} 这样的组件值解析成下标列表；格式不对就返回空表 */
    public static List<Integer> parseIndexes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                int value = Integer.parseInt(part.trim());
                if (value >= 0 && value < sources().size()) {
                    out.add(value);
                }
            } catch (NumberFormatException ignored) {
                // 单个坏值就跳过，不让整颗球废掉
            }
        }
        return out;
    }

    /** 把下标列表写成组件值 */
    public static String formatIndexes(List<Integer> indexes) {
        StringBuilder sb = new StringBuilder();
        for (int i : indexes) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(i);
        }
        return sb.toString();
    }

    /**
     * 造一颗带来源组件的组合球 —— 合成配方的产物就是它。
     *
     * <p>{@code indexes} 的顺序即摆放位置顺序，会原样写进
     * {@link ModComponents#COMBO_SOURCES}，随后决定这颗球的<b>名字</b>与<b>词条</b>。</p>
     *
     * <p><b>同时</b>把每一块的来源写进四个 {@code combo_slot_N} 组件（2.6.0 加）——
     * 那是<b>渲染层</b>用的：物品模型靠它按象限分别选图，再把四块叠起来。
     * 拆成四个 int 是因为 {@code minecraft:select} 只能按某个组件的整值匹配，
     * 没法「取字符串的第 n 段」。两个层面由这里一次性写入，所以永远一致。</p>
     */
    public static ItemStack makeComboBall(List<Integer> indexes) {
        ItemStack stack = new ItemStack(ModItems.COMBO_BALL.get());
        stack.set(ModComponents.COMBO_SOURCES.get(), formatIndexes(indexes));
        writeComboSlots(stack, indexes);
        return stack;
    }

    /**
     * 把每一块的来源写进 {@code combo_slot_1..4}（左上 / 右上 / 左下 / 右下）。
     *
     * <p>不足四块时（二合一只有两块）多出来的象限会留空 —— 写 -1，
     * 模型那边用 {@code fallback} 落到全透明，于是「上下两块」的球看起来就是上下两块。</p>
     */
    public static void writeComboSlots(ItemStack stack, List<Integer> indexes) {
        for (int i = 0; i < ModComponents.COMBO_SLOTS.size(); i++) {
            // 二合一只写前两块：上面那块占上半的两个象限，下面那块占下半的
            int value = slotValue(indexes, i);
            if (value < 0) {
                stack.remove(ModComponents.COMBO_SLOTS.get(i).get());
            } else {
                stack.set(ModComponents.COMBO_SLOTS.get(i).get(), value);
            }
        }

        // 诊断：组合球贴图全靠这四个组件选图（模型是 composite 叠四层 select）。
        // 万一贴图又不显示，看这一行就知道是组件没写进去、还是模型那边没匹配上。
        MoreBalls.LOGGER.debug("[ball] 组合球 slots：indexes={} → slot1={} slot2={} slot3={} slot4={}",
                indexes,
                stack.get(ModComponents.COMBO_SLOT_1.get()),
                stack.get(ModComponents.COMBO_SLOT_2.get()),
                stack.get(ModComponents.COMBO_SLOT_3.get()),
                stack.get(ModComponents.COMBO_SLOT_4.get()));
    }

    /**
     * <b>旧数据的自动迁移</b> —— 给只有 {@code combo_sources} 的老组合球补齐分块组件。
     *
     * <p>2.6.0 改成分层渲染后，贴图由四个 {@code combo_slot_1..4} 分别选象限；
     * 而存档里已有的组合球只有旧的 {@code combo_sources}，那四个是空的 ——
     * 于是四层全落到全透明 fallback，表现就是「贴图没了」。</p>
     *
     * <p>分块组件是纯粹的渲染派生物，随时能从 {@code combo_sources} 重算，
     * 所以这里就「用到就补」：{@code ComboBallItem.inventoryTick} 每次都会调它，
     * 任何老球一进玩家背包就被修好。</p>
     *
     * <p><b>先做一次廉价的齐不齐检查</b>，齐了立刻返回 —— 所以常态下零开销，
     * 可以放心每 tick 调用。</p>
     */
    public static void ensureComboSlots(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ComboBallItem)) {
            return;
        }
        // 齐了就别动 —— 这是热路径上的快速返回
        if (stack.has(ModComponents.COMBO_SLOT_1.get())
                && stack.has(ModComponents.COMBO_SLOT_4.get())) {
            return;
        }
        List<Integer> indexes = parseIndexes(
                stack.getOrDefault(ModComponents.COMBO_SOURCES.get(), ""));
        if (indexes.isEmpty()) {
            return;   // 连来源都没有（裸模板），没什么可补的
        }
        writeComboSlots(stack, indexes);
    }

    /**
     * 第 {@code quadrant} 个象限该用哪个来源。
     * <ul>
     *   <li><b>四合一</b>（4 个来源）：一一对应，左上/右上/左下/右下</li>
     *   <li><b>二合一</b>（2 个来源）：第一个来源占<b>上半</b>（左上+右上），
     *       第二个占<b>下半</b>（左下+右下）—— 这样贴图看起来才是「上半个球 + 下半个球」，
     *       与名字顺序（上,下）一致</li>
     * </ul>
     *
     * @return 来源下标；该象限没有内容时返回 -1
     */
    public static int slotValue(List<Integer> indexes, int quadrant) {
        if (indexes.isEmpty()) {
            return -1;
        }
        if (indexes.size() >= 4) {
            return quadrant < indexes.size() ? indexes.get(quadrant) : -1;
        }
        // 二合一：象限 0,1 归第一块；2,3 归第二块
        int half = quadrant / 2;
        return half < indexes.size() ? indexes.get(half) : -1;
    }

    /**
     * 造一个带来源组件的半成品（半球 / 四分之一球）。
     *
     * <p>切石机配方的产物就是它；创造模式里摆出来的「示例实例」也用它 ——
     * 因为这两件物品都是「模板 + 组件」，不带组件的裸物品既没来源也没法显示词条。</p>
     */
    public static ItemStack makeFragment(Item fragment, int sourceIndex) {
        ItemStack stack = new ItemStack(fragment);
        stack.set(ModComponents.FRAGMENT_SOURCE.get(), sourceIndex);
        return stack;
    }

    /**
     * 组合球 profile 的缓存 —— key 是组件里那个下标串。
     *
     * <p>{@code profileFor} 会被极频繁地调用（每刻、每次渲染、每次 tooltip），
     * 而组合球的参数要靠合成规则现算，不缓存的话代价太高。
     * 组合的种类是有限的（同一个下标串永远是同一颗球），所以缓存上限自然有界。</p>
     */
    /**
     * 组合球来源串 → 合成后的 profile。
     *
     * <p>键是来源下标串（如 {@code "0,2,3,5"}），可能的取值是 8 颗球的二合一与四合一，
     * 数量有限但**没有硬上界**。加个 LRU 限住 —— 免得被数据包刷出一堆垃圾键。</p>
     */
    private static final int COMBO_CACHE_CAPACITY = 512;

    private static final Map<String, BallProfile> COMBO_CACHE =
            new java.util.LinkedHashMap<>(64, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BallProfile> eldest) {
                    return size() > COMBO_CACHE_CAPACITY;
                }
            };

    /**
     * 从物品上的 {@link ModComponents#COMBO_SOURCES} 取出组合球的行为参数。
     *
     * <p>组件缺失或解析不出东西时落到 {@code DEFAULT} —— 用内置值而不是再取一次
     * {@code profileFor}，那会变成无限递归。</p>
     */
    public static BallProfile comboProfileFrom(ItemStack stack) {
        String key = stack.getOrDefault(ModComponents.COMBO_SOURCES.get(), "");
        List<Integer> indexes = parseIndexes(key);
        if (indexes.isEmpty()) {
            return BallBehavior.profileFor(new ItemStack(sources().get(0)));
        }
        return COMBO_CACHE.computeIfAbsent(formatIndexes(indexes), k -> comboProfile(parseIndexes(k)));
    }
}
