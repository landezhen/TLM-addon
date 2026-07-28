package com.github.maidmining.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 客户端渲染配置（仅客户端加载，服务端忽略）。
 * 隐性功能：只在配置文件里改，不进任务 GUI。控制锚点探测范围可视化的显示。
 */
public class MiningClientConfig {
    public static final ForgeConfigSpec SPEC;

    /** 总开关：是否渲染锚定挖矿女仆的可视化（范围框 + 矿物描棱 + 锚点图标）。 */
    public static final ForgeConfigSpec.BooleanValue ENABLE;
    /** 渲染距离（格）：相机到锚点超过此距离不渲染，控制远处开销。 */
    public static final ForgeConfigSpec.IntValue RENDER_DISTANCE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Maid Mining Task - 客户端锚点可视化（隐性功能，仅配置文件可改）").push("render");

        ENABLE = builder
                .comment("总开关：是否渲染处于锚定模式（一键连锁开+持续检测关）女仆的可视化。",
                        "包含：锚点探测范围灰色半透明方框、范围内矿物按类型描棱高亮、锚点定位图标。",
                        "默认关闭；此为调试/观察用的隐性功能。")
                .define("enable", false);
        RENDER_DISTANCE = builder
                .comment("渲染距离（格）：相机到锚点超过此距离则不渲染。")
                .defineInRange("renderDistance", 64, 8, 256);

        builder.pop();
        SPEC = builder.build();
    }
}