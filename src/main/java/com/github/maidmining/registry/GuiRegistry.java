package com.github.maidmining.registry;

import com.github.maidmining.MaidMining;
import com.github.maidmining.menu.MaidMiningConfigGui;
import com.github.maidmining.menu.MaidGatherConfigGui;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class GuiRegistry {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MaidMining.MODID);

    public static final RegistryObject<MenuType<MaidMiningConfigGui.Container>> MAID_MINING_CONFIG_GUI =
            MENU_TYPES.register("maid_mining_config_gui",
                    () -> IForgeMenuType.create((windowId, inv, data) ->
                            new MaidMiningConfigGui.Container(windowId, inv, data.readInt())));

    public static final RegistryObject<MenuType<MaidGatherConfigGui.Container>> MAID_GATHER_CONFIG_GUI =
            MENU_TYPES.register("maid_gather_config_gui",
                    () -> IForgeMenuType.create((windowId, inv, data) ->
                            new MaidGatherConfigGui.Container(windowId, inv, data.readInt())));

    public static void init(IEventBus modEventBus) {
        MENU_TYPES.register(modEventBus);
    }
}