package com.mcmodworkspace.moreballs.entity;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.monster.Enemy;
import com.mcmodworkspace.moreballs.BallBehavior;
import com.mcmodworkspace.moreballs.BallFragments;
import com.mcmodworkspace.moreballs.BallPouchHelper;
import com.mcmodworkspace.moreballs.MoltenBurnEffect;
import com.mcmodworkspace.moreballs.BallItem;
import com.mcmodworkspace.moreballs.BallProspecting;
import com.mcmodworkspace.moreballs.BallRoll;
import com.mcmodworkspace.moreballs.BallWeight;
import com.mcmodworkspace.moreballs.BallMobAI;
import com.mcmodworkspace.moreballs.BallMorph;
import com.mcmodworkspace.moreballs.BallThunder;
import com.mcmodworkspace.moreballs.BallTransmute;
import com.mcmodworkspace.moreballs.PiglinLure;
import com.mcmodworkspace.moreballs.ModAttachments;
import com.mcmodworkspace.moreballs.ModComponents;
import com.mcmodworkspace.moreballs.ModAdvancements;
import com.mcmodworkspace.moreballs.ModEnchantments;
import com.mcmodworkspace.moreballs.ModTags;
import com.mcmodworkspace.moreballs.ModEntities;
import com.mcmodworkspace.moreballs.ModItems;
import com.mcmodworkspace.moreballs.ProspectingHeatData;
import com.mcmodworkspace.moreballs.MoreBalls;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 「球」的通用投射物 —— 右键投掷与弩发射共用同一个实体。
 *
 * <p>在物理链路继承原版雪球的基础上，叠加本模组的概念：
 * 伤害 / 重量 / 穿透 / 坚固 / 弹射 / 滚动 / 静止拾取 / 球间弹性碰撞。</p>
 */
public class BallProjectile extends ThrowableItemProjectile {

