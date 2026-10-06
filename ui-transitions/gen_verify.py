#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 UI Transitions 的状态机验证工程（桩类 + 验证程序）。

桩类只包含被 mod 字节码真正引用的成员；矩阵栈会记录 push/pop/translate，
以便验证"位移确实发生了、动画结束后确实不再介入"。

注意：默认**不覆盖已存在的文件**（桩类里有手工补充的内容），只补缺失的；
想强制按模板重写用 --force，想只检查是否漂移用 --check。
"""

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))          # <仓库>/ui-transitions
ROOT = os.path.join(os.path.dirname(HERE), "verify-uit")   # <仓库>/verify-uit
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

/** 真实环境里是 AbstractContainerScreen<T extends AbstractContainerMenu>；这里只要泛型形状一致 */
public class AbstractContainerScreen<T> extends Screen {
    protected int leftPos;
    protected int topPos;
}
""",
    # 跨维度时的加载界面。模组按**简单类名**识别它，所以存根放哪个包都能命中 ——
    # 这正是我们要锁住的行为：它不是容器界面，必须被单独放行。
    "net/minecraft/client/gui/screens/LevelLoadingScreen.java": """
package net.minecraft.client.gui.screens;

/** 与 26.3 一致：带一个 reason 字段（NETHER_PORTAL / END_PORTAL / OTHER） */
public class LevelLoadingScreen extends Screen {
    public enum Reason { NETHER_PORTAL, END_PORTAL, OTHER }

    private Reason reason;

    public LevelLoadingScreen(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return this.reason;
    }
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

    /** 供跨维度遮罩计算全屏尺寸 */
    public int guiWidth = 1920;
    public int guiHeight = 1080;
    public int lastFillColor;

    public Matrix3x2fStack pose() {
        return this.pose;
    }

    public int guiWidth() {
        return this.guiWidth;
    }

    public int guiHeight() {
        return this.guiHeight;
    }

    public void fill(int x0, int y0, int x1, int y1, int color) {
        this.lastFillColor = color;
    }

    public void outline(int x, int y, int width, int height, int color) {
        this.lastFillColor = color;
    }

    public boolean scissorEnabled;
    public int scissorDisables;

    public void enableScissor(int x0, int y0, int x1, int y1) {
        this.scissorEnabled = true;
    }

    /** 跨维度遮罩画之前会先清掉残留裁剪区，否则全屏填充会被裁成一块方框 */
    public void disableScissor() {
        this.scissorEnabled = false;
        this.scissorDisables++;
    }
}
""",
    "net/minecraft/client/Minecraft.java": """
package net.minecraft.client;

import java.io.File;

public class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();

    /** 默认落到临时目录：断言跑起来不该改写仓库里的 config/ */
    public File gameDirectory = new File(System.getProperty("uitransitions.gamedir",
            System.getProperty("java.io.tmpdir") + File.separator + "uitransitions-verify"));
    public net.minecraft.client.gui.Gui gui = new net.minecraft.client.gui.Gui();
    public MouseHandler mouseHandler = new MouseHandler();

    public static Minecraft getInstance() {
        return INSTANCE;
    }

    /** allowLookDuringClose 分支会调用 grabMouse() */
    public static class MouseHandler {
        public void grabMouse() {
        }
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
    /** 与真实 JOML 一致：平移分量存在 m20 / m21 */
    private float m20;
    private float m21;

    public Matrix3x2f() {
    }

    public Matrix3x2f(Matrix3x2fc source) {
        if (source instanceof Matrix3x2f) {
            this.m20 = ((Matrix3x2f) source).m20;
            this.m21 = ((Matrix3x2f) source).m21;
        }
    }

    public Matrix3x2f(Matrix3x2f source) {
        this.m20 = source.m20;
        this.m21 = source.m21;
    }

    public Matrix3x2f translate(float x, float y) {
        this.m20 += x;
        this.m21 += y;
        Matrix3x2fStack.lastTranslateX = x;
        Matrix3x2fStack.lastTranslateY = y;
        return this;
    }

    public float m20() {
        return this.m20;
    }

    public float m21() {
        return this.m21;
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
    # 模拟 JEI 这类物品管理器的界面，用来验证 extraScreens 的前缀匹配
    "mezz/jei/TestScreen.java": """
package mezz.jei;

import net.minecraft.client.gui.screens.Screen;

/** 桩类：模拟 JEI 这类物品管理器的界面，用来验证 extraScreens 前缀匹配 */
public class TestScreen extends Screen {
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

