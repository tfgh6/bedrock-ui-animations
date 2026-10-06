package net.minecraft.client.gui.screens;

/** 与 26.3 一致：带一个 reason 字段（NETHER_PORTAL / END_PORTAL / OTHER） */
public class LevelLoadingScreen extends Screen {
    public enum Reason { NETHER_PORTAL, END_PORTAL, OTHER }

    private Reason reason;

    public LevelLoadingScreen(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return this.reason;
    }
}
