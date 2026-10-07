package com.mcmodworkspace.moreballs;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/**
 * 带 Curios 饰品能力的收纳袋。
 *
 * <h2>为什么单独一个类</h2>
 * <p>它 implements 了 Curios 的 {@link ICurioItem}，所以<b>只有装了 Curios 时才能加载</b>。</p>
 *
 * <p>注册时用 {@code ModList.get().isLoaded("curios")} 判断：
 * 装了就用这个类、没装就用普通的 {@link BallPouchItem}。
 * JVM 是懒加载的 —— 判断为 false 时这个类根本不会被碰，因此
 * <b>没装 Curios 的玩家也不会因为找不到它的接口而崩溃</b>。</p>
 *
 * <h2>装备位置</h2>
 * <p>收纳袋走的是 Curios 的<b>物品标签</b>机制 —— 槽位本身带 {@code curios:tag} 校验器，
 * 只收「在对应标签里」的物品。本模组给出三份标签（{@code #curios:back} /
 * {@code #curios:belt} / {@code #curios:charm}），所以收纳袋能戴在：</p>
 * <ul>
 *   <li><b>背饰（back）</b> —— 背着走</li>
 *   <li><b>腰带（belt）</b> —— 挂腰上</li>
 *   <li><b>护符（charm）</b> —— 带身上</li>
 * </ul>
 *
 * <p>这三个槽位还要「加到玩家身上」才看得见，那一步由
 * {@code data/more_balls/curios/entities/player_slots.json} 声明；
 * 三个槽位之间互不排斥，可以同时各戴一个袋子。</p>
 */
public class BallPouchCurioItem extends BallPouchItem implements ICurioItem {

    public BallPouchCurioItem(Properties properties, BallPouchTier tier) {
        super(properties, tier);
    }

    /**
     * 装备在饰品栏时，每个 tick 都给作者做两件事：
     *
     * <ol>
     *   <li><b>自动收纳</b> —— 把身边停着的球收进袋子（有空格才收）</li>
     *   <li><b>经验修补之类的联动</b> —— 目前不需要，留作扩展点</li>
     * </ol>
     *
     * <p>只在服务端做：客户端做一遍会造成两边数据不一致。</p>
     */
    @Override
    public void curioTick(SlotContext slotContext, ItemStack stack) {
        LivingEntity wearer = slotContext.entity();
        if (wearer == null || wearer.level().isClientSide()) {
            return;
        }
        BallPouchCurios.autoCollect(wearer, stack);
    }
}
