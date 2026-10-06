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
        TransitionConfig.setDurationMs(2000);

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
        TransitionConfig.setDurationMs(2000);
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
        TransitionConfig.setDurationMs(2000);
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

        // 非动画帧必须回到完全不透明，否则物品会被残留值错误淡出。
        // 用"同类界面切换被跳过"来构造一个确实不做动画的界面。
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

        // ---------- 8b) 关闭时分两段消失：内容先没、底板最后 ----------
        TransitionConfig.setStaggerClose(true);
        TransitionConfig.setAnimatePanel(true);
        gui.setScreen(new Screen());
        Screen closing = new EmptyContainer();
        // 先把打开动画完整走完（用较短的时长省时间）。
        // 若在打开到一半时就关闭，"打断接续"会把关闭进度回拨到与当前可见透明度一致的位置；
        // 而此刻面板几乎是全透明的，于是关闭会被判定为"已经结束"、瞬间完成 ——
        // 那是正确行为，但那样就测不到关闭中段了。
        TransitionConfig.setDurationMs(200);
        openPanel(gui, closing);
        UiTransitions.beginContentLayer(closing, extractor);
        UiTransitions.endContentLayer(closing, extractor);
        Thread.sleep(350);                                    // 等打开动画结束并标记为已就位
        TransitionConfig.setDurationMs(2000);
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
        TransitionConfig.setDurationMs(2000);
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
        TransitionConfig.setDurationMs(150);

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
        TransitionConfig.setCurveCustom("0,0,1,1");          // 与线性等价
        float linearLike = customEaseOut(0.5F);
        TransitionConfig.setCurveCustom("0.34,1.56,0.64,1"); // 带回弹
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

        // 玩家模型：跟随模式应与内容层同透明度，而不是被推迟或隐藏
        TransitionConfig.setFade(true);
        TransitionConfig.setPlayerModelFollowsAnimation(true);
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
        check("玩家模型跟随动画时与内容层同透明度",
                followAlpha == layerAlpha && followAlpha < 255,
                "内容层=" + layerAlpha + " 模型=" + followAlpha);

        TransitionConfig.setPlayerModelFollowsAnimation(false);
        TransitionConfig.setHidePlayerModelOnClose(true);
        UiTransitions.interceptSetScreen(gui, null);        // 触发关闭 -> HIDE_PREVIEW
        Thread.sleep(100);
        UiTransitions.beginContentLayer(pipHost, extractor);
        UiTransitions.beginPipBlit(fakeEntity);
        int hiddenAlpha = UiTransitions.applyAlphaBlit(0xFFFFFFFF) >>> 24;
        UiTransitions.endPipBlit();
        UiTransitions.endContentLayer(pipHost, extractor);
        check("关掉跟随开关后恢复旧行为（关闭时直接隐藏模型）",
                hiddenAlpha == 0, "alpha=" + hiddenAlpha);

        UiTransitions.setOverlayModPresentForTest(null);

        // ---------- 12) 画中画跟随位移 + 渐入/渐出各有一份自定义曲线 ----------
        TransitionConfig.setPlayerModelFollowsAnimation(true);
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
        TransitionConfig.setDurationMs(2000);
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
        TransitionConfig.setDurationMs(1200);
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

    private static void check(String label, boolean ok, String detail) {
        if (!ok) {
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
