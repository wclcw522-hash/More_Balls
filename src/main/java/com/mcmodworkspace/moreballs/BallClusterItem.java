package com.mcmodworkspace.moreballs;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * <b>球堆</b> —— 一批同种、同耐久档位的球的「壳」。
 *
 * <h2>它解决什么问题</h2>
 * <p>球的耐久记在 {@link ModComponents#TOUGHNESS_LEFT} 上，而 MC 的堆叠判定要求
 * <b>组件完全一致</b>。副手打出去一组球、回收回来时，每颗挨的碰撞次数都不一样，
 * 于是<b>每颗独占一格</b>，一趟下来背包被吃掉大半。</p>
 *
 * <p>球堆把这些球装进一个壳里：<b>壳只记「哪种球 + 哪一档」</b>（档位甚至是现算的，见
 * {@link BallClusterTier}），内部的球则<b>原样存着</b>，每颗都带自己精确的耐久。
 * 取出时把原来那颗还回去，<b>耐久一分不差</b>。</p>
 *
 * <h2>几条设计上的约定（作者 2026-10-14 指定）</h2>
 * <ul>
 *   <li><b>不进创造栏 / 不进 JEI</b> —— 这只是「减少物品栏占用」的措施，
 *       不是给玩家直接拿的东西。创造栏是标签驱动的（只收 {@code #more_balls:balls}），
 *       所以本物品<b>不挂那个标签</b>就自动进不去，一行代码都不用改</li>
 *   <li><b>满耐久的球不进球堆</b> —— 它们本来就能和同批新球叠成一堆，不需要壳</li>
 *   <li><b>只在「回收」时生成</b>（见 {@code BallProjectile#deliverToOwner}），
 *       不是「捡起就变」</li>
 *   <li><b>右键取球时给耐久最低的那颗</b> —— {@link BallClusterContents} 的球列
 *       始终保持升序，所以这只是取下标 0</li>
 * </ul>
 *
 * <h2>物品栏图标角落的数量</h2>
 * <p>壳是 {@code stacksTo(1)} 的，原版不会给它画堆叠数字。那个数字由
 * {@code BallClusterDecorator}（{@code IItemDecorator}）自己画 ——
 * <b>数字是从球列长度现算的</b>，不存在「忘了同步」的状态。</p>
 */
public class BallClusterItem extends Item {

    public BallClusterItem(Properties properties) {
        super(properties);
    }

    // ===== 内容读写 =====

    /**
     * 取壳里的内容。
     *
     * <p>不带 {@code BALL_CLUSTER} 组件 = 空壳。</p>
     */
    public static BallClusterContents contents(ItemStack stack) {
        BallClusterContents contents = stack.get(ModComponents.BALL_CLUSTER.get());
        return contents == null ? BallClusterContents.EMPTY : contents;
    }

    /**
     * 写回内容。
     *
     * <p>⚠️ <b>空壳不写组件</b>（直接 remove）。这样「空」只有一种表示法，
     * 不会出现「有组件但列表为空」和「没组件」两种状态打架。</p>
     */
    public static void setContents(ItemStack stack, BallClusterContents contents) {
        if (contents.isEmpty()) {
            stack.remove(ModComponents.BALL_CLUSTER.get());
        } else {
            stack.set(ModComponents.BALL_CLUSTER.get(), contents);
        }
    }

    /**
     * 把<b>一颗球</b>套成壳 —— 满耐久的原样返回，用过的才包。
     *
     * <h2>它是回收流程的入口（作者 2026-10-15 明确）</h2>
     * <p>作者要的是「回收时**默认**变成球堆」—— 也就是说套壳要排在袋子前面，
     * 而不是「袋子收不下才套」。所以 {@code deliverToOwner} 拿到球之后先过这一道，
     * 之后不管进袋子还是进背包，玩家拿到的都是「一格装一堆」。</p>
     *
     * <p>满耐久的球原样返回 —— 它们不带耐久组件、彼此组件一致，本来就能叠成一堆。</p>
     *
     * @return 套好壳的物品堆；满耐久或空堆时原样返回
     */
    public static ItemStack clusterize(ItemStack ball) {
        if (ball.isEmpty() || !BallClusterTier.of(ball).isClustered()) {
            return ball;
        }
        ItemStack shell = new ItemStack(ModItems.BALL_CLUSTER.get());
        setContents(shell, BallClusterContents.EMPTY.withAdded(ball.copy()));

        // ===== 自检日志（作者 2026-10-15 加）=====
        //
        // 只打**第一次** —— 目的是让作者不用靠眼睛找就能确认机制到底有没有生效：
        //   · 日志里出现这一行 ⇒ 套壳真的发生了（机制生效）
        //   · 打了一发却完全没有这一行 ⇒ 球根本没走到 clusterize（机制仍然没接上）
        //
        // 之所以要这个，是因为「球堆没生效」这件事上一轮查了太久、
        // 每次都要靠肉眼在背包里翻。一行日志能省掉整轮排查。
        if (!FIRST_CLUSTER_LOGGED) {
            FIRST_CLUSTER_LOGGED = true;
            Integer left = ball.get(ModComponents.TOUGHNESS_LEFT.get());
            int max = Math.max(0, BallBehavior.profileFor(ball).toughness());
            MoreBalls.LOGGER.info("[球堆] 首次生成球堆：{} 套成壳（剩余耐久 {}/{}，档位 {}）",
                    ball.getHoverName().getString(),
                    left == null ? max : left, max,
                    BallClusterTier.of(ball).name());
        }
        return shell;
    }

    /** 自检日志只打一次 */
    private static volatile boolean FIRST_CLUSTER_LOGGED = false;

    /** 这个物品堆是不是球堆 */
    public static boolean isCluster(ItemStack stack) {
        return stack.getItem() instanceof BallClusterItem;
    }

    // ===== 显示名 =====

    /**
     * 显示名 = 「瑕疵的钻石球」这种。
     *
     * <p>档位前缀 + 内部球的显示名。内部球没了就回落成物品自己的名字
     * （理论上空壳不该存在，见 {@link #setContents}）。</p>
     */
    @Override
    public Component getName(ItemStack stack) {
        BallClusterContents contents = contents(stack);
        ItemStack inner = contents.lowest();
        if (inner.isEmpty()) {
            return super.getName(stack);
        }
        return Component.translatable("item.more_balls.ball_cluster.named",
                Component.translatable(tierKey(contents.tier())),
                inner.getHoverName());
    }

    private static String tierKey(BallClusterTier tier) {
        return "cluster.more_balls.tier." + tier.name().toLowerCase(Locale.ROOT);
    }

    // ===== 入包：回收时把用过的球套壳 =====

    /**
     * 把「球堆」摊开成里面那颗球 —— <b>取耐久最低的那颗</b>，
     * 与 {@link #use} 里右键取球的口径完全一致。
     *
     * <h2>它是干什么用的</h2>
     * <p>弩装填、收纳袋、铁砧这些流程都只认「一颗球」。让它们全都去判断壳的存在，
     * 会把逻辑摊到七八个文件里；所以统一在这里摊开 ——
     * 调用方只要在「真正要拿一颗球用」的那一处调本方法即可。</p>
     *
     * <p><b>不是球堆就原样返回</b>，所以调用点写起来是透明的：
     * {@code ItemStack ball = BallClusterItem.asBall(stack);}</p>
     *
     * <p>⚠️ 返回的是<b>副本</b>，改它不会动到壳里的原件。真要「拿走」用 {@link #takeOne}。</p>
     */
    public static ItemStack asBall(ItemStack stack) {
        if (!isCluster(stack)) {
            return stack;
        }
        ItemStack inner = contents(stack).lowest();
        return inner.isEmpty() ? ItemStack.EMPTY : inner.copy();
    }

    /**
     * 从壳里<b>就地取走一颗</b>（改组件），并返回取出的那一颗。
     *
     * <p>取的是耐久最低的（{@link BallClusterContents} 始终升序，所以是下标 0）。</p>
     *
     * <p>⚠️ 取走最后一颗时，壳会变成<b>空壳</b>（组件被摘掉）—— 本方法<b>不</b>负责把它
     * 变成球或清掉那一格，因为调用方（弩装填 / 袋子）各自对「这一格接下来是什么」
     * 有不同处理。调用方拿到非空返回值后应当自行决定：</p>
     * <ul>
     *   <li>若 {@code shell.isEmpty() == false} 但仍需清格 → 自行 {@code setCount(0)}</li>
     *   <li>或者干脆保留空壳（玩家可以用 {@link #store} 再往里收球）</li>
     * </ul>
     *
     * @return 取出的那颗球；壳是空的或不是球堆时返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack takeOne(ItemStack shell) {
        if (!isCluster(shell)) {
            return ItemStack.EMPTY;
        }
        BallClusterContents current = contents(shell);
        if (current.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = current.lowest().copy();
        BallClusterContents rest = current.withoutLowest();

        // ===== ⑤ 「仅剩 1 颗就自动脱离」（作者 2026-10-15）=====
        //
        // ⚠️ 这里**不能**把壳那一格原地改成球 —— `ItemStack` 的 item 本身是 final 的，
        //    换不了。所以「脱离」这件事拆成两步：
        //      · 本方法：把壳**清空**（摘掉球堆组件 + 数量归零），那一格变成空的
        //      · 调用方：拿到 `taken`（取走的那颗）之后，再把壳里**剩下的那颗**
        //        取回去 —— 见 `declusterInto`
        //    最终玩家看到的是「取出一颗 + 那一格变成另一颗球」，**不会留空球堆**。
        //
        //    （原先我写的是 `shell.copyFrom(last)`，在 26.2 里对 copyFrom 的引用是**歧义的**，
        //      而且就算消掉歧义也换不掉 item —— 这条编译错误就是这么来的。）
        setContents(shell, rest);   // 空了会自己摘掉组件
        return taken;
    }

    /**
     * <b>「只剩一颗就脱离」的收尾</b>（作者 2026-10-15）。
     *
     * <h2>它做什么</h2>
     * <p>取完一颗之后，如果壳里<b>只剩 1 颗</b>，就把那一颗取出来返回、并把壳清空 ——
     * 让调用方把那一格换成「那颗球」。</p>
     *
     * <h2>为什么必须由调用方来换</h2>
     * <p>{@code ItemStack} 的 item 是 final 的，<b>不能就地从一个物品变成另一个物品</b>
     * （这也是 {@code shell.copyFrom(last)} 那个写法编译不过的根本原因：26.2 里
     * {@code copyFrom} 有两个重载、单参调用本身就歧义，而且就算消掉歧义也换不掉 item）。</p>
     *
     * <p>所以「壳变回球」只能由持有那一格的人做 —— {@code setItemInHand} /
     * {@code slot.setByPlayer} / {@code segment.set(i, …)}。本方法只负责把内容掏干净并交出来。</p>
     *
     * @return 壳里仅剩的那颗球；壳里不是「恰好 1 颗」时返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack declusterInto(ItemStack shell) {
        if (!isCluster(shell)) {
            return ItemStack.EMPTY;
        }
        BallClusterContents current = contents(shell);
        if (current.size() != 1) {
            return ItemStack.EMPTY;
        }
        ItemStack last = current.balls().get(0).copy();
        setContents(shell, BallClusterContents.EMPTY);   // 摘掉球堆组件
        shell.setCount(0);                                // 那一格变空
        return last;
    }

    /**
     * 取一颗的完整结果 —— 见 {@link #takeOneWithDecluster}。
     *
     * @param taken    取走的那颗球（要还给玩家 / 扔出去 / 装进弩）
     * @param leftover 非空时表示「壳已经空了，这一格该换成它」；空表示壳还有球，照旧
     */
    public record TakeResult(ItemStack taken, ItemStack leftover) {
        public boolean hasLeftover() {
            return !this.leftover.isEmpty();
        }
    }

    /**
     * 取一颗，并在「壳里只剩一颗」时把那一颗也交出来 —— 调用方一次拿全，不用自己判两次。
     *
     * <p>返回的 {@code leftover} 非空时，调用方要把<b>那一格</b>换成它
     * （{@code setItemInHand(hand, leftover)} 之类），这样就不会留下空球堆。</p>
     */
    public static TakeResult takeOneWithDecluster(ItemStack shell) {
        ItemStack taken = takeOne(shell);
        return new TakeResult(taken, declusterInto(shell));
    }

    /**
     * 把一颗球「收进玩家的背包」——
     * <b>用过的球套壳，满耐久的原样放</b>。
     *
     * <h2>为什么不直接 {@code inventory.add(ball)}</h2>
     * <p>这正是本机制要解决的问题：用过的球耐久各不相同，而 MC 的堆叠判定要求组件完全一致，
     * 于是每一颗都独占一格。作者反馈的就是「打出去一组、收回来五六组耐久不同的球」。</p>
     *
     * <h2>为什么不能「每次新建一个壳」</h2>
     * <p>那样背包里会攒出一堆<b>各装 1 颗</b>的壳，等于没省格子。
     * 所以顺序是：<b>先找背包里已有的同类壳</b>（同球种 + 同档位 + 还有空位）塞进去，
     * <b>找不到才新建</b>。</p>
     *
     * <h2>满耐久的球为什么不套壳</h2>
     * <p>作者指定。它们本来就能和同批新球叠成一堆，套壳反而是画蛇添足。</p>
     *
     * <p>放不下时一律掉在脚下（与原版 {@code Inventory#add} 失败时的处理一致）。</p>
     */
    public static void store(Player player, ItemStack ball) {
        if (ball.isEmpty()) {
            return;
        }

        // 满耐久（含没有耐久概念的球）：不套壳，原样进背包
        if (!BallClusterTier.of(ball).isClustered()) {
            if (!player.getInventory().add(ball)) {
                player.drop(ball, false);
            }
            return;
        }

        // ① 先找已有的同类壳
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (!isCluster(slot)) {
                continue;
            }
            BallClusterContents existing = contents(slot);
            if (existing.hasRoom() && existing.matches(ball)) {
                setContents(slot, existing.withAdded(ball));
                return;
            }
        }

        // ② 没有就新建一个壳
        ItemStack shell = new ItemStack(ModItems.BALL_CLUSTER.get());
        setContents(shell, BallClusterContents.EMPTY.withAdded(ball));
        if (!inventory.add(shell)) {
            player.drop(shell, false);
        }
    }

    // ===== tooltip =====

    /**
     * 作者指定的两条信息规则：
     *
     * <ol>
     *   <li><b>常规信息栏</b>（不是 Shift 展开）显示<b>当前耐久最低的那一颗的耐久值</b></li>
     *   <li><b>按住 Alt</b>：隐藏数值与特性，改显示<b>本堆叠耐久最少的 6 颗</b>的剩余耐久</li>
     * </ol>
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        BallClusterContents contents = contents(stack);
        if (contents.isEmpty()) {
            return;
        }

        int count = contents.size();
        ItemStack lowest = contents.lowest();

        if (isAltDown()) {
            // Alt：只列最少的 6 颗的剩余耐久，其余全部藏起来
            tooltip.accept(Component.translatable("tooltip.more_balls.cluster.alt_header", count)
                    .withStyle(ChatFormatting.GOLD));
            List<ItemStack> few = contents.lowestN(6);
            for (ItemStack ball : few) {
                tooltip.accept(Component.literal("  ")
                        .append(ball.getHoverName())
                        .append(Component.literal("  "))
                        .append(Component.translatable("tooltip.more_balls.cluster.left", left(ball))
                                .withStyle(ChatFormatting.GRAY)));
            }
            if (count > few.size()) {
                tooltip.accept(Component.translatable("tooltip.more_balls.cluster.alt_more",
                        count - few.size()).withStyle(ChatFormatting.DARK_GRAY));
            }
            return;
        }

        // 常规栏：数量 + **球的详细信息**（作者 2026-10-15 明确）
        //
        // ⚠️ 作者原话：「**不需要**在悬停界面显示名字（名字里本身就包含这是啥球），
        //    而是显示对应球的详细信息，比如【坚固x】【基础伤害x】等这些，
        //    **格式和球本身的一样**。（包括悬停页和 shift 展开页）」
        //
        // 所以这里**不再自己拼格式**，直接复用球自己的 tooltip 生成器
        // {@link BallTooltip#entries} —— 和 {@code BallItem.appendHoverText} 逐行同构：
        //   · 不按 Shift：只给词条本身（【基础伤害11】【坚固3】…）
        //   · 按住 Shift：每条后面追加它的说明（entry.detail()，缩进一格）
        // 两处读同一份数据，**将来球改了词条，球堆自动跟着变**，不会说法不一致。
        tooltip.accept(Component.translatable("tooltip.more_balls.cluster.count", count)
                .withStyle(ChatFormatting.GOLD));
        if (!lowest.isEmpty()) {
            boolean shift = isShiftDown();

            // ★★ 完全照搬球本身（作者 2026-10-15 明确要求）：
            //
            //   「原来的球直接显示那些信息，现在就显示那些信息；
            //     原来要 shift 展开那些，在球堆上就要 shift 展开哪些，
            //     **而不是现在只有【蓄力x】一条**，并且有时候蓄力也是在 shift 展开信息里的。」
            //
            // 所以这里就是 `BallItem.appendHoverText` 那一段的逐字复刻 ——
            // **不按 kind 过滤、不加任何特例**。
            // （我 1.1.0.25 曾把 `Kind.CHARGE` 改成恒显，那是多余的，已撤掉。）
            for (BallTooltip.Entry entry : BallTooltip.entries(lowest, shift)) {
                tooltip.accept(entry.text());
                if (shift && entry.detail() != null) {
                    tooltip.accept(Component.literal("  ").append(entry.detail()));
                }
            }
            // 「这一档有几颗」（作者 2026-10-15）—— 「最低耐久的那一组」的大小，
            // 和 Shift+右键 / 右键取一组 是同一个口径
            tooltip.accept(Component.translatable("tooltip.more_balls.cluster.lowest",
                            left(lowest), contents.lowestGroupSize())
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltip.accept(Component.translatable("tooltip.more_balls.cluster.hint_alt")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * 是否按住 Shift（和 {@code BallItem} 里那个同样的写法）。
     *
     * <p>26.x 的 {@code Screen} 已经没有 {@code hasShiftDown()}，改问 {@link Minecraft} 本身；
     * 只有客户端有这个类，所以用 try/catch 兜住 —— 取不到就当没按。</p>
     */
    private static boolean isShiftDown() {
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            return minecraft != null && minecraft.hasShiftDown();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 一颗球还剩几次。
     *
     * <p>口径与 {@link BallClusterContents} 的排序一致：读 {@code TOUGHNESS_LEFT}，
     * <b>没带组件 = 满耐久</b>，那就返回上限。</p>
     */
    private static int left(ItemStack ball) {
        Integer value = ball.get(ModComponents.TOUGHNESS_LEFT.get());
        if (value != null) {
            return value;
        }
        return Math.max(0, BallBehavior.profileFor(ball).toughness());
    }

    /**
     * Alt 键是否按下。
     *
     * <p>{@code Screen} 是客户端类，所以整个判定包在 try/catch 里 ——
     * 与 {@code BallItem#isClientCreative} 同一套做法，专用服务端上不会因此崩。</p>
     */
    private static boolean isAltDown() {
        try {
            com.mojang.blaze3d.platform.Window window = net.minecraft.client.Minecraft.getInstance().getWindow();
            // ⚠️ InputConstants.isKeyDown 的第一个参数**直接就是 Window 对象**，不是句柄 ——
            //    别画蛇添足再调一层（Window 上没有 getWindow()/getWindowHandle()，扒过 javap）。
            return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT)
                    || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ===== 右键取球 =====

    /**
     * 右键：取出<b>耐久最低</b>的那一颗。
     *
     * <p>作者指定「耐久从低到高依次取出」—— 因为球列始终升序，这里就是不断取下标 0。</p>
     *
     * <h2>两套动作（作者 2026-10-15 指定）</h2>
     * <ul>
     *   <li><b>右键</b> —— 扔出<b>一颗</b>耐久最低的球</li>
     *   <li><b>Shift + 右键</b> —— 把<b>一组</b>耐久最低的球<b>放进空着的物品栏</b>
     *       （是放进背包，<b>不是</b>扔出去 —— 我 1.1.0.13 曾把这条也做成投掷，理解错了）</li>
     * </ul>
     *
     * <p>「一组」的口径见 {@link BallClusterContents#lowestGroupSize()} ——
     * 耐久值相同的那一批。</p>
     *
     * <p><b>不留空球堆</b>：取完若壳里只剩 1 颗，那一颗会直接顶上这一格
     * （见 {@link #takeOneWithDecluster}）。</p>
     */
    /**
     * <b>蓄力条的长度</b>（作者 2026-10-15 反馈「蓄力等级不显示」）。
     *
     * <h2>为什么之前没有条</h2>
     * <p>原版 HUD 画蓄力条时读的是<b>玩家正在使用的那件物品</b> —— 而手持球堆蓄力时，
     * 那件物品是<b>壳</b>，不是球。壳原先没有这两个覆写，原版拿到 duration 0 / 动画 NONE，
     * 于是**什么都不画**（蓄力其实是生效的，只是看不见）。</p>
     *
     * <p>长度取 {@link BallCharge#USE_DURATION}，与 {@code BallThrowHandler.onStartUsing}
     * 给球设的完全一致 —— 两边必须是同一个数，否则条画满了但等级还没到。</p>
     *
     * <p>只有「壳里那颗球本身可蓄力」时才给长度；不坚固的球（chargeLevels == 0）返回 0，
     * 与它们直接出手的行为一致。</p>
     */
    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        ItemStack probe = asBall(stack);
        if (probe.isEmpty() || BallBehavior.profileFor(probe).chargeLevels() <= 0) {
            return 0;
        }
        return BallCharge.USE_DURATION;
    }

    /**
     * <b>蓄力条的样式</b> —— 用弓那种（拉满的弧线），一眼能看出「在蓄力」。
     *
     * <p>不覆写的话原版用 {@code ItemUseAnimation.NONE}，同样画不出条。</p>
     */
    @Override
    public net.minecraft.world.item.ItemUseAnimation getUseAnimation(ItemStack stack) {
        ItemStack probe = asBall(stack);
        if (probe.isEmpty() || BallBehavior.profileFor(probe).chargeLevels() <= 0) {
            return net.minecraft.world.item.ItemUseAnimation.NONE;
        }
        return net.minecraft.world.item.ItemUseAnimation.BOW;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack shell = player.getItemInHand(hand);
        BallClusterContents contents = contents(shell);
        if (contents.isEmpty() || player.isSpectator()) {
            return InteractionResult.PASS;
        }

        // ===== 可蓄力的球：进入原版「使用中」状态，松开右键才出手 =====
        //
        // ⚠️ 这一段必须排在 `level.isClientSide()` 检查**之前**，而且**两端都要走** ——
        //    蓄力条的长度与原版的 usedTicks 都来自「使用中」状态，客户端不进这个状态
        //    就看不到蓄力条。（原版的弓 / 雪球也是两端都 startUsingItem。）
        //
        //    进入之后由 BallThrowHandler 接管：
        //      · onStartUsing  认壳 → 设蓄力条长度
        //      · onStopUsing   认壳 → 从壳取一颗 → 按 BallCharge.levelAt 算等级出手
        //    蓄力曲线、袖珍活塞那两个接点因此走的还是同一条路，不会分叉。
        //
        //    作者 2026-10-15：「从球堆扔球也要继承球本身的那些信息……包括蓄力」——
        //    能不能蓄力由**壳里那颗球**的 profile 决定，不是壳决定的。
        //    ⚠️ **必须排除 Shift** —— Shift+右键 是「放进物品栏」，不能进蓄力。
        //       我 1.1.0.18 加这一段时忘了这一条，于是 Shift 也走 startUsingItem，
        //       松开时被 onStopUsing 当成普通投掷扔出去 —— 作者实测反馈「shift右键还是扔出」。
        ItemStack probe = asBall(shell);
        if (!player.isShiftKeyDown()
                && !probe.isEmpty()
                && BallBehavior.profileFor(probe).chargeLevels() > 0) {
            player.startUsingItem(hand);
            return InteractionResult.CONSUME;
        }

        // ===== 以下都是「立即出手」，只在服务端做 =====
        //
        // ⚠️ use 两端都会调，客户端也动手就是做两份。
        //    客户端只返回 CONSUME，告诉原版这次右键用掉了。
        if (level.isClientSide()) {
            return InteractionResult.CONSUME;
        }

        ItemStack copy = shell.copy();

        if (player.isShiftKeyDown()) {
            // ===== Shift+右键：**释放一组到空着的物品栏**（作者 2026-10-15 明确）=====
            //
            // ⚠️ 我 1.1.0.13 把这一条也做成了「扔出去」—— 理解错了。
            //    作者的原话是「释放一组到空着的物品栏」，也就是**放进背包空格**，不是投掷。
            //
            //    放进空格用 Inventory.add：它会先找可叠的同类，找不到才占空格；
            //    实在没地方就掉在脚下（不能凭空消失）。
            int group = contents.lowestGroupSize();
            ItemStack leftover = ItemStack.EMPTY;
            for (int i = 0; i < group; i++) {
                TakeResult r = takeOneWithDecluster(copy);
                if (r.taken().isEmpty()) {
                    break;
                }
                if (!player.getInventory().add(r.taken())) {
                    player.drop(r.taken(), false);
                }
                if (r.hasLeftover()) {
                    // 壳里只剩一颗 → 那一颗直接顶上这一格，**不留空球堆**
                    leftover = r.leftover();
                    break;
                }
            }
            player.setItemInHand(hand, leftover.isEmpty() ? copy : leftover);
            return InteractionResult.CONSUME;
        }

        // ===== 普通右键：**扔出一颗**（作者最初的要求，保持）=====
        TakeResult r = takeOneWithDecluster(copy);
        if (r.taken().isEmpty()) {
            return InteractionResult.PASS;
        }
        com.mcmodworkspace.moreballs.BallThrowHandler.throwBall(player, r.taken(), 0);
        // 扔完壳只剩一颗 → 那一颗顶上这一格（不留空球堆）
        player.setItemInHand(hand, r.hasLeftover() ? r.leftover() : copy);
        return InteractionResult.CONSUME;
    }
}
