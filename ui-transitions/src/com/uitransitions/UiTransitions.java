package com.uitransitions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.joml.Matrix3x2fStack;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 过渡动画的核心状态机。
 *
 * 分层模型（26.3 的界面就是分两层画的）：
 *   背景层 extractBackground —— 变暗遮罩 + 容器底板 + 槽位背景 + 音效字幕
 *   内容层 extractRenderState —— 槽内物品、标题文字等
 * 两层各自压栈平移并设定透明度；其中两类内容需要"抵消"动画：
 *   · 变暗遮罩（extractTransparentBackground）—— 默认不位移，但可以随界面一起淡出（fadeDim）
 *   · 音效字幕（Hud.extractDeferredSubtitles）—— 默认既不动也不淡（animateSubtitles 可放开）
 *
 * 打断处理：关闭动画进行到一半时若又打开、或打开到一半时又关闭，
 * 新动画会把起点"回拨"到与当前可见状态一致的位置，避免画面跳变。
 */
public final class UiTransitions {

    /** 物品图标是从图集在提交阶段绘制的，需要记住它被提取时的透明度 */
    private static final Map<Object, Float> ITEM_ALPHAS = new IdentityHashMap<>();

    private static final Map<Screen, Open> OPEN_START = new WeakHashMap<>();
    private static final Map<Screen, Close> CLOSING = new WeakHashMap<>();
    private static final Set<Screen> FINISHED = Collections.newSetFromMap(new WeakHashMap<>());

    private static final ThreadLocal<Float> WINDOW_ALPHA = ThreadLocal.withInitial(() -> 1.0F);
    private static final ThreadLocal<Screen> PUSHED_SCREEN = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<Boolean> BACKGROUND_PUSHED = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Float> LAYER_SHIFT = ThreadLocal.withInitial(() -> 0.0F);
    private static final ThreadLocal<Float> LAYER_ALPHA = ThreadLocal.withInitial(() -> 1.0F);
    private static final ThreadLocal<Boolean> STATIC_REGION = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> HUD_PAUSED = ThreadLocal.withInitial(() -> false);
    /** pauseForHud 是否真的抵消过位移（跟随动画那条路不抵消，恢复时也不能补） */
    private static final ThreadLocal<Boolean> HUD_SHIFTED = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> PIP_BLITTING = ThreadLocal.withInitial(() -> false);
    /** 本帧内容层整体的淡变透明度（帧级：直到下一帧开始才复位，供 HUD 快捷栏判断用） */
    private static volatile float FRAME_FADE_ALPHA = 1.0F;
    /**
     * 内容层本帧的淡变透明度，**帧内跨阶段保留**。
     *
     * 为什么不能用 FRAME_ALPHA：它在提取阶段结束时就被 endScreenFrame 复位了，
     * 而画中画是在渲染阶段（GuiRenderer.render → prepare → blitTexture）才贴回界面的，
     * 那时读 FRAME_ALPHA 只会拿到 1.0 —— 书 / 地图 / 旗帜预览于是完全不淡变。
     * 帧级复位发生在下一帧的 beginContentLayer，所以这个值在整帧内都有效。
     */
    private static volatile float PIP_FRAME_ALPHA = 1.0F;
    /**
     * 画中画内容这一帧该跟着界面位移多少。
     *
     * 画中画是在渲染阶段单独贴回界面的，它自己的 pose 恒为单位矩阵，
     * 所以界面滑动时它只会淡、不会动 —— 看起来就像"面板走了，布娃娃/附魔书
     * 还孤零零留在原地"。这里把内容层的位移记下来，
     * 由 PictureInPictureRendererMixin 叠到它那张贴图的 pose 上。
     */
    private static final ThreadLocal<Float> PIP_SHIFT = ThreadLocal.withInitial(() -> 0.0F);
    private static final ThreadLocal<Boolean> TAB_STATIC = ThreadLocal.withInitial(() -> false);
    /** 冻住快捷栏时被替换掉的 PIP_FRAME_ALPHA，恢复时还原 */
    private static final ThreadLocal<Float> TAB_STATIC_SAVED_PIP = ThreadLocal.withInitial(() -> 1.0F);
    /**
     * 冻住快捷栏时被替换掉的 TEXT_ALPHA，恢复时还原。
     *
     * 快捷栏物品的**数量文字**走文字通道（`applyAlphaText` → `TEXT_ALPHA`），
     * 它跟 pip 那条路是两套来源。1.5.01 之前只冻了 pip，于是出现
     * "图标不淡、数字在淡" —— 用户看到的仍然是"快捷栏跟着一起渐变"。
     */
    private static final ThreadLocal<Float> TAB_STATIC_SAVED_TEXT = ThreadLocal.withInitial(() -> 1.0F);
    /**
     * 物品/画中画走的是预乘 alpha 管线（GUI_TEXTURED_PREMULTIPLIED_ALPHA）：
     * 颜色通道本应已经乘过 alpha。只改 alpha 而不动 RGB，元素就会比周围偏亮
     * —— 这就是"切换时物品突然变亮"的原因。进这条管线时把 RGB 一起按比例缩放。
     */
    private static final ThreadLocal<Boolean> PREMULTIPLIED = ThreadLocal.withInitial(() -> false);
    /** 本帧的动画透明度：物品渲染状态没登记到（例如状态是在动画开始前建立的）就退回这个值 */
    private static final ThreadLocal<Float> FRAME_ALPHA = ThreadLocal.withInitial(() -> 1.0F);

    /**
     * 文字的透明度。与 WINDOW_ALPHA 分开，是为了让「文字」能配一条自己的曲线：
     * WINDOW_ALPHA 管的是物品与矩形，文字走这里（见 applyAlphaText）。
     * 内容层没在动画时保持 1.0，文字就是原样。
     */
    private static final ThreadLocal<Float> TEXT_ALPHA = ThreadLocal.withInitial(() -> 1.0F);
    /**
     * 当前背景层用的提取器。
     *
     * 字幕（Hud.extractDeferredSubtitles）是在背景层内部被调用的，但它自己没有
     * extractor 参数；把当前这个存下来，就能在 Hud 内部统一抵消位移，
     * 从而覆盖所有调用点（Screen / PauseScreen / LoadingOverlay），
     * 而不是只在 Screen.extractBackground 这一个调用点上打补丁。
     */
    private static final ThreadLocal<GuiGraphicsExtractor> BACKGROUND_EXTRACTOR = new ThreadLocal<>();
    /** 当前处于"背景层"的界面：遮罩要按自己的曲线重算透明度时需要它 */
    private static final ThreadLocal<Screen> BACKGROUND_SCREEN = new ThreadLocal<>();

    /** 创造模式分类标签等"换页"动画：记录每屏的开始时间与滚动方向 */
    private static final Map<Screen, TabSwitch> TAB_SWITCH = new WeakHashMap<>();
    /** 每个界面上一帧的滚动位置，用来判断列表是否真的滚动了 */
    private static final Map<Screen, Float> LAST_SCROLL = new WeakHashMap<>();
    /**
     * 本帧观察到的格子区上下界（像素）。
     * 用**上一帧**的值来算渐变，避免同一帧里边画边改导致前面的格子跟着变。
     */
    private static final Map<Screen, float[]> GRID_BOUNDS = new WeakHashMap<>();
    /** 本帧正在累积的格子区上下界 */
    private static final ThreadLocal<float[]> GRID_BOUNDS_NOW = ThreadLocal.withInitial(() -> null);
    /** 保存逐格淡变前的透明度，供 endSlotFade 还原 */
    private static final ThreadLocal<Float> SLOT_SAVED_ALPHA = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SLOT_FADED = ThreadLocal.withInitial(() -> false);
    /**
     * 当前正在做"原地淡变"的界面，由 beginTabContent 登记。
     *
     * 不能用 PUSHED_SCREEN：那个只在**打开/关闭动画进行中**才被赋值，
     * 而点标签/滚动发生在界面早已静止之后 —— 用它会导致逐格淡变整块失效。
     * beginTabContent 挂在 CreativeModeInventoryScreen.extractRenderState 的 HEAD 上，
     * 每帧都会跑，且包住了槽位绘制，正好是需要的范围。
     */
    private static final ThreadLocal<Screen> IN_PLACE_SCREEN = new ThreadLocal<>();


    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean EFFECTIVE = new AtomicBoolean();
    /** JEI / EMI / REI 是否存在：只按类是否存在判断，不依赖任何加载器 API */
    private static volatile Boolean overlayModPresent;

    private static final String[] OVERLAY_MOD_CLASSES = {
            "mezz.jei.api.IModPlugin",              // JEI
            "dev.emi.emi.api.EmiPlugin",            // EMI
            "me.shedaniel.rei.api.common.plugins.REIPluginProvider",  // REI
    };

    private UiTransitions() {
    }

    // ================================================================== 供 Mixin 调用：切屏