    private static final EntityDataAccessor<Byte> PIERCE_LEVEL =
            SynchedEntityData.defineId(BallProjectile.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> WEIGHT =
            SynchedEntityData.defineId(BallProjectile.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> FROM_CROSSBOW =
            SynchedEntityData.defineId(BallProjectile.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> TOUGHNESS =
            SynchedEntityData.defineId(BallProjectile.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BOUNCE =
            SynchedEntityData.defineId(BallProjectile.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> SETTLED =
            SynchedEntityData.defineId(BallProjectile.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> ROLLING =
            SynchedEntityData.defineId(BallProjectile.class, EntityDataSerializers.BOOLEAN);

    /** 判定为静止的速度平方阈值（约 0.03 格/刻） */
    private static final double SETTLE_SPEED_SQR = 0.0009D;

    /** 静止后存活时长：2 分钟 = 2400 刻 */
    private static final int SETTLED_LIFETIME_TICKS = 2400;

    /**
     * 反弹时把球沿碰撞面法线推离的距离。
     *
     * <p>投射物命中后会被摆到方块<b>表面</b>上，而球的判定盒有 0.25 高 ——
     * 球心贴面意味着下半截嵌在方块里，下一 tick 一动就再次命中地面，
     * 于是每 tick 扣一次耐久。</p>
     */
    private static final double SURFACE_PUSH = 0.02D;

    /** 滚动时球底与方块顶面之间留的间隙，避免每 tick 都判定到地面碰撞（那就是抽搐的来源） */
    private static final double GROUND_CLEARANCE = 0.01D;

    /** 静止后允许被右键选中的半径 —— 球本身很小，给宽一点才点得中 */
    private static final float PICK_RADIUS = 1.0F;

    /** 飞行中的拾取半径（用于接球）：比静止时小，既能接住又不会太挡准星 */
    private static final float FLYING_PICK_RADIUS = 0.5F;

    /** 接球时承受的伤害比例 —— 作者指定：原本的一半 */
    private static final float CATCH_DAMAGE_RATIO = 0.5F;

    // （这里原本有个 SPEED_DAMAGE_CAP = 3.0 的伤害上限，作者 2026-10-07 要求去掉，
    //   所以常量本身也一并删了 —— 免得留个名字让人以为还有上限。）

    /**
     * 投掷者要等多少刻之后才能接回自己扔出的球（0.5 秒）。
     *
     * <p>只挡「出手瞬间立刻收回」—— 再往后就是正常的接球，该接得到。</p>
     */
    private static final int OWNER_SELF_PICKUP_DELAY_TICKS = 10;

    /**
     * 出手后的「无害期」：0.1 秒 = 2 刻（作者指定）。
     *
     * <p>贴脸投掷（对着脚边的墙、或往下砸）时球会立刻反弹回来，而那一下速度还满着，
     * 容易把投掷者自己打伤。前 2 刻不结算命中伤害即可避开这一下；
     * 反弹、滚动等物理行为不受影响，只是这段时间内不扣血。</p>
     */
    private static final int SPAWN_GRACE_TICKS = 2;

    /**
     * 被外力撞开后，「禁止判定静止」的窗口（刻）。
     *
     * <p>已经停在地上的球被别的球撞、或者被玩家蹭到之后，本该滚出去一段。
     * 但静止判定只看「速度是否低于 {@link #SETTLE_SPEED_SQR}」——
     * 碰撞刚传过来的速度往往就在阈值附近，下一 tick 立刻又被判回静止，
     * 表现就是「已经静止的球怎么撞都不动」。给一个免疫窗口，让它先滚出去再说。</p>
     */
    private static final int IMPULSE_GRACE_TICKS = 12;

    /**
     * 低于这个竖直速度的向上分量直接抹平（格/刻）。
     *
     * <p>球的重力本来就小，一丁点向上的残余速度够它飘很久 ——
     * 看着就像球自己无端飞起来。这个阈值远低于正常反弹速度
     * （3 格高度落下约反弹 0.24 格/刻），所以不会误伤真正的弹跳。</p>
     */
    private static final double MIN_BOUNCE_UP_SPEED = 0.08D;

    /**
     * 球与球判定为「撞上」的中心距离（格）。
     *
     * <p>球的碰撞箱宽 0.25，取 0.35 留一点余量 —— 连续碰撞检测就靠这个半径去判
     * 「这一 tick 的路径有没有擦到对方」。</p>
     */
    private static final double CONTACT_DISTANCE = 0.35D;

    /**
     * 落定之后的「坐实」刻数（作者指定 5 刻）。
     *
     * <p>球判定静止的瞬间往往还悬在地面之上一点点，这 5 刻里只做一个纯粹的向下动作：
     * <b>不反弹、不滑动、不上飘</b>，把球踏踏实实按到地面上，之后才转入真正的静止。
     * 期间的水平与向上分量全部为零。</p>
     */
    private static final int SETTLE_DROP_TICKS = 5;

    /** 坐实阶段的下落速度（格/刻）—— 慢一点才看得出是「落定」而不是「又弹了一下」 */
    private static final double SETTLE_DROP_SPEED = 0.08D;

    /** 【空气动力球】回归的起始速度（格/刻）—— 参考忠诚附魔：起步不快，然后加速 */
    private static final double RETURN_BASE_SPEED = 0.6D;

    /** 【空气动力球】回归每刻的加速度 */
    private static final double RETURN_ACCEL = 0.09D;

    /** 【空气动力球】回归速度上限 */
    private static final double RETURN_MAX_SPEED = 4.0D;

    /** 【空气动力球】离作者多近算「到家」（格） */
    private static final double RETURN_ARRIVE_DISTANCE = 1.8D;

    /** 找绕行路线时，最多往作者上方试探几格 */
    private static final int RETURN_CLIMB_STEPS = 8;

    /** 检测「这条路通不通」时的采样步长（格） */
    private static final double PATH_SAMPLE_STEP = 0.5D;

    /** 回归时每隔几刻重算一次路线 —— 逐刻重算会让球在障碍边缘左右横跳 */
    private static final int RETURN_RETARGET_INTERVAL = 5;

    /**
     * 球在水里泡这么多刻，就当它「落定」了。
     *
     * <p>水里的球被浮力托着，速度永远压不到静止阈值以下，正常那套落定判定根本走不到 ——
     * 于是掉进水里就永远不会启动回归（作者反馈的 bug）。这里给个替代条件：
     * 泡够 1 秒，该回家就回家。离开水面会清零。</p>
     */
    private static final int WATER_SETTLE_TICKS = 20;

    /**
     * 兜底落定的速度阈值（平方）—— 比严格静止阈值（0.03）宽松得多。
     *
     * <p>球卡在方块边缘打转时，速度常常在 0.05~0.08 之间晃，永远压不到 0.03 以下。</p>
     */
    private static final double STUCK_SPEED_SQR = 0.01D;

    /** 低速持续多少刻就当球停下了（1 秒） */
    private static final int STUCK_SETTLE_TICKS = 20;

    private @Nullable IntOpenHashSet piercingIgnoreEntityIds;

    private int settledTicks;

    /** 剩余多少刻内禁止判定静止（被撞飞 / 被推开之后的一小段） */
    private int impulseTicks;

    /** 落定后还剩多少刻的「向下坐实」动作 */
    private int settleDropTicks;

    /** 【空气动力球】：这颗球静止后要自己飞回作者 */
    private boolean returnToOwner;

    /** 正在回家的路上（回归期间免重力，免得飞一半掉下去） */
    private boolean homingBack;

    /** 回归已经飞了几刻 —— 用来做「越飞越快」的加速 */
    private int homingTicks;

    /** 上一刻是否在实心里 —— 用来把「连续实心段」和「被空气隔开的另一段」区分开 */
    private boolean wasInsideBlock;

    /** 当前这一段回归用的方向（隔几刻才重算一次，见 RETURN_RETARGET_INTERVAL） */
    private Vec3 returnDirection;

    /** 连着泡在水里的刻数 —— 够了就当作「落定」，见 WATER_SETTLE_TICKS */
    private int inWaterTicks;

    /** 低速持续的刻数 —— 够了就当作「落定」（只给带空气动力球的球用） */
    private int stuckTicks;

    /** 回归期间挂着区块票据的那一格区块；球换区块时要跟着换票据 */
    private ChunkPos ticketChunk;

    /**
     * 这是多重射击复制出来的<b>附属弹</b>。作者指定：附属弹停下来就碎，
     * 不留在地上、也不触发【空气动力球】之类的效果 —— 只有主弹药享受完整待遇。
     */
    private boolean multishotSide;

    /** 感应累积的热量；攒到配置的「融化」阈值（雪球类默认 200）就化掉 */
    /**
     * 球自身的热量。
     *
     * <p><b>是 float 而不是 int</b>（2.8.0 改）：热量按「当前速度 / 初速度」折算效率，
     * 慢速飞行时每刻的增量可能远小于 1，用 int 会被直接截成 0 —— 表现就是
     * 「球慢下来之后完全不再积热」。整数化只在显示档位时才做。</p>
     */
    private float heat;

    /**
     * 上一次打过诊断日志的热量档位（每跨过一个 {@link #HEAT_DIAG_STEP} 就打一条）。
     *
     * <p>热量是<b>实体内部字段</b>：没进 NBT、也没同步给客户端，所以游戏外只能靠日志看。
     * 作者要求「看攒到多少了」时，翻 latest.log 里 {@code [ball] … 热量} 那几行即可。</p>
     */
    private int heatDiagMilestone = Integer.MIN_VALUE;

    /** 热量诊断日志的档位宽度：每跨过这么多点记一条 */
    private static final int HEAT_DIAG_STEP = 100;

    /** 距离下次重新扫描还有几刻（扫描很贵，不必每刻做） */
    private int scanCooldown;

    /** 上次扫描到的金属矿物格数 */
    private int nearbyMetalBlocks;

    /** 上次扫描到的范围内生物（含玩家；每 SCAN_INTERVAL 刻刷新一次） */
    private List<LivingEntity> nearbyEntities = List.of();

    /** 上次扫描到的金属矿物位置（每刻给它们升温） */
    private List<BlockPos> nearbyOres = List.of();

    /** 磁吸目标：矿物与生物按「热量积累速度」竞争后的胜者 */
    private BallProspecting.MagnetTarget magnetTarget;

    /** 上一次扣耐久的刻，用于挡住同一瞬间的重复结算 */
    private int lastDamageTick = Integer.MIN_VALUE;

    /**
     * 命中伤害 —— <b>按「击中速度 ÷ 出手速度」的比例缩放</b>（作者 2026-10-07 指定）。
     *
     * <h2>为什么要这一层</h2>
     * <p>原本伤害是「出手那一刻」定死的，之后球被怎么加速、怎么减速都不影响。
     * 而持球苦力怕那套是<b>拿爆炸当发射药</b> —— 球会被炸到远超正常投掷的速度，
     * 伤害却还是原来那个数，所以「超快的球不疼」。</p>
     *
     * <h2>比例可缩可放、不设上限</h2>
     * <p>作者明确：**两边都要**（比出手快就加成、比出手慢就缩水），而且
     * **不要上限**（参考原版箭矢的直算思路）。所以这里就是最朴素的
     * {@code damage × (击中速度 / 出手速度)}，没有 clamp。</p>
     *
     * <p>基准必须用「**实际出手速度**」（{@link #launchSpeed}，在 {@link #shoot} 里记的），
     * 不能用 {@code profile.physicalVelocity()} —— 后者不含蓄力与附魔，
     * 拿它当除数会让蓄力被算两次。</p>
     */
    private float impactDamage() {
        // 基准 = 实际出手速度。理论上不会缺；万一是没走 shoot 的路径就退回这颗球的正常出手速度。
        double base = this.launchSpeed > 1.0E-4D
                ? this.launchSpeed
                : this.profile().physicalVelocity();
        if (base <= 1.0E-4D) {
            return this.damage;
        }
        double hitSpeed = this.getDeltaMovement().length();
        float ratio = (float) (hitSpeed / base);
        float result = this.damage * ratio;

        // 诊断日志：这条链上有好几个量（基础伤害、出手速度、击中速度），
        // 光看画面分不清是哪一环不对 —— 打出来一看就知道。
        MoreBalls.LOGGER.info("[ball] 命中伤害 {} = 基础 {} × 速度比 {}（击中速度 {} / 出手速度 {}）",
                result, this.damage, ratio, hitSpeed, base);
        return result;
    }

    /**
     * 出手速度 —— 由 {@link #shoot} 自动记下，命中时当除数用。
     *
     * <p>默认 0 表示「还没记录过」，{@link #impactDamage()} 会退回按
     * {@code profile.physicalVelocity()} 算。</p>
     */
    private double launchSpeed;

    private float damage = 3.0F;

    /** 发射力度倍率（强弩附魔加成），在 shoot 时乘到初速度上 */
    private float launchMultiplier = 1.0F;

    /**
     * 【金光闪闪】的<b>吸引窗口</b>截止时刻（游戏刻）。
     *
     * <h2>规则（作者 2026-10-08 指定）</h2>
     * <p>金球<b>第一次碰撞</b>之后的 5 秒（100 刻）内：</p>
     * <ul>
     *   <li>猪灵与猪灵蛮兵<b>不会被它捡起</b></li>
     *   <li>它们被<b>强制禁用仇恨</b>（清掉记恨与当前攻击目标）</li>
     *   <li>并被这颗金球<b>吸引</b>过去</li>
     * </ul>
     * <p>每次碰撞都会把这个截止时刻往后推 5 秒，所以「撞一下 → 猪灵围过来」可以反复触发。</p>
     */
    private long goldLureUntilTick = 0L;

    /** 【金光闪闪】的窗口时长：5 秒 */
    public static final int GOLD_LURE_WINDOW_TICKS = 100;

    /** 这颗金球此刻是否处于吸引窗口内 */
    public boolean isGoldLuring(long now) {
        return this.getGoldLureUntil() > now;
    }

    public long getGoldLureUntil() {
        return this.goldLureUntilTick;
    }

    /** 刷新吸引窗口 —— 由碰撞触发 */
    public void refreshGoldLureWindow() {
        this.goldLureUntilTick = this.level().getGameTime() + GOLD_LURE_WINDOW_TICKS;
    }
    public BallProjectile(EntityType<? extends BallProjectile> type, Level level) {
        super(type, level);
    }

    public BallProjectile(Level level, LivingEntity shooter, ItemStack stack) {
        super(ModEntities.BALL.get(), shooter, level, stack);
        // 弩在装填/发射时会往被消耗的弹药上打「intangible_projectile」（不可回收）标记，
        // 原版箭在 AbstractArrow 构造里主动移除了它 —— 我们的球是可以被捡回来的，
        // 不清掉的话捡回来的球会显示「不可回收」，而且因为多了一个数据组件而叠不进普通堆。
        ItemStack held = this.getItem();
        BallItem.stripIntangible(held);
        this.setItem(held);
    }

    /**
     * 实体的名字 —— 跟着物品走：木球扔出去就叫「木球」，铁球就叫「铁球」。
     *
     * <h2>为什么不走原版 custom name</h2>
     * 原版的 {@code setCustomName} 自带「把名字渲染到世界里」这条绑定，
     * 一堆球飞起来就满天是字，怎么压都压不干净。
     * 这里改成<b>直接覆写取名字的方法</b> —— 名字只存在于「查询接口」这一层：
     * Jade 之类的信息面板读的就是它，而渲染器拿不到任何可显示的名字。
     *
     * <p>构造阶段可能还没拿到物品，取不到就退回超类的行为，不会抛异常。</p>
     */
    @Override
    public Component getName() {
        ItemStack stack = this.getItem();
        if (stack.isEmpty()) {
            return super.getName();
        }
        return stack.getHoverName();
    }

    public void setDamage(float damage) {
        this.damage = damage;
    }

    public float getDamage() {
        return this.damage;
    }

    public void setLaunchMultiplier(float launchMultiplier) {
        this.launchMultiplier = Math.max(0.0F, launchMultiplier);
    }

    /** 出手时把力度倍率乘进初速度（射程与初速平方成正比，故用开方换算） */
    @Override
    public void shoot(double x, double y, double z, float velocity, float inaccuracy) {
        super.shoot(x, y, z, velocity * this.launchMultiplier, inaccuracy);
        // 记下「出手速度」—— 命中伤害要按「击中速度 / 出手速度」算（作者 2026-10-07 指定）。
        //
        // 放在 shoot() 里而不是各个投掷点，是因为**玩家手扔、怪物投掷、弩发射全部走这里**：
        // 一处记录就全覆盖，也不会漏掉附魔倍率（launchMultiplier 刚刚才乘进去）。
        // 基准必须用「实际出手速度」而不是 profile.physicalVelocity()——
        // 后者不含蓄力/附魔，拿它当除数会让蓄力被算两次。
        this.launchSpeed = this.getDeltaMovement().length();
    }

    public byte getPierceLevel() {
        return this.entityData.get(PIERCE_LEVEL);
    }

    public void setPierceLevel(byte pierceLevel) {
        this.entityData.set(PIERCE_LEVEL, pierceLevel);
    }

    public int getWeight() {
        return this.entityData.get(WEIGHT);
    }

    public void setWeight(int weight) {
        this.entityData.set(WEIGHT, Mth.clamp(weight, BallWeight.MIN, BallWeight.MAX));
    }

    public boolean isFromCrossbow() {
        return this.entityData.get(FROM_CROSSBOW);
    }

    public void setFromCrossbow(boolean fromCrossbow) {
        this.entityData.set(FROM_CROSSBOW, fromCrossbow);
    }

    public int getToughness() {
        return this.entityData.get(TOUGHNESS);
    }

    public void setToughness(int toughness) {
        this.entityData.set(TOUGHNESS, toughness);
    }

    public boolean isTough() {
        return this.getToughness() != BallBehavior.NOT_TOUGH;
    }

    public int getBounce() {
        return this.entityData.get(BOUNCE);
    }

    public void setBounce(int bounce) {
        this.entityData.set(BOUNCE, Mth.clamp(bounce, 0, 10));
    }

    public boolean isSettled() {
        return this.entityData.get(SETTLED);
    }

    private void setSettled(boolean settled) {
        // 【智慧x】**正在无视重力飞行**时不进入静止态（作者 2026-10-09 指定）。
        // 静止态的球会被「按在地上」（贴地、可被捡、触发静止特效），
        // 而此时它还在空中追目标 —— 必须拦住。
        //
        // ⚠️ 判据是 **isNoGravity()**，不是 wisdomStillTracking()。
        //    后者在「第一次命中之前」也为真，会让球一扔出去就拒绝静止；
        //    而作者要的是「无视重力的时候才不静止」—— 命中前照常能落地静止。
        if (settled && this.isNoGravity()) {
            this.entityData.set(SETTLED, false);
            return;
        }
        this.entityData.set(SETTLED, settled);
    }

    public boolean isRolling() {
        return this.entityData.get(ROLLING);
    }

    private void setRolling(boolean rolling) {
        this.entityData.set(ROLLING, rolling);
    }

    /**
     * 能否被玩家的准星选中做交互。
     *
     * <p><b>必须覆写</b>：原版 {@code Projectile} 把它写死成
     * {@code this.is(EntityTypeTags.REDIRECTABLE_PROJECTILE)}，普通投射物一律返回 false，
     * 于是球根本不在射线候选里、右键完全没反应。</p>
     *
     * <p>这里对所有存活的球都返回 true —— 地上的球要能捡，飞在空中的球要能接。</p>
     */
    /**
     * 一律不显示名字标签 —— 任何情况都不显示（作者指定）。
     *
     * <p>名字只存在于 {@link #getName()} 这一层（给信息面板读），
     * 世界里飘着的名字标签归这个开关管，直接关死。</p>
     */
    @Override
    public boolean shouldShowName() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return this.isAlive();
    }

    /**
     * 拾取半径：地上的球给得宽裕、好点中；飞行中的球给一个小半径，
     * 既接得住，又不至于把玩家的准星挡死。
     */
    @Override
    public float getPickRadius() {
        return this.isSettled() ? PICK_RADIUS : FLYING_PICK_RADIUS;
    }

    /**
     * 允许被其它实体推动 —— 玩家或生物蹭一下，球就会滚起来。
     *
     * <h2>为什么【金光闪闪】的吸引窗口内不给推</h2>
     * <p>被吸引的猪灵会一拥而上，而它们会把球<b>顶着一路走</b>。球每滚一下就会撞到方块，
     * 走 {@code consumeDurability("反弹")} 扣耐久 —— 金球耐久本来就低，推一会儿耐久就空了，
     * 于是 {@code burst()} 里走 morph 直接变成金块（作者 2026-10-09 报的
     * 「猪灵推动金球导致金球提前变成金块」）。</p>
     *
     * <p>那 5 秒窗口本来就是要让玩家看清楚「猪灵被吸引过来」，球在这段时间里
     * 原地不动才符合设计意图 —— 而且窗口一过就恢复可推。</p>
     */
    @Override
    public boolean isPushable() {
        if (this.level() instanceof ServerLevel server
                && BallBehavior.isGoldShiny(this.getItem())
                && this.isGoldLuring(server.getGameTime())) {
            return false;
        }
        return true;
    }

    /**
     * 被推动时把位移转成「滚动」：直接切到贴地滚动状态，而不是被弹回空中。
     */
    @Override
    public void push(double x, double y, double z) {
        super.push(x, y, z);
        if (!this.level().isClientSide() && this.isTough()) {
            Vec3 velocity = this.getDeltaMovement();
            this.setDeltaMovement(velocity.x, 0.0D, velocity.z);
            if (!this.isRolling()) {
                this.setRolling(true);
            }
            this.setSettled(false);
            this.impulseTicks = IMPULSE_GRACE_TICKS;
        }
    }

    /**
     * 重力：滚动中贴地、或已经停下等人来捡，都不再受重力；其余情况按重量与发射方式换算。
     *
     * <p>静止态也必须切断重力 —— 否则球停下后会被重力拽向地面、撞出一次「微型反弹」，
     * 接着又弹又停又沉，视觉上就是停下来时的一顿一顿。</p>
     */
    @Override
    protected double getDefaultGravity() {
        // 只有「还踩在地上」的滚动 / 静止球才断重力。
        // 一旦离地（被弹起、被撞飞、被拽开），重力必须恢复 ——
        // 否则球会带着一丁点向上的速度永远飘着上升（作者反馈过这个现象）。
        //
        // 【空气动力球】回归途中同样断重力：它是「飞回去」，不是「掉回去」。
        // 判定用 homingTicks > 0 兜底 —— 只要这颗球已经起飞过，就一直是回归状态，
        // 哪怕中间某一刻拿不到作者（下线、维度切换）也不该突然被重力拽下去。
        if (this.isSettled() || this.homingBack || this.isReturning()
                || (this.isRolling() && this.hasSupportBelow())) {
            return 0.0D;
        }
        return BallWeight.gravityFor(this.getWeight(), this.isFromCrossbow());
    }

    /** 这颗球是不是已经在回家的路上了（起飞过就一直算） */
    private boolean isReturning() {
        // 注：「次数未耗尽就不回家」的**主拦截点在 homingToOwner()**，
        // 这里再判一次是为了让「已经在回家路上」的球在次数被重置时也能立刻停 —— 双保险。
        if (this.wisdomStillTracking()) {
            return false;
        }
        return this.returnToOwner && this.homingTicks > 0;
    }

    /**
     * 回归期间不受方块影响。
     *
     * <p><b>注意：这条不是恼鬼的做法。</b>翻 `Vex.isAffectedByBlocks()` 的字节码，
     * 它其实是 {@code return !isRemoved();} —— 返回 {@code true}，也就是<b>默认行为</b>，
     * 恼鬼压根没动这个方法。我先前误判成「它靠这个穿墙」，还照着改了一版，属于走错路。
     *
     * <p>这里保留覆盖是因为「回归期间确实不该受方块影响」本身是对的，
     * 但要清楚：<b>穿墙真正靠的是 {@code noPhysics}</b>，别再把希望寄托在这个方法上。</p>
     */
    @Override
    protected boolean isAffectedByBlocks() {
        return !this.homingBack && super.isAffectedByBlocks();
    }

    /**
     * 维持回归路上的<b>区块票据</b> —— 让球能在自己飞出去的那片未加载区域里继续存在、继续 tick。
     *
     * <p>用 {@code ServerLevel.setChunkForced} 直接强制加载球所在的那<b>一格</b>区块：
     * 够球飞出当前区块前把下一块准备好，又不像传送门票据那样一次拉一大片、外头刷满怪。
     * 球跨区块时先撤旧的再挂新的，移除时释放，所以身后不会留一串。</p>
     *
     * <p><b>只给带【空气动力球】的球挂</b>——调用点在 {@link #homingToOwner()} 里，
     * 那里有 {@code returnToOwner} 守卫。普通球不会回家，没必要为它开区块。</p>
     */
    private void updateLoadTicket(ServerLevel level) {
        ChunkPos now = this.chunkPosition();
        if (now.equals(this.ticketChunk)) {
            return; // 还在同一块里，不用动
        }
        this.releaseLoadTicket(level);
        // 激进：不用票据那套分级，直接强制加载这一格。
        // setChunkForced 是原版最硬的加载手段，只作用一个区块 ——
        // 比票据半径 1 的 3×3 还省，更不会在外面刷怪。
        level.setChunkForced(now.x(), now.z(), true);
        this.ticketChunk = now;
    }

    /**
     * 释放强制加载。回归结束、或者球被移除时都要调 ——
     * 否则那一格会永久留在 forcedchunks 里，变成实打实的泄漏。
     */
    private void releaseLoadTicket(ServerLevel level) {
        if (this.ticketChunk == null) {
            return;
        }
        level.setChunkForced(this.ticketChunk.x(), this.ticketChunk.z(), false);
        this.ticketChunk = null;
    }

    @Override
    public void onRemoval(Entity.RemovalReason reason) {
        if (this.level() instanceof ServerLevel level) {
            this.releaseLoadTicket(level);
        }
        super.onRemoval(reason);
    }

    /**
     * 水里待着时的替代「落定」判定。     *
     * <p>水里的球被浮力托着，速度压不到静止阈值以下，正常那套判定永远不成立。
     * 这里按「连着泡够 {@value #WATER_SETTLE_TICKS} 刻」当作落定；一出水就清零重算，
     * 所以球在水里漂的前一秒仍然跟着水流走，不会被硬拽。</p>
     */
    private boolean tickInWater() {
        if (!this.isInWater()) {
            this.inWaterTicks = 0;
            return false;
        }
        return ++this.inWaterTicks >= WATER_SETTLE_TICKS;
    }

    /**
     * 【空气动力球】的兜底落定：连续 {@value #STUCK_SETTLE_TICKS} 刻速度都不高，就当球停下了。
     *
     * <p>严格的静止条件（速度 &lt; 0.03 格/刻、脚下有支撑、不在脉冲保护期）在球卡进方块边缘、
     * 贴着墙打转时可能永远凑不齐 —— 实测日志里 {@code settled} 从头到尾没变成过 {@code true}，
     * 球就那么干卡在原地：既不落定（于是不启动回归），也不碎。这正是作者反馈的「卡住」。</p>
     *
     * <p>阈值 0.1 格/刻比严格静止宽三倍多，只给带【空气动力球】的球用（调用点有
     * {@code returnToOwner} 守卫），所以不会影响普通球的落定手感。</p>
     */
    private boolean tickSlowStuck() {
        if (this.getDeltaMovement().lengthSqr() > STUCK_SPEED_SQR) {
            this.stuckTicks = 0;
            return false;
        }
        return ++this.stuckTicks >= STUCK_SETTLE_TICKS;
    }

    /** 滚动（以及静止）时阻力由地面摩擦负责，不叠加空气阻力 */
    @Override
    protected float getAirDrag() {
        return (this.isRolling() || this.isSettled()) ? 1.0F : super.getAirDrag();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(PIERCE_LEVEL, (byte) 0);
        builder.define(WEIGHT, BallWeight.BASELINE);
        builder.define(FROM_CROSSBOW, false);
        builder.define(TOUGHNESS, BallBehavior.NOT_TOUGH);
        builder.define(BOUNCE, BallBehavior.DEFAULT_BOUNCE);
        builder.define(SETTLED, false);
        builder.define(ROLLING, false);
    }

    @Override
    protected Item getDefaultItem() {
        return Items.SNOWBALL;
    }

    /**
     * 能否命中某个实体。
     *
     * <p>这里<b>不</b>排除投掷者 —— 球允许撞到自己（碰撞本身是正常的），
     * 只是出手瞬间（{@link #SPAWN_GRACE_TICKS} 刻）不结算伤害，
     * 见 {@link #onHitEntity}。原版 {@code Projectile} 自带的那套
     * 「离开投掷者之后才可命中」逻辑保持不动。</p>
     */
    @Override
    protected boolean canHitEntity(Entity target) {
        // 怪物扔出来的球不该打到自己（作者指定）。
        // 超类那句「离开投掷者之后才可命中」只兜住出手瞬间，而怪物都是贴脸出手的，
        // 几刻之后球还在它自己身上 → 被自己的球打疼 → 一头扎进去和球纠缠不休。
        // 玩家投掷的球维持原样：弹回来打到自己是明确定过的手感，不动。
        if (target == this.getOwner() && this.getOwner() instanceof Mob) {
            return false;
        }
        return super.canHitEntity(target)
                && (this.piercingIgnoreEntityIds == null
                        || !this.piercingIgnoreEntityIds.contains(target.getId()));
    }

    /**
     * <b>单个维度**内允许同时存在的球实体上限（作者 2026-10-09 指定）。</b>
     *
     * <p>超出之后从**最旧的**开始消失：球的飞行、静止、区块加载、回归寻路都会占资源，
     * 玩家在外面狂扔一通再传走，那些球会一直挂在那儿 tick。上限本身就是防堆积。</p>
     */
    public static final int BALL_CAP = 50;

    /** 上限检查的间隔（刻）—— 一秒一次足够，不必逐刻遍历维度 */
    public static final int BALL_CAP_CHECK_INTERVAL = 20;

    /**
     * 覆盖整个维度的包围盒 —— {@code getEntitiesOfClass} 需要一个 AABB，
     * 而我们要的是「这个维度里的全部球」。取值按 MC 的世界边界上界再放宽一点。
     */
    private static final net.minecraft.world.phys.AABB WHOLE_LEVEL_BOX =
            new net.minecraft.world.phys.AABB(
                    -3.0E7D, -2048.0D, -3.0E7D,
                    3.0E7D, 2048.0D, 3.0E7D);

    /**
     * 执行一次上限检查：本维度的球多于 {@link #BALL_CAP} 时，
     * 按<b>存在时长从久到近</b>的顺序清掉多出来的那些。
     *
     * <h2>为什么用 tickCount 排「新旧」</h2>
     * <p>{@code tickCount} 是实体从生成起累计的 tick 数，越大代表存在越久 ——
     * 正好就是「最旧」。不需要额外记时间戳。</p>
     *
     * <h2>为什么用 discard() 而不是 kill()</h2>
     * <p>{@code kill()} 会走 {@code hurt(damageSources().genericKill())} 那条路，
     * 对投射物来说会触发掉落/碎裂之类的收尾逻辑；{@code discard()} 是直接移除，
     * **不产生任何掉落物** —— 这正是作者要的「无掉落消失」。</p>
     */
    private void enforceBallCap() {
        if (!(this.level() instanceof net.minecraft.server.level.ServerLevel server)) {
            return;
        }
        java.util.List<BallProjectile> all =
                server.getEntitiesOfClass(BallProjectile.class, WHOLE_LEVEL_BOX);
        int overflow = all.size() - BALL_CAP;
        if (overflow <= 0) {
            return;
        }
        // 存在最久的排前面
        // tickCount 是 Entity 的 public 字段（不是 getter）
        all.sort((a, b) -> Integer.compare(b.tickCount, a.tickCount));
        for (int i = 0; i < overflow; i++) {
            BallProjectile oldest = all.get(i);
            if (oldest != this) {
                // 不动「自己」—— 自己的 tick 正在跑，提前移除会打乱这一帧的后续逻辑
                oldest.discard();
            } else if (overflow > 1) {
                // 自己被点到但还有别的要清，先跳过，下一轮再算
                continue;
            }
        }
        MoreBalls.LOGGER.debug("[ball] 实体上限 {}：本维度有 {} 颗，清掉最旧的 {} 颗",
                BALL_CAP, all.size(), overflow);
    }

    @Override
    public void tick() {
        // 【实体上限】本维度的球超过上限时，从最旧的开始清掉。
        //
        // 每 20 刻（1 秒）才查一次 —— 逐刻遍历整个维度的实体没意义，
        // 而这个上限本身是「防堆积」用的，晚一秒清理完全够。
        // 只在服务端做：客户端的实体列表不完整，也轮不到它决定谁该消失。
        if (!this.level().isClientSide() && this.level().getGameTime() % BALL_CAP_CHECK_INTERVAL == 0L) {
            enforceBallCap();
        }

        // 回归期间，<b>在 super.tick() 之前</b>先把恼鬼的开关压上。
        //
        // 这是照着恼鬼的字节码来的：它的 tick() 在父类 tick <b>前后各设一次</b>
        // noPhysics、并且每刻重设 noGravity —— 说明这些开关在父类 tick 过程里会被动到。
        // 我之前的写法全在事后设，于是「这一 tick 里跑的物理」用的还是旧值，
        // 球照样被方块卡住。前后都压住才算真的一样。
        if (this.homingBack) {
            this.noPhysics = true;
            // ⚠️ 只在值真的变化时调用 —— setNoGravity 是同步数据，
            //    值没变也会触发实体同步，每 tick 调就是「卡一下闪现一下」。
            if (!this.isNoGravity()) {
                this.setNoGravity(true);
            }
        }

        // 【区块加载】带空气动力球的球<b>从落地那刻就得挂上</b>，不能等回归启动再挂。
        //
        // 踩过：之前只在 homingToOwner() 里挂，也就是「回归已启动」之后才挂 ——
        // 而球真正需要被加载的恰恰是它<b>停下来等着落定</b>的那段时间。
        // 实测往 100 格外扔一颗，区块一卸载球就冻结在那儿不 tick，
        // 于是永远等不到落定、也就永远不启动回归，看起来就是「最后几发没回来」。
        //
        // 判据是 returnToOwner（= 武器带空气动力球附魔），普通球不参与，不浪费加载。
        if (this.returnToOwner && this.level() instanceof ServerLevel ticketLevel) {
            this.updateLoadTicket(ticketLevel);
        }

        // 记下这一 tick 开始时的状态，super.tick() 之后要用它补位移
        Vec3 tickStartPos = this.position();
        Vec3 tickStartVel = this.getDeltaMovement();

        super.tick();

        // 【回归虚化】把被「钉住」的位移补回来。
        //
        // 父类 ThrowableProjectile.tick() 是这么移动的：
        //     HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, ...);
        //     if (hit != MISS) this.setPos(hit.getLocation());      // ← 钉在命中点
        //     else            this.setPos(position().add(delta));
        // 球一旦落在方块里，射线检测每刻立刻命中（距离 0），于是位置被设成
        // 「紧贴方块表面的那一点」—— 表现就是速度 4.0、而 110 刻只挪了 0.2 格
        // （实测日志）。那个 setPos 是无条件的，noPhysics 也好、覆盖
        // hitTargetOrDeflectSelf 也好，都拦不住它。
        //
        // 所以这里按「本刻起点 + 本刻速度」把正确位置补回去。
        if (this.homingBack) {
            Vec3 wanted = tickStartPos.add(tickStartVel);
            if (this.position().distanceToSqr(wanted) > 1.0E-6D) {
                this.setPos(wanted.x, wanted.y, wanted.z);
            }
        }
        if (this.level().isClientSide()) {
            return;
        }

        // 「感应」必须在 isTough() 之前执行：带这个性质的「雪球？（铁粒）」本身<b>不坚固</b>，
        // 若放在坚固分支之后，会被上面那道 return 直接跳过 —— 表现就是「感应完全不生效」。
        //
        // 判据是底层词条 BallProfile#hasSense()，不再硬编码具体物品 ——
        // 以后任何球加上 .withSense(n) 就自动具备这套能力。
        if (this.profile().hasSense()) {
            this.prospectingTick();
        }

        // 【智慧】—— 紫水晶球：发射后扫描 10 格内无遮挡可直达的敌对（或仇恨中的中立）
        // 生物，找到最近的立刻锁定、把速度改成朝它飞。锁定一次即生效，之后不再改方向。
        // 【智慧】原来是「锁一次就不再评估」（wisdomLocked 一置位永不复评），
        // 而且搜索窗口只有前 40 刻。结果：怪物扔回来的球出手时方向已经对着目标，
        // 那一次锁定几乎不改变弹道，之后再也不会拐弯 —— 表现就是「智慧不生效」。
        // 改成周期性重锁：持续跟踪目标，直到球失效。
        if (this.profile().wisdom() > 0) {
            // 首次初始化剩余次数
            if (this.wisdomUsesLeft < 0) {
                this.wisdomUsesLeft = this.profile().wisdom();
                this.wisdomGravityBefore = this.isNoGravity();   // 记下原始重力状态
            }
            // 【智慧x】的三段式重力（作者 2026-10-09 修正）：
            //   第一次命中**之前** —— 照常受重力（正常弹道，扔出去该抛就该抛）
            //   命中一次之后、次数未耗尽 —— **无视重力**，锁定目标追过去
            //   最后一次机会消耗完 —— **恢复重力**，正常掉落在原地静止
            // ⚠️ 「追踪」与「无重力」是**两件事**，不能绑在一起。
            //    作者 2026-10-09 实测：绑在一起时第一次命中之前连追踪都不做了。
            //
            //   追踪：只要还有次数就一直做（包括第一次出手的飞行途中）
            //   重力：第一次命中**之前**照常受重力；命中一次后改用无重力飞行；
            //         次数耗尽后恢复重力，让它正常掉落静止
            if (this.wisdomUsesLeft > 0) {
                if (this.wisdomRelockCooldown-- <= 0) {
                    this.wisdomRelockCooldown = WISDOM_RELOCK_INTERVAL;
                    this.tryWisdomLock();
                }
            }
            boolean tracked = this.wisdomUsesLeft < this.profile().wisdom();   // 已经命中过至少一次
            if (tracked && this.wisdomUsesLeft > 0) {
                if (!this.isNoGravity()) {
                    this.setNoGravity(true);
                }
            } else if (this.isNoGravity() != this.wisdomGravityBefore) {
                // ⚠️ 还原成**最初的重力状态**（通常是 false = 受重力）。
                //
                //    这里原来错写成 setNoGravity(true) —— 在「还原重力」的分支里
                //    又把重力关掉了，于是次数耗尽后球永远不落地
                //    （作者 2026-10-09 遍历时发现的）。
                //
                //    ⚠️ 同时注意：**只在值真的变化时**调用 —— setNoGravity 是同步数据，
                //    值没变也会触发一次实体同步，每 tick 调就是「卡一下闪现一下」。
                this.setNoGravity(this.wisdomGravityBefore);
            }
        }

        // 【金光闪闪】的吸引窗口：**无论球是不是静止**都要吸引。
        //
        // 原来这段只在「已静止」分支里，于是金球刚撞完还在滚动的那 5 秒里
        // 猪灵完全没反应 —— 而作者要的正是「碰撞后的那 5 秒」。
        if (BallBehavior.isGoldShiny(this.getItem())
                && this.level() instanceof ServerLevel goldLevel
                && this.isGoldLuring(goldLevel.getGameTime())) {
            PiglinLure.lure(goldLevel, this.position());
        }

        // 【熔融】状态的外观：进了熔融的球周身冒火（环绕 + 拖尾）
        this.moltenParticlesTick();

        if (!this.isTough()) {
            return;
        }

        if (this.isRolling()) {
            this.rollTick();
        }
        // 球间碰撞无论是否在滚动都要检测 —— 滚动的球撞上静止的球一样该传递动能。
        //
        // ⚠️ **必须限流**：这段每 tick 对每个球做一次 getEntitiesOfClass，
        //    球的数量一多就是 O(n²)。作者实测「十来个球就特别卡、球越多越严重」。
        //    球速上限约 1.5 格/刻，而这段本身用「上一刻→这一刻的线段」做连续检测，
        //    把间隔放到 4 刻（最大相对位移 6 格）仍然接得住 —— 线段判定不会漏。
        if (this.level().getGameTime() % BALL_COLLIDE_INTERVAL == 0L) {
            this.collideWithNearbyBalls();
        }
        // 「磁吸」：飞行中把身边的金属拽向自己（空心铁球）
        if (this.level() instanceof ServerLevel serverLevel) {
            this.magnetTick(serverLevel);
        }

        if (this.impulseTicks > 0) {
            this.impulseTicks--;
        }

        // 【空气动力球】：回归逻辑必须独立于静止态。
        //
        // 之前这段挂在「静止分支」里，而 homingToOwner 自己会把球拉出静止态
        // （setSettled(false) 才能起飞），于是球只飞一 tick 就掉回普通物理、落地、
        // 再判定静止、再飞一 tick……表现就是「球在作者旁边一直弹，永远回不了背包」。
        // 回归期间<b>全程硬维持</b>「恼鬼三件套」。
        //
        // 作者指定：从开始回归到重新回收为止，这期间一直要拥有全部恼鬼特性。
        // 只在起飞那一刻设一次是不够的 —— 中途任何一步把它还原、或者别的逻辑碰了这些开关，
        // 球就会重新被方块卡住（这正是「还是会卡方块」的原因）。
        // 第三件 isAffectedByBlocks() 是跟随 homingBack 的覆盖，不用在这里管。
        if (this.homingBack) {
            if (!this.noPhysics) {
                this.noPhysics = true;
            }
            if (!this.isNoGravity()) {
                this.setNoGravity(true);
            }
        }

        if (this.homingBack) {
            if (this.homingToOwner()) {
                return;
            }
            // 作者不在了（下线 / 换维度 / 已死），放弃回归 ——
            // 必须把「恼鬼三件套」和记账状态一起还原，否则球会一直穿墙飘着
            this.homingBack = false;
            this.noPhysics = false;
            this.setNoGravity(false);
            this.wasInsideBlock = false;
        }

        if (!this.isSettled()) {
            // 必须「脚下有支撑」才判定静止。只看速度的话，球飞到抛物线顶点时速度同样接近 0，
            // 一旦被误判成静止就会切断重力 —— 表现就是球飘在半空缓慢移动，
            // 直到玩家死亡、区块重载才掉下来。
            //
            // 刚被撞开的那几刻例外：碰撞传来的速度就在阈值附近，立刻判定静止的话
            // 球会「粘」在原地 —— 现象就是已经停下的球怎么撞都不动。
            if (this.impulseTicks <= 0
                    && this.hasSupportBelow()
                    && this.getDeltaMovement().lengthSqr() < SETTLE_SPEED_SQR) {
                // 多重射击的附属弹：一落定就碎，不留在地上、也不回家
                if (this.multishotSide || this.shatterOnSettle) {
                    this.burst();
                    return;
                }
                // 【智慧x】次数未耗尽时**不触发【空气动力球】回收** —— 它还在追目标，
    // 不该被拽回主人身边（作者 2026-10-09 指定：次数耗尽后才正常掉落并可被回收）。
    // 【空气动力球】：本该停下的球，改成起飞回家
                if (this.returnToOwner && this.homingToOwner()) {
                    return;
                }
                this.setSettled(true);
                this.settledTicks = 0;
                // 先走 5 刻的「向下坐实」，再进真正的静止
                this.settleDropTicks = SETTLE_DROP_TICKS;
            } else if (this.returnToOwner && this.tickSlowStuck()) {
                // 【空气动力球】的<b>兜底落定</b>。
                //
                // 上面那套严格条件（速度 < 0.03 且脚下有支撑）球卡在方块边缘滚动时可能
                // 永远凑不齐 —— 实测日志里 settled 从头到尾没变成过 true，球就那么干卡在原地，
                // 既不落定（所以不启动回归）也不碎。作者反馈的「卡住了」就是这个。
                // 这里放宽成「连续一秒速度都不高」就当它停下了，直接起飞回家。
                if (this.homingToOwner()) {
                    return;
                }
            } else if (this.returnToOwner && this.tickInWater()) {
                // 水里的球是另一套：浮力托着它、速度永远压不到静止阈值以下，
                // 走不到上面的落定判定，于是泡在水里一辈子也不会被回收（作者反馈的 bug）。
                // 这里给个替代的「落定」条件 —— 泡够 1 秒就当它落定了。
                if (this.homingToOwner()) {
                    return;
                }
            }
        } else {
            // 【空气动力球】：停在地上的球同样要起飞回家
            if (this.returnToOwner && this.homingToOwner()) {
                return;
            }

            // 落定后的 5 刻「坐实」：只往下走，不弹、不滑、不上飘（作者指定）。
            // 球判定静止那一瞬常常还悬在地面上方一点点，这几刻把它踏实按到地面。
            if (this.settleDropTicks > 0) {
                this.settleDropTicks--;
                this.setDeltaMovement(0.0D, -SETTLE_DROP_SPEED, 0.0D);
                return;
            }

            // 「坐实」走完 = 作者说明的「第二次静止」。附属弹到这一步<b>直接碎掉</b>。
            //
            // 作者指定按这个时机处理，理由很实在：第一次静止判定发生的那一瞬，
            // 球往往还悬在地面上方一点点、状态还没稳定，检查有可能被别的分支抢先 return 掉。
            // 放到这里就没有时序问题了 —— 只要它真的停稳，就一定裂。
            if (this.multishotSide || this.shatterOnSettle) {
                this.burst();
                return;
            }

            // 静止态的球：<b>除了「向下」，另外五个方向的速度全部持续归零</b>（作者指定）——
            // 水平不让它滑、向上不让它飘，只留下坠的分量。
            //
            // 之所以特意留着向下那一份：万一球悬在半空（支撑面被挖掉了、区块变动），
            // 靠它还能自己落回地面，而不是定死在空中。
            Vec3 velocity = this.getDeltaMovement();
            double down = Math.min(velocity.y, 0.0D);
            if (velocity.x != 0.0D || velocity.z != 0.0D || velocity.y != down) {
                this.setDeltaMovement(0.0D, down, 0.0D);
            }
            // 脚下已经没有支撑的话，解除静止让它按正常物理落回去
            if (!this.hasSupportBelow()) {
                this.setSettled(false);
                this.settledTicks = 0;
            } else if (++this.settledTicks >= SETTLED_LIFETIME_TICKS) {
                // 停够 2 分钟没人来捡就自动消失。
                // 捡球由怪物那边负责（见 BallMobAI#tryPickUpBall：1.5 格 / 每刻 / 2%）。
                this.discard();
            }

            // 【金光闪闪】：金球躺在地上就一直在喊猪灵过来 ——
            // 每刻覆写它们的寻路终点与攻击目标，直到球被捡走（球没了这段自然停止）。
            if (BallBehavior.isGoldShiny(this.getItem())
                    && this.level() instanceof ServerLevel lureLevel) {
                PiglinLure.lure(lureLevel, this.position());
            }
        }
    }

    /**
     * 「磁吸」：飞行过程中把身边的金属拽向自己（空心铁球）。
     *
     * <h2>规则（作者指定）</h2>
     * <ul>
     *   <li>作用半径 {@link BallProspecting#MAGNET_RADIUS}（2.5 格）</li>
     *   <li>范围内<b>任何金属</b>——含粗矿、矿石、矿块、矿粉、以及用金属做出来的装备 ——
     *       都会获得一个<b>指向球、大小等于球速一半</b>的瞬间速度</li>
     *   <li>脱离范围就不再施加（本来就是逐刻检查，天然如此）</li>
     *   <li><b>球静止后停止施加</b></li>
     * </ul>
     *
     * <p>只作用于掉落物实体（{@code ItemEntity}）—— 已经摆在世界里的金属方块拽不动，
     * 能移动的金属形态就是掉在地上的那些。</p>
     */
    private void magnetTick(ServerLevel level) {
        BallBehavior.BallProfile profile = this.profile();
        if (!profile.hasMagnet() || this.isSettled()) {
            return; // 没这个性质，或者球已经落定 → 停手
        }
        double speed = this.getDeltaMovement().length();
        if (speed < 1.0E-3D) {
            return; // 自己都停下来了，没什么可拽
        }

        Vec3 self = this.position();
        double pull = speed * BallProspecting.MAGNET_SPEED_RATIO;
        List<ItemEntity> metals = level.getEntitiesOfClass(ItemEntity.class,
                this.getBoundingBox().inflate(profile.magnetRadius()),
                item -> item.isAlive() && BallProspecting.isMetalEquipment(item.getItem()));

        for (ItemEntity item : metals) {
            Vec3 toSelf = self.subtract(item.position());
            if (toSelf.lengthSqr() < 1.0E-6D) {
                continue;
            }
            item.setDeltaMovement(toSelf.normalize().scale(pull));
        }
    }

    /** 脚下是否有可站立的碰撞箱（滚到悬崖边、或者还飞在半空时都会是 false） */
    private boolean hasSupportBelow() {        BlockPos below = this.blockPosition().below();
        return !this.level().getBlockState(below)
                .getCollisionShape(this.level(), below)
                .isEmpty();
    }

    /**
     * 滚动一帧：球精确压在方块顶面之上（留一点间隙），再按摩擦与重量衰减水平速度。
     *
     * <p>间隙是必需的：球底正好贴着顶面时，浮点误差会让每 tick 的移动都判成一次地面碰撞，
     * 位置被反复拉低再抬回 —— 那正是肉眼看到的「抽搐」。</p>
     */
    private void rollTick() {
        Vec3 velocity = this.getDeltaMovement();

        BlockPos below = this.blockPosition().below();
        BlockState state = this.level().getBlockState(below);
        VoxelShape shape = state.getCollisionShape(this.level(), below);

        if (shape.isEmpty()) {
            this.setRolling(false);
            return;
        }

        double topY = below.getY() + shape.max(Direction.Axis.Y);
        this.setPos(this.getX(), topY + this.getBbHeight() * 0.5D + GROUND_CLEARANCE, this.getZ());

        double keep = BallRoll.keepFactor(this.getWeight(), state.getBlock().getFriction());
        double vx = velocity.x * keep;
        double vz = velocity.z * keep;
        this.setDeltaMovement(vx, 0.0D, vz);

        if (vx * vx + vz * vz < SETTLE_SPEED_SQR) {
            this.setDeltaMovement(Vec3.ZERO);
            this.setRolling(false);
            // 同一 tick 内转入静止态，让重力立刻断掉
            this.setSettled(true);
            this.settledTicks = 0;
        }
    }

    /**
     * 球与球之间的弹性碰撞。
     *
     * <p>沿两球连线做一维弹性碰撞，<b>质量取重量</b>（重量越大越难被撞动）、
     * <b>弹性系数取两球弹射的平均值</b>（弹射 6 → e = 0.6）。
     * 每对球只在 id 较小的一方处理，避免算两遍。</p>
     *
     * <p>球撞球同样算一次碰撞，<b>两边各消耗 1 点耐久</b>（作者指定）——
     * 弹性系数只决定动能怎么分配，不代表这一下不损伤球体。
     * 已经在分离的两球不重复处理，所以接触一次只会扣一次。</p>
     */
    private void collideWithNearbyBalls() {
        // ===== 连续碰撞检测 =====
        // 球速能到 1.5+ 格/刻，而球的碰撞箱只有 0.25 宽 —— 只查「包围盒是否重叠」的话，
        // 飞行球会<b>一整个 tick 直接跨过</b>停在地上的球，两边从未重叠、碰撞被整个跳过。
        // 现象就是「停着的球怎么撞都不动」。所以改成拿「上一刻位置 → 当前位置」
        // 这条线段去判接近，路径擦过就算撞上。
        Vec3 from = new Vec3(this.xo, this.yo, this.zo);
        Vec3 move = this.position().subtract(from);

        // ⚠️ 搜索半径**设上限**：原来直接拿 `move.length()` 去膨胀包围盒，
        //    被爆炸打飞的球一 tick 能移动好几格，查询范围随之膨胀、开销陡增。
        //    超过 MAX_COLLIDE_REACH 之后就交给下几次检测（间隔 4 刻）覆盖。
        double reach = Math.min(MAX_COLLIDE_REACH, Math.max(0.5D, move.length() + CONTACT_DISTANCE));
        List<BallProjectile> others = this.level().getEntitiesOfClass(
                BallProjectile.class,
                this.getBoundingBox().inflate(reach),
                other -> other != this && other.isAlive());

        for (BallProjectile other : others) {
            if (this.getId() > other.getId()) {
                continue; // 每对只算一次
            }

            // 对方这一 tick 也在动，所以拿两者的「相对路径」判断是否擦过
            Vec3 otherFrom = new Vec3(other.xo, other.yo, other.zo);
            Vec3 relativeFrom = from.subtract(otherFrom);
            Vec3 relativeMove = move.subtract(other.position().subtract(otherFrom));
            double t = closestApproachT(relativeFrom, relativeMove);
            if (relativeFrom.add(relativeMove.scale(t)).lengthSqr() > CONTACT_DISTANCE * CONTACT_DISTANCE) {
                continue; // 这一 tick 的路径根本没碰到
            }

            Vec3 delta = other.position().subtract(this.position());
            if (delta.lengthSqr() < 1.0E-6D) {
                continue;
            }
            Vec3 normal = delta.normalize();

            double v1 = this.getDeltaMovement().dot(normal);
            double v2 = other.getDeltaMovement().dot(normal);
            // 已经在分离就不重复处理
            if (v1 - v2 <= 0.0D) {
                continue;
            }

            double m1 = Math.max(1.0D, this.getWeight());
            double m2 = Math.max(1.0D, other.getWeight());
            double e = (this.getBounce() + other.getBounce()) / 20.0D;

            double u1 = ((m1 - e * m2) * v1 + (1.0D + e) * m2 * v2) / (m1 + m2);
            double u2 = ((m2 - e * m1) * v2 + (1.0D + e) * m1 * v1) / (m1 + m2);

            this.setDeltaMovement(this.getDeltaMovement().add(normal.scale(u1 - v1)));
            other.setDeltaMovement(other.getDeltaMovement().add(normal.scale(u2 - v2)));

            // 球撞球同样算一次碰撞：两边各消耗 1 点耐久（作者指定）——
            // 弹性系数只决定动能怎么分配，不代表这一下不损伤球体
            // ⚠️ **球撞球不再扣耐久**（作者 2026-10-09 修正）。
            //
            //    原来的规则是「两边各扣 1 点耐久」，但球是会互相持续接触的 ——
            //    两个铁球撞在一起时每刻都在触发，<b>不到一秒就把耐久耗光当场炸掉</b>。
            //    动能传递照旧做（上面已经算过），只是不再记账耐久。
            //    耐久现在只由「撞墙 / 撞生物」这类真正的碰撞消耗。

            // 被撞后转为贴地滚动。
            //
            // ⚠️ **必须限流**：球靠在一起时这一段每 tick 都会重新算一遍，
            //    而 startRollingFromImpulse() 会反复切换状态、还带粒子/音效，
            //    十来个球就足以让服务端每 tick 卡顿（作者 2026-10-09 反馈
            //    「十来个球就特别卡、生物卡一下闪现一下」）。
            //    用「最近碰撞刻」做冷却：同一对球在 COLLIDE_COOLDOWN_TICKS 内只处理一次。
            long now = this.level().getGameTime();
            long thisLast = this.lastBallCollideTick;
            long otherLast = other.lastBallCollideTick;
            if (now - thisLast < COLLIDE_COOLDOWN_TICKS || now - otherLast < COLLIDE_COOLDOWN_TICKS) {
                continue;
            }
            this.lastBallCollideTick = now;
            other.lastBallCollideTick = now;
            this.startRollingFromImpulse();
            other.startRollingFromImpulse();
        }
    }

    /**
     * 线段与点最接近的位置参数 t ∈ [0,1]。
     *
     * <p>用来做连续碰撞检测：把「上一刻到这一刻的相对位移」当成一条线段，
     * 求它离原点最近的那一点 —— 距离够近就说明这一 tick 里擦到了对方。
     * 不对整条路径采样，所以再快的球也不会漏判。</p>
     *
     * @param origin 线段起点
     * @param dir    线段位移（终点 = origin + dir）
     */
    private static double closestApproachT(Vec3 origin, Vec3 dir) {
        double lengthSqr = dir.lengthSqr();
        if (lengthSqr < 1.0E-9D) {
            return 0.0D; // 相对没动，只有起点本身有意义
        }
        double t = -origin.dot(dir) / lengthSqr;
        if (t < 0.0D) {
            return 0.0D;
        }
        return Math.min(t, 1.0D);
    }

    /** 受到外力后转入滚动状态 */
    private void startRollingFromImpulse() {        Vec3 velocity = this.getDeltaMovement();
        this.setDeltaMovement(velocity.x, 0.0D, velocity.z);
        this.impulseTicks = IMPULSE_GRACE_TICKS;
        if (!this.isRolling()) {
            this.setRolling(true);
        }
        this.setSettled(false);
    }

    /**
     * 右键交互 —— 分两种：地上的球直接捡走，空中的球「接住」。
     *
     * <p><b>接球</b>（作者指定）：消耗 1 点耐久，同时让玩家承受该球原本伤害的<b>一半</b>。
     * 若这一下把耐久扣到 0，球会当场碎裂、也就拿不到手了。</p>
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (this.level().isClientSide() || !this.isAlive()) {
            return InteractionResult.PASS;
        }

        if (this.isSettled()) {
            // 地上的球：作者指定「主手副手都空着」才能捡 ——
            // 手上还拿着东西时右键不该把球顺手收走
            if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) {
                return InteractionResult.PASS;
            }
            this.giveTo(player);
            return InteractionResult.SUCCESS;
        }

        // 空中的球：和捡地上的球同一条规矩 —— <b>主手副手都空着</b>才能接（作者指定）。
        // 手上拿着任何东西（包括另一颗球）时右键，都不该是把球收走。
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) {
            return InteractionResult.PASS;
        }
        // 投掷者不能把自己<b>刚出手</b>的球立刻收回来（那等于白扔一下）。
        // 但球飞出去一段之后就允许接回来 —— 不设这道时限的话，自己扔的球永远接不到。
        if (player == this.getOwner() && this.tickCount < OWNER_SELF_PICKUP_DELAY_TICKS) {
            return InteractionResult.PASS;
        }

        // 空中的球：接住它 —— 先结算代价（一半伤害）
        if (this.level() instanceof ServerLevel serverLevel) {
            player.hurtServer(serverLevel, this.damageSources().thrown(this, this.getOwner()),
                    this.damage * CATCH_DAMAGE_RATIO);
        }
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.6F, 1.4F);

        // 再接一次耐久消耗；限次坚固可能因此当场碎裂
        this.consumeDurability("接球");
        if (this.isRemoved()) {
            return InteractionResult.SUCCESS; // 接球过程中球碎了，拿不到
        }

        this.giveTo(player);
        return InteractionResult.SUCCESS;
    }

    /**
     * 把球收进玩家手里（没地方就掉在原地），随后移除实体。
     *
     * <p>作者指定的拾取顺序：<b>快捷栏最前面的空位 → 手里 → 袋子的中转槽 → 背包最前面</b>。
     * 交给 {@link BallPouchHelper#giveToPlayer} 统一处理。</p>
     */
    private void giveTo(Player player) {
        ItemStack stack = this.getItem().copy();
        // 洗掉弩留下的「不可回收」标记，否则捡回来的球与普通球叠不到一起
        BallItem.stripIntangible(stack);
        // 把飞行中消耗掉的耐久写回物品 —— 这就是「全局耐久」
        BallItem.applyToughness(stack, this.getToughness());

        if (!stack.isEmpty() && BallPouchHelper.giveToPlayer(player, stack)) {
            this.discard();
            return;
        }

        // 实在没地方 → 掉在脚边
        if (!stack.isEmpty() && this.level() instanceof ServerLevel serverLevel) {
            this.spawnAtLocation(serverLevel, stack, 0.1F);
        }
        this.discard();
    }

    private ParticleOptions getParticle() {
        ItemStack item = this.getItem();
        return item.isEmpty()
                ? ParticleTypes.ITEM_SNOWBALL
                : new ItemParticleOption(ParticleTypes.ITEM, ItemStackTemplate.fromNonEmptyStack(item));
    }

    /** 碎裂特效：碎屑炸开 + 一圈烟，让「球碎了」一眼可见 */
    @Override
    public void handleEntityEvent(byte id) {
        if (id != 3) {
            return;
        }
        ParticleOptions particle = this.getParticle();
        for (int i = 0; i < 20; i++) {
            this.level().addParticle(particle,
                    this.getX(), this.getY(), this.getZ(),
                    (this.random.nextDouble() - 0.5D) * 0.5D,
                    this.random.nextDouble() * 0.4D,
                    (this.random.nextDouble() - 0.5D) * 0.5D);
        }
        for (int i = 0; i < 10; i++) {
            this.level().addParticle(ParticleTypes.SMOKE,
                    this.getX(), this.getY(), this.getZ(),
                    (this.random.nextDouble() - 0.5D) * 0.3D,
                    this.random.nextDouble() * 0.2D,
                    (this.random.nextDouble() - 0.5D) * 0.3D);
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);

        // 【空气动力球】回家途中撞到作者：这一下就是「到家」，直接交付。
        //
        // 不这么处理的话，球会先撞在作者身上结算伤害、然后被弹开，
        // 于是永远走不到静止态、也就永远回不了背包 ——
        // 现象就是「球在旁边一直弹，就是不进背包」（作者反馈过）。
        if (this.homingBack && hitResult.getEntity() == this.getOwner()
                && hitResult.getEntity() instanceof LivingEntity owner
                && this.level() instanceof ServerLevel level) {
            this.deliverToOwner(level, owner);
            return;
        }

        // 出手后的 0.1 秒内不结算伤害：防止贴脸投掷、反弹回来把自己打死
        if (this.tickCount < SPAWN_GRACE_TICKS) {
            MoreBalls.LOGGER.info("[ball][伤害] 早退：出手宽限期内（tick={} < {}），目标={}",
                    this.tickCount, SPAWN_GRACE_TICKS, hitResult.getEntity().getName().getString());
            return;
        }
        Entity entity = hitResult.getEntity();

        // 会打架的怪物有可能把球接下来（按难度掷概率），接着蓄力扔回来 ——
        // 接住了就整颗球归它，命中伤害自然不结算
        if (entity instanceof Mob mob && BallMobAI.tryCatchBall(mob, this)) {
            MoreBalls.LOGGER.info("[ball][伤害] 早退：{} 接住了球（Mob 接球），不结算伤害", mob.getName().getString());
            return;
        }

        // 【善良】：<b>不伤害同阵营</b> —— 碰到自己人就原样弹开，不结算伤害。
        //
        // 作者 2026-10-09 明确：判据是「**同阵营**」，而不是「对方是不是友好生物」。
        // 也就是**按发射者**决定：玩家扔出的球不伤玩家阵营（友好 + 中立），
        // 而**敌怪扔出的球不伤敌怪** —— 怪物互相残杀不算「善良」。
        //
        // 阵营用原版的 {@link Enemy} 接口划分：僵尸 / 骷髅 / 苦力怕 / 掠夺者实现它，
        // 中立（猪 / 末影人）与友好（村民 / 铁傀儡）不实现 ——
        // 比按 MobCategory 分类更准，也自动涵盖其它模组添加的生物。
        // ⚠️ 前提是**还知道发射者是谁**。发射者已经消失时（典型的：持球苦力怕扔完球
        //    当场自爆，球的主人 UUID 查不到了）无从判断「是不是自己人」——
        //    此时**不弹开、照常结算伤害**。
        //    否则 owner 为 null 会让 isSameSide 恒返回 true，那颗球就谁都不打
        //    （作者 2026-10-09 实测：苦力怕扔的紫水晶球打玩家不掉血）。
        if (this.getOwner() != null
                && this.profile().kindness()
                && entity instanceof LivingEntity other
                && other != this.getOwner()
                && isSameSide(this.getOwner(), other)) {
            MoreBalls.LOGGER.info("[ball][伤害] 早退：{}（kindness={}）判定为「同阵营」被弹开，不结算伤害。"
                            + " 发射者={} 是敌对={}，目标={} 是敌对={}",
                    other.getName().getString(), this.profile().kindness(),
                    String.valueOf(this.getOwner()),
                    this.getOwner() instanceof net.minecraft.world.entity.monster.Enemy,
                    other.getName().getString(),
                    other instanceof net.minecraft.world.entity.monster.Enemy);
            this.bounceOffEntity(other);
            this.friendlyBounced = true;   // 见 onHit：别让二次 bounceBack 覆盖它
            return;
        }

        if (this.level() instanceof ServerLevel serverLevel) {
            // 命中伤害走 impactDamage()：基础伤害再按「实际速度 / 投掷基准速度」加成，
            // 所以被爆炸打飞的球会比手扔的疼（作者指定）
            float dealt = this.impactDamage();
            boolean hurt = entity.hurtServer(serverLevel, this.damageFromOwner(), dealt);

            // ===== 两个「被打」成就（都在确认真的掉了血之后才发）=====
            if (hurt && entity instanceof Player victim) {
                // 「你咋也有？」：被**怪物**用球击中（玩家之间互扔不算）
                if (this.getOwner() instanceof Mob) {
                    ModAdvancements.award(victim, ModAdvancements.HURT_BY_MOB_BALL);
                }
                // 「谁**掺石子了？」：被任意**雪球类**的球打掉血。
                // 判据是名字里带 snowball 的球 —— 也就是「雪球？」系列那四颗，
                // 它们本来就更像雪球而不是石头球。
                if (this.getItem().is(ModTags.Items.BALLS_SNOWBALL)) {
                    ModAdvancements.award(victim, ModAdvancements.SNOWBALL_HURT);
                }
            }

            // 【熔融】：热量攒够阈值后，这一击会给目标挂上「熔融物烧伤」。
            // 空心铁球那颗是 1000 —— 得先在矿脉附近飞一会儿把矿石烤热、自己攒够热量才行。
            BallBehavior.BallProfile moltenProfile = this.profile();
            if (moltenProfile.hasMolten() && this.heat >= moltenProfile.moltenThreshold()
                    && entity instanceof LivingEntity livingTarget) {
                MoltenBurnEffect.applyMoltenBurn(serverLevel, livingTarget,
                        this.getOwner() instanceof LivingEntity livingOwner ? livingOwner : null,
                        BallBehavior.MOLTEN_BURN_TICKS);
            }

            // 【金光闪闪】：金球砸猪灵不结仇。
            // 顺便给这只猪灵留个记号 —— 它之后捡起这颗球时，交易是不是「特殊交易」
            // 就看这个记号（被球打过就只能拿普通回礼）。
            if (entity instanceof AbstractPiglin piglin
                    && BallBehavior.isGoldShiny(this.getItem())) {
                piglin.setData(ModAttachments.GOLD_BALL_HURT.get(), true);
                PiglinLure.forgetAnger(piglin);
            }
        }
    }

    /**
     * 【善良】碰到友好 / 中立生物时的反弹。
     *
     * <p>做的是<b>标准向量反射</b>：把速度沿「球 → 目标」的法线翻折，
     * 再乘一个阻尼。这样是一次干净的弹开，而不是原路折回 ——
     * 后者会让球沿着来路飞回去打在投掷者身上。</p>
     *
     * <p>弹射值高的球反弹后保留的速度更多（和方块弹射共用 {@code bounce} 这个性质）。</p>
     */
    private void bounceOffEntity(LivingEntity target) {
        Vec3 delta = this.getDeltaMovement();
        Vec3 away = this.position().subtract(target.position());

        // 完全重叠时法线取不到，退化成「原路返回」
        Vec3 normal = away.lengthSqr() < 1.0E-6
                ? (delta.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 1.0, 0.0) : delta.reverse().normalize())
                : away.normalize();

        // r = v - 2(v·n)n
        double along = delta.dot(normal);
        Vec3 reflected = delta.subtract(normal.scale(2.0 * along));

        // 弹射越高保留得越多；再追加一点法向推力，确保能脱离目标（不然会黏住反复触发）
        double keep = this.profile().bounce() > 0 ? 0.90 : 0.70;
        Vec3 out = reflected.scale(keep).add(normal.scale(0.12));
        this.setDeltaMovement(out);

        // 沿法线顶出去一点，避免下一刻还判定在同一个命中位置
        this.setPos(this.getX() + normal.x * 0.15, this.getY() + normal.y * 0.15,
                this.getZ() + normal.z * 0.15);

        BallBehavior.BallSound sound = this.profile().sound();
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                sound.resolveHitSound(), SoundSource.NEUTRAL,
                0.7F * sound.volume(), 1.35F);
    }


    /**
     * 【热量积累效率】= 当前速度 / 自身初速度（作者 2026-10-08 指定）。
     *
     * <p>基础增量乘以这个系数才是实际增量。于是：</p>
     * <ul>
     *   <li><b>停住的球效率为 0</b> —— 不再凭空积热（修掉的历史遗留问题）</li>
     *   <li>飞得越快积得越快，扔出去的那一下增量最大</li>
     *   <li>初速度取球自己的 {@code profile.velocity()}，所以慢球（雪球那类）与
     *       快球各自以「自己扔出去时的速度」为基准</li>
     * </ul>
     *
     * <p>双向的三个入口（球自身、金属矿、生物）都走这一个函数，
     * 保证「热量的叠加是双向的、且两侧同速」。</p>
     */
    private float heatEfficiency() {
        // 分母 = **0 级蓄力的出手速度**（作者 2026-10-08 明确指定）。
        //
        // 这个量在投掷那边叫「无蓄力投掷速度」，就是 profile.physicalVelocity()：
        //   BallThrowHandler 里 baseVelocity = profile.physicalVelocity()，
        //   实际出手速度 = baseVelocity × BallCharge.velocityMultiplier(蓄力等级)。
        // 所以用 0 级（倍率 1.0）的速度当基准，正好是「不蓄力扔出去那一下」的速度。
        //
        // ⚠️ 千万别写成 profile.velocity()：那是**配置量纲**（基准 10），
        //    而 getDeltaMovement() 是**物理量纲**，两者差一个 LAUNCH_SPEED_SCALE（0.15）。
        //    写错的话比值整体缩水 6.7 倍，表现就是「热量积累慢了很多」。
        float base = this.profile().physicalVelocity();
        if (base <= 0.0F) {
            return 0.0F;   // 没配初速度就别积热，免得除以零
        }
        return (float) (this.getDeltaMovement().length() / base);
    }
    /**
     * 回归虚化：<b>整条命中链都跳过</b>。
     *
     * <h2>为什么非要在这一层拦</h2>
     * 实测日志里球的「速度 = 4.0」而「190 刻只挪了 0.8 格」—— 开关（`noPhysics`、`noGravity`）
     * 全都是对的，速度也对，就是位移不兑现。原因就在这条链上：
     * 投射物撞到方块时，`hitTargetOrDeflectSelf` 会在<b>调用 `onHit` 之前</b>
     * 就把球按到方块表面、并把速度改掉。我只在 `onHit` 里 return，拦到的是第二道工序，
     * 球早被按住了。
     *
     * <p>返回 {@code NONE} 表示「不偏转、什么都不做」，让它老老实实一路穿过去。
     * 穿墙的耐久代价由 {@link #chargeForPhasing()} 独立记账，不受影响。</p>
     */
    @Override
    protected ProjectileDeflection hitTargetOrDeflectSelf(HitResult hitResult) {
        if (this.homingBack) {
            return ProjectileDeflection.NONE;
        }
        return super.hitTargetOrDeflectSelf(hitResult);
    }

    /** 命中分岔：<b>穿透 → 继续飞；坚固 → 反弹；其余 → 碎裂</b> */
    @Override
    protected void onHit(HitResult hitResult) {
        // 【金光闪闪】作者 2026-10-08 指定：金球**第一次碰撞**之后 5 秒内，
        // 猪灵与猪灵蛮兵不会被它捡起，且这段时间会被强制禁用仇恨并被它吸引。
        // 每次碰撞都把窗口往后推 5 秒 —— 见 refreshGoldLureWindow()。
        if (BallBehavior.isGoldShiny(this.getItem())) {
            this.refreshGoldLureWindow();
        }
        // 回归虚化期间穿过一切，不结算任何碰撞。
        //
        // 光设 noPhysics 不够：那只让 move() 不再做碰撞推挤，
        // 而投射物自己的射线检测照样会命中方块并回调到这里 ——
        // 于是球回归时仍会撞墙、被弹开甚至撞碎，表现就是「虚化没实现」。
        // 穿墙的耐久代价由 chargeForPhasing 独立记账，不受这里影响。
        if (this.homingBack) {
            return;
        }

        if (hitResult.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHit = (EntityHitResult) hitResult;

            if (this.getPierceLevel() > 0) {
                if (this.piercingIgnoreEntityIds == null) {
                    this.piercingIgnoreEntityIds = new IntOpenHashSet(5);
                }
                if (this.piercingIgnoreEntityIds.size() >= this.getPierceLevel() + 1) {
                    if (this.isTough()) {
                        super.onHit(hitResult);
                        // 同上：善良已经反射过就不要覆盖
                        if (this.friendlyBounced) {
                            this.friendlyBounced = false;
                        } else {
                            this.bounceBack();
                        }
                    } else {
                        this.burst();
                    }
                    return;
                }
                this.piercingIgnoreEntityIds.add(entityHit.getEntity().getId());
                super.onHit(hitResult);
                this.consumeDurability("穿透");
                return;
            }

            if (this.isTough()) {
                super.onHit(hitResult);
                // ⚠️ 【善良】命中友好生物时 super.onHit 已经走过 bounceOffEntity
                //    做过标准向量反射；这里再无条件 bounceBack()（水平整体取反）
                //    会把那次反射**覆盖成原路折回** —— 表现就是「善良的反弹不生效」。
                if (this.friendlyBounced) {
                    this.friendlyBounced = false;   // 用完即清，别影响后续命中
                } else {
                    this.bounceBack();
                }
                return;
            }
        } else if (hitResult.getType() == HitResult.Type.BLOCK) {
            // 【点金】：命中方块时先把这一片石头点成矿物（不坚固的球也要触发，
            // 所以放在 isTough() 判断之外）
            if (this.profile().transmuteChance() > BallBehavior.NOT_TRANSMUTE
                    && this.level() instanceof ServerLevel serverLevel) {
                BallTransmute.transmute(serverLevel, (BlockHitResult) hitResult);
            }

            if (!this.isTough()) {
                // 不坚固的球落下去走统一的碎裂流程
                super.onHit(hitResult);
                this.burst();
                return;
            }

            BlockHitResult blockHit = (BlockHitResult) hitResult;
            if (this.isRolling() && blockHit.getDirection().getAxis() == Direction.Axis.Y) {
                return;
            }
            // 落定后的「坐实」阶段不结算反弹 —— 作者指定这 5 刻只有下落、不弹。
            // 位置本身仍受方块碰撞约束（那是 move() 干的），所以它只是贴到地面，不会陷进去。
            if (this.settleDropTicks > 0) {
                return;
            }
            this.bounceOff(blockHit.getDirection());
            return;
        }

        super.onHit(hitResult);
        this.burst();
    }

    /** 撞方块反弹：先沿碰撞面法线推离，再做镜面反射，最后按弹射衰减 */
    private void bounceOff(Direction face) {
        Vec3 vel = this.getDeltaMovement();
        double nx = face.getStepX();
        double ny = face.getStepY();
        double nz = face.getStepZ();
        double dot = vel.x * nx + vel.y * ny + vel.z * nz;

        this.setPos(this.getX() + nx * SURFACE_PUSH,
                this.getY() + ny * SURFACE_PUSH,
                this.getZ() + nz * SURFACE_PUSH);

        this.applyBounce(new Vec3(
                vel.x - 2.0D * dot * nx,
                vel.y - 2.0D * dot * ny,
                vel.z - 2.0D * dot * nz));
    }

    /** 撞实体反弹：水平速度反向，竖直分量保留 */
    private void bounceBack() {
        Vec3 vel = this.getDeltaMovement();
        this.applyBounce(new Vec3(-vel.x, vel.y, -vel.z));
    }

    private void applyBounce(Vec3 direction) {
        if (this.level().isClientSide()) {
            return;
        }

        Vec3 bounced = direction.scale(this.getBounce() / 10.0D);

        // 微小的向上分量直接抹掉。
        // 留着它的话，球会带着一丁点速度「慢慢往上飘」—— 因为重力本来就小，
        // 这点速度够它飘很久，看起来就像球自己无端飞起来了（作者反馈过这个现象）。
        if (bounced.y > 0.0D && bounced.y < MIN_BOUNCE_UP_SPEED) {
            bounced = new Vec3(bounced.x, 0.0D, bounced.z);
        }

        // 转入滚动的判据按「这一跳还能弹多高」算（高度 = vy² / 2g），而不是绝对速度
        double gravity = BallWeight.gravityFor(this.getWeight(), this.isFromCrossbow());
        if (BallRoll.shouldRoll(bounced.y, gravity)) {
            bounced = new Vec3(bounced.x, 0.0D, bounced.z);
            this.setRolling(true);
        }

        // 只要这一下真给了速度，就必须退出静止态 ——
        // 静止态是<b>断重力</b>的，带着速度留在静止态就等于永远飘着不落地。
        // 之前的「慢慢往上飞」正是从这里漏出来的：球被弹起却仍被当成静止。
        if (bounced.lengthSqr() > 1.0E-6D) {
            this.setSettled(false);
        }

        this.setDeltaMovement(bounced);
        // 撞击音按每个球的音效配置取，音量也由配置决定（铁球类压到 30%）
        BallBehavior.BallSound sound = this.profile().sound();
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                sound.resolveHitSound(), SoundSource.NEUTRAL, 0.4F * sound.volume(), 1.4F);
        this.consumeDurability("反弹");
    }

    /**
     * 限次坚固：每次碰撞扣 1 点耐久，归零即碎裂。
     *
     * <p>同一 tick 内只结算一次 —— 球在方块表面抖动时可能连续触发碰撞，
     * 那种重复绝不该算成「弹了两次」。</p>
     */
    private void consumeDurability(String cause) {
        int toughness = this.getToughness();
        if (toughness == BallBehavior.TOUGH_FOREVER || toughness <= BallBehavior.NOT_TOUGH) {
            return;
        }
        if (this.tickCount == this.lastDamageTick) {
            return; // 同一 tick 内的重复碰撞不计
        }
        this.lastDamageTick = this.tickCount;

        int remaining = Math.max(0, toughness - 1);
        this.setToughness(remaining);

        if (remaining == 0) {
            this.burst();
        }
    }

    /** 当前球的行为参数 —— 实体通过携带的物品堆反查，不必把一整份参数都塞进同步数据 */
    private BallBehavior.BallProfile profile() {
        return BallBehavior.profileFor(this.getItem());
    }

    /**
     * 【导电】被自然闪电劈中时的处理 —— 消耗 1 点耐久（作者指定）。
     *
     * <p>走的是和碰撞同一套扣耐久逻辑，所以「耐久归零就碎裂」、
     * 「同一 tick 不重复结算」这些既有规则一并生效。</p>
     *
     * <p>公开给 {@link BallConduction} 调用 —— 它负责把闪电挪到这颗球身上。</p>
     */
    public void onStruckByLightning() {
        this.consumeDurability("lightning");
    }

    /** 【智慧】的扫描半径（格）—— 作者指定 10 */
    public static final double WISDOM_RADIUS = 10.0;



    /** 【智慧】发射后持续尝试多少刻；过了这段还没找到就算了，别一直扫 */
    private static final int WISDOM_SCAN_TICKS = 40;

    /** 【智慧】是否已经锁定过 */
    /** 球撞球的处理冷却（刻）—— 防止贴在一起的球每 tick 重复触发 */
    private static final int COLLIDE_COOLDOWN_TICKS = 8;

    /** 球撞球的检测间隔（刻）—— 每 tick 做是 O(n²)，球一多就卡 */
    private static final int BALL_COLLIDE_INTERVAL = 4;

    /** 单次球撞球检测的搜索半径上限（格） */
    private static final double MAX_COLLIDE_REACH = 3.0D;

    /** 本球上一次被别的球撞到的游戏刻 */
    private long lastBallCollideTick = Long.MIN_VALUE / 2;

    private boolean wisdomLocked;   // 只表示「当前处于锁定态」，不再是一道永久闸门

    /**
     * 【智慧N】的<b>剩余追踪次数</b>。
     *
     * <p>{@code -1} = 还没初始化（首次 tick 时从 {@code profile().wisdom()} 取）。
     * 每次<b>成功锁定</b>扣 1，扣到 0 就彻底不再追踪 —— 于是球会正常坠落、静止、
     * 并交还给【空气动力球】的回收逻辑。</p>
     */
    private int wisdomUsesLeft = -1;

    /** 开启【智慧】追踪前的原始重力状态，次数耗尽后还原 */
    private boolean wisdomGravityBefore = false;

    /** 【善良】已经在 onHitEntity 里反弹过 —— 告诉 onHit 不要再 bounceBack 覆盖它 */
    private boolean friendlyBounced;

    /** 【智慧】的重锁倒计时（刻）—— 归零时重新搜索并锁定目标 */
    private int wisdomRelockCooldown;

    /** 【智慧】多久重新锁定一次目标（刻）。原来是「只锁一次」，那对怪物扔回的球几乎无效 */
    /** 重锁间隔 —— 作者指定 0.5 秒（10 刻） */
    /** 重锁间隔 —— 作者指定 <b>0.5 秒</b>（10 刻） */
    private static final int WISDOM_RELOCK_INTERVAL = 10;

    /**
     * 【智慧】的扫描与锁定。
     *
     * <p>找的是「最近的一个合格目标」：
     * <b>敌对生物</b>（实现了原版 {@link Enemy}），或<b>正处于仇恨中的中立生物</b>
     * （有 {@code getTarget()}）。后者是按作者的说法「处于仇恨中」——
     * 也就是「你打了它、它记恨了谁」，而不是路过的猪牛羊。</p>
     *
     * <p>「没有方块遮挡可以直接抵达」用一次方块射线判定：从球心到目标胸口，
     * 全程不碰到碰撞体才算通。这样隔墙的怪不会被锁。</p>
     *
     * <p>锁定后<b>保留当前速度大小、只换方向</b> —— 球飞得快的还是快，
     * 不会因为锁定突然减速或加速。</p>
     */
    private void tryWisdomLock() {
        // 不再有「只在前 40 刻搜索」的硬窗口 —— 只靠上面的重锁间隔限流，
        // 否则球飞过 2 秒后就永远不再识别新目标。
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }

        // ===== 怪物持有的【智慧】球：目标与持有者一致（作者 2026-10-07 指定）=====
        //
        // 玩家扔的时候是「自己找最近的敌人」，而怪物扔的时候不该各找各的 ——
        // 它该打谁就打谁。这样一群怪朝你扔【智慧】球时不会散开去打别人。
        if (this.getOwner() instanceof Mob ownerMob && ownerMob.getTarget() != null) {
            LivingEntity mobTarget = ownerMob.getTarget();
            // 目标可能刚死、或者隔着墙 —— 那就退回下面的通用扫描
            if (mobTarget.isAlive() && !mobTarget.isSpectator()
                    && mobTarget != this.getOwner()
                    && this.hasClearPathTo(level, mobTarget)) {
                this.lockOnto(mobTarget);
                return;
            }
        }

        LivingEntity best = null;
        double bestDistance = WISDOM_RADIUS;
        AABB box = this.getBoundingBox().inflate(WISDOM_RADIUS);

        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (candidate == this.getOwner() || candidate == this.getVehicle()) {
                continue;
            }
            if (!candidate.isAlive() || candidate.isSpectator()) {
                continue;
            }
            if (!isWisdomTarget(candidate)) {
                continue;
            }
            if (!hasClearPathTo(level, candidate)) {
                continue;
            }
            double distance = this.position().distanceTo(candidate.position());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        if (best == null) {
            return;
        }
        this.lockOnto(best);
    }

    /**
     * 锁定到指定目标 —— 把速度改成朝它飞。
     *
     * <p>瞄<b>胸口</b>高度而不是脚底：不然贴地球飞过去会从腿边擦过。</p>
     *
     * <p><b>保留当前速度大小、只换方向</b> —— 球飞得快的还是快，
     * 不会因为锁定突然减速或加速。</p>
     */
    /**
     * 【智慧x】是否还在追踪中（次数未耗尽）。
     *
     * <p>追踪期间这颗球<b>不受重力</b>、也<b>不被【空气动力球】回收</b> ——
     * 它会一直追到把「机会」用完为止（作者 2026-10-09 指定）。</p>
     */
    private boolean wisdomStillTracking() {
        return this.profile().wisdom() > 0 && this.wisdomUsesLeft > 0;
    }

    private void lockOnto(LivingEntity target) {
        this.wisdomLocked = true;

        // 【智慧N】每次成功锁定扣一次 —— 扣到 0 就不再追踪
        // （作者 2026-10-09：数值代表可追踪次数，消耗完就不再锁定）
        if (this.wisdomUsesLeft > 0) {
            this.wisdomUsesLeft--;
        }

        // 【智慧】成就：首次触发锁定 —— 记在**投掷者**头上（怪物扔的不算，
        // 它没有成就页；作者给出的文案也是给玩家看的）
        if (this.getOwner() instanceof Player wisdomOwner) {
            ModAdvancements.award(wisdomOwner, ModAdvancements.WISDOM);
        }

        Vec3 aim = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
        Vec3 direction = aim.subtract(this.position());
        if (direction.lengthSqr() < 1.0E-6) {
            return;
        }
        // 【智慧】的锁定 = **只把方向转向目标，速度大小原样保留**。
        //
        // ⚠️ 这里**不要**加任何「向上抬升」之类的人为修正。
        //    作者 2026-10-09 明确：智慧就该是「反弹之后保留速度、无视重力、让球自己飞」——
        //    球在追踪期间本来就不吃重力（见 tick 里的三段式），它会自然保持高度飞过去。
        //    我之前加的上抬分量反而是「直接往天上飞」的元凶，已删除。
        double speed = Math.max(0.6, this.getDeltaMovement().length());
        this.setDeltaMovement(direction.normalize().scale(speed));
    }

    /**
     * 这颗生物算不算【智慧】的目标。
     *
     * <p>三个来源：<b>敌对生物</b>（原版 {@link Enemy} 接口，自动涵盖模组生物）、
     * <b>有仇恨目标的中立生物</b>，以及<b>玩家</b>。</p>
     *
     * <p>为什么玩家也算 —— 作者 2026-10-08 报的：怪物拿【智慧】球扔玩家时，
     * 球完全不会锁定。原因是玩家既不是 {@link Enemy}、也不是 {@link Mob}，
     * 在上面两条判据里全部落空。把玩家放进来之后：</p>
     * <ul>
     *   <li>玩家扔的球 —— 自己会被 {@code candidate == getOwner()} 排除，不会自锁</li>
     *   <li>怪物扔的球 —— 锁定最近的玩家，正是想要的行为</li>
     *   <li>玩家之间互扔 —— 会互相锁定（相当于 PVP 里多了个追踪，合理）</li>
     * </ul>
     */
    /**
     * 发射者与命中目标是不是「同一阵营」。
     *
     * <p>划分依据是原版的 {@link Enemy} 接口 —— 它是「敌对生物」的标记接口：
     * {@code Monster} 与 {@code Piglin} 系都实现它，而动物、村民、铁傀儡不实现。</p>
     *
     * <ul>
     *   <li>发射者是敌怪 → 命中敌怪算同阵营（不伤害）</li>
     *   <li>发射者不是敌怪（玩家 / 动物 / 没有发射者） → 命中非敌怪算同阵营</li>
     * </ul>
     *
     * <p>没有发射者时按「玩家阵营」算 —— 无主之球不该误伤村民与动物。</p>
     */
    private static boolean isSameSide(net.minecraft.world.entity.Entity owner, LivingEntity target) {
        boolean ownerHostile = owner instanceof Enemy;
        boolean targetHostile = target instanceof Enemy;
        return ownerHostile == targetHostile;
    }
    private static boolean isWisdomTarget(LivingEntity entity) {
        if (entity instanceof Enemy) {
            return true;
        }
        if (entity instanceof Player) {
            return true;
        }
        return entity instanceof Mob mob && mob.getTarget() != null;
    }

    /** 从球心到目标胸口有没有方块挡着 */
    private boolean hasClearPathTo(ServerLevel level, LivingEntity target) {
        Vec3 from = new Vec3(this.getX(), this.getY() + this.getBbHeight() * 0.5, this.getZ());
        Vec3 to = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ());
        BlockHitResult hit = level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * 【熔融】状态的外观 —— <b>火焰粒子环绕 + 拖尾</b>（作者 2026-10-07 指定）。
     *
     * <p>判据和伤害那边同一条：{@code profile.hasMolten()} 且自身热量已经攒到
     * {@code moltenThreshold}（空心铁球是 1000）。没到这个温度就是普通球，
     * 一旦到了它已经在发红发热，外面也得看得出来。</p>
     *
     * <p>两层观感：</p>
     * <ul>
     *   <li><b>环绕</b>：球身边随机方位上冒一点火，看起来像整颗球被火焰包着</li>
     *   <li><b>拖尾</b>：顺着速度反方向拖一串火，快飞时有明显的火线；
     *       再偶尔掺一缕烟，像烧红的铁在冒</li>
     * </ul>
     *
     * <p>隔刻发射即可 —— 逐刻发会把球糊成一团光斑，反而看不出是球。</p>
     */
    private void moltenParticlesTick() {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        BallBehavior.BallProfile profile = this.profile();
        if (!profile.hasMolten() || this.heat < profile.moltenThreshold()) {
            return;
        }
        if (this.tickCount % 2 != 0) {
            return;
        }

        // ① 环绕：球身边随机方位上冒一点火（随机源用实体自带的 —— Level.random 是 protected）
        RandomSource random = this.getRandom();
        double angle = random.nextDouble() * (Math.PI * 2.0D);
        double ring = 0.28D;
        level.sendParticles(ParticleTypes.FLAME,
                this.getX() + Math.cos(angle) * ring,
                this.getY() + 0.05D + random.nextDouble() * 0.22D,
                this.getZ() + Math.sin(angle) * ring,
                1, 0.0D, 0.0D, 0.0D, 0.0D);

        // ② 拖尾：沿速度反方向拉一串
        Vec3 velocity = this.getDeltaMovement();
        if (velocity.length() > 0.05D) {
            Vec3 back = velocity.normalize().scale(-0.30D);
            level.sendParticles(ParticleTypes.FLAME,
                    this.getX() + back.x, this.getY() + 0.10D + back.y, this.getZ() + back.z,
                    1, 0.02D, 0.02D, 0.02D, 0.005D);
            if (random.nextFloat() < 0.4F) {
                level.sendParticles(ParticleTypes.SMOKE,
                        this.getX() + back.x * 1.6D, this.getY() + 0.12D + back.y * 1.6D,
                        this.getZ() + back.z * 1.6D,
                        1, 0.01D, 0.01D, 0.01D, 0.0D);
            }
        }
    }

    /**
     * 「探寻」每刻结算 —— 「雪球？（铁粒）」的特殊能力。
     *
     * <p>半径 5 的立方体有 1331 格，每刻全扫太贵，所以每
     * {@link BallProspecting#SCAN_INTERVAL} 刻才重新扫一次，中间的每刻沿用上次结果继续累加热量。</p>
     */
    private void prospectingTick() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (--this.scanCooldown <= 0) {
            this.scanCooldown = BallProspecting.SCAN_INTERVAL;
            BallProspecting.ScanResult result = BallProspecting.scan(serverLevel, this.position());
            this.nearbyMetalBlocks = result.metalBlocks();
            this.nearbyOres = result.orePositions();

            this.nearbyEntities = serverLevel.getEntitiesOfClass(LivingEntity.class,
                    this.getBoundingBox().inflate(BallProspecting.RADIUS));
            // 检测半径 15 只负责「提前看到」；偏转目标按热量积累速度竞争，
            // 且只有进入偏转半径 7 的候选有资格（矿物与生物同一条赛道）
            // 投掷者本人排除在外 —— 自己扔的球不该往自己身上拐
            Entity owner = this.getOwner();
            List<LivingEntity> candidates = this.nearbyEntities.stream()
                    .filter(e -> e != owner)
                    .toList();
            // 偏航只归【磁性】管 —— 空心铁球只有【感应】（只管积热），不该自己拐弯。
            // 之前这里无条件选目标，于是 2.0.0 给它加上感应之后它就开始「飞出去」了（作者反馈过）。
            this.magnetTarget = this.profile().hasMagnetic()
                    ? BallProspecting.bestMagnetTarget(serverLevel, this.position(), this.nearbyOres, candidates)
                    : null;
        }

        // 磁吸转向：直接把速度方向指向目标 —— 掠过头了也会掉头咬回来，
        // 所以孤零零一格矿物同样追得到（螺旋收敛那套对单格矿物会错过）
        if (this.magnetTarget != null) {
            this.setDeltaMovement(BallProspecting.homeToward(
                    this.getDeltaMovement(), this.position(), this.magnetTarget.position()));
        }

        // 玩家与生物热量：先把范围内的目标加热（衰退由 BallHeatHandler 统一处理）
        this.heatNearbyEntities(serverLevel);

        // ===== 球自身热量：**双向叠加**（作者 2026-10-08 指定）=====
        //
        // 两个来源加在一起：
        //   · 附近的**金属方块**：每格每刻 +1
        //   · 附近的**生物**：按它身上的金属装备件数给，与「生物那边被加热的速度」完全一致
        //     （生物侧是 heatNearbyEntities 里每件 +1，这边镜像过来，所以是双向等速的）
        //
        // 之前只算了方块那一半 —— 球擦着一身铁甲的玩家飞过去，球自己一点热都不积，
        // 那套「热是双向的」就只成立了一半。
        int heatFromEntities = 0;
        Entity heatOwner = this.getOwner();
        for (LivingEntity nearby : this.nearbyEntities) {
            if (nearby == heatOwner) {
                continue;   // 作者自己不给球加热（和「球不烤作者」对称）
            }
            heatFromEntities += BallProspecting.countMetalEquipment(nearby);
        }

        int totalGain = this.nearbyMetalBlocks + heatFromEntities;
        if (totalGain <= 0) {
            return;
        }
        // 【热量积累效率】（作者 2026-10-08 指定）：实际增量 = 基础增量 × (当前速度 / 初速度)。
        //
        // 这修掉了一个历史遗留问题 —— 停在【感应】球也会一直积热。
        // 球停下来后速度为 0，效率也就是 0，于是热量不再增长；飞得越快积得越快。
        // 双向的两个来源（方块侧、生物侧）都乘同一个系数，保持对称。
        this.heat += totalGain * heatEfficiency();

        // 同时给范围内的矿升温：每格每刻 +（1 + 紧邻六面矿物数），攒到阈值会被炸开。
        // 这里只负责升温，降温与爆破交给 BallHeatHandler 的世界 tick —— 球飞走后余热也该继续散
        ProspectingHeatData heatData = ProspectingHeatData.get(serverLevel);
        long gameTime = serverLevel.getGameTime();
        for (BlockPos ore : this.nearbyOres) {
            // 顺手记下加热者：矿物被烤熟是在世界 tick 里结算的，那时候球早飞走了，
            // 只有现在把「谁干的」写进去，成就「这真的科学吗？」才知道该发给谁。
            // 怪物烤熟的不记（它没有成就页），统一留空串。
            String heater = this.getOwner() instanceof Player heaterPlayer
                    ? heaterPlayer.getStringUUID() : "";
            heatData.addHeat(ore, (1.0F + BallProspecting.countNeighborOres(serverLevel, ore))
                            * heatEfficiency(),
                    gameTime, heater);
        }


        // 攒到「融化」阈值就化掉（雪球类默认 200）；没配融化的球不会被热量搞没
        BallBehavior.BallProfile profile = this.profile();

        // 诊断：带【熔融】的球，热量每跨过 100 点记一条 ——
        // heat 是实体内部字段（不进 NBT、不同步客户端），想让作者「看看攒到多少了」只能靠日志。
        // 用「档位」而不是「每刻」来打，一条日志对应 100 点，既看得到过程也不会刷屏。
        if (profile.hasMolten()) {
            int milestone = (int) (this.heat / HEAT_DIAG_STEP);
            if (milestone != this.heatDiagMilestone) {
                this.heatDiagMilestone = milestone;
                MoreBalls.LOGGER.info("[ball] {} 热量 {} / {}（邻近金属矿 {} 格）",
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(this.getItem().getItem()),
                        this.heat, profile.moltenThreshold(), this.nearbyMetalBlocks);
            }
        }

        if (profile.hasMelt() && this.heat >= profile.meltThreshold()) {
            this.melt(serverLevel);
        }

        // 【引雷】—— 空心铜球：热量攒够阈值就释放一次雷电，并<b>清空自身热量</b>。
        //
        // 和【融化】并列放在这里判断 —— 两者都是热量系词条，区别是
        // 融化让自己消失，引雷把自己打出去（球留着，热量清零重新攒）。
        if (profile.hasThunder() && this.heat >= profile.thunderThreshold()) {
            this.heat = 0;
            BallThunder.release(serverLevel, this);
        }
    }

