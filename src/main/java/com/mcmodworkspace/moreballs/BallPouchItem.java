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
 * 见 {@link BallPouchTier}。两段都存在同一个组件
 * {@link ModComponents#POUCH_CONTENTS} 上（各占 54 格，实际可用格数由等级决定）。</p>
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
        // ★★ 球堆要摊开看**壳里那颗球**（作者 2026-10-15：
        //    「收回时球会错误的在备用区变成球堆」）。
        //
        // ⚠️⚠️ 壳自身**没有** `TOUGHNESS_LEFT` 组件（耐久记在里面的球上），
        //     所以原先这个判据对壳**恒返回 true** ⇒ 它被当成「满耐久」⇒
        //     `deliverReturned` 把它送进 **ammo（备用区）**。
        //     而备用区走的是 `fillInto`（按 `isSameItemSameComponents` 全等合并）——
        //     壳和球永远不相等 ⇒ **每个壳独占一格**，表现就是你说的
        //     「用过的球收回后在备用区变成了球堆」。
        //
        // 正确口径：壳按**里面那颗球**的实际耐久分流。
        // 回收链上 `clusterize` 只对「用过的球」套壳（满耐久原样返回），
        // 所以壳摊开后必然带着耐久组件 ⇒ 这里返回 false ⇒ 进 **transit（转运段）**，
        // 而转运段本来就有 `clusterizeSegment` / `fillClustered` —— 那才是球堆该待的地方。
        ItemStack probe = BallClusterItem.isCluster(ball)
                ? BallClusterItem.asBall(ball)
                : ball;
        if (probe.isEmpty()) {
            return false;
        }
        return !probe.has(ModComponents.TOUGHNESS_LEFT.get());
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
        if (ball.isEmpty()) {
            return false;
        }

        // ===== 【球堆】壳进袋子（作者 2026-10-14）=====
        //
        // 壳不能走下面那条「就地扣数量」的路 —— 它的语义是「掏一颗出来」，
        // 而不是「这一格少一个」。所以在这里把壳里的球**逐颗倒进袋子**：
        //
        //   · 每颗都按它自己的耐久分流（满耐久 → 弹药段，用过的 → 转运段）
        //   · 袋子塞不下就把这一颗**放回壳里**并收工（其余的原样留着）
        //   · 壳被掏空后组件自动摘掉，调用方拿 contents().isEmpty() 就能看出来
        //
        // 这样「袋子优先、球堆兜底」那条分工才真正成立：袋子的容量先被用满，
        // 剩下的球才在回收流程里被套成壳。
        if (BallClusterItem.isCluster(ball)) {
            boolean moved = false;
            while (!BallClusterItem.contents(ball).isEmpty()) {
                ItemStack one = BallClusterItem.takeOne(ball);
                if (one.isEmpty()) {
                    break;
                }
                ItemStack probe = one.copy();   // 塞进去的是副本，探针用来判断成没成
                boolean ok = isPristine(probe)
                        ? insertToAmmo(pouch, probe)
                        : insertToTransit(pouch, probe);
                if (!probe.isEmpty()) {
                    // 塞不下：把这一颗放回壳里，收工（其余的也还在）
                    BallClusterContents current = BallClusterItem.contents(ball);
                    BallClusterItem.setContents(ball, current.withAdded(probe));
                    break;
                }
                moved = true;
            }
            return moved;
        }

        if (!BallAmmo.isBall(ball)) {
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
     * 数据层每段被 {@code fitTo} 无条件补齐到 54 格，写入端不截断就会把球
     * 塞进界面根本显示不出来的格子，而调用方看到「塞进去了」又把实体 discard，
     * 球就人间蒸发（作者 2026-10-09 报的）。容量检查在写入端，见 {@link #cappedSegment}。</p>
     *
     * @return true 表示全部塞进去了
     */
    public static boolean insertToTransit(ItemStack pouch, ItemStack ball) {
        // ★ 球堆（壳）也要收（作者 2026-10-15）：壳不是 `#more_balls:balls` 标签里的物品，
        //   原先只判 `isBall` ⇒ **球堆根本塞不进袋子** ⇒ 袋子里的球堆这条路整条是断的。
        if (ball.isEmpty() || !(BallAmmo.isBall(ball)
                || BallClusterItem.isCluster(ball))) {
            return false;
        }
        BallPouchContents current = contents(pouch);
        // ⚠️ 必须按**该等级真正的容量**截断。
        //
        // 数据层的每段被 BallPouchContents.fitTo 无条件补齐到 54 格，
        // 而 tier(...).transitSlots() 真正的容量（18 / 24 / … / 54，即弹药格的两倍）
        // **只有界面和 tooltip 在用** ——
        // 写入路径一次都没碰过。于是满袋时球会被塞进超出该等级容量的格子（最远到第 54 格）：
        // 界面把那些格子涂成不可见的灰格，实体又被 discard，看起来就是「球消失了」。
        List<ItemStack> segment = cappedSegment(current.transit(), tier(pouch).transitSlots());

        // ===== 【球堆】转运段按档合并（作者 2026-10-15 反馈）=====
        //
        // 反馈原话：「球堆在收纳袋里也应该生效。球堆现在完全不生效，反而一个球一堆完全不收纳。」
        //
        // 根因就在下面那句 fillInto：它用 ItemStack.isSameItemSameComponents 判「能不能并进同一格」，
        // 而这和 MC 的堆叠判定是**同一条** —— 用过的球耐久各不相同、组件必然不一致，
        // 于是**每颗必然独占一格**。袋子看着装满了，其实一点没省。
        //
        // 所以转运段改存**球堆**：同球种 + 同档位合并成一格。
        // 弹药段不动 —— 满耐久的球不带耐久组件，组件完全一致，本来就叠得起来。
        boolean changed = clusterizeSegment(segment) ;
        changed = fillClustered(segment, ball) || changed;

        if (changed) {
            // 另一段也裁一遍 —— 顺带清掉旧存档里已经存在的越界幽灵球
            setContents(pouch, new BallPouchContents(
                    cappedSegment(current.ammo(), tier(pouch).ammoSlots()), segment));
        }
        return ball.isEmpty();
    }

    /**
     * 把这一段里的**散球就地收拾成球堆**。
     *
     * <p>两个作用：</p>
     * <ol>
     *   <li><b>旧存档迁移</b> —— 1.1.0.0 之前进袋的散球还在那儿占着格子，
     *       每次写入时顺手把它们转成壳，不用玩家手动整理</li>
     *   <li><b>合并同类</b> —— 转成壳之后，被打散在不同格子上的同档球会并到一起</li>
     * </ol>
     *
     * @return 这一段有没有被改动
     */
    public static boolean clusterizeSegment(List<ItemStack> segment) {
        boolean changed = false;
        for (int i = 0; i < segment.size(); i++) {
            ItemStack slot = segment.get(i);
            if (slot.isEmpty() || BallClusterItem.isCluster(slot)) {
                continue;   // 空的、或者本来就是壳 → 不用管
            }

            // 这是个旧的散球 → 挪出来，套成壳，再找位置合并
            ItemStack ball = slot.copy();
            segment.set(i, ItemStack.EMPTY);

            boolean merged = false;
            for (int k = 0; k < segment.size(); k++) {
                ItemStack other = segment.get(k);
                if (!BallClusterItem.isCluster(other)) {
                    continue;
                }
                BallClusterContents existing = BallClusterItem.contents(other);
                ItemStack probe3 = BallClusterItem.asBall(ball);
                if (probe3.isEmpty() || !existing.hasRoom() || !existing.matches(probe3)) {
                    continue;
                }
                // ⚠️⚠️ 核对真的加进去了（作者 2026-10-15 报「整理时莫名消失一部分球」）。
                //      `withAdded` 在满 64 / 不匹配时会**静默返回 this** —— 而这里原来无条件
                //      置 `merged = true`，那样外层就会把这一格的改动当成成功，球实际没进壳。
                BallClusterContents after = existing.withAdded(probe3);
                if (after == existing) {
                    continue;   // 没加进去 → 换下一个壳试
                }
                BallClusterItem.setContents(other, after);
                merged = true;
                break;
            }
            if (!merged) {
                // 没得并 → 在这一格放一个新壳（壳里就这一颗）
                ItemStack shell = new ItemStack(ModItems.BALL_CLUSTER.get());
                BallClusterItem.setContents(shell, BallClusterContents.EMPTY.withAdded(ball));
                segment.set(i, shell);
            }
            changed = true;
        }
        return changed;
    }

    /**
     * 把一颗球并进这一段 —— <b>并进已有的同档壳，没有再新建一个壳占空格</b>。
     *
     * <p>这就是 {@code fillInto} 的「球堆版」：那边按组件全等判合并（散球必失败），
     * 这边按「同球种 + 同档位」判合并（球堆能成功）。</p>
     */
    private static boolean fillClustered(List<ItemStack> segment, ItemStack ball) {
        boolean changed = false;

        // ① 先并进已有的同档壳
        for (int i = 0; i < segment.size() && !ball.isEmpty(); i++) {
            ItemStack slot = segment.get(i);
            if (!BallClusterItem.isCluster(slot)) {
                continue;
            }
            BallClusterContents existing = BallClusterItem.contents(slot);
            // ⚠️ 要比对的是**壳里那颗球**，不是壳本身 —— 传进来的 ball 可能已经是壳了
            //    （回收流程现在会先套壳），拿壳去 matches 会因 items 不同而永远失配，
            //    结果是「一个壳装一颗」，等于没收纳。
            ItemStack probe = BallClusterItem.asBall(ball);
            if (probe.isEmpty() || !existing.hasRoom() || !existing.matches(probe)) {
                continue;
            }
            // ⚠️⚠️ **这就是那个丢球点**（作者 2026-10-15 报「整理时莫名消失一部分球」）。
            //
            //      原来是：
            //          BallClusterItem.setContents(slot, existing.withAdded(probe));
            //          ball.setCount(0);          ← 无条件清零
            //
            //      `withAdded` 在满 64 / 不匹配时会**静默返回 this**（球没进壳），
            //      而这里不管加没加都把源棧清零 ⇒ **球凭空消失**。
            //      现在先核对，加不进去就换下一个壳，**绝不清源栈**。
            BallClusterContents after = existing.withAdded(probe);
            if (after == existing) {
                continue;
            }
            BallClusterItem.setContents(slot, after);
            ball.setCount(0);
            changed = true;
        }

        // ② 还有剩就新建一个壳，占一个空格
        for (int i = 0; i < segment.size() && !ball.isEmpty(); i++) {
            if (!segment.get(i).isEmpty()) {
                continue;
            }
            ItemStack probe2 = BallClusterItem.asBall(ball);
            if (probe2.isEmpty()) {
                ball.setCount(0);
                continue;
            }
            ItemStack shell = new ItemStack(ModItems.BALL_CLUSTER.get());
            BallClusterItem.setContents(shell, BallClusterContents.EMPTY.withAdded(probe2));
            segment.set(i, shell);
            ball.setCount(0);
            changed = true;
        }
        return changed;
    }

    /** 只往<b>弹药段</b>塞 */
    public static boolean insertToAmmo(ItemStack pouch, ItemStack ball) {
        // ★ 球堆（壳）也要收（作者 2026-10-15）：壳不是 `#more_balls:balls` 标签里的物品，
        //   原先只判 `isBall` ⇒ **球堆根本塞不进袋子** ⇒ 袋子里的球堆这条路整条是断的。
        if (ball.isEmpty() || !(BallAmmo.isBall(ball)
                || BallClusterItem.isCluster(ball))) {
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
     * <p>数据层每段固定 54 格（{@code BallPouchContents.FIXED_SEGMENT_SIZE}），
     * 但该等级真正可用的只有 {@code tier.ammoSlots()} / {@code tier.transitSlots()} 格。
     * 写入前必须剪掉越界部分，
     * 否则球会落到界面上「不存在」的格子里去 —— 那正是「球被吸走却消失了」。</p>
     *
     * <p>顺带把旧存档里已经存在的越界内容也清掉（它们本来就是幽灵球）。</p>
     */
    private static List<ItemStack> cappedSegment(List<ItemStack> segment, int slots) {
        // ⚠️ 长度必须**等于容量**，不能取 max。
        //
        // 上一版写的是 Math.max(slots, FIXED_SEGMENT_SIZE) —— slots ≤ 54 恒成立
        // （转运格满级 54、弹药格 27），
        // 于是长度恒为 54，等于**根本没截断**；更糟的是循环把超出 slots 的格子清成空，
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

    /**
     * 从某一段里取出<b>第一格非空</b>物品。
     *
     * <h2>【球堆】那一格是壳时怎么办（作者 2026-10-15）</h2>
     * <p>原来的实现是「整格端走」—— 这对散球没问题（一格就是一颗），
     * 但转运段现在存的是球堆，整格端走会把<b>一整个壳</b>当成「一颗球」交出去，
     * 于是越取越多。</p>
     *
     * <p>所以这里改成：<b>是壳就掏一颗出来，壳留剩</b>；掏空之后把那一格清掉
     * （壳变成空壳等于没内容，留着只会占格）。</p>
     */
    private static ItemStack takeFirst(List<ItemStack> segment) {
        for (int i = 0; i < segment.size(); i++) {
            ItemStack slot = segment.get(i);
            if (slot.isEmpty()) {
                continue;
            }
            if (BallClusterItem.isCluster(slot)) {
                ItemStack one = BallClusterItem.takeOne(slot);
                if (!one.isEmpty()) {
                    if (BallClusterItem.contents(slot).isEmpty()) {
                        segment.set(i, ItemStack.EMPTY);   // 掏空了 → 那一格清掉
                    }
                    return one;
                }
                // 空壳（理论上不该出现）：当空格处理，继续找下一格
                segment.set(i, ItemStack.EMPTY);
                continue;
            }
            segment.set(i, ItemStack.EMPTY);
            return slot;
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
            ItemStack taken = takeOneFromSlot(segment, transitIndex);
            // 另一段也裁一遍 —— 顺带清掉旧存档里已经存在的越界幽灵球
            setContents(pouch, new BallPouchContents(
                    cappedSegment(current.ammo(), tier(pouch).ammoSlots()), segment));
            return taken;
        }

        int ammoIndex = indexOfLowestDurability(current.ammo(), wanted);
        if (ammoIndex >= 0) {
            List<ItemStack> segment = new ArrayList<>(current.ammo());
            ItemStack taken = takeOneFromSlot(segment, ammoIndex);
            setContents(pouch, new BallPouchContents(
                    segment, cappedSegment(current.transit(), tier(pouch).transitSlots())));
            return taken;
        }

        return ItemStack.EMPTY;
    }

    /**
     * 从某一段的第 {@code index} 格里**取出一颗球**。
     *
     * <p>⚠️ 那一格是<b>壳</b>时要「掏一颗、壳留剩」（作者 2026-10-15）——
     * 原来直接写的是 {@code segment.remove(index)}，会把**整个壳**当成一颗球交出去，
     * 而下游（弩的装填、`createProjectile`）只认「球」 ⇒ 表现就是装不上 / 塞出一个球堆。</p>
     *
     * <p>掏空了就把那一格清掉（空壳不留在袋子里，和物品栏那边同一个规矩）。</p>
     */
    private static ItemStack takeOneFromSlot(List<ItemStack> segment, int index) {
        ItemStack slot = segment.get(index);
        if (BallClusterItem.isCluster(slot)) {
            ItemStack one = BallClusterItem.takeOne(slot);      // 就地扣一颗（最低耐久那颗）
            if (BallClusterItem.contents(slot).isEmpty()) {
                segment.remove(index);                          // 掏空了 —— 那一格清掉
            } else {
                segment.set(index, slot);                       // 还有剩 —— 壳留在原格
            }
            return one;
        }
        segment.remove(index);
        return slot;
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
            // ★ 球堆：耐久要看**壳里那颗球**（作者 2026-10-15）
            //
            //   壳自身没有 `TOUGHNESS_LEFT` 组件，`remainingToughness(壳)` 会返回 MAX_VALUE
            //   ⇒ 壳永远排在最后 ⇒ **袋里的球堆永远取不出来**（弩装填时就装不上它）。
            //   摊开之后拿到的才是「这颗球还剩几次」。
            int left = remainingToughness(BallClusterItem.asBall(slot));
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
        // 【球堆】先把壳摊开成里面那颗球（作者 2026-10-15）。
        //
        // 不摊的话，下面那句 `a.getItem() != b.getItem()` 会直接判「不同种」——
        // 因为壳的 item 是 ball_cluster、球的 item 是别的，两者永远不相等。
        // 后果是「袋里有没有这种球」「从袋里取一颗同类球」全都**查不到壳里的球**，
        // 弩装填时就装不上袋里的球。
        //
        // 摊在这一处，下面 takeSameKindFrom / segmentHasKind / hasSameKindInTransit /
        // hasSameKindInAmmo 四个调用点一并受益。
        ItemStack x = BallClusterItem.asBall(a);
        ItemStack y = BallClusterItem.asBall(b);
        if (x.isEmpty() || y.isEmpty() || x.getItem() != y.getItem()) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(withoutToughness(x), withoutToughness(y));
    }

    /** 复制一份并抹掉耐久组件 —— 只用于「同种」比较 */
    private static ItemStack withoutToughness(ItemStack stack) {
        ItemStack copy = stack.copyWithCount(1);
        copy.remove(ModComponents.TOUGHNESS_LEFT.get());
        return copy;
    }

    /**
     * 从某一段里取出第一颗同类的球。
     *
     * <p>⚠️ 那一格是<b>球堆</b>时要掏一颗出来、壳留剩（作者 2026-10-15）——
     * 原来的实现是「整 stack 取走」，会把一整个壳当成一颗球交出去。</p>
     */
    private static ItemStack takeSameKindFrom(List<ItemStack> segment, ItemStack wanted) {
        for (int i = 0; i < segment.size(); i++) {
            ItemStack slot = segment.get(i);
            if (slot.isEmpty() || !sameKind(slot, wanted)) {
                continue;
            }
            if (BallClusterItem.isCluster(slot)) {
                ItemStack one = BallClusterItem.takeOne(slot);
                if (one.isEmpty()) {
                    continue;   // 空壳，当空格处理
                }
                if (BallClusterItem.contents(slot).isEmpty()) {
                    segment.set(i, ItemStack.EMPTY);
                }
                return one;
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
                    // ⚠️ 先写一个**版本标记字节**（作者 2026-10-16 报的联机崩溃）：
                    //    NeoForge 的 openMenu 在「额外数据为空」时会退回原版 ClientboundOpenScreenPacket，
                    //    而那条路径客户端工厂拿到的是 **null** buf ⇒ 客户端一句 decode 就 NPE，
                    //    并且会被 disconnect（表现为「非主机玩家一开袋子就崩/掉线」）。
                    //    写一个字节就彻底堵死这条路径；读侧在 BallPouchMenu.decodePouch 里对称处理。
                    buffer -> {
                        buffer.writeByte(BallPouchMenu.POUCH_MENU_DATA_VERSION);
                        ItemStack.STREAM_CODEC.encode(buffer, pouch);
                    });
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
                        BallPouchContents.countBalls(contents.ammo()),
                        BallPouchContents.countFilled(contents.ammo()), tier.ammoSlots())
                .withStyle(ChatFormatting.AQUA));

        // 【球堆】（作者 2026-10-15）：转运段一格可能装着一整堆，
        // 所以这里同时报「多少颗」和「用了几格」——
        // 只报格数的话，装了一堆球却显示「1 / 18」，看起来像球丢了。
        consumer.accept(Component.translatable("tooltip.more_balls.pouch.transit",
                        BallPouchContents.countBalls(contents.transit()),
                        BallPouchContents.countFilled(contents.transit()), tier.transitSlots())
                .withStyle(ChatFormatting.YELLOW));

        if (!tier.isMax()) {
            consumer.accept(Component.translatable("tooltip.more_balls.pouch.next",
                            Component.translatable("pouch.more_balls.tier." + tier.next().id()))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
