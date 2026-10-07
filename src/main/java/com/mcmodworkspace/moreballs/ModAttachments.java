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
 * 「探寻」要在玩家身上累积热量（穿金属盔甲、拿金属武器会被加热），
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
     * 玩家身上的「探寻热量」。
     *
     * <p>穿着/持有金属装备时被附近的球持续加热，脱离检测范围后每刻自然消退 2 点；
     * 超过 50 点会被点燃。</p>
     */
    public static final Supplier<AttachmentType<Float>> PROSPECTING_HEAT = ATTACHMENTS.register(
            "prospecting_heat",
            () -> AttachmentType.builder(() -> 0.0F)
                    .serialize(Codec.FLOAT.fieldOf("prospecting_heat"))
                    .build());

    /**
     * 上一次被加热的游戏刻。
     *
     * <p>用来区分「本刻正被球加热」（应该继续升温）与「已经脱离检测」（该按每刻 2 点消退）——
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

    // （这里原本有个 CREEPER_CHARGE_START attachment —— 用来记「持球苦力怕蓄了多久」。
    //   后来发现原版 Creeper.getSwelling() 就能直接读出蓄力进度，比自记时刻更准，
    //   于是那个 attachment 就没人用了，2026-10-07 清掉。）

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }
}
