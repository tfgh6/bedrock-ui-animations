package com.uitransitions;

import java.util.Map;

/**
 * 原版界面的**友好名** —— 把 `CreativeModeInventoryScreen` 显示成"创造物品栏界面"。
 *
 * 用户反馈过两件相关的事：
 *   1. "原版排除界面，你要标注好是哪个界面，直标注成创造UI界面、聊天框UI这样子"；
 *   2. "你不能只把包名列出来，要读到模组的名字"。
 *
 * 这两条是同一件事的两面：**列表要让人认得出来那是哪个界面**。
 *
 * <h2>为什么用类名做键，而不是 import 那些界面类</h2>
 *
 * 一是共享层不能依赖具体界面类（版本变动会让编译不过）；
 * 二是排除列表里可能出现**运行期并不存在**的类名（别的版本/别的模组留下的配置），
 * 那种情况下也要能显示，而 import 就意味着必须有那个类。
 *
 * <h2>加一条新界面的成本</h2>
 *
 * 往 {@link #NAMES} 加一行。**不需要改任何逻辑** —— 认不出来的会退回类名，
 * 所以这里漏掉某个界面只是"少显示一个中文名"，不会坏。
 */
public final class ScreenLabels {

    private ScreenLabels() {
    }

    /**
     * 原版界面类名 → 友好名。
     *
     * 键用**简单类名的子串**（不做全限定名匹配）：原版的包结构在版本间挪动过
     * （例如 `inventory` 包是后来拆出去的），只匹配简单名更耐用。
     */
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("CreativeModeInventoryScreen", "创造物品栏界面"),
            Map.entry("ChatScreen", "聊天输入界面"),
            Map.entry("PauseScreen", "游戏菜单（暂停）"),
            Map.entry("InventoryScreen", "生存背包界面"),
            Map.entry("CraftingScreen", "工作台界面"),
            Map.entry("ChestScreen", "箱子界面"),
            Map.entry("ShulkerBoxScreen", "潜影盒界面"),
            Map.entry("BarrelScreen", "木桶界面"),
            Map.entry("FurnaceScreen", "熔炉界面"),
            Map.entry("BlastFurnaceScreen", "高炉界面"),
            Map.entry("SmokerScreen", "烟熏炉界面"),
            Map.entry("HopperScreen", "漏斗界面"),
            Map.entry("DispenserScreen", "发射器界面"),
            Map.entry("DropperScreen", "投掷器界面"),
            Map.entry("BrewingStandScreen", "酿造台界面"),
            Map.entry("EnchantmentScreen", "附魔台界面"),
            Map.entry("AnvilScreen", "铁砧界面"),
            Map.entry("GrindstoneScreen", "砂轮界面"),
            Map.entry("LoomScreen", "织布机界面"),
            Map.entry("CartographyTableScreen", "制图台界面"),
            Map.entry("StonecutterScreen", "切石机界面"),
            Map.entry("SmithingScreen", "锻造台界面"),
            Map.entry("BeaconScreen", "信标界面"),
            Map.entry("MerchantScreen", "村民交易界面"),
            Map.entry("HorseInventoryScreen", "马匹背包界面"),
            Map.entry("CrafterScreen", "合成器界面"),
            Map.entry("ShieldScreen", "盾牌界面"),
            Map.entry("BookViewScreen", "成书界面"),
            Map.entry("BookEditScreen", "书与笔界面"),
            Map.entry("SignEditScreen", "告示牌编辑界面"),
            Map.entry("HangingSignEditScreen", "悬挂告示牌编辑界面"),
            Map.entry("DeathScreen", "死亡界面"),
            Map.entry("OptionsScreen", "选项界面"),
            Map.entry("VideoSettingsScreen", "视频设置界面"),
            Map.entry("SoundOptionsScreen", "声音设置界面"),
            Map.entry("ControlsScreen", "按键设置界面"),
            Map.entry("LanguageSelectScreen", "语言选择界面"),
            Map.entry("ShareToLanScreen", "对局域网开放界面"),
            Map.entry("SocialInteractionsScreen", "社交界面"),
            Map.entry("AdvancementsScreen", "进度界面"),
            Map.entry("StatisticsScreen", "统计界面"),
            Map.entry("SelectWorldScreen", "选择世界界面"),
            Map.entry("CreateWorldScreen", "创建世界界面"),
            Map.entry("WorldSelectionList", "世界列表"),
            Map.entry("DisconnectedScreen", "连接断开界面"),
            Map.entry("ConnectScreen", "连接中界面"),
            Map.entry("ReceivingLevelScreen", "正在接收世界数据"),
            Map.entry("LevelLoadingScreen", "世界加载界面"),
            Map.entry("ProgressScreen", "进度条界面"),
            Map.entry("GenericMessageScreen", "通用提示界面"),
            Map.entry("ErrorScreen", "错误提示界面"),
            Map.entry("TitleScreen", "主菜单"),
            Map.entry("AccessibilityOptionsScreen", "无障碍设置界面"),
            Map.entry("PackSelectionScreen", "资源包选择界面"),
            Map.entry("DatapackLoadFailureScreen", "数据包加载失败界面"),
            Map.entry("ConfirmScreen", "确认对话框"),
            Map.entry("NoticeScreen", "提示对话框"),
            Map.entry("PresetEditorScreen", "世界预设编辑界面"),
            Map.entry("DebugOptionsScreen", "调试选项界面"),
            Map.entry("GameModeSwitcherScreen", "游戏模式切换轮盘"),
            Map.entry("SpectatorMenu", "旁观者菜单"),
            Map.entry("ContainerScreen", "容器界面（基类）"),
            Map.entry("AbstractContainerScreen", "容器界面（基类）"),
            Map.entry("AbstractInventoryScreen", "带效果栏的容器界面（基类）"),
            Map.entry("AbstractRecipeBookScreen", "带配方书的容器界面（基类）")
    );

    /**
     * 友好名。认不出来返回 null —— 调用方自己决定退回什么（类名/短名）。
     */
    public static String friendlyName(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        if (!className.startsWith("net.minecraft.")) {
            // 模组的界面不给编中文名：它们有自己的名字，硬编会过期得很快
            return null;
        }
        String simple = shortName(className);
        String direct = NAMES.get(simple);
        if (direct != null) {
            return direct;
        }
        // 原版里不少界面叫 XxxXxxScreen / XxxMenu，退化到"去掉 Screen 后缀"再试一次
        for (Map.Entry<String, String> entry : NAMES.entrySet()) {
            if (simple.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 认不出来的原版界面显示成"原版界面 · 类名"，至少让人知道它是原版的 */
    public static String displayName(String className) {
        String friendly = friendlyName(className);
        if (friendly != null) {
            return friendly;
        }
        if (className != null && className.startsWith("net.minecraft.")) {
            return "原版界面 · " + shortName(className);
        }
        return null;
    }

    private static String shortName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
