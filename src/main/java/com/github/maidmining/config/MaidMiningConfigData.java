package com.github.maidmining.config;

import com.github.maidmining.MaidMining;
import com.github.tartaricacid.touhoulittlemaid.api.entity.data.TaskDataKey;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import studio.fantasyit.maid_useful_task.data.IConfigSetter;

/**
 * 单只女仆的挖矿配置（随存档保存、可通过任务 GUI 修改）。
 */
public class MaidMiningConfigData implements TaskDataKey<MaidMiningConfigData.Data> {

    public static final ResourceLocation LOCATION = new ResourceLocation(MaidMining.MODID, "mining_config");
    public static TaskDataKey<Data> KEY = null;

    /**
     * 取这只女仆的挖矿配置。
     *
     * <p><b>为什么不直接用 {@code getOrCreateData(KEY, Data.getDefault())}</b>：
     * Java 求值参数在前，那种写法<b>每次调用都会 new 一个 Data 并逐个赋三十多个字段</b>，
     * 哪怕女仆早就有配置、这个默认值当场就被丢弃。本方法在射线穿透判定、目标搜索等
     * 热路径里逐格调用，每 tick 数百到数千次，这些临时对象全是白扔给 GC 的垃圾。</p>
     *
     * <p>改成先用只读的 {@code getData} 探一次，命中就直接返回；只有真的没有配置
     * （新女仆第一次进挖矿任务）才构造默认值并写入。默认值实例不做共享——
     * 每只女仆必须持有自己的副本，否则改一只的设置会串到所有女仆身上。</p>
     */
    public static Data get(EntityMaid maid) {
        Data existing = maid.getData(KEY);
        if (existing != null) {
            return existing;
        }
        return maid.getOrCreateData(KEY, Data.getDefault());
    }

    @Override
    public ResourceLocation getKey() {
        return LOCATION;
    }

