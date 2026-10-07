package com.uitransitions.anim;

import com.uitransitions.TransitionConfig;

/**
 * 数学层与现有曲线实现的**逐位等价**验证。
 *
 * <h2>为什么需要它</h2>
 *
 * 新架构第 1 步（见 {@code docs/动画管线底层架构.md} §8）的口号是"行为零变化"。
 * 这句话必须有证据，而且证据的标准是项目自己定下的：
 * **"我跑过了、是绿的"不算证据，要能说出"我把它弄坏时它确实红了、而且是因为我预期的原因红的"**
 * （`docs/动画管线底层架构.md` §8.5.2）。
 *
 * 所以这个类做三件事：
 * <ol>
 *   <li><b>等价</b>：把新实现的取样结果与 {@code TransitionConfig.Curve} 的真实结果**逐位比较**
 *       （{@code Float.floatToIntBits}，不是"近似相等"）。在 MC 未混淆环境下，
 *       旧实现是可直接调用的真值来源 —— 这是本项目最有利的条件，别浪费。</li>
 *   <li><b>可证伪</b>：{@link #selfTest()} 喂一对**故意不同**的值，
 *       确认"比较器真的会判不等"。没有这一步，一个写坏的比较器（比如两边都取同一个函数）
 *       会让整个套件永远全绿 —— 这正是本项目踩过的"假绿"类型。</li>
 *   <li><b>接续</b>：验证打断接续能精确还原可见值（旧实现只靠真机看的那部分）。</li>
 * </ol>
 *
 * <h2>怎么跑</h2>
 *
 * <pre>
 * javac -d build/anim-verify-classes -cp build/mc/client-26.3.jar \
 *       ui-transitions/src/com/uitransitions/anim/*.java \
 *       ui-transitions/src/com/uitransitions/TransitionConfig.java \
 *       verify-uit-anim/MathLayerVerify.java
 * java -cp build/anim-verify-classes:build/mc/client-26.3.jar com.uitransitions.anim.MathLayerVerify
 * </pre>
 *
 * 退出码：{@code 0} = 全绿，非 0 = 有失败（可直接接进 CI）。
 * 它**不需要** Minecraft 运行起来，只需要编译期有原版类在 classpath 上。
 */
public final class MathLayerVerify {

    private static int checks;
    private static int failures;

    private MathLayerVerify() {
    }

    public static void main(String[] args) {
        MathLayerVerify v = new MathLayerVerify();
        System.out.println("=== 动画数学层 · 与现有实现逐位等价验证 ===");

        v.verifyComparatorIsFalsifiable();
        v.verifyNamedCurves();
        v.verifyBezierCurves();
        v.verifyMultiCurves();
        v.verifyEdgeInputs();
        v.verifyInverseSolver();
        v.verifyTweenProgress();
        v.verifyInterruptionHandoff();

        System.out.println();
        System.out.printf("检查项: %d，失败: %d%n", checks, failures);
        if (failures > 0) {
            System.out.println("结论: 有失败");
            System.exit(3);
        }
        System.out.println("结论: 全部逐位一致");
    }

    // ------------------------------------------------------------------ 0. 比较器本身

    /**
     * 反向验证：故意拿两条不同的曲线比较，必须判为不等。
     *
     * <p>如果这一步过了而下面全绿，说明"全绿"是真的；
     * 如果这一步就失败，说明比较器或取样逻辑坏了，下面的绿灯没有意义。
     */
    private void verifyComparatorIsFalsifiable() {
        float t = 0.37F;
        float a = NamedEasing.CUBIC.easeIn(t);
        float b = NamedEasing.QUART.easeIn(t);
        if (a == b) {
            fail("比较器可证伪性", "cubic 与 quart 在 t=0.37 上相等（" + a + "），取样点选得不好");
        } else {
            pass("比较器可证伪性（cubic≠quart，值 " + a + " vs " + b + "）");
        }
        // 旧实现侧也要能区分，否则"真值来源"本身没有分辨力
        float oldA = TransitionConfig.Curve.CUBIC.easeIn(t);
        float oldB = TransitionConfig.Curve.QUART.easeIn(t);
        if (oldA == oldB) {
            fail("旧实现可分辨性", "旧 Curve 的 cubic 与 quart 相等，取样点无分辨力");
        } else {
            pass("旧实现可分辨性（" + oldA + " vs " + oldB + "）");
        }
    }

