package com.mcmodworkspace.moreballs.mixin;

import com.mcmodworkspace.moreballs.BallClusterContents;
import com.mcmodworkspace.moreballs.BallClusterItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
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

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void moreballs$takeLowestGroup(int slotId, int button, ContainerInput input,
                                           Player player, CallbackInfo ci) {
        // ①② 只认「右键拿起」
        if (input != ContainerInput.PICKUP || button != 1) {
            return;
        }
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;

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
}