    /** Gui.setScreen 的 HEAD 注入：返回 true 表示取消这次原版切屏（改为播放关闭动画）。 */
    public static boolean interceptSetScreen(Gui gui, Screen target) {
        try {
            if (BYPASS.get()) {
                return false;
            }
            TransitionConfig.ensureLoaded();
            if (!TransitionConfig.enabled()) {
                return false;
            }
            long now = System.nanoTime();
            Screen current = gui.screen();
            // 每一次切屏都记一行：排查"某个界面怎么没效果"时，这是唯一能直接看出
            // "到底有没有走到这里、有没有被判成要做动画"的地方。
            //
            // 但要按"内容变了才记"去重：关闭动画期间游戏会**反复**调 setScreen(null)，
            // 原样记下来就是几十行一模一样的 "A -> (无)"，把日志淹掉（真实日志里见过）。
            logScreenChange(current, target);
            // 跨维度加载界面出现或消失 -> 拉满遮罩（它自己会缓缓淡出）。
            // 放在常规动画判定之前：这个界面**不参与**常规动画，见 shouldAnimate。
            if (isPortalLoading(current) || isPortalLoading(target)) {
                startPortalVeil(target != null ? target : current);
            }
            // 同类界面之间的"换页"（创造模式分类标签、配方书翻页等）直接切换，不做动画：
            // 它们本来就是同一个界面的内部操作，滑入滑出会很突兀。
            if (target != null && current != null && target != current
                    && !TransitionConfig.animateSameTypeSwitch()
                    && target.getClass() == current.getClass()) {
                CLOSING.remove(target);
                OPEN_START.remove(target);
                // 标记为"已就位"，这样 beginContentLayer 会走快路径，不会懒启动一次动画
                FINISHED.add(target);
                return false;
            }
            if (current != null && current != target && target == null && shouldAnimate(current)) {
                if (CLOSING.containsKey(current)) {
                    return true;                       // 已经在关闭中：继续拦着，不重启动画
                }
                // 打开动画还没播完就关闭：按当前可见透明度反解关闭曲线的进度，接着往下走
                long closeDuration = closeDurationNanos(current);
                long backdate = backdateNanos(
                        solveProgress(TransitionConfig.closeCurve(), visualAlpha(current), true), closeDuration);
                if (isPortalLoading(current)) {
                    log("渐出（跨维度加载界面）: " + current.getClass().getSimpleName()
                            + " reason=" + portalReason(current)
                            + " 时长=" + (closeDuration / 1_000_000L) + "ms 位移=" + offsetFor(current));
                }
                CLOSING.put(current, new Close(now - backdate, closeDuration, null));
                OPEN_START.remove(current);
                FINISHED.remove(current);
                // 关闭动画期间把鼠标交还给游戏，让玩家可以立刻转视角（配置可关）
                if (TransitionConfig.allowLookDuringClose()) {
                    try {
                        Minecraft minecraft = Minecraft.getInstance();
                        if (minecraft != null && minecraft.mouseHandler != null) {
                            minecraft.mouseHandler.grabMouse();
                        }
                    } catch (Throwable ignored) {
                        // 拿不到就算了，不影响动画
                    }
                }
                return true;
            }
            if (target != null && shouldAnimate(target)) {
                // 之前正在关闭这个界面（重新打开）：同样按当前透明度接续
                long openDuration = openDurationNanos(target);
                if (isPortalLoading(target)) {
                    log("渐入（跨维度加载界面）: " + target.getClass().getSimpleName()
                            + " reason=" + portalReason(target)
                            + " 时长=" + (openDuration / 1_000_000L) + "ms 位移=" + offsetFor(target));
                }
                long backdate = 0L;
                if (CLOSING.containsKey(target)) {
                    // 目标透明度来自 `visualAlpha(target)`。此刻 target 还在 CLOSING 里，
                    // 所以它走的是 `1.0F - curve.easeIn(p)` 这条**递减**式子 ——
                    // 因此第三个参数必须是 true（按 1-easeIn 反解）。
                    //
                    // 早先这里传的是 false（按 easeOut 反解一个递增式子），等于去找一个
                    // 不存在的根：实测 solveProgress(cubic, 0.131, false) = 0.0457，
                    // 代回去得到 0.0063 而不是 0.131。表现是"打开播到一半被关掉、再打开"
                    // 时透明度跳一下。
                    //
                    // 曲线取 `closeCurve()` 而不是 `openCurve()`：**必须与 visualAlpha 读的那条
                    // 是同一条**，否则反解出来的进度对不上可见值，接续仍然会跳。
                    // （`curveFor` 依赖 CLOSING，等下面 remove 之后就查不到这条了，所以这里直接取。）
                    Close interrupted = CLOSING.get(target);
                    float handoff = solveProgress(TransitionConfig.closeCurve(),
                            visualAlpha(target), true);
                    // **回拨必须用"算出这个进度的那段动画"的时长**（关闭段的），
                    // 不是接下来的渐入时长 —— 见 progress() 上面那段注释，这是同一条规矩。
                    // 用错时长会让新动画的起点整体偏移（实测跳变 43/255）。
                    backdate = interrupted != null
                            ? backdateNanos(handoff, interrupted.durationNanos())
                            : backdateNanos(handoff, openDuration);
                    if (DEBUG_REOPEN < 20) {
                        DEBUG_REOPEN++;
                        log("重开接续: 可见=" + visualAlpha(target)
                                + " 反解进程=" + handoff
                                + " 关闭段时长=" + (interrupted == null ? -1
                                        : interrupted.durationNanos() / 1_000_000L) + "ms"
                                + " 渐入时长=" + (openDuration / 1_000_000L) + "ms"
                                + " 回拨=" + (backdate / 1_000_000L) + "ms");
                    }
                    CLOSING.remove(target);
                }
                OPEN_START.put(target, new Open(now - backdate, openDuration));
                FINISHED.remove(target);
            }
            return false;
        } catch (Throwable t) {
            report("interceptSetScreen", t);
            return false;
        }
    }

    /** Gui.tick 的 TAIL 注入：关闭动画播完后补做真正切屏。 */
    public static void tick(Gui gui) {
        try {
            if (BYPASS.get()) {
                return;
            }
            FRAME_ALPHA.set(1.0F);      // 每 tick 复位一次，动画帧里会在 extract 阶段重新写入
            Screen current = gui.screen();
            if (current == null) {
                return;
            }
            Close close = CLOSING.get(current);
            if (close == null) {
                return;
            }
            if (System.nanoTime() - close.startNanos() < close.durationNanos()) {
                return;
            }
            CLOSING.remove(current);
            OPEN_START.remove(current);
            FINISHED.remove(current);
            synchronized (ITEM_ALPHAS) {
                ITEM_ALPHAS.clear();
            }
            BYPASS.set(true);
            try {
                gui.setScreen(close.target);
            } finally {
                BYPASS.set(false);
            }
        } catch (Throwable t) {
            BYPASS.set(false);
            report("tick", t);
        }
    }

    // ================================================================== 背景层

    /** 背景层开始：容器底板 / 槽位背景都画在这一层里。 */
    public static void beginBackgroundLayer(Screen screen, GuiGraphicsExtractor extractor) {
        try {
            FRAME_ALPHA.set(1.0F);      // 不参与动画时必须复位，否则会残留上一帧的透明度
            if (screen == null || !TransitionConfig.animatePanel()) {
                return;
            }
            if (FINISHED.contains(screen) && !CLOSING.containsKey(screen)) {
                return;
            }
            if (!shouldAnimate(screen)) {
                return;
            }
            float progress = progress(screen);
            if (progress >= 1.0F && !CLOSING.containsKey(screen)) {
                OPEN_START.remove(screen);
                FINISHED.add(screen);
                return;
            }
            beginLayer(screen, extractor, progress, false);
            BACKGROUND_PUSHED.set(true);
            BACKGROUND_EXTRACTOR.set(extractor);      // 供 Hud 里的字幕抵消使用
            BACKGROUND_SCREEN.set(screen);
        } catch (Throwable t) {
            report("beginBackgroundLayer", t);
        }
    }

    /** 背景层结束。 */
    public static void endBackgroundLayer(Screen screen, GuiGraphicsExtractor extractor) {
        try {
            if (BACKGROUND_PUSHED.get()) {
                extractor.pose().popMatrix();
                BACKGROUND_PUSHED.set(false);
            }
            WINDOW_ALPHA.set(1.0F);
        } catch (Throwable t) {
            BACKGROUND_PUSHED.set(false);
            report("endBackgroundLayer", t);
        } finally {
            BACKGROUND_EXTRACTOR.remove();
            BACKGROUND_SCREEN.remove();
        }
    }

    // ================================================================== 内容层

