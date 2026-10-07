package com.uitransitions.anim;

/**
 * 基岩版原版的过渡参数 —— 从**真实的基岩版资源包**里读出来的数值，不是推测。
 *
 * <p>来源：基岩版原版资源包 {@code ui/ui_common.json} 与 {@code ui/_global_variables.json}
 * （镜像仓库 {@code ZtechNetwork/MCBVanillaResourcePack}）。原样摘录：
 *
 * <pre>
 * "container_screen_exit_animation_push": {
 *   "anim_type": "offset", "easing": "out_cubic",
 *   "duration": "$container_transition_time_push",
 *   "from": [ 0, 0 ], "to": [ 0, "50%" ] },
 * "container_screen_entrance_animation_push": {
 *   "anim_type": "offset", "easing": "out_cubic",
 *   "duration": "$container_transition_time_push",
 *   "from": [ 0, "50%" ], "to": [ 0, 0 ] }
 * </pre>
 *
 * <p>以及 {@code _global_variables.json} 里的时长（单位：秒）：
 * {@code $container_transition_time_push = 0.4}、{@code $container_transition_time_pop = 0.4}。
 *
 * <h2>对照本项目当前的默认值</h2>
 *
 * <table>
 *   <tr><th></th><th>基岩版原版</th><th>本项目当前</th></tr>
 *   <tr><td>时长</td><td><b>400ms</b></td><td>500ms（{@code DEFAULT_DURATION_MS}）</td></tr>
 *   <tr><td>位移方向</td><td>垂直：{@code [0,"50%"] ↔ [0,0]}</td><td>同（开自下而上、关向下）✓</td></tr>
 *   <tr><td>缓动</td><td>{@code out_cubic}（位移与透明度都是）</td><td>{@code cubic} ✓</td></tr>
 *   <tr><td>面板与内容</td><td>同一个动画作用在包装元素上、{@code propagate_alpha: true}</td><td>两层各自包平移 ✓</td></tr>
 *   <tr><td>遮罩</td><td>独立的一套，{@code easing: linear}，底衬 {@code $fill_alpha} 默认 <b>0.8</b></td><td>遮罩独立且默认静止 ✓</td></tr>
 * </table>
 *
 * <p><b>两处已知差异</b>：
 * <ol>
 *   <li><b>时长</b>：基岩 400ms vs 本项目 500ms —— 属可调参数，若要"基岩手感有据可依"建议改 400。</li>
 *   <li><b>位移量</b>：基岩是**屏幕高度的 50%**（百分比，随分辨率缩放），本项目是**固定像素**（默认 120px）。
 *       两者在 720p 下接近，在超宽屏或竖屏下会明显分叉。若要完全对齐，
 *       需要把 {@code offset} 改成"可用高度 × 比例"。</li>
 * </ol>
 *
 * <p><b>另外两点值得知道</b>：
 * <ul>
 *   <li>社区流传的"约 250ms"是**误传** —— 那个 0.25 是 {@code $transition_time_pop_size}，
 *       用于通用界面的**缩放**动画，不是容器滑动。</li>
 *   <li>基岩的动画由**官方 JSON UI 动画系统**驱动（元素上的 {@code anims} + {@code play_event}），
 *       Java 版没有对应 API —— 这反证了本项目用 Mixin + 矩阵平移是**唯一可行**的路，
 *       不是取巧。</li>
 * </ul>
 */
public final class BedrockDefaults {

    /** 容器开/关时长（毫秒）：{@code $container_transition_time_push/pop = 0.4} 秒。 */
    public static final int CONTAINER_DURATION_MS = 400;

    /** 位移方向与缓动：{@code out_cubic}（即 {@link NamedEasing#CUBIC} 的缓出方向）。 */
    public static final NamedEasing CONTAINER_EASING = NamedEasing.CUBIC;

    /** 滑入/滑出量占可用高度的比例：{@code from [0,"50%"] → to [0,0]}。 */
    public static final float SLIDE_FRACTION = 0.5F;

    /** 遮罩底衬的默认不透明度：{@code common_dialogs.full_screen_background} 的 {@code $fill_alpha}。 */
    public static final float VEIL_FILL_ALPHA = 0.8F;

    /** 遮罩的缓动是 **linear**，与面板的 {@code out_cubic} 不同 —— 这条差异正是"基岩手感"的一部分。 */
    public static final NamedEasing VEIL_EASING = NamedEasing.LINEAR;

    private BedrockDefaults() {
    }

    /**
     * 按可用高度算位移像素 —— 与基岩的百分比语义对齐。
     *
     * <p>例：720p 逻辑高度 240（GUI 缩放 3 档）时返回 120，正好等于本项目当前默认值。
     */
    public static float slidePixels(int availableHeight) {
        return availableHeight * SLIDE_FRACTION;
    }
}
