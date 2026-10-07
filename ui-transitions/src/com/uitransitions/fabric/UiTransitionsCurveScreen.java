package com.uitransitions.fabric;

import com.uitransitions.TransitionConfig;
import com.uitransitions.UiTransitions;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * 曲线编辑界面。三列：左边曲线图（+ 贝塞尔模式下的四个滑块）、中间实机预览、右边**动画列表**。
 *
 * 预览贴的是原版背包界面的贴图（AbstractContainerScreen.INVENTORY_LOCATION），
 * 所以看到的就是实机里那块 UI 本身在做渐入 / 渐出，而不是抽象色块。
 *
 * ## 编辑对象不止"全局"
 *
 * 右边的动画列表是**全局 + 7 个部位**（底板 / 遮罩 / 物品 / 文字 / 字幕 / 标签 / 传送门）。
 * 点一行就切换编辑对象，行首的小方框点一下在「跟随全局」与「单独设置」之间切换。
 * 部位"跟随全局"时图上的曲线是灰的、拖不动 —— 因为那时候它跑的就是全局那条，
 * 在这里拖只会白拖（真要改就先把小方框打上勾）。这个状态必须一眼能看出来，
 * 否则用户会以为"曲线编辑器坏了"。
 *
 * ## 多点模式
 *
 * 除了贝塞尔的四个控制点，还可以切到**多点模式**：在图上点一下加点、按住拖点、
 * 双击删点。存的是 `Curve.formatMulti` 那套 `x,y;x,y;…`，与配置里同一个字段
 * （`*CurveCustom`），所以配置文件不需要新键。
 *
 * 点的增删改全部走 {@link TransitionConfig.Curve} 里的静态方法，界面只负责坐标换算
 * 与命中判定。这样"拖出 NaN / 点数对不上 / 顺序乱掉"这类问题可以在离线断言里
 * 真正被覆盖 —— 界面类依赖 Minecraft 的 GUI 类型，离线跑不起来。
 *
 * ## 底部四个**原版滑块**（贝塞尔模式）
 *
 * 图上拖拽依赖鼠标事件，在触控设备上不一定送得到（实测"鼠标移上去有高亮，
 * 按下去毫无反应"）。原版滑块点一下就会把值设到点击处，触控和鼠标都可靠，
 * 所以贝塞尔模式保留这条不依赖拖拽的修改路径。
 */
public final class UiTransitionsCurveScreen extends Screen {

    /** 编辑的是哪一段动画 */
    public enum Target {
        OPEN("ui_transitions.curve.target.open"),
        CLOSE("ui_transitions.curve.target.close");

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
    /**
     * 小方块的**视觉**半径。
     *
     * 这个值和命中范围是**两件事**，故意不成比例：方块画小（少挡内容、看得清曲线），
     * 命中范围给大（手指按得到）。用户实测反馈就是"那几个方块太挡了、还得按到正中间"，
     * 所以两个方向一起调 —— 只放大命中、不缩小视觉，或者反过来，都不解决问题。
     */
    private static final int HANDLE_RADIUS = 4;
    private static final int GRAB_DISTANCE = 22;
    /**
     * 多点模式下，离已有点多近才算"抓这个点"而不是"在这里加一个点"。
     *
     * **这个值是按触屏定的，不是按鼠标定的。** 用户手机上（逻辑分辨率 427x240 附近、
     * GUI 缩放 0~3 档）拖动"必须按到正中间"才能抓住 —— 原来的 14px 在那种屏幕上
     * 只有指甲盖大小。触屏需要 ~9mm 的容错，换算到逻辑像素大约 28~34。
     *
     * 放大它的代价是"想加点时可能误抓附近的点"，但抓错了还能拖/删，
     * 而**抓不到是根本没法操作**。两害相权取其轻。
     */
    private static final int POINT_GRAB_DISTANCE = 30;
    /** 图框外的容错边距：指尖很难精确落在框内，稍微出去一点也算在图里 */
    private static final int GRAPH_SLOP = 10;

    /** 原版背包贴图是 176x166，放在 256x256 的图里 */
    private static final int INV_W = 176;
    private static final int INV_H = 166;
    private static final float INV_TEX = 256.0F;

    private static final int COLOR_BG = 0xFF101418;
    private static final int COLOR_BORDER = 0xFF5A6470;
    private static final int COLOR_GRID = 0xFF2A3038;
    private static final int COLOR_DIAGONAL = 0xFF3A424C;
    private static final int COLOR_CURVE = 0xFF6FD08C;
    /** 部位"跟随全局"时，图上画的是全局曲线：用灰色表明"这条不归你改" */
    private static final int COLOR_CURVE_LOCKED = 0xFF4A5A50;
    private static final int COLOR_HANDLE = 0xFFFFD166;
    private static final int COLOR_POINT = 0xFF7FD1FF;
    private static final int COLOR_HANDLE_LINE = 0xFF7A6A3A;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_HINT = 0xFF9AA0A6;
    private static final int COLOR_ROW_HOVER = 0xFF2E3742;
    private static final int COLOR_ROW_ACTIVE = 0xFF3C4A3F;
    private static final int COLOR_SCOPE_ON = 0xFF6FD08C;

    /** 兜底用的假界面配色：万一贴图没画出来，至少还是一块像背包的底板 */
    private static final int COLOR_PANEL = 0xFFC6C6C6;
    private static final int COLOR_SLOT = 0xFF8B8B8B;
    private static final int COLOR_DOLL = 0xFF6FA8DC;

    private final Screen parent;
    private final Target target;

    /** 正在编辑的部位；null = 编辑**全局**的渐入/渐出曲线 */
    private TransitionConfig.Part part;

    /** 正在编辑的控制点（x1,y1,x2,y2）；x 在 0..1，y 允许超出 */
    private float[] points = { 0.25F, 0.1F, 0.25F, 1.0F };
    /** 多点模式的内部点（不含固定的首尾），成对存 x,y */
    private float[] multi = new float[0];
    /** true = 多点模式，false = 贝塞尔控制点模式 */
    private boolean multiMode;
    /** false = 这一行是"跟随全局"（图上曲线只读） */
    private boolean own = true;

    /**
     * 多点模式下"点一下"是加点还是移动已选中的点。
     *
     * 26.3 只在**真的按住拖动**时才送 mouseMoved/mouseDragged，而这套合成在手机上不可靠
     * （上一个会话已经因此给每个数值配了原版滑块）。所以多点编辑必须有**不依赖拖动**的路：
     * 「加点」模式下点空白 = 加点，切到「移动」模式后点哪里就把选中的点挪到哪里。
     */
    private boolean moveMode;
    /** 多点模式下当前选中的内部点下标；-1 = 没选中 */
    private int selectedPoint = -1;

    /** 四个原版滑块：不依赖鼠标拖拽，点一下就能改值（仅贝塞尔模式存在） */
    private final ValueSlider[] sliders = new ValueSlider[4];
    /** 多点模式下选中点的 x/y 微调滑块（同样不依赖拖动） */
    private ValueSlider multiXSlider;
    private ValueSlider multiYSlider;

