package com.mcmodworkspace.moreballs;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 球的词条文本 —— <b>物品提示与径向菜单共用同一份生成逻辑</b>。
 *
 * <h2>两层显示（作者 2026-10-07 定的）</h2>
 * <ul>
 *   <li><b>常显</b>：只给词条名（{@code 【坚固3】}、{@code 【感应】} …）——
 *       提示清爽，一眼扫得完</li>
 *   <li><b>Shift 展开</b>：在词条下面<b>追加一行说明</b>（见 {@link Entry#detail()}）——
 *       机制细节收在这里，不占常显的位置</li>
 * </ul>
 *
 * <p>有些词条的常显文本本身带一句短描述（例如 {@code 【磁性】芜——吸走了}），
 * 那是刻意的：词条名之外只留一句调侃，正经解释仍然收在 Shift 里。</p>
 *
 * <h2>为什么单独抽出来</h2>
 * <p>径向菜单要显示「基础属性」和「特性」，那就不能把这条链抄第二份 ——
 * 抄出来的那份迟早和提示里对不上，而玩家会立刻发现两处说法不一致。</p>
 */
public final class BallTooltip {

    /** 词条归属：基础属性 / 特性 / 蓄力 */
    public enum Kind {
        BASIC,
        TRAIT,
        /**
         * 蓄力 —— 物品提示里照旧显示，但<b>径向菜单里不显示</b>：
         * 轮盘里挑的球都是拿弩打出去的，蓄力那条在那儿没有意义。
         */
        CHARGE
    }

    /**
     * 一条词条。
     *
     * @param text   常显部分：词条名（可带数值），例如 {@code 【坚固3】}
     * @param kind   归属类别，径向菜单按它分层
     * @param detail 只在<b>按住 Shift</b> 时追加显示的说明；{@code null} 表示这条没有说明
     */
    public record Entry(Component text, Kind kind, Component detail) {

        /** 不带 Shift 说明的词条 */
        public static Entry of(Component text, Kind kind) {
            return new Entry(text, kind, null);
        }

        /** 带 Shift 说明的词条 */
        public static Entry of(Component text, Kind kind, Component detail) {
            return new Entry(text, kind, detail);
        }
    }

    private BallTooltip() {
    }

    /**
     * 把药水效果名渲染成原版那种颜色。
     *
     * <p>原版物品 tooltip 里，有益效果是蓝的、有害效果是红的 —— 这里照搬，
     * 所以「熔融物烧伤」（{@link net.minecraft.world.effect.MobEffectCategory#HARMFUL}）
     * 会以红色出现，一眼能看出是 debuff。</p>
     */
    private static Component effectName(MobEffect effect) {
        ChatFormatting color = switch (effect.getCategory()) {
            case HARMFUL -> ChatFormatting.RED;
            case BENEFICIAL -> ChatFormatting.BLUE;
            default -> ChatFormatting.GRAY;
        };
        return Component.translatable(effect.getDescriptionId()).withStyle(color);
    }

    /**
     * 药水效果名 + 时长，按原版 tooltip 的样子摆 —— {@code 效果名 (0:01)}。
     *
     * <p>时长用 {@link MobEffectUtil#formatDuration} 生成：它给出的才是 MC 的药水格式
     * （分:秒、秒补两位），而且随语言与 tick 速率变化。
     * 之前是在 lang 里写死「（0:01）」，那只是碰巧和本模组当前时长一致的一段文本，
     * 时长一改就会对不上。</p>
     *
     * <p>参数收 {@code Holder<MobEffect>} 而不是 {@code MobEffect} ——
     * {@code MobEffectInstance} 的构造器要的是 holder，而 {@code ModEffects} 里注册的
     * {@code DeferredHolder} 本身就是 holder，直接传即可，别 {@code .get()}。</p>
     */
    private static Component effectWithDuration(net.minecraft.core.Holder<MobEffect> effect, int ticks) {
        Component duration = MobEffectUtil.formatDuration(
                new MobEffectInstance(effect, ticks), 1.0F, 20.0F);
        return Component.translatable("tooltip.more_balls.effect_duration",
                effectName(effect.value()), duration);
    }

    /**
     * 按物品现有的数据生成词条列表。
     *
     * <p>注意返回的是<b>全部</b>词条：常显的那些，以及只有展开时才该出现的机制细节。
     * 调用方负责决定哪些显示 —— {@link BallItem} 用 {@code detailed} 过滤，
     * 径向菜单则统一按「常显」处理。</p>
     *
     * @param stack    要看的球
     * @param detailed 是否展开（对应「按住 Shift」）
     */
    public static List<Entry> entries(ItemStack stack, boolean detailed) {
        BallBehavior.BallProfile profile = BallBehavior.profileFor(stack);
        List<Entry> out = new ArrayList<>();

        // 【基础伤害】—— 常显
        out.add(Entry.of(line("tooltip.more_balls.entry.damage", format(profile.damage())), Kind.BASIC));

        // 【重量】【初速度】【弹性】—— 机制细节，展开时才露出来
        if (detailed) {
            out.add(Entry.of(line("tooltip.more_balls.entry.weight", String.valueOf(profile.weight())),
                    Kind.BASIC));
            out.add(Entry.of(line("tooltip.more_balls.entry.velocity", format(profile.launchSpeed())),
                    Kind.BASIC));
            // 不坚固的球碰上就碎、根本不会反弹，弹性对它没有意义
            if (profile.isTough()) {
                out.add(Entry.of(line("tooltip.more_balls.entry.bounce", String.valueOf(profile.bounce())),
                        Kind.BASIC));
            }
        }

        // 【蓄力x】—— 取默认值时归进折叠组；被改成别的数值就常显。
        // 单独归成 CHARGE 一类：径向菜单会跳过它（球都是用弩打出去的）
        if (detailed || profile.chargeLevels() != BallBehavior.DEFAULT_CHARGE_LEVELS) {
            out.add(Entry.of(line("tooltip.more_balls.entry.charge", String.valueOf(profile.chargeLevels())),
                    Kind.CHARGE));
        }

        // 【坚固】/【坚固x】—— 有才显示
        if (profile.toughness() == BallBehavior.TOUGH_FOREVER) {
            out.add(Entry.of(line("tooltip.more_balls.entry.tough_forever"), Kind.TRAIT));
        } else if (profile.toughness() > 0) {
            out.add(Entry.of(line("tooltip.more_balls.entry.tough", String.valueOf(profile.toughness())),
                    Kind.TRAIT));
        }

        // 【融化x】—— 雪球类默认 200；说明只在 Shift 展开时出现
        if (profile.hasMelt()) {
            out.add(Entry.of(
                    line("tooltip.more_balls.entry.melt", String.valueOf(profile.meltThreshold())),
                    Kind.TRAIT,
                    line("tooltip.more_balls.desc.melt", String.valueOf(profile.meltThreshold()))));
        }

        // 【磁吸】—— 空心铁球自有词条：飞行时把身边金属拽向自己。
        // ⚠️ 它和【磁性】**不是一回事**，别混、也别互相改名（作者 2026-10-07 纠正过）
        if (profile.hasMagnet()) {
            out.add(Entry.of(line("tooltip.more_balls.entry.magnet"), Kind.TRAIT));
        }

        // 【磁性】—— 从【探寻】改来的词条：飞行途中会朝铁磁性目标偏航。
        // 判据走底层词条 hasMagnetic()，不硬编码物品。说明收在 Shift 里
        if (profile.hasMagnetic()) {
            out.add(Entry.of(line("tooltip.more_balls.entry.magnetic"), Kind.TRAIT,
                    line("tooltip.more_balls.desc.magnetic")));
        }

        // 【熔融x】—— 空心铁球专属：热量攒够后击中目标会把它烫伤
        if (profile.hasMolten()) {
            out.add(Entry.of(
                    line("tooltip.more_balls.entry.molten", String.valueOf(profile.moltenThreshold())),
                    Kind.TRAIT,
                    // 效果名 + 时长整体走原版格式（名字带效果颜色、时长是药水格式）
                    line("tooltip.more_balls.desc.molten",
                            effectWithDuration(ModEffects.MOLTEN_BURN,
                                    BallBehavior.MOLTEN_BURN_TICKS))));
        }

        // 【变形】—— 金球
        if (profile.morphBlock() != null) {
            // 作者 2026-10-07 改的文本：常显是那句俏皮话，细则收在 Shift 里
            out.add(Entry.of(line("tooltip.more_balls.entry.morph"), Kind.TRAIT,
                    line("tooltip.more_balls.desc.morph")));
        }

        // ===== 2.6.0 新增的五个词条 =====

        // 【智慧】—— 紫水晶球：发射后扫描并锁定最近的敌对目标
        // 【智慧N】—— N 是可追踪的次数（没写就是 1）
        if (profile.wisdom() > 0) {
            out.add(Entry.of(Component.translatable("tooltip.more_balls.entry.wisdom", profile.wisdom()),
                    Kind.TRAIT, line("tooltip.more_balls.desc.wisdom")));
        }

        // 【善良】—— 不伤害友好与中立生物，碰到就反弹
        if (profile.kindness()) {
            out.add(Entry.of(line("tooltip.more_balls.entry.kindness"), Kind.TRAIT,
                    line("tooltip.more_balls.desc.kindness")));
        }

        // 【导电】—— 像避雷针一样吸引自然闪电
        if (profile.conduction()) {
            out.add(Entry.of(line("tooltip.more_balls.entry.conduction"), Kind.TRAIT,
                    line("tooltip.more_balls.desc.conduction")));
        }

        // 【引雷x】—— 热量满后释放雷电
        if (profile.hasThunder()) {
            out.add(Entry.of(
                    line("tooltip.more_balls.entry.thunder",
                            String.valueOf(profile.thunderThreshold())),
                    Kind.TRAIT,
                    line("tooltip.more_balls.desc.thunder")));
        }

        // 【电击】—— 直接伤害为闪电类型
        if (profile.shockDamage()) {
            out.add(Entry.of(line("tooltip.more_balls.entry.shock"), Kind.TRAIT,
                    line("tooltip.more_balls.desc.shock")));
        }

        // 【金光闪闪】—— 金球专属（这套猪灵行为整套都挂在金球上，所以直接认物品）
        if (BallBehavior.isGoldShiny(stack)) {
            out.add(Entry.of(line("tooltip.more_balls.entry.gold_shiny"), Kind.TRAIT));
        }

        // 【点金】—— 雪球-金粒
        if (profile.transmuteChance() > BallBehavior.NOT_TRANSMUTE) {
            out.add(Entry.of(line("tooltip.more_balls.entry.transmute"), Kind.TRAIT));
        }

        // ===== 3.0.0（0.3.4.0）新增的五个词条 =====

        // 【穿透x】—— 自带穿透，能连着打穿 x 个目标
        if (profile.hasPenetration()) {
            out.add(Entry.of(
                    line("tooltip.more_balls.entry.penetration", String.valueOf(profile.penetration())),
                    Kind.TRAIT,
                    line("tooltip.more_balls.desc.penetration", String.valueOf(profile.penetration()))));
        }

        // 【脉冲】—— 红石球：命中时让周围金属目标晕头转向。
        //
        // ⚠️ 效果名与时长**走原版药水格式**（作者 2026-10-10 指定）——
        //    和【熔融x】一个做法：名字带效果颜色、时长自动按 tick 速率格式化成 mm:ss，
        //    而不是在 lang 里写死「（00:03）」（那种写法时长一改就对不上）。
        if (profile.hasFlag(BallBehavior.BallProfile.FLAG_PULSE)) {
            out.add(Entry.of(line("tooltip.more_balls.entry.pulse"), Kind.TRAIT,
                    Component.translatable("tooltip.more_balls.desc.pulse",
                            effectWithDuration(ModEffects.SHOCK, RedstonePulse.DURATION_BASE),
                            effectWithDuration(ModEffects.SHOCK, RedstonePulse.DURATION_STRONG))));
        }

        // 【照明】—— 红石雪球：下方四棱锥区域内的生物被染色发光
        if (profile.hasFlag(BallBehavior.BallProfile.FLAG_ILLUMINATE)) {
            out.add(Entry.of(line("tooltip.more_balls.entry.illuminate"), Kind.TRAIT,
                    line("tooltip.more_balls.desc.illuminate")));
        }

        // 【破坏王x】—— 钻石球：能砸碎 x 个方块
        if (profile.hasFlag(BallBehavior.BallProfile.FLAG_BREAKER)) {
            out.add(Entry.of(
                    line("tooltip.more_balls.entry.breaker", String.valueOf(BallBehavior.BREAKER_BUDGET)),
                    Kind.TRAIT,
                    line("tooltip.more_balls.desc.breaker", String.valueOf(BallBehavior.BREAKER_BUDGET))));
        }

        // 【透镜】—— 钻石球：白天晴天时给下方持续积热
        if (profile.hasFlag(BallBehavior.BallProfile.FLAG_LENS)) {
            out.add(Entry.of(line("tooltip.more_balls.entry.lens"), Kind.TRAIT,
                    line("tooltip.more_balls.desc.lens")));
        }

        // 【感应】—— 底层词条，不带数字即 1 级（【感应】与【感应1】是一回事）；
        // 说明只在 Shift 展开时出现
        if (profile.hasSense()) {
            Component text = profile.sense() == BallBehavior.DEFAULT_SENSE_LEVEL
                    ? line("tooltip.more_balls.entry.sense_plain")
                    : line("tooltip.more_balls.entry.sense", String.valueOf(profile.sense()));
            out.add(Entry.of(text, Kind.TRAIT, line("tooltip.more_balls.desc.sense")));
        }

        return out;
    }

    /**
     * 取词条的「名字部分」—— 也就是<b>译文里第一个 {@code 】} 之前（含它）</b>的那一段。
     *
     * <p>径向菜单里一排格子放不下整句说明，用这个办法把「只显示中括号」做成一件事 ——
     * 以后加新词条也不用回来改这里。</p>
     */
    public static String nameOf(Component entry) {
        String text = entry.getString();
        int end = text.indexOf('】');
        return end >= 0 ? text.substring(0, end + 1) : text;
    }

    /** 统一词条外观：浅灰小字 */
    public static Component line(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.GRAY);
    }

    /** 数值显示：整数就不带小数点（3.0 → "3"，2.5 → "2.5"） */
    public static String format(float value) {
        if (value == Math.round(value)) {
            return String.valueOf((int) value);
        }
        return String.valueOf(value);
    }
}
