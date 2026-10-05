package com.uitransitions.fabric;

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric 入口。这里刻意不做任何游戏相关初始化：
 * 配置在首次真正用到时（第一个界面渲染）才加载，避免在 Minecraft 实例尚未就绪时锁定默认值。
 * 同一个 jar 也带 NeoForge 元数据，NeoForge 侧由 Mixin 直接生效，不需要入口类。
 */
public final class UiTransitionsFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        System.out.println("[UI Transitions] Fabric 客户端初始化完成");
    }
}
