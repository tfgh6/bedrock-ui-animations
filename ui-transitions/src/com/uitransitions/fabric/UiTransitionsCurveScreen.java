package com.uitransitions.fabric;

import com.uitransitions.TransitionConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * 曲线编辑界面：左边一张可拖拽的三次贝塞尔曲线图，右边是渐入 / 渐出的动画示例。
 *
 * 图上两个控制点可以直接拖 —— 拖动时右边的示例会立刻按新曲线重放，
 * "看到的"和"存下来的"用的是同一段求值代码（{@link TransitionConfig.Curve#bezierEase}），
 * 所以不会出现"预览好看、实机不对"的偏差。
 *
 * 点「完成」会把这条曲线存到当前方向（渐入或渐出），并把该方向切到 custom；
 * 两个方向各有一份控制点，互不影响。
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
    private static final int HANDLE_RADIUS = 4;
    private static final int GRAB_DISTANCE = 12;

    private static final int COLOR_BG = 0xFF101418;
    private static final int COLOR_BORDER = 0xFF5A6470;
    private static final int COLOR_GRID = 0xFF2A3038;
    private static final int COLOR_DIAGONAL = 0xFF3A424C;
    private static final int COLOR_CURVE = 0xFF6FD08C;
    private static final int COLOR_HANDLE = 0xFFFFD166;
    private static final int COLOR_HANDLE_LINE = 0xFF7A6A3A;
    private static final int COLOR_PREVIEW = 0xFFB9C4D0;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_HINT = 0xFF9AA0A6;

    private final Screen parent;
    private final Target target;

    /** 正在编辑的控制点（x1,y1,x2,y2）；x 在 0..1，y 允许超出 */
    private float[] points = { 0.25F, 0.1F, 0.25F, 1.0F };

    private int graphX;
    private int graphY;
    private int graphSize;
    private int previewX;
    private int previewWidth;

    /** 0 = 无，1 = 第一个控制点，2 = 第二个控制点 */
    private int dragging;

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
        int margin = 24;
        int size = Math.min(170, Math.max(90, Math.min(this.width / 3, this.height - 120)));
        this.graphSize = size;
        this.graphX = margin;
        this.graphY = 44;
        this.previewX = this.graphX + this.graphSize + 28;
        this.previewWidth = Math.max(80, this.width - this.previewX - margin);

        int y = this.height - 28;
        int buttonWidth = 90;
        int gap = 8;
        int total = buttonWidth * 3 + gap * 2;
        int x = (this.width - total) / 2;
        addRenderableWidget(Button.builder(Component.literal("重置"), b -> {
            float[] def = TransitionConfig.parseBezier(TransitionConfig.DEFAULT_CUSTOM_BEZIER);
            this.points = new float[] { def[0], def[1], def[2], def[3] };
        }).bounds(x, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> {
            save();
            this.minecraft.setScreenAndShow(this.parent);
        }).bounds(x + buttonWidth + gap, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b ->
                this.minecraft.setScreenAndShow(this.parent)).bounds(x + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());
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
        // 画之前先让父类把按钮等控件铺好
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        extractor.centeredText(this.font,
                Component.literal("曲线编辑 —— " + this.target.label() + "（拖动画布上的圆点）"),
                this.width / 2, 16, COLOR_TEXT);

        drawGraph(extractor);
        drawPreview(extractor, mouseX, mouseY);
    }

    private void drawGraph(GuiGraphicsExtractor extractor) {
        int x0 = this.graphX;
        int y0 = this.graphY;
        int x1 = x0 + this.graphSize;
        int y1 = y0 + this.graphSize;

        extractor.fill(x0, y0, x1, y1, COLOR_BG);
        extractor.outline(x0, y0, x1, y1, COLOR_BORDER);

        // 参考网格：0.25 / 0.5 / 0.75
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

        // 两个可拖拽的控制点
        drawHandle(extractor, this.points[0], this.points[1]);
        drawHandle(extractor, this.points[2], this.points[3]);

        extractor.text(this.font, String.format(Locale.ROOT, "P1 %.2f, %.2f", this.points[0], this.points[1]),
                x0, y1 + 6, COLOR_HINT);
        extractor.text(this.font, String.format(Locale.ROOT, "P2 %.2f, %.2f", this.points[2], this.points[3]),
                x0, y1 + 18, COLOR_HINT);
    }

    private void drawHandle(GuiGraphicsExtractor extractor, float cx, float cy) {
        int px = toScreenX(cx);
        int py = toScreenY(cy);
        extractor.fill(px - HANDLE_RADIUS, py - HANDLE_RADIUS, px + HANDLE_RADIUS, py + HANDLE_RADIUS, COLOR_HANDLE);
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

    private void drawPreview(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        int stripHeight = 42;
        int top = this.graphY;
        extractor.text(this.font, Component.literal("渐入示例（打开界面）"), this.previewX, top - 12, COLOR_TEXT);
        drawStrip(extractor, this.previewX, top, this.previewWidth, stripHeight, true);
        int second = top + stripHeight + 26;
        extractor.text(this.font, Component.literal("渐出示例（关闭界面）"), this.previewX, second - 12, COLOR_TEXT);
        drawStrip(extractor, this.previewX, second, this.previewWidth, stripHeight, false);

        int hintY = second + stripHeight + 10;
        extractor.text(this.font,
                Component.literal("示例会按当前曲线循环播放；点「完成」后才写入配置。"),
                this.previewX, hintY, COLOR_HINT);
        extractor.text(this.font,
                Component.literal("需要更长的观感就去配置界面调渐入 / 渐出时长。"),
                this.previewX, hintY + 12, COLOR_HINT);
    }

    /** 一小段"界面"按曲线淡入/淡出，顺带带一点位移，尽量贴近实际观感 */
    private void drawStrip(GuiGraphicsExtractor extractor, int x, int y, int width, int height, boolean opening) {
        extractor.fill(x, y, x + width, y + height, COLOR_BG);
        extractor.outline(x, y, x + width, y + height, COLOR_BORDER);

        int cycleMs = 1400;
        long elapsed = (System.nanoTime() - this.startNanos) / 1_000_000L;
        float t = (elapsed % cycleMs) / (float) cycleMs;
        // 前 75% 播放，后 25% 停一下，看得清结束状态
        t = Math.min(1.0F, t / 0.75F);

        TransitionConfig.Curve preview = TransitionConfig.Curve.custom(this.points);
        float alpha = opening ? preview.easeOut(t) : 1.0F - preview.easeIn(t);

        int panelWidth = Math.max(24, width - 60);
        int panelHeight = 20;
        int px = x + (width - panelWidth) / 2;
        int slide = Math.round(10.0F * (1.0F - alpha));
        int py = y + (height - panelHeight) / 2 + (opening ? slide : -slide);

        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
        extractor.fill(px, py, px + panelWidth, py + panelHeight, (a << 24) | (COLOR_PREVIEW & 0x00FFFFFF));
        // 透明度数值，方便对着调
        extractor.text(this.font, Component.literal(Math.round(alpha * 100.0F) + "%"),
                x + 4, y + height - 12, COLOR_HINT);
    }

    // ------------------------------------------------------------------ 拖拽

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
        int first = distance(mx, my, this.points[0], this.points[1]);
        int second = distance(mx, my, this.points[2], this.points[3]);
        if (first <= GRAB_DISTANCE || second <= GRAB_DISTANCE) {
            this.dragging = first <= second ? 1 : 2;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.dragging == 0) {
            return super.mouseDragged(event, dragX, dragY);
        }
        float cx = Math.max(0.0F, Math.min(1.0F, fromScreenX(event.x())));
        float cy = Math.max(VIEW_MIN, Math.min(VIEW_MAX, fromScreenY(event.y())));
        if (this.dragging == 1) {
            this.points[0] = cx;
            this.points[1] = cy;
        } else {
            this.points[2] = cx;
            this.points[3] = cy;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.dragging != 0) {
            this.dragging = 0;
            return true;
        }
        return super.mouseReleased(event);
    }

    private int distance(double mouseX, double mouseY, float cx, float cy) {
        double dx = mouseX - toScreenX(cx);
        double dy = mouseY - toScreenY(cy);
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy));
    }
}
