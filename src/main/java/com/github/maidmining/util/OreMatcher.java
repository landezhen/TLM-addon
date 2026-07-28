package com.github.maidmining.util;

import com.github.maidmining.config.MaidMiningConfigData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 矿物分类判定。把方块映射到一个类别（coal/iron/.../debris），
 * 再结合女仆的 per-maid 配置决定是否采集。
 *
 * <p><b>分类结果按 Block 缓存</b>：一个 Block 的类别在运行期恒定不变（判定只依赖注册名），
 * 但原实现每次调用都要查一次注册表、取一次 path 字符串、再跑最多九次
 * {@code String.contains}。这个函数在两条热路径上被高频调用：</p>
 * <ul>
 *   <li>客户端 {@code OreScanCache} 每 0.5 秒重扫锚点框，半径 8 时单次 4913 格；</li>
 *   <li>服务端目标搜索与穿透射线判定，每 tick 数百到数千次。</li>
 * </ul>
 * <p>缓存把这些调用摊薄成一次哈希查表，是本模组性能优化的第一刀。</p>
 */
public final class OreMatcher {

    private OreMatcher() {
    }

    public enum OreType {
        COAL, IRON, COPPER, GOLD, REDSTONE, LAPIS, DIAMOND, EMERALD, NETHER, DEBRIS, NONE
    }

    /**
     * Block → 分类结果缓存。用 {@link ConcurrentHashMap} 而非 IdentityHashMap：
     * 渲染线程与服务端线程会同时读写（单机自带集成服务端），必须线程安全。
     * Block 实例在注册期建好后全局唯一且不再变动，容量上界即注册表方块数，不会无限膨胀。
     */
    private static final Map<Block, OreType> CLASSIFY_CACHE = new ConcurrentHashMap<>();

    /**
     * Block → 是否可开路废石 的缓存，理由同 {@link #CLASSIFY_CACHE}。
     * 该判定在穿透射线里逐格调用，同属热路径。
     */
    private static final Map<Block, Boolean> PASS_STONE_CACHE = new ConcurrentHashMap<>();

    /**
     * 清空缓存。资源重载（{@code /reload}）或维度切换时注册表理论上不变，
     * 故常规游戏流程无需调用；保留此入口供调试与未来动态注册场景使用。
     */
    public static void clearCaches() {
        CLASSIFY_CACHE.clear();
        PASS_STONE_CACHE.clear();
    }

    /** 方块的精确注册名（如 minecraft:iron_ore）；空气/未注册返回空串。用于连锁"同种"判定。 */
    public static String blockId(BlockState state) {
        if (state == null || state.isAir()) {
            return "";
        }
        ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return rl == null ? "" : rl.toString();
    }

    /**
     * 把方块归类。识别原版矿物（含深板岩变种），并通过名字关键字兼容模组矿物。
     *
     * <p>结果按 Block 缓存，重复调用只付一次哈希查表。空气快速返回，不进缓存
     * （空气方块只有一个实例，判定本身就是一次 {@code isAir()}，缓存反而多一层）。</p>
     */
    public static OreType classify(BlockState state) {
        if (state == null || state.isAir()) {
            return OreType.NONE;
        }
        Block block = state.getBlock();
        OreType cached = CLASSIFY_CACHE.get(block);
        if (cached != null) {
            return cached;
        }
        OreType computed = computeClassify(block);
        CLASSIFY_CACHE.put(block, computed);
        return computed;
    }

    /** 真正的分类计算，只在每个 Block 首次出现时跑一遍。 */
    private static OreType computeClassify(Block block) {
        ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(block);
        if (rl == null) {
            return OreType.NONE;
        }
        String path = rl.getPath();
        // 只认"矿石"类方块，避免误挖（名字需含 ore 或为远古残骸）
        boolean looksLikeOre = path.contains("ore") || path.contains("ancient_debris");
        if (!looksLikeOre) {
            return OreType.NONE;
        }
        if (path.contains("ancient_debris")) return OreType.DEBRIS;
        if (path.contains("coal")) return OreType.COAL;
        if (path.contains("iron")) return OreType.IRON;
        if (path.contains("copper")) return OreType.COPPER;
        if (path.contains("redstone")) return OreType.REDSTONE;
        if (path.contains("lapis")) return OreType.LAPIS;
        if (path.contains("diamond")) return OreType.DIAMOND;
        if (path.contains("emerald")) return OreType.EMERALD;
        if (path.contains("quartz")) return OreType.NETHER;
        if (path.contains("gold")) {
            // nether_gold_ore 归入下界类，其余金矿归金类
            return path.contains("nether") ? OreType.NETHER : OreType.GOLD;
        }
        return OreType.NONE;
    }

    /** 该方块是否被这只女仆的配置选中采集。 */
    public static boolean isEnabledOre(EntityMaid maid, BlockState state) {
        OreType type = classify(state);
        if (type == OreType.NONE) {
            return false;
        }
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);
        switch (type) {
            case COAL: return d.coal;
            case IRON: return d.iron;
            case COPPER: return d.copper;
            case GOLD: return d.gold;
            case REDSTONE: return d.redstone;
            case LAPIS: return d.lapis;
            case DIAMOND: return d.diamond;
            case EMERALD: return d.emerald;
            case NETHER: return d.nether;
            case DEBRIS: return d.debris;
            default: return false;
        }
    }

    /**
     * 是否为"可开路废石"——女仆为接触矿物，允许挖掉路径上的这类方块。
     * 仅放行常见的地下填充岩石，避免误挖箱子、机器等功能方块。
     *
     * <p>结果按 Block 缓存。这个判定在穿透射线里对路径上每一格都要跑一次，
     * 原实现每次都做注册表查询加一串 switch 字符串比较，是热路径上的隐形开销。</p>
     */
    public static boolean isPassThroughStone(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        Block block = state.getBlock();
        Boolean cached = PASS_STONE_CACHE.get(block);
        if (cached != null) {
            return cached;
        }
        boolean computed = computePassThroughStone(block);
        PASS_STONE_CACHE.put(block, computed);
        return computed;
    }

    /** 真正的废石判定，只在每个 Block 首次出现时跑一遍。 */
    private static boolean computePassThroughStone(Block block) {
        ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(block);
        if (rl == null) {
            return false;
        }
        String path = rl.getPath();
        switch (path) {
            // 主世界
            case "stone":
            case "cobblestone":
            case "deepslate":
            case "cobbled_deepslate":
            case "dirt":
            case "gravel":
            case "andesite":
            case "diorite":
            case "granite":
            case "tuff":
            case "calcite":
            case "smooth_basalt":
            case "dripstone_block":
            case "sandstone":
            case "infested_stone":
            case "infested_deepslate":
            // 下界
            case "netherrack":
            case "basalt":
            case "blackstone":
            case "soul_sand":
            case "soul_soil":
            case "magma_block":
                return true;
            default:
                return false;
        }
    }

    /**
     * 物品是否可用作搭路材料：对应方块为可放置的废石/泥土类（复用 isPassThroughStone 列表）。
     */
    public static boolean isBridgeMaterial(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        return isPassThroughStone(blockItem.getBlock().defaultBlockState());
    }
}