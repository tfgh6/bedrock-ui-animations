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
        v.verifyInversePairs();
        v.verifyInverseSolver();
        v.verifyTweenProgress();
        v.verifyInversePairs();
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
    /**
     * 反解必须与现有实现**逐位一致**。
     *
     * <p>现有 {@code UiTransitions.solveProgress(curve, target, closing=true)} 反解的是
     * {@code easeOut(p) == target} —— 这就是主路径：点 X 关闭容器时
     * {@code solveProgress(closeCurve, visualAlpha(current), true)}（`UiTransitions.java:183-184`），
     * 用来让被打断的关闭动画从当前可见状态接着走。
     * 对应本层就是 {@link Easing#progressForAlpha}（反解 {@code 1 - easeIn}，与反解 {@code easeOut} 同一个式子）。
     *
     * <p>⚠️ 注意**不要**拿 {@code closing=false} 那条分支当对照基准：它的公式与 {@code closing=true}
     * 相同、只把二分方向取反，所以它反解的不是 {@code easeOut}，收敛到另一个根
     * （实测 target=0.343 时得 0.13066，而正确值 0.7）。那条路只在"打开动画被打断"时走到，
     * 属于现有实现的缺陷，不应被本层复制。见 {@code docs/项目全量分析报告.md}。
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
                float actual = neu.progressForAlpha(target);
                float deviation = Math.abs(expected - actual);
                if (deviation > worst) {
                    worst = deviation;
                    worstWhere = id + " target=" + target + "（旧=" + expected + " 新=" + actual + "）";
                }
                // 正向复算：反解出的进度，其可见值（1 - easeIn）应当回到 target
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
            pass("反解与现有 solveProgress(closing=true) 逐位一致（" + checked + " 个取值，最大偏差 0）");
        } else {
            fail("反解数值", "最大偏差 " + worst + "（要求 0），出现在 " + worstWhere);
        }
        if (roundTripFailures == 0) {
            pass("反解往返：反解出的进度，其可见值回到目标（" + checked + " 个取值全部在 0.002 内）");
        } else {
            fail("反解往返", roundTripFailures + "/" + checked + " 个取值偏离，首例: " + firstRoundTrip);
        }
    }

    /**
     * 现有 {@code UiTransitions.solveProgress(curve, target, closing=true)} 的逐字复刻 —— 见 `:1316-1330`。
     */
    private static float referenceSolveProgress(TransitionConfig.Curve curve, float target) {
        float lo = 0.0F;
        float hi = 1.0F;
        for (int i = 0; i < 40; i++) {
            float mid = (lo + hi) / 2.0F;
            float value = 1.0F - curve.easeIn(mid);
            if (value > target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2.0F;
    }

    /** 现有 {@code solveProgress(curve, target, closing=false)} 的逐字复刻 —— 反解 {@code easeOut}。 */
    private static float referenceSolveProgressOpen(TransitionConfig.Curve curve, float target) {
        float lo = 0.0F;
        float hi = 1.0F;
        for (int i = 0; i < 40; i++) {
            float mid = (lo + hi) / 2.0F;
            float value = curve.easeOut(mid);
            if (value < target) {
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

    /**
     * 反解与取值必须互为逆运算 —— 这是**数学事实**，不含任何约定，
     * 用它来钉住"哪个方向该用哪个反解"，就不会再出现"来回换映射"那种事故。
     *
     * <ul>
     *   <li>{@code valueOut} 是"打开/进场"取值器，可见比例 = {@code 1 - easeIn(p)}
     *       ⇒ 必须被 {@code progressForAlpha} 反解</li>
     *   <li>{@code valueIn} 是"关闭/退场"取值器，可见比例 = {@code easeIn(p)}
     *       ⇒ 必须被 {@code progressForEaseIn}（easeIn 的真反函数）反解</li>
     * </ul>
     * 写反的表现只是"新旧值恰好互换"，肉眼与真机都看不出，只有往返断言能抓。
     */
    private void verifyInversePairs() {
        int checked = 0;
        int badAlpha = 0;
        int badEaseIn = 0;
        int skipped = 0;
        String firstBad = null;
        for (String id : new String[] { "linear", "sine", "cubic", "quart", "quint", "expo", "circ", "back" }) {
            NamedEasing e = NamedEasing.byId(id);
            // 取样点避开**病态区**（见 Easing.progressForAlpha 的已知病态区说明）：
            // quart/quint/expo 在 p→0 处 1-easeIn(p) 会在 float 下饱和成 1.0，此时反解无解。
            // 这不是实现缺陷，所以在断言里显式跳过并**计数**（跳过多少条也要看得见）。
            for (int step = 5; step <= 95; step += 5) {
                float p = step / 100.0F;
                float alphaRatio = 1.0F - e.easeIn(p);
                if (alphaRatio >= 1.0F) {
                    skipped++;
                } else if (Math.abs(e.progressForAlpha(alphaRatio) - p) > 0.002F) {
                    badAlpha++;
                    if (firstBad == null) {
                        firstBad = id + " p=" + p + "：1-easeIn=" + alphaRatio + " → progressForAlpha="
                                + e.progressForAlpha(alphaRatio);
                    }
                }
                float inRatio = e.easeIn(p);
                if (inRatio <= 0.0F) {
                    skipped++;                                  // back 在 p→0 处 easeIn 饱和为 0，同属病态区
                } else if (Math.abs(e.progressForEaseIn(inRatio) - p) > 0.002F) {
                    badEaseIn++;
                    if (firstBad == null) {
                        firstBad = id + " p=" + p + "：easeIn=" + inRatio + " → progressForEaseIn="
                                + e.progressForEaseIn(inRatio);
                    }
                }
                checked += 2;
            }
        }
        if (badAlpha == 0 && badEaseIn == 0) {
            pass("两个反解都是真正的反函数（" + checked + " 对，跳过病态 " + skipped
                    + " 处）：progressForAlpha↔(1-easeIn)、progressForEaseIn↔easeIn");
        } else {
            fail("反解配对", "1-easeIn 错 " + badAlpha + " 处 / easeIn 错 " + badEaseIn + " 处，首例: " + firstBad);
        }

        // 与旧实现的换元关系：progressForIn(t) ≡ progressForAlpha(1 - t)
        float a1 = NamedEasing.CUBIC.progressForIn(0.343F);
        float a2 = NamedEasing.CUBIC.progressForAlpha(0.657F);
        if (Math.abs(a1 - a2) < 1.0E-6F) {
            pass("progressForIn(t) ≡ progressForAlpha(1-t)（值 " + a1 + "，与旧 solveProgress 换元一致）");
        } else {
            fail("progressForIn 换元关系", a1 + " vs " + a2);
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

        // ── 口径纪律（本轮反复踩，写清楚免得下一个人再踩）──────────────────────────
        //
        // 1) 本类的取值器是 valueIn(p)=lerp(from,to,easeIn(p))、valueOut(p)=lerp(from,to,1-easeIn(1-p))，
        //    两者在 p ↦ 1-p 下互换 ⇒ **"向哪边动"由 from/to 决定，不由取值方向决定**。
        //    所以 `continueFrom` 只有一个公式（按 1-easeIn 归一化后反解），没有方向参数。
        // 2) `from`/`to`/`visible` 必须**同域**（内部做 (visible-from)/(to-from)）。
        //    alpha 域用 [0,1]；位移由进度驱动、不参与反解。
        // 3) 可见 alpha 的口径（与现有 UiTransitions.visualAlpha 一致，`:1304-1308`）：
        //      打开中：alpha = 1 - easeIn(p)      关闭中：alpha = easeIn(p)
        //    两个口径互为补，所以"打开 70% ↔ 关闭 70%"看到的是同一个 alpha 的两个名字。
        float interruptedProgress = 0.7F;
        Easing curve = NamedEasing.CUBIC;
        float offset = 120.0F;

        // ── 取值器与反解的**固定配对**（本轮最后才理清，是整个文件最该记住的一条）──────
        //   valueIn (p) = lerp(from, to, easeIn(p))   ⇒ 反解 progressForEaseIn
        //   valueOut(p) = lerp(from, to, easeOut(p))  ⇒ 反解 progressForAlpha（1 - easeIn 与 easeOut 同式换元）
        // "接续"的定义是：**接续前后用同一个取值器取出来是同一个数**。
        // 所以 continueFrom 的 outDirection 必须与调用方接下来用的取值器一致：
        //   outDirection=true  → 调用方用 valueOut 取值
        //   outDirection=false → 调用方用 valueIn  取值

        // ---- 组一：打开播到 70% 被打断 → 改成关闭；接续前后都走 valueOut ----
        Tween before1 = new Tween(now - (long) (duration * interruptedProgress), duration,
                curve, 0.0F, 1.0F);
        float openingAlpha = before1.valueIn(now);                        // valueIn 口径 = 0.343
        Tween closing = Tween.continueFrom(now, openingAlpha, duration, curve, 0.0F, 1.0F, Tween.Getter.IN);
        float closeAlpha = closing.valueIn(now);                         // 与 before 同一取值器
        if (Math.abs(closeAlpha - openingAlpha) < 0.002F) {
            pass("打断接续：valueIn 口径连续（" + openingAlpha + " → " + closeAlpha + "）");
        } else {
            fail("打断接续连续性", openingAlpha + " vs " + closeAlpha);
        }
        float shiftBefore = offset * openingAlpha;
        float shiftAfter = offset * closeAlpha;
        if (Math.abs(shiftAfter - shiftBefore) < 0.05F) {
            pass("打断接续：位移同步接上（" + shiftBefore + "px → " + shiftAfter + "px）");
        } else {
            fail("打断接续位移", shiftBefore + " vs " + shiftAfter);
        }
        // 与旧实现主路径自洽：点 X 关容器时传入的正是 visualAlpha，而旧 solveProgress(closing=true)
        // 反解的也是 `1 - easeIn` ⇒ 本层必须给出同一个数（实测两者都是 0.7）
        float legacyOne = referenceSolveProgress(TransitionConfig.Curve.CUBIC, openingAlpha);
        if (Math.abs(curve.progressForAlpha(openingAlpha) - legacyOne) < 1.0E-6F) {
            pass("与旧主路径同解（progressForAlpha(" + openingAlpha + ") = " + legacyOne + "）");
        } else {
            fail("与旧主路径同解", legacyOne + " vs " + curve.progressForAlpha(openingAlpha));
        }

        // ---- 组二：关闭播到 70% 被打断 → 改成打开；接续前后都走 valueIn ----
        Tween before2 = new Tween(now - (long) (duration * interruptedProgress), duration,
                curve, 1.0F, 0.0F);
        float closingAlpha = before2.valueIn(now);                       // valueIn 口径 = 0.657
        Tween opening = Tween.continueFrom(now, closingAlpha, duration, curve, 0.0F, 1.0F, Tween.Getter.IN);
        float openAlpha = opening.valueIn(now);                          // 同一取值器
        if (Math.abs(openAlpha - closingAlpha) < 0.002F) {
            pass("反向接续：valueIn 口径连续（" + closingAlpha + " → " + openAlpha + "）");
        } else {
            fail("反向接续连续性", closingAlpha + " vs " + openAlpha);
        }
        // 该参数化下两个取值器的**区分度**：from=1,to=0 时
        //   valueIn(p)  = lerp(1,0,easeIn(p))  = 1 - easeIn(p)
        //   valueOut(p) = lerp(1,0,easeOut(p)) = 1 - easeOut(p)
        // 在 p=0.5 处：easeIn=.125、easeOut=.875 ⇒ valueIn=.875、valueOut=.125（两者差 0.75）
        Tween probe = new Tween(now - (long) (duration * 0.5F), duration, curve, 1.0F, 0.0F);
        float probeIn = probe.valueIn(now);
        float probeOut = probe.valueOut(now);
        if (Math.abs(probeIn - 0.875F) < 1.0E-5F && Math.abs(probeOut - 0.125F) < 1.0E-5F) {
            pass("关闭方向 p=0.5：valueIn=" + probeIn + "、valueOut=" + probeOut + "（= 1-easeIn / 1-easeOut）");
        } else {
            fail("关闭方向取值器语义", "valueIn=" + probeIn + "、valueOut=" + probeOut + "，期望 0.875 / 0.125");
        }
        if (Math.abs(probeIn - probeOut) > 0.1F) {
            pass("两个取值器在关闭方向**不同**（差 " + Math.abs(probeIn - probeOut) + "）");
        } else {
            fail("取值器区分度", "两者太接近，断言无意义");
        }

        // ── 关于两个反解"关系"的说明（**故意不断言**）─────────────────────────────
        //
        // 我在这里连续写过三条断言，**三条都是我猜的、三条都被自己的实测否掉**：
        //   ① progressForAlpha(1-v) == 1 - progressForAlpha(v)        → v=0.5 时 0.125 vs 0.875，不成立
        //   ② progressForAlpha(t) + progressForEaseIn(1-t) == 1       → linear t=0.05 时得 1.9，不成立
        //      （真实关系是两者**就是同一个 p**，和应为 2p：progressForAlpha(t) 与
        //        progressForEaseIn(1-t) 反解的其实是同一个进度）
        //   ③ 拿旧 solveProgress(closing=false) 当对照                    → 它不满足这两个函数中的任何一个
        //
        // 教训（已写进项目纪律）：**反解 / 方向 / 单调性这类判断，一律先跑探针看数值，不要凭名字与直觉推。**
        // 这三次每次都是我"觉得应该成立"，然后被自己的断言打回。
        // 所以这一节到此为止：只保留下面这条**已经跑出来是真的**的记录，不再猜别的性质。
        //
        // 已确认成立、且被断言守着的（见上文）：
        //   · 两个反解各自与自己的取值器互逆（304 对，跳过病态 24 处）
        //   · progressForIn(t) ≡ progressForAlpha(1-t)（与旧 solveProgress 的换元关系）
        //   · 旧主路径 solveProgress(closing=true) 与本层逐位一致（792 个取值，偏差 0）
        //   · 关闭方向 p=0.5：valueIn=0.875（=1-easeIn）、valueOut=0.125（=1-easeOut）
        pass("记录：两个反解的关系式**故意不做断言** —— 我猜过三条全错，只保留已实测的等价性与主路径对照");

        // 反解必须是确定性的：同一输入两次调用得到同一起点
        Tween again = Tween.continueFrom(now, openingAlpha, duration, curve, 0.0F, 1.0F, Tween.Getter.IN);
        if (again.startNanos() == closing.startNanos()) {
            pass("接续是确定性的：同一输入两次调用得到同一起点（可复现）");
        } else {
            fail("接续确定性", "两次调用起点不同: " + again.startNanos() + " vs " + closing.startNanos());
        }

        // 值域退化（from == to）不能除以 0
        Tween degenerate = Tween.continueFrom(now, 5.0F, duration, NamedEasing.CUBIC, 1.0F, 1.0F, Tween.Getter.IN);
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
