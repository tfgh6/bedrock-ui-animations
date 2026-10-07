package com.uitransitions;

/**
 * 桩类：`ModNames` 在离线链里的替身（永远是"解析不出来"）。
 *
 * 真身走反射探测 Fabric/NeoForge 的模组列表 —— 而离线这一关**两个加载器都不在**，
 * 所以它本来就会返回 null。桩在这里只是让编译通过，行为与真实环境一致。
 *
 * 有意义的验证是"解析不出来时怎么办"：调用方必须能退回显示包名，
 * **排除功能本身绝不能依赖能不能解析出模组名**。这条由 VerifyAdvanced 断言。
 */
public final class ModNames {

    private ModNames() {
    }

    public static String modNameFor(String className) {
        return null;
    }

    public static void resetCache() {
    }
}
