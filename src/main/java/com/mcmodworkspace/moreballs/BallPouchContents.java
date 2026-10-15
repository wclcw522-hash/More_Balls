package com.mcmodworkspace.moreballs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 魔丸收纳袋里装着的东西。
 *
 * <p>作者指定的两段式结构：</p>
 * <ul>
 *   <li><b>弹药格（ammo）</b> —— 只放满耐久的球</li>
 *   <li><b>转运格（transit）</b> —— 只放用过的球（耐久有消耗的）</li>
 * </ul>
 *
 * <p>两段<b>各存</b> {@code FIXED_SEGMENT_SIZE}（54）格；其中该等级真正可用的格数由
 * {@link BallPouchTier} 决定（弹药 {@code ammoSlots()} / 转运 {@code transitSlots()}）。
 * 弹药段的第 i 格和转运段的第 i 格
 * 是两回事 —— 用<b>两个独立的列表</b>存，而不是「一个大列表切两半」，
 * 这样界面拖动、容量变化时都不容易串位。</p>
 *
 * <h2>为什么不用原版的 {@code ItemContainerContents}</h2>
 * <p>那个组件是「单段、平铺」的，塞不下「两段各有分工」这个语义 ——
 * 而收纳袋的核心恰恰是这两段不能混。自己写一个 record 更直观，
 * 编解码也更省（不用为每格都带一遍空标记）。</p>
 */
public record BallPouchContents(List<ItemStack> ammo, List<ItemStack> transit) {

    /** 空袋 */
    public static final BallPouchContents EMPTY = new BallPouchContents(List.of(), List.of());

    /**
     * 每段的<b>存储</b>格数（不是实际可用格数 —— 那个由 {@link BallPouchTier} 决定）。
     *
     * <p>⚠️ 2026-10-10 作者要求「中转区容量翻倍」后，满级转运段要用到
     * {@code 27 × 2 = 54} 格，所以这里从 27 扩到 54。</p>
     *
     * <p>⚠️ 固定的只是<b>存储</b>格数，界面尺寸<b>不</b>恒定：{@link BallPouchMenu}
     * 按各等级的实际格数排行，高度随等级变（见 {@code guiHeight()}），等级决定
     * 「哪些格子解锁」、没解锁的在界面上盖灰禁用。升级不必重建容器。</p>
     *
     * <p>旧存档里的段还是按当时的长度存的，读取时由 {@link #resize} 补齐 —— 不会丢东西。</p>
     */
    public static final int FIXED_SEGMENT_SIZE = 54;

    /**
     * 编解码用「可空堆」那一套（{@code OPTIONAL_*}）——
     * 空格要能原样存下来，否则格子会被压紧、玩家摆好的位置就串了。
     */
    public static final Codec<BallPouchContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("ammo").forGetter(BallPouchContents::ammo),
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("transit").forGetter(BallPouchContents::transit)
    ).apply(instance, BallPouchContents::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, BallPouchContents> STREAM_CODEC =
            StreamCodec.composite(
                    ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()),
                    BallPouchContents::ammo,
                    ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()),
                    BallPouchContents::transit,
                    BallPouchContents::new);

    /** 这一段是不是空的 */
    public static boolean isEmptySegment(List<ItemStack> segment) {
        for (ItemStack stack : segment) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 整袋是不是空的 */
    public boolean isEmpty() {
        return isEmptySegment(this.ammo) && isEmptySegment(this.transit);
    }

    /**
     * 把某一段补齐到指定格数（多出来的截掉）。
     *
     * <p>升级袋子时容量会变大、降级（理论上不会发生，但存档可能被改）会变小，
     * 每次都重新规整一遍最稳。</p>
     */
    public static List<ItemStack> resize(List<ItemStack> segment, int size) {
        List<ItemStack> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(i < segment.size() ? segment.get(i) : ItemStack.EMPTY);
        }
        return out;
    }

    /**
     * 按固定容量规整两段长度。
     *
     * <p>⚠️ {@code tier} <b>不参与计算</b>：两段一律补齐到 {@link #FIXED_SEGMENT_SIZE}（54），
     * 方法体只用到这个常量，等级在这里没有作用。真正按等级区分的是两处：
     * <b>界面</b>按 {@code tier.ammoSlots()} / {@code tier.transitSlots()} 决定哪些格子解锁
     * （其余盖灰禁用），<b>写入端</b>（{@code BallPouchItem.cappedSegment}）按同一对数值截断。
     * 形参保留只是为了调用点语义清楚（升级时按新等级规整长度）。</p>
     */
    public BallPouchContents fitTo(BallPouchTier tier) {
        return new BallPouchContents(
                resize(this.ammo, FIXED_SEGMENT_SIZE),
                resize(this.transit, FIXED_SEGMENT_SIZE));
    }

    /** 统计某一段里非空格的占用数（= 用掉了几格，仍然给容量对照用） */
    public static int countFilled(List<ItemStack> segment) {
        int n = 0;
        for (ItemStack stack : segment) {
            if (!stack.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 统计某一段里<b>实际装了多少颗球</b>。
     *
     * <h2>为什么需要它（作者 2026-10-15 反馈）</h2>
     * <p>转运段现在存的是<b>球堆</b> —— 一格可能装着几十颗。而 {@link #countFilled}
     * 数的是「占了几格」，于是袋子里明明装了一堆球，tooltip 上却写着「1 / 18」，
     * 看起来像球丢了。</p>
     *
     * <p>所以那个数是给容量对照用的，这个数才是「我有多少颗」。</p>
     *
     * <h2>怎么算</h2>
     * <ul>
     *   <li><b>球堆</b> → 取它内部球列的长度（{@link BallClusterContents#size()}）</li>
     *   <li><b>散球</b> → 取物品堆的数量（满耐久的球能叠，一格可能好几颗）</li>
     * </ul>
     */
    public static int countBalls(List<ItemStack> segment) {
        int n = 0;
        for (ItemStack stack : segment) {
            if (stack.isEmpty()) {
                continue;
            }
            n += BallClusterItem.isCluster(stack)
                    ? BallClusterItem.contents(stack).size()
                    : stack.getCount();
        }
        return n;
    }
}