    private int graphX;
    private int graphY;
    private int graphSize;
    private int sliderX;
    private int sliderY;
    private int sliderWidth;
    private int previewX;
    private int previewY;
    private int previewWidth;
    private int previewHeight;
    private int listX;
    private int listY;
    private int listWidth;
    /** 动画列表是否真的显示（窗口太窄时整列不画，也不接受点击） */
    private boolean showPartsPanel;
    /** 列表完整显示的下标上界（行数太多时截断并提示） */
    private int listVisibleRows;
    /** 列表顶端显示的是第几行：窗口太矮时靠它翻到看不见的那几项 */
    private int listScroll;
    /** 本次布局下列表最多能滚多少行（渲染时算出来，供滚轮/翻页判断） */
    private int listMaxScroll;

    /** 0 = 无，1 = 第一个控制点，2 = 第二个控制点，3 = 多点模式的某个点 */
    private int dragging;
    /** 多点模式下正在拖的点下标；-1 = 没在拖 */
    private int draggingPoint = -1;

    /** 切换编辑对象 / 曲线类型 / 部位跟随状态后，需要重建控件 */
    private boolean widgetsDirty;

    /** 只为诊断：前几次点击记日志，避免刷屏 */
    private static int CLICK_LOGGED;

    private long startNanos;

    public UiTransitionsCurveScreen(Screen parent, Target target) {
        this(parent, target, null);
    }

    /**
     * @param part 要编辑的部位；null = 全局曲线
     */
    public UiTransitionsCurveScreen(Screen parent, Target target, TransitionConfig.Part part) {
        super(Component.translatable("ui_transitions.curve.title"));
        this.parent = parent;
        this.target = target;
        this.part = part;
        reloadFromConfig();
    }

    // ------------------------------------------------------------------ 模型

    private boolean closing() {
        return this.target == Target.CLOSE;
    }

    /** 当前编辑对象配的是哪条曲线 id（全局时看 openCurve / closeCurve） */
    private String currentCurveId() {
        if (this.part != null) {
            return TransitionConfig.partCurveId(this.part, closing());
        }
        return (closing() ? TransitionConfig.closeCurve() : TransitionConfig.openCurve()).id();
    }

    /**
     * 从配置里把当前编辑对象的状态读回来。
     *
     * 关键点：**部位"跟随全局"时，图上要显示全局那条曲线**（灰的），
     * 而不是显示它自己那份（可能还是默认值）—— 否则用户看到的与他实际会得到的不是同一条。
     */
    private void reloadFromConfig() {
        if (this.part != null) {
            this.own = !TransitionConfig.Curve.FOLLOW_ID.equals(currentCurveId());
        } else {
            this.own = true;
        }
        TransitionConfig.Curve effective = TransitionConfig.curveFor(this.part, closing());
        this.multiMode = TransitionConfig.Curve.MULTI_ID.equals(effective.id());
        if (this.multiMode) {
            this.multi = interiorOf(effective.points());
            // 多点模式下四个滑块没有意义，但仍要让它们有个能用的值
            this.points = TransitionConfig.parseBezier(TransitionConfig.DEFAULT_CUSTOM_BEZIER);
        } else {
            float[] bezier = effective.bezier();
            this.points = bezier == null || bezier.length != 4
                    ? TransitionConfig.parseBezier(TransitionConfig.DEFAULT_CUSTOM_BEZIER)
                    : new float[] { bezier[0], bezier[1], bezier[2], bezier[3] };
            this.multi = new float[0];
        }
        this.widgetsDirty = true;
    }

    /** 去掉首尾两个固定点，只留下可拖可删的内部点 */
    private static float[] interiorOf(float[] full) {
        if (full == null || full.length < 6) {
            return new float[0];
        }
        float[] out = new float[full.length - 4];
        System.arraycopy(full, 2, out, 0, out.length);
        return out;
    }

    /** 内部点 + 强制首尾，整理成完整点集 */
    private float[] fullMulti() {
        return TransitionConfig.Curve.normalizeMulti(this.multi);
    }

    private boolean editable() {
        return this.own;
    }

    /** 正在编辑的那条曲线（多点模式 → 多点曲线，否则 → 贝塞尔曲线） */
    private TransitionConfig.Curve editingCurve() {
        return this.multiMode
                ? TransitionConfig.Curve.multi(fullMulti())
                : TransitionConfig.Curve.custom(this.points);
    }

    /**
     * 预览用的曲线：**一律用编辑中的那份状态**，而不是"部位就读已保存的那条"。
     *
     * 早先这里对"部位 + 贝塞尔模式"返回的是 {@code curveFor(part)}（已存盘的那条），
     * 于是拖控制点、拉滑块时预览纹丝不动 —— 只有多点模式才是实时的。
     * 而预览的全部意义就是"调一下、马上看效果"，不跟手等于没有。
     * 全局早就是实时的（走 {@code editingCurve()}），这里把这个行为对齐到所有情况。
     */
    private TransitionConfig.Curve previewCurve() {
        return editingCurve();
    }

    @Override
    protected void init() {
        this.startNanos = System.nanoTime();
        buildWidgets();
    }

    /**
     * 窗口尺寸变了要重排。
     *
     * 原版会在 resize 时重新调 init()，但本界面的布局是"按 dirty 标志在渲染前重建"的，
     * 所以这里只需要置标志、让下一帧重建一次（**不在 resize 里直接重建**：
     * 那会在 init 流程中间改控件列表，属于自找麻烦）。
     * 不这么做的话，转到横屏/分屏后三列布局会保持旧尺寸 —— 与"左列表宽度算错"是同一类问题。
     */
    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        this.widgetsDirty = true;
    }

    /**
     * 按当前模式摆控件。
     *
     * 切模式（贝塞尔 ↔ 多点）和切编辑对象都会改这里的布局，所以单独抽出来，
     * 由 {@link #rebuildWidgetsIfDirty()} 在渲染前重建。
     */
    private void buildWidgets() {
        this.widgetsDirty = false;
        clearWidgets();
        // 控件已经清空，字段也要跟着清：滑块是按模式二选一构建的，
        // 留着上一次的引用会让 sync 逻辑去操作已经不在界面上的控件。
        for (int i = 0; i < this.sliders.length; i++) {
            this.sliders[i] = null;
        }
        this.multiXSlider = null;
        this.multiYSlider = null;

        int margin = 16;
        int gap = 10;

        // 三列都要放得下才显示右列。**门槛按实际需要算，不要写死一个"够大"的宽度**：
        // 曾经写的是 `width >= 560`，而实机里 GUI 缩放 3 档时逻辑分辨率只有 427x240 ——
        // 于是动画列表**一次都没显示过**，用户根本看不到新功能。
        // 实测（visualtest 的 curveui 阶段）：427x240 下三列是放得下的（图 56 / 预览 194 / 列表 128）。
        int listWidthWanted = Math.max(128, Math.min(210, this.width / 5));
        int minGraph = 56;
        int minPreview = 120;
        int needGraph = Math.min(150, Math.max(88, this.width / 4));
        int needTotal = margin * 2 + needGraph + gap + minPreview + gap + listWidthWanted;
        boolean showList = this.width >= needTotal;
        this.showPartsPanel = showList;

        int listActual = showList ? listWidthWanted : 0;
        this.listWidth = listActual;
        this.listX = showList ? this.width - margin - listActual : this.width + 1;
        this.listY = 44;

        // 图比别的东西重要：它是**用户唯一能直接操作**的东西，所以优先把宽度给它。
        // 预览只是"看一眼效果"，做小一点不影响操作 —— 用户明确要求过"预览做小、图做大"。
        int maxPreview = 96;
        int remaining = this.width - margin * 2 - (showList ? listActual + gap : 0);
        int columnWidth = Math.max(minGraph, Math.min(200, remaining / 3));
        this.graphX = margin;
        this.graphY = 44;

        // 图下面的一排滑块：贝塞尔模式是 4 个控制点滑块，多点模式是 2 个"选中点"滑块。
        // 窗口很矮时宁可藏掉滑块：图本来就画得比滑块高，而且"图上直接点"这条路仍然在。
        int bottomBar = 30;
        int slidersBlock = this.multiMode ? 2 * 21 + 12 : 4 * 21 + 12;
        boolean showSliders = this.height - bottomBar - this.graphY - slidersBlock - 16 >= 56;
        slidersBlock = showSliders ? slidersBlock : 0;
        int available = this.height - bottomBar - this.graphY - slidersBlock - 16;
        this.graphSize = Math.max(56, Math.min(200, Math.min(columnWidth, available)));

        this.sliderX = this.graphX;
        this.sliderY = this.graphY + this.graphSize + 14;
        this.sliderWidth = columnWidth;
        if (showSliders) {
            if (this.multiMode) {
                buildMultiSliders();
            } else {
                buildSliders();
            }
        }

        this.previewX = this.graphX + columnWidth + 14;
        this.previewY = this.graphY;
        // 预览：**故意做小**（用户要求"预览做小、图做大"）。它是只读的示范动画，
        // 宽度只影响观感；而图是唯一的操作面，宽度直接决定能不能点准。
        this.previewWidth = Math.max(72, Math.min(maxPreview, this.listX - 14 - this.previewX));
        this.previewHeight = Math.max(60, this.height - bottomBar - this.previewY - 18);

        buildButtons();
    }

