#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成用于校验补丁的桩类（stub）与验证程序。

桩类只包含被 mod 字节码实际引用的成员（静态性取自 javap 的助记符），
用于让 JVM 能真正完成 链接 -> 校验 -> 初始化 -> 调用 的全过程。
"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))          # 仓库根
VERIFY = os.path.join(HERE, "verify")
STUBS = os.path.join(VERIFY, "stubs")

STUB_SOURCES = {

    "net/minecraft/client/gui/screens/Screen.java": """
package net.minecraft.client.gui.screens;

public class Screen {
    public int width;
    public int height;
    public Screen() { }
}
""",

    "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.java": """
package net.minecraft.client.gui.screens.inventory;

import net.minecraft.client.gui.screens.Screen;

public class AbstractContainerScreen extends Screen {
    protected int leftPos;
    protected int topPos;
    public AbstractContainerScreen() { }
}
""",

    "net/minecraft/client/gui/Gui.java": """
package net.minecraft.client.gui;

import net.minecraft.client.gui.screens.Screen;

public class Gui {
    public Screen current;
    public Screen screen() { return current; }
    public void setScreen(Screen screen) { current = screen; }
}
""",

    "net/minecraft/client/gui/GuiGraphicsExtractor.java": """
package net.minecraft.client.gui;

import org.joml.Matrix3x2fStack;

public class GuiGraphicsExtractor {
    public Matrix3x2fStack pose() { return new Matrix3x2fStack(); }
}
""",

    "net/minecraft/client/Minecraft.java": """
package net.minecraft.client;

import java.io.File;

public class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();
    public File gameDirectory = new File(System.getProperty("bedrockui.gamedir", "."));
    public net.minecraft.client.gui.Gui gui = new net.minecraft.client.gui.Gui();
    public net.minecraft.client.gui.screens.Screen lastScreen;
    public int setScreenCalls;
    public static Minecraft getInstance() { return INSTANCE; }

    /**
     * 26.3 的 Minecraft **没有** setScreen(Screen)，只有 setScreenAndShow(Screen)。
     * 这里必须与真实 API 一致：早期版本凭空造了一个 setScreen，
     * 于是 tickGui 里那句 getMethod("setScreen", ...) 在桩环境能过、在真机抛
     * NoSuchMethodException，缺陷被完全掩盖。
     * 真实的 setScreenAndShow 会转调 Gui.setScreen —— 这里照样实现，断言才有意义。
     */
    public void setScreenAndShow(net.minecraft.client.gui.screens.Screen screen) {
        lastScreen = screen;
        setScreenCalls++;
        gui.setScreen(screen);
    }
}
""",

    "org/joml/Matrix3x2fc.java": """
package org.joml;

public interface Matrix3x2fc {
}
""",

    "org/joml/Matrix3x2f.java": """
package org.joml;

public class Matrix3x2f implements Matrix3x2fc {
    public Matrix3x2f() { }
    public Matrix3x2f(Matrix3x2fc source) { }
    public Matrix3x2f(Matrix3x2f source) { }
    public Matrix3x2f translate(float x, float y) { return this; }
}
""",

    "org/joml/Matrix3x2fStack.java": """
package org.joml;

public class Matrix3x2fStack extends Matrix3x2f {
    public Matrix3x2fStack pushMatrix() { return this; }
    public Matrix3x2fStack popMatrix() { return this; }
}
""",

    "net/fabricmc/api/ClientModInitializer.java": """
package net.fabricmc.api;

public interface ClientModInitializer {
    void onInitializeClient();
}
""",

    "com/terraformersmc/modmenu/api/ModMenuApi.java": """
package com.terraformersmc.modmenu.api;

public interface ModMenuApi {
}
""",
}