    /** 内容层开始：槽内物品、标题文字等。 */
    public static void beginContentLayer(Screen screen, GuiGraphicsExtractor extractor) {
        FRAME_FADE_ALPHA = 1.0F;      // 新的一帧开始
        PIP_FRAME_ALPHA = 1.0F;
        // 这一帧若不做动画，位移必须归零：LAYER_SHIFT 在上一次动画收尾时才会回到 0，
        // 中途切到不做动画的界面会残留上一次的值，画中画就会被莫名其妙地推开。
        LAYER_SHIFT.set(0.0F);
        try {
            FRAME_ALPHA.set(1.0F);      // 同上：非动画帧一律按不透明处理
            synchronized (ITEM_ALPHAS) {
                ITEM_ALPHAS.clear();    // 每帧清一次，杜绝上一帧的登记值残留到这一帧
            }
            if (screen == null) {
                return;
            }
            if (FINISHED.contains(screen) && !CLOSING.containsKey(screen)) {
                return;
            }
            if (!shouldAnimate(screen)) {
                return;
            }
            float progress = progress(screen);
            if (progress >= 1.0F && !CLOSING.containsKey(screen)) {
                OPEN_START.remove(screen);
                FINISHED.add(screen);
                return;
            }
            beginLayer(screen, extractor, progress, true);
            PUSHED_SCREEN.set(screen);

            if (EFFECTIVE.compareAndSet(false, true)) {
                System.out.println("[UI Transitions] 过渡动画已生效（首个界面: "
                        + screen.getClass().getSimpleName() + "）");
            }
        } catch (Throwable t) {
            report("beginContentLayer", t);
        }
    }

    /** 内容层结束。 */
    public static void endContentLayer(Screen screen, GuiGraphicsExtractor extractor) {
        try {
            if (PUSHED_SCREEN.get() == screen) {
                extractor.pose().popMatrix();
                PUSHED_SCREEN.remove();
                // 这里**故意不复位 WINDOW_ALPHA**。
                //
                // extractRenderStateWithTooltipAndSubtitles 的顺序是：
                //   extractBackground -> extractRenderState -> extractDeferredElements
                // 最后那步画的是物品提示框（那条深色文字框）。之前在这里把透明度复位成 1，
                // 于是界面在淡出、提示框却还全不透明地杵在那儿，一直到动画播完才消失
                // （用户反馈的"动画没做完时有个文字框一直留着"就是它）。
                //
                // 留在内容层的透明度上，提示框就会跟着界面一起淡；
                // 复位交给 endScreenFrame —— 那时整个界面已经画完，不会漏到 HUD。
                WINDOW_ALPHA.set(LAYER_ALPHA.get());
                if (progress(screen) >= 1.0F && !CLOSING.containsKey(screen)) {
                    OPEN_START.remove(screen);
                    FINISHED.add(screen);
                }
            }
        } catch (Throwable t) {
            PUSHED_SCREEN.remove();
            WINDOW_ALPHA.set(1.0F);
            report("endContentLayer", t);
        }
    }

    private static void beginLayer(Screen screen, GuiGraphicsExtractor extractor, float progress,
                                   boolean contentLayer) {
        float shift = shift(screen, progress);
        // 底板与物品各用各的曲线；文字再单独算一条（见 TEXT_ALPHA）
        TransitionConfig.Part part = contentLayer
                ? TransitionConfig.Part.ITEMS : TransitionConfig.Part.PANEL;
        float alpha = TransitionConfig.fade() ? alpha(screen, progress, part) : 1.0F;
        float textAlpha = alpha;
        if (TransitionConfig.fade() && contentLayer && TransitionConfig.fadeText()) {
            textAlpha = alpha(screen, progress, TransitionConfig.Part.TEXT);
        }
        LAYER_SHIFT.set(shift);
        LAYER_ALPHA.set(alpha);
        WINDOW_ALPHA.set(alpha);
        FRAME_ALPHA.set(alpha);
        TEXT_ALPHA.set(textAlpha);
        PIP_FRAME_ALPHA = alpha;       // 帧级：渲染阶段贴画中画时还要用
        // **压栈在这里是无条件的**（历史上这里曾经只在某些分支走到）：
        // endBackgroundLayer / endContentLayer 只看自己那一对 begin 有没有跑过就弹栈，
        // 少压一次就等于多弹一次，整个渲染管线的矩阵栈会错位 ——
        // 后果是后续绘制坐标全偏，甚至把裁剪区算成 0 高/0 宽而崩在渲染阶段
        // （26.3 的裁剪是延迟下发的，堆栈里看不到调用者）。
        // 关掉淡变时位移照旧生效（那本来就是"只滑动、不淡出"），所以这一压栈也是必须的。
        Matrix3x2fStack pose = extractor.pose();
        pose.pushMatrix();
        pose.translate(0.0F, shift);
    }

    // ================================================================== 抵消区（遮罩 / 字幕）

    /**
     * 变暗遮罩：默认不跟着位移，但默认跟着淡出（fadeDim）——
     * 界面淡出时遮罩一起变淡，世界随之变亮，避免动画中途"灰黑一片"。
     */
    public static void pauseForStaticRegion(GuiGraphicsExtractor extractor) {
        try {
            if (!BACKGROUND_PUSHED.get() || STATIC_REGION.get()) {
                return;
            }
            float shift = LAYER_SHIFT.get();
            if (!TransitionConfig.animateDim() && shift != 0.0F) {
                extractor.pose().translate(0.0F, -shift);
            }
            // 遮罩走自己的曲线：拿当前进度重算一次，而不是沿用底板的透明度
            float dimAlpha = LAYER_ALPHA.get();
            Screen dimScreen = BACKGROUND_SCREEN.get();
            if (dimScreen != null && TransitionConfig.fade()) {
                dimAlpha = alpha(dimScreen, progress(dimScreen), TransitionConfig.Part.DIM);
            }
            WINDOW_ALPHA.set(TransitionConfig.fadeDim() ? dimAlpha : 1.0F);
            STATIC_REGION.set(true);
        } catch (Throwable t) {
            report("pauseForStaticRegion", t);
        }
    }

    public static void resumeAfterStaticRegion(GuiGraphicsExtractor extractor) {
        try {
            if (!STATIC_REGION.get()) {
                return;
            }
            STATIC_REGION.set(false);
            float shift = LAYER_SHIFT.get();
            if (!TransitionConfig.animateDim() && shift != 0.0F) {
                extractor.pose().translate(0.0F, shift);
            }
            WINDOW_ALPHA.set(LAYER_ALPHA.get());
        } catch (Throwable t) {
            STATIC_REGION.set(false);
            report("resumeAfterStaticRegion", t);
        }
    }

    /**
     * 音效字幕：顺带在背景层里绘制，默认既不平移也不淡出
     * （否则打开背包时字幕会跟着一起动）。animateSubtitles=true 时让它一起动画。
     *
     * 这一对由 **HudSubtitleMixin** 注入在 {@code Hud.extractDeferredSubtitles} 上，
     * 因此 Screen / PauseScreen / LoadingOverlay 这些调用点都被覆盖 —— 早期版本只在
     * Screen.extractBackground 的调用点做抵消，暂停菜单自己重写了该方法，字幕照样会动。
     */
    public static void pauseForHud() {
        try {
            GuiGraphicsExtractor extractor = BACKGROUND_EXTRACTOR.get();
            if (extractor == null || !BACKGROUND_PUSHED.get() || HUD_PAUSED.get()) {
                return;
            }
            if (TransitionConfig.animateSubtitles()) {
                // 跟随动画：位移照旧跟着走，但**淡变用字幕自己的曲线**。
                // 这里不抵消位移，所以也不置 HUD_SHIFTED，恢复时自然不会反向补回来。
                Screen subtitleScreen = BACKGROUND_SCREEN.get();
                if (subtitleScreen != null && TransitionConfig.fade()) {
                    WINDOW_ALPHA.set(alpha(subtitleScreen, progress(subtitleScreen),
                            TransitionConfig.Part.SUBTITLES));
                }
                HUD_PAUSED.set(true);
                return;
            }
            // 默认：冻结 —— 抵消位移并保持不透明，字幕完全等同原版
            float shift = LAYER_SHIFT.get();
            if (shift != 0.0F) {
                extractor.pose().translate(0.0F, -shift);
                HUD_SHIFTED.set(true);
            }
            WINDOW_ALPHA.set(1.0F);
            HUD_PAUSED.set(true);
        } catch (Throwable t) {
            report("pauseForHud", t);
        }
    }

    public static void resumeAfterHud() {
        try {
            if (!HUD_PAUSED.get()) {
                return;
            }
            HUD_PAUSED.set(false);
            GuiGraphicsExtractor extractor = BACKGROUND_EXTRACTOR.get();
            float shift = LAYER_SHIFT.get();
            // 只有冻结那次真的抵消过位移，才反向补回来
            if (HUD_SHIFTED.get() && extractor != null && shift != 0.0F) {
                extractor.pose().translate(0.0F, shift);
            }
            HUD_SHIFTED.set(false);
            WINDOW_ALPHA.set(LAYER_ALPHA.get());
        } catch (Throwable t) {
            HUD_PAUSED.set(false);
            report("resumeAfterHud", t);
        }
    }

    // ================================================================== 颜色 / 物品

    /** 贴图块、纯色块（含底板与遮罩渐变）的 alpha 调制 */
    public static int applyAlphaBlit(int color) {
        return modulate(color);
    }

