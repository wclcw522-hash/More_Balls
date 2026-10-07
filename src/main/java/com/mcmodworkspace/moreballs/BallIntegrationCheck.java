package com.mcmodworkspace.moreballs;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;

/**
 * <b>「加新球」的自检</b> —— 启动时跑一遍，把「忘了适配」当场报出来。
 *
 * <h2>为什么要有这个</h2>
 * <p>加一颗球要同时动好几处：注册物品、进 {@code #more_balls:balls} 标签、
 * 进 {@link BallFragments#sources()}（这样才切得动、才能进组合球）、
 * 补弩装填的模型 case、给贴图与模型文件。任何一处漏掉都不会报错，
 * 只会表现为「某个功能对这颗球不起作用」—— 而且往往要等到玩家实测才被发现。</p>
 *
 * <p>实测漏过两次：一次是新球没进 {@code balls} 标签，导致「不能扔也不能当弹药」；
 * 一次是新球没进 {@link BallFragments#sources()}，导致「切不动、也没弩装填图」
 * （作者 2026-10-08 报的）。</p>
 *
 * <h2>检查什么</h2>
 * <ul>
 *   <li><b>反向检查</b>（硬错）：{@code sources()} 里的每一颗球都必须在
 *       {@code #more_balls:balls} 标签里 —— 不在的话它切出来、合出来也不能当球用，
 *       属于配置矛盾。</li>
 *   <li><b>正向提示</b>（软警报）：标签里「看起来像球」但不在 {@code sources()} 里的物品。
 *       有些球是<b>故意</b>不参与组合球的（雪球系不参与切割、组合球自己是产物），
 *       所以这里只记 WARN 不报错，让人一眼扫到「是不是忘了登记」。</li>
 * </ul>
 *
 * <h2>⚠️ 这里绝对不能碰 ItemStack</h2>
 * <p>两个坑叠加导致过一次<b>启动崩溃</b>（作者 2026-10-08 报的）：</p>
 * <ol>
 *   <li>挂在 {@code FMLCommonSetupEvent} 上太早 —— 那时物品标签还没加载完；</li>
 *   <li>用 {@code new ItemStack(item).is(tag)} 判标签会直接抛
 *       {@code NullPointerException: Components not bound yet}，
 *       因为那时 {@code DataComponents} 还没绑定。</li>
 * </ol>
 * <p>所以这里一律用 {@link net.minecraft.core.Holder#is(net.minecraft.tags.TagKey)}
 * —— 它直接查物品的注册表持有者，不构造任何 ItemStack。钩子也挪到了
 * {@code FMLLoadCompleteEvent}（标签此时已就绪）。</p>
 *
 * <p>检查只在启动时跑一次，代价可以忽略，输出走 INFO/WARN，不会打断启动。</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallIntegrationCheck {

    /**
     * 服务端启动时跑自检 —— 只有这时物品标签才真正加载好了。
     *
     * <p>试过挂在 {@code FMLLoadCompleteEvent}（mod 总线）：标签仍是空的，
     * 自检会误报「sources() 里的球不在 balls 标签中」。</p>
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerAboutToStart(
            net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) {
        run();
    }

    private BallIntegrationCheck() {
    }

    /**
     * 刻意<b>不</b>参与组合球与切割的球。
     *
     * <p>列在这里就不会被正向检查报 WARN —— 每一条都要写清理由，
     * 否则这张表迟早变成「把报警静音」的地方。</p>
     */
    private static final List<String> INTENTIONALLY_EXCLUDED = List.of(
            // 雪球系四颗：作者指定不参与切割（它们本来就该是「一摔就碎」的雪球）
            "snowball_question",
            "snowball_iron_nugget",
            "snowball_gold_nugget",
            "snowball_copper_nugget",
            // 组合球自己是产物，不是部件
            "combo_ball");

    /** 跑一遍自检并把结果写进日志。在 {@code FMLCommonSetupEvent} 里调用。 */
    public static void run() {
        List<Item> sources = BallFragments.sources();

        // ===== 反向检查：sources() 里的球必须在 balls 标签里 =====
        List<String> sourcesNotInTag = new ArrayList<>();
        for (Item ball : sources) {
            if (!ball.builtInRegistryHolder().is(ModTags.Items.BALLS)) {
                sourcesNotInTag.add(name(ball));
            }
        }

        // ===== 正向检查：标签里像球、但没进 sources() 的 =====
        List<String> notRegistered = new ArrayList<>();
        for (Item item : BallFragments.ballsInTag()) {
            if (sources.contains(item)) {
                continue;
            }
            if (INTENTIONALLY_EXCLUDED.contains(name(item))) {
                continue;   // 故意不参与的，不算漏
            }
            notRegistered.add(name(item));
        }

        if (sourcesNotInTag.isEmpty() && notRegistered.isEmpty()) {
            MoreBalls.LOGGER.info("[ball] 新球适配自检通过：{} 颗组合球部件、{} 个 balls 标签成员，无遗漏",
                    sources.size(), BallFragments.ballsInTag().size());
            return;
        }

        if (!sourcesNotInTag.isEmpty()) {
            // 这个是硬矛盾：切得出来却扔不出去
            MoreBalls.LOGGER.warn("[ball] ★ 自检失败：以下球在 BallFragments.sources() 里，"
                    + "但不在 #more_balls:balls 标签中 —— 它们切出来的碎片合不成可用的球，"
                    + "请往 tags/item/balls.json 补上：{}", sourcesNotInTag);
        }
        if (!notRegistered.isEmpty()) {
            MoreBalls.LOGGER.warn("[ball] ★ 自检提示：以下物品在 #more_balls:balls 标签里，"
                    + "但没进 BallFragments.sources() —— 如果它们本该能切、能合组合球，"
                    + "就去补 sources()（顺序必须追加在末尾）；"
                    + "如果是刻意排除的（比如雪球系），把它们加进 BallIntegrationCheck."
                    + "INTENTIONALLY_EXCLUDED 并写明理由：{}", notRegistered);
        }
    }

    /** 取注册名（不含命名空间），日志里好认 */
    private static String name(Item item) {
        return item.builtInRegistryHolder().key().identifier().getPath();
    }
}
