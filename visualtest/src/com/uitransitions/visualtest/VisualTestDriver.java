package com.uitransitions.visualtest;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/**
 * 开发期「截图时间线」驱动。
 *
 * 流程：等主界面 → 打开一个自带的自定义界面（方块面板，位移/透明度一眼可见）
 *      → 按时间点抓帧 → 关闭（走关闭动画）→ 再抓帧 → 再开一次抓稳定态 → 退出游戏。
 *
 * 为什么用自定义界面而不是箱子界面：箱子的 tick() 会直接读 minecraft.player，
 * 而不进世界就没有玩家，会 NPE。自定义界面不依赖世界/玩家，
 * 但渲染路径完全一样（都走 Screen.extractRenderState），因此足以验证动画本身。
 * 需要配合配置 animateAllScreens=true（由启动脚本写入）。
 *
 * 启动参数：
 *   -Duitransitions.visualTest=true
 *   -Duitransitions.visualTest.dir=<输出目录>
 */
public final class VisualTestDriver {

    private static final String TAG = "[VisualTest] ";
    /** 每段动画抓几帧、帧间隔（毫秒）。动画时长在测试配置里设为 3000ms */
    private static final int FRAME_COUNT = 8;
    private static final long INTERVAL_MS = 200L;

    /** 本次运行的输出目录，连拍也归档到这里，免得散在两个地方 */
    private static File OUT_DIR;

    /** 由两个加载器各自的薄入口调用（Fabric: ClientModInitializer / NeoForge: @Mod 构造函数） */
    public static void start() {
        if (!Boolean.getBoolean("uitransitions.visualTest")) {
            return;
        }
        Thread thread = new Thread(VisualTestDriver::run, "ui-transitions-visualtest");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        Minecraft minecraft = Minecraft.getInstance();
        File outDir = new File(System.getProperty("uitransitions.visualTest.dir", "visual-out"));
        outDir.mkdirs();
        OUT_DIR = outDir;
        log("输出目录: " + outDir.getAbsolutePath());
        String phases = System.getProperty("uitransitions.visualTest.phases", "all");
        log("运行阶段: " + phases);

        log("等待主界面出现...");
        if (!waitFor(() -> minecraft.gui.screen() != null, 180_000L)) {
            log("失败：180 秒内没出现任何界面");
            quit(minecraft);
            return;
        }
        sleep(20000);
        try {
            minecraft.options.pauseOnLostFocus = false;
            log("已关闭失焦暂停");
        } catch (Throwable t) {
            log("关闭失焦暂停失败: " + t);
        }
        // 预热：第一次截图要十几秒（分配缓冲 + 首次读帧），先丢掉一张，
        // 否则"打开动画"会在预热期间就播完了，抓不到过程。
        log("预热截图管线...");
        capture(minecraft, outDir, "warmup", System.nanoTime(), 0);
        log("预热完成");

        // 先备份用户配置，再套用测试基线：跑完会恢复，不留副作用
        backupUserConfig(minecraft);
        applyTestBaseline(minecraft);

        // ---------- 打开动画 ----------
        if (phaseEnabled("panels")) {
            log("打开测试界面（面板应自下而上滑入并淡入）");
            setSubtitleProbe(minecraft);
            openPanel(minecraft, true);
            captureTimeline(minecraft, outDir, "open");
            sleep(1200);

            // ---------- 关闭动画 ----------
            log("关闭测试界面（应向下滑出并淡出）");
            closeScreen(minecraft);
            captureTimeline(minecraft, outDir, "close");
            sleep(1200);

            // ---------- 稳定态 ----------
            log("再次打开，抓稳定态（应无位移、无透明度）");
            setSubtitleProbe(minecraft);
            openPanel(minecraft, true);
            sleep(2500);
            capture(minecraft, outDir, "steady", System.nanoTime(), 0);

            // ---------- A/B：关掉"容器底板跟随动画"，底板应保持不动 ----------
            log("A/B 测试：animatePanel=false（预期：底板静止，只有内容动）");
            setConfigBoolean("setAnimatePanel", false);
            sleep(300);
            setSubtitleProbe(minecraft);
            openPanel(minecraft, true);
            captureTimeline(minecraft, outDir, "nopanel");
            sleep(1000);
            closeScreen(minecraft);
            sleep(2500);
        }

        // ---------- 配置界面（Cloth Config） ----------
        if (phaseEnabled("config")) {
            log("打开图形化配置界面");
            openConfigScreen(minecraft);
            // **分三段走，全程按真实用户路径**：
            //   ① `openConfigScreen` 打开的是**入口页**（不是 Cloth 页）——
            //      模组故意加了一层入口页，因为 Cloth 里那个自绘条目真实鼠标点不到；
            //   ② 在入口页上点「界面动画设置」，这才是真的 Cloth 配置页；
            //   ③ 每一步都**等到界面真的变了**，别用固定 sleep。
            //
            // 为什么不能只看"不是入口页"：点开时会先"拦下切屏播关闭动画"，
            // 那期间 `screen()` 还是**上一个**界面（标题界面），既不是入口页也不是配置页 ——
            // 条件会立刻满足，抓到标题界面。实测就是这么假绿的。
            boolean sawHub = waitForScreenNamed(minecraft, "HubScreen", 20_000L);
            if (sawHub) {
                log("已到入口页，点「界面动画设置」进 Cloth 配置页");
                clickButtonByKey(minecraft, "界面动画设置", "ui_transitions.hub.config");
            } else {
                log("❌ 20 秒内没到入口页");
            }
            Screen now = null;
            long deadline = System.currentTimeMillis() + 20_000L;
            while (System.currentTimeMillis() < deadline) {
                now = minecraft.gui.screen();
                if (now != null && !now.getClass().getSimpleName().contains("HubScreen")
                        && !now.getClass().getSimpleName().contains("TitleScreen")) {
                    break;
                }
                sleep(200);
            }
            String name = now == null ? "null" : now.getClass().getSimpleName();
            boolean isCloth = now != null && now.getClass().getName().contains("clothconfig");
            log("配置界面当前屏幕 = " + name
                    + (isCloth ? "  ✅（Cloth 配置页）" : "  ❌（不是 Cloth 配置页，抓到的是别的界面）"));
            sleep(800);
            capture(minecraft, outDir, "configgui", System.nanoTime(), 0);
            // 切到「按界面分类」页再扫：**Cloth 只为当前选中的标签页创建条目**，
            // 在默认打开的「动画」页那棵树里搜分类分组，永远搜不到 ——
            // 我第一版就是这么误报成"6 个分组一个都没建出来"的。
            // 切到「按界面分类」页并抓图。
            //
            // **判定用什么**：Cloth 只为当前选中的标签页创建条目，而且它的分组标题是
            // **自己画上去的**、不在控件树的 getMessage() 里 —— 所以"按文本扫分组"这条路走不通
            // （我先后按翻译键、按显示名扫，两次都报 0/6，而截图里分组明明都在）。
            // 这里只可靠地判定"标签页切过去了"（抓图前缀就是证据），
            // 页面内容由截图人工确认 —— 并把控件树文本记一行，方便以后排查。
            if (isCloth) {
                clickButtonByKey(minecraft, "按界面分类", "ui_transitions.config.category.by_screen");
                sleep(1500);
                capture(minecraft, outDir, "configgui_categories", System.nanoTime(), 0);
                log("已切到「按界面分类」并抓图 configgui_categories_*.png（分组是 Cloth 自绘的，"
                        + "无法从控件树里核，请看截图）");
            }
        }

        // ---------- 配置界面里的"打开曲线编辑器"入口能不能点开 ----------
        // ---------- 入口页：四个按钮真的能把各自的界面打开吗 ----------
        //
        // 这个阶段原来是 `configclick`：当年曲线编辑器的入口是 Cloth 里的一个**自绘条目**，
        // 渲染正常、直接派发点击也能开，但真实鼠标点击传不到它那儿 —— 那个阶段就是为抓它写的。
        // 后来入口改成了入口页（Hub）上的**原版按钮**，那个条目连类都不存在了，
        // 于是这个阶段变成"找一个已经删掉的东西"，永远 ❌ ——**看起来像功能坏了，
        // 实际是测试在检查一个不存在的实现**（这种假红比假绿更浪费时间）。
        //
        // 现在改成检查**真正在用的那条入口链**：Hub 的每个按钮点下去，
        // 开出来的界面类名对不对。这正是用户抱怨过"点了没反应"的那一类问题。
        if (phaseEnabled("hub")) {
            log("=== 入口页按钮检查 ===");
            openHub(minecraft);
            sleep(2000);
            capture(minecraft, outDir, "hub", System.nanoTime(), 0);
            // 先把入口页上的控件列一遍：按钮文字对不上、位置算错、控件没建出来，
            // 这三种原因的修法完全不同，一眼看清能省一轮往返。
            minecraft.execute(() -> dumpWidgets(minecraft.gui.screen()));
            sleep(400);
            String[] labels = { "界面动画设置", "曲线编辑器 — 渐入", "排除的界面" };
            String[] keys = { "ui_transitions.hub.config", "ui_transitions.hub.curve_open",
                    "ui_transitions.hub.exclude" };
            for (int i = 0; i < labels.length; i++) {
                String label = labels[i];
                String key = keys[i];
                // 每一轮都**先回到入口页**：上一轮点开的界面关闭时，"拦下切屏 + 延迟补做"
                // 会把当前界面推到后台栈的下一层（通常是标题界面），继续在那一层找按钮
                // 只会得到"没找到" —— 看起来像按钮坏了，其实是测试跑错界面了。
                openHub(minecraft);
                sleep(1500);
                clickButtonByKey(minecraft, label, key);
                // 要等够：点开新界面时模组会拦下切屏播关闭动画，动画播完才真正换屏。
                // 1.8 秒在动画时长被测试基线调成 3000ms 时是**不够**的（踩过）。
                sleep(3500);
                Screen now = minecraft.gui.screen();
                String name = now == null ? "null" : now.getClass().getSimpleName();
                boolean opened = !"null".equals(name) && !name.contains("HubScreen");
                log("点「" + label + "」-> " + name + (opened ? "  ✅" : "  ❌（没打开，还停在入口页）"));
                if (opened) {
                    capture(minecraft, outDir, "hub_" + phaseSlug(label), System.nanoTime(), 0);
                    if ("exclude".equals(phaseSlug(label))) {
                        dumpWidgets(now);
                    }
                }
            }
            closeScreen(minecraft);
            sleep(1200);
        }

        // ---------- 曲线编辑器（不需要世界） ----------
        if (phaseEnabled("curve")) {
            log("打开曲线编辑器（渐入）");
            openCurveEditor(minecraft, "OPEN");
            sleep(1500);
            capture(minecraft, outDir, "curve_open", System.nanoTime(), 0);
            log("打开曲线编辑器（渐出）");
            openCurveEditor(minecraft, "CLOSE");
            sleep(1500);
            capture(minecraft, outDir, "curve_close", System.nanoTime(), 0);
            closeScreen(minecraft);
            sleep(1000);
        }

        // ---------- 曲线编辑器的**交互**（不需要世界） ----------
        //
        // 为什么必须单独有一个交互阶段：截图上"长得对"和"点得到"是两件事，
        // 这个项目已经因此栽过两次 —— 配置界面里的曲线编辑器条目渲染完全正常，
        // 但真实鼠标点击传不到它那儿；曲线编辑器本身的预览列宽度也曾经算错，
        // 静态看代码和看截图都发现不了，只有真的点一下才会暴露。
        if (phaseEnabled("curveui")) {
            log("=== 曲线编辑器交互检查 ===");
            openCurveEditor(minecraft, "OPEN");
            sleep(1800);
            probeCurveEditorLayout(minecraft);
            // **分步做，而且"点击在渲染线程、等待在驱动线程"**：
            // 整段塞进一个 execute 里会把渲染线程堵住，界面状态（控件重建）就永远不推进。
            interactWithCurveEditorSetup(minecraft);   // 渲染线程：点列表 / 切部位 / 切模式
            sleep(2500);                               // 驱动线程：放帧过去
            interactWithCurveEditorPoints(minecraft);  // 渲染线程：加点 / 移动 / 删点
            sleep(1500);
            capture(minecraft, outDir, "curveui_after", System.nanoTime(), 0);
            closeScreen(minecraft);
            sleep(1200);
        }

        // ---------- 进世界：实测真实容器界面（含玩家小模型） ----------
        if (phaseEnabled("world") || phaseEnabled("inventory") || phaseEnabled("enchant")
                || phaseEnabled("creative")) {
            log("创建并进入世界（用于实测背包界面与玩家小模型）");
            if (enterWorld(minecraft)) {
            // 慢机器上"进入世界"后还会有一段加载地形的时间，必须等遮罩消失且稳定下来再拍，
            // 否则抓到的全是"加载地形中…"的画面。
            log("等待世界真正加载完成（加载遮罩消失并稳定 3 秒）");
            if (waitForWorldReady(minecraft, 180_000L)) {
                log("世界已就绪，开始测试");
            } else {
                log("警告：180 秒内世界仍未就绪，继续尝试");
            }
            capture(minecraft, outDir, "world", System.nanoTime(), 0);
            setSubtitleProbe(minecraft);

            // ---------- 生存背包：玩家小模型（画中画）是否跟着界面一起动 ----------
            if (phaseEnabled("inventory")) {
                log("切换为生存模式（生存背包才有玩家小模型）");
                switchGameMode(minecraft, "SURVIVAL");
                sleep(2000);
                slowDownForCapture(minecraft);
                log("打开背包（真实容器界面：底板 + 玩家小模型一起淡入）");
                openInventory(minecraft);
                captureBurst(minecraft, "inv_open", 18, 150);
                capture(minecraft, outDir, "inv_steady", System.nanoTime(), 0);
                log("关闭背包（一起淡出）");
                closeScreen(minecraft);
                captureBurst(minecraft, "inv_close", 18, 150);
                sleep(2500);
            }

            // ---------- 附魔台：附魔书（同样是画中画） ----------
            if (phaseEnabled("enchant")) {
                log("打开附魔台界面（附魔书走画中画，检查是否跟着界面动、有没有被裁）");
                slowDownForCapture(minecraft);
                openEnchantScreen(minecraft);
                sleep(200);
                captureBurst(minecraft, "enchant_open", 18, 150);
                capture(minecraft, outDir, "enchant_steady", System.nanoTime(), 0);
                closeScreen(minecraft);
                captureBurst(minecraft, "enchant_close", 18, 150);
                sleep(2000);
            }

            // ---------- 创造模式物品栏：标签切换 + 滚动逐格渐变 ----------
            if (phaseEnabled("creative")) {
                log("切到创造模式并打开物品栏");
                switchGameMode(minecraft, "CREATIVE");
                sleep(1500);
                openCreativeScreen(minecraft);
                sleep(1500);
                capture(minecraft, outDir, "creative_steady", System.nanoTime(), 0);

                log("切换分类标签（物品区应原地淡入，快捷栏那一排不动）");
                selectCreativeTab(minecraft, 1);
                captureBurst(minecraft, "creative_tab", 10, 120);
                sleep(1200);

                log("滚动物品列表（应逐格渐变，越靠进入边越淡）");
                setCreativeScroll(minecraft, 0.2F);
                sleep(120);
                setCreativeScroll(minecraft, 0.6F);
                captureBurst(minecraft, "creative_scroll", 10, 120);
                sleep(1500);
                closeScreen(minecraft);
                sleep(1500);
            }
            }   // if (enterWorld)
        }       // if (phaseEnabled("world") || ...)

        // ---------- Sodium 视频设置里的本模组页面 ----------
        if (phaseEnabled("sodium")) {
            log("打开 Sodium 视频设置（应能看到 UI Transitions 页面）");
            openSodiumScreen(minecraft);
            sleep(3500);
            capture(minecraft, outDir, "sodium", System.nanoTime(), 0);
        }

        log("测试完成");
        restoreUserConfig(minecraft);
        quit(minecraft);
    }

