package com.mcmodworkspace.moreballs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「球堆」里装着的东西 —— 一批<b>同种</b>的球。
 *
 * <h2>为什么需要它</h2>
 * <p>球的耐久记在 {@link ModComponents#TOUGHNESS_LEFT} 组件上，而 MC 的堆叠判定要求
 * <b>组件完全一致</b>。于是副手打出去一组球、回收回来时，每颗挨的次数都不一样，
 * 组件值自然各不相同 —— <b>每颗独占一格</b>，一趟下来背包被吃掉大半。</p>
 *
 * <p>球堆就是给这些球套一个「壳」：<b>壳本身只记「哪种球 + 哪一档」</b>，
 * 内部的球则<b>原样存着</b>（{@link #balls} 里每颗都带自己精确的耐久）。
 * 取出时把原来那颗还回去，<b>耐久一分不差</b>。</p>
 *
 * <h2>⚠️ 壳与壳之间不能互相叠</h2>
 * <p>因为 {@link #balls} 的内容本身就是组件的一部分 —— 两堆球只要有一颗粒子的耐久不同，
 * 组件就不同，MC 就不会让它们叠在一起。<b>但这不影响本机制的目的</b>：
 * 一个壳能装 {@link #MAX_BALLS} 颗，<b>自己就只占一格</b>，
 * 所以「减少占格」这件事根本不依赖「壳互相叠」。</p>
 *
 * <p>（这和潜影盒一样：装满了的潜影盒之间也叠不了，但它本来就只占一格。）</p>
 *
 * <h2>{@link #balls} 的排序约定</h2>
 * <p><b>始终按剩余耐久升序</b>。作者指定「右键取出时给耐久最低的」、
 * tooltip 也要「显示最低的那一颗」—— 保持有序之后，这两件事都只是取下标 0，
 * 不用每次遍历找最小值。</p>
 */
public record BallClusterContents(ItemStack icon, List<ItemStack> balls) {

    /** 一个球堆最多装多少颗 —— 与 {@code ModItems.BALL_STACK_SIZE} 一致 */
    public static final int MAX_BALLS = 64;

    /** 空壳（内部没有任何球）。不带 {@code BALL_CLUSTER} 组件 = 这个 */
    public static final BallClusterContents EMPTY =
            new BallClusterContents(ItemStack.EMPTY, List.of());

    /**
     * 编解码用<b>不可空</b>的 {@code ItemStack.CODEC}（不是收纳袋那套 {@code OPTIONAL_}）——
     * 球堆里不允许有空格，它是个紧凑列表，不是摆位容器。
     */
    public static final Codec<BallClusterContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ItemStack.CODEC.fieldOf("icon").forGetter(BallClusterContents::icon),
            ItemStack.CODEC.listOf().fieldOf("balls").forGetter(BallClusterContents::balls)
    ).apply(instance, BallClusterContents::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, BallClusterContents> STREAM_CODEC =
            StreamCodec.composite(
                    ItemStack.STREAM_CODEC,
                    BallClusterContents::icon,
                    ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    BallClusterContents::balls,
                    BallClusterContents::new);

    /** 这一堆里有多少颗 —— <b>图标角落那个数字就是它</b>，现算，不另存字段 */
    public int size() {
        return this.balls.size();
    }

    public boolean isEmpty() {
        return this.balls.isEmpty();
    }

    /** 这一堆是哪一档（由代表球算出来；代表球没了就按最低那颗算） */
    public BallClusterTier tier() {
        ItemStack probe = this.icon.isEmpty() ? lowest() : this.icon;
        return probe.isEmpty() ? BallClusterTier.PRISTINE : BallClusterTier.of(probe);
    }

    /** 这一堆里耐久最低的那颗；空堆返回 {@link ItemStack#EMPTY} */
    public ItemStack lowest() {
        return this.balls.isEmpty() ? ItemStack.EMPTY : this.balls.get(0);
    }

    /**
     * 耐久最低的 {@code n} 颗（升序）。
     *
     * <p>作者指定：按住 Alt 时要显示「本堆叠耐久最少的 6 个球的剩余耐久」。</p>
     */
    public List<ItemStack> lowestN(int n) {
        int end = Math.min(n, this.balls.size());
        return List.copyOf(this.balls.subList(0, end));
    }

    /**
     * 这颗球能不能进这一堆。
     *
     * <p>两个条件：<b>同一种球</b>（物品相同）且<b>同一档位</b>。</p>
     *
     * <p>⚠️ 只比「档位」而不比「精确耐久」—— 这正是本机制的意义所在。
     * 进堆后每颗球的精确耐久仍然独立保存，只是不再影响它们能否待在一起。</p>
     */
    public boolean matches(ItemStack ball) {
        if (ball.isEmpty() || this.balls.isEmpty()) {
            return false;
        }
        // ===== 兼容性修订（作者 2026-10-15）=====
        //
        // ⚠️ 这里**只比球种，不再比档位**。
        //
        //    原先还有一条 `&& BallClusterTier.of(ball) == tier()`，于是同一个球种会按
        //    剩余耐久被拆成最多 5 个壳（PRISTINE / FLAWED / DAMAGED / BROKEN / CRUMBLING）。
        //    打完一局回收回来，玩家看到的就是**好几个各装一两颗的壳** ——
        //    作者原话「打出去收回来的还是不堆叠」说的就是这个。
        //
        //    而作者最初的需求写得很明确：「把一堆**耐久各异**的球收进一格」——
        //    「耐久各异」正是要**放在一起**，档位不该成为分开的理由。
        //
        //    每颗球的精确耐久仍然各自独立保存（balls 里每颗都带自己的 TOUGHNESS_LEFT），
        //    取出时一分不差地还回去 —— 这一点没有变。
        //    档位仍然用于**显示**（壳的名字、「最低耐久」那行 tooltip），只是不再用于分组。
        return ItemStack.isSameItem(ball, this.balls.get(0));
    }

    /**
     * <b>「最低耐久的那一组」的大小</b> —— 从下标 0 起、连续与最低那颗<b>耐久完全相同</b>的颗数。
     *
     * <h2>什么叫「一组」（作者 2026-10-15 定的口径）</h2>
     * <p>作者原话是「释放一组耐久最低的球」，同时又要「显示该耐久的球有几个」——
     * 这两句说的是同一件事：<b>「一组」= 耐久值相同的那一批</b>，
     * 不是固定 64 颗、也不是随便一批。</p>
     *
     * <p>{@link #balls} 始终按剩余耐久升序，所以这一组天然就排在开头，
     * 只要从 0 往后数到耐久变了为止 —— <b>不用新增字段</b>。</p>
     */
    public int lowestGroupSize() {
        if (this.balls.isEmpty()) {
            return 0;
        }
        int first = toughnessLeft(this.balls.get(0));
        int n = 1;
        while (n < this.balls.size() && toughnessLeft(this.balls.get(n)) == first) {
            n++;
        }
        return n;
    }

    /**
     * 取走开头 {@code n} 颗（也就是最低耐久的那一组），返回取完后的新内容。
     *
     * @param n 取走多少颗；超出总数时按总数算
     */
    public BallClusterContents withoutFirst(int n) {
        if (this.balls.isEmpty() || n <= 0) {
            return this;
        }
        int take = Math.min(n, this.balls.size());
        List<ItemStack> next = new ArrayList<>(this.balls.subList(take, this.balls.size()));
        return new BallClusterContents(next.isEmpty() ? ItemStack.EMPTY : this.icon, next);
    }

    /** 这一堆还装得下吗 */
    public boolean hasRoom() {
        return this.balls.size() < MAX_BALLS;
    }

    /**
     * 塞一颗球进来，返回新的内容。
     *
     * <p>塞不下、或种类/档位不符时<b>原样返回 {@code this}</b> —— 调用方拿返回值比一下
     * 就知道成没成，不用另开 boolean。</p>
     *
     * <p><b>⚠️ 按 {@code count} 拆成一颗一个元素</b>（作者 2026-10-15 实测反馈
     * 「同耐久的一组球整理后只剩 1 个」就是这条引起的）。</p>
     *
     * <h2>为什么必须拆</h2>
     * <p>背包里 8 颗同耐久的球是<b>一个 {@code count=8} 的栈</b>。原先这里写的是
     * {@code next.add(ball.copy())} —— 把<b>整个栈当成一个元素</b>塞进去，于是
     * `balls.size()` 返回 1，而 {@link #size()}（图标角标、tooltip、「一组」的数量、
     * `declusterInto` 的判据）全都拿它当「几颗」用 ⇒ 8 颗显示成 1 颗。</p>
     *
     * <p>现在一个元素 = 一颗球，`size()` 就是货真价实的颗数 ——
     * <b>六处口径一次性对齐，不用逐处改</b>。</p>
     *
     * <p>塞完重新按耐久升序排一次（见类注释里的排序约定）。</p>
     */
    public BallClusterContents withAdded(ItemStack ball) {
        if (ball.isEmpty() || this.balls.size() >= MAX_BALLS) {
            return this;
        }
        ItemStack iconNow = this.icon.isEmpty() ? ball.copyWithCount(1) : this.icon;
        if (!this.balls.isEmpty() && !this.matches(ball)) {
            return this;   // 种类或档位不符
        }
        List<ItemStack> next = new ArrayList<>(this.balls);
        // 一颗一个元素；装不下的部分留在原栈里（调用方靠返回值判断成没成）
        int room = MAX_BALLS - next.size();
        int add = Math.min(ball.getCount(), room);
        for (int i = 0; i < add; i++) {
            next.add(ball.copyWithCount(1));
        }
        return new BallClusterContents(iconNow, sortByLowest(next));
    }

    /**
     * 取出耐久最低的那一颗，返回<b>取出后的新内容</b>（record 不可变）。
     *
     * <p>取出的那颗由 {@link #lowest()} 拿 —— 它就是 {@code balls} 的第 0 个，
     * 所以「取最低」这件事在这里是免费的。</p>
     */
    public BallClusterContents withoutLowest() {
        if (this.balls.isEmpty()) {
            return this;
        }
        List<ItemStack> next = new ArrayList<>(this.balls);
        next.remove(0);
        return new BallClusterContents(next.isEmpty() ? ItemStack.EMPTY : this.icon, next);
    }

    /**
     * 按「剩余耐久」升序排。
     *
     * <p>口径与 {@link BallItem} 一致：读 {@code TOUGHNESS_LEFT} 组件，
     * <b>没带组件 = 满耐久</b>（排在最后）。</p>
     */
    private static List<ItemStack> sortByLowest(List<ItemStack> list) {
        List<ItemStack> out = new ArrayList<>(list);
        out.sort(Comparator.comparingInt(BallClusterContents::toughnessLeft));
        return out;
    }

    /**
     * 一颗球还剩几次 —— 没带组件时返回上限（代表满耐久）。
     *
     * <p>这里**不**返回 {@code Integer.MAX_VALUE}：满耐久的球应该在排序里排最后，
     * 而不是排最前。「取最低」取的是快坏的那颗，不是刚出炉的。</p>
     */
    private static int toughnessLeft(ItemStack stack) {
        Integer left = stack.get(ModComponents.TOUGHNESS_LEFT.get());
        if (left != null) {
            return left;
        }
        return Math.max(0, BallBehavior.profileFor(stack).toughness());
    }
}
