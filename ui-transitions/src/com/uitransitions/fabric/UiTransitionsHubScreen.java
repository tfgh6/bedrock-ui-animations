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
 * 本页是**两个平台共用**的（Fabric 经 ModMenu、NeoForge 经 IConfigScreenFactory
 * 都指到这里），所以页内代码不能带任何单平台的编译期依赖。见 {@link #clothConfigPresent()}。
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

    /**
     * 判断 Cloth Config 是否在场。
     *
     * **必须按类名字符串探测，不能 `import net.fabricmc.loader.api.FabricLoader`。**
     * 本页在两个平台共用，而 Fabric Loader 在 NeoForge 上根本不存在：写成编译期引用后，
     * NeoForge 玩家一点「配置」就会 `NoClassDefFoundError` 崩游戏（1.3.19 发布包的实际缺陷）。
     * 走反射则 Fabric 侧正常解析、NeoForge 侧只得到 `ClassNotFoundException`，不会崩。
     *
     * 另外：本模组的元数据把 Cloth Config 声明为硬前置，所以两个加载器在缺 Cloth 时
     * 更早就已拒绝加载，这里实际上永远是 true —— 保留分支是为了改依赖声明时不用重新想一遍。
     */
    private static boolean clothConfigPresent() {
        try {
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loader.getMethod("getInstance").invoke(null);
            Object present = loader.getMethod("isModLoaded", String.class).invoke(instance, "cloth-config");
            return Boolean.TRUE.equals(present);
        } catch (Throwable t) {
            // 类不存在（NeoForge）或反射被拒：退回"按在场处理"，让按钮可点。
            return true;
        }
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int total = BUTTON_HEIGHT * 3 + GAP * 2;
        int y = Math.max(40, this.height / 2 - total / 2);

        // 配置界面依赖 Cloth Config；曲线编辑器是本模组自带的，不依赖它。
        // 所以没装 Cloth 时依然能进来调曲线，只是第一个按钮换成一行说明。
        if (clothConfigPresent()) {
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
