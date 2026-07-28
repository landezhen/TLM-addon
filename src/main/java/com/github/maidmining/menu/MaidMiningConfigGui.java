package com.github.maidmining.menu;

import com.github.maidmining.config.MaidMiningConfigData;
import com.github.maidmining.registry.GuiRegistry;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.task.MaidTaskConfigGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidConfigButton;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import studio.fantasyit.maid_useful_task.network.MaidConfigurePacket;

import java.util.ArrayList;
import java.util.List;

/**
 * 挖矿任务配置界面，分五页：
 * 0 - 行为页：连锁开关/连锁上限/正确工具/穿透/半径/搭路 + 导航
 * 1 - 矿物页（10 种矿物）+ 返回
 * 2 - 废石设置页：丢弃模式 + 保留量 + 白名单入口 + 返回
 * 3 - 废石白名单页A（前9） + 返回 + 下一页
 * 4 - 废石白名单页B（后10） + 返回
 */
public class MaidMiningConfigGui extends MaidTaskConfigGui<MaidMiningConfigGui.Container> {

    private MaidMiningConfigData.Data data;

    private final List<AbstractWidget> behaviorWidgets = new ArrayList<>();
    private final List<AbstractWidget> oreWidgets = new ArrayList<>();
    private final List<AbstractWidget> junkSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> junkListAWidgets = new ArrayList<>();
    private final List<AbstractWidget> junkListBWidgets = new ArrayList<>();
    private int currentPage = 0;

    public MaidMiningConfigGui(Container container, Inventory inv, Component title) {
        super(container, inv, title);
    }

    public static class Container extends TaskConfigContainer {
        public Container(int id, Inventory inventory, int entityId) {
            super(GuiRegistry.MAID_MINING_CONFIG_GUI.get(), id, inventory, entityId);
        }
    }

    @Override
    protected void initAdditionData() {
        data = MaidMiningConfigData.get(this.maid);
    }

    @Override
    protected void initAdditionWidgets() {
        int left = this.leftPos + 78;
        int baseTop = this.topPos + 36;
        int step = 18;
        int denseStep = 12;
        int top;

        // ========== 行为页 (0) ==========
        top = baseTop;
        behaviorWidgets.add(addToggle(left, top, "vein", data.vein)); top += step;
        behaviorWidgets.add(addChainLimit(left, top)); top += step;
        behaviorWidgets.add(addToggle(left, top, "correctTool", data.correctTool)); top += step;
        behaviorWidgets.add(addToggle(left, top, "passthrough", data.passthrough)); top += step;
        behaviorWidgets.add(addRadius(left, top)); top += step;
        // bridge（自动搭路）已锁死常开，从 GUI 移除，位置让给持续检测开关。
        behaviorWidgets.add(addToggle(left, top, "continuousScan", data.continuousScan)); top += step;
        behaviorWidgets.add(addNavButton(left, top, "ore_filter", 1)); top += step;
        behaviorWidgets.add(addNavButton(left, top, "junk_config", 2));

        // ========== 矿物页 (1)：10 种矿物 + 返回 ==========
        top = baseTop;
        oreWidgets.add(addToggle(left, top, "coal", data.coal)); top += denseStep;
        oreWidgets.add(addToggle(left, top, "iron", data.iron)); top += denseStep;
        oreWidgets.add(addToggle(left, top, "copper", data.copper)); top += denseStep;
        oreWidgets.add(addToggle(left, top, "gold", data.gold)); top += denseStep;
        oreWidgets.add(addToggle(left, top, "redstone", data.redstone)); top += denseStep;
        oreWidgets.add(addToggle(left, top, "lapis", data.lapis)); top += denseStep;
        oreWidgets.add(addToggle(left, top, "diamond", data.diamond)); top += denseStep;
        oreWidgets.add(addToggle(left, top, "emerald", data.emerald)); top += denseStep;
        oreWidgets.add(addToggle(left, top, "nether", data.nether)); top += denseStep;
        oreWidgets.add(addToggle(left, top, "debris", data.debris)); top += denseStep;
        oreWidgets.add(addNavButton(left, top, "back", 0));

        // ========== 废石设置页 (2) ==========
        top = baseTop;
        junkSettingWidgets.add(addDropModeButton(left, top)); top += step;
        junkSettingWidgets.add(addKeepAmountButton(left, top)); top += step;
        junkSettingWidgets.add(addNavButton(left, top, "junk_list", 3)); top += step;
        junkSettingWidgets.add(addNavButton(left, top, "back", 0));

        // ========== 废石白名单页A (3)：前9个 + 返回 + 下一页 ==========
        top = baseTop;
        junkListAWidgets.add(addToggle(left, top, "junk_stone", data.junk_stone)); top += denseStep;
        junkListAWidgets.add(addToggle(left, top, "junk_cobblestone", data.junk_cobblestone)); top += denseStep;
        junkListAWidgets.add(addToggle(left, top, "junk_deepslate", data.junk_deepslate)); top += denseStep;
        junkListAWidgets.add(addToggle(left, top, "junk_cobbled_deepslate", data.junk_cobbled_deepslate)); top += denseStep;
        junkListAWidgets.add(addToggle(left, top, "junk_granite", data.junk_granite)); top += denseStep;
        junkListAWidgets.add(addToggle(left, top, "junk_diorite", data.junk_diorite)); top += denseStep;
        junkListAWidgets.add(addToggle(left, top, "junk_andesite", data.junk_andesite)); top += denseStep;
        junkListAWidgets.add(addToggle(left, top, "junk_tuff", data.junk_tuff)); top += denseStep;
        junkListAWidgets.add(addToggle(left, top, "junk_gravel", data.junk_gravel)); top += denseStep;
        junkListAWidgets.add(addNavButton(left, top, "back", 2)); top += denseStep;
        junkListAWidgets.add(addNavButton(left, top, "next_page", 4));

        // ========== 废石白名单页B (4)：后10个 + 返回 ==========
        top = baseTop;
        junkListBWidgets.add(addToggle(left, top, "junk_dirt", data.junk_dirt)); top += denseStep;
        junkListBWidgets.add(addToggle(left, top, "junk_netherrack", data.junk_netherrack)); top += denseStep;
        junkListBWidgets.add(addToggle(left, top, "junk_blackstone", data.junk_blackstone)); top += denseStep;
        junkListBWidgets.add(addToggle(left, top, "junk_basalt", data.junk_basalt)); top += denseStep;
        junkListBWidgets.add(addToggle(left, top, "junk_soul_sand", data.junk_soul_sand)); top += denseStep;
        junkListBWidgets.add(addToggle(left, top, "junk_soul_soil", data.junk_soul_soil)); top += denseStep;
        junkListBWidgets.add(addToggle(left, top, "junk_magma_block", data.junk_magma_block)); top += denseStep;
        junkListBWidgets.add(addToggle(left, top, "junk_calcite", data.junk_calcite)); top += denseStep;
        junkListBWidgets.add(addToggle(left, top, "junk_dripstone_block", data.junk_dripstone_block)); top += denseStep;
        junkListBWidgets.add(addToggle(left, top, "junk_sandstone", data.junk_sandstone)); top += denseStep;
        junkListBWidgets.add(addNavButton(left, top, "back", 2));

        setPage(0);
    }

