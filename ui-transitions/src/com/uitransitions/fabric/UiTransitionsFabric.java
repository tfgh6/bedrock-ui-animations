package com.uitransitions.fabric;

import com.uitransitions.DependencyCheck;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric 入口。
 *
 * 依赖检查放在这里：Cloth Config 是本模组的**硬前置**（配置界面全靠它）。
 * 缺了就直接让游戏崩，并把"缺什么、去哪装、不想装怎么办"写在崩溃信息里 ——
 * 静默降级只会让用户看到"装了个什么都不能调的模组"，还以为是坏了。
 *
 * fabric.mod.json 里也把 cloth-config 写进了 depends，正常情况下加载器会更早拦住；
 * 这里再查一遍是为了让报错信息更完整（加载器那句通常只有一行 mod id）。
 */
public final class UiTransitionsFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        boolean clothPresent = false;
        for (String id : DependencyCheck.CLOTH_IDS) {
            if (FabricLoader.getInstance().isModLoaded(id)) {
                clothPresent = true;
                break;
            }
        }
        if (!clothPresent) {
            DependencyCheck.failMissingCloth("Fabric");
        }
        System.out.println("[UI Transitions] Fabric 客户端初始化完成（已检测到 Cloth Config）");
    }
}