    /**
     * 给范围内的目标加热 —— <b>玩家与生物一视同仁</b>（作者指定），
     * 但<b>投掷者本人除外</b>：热量机制不该烧到使用者自己。
     *
     * <p>穿/持金属装备 → 每件每刻 +1（全套铁甲 4 点、再加把铁剑 5 点）；
     * 热量超过阈值就把目标点着，每刻续燃直到热量降回去。
     * 僵尸、骷髅戴着铁头盔一样会被烤。
     * 注意这里只负责「加热」，脱离检测后的自然消退在
     * {@code BallHeatHandler} 的实体 tick 事件里统一做 —— 否则球一消失热量就永远留着。</p>
     */
    private void heatNearbyEntities(ServerLevel serverLevel) {
        Entity owner = this.getOwner();
        for (LivingEntity living : this.nearbyEntities) {
            // 使用者自己不吃这套（作者指定）
            if (living == owner) {
                continue;
            }
            int metal = BallProspecting.countMetalEquipment(living);
            if (metal <= 0) {
                continue; // 没穿金属的不吃这套，交给衰退逻辑
            }
            living.setData(ModAttachments.LAST_HEATED_TICK.get(), living.tickCount);

            float heat = living.getData(ModAttachments.PROSPECTING_HEAT.get())
                    + metal * heatEfficiency();
            living.setData(ModAttachments.PROSPECTING_HEAT.get(), heat);

            if (heat > BallProspecting.PLAYER_HEAT_IGNITE) {
                living.igniteForTicks(20);
            }
        }
    }

