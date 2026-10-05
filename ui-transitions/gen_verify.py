#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 UI Transitions 的状态机验证工程（桩类 + 验证程序）。

桩类只包含被 mod 字节码真正引用的成员；矩阵栈会记录 push/pop/translate，
以便验证"位移确实发生了、动画结束后确实不再介入"。
"""

import os

ROOT = r"D:\Program Files (x86)\deepseekHarness\Project\verify-uit"
STUBS = os.path.join(ROOT, "stubs")

STUB_SOURCES = {
    "net/minecraft/client/gui/screens/Screen.java": """
package net.minecraft.client.gui.screens;

public class Screen {
    public int width;
    public int height;
}
""",
    "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.java": """
package net.minecraft.client.gui.screens.inventory;

import net.minecraft.client.gui.screens.Screen;

public class AbstractContainerScreen extends Screen {
    protected int leftPos;
    protected int topPos;
}
""",
    "net/minecraft/client/gui/Gui.java": """
package net.minecraft.client.gui;

import net.minecraft.client.gui.screens.Screen;

public class Gui {
    public Screen current;

    public Screen screen() {
        return this.current;
    }

    public void setScreen(Screen screen) {
        this.current = screen;
    }
}
""",
    "net/minecraft/client/gui/GuiGraphicsExtractor.java": """
package net.minecraft.client.gui;

import org.joml.Matrix3x2fStack;

public class GuiGraphicsExtractor {
    public final Matrix3x2fStack pose = new Matrix3x2fStack();

    public Matrix3x2fStack pose() {
        return this.pose;
    }
}
""",
    "net/minecraft/client/Minecraft.java": """
package net.minecraft.client;

import java.io.File;

public class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();

    public File gameDirectory = new File(System.getProperty("uitransitions.gamedir", "."));
    public net.minecraft.client.gui.Gui gui = new net.minecraft.client.gui.Gui();

    public static Minecraft getInstance() {
        return INSTANCE;
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
    public Matrix3x2f() {
    }

    public Matrix3x2f(Matrix3x2fc source) {
    }

    public Matrix3x2f(Matrix3x2f source) {
    }

    public Matrix3x2f translate(float x, float y) {
        Matrix3x2fStack.lastTranslateX = x;
        Matrix3x2fStack.lastTranslateY = y;
        return this;
    }
}
""",
    "org/joml/Matrix3x2fStack.java": """
package org.joml;

/** 记录调用情况，供验证程序断言 */
public class Matrix3x2fStack extends Matrix3x2f {
    public static int pushCount;
    public static int popCount;
    public static float lastTranslateX;
    public static float lastTranslateY;

    public static void reset() {
        pushCount = 0;
        popCount = 0;
        lastTranslateX = 0.0F;
        lastTranslateY = 0.0F;
    }

    public Matrix3x2fStack pushMatrix() {
        pushCount++;
        return this;
    }

    public Matrix3x2fStack popMatrix() {
        popCount++;
        return this;
    }

    @Override
    public Matrix3x2f translate(float x, float y) {
        lastTranslateX = x;
        lastTranslateY = y;
        return this;
    }
}
""",
}

HARNESS = r"""
import com.uitransitions.TransitionConfig;
import com.uitransitions.UiTransitions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.joml.Matrix3x2fStack;

import java.io.File;
import java.nio.file.Files;

public class VerifyTransitions {

    private static int failures;