    @Override
    public CompoundTag writeSaveData(Data d) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("coal", d.coal);
        tag.putBoolean("iron", d.iron);
        tag.putBoolean("copper", d.copper);
        tag.putBoolean("gold", d.gold);
        tag.putBoolean("redstone", d.redstone);
        tag.putBoolean("lapis", d.lapis);
        tag.putBoolean("diamond", d.diamond);
        tag.putBoolean("emerald", d.emerald);
        tag.putBoolean("nether", d.nether);
        tag.putBoolean("debris", d.debris);
        tag.putBoolean("vein", d.vein);
        tag.putInt("chainLimit", d.chainLimit);
        tag.putBoolean("correctTool", d.correctTool);
        tag.putBoolean("passthrough", d.passthrough);
        tag.putInt("passRadius", d.passRadius);
        tag.putBoolean("bridge", d.bridge);
        tag.putBoolean("continuousScan", d.continuousScan);
        // 丢弃废石系统
        tag.putInt("dropMode", d.dropMode);
        tag.putInt("junkKeepAmount", d.junkKeepAmount);
        tag.putBoolean("junk_stone", d.junk_stone);
        tag.putBoolean("junk_cobblestone", d.junk_cobblestone);
        tag.putBoolean("junk_deepslate", d.junk_deepslate);
        tag.putBoolean("junk_cobbled_deepslate", d.junk_cobbled_deepslate);
        tag.putBoolean("junk_granite", d.junk_granite);
        tag.putBoolean("junk_diorite", d.junk_diorite);
        tag.putBoolean("junk_andesite", d.junk_andesite);
        tag.putBoolean("junk_tuff", d.junk_tuff);
        tag.putBoolean("junk_gravel", d.junk_gravel);
        tag.putBoolean("junk_dirt", d.junk_dirt);
        tag.putBoolean("junk_netherrack", d.junk_netherrack);
        tag.putBoolean("junk_blackstone", d.junk_blackstone);
        tag.putBoolean("junk_basalt", d.junk_basalt);
        tag.putBoolean("junk_soul_sand", d.junk_soul_sand);
        tag.putBoolean("junk_soul_soil", d.junk_soul_soil);
        tag.putBoolean("junk_magma_block", d.junk_magma_block);
        tag.putBoolean("junk_calcite", d.junk_calcite);
        tag.putBoolean("junk_dripstone_block", d.junk_dripstone_block);
        tag.putBoolean("junk_sandstone", d.junk_sandstone);
        return tag;
    }

    @Override
    public Data readSaveData(CompoundTag t) {
        Data d = Data.getDefault();
        if (t.contains("coal")) d.coal = t.getBoolean("coal");
        if (t.contains("iron")) d.iron = t.getBoolean("iron");
        if (t.contains("copper")) d.copper = t.getBoolean("copper");
        if (t.contains("gold")) d.gold = t.getBoolean("gold");
        if (t.contains("redstone")) d.redstone = t.getBoolean("redstone");
        if (t.contains("lapis")) d.lapis = t.getBoolean("lapis");
        if (t.contains("diamond")) d.diamond = t.getBoolean("diamond");
        if (t.contains("emerald")) d.emerald = t.getBoolean("emerald");
        if (t.contains("nether")) d.nether = t.getBoolean("nether");
        if (t.contains("debris")) d.debris = t.getBoolean("debris");
        if (t.contains("vein")) d.vein = t.getBoolean("vein");
        if (t.contains("chainLimit")) d.chainLimit = t.getInt("chainLimit");
        if (t.contains("correctTool")) d.correctTool = t.getBoolean("correctTool");
        if (t.contains("passthrough")) d.passthrough = t.getBoolean("passthrough");
        if (t.contains("passRadius")) d.passRadius = t.getInt("passRadius");
        if (t.contains("bridge")) d.bridge = t.getBoolean("bridge");
        if (t.contains("continuousScan")) d.continuousScan = t.getBoolean("continuousScan");
        // 丢弃废石 - 兼容旧存档 dropJunk boolean
        if (t.contains("dropMode")) {
            d.dropMode = t.getInt("dropMode");
        } else if (t.contains("dropJunk")) {
            d.dropMode = t.getBoolean("dropJunk") ? 1 : 0;
        }
        if (t.contains("junkKeepAmount")) d.junkKeepAmount = t.getInt("junkKeepAmount");
        if (t.contains("junk_stone")) d.junk_stone = t.getBoolean("junk_stone");
        if (t.contains("junk_cobblestone")) d.junk_cobblestone = t.getBoolean("junk_cobblestone");
        if (t.contains("junk_deepslate")) d.junk_deepslate = t.getBoolean("junk_deepslate");
        if (t.contains("junk_cobbled_deepslate")) d.junk_cobbled_deepslate = t.getBoolean("junk_cobbled_deepslate");
        if (t.contains("junk_granite")) d.junk_granite = t.getBoolean("junk_granite");
        if (t.contains("junk_diorite")) d.junk_diorite = t.getBoolean("junk_diorite");
        if (t.contains("junk_andesite")) d.junk_andesite = t.getBoolean("junk_andesite");
        if (t.contains("junk_tuff")) d.junk_tuff = t.getBoolean("junk_tuff");
        if (t.contains("junk_gravel")) d.junk_gravel = t.getBoolean("junk_gravel");
        if (t.contains("junk_dirt")) d.junk_dirt = t.getBoolean("junk_dirt");
        if (t.contains("junk_netherrack")) d.junk_netherrack = t.getBoolean("junk_netherrack");
        if (t.contains("junk_blackstone")) d.junk_blackstone = t.getBoolean("junk_blackstone");
        if (t.contains("junk_basalt")) d.junk_basalt = t.getBoolean("junk_basalt");
        if (t.contains("junk_soul_sand")) d.junk_soul_sand = t.getBoolean("junk_soul_sand");
        if (t.contains("junk_soul_soil")) d.junk_soul_soil = t.getBoolean("junk_soul_soil");
        if (t.contains("junk_magma_block")) d.junk_magma_block = t.getBoolean("junk_magma_block");
        if (t.contains("junk_calcite")) d.junk_calcite = t.getBoolean("junk_calcite");
        if (t.contains("junk_dripstone_block")) d.junk_dripstone_block = t.getBoolean("junk_dripstone_block");
        if (t.contains("junk_sandstone")) d.junk_sandstone = t.getBoolean("junk_sandstone");
        return d;
    }

    public static class Data implements IConfigSetter {
        // 矿物开关
        public boolean coal;
        public boolean iron;
        public boolean copper;
        public boolean gold;
        public boolean redstone;
        public boolean lapis;
        public boolean diamond;
        public boolean emerald;
        public boolean nether;
        public boolean debris;
        /** 连锁挖矿 */
        public boolean vein;
        /** 连锁挖矿单次上限（8-48） */
        public int chainLimit;
        /** 仅正确工具 */
        public boolean correctTool;
        /** 穿透寻找 */
        public boolean passthrough;
        /** 穿透/搜索半径（1-16） */
        public int passRadius;
        /** 自动搭路 */
        public boolean bridge;
        /**
         * 持续检测（漂移模式）。仅一键连锁（vein=true）时生效。
         * <ul>
         *   <li>false（默认，锚定模式）：先挖完首次探测锚定范围内的所有目标，再重新探测。稳定不漂移。</li>
         *   <li>true（漂移模式）：探测中心跟随女仆当前位置，走到哪从哪探测，快速推进但会「越挖越远」，
         *       等价于 1.1.0 的声波跟随行为——保留给不计损失追求效率的玩家。</li>
         * </ul>
         */
        public boolean continuousScan;

        // ===== 丢弃废石系统 =====
        /** 丢弃模式：0=关闭, 1=丢弃多余(保留junkKeepAmount), 2=丢弃全部 */
        public int dropMode;
        /** 丢弃多余时每种废石的保留量（0-64） */
        public int junkKeepAmount;
        // 废石白名单开关（per-maid 可配）
        public boolean junk_stone;
        public boolean junk_cobblestone;
        public boolean junk_deepslate;
        public boolean junk_cobbled_deepslate;
        public boolean junk_granite;
        public boolean junk_diorite;
        public boolean junk_andesite;
        public boolean junk_tuff;
        public boolean junk_gravel;
        public boolean junk_dirt;
        public boolean junk_netherrack;
        public boolean junk_blackstone;
        public boolean junk_basalt;
        public boolean junk_soul_sand;
        public boolean junk_soul_soil;
        public boolean junk_magma_block;
        public boolean junk_calcite;
        public boolean junk_dripstone_block;
        public boolean junk_sandstone;


        /** 废石白名单 key 列表（与字段名对应），供 GUI 和行为遍历。 */
        public static final String[] JUNK_KEYS = {
                "junk_stone", "junk_cobblestone", "junk_deepslate", "junk_cobbled_deepslate",
                "junk_granite", "junk_diorite", "junk_andesite", "junk_tuff",
                "junk_gravel", "junk_dirt", "junk_netherrack", "junk_blackstone",
                "junk_basalt", "junk_soul_sand", "junk_soul_soil", "junk_magma_block",
                "junk_calcite", "junk_dripstone_block", "junk_sandstone"
        };

        /** 获取指定废石开关的值 */
        public boolean getJunkFlag(String key) {
            switch (key) {
                case "junk_stone": return junk_stone;
                case "junk_cobblestone": return junk_cobblestone;
                case "junk_deepslate": return junk_deepslate;
                case "junk_cobbled_deepslate": return junk_cobbled_deepslate;
                case "junk_granite": return junk_granite;
                case "junk_diorite": return junk_diorite;
                case "junk_andesite": return junk_andesite;
                case "junk_tuff": return junk_tuff;
                case "junk_gravel": return junk_gravel;
                case "junk_dirt": return junk_dirt;
                case "junk_netherrack": return junk_netherrack;
                case "junk_blackstone": return junk_blackstone;
                case "junk_basalt": return junk_basalt;
                case "junk_soul_sand": return junk_soul_sand;
                case "junk_soul_soil": return junk_soul_soil;
                case "junk_magma_block": return junk_magma_block;
                case "junk_calcite": return junk_calcite;
                case "junk_dripstone_block": return junk_dripstone_block;
                case "junk_sandstone": return junk_sandstone;
                default: return false;
            }
        }

        /** 废石 key 对应的方块注册名（minecraft:xxx） */
        public static String junkKeyToBlockId(String key) {
            return "minecraft:" + key.substring("junk_".length());
        }

        public static Data getDefault() {
            Data d = new Data();
            d.coal = true;
            d.iron = true;
            d.copper = true;
            d.gold = true;
            d.redstone = true;
            d.lapis = true;
            d.diamond = true;
            d.emerald = true;
            d.nether = true;
            d.debris = true;
            d.vein = true;
            d.chainLimit = 24;
            d.correctTool = true;
            d.passthrough = true;
            d.passRadius = 8;
            d.bridge = true;
            // 持续检测默认关：锚定模式稳定不漂移，追求效率的玩家可手动开启
            d.continuousScan = false;
            // 丢弃废石默认：丢弃多余，保留16
            d.dropMode = 1;
            d.junkKeepAmount = 16;
            d.junk_stone = true;
            d.junk_cobblestone = true;
            d.junk_deepslate = true;
            d.junk_cobbled_deepslate = true;
            d.junk_granite = true;
            d.junk_diorite = true;
            d.junk_andesite = true;
            d.junk_tuff = true;
            d.junk_gravel = true;
            d.junk_dirt = true;
            d.junk_netherrack = true;
            d.junk_blackstone = true;
            d.junk_basalt = true;
            d.junk_soul_sand = true;
            d.junk_soul_soil = true;
            d.junk_magma_block = true;
            d.junk_calcite = true;
            d.junk_dripstone_block = true;
            d.junk_sandstone = true;
            return d;
        }

        @Override
        public void setConfigValue(String name, String value) {
            if ("passRadius".equals(name)) {
                try {
                    int r = Integer.parseInt(value);
                    passRadius = Math.max(1, Math.min(8, r));
                } catch (NumberFormatException ignored) {
                }
                return;
            }
            if ("dropMode".equals(name)) {
                try {
                    int m = Integer.parseInt(value);
                    dropMode = Math.max(0, Math.min(2, m));
                } catch (NumberFormatException ignored) {
                }
                return;
            }
            if ("junkKeepAmount".equals(name)) {
                try {
                    int k = Integer.parseInt(value);
                    junkKeepAmount = Math.max(0, Math.min(64, k));
                } catch (NumberFormatException ignored) {
                }
                return;
            }
            if ("chainLimit".equals(name)) {
                try {
                    int c = Integer.parseInt(value);
                    chainLimit = Math.max(8, Math.min(48, c));
                } catch (NumberFormatException ignored) {
                }
                return;
            }
            boolean v = Boolean.parseBoolean(value);
            switch (name) {
                case "coal": coal = v; break;
                case "iron": iron = v; break;
                case "copper": copper = v; break;
                case "gold": gold = v; break;
                case "redstone": redstone = v; break;
                case "lapis": lapis = v; break;
                case "diamond": diamond = v; break;
                case "emerald": emerald = v; break;
                case "nether": nether = v; break;
                case "debris": debris = v; break;
                case "vein": vein = v; break;
                case "correctTool": correctTool = v; break;
                case "passthrough": passthrough = v; break;
                case "bridge": bridge = v; break;
                case "continuousScan": continuousScan = v; break;
                case "junk_stone": junk_stone = v; break;
                case "junk_cobblestone": junk_cobblestone = v; break;
                case "junk_deepslate": junk_deepslate = v; break;
                case "junk_cobbled_deepslate": junk_cobbled_deepslate = v; break;
                case "junk_granite": junk_granite = v; break;
                case "junk_diorite": junk_diorite = v; break;
                case "junk_andesite": junk_andesite = v; break;
                case "junk_tuff": junk_tuff = v; break;
                case "junk_gravel": junk_gravel = v; break;
                case "junk_dirt": junk_dirt = v; break;
                case "junk_netherrack": junk_netherrack = v; break;
                case "junk_blackstone": junk_blackstone = v; break;
                case "junk_basalt": junk_basalt = v; break;
                case "junk_soul_sand": junk_soul_sand = v; break;
                case "junk_soul_soil": junk_soul_soil = v; break;
                case "junk_magma_block": junk_magma_block = v; break;
                case "junk_calcite": junk_calcite = v; break;
                case "junk_dripstone_block": junk_dripstone_block = v; break;
                case "junk_sandstone": junk_sandstone = v; break;
            }
        }
    }
}