    private void setPage(int page) {
        currentPage = page;
        behaviorWidgets.forEach(w -> w.visible = page == 0);
        oreWidgets.forEach(w -> w.visible = page == 1);
        junkSettingWidgets.forEach(w -> w.visible = page == 2);
        junkListAWidgets.forEach(w -> w.visible = page == 3);
        junkListBWidgets.forEach(w -> w.visible = page == 4);
    }

    private MaidConfigButton addToggle(int x, int y, String key, boolean initial) {
        MaidConfigButton btn = new MaidConfigButton(x, y,
                Component.translatable("gui.maid_mining." + key),
                Component.translatable("gui.maid_mining." + (initial ? "on" : "off")),
                button -> {
                    button.setValue(Component.translatable("gui.maid_mining.off"));
                    data.setConfigValue(key, "false");
                    MaidConfigurePacket.send(this.maid, MaidMiningConfigData.LOCATION, key, "false");
                },
                button -> {
                    button.setValue(Component.translatable("gui.maid_mining.on"));
                    data.setConfigValue(key, "true");
                    MaidConfigurePacket.send(this.maid, MaidMiningConfigData.LOCATION, key, "true");
                }
        );
        applyTooltip(btn, key);
        return this.addRenderableWidget(btn);
    }

    /**
     * 给配置按钮挂鼠标悬浮说明（像物品栏一样）。文本走语言键 {@code gui.maid_mining.<key>.tip}，
     * 支持 {@code \n} 多行；未定义该键时不挂 tooltip（避免显示原始键名）。
     *
     * <p>{@code MaidConfigButton extends Button}，自带 {@code setTooltip}；基类 GUI 走原版
     * Screen 渲染流程，悬浮时自动绘制。</p>
     */
    private void applyTooltip(AbstractWidget btn, String key) {
        String tipKey = "gui.maid_mining." + key + ".tip";
        // 未翻译时 getString() 会原样返回键名，据此判断是否存在该 tip
        Component probe = Component.translatable(tipKey);
        if (probe.getString().equals(tipKey)) {
            return;
        }
        // 按 \n 拆成多行 tooltip
        String[] lines = probe.getString().split("\\n");
        net.minecraft.network.chat.MutableComponent tip = Component.literal(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            tip.append("\n").append(lines[i]);
        }
        btn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tip));
    }

    private Button addNavButton(int x, int y, String labelKey, int targetPage) {
        Button btn = Button.builder(
                Component.translatable("gui.maid_mining." + labelKey),
                b -> setPage(targetPage)
        ).bounds(x, y, 120, 16).build();
        return this.addRenderableWidget(btn);
    }

    private MaidConfigButton addRadius(int x, int y) {
        MaidConfigButton btn = new MaidConfigButton(x, y,
                Component.translatable("gui.maid_mining.passRadius"),
                Component.literal(data.passRadius + " \u00a77(\u00b1)"),
                button -> {
                    data.passRadius = Math.max(1, data.passRadius - 1);
                    button.setValue(Component.literal(data.passRadius + " \u00a77(\u00b1)"));
                    MaidConfigurePacket.send(this.maid, MaidMiningConfigData.LOCATION, "passRadius", Integer.toString(data.passRadius));
                },
                button -> {
                    data.passRadius = Math.min(8, data.passRadius + 1);
                    button.setValue(Component.literal(data.passRadius + " \u00a77(\u00b1)"));
                    MaidConfigurePacket.send(this.maid, MaidMiningConfigData.LOCATION, "passRadius", Integer.toString(data.passRadius));
                }
        );
        applyTooltip(btn, "passRadius");
        return this.addRenderableWidget(btn);
    }

    private MaidConfigButton addChainLimit(int x, int y) {
        MaidConfigButton btn = new MaidConfigButton(x, y,
                Component.translatable("gui.maid_mining.chainLimit"),
                Component.literal(data.chainLimit + " \u00a77(\u00b14)"),
                button -> {
                    data.chainLimit = Math.max(8, data.chainLimit - 4);
                    button.setValue(Component.literal(data.chainLimit + " \u00a77(\u00b14)"));
                    MaidConfigurePacket.send(this.maid, MaidMiningConfigData.LOCATION, "chainLimit", Integer.toString(data.chainLimit));
                },
                button -> {
                    data.chainLimit = Math.min(48, data.chainLimit + 4);
                    button.setValue(Component.literal(data.chainLimit + " \u00a77(\u00b14)"));
                    MaidConfigurePacket.send(this.maid, MaidMiningConfigData.LOCATION, "chainLimit", Integer.toString(data.chainLimit));
                }
        );
        return this.addRenderableWidget(btn);
    }

    private MaidConfigButton addDropModeButton(int x, int y) {
        MaidConfigButton btn = new MaidConfigButton(x, y,
                Component.translatable("gui.maid_mining.dropMode"),
                dropModeText(data.dropMode),
                button -> {
                    data.dropMode = (data.dropMode + 2) % 3;
                    button.setValue(dropModeText(data.dropMode));
                    MaidConfigurePacket.send(this.maid, MaidMiningConfigData.LOCATION, "dropMode", Integer.toString(data.dropMode));
                },
                button -> {
                    data.dropMode = (data.dropMode + 1) % 3;
                    button.setValue(dropModeText(data.dropMode));
                    MaidConfigurePacket.send(this.maid, MaidMiningConfigData.LOCATION, "dropMode", Integer.toString(data.dropMode));
                }
        );
        applyTooltip(btn, "dropMode");
        return this.addRenderableWidget(btn);
    }

    private Component dropModeText(int mode) {
        switch (mode) {
            case 1: return Component.translatable("gui.maid_mining.dropMode.excess");
            case 2: return Component.translatable("gui.maid_mining.dropMode.all");
            default: return Component.translatable("gui.maid_mining.dropMode.off");
        }
    }

    private MaidConfigButton addKeepAmountButton(int x, int y) {
        MaidConfigButton btn = new MaidConfigButton(x, y,
                Component.translatable("gui.maid_mining.junkKeepAmount"),
                Component.literal(data.junkKeepAmount + " \u00a77(\u00b18)"),
                button -> {
                    data.junkKeepAmount = Math.max(0, data.junkKeepAmount - 8);
                    button.setValue(Component.literal(data.junkKeepAmount + " \u00a77(\u00b18)"));
                    MaidConfigurePacket.send(this.maid, MaidMiningConfigData.LOCATION, "junkKeepAmount", Integer.toString(data.junkKeepAmount));
                },
                button -> {
                    data.junkKeepAmount = Math.min(64, data.junkKeepAmount + 8);
                    button.setValue(Component.literal(data.junkKeepAmount + " \u00a77(\u00b18)"));
                    MaidConfigurePacket.send(this.maid, MaidMiningConfigData.LOCATION, "junkKeepAmount", Integer.toString(data.junkKeepAmount));
                }
        );
        applyTooltip(btn, "junkKeepAmount");
        return this.addRenderableWidget(btn);
    }
}
