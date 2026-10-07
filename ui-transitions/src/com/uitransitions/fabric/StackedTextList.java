package com.uitransitions.fabric;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个自绘的竖向文本列表：**点一下某一行就触发回调**。
 *
 * ## 为什么不用原版控件
 *
 * 原版有两个候选，都被排除过：
 *
 * · `ObjectSelectionList` 会在构造时缓存 `Minecraft.getInstance().level` 并据此选背景贴图 ——
 *   界面在标题画面/配置流程里也可能被打开，那时 level 为 null，这条路不必要地多一个空指针面。
 * · Cloth Config 的自绘条目**已经栽过一次**：渲染正常、直接派发点击也能开，
 *   但真实鼠标点击传不到（命中判定链路太长，还离线验证不了）。详见 UiTransitionsHubScreen 的注释。
 *
 * 所以这里走**和曲线编辑器完全相同**的那条已验证路径：注册成普通控件（`addRenderableWidget`），
 * 点击由 `Screen.mouseClicked → ContainerEventHandler` 的默认派发送到 `children()`，
 * 再落到本类的 `mouseClicked`。曲线编辑器里的拖拽就是靠这条链路在实机跑通的。
 *
 * 绘制全部用 `fill` / `outline` / `text` 三个最基本的调用，不用任何贴图。
 */
final class StackedTextList extends AbstractWidget {

    private static final int COLOR_BG = 0xFF101418;
    private static final int COLOR_BORDER = 0xFF5A6470;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_MUTED = 0xFF9AA0A6;
    private static final int COLOR_HOVER = 0xFF2E3742;
    private static final int COLOR_EMPTY = 0xFF707880;

    /** 一行里的一段文字：内容 + 颜色（用来把"前缀"标成另一种颜色） */
    record Cell(String text, int color) {
    }

    private final Font font;
    private final List<List<Cell>> rows = new ArrayList<>();
    private final java.util.function.IntConsumer onPick;
    private final Component emptyText;
    /** 标题右侧的小字，例如"点一下就不做动画" */
    private final Component hint;

    private int scroll;
    private int rowHeight;
    private int visibleRows;

    StackedTextList(int x, int y, int width, int height, Component title, Component hint,
                    Component emptyText, Font font, java.util.function.IntConsumer onPick) {
        super(x, y, width, height, title);
        this.font = font;
        this.hint = hint;
        this.emptyText = emptyText;
        this.onPick = onPick;
        // 中文行高比英文大，写死 12 会在简中下把字挤在一起
        this.rowHeight = Math.max(11, font.lineHeight + 2);
        this.visibleRows = Math.max(1, (height - headerHeight()) / this.rowHeight);
    }

    private static int headerHeight() {
        return 26;
    }

    void setRows(List<List<Cell>> newRows) {
        this.rows.clear();
        this.rows.addAll(newRows);
        clampScroll();
    }

    /** 行数（供页面在重建内容后做提示用） */
    int rowCount() {
        return this.rows.size();
    }

    /**
     * 设置位置与尺寸。面板宽度要等 init() 之后才确定，所以布局不能只在构造时做一次。
     *
     * **参数顺序容易搞反**：`AbstractWidget.setRectangle` 的签名是
     * `(x, y, width, height)`，和本方法一致 —— 但早期这里写成
     * `setRectangle(x, y, width, height)` 却把变量按 `(x, y, x2, y2)` 理解，
     * 结果位置被设成了 `(width, height)`、尺寸被设成了 `(x2-x, y2-y)`，
     * 左列表于是变成 16 像素宽、停在 (191,128) 这种诡异位置，
     * 看起来就是"左边那个列表根本没画出来"。
     * 用 setPosition/setSize 分开写，读起来不会再和矩形坐标混淆。
     */
    void layout(int x, int y, int width, int height) {
        setPosition(x, y);
        setSize(Math.max(1, width), Math.max(1, height));
        this.rowHeight = Math.max(11, this.font.lineHeight + 2);
        this.visibleRows = Math.max(1, (height - headerHeight()) / this.rowHeight);
        clampScroll();
    }

    private void clampScroll() {
        int max = Math.max(0, this.rows.size() - this.visibleRows);
        this.scroll = Math.max(0, Math.min(max, this.scroll));
    }

    private int rowTop(int visibleIndex) {
        return getY() + headerHeight() + visibleIndex * this.rowHeight;
    }

