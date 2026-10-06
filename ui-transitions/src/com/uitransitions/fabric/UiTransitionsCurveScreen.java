package com.uitransitions.fabric;

import com.uitransitions.TransitionConfig;
import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * 曲线编辑界面：左边可拖拽的贝塞尔曲线图，右边是**背包开关动画**的预览。
 *
 * 预览贴的是原版背包界面的贴图（AbstractContainerScreen.INVENTORY_LOCATION），
 * 所以看到的就是实机里那块 UI 本身在做渐入 / 渐出，而不是抽象色块。
 *
 * 底部四个**原版滑块**是主要的修改方式：单击即可改值，触屏上也可靠
 * （图上拖拽依赖鼠标事件，在安卓这类触控设备上不一定送得到）。
 */
public final class UiTransitionsCurveScreen extends Screen {

    /** 编辑的是哪一段动画 */
    public enum Target {
        OPEN("渐入（打开界面）"),
        CLOSE("渐出（关闭界面）");

        private final String label;

        Target(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    /** 画图时上下各留出一点空间，这样带回弹过冲的曲线也画得下 */
    private static final float VIEW_MIN = -0.5F;
    private static final float VIEW_MAX = 1.5F;
    private static final int HANDLE_RADIUS = 5;
    private static final int GRAB_DISTANCE = 18;

    /** 原版背包贴图是 176x166，放在 256x256 的图里 */
    private static final int INV_W = 176;
    private static final int INV_H = 166;
    private static final float INV_TEX = 256.0F;

    private static final int COLOR_BG = 0xFF101418;
    private static final int COLOR_BORDER = 0xFF5A6470;
    private static final int COLOR_GRID = 0xFF2A3038;
    private static final int COLOR_DIAGONAL = 0xFF3A424C;
    private static final int COLOR_CURVE = 0xFF6FD08C;
    private static final int COLOR_HANDLE = 0xFFFFD166;
    private static final int COLOR_HANDLE_LINE = 0xFF7A6A3A;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_HINT = 0xFF9AA0A6;

    /** 兜底用的假界面配色：万一贴图没画出来，至少还是一块像背包的底板 */
    private static final int COLOR_PANEL = 0xFFC6C6C6;
    private static final int COLOR_SLOT = 0xFF8B8B8B;
    private static final int COLOR_DOLL = 0xFF6FA8DC;

    private final Screen parent;
    private final Target target;

    /** 正在编辑的控制点（x1,y1,x2,y2）；x 在 0..1，y 允许超出 */
    private float[] points = { 0.25F, 0.1F, 0.25F, 1.0F };

    /** 四个原版滑块：不依赖鼠标拖拽，点一下就能改值 */
    private final ValueSlider[] sliders = new ValueSlider[4];

    private int graphX;
    private int graphY;
    private int graphSize;
    private int previewX;
    private int previewY;
    private int previewWidth;
    private int previewHeight;

    /** 0 = 无，1 = 第一个控制点，2 = 第二个控制点 */
    private int dragging;

    /** 只为诊断：前几次点击记日志，避免刷屏 */
    private static int CLICK_LOGGED;

    private long startNanos;

    public UiTransitionsCurveScreen(Screen parent, Target target) {
        super(Component.literal("曲线编辑"));
        this.parent = parent;
        this.target = target;
        // 从当前方向已有的控制点起步，而不是每次都从默认值开始
        String current = target == Target.OPEN
                ? TransitionConfig.openCurveCustom()
                : TransitionConfig.closeCurveCustom();
        float[] parsed = TransitionConfig.parseBezier(current);
        this.points = new float[] { parsed[0], parsed[1], parsed[2], parsed[3] };
    }

    @Override
    protected void init() {
        this.startNanos = System.nanoTime();
        int margin = 16;
        int bottomBar = 30;

        // 左边一列：曲线图 + 四个滑块。右边：预览。
        int columnWidth = Math.max(96, Math.min(this.width / 4, 150));
        this.graphX = margin;
        this.graphY = 44;
        int sliderBlock = 4 * 21 + 12;                  // 四个滑块 + 与图之间的间距
        int available = this.height - bottomBar - this.graphY - sliderBlock - 14;
        this.graphSize = Math.max(56, Math.min(130, Math.min(columnWidth, available)));

        this.previewX = this.graphX + columnWidth + 14;
        this.previewY = this.graphY;
        this.previewWidth = Math.max(120, this.width - this.previewX - margin);
        this.previewHeight = Math.max(60, this.height - bottomBar - this.previewY - 18);

        buildSliders();

        int buttonWidth = 90;
        int gap = 8;
        int total = buttonWidth * 3 + gap * 2;
        int x = (this.width - total) / 2;
        int y = this.height - 24;
        addRenderableWidget(Button.builder(Component.literal("重置"), b -> {
            float[] def = TransitionConfig.parseBezier(TransitionConfig.DEFAULT_CUSTOM_BEZIER);
            this.points = new float[] { def[0], def[1], def[2], def[3] };
            syncSliders();
        }).bounds(x, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> {
            save();
            this.minecraft.setScreenAndShow(this.parent);
        }).bounds(x + buttonWidth + gap, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b ->
                this.minecraft.setScreenAndShow(this.parent))
                .bounds(x + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());
    }

    /**
     * 四个值各配一个**原版滑块**。
     *
     * 为什么一定要有：图上的拖拽依赖鼠标事件，而在触屏设备（Pojav/Zalith）上，
     * 事件合成跟鼠标并不一样 —— 实测出现过"鼠标移上去有高亮，但按下去毫无反应"。
     * 原版滑块点一下就会把值设到点击处（AbstractSliderButton.setValueFromMouse），
     * 触控和鼠标都可靠，而且这是每个模组都在用的控件。
     */
    private void buildSliders() {
        int y = this.graphY + this.graphSize + 14;
        int x = this.graphX;
        int w = Math.max(90, Math.min(this.width / 4, 150));
        for (int i = 0; i < 4; i++) {
            final int index = i;
            boolean isX = (i % 2 == 0);
            float min = isX ? 0.0F : VIEW_MIN;
            float max = isX ? 1.0F : VIEW_MAX;
            String label = new String[] { "P1 x", "P1 y", "P2 x", "P2 y" }[i];
            ValueSlider slider = new ValueSlider(x, y + i * 21, w, 20, label, min, max,
                    this.points[i], value -> this.points[index] = value);
            this.sliders[i] = slider;
            addRenderableWidget(slider);
        }
    }

    private void syncSliders() {
        for (int i = 0; i < 4; i++) {
            if (this.sliders[i] != null) {
                this.sliders[i].setFromModel(this.points[i]);
            }
        }
    }

    private void save() {
        String text = String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f",
                this.points[0], this.points[1], this.points[2], this.points[3]);
        if (this.target == Target.OPEN) {
            TransitionConfig.setOpenCurveCustom(text);
        } else {
            TransitionConfig.setCloseCurveCustom(text);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        extractor.centeredText(this.font,
                Component.literal("曲线编辑 —— " + this.target.label()), this.width / 2, 10, COLOR_TEXT);
        extractor.centeredText(this.font,
                Component.literal("拖动图上的黄色方块，或用左下角的滑块调整"),
                this.width / 2, 24, COLOR_HINT);

        drawGraph(extractor, mouseX, mouseY);
        drawPreview(extractor);
    }

    private void drawGraph(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        int x0 = this.graphX;
        int y0 = this.graphY;
        int x1 = x0 + this.graphSize;
        int y1 = y0 + this.graphSize;

        extractor.fill(x0, y0, x1, y1, COLOR_BG);
        // 注意：outline 是 (x, y, 宽, 高)，fill 是 (x0, y0, x1, y1) —— 两者语义不同
        extractor.outline(x0, y0, this.graphSize, this.graphSize, COLOR_BORDER);

        for (int i = 1; i < 4; i++) {
            int gx = x0 + this.graphSize * i / 4;
            int gy = y0 + this.graphSize * i / 4;
            extractor.verticalLine(gx, y0 + 1, y1 - 1, COLOR_GRID);
            extractor.horizontalLine(x0 + 1, x1 - 1, gy, COLOR_GRID);
        }
        // 对角线（匀速）参考
        for (int i = 0; i <= 40; i++) {
            float t = i / 40.0F;
            int px = toScreenX(t);
            int py = toScreenY(t);
            extractor.fill(px, py, px + 1, py + 1, COLOR_DIAGONAL);
        }

        drawCurve(extractor);

        // 控制点连线：必须画**线段**。
        // 早先这里写成 fill(x0, y0, px, py, 色) —— 那是画矩形不是画线，
        // 控制点一旦拉远，整块矩形就把曲线盖住了（用户截图里那块黄色就是它）。
        drawSegment(extractor, toScreenX(0.0F), toScreenY(0.0F),
                toScreenX(this.points[0]), toScreenY(this.points[1]), COLOR_HANDLE_LINE);
        drawSegment(extractor, toScreenX(this.points[2]), toScreenY(this.points[3]),
                toScreenX(1.0F), toScreenY(1.0F), COLOR_HANDLE_LINE);

        drawHandle(extractor, this.points[0], this.points[1],
                this.dragging == 1 || isNear(mouseX, mouseY, this.points[0], this.points[1]));
        drawHandle(extractor, this.points[2], this.points[3],
                this.dragging == 2 || isNear(mouseX, mouseY, this.points[2], this.points[3]));

        String state;
        if (this.dragging == 1) {
            state = "正在调整 P1";
        } else if (this.dragging == 2) {
            state = "正在调整 P2";
        } else {
            state = "P1 / P2 也可以点图挪动";
        }
        extractor.text(this.font,
                String.format(Locale.ROOT, "P1 %.2f,%.2f  P2 %.2f,%.2f",
                        this.points[0], this.points[1], this.points[2], this.points[3]),
                x0, y1 + 3, COLOR_TEXT);
        extractor.text(this.font, Component.literal(state), x0, y1 + 14,
                this.dragging != 0 ? COLOR_HANDLE : COLOR_HINT);
    }

    /**
     * 把曲线画成**连续的折线**，而不是一串小方块。
     *
     * 早先是每隔一点画一个 2x2 的方块，采样一稀就露出锯齿、看着发糊。
     * 现在逐列填充、把相邻采样点连起来，线是连续的，边缘也干净。
     */
    private void drawCurve(GuiGraphicsExtractor extractor) {
        int samples = Math.max(64, this.graphSize * 2);
        int prevX = Integer.MIN_VALUE;
        int prevY = 0;
        for (int i = 0; i <= samples; i++) {
            float t = i / (float) samples;
            float v = TransitionConfig.Curve.bezierEase(this.points, t);
            int px = toScreenX(t);
            int py = toScreenY(v);
            if (prevX == Integer.MIN_VALUE) {
                extractor.fill(px, py, px + 1, py + 1, COLOR_CURVE);
            } else if (px > prevX) {
                for (int x = prevX + 1; x <= px; x++) {
                    int y = prevY + Math.round((py - prevY) * (x - prevX) / (float) (px - prevX));
                    extractor.fill(x, y, x + 1, y + 1, COLOR_CURVE);
                }
            }
            prevX = px;
            prevY = py;
        }
    }

    private boolean isNear(double mouseX, double mouseY, float cx, float cy) {
        return distance(mouseX, mouseY, cx, cy) <= GRAB_DISTANCE;
    }

    /**
     * 画一条 1 像素宽的线段（逐点填充）。
     *
     * 注意 fill(x0, y0, x1, y1) 画的是**矩形**，不是两点之间的连线 ——
     * 拿它当连线用，两点一拉远就会糊掉一大片。
     */
    private void drawSegment(GuiGraphicsExtractor extractor, int x0, int y0, int x1, int y1, int color) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) {
            extractor.fill(x0, y0, x0 + 1, y0 + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x0 + Math.round(dx * (i / (float) steps));
            int y = y0 + Math.round(dy * (i / (float) steps));
            extractor.fill(x, y, x + 1, y + 1, color);
        }
    }

    private void drawHandle(GuiGraphicsExtractor extractor, float cx, float cy, boolean highlighted) {
        int px = toScreenX(cx);
        int py = toScreenY(cy);
        int r = highlighted ? HANDLE_RADIUS + 2 : HANDLE_RADIUS;
        if (highlighted) {
            // 外面再套一圈，鼠标扫过去就能看出"这个可以抓"
            extractor.fill(px - r - 2, py - r - 2, px + r + 2, py + r + 2, COLOR_HANDLE_LINE);
        }
        extractor.fill(px - r, py - r, px + r, py + r, COLOR_HANDLE);
    }

    private int toScreenX(float t) {
        return this.graphX + Math.round(t * this.graphSize);
    }

    private int toScreenY(float v) {
        float clamped = Math.max(VIEW_MIN, Math.min(VIEW_MAX, v));
        float ratio = (VIEW_MAX - clamped) / (VIEW_MAX - VIEW_MIN);
        return this.graphY + Math.round(ratio * this.graphSize);
    }

    private float fromScreenX(double mouseX) {
        return (float) ((mouseX - this.graphX) / (double) this.graphSize);
    }

    private float fromScreenY(double mouseY) {
        float ratio = (float) ((mouseY - this.graphY) / (double) this.graphSize);
        return VIEW_MAX - ratio * (VIEW_MAX - VIEW_MIN);
    }

    // ------------------------------------------------------------------ 预览：背包开关动画

    /**
     * 按**真实配置的时长与曲线**循环播放一次"打开背包 → 停一会儿 → 关闭背包"。
     *
     * 贴的是原版背包贴图，所以看到的就是实机那块 UI 本身在动。
     * 用真实时长而不是固定的演示时长：调完曲线想看看"500ms 到底是多快"时，
     * 这里给的就是实机的节奏。
     */
    private void drawPreview(GuiGraphicsExtractor extractor) {
        int x = this.previewX;
        int y = this.previewY;
        int w = this.previewWidth;
        int h = this.previewHeight;

        extractor.fill(x, y, x + w, y + h, COLOR_BG);
        extractor.outline(x, y, w, h, COLOR_BORDER);

        int openMs = Math.max(1, TransitionConfig.openDurationMs());
        int closeMs = Math.max(1, TransitionConfig.closeDurationMs());
        int holdMs = 550;

        long elapsed = (System.nanoTime() - this.startNanos) / 1_000_000L;
        int cycle = openMs + holdMs + closeMs;
        long phase = elapsed % cycle;

        float alpha;
        float slide;
        String phaseName;
        TransitionConfig.Curve openCurve = TransitionConfig.Curve.custom(this.points);
        TransitionConfig.Curve closeCurve = TransitionConfig.Curve.custom(this.points);
        if (phase < openMs) {
            float p = phase / (float) openMs;
            alpha = openCurve.easeOut(p);
            slide = 1.0F - alpha;
            phaseName = "渐入";
        } else if (phase < openMs + holdMs) {
            alpha = 1.0F;
            slide = 0.0F;
            phaseName = "保持";
        } else {
            float p = (phase - openMs - holdMs) / (float) closeMs;
            float closed = closeCurve.easeIn(p);
            alpha = 1.0F - closed;
            slide = closed;
            phaseName = "渐出";
        }

        // 统一交给模组的透明度通道：贴图和方块都会跟着淡
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
        int stageH = Math.max(30, h - 16);
        int maxSlide = Math.max(6, stageH / 6);
        int offset = Math.round(maxSlide * slide);

        UiTransitions.pushPreviewAlpha(alpha);
        try {
            int panelW = Math.min(w - 12, INV_W);
            int panelH = Math.min(stageH, INV_H);
            int px = x + (w - panelW) / 2;
            int py = y + 4 + (stageH - panelH) / 2 + offset;
            drawMockInventory(extractor, px, py, panelW, panelH, a);
            blitVanillaInventory(extractor, px, py, panelW, panelH);
        } finally {
            UiTransitions.popPreviewAlpha();
        }

        // 文字放在面板**外面**，不再压住画面
        extractor.text(this.font, Component.literal("预览：打开 / 关闭背包"), x + 4, y + 4, COLOR_TEXT);
        extractor.text(this.font,
                Component.literal(String.format(Locale.ROOT, "%s   透明度 %d%%   渐入 %dms / 渐出 %dms",
                        phaseName, Math.round(alpha * 100), openMs, closeMs)),
                x + 4, y + h - 12, COLOR_HINT);
    }

    /** 贴原版背包贴图：视觉上就是实机那块 UI */
    private void blitVanillaInventory(GuiGraphicsExtractor extractor, int x, int y, int w, int h) {
        try {
            extractor.blit(AbstractContainerScreen.INVENTORY_LOCATION,
                    x, y, w, h,
                    0.0F, 0.0F,
                    w / INV_TEX, h / INV_TEX);
        } catch (Throwable t) {
            // 贴图没画出来也不要紧：下面那层兜底底板还在
        }
    }

    /**
     * 兜底底板：照背包的样子用色块摆一块。
     *
     * 原版贴图万一在某个版本上贴不出来，这里至少还能看出"一块背包在淡入淡出"，
     * 不至于预览区一片空白。
     */
    private void drawMockInventory(GuiGraphicsExtractor extractor, int x, int y, int w, int h, int alpha) {
        if (alpha <= 1) {
            return;
        }
        int panel = withAlpha(COLOR_PANEL, alpha);
        extractor.fill(x, y, x + w, y + h, panel);
        extractor.outline(x, y, w, h, withAlpha(COLOR_BORDER, alpha));

        int dollW = Math.max(10, w / 7);
        int dollH = Math.max(14, h / 3);
        extractor.fill(x + 4, y + 4, x + 4 + dollW, y + 4 + dollH, withAlpha(COLOR_DOLL, alpha));

        int gridX = x + 4 + dollW + 6;
        int cell = Math.max(6, Math.min(12, (w - (gridX - x) - 8) / 9));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int sx = gridX + col * (cell + 1);
                int sy = y + h / 2 + row * (cell + 1);
                if (sx + cell > x + w - 3 || sy + cell > y + h - 3) {
                    continue;
                }
                extractor.fill(sx, sy, sx + cell, sy + cell, withAlpha(COLOR_SLOT, alpha));
            }
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    // ------------------------------------------------------------------ 拖拽

    /**
     * 点击图框内**任意位置**都会有反应：抓住最近的那个方块，并立刻挪过去。
     *
     * 不靠 mouseDragged：26.3 里 AbstractContainerEventHandler 没有实现 mouseClicked，
     * 拖动依赖 MouseHandler 的内部状态，不保证送达。按住之后改用 mouseMoved 跟踪。
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != 0) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (CLICK_LOGGED < 6) {
            CLICK_LOGGED++;
            System.out.println("[UI Transitions] 曲线界面收到左键点击 (" + Math.round(mx) + ","
                    + Math.round(my) + ")  图框=" + this.graphX + "," + this.graphY
                    + " 尺寸=" + this.graphSize + "  在图框内=" + isInsideGraph(mx, my));
        }
        if (!isInsideGraph(mx, my)) {
            return false;
        }
        int first = distance(mx, my, this.points[0], this.points[1]);
        int second = distance(mx, my, this.points[2], this.points[3]);
        this.dragging = first <= second ? 1 : 2;
        applyDrag(mx, my);
        return true;
    }

    /** 鼠标是不是在图框里（留几像素余量，贴着边框点也算） */
    private boolean isInsideGraph(double mouseX, double mouseY) {
        return mouseX >= this.graphX - 2 && mouseX <= this.graphX + this.graphSize + 2
                && mouseY >= this.graphY - 2 && mouseY <= this.graphY + this.graphSize + 2;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (this.dragging != 0) {
            applyDrag(mouseX, mouseY);
            return;
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.dragging != 0) {
            applyDrag(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.dragging != 0) {
            this.dragging = 0;
            return true;
        }
        return super.mouseReleased(event);
    }

    private void applyDrag(double mouseX, double mouseY) {
        // x 必须夹在 0..1：否则反解参数会失真，曲线会变得不可预期
        float cx = Math.max(0.0F, Math.min(1.0F, fromScreenX(mouseX)));
        float cy = Math.max(VIEW_MIN, Math.min(VIEW_MAX, fromScreenY(mouseY)));
        if (this.dragging == 1) {
            this.points[0] = cx;
            this.points[1] = cy;
        } else if (this.dragging == 2) {
            this.points[2] = cx;
            this.points[3] = cy;
        }
        syncSliders();      // 图上拖了，滑块跟着走
    }

    private int distance(double mouseX, double mouseY, float cx, float cy) {
        double dx = mouseX - toScreenX(cx);
        double dy = mouseY - toScreenY(cy);
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy));
    }

