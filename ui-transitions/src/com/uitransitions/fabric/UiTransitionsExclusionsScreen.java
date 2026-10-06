package com.uitransitions.fabric;

import com.uitransitions.TransitionConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 「排除的界面」双列表界面：左边是**最近见过的界面**，右边是**已排除**，点一下就在两边之间移动。
 *
 * ## 为什么要单独做一个界面
 *
 * 原来这一项只是 Cloth Config 里的一个字符串列表。用户反馈的实际困难是：
 * 要把某个界面排除掉，得先去翻日志、或者记住一个又长又难拼的类名。
 * 而这个模组**自己就知道**运行期见过哪些界面（{@code TransitionConfig.seenScreens}），
 * 两边一摆、点一下就搬过去，就不需要拼类名了。
 *
 * ## 一个绕不开的语义问题（这里的设计取舍）
 *
 * 左右两边**不是同一种东西**：
 *   · 左边是**具体界面类名**（`mezz.jei.SomeScreen`）
 *   · 右边是**排除规则**，按前缀匹配 —— 既可能是类名，也可能是用户手填的包名（`mezz.jei`）
 *
 * 所以"点一下即移动"不能做成简单的对称搬运，否则会有歧义：一个包名前缀覆盖很多界面，
 * 从它下面"移走一个界面"到底该移动谁？这里定的规则是：
 *
 *   · 点左边某一项 → 把**它的完整类名**加进排除列表（精确排除这一个界面）
 *   · 点右边某一项 → **整条删除**。不管它是类名还是包名前缀，用户想删就必须能删掉，
 *     不能让程序替他猜"你是不是只想移走其中一个"（那才是真的会让人卡住）
 *   · 左边只列**当前没被排除规则覆盖**的界面。已被某个前缀覆盖的不再显示，
 *     避免"在左边点了一下、加进去却看不到任何变化"这种自相矛盾的反馈
 *
 * 包名前缀在右边用另一种颜色标出来，并注明它一次覆盖一批界面 —— 因为删掉它影响面更大。
 */
public final class UiTransitionsExclusionsScreen extends Screen {

    private static final int MARGIN = 16;
    private static final int GAP = 12;
    private static final int HEADER_TOP = 34;
    private static final int BOTTOM_BAR = 30;
    /**
     * 列表控件的**初始**宽度。真正上屏的宽度由 {@link #layoutLists()} 按窗口算，
     * 这里只是个占位 —— 名字不能叫 MIN_*，否则下一个人会以为它是个不能突破的下界
     * （那正是把左列表压成 16 像素的原因）。
     */
    private static final int INITIAL_LIST_WIDTH = 120;

    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_HINT = 0xFF9AA0A6;

    private final Screen parent;

    private StackedTextList seenList;
    private StackedTextList excludedList;

    /** 两个列表的内容，随时可从配置重建 */
    private List<String> seen = new ArrayList<>();
    private List<String> excluded = new ArrayList<>();

    /** 被现有排除规则覆盖、因此不在左边显示的界面数（界面上要说明，免得用户以为丢了） */
    private int hiddenByRules;

    /** 连续两次点击同一项时提示一下"其实已经搬过去了"，避免用户以为点了没反应 */
    private String lastAction = "";
    private long lastActionNanos;

