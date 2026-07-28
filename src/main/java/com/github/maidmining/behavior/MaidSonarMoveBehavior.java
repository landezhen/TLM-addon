package com.github.maidmining.behavior;

import com.github.maidmining.config.MaidMiningConfigData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import studio.fantasyit.maid_useful_task.behavior.common.DestoryBlockMoveBehavior;

/**
 * 声波探测移动/选矿行为（仅一键连锁 vein=true 生效）。
 *
 * <p><b>为什么是继承框架 Move 而不是重写：</b>框架 {@code MaidCenterMoveToBlockTask.searchForDestination}
 * 的遍历循环本身就是「女仆为心、切比雪夫方形壳层、近层优先」的结构——这恰好等价于声波探测的扫描模型。
 * 唯一的短板是壳层上限 {@code searchRange}（框架构造写死为 8，实际只覆盖切比雪夫 ±7），
 * 导致「矿在第 8 格」时女仆静止时锁不到目标（表现为纹丝不动，走近到能纳入壳层才动）。
 * {@code searchRange} 有公开 setter，因此只需在启动判定阶段把它放大即可完成声波探测的核心。</p>
 *
 * <p><b>圆中方（锁定 ⊇ 穿透）：</b>探测用切比雪夫方形壳层（本行为），穿透用欧几里得直线距离
 * （{@code MaidMiningTask.toDestroyFromStanding} 的距离闸）。方形外接于圆，保证任何能被穿透闸
 * 接受的目标都一定落在探测壳层内——不会出现「穿透够得到、声波却没锁定」的空挖。反之，
 * 声波锁定了但穿透闸过不了的候选，会被框架逐个跳过，直到找到能挖的，或本轮放弃（兜底②）。</p>
 *
 * <p><b>触发与兜底（框架天然具备，无需额外代码）：</b></p>
 * <ul>
 *   <li>触发：女仆 IDLE 时框架周期性重跑本行为选新目标；连锁进行中
 *       {@code MaidMiningTask.shouldDestroyBlock} 因 {@code hasPending} 返回 false 不锁新目标，
 *       引线烧完 {@code hasPending} 转 false 后下一次 IDLE tick 自动重新探测（= 连锁结束瞬间触发新探测）。</li>
 *   <li>兜底②（目标超范围/穿不过）：候选过不了穿透闸就跳下一个，全壳层无解则本轮不动，下 tick 重试。</li>
 *   <li>兜底③（收放符魂）：框架切换任务时清空目标记忆并重置状态，等价于重新触发探测。</li>
 * </ul>
 *
 * <p><b>已知偏差（受框架 final 方法限制）：</b>层内精确 Y&gt;X&gt;Z 排序、以及垂直方向
 * 覆盖满 ±passRadius（框架垂直上限写死 7），无法通过继承实现，需完整重写搜索行为才行。
 * 当前版本水平壳层已覆盖 ±passRadius，垂直覆盖 ±7，层内顺序沿用框架壳层遍历顺序
 * （功能等价，仅同层多矿的选取偏好不同）。</p>
 */
public class MaidSonarMoveBehavior extends DestoryBlockMoveBehavior {

    /**
     * 启动判定：在框架启动检查之前，按一键连锁开关设定本轮的探测壳层半径。
     *
     * <p>放在 {@code checkExtraStartConditions}（而非 {@code start}）里设置的原因：框架
     * {@code start} 内部会立即调用 {@code searchForDestination} 执行搜索，此时 searchRange
     * 必须已是目标值；而 {@code checkExtraStartConditions} 恰在 {@code start} 之前调用。</p>
     *
     * <ul>
     *   <li>vein=true：searchRange = passRadius + 1，切比雪夫壳层覆盖 ±passRadius（声波锁定范围 ⊇ 穿透范围）。</li>
     *   <li>vein=false：searchRange = 8，维持框架原机制现状（水平有效 ±7，即用户确认「原版 7 格先不管」）。</li>
     * </ul>
     *
     * <p>注意：女仆处于 home（限定活动范围）模式时，框架 {@code start} 会用女仆活动半径覆盖此值，
     * 属预期行为——home 模式本就应把女仆约束在活动范围内，不做声波放大。</p>
     */
    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);
        // vein=true（一键连锁）交给 MaidAnchorMoveBehavior 锚定挖矿，本行为让位不启动。
        // 本行为只负责 vein=false 的原机制（框架跟随女仆的声波壳层，用户确认「原版 7 格先不管」）。
        if (d.vein) {
            return false;
        }
        setSearchRange(8);
        return super.checkExtraStartConditions(level, maid);
    }
}
