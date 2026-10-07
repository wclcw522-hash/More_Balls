package com.mcmodworkspace.moreballs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 矿物被「探寻」加热的世界级数据。
 *
 * <h2>规则（作者指定）</h2>
 * <ul>
 *   <li>矿物<b>被探测期间</b>每刻积累 {@code 1 + 紧邻六面的矿物数量} 点热量</li>
 *   <li>热量达到 {@link #BREAK_THRESHOLD}（200）时<b>破坏该方块</b>，
 *       并以<b>时运 1</b> 的规则掉落<b>对应的矿锭</b></li>
 *   <li>矿物<b>没被检测</b>时，每刻自然消散 {@link #DECAY_PER_TICK}（2）点</li>
 * </ul>
 *
 * <h2>为什么存这里</h2>
 * 热量属于「世界上的某个方块位置」，既不是物品的也不是玩家的：
 * <ul>
 *   <li>球飞走了热度还在，所以要存档 → 用 {@link SavedData}</li>
 *   <li>多个球可能同时烤同一片矿，所以要按位置共享同一份数据</li>
 *   <li>玩家重进世界后热度不该凭空消失</li>
 * </ul>
 *
 * <p>升温发生在球的 tick 里（{@code BallProjectile}），消退与破坏由
 * {@code BallHeatHandler} 的世界 tick 统一结算 —— 这样即使球早就不在了，
 * 余热也会自己散掉、或者在攒够时把矿炸开。</p>
 */
public class ProspectingHeatData extends SavedData {

    /** 破坏方块的阈值（普通矿石） */
    public static final float BREAK_THRESHOLD = 200.0F;

    /** 粗矿块的阈值倍率：粗矿比矿石结实得多（作者指定 8 倍） */
    public static final float RAW_BLOCK_THRESHOLD_MULTIPLIER = 8.0F;

    /** 未被检测时每刻消散的热量（作者指定：1 点 / 5 刻 = 0.2 点/刻） */
    public static final float DECAY_PER_TICK = 0.2F;

    /**
     * 每条记录：位置 + 当前热量 + 上次被加热的游戏刻 + <b>加热者</b>。
     *
     * <p>{@code heater} 是 2.6.0 为成就「这真的科学吗？」加的：矿物被烤熟的那一刻是在
     * 世界 tick 里由方块余热结算的，那儿根本不知道是谁烤的，所以得在<b>加温的时候</b>
     * 顺手把来源记下来。存成 UUID 字符串，空串表示没记到（老存档读进来就是空串）。</p>
     */
    private record Entry(long pos, float heat, long lastHeatedTick, String heater) {

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("pos").forGetter(Entry::pos),
                Codec.FLOAT.fieldOf("heat").forGetter(Entry::heat),
                Codec.LONG.fieldOf("tick").forGetter(Entry::lastHeatedTick),
                // ⚠️ optional：升级前的存档里没有这个字段。
                //    不加 optional 会让整份 SavedData 解析失败 —— 那等于热量系统整个坏掉。
                Codec.STRING.optionalFieldOf("heater", "").forGetter(Entry::heater)
        ).apply(instance, Entry::new));
    }

    public static final Codec<ProspectingHeatData> CODEC = Entry.CODEC.listOf()
            .xmap(ProspectingHeatData::new, ProspectingHeatData::toEntries);

    public static final SavedDataType<ProspectingHeatData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "prospecting_heat"),
            ProspectingHeatData::new,
            CODEC);

    private final Map<Long, Entry> entries = new HashMap<>();

    public ProspectingHeatData() {
    }

    private ProspectingHeatData(List<Entry> list) {
        for (Entry entry : list) {
            this.entries.put(entry.pos(), entry);
        }
    }

    private List<Entry> toEntries() {
        return new ArrayList<>(this.entries.values());
    }

    /** 取（或新建）当前维度的热量数据 */
    public static ProspectingHeatData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * 给某格加热（被探测时调用）。
     *
     * @param amount 本次增加的热量
     * @param tick   当前游戏刻
     */
    public void addHeat(BlockPos pos, float amount, long tick) {
        addHeat(pos, amount, tick, "");
    }

    /**
     * 给某格加热，并记下是谁加热的。
     *
     * <p>{@code heater} 是加热者的 UUID 字符串；矿物被烤熟时靠它把成就发给对的人。
     * 传空串表示不记来源（老调用点走上面那个重载）。</p>
     *
     * <p>后来者覆盖前者：谁最后烤的这格方块，就算谁烤熟的。矿物被多个热源一起烤时，
     * 这个是合理归属 —— 反正最后一击是谁的就是谁的。</p>
     */
    public void addHeat(BlockPos pos, float amount, long tick, String heater) {
        long key = pos.asLong();
        Entry old = this.entries.get(key);
        float heat = (old != null ? old.heat() : 0.0F) + amount;
        // 空串不覆盖已有的来源，免得「先记到人、后被无名加热」把来源冲掉
        String who = heater.isEmpty() && old != null ? old.heater() : heater;
        this.entries.put(key, new Entry(key, heat, tick, who));
        this.setDirty();
    }

    /**
     * 把某格的热量压到不超过 {@code max}。
     *
     * <p>给「普通方块 200 封顶」用的（作者 2026-10-08 指定）：非金属方块也能被烤热，
     * 但到顶就不再涨 —— 它们不会像矿物那样到阈值就爆开，只是变成一块烫脚的方块。</p>
     */
    public void capHeat(BlockPos pos, float max) {
        long key = pos.asLong();
        Entry old = this.entries.get(key);
        if (old == null || old.heat() <= max) {
            return;
        }
        this.entries.put(key, new Entry(key, max, old.lastHeatedTick(), old.heater()));
        this.setDirty();
    }

    /**
     * 这格方块的加热者 UUID 字符串；没有记录或没记来源时返回空串。
     */
    public String getHeater(BlockPos pos) {
        Entry entry = this.entries.get(pos.asLong());
        return entry == null ? "" : entry.heater();
    }

    /** 当前热量；没有记录则为 0 */
    public float getHeat(BlockPos pos) {
        Entry entry = this.entries.get(pos.asLong());
        return entry != null ? entry.heat() : 0.0F;
    }

    /** 遍历所有记录（供世界 tick 结算用） */
    public List<BlockPos> positions() {
        List<BlockPos> list = new ArrayList<>(this.entries.size());
        for (long key : this.entries.keySet()) {
            list.add(BlockPos.of(key));
        }
        return list;
    }

    /**
     * 结算某一格：先按「自上次加热以来经过的刻数」消散，再判断是否该炸。
     *
     * @param threshold 该方块的破坏阈值（粗矿块是普通矿石的 8 倍）
     * @return 是否达到了破坏阈值
     */
    public boolean settle(BlockPos pos, long tick, float threshold) {
        long key = pos.asLong();
        Entry entry = this.entries.get(key);
        if (entry == null) {
            return false;
        }

        long elapsed = tick - entry.lastHeatedTick();
        float heat = entry.heat();
        if (elapsed > 0) {
            heat = Math.max(0.0F, heat - DECAY_PER_TICK * elapsed);
            // 消散到零就整条删掉，别让存档越滚越大
            if (heat <= 0.0F) {
                this.entries.remove(key);
                this.setDirty();
                return false;
            }
            // 消退时保留加热者 —— 否则余热一散，来源就丢了，成就也发不出去
            this.entries.put(key, new Entry(key, heat, tick, entry.heater()));
            this.setDirty();
        }

        return heat >= threshold;
    }

    /** 移除一条记录（方块被破坏 / 消失时调用） */
    public void remove(BlockPos pos) {
        if (this.entries.remove(pos.asLong()) != null) {
            this.setDirty();
        }
    }

    /** 某格是否还在被测（决定要不要继续加热） */
    public @Nullable Entry peek(BlockPos pos) {
        return this.entries.get(pos.asLong());
    }
}