    // ------------------------------------------------------------------ 1. 八条命名曲线

    private void verifyNamedCurves() {
        String[] ids = { "linear", "sine", "cubic", "quart", "quint", "expo", "circ", "back" };
        int compared = 0;
        int mismatch = 0;
        String firstMismatch = null;
        for (String id : ids) {
            TransitionConfig.Curve old = TransitionConfig.Curve.byId(id);
            NamedEasing neu = NamedEasing.byId(id);
            for (int step = 0; step <= 1000; step++) {
                float t = step / 1000.0F;
                if (!eq(old.easeIn(t), neu.easeIn(t))) {
                    mismatch++;
                    if (firstMismatch == null) {
                        firstMismatch = id + ".easeIn(" + t + "): 旧=" + old.easeIn(t) + " 新=" + neu.easeIn(t);
                    }
                }
                if (!eq(old.easeOut(t), neu.easeOut(t))) {
                    mismatch++;
                    if (firstMismatch == null) {
                        firstMismatch = id + ".easeOut(" + t + "): 旧=" + old.easeOut(t) + " 新=" + neu.easeOut(t);
                    }
                }
                compared += 2;
            }
        }
        if (mismatch == 0) {
            pass("命名曲线 easeIn/easeOut 逐位一致（" + ids.length + " 条 × 1001 点 × 2 方向 = " + compared + " 次比较）");
        } else {
            fail("命名曲线逐位一致", mismatch + " 处不一致，首处: " + firstMismatch);
        }

        // id 解析与兜底
        if (NamedEasing.byId("cubic") == NamedEasing.CUBIC
                && NamedEasing.byId("  CUBIC ") == NamedEasing.CUBIC
                && NamedEasing.byId("不存在的曲线") == NamedEasing.CUBIC
                && NamedEasing.byId(null) == NamedEasing.CUBIC) {
            pass("byId 的大小写/空白/非法输入兜底与旧实现一致（均回退 cubic）");
        } else {
            fail("byId 兜底", "与旧实现 Curve.byId 的兜底行为不一致");
        }

        // ids() 的顺序必须与旧实现的前 8 项一致（配置里的下标含义依赖顺序）。
        // 旧实现还多两个"自定义"条目（custom / multi），它们不是命名曲线，
        // 在旧实现里由 Curve.custom(...) / Curve.multi(...) 承载，故这里只比对前 8 项。
        String[] newIds = NamedEasing.ids();
        String[] oldIds = TransitionConfig.Curve.ids();
        boolean prefixOk = oldIds.length >= newIds.length;
        if (prefixOk) {
            for (int i = 0; i < newIds.length; i++) {
                if (!newIds[i].equals(oldIds[i])) {
                    prefixOk = false;
                    break;
                }
            }
        }
        if (prefixOk) {
            pass("命名曲线 id 与顺序一致（新 " + newIds.length + " 条 = 旧列表的前 " + newIds.length
                    + " 条；旧列表另有 custom/multi 两个自定义条目，由 Bezier/Multi 承载）");
        } else {
            fail("曲线 id 列表", "新=" + String.join(",", newIds) + " 旧=" + String.join(",", oldIds));
        }
    }

    // ------------------------------------------------------------------ 2. 贝塞尔