    private static void openSodiumScreen(Minecraft minecraft) {
        minecraft.execute(() -> {
            try {
                Class<?> cls = Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen");
                Screen parent = minecraft.gui.screen();
                if (parent == null) {
                    parent = new TestPanelScreen();
                }
                Object screen = cls.getMethod("createScreen", Screen.class).invoke(null, parent);
                minecraft.gui.setScreen((Screen) screen);
                log("已打开 Sodium 视频设置: " + screen.getClass().getName());
            } catch (Throwable t) {
                log("打开 Sodium 界面失败: " + t);
                Throwable cause = t.getCause();
                while (cause != null) {
                    log("  根因: " + cause);
                    cause = cause.getCause();
                }
            }
        });
    }

    /**
     * 探针：程序化设置一条字幕。字幕是在 Screen.extractBackground 结尾绘制的，
     * 正好落在这个动画窗口内 —— 抵消逻辑一旦失效，它会跟着界面上下移动，一眼可见。
     */
    private static void setSubtitleProbe(Minecraft minecraft) {
        minecraft.execute(() -> {
            try {
                minecraft.gui.hud.setSubtitle(
                        Component.literal("[SUBTITLE PROBE] 字幕不应跟随界面移动"));
                log("已设置字幕探针");
            } catch (Throwable t) {
                log("设置字幕探针失败: " + t);
            }
        });
    }

    /** 通过反射改配置（测试驱动不硬依赖被测模组） */
    private static void setConfigBoolean(String setter, boolean value) {
        try {
            Class<?> config = Class.forName("com.uitransitions.TransitionConfig");
            config.getMethod(setter, boolean.class).invoke(null, value);
            log("已设置 " + setter + "=" + value);
        } catch (Throwable t) {
            log("设置 " + setter + " 失败: " + t);
        }
    }

    private static void setConfigInt(String setter, int value) {
        try {
            Class<?> config = Class.forName("com.uitransitions.TransitionConfig");
            config.getMethod(setter, int.class).invoke(null, value);
            log("已设置 " + setter + "=" + value);
        } catch (Throwable t) {
            log("设置 " + setter + " 失败: " + t);
        }
    }

    private static void setConfigFloat(String setter, float value) {
        try {
            Class<?> config = Class.forName("com.uitransitions.TransitionConfig");
            config.getMethod(setter, float.class).invoke(null, value);
            log("已设置 " + setter + "=" + value);
        } catch (Throwable t) {
            log("设置 " + setter + " 失败: " + t);
        }
    }