    /** 文字（含文字背景）的 alpha 调制，可单独关闭 */
    public static int applyAlphaText(int color) {
        if (!TransitionConfig.fadeText()) {
            return color;
        }
        // 冻结期间（点标签换页时保快捷栏原版观感）**任何乘子都不生效**。
        // 判断放在最前面而不是只靠"把 TEXT_ALPHA 复位成 1"：这样以后往这里加新的乘子，
        // 也不会再需要记得"也要在冻结清单里加一项"（那个清单已经漏过两次）。
        if (TAB_STATIC.get()) {
            return color;
        }
        // 文字用自己那条曲线算出来的透明度（见 TEXT_ALPHA），物品与矩形仍走 WINDOW_ALPHA
        float alpha = TEXT_ALPHA.get();
        // 聊天栏正在淡入时把它乘进来：聊天文字走的正是这条通道
        if (chatFadeActive) {
            alpha *= chatFadeAlpha();
        }
        return modulate(color, alpha);
    }

    private static int modulate(int color) {
        return modulate(color, WINDOW_ALPHA.get());
    }

    private static int modulate(int color, float alpha) {
        try {
            if (alpha >= 0.999F) {
                return color;
            }
            int existing = (color >>> 24) & 0xFF;
            // 透明度已经很低时直接归零：否则尾部几帧会残留一点亮度，看起来像在闪
            if (alpha <= 0.04F) {
                return 0;
            }
            if (PREMULTIPLIED.get()) {
                // 预乘 alpha：RGB 必须一起缩放，否则元素会偏亮（物品尤其明显）
                int r = Math.round(((color >> 16) & 0xFF) * alpha);
                int g = Math.round(((color >> 8) & 0xFF) * alpha);
                int b = Math.round((color & 0xFF) * alpha);
                int a = Math.round(existing * alpha);
                return (a << 24) | (r << 16) | (g << 8) | b;
            }
            int modulated = Math.max(0, Math.min(255, Math.round(existing * alpha)));
            return (color & 0xFFFFFF) | (modulated << 24);
        } catch (Throwable t) {
            report("modulate", t);
            return color;
        }
    }

    /** GuiItemRenderState 构造完成时登记它当时的透明度 */
    public static void tagItem(Object state) {
        try {
            if (!TransitionConfig.fadeItems()) {
                return;
            }
            float alpha = WINDOW_ALPHA.get();
            if (alpha >= 0.999F) {
                return;
            }
            synchronized (ITEM_ALPHAS) {
                ITEM_ALPHAS.put(state, alpha);
            }
        } catch (Throwable t) {
            report("tagItem", t);
        }
    }

    public static void beginItemSubmit(Object state) {
        try {
            if (!TransitionConfig.fadeItems()) {
                WINDOW_ALPHA.set(1.0F);
                return;
            }
            Float alpha;
            synchronized (ITEM_ALPHAS) {
                alpha = ITEM_ALPHAS.get(state);
            }
            // 硬约束：物品的透明度不得超过本帧的动画透明度。
            // 渲染状态可能是动画开始前建立的（登记值偏大），若不夹住，
            // 收尾几帧物品会突然比周围更不透明 —— 看起来就是"闪一下"或发白。
            //
            // 这里必须用 PIP_FRAME_ALPHA 而不是 FRAME_ALPHA：
            // 物品是在**渲染阶段**才从图集提交的，而 FRAME_ALPHA 在 endScreenFrame
            // （提取阶段收尾）就被复位成 1 了 —— 用它等于"没登记过的物品一律全不透明"，
            // 表现就是关闭动画里物品不跟着界面一起淡、留下一排残影。
            // 画中画当初踩的就是同一个坑，见 PIP_FRAME_ALPHA 的注释。
            float frame = PIP_FRAME_ALPHA;
            WINDOW_ALPHA.set(alpha == null ? frame : Math.min(alpha, frame));
            PREMULTIPLIED.set(true);       // 物品贴图是预乘 alpha
        } catch (Throwable t) {
            report("beginItemSubmit", t);
        }
    }

    public static void endItemSubmit(Object state) {
        try {
            WINDOW_ALPHA.set(1.0F);
            PREMULTIPLIED.set(false);
            synchronized (ITEM_ALPHAS) {
                ITEM_ALPHAS.remove(state);
            }
        } catch (Throwable t) {
            report("endItemSubmit", t);
        }
    }

    /** JEI / EMI / REI 是否装了（首次调用时检测一次并缓存） */
    public static boolean hasOverlayMod() {
        Boolean cached = overlayModPresent;
        if (cached != null) {
            return cached;
        }
        boolean found = false;
        ClassLoader loader = UiTransitions.class.getClassLoader();
        for (String className : OVERLAY_MOD_CLASSES) {
            try {
                Class.forName(className, false, loader);
                found = true;
                break;
            } catch (Throwable ignored) {
                // 没装就继续试下一个
            }
        }
        overlayModPresent = found;
        return found;
    }

    /** 供测试用：强制指定"是否有叠加层模组" */
    public static void setOverlayModPresentForTest(Boolean value) {
        overlayModPresent = value;
    }

    /**
     * 界面这一帧的绘制结束：把动画透明度复位。
     * 快捷栏等 HUD 元素是在界面之后、同一帧内绘制的，如果不复位，
     * 它们会被误当成动画的一部分跟着淡出，动画结束后又突然弹回来。
     * 界面自身的物品已在提取阶段登记在册，各自生效，不受这次复位影响。
     */
    /** 分类标签被点选：记一次均匀淡入（scrollDirection = 0） */
    public static void onTabSelected(Screen screen) {
        try {
            TransitionConfig.ensureLoaded();
            if (screen == null || !TransitionConfig.animateTabSwitch()) {
                return;
            }
            TAB_SWITCH.put(screen, new TabSwitch(System.nanoTime(), 0.0F));
            GRID_BOUNDS.remove(screen);
        } catch (Throwable t) {
            report("onTabSelected", t);
        }
    }

    /**
     * 每帧调用：只有滚动位置**真的变了**才刷新逐格渐变的时间基准。
     *
     * 不能挂在输入事件上 —— 手机上每次点击都会被映射成拖动事件，
     * 但这里已经用"滚动位置是否真的变了"兜住了：单纯点一下不会触发。
     *
     * 时间基准每次滚动都**重新计时**，也就是这段渐变随时可以被打断、立刻跟上手速。
     * 早先版本加了"上一段没播完就不重置"的守卫，结果拖动时画面要等一整段动画
     * 走完才更新一次 —— 手感上就是"隔一会儿才刷新一下"，动画时长调长之后尤其明显。
     *
     * 当初那个守卫是为了避免"重置起点导致物品一直停在近乎空白"：
     * 那说的是**整片均匀**淡变的老实现。现在每格用的是按离进入边距离算出的
     * 各自下限（见 slotFloorAlpha），远离进入边的格子基本保持不透明，
     * 反复重置只会让进入边一直保持淡出 —— 那正是滚动时想要的反馈。
     */
    public static void onGridScrollIfChanged(Screen screen, float scrollOffs) {
        try {
            TransitionConfig.ensureLoaded();
            if (screen == null || !TransitionConfig.animateTabSwitch()) {
                return;
            }
            Float previous = LAST_SCROLL.get(screen);
            LAST_SCROLL.put(screen, scrollOffs);
            if (previous == null || Math.abs(previous - scrollOffs) < 0.0005F) {
                return;      // 没真的滚动：保持现状，让当前这段继续往 1 收敛
            }
            float direction = scrollOffs > previous ? 1.0F : -1.0F;
            TAB_SWITCH.put(screen, new TabSwitch(System.nanoTime(), direction));
        } catch (Throwable t) {
            report("onGridScrollIfChanged", t);
        }
    }

    /** 当前界面是否正处在"原地淡变"窗口（标签切换 / 滚动），返回整体进度；1 = 不介入 */
    private static float inPlaceFadeProgress(Screen screen) {
        TabSwitch state = screen == null ? null : TAB_SWITCH.get(screen);
        if (state == null) {
            return 1.0F;
        }
        float ms = Math.max(TransitionConfig.MIN_TAB_SWITCH_MS,
                TransitionConfig.tabSwitchMs()) * 1_000_000.0F;
        return clamp01((System.nanoTime() - state.startNanos()) / ms);
    }

    /**
     * 逐槽位的透明度。
     *
     * @param pinToVanilla true = 这一格固定为原版观感（玩家快捷栏那一排），完全不参与淡变
     * @param slotY        槽位的纵坐标，用来算"离进入边多远"
     *
     * 两种情形共用一条公式：alpha = base + (1 - base) * eased
     *   · 点标签：base 是全区域统一的 FADE_FLOOR，整片柔和浮现
     *   · 滚动  ：base 按槽位离进入边的距离逐格变化，越靠进入边越淡
     * 用"从 base 升到 1"而不是从 0 开始，是为了避免整片透明闪一下。
     */
    public static void beginSlotFade(boolean pinToVanilla, int slotY) {
        try {
            SLOT_FADED.set(false);
            Screen screen = IN_PLACE_SCREEN.get();
            float progress = inPlaceFadeProgress(screen);
            if (progress >= 1.0F || !TransitionConfig.fade()) {
                return;      // 不在原地淡变窗口里：什么都不做
            }
            SLOT_SAVED_ALPHA.set(WINDOW_ALPHA.get());
            SLOT_FADED.set(true);
            if (pinToVanilla) {
                // 玩家快捷栏：固定原版，既不淡也不动
                WINDOW_ALPHA.set(1.0F);
                FRAME_ALPHA.set(1.0F);
                return;
            }
            float eased = curveFor(screen, TransitionConfig.Part.TAB).easeOut(progress);
            float base = slotFloorAlpha(screen, slotY);
            float alpha = base + (1.0F - base) * eased;
            WINDOW_ALPHA.set(alpha);
            FRAME_ALPHA.set(alpha);
        } catch (Throwable t) {
            report("beginSlotFade", t);
        }
    }