    /**
     * 「融化」：热量攒到阈值时球化掉（雪球类默认 200）。
     *
     * <p>作者指定：感应球积攒的热量到顶不是「炸开」而是<b>融化</b> ——
     * 毕竟是雪做的球。这里走雪片粒子 + 积雪破碎音，然后<b>必掉</b>
     * {@link BallProspecting#BURST_NUGGETS} 个铁粒（这是「探寻」能力本来就有的回报，
     * 保持不变），最后走常规碎裂流程收尾。</p>
     */
    private void melt(ServerLevel serverLevel) {
        serverLevel.sendParticles(ParticleTypes.SNOWFLAKE,
                this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                12, 0.25D, 0.25D, 0.25D, 0.02D);
        serverLevel.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.SNOW_BREAK, SoundSource.NEUTRAL, 0.8F, 1.2F);
        this.spawnAtLocation(serverLevel,
                new ItemStack(Items.IRON_NUGGET, BallProspecting.BURST_NUGGETS), 0.1F);
        this.burst();
    }

    /** 【空气动力球】：标记这颗球会在静止后自己飞回来 */
    public void setReturnToOwner(boolean value) {
        this.returnToOwner = value;
    }

    /** 标记这是多重射击复制出来的附属弹（落定即碎、碎裂无掉落、也不回家） */
    public void setMultishotSide(boolean value) {
        this.multishotSide = value;
        if (value) {
            this.returnToOwner = false;
        }
    }

    /** 这颗球有没有被认定成多重射击的附属弹 */
    public boolean isMultishotSide() {
        return this.multishotSide;
    }

    /**
     * 标记这颗球<b>静止后破碎</b>（作者 2026-10-07 指定，用于苦力怕用爆炸甩出来的球）。
     *
     * <p>为什么苦力怕的球要这样：它是被爆炸「打」出去的，速度快、方向由爆炸决定，
     * 落点基本是随机的 —— 让它静静躺在地上可捡，就成了「炸一次白送一颗球」。
     * 碎掉就干净了。</p>
     *
     * <p>与 {@link #setMultishotSide(boolean)} 走同一套落定判定，区别只在碎裂时的<b>掉落</b>：
     * 附属弹不掉材料，这颗照常掉（见 {@code burst()} 里那条 {@code !multishotSide} 判断）。</p>
     */
    public void setShatterOnSettle(boolean value) {
        this.shatterOnSettle = value;
    }

    /**
     * 静止后是否破碎 —— 见 {@link #setShatterOnSettle(boolean)}。
     *
     * <p>不收进 synched data：判定完全在服务端做。</p>
     */
    private boolean shatterOnSettle;

    /**
     * 【空气动力球】：把球朝作者方向推过去，像忠诚附魔的三叉戟那样。
     *
     * <p>作者指定的细节都落在这里：回归途中<b>照常结算伤害与耐久</b>
     * （因为位置更新仍然走正常的 {@code move()} 与命中回调，只是速度方向由这里定），
     * 到家时额外来 1 点物理伤害，然后进背包。</p>
     *
     * @return true 表示这一 tick 已经由回归逻辑接管，常规的静止处理应当让路
     */
    private boolean homingToOwner() {
        // 【智慧x】次数未耗尽时**不启动回归** —— 它还在追目标，不该被拽回主人身边。
        // 次数用完（wisdomUsesLeft == 0）才解除这条限制，正常掉落并可被回收。
        // （作者 2026-10-09 指定）
        //
        // ⚠️ 判定必须放在**这里**而不是 isReturning()：本方法才是「开始回家」的入口，
        //    它自己不检查 returnToOwner（由调用方检查），所以只有在这里拦才有效。
        if (this.wisdomStillTracking()) {
            return false;
        }

        Entity owner = this.getOwner();
        if (!(owner instanceof LivingEntity living)
                || !living.isAlive()
                || living.level() != this.level()) {
            return false; // 作者不在了（下线 / 换维度 / 已死），那就老实留在地上
        }

        // 挂区块票据 —— 但<b>只有带【空气动力球】的球才需要</b>（作者指定）。
        //
        // 注意：真正的挂载点已经在 tick() 里（从落地前就挂上），这里这一处算是冗余保险，
        // 留着是因为回归路径上多一道检查没坏处。
        if (this.returnToOwner && this.level() instanceof ServerLevel ticketLevel) {
            this.updateLoadTicket(ticketLevel);
        }

        Vec3 target = living.getEyePosition();
        Vec3 toOwner = target.subtract(this.position());
        if (toOwner.length() <= RETURN_ARRIVE_DISTANCE) {
            if (this.level() instanceof ServerLevel serverLevel) {
                this.deliverToOwner(serverLevel, living);
            }
            return true;
        }

        this.homingBack = true;
        this.setSettled(false);

        // 回归期间照抄「恼鬼三件套」（作者给出的思路）：
        //   1. noPhysics           —— 穿过方块不做碰撞推挤
        //   2. setNoGravity(true)  —— 悬空飞行、不吃重力
        //   3. isAffectedByBlocks  —— 见下面的覆盖，这是「穿墙被减速」的元凶
        // 配合上面的「朝目标加速」就是恼鬼那套飞行方式。
        // 代价照旧按穿墙次数扣耐久（见 chargeForPhasing），所以不是白穿。
        this.noPhysics = true;
        this.setNoGravity(true);
        this.homingTicks++;

        // 越飞越快（作者指定「加速飞回」）
        double speed = Math.min(RETURN_MAX_SPEED,
                RETURN_BASE_SPEED + this.homingTicks * RETURN_ACCEL);

        // 方向不必每刻重算：贴着障碍物边缘时逐刻重算会左右横跳，
        // 表现就是球在空中一抖一抖。隔几刻定一次就够，也省掉大部分射线检测
        if (this.returnDirection == null || this.homingTicks % RETURN_RETARGET_INTERVAL == 0) {
            this.returnDirection = this.chooseReturnDirection(living, toOwner);
        }

        // <b>仿恼鬼：朝目标全程加速前进</b>（作者指定的方案）。
        //
        // 上一版「速度清零 + 手动 setPos」是绕开物理引擎，结果飞得死板、没有惯性 ——
        // 作者一句话点破了：不该绕开引擎，而是该换成恼鬼那种「对着目标持续加速」的走法。
        // 所以这里：
        //   · 方向 —— 每刻立即对齐目标（锐角转弯，不兜圈子）
        //   · 速度 —— 在旧速度基础上持续累加（全程加速，越飞越快）
        //   · 物理 —— 照常交给 move() 处理，惯性和位移都由引擎算
        double currentSpeed = this.getDeltaMovement().length();
        double nextSpeed = Math.min(RETURN_MAX_SPEED, currentSpeed + RETURN_ACCEL);
        this.setDeltaMovement(this.returnDirection.scale(nextSpeed));

        // 已经在方块里 = 正在穿墙，按「每穿一个新方块扣一点耐久」记账
        this.chargeForPhasing();
        return true;
    }

    /**
     * 挑一条尽量不撞墙的回家方向（作者指定：<b>先找无任何障碍的路线，找不到才穿墙</b>）。
     *
     * <p>三步：</p>
     * <ol>
     *   <li>直线通 → 直接朝作者飞</li>
     *   <li>直线被挡 → 往作者上方逐格试探，找一个「球能直通」的抬头点绕过去
     *       （很贴近忠诚附魔那种「从上方绕回来」的观感）</li>
     *   <li>怎么绕都不通 → 硬飞，交给 {@link #chargeForPhasing()} 按穿墙次数扣耐久</li>
     * </ol>
     */
    private Vec3 chooseReturnDirection(LivingEntity owner, Vec3 toOwner) {
        Vec3 eyeTarget = owner.getEyePosition();

        if (this.isPathClear(this.position(), eyeTarget)) {
            return toOwner.normalize();
        }

        for (int up = 1; up <= RETURN_CLIMB_STEPS; up++) {
            Vec3 lifted = eyeTarget.add(0.0D, up, 0.0D);
            if (this.isPathClear(this.position(), lifted)) {
                return lifted.subtract(this.position()).normalize();
            }
        }

        return toOwner.normalize();
    }

    /**
     * 两点之间有没有方块挡着。沿直线按 {@value #PATH_SAMPLE_STEP} 格采样，
     * 任一点落在有碰撞体积的方块里就算「不通」。
     */
    private boolean isPathClear(Vec3 from, Vec3 to) {
        if (!(this.level() instanceof ServerLevel level)) {
            return true; // 客户端不参与判定
        }
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        int steps = Math.max(2, (int) Math.ceil(length / PATH_SAMPLE_STEP));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int i = 1; i < steps; i++) {
            Vec3 point = from.add(delta.scale((double) i / steps));
            cursor.set(point.x, point.y, point.z);
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 穿墙记账 —— 作者指定：<b>每次穿墙消耗一点耐久</b>。
     *
     * <h2>「一次」怎么算</h2>
     * 按<b>连续实心段</b>计，不是按方块格数：
     * <ul>
     *   <li>一堵 1 格厚的实心墙 → 进入实心一次 → <b>扣 1 点</b></li>
     *   <li>一堵 3 格厚、连成一片的实心墙 → 全程都在实心里 → 仍然<b>只扣 1 点</b></li>
     *   <li>3 格厚但中间有空气夹层（实-空-实）→ 空气把它分成两段 → <b>扣 2 点</b></li>
     * </ul>
     * 所以判据是「从空气跨进实心」的那一下。
     *
     * <p>另外路径要<b>采样</b>：球回归时一格/刻能飞 1.6 格，只看落点会把整堵墙漏过去。</p>
     */
    private void chargeForPhasing() {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 from = new Vec3(this.xo, this.yo, this.zo);
        Vec3 delta = this.position().subtract(from);
        int steps = Math.max(1, (int) Math.ceil(delta.length() / PATH_SAMPLE_STEP));

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= steps; i++) {
            Vec3 point = from.add(delta.scale((double) i / steps));
            cursor.set(point.x, point.y, point.z);
            boolean solid = !level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty();

            // 只在「空气 → 实心」这一下记账，所以连成一片的厚墙只算一次
            if (solid && !this.wasInsideBlock) {
                this.consumeDurability("穿墙");
            }
            this.wasInsideBlock = solid;
        }
    }

    /**
     * 这颗球造成的伤害<b>归属到投掷者本人</b>（作者指定：来源要是投掷者，不是球本身）。
     *
     * <p>原版的 {@code thrown(球, 投掷者)} 是「投射物伤害源」，来源仍挂在球这个实体上；
     * 换成 {@code playerAttack} / {@code mobAttack} 之后：</p>
     * <ul>
     *   <li>击杀归属正确 —— 算在投掷者头上，而不是「被球砸死」</li>
     *   <li>仇恨归属正确 —— 猪灵记恨的是扔球的人，不是那颗球</li>
     *   <li>各类「玩家击杀」「生物击杀」的判定与成就也才能正常触发</li>
     * </ul>
     */
    private DamageSource damageFromOwner() {
        Entity owner = this.getOwner();

        // 【电击】：这颗球直接造成的伤害按**闪电类型**结算。
        //
        // ⚠️ 不能直接用 damageSources().lightningBolt() —— 那样会把归属丢掉，
        //    于是击杀不计在投掷者头上、仇恨也记到「闪电」身上。
        //    这里用 source(类型, 归属) 两样都保住：伤害类型是闪电，归属仍是投掷者。
        if (this.profile().shockDamage()) {
            return this.damageSources().source(
                    net.minecraft.world.damagesource.DamageTypes.LIGHTNING_BOLT, owner);
        }

        if (owner instanceof Player player) {
            return this.damageSources().playerAttack(player);
        }
        if (owner instanceof LivingEntity living) {
            return this.damageSources().mobAttack(living);
        }
        // 找不到作者（比如它已下线）时退回投射物来源，总比没有来源好
        return this.damageSources().thrown(this, null);
    }

    /**
     * 回到作者身边：先结算那一下伤害，再把球放进发射者的背包。
     */
    private void deliverToOwner(ServerLevel level, LivingEntity owner) {
        // 作者指定：回归时对作者造成 1 滴血的物理伤害；
        // 本来就没伤害的球（伤害为 0）不造成
        if (this.getDamage() > 0.0F) {
            boolean hurt = owner.hurtServer(level, this.damageFromOwner(),
                    ModEnchantments.AERODYNAMIC_SELF_DAMAGE);

            // ===== 这一对成就共用同一个判定点：**回收受伤的那一刻**（作者指定）=====
            if (hurt && owner instanceof Player victim) {
                // 「它怎么回来的？」：用附魔了空气动力球的弩打出、又被它回来砸到
                ModAdvancements.award(victim, ModAdvancements.AERODYNAMIC_RETURN);
                // 「自刎归天！」（传奇）：被自己回收的球**砸死**。
                // 放在这里而不是查死亡事件，是因为「是不是这一下打死的」
                // 在受伤返回之后就能立刻判断，不必再去比对伤害来源。
                if (!victim.isAlive()) {
                    ModAdvancements.award(victim, ModAdvancements.KILLED_BY_OWN_BALL);
                }
            }
        }

        ItemStack stack = this.getItem().copy();
        BallItem.stripIntangible(stack);
        BallItem.applyToughness(stack, this.getToughness());

        // 作者指定的回收顺序：袋子的中转槽 → 背包最前 → 快捷栏最前 → 掉成掉落物。
        // （用附魔回收时玩家多半没空手接，所以和主动拾取的顺序是反的）
        boolean stored = owner instanceof Player player
                && BallPouchHelper.deliverReturned(player, stack) != BallPouchHelper.Delivery.NOWHERE;
        if (!stored) {
            this.spawnAtLocation(level, stack, 0.1F);
        }
        this.discard();
    }

    /**
     * 【变形】：停在当前位置，把球本身换成方块。
     *
     * <h2>作者 2026-10-07 重新定的规格</h2>
     * <p>耐久耗尽时<b>随机变成组成该球的原材料之一</b>：</p>
     * <ul>
     *   <li>金球 → 金块（只有一种材质）</li>
     *   <li>「木-金-圆石-铁球」→ 木板 / 金块 / 圆石 / 铁块 <b>四者随机之一</b></li>
     * </ul>
     * <p>该位置放不下时（被方块占了 / 超出世界高度），同样<b>随机</b>退化成
     * 对应方块自己的物品形式 —— 不固定掉某一种。</p>
     *
     * <p>候选从 {@link BallMorph} 现算：普通球一种，组合球按组件的来源列表展开。
     * 这里不读 {@code profile.morphBlock()}，因为那个字段只能存一个方块，
     * 它现在的唯一职责是「表示这颗球有变形特质」。</p>
     */
    private void morphIntoBlock(ServerLevel level, Block block) {
        // 真正要变的那一个：从这颗球的全部材质里随机挑（普通球只有一个候选，等于不变）
        Block chosen = BallMorph.randomBlock(this.getItem(), this.getRandom());
        if (chosen == null) {
            chosen = block;   // 兜底：至少别什么都不做
        }

        BlockPos pos = this.blockPosition();
        BlockState target = chosen.defaultBlockState();

        if (level.getBlockState(pos).canBeReplaced()) {
            // 原地变成方块
            level.setBlockAndUpdate(pos, target);
            level.playSound(null, pos, target.getSoundType().getPlaceSound(),
                    SoundSource.BLOCKS, 0.8F, 1.2F);
        } else {
            // 放不下 → 退化成掉落物（掉的就是随机挑中的那一种）
            this.spawnAtLocation(level, new ItemStack(chosen.asItem()), 0.1F);
        }
        this.discard();
    }

    /**
     * 碎裂：材质破坏音 + 粒子 + 按概率掉落材料 + 移除自身。
     *
     * <p>掉落概率与数量由 {@link BallBehavior.BallDrop} 配置 ——
     * 例如圆石球 60% 掉 1–2 个碎石、「雪球？」10% 掉 1 个碎石。</p>
     */
    private void burst() {
        if (this.level().isClientSide()) {
            return;
        }
        BallBehavior.BallProfile profile = this.profile();

        // 【变形】：耐久耗尽不碎裂，而是停在原地变成方块
        // （随机变成自身材质之一；变不了就掉对应的掉落物 —— 见 morphIntoBlock）
        //
        // ⚠️ 多重射击的**附属弹不变形**（作者指定）：附属弹连掉落物都没有，
        //    会变成方块反而更奇怪。所以这里把它排除掉。
        if (!this.multishotSide
                && profile.morphBlock() != null
                && this.level() instanceof ServerLevel serverLevel) {
            this.morphIntoBlock(serverLevel, profile.morphBlock());
            return;
        }

        // 破碎音按每个球的音效配置取，音量由配置决定
        BallBehavior.BallSound sound = profile.sound();
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                sound.resolveBreakSound(), SoundSource.NEUTRAL, 0.8F * sound.volume(), 1.2F);

        // 掉落：可多层，每层各自独立掷概率（铁球就是四层叠加）。
        // 多重射击的附属弹<b>不掉落</b> —— 作者指定：附属弹碎裂应该无掉落物。
        if (!this.multishotSide && this.level() instanceof ServerLevel serverLevel) {
            // 组合球：不是把各来源的掉落表全跑一遍，而是**先按等权重随机挑一个来源**，
            // 再只跑那个来源的掉落表（作者指定：「各组成的原版掉落物按照相等的权重
            // 随机掉落其中一份」）。所以铁-金球碎了要么按铁球掉铁、要么按金球掉，
            // 不会两边都掉。
            List<BallBehavior.BallDrop> drops = this.comboDropsFor(profile);
            for (BallBehavior.BallDrop drop : drops) {
                if (this.random.nextFloat() >= drop.chance()) {
                    continue;
                }
                int span = Math.max(1, drop.maxCount() - drop.minCount() + 1);
                int count = drop.minCount() + this.random.nextInt(span);
                if (count > 0) {
                    this.spawnAtLocation(serverLevel, new ItemStack(drop.item(), count), 0.1F);
                }
            }
        }

        this.level().broadcastEntityEvent(this, (byte) 3);
        this.discard();
    }

    /**
     * 组合球的掉落表 —— 从它的组成球里<b>等权重随机挑一个</b>，返回那个球的掉落表。
     *
     * <p>普通球直接返回自己的掉落表，行为和以前完全一致。</p>
     */
    private List<BallBehavior.BallDrop> comboDropsFor(BallBehavior.BallProfile profile) {
        if (this.getItem().getItem() != ModItems.COMBO_BALL.get()) {
            return profile.drops();
        }
        List<Integer> indexes = BallFragments.parseIndexes(
                this.getItem().getOrDefault(ModComponents.COMBO_SOURCES.get(), ""));
        if (indexes.isEmpty()) {
            return List.of();
        }
        int pick = indexes.get(this.random.nextInt(indexes.size()));
        Item source = BallFragments.sources().get(pick);
        return BallBehavior.profileFor(new ItemStack(source)).drops();
    }
}
