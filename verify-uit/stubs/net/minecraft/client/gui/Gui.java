package net.minecraft.client.gui;

import net.minecraft.client.gui.screens.Screen;

public class Gui {
    public Screen current;

    public Screen screen() {
        return this.current;
    }

    public void setScreen(Screen screen) {
        this.current = screen;
    }
}
