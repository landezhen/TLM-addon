package com.github.maidmining.util;

import com.github.maidmining.util.OreMatcher.OreType;

/**
 * 矿物类型 → 描棱高亮颜色（RGB，0-255）。金矿金色、铁矿银色、钻石青色……
 * 与 {@link OreMatcher.OreType} 一一对应。
 */
public final class OreColors {

    private OreColors() {
    }

    public static final class Rgb {
        public final float r, g, b;
        Rgb(int hex) {
            this.r = ((hex >> 16) & 0xFF) / 255.0f;
            this.g = ((hex >> 8) & 0xFF) / 255.0f;
            this.b = (hex & 0xFF) / 255.0f;
        }
    }

    private static final Rgb COAL = new Rgb(0x4D4D4D);
    private static final Rgb IRON = new Rgb(0xD8D8D8);   // 银白
    private static final Rgb COPPER = new Rgb(0xE07B3C);  // 橙铜
    private static final Rgb GOLD = new Rgb(0xFCDC3B);    // 金
    private static final Rgb REDSTONE = new Rgb(0xFF2B2B);// 红
    private static final Rgb LAPIS = new Rgb(0x2B5CFF);   // 青金蓝
    private static final Rgb DIAMOND = new Rgb(0x4DFFE0); // 钻石青
    private static final Rgb EMERALD = new Rgb(0x2BFF5C); // 绿
    private static final Rgb NETHER = new Rgb(0xFFE8D0);  // 石英/下界金 浅白
    private static final Rgb DEBRIS = new Rgb(0x8A5A4A);  // 远古残骸 暗棕
    private static final Rgb NONE = new Rgb(0xFFFFFF);

    /** 取某矿物类型的高亮颜色。 */
    public static Rgb of(OreType type) {
        switch (type) {
            case COAL: return COAL;
            case IRON: return IRON;
            case COPPER: return COPPER;
            case GOLD: return GOLD;
            case REDSTONE: return REDSTONE;
            case LAPIS: return LAPIS;
            case DIAMOND: return DIAMOND;
            case EMERALD: return EMERALD;
            case NETHER: return NETHER;
            case DEBRIS: return DEBRIS;
            default: return NONE;
        }
    }
}