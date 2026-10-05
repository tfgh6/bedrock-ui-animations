package com.uitransitions.visualtest;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/** NeoForge 侧薄入口：驱动逻辑本身与加载器无关，见 {@link VisualTestDriver}。 */
@Mod(value = "ui_transitions_visualtest", dist = Dist.CLIENT)
public final class VisualTestNeoForge {

    public VisualTestNeoForge() {
        VisualTestDriver.start();
    }
}
