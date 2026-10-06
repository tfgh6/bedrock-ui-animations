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
    private static final ThreadLocal<Boolean> PIP_BLITTING = ThreadLocal.withInitial(() -> false);
    /** 当前这一帧的界面是否已收到关闭指令（收到就立即隐藏玩家模型） */
    private static volatile boolean HIDE_PREVIEW = false;
    /** 本帧内容层整体的淡变透明度（帧级：直到下一帧开始才复位，供 HUD 快捷栏判断用） */
    private static volatile float FRAME_FADE_ALPHA = 1.0F;
    /** 打开界面时，玩家模型的透明度覆盖值；负数表示不干预 */
    private static volatile float PREVIEW_ALPHA_OVERRIDE = -1.0F;
    /**
     * 内容层本帧的淡变透明度，**帧内跨阶段保留**。
     *
     * 为什么不能用 FRAME_ALPHA：它在提取阶段结束时就被 endScreenFrame 复位了，
     * 而画中画是在渲染阶段（GuiRenderer.render → prepare → blitTexture）才贴回界面的，
     * 那时读 FRAME_ALPHA 只会拿到 1.0 —— 书 / 地图 / 旗帜预览于是完全不淡变。
     * 帧级复位发生在下一帧的 beginContentLayer，所以这个值在整帧内都有效。
     */
    private static volatile float PIP_FRAME_ALPHA = 1.0F;
    private static final ThreadLocal<Boolean> TAB_STATIC = ThreadLocal.withInitial(() -> false);
    /**
     * 物品/画中画走的是预乘 alpha 管线（GUI_TEXTURED_PREMULTIPLIED_ALPHA）：
     * 颜色通道本应已经乘过 alpha。只改 alpha 而不动 RGB，元素就会比周围偏亮
     * —— 这就是"切换时物品突然变亮"的原因。进这条管线时把 RGB 一起按比例缩放。
     */
    private static final ThreadLocal<Boolean> PREMULTIPLIED = ThreadLocal.withInitial(() -> false);
    /** 本帧的动画透明度：物品渲染状态没登记到（例如状态是在动画开始前建立的）就退回这个值 */
    private static final ThreadLocal<Float> FRAME_ALPHA = ThreadLocal.withInitial(() -> 1.0F);
    /**
     * 当前背景层用的提取器。
     *
     * 字幕（Hud.extractDeferredSubtitles）是在背景层内部被调用的，但它自己没有
     * extractor 参数；把当前这个存下来，就能在 Hud 内部统一抵消位移，
     * 从而覆盖所有调用点（Screen / PauseScreen / LoadingOverlay），
     * 而不是只在 Screen.extractBackground 这一个调用点上打补丁。
     */
    private static final ThreadLocal<GuiGraphicsExtractor> BACKGROUND_EXTRACTOR = new ThreadLocal<>();

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
                long closeDuration = durationNanos();
                long backdate = backdateNanos(
                        solveProgress(TransitionConfig.curve(), visualAlpha(current), true), closeDuration);
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
                long openDuration = durationNanos();
                long backdate = 0L;
                if (CLOSING.containsKey(target)) {
                    backdate = backdateNanos(
                            solveProgress(TransitionConfig.curve(), visualAlpha(target), false), openDuration);
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
        }
    }

    // ================================================================== 内容层

    /** 内容层开始：槽内物品、标题文字等。 */
    public static void beginContentLayer(Screen screen, GuiGraphicsExtractor extractor) {
        FRAME_FADE_ALPHA = 1.0F;      // 新的一帧开始
        PIP_FRAME_ALPHA = 1.0F;
        // 只针对背包/容器界面里的玩家模型；书、地图等其它画中画预览照旧渐隐
        boolean container = screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
        HIDE_PREVIEW = isClosing(screen) && container;
        PREVIEW_ALPHA_OVERRIDE = -1.0F;
        if (container && !HIDE_PREVIEW) {
            try {
                Open open = OPEN_START.get(screen);
                if (open != null) {
                    // 用这一段动画自己的时长，和 progress() 保持一致
                    float progress = (System.nanoTime() - open.startNanos())
                            / (float) open.durationNanos();
                    // 先等一小会儿（约 35% 时长）再淡入，避免和界面一起冒出来显得突兀
                    float delay = TransitionConfig.previewFadeDelay() / 100.0F;
                    float delayed = (progress - delay) / Math.max(0.05F, 1.0F - delay);
                    PREVIEW_ALPHA_OVERRIDE = Math.max(0.0F, Math.min(1.0F, delayed));
                }
            } catch (Throwable ignored) {
                PREVIEW_ALPHA_OVERRIDE = -1.0F;
            }
        }
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
                WINDOW_ALPHA.set(1.0F);
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
        float alpha = TransitionConfig.fade() ? alpha(screen, progress, contentLayer) : 1.0F;
        LAYER_SHIFT.set(shift);
        LAYER_ALPHA.set(alpha);
        WINDOW_ALPHA.set(alpha);
        FRAME_ALPHA.set(alpha);
        PIP_FRAME_ALPHA = alpha;       // 帧级：渲染阶段贴画中画时还要用
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
            WINDOW_ALPHA.set(TransitionConfig.fadeDim() ? LAYER_ALPHA.get() : 1.0F);
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
     * 这一对由 HotbarTabExcludeMixin 注入在 {@code Hud.extractDeferredSubtitles} 上，
     * 因此 Screen / PauseScreen / LoadingOverlay 这些调用点都被覆盖 —— 早期版本只在
     * Screen.extractBackground 的调用点做抵消，暂停菜单自己重写了该方法，字幕照样会动。
     */
    public static void pauseForHud() {
        try {
            GuiGraphicsExtractor extractor = BACKGROUND_EXTRACTOR.get();
            if (extractor == null || !BACKGROUND_PUSHED.get() || HUD_PAUSED.get()
                    || TransitionConfig.animateSubtitles()) {
                return;
            }
            float shift = LAYER_SHIFT.get();
            if (shift != 0.0F) {
                extractor.pose().translate(0.0F, -shift);
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
            if (extractor != null && shift != 0.0F) {
                extractor.pose().translate(0.0F, shift);
            }
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
        return modulate(color);
    }

    private static int modulate(int color) {
        try {
            float alpha = WINDOW_ALPHA.get();
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
            float frame = FRAME_ALPHA.get();
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
            float eased = TransitionConfig.curve().easeOut(progress);
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
            float eased = TransitionConfig.curve().easeOut(progress);
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

    /** 快捷栏：在换页动画期间保持原版观感（不跟着一起淡） */
    public static void pauseForTabStatic(GuiGraphicsExtractor extractor) {
        try {
            if (FRAME_FADE_ALPHA >= 0.999F || TAB_STATIC.get()) {
                return;
            }
            WINDOW_ALPHA.set(1.0F);
            FRAME_ALPHA.set(1.0F);
            TAB_STATIC.set(true);
        } catch (Throwable t) {
            report("pauseForTabStatic", t);
        }
    }

    public static void resumeAfterTabStatic(GuiGraphicsExtractor extractor) {
        try {
            if (!TAB_STATIC.get()) {
                return;
            }
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
        } catch (Throwable t) {
            report("endScreenFrame", t);
        }
    }

    public static boolean isClosing(Screen screen) {
        return CLOSING.containsKey(screen);
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
                return;
            }
            if (isPlayerPreview(state) && !HIDE_PREVIEW && PREVIEW_ALPHA_OVERRIDE >= 0.0F) {
                WINDOW_ALPHA.set(PREVIEW_ALPHA_OVERRIDE);
                PIP_BLITTING.set(true);
                return;
            }
            if (TransitionConfig.hidePlayerModelOnClose() && isPlayerPreview(state) && HIDE_PREVIEW) {
                WINDOW_ALPHA.set(0.0F);      // 关闭动画一开始：玩家模型直接不画
                PIP_BLITTING.set(true);
                return;
            }
            WINDOW_ALPHA.set(PIP_FRAME_ALPHA);
            PIP_BLITTING.set(true);
        } catch (Throwable t) {
            report("beginPipBlit", t);
        }
    }

    /** 是否是玩家模型那类画中画预览（背包里的布娃娃） */
    private static boolean isPlayerPreview(Object state) {
        try {
            if (state == null) {
                return false;
            }
            String name = state.getClass().getSimpleName();
            return name.contains("Entity") || name.contains("Player");
        } catch (Throwable t) {
            return false;
        }
    }

    public static void endPipBlit() {
        try {
            if (PIP_BLITTING.get()) {
                PIP_BLITTING.set(false);
                WINDOW_ALPHA.set(1.0F);
            }
        } catch (Throwable t) {
            PIP_BLITTING.set(false);
            report("endPipBlit", t);
        }
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
            if (TransitionConfig.isExcluded(name)) {
                return false;
            }
            if (TransitionConfig.animateAllScreens()) {
                return true;
            }
            // 容器界面之外，再放行 JEI / REI / EMI 这类物品管理器的界面（可配置）
            return screen instanceof AbstractContainerScreen<?> || TransitionConfig.isExtraScreen(name);
        } catch (Throwable t) {
            report("shouldAnimate", t);
            return false;
        }
    }

    /** 当前"可见透明度"（关闭中递减、打开中递增），用于打断时接续 */
    private static float visualAlpha(Screen screen) {
        float p = progress(screen);
        TransitionConfig.Curve curve = TransitionConfig.curve();
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
            OPEN_START.put(screen, new Open(now, durationNanos()));
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

    private static float alpha(Screen screen, float progress, boolean contentLayer) {
        TransitionConfig.Curve curve = TransitionConfig.curve();
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

    private static float shift(Screen screen, float progress) {
        // 装了 JEI 这类"在容器界面上叠固定按钮"的模组时，改成只淡变不位移：
        // 那些按钮画在同一条渲染层里，只能靠整个界面不滑来让它们待在原地。
        if (TransitionConfig.overlayModsFadeOnly() && hasOverlayMod()) {
            return 0.0F;
        }
        float offset = TransitionConfig.offset();
        TransitionConfig.Curve curve = TransitionConfig.curve();
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
     * 本段动画的时长（纳秒）。
     *
     * 用 TransitionConfig 的上下限常量，而不是在这里另外写死一组数字 ——
     * 之前这里写的是 1..5000，而配置侧的合法范围是 50..2000，
     * 两套边界不一致，改配置范围时很容易忘掉这一处。
     */
    private static long durationNanos() {
        try {
            int millis = Math.max(TransitionConfig.MIN_DURATION_MS,
                    Math.min(TransitionConfig.MAX_DURATION_MS, TransitionConfig.durationMs()));
            return millis * 1_000_000L;
        } catch (Throwable t) {
            return (long) TransitionConfig.DEFAULT_DURATION_MS * 1_000_000L;
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

    public static String status() {
        Screen current = null;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.gui != null) {
                current = minecraft.gui.screen();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return "当前界面=" + (current == null ? "无" : current.getClass().getSimpleName())
                + " 打开中=" + OPEN_START.size() + " 关闭中=" + CLOSING.size()
                + " 曲线=" + TransitionConfig.curve().id();
    }
}