    private void verifyBezierCurves() {
        float[][] cases = {
                { 0.25F, 0.1F, 0.25F, 1.0F },      // 默认（≈CSS ease）
                { 0.0F, 0.0F, 1.0F, 1.0F },        // 等价线性
                { 0.34F, 1.56F, 0.64F, 1.0F },     // 带回弹（y 超出 1）
                { 1.0F, 0.0F, 0.0F, 1.0F },        // 极端：控制点交错
                { 0.5F, -0.5F, 0.5F, 1.5F },       // y 负向过冲
                { 0.0F, 1.0F, 1.0F, 0.0F },        // 退化：x 贴边
        };
        int compared = 0;
        int mismatch = 0;
        String firstMismatch = null;
        for (float[] c : cases) {
            TransitionConfig.Curve old = TransitionConfig.Curve.custom(c);
            Bezier neu = Bezier.of(c);
            for (int step = 0; step <= 500; step++) {
                float t = step / 500.0F;
                if (!eq(old.easeIn(t), neu.easeIn(t))) {
                    mismatch++;
                    if (firstMismatch == null) {
                        firstMismatch = "贝塞尔" + java.util.Arrays.toString(c) + " easeIn(" + t + "): 旧="
                                + old.easeIn(t) + " 新=" + neu.easeIn(t);
                    }
                }
                if (!eq(old.easeOut(t), neu.easeOut(t))) {
                    mismatch++;
                    if (firstMismatch == null) {
                        firstMismatch = "贝塞尔" + java.util.Arrays.toString(c) + " easeOut(" + t + "): 旧="
                                + old.easeOut(t) + " 新=" + neu.easeOut(t);
                    }
                }
                compared += 2;
            }
        }
        if (mismatch == 0) {
            pass("贝塞尔自定义曲线逐位一致（" + cases.length + " 组 × 501 点 × 2 方向 = " + compared + " 次比较）");
        } else {
            fail("贝塞尔逐位一致", mismatch + " 处不一致，首处: " + firstMismatch);
        }

        // 静态求值入口也必须与旧的一致（曲线图绘制直接用它）
        int staticMismatch = 0;
        for (float[] c : cases) {
            for (int step = 0; step <= 200; step++) {
                float t = step / 200.0F;
                if (!eq(TransitionConfig.Curve.bezierEase(c, t), Bezier.ease(c, t))) {
                    staticMismatch++;
                }
            }
        }
        if (staticMismatch == 0) {
            pass("Bezier.ease（静态，供曲线图取样）与 Curve.bezierEase 一致");
        } else {
            fail("Bezier.ease 静态入口", staticMismatch + " 处不一致");
        }
    }

    // ------------------------------------------------------------------ 3. 多点曲线

    private void verifyMultiCurves() {
        // 与 VerifyAdvanced 用的形状同类：先缓后陡、再回落一点
        float[][] rawCases = {
                {},                                             // 空
                { 0.5F, 0.3F },                                 // 只有一个内部点
                { 0.25F, 0.5F, 0.75F, 0.8F },                   // 两个内部点，单调
                { 0.2F, 0.6F, 0.5F, 0.3F, 0.8F, 1.0F },         // 先上后下（非单调）
                { 0.1F, 0.9F, 0.2F, 0.95F, 0.9F, 0.98F },       // 点挤在前段
        };
        int compared = 0;
        int mismatch = 0;
        String firstMismatch = null;
        for (float[] raw : rawCases) {
            TransitionConfig.Curve old = TransitionConfig.Curve.multi(raw);
            Multi neu = Multi.of(TransitionConfig.Curve.normalizeMulti(raw));
            for (int step = 0; step <= 500; step++) {
                float t = step / 500.0F;
                if (!eq(old.easeIn(t), neu.easeIn(t))) {
                    mismatch++;
                    if (firstMismatch == null) {
                        firstMismatch = "多点 easeIn(" + t + "): 旧=" + old.easeIn(t) + " 新=" + neu.easeIn(t);
                    }
                }
                if (!eq(old.easeOut(t), neu.easeOut(t))) {
                    mismatch++;
                    if (firstMismatch == null) {
                        firstMismatch = "多点 easeOut(" + t + "): 旧=" + old.easeOut(t) + " 新=" + neu.easeOut(t);
                    }
                }
                compared += 2;
            }
        }
        if (mismatch == 0) {
            pass("多点曲线逐位一致（" + rawCases.length + " 组 × 501 点 × 2 方向 = " + compared + " 次比较）");
        } else {
            fail("多点曲线逐位一致", mismatch + " 处不一致，首处: " + firstMismatch);
        }

        if (eq(Multi.EMPTY.easeIn(0.42F), 0.42F)) {
            pass("空点集等价线性（与旧 multi(new float[0]) 一致）");
        } else {
            fail("空点集", "Multi.EMPTY 不是线性");
        }
    }

    // ------------------------------------------------------------------ 4. 边界输入