    /**
     * 一个值一个原版滑块。
     *
     * 抽象滑块点一下就会把值设到点击位置（内部走 setValueFromMouse），
     * 所以**单击就能改**，不需要"按住拖动"—— 这正是触屏上最可靠的做法。
     */
    private static final class ValueSlider extends net.minecraft.client.gui.components.AbstractSliderButton {

        private final String label;
        private final float min;
        private final float max;
        private final java.util.function.Consumer<Float> onChange;

        private ValueSlider(int x, int y, int width, int height, String label,
                            float min, float max, float value,
                            java.util.function.Consumer<Float> onChange) {
            super(x, y, width, height, Component.empty(), (value - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.onChange = onChange;
            updateMessage();
        }

        private float current() {
            if (this.max <= this.min) {
                return this.min;
            }
            return (float) (this.min + (this.max - this.min) * this.value);
        }

        private void setFromModel(float v) {
            if (this.max > this.min) {
                this.value = Math.max(0.0, Math.min(1.0, (v - this.min) / (this.max - this.min)));
            }
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            // 构造期间父类也会调到这里，那时字段还没赋值 —— 必须挡住
            if (this.label == null || this.max <= this.min) {
                setMessage(Component.empty());
                return;
            }
            setMessage(Component.literal(String.format(Locale.ROOT, "%s  %.2f", this.label, current())));
        }

        @Override
        protected void applyValue() {
            if (this.onChange != null) {
                this.onChange.accept(current());
            }
        }
    }
}
