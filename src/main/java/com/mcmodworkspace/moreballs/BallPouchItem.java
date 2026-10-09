package com.mcmodworkspace.moreballs;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 魔丸收纳袋 —— 专门装「球」的容器。
 *
 * <p>两段式：<b>弹药格</b>放满耐久的球、<b>转运格</b>放用过的球。容量随等级成长，
 * 见 {@link BallPouchTier}。数据存在 {@link ModComponents#POUCH_TIER} 与
 * {@link ModComponents#POUCH_CONTENTS} 两个组件上。</p>
 *
 * <h2>两段为什么分开</h2>
 * <p>作者指定的设计意图：<b>满耐久的球是「新弹药」，用过的是「该先打出去的」</b>。
 * 分开之后就能做到「消耗优先从转运段拿」—— 打出去的都是旧球，新球一直攒着。
 * 所以这不是单纯的分类，而是让「先用旧的」这件事在数据层就成立。</p>
 */
public class BallPouchItem extends Item {

    /** 这件物品对应的等级 —— 七件独立物品各自持有自己的 */
    private final BallPouchTier tier;

    public BallPouchItem(Properties properties, BallPouchTier tier) {
        super(properties);
        this.tier = tier;
    }

    /** 这件物品是哪一级的袋子 */
    public BallPouchTier tier() {
        return this.tier;
    }

    // ===== 等级 =====

    /** 读到袋子的等级 —— 直接看它是哪件物品 */
    public static BallPouchTier tier(ItemStack pouch) {
        return pouch.getItem() instanceof BallPouchItem item
                ? item.tier()
                : BallPouchTier.LEATHER;
    }

    /** 这是不是一件收纳袋物品 */
    public static boolean isPouch(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BallPouchItem;
    }

    /** 算出「升到下一级」之后应该是哪件物品（含把内容物原样带过去） */
    public static ItemStack upgraded(ItemStack pouch) {
        BallPouchTier next = tier(pouch).next();
        if (next == tier(pouch)) {
            return pouch.copy();
        }
        ItemStack out = new ItemStack(ModItems.pouchFor(next));
        // 内容物原样搬过去；新的物品容量更大，setContents 会按新等级补齐格子
        setContents(out, contents(pouch));
        return out;
    }

    // ===== 内容物 =====

    /** 读到袋子里的东西；没写组件就是空袋，并按当前等级补齐长度 */
    public static BallPouchContents contents(ItemStack pouch) {
        BallPouchContents stored = pouch.get(ModComponents.POUCH_CONTENTS.get());
        if (stored == null) {
            stored = BallPouchContents.EMPTY;
        }
        return stored.fitTo(tier(pouch));
    }

    /** 写入内容物（顺带按等级规整长度） */
    public static void setContents(ItemStack pouch, BallPouchContents contents) {
        pouch.set(ModComponents.POUCH_CONTENTS.get(), contents.fitTo(tier(pouch)));
    }

    // ===== 取放 =====

    /**
     * 这颗球是「满耐久」吗 —— 决定它该进弹药段还是转运段。
     *
     * <p>判据就是「有没有写过耐久组件」：全新的球不带那个组件（代表满耐久），
     * 弹过一次就写入了具体数值。</p>
     */
    public static boolean isPristine(ItemStack ball) {
        return !ball.has(ModComponents.TOUGHNESS_LEFT.get());
    }

    /**
     * 往袋子里塞一颗球。
     *
     * <p>按球的耐久自动分流：<b>满耐久的进弹药段、用过的进转运段</b>。</p>
     *
     * <p>传进来的 {@code ball} 会被就地消耗（数量扣减），调用方据返回值判断是否放完了。</p>
     *
     * @return true 表示这颗球<b>全部</b>塞进去了
     */
    public static boolean insert(ItemStack pouch, ItemStack ball) {
        if (ball.isEmpty() || !BallAmmo.isBall(ball)) {
            return false;
        }
        boolean toAmmo = isPristine(ball);
        return toAmmo ? insertToAmmo(pouch, ball) : insertToTransit(pouch, ball);
    }

    /**
     * 只往<b>转运段</b>塞 —— 作者指定的拾取去向里，中转槽是明确的一站。
     *
     * <p>不检查球是不是用过的：这是「玩家/回收流程要求放中转槽」的语义。</p>
     *
     * <p>⚠️ <b>写入同样受该等级容量约束</b>（{@code tier(pouch).transitSlots()}）。
     * 早先这里写着「界面上的槽位限制管的是手动拖拽，两者不冲突」，那是错的 ——
     * 数据层每段被 {@code fitTo} 无条件补齐到 27 格，写入端不截断就会把球
     * 塞进界面根本显示不出来的格子，而调用方看到「塞进去了」又把实体 discard，
     * 球就人间蒸发（作者 2026-10-09 报的）。容量检查在写入端，见 {@link #cappedSegment}。</p>
     *
     * @return true 表示全部塞进去了
     */
    public static boolean insertToTransit(ItemStack pouch, ItemStack ball) {
        if (ball.isEmpty() || !BallAmmo.isBall(ball)) {
            return false;
        }
        BallPouchContents current = contents(pouch);
        // ⚠️ 必须按**该等级真正的容量**截断。
        //
        // 数据层的每段被 BallPouchContents.fitTo 无条件补齐到 27 格，
        // 而 tier(...).transitSlots() 真正的容量（9 / 12 / … / 27）**只有界面和 tooltip 在用** ——
        // 写入路径一次都没碰过。于是满袋时球会被塞进第 10~27 格：
        // 界面把那些格子涂成不可见的灰格，实体又被 discard，看起来就是「球消失了」。
        List<ItemStack> segment = cappedSegment(current.transit(), tier(pouch).transitSlots());
        boolean changed = fillInto(segment, ball);
        if (changed) {
            // 另一段也裁一遍 —— 顺带清掉旧存档里已经存在的越界幽灵球
            setContents(pouch, new BallPouchContents(
                    cappedSegment(current.ammo(), tier(pouch).ammoSlots()), segment));
        }
        return ball.isEmpty();
    }

    /** 只往<b>弹药段</b>塞 */
    public static boolean insertToAmmo(ItemStack pouch, ItemStack ball) {
        if (ball.isEmpty() || !BallAmmo.isBall(ball)) {
            return false;
        }
        BallPouchContents current = contents(pouch);
        // 同 insertToTransit：写入前按该等级容量截断
        List<ItemStack> segment = cappedSegment(current.ammo(), tier(pouch).ammoSlots());
        boolean changed = fillInto(segment, ball);
        if (changed) {
            setContents(pouch, new BallPouchContents(
                    segment, cappedSegment(current.transit(), tier(pouch).transitSlots())));
        }
        return ball.isEmpty();
    }

    /**
     * 取这一段的**可用前缀** —— 超出该等级容量的格子一律清空。
     *
     * <p>数据层每段固定 27 格（{@code BallPouchContents.FIXED_SEGMENT_SIZE}），
     * 但该等级真正可用的只有 {@code tier.slots()} 格。写入前必须剪掉越界部分，
     * 否则球会落到界面上「不存在」的格子里去 —— 那正是「球被吸走却消失了」。</p>
     *
     * <p>顺带把旧存档里已经存在的越界内容也清掉（它们本来就是幽灵球）。</p>
     */
    private static List<ItemStack> cappedSegment(List<ItemStack> segment, int slots) {
        // ⚠️ 长度必须**等于容量**，不能取 max。
        //
        // 上一版写的是 Math.max(slots, FIXED_SEGMENT_SIZE) —— slots ≤ 27 恒成立，
        // 于是长度恒为 27，等于**根本没截断**；更糟的是循环把 9~26 格清成空，
        // 而 fillInto 的「找空格」正好命中这些空格，把球写进界面根本不存在的格子，
        // 再把 ball.setCount(0)、返回 true，调用方据此 discard 实体 —— 球就这么没了。
        // 换句话说：那个 max 不但没拦住，还亲手给溢出球腾出了落点。
        int size = Math.min(Math.max(slots, 0), BallPouchContents.FIXED_SEGMENT_SIZE);
        List<ItemStack> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(i < segment.size() ? segment.get(i) : ItemStack.EMPTY);
        }
        return out;
    }
    /** 把球填进这一段：先叠到同类堆上，再占空格 */
    private static boolean fillInto(List<ItemStack> segment, ItemStack ball) {
        boolean changed = false;

        for (int i = 0; i < segment.size() && !ball.isEmpty(); i++) {
            ItemStack slot = segment.get(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, ball)) {
                continue;
            }
            int room = slot.getMaxStackSize() - slot.getCount();
            if (room <= 0) {
                continue;
            }
            int move = Math.min(room, ball.getCount());
            slot.grow(move);
            ball.shrink(move);
            changed = true;
        }

        for (int i = 0; i < segment.size() && !ball.isEmpty(); i++) {
            if (segment.get(i).isEmpty()) {
                segment.set(i, ball.copy());
                ball.setCount(0);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 从袋子里取一颗球 —— 作者指定的消耗顺序：<b>先转运段，再弹药段</b>。
     *
     * <p>所以打出去的都是弹过的旧球，全新的球会一直攒在袋底。</p>
     *
     * @return 取到的球；袋子空了返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack takeOne(ItemStack pouch) {
        BallPouchContents current = contents(pouch);

        // ① 转运段（用过的球优先出场）
        List<ItemStack> transit = new ArrayList<>(current.transit());
        ItemStack fromTransit = takeFirst(transit);
        if (!fromTransit.isEmpty()) {
            setContents(pouch, new BallPouchContents(current.ammo(), transit));
            return fromTransit;
        }

        // ② 弹药段
        List<ItemStack> ammo = new ArrayList<>(current.ammo());
        ItemStack fromAmmo = takeFirst(ammo);
        if (!fromAmmo.isEmpty()) {
            setContents(pouch, new BallPouchContents(ammo, current.transit()));
        }
        return fromAmmo;
    }

    /** 从某一段里取出<b>第一格非空</b>物品（取空该格） */
    private static ItemStack takeFirst(List<ItemStack> segment) {
        for (int i = 0; i < segment.size(); i++) {
            ItemStack slot = segment.get(i);
            if (!slot.isEmpty()) {
                segment.set(i, ItemStack.EMPTY);
                return slot;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 袋子里还有没有球 */
    public static boolean hasAnyBall(ItemStack pouch) {
        return !contents(pouch).isEmpty();
    }

    /**
     * 从转运段取一颗<b>同类</b>的球 —— 作者指定的消耗顺序里排第一位。
     *
     * <p>「同类」只比对物品种类，<b>不看耐久</b>：用过的旧球就该先出场。</p>
     */
    public static ItemStack takeSameKindFromTransit(ItemStack pouch, ItemStack wanted) {
        BallPouchContents current = contents(pouch);
        List<ItemStack> segment = new ArrayList<>(current.transit());
        ItemStack taken = takeSameKindFrom(segment, wanted);
        if (!taken.isEmpty()) {
            // 另一段也裁一遍 —— 顺带清掉旧存档里已经存在的越界幽灵球
            setContents(pouch, new BallPouchContents(
                    cappedSegment(current.ammo(), tier(pouch).ammoSlots()), segment));
        }
        return taken;
    }

    /** 从弹药段取一颗同类的球 */
    public static ItemStack takeSameKindFromAmmo(ItemStack pouch, ItemStack wanted) {
        BallPouchContents current = contents(pouch);
        List<ItemStack> segment = new ArrayList<>(current.ammo());
        ItemStack taken = takeSameKindFrom(segment, wanted);
        if (!taken.isEmpty()) {
            setContents(pouch, new BallPouchContents(
                    segment, cappedSegment(current.transit(), tier(pouch).transitSlots())));
        }
        return taken;
    }

    /**
     * 从袋子里取一颗指定种类中<b>剩余耐久最低</b>的球。
     *
     * <p>径向菜单选中弹药之后，弩的自动装填走这个方法 —— 作者要求求
     * 「按耐久从低到高消耗」，也就是先把用过的旧球打出去，新的留着。</p>
     *
     * <p>查找顺序：先中转段（用过的球本来就在那儿），再弹药段。
     * 两段各自找出同种里耐久最低的那颗取走。</p>
     */
    public static ItemStack takeLowestDurability(ItemStack pouch, ItemStack wanted) {
        BallPouchContents current = contents(pouch);

        int transitIndex = indexOfLowestDurability(current.transit(), wanted);
        if (transitIndex >= 0) {
            List<ItemStack> segment = new ArrayList<>(current.transit());
            ItemStack taken = segment.remove(transitIndex);
            // 另一段也裁一遍 —— 顺带清掉旧存档里已经存在的越界幽灵球
            setContents(pouch, new BallPouchContents(
                    cappedSegment(current.ammo(), tier(pouch).ammoSlots()), segment));
            return taken;
        }

        int ammoIndex = indexOfLowestDurability(current.ammo(), wanted);
        if (ammoIndex >= 0) {
            List<ItemStack> segment = new ArrayList<>(current.ammo());
            ItemStack taken = segment.remove(ammoIndex);
            setContents(pouch, new BallPouchContents(
                    segment, cappedSegment(current.transit(), tier(pouch).transitSlots())));
            return taken;
        }

        return ItemStack.EMPTY;
    }

    /**
     * 在某一段里找同类中<b>剩余耐久最低</b>那颗的下标；找不到返回 -1。
     *
     * <p>⚠️ 剩余耐久读的是<b>自定义组件 {@link ModComponents#TOUGHNESS_LEFT}</b>，
     * 不是原版 {@code DataComponents.DAMAGE} —— 球的耐久整套都走自定义组件
     * （原版耐久会让物品变成不可堆叠，见 {@link ModComponents} 的说明）。</p>
     *
     * <p>⚠️⚠️ 判据是 {@code best < 0 || left < bestLeft}，<b>不能只写 {@code left < bestLeft}</b>：
     * 满耐久的球 {@link #remainingToughness} 返回 {@link Integer#MAX_VALUE}，
     * 正好等于 {@code bestLeft} 的初始值，于是 {@code <} 永不成立、一颗都选不中 ——
     * 表现为「袋里全是满耐久球时，自动装填完全失效」（作者反馈过，2.5.0 修复）。</p>
     */
    private static int indexOfLowestDurability(List<ItemStack> segment, ItemStack wanted) {
        int best = -1;
        int bestLeft = Integer.MAX_VALUE;
        for (int i = 0; i < segment.size(); i++) {
            ItemStack slot = segment.get(i);
            if (slot.isEmpty() || !sameKind(slot, wanted)) {
                continue;
            }
            int left = remainingToughness(slot);
            if (best < 0 || left < bestLeft) {
                bestLeft = left;
                best = i;
            }
        }
        return best;
    }

    /**
     * 这颗球还剩几点耐久。
     *
     * <p>缺 {@link ModComponents#TOUGHNESS_LEFT} 组件就是<b>满耐久</b> ——
     * 用 {@link Integer#MAX_VALUE} 表示，这样它在「取最旧的」排序里永远排最后。</p>
     */
    public static int remainingToughness(ItemStack ball) {
        Integer left = ball.get(ModComponents.TOUGHNESS_LEFT.get());
        return left == null ? Integer.MAX_VALUE : left;
    }

    /** 玩家所有袋子里，某种球的最低剩余耐久；一颗都没有时返回 {@link Integer#MAX_VALUE} */
    public static int lowestToughnessInPouches(Player player, ItemStack wanted) {
        int lowest = Integer.MAX_VALUE;
        for (ItemStack pouch : BallPouchHelper.allPouchStacks(player)) {
            BallPouchContents contents = contents(pouch);
            lowest = Math.min(lowest, lowestToughness(contents.transit(), wanted));
            lowest = Math.min(lowest, lowestToughness(contents.ammo(), wanted));
        }
        return lowest;
    }

    private static int lowestToughness(List<ItemStack> segment, ItemStack wanted) {
        int lowest = Integer.MAX_VALUE;
        for (ItemStack slot : segment) {
            if (!slot.isEmpty() && sameKind(slot, wanted)) {
                lowest = Math.min(lowest, remainingToughness(slot));
            }
        }
        return lowest;
    }

    /**
     * 两颗球是不是「同一种」。
     *
     * <h2>为什么不能直接用 {@code isSameItem}</h2>
     * <p>组合球**全都是同一个物品 id**（`combo_ball`），区别只在组件里。
     * 只比 item 的话，铁-金球和木-圆石球会被当成同一种 —— 作者反馈的
     * 「收纳袋无法正常回收组合球」就是这个：取出来的根本不是原来那颗。</p>
     *
     * <h2>为什么也不能直接用 {@code isSameItemSameComponents}</h2>
     * <p>那会把「耐久用掉了几点」也算成不同种，而作者明确定过
     * <b>取弹不看耐久</b>（同种球就该能互相顶替）。</p>
     *
     * <p>所以这里的口径是：<b>同 item + 同数据组件，但刻意忽略耐久组件</b>。
     * 对老球来说组件本来就只有耐久，于是行为和以前完全一致；
     * 对组合球 / 半成品来说，多出来的 `combo_sources` / `fragment_source` 会参与比较，
     * 不同组合自然就区分开了。</p>
     */
    public static boolean sameKind(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty() || a.getItem() != b.getItem()) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(withoutToughness(a), withoutToughness(b));
    }

    /** 复制一份并抹掉耐久组件 —— 只用于「同种」比较 */
    private static ItemStack withoutToughness(ItemStack stack) {
        ItemStack copy = stack.copyWithCount(1);
        copy.remove(ModComponents.TOUGHNESS_LEFT.get());
        return copy;
    }

    /** 从某一段里取出第一颗同类的球（整stack取走） */
    private static ItemStack takeSameKindFrom(List<ItemStack> segment, ItemStack wanted) {
        for (int i = 0; i < segment.size(); i++) {
            ItemStack slot = segment.get(i);
            if (slot.isEmpty() || !sameKind(slot, wanted)) {
                continue;
            }
            segment.set(i, ItemStack.EMPTY);
            return slot;
        }
        return ItemStack.EMPTY;
    }

    /** 转运段里有没有这种球 */
    public static boolean hasSameKindInTransit(ItemStack pouch, ItemStack wanted) {
        return segmentHasKind(contents(pouch).transit(), wanted);
    }

    /** 弹药段里有没有这种球 */
    public static boolean hasSameKindInAmmo(ItemStack pouch, ItemStack wanted) {
        return segmentHasKind(contents(pouch).ammo(), wanted);
    }

    private static boolean segmentHasKind(List<ItemStack> segment, ItemStack wanted) {
        for (ItemStack slot : segment) {
            if (!slot.isEmpty() && sameKind(slot, wanted)) {
                return true;
            }
        }
        return false;
    }

    // ===== 提示 =====

    // ===== 打开界面 =====

    /**
     * 右键打开收纳袋 —— <b>只认主手</b>。
     *
     * <p>副手拿着袋子时右键什么也不开：副手在本模组里是「弹药位」，
     * 袋子摆在那儿就是当<b>随身弹药仓</b>用的 —— 按住 R 能用轮盘挑弹药，
     * 与戴在饰品栏里一视同仁（见 {@link BallPouchCurios}）。
     * 界面入口留在主手，右键就不会跟弩和球的操作打架。</p>
     *
     * <p>把袋子这个物品堆本身随菜单发给客户端 —— 界面要读它的内容物组件才知道画什么。
     * 这也是用 {@code IMenuTypeExtension.create} 而不是原版简单工厂的原因。</p>
     */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // 副手：只当弹药仓，不开界面（理由见上面的方法说明）
        if (hand == InteractionHand.OFF_HAND) {
            return InteractionResult.PASS;
        }

        ItemStack pouch = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(
                    new SimpleMenuProvider(
                            (id, inventory, p) -> new BallPouchMenu(id, inventory, pouch),
                            // 界面顶部那条标题写「备用区」—— 不再用物品名，
                            // 因为下面还有第二个标题区写「中转区」，两个标题各管一段
                            Component.translatable("container.more_balls.ball_pouch")),
                    buffer -> ItemStack.STREAM_CODEC.encode(buffer, pouch));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack pouch, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> consumer, TooltipFlag flag) {
        BallPouchTier tier = tier(pouch);
        BallPouchContents contents = contents(pouch);

        consumer.accept(Component.translatable("tooltip.more_balls.pouch.tier",
                        Component.translatable("pouch.more_balls.tier." + tier.id()))
                .withStyle(ChatFormatting.GOLD));

        consumer.accept(Component.translatable("tooltip.more_balls.pouch.ammo",
                        BallPouchContents.countFilled(contents.ammo()), tier.ammoSlots())
                .withStyle(ChatFormatting.AQUA));

        consumer.accept(Component.translatable("tooltip.more_balls.pouch.transit",
                        BallPouchContents.countFilled(contents.transit()), tier.transitSlots())
                .withStyle(ChatFormatting.YELLOW));

        if (!tier.isMax()) {
            consumer.accept(Component.translatable("tooltip.more_balls.pouch.next",
                            Component.translatable("pouch.more_balls.tier." + tier.next().id()))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
