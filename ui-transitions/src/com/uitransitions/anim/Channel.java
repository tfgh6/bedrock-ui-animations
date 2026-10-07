package com.uitransitions.anim;

/**
 * 颜色通道 —— 显式取代现有实现里那个**全局** {@code PREMULTIPLIED} 标志。
 *
 * <h2>它修的是什么</h2>
 *
 * 现有 {@code UiTransitions.modulate} 读一个 ThreadLocal 标志决定"要不要连 RGB 一起缩放"，
 * 而该标志由 {@code beginItemSubmit} 置位、{@code endItemSubmit} 复位。
 * 于是**物品提交窗口内画出的任何文字/矩形**都会被当成预乘颜色 —— 现在没暴露只是因为
 * 恰好没人在那个窗口里画文字。通道化之后，"要不要预乘"跟着**颜色属于哪条通道**走，
 * 与"此刻谁在压栈"无关。
 *
 * <h2>为什么每个通道带一个 {@link AlphaSource}</h2>
 *
 * 现有实现里每条通道取 alpha 的来源不同，而且这些差异**都是有意的**（每条都对应一次真实故障）：
 * <ul>
 *   <li>{@link #BLIT} / {@link #RECT} —— 取窗口透明度（现在叫 {@code WINDOW_ALPHA}）</li>
 *   <li>{@link #TEXT} —— 取**文字**透明度（{@code TEXT_ALPHA}），与物品/矩形分开，
 *       这样"文字"能配一条自己的曲线</li>
 *   <li>{@link #ITEM} / {@link #PIP} —— 取**帧级**透明度（{@code PIP_FRAME_ALPHA}），
 *       因为它们在渲染阶段才提交，那时提取阶段的帧级值已被复位</li>
 * </ul>
 * 把 {@code AlphaSource} 做成枚举构造参数，迁移时只要把"谁读哪个 ThreadLocal"填进去即可，
 * 通道定义本身与旧状态**零耦合**。
 */
public enum Channel {

    /** 贴图块（底板、图标）。只调 alpha。 */
    BLIT(false, AlphaSource.WINDOW),

    /** 纯色块（格子高亮、分隔线、部分底板）。只调 alpha。 */
    RECT(false, AlphaSource.WINDOW),

    /**
     * 文字（含文字背景、聊天栏文字）。只调 alpha。
     *
     * <p><b>永不预乘</b> —— 这正是本轮修掉的那处：旧实现里它会被全局标志带着走。
     */
    TEXT(false, AlphaSource.TEXT),

    /** 物品图集提交（提取阶段登记、提交阶段套用）。预乘：RGB 必须一起缩放。 */
    ITEM(true, AlphaSource.FRAME_PRESENTED),

    /** 画中画贴回（布娃娃 / 附魔书 / 地图 / 旗帜）。预乘。 */
    PIP(true, AlphaSource.FRAME_PRESENTED);

    /** alpha 的来源。迁移期用它把旧的 ThreadLocal 接进来；迁完之后由帧上下文直接给值。 */
    public enum AlphaSource {
        WINDOW,
        TEXT,
        FRAME_PRESENTED
    }

    private final boolean premultiplied;
    private final AlphaSource source;

    Channel(boolean premultiplied, AlphaSource source) {
        this.premultiplied = premultiplied;
        this.source = source;
    }

    /** 该通道的颜色是否来自预乘 alpha 管线。 */
    public boolean premultiplied() {
        return this.premultiplied;
    }

    /** 该通道的透明度来源。 */
    public AlphaSource alphaSource() {
        return this.source;
    }
}
