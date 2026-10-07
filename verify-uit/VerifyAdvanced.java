import com.uitransitions.TransitionConfig;
import com.uitransitions.UiTransitions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.joml.Matrix3x2fStack;

public class VerifyAdvanced {

    private static int failures;

    public static void main(String[] args) throws Exception {
        System.out.println("=== UI Transitions 进阶行为验证 ===");

        Gui gui = Minecraft.getInstance().gui;
        GuiGraphicsExtractor extractor = new GuiGraphicsExtractor();
        AbstractContainerScreen container = new EmptyContainer();
        Screen plain = new Screen();
        Screen jeiLike = new mezz.jei.TestScreen();

        TransitionConfig.ensureLoaded();
        TransitionConfig.resetToDefaults();
        TransitionConfig.setDurationMsBoth(2000);

        // ---------- 1) JEI 一类界面适配 ----------
        check("普通界面默认不参与", !UiTransitions.shouldAnimate(plain), "false");
        check("容器界面参与", UiTransitions.shouldAnimate(container), "true");
        check("JEI 风格界面（extraScreens 前缀）参与", UiTransitions.shouldAnimate(jeiLike), "true");
        TransitionConfig.setExtraScreens("");
        check("清空 extraScreens 后 JEI 风格界面不参与", !UiTransitions.shouldAnimate(jeiLike), "false");
        TransitionConfig.setExtraScreens(TransitionConfig.DEFAULT_EXTRA_SCREENS);
        check("排除列表优先生效", excludedWorks(plain), "已排除的界面不参与");

        // ---------- 2) 缓动曲线 ----------
        float cubicMid = curveValue(container, gui, extractor, "cubic");
        float linearMid = curveValue(container, gui, extractor, "linear");
        check("曲线可切换（linear 与 cubic 同进度位移不同）",
                Math.abs(cubicMid - linearMid) > 1.0F,
                "cubic=" + cubicMid + " linear=" + linearMid);
        TransitionConfig.setCurveId("cubic");

        // ---------- 3) 文字 / 物品 独立开关 ----------
        TransitionConfig.setFadeText(false);
        TransitionConfig.setFadeItems(false);
        TransitionConfig.setFadeDim(false);
        openPanel(gui, container);
        UiTransitions.beginContentLayer(container, extractor);
        int textColor = UiTransitions.applyAlphaText(0xFFFFFFFF);
        int blitColor = UiTransitions.applyAlphaBlit(0xFFFFFFFF);
        check("fadeText=false 时文字不淡变", (textColor >>> 24) == 255, "alpha=" + (textColor >>> 24));
        check("贴图/底板仍淡变", (blitColor >>> 24) < 255, "alpha=" + (blitColor >>> 24));
        Object itemState = new Object();
        UiTransitions.tagItem(itemState);
        UiTransitions.beginItemSubmit(itemState);
        check("fadeItems=false 时物品不淡变",
                (UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24) == 255,
                "alpha=" + (UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24));
        UiTransitions.endItemSubmit(itemState);
        UiTransitions.endContentLayer(container, extractor);
        TransitionConfig.setFadeText(true);
        TransitionConfig.setFadeItems(true);
        TransitionConfig.setFadeDim(true);

        // ---------- 4) 遮罩 / 字幕的抵消行为 ----------
        openPanel(gui, container);
        UiTransitions.beginBackgroundLayer(container, extractor);
        UiTransitions.pauseForStaticRegion(extractor);
        int veilFaded = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.resumeAfterStaticRegion(extractor);
        check("fadeDim=true 时遮罩随动画淡出", veilFaded < 255, "遮罩 alpha=" + veilFaded);
        TransitionConfig.setFadeDim(false);
        UiTransitions.beginBackgroundLayer(container, extractor);
        UiTransitions.pauseForStaticRegion(extractor);
        int veilSolid = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.resumeAfterStaticRegion(extractor);
        check("fadeDim=false 时遮罩保持最深", veilSolid == 255, "遮罩 alpha=" + veilSolid);
        TransitionConfig.setFadeDim(true);

        // 字幕：默认完全不动（既不位移也不淡变）
        Matrix3x2fStack.reset();
        UiTransitions.beginBackgroundLayer(container, extractor);
        float shiftBeforeSubtitle = Matrix3x2fStack.lastTranslateY;
        UiTransitions.pauseForHud();
        float subtitleCompensation = Matrix3x2fStack.lastTranslateY;
        int subtitleAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.resumeAfterHud();
        float restored = Matrix3x2fStack.lastTranslateY;
        UiTransitions.endBackgroundLayer(container, extractor);
        check("字幕默认被抵消位移（不跟着动）",
                Math.abs(subtitleCompensation + shiftBeforeSubtitle) < 0.01F,
                "层位移=" + shiftBeforeSubtitle + " 抵消=" + subtitleCompensation);
        check("字幕默认不被淡出", subtitleAlpha == 255, "alpha=" + subtitleAlpha);
        check("字幕之后位移被复原", Math.abs(restored - shiftBeforeSubtitle) < 0.01F, "复原=" + restored);

        // ---------- 5) 打断动画接续 ----------
        TransitionConfig.setDurationMsBoth(2000);
        openPanel(gui, container);
        UiTransitions.beginContentLayer(container, extractor);   // 开始打开动画
        Thread.sleep(700);                                      // 打开到约 1/3
        UiTransitions.endContentLayer(container, extractor);
        boolean intercepted = UiTransitions.interceptSetScreen(gui, null);   // 打断：改关闭
        check("关闭被打断时被拦下", intercepted, "true");
        // 测底板层：关闭时内容层会提前淡出（staggerClose），
        // 用满整段、负责接续的是底板层，所以这里量底板。
        TransitionConfig.setAnimatePanel(true);
        UiTransitions.beginBackgroundLayer(container, extractor);
        int handoffAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        float handoffShift = Matrix3x2fStack.lastTranslateY;
        UiTransitions.endBackgroundLayer(container, extractor);
        check("打断后底板从当前可见状态继续（不是瞬间跳回全不透明）",
                handoffAlpha < 200 && handoffAlpha > 0, "接续 alpha=" + handoffAlpha);
        check("打断后位移不为 0（继续往下走）", handoffShift > 0.5F, "接续位移=" + handoffShift);

        // ---------- 5c) 触屏可用性：命中范围必须**明显大于**视觉方块 ----------
        //
        // 这条拦的是一类具体的可用性问题（用户实测反馈）：
        // "那几个方块太挡了，而且我得按到正中间才能拖"。
        // 也就是说：方块画得大、命中范围却和方块一样大 —— 屏幕上又挡内容、又按不准。
        // 正确做法是两者**故意不成比例**：画小、命中大。
        //
        // 断言用反射读常量，是为了让"以后有人把 POINT_GRAB_DISTANCE 调回 14"这件事
        // 直接变红，而不是又等用户按不动了才发现。
        {
            // 注意：`UiTransitionsCurveScreen` 在 fabric/ 下、依赖 MC 类型，
            // 而这一关（run_verify.py）**只编译 UiTransitions + TransitionConfig 两个源文件**，
            // 所以它在这里根本不在 classpath 上 —— 类拿不到时**明确跳过并说明**，
            // 不要假装验证过，也不要让它红（红了会掩盖真正的问题）。
            Class<?> curveScreen = null;
            try {
                curveScreen = Class.forName("com.uitransitions.fabric.UiTransitionsCurveScreen");
            } catch (Throwable ignored) {
                System.out.println("[SKIP] 曲线编辑器的命中范围断言：该类不在本关 classpath"
                        + "（离线链只编 2 个源文件）。这条比例关系目前由人工核对 + "
                        + "visualtest 的 curveui 阶段覆盖。");
            }
            if (curveScreen != null) {
                int handleRadius = readStaticInt(curveScreen, "HANDLE_RADIUS");
                int grab = readStaticInt(curveScreen, "GRAB_DISTANCE");
                int pointGrab = readStaticInt(curveScreen, "POINT_GRAB_DISTANCE");
                check("视觉方块半径保持小巧（不挡内容）", handleRadius <= 5,
                        "HANDLE_RADIUS=" + handleRadius);
                check("两点命中范围是视觉半径的 3 倍以上（手指按得到）",
                        pointGrab >= handleRadius * 3,
                        "POINT_GRAB_DISTANCE=" + pointGrab + " vs HANDLE_RADIUS=" + handleRadius);
                check("拖动命中范围也大于视觉半径（不只是多点那条路）",
                        grab > handleRadius,
                        "GRAB_DISTANCE=" + grab);
            }
        }

        // 说明：这里**没有**"关闭到一半又被重新打开"的断言。
        //
        // 那条路径（interceptSetScreen 里 CLOSING.containsKey(target) 的分支）需要
        // **真实客户端的 setScreen 拦截**才会走到：离线直接调 interceptSetScreen /
        // gui.setScreen 时 CLOSING 里没有条目，分支根本不进（我按这个思路写的断言
        // 实测打不出任何诊断行，而且它在离线永远红 —— 一条"我知道测不到还留着"的断言
        // 比没有更糟，所以删掉了）。
        //
        // 那条路径的正确性靠两样东西保证：
        //   · 数学层的独立验证（tools/anim_verify.py 用真实 TransitionConfig.Curve 反解对照）；
        //   · 真实客户端上的观感（打开一半被关掉再打开，不应看到透明度跳变）。
        // 该分支已修的两处：solveProgress 的 closing 参数用错（应 true）、
        // 回拨时长用错（应用"算出该进度的那段动画"的时长）。

        // ---------- 6) 果冻回弹：0 = 关闭，>0 时打开过程中会冲过静止位置 ----------
        TransitionConfig.setJelly(0.0F);
        float noJelly = openShiftAtPeak(container, gui, extractor);
        TransitionConfig.setJelly(0.8F);
        float withJelly = openShiftAtPeak(container, gui, extractor);
        check("果冻关闭时位移不会反向", noJelly >= 0.0F, "位移=" + noJelly);
        check("果冻开启后出现回弹（位移一度变负）", withJelly < -0.5F, "最小位移=" + withJelly);
        TransitionConfig.setJelly(0.0F);

        // ---------- 7) 同类界面切换（创造模式分类标签）默认不做动画 ----------
        TransitionConfig.setAnimateSameTypeSwitch(false);
        Screen first = new EmptyContainer();
        Screen second = new EmptyContainer();
        openPanel(gui, first);
        UiTransitions.interceptSetScreen(gui, second);
        gui.setScreen(second);
        Matrix3x2fStack.reset();
        UiTransitions.beginContentLayer(second, extractor);
        int skippedPush = Matrix3x2fStack.pushCount;
        UiTransitions.endContentLayer(second, extractor);
        check("同类界面切换默认直接切换（不压栈、不动画）", skippedPush == 0,
                "pushCount=" + skippedPush);

        TransitionConfig.setAnimateSameTypeSwitch(true);
        Screen third = new EmptyContainer();
        Screen fourth = new EmptyContainer();
        openPanel(gui, third);
        UiTransitions.interceptSetScreen(gui, fourth);
        gui.setScreen(fourth);
        Matrix3x2fStack.reset();
        UiTransitions.beginContentLayer(fourth, extractor);
        int animatedPush = Matrix3x2fStack.pushCount;
        UiTransitions.endContentLayer(fourth, extractor);
        check("打开开关后同类界面切换会做动画", animatedPush == 1, "pushCount=" + animatedPush);
        TransitionConfig.setAnimateSameTypeSwitch(false);

        // ---------- 8) 物品透明度兜底（收尾闪烁修复） ----------
        // 先切到一个不同类的界面，避免触发"同类界面直接切换"而让下面这个界面不做动画
        gui.setScreen(new Screen());
        Screen tail = new EmptyContainer();
        TransitionConfig.setDurationMsBoth(2000);
        openPanel(gui, tail);
        UiTransitions.beginContentLayer(tail, extractor);   // 启动打开动画
        UiTransitions.endContentLayer(tail, extractor);
        Thread.sleep(400);
        UiTransitions.beginContentLayer(tail, extractor);
        int frameAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;   // 本帧层内透明度
        Object untagged = new Object();                    // 从未登记过的渲染状态
        UiTransitions.beginItemSubmit(untagged);
        int fallbackAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endItemSubmit(untagged);
        UiTransitions.endContentLayer(tail, extractor);
        check("未登记物品沿用本帧透明度（不会瞬间弹回不透明）",
                fallbackAlpha == frameAlpha && fallbackAlpha != 255,
                "层内=" + frameAlpha + " 兜底=" + fallbackAlpha);

        // ---------- 7a-2) 提取阶段结束后，物品提交仍要拿到动画透明度 ----------
        // 物品是在**渲染阶段**才从图集提交的，而 FRAME_ALPHA 在 endScreenFrame
        // （提取阶段收尾）就被复位成 1 了。若 beginItemSubmit 读 FRAME_ALPHA，
        // 没被登记过的物品就会全不透明地留在画面上 —— 关闭动画里的"残影"就是这个。
        // 这里按真实时序来：beginContentLayer -> endScreenFrame -> 提交物品。
        TransitionConfig.setDurationMsBoth(2000);
        gui.setScreen(new Screen());
        Screen itemHost = new EmptyContainer();
        openPanel(gui, itemHost);
        UiTransitions.beginContentLayer(itemHost, extractor);
        UiTransitions.endContentLayer(itemHost, extractor);
        Thread.sleep(500);
        UiTransitions.beginContentLayer(itemHost, extractor);
        UiTransitions.endContentLayer(itemHost, extractor);
        UiTransitions.endScreenFrame();                     // 提取阶段收尾
        Object freshItem = new Object();                    // 没被登记过的新物品
        UiTransitions.beginItemSubmit(freshItem);
        int lateItemAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endItemSubmit(freshItem);
        check("提取结束后提交的物品仍跟着动画淡（不是全不透明）",
                lateItemAlpha > 0 && lateItemAlpha < 255, "alpha=" + lateItemAlpha);

        // 非动画帧必须回到完全不透明，否则物品会被残留值错误淡出。
        // 用"同类界面切换被跳过"来构造一个确实不做动画的界面。
        //
        // 先补一次 endScreenFrame：复位的时机从"内容层结束"挪到了"整帧结束"，
        // 因为内容层之后紧接着要画物品提示框，它也得跟着界面一起淡（见下面的专项断言）。
        UiTransitions.endScreenFrame();
        gui.setScreen(new EmptyContainer());
        Screen idle = new EmptyContainer();
        UiTransitions.interceptSetScreen(gui, idle);
        gui.setScreen(idle);
        Matrix3x2fStack.reset();
        UiTransitions.beginContentLayer(idle, extractor);   // 该界面被标记为已就位，不应压栈
        int idleFrame = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        Object idleState = new Object();
        UiTransitions.beginItemSubmit(idleState);
        int idleFallback = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endItemSubmit(idleState);
        int idlePush = Matrix3x2fStack.pushCount;
        UiTransitions.endContentLayer(idle, extractor);
        check("非动画帧物品不会被残留透明度淡出（兜底=255）",
                idleFallback == 255 && idleFrame == 255 && idlePush == 0,
                "层内=" + idleFrame + " 兜底=" + idleFallback + " push=" + idlePush);

        // ---------- 8a-2) 物品提示框必须跟着界面一起淡 ----------
        // extractRenderStateWithTooltipAndSubtitles 的顺序是
        //   extractBackground -> extractRenderState -> extractDeferredElements
        // 提示框在最后那步画。如果 endContentLayer 在这里就把透明度复位，
        // 界面在淡出、提示框却全不透明地杵着不动 —— 用户反馈的"动画没做完时
        // 有个文字框一直留着"就是这个。这里把两种时机都钉住。
        TransitionConfig.setDurationMsBoth(2000);
        gui.setScreen(new Screen());
        Screen tooltipHost = new EmptyContainer();
        openPanel(gui, tooltipHost);
        UiTransitions.beginContentLayer(tooltipHost, extractor);
        UiTransitions.endContentLayer(tooltipHost, extractor);
        Thread.sleep(600);
        UiTransitions.beginContentLayer(tooltipHost, extractor);
        UiTransitions.endContentLayer(tooltipHost, extractor);
        int deferredAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        check("内容层结束后透明度**保持**在动画值（提示框才会跟着淡）",
                deferredAlpha > 0 && deferredAlpha < 255, "alpha=" + deferredAlpha);
        UiTransitions.endScreenFrame();
        int afterFrameAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        check("整帧结束后透明度复位（不会漏到 HUD）", afterFrameAlpha == 255, "alpha=" + afterFrameAlpha);

        // ---------- 8b) 关闭时分两段消失：内容先没、底板最后 ----------
        TransitionConfig.setStaggerClose(true);
        TransitionConfig.setAnimatePanel(true);
        gui.setScreen(new Screen());
        Screen closing = new EmptyContainer();
        // 先把打开动画完整走完（用较短的时长省时间）。
        // 若在打开到一半时就关闭，"打断接续"会把关闭进度回拨到与当前可见透明度一致的位置；
        // 而此刻面板几乎是全透明的，于是关闭会被判定为"已经结束"、瞬间完成 ——
        // 那是正确行为，但那样就测不到关闭中段了。
        TransitionConfig.setDurationMsBoth(200);
        openPanel(gui, closing);
        UiTransitions.beginContentLayer(closing, extractor);
        UiTransitions.endContentLayer(closing, extractor);
        Thread.sleep(350);                                    // 等打开动画结束并标记为已就位
        TransitionConfig.setDurationMsBoth(2000);
        UiTransitions.interceptSetScreen(gui, null);          // 触发关闭
        Thread.sleep(1500);                                   // 约 75% 处
        UiTransitions.beginBackgroundLayer(closing, extractor);
        int panelAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endBackgroundLayer(closing, extractor);
        UiTransitions.beginContentLayer(closing, extractor);
        int contentAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endContentLayer(closing, extractor);
        // staggerClose 的语义是"内容比底板**更早**淡完"（当前提前约 8%，见 CONTENT_FADE_SPAN 0.92），
        // 不是"中段就已经完全消失" —— 那对应的是早期 0.55 的取值。
        check("关闭中段：内容层比底板淡得更早", contentAlpha < panelAlpha && panelAlpha > 0,
                "内容=" + contentAlpha + " 底板=" + panelAlpha);
        TransitionConfig.setStaggerClose(false);
        UiTransitions.beginBackgroundLayer(closing, extractor);
        int panelAlpha2 = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endBackgroundLayer(closing, extractor);
        UiTransitions.beginContentLayer(closing, extractor);
        int contentAlpha2 = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endContentLayer(closing, extractor);
        check("关掉 staggerClose 后两者同步淡出", contentAlpha2 == panelAlpha2,
                "内容=" + contentAlpha2 + " 底板=" + panelAlpha2);
        TransitionConfig.setStaggerClose(true);

        // ---------- 9) 装了 JEI 类模组时：只淡变、不位移 ----------
        UiTransitions.setOverlayModPresentForTest(Boolean.TRUE);
        TransitionConfig.setOverlayModsFadeOnly(true);
        gui.setScreen(new Screen());
        Screen overlayHost = new EmptyContainer();
        TransitionConfig.setDurationMsBoth(2000);
        openPanel(gui, overlayHost);
        Matrix3x2fStack.reset();
        UiTransitions.beginContentLayer(overlayHost, extractor);
        float overlayShift = Matrix3x2fStack.lastTranslateY;
        int overlayAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endContentLayer(overlayHost, extractor);
        Thread.sleep(300);
        UiTransitions.beginContentLayer(overlayHost, extractor);
        float overlayShift2 = Matrix3x2fStack.lastTranslateY;
        int overlayAlpha2 = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endContentLayer(overlayHost, extractor);
        check("有 JEI 类模组时位移为 0（固定按钮留在原地）",
                overlayShift == 0.0F && overlayShift2 == 0.0F,
                "位移=" + overlayShift + "/" + overlayShift2);
        check("有 JEI 类模组时仍然淡变", overlayAlpha < 255 || overlayAlpha2 < 255,
                "alpha=" + overlayAlpha + "/" + overlayAlpha2);

        TransitionConfig.setOverlayModsFadeOnly(false);
        UiTransitions.setOverlayModPresentForTest(Boolean.TRUE);
        gui.setScreen(new Screen());
        Screen overlayHost2 = new EmptyContainer();
        openPanel(gui, overlayHost2);
        Thread.sleep(300);
        UiTransitions.beginContentLayer(overlayHost2, extractor);
        float overlayShift3 = Matrix3x2fStack.lastTranslateY;
        UiTransitions.endContentLayer(overlayHost2, extractor);
        check("关掉该选项后恢复位移", overlayShift3 > 0.5F, "位移=" + overlayShift3);
        TransitionConfig.setOverlayModsFadeOnly(true);
        UiTransitions.setOverlayModPresentForTest(null);

        // ---------- 10) 逐槽位淡变：点标签（均匀）与滚动（逐格），快捷栏那一排固定原版 ----------
        TransitionConfig.setAnimateTabSwitch(true);
        TransitionConfig.setFade(true);
        TransitionConfig.setTabSwitchMs(400);
        TransitionConfig.setScrollFadeBand(200);
        TransitionConfig.setScrollFadeMin(0);
        // 打开动画故意设得极短：这样下面测的时候界面早已静止，
        // 从而验证"逐格淡变不依赖打开/关闭动画"—— 真机上点标签时正是这个状态。
        TransitionConfig.setDurationMsBoth(150);

        gui.setScreen(new Screen());
        Screen creative = new EmptyContainer();
        openPanel(gui, creative);
        Thread.sleep(250);                              // 让打开动画彻底结束

        // (a) 点分类标签：物品列表淡、玩家快捷栏那一排固定原版
        UiTransitions.onTabSelected(creative);
        UiTransitions.beginContentLayer(creative, extractor);
        UiTransitions.beginTabContent(creative, extractor);     // 每帧都会调（extractRenderState 的 HEAD）
        UiTransitions.beginSlotFade(false, 100);
        int tabGridAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endSlotFade(100);
        UiTransitions.beginSlotFade(true, 200);
        int tabHotbarAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endSlotFade(200);
        UiTransitions.endTabContent(creative, extractor);
        UiTransitions.endContentLayer(creative, extractor);
        check("点标签时物品列表在淡变", tabGridAlpha < 255, "alpha=" + tabGridAlpha);
        check("点标签时玩家快捷栏固定为原版（不淡）", tabHotbarAlpha == 255,
                "alpha=" + tabHotbarAlpha);

        // (a2) **同一件事的文字通道** —— 快捷栏物品的"数量文字"走 applyAlphaText，不走 blit。
        //
        // 这条断言是为一个真实回归补的，而且它已经复发过一次：
        //   · 1.4.0：只置回 WINDOW_ALPHA，pip 兜底仍读到淡变中的 PIP_FRAME_ALPHA → 图标淡；
        //   · 1.5.01：补了 pip，却漏了 TEXT_ALPHA → **图标不淡、数字在淡**，
        //     用户看到的仍然是"点标签时整个 UI 都在渐变，快捷栏也不固定"。
        // 上面那条 tabHotbarAlpha 用的是 applyAlphaBlit，所以它对这类问题**完全无感** ——
        // 这就是它能复发的原因：覆盖的是物品通道，坏的是文字通道。
        {
            UiTransitions.beginContentLayer(creative, extractor);
            UiTransitions.beginTabContent(creative, extractor);
            // 冻结前先记一下：确认这段确实是"在淡变中"（否则下面那条断言会因为没淡而假过）
            int textBeforeFreeze = UiTransitions.applyAlphaText(0xFFFFFFFF) >>> 24;
            UiTransitions.pauseForTabStatic(extractor);        // 快捷栏绘制开始
            int hotbarTextDuring = UiTransitions.applyAlphaText(0xFFFFFFFF) >>> 24;
            UiTransitions.resumeAfterTabStatic(extractor);     // 快捷栏绘制结束
            UiTransitions.endTabContent(creative, extractor);
            UiTransitions.endContentLayer(creative, extractor);

            check("点标签时界面文字确实在淡变（否则下面那条会假过）", textBeforeFreeze < 255,
                    "alpha=" + textBeforeFreeze);
            check("点标签时快捷栏的数量文字固定为原版（文字通道也冻结）", hotbarTextDuring == 255,
                    "alpha=" + hotbarTextDuring + "（冻结前=" + textBeforeFreeze + "）");
        }

        // (b) 等这段淡变走完（beginTabContent 在进度满时会清掉这次换页状态）
        Thread.sleep(450);
        UiTransitions.beginContentLayer(creative, extractor);
        UiTransitions.beginTabContent(creative, extractor);
        UiTransitions.endTabContent(creative, extractor);
        UiTransitions.endContentLayer(creative, extractor);

        // (c) 第一帧滚动：只有把格子区上下界记录下来，下一帧才能逐格算
        UiTransitions.onGridScrollIfChanged(creative, 0.0F);    // 基准，不启动
        UiTransitions.onGridScrollIfChanged(creative, 0.5F);    // 真的滚了 → 启动
        UiTransitions.beginContentLayer(creative, extractor);
        UiTransitions.beginTabContent(creative, extractor);
        for (int y : new int[] {20, 40, 60, 80, 100, 120, 140, 160, 180}) {
            UiTransitions.beginSlotFade(false, y);
            UiTransitions.endSlotFade(y);
        }
        UiTransitions.endTabContent(creative, extractor);
        UiTransitions.endContentLayer(creative, extractor);

        // (d) 让这一段结束，再滚一次：这次有上一帧的边界，可以逐格算了
        Thread.sleep(450);
        UiTransitions.onGridScrollIfChanged(creative, 0.8F);
        UiTransitions.beginContentLayer(creative, extractor);
        UiTransitions.beginTabContent(creative, extractor);
        UiTransitions.beginSlotFade(false, 180);        // 紧贴进入边（向下滚 → 进入边在底部）
        int nearEdge = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endSlotFade(180);
        UiTransitions.beginSlotFade(false, 20);         // 远离进入边
        int farFromEdge = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endSlotFade(20);
        UiTransitions.beginSlotFade(true, 200);         // 快捷栏那一排仍然不许淡
        int scrollHotbarAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endSlotFade(200);
        UiTransitions.endTabContent(creative, extractor);
        UiTransitions.endContentLayer(creative, extractor);
        check("滚动时靠近进入边的格子更淡", nearEdge < farFromEdge,
                "进入边=" + nearEdge + " 远端=" + farFromEdge);
        check("滚动时远端格子比近端明显更不透明", farFromEdge > nearEdge + 100,
                "进入边=" + nearEdge + " 远端=" + farFromEdge);
        check("滚动时玩家快捷栏固定为原版（不淡）", scrollHotbarAlpha == 255,
                "alpha=" + scrollHotbarAlpha);

        // (e) 同一个滚动位置重复喂入不应重置动画（拖动滚动条是每帧调用的）
        Thread.sleep(150);
        UiTransitions.onGridScrollIfChanged(creative, 0.8F);    // 同值 → 应被忽略
        UiTransitions.beginContentLayer(creative, extractor);
        UiTransitions.beginTabContent(creative, extractor);
        UiTransitions.beginSlotFade(false, 180);
        int afterRepeat = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endSlotFade(180);
        UiTransitions.endTabContent(creative, extractor);
        UiTransitions.endContentLayer(creative, extractor);
        check("同一滚动位置重复调用不会重置渐变", afterRepeat > 60,
                "alpha=" + afterRepeat + "（被重置的话会掉回 0 附近）");

        // (f) 动画结束后必须回到完全不透明。
        // 先补一次"帧结束"：真实游戏里每帧末尾 ScreenMixin 都会调 endScreenFrame 复原透明度。
        Thread.sleep(500);
        UiTransitions.endScreenFrame();
        UiTransitions.beginContentLayer(creative, extractor);
        UiTransitions.beginTabContent(creative, extractor);
        UiTransitions.beginSlotFade(false, 100);
        int idleAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endSlotFade(100);
        UiTransitions.endTabContent(creative, extractor);
        UiTransitions.endContentLayer(creative, extractor);
        check("原地淡变结束后槽位恢复不透明", idleAlpha == 255, "alpha=" + idleAlpha);

        // (g) 连续拖动必须能**打断**上一段：滚动位置一变就重新计时，
        //     否则动画时长一长，拖动时会变成"隔一会儿才刷新一次"。
        UiTransitions.onGridScrollIfChanged(creative, 0.1F);   // 换个基准
        UiTransitions.onGridScrollIfChanged(creative, 0.3F);   // 滚动 → 起一段
        Thread.sleep(250);                                     // 让它爬升到中途
        UiTransitions.beginContentLayer(creative, extractor);
        UiTransitions.beginTabContent(creative, extractor);
        UiTransitions.beginSlotFade(false, 180);
        int midRamp = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endSlotFade(180);
        UiTransitions.endTabContent(creative, extractor);
        UiTransitions.endContentLayer(creative, extractor);

        UiTransitions.onGridScrollIfChanged(creative, 0.6F);   // 又滚了 → 必须立刻重新计时
        UiTransitions.beginContentLayer(creative, extractor);
        UiTransitions.beginTabContent(creative, extractor);
        UiTransitions.beginSlotFade(false, 180);
        int afterInterrupt = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endSlotFade(180);
        UiTransitions.endTabContent(creative, extractor);
        UiTransitions.endContentLayer(creative, extractor);
        check("拖动中途再次滚动会立刻重新计时（跟得上手速）",
                afterInterrupt < 60 && afterInterrupt < midRamp,
                "爬升中=" + midRamp + " 再次滚动后=" + afterInterrupt);

        // ---------- 11) 渐入/渐出分开、自定义曲线、玩家模型跟随 ----------
        TransitionConfig.setOpenDurationMs(200);
        TransitionConfig.setCloseDurationMs(800);
        check("渐入与渐出时长可以分开设",
                TransitionConfig.openDurationMs() == 200 && TransitionConfig.closeDurationMs() == 800,
                "渐入=" + TransitionConfig.openDurationMs() + " 渐出=" + TransitionConfig.closeDurationMs());

        gui.setScreen(new Screen());
        Screen split = new EmptyContainer();
        openPanel(gui, split);
        Thread.sleep(300);                                  // 超过渐入的 200ms
        UiTransitions.beginContentLayer(split, extractor);
        int afterOpen = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endContentLayer(split, extractor);
        check("渐入按自己的 200ms 走完", afterOpen == 255, "alpha=" + afterOpen);

        UiTransitions.interceptSetScreen(gui, null);        // 触发关闭：渐出应为 800ms
        Thread.sleep(300);                                  // 只过了 300ms，应该还在淡出
        UiTransitions.beginContentLayer(split, extractor);
        int midClose = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endContentLayer(split, extractor);
        check("渐出按自己的 800ms 走（300ms 时仍未结束）",
                midClose > 0 && midClose < 255, "alpha=" + midClose);

        // 自定义曲线：同一时刻的缓动结果必须随参数变化
        TransitionConfig.setOpenCurveCustom("0,0,1,1");          // 与线性等价
        float linearLike = customEaseOut(0.5F);
        TransitionConfig.setOpenCurveCustom("0.34,1.56,0.64,1"); // 带回弹
        float bouncy = customEaseOut(0.5F);
        check("自定义曲线参数会实际改变缓动结果",
                Math.abs(linearLike - bouncy) > 0.05F,
                "线性等价=" + linearLike + " 回弹=" + bouncy);
        check("非法自定义参数回退到默认值",
                TransitionConfig.parseBezier("不是数字")[0] == 0.25F
                        && !TransitionConfig.isValidBezier("不是数字"),
                "x1=" + TransitionConfig.parseBezier("不是数字")[0]);
        check("自定义参数的 x 会被夹到 0..1",
                TransitionConfig.parseBezier("2,0,3,1")[0] == 1.0F
                        && TransitionConfig.parseBezier("2,0,3,1")[2] == 1.0F,
                "x1=" + TransitionConfig.parseBezier("2,0,3,1")[0]
                        + " x2=" + TransitionConfig.parseBezier("2,0,3,1")[2]);

        // 玩家模型：一律与内容层同透明度（不再有"延迟浮现 / 关闭即隐藏"的特殊处理）
        TransitionConfig.setFade(true);
        TransitionConfig.setDurationMsBoth(2000);
        gui.setScreen(new Screen());
        Screen pipHost = new EmptyContainer();
        openPanel(gui, pipHost);
        UiTransitions.beginContentLayer(pipHost, extractor);
        UiTransitions.endContentLayer(pipHost, extractor);
        Thread.sleep(1000);                                 // 走到打开动画中段
        UiTransitions.beginContentLayer(pipHost, extractor);
        int layerAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        Object fakeEntity = new FakeGuiEntityState();
        UiTransitions.beginPipBlit(fakeEntity);
        int followAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endPipBlit();
        UiTransitions.endContentLayer(pipHost, extractor);
        check("玩家模型与内容层同透明度（打开中段）",
                followAlpha == layerAlpha && followAlpha < 255,
                "内容层=" + layerAlpha + " 模型=" + followAlpha);

        // 关闭方向就不在这里卡时间点了：关闭会按当前可见透明度回拨进度，回拨多少取决于
        // 上一刻的状态，纯靠 sleep 很容易刚好错过中段（试过连续采样，六次全落在结束后）。
        // 关闭方向由实机测试覆盖：visualtest 的连拍量出布娃娃/附魔书与底板位移差为 0。

        UiTransitions.setOverlayModPresentForTest(null);

        // ---------- 12) 画中画跟随位移 + 渐入/渐出各有一份自定义曲线 ----------
        TransitionConfig.setDurationMsBoth(2000);
        TransitionConfig.setOffset(120.0F);
        gui.setScreen(new Screen());
        Screen pipShiftHost = new EmptyContainer();
        openPanel(gui, pipShiftHost);
        UiTransitions.beginContentLayer(pipShiftHost, extractor);
        UiTransitions.endContentLayer(pipShiftHost, extractor);
        Thread.sleep(300);                                  // 打开动画刚起步，位移接近满值
        UiTransitions.beginContentLayer(pipShiftHost, extractor);
        Object entityState = new FakeGuiEntityState();
        UiTransitions.beginPipBlit(entityState);
        // 传一个真实的单位矩阵进去：位姿是"叠加"的，传 null 会原样返回 null，测不出东西
        float shifted = pipShiftY(UiTransitions.shiftPipPose(new org.joml.Matrix3x2f()));
        UiTransitions.endPipBlit();
        UiTransitions.endContentLayer(pipShiftHost, extractor);
        check("画中画会跟着界面一起位移（不再原地不动）", shifted > 20.0F, "位移=" + shifted);

        // 离开画中画之后应当回到原位
        UiTransitions.beginContentLayer(pipShiftHost, extractor);
        UiTransitions.beginPipBlit(entityState);
        UiTransitions.endPipBlit();
        float afterEnd = pipShiftY(UiTransitions.shiftPipPose(new org.joml.Matrix3x2f()));
        UiTransitions.endContentLayer(pipShiftHost, extractor);
        check("离开画中画后位移归零", Math.abs(afterEnd) < 0.001F, "位移=" + afterEnd);

        // 渐入 / 渐出各存一份自定义控制点，互不影响
        TransitionConfig.setOpenCurveCustom("0,0,1,1");            // 匀速
        TransitionConfig.setCloseCurveCustom("0.34,1.56,0.64,1");  // 带回弹
        float openVal = TransitionConfig.openCurve().easeOut(0.5F);
        float closeVal = TransitionConfig.closeCurve().easeOut(0.5F);
        check("渐入与渐出的自定义控制点互不影响",
                Math.abs(openVal - closeVal) > 0.05F,
                "渐入=" + openVal + " 渐出=" + closeVal);
        check("设置自定义参数后该方向自动切到 custom",
                "custom".equals(TransitionConfig.openCurve().id())
                        && "custom".equals(TransitionConfig.closeCurve().id()),
                "渐入=" + TransitionConfig.openCurve().id() + " 渐出=" + TransitionConfig.closeCurve().id());

        // 曲线编辑器画图与实机求值必须是同一段代码
        float[] editorPoints = { 0.25F, 0.1F, 0.25F, 1.0F };
        check("贝塞尔求值就是曲线编辑器画图用的那一份",
                Math.abs(TransitionConfig.Curve.bezierEase(editorPoints, 0.5F)
                        - TransitionConfig.Curve.custom(editorPoints).easeIn(0.5F)) < 1.0e-5F,
                "两者一致");

        // 通用曲线切成 custom 时，不能把已经调好的两个方向的控制点丢掉
        TransitionConfig.setCurveId("custom");
        check("切到 custom 会沿用各方向已保存的控制点",
                Math.abs(TransitionConfig.openCurve().easeOut(0.5F) - openVal) < 1.0e-5F
                        && Math.abs(TransitionConfig.closeCurve().easeOut(0.5F) - closeVal) < 1.0e-5F,
                "渐入=" + TransitionConfig.openCurve().easeOut(0.5F)
                        + " 渐出=" + TransitionConfig.closeCurve().easeOut(0.5F));

        // ---------- 13) 跨维度加载界面：不参与常规动画，改由全屏遮罩负责 ----------
        // 真实日志显示 26.3 换维度时会反复创建/销毁 LevelLoadingScreen（连着十几次、每次不到一秒），
        // 包一层必然与它打架 —— 所以这个界面**故意**不做常规动画，交给遮罩。
        Screen portal = new net.minecraft.client.gui.screens.LevelLoadingScreen(
                net.minecraft.client.gui.screens.LevelLoadingScreen.Reason.NETHER_PORTAL);
        check("跨维度加载界面不参与常规动画（避免与反复创建销毁打架）",
                !UiTransitions.shouldAnimate(portal), "shouldAnimate=true");

        TransitionConfig.setPortalDurationMs(3000);
        UiTransitions.startPortalVeil(portal);
        extractor.lastFillColor = 0;
        UiTransitions.drawPortalVeil(extractor);
        int veilFull = extractor.lastFillColor >>> 24;
        // 不要求正好 255：从 startPortalVeil 到这次绘制之间总会过去几微秒，
        // 取整后是 254 而不是 255。只要"几乎全黑"就说明起手是对的。
        check("刚跨维度时遮罩几乎是全黑的（界面从黑里缓缓切入）",
                veilFull >= 250, "alpha=" + veilFull);

        Thread.sleep(1200);
        extractor.lastFillColor = 0;
        UiTransitions.drawPortalVeil(extractor);
        int veilMid = extractor.lastFillColor >>> 24;
        check("1.2 秒后遮罩已淡开一部分（逐渐变为透明）",
                veilMid > 0 && veilMid < 255, "alpha=" + veilMid);

        Thread.sleep(2600);
        extractor.lastFillColor = 0;
        UiTransitions.drawPortalVeil(extractor);
        int veilEnd = extractor.lastFillColor >>> 24;
        check("淡出结束后遮罩完全透明（画面交还给世界）", veilEnd == 0, "alpha=" + veilEnd);

        check("时长为 0 表示不要这个过渡（不再画遮罩）",
                TransitionConfig.MIN_PORTAL_DURATION_MS == 0 && TransitionConfig.MAX_PORTAL_DURATION_MS <= 3000,
                "范围=" + TransitionConfig.MIN_PORTAL_DURATION_MS + ".." + TransitionConfig.MAX_PORTAL_DURATION_MS);

        // ---------- 14) 多点曲线：曲线编辑器里"点一下加点 / 拖点 / 双击删点"的模型 ----------
        //
        // 这一段是**唯一**能覆盖曲线编辑器交互的地方。界面类依赖 Minecraft 的 GUI 类型，
        // run_verify.py 只编译 UiTransitions + TransitionConfig 两个核心类，跑不到它 ——
        // 所以加/删/拖三件事刻意做成了 TransitionConfig.Curve 上的纯静态方法，
        // 界面只负责坐标换算与命中判定。这里验的就是那三个方法，
        // 以及"存进配置再读回来还是同一条曲线"这条容易断的链路。

        float[] two = TransitionConfig.Curve.normalizeMulti(new float[] { 0.5F, 0.8F });
        check("多点曲线首尾由 normalizeMulti 强制补齐",
                two.length == 6 && two[0] == 0.0F && two[1] == 0.0F
                        && two[4] == 1.0F && two[5] == 1.0F,
                "点数=" + (two.length / 2) + " 首=" + two[0] + "," + two[1]
                        + " 尾=" + two[4] + "," + two[5]);

        // 加点：点在图上的 (0.3, 0.9) 处，曲线里应当多出一个点
        float[] added = TransitionConfig.Curve.insertMulti(two, 0.3F, 0.9F);
        check("在图上的空白处点一下会加一个点", added.length == two.length + 2,
                "点数 " + (two.length / 2) + " -> " + (added.length / 2));
        check("新点落在点击的位置上",
                Math.abs(TransitionConfig.Curve.pointX(added, 0) - 0.3F) < 1.0e-4F
                        && Math.abs(TransitionConfig.Curve.pointY(added, 0) - 0.9F) < 1.0e-4F,
                "x=" + TransitionConfig.Curve.pointX(added, 0)
                        + " y=" + TransitionConfig.Curve.pointY(added, 0));
        check("加进去的点按 x 排在正确的位置（顺序不会乱）",
                TransitionConfig.Curve.pointX(added, 0) < TransitionConfig.Curve.pointX(added, 1),
                "x0=" + TransitionConfig.Curve.pointX(added, 0)
                        + " x1=" + TransitionConfig.Curve.pointX(added, 1));

        // 靠得太近的点加不进去：加不进去时要**原样返回同一个数组引用**，
        // 界面靠 `after != before` 判断"这次点击什么也没做"
        float[] tooClose = TransitionConfig.Curve.insertMulti(added, 0.3F + 0.005F, 0.2F);
        check("和已有点挨太近时不会加点（返回原数组）", tooClose == added,
                "引用相同=" + (tooClose == added));

        // 越界的点击被夹进合法范围，而不是造出 x<0 或 x>1 的点。
        // 两件事一起验：①夹到的最左边**确实能加进点**（边界留了足够余量，
        // 早先只留一个 gap，夹完正好贴着端点，于是边缘的点击永远加不进去）；
        // ②夹出来的 x 落在合法范围内，而不是负数。
        float[] clamped = TransitionConfig.Curve.insertMulti(two, -0.5F, 0.5F);
        check("点在图外面时新点的 x 被夹进合法范围（而且真的加得进去）",
                clamped.length == 8
                        && TransitionConfig.Curve.pointX(clamped, 0) >= TransitionConfig.Curve.MIN_POINT_X - 1.0e-6F,
                "长度=" + clamped.length
                        + " 首点x=" + (clamped.length >= 6 ? TransitionConfig.Curve.pointX(clamped, 0) : "没加点"));

        // 拖点：x 会被夹在左右邻居之间，不许越到邻居另一侧
        // （越过去的话排序之后点的身份就变了，表现为"手指下的点突然换成另一个"）
        float[] moved = TransitionConfig.Curve.moveMulti(added, 0, 0.99F, 0.4F);
        check("拖点时 x 被夹在左边界与右邻居之间",
                TransitionConfig.Curve.pointX(moved, 0) < TransitionConfig.Curve.pointX(moved, 1)
                        && Math.abs(TransitionConfig.Curve.pointY(moved, 0) - 0.4F) < 1.0e-4F,
                "x=" + TransitionConfig.Curve.pointX(moved, 0)
                        + " (右邻居 " + TransitionConfig.Curve.pointX(moved, 1) + ")");
        check("拖点不会改变点的个数", moved.length == added.length,
                "点数=" + (moved.length / 2));
        float[] draggedOut = TransitionConfig.Curve.moveMulti(added, 0, 0.3F, 99.0F);
        check("拖点时 y 会被夹在可视范围内（不会拖到画布外）",
                TransitionConfig.Curve.pointY(draggedOut, 0) <= 1.5F,
                "y=" + TransitionConfig.Curve.pointY(draggedOut, 0));

        // 删点：首尾不许删，越界不许删 —— 这两种情况都必须原样返回
        check("首尾两个点删不掉（返回原数组）", TransitionConfig.Curve.removeMulti(added, -1) == added,
                "index=-1 引用相同");
        check("越界的下标删不掉（返回原数组）", TransitionConfig.Curve.removeMulti(added, 99) == added,
                "index=99 引用相同");
        float[] removed = TransitionConfig.Curve.removeMulti(added, 0);
        check("删掉一个内部点后点数减一", removed.length == added.length - 2,
                "点数 " + (added.length / 2) + " -> " + (removed.length / 2));
        check("删完首尾仍然固定为 (0,0) 与 (1,1)",
                removed[0] == 0.0F && removed[1] == 0.0F
                        && removed[removed.length - 2] == 1.0F && removed[removed.length - 1] == 1.0F,
                "首=" + removed[0] + "," + removed[1]
                        + " 尾=" + removed[removed.length - 2] + "," + removed[removed.length - 1]);

        // 存盘格式：curveCustom 字段被贝塞尔与多点**共用**，靠 curve id 决定怎么解析
        String multiText = TransitionConfig.Curve.formatMulti(added);
        check("多点曲线的存储格式是 x,y;x,y;… 且带首尾",
                multiText.startsWith("0,0;") && multiText.endsWith(";1,1"),
                "内容=" + multiText);
        check("存成字符串再读回来是同一条点集",
                Math.abs(TransitionConfig.Curve.multi(
                        TransitionConfig.Curve.parseMulti(multiText)).easeIn(0.5F)
                        - TransitionConfig.Curve.multi(added).easeIn(0.5F)) < 1.0e-5F,
                "easeIn(0.5)=" + TransitionConfig.Curve.multi(added).easeIn(0.5F));

        // 多点插值：相邻点之间是 smoothstep，必须单调、不过冲。
        // 注意取样数据本身要单调 —— 拿一个"先上后下"的点集去断言单调，
        // 失败的是测试数据而不是被测代码（这里第一版就写错了）。
        float[] rising = TransitionConfig.Curve.normalizeMulti(
                new float[] { 0.3F, 0.25F, 0.7F, 0.75F });
        boolean monotonic = true;
        float previous = -1.0F;
        for (int i = 0; i <= 100; i++) {
            float value = TransitionConfig.Curve.multi(rising).easeIn(i / 100.0F);
            if (value < previous - 1.0e-5F) {
                monotonic = false;
            }
            previous = value;
        }
        check("多点曲线全程单调递增（不会来回抖）", monotonic,
                "easeIn(0.5)=" + TransitionConfig.Curve.multi(rising).easeIn(0.5F));
        check("多点曲线在两端精确落在 0 与 1（不会留下残移）",
                TransitionConfig.Curve.multi(rising).easeIn(0.0F) == 0.0F
                        && TransitionConfig.Curve.multi(rising).easeIn(1.0F) == 1.0F,
                "easeIn(0)=" + TransitionConfig.Curve.multi(rising).easeIn(0.0F)
                        + " easeIn(1)=" + TransitionConfig.Curve.multi(rising).easeIn(1.0F));

        // 贝塞尔 ↔ 多点的互转：切过去看一眼再切回来，形状不能白调
        float[] roundTrip = TransitionConfig.Curve.multiToBezier(added);
        check("多点转贝塞尔会得到合法的四个控制点",
                roundTrip.length == 4
                        && roundTrip[0] >= 0.0F && roundTrip[0] <= 1.0F
                        && roundTrip[2] >= 0.0F && roundTrip[2] <= 1.0F,
                "P1=(" + roundTrip[0] + "," + roundTrip[1] + ") P2=(" + roundTrip[2] + "," + roundTrip[3] + ")");
        check("退化输入（没有中间点）也能转出可用的控制点",
                TransitionConfig.Curve.multiToBezier(new float[0]).length == 4,
                "长度=" + TransitionConfig.Curve.multiToBezier(new float[0]).length);

        // ---------- 15) 按部位分曲线：全局多点 setter 与"部位单独设"的 kind 保持 ----------
        //
        // 这里覆盖的是一个**已经真实踩到的 bug**：setPartCurveCustom 曾经无条件把部位切到 custom，
        // 于是多点曲线（存在同一个 curveCustom 字段里）会被 parseBezier 当成非法输入、
        // 静默回退成默认控制点 —— 配置里明明存着点集，画出来的却是另一条。

        TransitionConfig.resetToDefaults();
        TransitionConfig.setPartCurve(TransitionConfig.Part.ITEMS, false,
                TransitionConfig.Curve.FOLLOW_ID);
        check("部位默认跟随全局（老配置行为不变）",
                TransitionConfig.Curve.FOLLOW_ID.equals(
                        TransitionConfig.partCurveId(TransitionConfig.Part.ITEMS, false)),
                "id=" + TransitionConfig.partCurveId(TransitionConfig.Part.ITEMS, false));
        check("跟随全局时取到的就是全局曲线",
                Math.abs(TransitionConfig.curveFor(TransitionConfig.Part.ITEMS, false).easeOut(0.5F)
                        - TransitionConfig.openCurve().easeOut(0.5F)) < 1.0e-6F,
                "部位=" + TransitionConfig.curveFor(TransitionConfig.Part.ITEMS, false).easeOut(0.5F)
                        + " 全局=" + TransitionConfig.openCurve().easeOut(0.5F));

        // 全局多点 setter
        TransitionConfig.setOpenCurveMulti("0,0;0.5,0.9;1,1");
        check("全局渐入可以设成多点曲线（id 与求值都对）",
                TransitionConfig.Curve.MULTI_ID.equals(TransitionConfig.openCurve().id())
                        && Math.abs(TransitionConfig.openCurve().easeOut(0.5F) - 0.1F) < 1.0e-4F,
                "id=" + TransitionConfig.openCurve().id()
                        + " easeOut(0.5)=" + TransitionConfig.openCurve().easeOut(0.5F));

        // 部位多点 setter
        TransitionConfig.setPartCurveMulti(TransitionConfig.Part.TEXT, false, "0,0;0.4,0.1;1,1");
        check("部位可以单独设成多点曲线",
                TransitionConfig.Curve.MULTI_ID.equals(
                        TransitionConfig.partCurveId(TransitionConfig.Part.TEXT, false))
                        && TransitionConfig.curveFor(TransitionConfig.Part.TEXT, false).points() != null,
                "id=" + TransitionConfig.partCurveId(TransitionConfig.Part.TEXT, false));

        // 这就是那个真实 bug：部位已经是多点曲线时，再调 setPartCurveCustom 不能把它打回 custom
        TransitionConfig.setPartCurveCustom(TransitionConfig.Part.TEXT, false, "0,0;0.4,0.1;1,1");
        check("已经是多点的部位，改控制点后**仍然是多点**（不会被打回 custom 而丢点位）",
                TransitionConfig.Curve.MULTI_ID.equals(
                        TransitionConfig.partCurveId(TransitionConfig.Part.TEXT, false)),
                "id=" + TransitionConfig.partCurveId(TransitionConfig.Part.TEXT, false)
                        + "（曾经在这里被静默改成 custom）");

        // 反向：本来就是贝塞尔的部位，调 setPartCurveCustom 必须切成 custom（不能被这条修复改坏）
        TransitionConfig.setPartCurve(TransitionConfig.Part.PANEL, false, "cubic");
        TransitionConfig.setPartCurveCustom(TransitionConfig.Part.PANEL, false, "0.1,0.2,0.3,0.4");
        check("贝塞尔的部位改控制点后切到 custom（这条修复没有把原行为改坏）",
                TransitionConfig.Curve.CUSTOM_ID.equals(
                        TransitionConfig.partCurveId(TransitionConfig.Part.PANEL, false)),
                "id=" + TransitionConfig.partCurveId(TransitionConfig.Part.PANEL, false));

        // 真正会生效：部位曲线确实参与了透明度计算，而不是只存在配置里。
        //
        // **必须先清掉排除列表**：第 1 节测过"排除列表优先生效"，而那个 helper 只在结束时
        // 把 `excludedScreens` 清空，`excludedSet` 这个缓存集合要等下一次 load/rebuildSets
        // 才会跟着更新。前两版这里没清，于是这个界面被判成"不参与动画"、
        // 两层的 alpha 都拿到 1.0（=0 位移的稳定态），断言看到的是 0.0 / 0.0 —— 假红。
        TransitionConfig.resetToDefaults();
        TransitionConfig.setDurationMsBoth(2000);
        TransitionConfig.setPartCurve(TransitionConfig.Part.PANEL, false, "linear");
        TransitionConfig.setPartCurve(TransitionConfig.Part.ITEMS, false, "linear");
        TransitionConfig.setOpenCurve("linear");
        TransitionConfig.setCloseCurve("linear");
        TransitionConfig.setPartCurveMulti(TransitionConfig.Part.ITEMS, false, "0,0;0.5,0.95;1,1");
        Screen partScreen = new EmptyContainer();
        check("前置：这一段用的界面确实参与动画（否则下面测的是稳定态）",
                UiTransitions.shouldAnimate(partScreen),
                "excluded=" + TransitionConfig.isExcluded(partScreen.getClass().getName()));
        openPanel(gui, partScreen);
        // 沿时间轴采样两层，而不是压在单个时刻上：曲线分离与否应当在全过程都成立，
        // 测一个瞬间既容易被时序抖动打歪，也说不清"到底是哪一段不对"。
        float maxGap = 0.0F;
        int itemAtMax = -1;
        int panelAtMax = -1;
        for (int i = 0; i < 12; i++) {
            Thread.sleep(120);
            Matrix3x2fStack.reset();
            UiTransitions.beginContentLayer(partScreen, extractor);
            int item = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
            UiTransitions.endContentLayer(partScreen, extractor);
            UiTransitions.beginBackgroundLayer(partScreen, extractor);
            int panel = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
            UiTransitions.endBackgroundLayer(partScreen, extractor);
            if (Math.abs(item - panel) > maxGap) {
                maxGap = Math.abs(item - panel);
                itemAtMax = item;
                panelAtMax = panel;
            }
        }
        gui.setScreen(null);
        Thread.sleep(120);
        check("同一时刻，底板与物品用的是各自部位的曲线（两条曲线真的分开生效）",
                maxGap >= 30.0F,
                "最大差值=" + maxGap + "（物品=" + itemAtMax + " 底板=" + panelAtMax + "）");

        // ---------- 16) 排除列表的字符串切分（双列表界面与配置界面共用同一套） ----------
        TransitionConfig.setExcludedScreens(" a.b.C ,, d.e.F , ");
        check("排除列表切分会 trim 并忽略空项",
                TransitionConfig.excludedEntries().size() == 2
                        && TransitionConfig.excludedEntries().get(0).equals("a.b.C"),
                "条数=" + TransitionConfig.excludedEntries().size()
                        + " 首项=" + TransitionConfig.excludedEntries().get(0));
        check("列表再拼回去是规范的逗号分隔",
                TransitionConfig.joinList(TransitionConfig.excludedEntries()).equals("a.b.C,d.e.F"),
                "内容=" + TransitionConfig.joinList(TransitionConfig.excludedEntries()));
        check("包名（小写结尾）被识别为前缀，完整类名不是",
                TransitionConfig.isPrefixEntry("mezz.jei")
                        && !TransitionConfig.isPrefixEntry("mezz.jei.SomeScreen"),
                "mezz.jei=" + TransitionConfig.isPrefixEntry("mezz.jei")
                        + " 类名=" + TransitionConfig.isPrefixEntry("mezz.jei.SomeScreen"));
        TransitionConfig.setExcludedScreens("");

        // ---------- 17) 界面分类：曲线 / 时长 / "跟随全局"的第三种状态 ----------
        //
        // 分类是"数据不是行为"（底层架构文档 §5.2）：加一个分类只加数据，不加代码。
        // 这里验的就是那份数据的语义，以及最容易断的一环：**存盘再读回来还认不认**。

        TransitionConfig.resetToDefaults();
        check("分类默认跟随全局（老配置行为不变）",
                TransitionConfig.Curve.FOLLOW_ID.equals(
                        TransitionConfig.categoryCurveId(TransitionConfig.UiCategory.CHAT))
                        && !TransitionConfig.hasOwnDuration(TransitionConfig.UiCategory.CHAT, false),
                "id=" + TransitionConfig.categoryCurveId(TransitionConfig.UiCategory.CHAT)
                        + " ownOpen=" + TransitionConfig.hasOwnDuration(TransitionConfig.UiCategory.CHAT, false));

        // 聊天栏这一类单独配曲线 + 时长
        TransitionConfig.setCategoryCurve(TransitionConfig.UiCategory.CHAT, "linear");
        TransitionConfig.setCategoryDuration(TransitionConfig.UiCategory.CHAT, false, 700);
        check("分类可以单独配曲线（取到的是配的那条，不是全局）",
                "linear".equals(TransitionConfig.categoryCurveId(TransitionConfig.UiCategory.CHAT))
                        && Math.abs(TransitionConfig.curveForCategory(TransitionConfig.UiCategory.CHAT)
                        .easeOut(0.5F) - TransitionConfig.Curve.LINEAR.easeOut(0.5F)) < 1.0e-6F,
                "id=" + TransitionConfig.categoryCurveId(TransitionConfig.UiCategory.CHAT));
        check("分类可以单独配时长",
                TransitionConfig.openDurationFor(TransitionConfig.UiCategory.CHAT) == 700
                        && TransitionConfig.closeDurationFor(TransitionConfig.UiCategory.CHAT)
                        == TransitionConfig.closeDurationMs(),
                "渐入=" + TransitionConfig.openDurationFor(TransitionConfig.UiCategory.CHAT)
                        + " 渐出=" + TransitionConfig.closeDurationFor(TransitionConfig.UiCategory.CHAT)
                        + "（只配了渐入，渐出应当仍是全局 "
                        + TransitionConfig.closeDurationMs() + "）");
        check("配了时长的分类 hasOwnDuration=true（界面靠它区分「跟随全局」与「设成同一个值」）",
                TransitionConfig.hasOwnDuration(TransitionConfig.UiCategory.CHAT, false)
                        && !TransitionConfig.hasOwnDuration(TransitionConfig.UiCategory.CHAT, true),
                "open=" + TransitionConfig.hasOwnDuration(TransitionConfig.UiCategory.CHAT, false)
                        + " close=" + TransitionConfig.hasOwnDuration(TransitionConfig.UiCategory.CHAT, true));

        // 别的分类不受影响
        check("改一个分类不影响另一个分类",
                TransitionConfig.openDurationFor(TransitionConfig.UiCategory.CREATIVE)
                        == TransitionConfig.openDurationMs(),
                "创造物品栏=" + TransitionConfig.openDurationFor(TransitionConfig.UiCategory.CREATIVE)
                        + " 全局=" + TransitionConfig.openDurationMs());

        // **回退到"跟随全局"**：这是第三种状态，配置界面上输入 default 就走这条
        TransitionConfig.setCategoryDuration(TransitionConfig.UiCategory.CHAT, false, 0);
        check("时长设回 0 表示「回到跟随全局」（而不是「时长 0 毫秒」）",
                !TransitionConfig.hasOwnDuration(TransitionConfig.UiCategory.CHAT, false)
                        && TransitionConfig.openDurationFor(TransitionConfig.UiCategory.CHAT)
                        == TransitionConfig.openDurationMs(),
                "own=" + TransitionConfig.hasOwnDuration(TransitionConfig.UiCategory.CHAT, false)
                        + " 生效值=" + TransitionConfig.openDurationFor(TransitionConfig.UiCategory.CHAT)
                        + " 全局=" + TransitionConfig.openDurationMs());
        TransitionConfig.setCategoryCurve(TransitionConfig.UiCategory.CHAT,
                TransitionConfig.Curve.FOLLOW_ID);
        check("曲线设回 default 表示跟随全局",
                TransitionConfig.Curve.FOLLOW_ID.equals(
                        TransitionConfig.categoryCurveId(TransitionConfig.UiCategory.CHAT)),
                "id=" + TransitionConfig.categoryCurveId(TransitionConfig.UiCategory.CHAT));

        // 分类曲线真的参与透明度计算，而不是只存在配置里
        TransitionConfig.setOpenCurve("linear");
        TransitionConfig.setCloseCurve("linear");
        TransitionConfig.setCategoryCurve(TransitionConfig.UiCategory.CONTAINER, "quart");
        float globalMid = TransitionConfig.openCurve().easeOut(0.5F);
        float categoryMid = TransitionConfig.curveForCategory(
                TransitionConfig.UiCategory.CONTAINER).easeOut(0.5F);
        check("分类曲线与全局曲线是两条不同的求值",
                Math.abs(globalMid - categoryMid) > 0.05F,
                "全局=" + globalMid + " 容器分类=" + categoryMid);

        // ---------- 18) 分类配置的**存取往返**（最容易静默丢数据的一环）----------
        //
        // 只写"不跟随全局"的分类，所以必须验证两件事：
        //   · 配过的分类，存盘再读回来还在；
        //   · 没配过的分类，读回来仍是"跟随全局"（而不是被写成全局的值）。
        TransitionConfig.resetToDefaults();
        TransitionConfig.setCategoryCurve(TransitionConfig.UiCategory.GAME_MENU, "expo");
        TransitionConfig.setCategoryDuration(TransitionConfig.UiCategory.GAME_MENU, true, 900);
        TransitionConfig.save();
        TransitionConfig.ensureLoaded();   // 公开的读取入口（load() 是私有的）
        check("分类的曲线能存盘再读回来",
                "expo".equals(TransitionConfig.categoryCurveId(TransitionConfig.UiCategory.GAME_MENU)),
                "id=" + TransitionConfig.categoryCurveId(TransitionConfig.UiCategory.GAME_MENU));
        check("分类的时长能存盘再读回来",
                TransitionConfig.hasOwnDuration(TransitionConfig.UiCategory.GAME_MENU, true)
                        && TransitionConfig.closeDurationFor(TransitionConfig.UiCategory.GAME_MENU) == 900,
                "ownClose=" + TransitionConfig.hasOwnDuration(TransitionConfig.UiCategory.GAME_MENU, true)
                        + " 值=" + TransitionConfig.closeDurationFor(TransitionConfig.UiCategory.GAME_MENU));
        check("没配过的分类读回来仍是跟随全局（不被写成全局的值）",
                TransitionConfig.Curve.FOLLOW_ID.equals(
                        TransitionConfig.categoryCurveId(TransitionConfig.UiCategory.CREATIVE))
                        && !TransitionConfig.hasOwnDuration(TransitionConfig.UiCategory.CREATIVE, true),
                "id=" + TransitionConfig.categoryCurveId(TransitionConfig.UiCategory.CREATIVE)
                        + " ownClose=" + TransitionConfig.hasOwnDuration(TransitionConfig.UiCategory.CREATIVE, true));

        // ---------- 19) 分类判定：类名 → 分类 ----------
        check("容器界面归到 container",
                TransitionConfig.UiCategory.of("net.minecraft.client.gui.screens.inventory.ChestScreen")
                        == TransitionConfig.UiCategory.CONTAINER,
                "=" + TransitionConfig.UiCategory.of(
                        "net.minecraft.client.gui.screens.inventory.ChestScreen"));
        check("聊天输入框归到 chat",
                TransitionConfig.UiCategory.of("net.minecraft.client.gui.screens.ChatScreen")
                        == TransitionConfig.UiCategory.CHAT,
                "=" + TransitionConfig.UiCategory.of("net.minecraft.client.gui.screens.ChatScreen"));
        check("创造物品栏归到 creative",
                TransitionConfig.UiCategory.of(
                        "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen")
                        == TransitionConfig.UiCategory.CREATIVE,
                "=" + TransitionConfig.UiCategory.of(
                        "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen"));
        check("认不出来的界面归到 other（不是崩、也不是当成容器）",
                TransitionConfig.UiCategory.of("com.example.WeirdScreen") == TransitionConfig.UiCategory.OTHER
                        && TransitionConfig.UiCategory.of(null) == TransitionConfig.UiCategory.OTHER,
                "=" + TransitionConfig.UiCategory.of("com.example.WeirdScreen"));

        TransitionConfig.resetToDefaults();
        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println("FAILED: " + failures + " 项未通过");
            System.exit(3);
        }
    }

    private static boolean excludedWorks(Screen screen) {
        TransitionConfig.setExcludedScreens(screen.getClass().getName());
        boolean animated = UiTransitions.shouldAnimate(screen);
        TransitionConfig.setExcludedScreens("");
        return !animated;
    }

    /** 让界面进入打开动画中段，返回该曲线下的位移，用于比较曲线差异 */
    private static float curveValue(Screen screen, Gui gui, GuiGraphicsExtractor extractor,
                                    String curveId) throws Exception {
        TransitionConfig.setCurveId(curveId);
        TransitionConfig.setDurationMsBoth(2000);
        Screen fresh = new EmptyContainer();
        openPanel(gui, fresh);
        UiTransitions.beginContentLayer(fresh, extractor);
        Thread.sleep(800);
        Matrix3x2fStack.reset();
        UiTransitions.beginContentLayer(fresh, extractor);
        float shift = Matrix3x2fStack.lastTranslateY;
        UiTransitions.endContentLayer(fresh, extractor);
        gui.setScreen(null);
        Thread.sleep(100);
        return shift;
    }

    /** 采样打开动画全程的最小位移：果冻开启时应当出现负值（冲过静止位置） */
    private static float openShiftAtPeak(Screen unused, Gui gui, GuiGraphicsExtractor extractor)
            throws Exception {
        Screen fresh = new EmptyContainer();
        TransitionConfig.setDurationMsBoth(1200);
        openPanel(gui, fresh);
        float min = Float.MAX_VALUE;
        long deadline = System.currentTimeMillis() + 1600L;
        while (System.currentTimeMillis() < deadline) {
            Matrix3x2fStack.reset();
            UiTransitions.beginContentLayer(fresh, extractor);
            min = Math.min(min, Matrix3x2fStack.lastTranslateY);
            UiTransitions.endContentLayer(fresh, extractor);
            Thread.sleep(40);
        }
        gui.setScreen(null);
        Thread.sleep(80);
        return min;
    }

    private static void openPanel(Gui gui, Screen screen) {
        UiTransitions.interceptSetScreen(gui, screen);
        gui.setScreen(screen);
    }

    /** 反射读一个 private static int 常量（用于断言"命中范围 vs 视觉尺寸"这类比例关系） */
    private static int readStaticInt(Class<?> owner, String name) throws Exception {
        java.lang.reflect.Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void check(String label, boolean ok, String detail) {        if (!ok) {
            failures++;
        }
        System.out.printf("%-6s %-40s %s%n", ok ? "[OK]" : "[FAIL]", label, detail);
    }

    static class EmptyContainer extends AbstractContainerScreen<Object> {
    }

    /** 把通用曲线设成 custom 并求某一时刻的缓出值 */
    private static float customEaseOut(float t) {
        TransitionConfig.setCurveId("custom");
        return TransitionConfig.curve().easeOut(t);
    }

    /**
     * 从位姿矩阵里取出 y 方向位移。
     * pose 为 null 时 shiftPipPose 会原样返回 null —— 那说明"没有位移"，
     * 但为了区分"没位移"和"没生效"，这里把 null 视作 0，另用位移值本身判断。
     */
    private static float pipShiftY(org.joml.Matrix3x2fc pose) {
        if (pose == null) {
            return 0.0F;
        }
        if (pose instanceof org.joml.Matrix3x2f) {
            return ((org.joml.Matrix3x2f) pose).m21();
        }
        return 0.0F;
    }

    /** 类名里含 "Entity"，用来命中 isPlayerPreview 的判定 */
    static class FakeGuiEntityState {
    }
}
