package com.github.maidmining.behavior;

import com.github.maidmining.config.MaidMiningConfigData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import studio.fantasyit.maid_useful_task.memory.CurrentWork;
import studio.fantasyit.maid_useful_task.util.MemoryUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * 丢弃废石行为（per-maid 配置版）。
 *
 * dropMode:
 *   0 = 关闭
 *   1 = 丢弃多余（每种废石保留 junkKeepAmount，超出丢弃）
 *   2 = 丢弃全部（白名单内废石一律丢出）
 *
 * 仅在挖矿模式（gatherMode=false）且女仆 IDLE 时触发。
 */
public class MaidDropJunkBehavior extends Behavior<EntityMaid> {
    private static final int DROP_INTERVAL = 40;
    private int cooldown = 0;

    public MaidDropJunkBehavior() {
        super(Map.of(), 60);
    }

    @Override
    protected boolean checkExtraStartConditions(@NotNull ServerLevel level, @NotNull EntityMaid maid) {
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);
        // 仅挖矿模式下启用（采集模式已独立为单独任务，不会走到这里）
        if (d.dropMode == 0) return false;
        if (MemoryUtil.getCurrent(maid) != CurrentWork.IDLE) return false;
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        return hasExcessJunk(maid, d);
    }

    @Override
    protected void start(@NotNull ServerLevel level, @NotNull EntityMaid maid, long gameTime) {
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);
        dropExcessJunk(maid, d);
        cooldown = DROP_INTERVAL;
    }

    @Override
    protected boolean canStillUse(@NotNull ServerLevel level, @NotNull EntityMaid maid, long gameTime) {
        return false;
    }

    @Override
    protected boolean timedOut(long gameTime) {
        return false;
    }

    /** 判断物品是否在该女仆的废石白名单中。 */
    private boolean isJunkItem(ItemStack stack, MaidMiningConfigData.Data d) {
        if (stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(blockItem.getBlock());
        if (rl == null) return false;
        String path = rl.getPath();
        switch (path) {
            case "stone": return d.junk_stone;
            case "cobblestone": return d.junk_cobblestone;
            case "deepslate": return d.junk_deepslate;
            case "cobbled_deepslate": return d.junk_cobbled_deepslate;
            case "granite": return d.junk_granite;
            case "diorite": return d.junk_diorite;
            case "andesite": return d.junk_andesite;
            case "tuff": return d.junk_tuff;
            case "gravel": return d.junk_gravel;
            case "dirt": return d.junk_dirt;
            case "netherrack": return d.junk_netherrack;
            case "blackstone": return d.junk_blackstone;
            case "basalt": return d.junk_basalt;
            case "soul_sand": return d.junk_soul_sand;
            case "soul_soil": return d.junk_soul_soil;
            case "magma_block": return d.junk_magma_block;
            case "calcite": return d.junk_calcite;
            case "dripstone_block": return d.junk_dripstone_block;
            case "sandstone": return d.junk_sandstone;
            default: return false;
        }
    }

    private boolean hasExcessJunk(EntityMaid maid, MaidMiningConfigData.Data d) {
        IItemHandlerModifiable inv = maid.getAvailableBackpackInv();
        if (d.dropMode == 2) {
            // 丢弃全部：只要有白名单物品就触发
            for (int i = 0; i < inv.getSlots(); i++) {
                if (isJunkItem(inv.getStackInSlot(i), d)) return true;
            }
            return false;
        }
        // 丢弃多余：超过保留量才触发
        int keep = d.junkKeepAmount;
        Map<net.minecraft.world.item.Item, Integer> seen = new HashMap<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!isJunkItem(stack, d)) continue;
            int total = seen.getOrDefault(stack.getItem(), 0) + stack.getCount();
            seen.put(stack.getItem(), total);
            if (total > keep) return true;
        }
        return false;
    }

    private void dropExcessJunk(EntityMaid maid, MaidMiningConfigData.Data d) {
        IItemHandlerModifiable inv = maid.getAvailableBackpackInv();
        if (d.dropMode == 2) {
            // 丢弃全部：白名单废石直接从背包移除（销毁，不生成掉落物，避免脚下被捡回）
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (isJunkItem(stack, d)) {
                    inv.setStackInSlot(i, ItemStack.EMPTY);
                }
            }
            return;
        }
        // 丢弃多余：超过保留量的部分直接销毁，不生成掉落物
        int keep = d.junkKeepAmount;
        Map<net.minecraft.world.item.Item, Integer> kept = new HashMap<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!isJunkItem(stack, d)) continue;
            net.minecraft.world.item.Item item = stack.getItem();
            int alreadyKept = kept.getOrDefault(item, 0);
            int canKeep = Math.max(0, keep - alreadyKept);
            int count = stack.getCount();
            if (count <= canKeep) {
                kept.put(item, alreadyKept + count);
                continue;
            }
            // 保留 canKeep 份，多余的直接销毁
            kept.put(item, alreadyKept + canKeep);
            ItemStack remain = stack.copy();
            remain.setCount(canKeep);
            inv.setStackInSlot(i, remain.isEmpty() ? ItemStack.EMPTY : remain);
        }
    }
}
