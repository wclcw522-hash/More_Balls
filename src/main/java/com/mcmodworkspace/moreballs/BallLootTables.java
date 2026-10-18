package com.mcmodworkspace.moreballs;

import com.mojang.serialization.MapCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.LootTableLoadEvent;

import java.util.List;

/**
 * <b>战利品箱里生成球</b>（作者 2026-10-18 指定）。
 *
 * <h2>「战利品概率」与「生成概率」是两件事</h2>
 * <ul>
 *   <li><b>生成概率</b> = {@code BallProfile.rarity()}，管<b>刷在怪手里</b>
 *       （见 {@code BallBehavior.randomBall()}）；</li>
 *   <li><b>战利品概率</b> = 本类，管<b>开箱子开出来</b>。两套完全独立。</li>
 * </ul>
 *
 * <h2>七条规则（逐条照作者原话）</h2>
 * <table border="1">
 *   <caption>战利品规则</caption>
 *   <tr><th>球</th><th>出现条件</th><th>概率</th><th>数量</th><th>尝试</th></tr>
 *   <tr><td>铁球</td><td>原本会生成铁锭的箱子</td><td>该箱铁锭概率 × 30%</td><td>1–2</td><td>3</td></tr>
 *   <tr><td>金球</td><td>猪灵堡垒的箱子</td><td>20%</td><td>1–2</td><td>3</td></tr>
 *   <tr><td>潮汐球</td><td>藏宝图对应的箱子</td><td>30%，<b>代替海洋之心</b></td><td>1</td><td>1</td></tr>
 *   <tr><td>钻石球</td><td>原本会生成钻石的箱子</td><td>该箱钻石概率 × 30%</td><td>1</td><td>3</td></tr>
 *   <tr><td>回响球</td><td>远古城市的箱子</td><td>2%</td><td>1–2</td><td>2</td></tr>
 *   <tr><td>青金石球</td><td>原本会生成青金石的箱子</td><td>10%</td><td>1–2</td><td>2</td></tr>
 *   <tr><td>紫水晶球</td><td>y &lt; 30 的箱子</td><td>20%</td><td>1–4</td><td>3</td></tr>
 * </table>
 *
 * <h2>「该箱 X 概率 × 30%」怎么算</h2>
 * <p>铁球与钻石球要<b>跟随原表里对应物料的概率</b>，所以在这个事件里<b>反射读原表权重</b>：
 * 查过 26.2 源码，{@code LootTable.pools} 与 {@code LootPool.entries} 都是 private，
 * 只能反射取；拿到「条目权重 ÷ 所在池的总权重」当它在该池的概率，
 * 多个池都含它就取<b>最大值</b>（对玩家最有利的解读），再乘 30%。</p>
 *
 * <p>⚠️ 反射<b>全程兜底</b>：任何一步失败只是「这条规则这次不加」，绝不让战利品生成崩掉。</p>
 *
 * <h2>怎么挂上去的（⚠️ 这里是踩过的坑）</h2>
 * <p>第一版我以为 {@code LootTableLoadEvent} 是 mod 总线事件，在 {@code MoreBalls} 构造器里
 * 用 {@code modEventBus.addListener(...)} 挂 —— <b>一跑就崩</b>：</p>
 *
 * <pre>
 * java.lang.IllegalArgumentException: Listener for event class
 *   net.neoforged.neoforge.event.LootTableLoadEvent takes an argument that is not valid for this bus
 * Caused by: This bus only accepts subclasses of IModBusEvent,
 *   which class LootTableLoadEvent is not.
 * </pre>
 *
 * <p><b>它是 GAME 总线事件</b>（{@code NeoForge.EVENT_BUS}），不是 {@code IModBusEvent}。
 * 所以改用 {@code @EventBusSubscriber(modid = ...)}（默认就是 GAME 总线）+ {@code @SubscribeEvent}。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)   // LootTableLoadEvent 是 GAME 总线事件（不是 IModBusEvent）
public final class BallLootTables {

    private BallLootTables() {
    }

    /** 各规则的尝试次数（= 往表里加几个 roll） */
    private static final int IRON_TRIES = 3;
    private static final int GOLD_TRIES = 3;
    private static final int DIAMOND_TRIES = 3;
    private static final int ECHO_TRIES = 2;
    private static final int LAPIS_TRIES = 2;
    private static final int AMETHYST_TRIES = 3;

    /** 固定概率的几条（铁/钻石要跟随原表，不在这里） */
    private static final float GOLD_CHANCE = 0.20F;
    private static final float TIDAL_CHANCE = 0.30F;
    private static final float ECHO_CHANCE = 0.02F;
    private static final float LAPIS_CHANCE = 0.10F;
    private static final float AMETHYST_CHANCE = 0.20F;

    /** 「跟随原表」的折算比例 */
    private static final float FOLLOW_RATIO = 0.30F;

    /** 紫水晶球只在 y &lt; 30 的箱子里出现 */
    private static final int AMETHYST_MAX_Y = 30;

    /**
     * 战利品表加载时调用 —— 往符合条件的表里加「球的池」。
     *
     * <p>整体包在 {@code try/catch Throwable} 里：战利品加载是启动路径上的事，
     * 绝不能因为本模组让整个世界加载失败。出问题就当这些规则不存在。</p>
     */
    @SubscribeEvent
    public static void onLootTableLoad(LootTableLoadEvent event) {
        try {
            final Identifier id = event.getName();
            final String path = id == null ? "" : id.getPath();

            // ① 铁球：跟随该箱铁锭概率的 30%，1–2 个，试 3 次
            final float ironProb = followItemChance(event, "minecraft:iron_ingot");
            if (ironProb > 0.0F) {
                addBallPool(event, "iron", ModItems.IRON_BALL.get(),
                        ironProb * FOLLOW_RATIO, 1, 2, IRON_TRIES, null);
            }

            // ② 钻石球：跟随该箱钻石概率的 30%，1 个，试 3 次
            final float diamondProb = followItemChance(event, "minecraft:diamond");
            if (diamondProb > 0.0F) {
                addBallPool(event, "diamond", ModItems.DIAMOND_BALL.get(),
                        diamondProb * FOLLOW_RATIO, 1, 1, DIAMOND_TRIES, null);
            }

            // ③ 青金石球：该箱本来会出青金石，概率固定 10%，1–2 个，试 2 次
            if (followItemChance(event, "minecraft:lapis_lazuli") > 0.0F) {
                addBallPool(event, "lapis", ModItems.LAPIS_BALL.get(),
                        LAPIS_CHANCE, 1, 2, LAPIS_TRIES, null);
            }

            // ④ 金球：猪灵堡垒的箱子（20%），1–2 个，试 3 次
            if (path.startsWith("bastion_")) {
                addBallPool(event, "gold", ModItems.GOLD_BALL.get(),
                        GOLD_CHANCE, 1, 2, GOLD_TRIES, null);
            }

            // ⑤ 潮汐球：藏宝图对应的箱子（30%），代替海洋之心 ⇒ 数量 1、试 1 次
            if (path.equals("buried_treasure")) {
                addBallPool(event, "tidal", ModItems.TIDAL_BALL.get(),
                        TIDAL_CHANCE, 1, 1, 1, null);
            }

            // ⑥ 回响球：远古城市的箱子（2%），1–2 个，试 2 次
            if (path.startsWith("ancient_city")) {
                addBallPool(event, "echo", ModItems.ECHO_BALL.get(),
                        ECHO_CHANCE, 1, 2, ECHO_TRIES, null);
            }

            // ⑦ 紫水晶球：y < 30 的箱子（20%），1–4 个，试 3 次
            addBallPool(event, "amethyst", ModItems.AMETHYST_BALL.get(),
                    AMETHYST_CHANCE, 1, 4, AMETHYST_TRIES, YBelowCondition.yBelow(AMETHYST_MAX_Y));
        } catch (Throwable ignored) {
            // 见方法注释：宁可这条规则不加，也不能让战利品加载失败
        }
    }

    /**
     * 往一张表里加一个「球的池」。
     *
     * @param key       池名后缀（同名只加一次，防资源重载重复添加）
     * @param ball      要掉的那颗球
     * @param chance    每次尝试的命中概率
     * @param min       数量下限
     * @param max       数量上限
     * @param tries     尝试次数（= rolls）
     * @param extraWhen 额外条件（紫水晶的 y 判定），可为 {@code null}
     */
    private static void addBallPool(LootTableLoadEvent event, String key, Item ball,
                                    float chance, int min, int max, int tries,
                                    LootItemCondition.Builder extraWhen) {
        if (chance <= 0.0F || tries <= 0) {
            return;
        }
        final String poolName = "more_balls_ball_" + key;
        if (event.getTable().getPool(poolName) != null) {
            return;   // 已经加过了
        }
        final LootPool.Builder pool = LootPool.lootPool();
        pool.setRolls(ConstantValue.exactly(tries));
        LootItem.Builder<?> entry = LootItem.lootTableItem(ball);
        if (max > min) {
            entry = entry.apply(SetItemCountFunction.setCount(UniformGenerator.between(min, max)));
        }
        // 「每次尝试 p 概率」—— 池内只有一个条目，概率完全由这个条件门控
        entry = entry.when(LootItemRandomChanceCondition.randomChance(chance));
        if (extraWhen != null) {
            entry = entry.when(extraWhen);
        }
        pool.add(entry);
        pool.name(poolName);
        event.getTable().addPool(pool.build());
    }

    // ===== 「跟随原表概率」：反射读权重 =====

    /**
     * 该箱子里<b>某个物品出现的概率</b>（多个池都含它时取最大值）。
     *
     * @return 0 表示这张表里没有该物品，或反射失败
     */
    private static float followItemChance(LootTableLoadEvent event, String itemId) {
        try {
            final List<?> pools = (List<?>) getField(event.getTable(), "pools");
            if (pools == null) {
                return 0.0F;
            }
            final Identifier want = Identifier.parse(itemId);
            float best = 0.0F;
            for (Object poolObj : pools) {
                final float total = totalWeight(poolObj);
                if (total <= 0.0F) {
                    continue;
                }
                final List<?> entries = (List<?>) getField(poolObj, "entries");
                if (entries == null) {
                    continue;
                }
                for (Object entryObj : entries) {
                    if (!want.equals(itemOf(entryObj))) {
                        continue;
                    }
                    final float w = weightOf(entryObj);
                    if (w > 0.0F) {
                        best = Math.max(best, w / total);
                    }
                }
            }
            return best;
        } catch (Throwable ignored) {
            return 0.0F;   // 反射失败 ⇒ 这条规则这次不加
        }
    }

    /** 池的总权重（把条目权重加起来，比去碰私有方法稳） */
    private static float totalWeight(Object poolObj) {
        final List<?> entries = (List<?>) getField(poolObj, "entries");
        if (entries == null) {
            return 0.0F;
        }
        float sum = 0.0F;
        for (Object e : entries) {
            sum += Math.max(0.0F, weightOf(e));
        }
        return sum;
    }

    /** 条目权重（原版字段名 {@code weight}） */
    private static float weightOf(Object entryObj) {
        final Object w = getField(entryObj, "weight");
        return (w instanceof Number n) ? n.floatValue() : 0.0F;
    }

    /**
     * 条目的物品 —— 只有 {@code LootItem} 才有 {@code item} 字段。
     *
     * <p>⚠️⚠️ <b>{@code LootItem.item} 的类型是 {@code Holder<Item>}，不是 {@code Item}</b>
     * （查 26.2 源码确认：{@code private final Holder<Item> item;}）。
     * 第一版我按 {@code instanceof Item} 判，结果<b>恒为 false</b> ——
     * 铁球和钻石球这两条「跟随原表概率」的规则会<b>静默失效</b>（不报错、就是永远不加），
     * 这种错没实测根本看不出来。所以这里必须按 Holder 取值。</p>
     */
    private static Identifier itemOf(Object entryObj) {
        final Object raw = getField(entryObj, "item");
        if (raw instanceof net.minecraft.core.Holder<?> holder && holder.value() instanceof Item it) {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it);
        }
        // 兜底：万一某个版本把它存成裸 Item
        if (raw instanceof Item it) {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it);
        }
        return null;
    }

    /** 沿继承链找字段并取值；找不到 / 取不到都返回 {@code null} */
    private static Object getField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                final java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    // ===== 自定义条件：位置 y 低于 N =====

    /**
     * <b>「箱子位置 y &lt; N」条件</b> —— 紫水晶球专用（作者指定「y 值在 30 以下的箱子」）。
     *
     * <p>原版 {@code LocationCheck} 也能表达 y 范围，但要绕 {@code LocationPredicate}
     * 的 builder 并给 position 偏移；这里直接读 {@link LootContext} 的 {@code ORIGIN}
     * 比一下 y，直白且行为可控（拿不到位置就判否）。</p>
     *
     * <p>26.2 的 {@code LootItemCondition} 只需要实现 {@code codec()}，
     * <b>不需要注册条件类型</b>（{@code LootItemConditionType} 这个类在这个版本已经没有了）。
     * 本条件只在代码里构造，所以 {@code codec()} 给一个 {@link MapCodec#unit} 占位即可。</p>
     */
    public record YBelowCondition(int maxY) implements LootItemCondition {

        /** 给 {@code when(...)} 用的 Builder（{@code LootItemCondition.Builder} 是函数式接口） */
        public static LootItemCondition.Builder yBelow(int maxY) {
            return () -> new YBelowCondition(maxY);
        }

        @Override
        public MapCodec<? extends LootItemCondition> codec() {
            return MapCodec.unit(this);
        }

        @Override
        public boolean test(LootContext context) {
            try {
                final net.minecraft.world.phys.Vec3 origin = context.getOptionalParameter(
                        net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN);
                // 拿不到位置（钓鱼、怪物掉落之类）⇒ 不算箱子，不发光水晶
                return origin != null && origin.y < this.maxY;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
