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
 */
public final class UiTransitionsHubScreen extends Screen {

    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;

    private final Screen parent;

    public UiTransitionsHubScreen(Screen parent) {
        super(Component.literal("Bedrock UI Animations"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int total = BUTTON_HEIGHT * 3 + GAP * 2;
        int y = Math.max(40, this.height / 2 - total / 2);

        // 配置界面依赖 Cloth Config；曲线编辑器是本模组自带的，不依赖它。
        // 所以没装 Cloth 时依然能进来调曲线，只是第一个按钮换成一行说明。
        boolean cloth = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cloth-config");
        if (cloth) {
            addRenderableWidget(Button.builder(
                            Component.literal("界面动画设置"),
                            b -> this.minecraft.setScreenAndShow(UiTransitionsConfigScreen.create(this)))
                    .bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build());
        } else {
            addRenderableWidget(Button.builder(
                            Component.literal("未安装 Cloth Config —— 请直接编辑配置文件"),
                            b -> { })
                    .bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build())
                    .active = false;
        }

        y += BUTTON_HEIGHT + GAP;
        addRenderableWidget(Button.builder(
                        Component.literal("曲线编辑器 — 渐入（打开界面）"),
                        b -> this.minecraft.setScreenAndShow(
                                new UiTransitionsCurveScreen(this, UiTransitionsCurveScreen.Target.OPEN)))
                .bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        y += BUTTON_HEIGHT + GAP;
        addRenderableWidget(Button.builder(
                        Component.literal("曲线编辑器 — 渐出（关闭界面）"),
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
                Component.literal("曲线编辑器里可以直接拖动控制点，右边会示范渐入与渐出的效果"),
                this.width / 2, 34, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }
}
