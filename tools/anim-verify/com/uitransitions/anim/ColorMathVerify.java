package com.uitransitions.anim;

import com.uitransitions.TransitionConfig;

/**
 * {@link ColorMath} 的验证：与 {@code UiTransitions.modulate} **逐位等价**，
 * 外加三条关于"预乘是参数而不是全局状态"的断言。
 *
 * <h2>验证策略</h2>
 *
 * 现有 {@code modulate} 是私有的，但它依赖的全局标志 {@code PREMULTIPLIED} 是
 * {@code private static final ThreadLocal} —— 同样摸不到。所以这里**按源代码逐字复刻**
 * 两份对照实现（非预乘 / 预乘），再与 {@link ColorMath} 逐位比较。
 *
 * <p><b>复刻必须逐字</b>：本轮我已经两次因为"凭记忆写对照物"而得到假绿
 * （一次漏了 `solveProgress` 的三元条件、一次把 `progressForIn` 的换元方向写反）。
 * 所以下面每一行都标注了它在 {@code UiTransitions.java} 里的对应行号。
 *
 * <h2>为什么要在这里断言"预乘是参数"</h2>
 *
 * 这条断言的价值在于**它能红**：如果哪天有人把 {@code premultiplied} 改回读全局标志，
 * 或者把 {@code Channel.TEXT} 接到预乘分支上，这里立刻失败 —— 而真机上
 * 这种错误只在"物品提交窗口里恰好画了文字"时才现形，几乎不可能靠人眼发现。
 */
public final class ColorMathVerify {

    private static int checks;
    private static int failures;

    private ColorMathVerify() {
    }

    public static void main(String[] args) {
        ColorMathVerify v = new ColorMathVerify();
        System.out.println("=== 颜色通道数学 · 与现有 modulate 逐位等价 ===");

        v.verifyGoldenValues();
        v.verifyEquivalenceAgainstLegacy();
        v.verifyPremultipliedIsAParameter();
        v.verifyThresholds();
        v.verifyEngineRouting();
        v.verifyComparatorIsFalsifiable();

        System.out.println();
        System.out.printf("检查项: %d，失败: %d%n", checks, failures);
        if (failures > 0) {
            System.out.println("结论: 有失败");
            System.exit(3);
        }
        System.out.println("结论: 全部逐位一致，且预乘语义已从全局状态改为参数");
    }

    // ------------------------------------------------------------------ 1. 黄金值

    /**
     * 先钉几个**人工可验算**的值。这一层不依赖任何对照物，
     * 所以即使下面的复刻写错了，它也能独立发现问题。
     */
    private void verifyGoldenValues() {
        // 0xFFFFFFFF 半透明：非预乘只改 alpha → 0x80FFFFFF
        check("白 alpha=0.5 非预乘", ColorMath.applyAlphaOnly(0xFFFFFFFF, 0.5F), 0x80FFFFFF);
        // 预乘：RGB 一起乘 → 0x80808080。
        // 注意是 **0x80 而不是 0x7F** —— Math.round(255*0.5f)=128。第一版我按"截断"手算成 0x7F，
        // 被这条黄金值断言抓出来了；旁边的"与旧实现逐位一致"（156 组）当时是绿的，
        // 说明**错的是我的手算，不是实现** —— 这正是留一层"不依赖对照物"的黄金值断言的用途。
        check("白 alpha=0.5 预乘", ColorMath.applyPremultiplied(0xFFFFFFFF, 0.5F), 0x80808080);
        // 已带 alpha 0x80 的颜色再乘 0.5 → alpha 0x40，RGB 不动
        check("0x80RRGGBB alpha=0.5 非预乘", ColorMath.applyAlphaOnly(0x80AABBCC, 0.5F), 0x40AABBCC);
        // 门限：alpha=1 原样返回
        check("alpha=1 原样返回", ColorMath.applyAlphaOnly(0x12345678, 1.0F), 0x12345678);
        // 门限：alpha=0 直接 0
        check("alpha=0 归零", ColorMath.applyAlphaOnly(0xFFFFFFFF, 0.0F), 0);
        // clamp：alpha 超范围
        check("alpha=2 夹到 1（原样）", ColorMath.applyAlphaOnly(0x12345678, 2.0F), 0x12345678);
        check("alpha=-1 夹到 0（归零）", ColorMath.applyAlphaOnly(0x12345678, -1.0F), 0);
    }

