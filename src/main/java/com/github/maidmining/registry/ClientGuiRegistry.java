package com.github.maidmining.registry;

import com.github.maidmining.MaidMining;
import com.github.maidmining.menu.MaidMiningConfigGui;
import com.github.maidmining.menu.MaidGatherConfigGui;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = MaidMining.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientGuiRegistry {
    @SubscribeEvent
    public static void init(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
                MenuScreens.register(GuiRegistry.MAID_MINING_CONFIG_GUI.get(), MaidMiningConfigGui::new);
                MenuScreens.register(GuiRegistry.MAID_GATHER_CONFIG_GUI.get(), MaidGatherConfigGui::new);
        });
    }
}