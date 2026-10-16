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
 *   <li><b>数值词条</b>（坚固、感应、磁吸、点金）在半成品上按 1/2、1/4 缩放，
 *       <b>暂时不取整</b> —— 所以这里一律用 {@code double} 运算，不能落回 int。
 *       <b>例外：融化 / 熔融是阈值类，不缩放</b>（见 {@code Fragment#scaled}）</li>
 *   <li><b>无数值特性</b>（变形、磁性）由半球<b>直接继承</b>；
 *       <b>四分之一球把它们藏起来</b>，到四合一才重新判断。
 *       <b>例外：【金光闪闪】不参与合成</b>，被切开即永久失效</li>
 *   <li><b>二合一</b>：数值与特质<b>相叠加</b>，数值<b>向下取整</b></li>
 *   <li><b>四合一</b>：数值取四份之和后向下取整；同种特质要<b>存在 ≥2 份才显示并生效</b>，
 *       否则视为失去该特质</li>
 *   <li><b>雪球系 5 颗都不参与</b>（碎石 / 铁粒 / 金粒 / 红石 / 铜粒；作者指定）</li>
 * </ul>
 *
 * <h2>为什么半成品的词条不进 BallProfile</h2>
 * <p>{@link BallProfile} 的数值字段都是 {@code int}，而作者要求求半成品「暂时不用取整」。
 * 所以半成品另走一条路：物品上只存「来源球 id + 分数」，词条每次由
 * {@link #scaled} 现算 —— 既保住了小数，也不污染球本身的数据结构。</p>
 *
 * <h2>★ 切球 / 重组的三类词条法则（作者 2026-10-10 定，底层准则 —— 改这里之前先读这一节）</h2>
 *
 * <p>一颗球被切开再合回去，词条不是简单「除一下再乘回来」。按词条的<b>性质</b>分成三类，各走各的规则：</p>
 *
 * <h3>① 数值类 —— 基础</h3>
 * <p>伤害 / 重量 / 坚固 / 弹性 / 蓄力 / 初速 / 不精确度 等「球的物理属性」。</p>
 * <ul>
 *   <li>切开：每份 <b>× 1/份数</b>（半球 0.5、四分之一球 0.25）</li>
 *   <li>合成：各份<b>相加</b>，然后 <b>向下取整</b></li>
 *   <li>例：【伤害 10】四等分 → 4 个【伤害 2.5】；两个 2.5 加两个 0.25 → 5.5 → <b>取整得 5</b></li>
 * </ul>
 *
 * <h3>② 数值类 —— 特性</h3>
 * <p>带数字的特性词条：【智慧N】【穿透N】。</p>
 * <ul>
 *   <li>切开：每份 <b>× 1/份数</b>，且处于 <b>未激活</b> 状态 —— 单份<b>不显示、不生效</b>
 *       （例：【智慧 5】→ 4 个【智慧 1.25·未激活】）</li>
 *   <li>合成：<b>凑够份数才激活</b>（四合一要 ≥2 份），激活时各份<b>相加再向下取整</b>
 *       <ul>
 *         <li>1 份 1.25 + 3 份 0 → 份数不够 → <b>不显示、不生效</b></li>
 *         <li>2 份 1.25 + 2 份 0 → 2.5 → <b>取整得 2</b>，即【智慧 2】</li>
 *       </ul></li>
 * </ul>
 *
 * <h3>③ 阈值类 —— 不缩放</h3>
 * <p>【引雷x】这类「攒到 x 才触发」的词条。</p>
 * <ul>
 *   <li>切开：<b>四份都保留原阈值</b>（不是 120/4 = 30）—— 缩放了就永远回不到 120</li>
 *   <li>合成：<b>取最大值</b>。例：【引雷 120】+【引雷 90】→ <b>【引雷 120】</b></li>
 *   <li>另有【融化x】【熔融x】两个阈值词条走各自的取端规则
 *       （<b>融化取最低、熔融取最高</b>），见 {@code withThresholds}</li>
 * </ul>
 *
 * <h3>④ 布尔 / 位掩码类 —— 按份数携带</h3>
 * <p>变形 / 磁性 / 善良 / 导电 / 电击 / 移形换位 / 潮涌，以及【脉冲】【照明】【破坏王】【透镜】。</p>
 * <ul>
 *   <li>切开：原样透传（数据要留着，合成了还要数份数）</li>
 *   <li>合成：<b>≥1 份即携带</b> —— 二合一任一份有就带、四合一同样只要 ≥1 份。
 *       ⚠️ 作者 2026-10-13 起<b>取消了「激活」门槛</b>：以前四合一要 ≥2 份才保留，
 *       现在一份带着就保留（{@code QUAD_TRAIT_THRESHOLD} 因此已无任何使用点，只留作历史）</li>
 *   <li><b>例外：【金光闪闪】</b>是金球独占 —— <b>不参与合成</b>，被切开就永久失效，
 *       合回去也不能重新获得（作者 2026-10-09 指定）</li>
 *   <li><b>例外：【金光闪闪】</b>是金球独占 —— <b>不参与合成</b>，被切开就永久失效，
 *       合回去也不能重新获得（作者 2026-10-09 指定）</li>
 *   <li><b>另一条例外：【忠诚】</b>—— 潮汐球独占，作者 2026-10-13 把它归入
 *       <b>「唯一特殊」</b>这一类：与【金光闪闪】同样的规则，<b>不继承、不参与合成</b>。
 *       落地在 {@code of()} 硬写 false、{@code comboProfile} 也硬写 false</li> * </ul>
 *
 * <p>实现落点：{@code Fragment.scaled}（按份数缩放）、{@code Fragment.plus}（叠加）、
 * {@code combinePair} / {@code combineQuad}（二合一 / 四合一的份数判定）、
 * {@code comboProfile}（落回 BallProfile）。<b>这四处加词条时要一起改。</b></p>
 *
 * <p>⚠️ <b>注意 {@code comboProfile} 那处写回编译器抓不到</b> —— 少写一行不会报错，
 * 只会让「各来源球上明明有的词条」在组合球上凭空消失。历史上
 * wisdom / kindness / conduction / thunder / shock 五个就是这么丢的（作者 2026-10-08 报的）。
 * <b>加任何新词条之后，都要回头确认 {@code comboProfile} 里有它的一行。</b></p>
 */
public final class BallFragments {

    private BallFragments() {
    }

    /**
     * 参与切割的球 —— 「所有类型的雪球不参与」（作者指定）。
     *
     * <p>当前共 <b>11 颗</b>（雪球系 5 颗全部不参与：碎石 / 铁粒 / 金粒 / 红石 / 铜粒）。</p>
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
                    ModItems.HOLLOW_COPPER_BALL.get(),
                    // ⚠️ 3.0.0（0.3.4.0）追加的两颗，同样**必须排在后面**：
                    //    永远只在末尾追加，中间插入会让所有老存档的来源下标错位。
                    ModItems.REDSTONE_BALL.get(),
                    ModItems.DIAMOND_BALL.get(),
                    // ⚠️ 0.3.5.0 追加的青金石球，同样**只在末尾追加**。
                    //    它的下标是 10 —— 三张 json 下标表（ball_half / ball_quarter /
                    //    combo_ball）也必须同步补 when:10，否则碎片与组合球分片会
                    //    落到 fallback（木球的贴图）。
                    ModItems.LAPIS_BALL.get(),
                    // ⚠️ 0.3.6.0 追加的**末影珍珠**（原版物品），同样**只在末尾追加** —— 下标 11。
                    //    它是原版物品，所以有些东西早就齐了：#more_balls:balls 与
                    //    balls/solid 标签里都有它、弩的 case 与 crossbow_ball_ender_pearl
                    //    贴图也早就有了。真正缺的只是「进这张表」这一步。
                    //    这里写全限定名 —— 本文件其它地方没用到 Items，省得为一个原版
                    //    常量去动 import 区。
                    net.minecraft.world.item.Items.ENDER_PEARL,
                    // ⚠️ 0.3.6.0 追加的**潮汐球**，同样**只在末尾追加** —— 下标 12。
                    ModItems.TIDAL_BALL.get(),
                    // ⚠️ 第十三批重做（作者 2026-10-15）：**只在末尾追加** —— 下标 13..17。
                    //    史莱姆球是**原版物品**（Items.SLIME_BALL）：作者指定把它纳入适配范围，
                    //    所以它也要进这张表（能切、能进组合球）。它自带原版贴图，
                    //    但弩装填图、碎片贴图、charge_piece 六张照样一处不能少。
                    net.minecraft.world.item.Items.SLIME_BALL,   // 13 史莱姆
                    ModItems.EMERALD_BALL.get(),                 // 14 绿宝石
                    ModItems.DRIPSTONE_BALL.get(),               // 15 滴水石
                    ModItems.PRESERVE_BALL.get(),                // 16 蜜饯
                    ModItems.TALISMAN_BALL.get(),                 // 17 符箓
                    // 第十四批（作者 2026-10-16）—— 一律追加在末尾
                    ModItems.ECHO_BALL.get());                    // 18 回响
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
    /**
     * 组合球<b>显示名</b>里用的短名，与 {@link #sources()} <b>同序、同长度</b>。
     *
     * <p>⚠️ 这两张表和 {@code sources()} 是<b>按下标对齐</b>的 —— 加来源球时必须一起追加，
     * 否则新球的下标会越界。作者 2026-10-10 报「切出来的名字都是错的、完全套的铜球」，
     * 根因就是这里漏了两项：{@code BallFragmentItem} 取值时做了 clamp，
     * 下标 8/9 被夹回 7（= 铜），于是红石球和钻石球的碎片全叫「铜」。</p>
     */
    public static final List<String> SHORT_NAME =
            List.of("木", "圆石", "铁", "金", "空心铁", "紫水晶", "铜", "空心铜",
                    "红石", "钻石", "青金石", "末影", "潮汐",
                    // 第十三批重做（下标 13..17）—— 一律追加在末尾
                    "史莱姆", "绿宝石", "滴水石", "蜜饯", "符箓",
                    // 第十四批（下标 18）
                    "回响");

    /**
     * 组合球<b>内部 id</b> 里用的短名，与 {@link #sources()} <b>同序、同长度</b>。
     *
     * <p>见 {@link #SHORT_NAME} 的说明 —— 这两张表必须和 {@code sources()} 一起增长。</p>
     */
    public static final List<String> SHORT_ID =
            List.of("wooden", "cobblestone", "iron", "gold", "hollow_iron",
                    "amethyst", "copper", "hollow_copper",
                    "redstone", "diamond", "lapis", "ender_pearl", "tidal",
                    // 第十三批重做（下标 13..17）—— 与 SHORT_NAME 严格同序
                    "slime", "emerald", "dripstone", "preserve", "talisman",
                    // 第十四批（下标 18）
                    "echo");

    /** 这颗球在 {@link #sources()} 里的下标；不属于其中就返回 -1（雪球会走到这里） */
    public static int indexOf(Item ball) {
        return sources().indexOf(ball);
    }

    // ===== 词条快照 =====

    /**
     * 坚固值里的「永不碎裂」在**加法阶段**的替身。
     *
     * <p>{@code TOUGH_FOREVER} 是 {@code -1}，直接参与算术会算出 {@code -2}
     * （一个比「永不碎裂」还强的怪值）。合成时先抬成这个足够大的数，
     * 落回 profile 时再按阈值还原成哨兵。</p>
     */
    public static final double TOUGH_FOREVER_MARK = 100000.0D;

    /**
     * 一个「碎片」的词条快照 —— 全部用 {@code double}。
     *
     * <p>{@code 0} 一律表示<b>没有这条词条</b>（对应 {@code BallProfile} 里那些
     * {@code NOT_XXX} 哨兵值，这里统一归零好做加法）。</p>
     *
     * <p>数值项走「相加」，特质项走「出现过 / 四合一要 ≥2 份」。</p>
     *
     * <p>⚠️ <b>特质项必须与 {@link BallProfile} 上的词条一一对应</b>。
     * 早先这里只有 {@code morph / magnetic / glint} 三个，于是
     * {@code wisdom / kindness / conduction / thunder / shock} 五个词条
     * 在合成时被整个丢掉 —— 表现就是「紫水晶球 + 别的球合出来的组合球
     * 没有【智慧】【善良】」（作者 2026-10-08 报的）。加新词条时这里和
     * {@link #of(BallProfile, boolean)}、{@code comboProfile} 三处要一起改。</p>
     *
     * @param toughness 坚固（可反弹次数）
     * @param sense     感应等级
     * @param melt      融化阈值
     * @param molten    熔融阈值
     * @param magnet    磁吸半径
     * @param transmute 点金概率
     * @param morph     【变形】特质（无数值，看有没有）
     * @param magnetic  【磁性】特质（无数值，看有没有）
     * @param glint     【金光闪闪】特质（无数值，金球专属；不参与合成）
     * @param wisdom    【智慧N】（数值特性，切开均分、够份数才激活）
     * @param kindness  【善良】特质
     * @param conduction 【导电】特质
     * @param thunder   【引雷x】阈值类：切开不缩放、合成取最高，且要激活
     * @param shock     【电击】特质
     * @param penetration 【穿透N】（数值特性）
     * @param flags     位掩码型词条（脉冲 / 照明 / 透镜）
     * @param breaker   【破坏王N】（数值特性：切开均分、激活后相加取整）
     * @param transpose 【移形换位】特质（其它特殊类：切开原样透传、合成 ≥1 份即携带）
     */
    public record Fragment(double toughness, double sense, double melt, double molten,
                           double magnet, double transmute,
                           boolean morph, boolean magnetic, boolean glint,
                           // 【智慧N】【穿透N】是数值类特性 —— 中间层一律**保留小数**，
                           // 取整只在落回 BallProfile 时做（否则 1/4 份会被截成 0，份数判定随之失败）
                           double wisdom, boolean kindness, boolean conduction,
                           boolean thunder, boolean shock,
                           double penetration, int flags,
                           /** 【破坏王N】—— 数值类特性词条：切开均分、激活后相加取整（同样保留小数） */
                           double breaker,
                           /**
                            * 【移形换位】（原版 0.3.6.0 加入）—— 「其它特殊」类：
                            * 切开<b>原样透传</b>、合成时 **≥1 份即携带**（和其它特殊类一致，
                            * 不再需要激活）。字段**追加在末尾**，不动既有分量的顺序。
                            */
                           boolean transpose,
                           /** 【潮涌】（「其它特殊」类）—— 入水后不受重力、速度 ×3，并环绕激流纹理 */
                           boolean tidalSurge,
                           /** 【忠诚】（「其它特殊」类）—— 永远带忠诚附魔，且不会被任何生物捡走 */
                           boolean loyalty,
                           /**
                            * 【招蜂引蝶N】（蜜饯球）—— **数值类特性**类（作者 2026-10-15 的分类）：
                            * 切开均分（中间层保住小数）、合成 ≥1 份激活后相加取整。
                            */
                           double bee,
                           /**
                            * 【滋养】（蜜饯球）—— <b>组合类特性词条</b>（作者 2026-10-15 定稿的新类别）。
                            *
                            * <p>与「唯一特殊」正好相反：完整球里携带但<b>不生效</b>；
                            * 切开时按份数<b>均分触发概率</b>（每份 1/份数），
                            * 合进组合球后各份相加、≥1 份即生效。</p>
                            *
                            * <p>它是概率，所以**不参与 floor** —— 四份各 0.25 相加正好回到 1.0。</p>
                            */
                           double nurture) {

        public static final Fragment EMPTY =
                new Fragment(0, 0, 0, 0, 0, 0, false, false, false, 0, false, false, false, false, 0, 0, 0, false,
                        false, false, 0, 0);

        /**
         * 按分数缩放一份词条（半球 0.5、四分之一球 0.25）。
         *
         * <p><b>按「切球 / 重组的三类词条法则」（作者 2026-10-10 定）：</b></p>
         * <ul>
         *   <li><b>数值类</b>一律 ×factor —— 包含**基础**（坚固/感应/磁吸/点金）
         *       与**特性**（【智慧N】【穿透N】【破坏王N】）。</li>
         *   <li><b>阈值类</b>（【融化x】【熔融x】【引雷x】）**不缩放**：各份都保留原阈值。
         *       引雷合成取最大值；融化 / 熔融见 {@code withThresholds}
         *       （融化取最低、熔融取最高）。缩放成 30 就永远回不到 120 了。</li>
         *   <li><b>布尔 / 位掩码类</b>（变形/磁性/善良/导电/电击/移形换位/潮涌、
         *       脉冲/照明/破坏王/透镜）原样透传 ——
         *       「要够几份才激活」由 combine* 判定，不在这里动。</li>
         * </ul>
         */
        public Fragment scaled(double factor) {
            // ⚠️ melt / molten 是**阈值类**（【融化x】【熔融x】）—— 法则要求「不缩放」。
            //    缩放了就永远回不到原阈值（【融化 200】切四份变 50，合回来只有 50）。
            return new Fragment(toughness * factor, sense * factor, melt,
                    molten, magnet * factor, transmute * factor,
                    morph, magnetic, glint,
                    // ⚠️ 【智慧N】是**带数值的特性**词条 —— 切四份后每份是「1.25，未激活」，
                    //    所以必须 ×factor。否则两份相加会算成 5+5=10（应为 1.25×2=2.5 → 2）。
                    //    ⚠️ 这里**不取整**：1/4 份时 floor 会把 1 → 0，穿透/智慧/破坏王
                    //       直接归零，「份数 > 0」的激活判定随之失败，组合球永远拿不到它们。
                    //       取整只在落回 BallProfile 时做（combinePair / combineQuad）。
                    wisdom * factor,
                    kindness, conduction, thunder, shock,
                    penetration * factor,
                    flags,
                    // 【破坏王N】数值类**特性**：切开均分（够不够份数由 combine* 判），同样不取整
                    breaker * factor,
                    // 【移形换位】布尔类：原样透传
                    transpose, tidalSurge, loyalty,
                    // 【招蜂引蝶N】数值类**特性**：切开均分（中间层保住小数，够不够份数由 combine* 判）
                    bee * factor,
                    // 【滋养】组合类：切开时**均分触发概率**（完整球 1.0 → 四分之一球 0.25）
                    nurture * factor);
        }

        /** 叠加 —— 数值相加，特质取「出现过」 */
        public Fragment plus(Fragment other) {
            return new Fragment(toughness + other.toughness, sense + other.sense,
                    melt + other.melt, molten + other.molten,
                    magnet + other.magnet, transmute + other.transmute,
                    morph || other.morph, magnetic || other.magnetic, glint || other.glint,
                    wisdom + other.wisdom, kindness || other.kindness,
                    conduction || other.conduction, thunder || other.thunder,
                    shock || other.shock,
                    // 【穿透x】是数值型，和【智慧】一样相加（够份数/取整由 combine* 决定）
                    penetration + other.penetration,
                    // 位掩码型词条（【脉冲】【照明】【透镜】）取并集
                    flags | other.flags,
                    // 【破坏王N】数值类特性：相加（够不够份数由 combine* 判）
                    breaker + other.breaker,
                    // 【移形换位】布尔类：取或
                    transpose || other.transpose,
                    tidalSurge || other.tidalSurge,
                    loyalty || other.loyalty,
                    // 【招蜂引蝶N】数值类特性：相加（够不够份数由 combine* 判）
                    bee + other.bee,
                    // 【滋养】组合类：**概率相加** —— 每份带着自己那一份概率
                    nurture + other.nurture);
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
     * <p>【金光闪闪】虽然已经是 {@link BallProfile} 上的词条，但组合球一律不继承它
     * （见 {@code comboProfile}），所以这里仍按「是不是完整金球」单独判定。</p>
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
                p.shockDamage(),
                // 新增：穿透数值 + 位掩码型词条
                p.penetration(),
                p.flags(),
                p.breaker(), p.transpose(), p.tidalSurge(), false /* 【忠诚】唯一特殊：不继承 */,
                // 第十三批：招蜂引蝶（数值类特性）/ 滋养（组合类）。
                // ⚠️ 组合类的【滋养】在**完整球上携带但不生效** —— 照样读进 Fragment，
                //    「不生效」是在使用侧判的（见 BallProjectile.deliverToOwner）。
                p.beeCount(), p.nurture());
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
        // 二合一：布尔特质直接继承（任一份有就算有），数值向下取整。
        // 但**阈值类要单独拎出来**：【融化n】取最低、【熔融n】取最高 —— 见 withThresholds。
        return withThresholds(floor(sum), a, b);
    }

    /**
     * 把<b>阈值类</b>词条（【融化x】【熔融x】）按「融化取最低、熔融取最高」写回。
     *
     * <p>阈值类<b>不参与相加、也不取整</b>：各份都保留原阈值，合成时按上面两条各取一端。
     * 例：【融化 200】+【融化 150】→ 取最低 150（越容易化越优先）；
     * 【熔融 120】+【熔融 90】→ 取最高 120。</p>
     *
     * <p>【引雷x】也属阈值类，但它走另一条路（份数激活 + 取最高），
     * 见 {@code combineQuad} 与 {@code thunderThresholdFrom}。</p>
     */
    private static Fragment withThresholds(Fragment out, Fragment... parts) {
        double meltMin = Double.MAX_VALUE;
        double moltenMax = 0.0D;
        for (Fragment f : parts) {
            if (f.melt() > 0) {
                meltMin = Math.min(meltMin, f.melt());   // 【融化n】取同种最低
            }
            if (f.molten() > 0) {
                moltenMax = Math.max(moltenMax, f.molten());   // 【熔融n】取最高
            }
        }
        double melt = (meltMin == Double.MAX_VALUE) ? 0.0D : meltMin;
        return new Fragment(out.toughness(), out.sense(), melt, moltenMax,
                out.magnet(), out.transmute(),
                out.morph(), out.magnetic(), out.glint(),
                out.wisdom(), out.kindness(), out.conduction(), out.thunder(), out.shock(),
                out.penetration(), out.flags(), out.breaker(), out.transpose(),
                out.tidalSurge(), out.loyalty(), out.bee(), out.nurture());
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
        int wisdoms = (a.wisdom() > 0 ? 1 : 0) + (b.wisdom() > 0 ? 1 : 0) + (c.wisdom() > 0 ? 1 : 0) + (d.wisdom() > 0 ? 1 : 0);
        int kindnesses = (a.kindness() ? 1 : 0) + (b.kindness() ? 1 : 0) + (c.kindness() ? 1 : 0) + (d.kindness() ? 1 : 0);
        int conductions = (a.conduction() ? 1 : 0) + (b.conduction() ? 1 : 0) + (c.conduction() ? 1 : 0) + (d.conduction() ? 1 : 0);
        int thunders = (a.thunder() ? 1 : 0) + (b.thunder() ? 1 : 0) + (c.thunder() ? 1 : 0) + (d.thunder() ? 1 : 0);
        int shocks = (a.shock() ? 1 : 0) + (b.shock() ? 1 : 0) + (c.shock() ? 1 : 0) + (d.shock() ? 1 : 0);
        int penetrations = (a.penetration() > 0 ? 1 : 0) + (b.penetration() > 0 ? 1 : 0)
                + (c.penetration() > 0 ? 1 : 0) + (d.penetration() > 0 ? 1 : 0);
        int breakers = (a.breaker() > 0 ? 1 : 0) + (b.breaker() > 0 ? 1 : 0)
                + (c.breaker() > 0 ? 1 : 0) + (d.breaker() > 0 ? 1 : 0);
        int transposes = (a.transpose() ? 1 : 0) + (b.transpose() ? 1 : 0)
                + (c.transpose() ? 1 : 0) + (d.transpose() ? 1 : 0);
        int tidals = (a.tidalSurge() ? 1 : 0) + (b.tidalSurge() ? 1 : 0)
                + (c.tidalSurge() ? 1 : 0) + (d.tidalSurge() ? 1 : 0);
        int loyalties = (a.loyalty() ? 1 : 0) + (b.loyalty() ? 1 : 0)
                + (c.loyalty() ? 1 : 0) + (d.loyalty() ? 1 : 0);
        // 【招蜂引蝶N】是**数值类特性** —— 份数判定与智慧 / 穿透 / 破坏王同一套
        int bees = (a.bee() > 0 ? 1 : 0) + (b.bee() > 0 ? 1 : 0)
                + (c.bee() > 0 ? 1 : 0) + (d.bee() > 0 ? 1 : 0);
        // 位掩码型词条：【脉冲】【照明】【破坏王】【透镜】
        // 特性类词条**不再需要激活**（作者 2026-10-13）：只要有一份带着，四合一就保留它 ——
        // 逐位统计份数，各自判一次
        int flagMask = 0;
        for (int bit = 0; bit < 32; bit++) {
            int unit = 1 << bit;
            int count = ((a.flags() & unit) != 0 ? 1 : 0) + ((b.flags() & unit) != 0 ? 1 : 0)
                    + ((c.flags() & unit) != 0 ? 1 : 0) + ((d.flags() & unit) != 0 ? 1 : 0);
            if (count >= 1) {
                flagMask |= unit;
            }
        }
        return new Fragment(
                Math.floor(sum.toughness()), Math.floor(sum.sense()),
                // 阈值类的两条不同规则（作者六类法则）：
                //   【融化n】阈值类**基础** → 取同种**最低**（越低越容易化）
                //   【熔融n】阈值类**特殊** → 取**最高**，且要激活（份数判定在下面）
                meltPick(a, b, c, d, true), meltPick(a, b, c, d, false),
                Math.floor(sum.magnet()), sum.transmute(),   // 同上：概率保持小数
                morphs >= 1,
                magnets >= 1,
                glints >= 1,
                // 【智慧N】是**带数值**的词条：分量够（≥2 份）才保留，保留时给四份之和
                (wisdoms >= 1) ? (int) Math.max(1, Math.floor(sum.wisdom())) : 0,
                kindnesses >= 1,
                conductions >= 1,
                // 【引雷x】是**阈值类特殊**词条（作者 2026-10-10 分类）：
                // 切开不变、**需要激活**（四合一 ≥2 份）、合成取**最高**（阈值由 thunderThresholdFrom 取 max）
                thunders >= 1,
                shocks >= 1,
                // 【穿透x】数值型：够份数才保留，保留时给四份之和
                (penetrations >= 1)
                        ? (int) Math.floor(sum.penetration()) : 0,
                flagMask,
                // 【破坏王N】数值类特性：够份数才激活，激活时四份之和取整
                (breakers >= 1)
                        ? (int) Math.floor(sum.breaker()) : 0, transposes >= 1, tidals >= 1, loyalties >= 1,
                // 【招蜂引蝶N】数值类特性：≥1 份才激活，激活时四份之和取整
                (bees >= 1) ? (int) Math.floor(sum.bee()) : 0,
                // 【滋养】组合类：**概率直接相加、不取整**（四份各 0.25 → 1.0）。
                // 份数为 0 时 sum 自然是 0，所以不需要额外的份数门槛 —— 这就是作者定的
                // 「≥1 份即生效」：一份 25% 概率生效，四份 100%。
                sum.nurture());
    }

    /**
     * 四合一里取阈值：{@code wantMelt} 为 true 时取【融化】的**最低值**，
     * 否则取【熔融】的**最高值**（作者 2026-10-10 的六类法则）。
     */
    private static double meltPick(Fragment a, Fragment b, Fragment c, Fragment d, boolean wantMelt) {
        double best = wantMelt ? Double.MAX_VALUE : 0.0D;
        for (Fragment f : new Fragment[] { a, b, c, d }) {
            double v = wantMelt ? f.melt() : f.molten();
            if (v <= 0) {
                continue;
            }
            best = wantMelt ? Math.min(best, v) : Math.max(best, v);
        }
        return (best == Double.MAX_VALUE) ? 0.0D : best;
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
     *   <li><b>六个数值词条 + 特质</b>（【金光闪闪】除外，它不参与合成）：按作者定的规则合成
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
        // ⚠️ 碎片份数 = 1/份数（半成品 2 份 → 1/2；四分之一球 4 份 → 1/4）。
        //    这里必须**缩放后再相加**，而不是拿整球的值去除以份数 ——
        //    两者的算术结果相同，但「向下取整」的位置完全不同：
        //    作者要的是「各份相加=5.5 → floor → 5」，而不是「5.5 的浮点值直接留着」。
        double fraction = 1.0D / indexes.size();
        for (int i : indexes) {
            Item ball = balls.get(i);
            parts.add(BallBehavior.profileFor(new ItemStack(ball)));
            frags.add(of(ball).scaled(fraction));
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

        // 数值 = 各份**相加**（份数已在 frags 里按 1/n 缩好）。
        // 作者 2026-10-10 的准则：合成结果是「相加后**向下取整**」。
        // ⚠️ 每个来源只贡献 **1/份数**，与上面 frags 的 scaled(fraction) 同一套。
        //    这里曾经写成 `p.damage()` 直接累加 —— frags 那边缩放了、这五个数值没有，
        //    于是四合一的伤害/重量/弹性/蓄力全变成四倍；而重量又驱动初速度重算，
        //    表现就是「重量、初速度都异常」（作者 2026-10-10 报的）。
        double damage = 0.0D;
        double weight = 0.0D;
        double bounce = 0.0D;
        double charge = 0.0D;
        double inaccuracy = 0.0D;
        // ⚠️ 初值必须是 0，不能是 1.0 —— entityScale 是「相对基准球的倍率」，
        //    用 1.0 当种子之后，任何**小于 1** 的来源都会被它盖掉（Math.max 取不到）。
        float scale = 0.0F;
        Block morph = null;
        for (BallProfile p : parts) {
            damage += p.damage() * fraction;
            weight += p.weight() * fraction;
            bounce += p.bounce() * fraction;
            charge += p.chargeLevels() * fraction;
            inaccuracy += p.inaccuracy() * fraction;
            scale = Math.max(scale, p.entityScale());
            if (morph == null) {
                morph = p.morphBlock();
            }
        }

        // ===== 【音效】从各个组件的音效里**随机抽一个**（作者 2026-10-13 指定）=====
        //
        // ⚠️ 不能用 Math.random()/RandomSource —— comboProfile 会被**客户端与服务端各自
        //    算一遍**（tooltip、渲染、AI 全在调它），两边必须抽出同一个音效，否则
        //    手上显示的音效和实际碰出来的不一样。
        //    所以种子只由**来源下标**决定：同一颗组合球，在哪端、什么时候算，结果都相同。
        //
        //    注：音效为 null 的组件要跳过（BallSound 允许 null 吗？不允许 —— parts 里拿到的
        //    都是 profile.sound()，DEFAULT 那个是有值的，所以实际不会出现 null；
        //    这里仍然按「只收集非 null」来写，免得以后有人把 sound 改成可空时静默出错）。
        List<BallSound> soundPool = new ArrayList<>(parts.size());
        for (BallProfile p : parts) {
            if (p.sound() != null && !soundPool.contains(p.sound())) {
                soundPool.add(p.sound());
            }
        }
        BallSound sound = soundPool.isEmpty()
                ? null
                : soundPool.get(pickSoundIndex(indexes, soundPool.size()));

        // 拿第一份当底子再逐项覆盖：这样所有「没列出来的字段」都能保持合法默认值，
        // 不用去猜每个字段的构造函数位置
        BallProfile base = parts.get(0);
        BallProfile out = base
                // 相加后向下取整（作者准则）—— 例：2.5+2.5+0.25+0.25 = 5.5 → 5
                .withDamage((float) Math.floor(damage))
                .withWeight((int) Math.floor(weight))
                .withBounce((int) Math.floor(bounce))
                .withChargeLevels((int) Math.floor(charge))
                .withInaccuracy((float) Math.floor(inaccuracy))
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
                // ⚠️ 【智慧N】【穿透N】【破坏王N】在 Fragment 里是 double（为了保住 1/4 份的小数），
                //    到这里才收窄成 int —— merged 已由 combinePair / combineQuad 取过整，
                //    所以这里只是类型转换，数值口径与既有的 toughness / sense 一致。
                .withWisdom((int) merged.wisdom())
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
        // ===== 位掩码型与数值型的新词条同样要落回输出 =====
        //
        // ⚠️ 作者 2026-10-10 报「应该因为叠加而生效的词条没有生效」——
        //    和上面 wisdom / kindness 那一段是同一个坑：这些词条在各来源球上都是对的，
        //    但组合时**没有任何一行把它们写回 profile**。以后加新词条必须回来补这里。
        out = out
                .withPenetration((int) merged.penetration())
                .withFlags(merged.flags())
                .withBreaker((int) merged.breaker())
                // 【移形换位】（「其它特殊」类）：切开原样透传、合成 ≥1 份即携带 ——
                // 份数判定在 combineQuad（transposes >= 1）/ combinePair（plus 取或）里做完了，
                // 这里只是把结果写回 profile。⚠️ 就是上面警告过的那个坑，别漏这一行。
                .withTranspose(merged.transpose())
                // 【潮涌】同上：份数判定在 combine* 里做完了，这里写回 profile
                .withTidalSurge(merged.tidalSurge())
                // ⚠️ 【忠诚】是「唯一特殊」类 —— **不参与合成**，和【金光闪闪】同样的处理：
                //    显式写回 false，而不是沿用 merged.loyalty()。组合球永远拿不到忠诚，
                //    哪怕四份全是潮汐球。
                //
                // ⚠️ 口径已于 2026-10-16 核实过，本工程现行设定确实是「唯一特殊」：
                //    · BallFragments 的类注释（本文件开头）明确这么写；
                //    · CHANGELOG 1.0.0.10 记着「【忠诚】改为『唯一特殊』词条」；
                //    · docs/球与词条.md 同样归为「唯一特殊」；
                //    · 作者本人 2026-10-16 也这么说：「【忠诚】作为唯一特殊词条根本不会在组合球里出现」
                //      —— 那是**澄清**（他紧接着说「我说的是组合球的【潮涌】乱飞」），不是报 bug。
                //    ⚠️ 而 BallBehavior 里 loyalty 字段的注释一度写成「其它特殊」（笔误，已更正）——
                //       照着它判断会走弯路，别再看那个。
                .withLoyalty(false)
                // ===== 第十三批新增的两条（作者 2026-10-15）=====
                //
                // ⚠️ 就是上面警告过的那个坑：这处写回编译器抓不到，少一行 = 词条凭空消失。
                //   【招蜂引蝶N】数值类特性：份数判定在 combineQuad 里做完了，这里写回数值
                .withBeeCount((int) merged.bee())
                //   【滋养】组合类：均分后的概率相加结果直接落回。
                //   完整球上**也**带着这个值（1.0），那是「携带但不生效」；
                //   真正生效只在组合球上，判据在使用侧（见 BallProjectile.deliverToOwner）。
                .withNurture(merged.nurture());
        return out;
    }

    /**
     * 从各来源里找「带引雷那一份」的阈值。
     *
     * <p>【引雷】是阈值型词条 —— 份数够就保留，但阈值本身要沿用真正带它的那颗球的，
     * 不能取第一格（那颗球很可能根本没有引雷）。</p>
     */
    /**
     * 用来源下标算一个**稳定**的抽取下标（作者 2026-10-13 的「音效随机抽一个」）。
     *
     * <p>「随机」指的是<b>不是固定取第一份</b>，而不是每次调用都变 ——
     * 组合球的 profile 会被客户端与服务端分别算出来，两边必须一致，
     * 所以种子只取自来源下标（下标本身又是存档数据，天然稳定）。</p>
     */
    private static int pickSoundIndex(List<Integer> indexes, int bound) {
        if (bound <= 1) {
            return 0;
        }
        int seed = 0;
        for (int i : indexes) {
            seed = seed * 31 + i;
        }
        return Math.floorMod(seed, bound);
    }

    private static int thunderThresholdFrom(List<BallProfile> parts) {
        int best = BallBehavior.NOT_THUNDER;
        for (BallProfile p : parts) {
            if (p.hasThunder()) {
                best = Math.max(best, p.thunderThreshold());
            }
        }
        return best;
    }
    /** 数值向下取整（特质原样透传） */
    private static Fragment floor(Fragment f) {
        return new Fragment(
                Math.floor(f.toughness()), Math.floor(f.sense()),
                Math.floor(f.melt()), Math.floor(f.molten()),
                Math.floor(f.magnet()), f.transmute(),   // 点金是概率，不能 floor（0.1 -> 0 会整个抹掉）
                f.morph(), f.magnetic(), f.glint(),
                // 数值类特性（【智慧N】【穿透N】【破坏王N】）现在都是 double，二合一同样要向下取整
                Math.floor(f.wisdom()), f.kindness(), f.conduction(), f.thunder(), f.shock(),
                Math.floor(f.penetration()), f.flags(), Math.floor(f.breaker()), f.transpose(),
                f.tidalSurge(), f.loyalty(),
                // 【招蜂引蝶N】数值类特性：二合一同样向下取整
                Math.floor(f.bee()),
                // 【滋养】是**概率**，不能 floor（0.25 → 0 会把整条词条抹掉）
                f.nurture());
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

    /** 这一串来源是不是全部同一个（用来判断「拼回去 = 原本那颗球」） */
    private static boolean allSame(List<Integer> indexes) {
        int first = indexes.get(0);
        for (int v : indexes) {
            if (v != first) {
                return false;
            }
        }
        return true;
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
        // 【作者指定】来源**全部相同**时，合出来的就是那颗**原本的实心球**，
        // 而不是拼出来的组合球 —— 两个同种半球拼回去 = 一颗完整的球。
        // 四个同种四分之一球同理。
        //
        // ⚠️ 判定必须在写组件**之前**：这一步返回的是原物品，不经过 combo_ball。
        if (indexes != null && !indexes.isEmpty() && allSame(indexes)) {
            int only = indexes.get(0);
            List<Item> all = sources();
            if (only >= 0 && only < all.size()) {
                MoreBalls.LOGGER.debug("[ball] 组合球：来源全相同（{}），直接还原成实心球", only);
                return new ItemStack(all.get(only));
            }
        }
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
     *
     * <p>键是来源下标串（如 {@code "0,2,3,5"}），可能的取值是 {@link #sources()} 里
     * <b>11 颗球</b>的二合一与四合一，数量有限但**没有硬上界**。
     * 加个 LRU 限住 —— 免得被数据包刷出一堆垃圾键。</p>
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