    private int rowIndexAt(double mouseX, double mouseY) {
        if (mouseX < getX() || mouseX > getRight() || mouseY < getY() + headerHeight() || mouseY > getBottom()) {
            return -1;
        }
        int visibleIndex = (int) ((mouseY - (getY() + headerHeight())) / this.rowHeight);
        if (visibleIndex < 0 || visibleIndex >= this.visibleRows) {
            return -1;
        }
        int index = this.scroll + visibleIndex;
        return index >= 0 && index < this.rows.size() ? index : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.active || !this.visible || event.button() != 0) {
            return false;
        }
        int index = rowIndexAt(event.x(), event.y());
        if (index < 0) {
            return false;
        }
        if (this.onPick != null) {
            this.onPick.accept(index);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!this.active || !this.visible || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        if (this.rows.size() <= this.visibleRows) {
            // 没得滚的时候**不要吞掉**滚轮：否则整页的滚动会被一个空列表吃掉
            return false;
        }
        this.scroll = Math.max(0, Math.min(this.rows.size() - this.visibleRows,
                this.scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();

        extractor.fill(x, y, x + w, y + h, COLOR_BG);
        extractor.outline(x, y, w, h, COLOR_BORDER);
        // 标题与副标题也要按宽度截断：副标题（"点一下 → 移到右边…"）比标题长得多，
        // 窄列表里会直接画到边框外面（实测截图里能看到文字越过面板右缘）
        drawClipped(extractor, getMessage(), x + 6, y + 4, COLOR_TEXT, x + w - 6);
        if (this.hint != null) {
            drawClipped(extractor, this.hint, x + 6, y + 4 + this.font.lineHeight, COLOR_MUTED, x + w - 6);
        }
        if (this.rows.isEmpty()) {
            drawClipped(extractor, this.emptyText, x + 6, y + headerHeight() + 2, COLOR_EMPTY, x + w - 6);
            return;
        }

        // 滚动时让行别画到标题上：只裁列表区，标题与边框留在外面。
        // 用 beginClip：高度算出来 <=0 时**直接不裁**，而不是把空矩形交给渲染器。
        boolean clipped = beginClip(extractor, x + 1, y + headerHeight(), x + w - 1, y + h - 1);
        try {
            for (int visibleIndex = 0; visibleIndex < this.visibleRows; visibleIndex++) {
                int index = this.scroll + visibleIndex;
                if (index >= this.rows.size()) {
                    break;
                }
                int top = rowTop(visibleIndex);
                if (top + this.rowHeight > y + h) {
                    break;
                }
                boolean hovered = isMouseOver(mouseX, mouseY) && rowIndexAt(mouseX, mouseY) == index;
                if (hovered) {
                    extractor.fill(x + 2, top, x + w - 2, top + this.rowHeight - 1, COLOR_HOVER);
                }
                int textY = top + 2;
                int textX = x + 6;
                int limit = x + w - 6;
                for (Cell cell : this.rows.get(index)) {
                    // 逐段截断：宁可少画几个字，也不能让文字糊到列表外面去
                    String text = cell.text() == null ? "" : cell.text();
                    while (!text.isEmpty() && textX + this.font.width(text) > limit) {
                        text = text.substring(0, text.length() - 1);
                    }
                    if (text.isEmpty()) {
                        break;
                    }
                    extractor.text(this.font, text, textX, textY, cell.color());
                    textX += this.font.width(text);
                }
            }
        } finally {
            if (clipped) {
                extractor.disableScissor();
            }
        }
    }

    /**
     * 开启裁剪区，但**保证不会交出一个空矩形**。
     *
     * 26.3 的 GUI 是延迟渲染：`enableScissor` 只是把矩形记进渲染状态，
     * 真正下发时 `FrontendRenderPass.enableScissor` 会校验
     * （`IllegalArgumentException: Scissor size must be >0`）——
     * 也就是说，一旦某个界面把高度算成 0 的裁剪区记了进去，
     * **崩的不是那一行，而是这一帧稍后的绘制阶段**，堆栈里根本看不到调用者（真实崩溃日志就是这样）。
     * 所以这里统一兜住：算不出正的宽高就干脆不裁（内容最多越界一点，不会崩游戏）。
     *
     * @return 是否真的开启了裁剪（关闭时必须配对调用 disableScissor）
     */
    static boolean beginClip(GuiGraphicsExtractor extractor, int x0, int y0, int x1, int y1) {
        int left = Math.min(x0, x1);
        int right = Math.max(x0, x1);
        int top = Math.min(y0, y1);
        int bottom = Math.max(y0, y1);
        if (right - left <= 0 || bottom - top <= 0) {
            return false;
        }
        extractor.enableScissor(left, top, right, bottom);
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    /**
     * 画一行文字，超出 limitX 就逐字截断。
     *
     * 统一走这里，避免"某处忘了截断"：列表里行文字本来就截断了，
     * 但标题/副标题/空列表提示早期是直接 text(...) 画出去的，
     * 窄面板上会越过边框（截图证据）。
     */
    private void drawClipped(GuiGraphicsExtractor extractor, Component text,
                             int x, int y, int color, int limitX) {
        String value = text == null ? "" : text.getString();
        while (!value.isEmpty() && x + this.font.width(value) > limitX) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.isEmpty()) {
            extractor.text(this.font, value, x, y, color);
        }
    }

    /** 供子类/页面复用：把一段普通文本包成单段行 */
    static List<Cell> plain(String text, int color) {
        return List.of(new Cell(text, color));
    }

    static final int TEXT_COLOR = COLOR_TEXT;
    static final int MUTED_COLOR = COLOR_MUTED;
    static final int PREFIX_COLOR = 0xFFFFC46B;
}
