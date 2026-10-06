package com.uitransitions;

/**
 * 启动期的依赖检查：缺了必需的前置就直接让游戏崩，并把原因写清楚。
 *
 * 为什么宁可直接崩：这个模组的配置界面完全建立在 Cloth Config 之上。
 * 缺了它却"静默降级"的话，用户看到的是"模组装了但配置按钮点不开/什么都没有"，
 * 会以为是模组的 bug，然后来回报错 —— 不如在启动时就用一段能直接照做的说明拦住。
 *
 * 注意两个加载器的 mod id 不一样（Fabric 用连字符、NeoForge 用下划线），
 * 检测时两个都要认，否则会把"装了"误判成"没装"。
 */
public final class DependencyCheck {

    /** Fabric 侧的 Cloth Config mod id */
    public static final String CLOTH_ID_FABRIC = "cloth-config";
    /** NeoForge 侧的 Cloth Config mod id（见它自己的 neoforge.mods.toml） */
    public static final String CLOTH_ID_NEOFORGE = "cloth_config";

    /** 检测到"装没装"时要认的全部 id */
    public static final String[] CLOTH_IDS = { CLOTH_ID_FABRIC, CLOTH_ID_NEOFORGE };

    private DependencyCheck() {
    }

    /**
     * 拼出可直接照做的崩溃说明。
     *
     * 刻意把"你在哪个加载器上、缺的是什么、去哪装、不想装怎么办"四件事都写全 ——
     * 崩溃界面是用户唯一能看到的信息，写不清楚等于没写。
     */
    public static String missingClothMessage(String loaderName) {
        String separator = "=".repeat(64);
        return String.join("\n",
                "",
                separator,
                " Bedrock UI Animations 无法启动：缺少必需的依赖 Cloth Config",
                separator,
                " 加载器      : " + loaderName,
                " 需要的 mod  : Fabric = " + CLOTH_ID_FABRIC + " / NeoForge = " + CLOTH_ID_NEOFORGE,
                " 当前状态    : 没有检测到它",
                "",
                " 为什么会这样：本模组的配置界面完全由 Cloth Config 提供，",
                "               缺少它就无法工作，所以这里直接停下，而不是装作没事。",
                "",
                " 怎么解决：",
                "   1) 安装 Cloth Config（选与你加载器对应的版本）：",
                "      https://modrinth.com/mod/cloth-config",
                "   2) 装好后重新启动游戏。",
                "",
                " 不想装 Cloth Config？那就请删掉 Bedrock UI Animations。",
                separator);
    }

    /**
     * 打印并抛出。抛在模组构造/初始化里 → 加载器会把它显示成启动失败，
     * 消息原文会出现在崩溃报告与日志最显眼的位置。
     */
    public static void failMissingCloth(String loaderName) {
        String message = missingClothMessage(loaderName);
        System.err.println(message);
        throw new IllegalStateException(message);
    }
}
