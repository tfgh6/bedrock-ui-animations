package com.uitransitions;

/**
 * 启动期的依赖检查：缺了必需的前置就直接让游戏崩，并把原因写清楚。
 *
 * 为什么宁可直接崩：这些前置缺了以后，用户看到的是"模组装了但配置按钮点不开/什么都没有"，
 * 会以为是模组的 bug，然后来回报错 —— 不如在启动时就用一段能直接照做的说明拦住。
 *
 * 注意两个加载器的 mod id 不一样（Fabric 用连字符、NeoForge 用下划线），
 * 检测时两个都要认，否则会把"装了"误判成"没装"。
 */
public final class DependencyCheck {

    // ------------------------------------------------------------------ Cloth Config

    /** Fabric 侧的 Cloth Config mod id */
    public static final String CLOTH_ID_FABRIC = "cloth-config";
    /** NeoForge 侧的 Cloth Config mod id（见它自己的 neoforge.mods.toml） */
    public static final String CLOTH_ID_NEOFORGE = "cloth_config";
    public static final String[] CLOTH_IDS = { CLOTH_ID_FABRIC, CLOTH_ID_NEOFORGE };
    public static final String CLOTH_NAME = "Cloth Config";
    public static final String CLOTH_URL = "https://modrinth.com/mod/cloth-config";

    // ------------------------------------------------------------------ ModMenu（仅 Fabric）

    /** ModMenu 只在 Fabric 上有：它提供模组列表里的那个「配置」按钮 */
    public static final String[] MODMENU_IDS = { "modmenu" };
    public static final String MODMENU_NAME = "Mod Menu";
    public static final String MODMENU_URL = "https://modrinth.com/mod/modmenu";

    private DependencyCheck() {
    }

    /**
     * 拼出可直接照做的崩溃说明。
     *
     * 刻意把"你在哪个加载器上、缺的是什么、去哪装、不想装怎么办"四件事都写全 ——
     * 崩溃界面是用户唯一能看到的信息，写不清楚等于没写。
     *
     * 这段发生在资源加载之前，**用不上语言文件**，所以直接写成中英双语：
     * 无论玩家的游戏语言是什么，两段里总有一段是他看得懂的。
     */
    public static String missingMessage(String loaderName, String depName,
                                        String[] ids, String url) {
        String separator = "=".repeat(64);
        String idList = String.join(" / ", ids);
        return String.join("\n",
                "",
                separator,
                " Bedrock UI Animations 无法启动：缺少必需的依赖 " + depName,
                " Bedrock UI Animations cannot start: missing required dependency " + depName,
                separator,
                " 加载器 / Loader : " + loaderName,
                " 需要的 mod / Required mod : " + idList,
                " 当前状态 / Status : 没有检测到它 / not detected",
                "",
                " 为什么会这样：本模组的配置界面依赖它，缺少就无法使用，",
                "               所以这里直接停下，而不是装作没事。",
                " Why: this mod's config screen is built on it. Instead of silently",
                "      degrading, we stop here so the reason is obvious.",
                "",
                " 怎么解决 / How to fix：",
                "   1) 安装 " + depName + "（选与你加载器对应的版本）",
                "      Install " + depName + " (pick the build for your loader):",
                "      " + url,
                "   2) 装好后重新启动游戏 / Then restart the game.",
                "",
                " 不想装？那就请删掉 Bedrock UI Animations。",
                " Do not want it? Then please remove Bedrock UI Animations.",
                separator);
    }

    /**
     * 打印并抛出。抛在模组构造/初始化里 → 加载器会把它显示成启动失败，
     * 消息原文会出现在崩溃报告与日志最显眼的位置。
     */
    public static void failMissing(String loaderName, String depName, String[] ids, String url) {
        String message = missingMessage(loaderName, depName, ids, url);
        System.err.println(message);
        throw new IllegalStateException(message);
    }
}
