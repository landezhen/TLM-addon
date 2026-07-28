package com.github.maidmining;

import com.github.maidmining.config.MaidMiningConfigData;
import com.github.maidmining.config.MaidGatherConfigData;
import com.github.maidmining.task.MaidMiningTask;
import com.github.maidmining.task.MaidGatherTask;
import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.data.TaskDataRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import studio.fantasyit.maid_useful_task.data.MaidConfigKeys;

/**
 * 车万女仆扩展入口。被 TLM 通过 @LittleMaidExtension 注解自动发现。
 * 注册挖矿任务及其 per-maid 配置数据。
 */
@LittleMaidExtension
public class MaidMiningExtension implements ILittleMaid {

    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(new MaidMiningTask());
        manager.add(new MaidGatherTask());
        MaidMining.LOGGER.info("Registered maid mining and gather tasks.");
    }

    @Override
    public void registerTaskData(TaskDataRegister register) {
        MaidMiningConfigData.KEY = register.register(new MaidMiningConfigData());
        MaidConfigKeys.addKey(MaidMiningConfigData.LOCATION,
                MaidMiningConfigData.KEY,
                MaidMiningConfigData.Data::getDefault);
        MaidGatherConfigData.KEY = register.register(new MaidGatherConfigData());
        MaidConfigKeys.addKey(MaidGatherConfigData.LOCATION,
                MaidGatherConfigData.KEY,
                MaidGatherConfigData.Data::getDefault);
    }
}