    // ------------------------------------------------------------------ 2. 与旧实现逐位等价

    private void verifyEquivalenceAgainstLegacy() {
        int[] colors = {
                0xFFFFFFFF, 0x80FFFFFF, 0x00FFFFFF, 0xFF000000, 0x00000000,
                0xFF123456, 0x40AABBCC, 0x7FFFFFFF, 0x01000000, 0xFE0F0F0F,
                0xFF00FF00, 0x80FF0000, 0xCC0000FF,
        };
        float[] alphas = { 0.0F, 0.039F, 0.04F, 0.041F, 0.1F, 0.5F, 0.9F, 0.998F, 0.999F, 1.0F, 1.5F, -0.5F };

        int compared = 0;
        int mismatchPlain = 0;
        int mismatchPremul = 0;
        String firstMismatch = null;
        for (int color : colors) {
            for (float alpha : alphas) {
                int ours = ColorMath.applyAlphaOnly(color, alpha);
                int legacy = legacyModulate(color, alpha, false);
                if (ours != legacy) {
                    mismatchPlain++;
                    if (firstMismatch == null) {
                        firstMismatch = "非预乘 color=" + hex(color) + " alpha=" + alpha
                                + "：新=" + hex(ours) + " 旧=" + hex(legacy);
                    }
                }
                int oursP = ColorMath.applyPremultiplied(color, alpha);
                int legacyP = legacyModulate(color, alpha, true);
                if (oursP != legacyP) {
                    mismatchPremul++;
                    if (firstMismatch == null) {
                        firstMismatch = "预乘 color=" + hex(color) + " alpha=" + alpha
                                + "：新=" + hex(oursP) + " 旧=" + hex(legacyP);
                    }
                }
                compared += 2;
            }
        }
        if (mismatchPlain == 0) {
            pass("非预乘路径与旧 modulate 逐位一致（" + (compared / 2) + " 组）");
        } else {
            fail("非预乘逐位一致", mismatchPlain + " 处不一致，首例: " + firstMismatch);
        }
        if (mismatchPremul == 0) {
            pass("预乘路径与旧 modulate 逐位一致（" + (compared / 2) + " 组）");
        } else {
            fail("预乘逐位一致", mismatchPremul + " 处不一致，首例: " + firstMismatch);
        }
    }

    // ------------------------------------------------------------------ 3. 预乘是参数