HARNESS = r"""
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

public class VerifyHarness {

    static final String[] ALL = {
        "com.kurumi.bedrockui.BedrockUiAnimation",
        "com.kurumi.bedrockui.BedrockUiAnimation$CloseState",
        "com.kurumi.bedrockui.BedrockUiAnimationConfig",
        "com.kurumi.bedrockui.BedrockUiClientInitializer",
        "com.kurumi.bedrockui.BedrockUiConfigScreen",
        "com.kurumi.bedrockui.BedrockUiContainerController239",
        "com.kurumi.bedrockui.BedrockUiModMenu",
        "com.kurumi.bedrockui.ScreenAnimationBridge240",
        "com.kurumi.bedrockui.mixin234.BlitFadeMixin233",
        "com.kurumi.bedrockui.mixin234.ColoredRectFadeMixin233",
        "com.kurumi.bedrockui.mixin234.GuiItemStateMixin233",
        "com.kurumi.bedrockui.mixin234.GuiRendererItemFadeMixin233",
        "com.kurumi.bedrockui.mixin234.GuiSetScreenOpenMixin240",
        "com.kurumi.bedrockui.mixin234.ScreenLayersWrapMixin240",
        "com.kurumi.bedrockui.mixin234.TextFadeMixin233",
        "com.kurumi.bedrockui.mixin234.TiledBlitFadeMixin233",
    };

    static final String[] CORE = {
        "com.kurumi.bedrockui.BedrockUiContainerController239",
        "com.kurumi.bedrockui.BedrockUiAnimation",
        "com.kurumi.bedrockui.BedrockUiAnimationConfig",
    };

    public static void main(String[] args) throws Exception {
        URL[] urls = {
            new File(args[0]).toURI().toURL(),   // 补丁后的 mod 类
            new File(args[1]).toURI().toURL(),   // 桩类
        };
        URLClassLoader cl = new URLClassLoader(urls, VerifyHarness.class.getClassLoader());

        int loaded = 0;
        for (String name : ALL) {
            Class<?> c = Class.forName(name, false, cl);   // 加载：class 文件格式检查
            loaded++;
        }
        System.out.println("STEP1 LOAD  OK : " + loaded + "/" + ALL.length + " 个类通过格式检查");

        for (String name : CORE) {
            Class<?> c = Class.forName(name, true, cl);    // 初始化：链接 + 字节码校验
            System.out.println("STEP2 LINK  OK : " + c.getName()
                    + "  声明方法=" + c.getDeclaredMethods().length
                    + " 声明字段=" + c.getDeclaredFields().length);
        }

        Class<?> screenCls = Class.forName("net.minecraft.client.gui.screens.Screen", true, cl);
        Object screen = screenCls.getDeclaredConstructor().newInstance();
        Class<?> ctl = Class.forName("com.kurumi.bedrockui.BedrockUiContainerController239", true, cl);

        try {
            Method alpha = ctl.getMethod("currentAlphaForScreen", screenCls);
            Method shift = ctl.getMethod("currentShiftForScreen", screenCls);
            System.out.println("STEP3 CALL  OK : currentAlphaForScreen(Screen) -> " + alpha.invoke(null, screen));
            System.out.println("STEP3 CALL  OK : currentShiftForScreen(Screen) -> " + shift.invoke(null, screen));

            Method oldAlpha = ctl.getMethod("alpha", screenCls);
            Method oldShift = ctl.getMethod("shift", screenCls);
            System.out.println("STEP3 MATCH OK : alpha -> " + oldAlpha.invoke(null, screen)
                    + " , shift -> " + oldShift.invoke(null, screen));
        } catch (NoSuchMethodException e) {
            System.out.println("STEP3 SKIP     : 控制器缺少 " + e.getMessage().split("\\(")[0]
                    + "（这正是原版的缺陷，继续走真实渲染路径）");
        }

        // ---- 按真实时序模拟：打开 -> 渲染 -> 关闭 -> 延迟切屏 ------------------
        Class<?> containerCls = Class.forName(
                "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen", true, cl);
        Object container = containerCls.getDeclaredConstructor().newInstance();
        Class<?> guiCls = Class.forName("net.minecraft.client.gui.Gui", true, cl);
        Object gui = guiCls.getDeclaredConstructor().newInstance();
        Class<?> extractorCls = Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor", true, cl);
        Object extractor = extractorCls.getDeclaredConstructor().newInstance();
        Class<?> bridge = Class.forName("com.kurumi.bedrockui.ScreenAnimationBridge240", true, cl);
        Class<?> anim = Class.forName("com.kurumi.bedrockui.BedrockUiAnimation", true, cl);

        Method intercept = ctl.getMethod("interceptSetScreen", Object.class, screenCls);
        Method beginBackground = bridge.getMethod("beginBackground", screenCls);
        Method beginUi = bridge.getMethod("beginUi", screenCls, extractorCls);
        Method endUi = bridge.getMethod("endUi", screenCls, extractorCls);
        Method closeFinished = ctl.getMethod("closeFinished", screenCls);
        Method tickGui = ctl.getMethod("tickGui", Object.class);
        Method externalAlpha = anim.getMethod("currentExternalAlpha");

        System.out.println("STEP4 BEFORE   : isContainer=" + ctl.getMethod("isContainer", screenCls).invoke(null, container)
                + " active=" + ctl.getMethod("active").invoke(null)
                + " isBackground=" + ctl.getMethod("isBackground").invoke(null));

        boolean crashed = false;
        int failures = 0;
        try {
            // 打开：Gui.setScreen 拦截 -> 登记 OPEN -> 渲染背景层/内容层
            boolean cancelled = (Boolean) intercept.invoke(null, gui, container);
            failures += check("打开容器时不拦截原版切屏", !cancelled, "interceptSetScreen=" + cancelled);
            beginBackground.invoke(null, container);          // ← 原先在此抛 NoSuchMethodError
            System.out.println("STEP4 ALPHA    : beginBackground 后 externalAlpha=" + externalAlpha.invoke(null));
            beginUi.invoke(null, container, extractor);
            System.out.println("STEP4 UI       : beginUi 后 externalAlpha=" + externalAlpha.invoke(null)
                    + " , shift=" + ctl.getMethod("shift", screenCls).invoke(null, container));
            endUi.invoke(null, container, extractor);

            // 关闭：当前是容器、目标为 null -> 应拦截原版切屏并开始关闭动画
            java.lang.reflect.Field cur = guiCls.getDeclaredField("current");
            cur.set(gui, container);
            intercept.invoke(null, gui, container);
            boolean startedClose = (Boolean) intercept.invoke(null, gui, null);
            failures += check("关闭容器时拦截原版切屏", startedClose, "startedClose=" + startedClose);

            // 轮询等动画结束：时长是可配置的，写死 sleep 会让断言随时长变化而失效
            boolean finished = false;
            for (int i = 0; i < 60; i++) {
                if (Boolean.TRUE.equals(closeFinished.invoke(null, container))) {
                    finished = true;
                    break;
                }
                Thread.sleep(50);
            }
            failures += check("关闭动画在 3 秒内结束", finished, "closeFinished=" + finished);
            System.out.println("STEP5 PROGRESS : alpha=" + ctl.getMethod("alpha", screenCls).invoke(null, container)
                    + " shift=" + ctl.getMethod("shift", screenCls).invoke(null, container));

            Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", true, cl);
            Object mc = mcCls.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field last = mcCls.getDeclaredField("lastScreen");
            java.lang.reflect.Field calls = mcCls.getDeclaredField("setScreenCalls");
            Object mcGui = mcCls.getDeclaredField("gui").get(mc);
            java.lang.reflect.Field mcCurrent = guiCls.getDeclaredField("current");
            // 先摆成真实游戏里的状态：Minecraft.gui 正显示着这个容器界面。
            // 这样"切屏后它变成 null"才是一条有判别力的断言。
            mcCurrent.set(mcGui, container);

            tickGui.invoke(null, gui);                         // 动画结束 -> 反射补做真正的切屏

            int screenCalls = calls.getInt(mc);
            failures += check("延迟切屏恰好补做一次", screenCalls == 1,
                    "真实 setScreenAndShow 调用次数=" + screenCalls + " 目标=" + last.get(mc));
            // 查的是 Minecraft 自己那个 Gui：tickGui 走的是 Minecraft.getInstance()，
            // 不是测试里临时 new 出来的那个 Gui 实例。
            failures += check("补做切屏后 Minecraft.gui 的当前界面已置空",
                    mcCurrent.get(mcGui) == null, "gui.current=" + mcCurrent.get(mcGui));
            failures += check("补做切屏的目标确实是 null（关闭界面）", last.get(mc) == null,
                    "lastScreen=" + last.get(mc));
            float alphaAfter = ((Number) externalAlpha.invoke(null)).floatValue();
            failures += check("关闭结束后外部 alpha 复位为 1.0", Math.abs(alphaAfter - 1.0F) < 1.0e-4F,
                    "externalAlpha=" + alphaAfter);
        } catch (java.lang.reflect.InvocationTargetException e) {
            crashed = true;
            System.out.println("STEP4 PATH FAIL: " + e.getCause());
        }

        if (crashed) {
            System.out.println("RESULT: FAILED —— 该目录下的类存在未解析引用");
            System.exit(3);
        }
        if (failures > 0) {
            System.out.println("RESULT: FAILED —— " + failures + " 项断言未通过");
            System.exit(4);
        }
        System.out.println("ALL CHECKS PASSED");
    }

    /** 断言：不通过就记一笔，最后统一以非零退出码结束 */
    private static int check(String label, boolean ok, String detail) {
        System.out.printf("%-6s %-28s %s%n", ok ? "[OK]" : "[FAIL]", label, detail);
        return ok ? 0 : 1;
    }
}
"""


def main():
    for rel, src in STUB_SOURCES.items():
        path = os.path.join(STUBS, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(src.lstrip())
    harness = os.path.join(VERIFY, "VerifyHarness.java")
    os.makedirs(os.path.dirname(harness), exist_ok=True)   # 不再依赖上面的循环先建目录
    with open(harness, "w", encoding="utf-8", newline="") as fh:
        fh.write(HARNESS.lstrip())
    print("桩类与验证程序已生成：%s" % VERIFY)
    print("桩类数量：%d" % len(STUB_SOURCES))


if __name__ == "__main__":
    main()