    /**
     * 把动画放慢，方便连拍抓到中间帧。
     *
     * 默认 500ms 的动画，等第一次 Screenshot.grab 真正落到帧上时早就播完了 ——
     * 连拍出来是一堆和稳定帧一模一样的图，什么也验证不了（踩过）。
     * 顺带把位移调大，这样"有没有跟着动"在画面上更明显。
     */
    private static void slowDownForCapture(Minecraft minecraft) {
        log("放慢动画以便抓中间帧：渐入/渐出 3000ms，位移 200px");
        setConfigInt("setDurationMsBoth", 3000);
        setConfigFloat("setOffset", 200.0F);
        sleep(300);
    }

    // ================================================================== 配置卫生

    private static File CONFIG_BACKUP;

    /**
     * 测试会改用户的真实配置（A/B 阶段要关掉底板动画之类）。跑之前先备份，
     * 跑完恢复 —— 否则留下的 animatePanel=false 会让**下一次**测试看起来
     * 像"动画根本没生效"，非常容易误判成代码问题（真踩过，白查了一轮）。
     */
    private static void backupUserConfig(Minecraft minecraft) {
        try {
            File config = new File(minecraft.gameDirectory, "config/ui-transitions.properties");
            if (!config.isFile()) {
                log("没有用户配置可备份（首次运行）");
                return;
            }
            CONFIG_BACKUP = File.createTempFile("uit-config-backup", ".properties");
            java.nio.file.Files.copy(config.toPath(), CONFIG_BACKUP.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log("已备份用户配置 -> " + CONFIG_BACKUP.getAbsolutePath());
        } catch (Throwable t) {
            log("备份用户配置失败: " + t);
            CONFIG_BACKUP = null;
        }
    }

    private static void restoreUserConfig(Minecraft minecraft) {
        try {
            if (CONFIG_BACKUP == null || !CONFIG_BACKUP.isFile()) {
                return;
            }
            File config = new File(minecraft.gameDirectory, "config/ui-transitions.properties");
            java.nio.file.Files.copy(CONFIG_BACKUP.toPath(), config.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log("已恢复用户配置");
        } catch (Throwable t) {
            log("恢复用户配置失败: " + t);
        }
    }

    /** 测试基线：把配置置成已知状态，不受上一次运行残留的开关影响 */
    private static void applyTestBaseline(Minecraft minecraft) {
        log("应用测试基线配置（开动画、底板参与、不用对比模式）");
        setConfigBoolean("setEnabled", true);
        setConfigBoolean("setFade", true);
        setConfigBoolean("setAnimatePanel", true);
        setConfigBoolean("setFadeItems", true);
        setConfigBoolean("setFadeText", true);
        setConfigBoolean("setAnimateAllScreens", false);
        setConfigBoolean("setOverlayModsFadeOnly", false);
        setConfigInt("setOpenDurationMs", 3000);
        setConfigInt("setCloseDurationMs", 3000);
        setConfigFloat("setOffset", 200.0F);
        sleep(300);
    }

    /** 用原版入口新建一个默认世界并进入，成功返回 true（最多等 150 秒） */
    private static boolean enterWorld(Minecraft minecraft) {
        minecraft.execute(() -> {
            try {
                Class<?> cls = Class.forName(
                        "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen");
                // 优先用已有存档（玩家手动建的）直接进世界；
                // createWorldOpenFlows().openWorld(存档目录名, 失败回调)
                java.io.File saves = new java.io.File(minecraft.gameDirectory, "saves");
                String existing = null;
                java.io.File[] dirs = saves.listFiles(java.io.File::isDirectory);
                if (dirs != null) {
                    for (java.io.File dir : dirs) {
                        if (new java.io.File(dir, "level.dat").isFile()) {
                            existing = dir.getName();
                            break;
                        }
                    }
                }
                if (existing != null) {
                    Object flows = minecraft.createWorldOpenFlows();
                    flows.getClass()
                            .getMethod("openWorld", String.class, Runnable.class)
                            .invoke(flows, existing, (Runnable) () -> log("打开存档失败回调"));
                    log("已请求打开已有存档: " + existing);
                } else {
                    log("saves 里没有可用存档，改用 testWorld 建图");
                    cls.getMethod("testWorld", Minecraft.class, Runnable.class)
                            .invoke(null, minecraft, (Runnable) () -> log("测试世界回调已触发"));
                    log("已请求创建测试世界");
                }
            } catch (Throwable t) {
                log("创建世界失败: " + t);
                Throwable cause = t.getCause();
                while (cause != null) {
                    log("  根因: " + cause);
                    cause = cause.getCause();
                }
            }
        });
        long deadline = System.currentTimeMillis() + 150_000L;
        while (System.currentTimeMillis() < deadline) {
            if (minecraft.level != null && minecraft.player != null) {
                log("已进入世界: " + minecraft.level.dimension());
                return true;
            }
            sleep(250);
        }
        log("警告：150 秒内没有进入世界");
        return false;
    }

    /** 直接构造并打开背包界面（sendOpenInventory 走数据包往返，实测没能打开界面） */
    private static void openInventory(Minecraft minecraft) {
        minecraft.execute(() -> {
            try {
                if (minecraft.player == null) {
                    log("玩家为空，无法打开背包");
                    return;
                }
                Class<?> cls = Class.forName("net.minecraft.client.gui.screens.inventory.InventoryScreen");
                Object screen = cls.getConstructor(net.minecraft.world.entity.player.Player.class)
                        .newInstance(minecraft.player);
                minecraft.gui.setScreen((Screen) screen);
                log("已打开背包界面: " + screen.getClass().getSimpleName());
            } catch (Throwable t) {
                log("打开背包失败: " + t);
                Throwable cause = t.getCause();
                while (cause != null) {
                    log("  根因: " + cause);
                    cause = cause.getCause();
                }
            }
        });
    }

    /** 等世界真正就绪：区块全部编译完成、玩家存在，并且连续 3 秒保持这样 */
    private static boolean waitForWorldReady(Minecraft minecraft, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long clearSince = -1L;
        while (System.currentTimeMillis() < deadline) {
            boolean ready = minecraft.level != null
                    && minecraft.player != null
                    && sectionsReady(minecraft);
            if (ready) {
                if (clearSince < 0L) {
                    clearSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - clearSince > 3000L) {
                    return true;
                }
            } else {
                clearSince = -1L;
            }
            sleep(250);
        }
        return false;
    }

    /** "加载地形中…" 就是区块还没编译完时显示的，用 hasRenderedAllSections 判定 */
    private static boolean sectionsReady(Minecraft minecraft) {
        try {
            return minecraft.levelRenderer == null || minecraft.levelRenderer.hasRenderedAllSections();
        } catch (Throwable t) {
            return true;   // 取不到就退回"只看 level/player"
        }
    }


    /**
     * 连拍：只调 grab（立刻抓当前帧），不等文件写完。
     * 普通 capture 每帧要等约 2 秒的文件落盘，2 秒的动画根本抓不到中间帧。
     * 图片直接落在 <游戏目录>/screenshots/，测试后从那里取即可。
     */
    /**
     * 连拍：每一张都等到自己的文件落地再抓下一张。
     *
     * 早先的写法是"连续调 grab 不等文件，最后按修改时间排序归档"——
     * 截图是异步写盘的，落地顺序和抓取顺序并不一致，于是归档出来的帧序是乱的：
     * 看起来像"动画没动"，其实只是帧被排错了（真踩过，白查了半天）。
     * 代价是每帧多等一次落盘，但换来的帧序是**确定的**，并且文件名里带真实耗时。
     */
    private static void captureBurst(Minecraft minecraft, String prefix, int count, long gapMs) {
        java.io.File dir = new java.io.File(minecraft.gameDirectory, "screenshots");
        java.io.File outDir = OUT_DIR != null
                ? OUT_DIR
                : new java.io.File(minecraft.gameDirectory, "uitransitions-captures");
        outDir.mkdirs();
        long start = System.nanoTime();
        int saved = 0;

        for (int i = 0; i < count; i++) {
            Set<String> before = listFiles(dir);
            minecraft.execute(() -> {
                try {
                    Screenshot.grab(minecraft, false);
                } catch (Throwable t) {
                    log("连拍失败: " + t);
                }
            });

            File fresh = null;
            long deadline = System.currentTimeMillis() + 8_000L;
            while (System.currentTimeMillis() < deadline) {
                File candidate = newestNotIn(dir, before);
                if (candidate != null && candidate.length() > 0L) {
                    long size = candidate.length();
                    sleep(20);
                    if (candidate.length() == size) {
                        fresh = candidate;
                        break;
                    }
                }
                sleep(10);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            if (fresh == null) {
                log("连拍超时 " + prefix + " #" + i);
            } else {
                try {
                    java.nio.file.Files.copy(fresh.toPath(),
                            new java.io.File(outDir,
                                    String.format("%s_%02d_t%04dms.png", prefix, i, elapsedMs)).toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    saved++;
                } catch (Throwable t) {
                    log("复制连拍图失败: " + t);
                }
            }
            sleep(gapMs);
        }
        log("连拍 " + prefix + " 已归档 " + saved + "/" + count + " 张到 " + outDir.getAbsolutePath());
    }

    // ================================================================== 阶段开关

    /**
     * 要跑哪些阶段，由 -Duitransitions.visualTest.phases=xxx,yyy 指定；
     * 不填或填 all 就是全跑。加新功能时只跑相关阶段，能省掉几分钟的等待。
     */
    private static boolean phaseEnabled(String name) {
        String spec = System.getProperty("uitransitions.visualTest.phases", "");
        if (spec == null || spec.isBlank() || "all".equalsIgnoreCase(spec.trim())) {
            return true;
        }
        for (String part : spec.split(",")) {
            if (part.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    // ================================================================== 新增：曲线编辑器

    /** 打开模组自带的曲线编辑界面（反射加载，测试驱动不硬依赖模组类） */
    private static void openCurveEditor(Minecraft minecraft, String targetName) {
        minecraft.execute(() -> {
            try {
                Class<?> screenCls = Class.forName("com.uitransitions.fabric.UiTransitionsCurveScreen");
                Class<?> targetCls = Class.forName("com.uitransitions.fabric.UiTransitionsCurveScreen$Target");
                Object target = null;
                for (Object constant : targetCls.getEnumConstants()) {
                    if (targetName.equals(constant.toString())) {
                        target = constant;
                    }
                }
                Screen parent = minecraft.gui.screen();
                Object screen = screenCls.getConstructor(Screen.class, targetCls).newInstance(parent, target);
                minecraft.gui.setScreen((Screen) screen);
                log("已打开曲线编辑器(" + targetName + ")");
            } catch (Throwable t) {
                log("打开曲线编辑器失败: " + t);
                Throwable cause = t.getCause();
                while (cause != null) {
                    log("  根因: " + cause);
                    cause = cause.getCause();
                }
            }
        });
    }

    // ================================================================== 新增：附魔台（附魔书走画中画）

    /**
     * 客户端直接构造附魔台界面。
     *
     * 这里只关心**渲染**：附魔书走画中画通道，我们要看它在界面滑动时有没有跟着动、
     * 有没有被裁掉。菜单是本地新建的，服务端并不知情，所以格子里是空的 —— 对本次验证没有影响。
     */
    private static void openEnchantScreen(Minecraft minecraft) {
        minecraft.execute(() -> {
            try {
                if (minecraft.player == null) {
                    log("没有玩家，跳过附魔台界面");
                    return;
                }
                net.minecraft.world.entity.player.Inventory inventory = minecraft.player.getInventory();
                net.minecraft.world.inventory.EnchantmentMenu menu =
                        new net.minecraft.world.inventory.EnchantmentMenu(1, inventory);
                Screen screen = new net.minecraft.client.gui.screens.inventory.EnchantmentScreen(
                        menu, inventory, Component.literal("附魔"));
                minecraft.gui.setScreen(screen);
                log("已打开附魔台界面（附魔书应为画中画）");
            } catch (Throwable t) {
                log("打开附魔台界面失败: " + t);
            }
        });
    }

    // ================================================================== 新增：创造模式物品栏

    /** 切游戏模式（比原版 switchToSurvival 通用一点） */
    private static void switchGameMode(Minecraft minecraft, String modeName) {
        minecraft.execute(() -> {
            try {
                Object server = minecraft.getSingleplayerServer();
                if (server == null) {
                    log("没有集成服务器，无法切换游戏模式");
                    return;
                }
                Object playerList = server.getClass().getMethod("getPlayerList").invoke(server);
                java.util.List<?> players = (java.util.List<?>) playerList.getClass()
                        .getMethod("getPlayers").invoke(playerList);
                if (players.isEmpty()) {
                    log("服务器玩家列表为空");
                    return;
                }
                Class<?> gameType = Class.forName("net.minecraft.world.level.GameType");
                Object wanted = null;
                for (Object constant : gameType.getEnumConstants()) {
                    if (modeName.equals(constant.toString())) {
                        wanted = constant;
                    }
                }
                Object serverPlayer = players.get(0);
                serverPlayer.getClass().getMethod("setGameMode", gameType).invoke(serverPlayer, wanted);
                log("已切换为 " + modeName);
            } catch (Throwable t) {
                log("切换游戏模式失败: " + t);
            }
        });
    }

    /** 打开创造模式物品栏（物品列表的滚动与标签切换都由它承载） */
    private static void openCreativeScreen(Minecraft minecraft) {
        minecraft.execute(() -> {
            try {
                if (minecraft.player == null || minecraft.player.level() == null) {
                    log("没有玩家/世界，跳过创造物品栏");
                    return;
                }
                Screen screen = new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(
                        minecraft.player, minecraft.player.level().enabledFeatures(), false);
                minecraft.gui.setScreen(screen);
                log("已打开创造模式物品栏");
            } catch (Throwable t) {
                log("打开创造物品栏失败: " + t);
            }
        });
    }

    /** 反射调用私有的 selectTab，触发分类标签切换动画 */
    private static void selectCreativeTab(Minecraft minecraft, int index) {
        minecraft.execute(() -> {
            try {
                Screen screen = minecraft.gui.screen();
                if (screen == null) {
                    return;
                }
                java.util.List<net.minecraft.world.item.CreativeModeTab> tabs =
                        net.minecraft.world.item.CreativeModeTabs.tabs();
                if (tabs.isEmpty()) {
                    log("没有可用标签");
                    return;
                }
                Object tab = tabs.get(Math.floorMod(index, tabs.size()));
                java.lang.reflect.Method method =
                        screen.getClass().getDeclaredMethod("selectTab", net.minecraft.world.item.CreativeModeTab.class);
                method.setAccessible(true);
                method.invoke(screen, tab);
                log("已切换标签 -> " + index);
            } catch (Throwable t) {
                log("切换标签失败: " + t);
            }
        });
    }

    /** 反射改 scrollOffs，模拟滚轮/拖动滚动条（模组每帧读它来判断是否滚动） */
    private static void setCreativeScroll(Minecraft minecraft, float value) {
        minecraft.execute(() -> {
            try {
                Screen screen = minecraft.gui.screen();
                if (screen == null) {
                    return;
                }
                java.lang.reflect.Field field = screen.getClass().getDeclaredField("scrollOffs");
                field.setAccessible(true);
                field.setFloat(screen, value);
                log("已设置滚动位置 -> " + value);
            } catch (Throwable t) {
                log("设置滚动位置失败: " + t);
            }
        });
    }

    private static void openConfigScreen(Minecraft minecraft) {
        // 必须在渲染线程构建：Cloth Config 建屏时会碰 RenderSystem，跨线程会抛
        // IllegalStateException: Rendersystem called from wrong thread
        minecraft.execute(() -> {
            try {
                Class<?> modMenuClass = Class.forName("com.uitransitions.fabric.UiTransitionsModMenu");
                Object api = modMenuClass.getDeclaredConstructor().newInstance();
                Object factory = modMenuClass.getMethod("getModConfigScreenFactory").invoke(api);
                if (factory == null) {
                    log("配置界面不可用（缺 Cloth Config？）");
                    return;
                }
                Class<?> factoryInterface = Class.forName("com.terraformersmc.modmenu.api.ConfigScreenFactory");
                Screen parent = minecraft.gui.screen();
                if (parent == null) {
                    parent = new TestPanelScreen();
                }
                Object screen = factoryInterface.getMethod("create", Screen.class)
                        .invoke(factory, parent);
                minecraft.gui.setScreen((Screen) screen);
                log("已打开配置界面: " + (screen == null ? "null" : screen.getClass().getName()));
            } catch (Throwable t) {
                log("打开配置界面失败: " + t);
                Throwable cause = t.getCause();
                while (cause != null) {
                    log("  根因: " + cause);
                    cause = cause.getCause();
                }
            }
        });
    }

    /** 打开入口页（Hub）。它才是用户点「配置」之后真正看到的第一屏。 */
    private static void openHub(Minecraft minecraft) {
        minecraft.execute(() -> {
            try {
                Class<?> hubClass = Class.forName("com.uitransitions.fabric.UiTransitionsHubScreen");
                Screen parent = minecraft.gui.screen();
                if (parent == null) {
                    parent = new TestPanelScreen();
                }
                Object screen = hubClass.getConstructor(Screen.class).newInstance(parent);
                minecraft.gui.setScreen((Screen) screen);
                log("已打开入口页: " + screen.getClass().getName());
            } catch (Throwable t) {
                log("打开入口页失败: " + t);
            }
        });
    }

    /**
     * 按按钮上的文字点它（当前界面的控件树里找）。
     *
     * 必须先 `minecraft.execute` 回到渲染线程：控件树是渲染线程的东西，
     * 而且点击会改界面状态（本项目在 Cloth 那条路上就吃过"跨线程建屏"的亏）。
     */
    /**
     * 按**翻译键**找到按钮并点它。
     *
     * 为什么不能按显示文字找：这些按钮是 `Component.translatable(...)`，
     * 它的 `toString()` 是 `translation{key='ui_transitions.hub.config', args=[]}`——
     * **永远不含中文**。第一版就是按"界面动画设置"去找的，结果一个都没找到，
     * 于是所有按钮都报 ❌，看起来像三个按钮全坏了（实际只是找按钮的方法错了）。
     * 翻译键是稳定的，也不会随游戏语言变。
     *
     * 点击**直接派发给控件本身**：这才是"按钮被按到"的语义，
     * 而且不依赖控件树的事件分发链路（那条链路上本项目已经踩过 Cloth 自绘条目的坑）。
     * 想验的正是"按下这个按钮，界面会不会换"，所以直接 `onPress` 更贴近事实。
     */
    private static void clickButtonByKey(Minecraft minecraft, String label, String translationKey) {
        minecraft.execute(() -> {
            try {
                Screen screen = minecraft.gui.screen();
                if (screen == null) {
                    log("没有界面可点: " + label);
                    return;
                }
                Object widget = findWidgetByTranslationKey(screen, translationKey);
                if (widget == null) {
                    log("界面上没找到键为 " + translationKey + " 的控件");
                    dumpWidgets(screen);
                    return;
                }
                net.minecraft.client.gui.navigation.ScreenRectangle bounds =
                        (net.minecraft.client.gui.navigation.ScreenRectangle)
                                widget.getClass().getMethod("getRectangle").invoke(widget);
                // 走控件自己的 mouseClicked：与真实鼠标同一条"按钮被按下"的判定
                //   · 鼠标键号：26.3 的 AbstractWidget.isValidClickButton 判的是
                //     `button() == 1`（不是 0！）。用 0 构造事件会被控件判成"不是有效键"
                //     直接拒绝，mouseClicked 返回 false —— **看起来像按钮坏了，其实是我们造的事件不对**。
                // 按钮坐标取自控件自己的 getRectangle，不猜。
                net.minecraft.client.input.MouseButtonInfo info =
                        new net.minecraft.client.input.MouseButtonInfo(1, 0);
                net.minecraft.client.input.MouseButtonEvent event =
                        new net.minecraft.client.input.MouseButtonEvent(
                                bounds.left() + bounds.width() / 2.0,
                                bounds.top() + bounds.height() / 2.0, info);
                // 诊断：mouseClicked 的前置条件逐条量出来
                Object over = widget.getClass().getMethod("isMouseOver", double.class, double.class)
                        .invoke(widget, event.x(), event.y());
                Object result = widget.getClass()
                        .getMethod("mouseClicked",
                                net.minecraft.client.input.MouseButtonEvent.class, boolean.class)
                        .invoke(widget, event, false);
                // 松开也要派发：按钮的"按下"用 mouseClicked、"抬起"用 onRelease，
                // 只发一半在有些控件上会留下按下状态（这里两个都发，贴近真实鼠标）。
                widget.getClass()
                        .getMethod("mouseReleased", net.minecraft.client.input.MouseButtonEvent.class)
                        .invoke(widget, event);
                log("点「" + label + "」(" + bounds.left() + "," + bounds.top() + ")"
                        + " 键号=" + info.button()
                        + " isMouseOver=" + over + " mouseClicked=" + result
                        + " active=" + widget.getClass().getField("active").get(widget));
            } catch (Throwable t) {
                log("点「" + label + "」失败: " + t);
                Throwable cause = t.getCause();
                while (cause != null) {
                    log("  根因: " + cause);
                    cause = cause.getCause();
                }
            }
        });
    }

    /** 在控件树里按**翻译键**找控件（键不受游戏语言影响，比匹配显示文字可靠） */
    private static Object findWidgetByTranslationKey(Object root, String translationKey) {
        if (root == null) {
            return null;
        }
        try {
            Object message = root.getClass().getMethod("getMessage").invoke(root);
            if (message != null && message.toString().contains("key='" + translationKey + "'")) {
                return root;
            }
        } catch (Throwable ignored) {
            // 不是带文字的控件就继续往下找
        }
        java.util.List<?> children;
        try {
            children = (java.util.List<?>) root.getClass().getMethod("children").invoke(root);
        } catch (Throwable t) {
            return null;
        }
        if (children == null) {
            return null;
        }
        for (Object child : children) {
            Object found = findWidgetByTranslationKey(child, translationKey);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 把 children() 里每个控件的消息原样拼成一行（不做任何过滤，避免"我没看到"变成"不存在"） */
    private static String describeChildren(Object screen) {
        try {
            java.util.List<?> children =
                    (java.util.List<?>) screen.getClass().getMethod("children").invoke(screen);
            if (children == null) {
                return "children()=null";
            }
            StringBuilder sb = new StringBuilder("[" + children.size() + "] ");
            for (Object child : children) {
                String message = "?";
                try {
                    Object value = child.getClass().getMethod("getMessage").invoke(child);
                    message = String.valueOf(value);
                } catch (Throwable ignored) {
                    // 没消息
                }
                sb.append(child.getClass().getSimpleName()).append('=').append(message).append(" | ");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }

    /**
     * 把整个界面树（含嵌套子控件）的翻译键拼成一行。
     *
     * `describeChildren` 只看一层，而 Cloth 的条目是层层嵌套的 ——
     * 要确认"某一页里的分组建出来了"，必须递归。
     */
    private static String describeWidgetTree(Object root) {
        StringBuilder sb = new StringBuilder();
        collectWidgetKeys(root, sb, 0);
        return sb.toString();
    }

    private static void collectWidgetKeys(Object node, StringBuilder sb, int depth) {
        if (node == null || depth > 6) {
            return;
        }
        try {
            Object message = node.getClass().getMethod("getMessage").invoke(node);
            if (message != null) {
                sb.append(message).append(' ');
            }
        } catch (Throwable ignored) {
            // 没有文字的控件
        }
        java.util.List<?> children;
        try {
            children = (java.util.List<?>) node.getClass().getMethod("children").invoke(node);
        } catch (Throwable t) {
            return;
        }
        if (children == null) {
            return;
        }
        for (Object child : children) {
            collectWidgetKeys(child, sb, depth + 1);
        }
    }

    /** 等某个类名（简单名包含即可）成为当前界面；超时返回 false。只能在驱动线程调用。 */
    private static boolean waitForScreenNamed(Minecraft minecraft, String simpleNamePart, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Screen current = minecraft.gui.screen();
            if (current != null && current.getClass().getSimpleName().contains(simpleNamePart)) {
                return true;
            }
            sleep(150);
        }
        return false;
    }

    /** 界面当前有多少个控件（用来判断 init() 是否已经跑完 —— setScreen 只是排队） */
    private static int childrenCount(Object screen) {
        try {
            java.util.List<?> children =
                    (java.util.List<?>) screen.getClass().getMethod("children").invoke(screen);
            return children == null ? 0 : children.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 反射读字段，读不到就返回 "?"（诊断用，不要因为一个字段让整条日志挂掉） */
    private static Object readFieldQuietly(Object target, String name) {        try {
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return "?";
    }

    /** 把界面上所有控件的文字与位置打出来：排查"按钮没建出来 / 文字对不上 / 位置算错" */
    private static void dumpWidgets(Object root) {
        try {
            java.util.List<?> children =
                    (java.util.List<?>) root.getClass().getMethod("children").invoke(root);
            if (children == null || children.isEmpty()) {
                log("  控件树是空的（init 没跑到？）");
                return;
            }
            for (Object child : children) {
                String message = "";
                try {
                    Object value = child.getClass().getMethod("getMessage").invoke(child);
                    message = value == null ? "" : value.toString();
                } catch (Throwable ignored) {
                    // 不是带文字的控件
                }
                String bounds = "";
                try {
                    net.minecraft.client.gui.navigation.ScreenRectangle rect =
                            (net.minecraft.client.gui.navigation.ScreenRectangle)
                                    child.getClass().getMethod("getRectangle").invoke(child);
                    bounds = " @" + rect.left() + "," + rect.top()
                            + " " + rect.width() + "x" + rect.height();
                } catch (Throwable ignored) {
                    // 没有矩形
                }
                log("  控件 " + child.getClass().getSimpleName() + " \"" + message + "\"" + bounds
                        + " visible=" + readFieldQuietly(child, "visible")
                        + " width=" + readFieldQuietly(child, "width")
                        + " height=" + readFieldQuietly(child, "height"));
            }
        } catch (Throwable t) {
            log("  列出控件失败: " + t);
        }
    }

    /** 把中文标签变成安全的文件名片段（截图前缀不能用中文标点/空格） */
    private static String phaseSlug(String label) {
        if (label.contains("动画设置")) {
            return "config";
        }
        if (label.contains("渐入")) {
            return "curve_open";
        }
        if (label.contains("渐出")) {
            return "curve_close";
        }
        if (label.contains("排除")) {
            return "exclude";
        }
        return "screen";
    }

    private static void openPanel(Minecraft minecraft) {
        openPanel(minecraft, false);
    }

    /**
     * 打开测试界面；strictWait=true 时等它真的成为当前界面再返回，
     * 这样抓帧时间线才与动画起点对齐（首帧可能被开屏引导界面占着）。
     */
    private static void openPanel(Minecraft minecraft, boolean strictWait) {
        minecraft.execute(() -> {
            try {
                minecraft.gui.setScreen(new TestPanelScreen());
            } catch (Throwable t) {
                log("打开界面失败: " + t);
            }
        });
        if (!strictWait) {
            return;
        }
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            Screen current = minecraft.gui.screen();
            if (current instanceof TestPanelScreen) {
                return;
            }
            sleep(20);
        }
        log("警告：测试界面未在 15 秒内成为当前界面");
    }

    private static void closeScreen(Minecraft minecraft) {
        minecraft.execute(() -> {
            try {
                minecraft.gui.setScreen(null);
            } catch (Throwable t) {
                log("关闭界面失败: " + t);
            }
        });
    }

    /**
     * 与真实容器界面**结构相同**的最小界面：
     * 底板画在背景层（extractBackground），内容画在内容层（extractRenderState）。
     * 真实容器就是 ContainerScreen.extractBackground 里 super 之后 blit 底板。
     */
    static final class TestPanelScreen extends Screen {

        TestPanelScreen() {
            super(Component.literal("UI Transitions 测试界面"));
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                      float partialTick) {
            super.extractBackground(extractor, mouseX, mouseY, partialTick);
            int cx = this.width / 2;
            int cy = this.height / 2;
            extractor.fill(cx - 150, cy - 90, cx + 150, cy + 90, 0xFFE8E8E8);   // 底板
            extractor.fill(cx - 150, cy - 90, cx + 150, cy - 84, 0xFFFF3B30);   // 顶部红条
            extractor.fill(cx - 150, cy + 84, cx + 150, cy + 90, 0xFF2F6BFF);   // 底部蓝条
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                       float partialTick) {
            int cx = this.width / 2;
            int cy = this.height / 2;
            extractor.fill(cx - 60, cy - 30, cx + 60, cy + 30, 0xFF3AA76D);     // 槽内物品
            extractor.text(Minecraft.getInstance().font, "UI TRANSITIONS", cx - 78, cy - 4, 0xFFFFFFFF);
        }
    }

    // ================================================================== 曲线编辑器交互检查

    /**
     * 把曲线编辑器的布局字段读出来核对。
     *
     * 这里专门盯一个**真实发生过的回归**：预览的宽度是"图与列表之间剩多少"算出来的，
     * 而那段代码一度把 listX 写在 previewWidth 后面 —— 首次 init() 读到的是字段默认值 0，
     * 预览被兜成 110px 的一条窄带。截图上看是"有点窄"，很难判断是不是 bug；
     * 但把数字打出来，`预览宽=110 而可用宽度=488` 就是一眼可见的错误。
     */
    private static void probeCurveEditorLayout(Minecraft minecraft) {
        minecraft.execute(() -> {
            try {
                Screen screen = minecraft.gui.screen();
                if (screen == null || !screen.getClass().getSimpleName().contains("CurveScreen")) {
                    log("布局检查：当前不是曲线编辑器（"
                            + (screen == null ? "null" : screen.getClass().getName()) + "）");
                    return;
                }
                int graphSize = readIntField(screen, "graphSize");
                int graphX = readIntField(screen, "graphX");
                int previewX = readIntField(screen, "previewX");
                int previewWidth = readIntField(screen, "previewWidth");
                int listX = readIntField(screen, "listX");
                boolean showList = Boolean.TRUE.equals(readObjectField(screen, "showPartsPanel"));
                int gap = listX - (previewX + previewWidth);
                log(String.format("布局：界面=%dx%d 图=%d@x%d 预览=%d@x%d 列表x=%d 显示列表=%s 预览右缘到列表=%d",
                        screen.width, screen.height, graphSize, graphX, previewWidth, previewX,
                        listX, showList, gap));
                if (previewWidth <= 111 && screen.width >= 700) {
                    log("!! 问题：预览宽度被兜到了下限（" + previewWidth
                            + "），而窗口宽度有 " + screen.width + " —— 说明宽度算错了（曾经真的发生过）");
                } else {
                    log("布局检查：预览宽度正常 ✅");
                }
                if (showList && listX + readIntField(screen, "listWidth") > screen.width) {
                    log("!! 问题：动画列表超出了右边缘");
                }
            } catch (Throwable t) {
                log("布局检查失败: " + t);
            }
        });
    }

    /**
     * 交互第一步：核对布局、点动画列表换编辑对象、切「跟随/单独设置」、切模式按钮。
     *
     * **只在渲染线程做"点"这件事**，等状态变化的活交给驱动线程做 ——
     * 在渲染线程里 sleep 等控件重建，等于把渲染线程堵死，它永远等不到自己渲染的那一帧。
     */
    private static void interactWithCurveEditorSetup(Minecraft minecraft) {
        minecraft.execute(() -> {
            try {
                Screen screen = minecraft.gui.screen();
                if (screen == null || !screen.getClass().getSimpleName().contains("CurveScreen")) {
                    log("交互检查：当前不是曲线编辑器，跳过");
                    return;
                }
                // **等界面初始化完成再动它**：`setScreen` 只是排队，init() 要等下一帧才跑。
                // 早于它去点，控件树是空的（0 个控件），点谁都没反应 ——
                // 这一轮就被它误导了很久：日志看着像"按钮回调坏了"，其实按钮还没建出来。
                for (int attempt = 0; attempt < 60; attempt++) {
                    if (childrenCount(screen) >= 4) {
                        break;
                    }
                    sleep(100);
                }
                log("控件树就绪：控件数=" + childrenCount(screen));
                int graphX = readIntField(screen, "graphX");
                int graphY = readIntField(screen, "graphY");
                int graphSize = readIntField(screen, "graphSize");
                int listX = readIntField(screen, "listX");
                int listY = readIntField(screen, "listY");
                int listWidth = readIntField(screen, "listWidth");
                boolean showList = Boolean.TRUE.equals(readObjectField(screen, "showPartsPanel"));
                log("交互开始：界面=" + screen.width + "x" + screen.height
                        + " 显示列表=" + showList + " 列表x=" + listX + " 宽=" + listWidth);
                // 把字宽量出来并**按阈值判定**：窄屏下"图下面那行读数放不放得下"完全取决于它，
                // 靠估算字符个数猜不准（这个阈值就因为"相等也算放得下"而错过一次：
                // 427 宽下图是 64、读数正好 124、阈值 also 124 → 照旧被切断，只剩 "P1 … P2"）。
                // 所以这里不只打印，还直接判定，让"读数被截断"变成可发现的失败。
                int graphSizeForText = readIntField(screen, "graphSize");
                try {
                    net.minecraft.client.gui.Font font = minecraft.font;
                    String both = "P1 0.25,0.10  P2 0.25,1.00";
                    int readoutWidth = font.width(both);
                    int threshold = graphSizeForText + 60;
                    boolean fitsOneLine = readoutWidth + 8 <= threshold;
                    // 两行时每行是 "P1 0.25,0.10"，也必须放得下
                    boolean oneLineFits = font.width("P1 0.25,0.10") + 8 <= threshold;
                    log("读数排版：单行宽=" + readoutWidth + " 阈值=" + threshold
                            + " 图宽=" + graphSizeForText
                            + " → " + (fitsOneLine ? "单行" : "两行")
                            + (fitsOneLine || oneLineFits ? "  ✅" : "  ❌（连单个控制点都放不下）"));
                } catch (Throwable t) {
                    log("量字宽失败: " + t);
                }

                if (!showList) {
                    log("!! 动画列表没显示（窗口 = " + screen.width + "）—— 部位曲线在这块屏幕上根本选不到");
                } else {
                    // 行序与 Part 枚举一致：0 = 全局、1 = PANEL、2 = DIM、3 = ITEMS……
                    // 点第 3 行（ITEMS）验证"点行名能切编辑对象"。
                    //
                    // **必须先 sleep 一帧**：切编辑对象后 listVisibleRows / 滚动位置要等下一次
                    // 渲染才结算，紧接着点会因为行几何还没更新而落到相邻行上
                    // （实测：不等的话两次点到的是不同的部位，看起来像"点错了行"）。
                    int row = 3;
                    // 行几何按"渲染时算出来的可见行数"核对，别自己猜：
                    // 面板高度 = 30(表头) + 可见行数×22 + 4，行 y = listY + 30 + row*22。
                    int visibleRows = readIntField(screen, "listVisibleRows");
                    log("列表几何：可见行数=" + visibleRows + " 行高=22 首行 y=" + (listY + 30)
                            + " 滚动=" + readObjectField(screen, "listScroll"));
                    // 逐行扫一遍、打印每行被点中的部位：一行日志就能把"行几何对不对"钉死，
                    // 比来回猜坐标快得多（这个检查前后因此返工了两轮）。
                    int expected = row;
                    for (int r = 1; r <= 3; r++) {
                        double probeY = listY + 30 + 22 * r + 10;
                        click(screen, listX + listWidth / 2.0, probeY);
                        sleep(200);
                        Object picked = readObjectField(screen, "part");
                        int pickedRow = picked == null ? 0 : partOrdinal(picked) + 1;
                        log("  扫行 y=" + (int) probeY + " -> " + picked
                                + "（第 " + pickedRow + " 行）" + (pickedRow == r ? " ✅" : " ❌"));
                    }
                    double rowY = listY + 30 + 22 * expected + 10;
                    click(screen, listX + listWidth / 2.0, rowY);
                    sleep(250);
                    Object after = readObjectField(screen, "part");
                    log("点第 " + expected + " 行(y=" + (int) rowY + ")：部位 -> " + after
                            + (after != null && "ITEMS".equals(after.toString()) ? "  ✅" : "  ❌（预期 ITEMS）"));

                    // ② 点它的小方框：应当在"跟随全局 / 单独设置"之间切换
                    Object ownBefore = readObjectField(screen, "own");
                    click(screen, listX + 10, rowY);
                    sleep(250);
                    Object ownAfter = readObjectField(screen, "own");
                    log("点小方框：own " + ownBefore + " -> " + ownAfter
                            + (Boolean.valueOf(true).equals(ownAfter) ? "  ✅（已单独设置）" : "  ❌（预期 true）"));
                }

                // ③ 切到多点模式：按钮位置从字段算出来，不猜坐标
                log("切模式前：own=" + readObjectField(screen, "own")
                        + " multiMode=" + readObjectField(screen, "multiMode"));
                boolean clicked = clickCurveModeButton(screen);
                // 等待必须在**驱动线程**上做。这个 lambda 本身跑在渲染线程里，
                // 在这里 sleep 等"控件重建"等于把渲染线程堵死 —— 它永远等不到自己渲染的那一帧
                // （实测：16 秒里一帧都没跑，看起来就像"按钮永远建不出来"）。
                if (!clicked || !awaitMultiMode(screen)) {
                    log("❌ 多点模式没切过去，跳过加点/删点检查");
                    return;
                }
                log("模式切换后 multiMode=true  ✅");
            } catch (Throwable t) {
                log("交互检查(第一步)失败: " + t);
            }
        });
    }

    /**
     * 交互第二步：加点 / 点选式移动 / Delete 删点。
     *
     * 与第一步之间留出帧时间（由驱动线程等），因为模式切换后控件要重建。
     */
    private static void interactWithCurveEditorPoints(Minecraft minecraft) {
        minecraft.execute(() -> {
            try {
                Screen screen = minecraft.gui.screen();
                if (screen == null || !screen.getClass().getSimpleName().contains("CurveScreen")) {
                    log("交互检查：当前不是曲线编辑器，跳过");
                    return;
                }
                if (!Boolean.TRUE.equals(readObjectField(screen, "multiMode"))) {
                    log("❌ 还没进多点模式，跳过加点/移动/删点检查：multiMode="
                            + readObjectField(screen, "multiMode")
                            + " moveMode=" + readObjectField(screen, "moveMode"));
                    return;
                }
                int graphX = readIntField(screen, "graphX");
                int graphY = readIntField(screen, "graphY");
                int graphSize = readIntField(screen, "graphSize");
                int listX = readIntField(screen, "listX");
                int listY = readIntField(screen, "listY");
                int listWidth = readIntField(screen, "listWidth");
                // ④ 在图上点一下 → 应当加一个点。
                //
                // 先把中间点清空再点：上局跑完时曲线已经被拖过、图上有 5 个中间点，
                // 随手点的位置可能正好"离已有点太近"而被合理地拒绝 ——
                // 那不是 bug，却会让这个检查红掉（第一版就是这么误报的）。
                // 清空之后点在哪个 x 上都该成功。
                //
                // **必须显式回到"加点"模式**：加点和移动共用"点一下"这个手势，
                // 前面测模式按钮时可能已经停在移动模式了 —— 那时光标落在空白处会被
                // 解释成"把选中的点挪过来"，而不是加点（这一轮就是这么误报的）。
                writeObjectField(screen, "multi", new float[0]);
                writeObjectField(screen, "selectedPoint", -1);
                writeObjectField(screen, "moveMode", false);
                sleep(300);
                log("加点前状态：moveMode=" + readObjectField(screen, "moveMode")
                        + " multiMode=" + readObjectField(screen, "multiMode"));
                int beforePoints = countMulti(screen);
                click(screen, graphX + graphSize * 0.35, graphY + graphSize * 0.6);
                int afterPoints = countMulti(screen);
                log("加点模式：点空白处 " + beforePoints + " -> " + afterPoints
                        + (afterPoints == beforePoints + 1 ? "  ✅" : "  ❌（预期 +1）"));

                // ④-b **不依赖拖动**的移动：切到「移动」模式后点别处，选中的点应当被挪过去。
                // 这条是这一轮新增的能力，也是手机上唯一可靠的那条路，必须实测。
                Object selectedBefore = readObjectField(screen, "selectedPoint");
                clickCurveActionButton(screen);
                // 等"移动模式"真正生效再往下走：这台机器一帧能到一秒，短 sleep 不可靠
                awaitFlag(screen, "moveMode", true);
                Object moveMode = readObjectField(screen, "moveMode");
                log("切到移动模式：moveMode=" + moveMode
                        + (Boolean.valueOf(true).equals(moveMode) ? "  ✅" : "  ❌（预期 true）"));
                if (Boolean.valueOf(true).equals(moveMode) && selectedBefore instanceof Integer
                        && (Integer) selectedBefore >= 0) {
                    float[] beforeMove = (float[]) readObjectField(screen, "multi");
                    float oldX = beforeMove[0];
                    // 点到图右侧：选中的点应当被移过去，点数不变
                    click(screen, graphX + graphSize * 0.7, graphY + graphSize * 0.4);
                    float[] afterMove = (float[]) readObjectField(screen, "multi");
                    boolean moved = afterMove.length == beforeMove.length
                            && Math.abs(afterMove[0] - oldX) > 0.05F;
                    log(String.format("移动模式：点选式移动 x %.2f -> %.2f 点数 %d->%d %s",
                            oldX, afterMove[0], beforeMove.length / 2, afterMove.length / 2,
                            moved ? "  ✅" : "  ❌（点没被挪过去，或点数变了）"));
                } else {
                    log("❌ 没有选中点，无法测点选式移动（selectedPoint=" + selectedBefore + "）");
                }
                clickCurveActionButton(screen);     // 切回加点模式，别影响后面的删点检查
                sleep(250);

                // ⑤ 滚轮翻列表（装得下时"没得翻"也算正常，只记录）
                Object scrollTop = readObjectField(screen, "listScroll");
                boolean scrolled = screen.mouseScrolled(listX + 20, listY + 40, 0.0, -1.0);
                log("列表滚轮：返回=" + scrolled + " 顶部行 " + scrollTop + " -> "
                        + readObjectField(screen, "listScroll"));

                // ⑥ Delete 删点（先让鼠标"停在点上"：mouseMoved 会更新 lastMouseX/Y）
                // **必须按当前实际坐标定位**：上一步刚把点移到 0.70，若还拿 0.35 去指，
                // 那里已经没有点了 —— Delete 找不到目标，看起来像"删不掉"。
                float[] nowPoints = (float[]) readObjectField(screen, "multi");
                double pointX = nowPoints.length >= 2 ? nowPoints[0] : 0.5F;
                double pointY = nowPoints.length >= 2 ? nowPoints[1] : 0.5F;
                double px = graphX + graphSize * pointX;
                double py = graphY + graphSize * (1.0 - pointY);
                screen.mouseMoved(px, py);
                int beforeDelete = countMulti(screen);
                dispatchDelete(screen);
                int afterDelete = countMulti(screen);
                log(String.format("Delete 删点（鼠标指向 %.2f,%.2f → 像素 %.0f,%.0f）：点数 %d -> %d %s",
                        pointX, pointY, px, py, beforeDelete, afterDelete,
                        afterDelete == beforeDelete - 1 ? "  ✅" : "  ❌（预期 -1）"));
            } catch (Throwable t) {
                log("交互检查(第二步)失败: " + t);
            }
        });
    }

    /**
     * 点"多点 / 控制点"那个模式按钮。
     *
     * 位置从屏幕字段算出来，**不猜坐标** —— 布局会随窗口大小变，写死坐标的检查迟早会点空，
     * 而"点空了"和"功能坏了"在日志里长得一模一样。
     */
    private static boolean clickCurveModeButton(Screen screen) {
        try {
            // 按**翻译键**找，因为按钮文字是 Component.translatable，
            // toString() 是 `translation{key='…'}`、永远不含中文 ——
            // 早先按「多点」/「控制点」两个字去找，两个都找不到，只能退回到"按布局算坐标"，
            // 于是这个检查其实一直没在验证"按钮的文字/位置对不对"，只是碰巧点中了。
            // 标签会随模式切换（to_multi / to_bezier 二选一），所以两个键都要试。
            Object hit = findWidgetByTranslationKey(screen, "ui_transitions.curve.mode.to_multi");
            if (hit == null) {
                hit = findWidgetByTranslationKey(screen, "ui_transitions.curve.mode.to_bezier");
            }
            if (hit == null) {
                log("❌ 找不到多点/控制点模式按钮（按钮没建出来？）");
                dumpWidgets(screen);
                return false;
            }
            net.minecraft.client.gui.navigation.ScreenRectangle bounds =
                    (net.minecraft.client.gui.navigation.ScreenRectangle)
                            hit.getClass().getMethod("getRectangle").invoke(hit);
            boolean active = (Boolean) hit.getClass().getField("active").get(hit);
            log("模式按钮位置 " + bounds.left() + "," + bounds.top()
                    + " 尺寸 " + bounds.width() + "x" + bounds.height() + " active=" + active);
            if (!active) {
                log("❌ 模式按钮是灰的（own=false 时不该继续测加点）");
                return false;
            }
            // 先按真实鼠标那样派发；没生效再**直接调 onPress** 兜底，并把这件事记下来。
            // 两种方式都试是刻意的：派发无效但 onPress 有效 → 问题在事件链；
            // 两个都没反应 → 问题在回调本身。一次运行就能把断点定下来。
            boolean before = Boolean.TRUE.equals(readObjectField(screen, "multiMode"))
                    && Boolean.TRUE.equals(readObjectField(screen, "moveMode"));
            click(screen, bounds.left() + bounds.width() / 2.0,
                    bounds.top() + bounds.height() / 2.0);
            sleep(250);
            boolean after = Boolean.TRUE.equals(readObjectField(screen, "multiMode"))
                    && Boolean.TRUE.equals(readObjectField(screen, "moveMode"));
            if (before == after) {
                boolean multiBefore = Boolean.TRUE.equals(readObjectField(screen, "multiMode"));
                log("⚠️ 派发点击没有改变模式状态，改为直接调 onPress");
                widgetOnPress(hit);
                sleep(250);
                boolean multiAfter = Boolean.TRUE.equals(readObjectField(screen, "multiMode"));
                log("直接 onPress 后：multiMode " + multiBefore + " -> " + multiAfter
                        + (multiBefore != multiAfter ? "（说明是事件链的问题，不是回调）" : "（回调也没生效）"));
            }
            return true;
        } catch (Throwable t) {
            log("点模式按钮失败: " + t);
            return false;
        }
    }

    /** 点「去加点 / 去移动」那个模式切换按钮（多点模式下才有） */
    private static boolean clickCurveActionButton(Screen screen) {
        try {
            // 按钮是"渲染前按 dirty 标志重建"的，所以点完模式按钮后它要等下一帧才出现。
            // **不能用写死的短 sleep**：这台机器上（软件渲染）一帧能到一秒左右，
            // 按"一帧≈100ms"去等会一直等不到，看起来就像按钮根本没建出来 ——
            // 这一轮被它误导了很久（日志里"待重建帧"明明拿到了标志，只是那帧来晚了）。
            // 改成**等状态真正就绪**，上限给足。
            Object hit = null;
            long deadline = System.currentTimeMillis() + 15_000L;
            while (hit == null && System.currentTimeMillis() < deadline) {
                hit = findWidgetByTranslationKey(screen, "ui_transitions.curve.action.to_move");
                if (hit == null) {
                    hit = findWidgetByTranslationKey(screen, "ui_transitions.curve.action.to_add");
                }
                if (hit == null) {
                    sleep(150);
                }
            }
            if (hit == null) {
                log("❌ 找不到「去加点/去移动」按钮（等了 15 秒）"
                        + " 控件数=" + childrenCount(screen)
                        + " multiMode=" + readObjectField(screen, "multiMode")
                        + " moveMode=" + readObjectField(screen, "moveMode"));
                log("控件树原始内容: " + describeChildren(screen));
                return false;
            }
            net.minecraft.client.gui.navigation.ScreenRectangle bounds =
                    (net.minecraft.client.gui.navigation.ScreenRectangle)
                            hit.getClass().getMethod("getRectangle").invoke(hit);
            log("动作按钮位置 " + bounds.left() + "," + bounds.top()
                    + " active=" + hit.getClass().getField("active").get(hit));
            // 直接调 onPress：Hub 那边的实测结论是"派发鼠标事件容易被中间层吃掉"，
            // 而控件自己的 onPress 才是"按钮被按下"这件事本身。
            widgetOnPress(hit);
            return true;
        } catch (Throwable t) {
            log("点动作按钮失败: " + t);
            return false;
        }
    }

    /** 直接调控件的 onPress（诊断用：把"事件链没送到"和"回调本身没生效"分开） */
    private static void widgetOnPress(Object widget) {
        try {
            widget.getClass().getMethod("onPress", net.minecraft.client.input.InputWithModifiers.class)
                    .invoke(widget, new net.minecraft.client.input.MouseButtonInfo(1, 0));
        } catch (Throwable t) {
            log("onPress 调用失败: " + t.getCause());
        }
    }

    /**
     * 等"多点模式"真正生效。
     *
     * **只能在驱动线程调用**：界面状态要靠渲染帧推进，而在渲染线程里等待会把渲染线程堵死。
     * 这也解释了为什么之前"睡 400ms 再读"时好时坏 —— 那台机器一帧能到一秒。
     */
    private static boolean awaitMultiMode(Screen screen) {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(readObjectField(screen, "multiMode"))) {
                log("等多点模式：已就绪 moveMode=" + readObjectField(screen, "moveMode"));
                return true;
            }
            sleep(150);
        }
        log("❌ 等 15 秒仍不是多点模式：multiMode=" + readObjectField(screen, "multiMode")
                + " moveMode=" + readObjectField(screen, "moveMode"));
        return false;
    }

    /** 等指定字段变成期望的布尔值（同上：只能在驱动线程调用） */
    private static boolean awaitFlag(Screen screen, String field, boolean expected) {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.valueOf(expected).equals(readObjectField(screen, field))) {
                return true;
            }
            sleep(150);
        }
        log("❌ 等 15 秒 " + field + " 仍不是 " + expected);
        return false;
    }

    /** 部位枚举的序号（界面行序 = 序号 + 1，第 0 行是"全局"）；拿不到返回 -2 */
    private static int partOrdinal(Object part) {
        if (part == null) {
            return -1;      // 全局
        }
        try {
            Object ordinal = part.getClass().getMethod("ordinal").invoke(part);
            return ordinal instanceof Integer ? (Integer) ordinal : -2;
        } catch (Throwable t) {
            return -2;
        }
    }

    /** 多点模式下的总点数（含固定的首尾） */
    private static int countMulti(Screen screen) {
        Object multi = readObjectField(screen, "multi");
        return multi instanceof float[] ? ((float[]) multi).length / 2 + 2 : -1;
    }

    private static void dispatchDelete(Screen screen) {
        try {
            // 261 = GLFW_KEY_DELETE
            screen.keyPressed(new net.minecraft.client.input.KeyEvent(261, 0, 0));
        } catch (Throwable t) {
            log("派发 Delete 失败: " + t);
        }
    }

    /** 派发一次左键点击（走界面自己的 mouseClicked，与真实鼠标同一条路径） */
    private static void click(Screen screen, double x, double y) {
        click(screen, x, y, null);
    }

    private static void click(Screen screen, double x, double y, Object target) {
        try {
            net.minecraft.client.input.MouseButtonInfo info =
                    new net.minecraft.client.input.MouseButtonInfo(0, 0);
            net.minecraft.client.input.MouseButtonEvent event =
                    new net.minecraft.client.input.MouseButtonEvent(x, y, info);
            boolean result = screen.mouseClicked(event, false);
            if (target == null) {
                log(String.format("点击 (%.0f,%.0f) -> %s", x, y, result ? "已处理 ✅" : "没人处理 ❌"));
            }
            screen.mouseReleased(event);
        } catch (Throwable t) {
            log("派发点击失败: " + t);
        }
    }

    private static int readIntField(Object target, String name) {
        Object value = readObjectField(target, name);
        return value instanceof Integer ? (Integer) value : -1;
    }

    /** 读私有字段（只用于测试驱动，不进模组本体） */
    private static Object readObjectField(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 写私有字段：只为把被测状态摆到一个确定的起点（例如清空曲线上的点） */
    private static void writeObjectField(Object target, String name, Object value) {
        if (target == null) {
            return;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                log("写字段 " + name + " 失败: " + t);
                return;
            }
        }
    }

    /** 在控件树里按类名找控件（只匹配简单名，避免依赖具体包路径） */
    private static Object findWidget(Object root, String simpleName) {        if (root == null) {
            return null;
        }
        if (root.getClass().getSimpleName().equals(simpleName)) {
            return root;
        }
        java.util.List<?> children;
        try {
            java.lang.reflect.Method method = root.getClass().getMethod("children");
            children = (java.util.List<?>) method.invoke(root);
        } catch (Throwable t) {
            return null;
        }
        if (children == null) {
            return null;
        }
        for (Object child : children) {
            Object found = findWidget(child, simpleName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ================================================================== 抓帧

    private static void captureTimeline(Minecraft minecraft, File outDir, String prefix) {
        // 每帧截图本身要花几百毫秒，所以按"实际耗时"命名，并连续抓够帧数覆盖整段动画
        long start = System.nanoTime();
        for (int i = 0; i < FRAME_COUNT; i++) {
            capture(minecraft, outDir, prefix, start, i);
            sleep(INTERVAL_MS);
        }
    }

    /**
     * 抓一帧并存到输出目录。
     * 注意：原版截图是异步写文件的，必须等文件写完（大小稳定且非 0）才能复制，
     * 否则只能拿到 0 字节的空文件。
     */
    private static void capture(Minecraft minecraft, File outDir, String prefix, long startNanos, int index) {
        File screenshotDir = new File(minecraft.gameDirectory, "screenshots");
        Set<String> before = listFiles(screenshotDir);
        minecraft.execute(() -> Screenshot.grab(minecraft, false));

        File fresh = null;
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            File candidate = newestNotIn(screenshotDir, before);
            if (candidate != null && candidate.length() > 0L) {
                long size = candidate.length();
                sleep(150);
                if (candidate.length() == size) {
                    fresh = candidate;
                    break;
                }
            }
            sleep(30);
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        String name = String.format("%s_%02d_t%04dms", prefix, index, elapsedMs);
        if (fresh == null) {
            log("截图超时: " + name);
            return;
        }
        try {
            File target = new File(outDir, name + ".png");
            Files.copy(fresh.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log("已保存 " + target.getName() + "  (" + target.length() + " 字节)");
        } catch (Throwable t) {
            log("复制截图失败: " + t);
        }
    }

    private static Set<String> listFiles(File dir) {
        Set<String> names = new HashSet<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                names.add(file.getName());
            }
        }
        return names;
    }

    private static File newestNotIn(File dir, Set<String> known) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        File newest = null;
        for (File file : files) {
            if (known.contains(file.getName()) || !file.getName().endsWith(".png")) {
                continue;
            }
            if (newest == null || file.lastModified() > newest.lastModified()) {
                newest = file;
            }
        }
        return newest;
    }

    // ================================================================== 工具

    private static boolean waitFor(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 继续等
            }
            sleep(200);
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void quit(Minecraft minecraft) {
        log("退出游戏");
        try {
            minecraft.execute(minecraft::stop);
        } catch (Throwable t) {
            log("stop 失败: " + t);
        }
        sleep(6000);
        System.exit(0);
    }

    private static void log(String message) {
        System.out.println(TAG + message);
        System.out.flush();
    }
}