    private void buildButtons() {
        int y = this.height - 24;
        int height = 20;
        int gap = 8;
        int margin = 16;
        int right = this.width - margin;
        // 四个按钮加起来不能超过可用宽度。**不能用 `width - 32 - gap*3)/4` 再取下限**：
        // 那样算出的下限（56）在窄窗口下会让四个按钮叠在一起 ——
        // 实机 427x240（GUI 缩放 3 档的逻辑分辨率）上正好会撞。
        // 中英两套标签都按"四个字以内"写，缩到 52px 也读得全。
        int buttonWidth = Math.max(52, Math.min(96, (this.width - margin * 2 - gap * 3) / 4));

        // 左下角：重置 + 曲线类型切换（多点模式下它变成"切回控制点"）
        Button resetButton = Button.builder(Component.translatable("ui_transitions.curve.reset"), b -> {
            if (this.multiMode) {
                // 多点模式的"重置"= 清掉所有中间点，回到匀速直线
                this.multi = new float[0];
                this.selectedPoint = -1;
                syncMultiSliders();
            } else {
                float[] def = TransitionConfig.parseBezier(TransitionConfig.DEFAULT_CUSTOM_BEZIER);
                this.points = new float[] { def[0], def[1], def[2], def[3] };
                syncSliders();
            }
        }).bounds(this.graphX, y, buttonWidth, height).build();
        Button modeButton = Button.builder(modeLabel(), b -> cycleMode())
                .bounds(this.graphX + buttonWidth + gap, y, buttonWidth, height).build();

        // 第三个按钮只在多点模式下有意义：在「加点」与「移动」之间切。
        // 二者都要用"点一下"这个手势，不分开就没法表达意图（手机上尤其如此）。
        if (this.multiMode) {
            Button actionButton = Button.builder(actionLabel(), b -> {
                this.moveMode = !this.moveMode;
                this.widgetsDirty = true;      // 标签要跟着换
            }).bounds(this.graphX + (buttonWidth + gap) * 2, y, buttonWidth, height).build();
            actionButton.active = editable();
            addRenderableWidget(actionButton);
        }

        // 部位"跟随全局"时这几处都必须**禁掉**，不能只禁模式按钮：
        // 保存时 save() 第一行就 `if (!editable()) return;`，所以那时用户拖的手柄、点的重置
        // 全都会在「完成」时无声消失 —— 一个"能点但不算数"的界面比灰掉更难解释。
        resetButton.active = editable();
        modeButton.active = editable();
        addRenderableWidget(resetButton);
        addRenderableWidget(modeButton);

        addRenderableWidget(Button.builder(Component.translatable("ui_transitions.curve.cancel"), b ->
                        this.minecraft.setScreenAndShow(this.parent))
                .bounds(right - buttonWidth, y, buttonWidth, height).build());
        addRenderableWidget(Button.builder(Component.translatable("ui_transitions.curve.done"), b -> {
            save();
            this.minecraft.setScreenAndShow(this.parent);
        }).bounds(right - buttonWidth * 2 - gap, y, buttonWidth, height).build());
    }

    private Component modeLabel() {
        return Component.translatable(this.multiMode
                ? "ui_transitions.curve.mode.to_bezier"
                : "ui_transitions.curve.mode.to_multi");
    }

    /** 多点模式下第三个按钮的标签：显示的**下一个**状态 */
    private Component actionLabel() {
        return Component.translatable(this.moveMode
                ? "ui_transitions.curve.action.to_add"
                : "ui_transitions.curve.action.to_move");
    }

    /**
     * 三种状态循环：控制点 → 多点（加点）→ 多点（移动）→ 控制点。
     *
     * 为什么是一个按钮而不是两个：窄窗口下按钮已经排到第 4 个（52px 一个），
     * 再拆就没地方放了；而"模式"本身就是一组互斥状态，循环按钮最直观。
     * 贝塞尔 ↔ 多点两个方向都**尽量保住形状**（见下面取样/换算），
     * 直接丢掉重来的话，用户切过去看一眼再切回来就白调了。
     */
    private void cycleMode() {
        if (!this.multiMode) {
            enterMultiMode();
            return;
        }
        if (!this.moveMode) {
            this.moveMode = true;      // 多点·加点 → 多点·移动
            this.widgetsDirty = true;
            return;
        }
        leaveMultiMode();              // 多点·移动 → 控制点
    }