    public UiTransitionsExclusionsScreen(Screen parent) {
        super(Component.translatable("ui_transitions.exclude.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.seenList = new StackedTextList(0, 0, INITIAL_LIST_WIDTH, 60,
                Component.translatable("ui_transitions.exclude.seen.title"),
                Component.translatable("ui_transitions.exclude.seen.hint"),
                Component.translatable("ui_transitions.exclude.seen.empty"),
                this.font, index -> exclude(this.seen.get(index)));
        this.excludedList = new StackedTextList(0, 0, INITIAL_LIST_WIDTH, 60,
                Component.translatable("ui_transitions.exclude.excluded.title"),
                Component.translatable("ui_transitions.exclude.excluded.hint"),
                Component.translatable("ui_transitions.exclude.excluded.empty"),
                this.font, index -> unexclude(this.excluded.get(index)));
        addRenderableWidget(this.seenList);
        addRenderableWidget(this.excludedList);

        int buttonWidth = Math.min(120, Math.max(70, (this.width - MARGIN * 2 - GAP * 2) / 3));
        int y = this.height - 24;
        addRenderableWidget(Button.builder(Component.translatable("ui_transitions.exclude.clear"), b -> clearAll())
                .bounds(MARGIN, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("ui_transitions.exclude.done"), b ->
                        this.minecraft.setScreenAndShow(this.parent))
                .bounds(this.width - MARGIN - buttonWidth, y, buttonWidth, 20).build());

        layoutLists();
        refresh();
        logGeometry();
    }

    /**
     * 两个列表等宽平分。
     *
     * **宽度不能被一个"最小宽度"下界顶穿**：早期写法是 `max(MIN_LIST_WIDTH, available/2)`，
     * 一旦这个下界比实际可用空间还大，左列表就会被推到屏幕外、或被压成一条缝 ——
     * 实测（427x240）出现过左列表宽度只剩 **16 像素**、看起来"根本没画出来"。
     * 下界只能防"太窄不好点"，不能反过来制造溢出。
     */
    private void layoutLists() {
        int available = this.width - MARGIN * 2 - GAP;
        int each = Math.max(80, available / 2);
        int right = MARGIN + each + GAP;
        if (right + each > this.width - MARGIN) {
            // 极窄窗口：一起缩，宁可窄到不好点，也不要错位到看不见
            each = Math.max(60, (this.width - MARGIN * 2 - GAP) / 2);
            right = MARGIN + each + GAP;
        }
        // 底部要同时容纳两个按钮、状态行与回显行，列表高度必须让位，
        // 否则状态文字会压在列表边框上（截图里就是这样）
        int listHeight = Math.max(48, this.height - BOTTOM_BAR - HEADER_TOP - 26);
        // 底部还要放状态行 + 回显行（各一行字高），列表必须再让出这两行的高度，
        // 否则状态文字会压在列表边框上
        listHeight = Math.max(48, listHeight - (this.font.lineHeight + 2) * 2);
        this.seenList.layout(MARGIN, HEADER_TOP, each, listHeight);
        this.excludedList.layout(right, HEADER_TOP, each, listHeight);
    }

    /**
     * 窗口尺寸变了要重排。
     *
     * 界面刚被创建时 `this.width/height` 可能还是上一屏的尺寸（创建与上屏不是同一刻），
     * 只靠 init() 里算一次，列表就会按**错的宽度**摆好、再也不动 ——
     * 表现成"左边那个列表根本没画出来"。原版在 resize 时会重新调 init()，
     * 这里补一次重排，代价可忽略。
     */
    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (this.seenList != null && this.excludedList != null) {
            layoutLists();
            logGeometry();
        }
    }

    /** 把实际算出来的几何打一行日志：这类"看着没画出来"的问题，数字比截图好判 */
    private void logGeometry() {
        System.out.println("[UI Transitions] 排除界面几何: 界面=" + this.width + "x" + this.height
                + " 左列表=" + this.seenList.getX() + "," + this.seenList.getY()
                + " " + this.seenList.getWidth() + "x" + this.seenList.getHeight()
                + " 右列表=" + this.excludedList.getX() + "," + this.excludedList.getY()
                + " " + this.excludedList.getWidth() + "x" + this.excludedList.getHeight());
    }

    // ------------------------------------------------------------------ 数据

    /** 从配置重建两个列表 */
    private void refresh() {
        this.excluded = TransitionConfig.excludedEntries();
        List<String> remaining = new ArrayList<>();
        int hidden = 0;
        for (String name : TransitionConfig.seenScreens()) {
            if (isCovered(name)) {
                hidden++;
            } else {
                remaining.add(name);
            }
        }
        this.seen = remaining;
        this.hiddenByRules = hidden;

        List<List<StackedTextList.Cell>> seenRows = new ArrayList<>();
        for (String name : this.seen) {
            seenRows.add(List.of(new StackedTextList.Cell(shortName(name), COLOR_TEXT),
                    new StackedTextList.Cell("  " + packageOf(name), COLOR_HINT)));
        }
        this.seenList.setRows(seenRows);

        List<List<StackedTextList.Cell>> excludedRows = new ArrayList<>();
        for (String entry : this.excluded) {
            boolean prefix = TransitionConfig.isPrefixEntry(entry);
            excludedRows.add(List.of(new StackedTextList.Cell(entry,
                    prefix ? StackedTextList.PREFIX_COLOR : COLOR_TEXT)));
        }
        this.excludedList.setRows(excludedRows);
    }