        // 断言里的等待时长依赖动画时长，所以这里显式钉死它，
        // 免得以后调整默认值（比如 300 -> 500）把断言弄成偶发失败。
        final int DURATION_MS = 300;
        TransitionConfig.setDurationMsBoth(DURATION_MS);
        final long WAIT_MS = DURATION_MS + 150L;        // 留够余量等动画播完

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
        int openColor = UiTransitions.applyAlphaBlit(0xFFFFFFFF);
        check("打开时压栈一次", Matrix3x2fStack.pushCount == 1, "pushCount=" + Matrix3x2fStack.pushCount);
        check("打开时向下偏移(自下而上滑入)", openShift > 100.0F && openShift <= 120.0F, "shift=" + openShift);
        // 阈值放宽到 90：这条量的是"动画刚起步时的透明度"，而启动动画与检查之间
        // 会隔着几毫秒的 JIT/调度抖动（实测 alpha 在 0~13 之间浮动，卡在 12 会偶发失败）。
        // 90 仍然抓得住真问题：没淡（255）或者用错下限（158）。
        check("打开起始时内容明显透明", (openColor >>> 24) <= 90, "alpha=" + (openColor >>> 24));
        UiTransitions.endContentLayer(container, extractor);
        check("结束时弹栈一次", Matrix3x2fStack.popCount == 1, "popCount=" + Matrix3x2fStack.popCount);

        // ---------- 4) 动画结束后零介入 ----------
        Thread.sleep(WAIT_MS);
        Matrix3x2fStack.reset();
        UiTransitions.beginContentLayer(container, extractor);
        check("动画播完后不再压栈（闲置零开销）", Matrix3x2fStack.pushCount == 0,
                "pushCount=" + Matrix3x2fStack.pushCount);
        // 复位时机在"整帧结束"——内容层之后紧接着还要画物品提示框，它也得跟着界面一起淡，
        // 所以先补一次 endScreenFrame 模拟上一帧的收尾
        UiTransitions.endScreenFrame();
        check("动画播完后透明度恢复", (UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24) == 255, "alpha=255");
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

        Thread.sleep(WAIT_MS);
        Matrix3x2fStack.reset();
        UiTransitions.beginContentLayer(container, extractor);
        float closeShiftEnd = Matrix3x2fStack.lastTranslateY;
        int closeAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
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
        int itemAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        check("物品在提交阶段套用动画透明度", itemAlpha < 200, "alpha=" + itemAlpha);
        UiTransitions.endItemSubmit(itemState);
        check("提交结束后透明度复位", (UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24) == 255, "alpha=255");

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

    /** 桩类里的 AbstractContainerScreen 是泛型的，给个最小实现 */
    static class EmptyContainer extends AbstractContainerScreen<Object> {
    }
}
"""


def collect_targets():
    """返回 [(路径, 期望内容), ...]"""
    targets = []
    for rel, src in STUB_SOURCES.items():
        targets.append((os.path.join(STUBS, rel.replace("/", os.sep)), src.lstrip()))
    targets.append((os.path.join(ROOT, "VerifyTransitions.java"), HARNESS.lstrip()))
    return targets


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="按模板覆盖已存在的文件")
    parser.add_argument("--check", action="store_true",
                        help="不写任何文件，只报告是否存在漂移（有漂移则退出码 1）")
    args = parser.parse_args()

    created, same, differs, rewrote = [], [], [], []
    for path, content in collect_targets():
        if not os.path.isfile(path):
            if args.check:
                differs.append(path)
                continue
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8", newline="") as fh:
                fh.write(content)
            created.append(path)
            continue
        with open(path, encoding="utf-8") as fh:
            current = fh.read()
        if current == content:
            same.append(path)
        elif args.force and not args.check:
            with open(path, "w", encoding="utf-8", newline="") as fh:
                fh.write(content)
            rewrote.append(path)
        else:
            differs.append(path)

    if args.check:
        if differs:
            print("verify-uit 与模板存在漂移：")
            for p in differs:
                print("   %s" % os.path.relpath(p, ROOT))
            return 1
        print("verify-uit 与模板一致（%d 个文件）" % len(same))
        return 0

    print("verify-uit: 新建 %d，一致 %d，保留（有差异）%d，已重写 %d"
          % (len(created), len(same), len(differs), len(rewrote)))
    for label, group in (("created", created), ("rewrote", rewrote), ("differs", differs)):
        for p in group:
            print("  %-8s %s" % (label, os.path.relpath(p, ROOT)))
    if differs:
        print("\n上面标 differs 的文件与模板不一致，已保留原样（可能是手工补过的）；"
              "确认要按模板重写就加 --force。")
    print("-> %s" % ROOT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