    public static void endSlotFade(int slotY) {
        try {
            if (!SLOT_FADED.get()) {
                return;
            }
            Float saved = SLOT_SAVED_ALPHA.get();
            if (saved != null) {
                WINDOW_ALPHA.set(saved);
                FRAME_ALPHA.set(saved);
            }
            noteGridSlot(slotY);
        } catch (Throwable t) {
            report("endSlotFade", t);
        }
    }

    /** 这一格淡变的起点透明度：标签切换 = 统一下限；滚动 = 按离进入边的距离逐格变化 */
    private static float slotFloorAlpha(Screen screen, int slotY) {
        TabSwitch state = screen == null ? null : TAB_SWITCH.get(screen);
        if (state == null || state.scrollDirection() == 0.0F) {
            return FADE_FLOOR;                       // 点标签：全区域统一
        }
        float[] bounds = GRID_BOUNDS.get(screen);
        if (bounds == null) {
            return FADE_FLOOR;                       // 还没有上一帧的边界：先按统一下限来
        }
        // 进入侧：向下滚时物品往上走，新格从**底部**进来，所以离底部越近越淡
        float enterEdge = state.scrollDirection() > 0.0F ? bounds[1] : bounds[0];
        float distance = Math.abs(slotY - enterEdge);
        float band = Math.max(1.0F, TransitionConfig.scrollFadeBand());
        float minAlpha = Math.max(0.0F, Math.min(1.0F, TransitionConfig.scrollFadeMin() / 100.0F));
        float ratio = clamp01(distance / band);
        return minAlpha + (1.0F - minAlpha) * ratio;
    }

    /** 记录本帧格子区的上下界（供下一帧算渐变） */
    private static void noteGridSlot(int slotY) {
        float[] now = GRID_BOUNDS_NOW.get();
        if (now == null) {
            GRID_BOUNDS_NOW.set(new float[] { slotY, slotY });
        } else {
            now[0] = Math.min(now[0], slotY);
            now[1] = Math.max(now[1], slotY);
        }
    }

    /** 换页动画：只包内容层，底板与标签栏在背景层、天然不动 */
    public static void beginTabContent(Screen screen, GuiGraphicsExtractor extractor) {
        try {
            // 先登记当前界面：SlotFadeMixin 靠它知道"这一格属于哪个界面"，
            // 即使界面早已静止、没有打开/关闭动画也必须能取到。
            IN_PLACE_SCREEN.set(screen);
            if (screen == null || TAB_SWITCH.get(screen) == null) {
                return;
            }
            float progress = inPlaceFadeProgress(screen);
            if (progress >= 1.0F) {
                TAB_SWITCH.remove(screen);
                return;
            }
            float eased = TransitionConfig.openCurve().easeOut(progress);
            // 标签切换 / 滚动只做淡变，**不做任何位移**。
            float alpha = TransitionConfig.fade() ? (FADE_FLOOR + (1.0F - FADE_FLOOR) * eased) : 1.0F;
            WINDOW_ALPHA.set(alpha);
            FRAME_ALPHA.set(alpha);
            FRAME_FADE_ALPHA = alpha;      // 帧级：界面画完后 HUD 快捷栏还能读到
            PIP_FRAME_ALPHA = alpha;
        } catch (Throwable t) {
            report("beginTabContent", t);
        }
    }

    /**
     * 快捷栏：在换页动画期间保持原版观感（不跟着一起淡）。
     *
     * 注意必须把**所有**影响快捷栏的透明度来源一起冻住 —— 这里已经栽过两次：
     *
     *   · 第 1 次（1.4.0）：快捷栏物品走物品图集那条路。把 WINDOW_ALPHA 置回 1 之后
     *     `tagItem` 认为"不用登记"，提交时取不到登记值就回退到 `PIP_FRAME_ALPHA` ——
     *     它还是淡变中的值，于是快捷栏物品跟着一起淡。
     *   · 第 2 次（1.5.01）：漏了 `TEXT_ALPHA`。快捷栏物品的**数量文字**走文字通道，
     *     而 `TEXT_ALPHA` 由 `beginLayer` 设成"文字部位"的淡化值、只有帧末才复位 ——
     *     于是图标不淡、**数字在淡**，看起来还是"快捷栏跟着一起渐变"。
     *
     * 教训：这里是一张"透明度来源清单"，**新增任何透明度来源都必须同步加进来**。
     * 现在的清单：WINDOW_ALPHA / FRAME_ALPHA / PIP_FRAME_ALPHA / TEXT_ALPHA。
     *
     * `TAB_STATIC` 标志放在**最前面**置位：`applyAlphaText` 里还有别的乘子
     * （聊天淡入），将来还可能再加。先立标志、后做事，才能保证"冻结期间任何乘子都不生效"。
     */
    public static void pauseForTabStatic(GuiGraphicsExtractor extractor) {
        try {
            // 先立标志：哪怕下面的保存/复位出了岔子，本帧也不该再叠别的乘子
            boolean alreadyStatic = TAB_STATIC.get();
            TAB_STATIC.set(true);
            if (alreadyStatic || FRAME_FADE_ALPHA >= 0.999F) {
                return;       // 本来就不在淡变中，没什么可冻的（但标志留着，见上）
            }
            TAB_STATIC_SAVED_PIP.set(PIP_FRAME_ALPHA);
            TAB_STATIC_SAVED_TEXT.set(TEXT_ALPHA.get());
            WINDOW_ALPHA.set(1.0F);
            FRAME_ALPHA.set(1.0F);
            TEXT_ALPHA.set(1.0F);
            PIP_FRAME_ALPHA = 1.0F;
        } catch (Throwable t) {
            TAB_STATIC.set(true);
            report("pauseForTabStatic", t);
        }
    }

    public static void resumeAfterTabStatic(GuiGraphicsExtractor extractor) {
        try {
            if (!TAB_STATIC.get()) {
                return;
            }
            PIP_FRAME_ALPHA = TAB_STATIC_SAVED_PIP.get();
            TEXT_ALPHA.set(TAB_STATIC_SAVED_TEXT.get());
            TAB_STATIC.set(false);
        } catch (Throwable t) {
            TAB_STATIC.set(false);
            report("resumeAfterTabStatic", t);
        }
    }

    public static void endTabContent(Screen screen, GuiGraphicsExtractor extractor) {
        try {
            float[] now = GRID_BOUNDS_NOW.get();
            if (now != null && screen != null) {
                GRID_BOUNDS.put(screen, now);
            }
            GRID_BOUNDS_NOW.remove();
            SLOT_SAVED_ALPHA.remove();
            SLOT_FADED.set(false);
            IN_PLACE_SCREEN.remove();
            WINDOW_ALPHA.set(1.0F);
            FRAME_ALPHA.set(1.0F);
        } catch (Throwable t) {
            IN_PLACE_SCREEN.remove();
            report("endTabContent", t);
        }
    }

    public static void endScreenFrame() {
        try {
            FRAME_ALPHA.set(1.0F);
            WINDOW_ALPHA.set(1.0F);
            TEXT_ALPHA.set(1.0F);
        } catch (Throwable t) {
            report("endScreenFrame", t);
        }
    }

    /**
     * 画中画内容（背包里的玩家小模型、书、地图预览等）在渲染阶段贴回界面。
     *
     * 注意时序：画中画是在提取阶段产出渲染状态、在渲染阶段（GuiRenderer.render →
     * preparePictureInPicture → blitTexture）才真正贴回去的。所以这里读的是
     * PIP_FRAME_ALPHA —— 它跨阶段保留到下一帧开始。
     */
    public static void beginPipBlit(Object state) {
        try {
            if (!TransitionConfig.fade()) {
                PIP_SHIFT.set(0.0F);
                return;
            }
            // 让画中画跟着界面一起位移（内容层滑多少，它就滑多少）
            PIP_SHIFT.set(LAYER_SHIFT.get());
            // 布娃娃 / 附魔书 / 地图 / 旗帜预览一视同仁：用本帧内容层的透明度一起淡，
            // 不再给玩家模型搞"延迟浮现 + 关闭即隐藏"的特殊处理（那会在面板滑走后留下孤零零一个模型）
            WINDOW_ALPHA.set(PIP_FRAME_ALPHA);
            PIP_BLITTING.set(true);
        } catch (Throwable t) {
            PIP_SHIFT.set(0.0F);
            report("beginPipBlit", t);
        }
    }