    public static void main(String[] args) throws Exception {
        System.out.println("=== UI Transitions 状态机验证 ===");

        Gui gui = Minecraft.getInstance().gui;
        GuiGraphicsExtractor extractor = new GuiGraphicsExtractor();
        AbstractContainerScreen container = new EmptyContainer();
        Screen plain = new Screen();

        // ---------- 1) 配置 ----------
        TransitionConfig.ensureLoaded();
        File configFile = new File(Minecraft.getInstance().gameDirectory,
                "config" + File.separator + "ui-transitions.properties");
        check("配置文件已生成", configFile.isFile(), configFile.getPath());
        String text = Files.readString(configFile.toPath());
        check("配置含 enabled 键", text.contains("enabled=true"), "enabled=true");

        // ---------- 2) 只对容器界面生效 ----------
        check("容器界面参与动画", UiTransitions.shouldAnimate(container), "true");
        check("普通界面默认不参与", !UiTransitions.shouldAnimate(plain), "false");

        // ---------- 3) 打开动画 ----------
        boolean cancelled = UiTransitions.interceptSetScreen(gui, container);
        check("打开容器时不拦截原版切屏", !cancelled, "false");
        gui.setScreen(container);                       // 模拟原版完成切屏

        Matrix3x2fStack.reset();
        UiTransitions.beginContentLayer(container, extractor);
        float openShift = Matrix3x2fStack.lastTranslateY;
        int openColor = UiTransitions.applyAlpha(0xFFFFFFFF);
        check("打开时压栈一次", Matrix3x2fStack.pushCount == 1, "pushCount=" + Matrix3x2fStack.pushCount);
        check("打开时向下偏移(自下而上滑入)", openShift > 100.0F && openShift <= 120.0F, "shift=" + openShift);
        check("打开时内容接近全透明", (openColor >>> 24) <= 12, "alpha=" + (openColor >>> 24));
        UiTransitions.endContentLayer(container, extractor);
        check("结束时弹栈一次", Matrix3x2fStack.popCount == 1, "popCount=" + Matrix3x2fStack.popCount);

        // ---------- 4) 动画结束后零介入 ----------
        Thread.sleep(420);
        Matrix3x2fStack.reset();
        UiTransitions.beginContentLayer(container, extractor);
        check("动画播完后不再压栈（闲置零开销）", Matrix3x2fStack.pushCount == 0,
                "pushCount=" + Matrix3x2fStack.pushCount);
        check("动画播完后透明度恢复", (UiTransitions.applyAlpha(0xFFFFFFFF) >>> 24) == 255, "alpha=255");
        UiTransitions.endContentLayer(container, extractor);

        // ---------- 5) 关闭动画 + 延迟切屏 ----------
        boolean intercepted = UiTransitions.interceptSetScreen(gui, null);
        check("关闭容器时拦下原版切屏", intercepted, "true");
        check("此时界面仍是原容器界面", gui.screen() == container, "同一个实例");

        Matrix3x2fStack.reset();
        UiTransitions.beginContentLayer(container, extractor);
        float closeShiftStart = Matrix3x2fStack.lastTranslateY;
        check("关闭起始位移接近 0", Math.abs(closeShiftStart) < 10.0F, "shift=" + closeShiftStart);
        UiTransitions.endContentLayer(container, extractor);

        Thread.sleep(420);
        Matrix3x2fStack.reset();
        UiTransitions.beginContentLayer(container, extractor);
        float closeShiftEnd = Matrix3x2fStack.lastTranslateY;
        int closeAlpha = UiTransitions.applyAlpha(0xFFFFFFFF) >>> 24;
        check("关闭结束时向下偏移到位", closeShiftEnd > 100.0F, "shift=" + closeShiftEnd);
        check("关闭结束时接近全透明", closeAlpha <= 12, "alpha=" + closeAlpha);
        UiTransitions.endContentLayer(container, extractor);

        UiTransitions.tick(gui);
        check("tick 补做切屏（界面已关闭）", gui.screen() == null,
                "screen=" + (gui.screen() == null ? "null" : gui.screen().getClass().getSimpleName()));

        // ---------- 6) 物品透明度通道 ----------
        Object itemState = new Object();
        UiTransitions.interceptSetScreen(gui, container);
        gui.setScreen(container);
        UiTransitions.beginContentLayer(container, extractor);
        UiTransitions.tagItem(itemState);               // 物品在提取阶段被登记
        UiTransitions.endContentLayer(container, extractor);
        UiTransitions.beginItemSubmit(itemState);       // 提交阶段套用
        int itemAlpha = UiTransitions.applyAlpha(0xFFFFFFFF) >>> 24;
        check("物品在提交阶段套用动画透明度", itemAlpha < 200, "alpha=" + itemAlpha);
        UiTransitions.endItemSubmit(itemState);
        check("提交结束后透明度复位", (UiTransitions.applyAlpha(0xFFFFFFFF) >>> 24) == 255, "alpha=255");

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println("FAILED: " + failures + " 项未通过");
            System.exit(3);
        }
    }

    private static void check(String label, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-6s %-34s %s%n", ok ? "[OK]" : "[FAIL]", label, detail);
    }

    /** 真实环境里 AbstractContainerScreen 是泛型抽象类，这里给个最小实现（桩类未加泛型） */
    static class EmptyContainer extends AbstractContainerScreen {
    }
}
"""


def main():
    for rel, src in STUB_SOURCES.items():
        path = os.path.join(STUBS, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(src.lstrip())
    with open(os.path.join(ROOT, "VerifyTransitions.java"), "w", encoding="utf-8") as fh:
        fh.write(HARNESS.lstrip())
    print("桩类 %d 个 + 验证程序已生成 -> %s" % (len(STUB_SOURCES), ROOT))


if __name__ == "__main__":
    main()
