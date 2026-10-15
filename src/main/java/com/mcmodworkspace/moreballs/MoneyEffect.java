package com.mcmodworkspace.moreballs;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 【腰缠万贯】—— <b>负面效果</b>（作者 2026-10-15 指定，第十三批）。
 *
 * <h2>作者给的规格</h2>
 * <ul>
 *   <li>debuff，<b>最大 1 级</b></li>
 *   <li>持续时间内，效果持有者<b>每次受击</b>都有 <b>15%</b> 概率
 *       掉落「任意矿物产物」<b>1 个</b></li>
 *   <li>图标用作者给的那张图（保留外围闪光、去掉水印），18 × 18 放
 *       {@code textures/mob_effect/money.png}</li>
 * </ul>
 *
 * <h2>「矿物产物」怎么界定 —— 一条标签，第三方可以往里加</h2>
 * <p>作者要求「尽量直接写一个大类，让后续可能加入的 mod 矿物也包含在内」。
 * 所以这里查的是<b>本模组自己的一条物品标签</b>：
 * {@code #more_balls:mineral_products}，而那条标签的默认内容引用了 NeoForge 的
 * 通用矿物标签（{@code #c:ingots} / {@code #c:gems} / {@code #c:nuggets} /
 * {@code #c:raw_materials}）再加上原版那几样。<b>别人家的模组只要把自己的矿物登记进
 * {@code c:} 那一套（NeoForge 的通行做法），或者用数据包往我们的标签里加，
 * 它就自动进掉落池 —— 本模组一行代码都不用改。</b></p>
 *
 * <p>标签整条为空时（极端精简的整合包）回退到一小撮原版物品，
 * 保证「至少会掉点什么」，而不是安静地什么都不发生。</p>
 *
 * <h2>为什么挂在「伤害落下之前」</h2>
 * <p>用 {@link LivingIncomingDamageEvent}：这样即使这一击把持有者打死了，
 * 东西也已经掉出来了（作者要的是「每次受击」而不是「每次活下来」）。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public class MoneyEffect extends MobEffect {

    /** 英文 id（本模组自定，与作者给的图标文件 money.png 同名） */
    public static final String EFFECT_ID = "money";

    /**
     * 最大等级：<b>1 级</b>（作者指定）。
     *
     * <p>⚠️ 26.2 的 {@code MobEffect} <b>没有</b>「等级上限」这种可覆写的钩子
     * （按旧版本印象写过一次，编译直接报「方法不会覆盖或实现超类型的方法」）。
     * 所以「最大 1 级」是在<b>施加的那一侧</b>保证的：{@link BallExtraTraits#applyMoney}
     * 一律用 {@code amplifier = 0}，并且只在目标没有该效果、或剩余时长更短时才写。
     * 命令 {@code /effect give} 绕开这一层，但那是玩家自己在作弊面板上做的事。</p>
     */
    public static final int MAX_AMPLIFIER = 0;

    /** 每次受击掉落的概率（作者指定 15%） */
    public static final float DROP_CHANCE = 0.15F;

    /** 一次受击掉几个（作者指定 1 个） */
    public static final int DROP_COUNT = 1;

    /**
     * <b>矿物产物</b>的大类标签 —— 见类注释。
     *
     * <p>定义文件：{@code data/more_balls/tags/item/mineral_products.json}</p>
     */
    public static final TagKey<Item> MINERAL_PRODUCTS = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "mineral_products"));

    /**
     * 标签为空时的兜底池。
     *
     * <p>正常整合包里用不到 —— 但「整合包把 {@code c:} 标签整个删掉」这种事真发生过，
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
        // 负面效果；颜色同时决定粒子色与 HUD 图标外框的染色（真正的图标走 money.png）
        super(MobEffectCategory.HARMFUL, 0x2ECC71);
    }

    /**
     * 持有者受击时掷一次 —— 中了就掉一个矿物产物。
     *
     * <p>只在服务端做：掉落物是服务端行为，客户端跑一遍只会生成幽灵物品。</p>
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

        // 从受害者身上「掉出来」——位置略高一点，免得卡进脚下方块
        ItemEntity entity = new ItemEntity(victim.level(),
                victim.getX(), victim.getY() + 0.4D, victim.getZ(), drop);
        entity.setDefaultPickUpDelay();
        // 给一点随机初速，看起来像被打掉的
        entity.setDeltaMovement(new Vec3(
                (victim.getRandom().nextDouble() - 0.5D) * 0.15D,
                0.2D,
                (victim.getRandom().nextDouble() - 0.5D) * 0.15D));
        victim.level().addFreshEntity(entity);
    }

    /** 从「矿物大类」里随机抽一个物品（先查标签，标签空则用兜底池） */
    private static ItemStack pickOre(LivingEntity victim) {
        List<Item> pool = new ArrayList<>();
        for (var holder : BuiltInRegistries.ITEM.getTagOrEmpty(MINERAL_PRODUCTS)) {
            Item item = holder.value();
            if (item != net.minecraft.world.item.Items.AIR) {
                pool.add(item);
            }
        }
        if (pool.isEmpty()) {
            pool = FALLBACK;
        }
        ItemStack stack = new ItemStack(pool.get(victim.getRandom().nextInt(pool.size())));
        stack.setCount(DROP_COUNT);
        return stack;
    }
}
