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
            sleep(2500);
            capture(minecraft, outDir, "configgui", System.nanoTime(), 0);
        }

        // ---------- 配置界面里的"打开曲线编辑器"入口能不能点开 ----------
        if (phaseEnabled("configclick")) {
            log("测试配置界面里的曲线编辑器入口（用户反馈过点了没反应）");
            openConfigScreen(minecraft);
            sleep(2500);
            clickCurveEditorEntry(minecraft);
            sleep(2500);
            Screen now = minecraft.gui.screen();
            String name = now == null ? "null" : now.getClass().getName();
            log("点击后当前界面 = " + name);
            if (name.contains("CurveScreen")) {
                log("结果：曲线编辑器打开成功 ✅");
                capture(minecraft, outDir, "curve_from_config", System.nanoTime(), 0);
            } else {
                log("结果：曲线编辑器没打开 ❌");
                capture(minecraft, outDir, "configclick_fail", System.nanoTime(), 0);
            }
            closeScreen(minecraft);
            sleep(1500);
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
            interactWithCurveEditor(minecraft);
            sleep(600);
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

    // ================================================================== 新增：配置界面的曲线编辑器入口

    /**
     * 在配置界面里找到"打开曲线编辑器"那个自绘条目，替用户点一下，看它到底开不开。
     *
     * 用户反馈过这个入口"点了没反应"，而它在截图上看起来完全正常 ——
     * 这种只有交互才暴露的问题，必须真的派发一次点击才测得出来。
     */
    private static void clickCurveEditorEntry(Minecraft minecraft) {
        minecraft.execute(() -> {
            try {
                Screen screen = minecraft.gui.screen();
                if (screen == null) {
                    log("没有界面可点");
                    return;
                }
                Object entry = findWidget(screen, "CurveEditorEntry");
                if (entry == null) {
                    log("在配置界面里没找到 CurveEditorEntry（条目没被建出来？）");
                    return;
                }
                log("找到曲线编辑器条目: " + entry.getClass().getName());
                java.lang.reflect.Method clicked = entry.getClass().getMethod(
                        "mouseClicked", net.minecraft.client.input.MouseButtonEvent.class, boolean.class);
                // 条目是私有内部类：方法本身是 public，但类对外不可见，反射必须先开权限
                clicked.setAccessible(true);
                net.minecraft.client.input.MouseButtonInfo info =
                        new net.minecraft.client.input.MouseButtonInfo(0, 0);
                // 坐标随便给：这个条目不看坐标，只要左键就开
                Object event = new net.minecraft.client.input.MouseButtonEvent(0.0, 0.0, info);
                Object result = clicked.invoke(entry, event, false);
                log("mouseClicked 返回 " + result);
            } catch (Throwable t) {
                log("点击曲线编辑器条目失败: " + t);
                Throwable cause = t.getCause();
                while (cause != null) {
                    log("  根因: " + cause);
                    cause = cause.getCause();
                }
            }
        });
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

    /** 真的往曲线编辑器上派发点击，并核对"点完的状态对不对" */
    private static void interactWithCurveEditor(Minecraft minecraft) {
        minecraft.execute(() -> {
            try {
                Screen screen = minecraft.gui.screen();
                if (screen == null || !screen.getClass().getSimpleName().contains("CurveScreen")) {
                    log("交互检查：当前不是曲线编辑器，跳过");
                    return;
                }
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
                if (!clickCurveModeButton(screen)) {
                    return;     // 按钮点不了（不可编辑等）：后面的加点/删点没有意义
                }
                sleep(400);
                Object multiMode = readObjectField(screen, "multiMode");
                log("模式切换后 multiMode=" + multiMode
                        + (Boolean.valueOf(true).equals(multiMode) ? "  ✅" : "  ❌（预期 true）"));
                if (!Boolean.valueOf(true).equals(multiMode)) {
                    log("多点模式没切过去，跳过加点/删点检查");
                    return;
                }

                // ④ 在图上点一下 → 应当加一个点。
                //
                // 先把中间点清空再点：上局跑完时曲线已经被拖过、图上有 5 个中间点，
                // 随手点的位置可能正好"离已有点太近"而被合理地拒绝 ——
                // 那不是 bug，却会让这个检查红掉（第一版就是这么误报的）。
                // 清空之后点在哪个 x 上都该成功。
                writeObjectField(screen, "multi", new float[0]);
                int beforePoints = countMulti(screen);
                click(screen, graphX + graphSize * 0.35, graphY + graphSize * 0.6);
                int afterPoints = countMulti(screen);
                log("清空后在图中央点一下：点数 " + beforePoints + " -> " + afterPoints
                        + (afterPoints == beforePoints + 1 ? "  ✅" : "  ❌（预期 +1）"));

                // ⑤ 滚轮翻列表（装得下时"没得翻"也算正常，只记录）
                Object scrollTop = readObjectField(screen, "listScroll");
                boolean scrolled = screen.mouseScrolled(listX + 20, listY + 40, 0.0, -1.0);
                log("列表滚轮：返回=" + scrolled + " 顶部行 " + scrollTop + " -> "
                        + readObjectField(screen, "listScroll"));

                // ⑥ Delete 删点（先让鼠标"停在点上"：mouseMoved 会更新 lastMouseX/Y）
                screen.mouseMoved(graphX + graphSize * 0.35, graphY + graphSize * 0.6);
                int beforeDelete = countMulti(screen);
                dispatchDelete(screen);
                int afterDelete = countMulti(screen);
                log("Delete 删点：点数 " + beforeDelete + " -> " + afterDelete
                        + (afterDelete == beforeDelete - 1 ? "  ✅" : "  ❌（预期 -1）"));
            } catch (Throwable t) {
                log("交互检查失败: " + t);
                Throwable cause = t.getCause();
                while (cause != null) {
                    log("  根因: " + cause);
                    cause = cause.getCause();
                }
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
            int graphX = readIntField(screen, "graphX");
            int graphSize = readIntField(screen, "graphSize");
            int y = readIntField(screen, "height") - 24;
            // 与 buildButtons 里的算法保持一致：宽度按可用空间算，缩到 52 为止
            int buttonWidth = Math.max(52, Math.min(96, (screen.width - 16 * 2 - 8 * 3) / 4));
            double x = graphX + buttonWidth + 8 + buttonWidth / 2.0;
            double centerY = y + 10;
            // 先确认这个坐标上真的是那个按钮，而不是别的控件
            Object hit = findWidgetByMessage(screen, "多点");
            if (hit == null) {
                hit = findWidgetByMessage(screen, "控制点");
            }
            if (hit != null) {
                net.minecraft.client.gui.navigation.ScreenRectangle bounds =
                        (net.minecraft.client.gui.navigation.ScreenRectangle)
                                hit.getClass().getMethod("getRectangle").invoke(hit);
                x = bounds.left() + bounds.width() / 2.0;
                centerY = bounds.top() + bounds.height() / 2.0;
                log("模式按钮位置 " + bounds.left() + "," + bounds.top()
                        + " 尺寸 " + bounds.width() + "x" + bounds.height()
                        + " active=" + hit.getClass().getField("active").get(hit));
            } else {
                log("按文字没找到模式按钮，改用按布局算出的坐标 (" + (int) x + "," + (int) centerY + ")");
            }
            click(screen, x, centerY);
            return true;
        } catch (Throwable t) {
            log("点模式按钮失败: " + t);
            return false;
        }
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
    private static int countMulti(Screen screen) {        Object multi = readObjectField(screen, "multi");
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

    private static Object findWidgetByMessage(Object root, String text) {
        if (root == null) {
            return null;
        }
        try {
            java.lang.reflect.Method getMessage = root.getClass().getMethod("getMessage");
            Object message = getMessage.invoke(root);
            if (message instanceof Component && ((Component) message).getString().contains(text)) {
                return root;
            }
        } catch (Throwable ignored) {
            // 不是控件就继续往下找
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
            Object found = findWidgetByMessage(child, text);
            if (found != null) {
                return found;
            }
        }
        return null;
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