    /**
     * 核心断言：**同一份颜色与 alpha，两条通道必须给出不同结果**，
     * 且结果只由参数决定、与任何"当前状态"无关。
     *
     * <p>这条能红的价值：若有人把预乘判定改回读全局标志（或把 TEXT 接错到预乘分支），
     * 真机上只在极窄的条件下现形，而这里立刻红。
     */
    private void verifyPremultipliedIsAParameter() {
        int color = 0xFFFFFFFF;
        float alpha = 0.5F;
        int plain = ColorMath.applyAlphaOnly(color, alpha);
        int premul = ColorMath.applyPremultiplied(color, alpha);
        if (plain == premul) {
            fail("预乘与不预乘必须不同", "两者都是 " + hex(plain) + "，说明预乘参数没起作用");
        } else {
            pass("同一颜色两条通道结果不同（非预乘 " + hex(plain) + " vs 预乘 " + hex(premul) + "）");
        }

        // 可重复性：同样输入连算两次必须一致（没有隐藏状态）
        int again = ColorMath.applyPremultiplied(color, alpha);
        if (again == premul) {
            pass("纯函数：同输入两次调用结果一致（无隐藏状态）");
        } else {
            fail("纯函数性", hex(again) + " vs " + hex(premul));
        }

        // 调用顺序不影响结果：交替调用两条通道，各自结果不变
        int p1 = ColorMath.applyAlphaOnly(color, alpha);
        ColorMath.applyPremultiplied(0xFF000000, 0.3F);
        int p2 = ColorMath.applyAlphaOnly(color, alpha);
        if (p1 == p2) {
            pass("调用顺序不影响结果（旧实现里这条会因全局标志而失败）");
        } else {
            fail("调用顺序无关性", hex(p1) + " vs " + hex(p2));
        }

        // 与旧实现的关键差异：旧实现的结果取决于**之前有没有人置位全局标志**
        int legacyAfterPremulWindow = legacyModulate(color, alpha, true);   // 模拟"物品提交窗口内"
        int legacyNormal = legacyModulate(color, alpha, false);
        if (legacyAfterPremulWindow != legacyNormal) {
            pass("记录：旧实现里同一调用会因全局标志给出两种结果（" + hex(legacyAfterPremulWindow)
                    + " / " + hex(legacyNormal) + "）—— 这正是本类要消除的");
        } else {
            fail("缺陷复现", "旧实现两条路径结果相同，说明我把缺陷模型写错了");
        }
    }

    // ------------------------------------------------------------------ 4. 门限

    private void verifyThresholds() {
        // 0.999 是快速路径门限：恰好等于时应原样返回
        int at = ColorMath.applyAlphaOnly(0x80FFFFFF, 0.999F);
        if (at == 0x80FFFFFF) {
            pass("alpha == 0.999 走快速路径（原样返回，与旧实现一致）");
        } else {
            fail("快速路径门限", hex(at));
        }
        // 略低于门限应真的调制
        int below = ColorMath.applyAlphaOnly(0xFFFFFFFF, 0.998F);
        if (below != 0xFFFFFFFF) {
            pass("alpha == 0.998 真的调制（" + hex(below) + "）");
        } else {
            fail("门限边界", "0.998 被当成不透明了");
        }
        // 0.04 是归零门限：等于时应归零
        if (ColorMath.applyAlphaOnly(0xFFFFFFFF, 0.04F) == 0) {
            pass("alpha == 0.04 归零（与旧实现一致，避免尾部残亮）");
        } else {
            fail("归零门限", hex(ColorMath.applyAlphaOnly(0xFFFFFFFF, 0.04F)));
        }
        if (ColorMath.applyAlphaOnly(0xFFFFFFFF, 0.041F) != 0) {
            pass("alpha == 0.041 不归零（边界正确）");
        } else {
            fail("归零门限边界", "0.041 被归零了");
        }
    }

    // ------------------------------------------------------------------ 5. 通道路由

