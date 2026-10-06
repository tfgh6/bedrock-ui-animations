package net.minecraft.client;

import java.io.File;

public class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();

    /** 默认落到临时目录：断言跑起来不该改写仓库里的 config/ */
    public File gameDirectory = new File(System.getProperty("uitransitions.gamedir",
            System.getProperty("java.io.tmpdir") + File.separator + "uitransitions-verify"));
    public net.minecraft.client.gui.Gui gui = new net.minecraft.client.gui.Gui();
    public MouseHandler mouseHandler = new MouseHandler();

    public static Minecraft getInstance() {
        return INSTANCE;
    }

    /** allowLookDuringClose 分支会调用 grabMouse() */
    public static class MouseHandler {
        public void grabMouse() {
        }
    }
}
