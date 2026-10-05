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
        log("输出目录: " + outDir.getAbsolutePath());

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

        // ---------- 打开动画 ----------
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

        // ---------- 配置界面（Cloth Config） ----------
        log("打开图形化配置界面");
        openConfigScreen(minecraft);
        sleep(2500);
        capture(minecraft, outDir, "configgui", System.nanoTime(), 0);

        // ---------- 进世界：实测真实容器界面（含玩家小模型） ----------
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
            log("切换为生存模式（生存背包才有玩家小模型）");
            switchToSurvival(minecraft);
            sleep(2000);
            log("打开背包（真实容器界面：底板 + 玩家小模型一起淡入）");
            openInventory(minecraft);
            captureBurst(minecraft, "inv_open", 12, 120);
            capture(minecraft, outDir, "inv_steady", System.nanoTime(), 0);
            log("关闭背包（一起淡出）");
            closeScreen(minecraft);
            captureBurst(minecraft, "inv_close", 14, 120);
            sleep(2500);
        }

        // ---------- Sodium 视频设置里的本模组页面 ----------
        log("打开 Sodium 视频设置（应能看到 UI Transitions 页面）");
        openSodiumScreen(minecraft);
        sleep(3500);
        capture(minecraft, outDir, "sodium", System.nanoTime(), 0);

        log("测试完成");
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

    /** 切到生存模式：创造模式的物品栏不画玩家小模型，只有生存背包才有 */
    private static void switchToSurvival(Minecraft minecraft) {
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
                Object survival = null;
                for (Object constant : gameType.getEnumConstants()) {
                    if ("SURVIVAL".equals(constant.toString())) {
                        survival = constant;
                    }
                }
                Object serverPlayer = players.get(0);
                serverPlayer.getClass().getMethod("setGameMode", gameType).invoke(serverPlayer, survival);
                log("已切换为生存模式");
            } catch (Throwable t) {
                log("切换游戏模式失败: " + t);
            }
        });
    }

    /**
     * 连拍：只调 grab（立刻抓当前帧），不等文件写完。
     * 普通 capture 每帧要等约 2 秒的文件落盘，2 秒的动画根本抓不到中间帧。
     * 图片直接落在 <游戏目录>/screenshots/，测试后从那里取即可。
     */
    private static void captureBurst(Minecraft minecraft, String prefix, int count, long gapMs) {
        java.io.File dir = new java.io.File(minecraft.gameDirectory, "screenshots");
        long before = System.currentTimeMillis() - 1500L;
        for (int i = 0; i < count; i++) {
            final int index = i;
            minecraft.execute(() -> {
                try {
                    Screenshot.grab(minecraft, false);
                    log("连拍 " + prefix + " #" + index);
                } catch (Throwable t) {
                    log("连拍失败: " + t);
                }
            });
            sleep(gapMs);
        }
        sleep(4000);   // 等最后几张写完
        // 按相位把刚生成的图复制出来并改名，否则全部堆在 screenshots 里分不清属于哪个阶段
        java.io.File[] files = dir.listFiles((d, name) -> name.endsWith(".png"));
        if (files == null) {
            return;
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(java.io.File::lastModified));
        int index = 0;
        java.io.File outDir = new java.io.File(minecraft.gameDirectory, "uitransitions-captures");
        outDir.mkdirs();
        for (java.io.File file : files) {
            if (file.lastModified() < before) {
                continue;
            }
            try {
                java.nio.file.Files.copy(file.toPath(),
                        new java.io.File(outDir, String.format("%s_%02d.png", prefix, index)).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                index++;
            } catch (Throwable t) {
                log("复制连拍图失败: " + t);
            }
        }
        log("连拍 " + prefix + " 已归档 " + index + " 张到 uitransitions-captures/");
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
