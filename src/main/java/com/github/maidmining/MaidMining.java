package com.github.maidmining;

import com.github.maidmining.config.MiningConfig;
import com.github.maidmining.config.MiningClientConfig;
import com.github.maidmining.network.MiningNetwork;
import com.github.maidmining.registry.GuiRegistry;
import com.github.maidmining.task.MaidMiningTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import studio.fantasyit.maid_useful_task.memory.CurrentWork;
import studio.fantasyit.maid_useful_task.util.MemoryUtil;

import java.util.List;

@Mod(MaidMining.MODID)
public class MaidMining {
    public static final String MODID = "maid_mining";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MaidMining() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MiningConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, MiningClientConfig.SPEC);
        GuiRegistry.init(FMLJavaModLoadingContext.get().getModEventBus());
        MiningNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("Maid Mining Task loaded!");
    }

    /**
     * 全局脏状态清理：女仆加入世界时，若 CurrentWork 卡在非 IDLE 值（如上次会话残留的 DESTROY），
     * 主动复位为 IDLE。否则所有依赖 maid_useful_task 框架的任务（伐木/挖矿/采集）将无法启动。
     * 正常运行时 CurrentWork 由对应 behavior 管理，仅在加载/重载时兜底清理。
     */
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof EntityMaid maid) {
            onMaidJoin(maid);
        } else if (event.getEntity() instanceof ExperienceOrb orb) {
            onExperienceOrbSpawn(event, orb);
        }
    }

    private void onMaidJoin(EntityMaid maid) {
        try {
            CurrentWork work = MemoryUtil.getCurrent(maid);
            if (work != CurrentWork.IDLE) {
                MemoryUtil.setCurrent(maid, CurrentWork.IDLE);
                LOGGER.debug("Cleaned stuck CurrentWork ({}) for maid #{} on join", work, maid.getId());
            }
        } catch (Exception ignored) {
            // maid_useful_task 未加载时安全跳过
        }
    }

    /** 经验吸收半径（格）：连锁矿的经验常掉在远处，取较大范围让挖矿女仆直接收。 */
    private static final double XP_ABSORB_RADIUS = 24.0D;

    /**
     * 玩家优先半径（格）：经验球此距离内有玩家时，一律让给玩家走原版拾取，女仆不碰。
     * 与原版经验球对玩家的吸附距离一致（8 格），确保：
     * <ul>
     *   <li>玩家脚边扔的附魔之瓶经验、玩家自己手挖的经验，正常归玩家，女仆不抢；</li>
     *   <li>连锁矿掉在远处、玩家够不到的经验，才由挖矿女仆补收。</li>
     * </ul>
     */
    private static final double PLAYER_PRIORITY_RADIUS = 8.0D;

    /**
     * 经验自动吸收（两条轨道通用）。挖矿掉落的经验球生成时，若附近没有玩家能拾取，就近
     * 找一只正在执行挖矿任务的女仆，把经验直接加到她身上，并<b>取消经验球的生成</b>
     * （{@code setCanceled}）——省去玩家手挖通道捡远处连锁矿的经验。
     *
     * <p>只在服务端处理，对每颗经验球只在生成瞬间处理一次。原轨道（框架破碎）与新轨道
     * （引线破碎）掉的经验都走这里，无需区分。</p>
     *
     * <p>两个关键点，修正此前的双倍经验 / 抢玩家经验 bug：</p>
     * <ol>
     *   <li><b>取消生成而非 discard</b>：{@code EntityJoinLevelEvent} 正处于实体加入世界的
     *       过程中，此时 {@code orb.discard()} 只打移除标记但世界仍会把它加进去，导致经验
     *       球落地可被玩家二次拾取（双倍经验），且残留经验球卡在脚边乱跳。改用
     *       {@code event.setCanceled(true)} 才能真正阻止经验球进入世界。</li>
     *   <li><b>玩家优先</b>：8 格内有玩家时直接放行走原版，女仆不介入——避免女仆抢走
     *       玩家附魔之瓶 / 手挖的经验。</li>
     * </ol>
     */
    private void onExperienceOrbSpawn(EntityJoinLevelEvent event, ExperienceOrb orb) {
        Level level = event.getLevel();
        if (level.isClientSide) {
            return;
        }
        // 玩家优先：附近有玩家能拾取，交给原版，女仆不碰（防止抢附魔之瓶/玩家手挖经验）。
        if (level.getNearestPlayer(orb.getX(), orb.getY(), orb.getZ(), PLAYER_PRIORITY_RADIUS, false) != null) {
            return;
        }
        AABB box = orb.getBoundingBox().inflate(XP_ABSORB_RADIUS);
        List<EntityMaid> maids = level.getEntitiesOfClass(EntityMaid.class, box,
                m -> m.isAlive() && m.getTask() instanceof MaidMiningTask);
        if (maids.isEmpty()) {
            return;
        }
        EntityMaid nearest = null;
        double best = Double.MAX_VALUE;
        for (EntityMaid m : maids) {
            double dist = m.distanceToSqr(orb);
            if (dist < best) {
                best = dist;
                nearest = m;
            }
        }
        if (nearest != null) {
            nearest.setExperience(nearest.getExperience() + orb.getValue());
            // 取消经验球生成，彻底阻止落地被玩家二次拾取（治双倍经验 + 残留乱跳）。
            event.setCanceled(true);
        }
    }
}