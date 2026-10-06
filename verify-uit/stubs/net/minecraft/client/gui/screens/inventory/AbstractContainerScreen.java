package net.minecraft.client.gui.screens.inventory;

import net.minecraft.client.gui.screens.Screen;

/** 真实环境里是 AbstractContainerScreen<T extends AbstractContainerMenu>；这里只要泛型形状一致 */
public class AbstractContainerScreen<T> extends Screen {
    protected int leftPos;
    protected int topPos;
}
