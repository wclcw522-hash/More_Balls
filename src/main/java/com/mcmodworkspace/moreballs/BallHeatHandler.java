package com.mcmodworkspace.moreballs;

import net.minecraft.core.BlockPos;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 「探寻」的降温与结算。
 *
 * <h2>为什么单独一个类</h2>
 * 升温发生在球那边（{@code BallProjectile#heatNearbyPlayers}），
 * 但<b>消退必须独立于球存在</b> —— 球飞走了、碎掉了、甚至被卸载了，
 * 身上的余热仍要按每刻 2 点散掉，否则热度会永久留下。
 *
 * <p>两者的分工靠 {@link ModAttachments#LAST_HEATED_TICK} 区分：
 * 本刻刚被加热过 → 交给球处理；否则 → 在这里消退。</p>
 *
 * <h2>作用对象是「所有生物」</h2>
 * 作者指定：<b>玩家和生物的盔甲都会被加伤点燃</b> —— 所以这里盯的是
 * {@link LivingEntity} 而不是 {@link Player}，僵尸骷髅戴着铁头盔一样会被烤。
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class BallHeatHandler {

    private BallHeatHandler() {
    }

    /** 矿物热量的结算间隔（刻）：不必每刻遍历 */
    private static final int SETTLE_INTERVAL = 5;

    /** 着火续燃时长（刻） */
    private static final int IGNITE_TICKS = 20;

    /**
     * 「烫脚」门槛 —— 方块热量过了这么多，就冒火焰粒子、并且踩上去会掉血
     * （作者 2026-10-08 指定：绝对热量 50，不是阈值的百分比）。
     */
    public static final float SCALDING_HEAT = 50.0F;

    /**
     * 方块的通用热量上限（作者指定 200）。
     *
     * <p>矿石/粗矿块攒到各自的阈值会被烤熟破坏；<b>其它方块</b>攒到 200 也到顶了，
     * 但不会被破坏 —— 只是变成「烫脚的高温方块」。</p>
     */
    public static final float COMMON_BLOCK_MAX_HEAT = 200.0F;

    /**
     * 生物的热量上限（作者指定 200）。
     *
     * <p>到顶之后不再继续涨。若这家伙身上穿着金属盔甲，就会<b>持续中「熔融物烧伤」</b>
     * —— 相当于把自己烤成了一块行走的烙铁。</p>
     */
    public static final float MAX_LIVING_HEAT = 200.0F;

    /** 满热生物每隔多少刻续一次熔融物烧伤（免得每刻都刷效果实例） */
    private static final int MOLTEN_REFRESH_INTERVAL = 20;

    /** 「烫脚」伤害的判定间隔（刻）—— 岩浆块本身是靠无敌帧限流的，这里给个自己的节流 */
    private static final int SCALD_INTERVAL = 10;

    /**
     * 生物 tick：没在被加热就自然降温。
     *
     * <p>用 {@link EntityTickEvent.Post} 而不是 {@code PlayerTickEvent}，
     * 这样怪物、动物、村民身上的热量同样会散 —— 它们也会被球烤。</p>
     */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity living) || living.level().isClientSide()) {
            return;
        }

        float heat = living.getData(ModAttachments.PROSPECTING_HEAT.get());
        if (heat <= 0.0F) {
            return;
        }

        // ===== 满热判定（作者 2026-10-08 指定）=====
        //
        // ⚠️ 必须放在下面那条「刚被加热就 return」**之前** ——
        // 否则正被球烤着的生物永远走不到这里，而「满热」恰恰只在被烤时才成立。
        checkMoltenOverload(living, heat);

        // 本刻（或上一刻）刚被球加热过 → 升温阶段，不消退
        int lastHeated = living.getData(ModAttachments.LAST_HEATED_TICK.get());
        if (living.tickCount - lastHeated <= 1) {
            return;
        }

        // 泡在水里散热快得多（作者指定：10 倍速）
        float decay = BallProspecting.PLAYER_HEAT_DECAY;
        if (living.isInWaterOrRain()) {
            decay *= BallProspecting.WATER_DECAY_MULTIPLIER;
        }

        float next = Math.max(0.0F, heat - decay);
        living.setData(ModAttachments.PROSPECTING_HEAT.get(), next);

        // 仍在着火阈值之上就续燃，直到降回 100 以下（作者指定）
        if (next > BallProspecting.PLAYER_HEAT_IGNITE) {
            living.igniteForTicks(IGNITE_TICKS);
        }

        emitHeatParticles(living, next / BallProspecting.PLAYER_HEAT_IGNITE);
    }

    /**
     * 烫脚：踩在热量过 {@link #SCALDING_HEAT} 的方块上会掉血（作者 2026-10-08 指定）。
     *
     * <p>表现和岩浆块一致 —— 伤害类型也用 {@code hotFloor}，所以死亡消息、
     * 附魔与药水抗性这些全都跟着原版走。</p>
     *
     * <p><b>遍历方向是「从方块找实体」而不是反过来</b>：有热量的方块通常只有个位数，
     * 而一个区块里的实体可能很多。只在方块上方那一个格子范围里捞，代价最小。</p>
     *
     * <p>节流 {@link #SCALD_INTERVAL} 刻一次；再加上原版受伤后的无敌帧，
     * 实际频率和岩浆块接近。</p>
     */
    private static void scaldPlayersStandingOnHotBlocks(ServerLevel level, ProspectingHeatData data) {
        if (level.getGameTime() % SCALD_INTERVAL != 0) {
            return;
        }
        for (BlockPos pos : data.positions()) {
            if (data.getHeat(pos) < SCALDING_HEAT) {
                continue;
            }
            // 只捞「贴着这一格上方」的实体 —— 脚踩在方块上时身体就在这一格里
            AABB above = new AABB(pos.getX(), pos.getY() + 1.0D, pos.getZ(),
                    pos.getX() + 1.0D, pos.getY() + 2.0D, pos.getZ() + 1.0D);
            for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, above)) {
                // 得真的站在上面（不是跳过去擦到）
                if (!living.onGround()) {
                    continue;
                }
                living.hurtServer(level, level.damageSources().hotFloor(), 1.0F);
            }
        }
    }

    /**
     * 满热惩罚：热量顶到 {@link #MAX_LIVING_HEAT} 且身上<b>穿着金属盔甲</b>时，
     * 持续中「熔融物烧伤」（作者 2026-10-08 指定）。
     *
     * <p>思路是「盔甲被烤透了贴着自己烫」—— 所以判据用
     * {@link BallProspecting#countMetalEquipment}（四护甲位 + 主副手，每件算 1），
     * 光着身子即使热量到顶也不会中招。</p>
     *
     * <p>每 {@link #MOLTEN_REFRESH_INTERVAL} 刻续一次而不是每刻 —— 效果本身有持续时间，
     * 每刻新建实例纯属浪费。</p>
     */
    private static void checkMoltenOverload(LivingEntity living, float heat) {
        if (heat < MAX_LIVING_HEAT) {
            return;
        }
        // 到顶之后再涨就压住（作者指定：200 是上限）
        if (heat > MAX_LIVING_HEAT) {
            living.setData(ModAttachments.PROSPECTING_HEAT.get(), MAX_LIVING_HEAT);
        }
        if (BallProspecting.countMetalEquipment(living) <= 0) {
            return;   // 没穿金属的，烫不着自己
        }
        if (living.tickCount % MOLTEN_REFRESH_INTERVAL != 0) {
            return;
        }
        if (living.level() instanceof ServerLevel level) {
            // 效果挂在自己身上，归属也算自己 —— 这是「自己的盔甲把自己烫了」
            MoltenBurnEffect.applyMoltenBurn(level, living, living, BallBehavior.MOLTEN_BURN_TICKS);
        }
    }

    /**
     * 按热量比例冒烟：超过阈值 25% 冒黑烟、超过 50% 冒火苗（作者指定，都只是「少量」）。
     *
     * <p>用 {@code sendParticles} 广播给附近玩家，每刻最多一个粒子 —— 有提示但不刷屏。</p>
     */
    private static void emitHeatParticles(LivingEntity living, float ratio) {
        ServerLevel level = (ServerLevel) living.level();
        if (ratio > 0.5F) {
            level.sendParticles(ParticleTypes.FLAME,
                    living.getX(), living.getY() + living.getBbHeight() * 0.75D, living.getZ(),
                    1, 0.2D, 0.2D, 0.2D, 0.005D);
        } else if (ratio > 0.25F) {
            level.sendParticles(ParticleTypes.SMOKE,
                    living.getX(), living.getY() + living.getBbHeight() * 0.75D, living.getZ(),
                    1, 0.2D, 0.2D, 0.2D, 0.005D);
        }
    }

    /**
     * 世界 tick：结算所有被加热过的矿物。
     *
     * <p>球只负责升温，降温与爆破在这里做 —— 这样球飞走、碎掉、甚至被卸载之后，
     * 余热仍会自己散掉；攒够 200 点的矿也会照常炸开。</p>
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            long gameTime = level.getGameTime();
            if (gameTime % SETTLE_INTERVAL != 0) {
                continue;
            }

            ProspectingHeatData data = ProspectingHeatData.get(level);
            for (BlockPos pos : data.positions()) {
                BlockState state = level.getBlockState(pos);

                // 方块已经被挖掉/替换了 —— 记录必须丢掉。
                //
                // 原来只在「烤爆成功」那条路上 remove，于是玩家把热方块挖走后，
                // 残留记录还会继续冒粒子、继续烫脚（对着空气），
                // 直到热量自然衰减到 0 为止（作者反馈过「挖掉之后还在烧」）。
                if (state.isAir()) {
                    data.remove(pos);
                    continue;
                }

                if (BallProspecting.isBreakableOre(level, state)) {
                    // ===== 可破坏的金属矿 / 粗矿块：攒够阈值就被烤熟破坏 =====
                    // 判据是「是矿 **且** 能解析出金属产物」—— 判不出产物就不爆，
                    // 免得退回按原版掉落表掉出方块本身（作者 2026-10-08 报的）。
                    float threshold = ProspectingHeatData.BREAK_THRESHOLD
                            * thresholdMultiplier(state);
                    if (data.settle(pos, gameTime, threshold)) {
                        burstOre(level, pos, data);
                        continue;
                    }
                } else {
                    // ===== 其它方块：200 封顶，**不破坏**（作者 2026-10-08 指定）=====
                    // 它们会一直冒火、踩上去烫脚，但方块本身永远留在原地。
                    data.settle(pos, gameTime, COMMON_BLOCK_MAX_HEAT);
                    data.capHeat(pos, COMMON_BLOCK_MAX_HEAT);
                }

                // 粒子按「绝对热量」判，不是按阈值比例（作者指定：过 50 就冒火）
                emitBlockParticles(level, pos, data.getHeat(pos));
            }

            // 烫脚：热量过 50 的热方块和岩浆块一样伤人（作者指定）。
            // 单独扫一遍实体 —— 只有在有人踩上去时才需要判定，跟着方块循环走会白算。
            scaldPlayersStandingOnHotBlocks(level, data);
        }
    }

    /**
     * 被烤热的方块冒不冒火 —— <b>热量过 {@link #SCALDING_HEAT}（50）</b>就冒火焰粒子。
     *
     * <p>作者 2026-10-08 指定：门槛是<b>绝对热量 50 点</b>，不是「阈值的百分之多少」。
     * 之前写的是 {@code ratio > 0.5}，换算过来是 200×0.5 = 100 点，比作者要求的晚一倍。</p>
     *
     * <p>方块这一侧没法像实体一样每刻发粒子（结算本身就是每 5 刻一次），
     * 所以频率天然更低，正好符合「少量」。</p>
     */
    private static void emitBlockParticles(ServerLevel level, BlockPos pos, float heat) {
        if (heat >= SCALDING_HEAT) {
            level.sendParticles(ParticleTypes.FLAME,
                    pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D,
                    1, 0.25D, 0.05D, 0.25D, 0.005D);
        } else if (heat > 0.0F) {
            level.sendParticles(ParticleTypes.SMOKE,
                    pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D,
                    1, 0.25D, 0.05D, 0.25D, 0.005D);
        }
    }

    /**
     * 粗矿块比普通矿石结实得多 —— 作者指定阈值为矿石的 <b>8 倍</b>。
     *
     * <p>判定看方块注册名里有没有 {@code raw}（原版的粗铁块 / 粗铜块 / 粗金块都是
     * {@code raw_iron_block} 之类），模组按惯例命名的粗矿块同样适用。</p>
     */
    private static float thresholdMultiplier(BlockState state) {
        String path = state.getBlock().builtInRegistryHolder().key().identifier().getPath();
        return path.contains("raw") ? ProspectingHeatData.RAW_BLOCK_THRESHOLD_MULTIPLIER : 1.0F;
    }

    /**
     * 矿物烤熟：破坏方块，并按<b>时运 1</b> 的规则掉落<b>对应的矿锭</b>。
     *
     * <p>注意掉的是「锭」而不是原版的粗矿 —— 所以不走原版掉落表，而是取熔炼配方产物；
     * 方块本身用 {@code destroyBlock(pos, false)} 清掉，避免同时掉出一份粗矿。</p>
     */
    private static void burstOre(ServerLevel level, BlockPos pos, ProspectingHeatData data) {
        BlockState state = level.getBlockState(pos);
        // 先取出加热者再移除记录 —— remove 之后就问不到了
        awardHeatSmelt(level, pos, data);
        data.remove(pos);

        // ⚠️ 这里原来在产物为空时走 destroyBlock(pos, true) —— 那会按原版掉落表
        //    掉出**方块本身**，正是「粗矿块被熔炼后掉回粗矿块」的原因。
        //    现在改成留在原地（记录已 remove，回到「其它方块」那条路继续冒火烫脚）。
        ItemStack product = BallProspecting.heatProduct(level, state);
        if (product.isEmpty()) {
            return;
        }

        // 时运 1 的曲线：33% 概率翻倍（原版 applyBonus 对时运 I 的规则）
        int count = level.getRandom().nextFloat() < 0.33F ? 2 : 1;

        level.destroyBlock(pos, false);
        Block.popResource(level, pos, product.copyWithCount(count));
    }

    /**
     * 成就「这真的科学吗？」：第一次用热量系统把矿物烤熟。
     *
     * <p>发在<b>加热者</b>头上 —— 也就是当初把这格方块烤热的那颗球的主人。
     * 记录里存的是 UUID 字符串，这里反查在线玩家；离线或没记来源就跳过。</p>
     */
    private static void awardHeatSmelt(ServerLevel level, BlockPos pos, ProspectingHeatData data) {
        String heater = data.getHeater(pos);
        if (heater.isEmpty() || level.getServer() == null) {
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(heater);
        } catch (IllegalArgumentException ignored) {
            return;   // 记录被改坏了也别炸
        }
        Player player = level.getServer().getPlayerList().getPlayer(id);
        if (player != null) {
            ModAdvancements.award(player, ModAdvancements.HEAT_SMELT);
        }
    }
}