    /**
     * 把当前动画位移叠加到画中画的位姿上，交给 PictureInPictureRendererMixin 使用。
     *
     * 叠加而不是替换：贴图自己的 pose 以后若不再恒为单位矩阵，这里也不会丢信息。
     */
    public static org.joml.Matrix3x2fc shiftPipPose(org.joml.Matrix3x2fc pose) {
        try {
            float shift = PIP_SHIFT.get();
            if (shift == 0.0F || pose == null) {
                return pose;
            }
            return new org.joml.Matrix3x2f(pose).translate(0.0F, shift);
        } catch (Throwable t) {
            report("shiftPipPose", t);
            return pose;
        }
    }

    public static void endPipBlit() {
        try {
            if (PIP_BLITTING.get()) {
                PIP_BLITTING.set(false);
                WINDOW_ALPHA.set(1.0F);
            }
            PIP_SHIFT.set(0.0F);
        } catch (Throwable t) {
            PIP_BLITTING.set(false);
            PIP_SHIFT.set(0.0F);
            report("endPipBlit", t);
        }
    }

    // ================================================================== 聊天栏淡入

    /**
     * 聊天栏"新消息淡入"的计时与状态。
     *
     * 用**挂钟时间**：26.3 没有现成的"当前 GUI tick"可读（`Gui` 上没有 getGuiTicks，
     * `Line.addedTime()` 的基准也拿不到），而"这一块聊天上次变化是什么时候"
     * 本身就是最直接的信号。
     */
    private static volatile long chatFadeStartNanos;
    private static volatile boolean chatFadeActive;
    /** 诊断计数：重开接续只打前若干次，避免刷屏 */
    private static int DEBUG_REOPEN;
    /** 上一帧聊天的"指纹"：行数与文字内容，用来判断有没有新消息进来 */
    private static volatile int chatLineCount = -1;
    private static volatile int chatContentHash;

    /**
     * 聊天开始绘制（ChatFadeMixin 注入在 ChatComponent.extractRenderState 的 HEAD）。
     *
     * 这里判断"是不是有新消息"，然后决定这一帧的透明度走不走淡入。
     */
    public static void beginChatRender() {
        try {
            // **只在真的处于淡入窗口里才介入**：这个标志会被每次文字绘制读到（applyAlphaText），
            // 常开就等于给整帧的文字都加一次多余的乘法。淡完了就关掉，回到零开销。
            chatFadeActive = isChatFadeConfigured() && chatFadeStartNanos != 0L
                    && System.nanoTime() - chatFadeStartNanos < chatFadeNanos();
        } catch (Throwable t) {
            chatFadeActive = false;
            report("beginChatRender", t);
        }
    }

    public static void endChatRender() {
        // 不动 chatFadeActive：它由"是否还在淡入窗口里"决定，跨帧保持是刻意的
    }

    private static boolean isChatFadeConfigured() {
        return TransitionConfig.enabled() && TransitionConfig.fade()
                && TransitionConfig.chatFadeMs() > 0;
    }

    private static long chatFadeNanos() {
        return TransitionConfig.chatFadeMs() * 1_000_000L;
    }

    /**
     * 聊天栏新消息是不是正在淡入；是的话返回当前该乘的系数（否则恒为 1）。
     *
     * 计时由"聊天内容指纹变了"触发（见 {@link #noteChatContent}），
     * 与"哪一帧在画聊天"无关 —— 这样即使聊天被挡住不画，淡入窗口也会自然走完。
     */
    public static float chatFadeAlpha() {
        if (!chatFadeActive) {
            return 1.0F;
        }
        try {
            long start = chatFadeStartNanos;
            if (start == 0L) {
                return 1.0F;
            }
            long fadeNanos = chatFadeNanos();
            long age = System.nanoTime() - start;
            if (age >= fadeNanos) {
                chatFadeActive = false;
                return 1.0F;
            }
            float progress = age / (float) fadeNanos;
            return TransitionConfig.curveForCategory(TransitionConfig.UiCategory.CHAT).easeOut(progress);
        } catch (Throwable t) {
            report("chatFadeAlpha", t);
            chatFadeActive = false;
            return 1.0F;
        }
    }

    /**
     * 记录当前聊天的"指纹"，变了就重新开始淡入。
     *
     * 指纹取"行数 + 内容哈希"：行数变了说明有新消息（或被删）；
     * 内容哈希是为了覆盖"滚动、消息被替换"这些不改行数的情况 ——
     * 宁可多淡一次，也不要"新消息直接蹦出来"。
     */
    public static void noteChatContent(int lines, int contentHash) {
        try {
            if (lines == chatLineCount && contentHash == chatContentHash) {
                return;
            }
            chatLineCount = lines;
            chatContentHash = contentHash;
            if (isChatFadeConfigured()) {
                chatFadeStartNanos = System.nanoTime();
                chatFadeActive = true;
            }
        } catch (Throwable t) {
            report("noteChatContent", t);
        }
    }

    /** 换世界/重载界面时复位，避免拿着上一局的计时继续算 */
    public static void resetChatFade() {
        chatLineCount = -1;
        chatContentHash = 0;
        chatFadeStartNanos = 0L;
        chatFadeActive = false;
    }

    // ================================================================== 判定与计算

    public static boolean shouldAnimate(Screen screen) {
        if (screen == null) {
            return false;
        }
        try {
            TransitionConfig.ensureLoaded();
            if (!TransitionConfig.enabled()) {
                return false;
            }
            String name = screen.getClass().getName();
            TransitionConfig.noteSeenScreen(name);      // 记下来给配置界面做提示
            if (TransitionConfig.isExcluded(name)) {
                noteSkip(screen, "在排除列表里");
                return false;
            }
            // 跨维度加载界面**不参与常规动画**：26.3 在换维度期间会反复创建/销毁它
            // （真实日志里连着十几次），包一层必然与它打架。它由专门的全屏遮罩负责，
            // 效果稳定得多 —— 见 startPortalVeil。
            if (isPortalLoading(screen)) {
                return false;
            }
            if (TransitionConfig.animateAllScreens()) {
                return true;
            }
            // 其它加载/等待类界面（存档进度之类）没有这个问题，正常放行
            if (isWaitingScreen(screen)) {
                return true;
            }
            if (screen instanceof AbstractContainerScreen<?> || TransitionConfig.isExtraScreen(name)) {
                return true;
            }
            noteSkip(screen, "不是容器界面，也不在额外适配列表里");
            return false;
        } catch (Throwable t) {
            report("shouldAnimate", t);
            return false;
        }
    }

    /** 每个类只记一次，免得刷屏 */
    private static final Set<String> SKIP_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 记一条"这个界面为什么没做动画"。
     *
     * 排查"某个界面怎么没效果"时，没这条日志就只能靠猜 ——
     * 而白名单判定恰恰是最容易静默失效的地方。
     */
    private static void noteSkip(Screen screen, String why) {
        try {
            if (SKIP_LOGGED.size() > 200) {
                return;
            }
            if (SKIP_LOGGED.add(screen.getClass().getName() + "|" + why)) {
                log("不做动画: " + screen.getClass().getSimpleName() + " —— " + why);
            }
        } catch (Throwable ignored) {
            // 日志失败不影响功能
        }
    }

    /** 统一前缀，方便在 logs/latest.log 里搜 */
    private static void log(String message) {
        System.out.println("[UI Transitions] " + message);
    }

    /** 上一次真正记过的切屏内容，用来去重 */
    private static volatile String lastScreenChange = "";

    // ================================================================== 跨维度过渡遮罩

    /**
     * 跨维度（末地门 / 地狱门）时的过渡，用一层**全屏遮罩**来做，而不是包装那个界面。
     *
     * 为什么不包装界面：真实日志显示 26.3 在换维度期间会**反复创建/销毁**
     * LevelLoadingScreen（"切屏: (无) -> LevelLoadingScreen" 连着出现十几次，
     * 每次在屏不到一秒）。淡入还没走完界面就被撤掉、紧接着又新建一个 ——
     * 淡入淡出互相叠加，界面永远到不了全不透明，观感上就是"完全没效果"。
     *
     * 遮罩是独立于界面生命周期的：换维度期间一直保持全黑，等这阵子过去再缓缓淡出，
     * 于是"进去时缓缓切入、出来时缓缓切出、逐渐变为透明"这两件事都能稳定做到。
     */
    private static volatile long veilStartNanos;
    private static volatile long veilDurationNanos;
    private static volatile boolean veilActive;
    /** 这一轮遮罩最早是什么时候开始的：用来兜底，防止界面反复创建把遮罩一直顶住 */
    private static volatile long veilFirstStartNanos;
    /** 遮罩最长持续这么久，超过就强制放开 —— 宁可少淡一下，也不能一直挡着画面 */
    private static final long VEIL_MAX_ACTIVE_MS = 5000L;

