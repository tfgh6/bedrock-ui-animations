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
