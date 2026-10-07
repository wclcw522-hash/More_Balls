package com.mcmodworkspace.moreballs;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

/**
 * 组合球 —— 由 2 个半球或 4 个四分之一球合出来的球。
 *
 * <h2>为什么它只覆写名称</h2>
 * <p>它是**真正的球**（继承 {@link BallItem}），所以投掷、蓄力、弩弹药、坚固反弹、
 * 碎裂、热量、磁吸这些机制<b>全部直接继承</b>，一行都不用改 ——
 * 那些逻辑都是通过 {@link BallBehavior#profileFor(ItemStack)} 取参数的，
 * 而那个方法已经认得组合球（按组件现算，见 {@link BallFragments#comboProfileFrom}）。</p>
 *
 * <p>词条 tooltip 同理：{@code BallTooltip.entries()} 也走 {@code profileFor}，
 * 所以它也自动生效，这里不需要再写一遍。</p>
 *
 * <p>唯一的例外是<b>名称</b>：组合球的名字要按组件里的来源拼出来，
 * 这是一个物品实例一个样子的事，只能覆写 {@link #getName}。</p>
 *
 * <h2>命名顺序 = 摆放位置顺序（作者指定）</h2>
 * <p>{@code combo_sources} 里存的下标是<b>按格位顺序</b>排的，不排序：</p>
 * <ul>
 *   <li>二合一（上下两格）：{@code "铁,金"} → <b>铁-金球</b>（上铁下金）</li>
 *   <li>四合一（2×2）：{@code "圆石,木,金,铁"} → <b>圆石-木-金-铁球</b>
 *       （左上、右上、左下、右下）</li>
 * </ul>
 * <p>所以同一个「铁+金」组合，把铁放上面得到「铁-金球」，放下面得到「金-铁球」——
 * <b>是同一件物品</b>（不会重复注册），但名字与贴图都跟着位置走。</p>
 */
public class ComboBallItem extends BallItem {

    public ComboBallItem(Properties properties) {
        super(properties);
    }

    /**
     * 组合球的显示名 —— 各来源短名用 {@code -} 连起来，末尾统一加「球」。
     *
     * <p>来源短名走 lang（{@code ball.more_balls.short.<id>}），拼装模板也走 lang，
     * 所以英文环境能正常显示，不是硬编码中文。</p>
     */
    @Override
    public Component getName(ItemStack stack) {
        List<Integer> indexes = BallFragments.parseIndexes(
                stack.getOrDefault(ModComponents.COMBO_SOURCES.get(), ""));
        if (indexes.isEmpty()) {
            // 组件缺失（比如创造模式物品栏里那个裸模板）→ 退回普通名字，不会显示成裸翻译键
            return super.getName(stack);
        }

        // 注意这里**不能**用 .getString()：那样会把组件压成纯文本、丢掉语言环境与样式。
        // 正确做法是把若干 Component 拼成一个再整体当参数传进去。
        MutableComponent joined = Component.empty();
        boolean first = true;
        for (int index : indexes) {
            if (!first) {
                joined.append("-");
            }
            first = false;
            joined.append(Component.translatable(
                    "ball.more_balls.short." + BallFragments.SHORT_ID.get(index)));
        }
        return Component.translatable("item.more_balls.combo_ball.format", joined);
    }

    /**
     * <b>旧数据的自动迁移</b>（2.6.0 加入分层渲染后必需）。
     *
     * <h2>为什么要它</h2>
     * <p>2.6.0 之前组合球只存一个 {@code combo_sources}（字符串）。改成匠魂式分层渲染后，
     * 贴图由四个 {@code combo_slot_1..4} 组件分别选象限 ——
     * 于是<b>存档里已有的组合球那四个组件都是空的，四层全落到全透明 fallback，
     * 表现就是「贴图没了」</b>（作者反馈过）。</p>
     *
     * <p>组件本身是纯渲染用的派生物，完全可以从 {@code combo_sources} 重新算出来，
     * 所以这里做的是「用到就补」：<b>任何旧球只要进过玩家背包（捡起、从箱子里拿出来），
     * 下一刻就会被补齐</b>，不需要专门的数据迁移流程，也不会漏掉任何一处容器。</p>
     *
     * <p>实现上先做一次廉价的「齐不齐」检查，齐了立刻返回 —— 所以常态是零开销。</p>
     */
    @Override
    public void inventoryTick(ItemStack stack, net.minecraft.server.level.ServerLevel level,
                              net.minecraft.world.entity.Entity entity,
                              net.minecraft.world.entity.EquipmentSlot slot) {
        BallFragments.ensureComboSlots(stack);
    }
}
