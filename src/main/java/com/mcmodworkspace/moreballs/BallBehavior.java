package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.BallEnderpearl;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「球」的行为参数 —— 所有球类的默认行为，以及<b>按物品定制的唯一入口</b>。
 *
 * <h2>概念索引</h2>
 * <ul>
 *   <li><b>重量</b>（{@link BallWeight}）：决定出手后下坠多快</li>
 *   <li><b>坚固</b>（{@link BallProfile#toughness()}）：决定碰撞后碎裂还是反弹</li>
 *   <li><b>弹射</b>（{@link BallProfile#bounce()}）：反弹后保留多少速度</li>
 *   <li><b>蓄力</b>（{@link BallProfile#chargeLevels()}）：右键长按能蓄几级</li>
 *   <li><b>稀有度</b>（{@link BallProfile#rarity()}）：权重，越大越常见</li>
 *   <li><b>音效</b>（{@link BallSound}）：材质 + 音量 + 可选的指定音效</li>
 *   <li><b>破碎掉落</b>（{@link BallProfile#drops()}）：<b>多层</b>掉落，每层独立掷概率</li>
 * </ul>
 *
 * <h2>坚固规则（作者明确）</h2>
 * <ul>
 *   <li>标「<b>坚固</b>」→ {@link #TOUGH_FOREVER}：永不碎裂</li>
 *   <li>标「<b>坚固x</b>」→ x：可反弹 x 次，每次消耗 1 点耐久</li>
 *   <li><b>不说坚固</b> → {@link #NOT_TOUGH}：碰上就碎，<b>且不存在弹性</b>
 *       （不坚固的球压根不会反弹，弹射值对它没有意义）</li>
 * </ul>
 *
 * <h2>命名约定</h2>
 * <p>「雪球？」是一个<b>系列</b> —— 显示名一律是「雪球？」（问号是名字的一部分），
 * 靠括号里的内容区分，例如「雪球？（碎石）」「雪球？（铁粒）」。
 * 编辑与交流时带上括号，避免混淆。</p>
 */
public final class BallBehavior {

    private BallBehavior() {
    }

    /** 不坚固：碰到就碎，也没有弹性（默认） */
    public static final int NOT_TOUGH = 0;

    /** 坚固（无后缀）：永不碎裂，静止后变成可拾取实体 */
    public static final int TOUGH_FOREVER = -1;

    /**
     * 没有「感应」（默认）—— 不具备叠加热量的性质。
     *
     * <p>和「坚固」一样是<b>底层词条</b>：不写就是没有，写了不带数字就是<br>
     * {@link #DEFAULT_SENSE_LEVEL} 级。</p>
     */
    public static final int NOT_SENSE = 0;

    /** 「感应」不带数字时的等级 */
    public static final int DEFAULT_SENSE_LEVEL = 1;

    /**
     * 铜系三颗球用的感应等级（作者指定【感应2】）。
     *
     * <p>比默认高一级：铜的导热性好，积热更快。</p>
     */
    public static final int COPPER_SENSE_LEVEL = 2;

    /** 【引雷】阈值 —— 空心铜球热量攒到这么多就放一次雷（作者指定 <b>120</b>；300 → 200 → 120，2026-10-09 两次下调） */
    public static final int THUNDER_THRESHOLD = 120;

    /**
     * 没有【引雷】—— 热量攒到多少都不会释放雷电（默认）。
     *
     * <p>和「融化」一样属于热量系词条，但触发后是<b>打出去</b>而不是自己消失。</p>
     */
    public static final int NOT_THUNDER = 0;

    /** 没有「融化」—— 热量再高也不会因此消失 */
    public static final int NOT_MELT = 0;

    /**
     * 没有【熔融】—— 热量攒得再高，击中目标也不会把它烫伤（默认）。
     *
     * <p>与「融化」是两回事：融化是球<b>自己</b>化掉，熔融是拿热量<b>去烫别人</b>。</p>
     */
    public static final int NOT_MOLTEN = 0;

    /** 雪球类的默认融化阈值：热量攒到 200 就化掉（作者指定） */
    public static final int DEFAULT_MELT_THRESHOLD = 200;

    /** 没有「磁吸」—— 飞行时不会拽动身边的金属 */
    public static final double NOT_MAGNET = 0.0D;

    /**
     * 金球的实体渲染尺寸倍率。
     *
     * <p>球的基准碰撞箱是 0.25 格。金球做过「一格的内切球」，作者后来要求
     * <b>建模大小改成那个的一半</b>，所以是 0.25 × 2 = 0.5 格直径。</p>
     *
     * <p>注意这里只管<b>实体建模大小</b>，跟物品图标的贴图像素无关
     * —— 两者是分开的两件事。</p>
     */
    public static final float GOLD_BALL_SCALE = 2.0F;

    /** 没有【点金】 */
    public static final float NOT_TRANSMUTE = 0.0F;

    /**
     * 空心铁球的【熔融】阈值（作者指定 <b>120</b>）。
     *
     * <p>数值演变：<b>1000</b>（最初）→ <b>500</b>（2026-10-07）→ <b>300</b>
     * → <b>120</b>（2026-10-09 最终）。</p>
     *
     * <p>1000 攒不到的原因：球自身热量是「每刻 + 生效范围 7 格内的金属矿格数」，
     * 而球飞不了那么久 —— 10 格矿要 5 秒、3 格矿要 16.7 秒，后者球早落地静止了。
     * 一路下调到现在这个值，让它在自己扔出去的那一段（掷出者身上的甲也算热源）就能攒满。</p>
     */
    public static final int MOLTEN_THRESHOLD = 120;

    /**
     * 【破坏王 x】的预算 —— <b>10 次</b>（作者 2026-10-10 指定：【破坏王10】）。
     *
     * <p>钻石球每砸掉一个方块消耗 1 次，用完就再也砸不动。</p>
     */
    public static final int BREAKER_BUDGET = 10;

    /**
     * 【熔融】击中目标时挂上的灼伤时长（刻）—— 作者指定 <b>1 秒</b>。
     *
     * <p>注意这只是<b>基础</b>时长：真正生效的时长还要乘上目标身上的金属装备数，
     * 见 {@code MoltenBurnEffect}。</p>
     */
    public static final int MOLTEN_BURN_TICKS = 20;

    /** 「雪球_金粒」【点金】的单块转化概率（作者指定 10%） */
    public static final float TRANSMUTE_CHANCE = 0.10F;

    /**
     * 【初速度】的基准值 —— 雪球的初速度是 10（作者指定）。
     *
     * <p>重量越大初速度越低，换算规则见 {@link BallProfile#launchSpeed()}
     * （每比雪球基准重 1 点就减 1）。</p>
     */
    public static final float BASE_LAUNCH_SPEED = 10.0F;

    /**
     * 【初速度】数值 → 实际射速的换算系数。
     *
     * <p>原版雪球的实际初速是 1.5，而它的「初速度」数值是 10，
     * 所以 10 × 0.15 = 1.5 —— 换算之后**物理手感和以前一模一样**，
     * 变的只是这个数值怎么向玩家表达。</p>
     */
    public static final float LAUNCH_SPEED_SCALE = 0.15F;

    /** 坚固球的默认弹射等级（6 → 每次反弹后速度衰减到 60%） */
    public static final int DEFAULT_BOUNCE = 6;

    /** 默认可蓄力等级 */
    public static final int DEFAULT_CHARGE_LEVELS = 3;

    /** 默认稀有度权重 */
    public static final int DEFAULT_RARITY = 100;

    /** 默认音效：保持球类原本的弹跳手感 */
    public static final BallSound DEFAULT_SOUND = new BallSound(SoundType.SLIME_BLOCK, 1.0F, null, null);

    /**
     * 「雪球？」系列的投掷冷却：7 刻（作者指定）。
     *
     * <p>连续快投时，后一颗会撞上前一颗（球间弹性碰撞）并被弹回来打到自己，
     * 加一段冷却就能断开这个连锁。<b>以后新增的每一个「雪球？」变体都要带上它。</b></p>
     */
    public static final int SNOWBALL_QUESTION_COOLDOWN = 7;

    /**
     * 默认参数 —— 伤害 3、无冷却、【初速度】10（雪球基准）、散布 1.0、重量 4（雪球基准）、
     * 不坚固、弹射 6、可蓄力 3 级、稀有度 100、史莱姆音效、无掉落物、无感应。
     */
    public static final BallProfile DEFAULT = new BallProfile(
            3.0F, 0, BASE_LAUNCH_SPEED, 1.0F,
            BallWeight.BASELINE,
            NOT_TOUGH, DEFAULT_BOUNCE, DEFAULT_CHARGE_LEVELS, DEFAULT_RARITY,
            DEFAULT_SOUND, List.of(), NOT_SENSE, NOT_MELT, NOT_MOLTEN, NOT_MAGNET, null, 1.0F, NOT_TRANSMUTE, false,
            // 2.6.0 新增的五个词条：智慧 / 善良 / 导电 / 引雷阈值 / 电击
            0, false, false, NOT_THUNDER, false,
            false, 0, 0);   // glint（【金光闪闪】）、penetration（【穿透】）、flags（新词条位掩码）—— 默认都是关的

    /**
     * 坚固值的「相加」——<b>哨兵值不参与算术</b>。
     *
     * <p>{@link #TOUGH_FOREVER} 是 {@code -1}，直接相加会算出 {@code -2}
     * 这种「比永不碎裂还强」的怪值；而 {@code NOT_TOUGH} 是 {@code 0}，
     * 相加会把「不坚固」和「限次」混起来。规则：任一边是 TOUGH_FOREVER 就还是它，
     * 两边都是限次才相加。</p>
     */
    public static int addToughness(int a, int b) {
        if (a == TOUGH_FOREVER || b == TOUGH_FOREVER) {
            return TOUGH_FOREVER;
        }
        if (a == NOT_TOUGH || b == NOT_TOUGH) {
            return Math.max(a, b);
        }
        return a + b;
    }
    private static final Map<Item, BallProfile> OVERRIDES = new LinkedHashMap<>();
    private static final Set<Item> BYPASS = new LinkedHashSet<>();
    private static final Map<Item, VanillaProjectileFactory> VANILLA_PROJECTILES = new LinkedHashMap<>();

    /**
     * 全部按物品定制都在这里 —— 作者以后加条目也只改这一个方法。
     */
    public static void init() {
        // ===== 原版物品：右键行为一动不动，只「顺便能被弩装填」 =====
        bypass(Items.SNOWBALL);
        bypass(Items.ENDER_PEARL);

        useVanillaProjectile(Items.SNOWBALL,
                (level, shooter, ammo) -> new Snowball(level, shooter, ammo));
        // 末影珍珠作弹药时射专门的子类：带 5 点伤害、保留瞬移
        useVanillaProjectile(Items.ENDER_PEARL,
                (level, shooter, ammo) -> new BallEnderpearl(level, shooter, ammo));

        // 稀有度权重（越大越常见）—— 供怪物携带与掠夺者弹药抽取
        // 雪球类默认带「融化200」：热量攒到 200 就化掉
        override(Items.SNOWBALL, DEFAULT
                .withRarity(20)
                .withMelt(DEFAULT_MELT_THRESHOLD));
        override(Items.ENDER_PEARL, DEFAULT.withRarity(1));

        // ===== 本模组物品：木球 =====
        override(ModItems.WOODEN_BALL.get(), DEFAULT
                .withToughness(8)
                .withWeight(5)
                .withRarity(100)
                .withSoundType(SoundType.WOOD));

        // ===== 圆石球：坚固 2、重量 6、弹射 2、伤害 7、圆石音效 =====
        override(ModItems.COBBLESTONE_BALL.get(), DEFAULT
                .withDamage(7.0F)
                .withWeight(6)
                .withToughness(2)
                .withBounce(2)
                .withRarity(40)
                .withSoundType(SoundType.STONE)
                .withDrops(List.of(new BallDrop(ModItems.RUBBLE.get(), 1, 2, 0.60F))));

        // ===== 铁球：坚固 99、重量 7、弹射 3、伤害 11 =====
        // 铁砧落地音、音量 30%；碎裂分四层掉铁（每层独立掷概率）
        override(ModItems.IRON_BALL.get(), DEFAULT
                .withDamage(11.0F)
                .withWeight(7)
                .withToughness(99)
                .withBounce(3)
                .withRarity(20)
                .withSound(BallSound.of(SoundType.ANVIL, 0.3F))
                .withDrops(List.of(
                        new BallDrop(Items.IRON_NUGGET, 10, 30, 1.00F),
                        new BallDrop(Items.IRON_NUGGET, 10, 50, 0.80F),
                        new BallDrop(Items.IRON_INGOT, 1, 6, 0.60F),
                        new BallDrop(Items.IRON_BLOCK, 0, 1, 0.10F))));

        // ===== 空心铁球：坚固 20、重量 3、弹射 4、伤害 1、带【感应】【磁吸】【熔融120】 =====
        // 铁砧敲击音（三声那个的单次敲击），音量 30%
        // 稀有度 0 = 不参与随机抽取（作者指定：空心铁球不出现在怪物携带 / 掠夺者弹药里）
        override(ModItems.HOLLOW_IRON_BALL.get(), DEFAULT
                .withDamage(1.0F)
                .withWeight(3)
                .withToughness(20)
                .withBounce(4)
                .withRarity(0)
                .withSense(DEFAULT_SENSE_LEVEL)
                .withMagnet(BallProspecting.MAGNET_RADIUS)
                .withMolten(MOLTEN_THRESHOLD)
                .withSound(BallSound.of(SoundEvents.ANVIL_HIT, SoundEvents.ANVIL_HIT, 0.3F)));

        // ===== 「雪球？（碎石）」：不坚固（碰上就碎、无弹性）、重量 5、伤害 2、带【融化200】 =====
        override(ModItems.SNOWBALL_QUESTION.get(), DEFAULT
                .withDamage(2.0F)
                .withWeight(5)
                .withToughness(NOT_TOUGH)
                .withBounce(0)
                .withRarity(20)
                .withCooldown(SNOWBALL_QUESTION_COOLDOWN)
                .withSoundType(SoundType.SNOW)
                .withMelt(DEFAULT_MELT_THRESHOLD)
                .withDrops(List.of(new BallDrop(ModItems.RUBBLE.get(), 1, 1, 0.10F))));

        // ===== 「雪球？（铁粒）」：不坚固、重量 5、伤害 3、带【感应】 =====
        // 破碎 90% 掉 1–2 铁粒；【感应】让它叠加热量（见 BallProspecting），
        // 【探寻】让它自动追踪最近的铁磁性目标
        override(ModItems.SNOWBALL_IRON_NUGGET.get(), DEFAULT
                .withDamage(3.0F)
                .withWeight(5)
                .withToughness(NOT_TOUGH)
                .withBounce(0)
                .withRarity(20)
                .withCooldown(SNOWBALL_QUESTION_COOLDOWN)
                .withSoundType(SoundType.SNOW)
                .withSense(DEFAULT_SENSE_LEVEL)
                .withMelt(DEFAULT_MELT_THRESHOLD)
                .withMagnetic(true)
                .withDrops(List.of(new BallDrop(Items.IRON_NUGGET, 1, 2, 0.90F))));

        // ===== 金球：伤害 8、重量 8、坚固 8、弹射 2、蓄力只有 1 级、权重 20 =====
        // 经验球那声「叮」，音量压到 50%；模型是「一格内切球」大小，所以渲染尺寸调大
        // 特性：【变形】耐久耗尽变金块、【金光闪闪】猪灵全套（见 BallProjectile / PiglinLure）
        override(ModItems.GOLD_BALL.get(), DEFAULT
                .withDamage(8.0F)
                .withWeight(8)
                .withToughness(8)
                .withBounce(2)
                .withChargeLevels(1)
                .withRarity(20)
                .withMorph(Blocks.GOLD_BLOCK)
                .withEntityScale(GOLD_BALL_SCALE)
                // 【金光闪闪】现在是 profile 上的词条（不再只认物品），
                // 这样组合球按「四合一 ≥2 份」也能带上它
                .withGlint(true)
                .withSound(BallSound.of(SoundEvents.EXPERIENCE_ORB_PICKUP,
                        SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5F)));

        // ===== 「雪球_金粒」：不坚固、重量 5、伤害 2、蓄力 3、权重 20 =====
        // 特殊能力【点金】：命中方块时把命中面那一片石头类方块点成矿物，见 BallTransmute
        override(ModItems.SNOWBALL_GOLD_NUGGET.get(), DEFAULT
                .withDamage(2.0F)
                .withWeight(5)
                .withToughness(NOT_TOUGH)
                .withBounce(0)
                .withRarity(20)
                .withCooldown(SNOWBALL_QUESTION_COOLDOWN)
                .withSoundType(SoundType.SNOW)
                .withMelt(DEFAULT_MELT_THRESHOLD)
                .withTransmute(TRANSMUTE_CHANCE)
                .withDrops(List.of(new BallDrop(Items.GOLD_NUGGET, 1, 2, 0.60F))));

        // ===== 2.6.0 四颗新球（作者 2026-10-07 指定）=====

        // 紫水晶球：伤害 5、坚固 15、重量 5、蓄力 4、弹射 7、权重 5
        // 【智慧】发射后扫描 10 格内无遮挡可直达的敌对/仇恨中立生物并锁定
        // 【善良】碰到友好与中立生物改为反弹
        // 破碎 60% 掉 1–2 紫水晶
        override(ModItems.AMETHYST_BALL.get(), DEFAULT
                .withDamage(5.0F)
                .withToughness(10)
                .withWeight(5)
                .withChargeLevels(4)
                .withBounce(7)
                .withRarity(5)
                .withWisdom(5)
                .withKindness(true)
                // 碰撞音为紫水晶块破坏音（作者指定）
                .withSoundType(SoundType.AMETHYST)
                .withDrops(List.of(new BallDrop(Items.AMETHYST_SHARD, 1, 2, 0.60F))));

        // 铜球：伤害 10、坚固 20、重量 7、权重 20
        // 【感应2】【导电】处于实体状态时像避雷针一样让自然闪电优先劈自己，每次消耗 1 耐久
        // 破碎 75% 掉 2 铜锭、再 30% 掉 2–4 铜锭
        override(ModItems.COPPER_BALL.get(), DEFAULT
                .withDamage(10.0F)
                .withToughness(20)
                .withWeight(7)
                .withRarity(20)
                .withSense(COPPER_SENSE_LEVEL)
                .withConduction(true)
                // 声音与铁球相同（作者指定）：铁砧敲击音、音量 30%
                .withSound(BallSound.of(SoundType.ANVIL, 0.3F))
                .withDrops(List.of(
                        new BallDrop(Items.COPPER_INGOT, 2, 2, 0.75F),
                        new BallDrop(Items.COPPER_INGOT, 2, 4, 0.30F))));

        // 空心铜球：伤害 1、坚固 5、重量 2、弹射 3
        // 【感应2】【引雷120】热量满 120 释放 4–9 道闪电并清空热量
        // 破碎 50% 掉 1–2 铜粒
        override(ModItems.HOLLOW_COPPER_BALL.get(), DEFAULT
                .withDamage(1.0F)
                .withToughness(5)
                .withWeight(2)
                .withBounce(3)
                .withRarity(20)
                .withSense(COPPER_SENSE_LEVEL)
                .withThunder(THUNDER_THRESHOLD)
                // 声音与空心铁球相同（作者指定）：铁砧单次敲击、音量 30%
                .withSound(BallSound.of(SoundEvents.ANVIL_HIT, SoundEvents.ANVIL_HIT, 0.3F))
                .withDrops(List.of(new BallDrop(Items.COPPER_NUGGET, 1, 2, 0.50F))));

        // ===== 钻石球（作者 2026-10-10 指定）=====
        // 伤害 15、重量 7、坚固 250、弹性 0、蓄力 3
        // 【穿透 3】【破坏王】【透镜】—— 见 BallProjectile / BallLens
        // 掉落是**分层**的（60% 掉 4–6 钻石 / 20% 掉 1–3 钻石 / 都没中才 80% 掉煤炭），
        // 标准 BallDrop 只能独立掷，所以那三段逻辑写在 DiamondBallDrops 里。
        // 权重作者没给 —— 按「比紫水晶球（5）更稀有」取 3。
        override(ModItems.DIAMOND_BALL.get(), DEFAULT
                .withDamage(15.0F)
                .withWeight(7)
                .withToughness(250)
                .withBounce(0)
                .withChargeLevels(3)
                .withRarity(3)
                .withPenetration(3)
                .withFlag(BallProfile.FLAG_BREAKER | BallProfile.FLAG_LENS)
                .withSoundType(SoundType.STONE));

        // ===== 红石球（作者 2026-10-10 指定）=====
        // 伤害 2、坚固 5、重量 5、弹性 0、蓄力 6、权重 5
        // 【脉冲】命中时对周围金属目标施加【震荡】—— 效果由 RedstonePulse 负责
        // 破碎 50% 掉 5–7 红石粉；石头音效（作者指定）
        override(ModItems.REDSTONE_BALL.get(), DEFAULT
                .withDamage(2.0F)
                .withWeight(5)
                .withToughness(5)
                .withBounce(0)
                .withChargeLevels(6)
                .withRarity(5)
                .withFlag(BallProfile.FLAG_PULSE)
                .withSoundType(SoundType.STONE)
                .withDrops(List.of(new BallDrop(Items.REDSTONE, 5, 7, 0.50F))));

        // ===== 红石雪球（作者 2026-10-10 指定）=====
        // 伤害 0、重量 3、蓄力 6；不坚固、无弹射
        // 【照明】飞行时在正下方张开四棱锥判定区，区内生物被按阵营染色的发光 —— 见 BallIlluminate
        // 破碎 30% 掉 1–2 红石粉
        override(ModItems.REDSTONE_SNOWBALL.get(), DEFAULT
                .withDamage(0.0F)
                .withWeight(3)
                .withToughness(NOT_TOUGH)
                .withBounce(0)
                .withChargeLevels(6)
                .withRarity(20)
                .withCooldown(SNOWBALL_QUESTION_COOLDOWN)
                .withFlag(BallProfile.FLAG_ILLUMINATE)
                .withSoundType(SoundType.SNOW)
                // 雪球系的惯例：热量攒到 200 就化掉（碎石/铁粒/金粒/铜粒四颗都有）
                .withMelt(DEFAULT_MELT_THRESHOLD)
                .withDrops(List.of(new BallDrop(Items.REDSTONE, 1, 2, 0.30F))));

        // 雪球-铜粒：不坚固、重量 5、伤害 3
        // 【融化200】【感应2】【电击】直接伤害改为闪电类型
        // 破碎 25% 掉 1–2 铜粒
        override(ModItems.SNOWBALL_COPPER_NUGGET.get(), DEFAULT
                .withDamage(3.0F)
                .withWeight(5)
                .withToughness(NOT_TOUGH)
                .withBounce(0)
                .withRarity(20)
                .withCooldown(SNOWBALL_QUESTION_COOLDOWN)
                .withSoundType(SoundType.SNOW)
                .withMelt(DEFAULT_MELT_THRESHOLD)
                .withSense(COPPER_SENSE_LEVEL)
                .withShockDamage(true)
                .withDrops(List.of(new BallDrop(Items.COPPER_NUGGET, 1, 2, 0.25F))));
    }

    public static void override(Item item, BallProfile profile) {
        OVERRIDES.put(item, profile);
    }

    /**
     * 按稀有度权重随机抽一颗球 —— 供「怪物生成时携带」与「掠夺者改用球做弹药」使用。
     *
     * <p>权重就是 {@link BallProfile#rarity()}：越大越常见。
     * 权重 0 的球（例如空心铁球）会被完全跳过，永远抽不到。</p>
     *
     * @return 抽中的球物品栈；没有任何可抽的球时返回空栈
     */
    public static ItemStack randomBall(RandomSource random) {
        int total = 0;
        for (BallProfile profile : OVERRIDES.values()) {
            if (profile.rarity() > 0) {
                total += profile.rarity();
            }
        }
        if (total <= 0) {
            return ItemStack.EMPTY;
        }

        int roll = random.nextInt(total);
        for (Map.Entry<Item, BallProfile> entry : OVERRIDES.entrySet()) {
            int weight = entry.getValue().rarity();
            if (weight <= 0) {
                continue;
            }
            roll -= weight;
            if (roll < 0) {
                return new ItemStack(entry.getKey());
            }
        }
        return ItemStack.EMPTY;
    }

    public static void bypass(Item item) {
        BYPASS.add(item);
    }

    public static void useVanillaProjectile(Item item, VanillaProjectileFactory factory) {
        VANILLA_PROJECTILES.put(item, factory);
    }

    /**
     * 这颗球是否携带【金光闪闪】（猪灵全套行为）。
     *
     * <p>判据是 <b>profile 上的词条</b>，不是物品 id —— 金球自己在 override 里写了
     * {@code withGlint(true)}，而组合球按「四合一 ≥2 份」合成出来。
     * 以前一律写 {@code stack.is(GOLD_BALL)}，所以「两个金四分之一球合成的组合球」
     * 明明 tooltip 上显示【金光闪闪】，猪灵却不理它（作者 2026-10-08 报的）。</p>
     */
    public static boolean isGoldShiny(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.GOLD_BALL.get());
    }
    public static BallProfile profileFor(ItemStack stack) {
        // 组合球只有一个物品 id，具体参数存在组件里 —— 见 BallFragments
        if (stack.getItem() == ModItems.COMBO_BALL.get()) {
            return BallFragments.comboProfileFrom(stack);
        }
        return OVERRIDES.getOrDefault(stack.getItem(), DEFAULT);
    }

    public static boolean handles(ItemStack stack) {
        return BallAmmo.isBall(stack) && !BYPASS.contains(stack.getItem());
    }

    public static @Nullable VanillaProjectileFactory vanillaProjectileFor(ItemStack stack) {
        return VANILLA_PROJECTILES.get(stack.getItem());
    }

    /** 原版投射物工厂 —— 让某个物品发射时保持自己在原版里的投射物形态 */
    @FunctionalInterface
    public interface VanillaProjectileFactory {
        Projectile create(Level level, LivingEntity shooter, ItemStack ammo);
    }

    /**
     * 一层碎裂掉落。
     *
     * <p>一个球可以配<b>多层</b>，每层各自独立掷概率、互不影响 ——
     * 铁球就是「必掉铁粒 + 80% 再掉一份 + 60% 掉铁锭 + 10% 掉铁块」这样的四层。</p>
     *
     * @param item     掉什么
     * @param minCount 最少几个
     * @param maxCount 最多几个
     * @param chance   本层触发概率 0–1
     */
    public record BallDrop(Item item, int minCount, int maxCount, float chance) {
    }

    /**
     * 音效配置。
     *
     * @param soundType  材质音效组，默认取它的 {@code getHitSound()} / {@code getBreakSound()}
     * @param volume     音量倍率（1.0 为标准；铁球类用 0.3 压低）
     * @param breakSound 指定碎裂音，null 表示用材质自带的
     * @param hitSound   指定撞击音，null 表示用材质自带的
     */
    public record BallSound(SoundType soundType, float volume,
                            @Nullable SoundEvent breakSound, @Nullable SoundEvent hitSound) {

        /** 用材质音效组 + 自定义音量 */
        public static BallSound of(SoundType soundType, float volume) {
            return new BallSound(soundType, volume, null, null);
        }

        /** 直接用指定的两个音效（不经过材质） */
        public static BallSound of(SoundEvent breakSound, SoundEvent hitSound, float volume) {
            return new BallSound(SoundType.EMPTY, volume, breakSound, hitSound);
        }

        public SoundEvent resolveBreakSound() {
            return breakSound != null ? breakSound : soundType.getBreakSound();
        }

        public SoundEvent resolveHitSound() {
            return hitSound != null ? hitSound : soundType.getHitSound();
        }
    }

    /**
     * 单个物品的行为参数。
     *
     * @param damage        初始伤害（默认 3）；最终伤害会按出手速度缩放
     * @param cooldownTicks 使用冷却（刻，20 刻 = 1 秒；默认 0 = 无冷却）
     * @param velocity      出手初速度（原版雪球为 1.5）
     * @param inaccuracy    散布（原版雪球为 1.0，越小越准）
     * @param weight        重量 0–10，决定出手后的下坠速度（默认 4 = 雪球基准）
     * @param toughness     坚固：{@link #NOT_TOUGH} 碰到就碎（无弹性）/
     *                      {@link #TOUGH_FOREVER} 永不碎 /
     *                      {@code >0} 可反弹这么多次，每次消耗 1 点耐久
     * @param bounce        弹射 0–10：每次反弹后速度衰减到 (bounce×10)%；不坚固的球用不到
     * @param chargeLevels  可蓄力等级（默认 3）；0 表示不能蓄力
     * @param rarity        稀有度权重（越大越常见）
     * @param sound         音效配置（材质 / 音量 / 指定音效）
     * @param drops         碎裂掉落，可多层；空列表表示不掉东西
     * @param sense         感应等级：{@link #NOT_SENSE} 表示没有这个性质（默认）；
     *                      {@code >0} 表示带着「感应x」，会叠加热量并吸附目标。
     *                      和坚固一样是底层词条 —— 不写就是没有
     * @param meltThreshold 融化阈值：热量攒到这么多点就化掉（{@link #NOT_MELT} 表示不会融化）。
     *                      雪球类默认 {@link #DEFAULT_MELT_THRESHOLD}
     * @param moltenThreshold 【熔融】阈值：热量攒到这么多点之后，<b>击中目标会给它挂上
     *                      {@code molten_burn}</b>（{@link #NOT_MOLTEN} 表示没有这个性质，默认）。
     *                      与「融化」是两回事 —— 那个是球自己化掉，这个是烫伤别人
     * @param magnetRadius  磁吸半径（格）：飞行中把这个范围内的金属拽向自己；
     *                      {@link #NOT_MAGNET} 表示没有这个性质（默认）
     * @param morphBlock    【变形】耐久耗尽后要变成的方块；{@code null} 表示没有这个性质
     * @param entityScale   实体渲染尺寸倍率（1.0 = 基准球），金球那种大个头会调高
     * @param transmuteChance 【点金】命中方块时把石头点成矿物的概率；0 表示没有这个性质
     */
    public record BallProfile(
            float damage,
            int cooldownTicks,
            float velocity,
            float inaccuracy,
            int weight,
            int toughness,
            int bounce,
            int chargeLevels,
            int rarity,
            BallSound sound,
            List<BallDrop> drops,
            int sense,
            int meltThreshold,
            int moltenThreshold,
            double magnetRadius,
            Block morphBlock,
            float entityScale,
            float transmuteChance,
            boolean magnetic,
            int wisdom,
            boolean kindness,
            boolean conduction,
            int thunderThreshold,
            boolean shockDamage,
            boolean glint,
            /** 【穿透 N】可穿透的实体数（0 = 不穿透）*/
            int penetration,
            /** 新词条的位掩码，见 FLAG_* —— 用一个 int 装多个开关，免得 record 字段爆炸 */
            int flags) {

        // ===== 新词条开关位（2026-10-10 加） =====
        /** 【脉冲】红石球 —— 命中时对周围金属目标施加「震荡」 */
        public static final int FLAG_PULSE = 1 << 0;
        /** 【照明】红石雪球 —— 下方四棱锥区域内的生物被上色发光 */
        public static final int FLAG_ILLUMINATE = 1 << 1;
        /** 【破坏王】钻石球 —— 撞碎接触到的方块 */
        public static final int FLAG_BREAKER = 1 << 2;
        /** 【透镜】钻石球 —— 白天晴天时给下方范围内的方块与生物积热 */
        public static final int FLAG_LENS = 1 << 3;

        /** 是否为坚固球（含永久坚固与限次坚固）；不坚固的球碰到就碎，也不会反弹 */
        public boolean isTough() {
            return toughness != NOT_TOUGH;
        }

        /** 是否具备「感应」—— 决定它会不会叠加热量、吸附铁磁性目标 */
        public boolean hasSense() {
            return sense != NOT_SENSE;
        }

        /** 是否会因热量过高而融化 */
        public boolean hasMelt() {
            return meltThreshold != NOT_MELT;
        }

        /** 是否具备「磁吸」—— 飞行时把身边的金属拽向自己 */
        public boolean hasMagnet() {
            return magnetRadius > NOT_MAGNET;
        }

        /** 是否具备【熔融】—— 热量攒够后，击中目标会挂上 {@code molten_burn} */
        public boolean hasMolten() {
            return moltenThreshold != NOT_MOLTEN;
        }

        /**
         * 是否具备【磁性】—— 飞行途中会主动朝铁磁性目标偏航。
         *
         * <p>与 {@link #hasMagnet()}（磁吸：只把身边的金属拽过来，球自己不改向）是两回事。</p>
         */
        public boolean hasMagnetic() {
            return magnetic;
        }

        public BallProfile withDamage(float value) {
            return new BallProfile(value, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius, morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        public BallProfile withCooldown(int value) {
            return new BallProfile(damage, value, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius, morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        public BallProfile withVelocity(float value) {
            return new BallProfile(damage, cooldownTicks, value, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius, morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        public BallProfile withInaccuracy(float value) {
            return new BallProfile(damage, cooldownTicks, velocity, value, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius, morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /**
         * 重量 0 = 直线飞行，4 = 雪球，10 = 水平投掷只剩 2 格射程。
         *
         * <p>重量一变，<b>初速度跟着重算</b>：基准是雪球（重量 4）的 10，每重 1 点减 1。
         * 这样「重量」和「初速度」两个数值永远自洽，不用在两处各写一遍
         * —— 万一写成互相打架的两个数，玩家一眼就能看出来。</p>
         */
        public BallProfile withWeight(int value) {
            float speed = BASE_LAUNCH_SPEED - (value - BallWeight.BASELINE);
            return new BallProfile(damage, cooldownTicks, speed, inaccuracy, value, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius,
                    morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /** 【初速度】—— 由重量推出来的基础数值，基准 10 对应雪球（重量 4） */
        public float launchSpeed() {
            return velocity;
        }

        /**
         * 真正交给 {@code shoot()} 的物理射速 —— 初速度数值按
         * {@link BallBehavior#LAUNCH_SPEED_SCALE} 换算成原版量纲。
         */
        public float physicalVelocity() {
            return velocity * LAUNCH_SPEED_SCALE;
        }

        /** {@link #NOT_TOUGH}（默认）、{@link #TOUGH_FOREVER} 或可反弹次数 */
        public BallProfile withToughness(int value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, value,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius, morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /** 弹射 0–10：反弹后速度保留 (value×10)%，只对坚固球有意义 */
        public BallProfile withBounce(int value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    value, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius, morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /** 可蓄力等级；0 表示不能蓄力 */
        public BallProfile withChargeLevels(int value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, value, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius, morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /** 稀有度权重：越大越容易在随机抽取里出现 */
        public BallProfile withRarity(int value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, value, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius, morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /** 只用材质音效组（音量保持 1.0） */
        public BallProfile withSoundType(SoundType value) {
            return withSound(BallSound.of(value, 1.0F));
        }

        /** 完整音效配置：材质 / 音量 / 指定音效 */
        public BallProfile withSound(BallSound value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, value, drops, sense, meltThreshold, moltenThreshold, magnetRadius, morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /** 碎裂掉落（可多层，每层独立掷概率） */
        public BallProfile withDrops(List<BallDrop> value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, value, sense, meltThreshold, moltenThreshold, magnetRadius, morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /**
         * 「感应」等级 —— 底层词条，和坚固同级。
         *
         * <p>带上它之后，球会<b>扫描 15 格、但只从 7 格起才真正生效</b>：
         * 叠加热量、吸附最近且优先级最高的铁磁性目标（方块或生物）。
         * 不写就是 {@link #NOT_SENSE}，没有这个性质。</p>
         */
        public BallProfile withSense(int value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, value, meltThreshold, moltenThreshold, magnetRadius, morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /**
         * 「融化x」阈值 —— 热量攒到 x 点时球化掉。
         *
         * <p>雪球类默认 {@link #DEFAULT_MELT_THRESHOLD}（200）；不写就是
         * {@link #NOT_MELT}，热量再高也不会因此消失。</p>
         */
        public BallProfile withMelt(int value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, value, moltenThreshold, magnetRadius, morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /**
         * 「磁吸」半径 —— 飞行中把这个范围内的金属拽向自己。
         *
         * <p>作者给出的规格：对半径 2.5 格以内的金属（含粗矿、矿石、矿块、矿粉与制作出来的
         * 装备）施加一个<b>指向自身、大小为自身速度一半</b>的瞬间速度；
         * 脱离范围或球本身静止后就不再施加。不写就是 {@link #NOT_MAGNET}。</p>
         */
        public BallProfile withMagnet(double radius) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, radius,
                    morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /**
         * 【熔融】阈值 —— 热量攒到这么多点之后，球击中目标会给它挂上 {@code molten_burn}。
         *
         * <p>作者给出的规格：空心铁球是 <b>1000</b>，效果时长 <b>1 秒</b>。
         * 不写就是 {@link #NOT_MOLTEN}，没有这个性质。</p>
         */
        public BallProfile withMolten(int value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, value, magnetRadius, morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /**
         * 【磁性】—— 飞行途中主动朝最近、且优先级最高的铁磁性目标<b>偏航</b>。
         *
         * <p><b>和【磁吸】不是一回事</b>（作者 2026-10-07 明确纠正过）：</p>
         * <ul>
         *   <li>【磁吸】{@code magnetRadius}：球<b>不改变航向</b>，只是把身边 2.5 格内的金属
         *       拽向自己 —— 空心铁球那个</li>
         *   <li>【磁性】{@code magnetic}：球<b>自己拐弯</b>去追目标 —— 「雪球-铁粒」那个，
         *       原本是【探寻】的字面名字</li>
         * </ul>
         *
         * <p>⚠️ 之前偏航是挂在 {@code sense}（感应）上的，于是 2.0.0 给空心铁球加上感应之后，
         * 它也跟着自动拐弯了 —— 作者反馈「空心铁球还是会飞出去」。**别再挂回感应上**：
         * 感应只管积热，飞行方向归磁性管。</p>
         */
        public BallProfile withMagnetic(boolean value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius, morphBlock, entityScale, transmuteChance, value, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /**
         * 【变形】耐久耗尽后变成的方块 —— 金球专属性质。
         *
         * <p>不写就是 {@code null}，没有这个性质。
         * 具体行为见 {@code BallProjectile}：能放方块就放，放不下就掉成物品。</p>
         */
        public BallProfile withMorph(Block block) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius,
                    block, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /** 实体渲染尺寸倍率（1.0 = 基准球）；金球是「一格内切球」，所以调得比较大 */
        public BallProfile withEntityScale(float scale) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius,
                    morphBlock, scale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /**
         * 【点金】概率 —— 命中方块时，把命中面那一片 3×3×1 里的石头类方块
         * 按这个概率点成对应维度的矿物。
         *
         * <p>不写就是 0，没有这个性质。具体行为见 {@code BallTransmute}。</p>
         */
        public BallProfile withTransmute(float chance) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius,
                    morphBlock, entityScale, chance, magnetic, wisdom, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        // ===== 2.6.0 新增的五个词条（作者 2026-10-07 指定）=====

        /**
         * 【智慧】—— 发射后扫描周围 10 格内<b>没有方块遮挡、可以直接抵达</b>的敌对生物
         * （或正处于仇恨中的中立生物），找到最近的立刻锁定，把速度改成朝它飞。
         *
         * <p>不写就是 {@code 0}（没有这个性质）。</p>
         */
        public BallProfile withWisdom(int value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius,
                    morphBlock, entityScale, transmuteChance, magnetic, value, kindness, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /** 【善良】—— 不会伤害友好与中立生物；命中这类生物时原样反弹，不结算伤害 */
        public BallProfile withKindness(boolean value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius,
                    morphBlock, entityScale, transmuteChance, magnetic, wisdom, value, conduction, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /** 【导电】—— 处于实体状态时像避雷针一样让自然闪电优先击中自己；每次被劈消耗 1 点耐久 */
        public BallProfile withConduction(boolean value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius,
                    morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, value, thunderThreshold, shockDamage, glint, penetration, flags);
        }

        /**
         * 【引雷x】阈值 —— 热量攒到这么多就释放一次雷电并清空自身热量。
         *
         * <p>{@link #NOT_THUNDER}（0）表示没有这个性质。行为见 {@code BallThunder}。</p>
         */
        public BallProfile withThunder(int threshold) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius,
                    morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, threshold, shockDamage, glint, penetration, flags);
        }

        /** 【引雷】是否具备 */
        public boolean hasThunder() {
            return thunderThreshold > NOT_THUNDER;
        }

        /**
         * 【金光闪闪】—— 是否携带这套猪灵行为（砸猪灵不结仇、被撞声吸引、可被捡起、特殊交易）。
         *
         * <p>以前这个特质只认物品（{@code stack.is(GOLD_BALL)}），于是组合球永远读不到它 ——
         * 哪怕合成它的四分之一球里带【金光闪闪】（作者 2026-10-08 报的）。
         * 现在它是 profile 上的一个词条，金球自己在 override 里写 true，
         * 组合球则按「四合一 ≥2 份」合成出来。</p>
         */
        /** 是否带某个新词条开关（见 FLAG_*） */
        public boolean hasFlag(int flag) {
            return (flags & flag) != 0;
        }

        /** 是否具备【穿透】 */
        public boolean hasPenetration() {
            return penetration > 0;
        }

        public BallProfile withPenetration(int value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius,
                    morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction,
                    thunderThreshold, shockDamage, glint, value, flags);
        }

        /** 加一个词条开关（按位或） */
        public BallProfile withFlag(int flag) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius,
                    morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction,
                    thunderThreshold, shockDamage, glint, penetration, flags | flag);
        }

        public BallProfile withGlint(boolean value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius,
                    morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction,
                    thunderThreshold, shockDamage, value, penetration, flags);
        }
        /** 【电击】—— 这颗球直接造成的伤害改成<b>闪电类型</b>（数值不变，只换伤害类型） */
        public BallProfile withShockDamage(boolean value) {
            return new BallProfile(damage, cooldownTicks, velocity, inaccuracy, weight, toughness,
                    bounce, chargeLevels, rarity, sound, drops, sense, meltThreshold, moltenThreshold, magnetRadius,
                    morphBlock, entityScale, transmuteChance, magnetic, wisdom, kindness, conduction, thunderThreshold, value, glint, penetration, flags);
        }
    }
}