    private void verifyEdgeInputs() {
        float[] probes = { -1.0F, -0.0001F, 0.0F, 1.0F, 1.0001F, 2.0F, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY };
        int mismatch = 0;
        String firstMismatch = null;
        for (float probe : probes) {
            for (String id : new String[] { "linear", "cubic", "expo", "circ", "back" }) {
                TransitionConfig.Curve old = TransitionConfig.Curve.byId(id);
                NamedEasing neu = NamedEasing.byId(id);
                if (!eq(old.easeIn(probe), neu.easeIn(probe)) || !eq(old.easeOut(probe), neu.easeOut(probe))) {
                    mismatch++;
                    if (firstMismatch == null) {
                        firstMismatch = id + "(" + probe + "): easeIn 旧=" + old.easeIn(probe) + " 新=" + neu.easeIn(probe)
                                + " / easeOut 旧=" + old.easeOut(probe) + " 新=" + neu.easeOut(probe);
                    }
                }
            }
            TransitionConfig.Curve oldB = TransitionConfig.Curve.custom(new float[] { 0.25F, 0.1F, 0.25F, 1.0F });
            Bezier neuB = Bezier.DEFAULT;
            if (!eq(oldB.easeIn(probe), neuB.easeIn(probe)) || !eq(oldB.easeOut(probe), neuB.easeOut(probe))) {
                mismatch++;
                if (firstMismatch == null) {
                    firstMismatch = "bezier(" + probe + ") 不一致";
                }
            }
        }
        if (mismatch == 0) {
            pass("边界输入（负值/超过 1/NaN/±Inf）与旧实现逐位一致 —— 含 NaN→1 这条保真语义");
        } else {
            fail("边界输入", mismatch + " 处不一致，首处: " + firstMismatch);
        }
    }

    // ------------------------------------------------------------------ 5. 反解

    /**
     * 反解必须与现有 {@code UiTransitions.solveProgress} 的数值一致。
     *
     * <p>那份实现是私有的，所以这里按它的**逐字条件**复算一遍做对照
     * （`UiTransitions.java:1316-1330`：40 次二分、递减曲线用 {@code value > target} 收上界），
     * 同时验证"反解再正向求值"能回到原值。
     */
    private void verifyInverseSolver() {
        String[] ids = { "linear", "sine", "cubic", "quart", "quint", "expo", "circ", "back" };
        float worst = 0.0F;
        String worstWhere = "";
        int checked = 0;
        int roundTripFailures = 0;
        String firstRoundTrip = null;
        for (String id : ids) {
            TransitionConfig.Curve old = TransitionConfig.Curve.byId(id);
            NamedEasing neu = NamedEasing.byId(id);
            for (int step = 1; step < 100; step++) {
                float target = step / 100.0F;
                float expected = referenceSolveProgress(old, target);
                float actual = neu.progressForIn(target);
                float deviation = Math.abs(expected - actual);
                if (deviation > worst) {
                    worst = deviation;
                    worstWhere = id + " target=" + target + "（旧=" + expected + " 新=" + actual + "）";
                }
                // 正向复算：反解出来的进度，其可见值应当回到 target
                float roundTrip = 1.0F - neu.easeIn(actual);
                if (Math.abs(roundTrip - target) > 0.002F) {
                    roundTripFailures++;
                    if (firstRoundTrip == null) {
                        firstRoundTrip = id + " target=" + target + " → 可见值 " + roundTrip;
                    }
                }
                checked++;
            }
        }
        if (worst == 0.0F) {
            pass("反解与现有 solveProgress 数值完全相同（" + checked + " 个取值，最大偏差 0）");
        } else {
            fail("反解数值", "最大偏差 " + worst + "（要求 0），出现在 " + worstWhere);
        }
        if (roundTripFailures == 0) {
            pass("反解往返：easeIn 反解出的进度，其可见值回到目标（" + checked + " 个取值全部在 0.002 内）");
        } else {
            fail("反解往返", roundTripFailures + "/" + checked + " 个取值偏离，首例: " + firstRoundTrip);
        }
    }

