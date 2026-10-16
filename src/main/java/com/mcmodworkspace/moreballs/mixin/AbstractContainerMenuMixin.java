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

        // ===== ① 切石机：材料格是球堆时，点产物槽「只消耗里面的一颗」=====
        // 见 moreballs$cutOneFromCluster 的注释（作者 2026-10-16 的方案）
        if (moreballs$cutOneFromCluster(self, slotId, input, player)) {
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
     * 点一下就整个球堆没了。上一版的做法是把球堆摊成「一叠 N 颗同种球」，
     * 但那样会**把耐久统一成最低那颗**，损耗明显（作者反馈「损耗严重」）。</p>
     *
     * <h2>现在这一版（作者的原话）</h2>
     * <p>「球堆可以整个放进去，然后，再点击产物拿起的瞬间……球堆回到背包 →
     * 填充一颗球到材料格 → 选中刚刚选的配方 → 完成一次切球 → 把球堆放回材料格 →
     * 选中刚刚的配方」—— 也就是在<b>取产物的那一瞬间</b>做一次原子操作：</p>
     * <ol>
     *   <li>从壳里取走<b>耐久最低</b>的那一颗（{@link BallClusterItem#takeOne}），
     *       剩余的壳先攥在局部变量里（不落地、不进背包，免得出现第二份）；</li>
     *   <li>把那颗球放进材料格、重新选中刚才那条配方 —— 让配方算出正确的产物；</li>
     *   <li>产物交给玩家（光标空时拿在手上），并清掉产物槽；</li>
     *   <li>把剩余的壳<b>放回材料格</b>、再选中一次配方 —— 于是可以接着点下一次。</li>
     * </ol>
     * <p><b>耐久一点不丢</b>：壳里每颗球都按原样保留，每次只吃掉被切的那一颗。</p>
     *
     * <h2>为什么能拦在这里</h2>
     * <p>{@code AbstractContainerMenu#clicked} 是「玩家点击槽位」的唯一入口，
     * 而本类早就注入了它的 HEAD（背包右键取一组那个功能）。这里只是多加一个分支，
     * 用的是原版公开的 {@link StonecutterMenu#getSlot} / {@code clickMenuButton}
     * —— 不碰原版代码，也不需要新开 Mixin。</p>
     *
     * <h2>放行的几种情况</h2>
     * <p>光标上已经拿着东西时直接不管（免得把玩家手上的东西吞了）；材料格不是球堆、
     * 或者产物槽是空的（还没选配方）时也原样放行，走原版流程。</p>
     *
     * @return true 表示这次点击已被接管（调用方要 {@code ci.cancel()}）
     */
    private static boolean moreballs$cutOneFromCluster(AbstractContainerMenu self, int slotId,
                                                       ContainerInput input, Player player) {
        if (!(self instanceof StonecutterMenu menu)) {
            return false;
        }
        if (slotId != StonecutterMenu.RESULT_SLOT || input != ContainerInput.PICKUP) {
            return false;
        }
        // 光标必须空着 —— 有东西时不碰，免得把玩家手上的东西吞了
        if (!self.getCarried().isEmpty()) {
            return false;
        }

        Slot inputSlot = menu.getSlot(StonecutterMenu.INPUT_SLOT);
        ItemStack shell = inputSlot.getItem();
        if (!BallClusterItem.isCluster(shell)) {
            return false;   // 材料格不是球堆 → 走原版（普通球本来就是一次切一颗）
        }
        ItemStack product = self.getSlot(StonecutterMenu.RESULT_SLOT).getItem();
        if (product.isEmpty()) {
            return false;   // 还没选配方
        }
        int selected = menu.getSelectedRecipeIndex();
        if (selected < 0) {
            return false;
        }

        // ① 取走耐久最低那一颗；剩余的壳**只留在局部变量里**
        ItemStack one = BallClusterItem.takeOne(shell);
        if (one.isEmpty()) {
            return false;   // 空壳（理论上不会）
        }
        ItemStack rest = shell.copy();
        boolean hasRest = !BallClusterItem.contents(rest).isEmpty();

        // ② 那一颗进材料格，并重新选中刚才那条配方（材料换物品了，配方列表会被重置）
        inputSlot.set(one);
        menu.clickMenuButton(player, selected);

        // ③ 完成一次切球 —— 产物槽里那份就是这条配方按这颗球算出来的，直接交给玩家
        self.setCarried(product.copy());
        self.getSlot(StonecutterMenu.RESULT_SLOT).set(ItemStack.EMPTY);
        product.onCraftedBy(player, product.getCount());

        // ④ 剩余的壳放回材料格，再选中一次配方 —— 接着还能继续点
        if (hasRest) {
            inputSlot.set(rest);
            menu.clickMenuButton(player, selected);
        }
        self.broadcastChanges();
        return true;
    }
}