    /** 某个界面是否已被某条排除规则覆盖（与运行期 shouldAnimate 的判定保持同一套前缀语义） */
    private boolean isCovered(String className) {
        for (String entry : this.excluded) {
            if (entry.equals(className) || className.startsWith(entry)) {
                return true;
            }
        }
        return false;
    }

    private static String shortName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    /** 左边点一下：精确排除这个界面 */
    private void exclude(String className) {
        if (className == null || className.isEmpty()) {
            return;
        }
        List<String> list = new ArrayList<>(TransitionConfig.excludedEntries());
        if (!list.contains(className)) {
            list.add(className);
            TransitionConfig.setExcludedScreens(TransitionConfig.joinList(list));
        }
        note(className);
        refresh();
    }

    /**
     * 右边点一下：整条删掉。
     *
     * 按**字面值**删，不做任何匹配：用户点的是哪一行就删哪一行。
     * 曾经想过"按类名反查是哪条规则覆盖了它"，但一个前缀覆盖很多界面，
     * 反查出来的结果可能不是用户点的那一条，删错东西比删不掉更难解释。
     */
    private void unexclude(String entry) {
        if (entry == null || entry.isEmpty()) {
            return;
        }
        List<String> list = new ArrayList<>(TransitionConfig.excludedEntries());
        if (list.remove(entry)) {
            TransitionConfig.setExcludedScreens(TransitionConfig.joinList(list));
        }
        note(entry);
        refresh();
    }

    private void clearAll() {
        if (TransitionConfig.excludedEntries().isEmpty()) {
            return;
        }
        TransitionConfig.setExcludedScreens("");
        note("");
        refresh();
    }

    private void note(String what) {
        this.lastAction = what;
        this.lastActionNanos = System.nanoTime();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        extractor.centeredText(this.font, this.title, this.width / 2, 10, COLOR_TEXT);
        extractor.centeredText(this.font,
                Component.translatable("ui_transitions.exclude.hint"),
                this.width / 2, 22, COLOR_HINT);

        // 底部三样东西从下往上排：按钮（height-24 起，高 20）→ 回显行 → 状态行。
        // 每一行都要与上面那条隔开，不能写死一个"大概"的 y ——
        // 实测 427x240 时状态行（height-40）正好压在列表边框与按钮上，
        // 三行文字糊成一片（截图里就是这样）。
        int lineHeight = this.font.lineHeight + 2;
        int echoY = this.height - BOTTOM_BAR - lineHeight;
        int statusY = echoY - lineHeight;

        // 当前进度：左边还剩几个、右边排了几个、有几个被前缀规则一并盖住了。
        // 这一行是这个界面的"唯一真相"：右边列表本身也可能被滚走，看不到全貌时至少这里对得上。
        String status = Component.translatable("ui_transitions.exclude.status",
                this.seen.size(), this.excluded.size(), this.hiddenByRules).getString();
        extractor.centeredText(this.font, status, this.width / 2, statusY, COLOR_HINT);

        if (!this.lastAction.isEmpty()
                && (System.nanoTime() - this.lastActionNanos) / 1_000_000L < 2500L) {
            // 2.5 秒内回显刚才动了哪一条：点了之后列表会立刻重排，
            // 没有这一行的话，用户很难确认"我点的那一下到底生效了没有"
            extractor.centeredText(this.font,
                    Component.translatable("ui_transitions.exclude.did", this.lastAction),
                    this.width / 2, echoY, 0xFF6FD08C);
        }
    }
}
