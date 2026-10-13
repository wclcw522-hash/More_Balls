package com.mcmodworkspace.moreballs.entity;

import com.mcmodworkspace.moreballs.ModEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

/**
 * 作为<b>弩弹药</b>射出的末影珍珠 —— 在原版珍珠的基础上补上伤害。
 *
 * <p>原版 {@code ThrownEnderpearl.onHitEntity} 里写死了 {@code hurt(..., 0.0F)}，
 * 它只负责把射手传送过去。作者要求求「末影珍珠作为弹药时伤害 5，保留瞬移功能」，
 * 所以这里派生一个子类：命中实体时先结算 5 点弹药伤害，再走父类的传送链路
 * （传送、末影螨、传送事件、落地伤害全都照旧继承）。</p>
 *
 * <p>右键手扔的末影珍珠<b>不受影响</b>：它仍走原版 {@code EnderpearlItem}，
 * 只传送、不造成伤害。</p>
 */
public class BallEnderpearl extends ThrownEnderpearl {

    /** 作为弹药时的命中伤害（作者指定 5） */
    public static final float AMMO_DAMAGE = 5.0F;

    public BallEnderpearl(EntityType<? extends BallEnderpearl> type, Level level) {
        super(type, level);
    }

    /**
     * 由发射方构造：父类只提供 {@code (Level, LivingEntity, ItemStack)} 这一种带射手的构造，
     * 而它会把实体类型写死成原版珍珠，所以这里改用 {@code (EntityType, Level)} 再手动补齐。
     */
    public BallEnderpearl(Level level, LivingEntity shooter, ItemStack stack) {
        this(ModEntities.BALL_ENDER_PEARL.get(), level);
        this.setOwner(shooter);
        this.setItem(stack);
        this.setPos(shooter.getX(), shooter.getEyeY() - 0.1D, shooter.getZ());
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        // 先补上弹药伤害
        Entity entity = hitResult.getEntity();
        if (this.level() instanceof ServerLevel serverLevel) {
            entity.hurtServer(serverLevel, this.damageSources().thrown(this, this.getOwner()), AMMO_DAMAGE);
        }

        // ===== 【移形换位】（「其它特殊」类，作者 2026-10-13 指定）=====
        //
        // 带这个词条时：珍珠击中**其它生物** → 与该生物**交换位置**（原有瞬移照旧保留）。
        //
        // 做法分三步，顺序不能颠倒：
        //   ① 先记下射手**当前**的位置
        //   ② 走 super —— 父类会把射手瞬移到珍珠落点（也就是目标身边）
        //   ③ 再把目标送回「射手原来站的地方」
        // 净效果就是两者互换。反过来先搬目标的话，父类之后又会把射手拉到落点，
        // 目标等于被白白挪了一次。
        //
        // ⚠️ 判据走 profile（而不是物品 id）—— 组合球里只要有一份末影珍珠，
        //    comboProfile 就会写下 transpose=true，这里同样认。
        boolean transpose = com.mcmodworkspace.moreballs.BallBehavior
                .profileFor(this.getItem()).transpose();
        Entity owner = this.getOwner();
        LivingEntity swapTarget = null;
        double backX = 0.0D;
        double backY = 0.0D;
        double backZ = 0.0D;
        if (transpose
                && owner instanceof LivingEntity shooter
                && entity instanceof LivingEntity target
                && target != shooter
                && target.isAlive()) {
            backX = shooter.getX();
            backY = shooter.getY();
            backZ = shooter.getZ();
            swapTarget = target;
        }

        // 再走父类：原来的 0 伤害 + 后续传送链路
        super.onHitEntity(hitResult);

        // ③ 把目标送回射手原来的位置 —— 换位完成
        if (swapTarget != null && this.level() instanceof ServerLevel) {
            swapTarget.teleportTo(backX, backY, backZ);
        }
    }
}