    /** 在跨维度加载界面出现/消失时调用：把遮罩拉满，然后交给它自己淡出 */
    public static void startPortalVeil(Screen screen) {
        try {
            if (!TransitionConfig.enabled()) {
                return;
            }
            long now = System.nanoTime();
            if (!veilActive) {
                veilFirstStartNanos = now;
            } else if ((now - veilFirstStartNanos) / 1_000_000L > VEIL_MAX_ACTIVE_MS) {
                // 兜底：26.3 换维度期间会反复创建/销毁那个界面，每次都会把遮罩重新拉满。
                // 万一这阵子拖得很长，遮罩就会一直黑着 —— 到期直接放开。
                if (veilActive) {
                    veilActive = false;
                    log("跨维度遮罩超过 " + (VEIL_MAX_ACTIVE_MS / 1000) + " 秒，强制结束（避免一直挡着画面）");
                }
                return;
            }
            veilStartNanos = now;
            veilDurationNanos = millisToNanos(TransitionConfig.portalDurationMs(), durationFallback());
            if (!veilActive) {
                log("跨维度过渡遮罩启动: " + (screen == null ? "(无)"
                        : screen.getClass().getSimpleName() + " reason=" + portalReason(screen))
                        + " 淡出时长=" + (veilDurationNanos / 1_000_000L) + "ms");
            }
            veilActive = true;
        } catch (Throwable t) {
            report("startPortalVeil", t);
        }
    }

    /** 1 = 全黑，0 = 完全透明 */
    private static float veilAlpha() {
        if (!veilActive) {
            return 0.0F;
        }
        long now = System.nanoTime();
        long elapsed = now - veilStartNanos;
        boolean tooLong = (now - veilFirstStartNanos) / 1_000_000L > VEIL_MAX_ACTIVE_MS;
        // 时长为 0 表示"不要这个效果"：直接当作没有遮罩（下限已经放开到 0）
        if (veilDurationNanos <= 0L || elapsed >= veilDurationNanos || tooLong) {
            veilActive = false;
            if (tooLong) {
                log("跨维度遮罩结束（超时兜底）");
            }
            return 0.0F;
        }
        float p = elapsed / (float) veilDurationNanos;
        // 从全黑缓缓退到透明；走「传送门」自己的曲线（没单独配就跟随全局渐入曲线）
        return 1.0F - TransitionConfig.curveFor(TransitionConfig.Part.PORTAL, false).easeOut(p);
    }

    // ================================================================== 自绘预览用的透明度通道

    private static final ThreadLocal<Float> PREVIEW_ALPHA_SAVED = ThreadLocal.withInitial(() -> 1.0F);

    /**
     * 把接下来几次绘制的透明度交给模组自己的通道（曲线编辑器的预览用）。
     *
     * 贴图和色块最终都会经过 BlitRenderState / ColoredRectangleRenderState，
     * 它们统一读 WINDOW_ALPHA —— 所以这里改一下就能让整个预览一起淡。
     * 必须配对调用 popPreviewAlpha，否则透明度会泄漏到别的地方。
     */
    public static void pushPreviewAlpha(float alpha) {
        try {
            PREVIEW_ALPHA_SAVED.set(WINDOW_ALPHA.get());
            WINDOW_ALPHA.set(Math.max(0.0F, Math.min(1.0F, alpha)));
        } catch (Throwable t) {
            report("pushPreviewAlpha", t);
        }
    }

    public static void popPreviewAlpha() {
        try {
            WINDOW_ALPHA.set(PREVIEW_ALPHA_SAVED.get());
        } catch (Throwable t) {
            report("popPreviewAlpha", t);
        }
    }

    /** 遮罩绘制日志只记前若干次，避免刷屏 */
    private static int VEIL_DRAW_LOGGED;

    /** 画遮罩。界面存在时由 Screen 的收尾注入调用，没有界面时由 HUD 注入调用。 */
    public static void drawPortalVeil(GuiGraphicsExtractor extractor) {
        try {
            if (!TransitionConfig.enabled()) {
                return;
            }
            // 这里**不再**看 portalFadeOnly：那个开关的意思是"要不要滑动"，
            // 而遮罩方案本来就是只淡变。之前拿它当总开关，用户一关掉就彻底没有遮罩，
            // 表现成"一点效果都没有"（真实日志里就是 100ms + 开关状态不明）。
            float alpha = veilAlpha();
            if (alpha <= 0.004F) {
                return;
            }
            int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
            // 前若干次绘制记一行：这样"遮罩到底有没有在画、画出来的透明度是多少"
            // 就能从日志直接确认，不用再靠肉眼猜（这个功能已经因为看不见而返工过几次）
            if (VEIL_DRAW_LOGGED < 40) {
                VEIL_DRAW_LOGGED++;
                log("遮罩绘制 alpha=" + String.format(java.util.Locale.ROOT, "%.2f", alpha)
                        + " 尺寸=" + extractor.guiWidth() + "x" + extractor.guiHeight());
            }
            // 先清掉可能残留的裁剪区：界面画到最后常常还开着 scissor，
            // 全屏填充被它一裁就只剩中间一块方框。
            // 单独包一层：万一这个调用本身不被支持，也不能连累下面的填充。
            try {
                extractor.disableScissor();
            } catch (Throwable ignored) {
                // 取不到就算了，大不了被裁
            }
            extractor.fill(0, 0, extractor.guiWidth(), extractor.guiHeight(), a << 24);
        } catch (Throwable t) {
            report("drawPortalVeil", t);
        }
    }

    /**
     * 记一条切屏日志，但**内容没变就不重复记**。
     *
     * 关闭动画期间游戏会反复调用 setScreen(null)，不去重的话一次关界面就能刷出几十行
     * 一模一样的 "A -> (无)"；真实日志里已经被淹过一次，排查时反而更难读。
     */
    private static void logScreenChange(Screen current, Screen target) {
        try {
            String line = (current == null ? "(无)" : current.getClass().getSimpleName())
                    + " -> " + (target == null ? "(无)" : target.getClass().getSimpleName())
                    + (target != null && isPortalLoading(target) ? "【跨维度加载界面】" : "");
            if (line.equals(lastScreenChange)) {
                return;
            }
            lastScreenChange = line;
            log("切屏: " + line);
        } catch (Throwable ignored) {
            // 日志失败不影响功能
        }
    }

    /**
     * 这一段动画该用哪条曲线：关闭中用渐出曲线，其余（打开、原地淡变）用渐入曲线。
     * 曲线可以在配置里分开设，所以不能再统一读 TransitionConfig.curve()。
     */
    private static TransitionConfig.Curve curveFor(Screen screen) {
        return curveFor(screen, TransitionConfig.Part.PANEL);
    }

    /** 按部位取曲线：部位没单独配就回退到"该界面所属分类"的曲线（再回退到全局） */
    private static TransitionConfig.Curve curveFor(Screen screen, TransitionConfig.Part part) {
        boolean closing = CLOSING.containsKey(screen);
        String id = TransitionConfig.partCurveId(part, closing);
        // 部位没单独配：优先用这一**类界面**的曲线，仍没配就回退全局（curveFor 内部会处理）
        if (part != null && TransitionConfig.Curve.FOLLOW_ID.equals(id) && screen != null) {
            TransitionConfig.UiCategory category = categoryOf(screen);
            if (!TransitionConfig.Curve.FOLLOW_ID.equals(
                    TransitionConfig.categoryCurveId(category))) {
                return TransitionConfig.curveForCategory(category);
            }
        }
        return TransitionConfig.curveFor(part, closing);
    }

    /** 当前"可见透明度"（关闭中递减、打开中递增），用于打断时接续 */
    private static float visualAlpha(Screen screen) {
        float p = progress(screen);
        TransitionConfig.Curve curve = curveFor(screen);
        return CLOSING.containsKey(screen) ? 1.0F - curve.easeIn(p) : curve.easeOut(p);
    }

