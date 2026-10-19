package com.mcmodworkspace.moreballs;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的物品注册。
 *
 * <p>右键投掷、当弩弹药、坚固反弹等行为全部由标签 {@code #rotation_craft:balls} 驱动；
 * 这里只负责注册物品本体与**堆叠数**。
 * 每个物品的具体参数（重量 / 坚固 / 弹射 / 伤害 / 蓄力 / 稀有度 / 音效 / 掉落）在
 * {@link BallBehavior#init()} 里登记。</p>
 *
 * <h2>两个必须记住的注册要点</h2>
 *
 * <p><b>一、绝对不要给球加 {@code durability(n)}。</b>
 * MC 有一条硬校验：{@code Item cannot have both durability and be stackable}，
 * 加了它球就变成「最多一个」；而想用 {@code stacksTo()} 覆盖回来会让
 * 整个世界加载失败（踩过，见 CHANGELOG 1.20.8）。球的耐久走自定义组件，
 * 详见 {@link ModComponents}。</p>
 *
 * <p><b>二、雪球系是系列物品</b> —— 当前 {@link BallItem#snowballNewNaming} 为
 * {@code true}，显示名一律是「雪球-材料」，与内部 ID 的后缀对应：
 * {@code snowball_question} →「雪球-碎石」、{@code snowball_iron_nugget} →「雪球-铁粒」。
 * 旧格式「雪球？（材料）」只剩语言文件里 {@code .old} / {@code .old.short}
 * 那两套键，把开关翻成 {@code false} 才会用。</p>
 */
public final class ModItems {

    private ModItems() {
    }

    /** 球的最大堆叠数 */
    public static final int BALL_STACK_SIZE = 64;

    /** 物品注册器；在入口类里挂到 mod 事件总线 */
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MoreBalls.MOD_ID);

    // ===== 半球 / 四分之一球 / 组合球 —— 参考匠魂的「模板 + 组件」做法 =====
    //
    // 作者指定（2026-10-07，第二版规格）：**不为每种组合注册独立物品**，
    // 参考匠魂 —— 那里所有镐子都是同一件物品、材质由组件决定。
    // 这里照搬同一个思路：
    //   · 半球        1 件物品，组件记「是从哪颗球切的」
    //   · 四分之一球  1 件物品，同上
    //   · 组合球      1 件物品，组件记「由哪几颗球合成」（2 个或 4 个来源）
    //
    // 于是 5×2 + 10 + 65 = 85 种组合只占 **3 个物品 id**。
    // 物品的实际词条、名称、贴图全部由组件实时决定（见 BallFragments / BallTooltip）；
    // 创造模式与 JEI 里放的是**带组件的示例实例**，用来查配方与词条。

    /** 半球 —— 半成品，切石机从完整球上切下来，一颗出 2 个 */
    public static final DeferredItem<Item> BALL_HALF = ITEMS.registerItem(
            "ball_half", props -> new BallFragmentItem(props.stacksTo(BALL_STACK_SIZE), 2));

    /** 四分之一球 —— 半成品，一颗球出 4 个 */
    public static final DeferredItem<Item> BALL_QUARTER = ITEMS.registerItem(
            "ball_quarter", props -> new BallFragmentItem(props.stacksTo(BALL_STACK_SIZE), 4));

    /**
     * 组合球 —— 由 2 个半球或 4 个四分之一球无序合成而来。
     *
     * <p>它是**真正的球**（{@link BallItem}），能投掷、能当弩弹药；
     * 具体行为由组件里的来源列表实时算出的 {@link BallBehavior.BallProfile} 决定。</p>
     */
    public static final DeferredItem<Item> COMBO_BALL = ITEMS.registerItem(
            "combo_ball", props -> new ComboBallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 碎石 —— 材料，<b>不是球</b>，因此不在 {@code #rotation_craft:balls} 标签里。
     * 球被打碎时掉出来的渣，贴图是从圆石上抠下来的一块不规则碎片。
     */
    public static final DeferredItem<Item> RUBBLE = ITEMS.registerSimpleItem("rubble");

    /** 木球 —— 木板质感 + 史莱姆球轮廓；坚固 8、重量 5、木板音效 */
    public static final DeferredItem<Item> WOODEN_BALL = ITEMS.registerItem(
            "wooden_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /** 圆石球 —— 圆石质感；坚固 2、重量 6、弹射 2、伤害 7；60% 掉 1–2 碎石 */
    public static final DeferredItem<Item> COBBLESTONE_BALL = ITEMS.registerItem(
            "cobblestone_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 铁球 —— 铁块质感 + 史莱姆球轮廓；<b>坚固 99</b>、重量 7、弹射 3、伤害 11。
     * 铁砧落地音（音量压到 30%）。
     * 碎裂分四层掉铁：铁粒 10–30（必掉）、铁粒 10–50（80%）、铁锭 1–6（60%）、铁块 0–1（10%）。
     */
    public static final DeferredItem<Item> IRON_BALL = ITEMS.registerItem(
            "iron_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /** 空心铁球 —— 铁锭质感；坚固 20、重量 3、弹射 4、伤害 1；铁砧敲击音（音量 30%） */
    public static final DeferredItem<Item> HOLLOW_IRON_BALL = ITEMS.registerItem(
            "hollow_iron_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 「雪球-碎石」 —— 显示名走新命名格式（{@link BallItem#snowballNewNaming} 默认
     * {@code true}），旧格式名才是「雪球？（碎石）」；内部 ID {@code snowball_question}。
     *
     * <p>不坚固（碰上就碎）、重量 5、伤害 2；碎裂 10% 掉 1 个碎石。
     * 贴图与原版雪球一模一样，作用正是<b>在外观上冒充雪球</b>。</p>
     */
    public static final DeferredItem<Item> SNOWBALL_QUESTION =
            ITEMS.registerItem("snowball_question", BallItem::new);

    /**
     * 「雪球-铁粒」 —— 显示名同样走新命名格式，
     * 旧格式名才是「雪球？（铁粒）」；内部 ID {@code snowball_iron_nugget}。
     *
     * <p>不坚固、重量 5、伤害 3；碎裂 90% 掉 1–2 个铁粒。
     * 贴图与「雪球-碎石」完全一致 —— 从外观上无法区分，只能靠丢出去看效果。</p>
     *
     * <p>特殊能力「<b>探寻</b>」：扔出后会探测周围的金属矿物并累积热量，
     * 详见 {@code BallProspecting}。</p>
     */
    public static final DeferredItem<Item> SNOWBALL_IRON_NUGGET =
            ITEMS.registerItem("snowball_iron_nugget", BallItem::new);

    /**
     * 金球 —— 金块质感带高光，方块轮廓（史莱姆那种圆润方块感）。
     *
     * <p>伤害 8、重量 8、坚固 8、弹射 2、<b>蓄力只有 1 级</b>；音效是经验球的「叮」（音量 50%）。</p>
     *
     * <p>两个专属性质：<br>
     * <b>【变形】</b> 耐久耗尽后停在原地变成金块（变不了就掉金块）；<br>
     * <b>【金光闪闪】</b> 猪灵全套 —— 伤害不结仇、被落地声吸引、可被捡起并可特殊交易。</p>
     */
    public static final DeferredItem<Item> GOLD_BALL = ITEMS.registerItem(
            "gold_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 「雪球-金粒」 —— 不坚固、重量 5、伤害 2。
     *
     * <p>特殊能力「<b>点金</b>」：命中方块时把命中面那一片 3×3×1 的石头类方块
     * 按概率点成矿物，详见 {@code BallTransmute}。</p>
     */
    public static final DeferredItem<Item> SNOWBALL_GOLD_NUGGET =
            ITEMS.registerItem("snowball_gold_nugget", BallItem::new);

    // ===== 2.6.0 四颗新球（作者 2026-10-07 指定）=====

    /**
     * 紫水晶球 —— 伤害 5、坚固 10、重量 5、蓄力 4、弹射 7、权重 5。
     *
     * <p>两个专属性质：<br>
     * <b>【智慧】</b> 发射后扫描 10 格内无遮挡可直达的敌对 / 仇恨中立生物，锁定并转向；<br>
     * <b>【善良】</b> 碰到友好与中立生物时改为反弹，不结算伤害。</p>
     */
    public static final DeferredItem<Item> AMETHYST_BALL = ITEMS.registerItem(
            "amethyst_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 钻石球 —— 钻石块质感；伤害 15、坚固 250、重量 7、<b>弹性 0</b>、蓄力 3。
     *
     * <p>三个专属词条：<br>
     * <b>【穿透 3】</b> 自带穿透，能连着打穿三个目标；<br>
     * <b>【破坏王】</b> 撞碎接触到的方块，每碎一块速度降到 85%、扣 1 点耐久；<br>
     * <b>【透镜】</b> 白天晴天时给正下方 5×5×2 的方块与生物持续积热，够热就烧起来。</p>
     */
    public static final DeferredItem<Item> DIAMOND_BALL = ITEMS.registerItem(
            "diamond_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 红石球 —— 红石块质感；伤害 2、坚固 5、重量 5、<b>弹性 0</b>（不反弹）、蓄力 6。
     *
     * <p><b>【脉冲】</b>：命中时让周围「身着金属护甲或护甲值偏高」的生物晕头转向 ——
     * 施加【震荡】效果，装备越厚震得越久越重。详见 {@code RedstonePulse}。</p>
     *
     * <p>破碎 50% 掉 5–7 个红石粉；自然生成权重 5；音效是石头碎裂。</p>
     */
    public static final DeferredItem<Item> REDSTONE_BALL = ITEMS.registerItem(
            "redstone_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 铜球 —— 伤害 10、坚固 20、重量 7、权重 20，带【感应2】。
     *
     * <p><b>【导电】</b>：处于实体状态时，像避雷针一样让自然闪电优先击中自己，
     * 每次被劈消耗 1 点耐久。</p>
     */
    public static final DeferredItem<Item> COPPER_BALL = ITEMS.registerItem(
            "copper_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 空心铜球 —— 伤害 1、坚固 5、重量 2、弹射 3，带【感应2】。
     *
     * <p><b>【引雷120】</b>：热量攒到 120（{@link BallBehavior#THUNDER_THRESHOLD}）
     * 就释放一次雷电（4–9 道闪电）并清空自身热量。</p>
     */
    public static final DeferredItem<Item> HOLLOW_COPPER_BALL = ITEMS.registerItem(
            "hollow_copper_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 「青金石球」 —— 实心球，伤害 3、坚固 15、重量 6。
     *
     * <p><b>【魔法】</b>（作者 2026-10-10 指定）：命中目标时随机施加 1–20 秒、
     * 1–5 级的负面效果；被回收成功时对主人随机施加同范围的正面效果
     * （<b>不含本模组的【不灭】</b>）；另有 <b>0.1%</b> 概率抽中大奖，
     * 给主人挂 60 秒【不灭】并播放不死图腾的音效与粒子。详见 {@link BallMagic}。</p>
     *
     * <p>配方为 5 个青金石块（十字四臂 + 正中），一次出 4 个；
     * 碰撞与破碎都走附魔台音效；破碎 60% 掉 3–5 个青金石。</p>
     */
    public static final DeferredItem<Item> LAPIS_BALL = ITEMS.registerItem(
            "lapis_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 「潮汐球」 —— 伤害 8、重量 6、<b>坚固 300</b>、弹射 0（不反弹）、蓄力 3。
     *
     * <p>【潮涌】入水后不受重力、速度 ×3，并环绕激流的纹理特效（雨天不算）；
     * 【忠诚】永远带忠诚附魔，且不会被任何生物捡走。破碎<b>无掉落物</b>（作者指定）。</p>
     *
     * <p>配方（小键盘编号 7-8-9 / 4-5-6 / 1-2-3）：4/6/8/2 任意珊瑚块、
     * 9/7/1/3 任意珊瑚、5 潮涌核心 —— 9 格占满。</p>
     */
    public static final DeferredItem<Item> TIDAL_BALL = ITEMS.registerItem(
            "tidal_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 「雪球-红石」 —— 不坚固、重量 3、<b>伤害 0</b>、蓄力 6。
     *
     * <p><b>【照明】</b>：飞行时在正下方张开一个四棱锥判定区，区域内的生物持续被染色发光 ——
     * 友好蓝、中立黄、敌对红，对隐身与穿墙的目标同样有效。详见 {@code BallIlluminate}。</p>
     *
     * <p>飞行途中拖一条红色粒子尾迹；破碎 30% 掉 1–2 个红石粉。</p>
     */
    public static final DeferredItem<Item> REDSTONE_SNOWBALL =
            ITEMS.registerItem("redstone_snowball", BallItem::new);

    /**
     * 「雪球-铜粒」 —— 不坚固、重量 5、伤害 3，带【融化200】【感应2】。
     *
     * <p><b>【电击】</b>：这颗球直接造成的伤害为<b>闪电类型</b>。</p>
     */
    public static final DeferredItem<Item> SNOWBALL_COPPER_NUGGET = ITEMS.registerItem(
            "snowball_copper_nugget",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    // ===== 第十三批重做：四颗新球（作者 2026-10-15 指定）=====

    /**
     * 「绿宝石球」 —— 实心球，伤害 2、重量 6、坚固 20、弹射 3。
     *
     * <p><b>【敛财】</b>（「其它特殊」类）：击中时给被击中者挂【腰缠万贯】（10 秒）。</p>
     *
     * <p>贴图是<b>绿宝石块做成球</b>，音效是经验球的「叮」；
     * 配方 45682 摆绿宝石块（正十字五格），一次出 4 个。</p>
     */
    public static final DeferredItem<Item> EMERALD_BALL = ITEMS.registerItem(
            "emerald_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 「滴水石球」 —— 实心球，伤害 5、重量 7、坚固 2、弹射 0、蓄力 4。
     *
     * <p><b>【尖刺】</b>（「其它特殊」类）：击中、碰撞与破碎时，以垂直于速度的面为基准面，
     * 朝 8 个方向、与最终速度反方向夹角 60 度、最高点 2 格，射出 8 枚最小的滴水石锥，
     * 锥子基础伤害 3（其余按原版结算）。</p>
     *
     * <p>贴图是滴水石块做成球，音效是石头破碎；
     * 配方 5 格滴水石块 + 环形八格滴水石锥，一次出 1 个。</p>
     */
    public static final DeferredItem<Item> DRIPSTONE_BALL = ITEMS.registerItem(
            "dripstone_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 「蜜饯球」 —— 实心球，伤害 2、重量 4、蓄力 4（作者没说坚固 ⇒ 不坚固）。
     *
     * <p><b>【招蜂引蝶4】</b>（数值类特性）：击中时召唤 4 只对目标有仇恨、血量 5 的蜜蜂，
     * 并把目标的仇恨强制转移到它们身上。<br>
     * <b>【滋养】</b>（<b>组合类</b>特性词条）：完整球里携带但不生效，
     * 切开时按份数均分概率，合进组合球后 ≥1 份即生效 —— 回收成功时给主人 3 秒生命恢复。</p>
     *
     * <p>贴图主体是甜浆果截出的球体、空白处用蜂蜜的色块填上；音效是史莱姆；
     * 配方 4682甜浆果 + 9317蜂蜜瓶 + 5蜜脾，一次出 4 个。</p>
     */
    public static final DeferredItem<Item> PRESERVE_BALL = ITEMS.registerItem(
            "preserve_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 「回响球」 —— 幽匿主题（作者 2026-10-16 指定）。
     *
     * <p><b>数值</b>：伤害 10、重量 0、坚固 400、弹射 0、蓄力 10。
     * 重量 0 会让初速度抬得极高（{@code withWeight} 按重量重算速度），
     * 但它本来就<b>没有飞行过程</b>，所以这个速度只影响「朝哪边打出去」。</p>
     *
     * <p><b>【尖啸】</b>（「其它特殊」类，作者原话「好尖锐的声音（shift）大夫我有点不舒服」）：
     * 投出后<b>没有飞行过程</b> —— 无视路径上的生物、直接抵达终点撞墙停下，
     * 沿路生成一道监守者音波；路径半径 7 格内的生物吃 5 点伤害并被整套负面效果 + 击飞，
     * 落点半径 7 格内额外再吃 10 点并把部分效果升级。完整数值见 {@code BallExtraTraits} 的【尖啸】段。</p>
     *
     * <p><b>【忠诚】</b>（唯一特性类）、<b>【封印】</b>（「其它类」）也在它身上。</p>
     *
     * <p>贴图是幽匿块做的球、内部三个同心青色圆环代表声波（环色取自原版幽匿须贴图）；
     * 音效是监守者音波；配方 12369874回响碎片 + 5幽匿催发体，一次出 1 个。</p>
     */
    public static final DeferredItem<Item> ECHO_BALL = ITEMS.registerItem(
            "echo_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 「符箓球」 —— 空心球，伤害 1、重量 2、蓄力 2（作者没说坚固 ⇒ 不坚固）。
     *
     * <p><b>【封印】</b>（「其它特殊」类）：击中非盟友且非自身的玩家时，随机让该玩家
     * 快捷栏里的一件物品进入一次 3 秒冷却；击中怪物时强制它手里的<b>球</b>全部掉落
     * （原本就持有的弓 / 弩 / 剑这类不动），并封它 10 秒攻击 —— 期间不能攻击、也不能捡球。</p>
     *
     * <p>纹理是书与笔做成球（保留羽毛笔的轮廓），音效是附魔书翻开；
     * 配方 4682纸 + 5墨囊，一次出 4 个。</p>
     */
    public static final DeferredItem<Item> TALISMAN_BALL = ITEMS.registerItem(
            "talisman_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    // ===== 魔丸收纳袋：七个等级是七件独立物品 =====
    //
    // 作者指定（参考「精妙背包」的做法）：每个等级一件独立物品、各自一套贴图，
    // 外形轮廓一致、只有上半部分的材质随等级变化（下半始终是铁色）。
    //
    // 为什么不做成「一件物品 + 等级组件」：那样所有等级共用一个模型与贴图，
    // 外观区分只能靠物品名；而独立物品能让升级在视觉上一眼可见，
    // 也更贴合原版「工具材质升级」的观感。

    /** 1 级：皮革魔丸收纳袋（9 + 18 = 27 格） */
    public static final DeferredItem<Item> BALL_POUCH = registerPouch(BallPouchTier.LEATHER);

    /** 2 级：铁质 */
    public static final DeferredItem<Item> IRON_BALL_POUCH = registerPouch(BallPouchTier.IRON);

    /** 3 级：金质 */
    public static final DeferredItem<Item> GOLD_BALL_POUCH = registerPouch(BallPouchTier.GOLD);

    /** 4 级：绿宝石 */
    public static final DeferredItem<Item> EMERALD_BALL_POUCH = registerPouch(BallPouchTier.EMERALD);

    /** 5 级：钻石 */
    public static final DeferredItem<Item> DIAMOND_BALL_POUCH = registerPouch(BallPouchTier.DIAMOND);

    /** 6 级：黑曜石 */
    public static final DeferredItem<Item> OBSIDIAN_BALL_POUCH = registerPouch(BallPouchTier.OBSIDIAN);

    /** 7 级：下界合金（满级，27 + 54 = 81 格） */
    public static final DeferredItem<Item> NETHERITE_BALL_POUCH = registerPouch(BallPouchTier.NETHERITE);

    /**
     * <b>「球堆」</b>—— 见 {@link BallClusterItem}。
     *
     * <h2>为什么是 {@code stacksTo(1)}</h2>
     * <p>球堆内部存着一批球（{@link BallClusterContents}），而这些球的内容本身就是组件的一部分，
     * 所以两个球堆几乎不可能组件一致 —— <b>它们本来就叠不起来</b>。
     * 但这不影响它的目的：<b>一个球堆最多装 {@link BallClusterContents#MAX_BALLS} 颗，自己只占一格</b>。
     * （和潜影盒同理，装满的潜影盒之间也叠不了，但它本来就只占一格。）</p>
     *
     * <p>物品栏图标角落那个「内部球数」由 {@code BallClusterDecorator} 自己画 ——
     * 原版只给真的可堆叠物品画堆叠数字。</p>
     *
     * <h2>为什么它不会出现在创造栏和 JEI 里</h2>
     * <p>创造栏是<b>标签驱动</b>的（见 {@code ModCreativeTabs}：实时遍历注册表、只收
     * {@code #rotation_craft:balls} 标签的东西）。<b>本物品不挂那个标签</b>，
     * 于是它自动进不去 —— 一行代码都不用改。</p>
     *
     * <p>作者指定的定位：<b>它只是「减少物品栏占用」的措施</b>，
     * 不是给玩家直接拿的东西，玩家手上拿到的球堆一律是回收流程生成的。</p>
     */
    public static final DeferredItem<Item> BALL_CLUSTER =
            ITEMS.registerItem("ball_cluster", props -> new BallClusterItem(props.stacksTo(1)));

    /**
     * 注册一件收纳袋。
     *
     * <p>物品 id 就是等级的资源名（皮革那件叫 {@code ball_pouch}）。</p>
     *
     * <p><b>装了 Curios 就用带饰品能力的那个类，没装就用普通的。</b></p>
     *
     * <p>⚠️⚠️ <b>这里绝不能直接写 {@code new BallPouchCurioItem(...)} —— 哪怕包在三元判断里</b>
     * （作者 2026-10-17 关掉其它 mod 后游戏起不来，根因就是它）：</p>
     *
     * <pre>
     * java.lang.NoClassDefFoundError: top/theillusivec4/curios/api/type/capability/ICurioItem
     *     at com.mcmodworkspace.moreballs.MoreBalls.&lt;init&gt;(MoreBalls.java:24)
     * </pre>
     *
     * <p>原因不是运行时走了那条分支，而是 <b>JVM 字节码验证器</b>：{@code new BallPouchCurioItem(...)}
     * 的结果要赋给 {@code Item}，验证器就得确认「它是 {@code Item} 的子类」——
     * 这一确认就要**加载那个类**，而它 {@code implements ICurioItem}，Curios 不在就炸。
     * 运行时的 {@code isLoaded()} 判断在「类型解析」面前完全无效。</p>
     *
     * <p>所以真正的 {@code new} 挪进了 {@link BallPouchCurioFactory#create}（返回类型是 {@code Item}），
     * 本方法只在这个工厂外面套运行时判断 —— 没装 Curios 时那个类根本不会被加载。</p>
     */
    private static DeferredItem<Item> registerPouch(BallPouchTier tier) {
        return ITEMS.registerItem(tier.id(), props -> BallPouchCurios.isLoaded()
                ? BallPouchCurioFactory.create(props.stacksTo(1), tier)
                : new BallPouchItem(props.stacksTo(1), tier));
    }

    /** 按等级取对应的那件收纳袋 */
    public static Item pouchFor(BallPouchTier tier) {
        return switch (tier) {
            case LEATHER -> BALL_POUCH.get();
            case IRON -> IRON_BALL_POUCH.get();
            case GOLD -> GOLD_BALL_POUCH.get();
            case EMERALD -> EMERALD_BALL_POUCH.get();
            case DIAMOND -> DIAMOND_BALL_POUCH.get();
            case OBSIDIAN -> OBSIDIAN_BALL_POUCH.get();
            case NETHERITE -> NETHERITE_BALL_POUCH.get();
        };
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
