package com.github.maidmining.menu;

import com.github.maidmining.config.MaidGatherConfigData;
import com.github.maidmining.registry.GuiRegistry;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.task.MaidTaskConfigGui;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import studio.fantasyit.maid_useful_task.network.MaidConfigurePacket;

/**
 * 采集任务独立配置界面：3个目标方块输入框 + 1个数量输入框。
 * 每个输入框带灰色提示文字（hint），说明填写格式与用途。
 */
public class MaidGatherConfigGui extends MaidTaskConfigGui<MaidGatherConfigGui.Container> {

    private MaidGatherConfigData.Data data;

    public MaidGatherConfigGui(Container container, Inventory inv, Component title) {
        super(container, inv, title);
    }

    public static class Container extends TaskConfigContainer {
        public Container(int id, Inventory inventory, int entityId) {
            super(GuiRegistry.MAID_GATHER_CONFIG_GUI.get(), id, inventory, entityId);
        }
    }

    @Override
    protected void initAdditionData() {
        this.data = this.maid.getOrCreateData(MaidGatherConfigData.KEY, MaidGatherConfigData.Data.getDefault());
    }

    @Override
    protected void initAdditionWidgets() {
        super.initAdditionWidgets();
        int left = leftPos + 87;
        int top = topPos + 36;

        // 目标方块 1：必填项，支持注册名/中文名/短名
        EditBox t1 = new EditBox(Minecraft.getInstance().font, left, top, 120, 14, Component.translatable("gui.maid_mining.gatherTarget1"));
        t1.setMaxLength(64);
        t1.setValue(data.gatherTarget1);
        t1.setHint(Component.literal("目标1 如：石头/stone").withStyle(ChatFormatting.DARK_GRAY));
        t1.setResponder(s -> { data.gatherTarget1 = s; MaidConfigurePacket.send(this.maid, MaidGatherConfigData.LOCATION, "gatherTarget1", s); });
        this.addRenderableWidget(t1);
        top += 18;

        // 目标方块 2：选填，留空不启用
        EditBox t2 = new EditBox(Minecraft.getInstance().font, left, top, 120, 14, Component.translatable("gui.maid_mining.gatherTarget2"));
        t2.setMaxLength(64);
        t2.setValue(data.gatherTarget2);
        t2.setHint(Component.literal("目标2 选填").withStyle(ChatFormatting.DARK_GRAY));
        t2.setResponder(s -> { data.gatherTarget2 = s; MaidConfigurePacket.send(this.maid, MaidGatherConfigData.LOCATION, "gatherTarget2", s); });
        this.addRenderableWidget(t2);
        top += 18;

        // 目标方块 3：选填，留空不启用
        EditBox t3 = new EditBox(Minecraft.getInstance().font, left, top, 120, 14, Component.translatable("gui.maid_mining.gatherTarget3"));
        t3.setMaxLength(64);
        t3.setValue(data.gatherTarget3);
        t3.setHint(Component.literal("目标3 选填").withStyle(ChatFormatting.DARK_GRAY));
        t3.setResponder(s -> { data.gatherTarget3 = s; MaidConfigurePacket.send(this.maid, MaidGatherConfigData.LOCATION, "gatherTarget3", s); });
        this.addRenderableWidget(t3);
        top += 18;

        // 采集数量：0-128。采够后女仆回主人身边交付并自动归零，需重新输入开启下一轮
        EditBox amountBox = new EditBox(Minecraft.getInstance().font, left, top, 120, 14, Component.translatable("gui.maid_mining.gatherAmount"));
        amountBox.setMaxLength(3);
        // 重置后 gatherAmount==0，显示为空，提示玩家重新输入数量开启下一轮
        amountBox.setValue(data.gatherAmount > 0 ? Integer.toString(data.gatherAmount) : "");
        amountBox.setHint(Component.literal("数量 0-128 采够自动交付").withStyle(ChatFormatting.DARK_GRAY));
        amountBox.setResponder(s -> {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                data.gatherAmount = 0;
                MaidConfigurePacket.send(this.maid, MaidGatherConfigData.LOCATION, "gatherAmount", "0");
                return;
            }
            try {
                int a = Integer.parseInt(trimmed);
                a = Math.max(0, Math.min(128, a));
                data.gatherAmount = a;
                MaidConfigurePacket.send(this.maid, MaidGatherConfigData.LOCATION, "gatherAmount", Integer.toString(a));
            } catch (NumberFormatException ignored) {
            }
        });
        this.addRenderableWidget(amountBox);
    }
}