    /**
     * 反解出新动画的进度，使其在当前这一刻的可见透明度与 targetAlpha 一致。
     * closing=true 时目标曲线是 1-easeIn(p)（递减），否则是 easeOut(p)（递增）。
     *
     * 用二分法而不是解析求逆：曲线是可配置的，二分 40 次足够精确，而且只在打断时执行一次。
     */
    private static float solveProgress(TransitionConfig.Curve curve, float targetAlpha, boolean closing) {
        float lo = 0.0F;
        float hi = 1.0F;
        for (int i = 0; i < 40; i++) {
            float mid = (lo + hi) / 2.0F;
            float value = closing ? 1.0F - curve.easeIn(mid) : curve.easeOut(mid);
            boolean below = closing ? value > targetAlpha : value < targetAlpha;
            if (below) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2.0F;
    }

    /** 把"进度"换算成需要回拨的时间（毫秒 → 纳秒），时长取即将开始的那次动画的 */
    private static long backdateNanos(float progress, long durationNanos) {
        float clamped = Math.max(0.0F, Math.min(1.0F, progress));
        if (clamped <= 0.0001F) {
            return 0L;
        }
        return (long) (clamped * durationNanos);
    }

    /**
     * 动画进度 0..1。
     *
     * 每次都换算成"这一段动画自己的时长"：不能在动画进行中读当前的 durationMs ——
     * 用户在配置界面把时长从 200 调到 2000 再关掉配置界面时，
     * 正在进行的那段动画会突然按新时长重新换算，进度会跳变（表现为动画倒退或直接闪完）。
     * 同理，打断接续时的回拨也必须用同一个时长，否则新动画的起点是错的。
     */
    private static float progress(Screen screen) {
        long now = System.nanoTime();
        Close close = CLOSING.get(screen);
        if (close != null) {
            return clamp01((now - close.startNanos) / (float) close.durationNanos());
        }
        if (FINISHED.contains(screen)) {
            return 1.0F;
        }
        Open open = OPEN_START.get(screen);
        if (open == null) {
            OPEN_START.put(screen, new Open(now, openDurationNanos(screen)));
            return 0.0F;
        }
        return clamp01((now - open.startNanos()) / (float) open.durationNanos());
    }

    /**
     * 内容层（物品 + 文字 + 玩家小模型）在关闭时提前结束淡出的比例：
     * 0.92 表示它比底板早约 8% 走完 —— 刚好够避免露出空洞，又几乎看不出先后。
     * 之前是 0.55（早 45%），动画一长就能明显看出"先消失的痕迹"。
     */
    /**
     * 原地淡变的起点透明度（点分类标签时整个物品区从这里淡到 1）。
     *
     * 早先是 0.62：担心"整片透明会像闪一下"，于是只让它在 62%→100% 之间动。
     * 实际观感是这点变化太小、几乎看不出来。现在放宽到 0.25 —— 配合 600ms 的时长，
     * 既能明显看出一次淡入，又不至于整片消失后突然冒出来。
     * （滚动走的是另一条路：按离进入边的距离逐格算，见 slotFloorAlpha。）
     */
    private static final float FADE_FLOOR = 0.25F;

    private static final float CONTENT_FADE_SPAN = 0.92F;

    private static float alpha(Screen screen, float progress, TransitionConfig.Part part) {
        TransitionConfig.Curve curve = curveFor(screen, part);
        boolean contentLayer = part == TransitionConfig.Part.ITEMS
                || part == TransitionConfig.Part.TEXT;
        if (CLOSING.containsKey(screen)) {
            float span = 1.0F;
            if (contentLayer && TransitionConfig.staggerClose()) {
                span = CONTENT_FADE_SPAN;
            }
            float scaled = Math.min(1.0F, progress / span);
            return 1.0F - curve.easeIn(scaled);
        }
        return curve.easeOut(progress);
    }

    /**
     * 跨维度（末地门 / 地狱门）以及进世界时那个"正在生成世界"的加载界面。
     *
     * 26.3 里就是 LevelLoadingScreen —— 它自己带一个 Reason 字段，
     * 取值恰好是 NETHER_PORTAL / END_PORTAL / OTHER，用来区分是哪种传送门。
     * 客户端收到换维度的包之后是这么显示的：
     *   new LevelLoadingScreen(levelLoadTracker, reason) → Minecraft.setScreenAndShow(...)
     * 而 setScreenAndShow 内部转调 Gui.setScreen，正是本模组拦截的那个入口。
     *
     * **只有这一类**才用"只淡变 + 独立时长"：存档、回标题那些 ProgressScreen
     * 也属于加载界面，但它们该按普通界面的时长走 —— 否则用户把传送门时长调到 5 秒，
     * 存个档也要淡 5 秒，看着就像卡住了（真实日志里出现过）。
     */
    private static boolean isPortalLoading(Screen screen) {
        if (screen == null) {
            return false;
        }
        String simple = screen.getClass().getSimpleName();
        return simple.contains("LevelLoading") || simple.contains("ReceivingLevel");
    }

    /**
     * 广义的"加载/等待"界面：只用来决定**要不要给它做动画**。
     *
     * 按关键字匹配而不是精确类名 —— 精确匹配一旦对不上就是彻底静默失效，
     * 这个功能已经因为"以为匹配上了"返工过一次。放行动画本身没有副作用
     * （时长与位移仍按普通规则来），所以这里宁宽勿窄。
     */
    private static boolean isWaitingScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String simple = screen.getClass().getSimpleName();
        return simple.contains("Loading")
                || simple.contains("Waiting")
                || simple.contains("Receiving")
                || simple.contains("Downloading")
                || simple.contains("Progress");
    }

    /** 读一下 LevelLoadingScreen 的 reason，日志里能看出是哪种传送门（读不到就返回 "?"） */
    private static String portalReason(Screen screen) {
        try {
            java.lang.reflect.Field field = screen.getClass().getDeclaredField("reason");
            field.setAccessible(true);
            Object value = field.get(screen);
            return value == null ? "?" : value.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 这一段动画该位移多少像素（跨维度那类界面根本不参与常规动画，所以不用特判） */
    private static float offsetFor(Screen screen) {
        return TransitionConfig.offset();
    }

    private static float shift(Screen screen, float progress) {
        // 装了 JEI 这类"在容器界面上叠固定按钮"的模组时，改成只淡变不位移：
        // 那些按钮画在同一条渲染层里，只能靠整个界面不滑来让它们待在原地。
        if (TransitionConfig.overlayModsFadeOnly() && hasOverlayMod()) {
            return 0.0F;
        }
        float offset = offsetFor(screen);
        TransitionConfig.Curve curve = curveFor(screen);
        if (CLOSING.containsKey(screen)) {
            float direction = TransitionConfig.closeToBottom() ? 1.0F : -1.0F;
            return direction * offset * curve.easeIn(progress);
        }
        float direction = TransitionConfig.openFromBottom() ? 1.0F : -1.0F;
        float base = direction * offset * (1.0F - curve.easeOut(progress));

        // 果冻（回弹）：打开时冲过静止位置再回落。
        // 包络 4m(1-m) 在两端为 0，因此起步与收尾都精确停在基准位置，不会留下残移。
        float jelly = TransitionConfig.jelly();
        if (jelly > 0.0F) {
            float remaining = 1.0F - curve.easeOut(progress);
            float envelope = 4.0F * remaining * (1.0F - remaining);
            float wobble = (float) Math.sin(progress * Math.PI * 6.0);
            base += direction * offset * jelly * 0.55F * envelope * wobble;
        }
        return base;
    }

    /**
     * 某个界面属于哪一类（聊天栏 / 创造物品栏 / 游戏菜单 / 容器 / 传送门 / 其它）。
     *
     * 分类决定"这一类界面用哪条曲线、多长时长"；没单独配过的分类会自动跟随全局，
     * 所以老配置的行为完全不变。
     */
    private static TransitionConfig.UiCategory categoryOf(Screen screen) {
        return screen == null
                ? TransitionConfig.UiCategory.OTHER
                : TransitionConfig.UiCategory.of(screen.getClass().getName());
    }

    /**
     * 渐入（打开）这一段动画的时长（纳秒）。
     *
     * 用 TransitionConfig 的上下限常量，而不是在这里另外写死一组数字 ——
     * 之前这里写的是 1..5000，而配置侧的合法范围是 50..2000，
     * 两套边界不一致，改配置范围时很容易忘掉这一处。
     */
    private static long openDurationNanos(Screen screen) {
        if (isPortalLoading(screen)) {
            return millisToNanos(TransitionConfig.portalDurationMs(), durationFallback());
        }
        return millisToNanos(TransitionConfig.openDurationFor(categoryOf(screen)), durationFallback());
    }

    /** 渐出（关闭）这一段动画的时长（纳秒） */
    private static long closeDurationNanos(Screen screen) {
        if (isPortalLoading(screen)) {
            return millisToNanos(TransitionConfig.portalDurationMs(), durationFallback());
        }
        return millisToNanos(TransitionConfig.closeDurationFor(categoryOf(screen)), durationFallback());
    }

    private static int durationFallback() {
        try {
            return TransitionConfig.DEFAULT_DURATION_MS;
        } catch (Throwable t) {
            return 500;
        }
    }

    private static long millisToNanos(int millis, int fallback) {
        try {
            int clamped = Math.max(TransitionConfig.MIN_DURATION_MS,
                    Math.min(TransitionConfig.MAX_DURATION_MS, millis));
            return clamped * 1_000_000L;
        } catch (Throwable t) {
            return (long) fallback * 1_000_000L;
        }
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static void report(String where, Throwable t) {
        if (REPORTED.add(where)) {
            System.err.println("[UI Transitions] " + where + " 出错（同类错误只报一次）: " + t);
        }
    }

    /** 打开动画的状态：起点 + 这一段动画自己的时长 */
    private record Open(long startNanos, long durationNanos) {
    }

    /** 关闭动画的状态 */
    private record Close(long startNanos, long durationNanos, Screen target) {
    }

    /**
     * 原地淡变的状态。
     *
     * @param startNanos      起始时间
     * @param scrollDirection 0 = 点标签（整个物品区均匀淡入）；
     *                        +1 / -1 = 滚轮或拖动方向（逐格渐变，进入边随之切换）
     */
    private record TabSwitch(long startNanos, float scrollDirection) {
    }
}
