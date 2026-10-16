package com.mcmodworkspace.moreballs.mixin;

import com.mcmodworkspace.moreballs.BallClusterContents;
import com.mcmodworkspace.moreballs.BallClusterItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * <b>背包里右键球堆 = 取出「最低耐久那一组」，而不是拿起整个球堆</b>（作者 2026-10-15 要求）。
 *
 * <h2>为什么要 Mixin</h2>
 * <p>NeoForge <b>没有</b>「容器被点击」的事件 —— 查过 {@code event/} 目录，与容器有关的只有
 * {@code PlayerContainerEvent.Open/Close}（开关界面）。而物品栏里的左键/右键行为全部由
 * 原版 {@link AbstractContainerMenu#clicked} 直接处理，没有留钩子。</p>
 *
 * <p>项目里本来就有 Mixin 框架（{@code more_balls.mixins.json} + CrossbowItemMixin /
 * LocalPlayerMixin），所以这里沿用同一套，而不是去魔改 UI 层。</p>
 *
 * <h2>接管的时机（四条限制，缺一不可）</h2>
 * <ol>
 *   <li>{@code input == PICKUP} —— 普通的左/右键拿起动作</li>
 *   <li>{@code button == 1} —— 右键</li>
 *   <li>光标是空的 —— 光标上有东西时不碰，免得把玩家手上的东西吞了</li>
 *   <li>那个槽里是<b>球堆</b>且不是空的</li>
 * </ol>
 * <p>四条全满足才接管；其余情况一律放行，绝不干扰原版的左键拿起、Shift 快速移动、
 * 数字键交换、丢弃等等。</p>
 *
 * <h2>「一组」是什么</h2>
 * <p>和 {@code Shift+右键扔出一组}、tooltip 里那行「共 N 颗」是<b>同一个口径</b> ——
 * 见 {@link BallClusterContents#lowestGroupSize()}：<b>耐久值相同的那一批</b>。</p>
 *
 * <h2>为什么可以一次拿一组</h2>
 * <p>同一组里的球<b>耐久完全相同</b>，也就是数据组件完全相同 —— 它们本来就能叠成一堆。
 * 所以光标上放的是「一个数量 N 的球」而不是 N 个格子，这正是玩家要的效果。</p>
 *
 * <h2>为什么改的是那个槽而不是新建栈</h2>
 * <p>壳是 {@code stacksTo(1)} 的容器，装进多少个球它都只占一格。
 * 取出之后要把<b>剩余</b>写回<b>同一个槽</b>（用 {@code slot.setByPlayer}，
 * 它会顺手触发容器同步）；取空了就写 {@code ItemStack.EMPTY}，那一格自然消失。</p>
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void moreballs$specialClicks(int slotId, int button, ContainerInput input,
                                         Player player, CallbackInfo ci) {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;

        // ===== ① 切石机：材料格是球堆 / 组合球时，点产物槽自己接管 =====
        // 见 moreballs$cutBalls 的注释（作者 2026-10-16 的方案）
        if (moreballs$cutBalls(self, slotId, button, input, player)) {
            ci.cancel();
            return;
        }

        // ===== ② 背包里右键球堆 = 取出最低耐久那一组 =====
        // 只认「右键拿起」
        if (input != ContainerInput.PICKUP || button != 1) {
            return;
        }

        // ③ 光标必须空着
        if (!self.getCarried().isEmpty()) {
            return;
        }
        // 槽位合法性（原版允许 slotId 为 -1，表示点在了界面外面）
        if (slotId < 0 || slotId >= self.slots.size()) {
            return;
        }

        // ④ 目标是球堆
        Slot slot = self.slots.get(slotId);
        ItemStack shell = slot.getItem();
        if (shell.isEmpty() || !BallClusterItem.isCluster(shell)) {
            return;
        }
        BallClusterContents contents = BallClusterItem.contents(shell);
        if (contents.isEmpty()) {
            return;
        }
        if (!slot.mayPickup(player) || !slot.allowModification(player)) {
            return;   // 原版或别的模组把这个槽锁了 —— 尊重它
        }

        // ===== 取「最低耐久那一组」=====
        int groupSize = contents.lowestGroupSize();
        List<ItemStack> balls = contents.balls();
        if (groupSize <= 0 || balls.isEmpty()) {
            return;
        }
        // 同组球组件完全一致 ⇒ 一个数量 groupSize 的栈就代表它们全部
        ItemStack taken = balls.get(0).copyWithCount(groupSize);

        // 剩余写回同一个槽（壳只占一格，掏空了那一格就消失）
        BallClusterContents rest = contents.withoutFirst(groupSize);
        if (rest.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            ItemStack shellAfter = shell.copy();
            BallClusterItem.setContents(shellAfter, rest);
            slot.setByPlayer(shellAfter);
        }

        self.setCarried(taken);   // 放到光标上
        self.broadcastChanges();
        slot.onTake(player, taken);
        ci.cancel();              // 挡掉原版的「拿起整个球堆」
    }

    /**
     * <b>切石机里的球堆：整堆留在材料格，每取一次产物只消耗里面的一颗球</b>
     * （作者 2026-10-16 提出的方案）。
     *
     * <h2>它解决的问题</h2>
     * <p>原版切石机一次消耗「1 个物品」（{@code resultSlot.onTake} 里那句
     * {@code inputSlot.remove(1)}），而球堆 {@code count} 恒为 1 —— 不处理的话
     * 点一下就整个球堆没了。中间试过「摊成一叠 N 颗」，但那会把耐久统一成最低那颗
     * （作者反馈「损耗严重」），已废弃。</p>
     *
     * <h2>现在这一版（作者的原话 + 后续两处修正）</h2>
     * <p>「球堆可以整个放进去，然后，再点击产物拿起的瞬间……球堆回到背包 →
     * 填充一颗球到材料格 → 选中刚刚选的配方 → 完成一次切球 → 把球堆放回材料格 →
     * 选中刚刚的配方」—— 也就是在<b>取产物的那一瞬间</b>做一次原子操作。</p>
     *
     * <p>⚠️ <b>只服务端执行</b>：{@code AbstractContainerMenu#clicked} 在客户端（本地预测）
     * 与服务端都会跑，两端都做这套操作的话<b>每点一次会消耗两颗</b> ——
     * 作者实测报的「球堆放进去点了两次产物就给球堆切没了」就是它。</p>
     *
     * <p>⚠️ <b>光标上有东西时照样能切</b>（作者的第二个修正）：那时产物直接进背包，
     * 不再要求光标必须空 —— 手里拿着别的东西时，材料/产物格本来就点不动产物，
     * 挡掉反而切不了。</p>
     *
     * <h2>两种点击</h2>
     * <ul>
     *   <li><b>左键</b>（光标空）—— 切一颗，产物拿在光标上（与「拿起产物」一致）；
     *       光标上有东西时产物进背包</li>
     *   <li><b>Shift + 左键</b> —— <b>一次做完</b>：能切几颗切几颗，产物全进背包
     *       （背包塞不下就停手，剩下留在材料格里）</li>
     * </ul>
     *
     * <h2>为什么能拦在这里</h2>
     * <p>{@code AbstractContainerMenu#clicked} 是「玩家点击槽位」的唯一入口，
     * 而本类早就注入了它的 HEAD（背包右键取一组那个功能）。这里只是多加一个分支，
     * 用的是原版公开的 {@link StonecutterMenu} 槽位与 {@code clickMenuButton}
     * —— 不碰原版代码，也不需要新开 Mixin。</p>
     *
     * @return true 表示这次点击已被接管（调用方要 {@code ci.cancel()}）
     */
    private static boolean moreballs$cutBalls(AbstractContainerMenu self, int slotId, int button,
                                              ContainerInput input, Player player) {
        // ★ 只在服务端做（客户端那次只是本地预测，两端都做 = 双倍消耗）
        if (player.level().isClientSide()) {
            return false;
        }
        if (!(self instanceof StonecutterMenu menu) || slotId != StonecutterMenu.RESULT_SLOT) {
            return false;
        }

        boolean quickMove = input == ContainerInput.QUICK_MOVE;              // Shift + 左键
        boolean pickUp = input == ContainerInput.PICKUP && button == 0;      // 普通左键
        if (!quickMove && !pickUp) {
            return false;   // 右键 / 中键 / 丢弃一律放行
        }

        Slot inputSlot = menu.getSlot(StonecutterMenu.INPUT_SLOT);
        Slot resultSlot = self.getSlot(StonecutterMenu.RESULT_SLOT);
        ItemStack material = inputSlot.getItem();

        boolean cluster = BallClusterItem.isCluster(material);
        boolean combo = material.is(com.mcmodworkspace.moreballs.ModItems.COMBO_BALL.get());
        if (!cluster && !combo) {
            return false;   // 普通球 → 走原版（本来就是一次切一颗）
        }
        if (cluster && BallClusterItem.contents(material).isEmpty()) {
            return false;   // 空壳
        }

        int selected = menu.getSelectedRecipeIndex();
        if (selected < 0 || resultSlot.getItem().isEmpty()) {
            return false;   // 还没选配方 / 配方算不出东西
        }

        // ===== 耐久门槛：组合球与球堆同样要过 80%（作者 2026-10-16）=====
        // 球的耐久记在球自己身上（球堆的耐久记在**壳里那颗球**上），所以统一摊开来看。
        // 球堆里的球都是**同一档位**的（BallClusterContents.matches 要求同档位），
        // 所以「看最低那颗」就等于看整堆 —— 与 BallCuttingRecipe.matches 同一口径。
        //
        // ⚠️ 不达标时必须**吞掉这次点击**（什么都不做），绝不能 return false 漏给原版 ——
        //    原版对产物槽的这次点击会把整个球堆消耗掉（作者实测踩过这个）。
        ItemStack peek = cluster ? BallClusterItem.asBall(inputSlot.getItem()) : material;
        if (peek.isEmpty() || !com.mcmodworkspace.moreballs.BallItem.canBeCut(peek)) {
            return true;   // 门槛不达标 → 点不动
        }

        // ===== 这个点击该不该「点不动」=====
        // 作者 2026-10-16 明确：**要像原版那样** ——
        // 光标上拿着**同种产物**时再点，产物就**叠加上去**；
        // 光标与产物不一致（或叠了会超上限）时，按原版的「点不动」处理（什么也不发生）。
        //
        // ⚠️ 「点不动」必须是**我们接管之后什么都不做**，绝不能 return false 漏给原版 ——
        //    原版对产物槽的这次点击会把整个球堆消耗掉，那就是作者实测踩到的那个 bug。
        ItemStack preview = resultSlot.getItem();
        if (pickUp && !self.getCarried().isEmpty()) {
            ItemStack cur = self.getCarried();
            if (!ItemStack.isSameItemSameComponents(cur, preview)
                    || cur.getCount() + preview.getCount() > cur.getMaxStackSize()) {
                return true;   // 吞掉这次点击，什么都不做
            }
        }

        int rounds = quickMove ? com.mcmodworkspace.moreballs.BallClusterContents.MAX_BALLS : 1;

        int made = 0;
        for (int n = 0; n < rounds; n++) {
            // ===== 这一轮消耗的目标 =====
            //  · 材料是球堆 → 取走壳里耐久最低那一颗，剩余的壳暂存在局部变量里
            //    （不落地也不进背包 —— 那样会凭空多出第二份）
            //  · 材料是组合球本体 → 就是它自己，切完材料格清空
            ItemStack one;
            ItemStack rest;
            if (cluster) {
                ItemStack shell = inputSlot.getItem();
                if (!BallClusterItem.isCluster(shell)
                        || BallClusterItem.contents(shell).isEmpty()) {
                    break;
                }
                one = BallClusterItem.takeOne(shell);
                rest = shell.copy();
            } else {
                one = material;
                rest = ItemStack.EMPTY;
            }
            if (one.isEmpty()) {
                break;
            }
            boolean isCombo = one.is(com.mcmodworkspace.moreballs.ModItems.COMBO_BALL.get());
            boolean hasRest = cluster && !BallClusterItem.contents(rest).isEmpty();

            // ===== 那颗球进材料格 → 重新选中配方 → 算产物 =====
            // （材料换了物品，原版 slotsChanged 会把配方列表重置，所以必须重选）
            inputSlot.set(one);
            menu.clickMenuButton(player, selected);
            ItemStack product = resultSlot.getItem().copy();
            if (product.isEmpty()) {
                // 算不出产物（不该发生）→ 把球堆放回去收手，绝不吞球
                inputSlot.set(hasRest ? rest : ItemStack.EMPTY);
                break;
            }

            // ===== 产物交出去 =====
            // 左键：光标空就拿在手上；光标上已是同种产物就**叠加上去**
            // （能不能叠上面已经判过，这里直接加）
            // Shift 左键：直接进背包；背包满了这一份掉脚下然后收手
            if (pickUp) {
                ItemStack cur = self.getCarried();
                if (cur.isEmpty()) {
                    self.setCarried(product);
                } else {
                    cur.grow(product.getCount());
                    self.setCarried(cur);
                }
            } else if (!player.getInventory().add(product)) {
                // 背包满了：这一份掉脚下，然后收手（剩下的留在材料格里）
                player.drop(product, false);
                inputSlot.set(hasRest ? rest : ItemStack.EMPTY);
                made++;
                break;
            }

            // ===== 组合球：把「来源与产物槽不同的那几份」补进背包 =====
            // 产物槽里只放得下「与第一份同来源的那几份」（同来源可以叠），
            // 其余份在这里发出去；背包塞不下就直接扔地上（作者指定）。
            if (isCombo) {
                moreballs$giveRestPieces(player, one, product);
            }

            // ===== 材料复位 =====
            if (cluster) {
                inputSlot.set(hasRest ? rest : ItemStack.EMPTY);
                if (hasRest) {
                    menu.clickMenuButton(player, selected);
                }
            } else {
                inputSlot.set(ItemStack.EMPTY);
            }
            made++;
            if (!cluster) {
                break;   // 组合球本体只切一次
            }
            if (!hasRest) {
                break;   // 最后一颗切完 → 收工
            }
        }

        if (made == 0) {
            return false;
        }
        self.broadcastChanges();
        return true;
    }

    /**
     * <b>组合球切开的「其余份」</b>（作者 2026-10-16 定稿的规则）。
     *
     * <p>产物槽里那份代表「与第一份同来源的那几份」（同来源可以叠成一栈，
     * 数量由 {@code BallCuttingRecipe.assemble} 算好），所以这里只发
     * <b>来源与它不同的份</b>，直接进背包；<b>背包也放不下就扔在地上</b> ——
     * 作者明确要求不让它凭空消失。</p>
     *
     * <p>份数随模板走：四分之一 4 份、半球 2 份（见
     * {@link com.mcmodworkspace.moreballs.BallCuttingRecipe#comboPieceSource}）。</p>
     */
    private static void moreballs$giveRestPieces(Player player, ItemStack combo, ItemStack product) {
        Integer keptSource = product.get(
                com.mcmodworkspace.moreballs.ModComponents.FRAGMENT_SOURCE.get());
        boolean quarter = product.is(com.mcmodworkspace.moreballs.ModItems.BALL_QUARTER.get());
        int total = quarter ? 4 : 2;
        for (int i = 0; i < total; i++) {
            int source = com.mcmodworkspace.moreballs.BallCuttingRecipe
                    .comboPieceSource(combo, i, quarter);
            if (source < 0) {
                continue;   // 这个象限本来就是空的
            }
            if (keptSource != null && source == keptSource) {
                continue;   // 产物槽里那几份已经代表它了
            }
            ItemStack piece = new ItemStack(quarter
                    ? com.mcmodworkspace.moreballs.ModItems.BALL_QUARTER.get()
                    : com.mcmodworkspace.moreballs.ModItems.BALL_HALF.get());
            piece.set(com.mcmodworkspace.moreballs.ModComponents.FRAGMENT_SOURCE.get(), source);
            if (!player.getInventory().add(piece)) {
                player.drop(piece, false);
            }
        }
    }
}
