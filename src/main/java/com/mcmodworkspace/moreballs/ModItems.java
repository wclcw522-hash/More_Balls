package com.mcmodworkspace.moreballs;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的物品注册。
 *
 * <p>右键投掷、当弩弹药、坚固反弹等行为全部由标签 {@code #more_balls:balls} 驱动；
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
 * <p><b>二、「雪球？」是系列物品</b> —— 显示名一律是「雪球？」（问号是名字的一部分），
 * 内部 ID 与括号里的内容对应，例如 {@code snowball_question}（碎石）、
 * {@code snowball_iron_nugget}（铁粒）。</p>
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
     * 碎石 —— 材料，<b>不是球</b>，因此不在 {@code #more_balls:balls} 标签里。
     * 球被打碎时掉出来的渣，贴图是从圆石上抠下来的一块不规则碎片。
     */
    // ===== 仅用于配方展示的「示例」物品 =====
    //
    // 这四个**故意不进创造模式、也没有任何配方**，唯一用途是当 JEI / 配方书里的展示样本：
    //   · 切石配方要给人看「输入一颗球 → 得到半球/四分之一球」
    //   · 组合球配方要给人看「两个半球摆上下 → 合成组合球」
    // 用真的 ball_half / combo_ball 去展示也行，但它们带组件（fragment_source / combo_slots），
    // 在配方页里显示的是「某一个具体的球」，而配方其实接受**整个子分类**。
    // 所以另开四个不带组件的纯展示物品，语义上就是「（示例）」。
    //
    // ⚠️ 加物品时**不要**把它们塞进创造标签，也不要给配方 —— 那会让它们变成可获取物品。

    /** 【示例】半球 —— 仅用于配方展示 */
    public static final DeferredItem<Item> EXAMPLE_HALF =
            ITEMS.registerSimpleItem("example_half");

    /** 【示例】四分之一球 —— 仅用于配方展示 */
    public static final DeferredItem<Item> EXAMPLE_QUARTER =
            ITEMS.registerSimpleItem("example_quarter");

    /** 【示例】组合球（二合一）—— 仅用于配方展示 */
    public static final DeferredItem<Item> EXAMPLE_COMBO_VERTICAL =
            ITEMS.registerSimpleItem("example_combo_vertical");

    /** 【示例】组合球（四合一）—— 仅用于配方展示 */
    public static final DeferredItem<Item> EXAMPLE_COMBO_SQUARE =
            ITEMS.registerSimpleItem("example_combo_square");
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
     * 「雪球？（碎石）」 —— 显示名「雪球？」（问号是名字的一部分），
     * 隐藏名称「雪球-碎石」，内部 ID {@code snowball_question}。
     *
     * <p>不坚固（碰上就碎）、重量 5、伤害 2；碎裂 10% 掉 1 个碎石。
     * 贴图与原版雪球一模一样，作用正是<b>在外观上冒充雪球</b>。</p>
     */
    public static final DeferredItem<Item> SNOWBALL_QUESTION =
            ITEMS.registerItem("snowball_question", BallItem::new);

    /**
     * 「雪球？（铁粒）」 —— 显示名同样是「雪球？」，
     * 隐藏名称「雪球-铁粒」，内部 ID {@code snowball_iron_nugget}。
     *
     * <p>不坚固、重量 5、伤害 3；碎裂 90% 掉 1–2 个铁粒。
     * 贴图与「雪球？（碎石）」完全一致 —— 从外观上无法区分，只能靠丢出去看效果。</p>
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
     * 「雪球_金粒」 —— 不坚固、重量 5、伤害 2。
     *
     * <p>特殊能力「<b>点金</b>」：命中方块时把命中面那一片 3×3×1 的石头类方块
     * 按概率点成矿物，详见 {@code BallTransmute}。</p>
     */
    public static final DeferredItem<Item> SNOWBALL_GOLD_NUGGET =
            ITEMS.registerItem("snowball_gold_nugget", BallItem::new);

    // ===== 2.6.0 四颗新球（作者 2026-10-07 指定）=====

    /**
     * 紫水晶球 —— 伤害 5、坚固 15、重量 5、蓄力 4、弹射 7、权重 5。
     *
     * <p>两个专属性质：<br>
     * <b>【智慧】</b> 发射后扫描 10 格内无遮挡可直达的敌对 / 仇恨中立生物，锁定并转向；<br>
     * <b>【善良】</b> 碰到友好与中立生物时改为反弹，不结算伤害。</p>
     */
    public static final DeferredItem<Item> AMETHYST_BALL = ITEMS.registerItem(
            "amethyst_ball",
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
     * <p><b>【引雷300】</b>：热量攒到 300 就释放一次雷电（4–9 道闪电）并清空自身热量。</p>
     */
    public static final DeferredItem<Item> HOLLOW_COPPER_BALL = ITEMS.registerItem(
            "hollow_copper_ball",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    /**
     * 「雪球_铜粒」 —— 不坚固、重量 5、伤害 3，带【融化200】【感应2】。
     *
     * <p><b>【电击】</b>：这颗球直接造成的伤害为<b>闪电类型</b>。</p>
     */
    public static final DeferredItem<Item> SNOWBALL_COPPER_NUGGET = ITEMS.registerItem(
            "snowball_copper_nugget",
            props -> new BallItem(props.stacksTo(BALL_STACK_SIZE)));

    // ===== 魔丸收纳袋：七个等级是七件独立物品 =====
    //
    // 作者指定（参考「精妙背包」的做法）：每个等级一件独立物品、各自一套贴图，
    // 外形轮廓一致、只有上半部分的材质随等级变化（下半始终是铁色）。
    //
    // 为什么不做成「一件物品 + 等级组件」：那样所有等级共用一个模型与贴图，
    // 外观区分只能靠物品名；而独立物品能让升级在视觉上一眼可见，
    // 也更贴合原版「工具材质升级」的观感。

    /** 1 级：皮革魔丸收纳袋（9 + 9 = 18 格） */
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

    /** 7 级：下界合金（满级，27 + 27 = 54 格） */
    public static final DeferredItem<Item> NETHERITE_BALL_POUCH = registerPouch(BallPouchTier.NETHERITE);

    /**
     * 注册一件收纳袋。
     *
     * <p>物品 id 就是等级的资源名（皮革那件叫 {@code ball_pouch}）。</p>
     *
     * <p><b>装了 Curios 就用带饰品能力的那个类，没装就用普通的</b> ——
     * {@code BallPouchCurioItem} 引用了 Curios 的接口，所以它只有 Curios 在场时才能加载。
     * 三元表达式在 false 分支上不会构造它，JVM 也就不会去解析那个类，
     * 于是没装 Curios 的玩家完全不受影响。这是「第三方依赖只用 compileOnly + 运行时判断」
     * 那条规范的具体落地。</p>
     */
    private static DeferredItem<Item> registerPouch(BallPouchTier tier) {
        return ITEMS.registerItem(tier.id(), props -> BallPouchCurios.isLoaded()
                ? new BallPouchCurioItem(props.stacksTo(1), tier)
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