    private void enterMultiMode() {
        TransitionConfig.Curve before = editingCurve();
        java.util.List<Float> sampled = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            float t = i / 6.0F;
            sampled.add(t);
            sampled.add(clampY(before.easeIn(t)));
        }
        this.multi = new float[sampled.size()];
        for (int i = 0; i < this.multi.length; i++) {
            this.multi[i] = sampled.get(i);
        }
        this.multiMode = true;
        this.moveMode = false;
        this.selectedPoint = -1;
        this.dragging = 0;
        this.draggingPoint = -1;
        this.widgetsDirty = true;
    }

    private void leaveMultiMode() {
        this.points = TransitionConfig.Curve.multiToBezier(fullMulti());
        this.multiMode = false;
        this.moveMode = false;
        this.multi = new float[0];
        this.selectedPoint = -1;
        this.dragging = 0;
        this.draggingPoint = -1;
        this.widgetsDirty = true;
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
        for (int i = 0; i < 4; i++) {
            final int index = i;
            boolean isX = (i % 2 == 0);
            float min = isX ? 0.0F : VIEW_MIN;
            float max = isX ? 1.0F : VIEW_MAX;
            String label = new String[] { "P1 x", "P1 y", "P2 x", "P2 y" }[i];
            ValueSlider slider = new ValueSlider(this.sliderX, this.sliderY + i * 21, this.sliderWidth, 20,
                    label, min, max, this.points[i], value -> this.points[index] = value);
            // 跟随全局的部位没有可保存的东西，滑块一并灰掉（见 buildButtons 的说明）
            slider.active = editable();
            this.sliders[i] = slider;
            addRenderableWidget(slider);
        }
    }

    /**
     * 多点模式下给**选中的那个点**配两个滑块（x / y）。
     *
     * 这是"点选式移动"之外的精度补充：点选负责大范围挪，滑块负责最后一两像素。
     * 两者都不依赖拖动，所以手机上一定可用。
     */
    private void buildMultiSliders() {
        this.multiXSlider = new ValueSlider(this.sliderX, this.sliderY, this.sliderWidth, 20,
                "X", 0.0F, 1.0F, 0.5F, value -> moveSelected(value, null));
        this.multiYSlider = new ValueSlider(this.sliderX, this.sliderY + 21, this.sliderWidth, 20,
                "Y", VIEW_MIN, VIEW_MAX, 0.5F, value -> moveSelected(null, value));
        addRenderableWidget(this.multiXSlider);
        addRenderableWidget(this.multiYSlider);
        syncMultiSliders();
    }

    /** 把选中点挪到指定坐标（传 null 表示这一维不动） */
    private void moveSelected(Float x, Float y) {
        if (!this.multiMode || this.selectedPoint < 0 || !editable()) {
            return;
        }
        float[] full = fullMulti();
        float nx = x != null ? x : TransitionConfig.Curve.pointX(full, this.selectedPoint);
        float ny = y != null ? y : TransitionConfig.Curve.pointY(full, this.selectedPoint);
        this.multi = interiorOf(TransitionConfig.Curve.moveMulti(full, this.selectedPoint, nx, ny));
        if (x == null || y == null) {
            // 有一维是被滑块推着走的，把另一维也刷成实际值（可能被邻居夹过）
            syncMultiSliders();
        }
    }

    /** 让两个多点滑块显示当前选中点的实际坐标；没选中就灰掉 */
    private void syncMultiSliders() {
        if (this.multiXSlider == null || this.multiYSlider == null) {
            return;
        }
        float[] full = fullMulti();
        boolean has = this.multiMode && this.selectedPoint >= 0
                && this.selectedPoint < TransitionConfig.Curve.interiorPointCount(full);
        this.multiXSlider.active = has && editable();
        this.multiYSlider.active = has && editable();
        if (has) {
            this.multiXSlider.setFromModel(TransitionConfig.Curve.pointX(full, this.selectedPoint));
            this.multiYSlider.setFromModel(TransitionConfig.Curve.pointY(full, this.selectedPoint));
        }
    }

    private void syncSliders() {
        for (int i = 0; i < 4; i++) {
            if (this.sliders[i] != null) {
                this.sliders[i].setFromModel(this.points[i]);
            }
        }
    }

    /** 切编辑对象会改布局，但渲染期不能改控件列表 —— 所以只置标志，下一帧重建 */
    private void rebuildWidgetsIfDirty() {
        if (this.widgetsDirty) {
            buildWidgets();
        }
    }

    private void save() {
        if (!editable()) {
            return;         // 跟随全局：这里什么都没改，别顺手把它变成"单独设置"
        }
        if (this.multiMode) {
            String text = TransitionConfig.Curve.formatMulti(fullMulti());
            if (this.part == null) {
                if (closing()) {
                    TransitionConfig.setCloseCurveMulti(text);
                } else {
                    TransitionConfig.setOpenCurveMulti(text);
                }
            } else {
                TransitionConfig.setPartCurveMulti(this.part, closing(), text);
            }
            return;
        }
        String text = String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f",
                this.points[0], this.points[1], this.points[2], this.points[3]);
        if (this.part == null) {
            if (closing()) {
                TransitionConfig.setCloseCurveCustom(text);
            } else {
                TransitionConfig.setOpenCurveCustom(text);
            }
        } else {
            TransitionConfig.setPartCurveCustom(this.part, closing(), text);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // 上下键翻右侧列表：滚轮之外的**第二条路**。这个项目里已经吃过一次亏 ——
        // 触屏设备上鼠标事件不一定送得到，所以每个值都配了原版滑块；滚轮同理不可靠。
        // 265 / 264 就是 GLFW 的 UP / DOWN，直接用常量省掉一个 InputConstants 依赖。
        if (this.listMaxScroll > 0 && (event.key() == 265 || event.key() == 264)) {
            int delta = event.key() == 265 ? -1 : 1;
            this.listScroll = Math.max(0, Math.min(this.listMaxScroll, this.listScroll + delta));
            return true;
        }
        // Delete 删掉鼠标附近那个点：给"不想双击/双击判不准"的设备留一条路。
        // 261 就是 GLFW 的 GLFW_KEY_DELETE。
        if (this.multiMode && editable() && event.key() == 261) {
            int index = nearestPointIndex(this.lastMouseX, this.lastMouseY, 24);
            if (index >= 0) {
                dropPoint(index);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /**
     * 最近一次鼠标位置。Delete 删点要用它 —— 那个键事件里没有坐标，
     * 只能记下鼠标最后停在哪，才能判断"用户指的是哪个点"。
     */
    private double lastMouseX = -1.0;
    private double lastMouseY = -1.0;

    // ------------------------------------------------------------------ 绘制

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        // 控件列表不能在渲染中途改，所以把重建推到帧边界（下一帧开头）之前
        if (this.widgetsDirty) {
            rebuildWidgetsIfDirty();
        }
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        extractor.centeredText(this.font, titleLine(), this.width / 2, 10, COLOR_TEXT);
        extractor.centeredText(this.font,
                Component.translatable(this.multiMode
                        ? "ui_transitions.curve.hint.multi"
                        : "ui_transitions.curve.hint"),
                this.width / 2, 24, COLOR_HINT);

        drawGraph(extractor, mouseX, mouseY);
        drawPreview(extractor);
        drawPartsPanel(extractor, mouseX, mouseY);
    }

    /** 标题第二行：说明现在编辑的是"全局的渐入"还是"物品的渐出" */
    private Component titleLine() {
        Component direction = Component.translatable(this.target.label());
        if (this.part == null) {
            return Component.translatable("ui_transitions.curve.title_target", direction);
        }
        return Component.translatable("ui_transitions.curve.title_part",
                Component.translatable(partKey(this.part)), direction);
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

        if (this.multiMode) {
            drawMultiHandles(extractor, mouseX, mouseY);
        } else if (editable()) {
            // 控制点连线：必须画**线段**。
            // 早先这里写成 fill(x0, y0, px, py, 色) —— 那是画矩形不是画线，
            // 控制点一旦拉远，整块矩形就把曲线盖住了（用户截图里那块黄色就是它）。
            //
            // 只在可编辑时画：跟随全局的部位图上画的是**全局那条曲线**（灰的），
            // 而 `points` 里存的是它自己那份（可能还是默认值）—— 把手柄画上去就会
            // 落在与曲线对不上的位置，"看着能拖、拖了也不算数"，比不画更糟。
            drawSegment(extractor, toScreenX(0.0F), toScreenY(0.0F),
                    toScreenX(this.points[0]), toScreenY(this.points[1]), COLOR_HANDLE_LINE);
            drawSegment(extractor, toScreenX(this.points[2]), toScreenY(this.points[3]),
                    toScreenX(1.0F), toScreenY(1.0F), COLOR_HANDLE_LINE);

            drawHandle(extractor, this.points[0], this.points[1],
                    this.dragging == 1 || isNear(mouseX, mouseY, this.points[0], this.points[1]));
            drawHandle(extractor, this.points[2], this.points[3],
                    this.dragging == 2 || isNear(mouseX, mouseY, this.points[2], this.points[3]));
        }

        Component state;
        if (!editable()) {
            state = Component.translatable("ui_transitions.curve.locked");
        } else if (this.dragging == 1) {
            state = Component.translatable("ui_transitions.curve.dragging_p1");
        } else if (this.dragging == 2) {
            state = Component.translatable("ui_transitions.curve.dragging_p2");
        } else if (this.draggingPoint >= 0) {
            state = Component.translatable("ui_transitions.curve.dragging_point", this.draggingPoint + 1);
        } else if (this.multiMode) {
            // 提示必须说清"现在点一下会发生什么"：加点和移动共用同一个手势，
            // 不说清楚用户没法知道为什么点下去有时加点、有时挪点。
            state = Component.translatable(this.moveMode
                            ? "ui_transitions.curve.multi_hint.move"
                            : "ui_transitions.curve.multi_hint.add",
                    TransitionConfig.Curve.interiorPointCount(fullMulti()),
                    this.selectedPoint >= 0 ? this.selectedPoint + 1 : 0);
        } else {
            state = Component.translatable("ui_transitions.curve.drag_hint");
        }
        String[] readout = graphReadout();
        int stateY = y1 + 3 + readout.length * (this.font.lineHeight + 1);
        for (int i = 0; i < readout.length; i++) {
            extractor.text(this.font, readout[i], x0, y1 + 3 + i * (this.font.lineHeight + 1), COLOR_TEXT);
        }
        extractor.text(this.font, state, x0, stateY,
                this.dragging != 0 || this.draggingPoint >= 0 ? COLOR_HANDLE : COLOR_HINT);
    }

    /**
     * 图下面那行数值：贝塞尔报 P1/P2，多点报点数。
     *
     * 窄窗口下图只有 60 来像素宽，`P1 0.25,0.10  P2 0.25,1.00` 会从"P2"处被切断 ——
     * 只剩 "P1 0.25,0.10 P2" 看着像坏了。所以**按可用宽度挑一种写法**：
     * 放不下就退成每行一个控制点。数值本身必须完整，截断的读数比没有读数更糟。
     */
    private String[] graphReadout() {
        if (this.multiMode) {
            String line = Component.translatable("ui_transitions.curve.readout_multi",
                    TransitionConfig.Curve.interiorPointCount(fullMulti()) + 2).getString();
            return new String[] { line };
        }
        String both = String.format(Locale.ROOT, "P1 %.2f,%.2f  P2 %.2f,%.2f",
                this.points[0], this.points[1], this.points[2], this.points[3]);
        // 必须留出余量。实测：427x240 下图宽 64，这行读数正好 124 宽，
        // 而阈值写的是 graphSize+60 = 124 —— 相等就判成"放得下"，于是照旧被切断，
        // 只剩 "P1 0.25,0.10 P2"（截图里就是这样）。等号在这里是错的。
        if (this.font.width(both) + 8 <= this.graphSize + 60) {
            return new String[] { both };
        }
        return new String[] {
                String.format(Locale.ROOT, "P1 %.2f,%.2f", this.points[0], this.points[1]),
                String.format(Locale.ROOT, "P2 %.2f,%.2f", this.points[2], this.points[3]),
        };
    }

    /**
     * 把曲线画成**连续的折线**，而不是一串小方块。
     *
     * 早先是每隔一点画一个 2x2 的方块，采样一稀就露出锯齿、看着发糊。
     * 现在逐列填充、把相邻采样点连起来，线是连续的，边缘也干净。
     */
    private void drawCurve(GuiGraphicsExtractor extractor) {
        int color = editable() ? COLOR_CURVE : COLOR_CURVE_LOCKED;
        TransitionConfig.Curve curve = this.own ? editingCurve() : TransitionConfig.curveFor(this.part, closing());
        int samples = Math.max(64, this.graphSize * 2);
        int prevX = Integer.MIN_VALUE;
        int prevY = 0;
        for (int i = 0; i <= samples; i++) {
            float t = i / (float) samples;
            float v = sample(curve, t);
            int px = toScreenX(t);
            int py = toScreenY(v);
            if (prevX == Integer.MIN_VALUE) {
                extractor.fill(px, py, px + 1, py + 1, color);
            } else if (px > prevX) {
                for (int x = prevX + 1; x <= px; x++) {
                    int y = prevY + Math.round((py - prevY) * (x - prevX) / (float) (px - prevX));
                    extractor.fill(x, y, x + 1, y + 1, color);
                }
            }
            prevX = px;
            prevY = py;
        }
    }

    /**
     * 取样：图上画的必须和实际跑的是同一条。
     *
     * 贝塞尔用 {@link TransitionConfig.Curve#bezierEase}（就是渲染时用的那个函数），
     * 多点用 easeIn —— 它的下限是 0（不会像 back 那样冲到负数），
     * 正好把过冲裁在框内，看到的形状与实机一致。
     */
    private static float sample(TransitionConfig.Curve curve, float t) {
        if (curve.points() != null && curve.points().length >= 4) {
            return clampY(curve.easeIn(t));
        }
        return clampY(TransitionConfig.Curve.bezierEase(curve.bezier(), t));
    }

    private static float clampY(float v) {
        return Math.max(VIEW_MIN, Math.min(VIEW_MAX, v));
    }

    private void drawMultiHandles(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        float[] full = fullMulti();
        // 首尾是固定的：画成灰色小方块，明确"这两个不能拖"
        drawFixedPoint(extractor, full[0], full[1]);
        drawFixedPoint(extractor, full[full.length - 2], full[full.length - 1]);
        int count = TransitionConfig.Curve.interiorPointCount(full);
        for (int i = 0; i < count; i++) {
            float px = TransitionConfig.Curve.pointX(full, i);
            float py = TransitionConfig.Curve.pointY(full, i);
            boolean hot = this.draggingPoint == i || nearestPointIndex(mouseX, mouseY,
                    POINT_GRAB_DISTANCE) == i;
            drawPoint(extractor, px, py, hot);
        }
    }

    private void drawFixedPoint(GuiGraphicsExtractor extractor, float cx, float cy) {
        int px = toScreenX(cx);
        int py = toScreenY(cy);
        extractor.fill(px - 3, py - 3, px + 3, py + 3, COLOR_HINT);
    }

    private void drawPoint(GuiGraphicsExtractor extractor, float cx, float cy, boolean highlighted) {
        int px = toScreenX(cx);
        int py = toScreenY(cy);
        int r = highlighted ? HANDLE_RADIUS + 2 : HANDLE_RADIUS;
        if (highlighted) {
            extractor.fill(px - r - 2, py - r - 2, px + r + 2, py + r + 2, COLOR_HANDLE_LINE);
        }
        extractor.fill(px - r, py - r, px + r, py + r, COLOR_POINT);
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
        float clamped = clampY(v);
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

    // ------------------------------------------------------------------ 右侧：动画列表

    /** 部位在语言文件里的键；界面与断言共用同一套命名 */
    static String partKey(TransitionConfig.Part part) {
        return "ui_transitions.part." + part.id();
    }

    /** 列表行数 = 全局 + 全部部位 */
    private static int rowCount() {
        return TransitionConfig.Part.values().length + 1;
    }

    /** 第 row 行对应的部位；0 行是"全局"，返回 null */
    private static TransitionConfig.Part rowPart(int row) {
        TransitionConfig.Part[] values = TransitionConfig.Part.values();
        int index = row - 1;
        return index >= 0 && index < values.length ? values[index] : null;
    }

    private static int rowOf(TransitionConfig.Part part) {
        return part == null ? 0 : part.ordinal() + 1;
    }

    private static final int ROW_HEIGHT = 22;

    private void drawPartsPanel(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        if (!this.showPartsPanel) {
            return;
        }
        int headerHeight = 30;
        int rowsFit = Math.max(1, (this.height - 30 - (this.listY + headerHeight)) / ROW_HEIGHT);
        this.listVisibleRows = Math.min(rowCount(), rowsFit);
        // 装不下时允许滚动：鼠标滚轮，以及右侧列表上的上下键。
        // **必须留滚轮之外的第二条路** —— 这个项目里已经吃过一次亏：触屏设备上
        // 图上拖拽不可靠，所以才给每个值都配了一个原版滑块。同理，滚轮在触屏上也不可靠。
        this.listMaxScroll = Math.max(0, rowCount() - this.listVisibleRows);
        this.listScroll = Math.max(0, Math.min(this.listMaxScroll, this.listScroll));
        int panelHeight = headerHeight + this.listVisibleRows * ROW_HEIGHT + 4;

        extractor.fill(this.listX, this.listY, this.listX + this.listWidth, this.listY + panelHeight, COLOR_BG);
        extractor.outline(this.listX, this.listY, this.listWidth, panelHeight, COLOR_BORDER);
        extractor.text(this.font, Component.translatable("ui_transitions.curve.parts.title"),
                this.listX + 6, this.listY + 5, COLOR_TEXT);
        extractor.text(this.font, Component.translatable("ui_transitions.curve.parts.subtitle"),
                this.listX + 6, this.listY + 17, COLOR_HINT);

        for (int row = 0; row < this.listVisibleRows; row++) {
            drawPartsRow(extractor, this.listScroll + row, mouseX, mouseY);
        }
        // 滚动提示写在面板**里面**的第一行右边：写到面板下面会越出到按钮上
        // （实测 427x240 时它压住了底部按钮）。
        if (this.listMaxScroll > 0) {
            Component counter = Component.translatable("ui_transitions.curve.parts.scroll",
                    this.listScroll + 1, this.listScroll + this.listVisibleRows, rowCount());
            String text = counter.getString();
            // 可用宽度按**更宽的那行副标题**算，不是按标题：两行都要能并排放下才算真放得下
            int available = this.listWidth - 12 - Math.max(
                    this.font.width(Component.translatable("ui_transitions.curve.parts.title")),
                    this.font.width(Component.translatable("ui_transitions.curve.parts.subtitle")));
            // 窄列表里"第 1-6 项 / 共 8"根本放不下，退化成"还有 2 项"
            if (this.font.width(text) > available) {
                text = Component.translatable("ui_transitions.curve.parts.more",
                        rowCount() - this.listVisibleRows).getString();
            }
            extractor.text(this.font, text,
                    this.listX + this.listWidth - 6 - this.font.width(text), this.listY + 5, COLOR_HINT);
        }
    }

    private void drawPartsRow(GuiGraphicsExtractor extractor, int row, int mouseX, int mouseY) {
        TransitionConfig.Part rowPart = rowPart(row);
        int x = this.listX;
        int y = rowTop(row);
        int w = this.listWidth;
        boolean active = rowOf(this.part) == row;
        boolean hovered = isInsideRow(row, mouseX, mouseY);
        if (active) {
            extractor.fill(x + 2, y, x + w - 2, y + ROW_HEIGHT - 1, COLOR_ROW_ACTIVE);
        } else if (hovered) {
            extractor.fill(x + 2, y, x + w - 2, y + ROW_HEIGHT - 1, COLOR_ROW_HOVER);
        }

        // 第一列：跟随全局 / 单独设置的小方框。点它就是"这一项要不要单独设"。
        // 全局那一行没有这个概念，画个横杠表示"不适用"。
        int boxY = y + 5;
        if (rowPart == null) {
            extractor.text(this.font, "-", x + 7, y + 6, COLOR_HINT);
        } else {
            boolean follows = TransitionConfig.Curve.FOLLOW_ID.equals(
                    TransitionConfig.partCurveId(rowPart, closing()));
            extractor.outline(x + 5, boxY, 10, 10, COLOR_BORDER);
            if (!follows) {
                extractor.fill(x + 7, boxY + 2, x + 13, boxY + 8, COLOR_SCOPE_ON);
            }
        }

        String label = rowPart == null
                ? Component.translatable("ui_transitions.curve.parts.global").getString()
                : Component.translatable(partKey(rowPart)).getString();
        // 太长的行名截断，别糊到右边那一列上
        int textWidth = Math.max(30, w - 46);
        while (this.font.width(label) > textWidth && label.length() > 2) {
            label = label.substring(0, label.length() - 1);
        }
        extractor.text(this.font, label, x + 20, y + 6, active ? COLOR_TEXT : COLOR_HINT);
    }

    /** 第 row 行在屏幕上的 y（按当前滚动位置） */
    private int rowTop(int row) {
        return this.listY + 30 + (row - this.listScroll) * ROW_HEIGHT;
    }

    private boolean isInsideRow(int row, double mouseX, double mouseY) {
        if (!this.showPartsPanel || row < this.listScroll
                || row >= this.listScroll + this.listVisibleRows) {
            return false;
        }
        int y = rowTop(row);
        // **整行、整高都算命中**：原来上下各缩 1px、左右各缩 2px，看着没差多少，
        // 但用户实机反馈是"得按到正中间才有效果" —— 触屏上一条 22px 高的行本来就窄，
        // 再缩一圈就只剩中间一小块了。行与行之间本来就有分隔，不需要靠缩边距来区分。
        return mouseX >= this.listX && mouseX <= this.listX + this.listWidth
                && mouseY >= y && mouseY < y + ROW_HEIGHT;
    }

    /** 行首那个小方框的命中区 */
    private boolean isInsideScopeChip(int row, double mouseX, double mouseY) {
        if (!this.showPartsPanel || row < this.listScroll
                || row >= this.listScroll + this.listVisibleRows) {
            return false;
        }
        int y = rowTop(row);
        return mouseX >= this.listX + 3 && mouseX <= this.listX + 18
                && mouseY >= y + 3 && mouseY < y + ROW_HEIGHT - 3;
    }

    /** 屏幕坐标落在第几行（已把滚动位置算进去） */
    private int rowAt(double mouseX, double mouseY) {
        if (!this.showPartsPanel || mouseX < this.listX || mouseX > this.listX + this.listWidth) {
            return -1;
        }
        int top = this.listY + 30;
        if (mouseY < top) {
            return -1;
        }
        int visible = (int) ((mouseY - top) / ROW_HEIGHT);
        if (visible < 0 || visible >= this.listVisibleRows) {
            return -1;
        }
        int row = this.listScroll + visible;
        return row < rowCount() ? row : -1;
    }

    /**
     * 滚轮翻右侧列表。装不下时才接管，否则**必须还回去**：
     * 列表在任何窗口高度下都占着屏幕右边一条，如果无脑吞掉滚轮，
     * 将来这个界面上别的可滚区域就再也收不到事件了。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.listMaxScroll > 0 && rowAt(mouseX, mouseY) >= 0) {
            this.listScroll = Math.max(0, Math.min(this.listMaxScroll,
                    this.listScroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 点行名 = 切换编辑对象；点小方框 = 切换"单独设置 / 跟随全局" */
    private boolean handlePartsClick(double mouseX, double mouseY) {
        int row = rowAt(mouseX, mouseY);
        if (row < 0) {
            return false;
        }
        TransitionConfig.Part clicked = rowPart(row);
        if (!isInsideScopeChip(row, mouseX, mouseY)) {
            this.part = clicked;
            reloadFromConfig();
            return true;
        }
        if (clicked == null) {
            // 全局那一行的方框不是开关：点它等同于选中全局 —— 总得有个办法切回全局，
            // 不然进了某个部位就回不去了。
            this.part = null;
            reloadFromConfig();
            return true;
        }
        setPartOwn(clicked, TransitionConfig.Curve.FOLLOW_ID.equals(
                TransitionConfig.partCurveId(clicked, closing())));
        this.part = clicked;
        reloadFromConfig();
        return true;
    }

    /**
     * 把某个部位切成"单独设置"或"跟随全局"。
     *
     * 切成单独设置时**拿当前生效的那条曲线当起点**（也就是全局那条），而不是从默认值开始：
     * 用户的心智是"我要在这条的基础上微调"，从零开始会让他把刚才调好的全局曲线重调一遍。
     *
     * 这里传进去的是**多点格式**的点集，类型由 {@code setPartCurveCustom} 按值的格式推断
     * （见 TransitionConfig.kindForPoints）。早先那个方法无条件写 id=custom，
     * 于是这串多点值会被 parseBezier 判成非法、静默回退成默认贝塞尔 ——
     * 表现就是"勾上单独设置，曲线却跳到了另一条"。
     */
    private void setPartOwn(TransitionConfig.Part part, boolean enable) {
        if (enable) {
            TransitionConfig.setPartCurveCustom(part, closing(),
                    TransitionConfig.Curve.formatMulti(sampleCurveToMulti(TransitionConfig.curveFor(part, closing()))));
        } else {
            TransitionConfig.setPartCurve(part, closing(), TransitionConfig.Curve.FOLLOW_ID);
        }
    }

    /** 均匀取样任意曲线 → 多点曲线的点集（含首尾），用来给"单独设置"一个合适的起点 */
    private static float[] sampleCurveToMulti(TransitionConfig.Curve curve) {
        int steps = 6;
        java.util.List<Float> values = new java.util.ArrayList<>();
        values.add(0.0F);
        values.add(0.0F);
        for (int i = 1; i < steps; i++) {
            float t = i / (float) steps;
            values.add(t);
            values.add(clampY(sample(curve, t)));
        }
        values.add(1.0F);
        values.add(1.0F);
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    // ------------------------------------------------------------------ 预览：背包开关动画

    /**
     * 按**真实配置的时长与曲线**循环播放一次"打开背包 → 停一会儿 → 关闭背包"。
     *
     * 贴的是原版背包贴图，所以看到的就是实机那块 UI 本身在动。
     * 用真实时长而不是固定的演示时长：调完曲线想看看"500ms 到底是多快"时，
     * 这里给的就是实机的节奏。
     *
     * 曲线用**正在编辑的那条**（部位单独设了就预览它，否则预览全局），
     * 这样"我在改哪条 / 改完长什么样"是同一件事。
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

        TransitionConfig.Curve openCurve = previewCurve();
        TransitionConfig.Curve closeCurve = previewCurve();

        float alpha;
        float slide;
        Component phaseName;
        if (phase < openMs) {
            float p = phase / (float) openMs;
            alpha = openCurve.easeOut(p);
            slide = 1.0F - alpha;
            phaseName = Component.translatable("ui_transitions.curve.phase.in");
        } else if (phase < openMs + holdMs) {
            alpha = 1.0F;
            slide = 0.0F;
            phaseName = Component.translatable("ui_transitions.curve.phase.hold");
        } else {
            float p = (phase - openMs - holdMs) / (float) closeMs;
            float closed = closeCurve.easeIn(p);
            alpha = 1.0F - closed;
            slide = closed;
            phaseName = Component.translatable("ui_transitions.curve.phase.out");
        }

        // 统一交给模组的透明度通道：贴图和方块都会跟着淡
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
        int stageH = Math.max(30, h - 16);
        int maxSlide = Math.max(6, stageH / 6);
        int offset = Math.round(maxSlide * slide);

        // 面板滑动时会越出预览框，裁掉免得糊到列表上。
        // 用 beginClip：高度算成 <=0 时不裁，绝不把空矩形交给延迟渲染管线
        // （那会崩在**这一帧稍后的绘制阶段**，堆栈里看不到调用者 —— 见 StackedTextList.beginClip 的说明）
        boolean clipped = StackedTextList.beginClip(extractor, x + 1, y + 1, x + w - 1, y + h - 1);
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
            if (clipped) {
                extractor.disableScissor();
            }
        }

        // 文字放在面板**外面**，不再压住画面。
        //
        // 窄屏上这两行会互相压住（实测 427 宽时"预览：打开/关闭背包"和"预览对象：全局"
        // 直接叠在一起）：先量宽度，放不下就**把右侧那行省掉**，而不是让两行糊成一团。
        String previewTitle = Component.translatable("ui_transitions.curve.preview").getString();
        Component scope = Component.translatable("ui_transitions.curve.preview_scope",
                this.part == null
                        ? Component.translatable("ui_transitions.curve.parts.global")
                        : Component.translatable(partKey(this.part)));
        extractor.text(this.font, previewTitle, x + 4, y + 4, COLOR_TEXT);
        if (this.font.width(previewTitle) + this.font.width(scope) + 16 <= w) {
            extractor.text(this.font, scope, x + w - 6 - this.font.width(scope), y + 4, COLOR_HINT);
        }
        extractor.text(this.font,
                Component.translatable("ui_transitions.curve.preview_info",
                        phaseName, Math.round(alpha * 100), openMs, closeMs),
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
     * ## 为什么先自己处理、再交给控件（顺序很关键）
     *
     * `super.mouseClicked` 会遍历控件树，**任何一个控件消费掉这次点击就返回 true** ——
     * 原先这里第一行就是 `if (super.mouseClicked(...)) return true;`，于是
     * 「图/动画列表」这类**自己画、自己判命中**的区域，只要落点被某个控件认领过，
     * 就永远轮不到我们。实测反馈正是这个症状：入口页的原版按钮点得动，
     * 而自己画的动画列表点不动、图上也拖不动（鼠标移上去有高亮，按下去没反应）。
     *
     * 所以顺序改成：**先看是不是我这几个自绘区域 → 是就自己处理并吞掉；不是再交给控件。**
     * 这样控件仍然拿得到属于它的点击（按钮在这些区域之外，两者不重叠）。
     *
     * 多点模式下的判定顺序（一次点击只做一件事）：
     *   ① 双击 → 删掉指着的那个点（首尾不动）
     *   ② 离已有点够近 → 选中/抓住它
     *   ③ 有选中的点 → 把它移到这里（**点选式移动，不依赖拖动**）
     *   ④ 否则 → 在这个 x 上加一个新点
     * ①②优先于③④，是为了让"双击删点"和"移动点"不会被"加了个新点"搅乱。
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        double mx = event.x();
        double my = event.y();
        if (CLICK_LOGGED < 6) {
            CLICK_LOGGED++;
            System.out.println("[UI Transitions] 曲线界面收到左键点击 (" + Math.round(mx) + ","
                    + Math.round(my) + ")  图框=" + this.graphX + "," + this.graphY
                    + " 尺寸=" + this.graphSize + "  在图框内=" + isInsideGraph(mx, my)
                    + " 在列表内=" + (rowAt(mx, my) >= 0));
        }
        // ---- 先处理自绘区域 ----
        if (handlePartsClick(mx, my)) {
            return true;
        }
        if (isInsideGraph(mx, my) && editable()) {
            if (this.multiMode) {
                handleMultiClick(mx, my, doubleClick);
            } else {
                int first = distance(mx, my, this.points[0], this.points[1]);
                int second = distance(mx, my, this.points[2], this.points[3]);
                this.dragging = first <= second ? 1 : 2;
                applyDrag(mx, my);
            }
            return true;
        }
        // ---- 都不是才交给控件 ----
        return super.mouseClicked(event, doubleClick);
    }

    /**
     * 多点模式下一次点击的含义。
     *
     * 判定顺序（一次点击只做一件事）：
     *   ① 双击落在点上 → 删掉它（首尾不动）
     *   ② 落在某个点上 → 选中它（并允许接着拖）
     *   ③ 「移动」模式且已有选中的点 → 把它挪到点的地方 ← **不依赖拖动的那条路**
     *   ④ 「加点」模式 → 在这个 x 上加一个新点（并选中它）
     *
     * ③④ 用一个显式模式区分，是因为二者都要用"点一下"这个手势：
     * 不加区分的话，"移动一个点"和"在别处加一个点"会互相抢，用户没法表达意图。
     */
    private void handleMultiClick(double mouseX, double mouseY, boolean doubleClick) {
        int index = nearestPointIndex(mouseX, mouseY, POINT_GRAB_DISTANCE);
        if (doubleClick) {
            // 双击落在点上 = 删掉它。落在空白处什么都不做 —— 双击太容易误触了，
            // 不该顺手在用户没指的地方加点或删点。
            if (index >= 0) {
                dropPoint(index);
            }
            return;
        }
        if (index >= 0) {
            // 选中，并且允许直接拖着走（能拖的设备照旧顺手）
            this.selectedPoint = index;
            this.dragging = 3;
            this.draggingPoint = index;
            syncMultiSliders();
            return;
        }
        if (this.moveMode) {
            // 把选中的点挪到这里 —— 手机上主要靠这条
            if (this.selectedPoint >= 0) {
                this.multi = interiorOf(TransitionConfig.Curve.moveMulti(
                        fullMulti(), this.selectedPoint,
                        fromScreenX(clampToGraphX(mouseX)), fromScreenY(clampToGraphY(mouseY))));
                syncMultiSliders();
            }
            return;
        }
        float[] before = fullMulti();
        float[] after = TransitionConfig.Curve.insertMulti(before,
                fromScreenX(clampToGraphX(mouseX)), fromScreenY(clampToGraphY(mouseY)));
        if (after != before) {
            this.multi = interiorOf(after);
            this.selectedPoint = nearestPointIndex(mouseX, mouseY, POINT_GRAB_DISTANCE);
            // 新加的点马上就能拖：按下即抓住，符合"点一下放这儿、挪一挪调准"
            this.dragging = 3;
            this.draggingPoint = this.selectedPoint;
            syncMultiSliders();
        }
    }

    /** 删掉第 index 个内部点并收尾（把正在拖的状态一并清掉） */
    private void dropPoint(int index) {
        float[] before = fullMulti();
        float[] after = TransitionConfig.Curve.removeMulti(before, index);
        this.multi = interiorOf(after);
        this.dragging = 0;
        this.draggingPoint = -1;
    }

    /** 鼠标是不是在图框里（留几像素余量，贴着边框点也算） */
    private boolean isInsideGraph(double mouseX, double mouseY) {
        return mouseX >= this.graphX - GRAPH_SLOP && mouseX <= this.graphX + this.graphSize + GRAPH_SLOP
                && mouseY >= this.graphY - GRAPH_SLOP
                && mouseY <= this.graphY + this.graphSize + GRAPH_SLOP;
    }

    /**
     * 把屏幕坐标夹进图框内。
     *
     * 用于"在图框边缘附近按下"的情况：容错边距让 GRAPH_SLOP 范围内的点击也算作在图里，
     * 但**坐标本身要夹进合法范围**，否则会把点放到 x<0 或 y>1 的位置上
     * （曲线数据的合法域由 TransitionConfig 那侧再夹一次，但这里先夹能少一次无用写入）。
     */
    private double clampToGraphX(double mouseX) {
        return Math.max(this.graphX, Math.min(this.graphX + this.graphSize, mouseX));
    }

    private double clampToGraphY(double mouseY) {
        return Math.max(this.graphY, Math.min(this.graphY + this.graphSize, mouseY));
    }

    /** 离鼠标最近的那个**内部点**；超出 maxDistance 返回 -1 */
    private int nearestPointIndex(double mouseX, double mouseY, int maxDistance) {
        float[] full = fullMulti();
        int count = TransitionConfig.Curve.interiorPointCount(full);
        int best = -1;
        int bestDistance = maxDistance;
        for (int i = 0; i < count; i++) {
            int d = distance(mouseX, mouseY,
                    TransitionConfig.Curve.pointX(full, i), TransitionConfig.Curve.pointY(full, i));
            if (d <= bestDistance) {
                best = i;
                bestDistance = d;
            }
        }
        return best;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        if (this.dragging != 0 || this.draggingPoint >= 0) {
            applyDrag(mouseX, mouseY);
            return;
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.dragging != 0 || this.draggingPoint >= 0) {
            applyDrag(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.dragging != 0 || this.draggingPoint >= 0) {
            this.dragging = 0;
            this.draggingPoint = -1;
            return true;
        }
        return super.mouseReleased(event);
    }

    private void applyDrag(double mouseX, double mouseY) {
        // x 必须夹在 0..1：否则反解参数会失真，曲线会变得不可预期
        float cx = Math.max(0.0F, Math.min(1.0F, fromScreenX(mouseX)));
        float cy = clampY(fromScreenY(mouseY));
        if (this.draggingPoint >= 0) {
            this.multi = interiorOf(TransitionConfig.Curve.moveMulti(
                    fullMulti(), this.draggingPoint, cx, cy));
            return;
        }
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
