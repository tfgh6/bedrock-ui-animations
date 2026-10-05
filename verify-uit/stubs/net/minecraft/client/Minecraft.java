package net.minecraft.client;

import java.io.File;

public class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();

    public File gameDirectory = new File(System.getProperty("uitransitions.gamedir", "."));
    public net.minecraft.client.gui.Gui gui = new net.minecraft.client.gui.Gui();

    public static Minecraft getInstance() {
        return INSTANCE;
    }
}