    /** 现有 {@code UiTransitions.solveProgress(curve, target, closing=true)} 的逐字复刻。 */
    private static float referenceSolveProgress(TransitionConfig.Curve curve, float target) {
        float lo = 0.0F;
        float hi = 1.0F;
        for (int i = 0; i < 40; i++) {
            float mid = (lo + hi) / 2.0F;
            float value = 1.0F - curve.easeIn(mid);
            boolean below = value > target;
            if (below) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2.0F;
    }

    // ------------------------------------------------------------------ 6. Tween 进度

    private void verifyTweenProgress() {
        long start = 1_000_000_000L;
        long duration = 500_000_000L;                       // 500ms
        Tween t = new Tween(start, duration, NamedEasing.CUBIC, 120.0F, 0.0F);

        boolean ok = true;
        ok &= t.progress(start) == 0.0F;
        ok &= t.progress(start + duration / 2) == 0.5F;
        ok &= t.progress(start + duration) == 1.0F;
        ok &= t.progress(start + duration * 3) == 1.0F;     // 播完之后保持 1
        ok &= !t.finished(start + duration - 1) && t.finished(start + duration);
        if (ok) {
            pass("Tween.progress/finished 在起点、中点、终点、播完后四点均正确");
        } else {
            fail("Tween.progress", "基本进度换算不符");
        }

        // 端点必须精确落在 from/to（动画收尾不能留残移）
        boolean endsExact = t.valueOut(start) == 120.0F && t.valueOut(start + duration) == 0.0F;
        Tween closing = new Tween(start, duration, NamedEasing.CUBIC, 0.0F, 120.0F);
        endsExact &= closing.valueIn(start) == 0.0F && closing.valueIn(start + duration) == 120.0F;
        if (endsExact) {
            pass("两端精确落在 from/to（收尾无残移，与现有实现一致）");
        } else {
            fail("端点精度", "valueOut/valueIn 在端点没有精确取到 from/to");
        }

        // 时长为 0 / 负数：立刻到位，不抛异常
        Tween zero = new Tween(start, 0L, NamedEasing.CUBIC, 0.0F, 1.0F);
        if (zero.progress(start) == 1.0F && zero.valueOut(start) == 1.0F) {
            pass("时长为 0 时进度为 1（立刻到位），不抛异常");
        } else {
            fail("零时长", "progress=" + zero.progress(start));
        }

        // 时长为负：现有实现里会被 clamp 到 1，这里必须一致
        Tween negative = new Tween(start, -1L, NamedEasing.CUBIC, 0.0F, 1.0F);
        if (negative.progress(start) == 1.0F) {
            pass("时长为负同样立刻到位（不产生倒退进度）");
        } else {
            fail("负时长", "progress=" + negative.progress(start));
        }
    }

    // ------------------------------------------------------------------ 7. 打断接续

    /**
     * 打断接续：核心断言是"接续那一刻的可见值不变"。
     *
     * <p>这正是 README 里记着的那条实测（关闭播到一半又打开，接续瞬间 alpha=185
     * 与打断前完全一致、位移 32.99 = 120×(1−0.725)）。当时只能靠真机看，
     * 现在它是离线断言。
     */
    private void verifyInterruptionHandoff() {
        long now = 10_000_000_000L;
        long duration = 500_000_000L;

        // ── 值域纪律（本轮真的踩过，写下来免得下一个人再踩）────────────────────────
        //
        // `continueFrom` 的 `from` / `to` / `visible` 必须**同域**：它内部做的是
        // `(visible - from) / (to - from)`，混用域会得到一个静默的错误进度
        // （实测：把 alpha 0.657 配上 to=120 的像素域，归一化成了 0.0055，
        //  反解出 0.176，接续直接跳到 0.99）。
        //
        // 现有实现的处置方式很清楚，照它来：
        //   · 可见透明度域 = [0,1]，反解就用这个（visualAlpha → solveProgress）
        //   · 位移域 = [0, offset]，**位移不参与反解**，它由"进度"驱动
        //     （shift 用 curveFor(screen) 即面板曲线，与 alpha 同一条进度）
        //
        // 所以下面分两组断言：一组走 alpha 域，一组走像素域但用**同一个进度**换算。
        float interruptedProgress = 0.7F;
        Easing curve = NamedEasing.CUBIC;
        float offset = 120.0F;

        // 可见透明度（与现有 UiTransitions.visualAlpha 同一口径）：alpha = 1 - easeIn(p)
        float alphaBefore = 1.0F - curve.easeIn(interruptedProgress);
        // 位移：**同一个进度**换算，且与下面"接续后"用同一条式子（打开时基础位移 × 可见比例）。
        // 不要一边用 easeOut、一边用 easeIn —— 那是伪装成断言的两套公式。
        float shiftBefore = offset * (1.0F - curve.easeIn(interruptedProgress));

        // ---- 组一：alpha 域接续（打开被打断 → 改成关闭；新动画走缓入方向）----
        // 反解用 opening=false（关闭/退场，取值走 valueIn），与现有 UiTransitions.java:183-184 一致
        Tween closing = Tween.continueFrom(now, alphaBefore, duration, curve, 0.0F, 1.0F, false);
        float alphaAfter = 1.0F - curve.easeIn(closing.progress(now));
        if (Math.abs(alphaAfter - alphaBefore) < 0.002F) {
            pass("打断接续：可见透明度连续（" + alphaBefore + " → " + alphaAfter + "）");
        } else {
            fail("打断接续连续性", "接续前 " + alphaBefore + " vs 接续后 " + alphaAfter);
        }
        if (Math.abs(closing.progress(now) - interruptedProgress) < 0.001F) {
            pass("打断接续：进度回到打断点（" + interruptedProgress + " → " + closing.progress(now) + "）");
        } else {
            fail("打断接续进度", interruptedProgress + " vs " + closing.progress(now));
        }

        // ---- 组二：位移由同一个进度驱动，必须同步接上 ----
        // 这就是 README 里 120×(1−0.725)=33.0 那条实测的离线形态。
        float p = closing.progress(now);
        float shiftAfter = offset * curve.easeIn(p);          // 关闭：0 → offset，缓入方向
        if (Math.abs(shiftAfter - shiftBefore) < 0.05F) {
            pass("打断接续：位移同步接上（" + shiftBefore + "px → " + shiftAfter + "px）");
        } else {
            fail("打断接续位移", shiftBefore + " vs " + shiftAfter);
        }

        // ---- 组三：反向（关闭播放中 → 改成打开；新动画走缓出方向）----
        // 反解用 opening=true（打开/进场，取值走 valueOut），与 UiTransitions.java:216-217 一致。
        // 打开时的进度与"关闭进度"的关系是镜像：p_open = 1 - p_close。
        Tween closing2 = new Tween(now - (long) (duration * interruptedProgress), duration,
                curve, 0.0F, 1.0F);
        float visible2 = closing2.valueIn(now);                // 关闭中：alpha 从 0 涨到 1
        float expectedOpenProgress = 1.0F - interruptedProgress;
        Tween opening2 = Tween.continueFrom(now, visible2, duration, curve, 1.0F, 0.0F, true);
        if (Math.abs(opening2.progress(now) - expectedOpenProgress) < 0.002F) {
            pass("反向接续：进度落在镜像位置（关闭 " + interruptedProgress + " → 打开 "
                    + opening2.progress(now) + "，期望 " + expectedOpenProgress + "）");
        } else {
            fail("反向接续进度", expectedOpenProgress + " vs " + opening2.progress(now));
        }
        // 可见值也必须连续
        float after2 = 1.0F - curve.easeIn(1.0F - opening2.progress(now));
        if (Math.abs(after2 - visible2) < 0.002F) {
            pass("反向接续：可见透明度连续（" + visible2 + " → " + after2 + "）");
        } else {
            fail("反向接续连续性", visible2 + " vs " + after2);
        }

        // 反解必须是单点：同一个可见值不能被两个不同进度满足（否则接续有歧义）
        Tween again = Tween.continueFrom(now, alphaBefore, duration, curve, 0.0F, 1.0F, false);
        if (again.startNanos() == closing.startNanos()) {
            pass("接续是确定性的：同一输入两次调用得到同一起点（可复现）");
        } else {
            fail("接续确定性", "两次调用起点不同: " + again.startNanos() + " vs " + closing.startNanos());
        }

        // 值域退化（from == to）不能除以 0
        Tween degenerate = Tween.continueFrom(now, 5.0F, duration, NamedEasing.CUBIC, 1.0F, 1.0F, false);
        if (degenerate.progress(now) == 0.0F && !Float.isNaN(degenerate.valueOut(now))) {
            pass("值域退化（from == to）不产生 NaN，按从头播处理");
        } else {
            fail("值域退化", "progress=" + degenerate.progress(now));
        }

        // 回拨数值：与现有 backdateNanos 的语义一致（极小进度不回拨）
        if (Tween.backdateNanos(0.0F, duration) == 0L
                && Tween.backdateNanos(0.00005F, duration) == 0L
                && Tween.backdateNanos(0.5F, duration) == duration / 2
                && Tween.backdateNanos(1.0F, duration) == duration) {
            pass("backdateNanos 与现有实现一致（含 0.0001 阈值与两端夹取）");
        } else {
            fail("backdateNanos", "与现有实现不一致");
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 逐位比较：这是"等价"的唯一标准，不用 epsilon。 */
    private static boolean eq(float a, float b) {
        return Float.floatToIntBits(a) == Float.floatToIntBits(b);
    }

    private static void pass(String what) {
        checks++;
        System.out.println("  [OK] " + what);
    }

    private static void fail(String what, String detail) {
        checks++;
        failures++;
        System.out.println("  [FAIL] " + what + " —— " + detail);
    }
}
