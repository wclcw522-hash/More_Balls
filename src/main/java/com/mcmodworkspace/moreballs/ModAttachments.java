package com.mcmodworkspace.moreballs;

import com.mojang.serialization.Codec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.List;
import java.util.function.Supplier;

/**
 * 本模组的数据附加（Attachment）—— 挂在玩家身上的自定义数据。
 *
 * <h2>为什么用 Attachment 而不是 ItemStack 组件</h2>
 * 【感应】要在玩家身上累积热量（穿金属盔甲、拿金属武器会被加热），
 * 这个热量属于<b>玩家本身</b>、跟任何物品无关，也不该因为换装而丢失，
 * 所以用 NeoForge 的 Attachment 系统存在玩家实体上。
 *
 * <p>相关机制见 {@link BallProspecting} 与 {@code BallProjectile#prospectingTick()}。</p>
 */
public final class ModAttachments {

    private ModAttachments() {
    }

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MoreBalls.MOD_ID);

    /**
     * 玩家身上的【感应】热量。
     *
     * <p>穿着/持有金属装备时被附近的球持续加热；脱离检测满 1 秒
     * （{@code HEAT_DECAY_DELAY_TICKS} 宽限）后每刻自然消退
     * {@link BallProspecting#PLAYER_HEAT_DECAY}（0.2）点；超过
     * {@link BallProspecting#PLAYER_HEAT_IGNITE}（100）点会被点燃。</p>
     */
    public static final Supplier<AttachmentType<Float>> PROSPECTING_HEAT = ATTACHMENTS.register(
            "prospecting_heat",
            () -> AttachmentType.builder(() -> 0.0F)
                    .serialize(Codec.FLOAT.fieldOf("prospecting_heat"))
                    .build());

    /**
     * 上一次被加热的游戏刻。
     *
     * <p>用来区分「本刻正被球加热」（应该继续升温）与「已经脱离检测」（该按每刻
     * {@link BallProspecting#PLAYER_HEAT_DECAY}（0.2）点消退）——
     * 衰退由玩家自身的 tick 事件统一处理，不需要球一直存在。</p>
     */
    public static final Supplier<AttachmentType<Integer>> LAST_HEATED_TICK = ATTACHMENTS.register(
            "last_heated_tick",
            () -> AttachmentType.builder(() -> Integer.MIN_VALUE)
                    .serialize(Codec.INT.fieldOf("last_heated_tick"))
                    .build());

    /**
     * 怪物拿球时的「蓄力完成刻」。
     *
     * <p>怪物没有玩家的使用物品计时器，所以蓄力状态挂在实体自己身上：
     * 拿到球的那一刻记下 {@code tickCount + 蓄力所需刻数}，到点就扔。
     * 用实体自己的 tickCount 而不是世界游戏刻，省得处理长期运行的溢出。</p>
     */
    public static final Supplier<AttachmentType<Integer>> BALL_CHARGE_END = ATTACHMENTS.register(
            "ball_charge_end",
            () -> AttachmentType.builder(() -> 0)
                    .serialize(Codec.INT.fieldOf("ball_charge_end"))
                    .build());

    /**
     * 怪物这一轮蓄力选了几级。
     *
     * <p>接住球的那一刻按概率定下来（不蓄力 / 一层 / 两层 / 拉满），
     * 投掷时按这个等级算初速度加成，所以要跟 {@link #BALL_CHARGE_END} 一起存。</p>
     */
    public static final Supplier<AttachmentType<Integer>> BALL_CHARGE_LEVEL = ATTACHMENTS.register(
            "ball_charge_level",
            () -> AttachmentType.builder(() -> -1)
                    .serialize(Codec.INT.fieldOf("ball_charge_level"))
                    .build());

    /**
     * 这只掠夺者是否改用球当弩弹药。
     *
     * <p>生成时按概率掷一次定下来，之后它射出的箭都会在加入世界的那一刻被换成球。</p>
     */
    public static final Supplier<AttachmentType<Boolean>> BALL_AMMO_MOB = ATTACHMENTS.register(
            "ball_ammo_mob",
            () -> AttachmentType.builder(() -> false)
                    .serialize(Codec.BOOL.fieldOf("ball_ammo_mob"))
                    .build());

    /**
     * 这只猪灵是否被金球打过。
     *
     * <p>【金光闪闪】的特殊交易判定要用：「若捡起金球的猪灵未被金球伤害，则此次交易为特殊交易」。</p>
     */
    public static final Supplier<AttachmentType<Boolean>> GOLD_BALL_HURT = ATTACHMENTS.register(
            "gold_ball_hurt",
            () -> AttachmentType.builder(() -> false)
                    .serialize(Codec.BOOL.fieldOf("gold_ball_hurt"))
                    .build());

    /**
     * <b>【封印】的截止刻</b> —— 符箓球打中怪物时，把它封到「世界游戏刻的哪一刻」为止。
     *
     * <p>作者 2026-10-15 指定的效果：命中怪物时强制它手里的<b>球</b>全部掉落，
     * 并进入一次时长为 10 秒的攻击 cd ——<b>cd 期间无法发起任何攻击，也无法捡球</b>。</p>
     *
     * <p>为什么用 attachment 而不是原版药水效果：原版没有「不能攻击」这种通用状态
     * （虚弱只是减少伤害），而「不能捡球」更是本模组自己的判定（见 {@code BallMobAI}）。
     * 存一个截止刻最直接，判断也只有一行。</p>
     *
     * <p>⚠️ 存的是<b>世界游戏刻</b>（{@code level.getGameTime()}），不是实体自己的
     * tickCount —— 怪物被卸载再加载时 tickCount 会重置，截止刻跟着错。
     * 不需要同步到客户端（服务端行为）。</p>
     */
    public static final Supplier<AttachmentType<Long>> SEALED_UNTIL = ATTACHMENTS.register(
            "sealed_until",
            () -> AttachmentType.builder(() -> 0L)
                    .serialize(Codec.LONG.fieldOf("sealed_until"))
                    .build());

    /**
     * 玩家在径向菜单里<b>选中的弹药</b>（存一颗<b>样本球快照</b>，空栈表示没选）。
     *
     * <p>作者指定的机制：选中之后不必副手持弹，弩会自动从收纳袋里取这种球装填，
     * 而且要按<b>耐久从低到高</b>消耗（先把旧的打出去）。HUD 上的「弹药栏」也要显示它，
     * 所以这条数据必须<b>同步到客户端</b>。</p>
     *
     * <p><b>为什么从「物品 id 字符串」改成「ItemStack」</b>：组合球全都是同一个物品 id，
     * 区别只在数据组件里。存 id 的话铁-金球和木-圆石球会变成同一个，
     * 选中之后弩根本不知道该装哪一种（作者反馈的「轮盘合并 / 放不到副手」就是这个）。
     * 存整颗样本球才带得住组件。样本里会抹掉耐久 —— 种类不分耐久。</p>
     *
     * <p>⚠️ <b>注册名带 {@code _stack} 后缀不是随手起的</b>：之前叫 {@code selected_ammo}、
     * 存的是字符串。改成 ItemStack 后，旧存档里那个字符串会被新 codec 拿去解析 ——
     * 类型对不上直接抛异常。换个名字等于让旧值自然失效，玩家顶多重新选一次弹药。</p>
     *
     * <p>⚠️⚠️ <b>为什么用 {@code List<ItemStack>} 而不是直接存 {@code ItemStack}</b>：
     * {@code ItemStack.CODEC} / {@code OPTIONAL_CODEC} 都<b>拒绝空栈</b>
     * （{@code Value must be within range [1;99]: 0; Item must not be minecraft:air}），
     * 而这里必须能表示「没选」。列表就没有这个限制 ——
     * <b>空列表 = 没选，单元素 = 选中的那一种</b>。</p>
     *
     * <p>（踩过一次：先用了 {@code OPTIONAL_CODEC}，以为 OPTIONAL 就是「允许空值」，
     * 其实它的意思是「这个字段可以整个缺失」。结果一切到弩就崩 —— 见交接单第 54 条。）</p>
     */
    public static final Supplier<AttachmentType<List<ItemStack>>> SELECTED_AMMO = ATTACHMENTS.register(
            "selected_ammo_stack",
            () -> AttachmentType.<List<ItemStack>>builder(() -> List.of())
                    .serialize(ItemStack.CODEC.listOf().fieldOf("selected_ammo_stack"))
                    .sync(ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()))
                    .build());

    /**
     * <b>选中弹药的当前数量</b> —— 专门给 HUD 那个弹药格显示用（{@code -1} = 还没同步过）。
     *
     * <h2>为什么要单独存一个数</h2>
     * <p>HUD 显示的是「收纳袋里这种球还剩几颗」。原本是客户端自己数的
     * （{@code BallAmmoSelection.countOf} 遍历客户端那份袋子内容）—— 而袋子的内容存在
     * ItemStack 的<b>组件</b>里，服务端改了组件客户端不会自动知道
     * （挂在饰品栏上的袋子尤其如此：Curios 只认「栈被换过」）。</p>
     *
     * <p>症状就是作者 2026-10-15 反馈的「剩余弹药显示槽有延迟，要手动改一下袋子里的球才刷新」。</p>
     *
     * <p>所以改成：服务端每次改完袋子就把这个数写进来 —— 它是 {@code .sync(...)} 的
     * attachment，<b>由框架保证同步</b>，客户端直接读，不再依赖 Curios 的同步时机。</p>
     */
    public static final Supplier<AttachmentType<Integer>> SELECTED_AMMO_COUNT = ATTACHMENTS.register(
            "selected_ammo_count",
            () -> AttachmentType.builder(() -> -1)
                    .serialize(Codec.INT.fieldOf("selected_ammo_count"))
                    .sync(ByteBufCodecs.VAR_INT)
                    .build());

    /**
     * 怪物捡球时，从主手上<b>收起来</b>的那件东西（空列表 = 没收着）。
     *
     * <p>作者 2026-10-11 指定：捡球不再把原来手里的东西丢在地上，而是<b>收起来</b>，
     * 等这颗球扔出去之后再原样拿出。丢在地上的话会被别的怪或玩家捡走 ——
     * 那等于把猪灵的金剑、骷髅的弓弄丢了。</p>
     *
     * <p>⚠️ 同 {@link #SELECTED_AMMO}，用 {@code List} 而不是裸 {@code ItemStack} ——
     * {@code ItemStack.CODEC} 拒绝空栈，而这里必须能表示「没收着」。</p>
     *
     * <p>不需要同步到客户端：纯服务端状态，客户端看不到也无所谓。</p>
     */
    public static final Supplier<AttachmentType<List<ItemStack>>> STASHED_MAINHAND = ATTACHMENTS.register(
            "stashed_mainhand",
            () -> AttachmentType.<List<ItemStack>>builder(() -> List.of())
                    .serialize(ItemStack.CODEC.listOf().fieldOf("stashed_mainhand"))
                    .build());

    /**
     * <b>烈焰人的球袋</b> —— 它持有的球（作者 2026-10-11 指定：混装、最多 12 颗）。
     *
     * <p>为什么要单独存：烈焰人要能**同时拿不同种类**的球，
     * 而主手只有**一个物品栈**，一叠只能装同一种。所以球袋用
     * {@code List<ItemStack>}，每个元素是<b>单独一颗</b>（count 恒为 1）——
     * 这样每颗球各自带着自己的耐久与组件，投出去时也不会互相干扰。</p>
     *
     * <p>⚠️ 又一次是「{@code ItemStack.CODEC} 拒绝空栈」那个老问题 —— 用列表表示「没了」。</p>
     */
    public static final Supplier<AttachmentType<List<ItemStack>>> BLAZE_BALLS = ATTACHMENTS.register(
            "blaze_balls",
            () -> AttachmentType.<List<ItemStack>>builder(() -> List.of())
                    .serialize(ItemStack.CODEC.listOf().fieldOf("blaze_balls"))
                    .build());

    // （这里原本有个 CREEPER_CHARGE_START attachment —— 用来记「持球苦力怕蓄了多久」。
    //   后来发现原版 Creeper.getSwelling() 就能直接读出蓄力进度，比自记时刻更准，
    //   于是那个 attachment 就没人用了，2026-10-07 清掉。）

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }
}
