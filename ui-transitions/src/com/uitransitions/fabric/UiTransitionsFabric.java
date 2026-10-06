package com.uitransitions.fabric;

import com.uitransitions.DependencyCheck;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric 入口。
 *
 * 依赖检查放在这里。Fabric 上本模组有两个硬前置：
 *   · **Cloth Config** —— 配置界面完全由它提供；
 *   · **ModMenu**     —— Fabric 侧没有它就没有「配置」按钮，用户根本进不去配置界面。
 * 缺任何一个都直接让游戏崩，并把"缺什么、去哪装、不想装怎么办"写在崩溃信息里 ——
 * 静默降级只会让用户看到"装了个什么都不能调的模组"，还以为是坏了。
 *
 * fabric.mod.json 里两者也都写进了 depends，正常情况下加载器会更早拦住；
 * 这里再查一遍是为了让报错信息更完整（加载器那句通常只有一行 mod id）。
 */
public final class UiTransitionsFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        if (!anyLoaded(DependencyCheck.CLOTH_IDS)) {
            DependencyCheck.failMissing("Fabric", DependencyCheck.CLOTH_NAME,
                    DependencyCheck.CLOTH_IDS, DependencyCheck.CLOTH_URL);
        }
        if (!anyLoaded(DependencyCheck.MODMENU_IDS)) {
            DependencyCheck.failMissing("Fabric", DependencyCheck.MODMENU_NAME,
                    DependencyCheck.MODMENU_IDS, DependencyCheck.MODMENU_URL);
        }
        System.out.println("[UI Transitions] Fabric 客户端初始化完成（Cloth Config 与 ModMenu 均已就位）");
    }

    private static boolean anyLoaded(String[] ids) {
        for (String id : ids) {
            if (FabricLoader.getInstance().isModLoaded(id)) {
                return true;
            }
        }
        return false;
    }
}
