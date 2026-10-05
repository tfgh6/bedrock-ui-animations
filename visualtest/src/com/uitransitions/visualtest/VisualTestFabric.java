package com.uitransitions.visualtest;

import net.fabricmc.api.ClientModInitializer;

/** Fabric 侧薄入口：驱动逻辑本身与加载器无关，见 {@link VisualTestDriver}。 */
public final class VisualTestFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        VisualTestDriver.start();
    }
}