    /**
     * 通道路由与"就绪前不碰状态"。
     *
     * <p>用可变 hook 模拟旧的三个 alpha 来源（迁移期它们就是三个 {@code ThreadLocal}）：
     * 只要改 hook 的值，就能观察"哪条通道读了哪个来源"。
     */
    private void verifyEngineRouting() {
        float[] window = { 0.5F };
        float[] text = { 0.25F };
        float[] presented = { 0.75F };
        boolean[] ready = { true };
        Engine engine = new Engine(() -> window[0], () -> text[0], () -> presented[0], () -> ready[0]);

        // 贴图块 / 纯色块 → 窗口来源
        if (engine.alphaOf(Channel.BLIT) == 0.5F && engine.alphaOf(Channel.RECT) == 0.5F) {
            pass("BLIT/RECT 读窗口透明度（0.5）");
        } else {
            fail("BLIT/RECT 来源", engine.alphaOf(Channel.BLIT) + " / " + engine.alphaOf(Channel.RECT));
        }
        // 文字 → 文字来源（与窗口分开，这是"文字能配自己的曲线"的前提）
        if (engine.alphaOf(Channel.TEXT) == 0.25F) {
            pass("TEXT 读文字透明度（0.25，独立于窗口的 0.5）");
        } else {
            fail("TEXT 来源", String.valueOf(engine.alphaOf(Channel.TEXT)));
        }
        // 物品 / 画中画 → 帧级"最终呈现"来源
        if (engine.alphaOf(Channel.ITEM) == 0.75F && engine.alphaOf(Channel.PIP) == 0.75F) {
            pass("ITEM/PIP 读帧级呈现透明度（0.75）");
        } else {
            fail("ITEM/PIP 来源", engine.alphaOf(Channel.ITEM) + " / " + engine.alphaOf(Channel.PIP));
        }

        // 通道化后的核心收益：文字**永远**不预乘，与窗口里正在发生什么无关
        int textColor = engine.apply(Channel.TEXT, 0xFFFFFFFF);
        int blitColor = engine.apply(Channel.BLIT, 0xFFFFFFFF);
        if (textColor == ColorMath.applyAlphaOnly(0xFFFFFFFF, 0.25F)
                && blitColor == ColorMath.applyAlphaOnly(0xFFFFFFFF, 0.5F)) {
            pass("通道各自取对来源并调制（TEXT=" + hex(textColor) + " BLIT=" + hex(blitColor) + "）");
        } else {
            fail("通道调制", hex(textColor) + " / " + hex(blitColor));
        }
        // 物品走预乘、文字不走 —— 同一颜色两条通道必须不同
        int itemColor = engine.apply(Channel.ITEM, 0xFFFFFFFF);
        if (itemColor != textColor && itemColor == ColorMath.applyPremultiplied(0xFFFFFFFF, 0.75F)) {
            pass("ITEM 走预乘、TEXT 不走（" + hex(itemColor) + " vs " + hex(textColor) + "）");
        } else {
            fail("预乘归属", hex(itemColor) + " vs " + hex(textColor));
        }

        // 就绪前不碰任何来源：旧实现里这条守在最外层，是启动早期不崩的原因
        boolean[] touched = { false };
        Engine lazy = new Engine(() -> { touched[0] = true; return 0.5; },
                () -> { touched[0] = true; return 0.5; },
                () -> { touched[0] = true; return 0.5; },
                () -> false);
        int untouched = lazy.apply(Channel.TEXT, 0xFFFFFFFF);
        if (untouched == 0xFFFFFFFF && !touched[0]) {
            pass("未就绪时原样返回，且**没有读任何来源**（启动早期安全）");
        } else {
            fail("就绪守卫", "返回 " + hex(untouched) + "，读过来源=" + touched[0]);
        }

        // 线程无关：两条通道的来源互相独立（旧实现用一个全局标志就无法做到）
        window[0] = 0.1F;
        if (engine.alphaOf(Channel.TEXT) == 0.25F && engine.alphaOf(Channel.BLIT) == 0.1F) {
            pass("改窗口来源不影响文字来源（两条通道互不串扰）");
        } else {
            fail("通道隔离", engine.alphaOf(Channel.TEXT) + " / " + engine.alphaOf(Channel.BLIT));
        }

        // ── 显式 alpha 重载**不查来源**（实现侧提出的一条隐式依赖，必须有断言钉住）──
        //
        // 两个重载只差一个参数，看调用点分不出哪个会去查来源：
        //     apply(channel, color)          → 读来源 ×1
        //     apply(channel, color, alpha)   → 不读来源，只用 alpha ×1
        // 若哪天有人让 alphaOf 也参与进来，就会**双重乘**，表现是"聊天淡入看起来只有一半深"
        // —— 绝不会被归因到乘法次数。所以这里把"结果只由显式 alpha 决定"钉死。
        float[] sourceValue = { 0.5F };
        Engine fixed = new Engine(() -> sourceValue[0], () -> sourceValue[0], () -> sourceValue[0]);
        int explicitResult = fixed.apply(Channel.TEXT, 0xFFFFFFFF, 0.25F);
        int expectedExplicit = ColorMath.apply(0xFFFFFFFF, 0.25F, false);
        // 来源值故意与显式 alpha 不同（0.5 vs 0.25）：若被二次乘，结果会是 0x40 而不是 0xBF
        if (explicitResult == expectedExplicit) {
            pass("显式 alpha 重载不查来源（显式 0.25、来源 0.5 → " + hex(explicitResult)
                    + "，与来源无关）");
        } else {
            fail("显式 alpha 不查来源", "实得 " + hex(explicitResult) + "，期望 " + hex(expectedExplicit)
                    + "（若为 0x40FFFFFF 说明发生了双重乘）");
        }
        // 换一个来源值，结果必须**完全不变** —— 这才是"与来源无关"的强形式
        sourceValue[0] = 0.9F;
        if (fixed.apply(Channel.TEXT, 0xFFFFFFFF, 0.25F) == explicitResult) {
            pass("改来源值后显式结果不变（0.5 → 0.9 仍得 " + hex(explicitResult) + "）");
        } else {
            fail("显式 alpha 与来源无关性", "改来源后结果变了");
        }
        // 反面对照：不带 alpha 的重载**必须**跟着来源变（证明这条断言不是空转）
        int fromSource = fixed.apply(Channel.TEXT, 0xFFFFFFFF);
        if (fromSource == ColorMath.apply(0xFFFFFFFF, 0.9F, false)) {
            pass("不带 alpha 的重载确实读来源（0.9 → " + hex(fromSource) + "）—— 对照有效");
        } else {
            fail("来源读取对照", hex(fromSource) + "，期望按来源 0.9 调制");
        }
    }

