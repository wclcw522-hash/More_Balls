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
 * <p>两段各占 {@code tier.ammoSlots()} 格，所以弹药段的第 i 格和转运段的第 i 格
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
     * 每段的<b>固定</b>格数 —— 就按满级（下界合金）的 27 格存。
     *
     * <p>作者的方案：界面尺寸永远按满级来（原版箱子那么大），等级只决定
     * <b>哪些格子解锁</b>、没用到的格子盖灰禁用。所以容器的格子数也固定，
     * 不必随等级伸缩 —— 这样升级时不需要重建界面，纹理也永远对得齐。</p>
     */
    public static final int FIXED_SEGMENT_SIZE = 27;

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

    /** 按固定容量规整两段长度 */
    public BallPouchContents fitTo(BallPouchTier tier) {
        return new BallPouchContents(
                resize(this.ammo, FIXED_SEGMENT_SIZE),
                resize(this.transit, FIXED_SEGMENT_SIZE));
    }

    /** 统计某一段里非空格的占用数 */
    public static int countFilled(List<ItemStack> segment) {
        int n = 0;
        for (ItemStack stack : segment) {
            if (!stack.isEmpty()) {
                n++;
            }
        }
        return n;
    }
}
