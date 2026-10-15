package com.mcmodworkspace.moreballs;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 【腰缠万贯】—— <b>负面效果</b>（作者 2026-10-15 指定）。
 *
 * <h2>作者给的规格</h2>
 * <ul>
 *   <li>debuff</li>
 *   <li>持续时间内，效果持有者<b>每次受击</b>都有 <b>15%</b> 概率
 *       掉落<b>任意矿物产物</b>（矿锭、宝石、合金锭……）<b>1 个</b></li>
 *   <li><b>最大 1 级</b></li>
 * </ul>
 *
 * <h2>「矿物产物」怎么界定 —— 用通用标签，不写死物品</h2>
 * <p>作者要求「尽量写一个大类，让后续可能加入的 mod 矿物也包含在内」。
 * 所以这里<b>不枚举具体物品</b>，而是查 NeoForge / 社区约定的通用标签：</p>
 *
 * <ul>
 *   <li>{@code c:ingots} —— 矿锭（含各类合金锭）</li>
 *   <li>{@code c:gems} —— 宝石</li>
 *   <li>{@code c:nuggets} —— 粒</li>
 *   <li>{@code c:raw_materials} —— 原矿</li>
 * </ul>
 *
 * <p>只要别的模组把自己的矿物登记进这些标签（这是 NeoForge 的通行做法），
 * 它们就会自动进这个掉落池 —— 本模组不需要跟着改一行代码。</p>
 *
 * <p>标签为空时（极端精简的整合包）回退到一小撮原版物品，保证「至少会掉点什么」，
 * 而不是安静地什么都不发生。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public class MoneyEffect extends MobEffect {

    /** 英文 id（作者指定） */
    public static final String EFFECT_ID = "money";

    /**
     * 最大等级 —— 作者指定「最大 1 级」。
     *
     * <p>内部 amplifier 从 0 起算，所以上限是 {@code 0}。</p>
     */
    public static final int MAX_AMPLIFIER = 0;

    /** 每次受击掉落的概率（作者指定 15%） */
    public static final float DROP_CHANCE = 0.15F;

    /** 一次受击最多掉几个 —— 作者指定 1 个 */
    public static final int DROP_COUNT = 1;

    /**
     * 矿物产物的来源标签。
     *
     * <p>顺序无所谓，抽取时会先合成成一个候选列表再随机取。</p>
     */
    private static final List<TagKey<Item>> ORE_TAGS = List.of(
            commonTag("ingots"),
            commonTag("gems"),
            commonTag("nuggets"),
            commonTag("raw_materials"));

    private static TagKey<Item> commonTag(String path) {
        return TagKey.create(Registries.ITEM,
                Identifier.fromNamespaceAndPath("c", path));
    }

    /**
     * 标签全空时的兜底池。
     *
     * <p>正常整合包里用不到 —— 但「整合包把 c: 标签全删了」这种事真发生过，
     * 那时候效果会变成「挂着但永不触发」，看起来像 bug。这里给它一个底。</p>
     */
    private static final List<Item> FALLBACK = List.of(
            net.minecraft.world.item.Items.IRON_INGOT,
            net.minecraft.world.item.Items.GOLD_INGOT,
            net.minecraft.world.item.Items.COPPER_INGOT,
            net.minecraft.world.item.Items.DIAMOND,
            net.minecraft.world.item.Items.EMERALD,
            net.minecraft.world.item.Items.LAPIS_LAZULI,
            net.minecraft.world.item.Items.REDSTONE,
            net.minecraft.world.item.Items.COAL);

    public MoneyEffect() {
        // 负面效果；颜色只是药水粒子/图标底色，真正的图标走 textures/mob_effect/money.png
        super(MobEffectCategory.HARMFUL, 0x2ECC71);
    }

    /**
     * 持有者受击时掷一次 —— 中了就掉一个矿物产物。
     *
     * <p>用 {@link LivingIncomingDamageEvent}（伤害<b>落下来之前</b>）而不是伤害结算后：
     * 这样即使这一击把持有者打死了，东西也已经掉出来了。</p>
     *
     * <p>只在服务端做 —— 掉落物是服务端行为，客户端跑一遍会重复生成客户端幽灵物品。</p>
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) {
            return;
        }
        if (!victim.hasEffect(ModEffects.MONEY)) {
            return;
        }
        if (victim.getRandom().nextFloat() >= DROP_CHANCE) {
            return;
        }

        ItemStack drop = pickOre(victim);
        if (drop.isEmpty()) {
            return;
        }
        drop.setCount(DROP_COUNT);

        // 从受害者身上「掉出来」——位置略高一点，免得卡在脚方块里
        ItemEntity entity = new ItemEntity(victim.level(),
                victim.getX(), victim.getY() + 0.4D, victim.getZ(), drop);
        entity.setDefaultPickUpDelay();
        // 给一点随机初速，看起来像被打掉的
        entity.setDeltaMovement(
                (victim.getRandom().nextDouble() - 0.5D) * 0.15D,
                0.2D,
                (victim.getRandom().nextDouble() - 0.5D) * 0.15D);
        victim.level().addFreshEntity(entity);
    }

    /**
     * 从「矿物大类」里随机抽一个物品。
     *
     * <p>先查通用标签（这样能覆盖别的模组加的矿物）；一个都没有时退回兜底池。</p>
     */
    private static ItemStack pickOre(LivingEntity victim) {
        List<Item> pool = new ArrayList<>();
        var registries = victim.level().registryAccess();
        for (TagKey<Item> tag : ORE_TAGS) {
            var holders = registries.lookupOrThrow(Registries.ITEM).get(tag);
            if (holders.isEmpty()) {
                continue;
            }
            for (var holder : holders.get()) {
                Item item = holder.value();
                if (item != net.minecraft.world.item.Items.AIR) {
                    pool.add(item);
                }
            }
        }
        if (pool.isEmpty()) {
            pool = FALLBACK;
        }
        if (pool.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(pool.get(victim.getRandom().nextInt(pool.size())));
    }

    // ===== 关于「最大 1 级」=====
    //
    // ⚠️ 26.2 的 `MobEffect` **没有** `getMaxAmplifier()` 这种可覆写的等级上限钩子
    // （我按旧版本印象写过一次，编译直接报「方法不会覆盖或实现超类型的方法」）。
    //
    // 所以「最大 1 级」是在**施加的那一侧**保证的：【敛财】挂效果时一律用
    // `amplifier = 0`，且只在目标还没有该效果时施加 —— 于是它在游戏里只会是 1 级。
}