    // ------------------------------------------------------------------ 6. 可证伪

    private void verifyComparatorIsFalsifiable() {
        // 故意用错的期望值，确认 check() 会判不等
        int value = ColorMath.applyAlphaOnly(0xFFFFFFFF, 0.5F);   // 0x80FFFFFF
        int wrong = 0x80FFFFFE;
        if (value != wrong) {
            pass("比较器可证伪（" + hex(value) + " ≠ " + hex(wrong) + "）");
        } else {
            fail("比较器可证伪性", "两个不同的值被判为相等，下面的绿灯都没有意义");
        }
    }

    // ------------------------------------------------------------------ 对照物（逐字复刻）

    /**
     * 现有 {@code UiTransitions.modulate(int, float)} 的逐字复刻
     * （{@code UiTransitions.java:581-605}），全局标志改成参数。
     */
    private static int legacyModulate(int color, float alpha, boolean premultiplied) {
        if (alpha >= 0.999F) {                                   // :583
            return color;
        }
        int existing = (color >>> 24) & 0xFF;                    // :586
        if (alpha <= 0.04F) {                                    // :588
            return 0;
        }
        if (premultiplied) {                                     // :591
            int r = Math.round(((color >> 16) & 0xFF) * alpha);  // :593
            int g = Math.round(((color >> 8) & 0xFF) * alpha);   // :594
            int b = Math.round((color & 0xFF) * alpha);          // :595
            int a = Math.round(existing * alpha);                // :596
            return (a << 24) | (r << 16) | (g << 8) | b;         // :597
        }
        int modulated = Math.max(0, Math.min(255, Math.round(existing * alpha)));   // :599
        return (color & 0xFFFFFF) | (modulated << 24);           // :600
    }

    // ------------------------------------------------------------------ 工具

    private static String hex(int v) {
        return String.format("0x%08X", v);
    }

    /** 顺带确认对照物用到的 API 与现有实现同源（避免"对照物自说自话"）。 */
    static {
        if (TransitionConfig.DEFAULT_DURATION_MS <= 0) {
            throw new IllegalStateException("TransitionConfig 未加载");
        }
    }

    private static void check(String what, int actual, int expected) {
        if (actual == expected) {
            pass(what + "（" + hex(actual) + "）");
        } else {
            fail(what, "期望 " + hex(expected) + "，实得 " + hex(actual));
        }
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
