package com.uitransitions.fabric;

import com.uitransitions.TransitionConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * 曲线编辑界面：左边可拖拽的贝塞尔曲线图，右边是**背包开关动画**的预览。
 *
 * 预览不是抽象色块，而是照着背包界面的样子摆的：底板 + 物品格 + 玩家小模型，
 * 按真实的渐入/渐出时长与曲线循环播放。这样调出来的手感就是实机的手感。
 *
 * 图上两个控制点可以直接拖。"看到的"和"存下来的"用的是同一段求值代码
 * （{@link TransitionConfig.Curve#bezierEase}），不会出现"预览好看、实机不对"。
 *
 * 点「完成」会把这条曲线存到当前方向，并把该方向切到 custom。
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
    /** 画出来的方块半径（GUI 单位，实际观感还要乘界面缩放） */
    private static final int HANDLE_RADIUS = 5;
    /** 离方块多近算"抓住它"；超出这个距离也照样响应，只是改成"把最近的移过来" */
    private static final int GRAB_DISTANCE = 18;

    private static final int COLOR_BG = 0xFF101418;
    private static final int COLOR_BORDER = 0xFF5A6470;
    private static final int COLOR_GRID = 0xFF2A3038;
    private static final int COLOR_DIAGONAL = 0xFF3A424C;
    private static final int COLOR_CURVE = 0xFF6FD08C;
    private static final int COLOR_HANDLE = 0xFFFFD166;
    private static final int COLOR_HANDLE_LINE = 0xFF7A6A3A;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_HINT = 0xFF9AA0A6;

    /** 预览里那块"背包"的配色，尽量贴近原版 */
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
        int margin = 20;
        int buttonRow = 26;
        // 图 + 四个滑块要竖着排下来，所以图不能再占满高度
        int size = Math.min(120, Math.max(70, Math.min(this.width / 2 - margin - 10,
                this.height - buttonRow - 150)));
        this.graphSize = size;
        this.graphX = margin;
        this.graphY = 46;

        this.previewX = this.graphX + this.graphSize + 22;
        this.previewY = this.graphY;
        this.previewWidth = Math.max(110, this.width - this.previewX - margin);
        this.previewHeight = Math.max(70, this.height - this.previewY - buttonRow - 76);

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
        int y = this.graphY + this.graphSize + 18;
        int x = this.graphX;
        int w = Math.max(80, this.graphSize);
        for (int i = 0; i < 4; i++) {
            final int index = i;
            boolean isX = (i % 2 == 0);
            float min = isX ? 0.0F : VIEW_MIN;
            float max = isX ? 1.0F : VIEW_MAX;
            String label = new String[] { "P1 x", "P1 y", "P2 x", "P2 y" }[i];
            ValueSlider slider = new ValueSlider(x, y + i * 22, w, 20, label, min, max,
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
                Component.literal("曲线编辑 —— " + this.target.label()),
                this.width / 2, 14, COLOR_TEXT);
        extractor.centeredText(this.font,
                Component.literal("在图框里任意位置点一下，最近的那个方块就会移过去；按住拖动即可微调"),
                this.width / 2, 28, COLOR_HINT);

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

        // 控制点连线
        extractor.fill(toScreenX(0.0F), toScreenY(0.0F), toScreenX(this.points[0]), toScreenY(this.points[1]),
                COLOR_HANDLE_LINE);
        extractor.fill(toScreenX(1.0F), toScreenY(1.0F), toScreenX(this.points[2]), toScreenY(this.points[3]),
                COLOR_HANDLE_LINE);

        // 曲线本体：逐点取样画小方块，用的是与实机同一段求值代码
        for (int i = 0; i <= 120; i++) {
            float t = i / 120.0F;
            float v = TransitionConfig.Curve.bezierEase(this.points, t);
            int px = toScreenX(t);
            int py = toScreenY(v);
            extractor.fill(px, py, px + 2, py + 2, COLOR_CURVE);
        }

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
            state = "把鼠标移到图上，点一下就能把最近的方块放过去";
        }
        extractor.text(this.font,
                String.format(Locale.ROOT, "P1 %.2f, %.2f    P2 %.2f, %.2f",
                        this.points[0], this.points[1], this.points[2], this.points[3]),
                x0, y1 + 6, COLOR_TEXT);
        extractor.text(this.font, Component.literal(state), x0, y1 + 18,
                this.dragging != 0 ? COLOR_HANDLE : COLOR_HINT);
    }

    private boolean isNear(double mouseX, double mouseY, float cx, float cy) {
        return distance(mouseX, mouseY, cx, cy) <= GRAB_DISTANCE;
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
        extractor.text(this.font, Component.literal("预览：打开 / 关闭背包"),
                x + 6, y + 5, COLOR_TEXT);

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

        int innerX = x + 8;
        int innerY = y + 18;
        int innerW = w - 16;
        int innerH = h - 40;
        // 位移按预览区高度缩放，最多走 1/4 屏，够看出方向又不至于跑出框
        int maxSlide = Math.max(6, innerH / 4);
        int offset = Math.round(maxSlide * slide);
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));

        drawMockInventory(extractor, innerX, innerY + offset, innerW, innerH, a);

        extractor.text(this.font,
                Component.literal(String.format(Locale.ROOT, "%s  透明度 %d%%", phaseName, Math.round(alpha * 100))),
                x + 6, y + h - 14, COLOR_HINT);
        extractor.text(this.font,
                Component.literal(String.format(Locale.ROOT, "渐入 %dms / 渐出 %dms", openMs, closeMs)),
                x + 6, y + h - 26, COLOR_HINT);
    }

    /** 照背包的样子画一块底板：物品格 + 玩家模型位，整体按 alpha 淡、按 offset 移 */
    private void drawMockInventory(GuiGraphicsExtractor extractor, int x, int y, int w, int h, int alpha) {
        if (alpha <= 1) {
            return;
        }
        int panel = withAlpha(COLOR_PANEL, alpha);
        extractor.fill(x, y, x + w, y + h, panel);
        extractor.outline(x, y, w, h, withAlpha(COLOR_BORDER, alpha));

        // 玩家小模型的位置（左侧那一块）
        int dollW = Math.max(12, w / 7);
        int dollH = Math.max(16, h / 2);
        extractor.fill(x + 6, y + 6, x + 6 + dollW, y + 6 + dollH, withAlpha(COLOR_DOLL, alpha));

        // 3 x 9 的物品格
        int gridX = x + 6 + dollW + 8;
        int cell = Math.max(7, Math.min(14, (w - (gridX - x) - 12) / 9));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int sx = gridX + col * (cell + 1);
                int sy = y + h / 2 + row * (cell + 1);
                if (sx + cell > x + w - 4) {
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
     * 点击图上的**任意位置**都会有反应。
     *
     * 之前是"必须点中那个小方块才能拖"，点在图上别的地方什么都不会发生 ——
     * 用起来就像"点了没反应"。现在改成：
     *   · 点在方块附近      → 抓住它，继续拖
     *   · 点在图上其它位置  → 把**最近的那个**方块挪到点击处，并顺势抓住
     * 无论点哪里，曲线都会立刻跟着变，反馈是确定的。
     *
     * 另外这里**不靠 mouseDragged**：26.3 里 AbstractContainerEventHandler 没有实现
     * mouseClicked，拖动依赖 MouseHandler 的内部状态，mouseDragged 不保证送到。
     * 所以按住之后改用 mouseMoved 跟踪（那个是无条件送达的）。
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
        // 前几次点击记一行日志：万一"点了还是没反应"，看日志就能分清是
        // "点击根本没送到这个界面"还是"送到了但没命中图框"，不用再来回猜
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
        // 抓住离得近的那个；点在图上别处时，同样是把最近的那个挪过来 —— 行为一致
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

    private int distance(double mouseX, double mouseY, float cx, float cy) {
        double dx = mouseX - toScreenX(cx);
        double dy = mouseY - toScreenY(cy);
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy));
    }
}
