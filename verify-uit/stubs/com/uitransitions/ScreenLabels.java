package com.uitransitions;

/**
 * 桩类：`ScreenLabels` 在离线链里的替身。
 *
 * <h2>为什么这两张表只能给桩，而不是编译真身</h2>
 *
 * `tools/run_verify.py` **故意没有 classpath**（用桩类替换 MC 类型），而
 * `ScreenLabels` 本身虽然零 MC 依赖，但**真正的价值在它那张表的内容**，
 * 而表的内容只能靠"跑起来看它返回什么"来验 —— 在这一关里没有意义。
 *
 * 所以这一关里它返回 null，从而只能验证**调用方拿到了 null 会怎么走**
 * （也就是"解析不出来时不崩、退回显示包名"这条路）。
 *
 * <h2>那真身由谁验</h2>
 *
 * `visualtest` 的 `hub` 阶段会真的打开排除界面并 dump 控件树，
 * 那里能看到实际显示出来的文字。**离线断言不假装替它验过表的内容。**
 */
public final class ScreenLabels {

    private ScreenLabels() {
    }

    public static String friendlyName(String className) {
        return null;
    }

    public static String displayName(String className) {
        return null;
    }
}
