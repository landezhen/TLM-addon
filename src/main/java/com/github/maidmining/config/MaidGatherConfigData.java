package com.github.maidmining.config;

import com.github.maidmining.MaidMining;
import com.github.tartaricacid.touhoulittlemaid.api.entity.data.TaskDataKey;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import studio.fantasyit.maid_useful_task.data.IConfigSetter;

/**
 * 单只女仆的采集任务配置。
 */
public class MaidGatherConfigData implements TaskDataKey<MaidGatherConfigData.Data> {

    public static final ResourceLocation LOCATION = new ResourceLocation(MaidMining.MODID, "gather_config");
    public static TaskDataKey<Data> KEY = null;

    public static Data get(EntityMaid maid) {
        return maid.getOrCreateData(KEY, Data.getDefault());
    }

    /** 写回数据到女仆实体（用于计数累计、完成一轮后重置等运行时改动）。 */
    public static void set(EntityMaid maid, Data d) {
        maid.setData(KEY, d);
    }

    /** 写回并同步到客户端（用于交付完成后重置数量栏，让 GUI 立即归零）。 */
    public static void setAndSync(EntityMaid maid, Data d) {
        maid.setAndSyncData(KEY, d);
    }

    @Override
    public ResourceLocation getKey() {
        return LOCATION;
    }

    @Override
    public CompoundTag writeSaveData(Data d) {
        CompoundTag tag = new CompoundTag();
        tag.putString("gatherTarget1", d.gatherTarget1);
        tag.putString("gatherTarget2", d.gatherTarget2);
        tag.putString("gatherTarget3", d.gatherTarget3);
        tag.putInt("gatherAmount", d.gatherAmount);
        tag.putInt("gatheredCount", d.gatheredCount);
        return tag;
    }

    @Override
    public Data readSaveData(CompoundTag t) {
        Data d = Data.getDefault();
        if (t.contains("gatherTarget1")) d.gatherTarget1 = t.getString("gatherTarget1");
        if (t.contains("gatherTarget2")) d.gatherTarget2 = t.getString("gatherTarget2");
        if (t.contains("gatherTarget3")) d.gatherTarget3 = t.getString("gatherTarget3");
        if (t.contains("gatherAmount")) d.gatherAmount = t.getInt("gatherAmount");
        if (t.contains("gatheredCount")) d.gatheredCount = t.getInt("gatheredCount");
        return d;
    }

    public static class Data implements IConfigSetter {
        /** 采集目标方块 1（支持注册名 minecraft:stone / 中文名「石头」/ 短名 stone）。 */
        public String gatherTarget1;
        /** 采集目标方块 2，留空表示不启用。 */
        public String gatherTarget2;
        /** 采集目标方块 3，留空表示不启用。 */
        public String gatherTarget3;
        /** 本轮目标采集数量（0-128）。设为 0 表示任务停止、不再挖掘。 */
        public int gatherAmount;
        /**
         * 本轮已采集数量（运行时累计，不依赖背包）。
         * 用于：1) 精确控制采够即停；2) 防止交付后背包清空导致重新开挖的死循环。
         * 达到 gatherAmount 后触发交付，交付完成时连同进度一起归零（重置数量栏）。
         */
        public int gatheredCount;

        public static Data getDefault() {
            Data d = new Data();
            d.gatherTarget1 = "";
            d.gatherTarget2 = "";
            d.gatherTarget3 = "";
            d.gatherAmount = 64;
            d.gatheredCount = 0;
            return d;
        }

        @Override
        public void setConfigValue(String name, String value) {
            if ("gatherAmount".equals(name)) {
                try {
                    int a = Integer.parseInt(value);
                    gatherAmount = Math.max(0, Math.min(128, a));
                    // 玩家重新设置目标数量 = 开启新一轮，进度清零
                    gatheredCount = 0;
                } catch (NumberFormatException ignored) {
                }
                return;
            }
            if ("gatherTarget1".equals(name)) { gatherTarget1 = value; gatheredCount = 0; return; }
            if ("gatherTarget2".equals(name)) { gatherTarget2 = value; gatheredCount = 0; return; }
            if ("gatherTarget3".equals(name)) { gatherTarget3 = value; gatheredCount = 0; return; }
        }
    }
}