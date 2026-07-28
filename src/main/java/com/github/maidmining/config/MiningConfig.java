package com.github.maidmining.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Arrays;
import java.util.List;

/**
 * 通用配置：定义女仆挖矿任务的全局行为。
 * 这些配置对所有女仆生效；单只女仆的开关通过任务 GUI（连锁/正确工具）控制。
 */
public class MiningConfig {
    public static final ForgeConfigSpec SPEC;

    /** 矿物白名单（方块注册名）。为空时回退到 forge:ores 标签。 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ORE_WHITELIST;
    /** 是否使用 forge:ores 标签作为默认矿物判定（白名单为空时）。 */
    public static final ForgeConfigSpec.BooleanValue USE_ORE_TAG;
    /** 连锁挖矿单次最大方块数，防止挖穿巨型矿脉卡顿。 */
    public static final ForgeConfigSpec.IntValue VEIN_MAX_BLOCKS;
    /** 女仆够取/挖掘的半径（格）。 */
    public static final ForgeConfigSpec.IntValue REACH_DISTANCE;
    /** 丢废石时，背包内每种废石保留的数量（供搭路使用），超出部分丢弃。 */
    public static final ForgeConfigSpec.IntValue JUNK_KEEP;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Maid Mining Task - 通用配置").push("mining");

        ORE_WHITELIST = builder
                .comment("矿物白名单（方块注册名，例如 minecraft:diamond_ore）。留空则使用矿物标签。")
                .defineList("oreWhitelist", Arrays.asList(
                        "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
                        "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
                        "minecraft:copper_ore", "minecraft:deepslate_copper_ore",
                        "minecraft:gold_ore", "minecraft:deepslate_gold_ore",
                        "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
                        "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
                        "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
                        "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
                        "minecraft:nether_quartz_ore", "minecraft:nether_gold_ore",
                        "minecraft:ancient_debris"
                ), o -> o instanceof String);

        USE_ORE_TAG = builder
                .comment("白名单为空时，是否把所有带 forge:ores 标签的方块都视为矿物。")
                .define("useOreTagWhenEmpty", true);

        VEIN_MAX_BLOCKS = builder
                .comment("连锁挖矿单次最大方块数。")
                .defineInRange("veinMaxBlocks", 64, 1, 512);

        REACH_DISTANCE = builder
                .comment("女仆挖掘可达半径（格）。")
                .defineInRange("reachDistance", 7, 3, 24);

        JUNK_KEEP = builder
                .comment("开启丢废石后，每种废石在背包内保留的数量（用于搭路），超出部分丢弃。设为 0 则全部丢弃。")
                .defineInRange("junkKeep", 64, 0, 1024);

        builder.pop();
        SPEC = builder.build();
    }
}