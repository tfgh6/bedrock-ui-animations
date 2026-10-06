package com.uitransitions.fabric;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 入口页：只放几个**原版按钮**，通向配置界面与曲线编辑器。
 *
 * 为什么要有这一页：曲线编辑器原本做成 Cloth Config 里的一个自绘条目，
 * 渲染正常、直接派发点击也能开，但真实鼠标点击就是传不到它那儿
 * （Cloth 的条目命中判定链路太长、又没法离线验证）。与其继续跟它较劲，
 * 不如用一个绝不会出问题的原版按钮把入口摆出来 —— 按钮的点击是标准路径。
 *
 * ## 这里曾经写死过一个 Fabric 专属调用
 *
 * 早先这里用 `FabricLoader.getInstance().isModLoaded("cloth-config")` 判断要不要显示
 * 第一个按钮。这个类在 **NeoForge 上会被加载**，而 FabricLoader 在那边根本不存在 ——
 * `init()` 一执行就抛 NoClassDefFoundError，控件一个都没加上，
 * 表现成"NeoForge 上配置界面打开后空空如也"（真实反馈）。
 *
 * 现在不需要这个判断了：Cloth Config 已经是**硬前置**（缺了会在启动时直接崩），
 * 所以第一个按钮无条件显示。共用的界面类里也**不允许**再出现
 * net.fabricmc / net.neoforged 的引用，tools/check_shared_code.py 会拦住。
 */
public final class UiTransitionsHubScreen extends Screen {

    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;

    private final Screen parent;

    public UiTransitionsHubScreen(Screen parent) {
        super(Component.translatable("ui_transitions.hub.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int total = BUTTON_HEIGHT * 3 + GAP * 2;
        int y = Math.max(40, this.height / 2 - total / 2);

        addRenderableWidget(Button.builder(
                        Component.translatable("ui_transitions.hub.config"),
                        b -> this.minecraft.setScreenAndShow(UiTransitionsConfigScreen.create(this)))
                .bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        y += BUTTON_HEIGHT + GAP;
        addRenderableWidget(Button.builder(
                        Component.translatable("ui_transitions.hub.curve_open"),
                        b -> this.minecraft.setScreenAndShow(
                                new UiTransitionsCurveScreen(this, UiTransitionsCurveScreen.Target.OPEN)))
                .bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        y += BUTTON_HEIGHT + GAP;
        addRenderableWidget(Button.builder(
                        Component.translatable("ui_transitions.hub.curve_close"),
                        b -> this.minecraft.setScreenAndShow(
                                new UiTransitionsCurveScreen(this, UiTransitionsCurveScreen.Target.CLOSE)))
                .bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
        extractor.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        extractor.centeredText(this.font,
                Component.translatable("ui_transitions.hub.hint"),
                this.width / 2, 34, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }
}
