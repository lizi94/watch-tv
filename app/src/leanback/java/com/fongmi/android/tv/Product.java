package com.fongmi.android.tv;

import android.content.Context;
import android.util.DisplayMetrics;

import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;

public class Product {

    public static int getDeviceType() {
        return 0;
    }

    /**
     * 每行封面列数（4~7）。
     *
     * <p><b>列数下限保护（必须保留）</b>：列数是「{@code GridLayoutManager} 的 spanCount」与
     * 「{@code SpaceItemDecoration} 的 spanCount」两处的除数，一旦为 0 会直接抛
     * {@code IllegalArgumentException / ArithmeticException}。档位本身已被
     * {@link PlayerSetting#getSize()} 夹在 0~3，正常路径恒为 4~7，这里的 {@code Math.max(1, …)}
     * 只是防「偏好被外部写坏 / 以后加档位」这类非常规输入。</p>
     */
    public static int getColumn() {
        return Math.max(1, Math.abs(PlayerSetting.getSize() - 7));
    }

    /** 横版封面（比例 &gt; 1）少一列，同样带下限保护 —— 横版恒 ≥ 1 列。 */
    public static int getColumn(Style style) {
        int column = getColumn();
        return style.isLand() ? Math.max(1, column - 1) : column;
    }

    public static int[] getSpec(Context context, Style style) {
        int column = getColumn(style);
        return getSpec(screenWidth(context), space(context, column, style), column, style);
    }

    /**
     * 屏宽（px）：取 <b>Activity 侧</b> metrics 的 {@code widthPixels}。
     *
     * <p><b>⛔ 为什么不能用 {@code ResUtil.getScreenWidth()}（2026-10-28 修「大屏少一列」）</b>：
     * 那个无参重载走 {@code App.get()}，内部靠 {@code isLand(App)} 决定「取长边还是短边」。
     * 而 orientation 来自 <b>Application 侧</b> configuration（系统全局配置），<b>不保证跟随
     * Activity 的 {@code sensorLandscape}</b> —— 部分电视的系统默认 orientation 就是 portrait。
     * 一旦被判成竖屏，它返回的是<b>短边</b>：1080p 机器返回 1080、4K 机器返回 2160。</p>
     *
     * <p>后果：{@code base = 短边 − space} 算出的封面宽度只有应有值的 56% 左右 —— 一行 4 个封面
     * 加起来只占屏宽的 2/3，<b>右边空出一大块，看起来就像少排了一列</b>；功能区
     * （{@code FuncPresenter}）用的是同一个错基线，于是整块内容一起缩在左侧。
     * 这正解释了现象为何只在「某些大屏设备」上出现 —— 取决于该机型 Application 的 orientation 是否同步。</p>
     *
     * <p>Activity 侧 {@code metrics.widthPixels} 是当前窗口的真实像素宽（TV 恒横屏），
     * 且 {@code BaseActivity#hackResources()} 只改写 density / scaledDensity、<b>不动 widthPixels</b>，
     * 因此它与 XML 里 dp 的换算同源，也与 {@link #space} 处在同一个 density 域。</p>
     */
    private static int screenWidth(Context context) {
        return Math.max(1, ResUtil.getDisplayMetrics(context).widthPixels);
    }

    /**
     * 行内边距与列间距的占位宽度（px）——<b>必须与功能区严格同源，否则每行封面都无法与功能区对齐</b>。
     *
     * <p><b>2026-10-16 修复「封面行与功能区不对称」</b>：本方法原先用 {@code ResUtil.dp2px(48)} /
     * {@code ResUtil.dp2px(16 * …)}，而 {@code ResUtil.dp2px} 走的是 {@code App.get()} 的 Resources ——
     * 那是 <b>Application 侧 density</b>，<b>不</b>随「字号大小」档位与屏幕补偿缩放。
     * 而功能区（{@code FuncPresenter}）与行间距（{@code CustomRowPresenter}）早已刻意改用
     * {@code ResUtil.getDisplayMetrics(parent.getContext())} —— <b>Activity 侧 density</b>
     * （{@code BaseActivity#hackResources()} 按 {@code 屏宽/1920 × 字号倍率(1.2~2.0) × 大屏补偿} 缩放过）。
     *
     * <p>结果：档位越高，封面行的左右边距与列间距相对越被压缩，与功能区错开。
     * 1080p + 补偿×1.17 下档位 4 时 {@code d_Activity ≈ d_App × 1.4}，
     * 封面行左右各比功能区少缩进约 13dp，<b>合计错开约 26dp</b>；档位 0 时几乎看不出，
     * 所以现象是「无论什么档位都对不齐，只是档位越高越明显」。
     *
     * <p>因此这里也取 {@code Context} 的 Resources 密度，与那两处严格同源。</p>
     */
    private static int space(Context context, int column, Style style) {
        DisplayMetrics metrics = ResUtil.getDisplayMetrics(context);
        int space = Math.round(48 * metrics.density) + Math.round(16 * (column - 1) * metrics.density);
        if (style.isOval()) space += Math.round(column * 16 * metrics.density);
        return space;
    }

    /**
     * 由「屏宽 + 行距占位 + 列数」算出单个封面尺寸（px）。
     *
     * <p>{@code screenWidth} 必须由调用方传入 <b>Activity 侧</b>的真实窗口宽（见 {@link #screenWidth}）——
     * 不要再在方法内部自己取，否则又会退回「基线与 space 不同域」的老坑。</p>
     *
     * <p><b>下限保护（必须保留）</b>：字号档位会把全局 density 放大到 2 倍（极大字号），
     * 大屏补偿再乘最多 1.6 倍，{@code space} 随之变大。正常情况 {@code space} 与屏宽同比例增长、
     * 算出的尺寸稳定，但屏宽读取异常（返回 0）或「小屏 + 高密度 + 少列」叠加时，
     * {@code base / column} 可能落到 0 或负值，再写进 {@code LayoutParams.width/height}
     * 就是非法尺寸 → 布局/测量异常。因此这里统一兜到「至少 1px」。</p>
     */
    public static int[] getSpec(int screenWidth, int space, int column, Style style) {
        column = Math.max(1, column);
        int base = Math.max(1, screenWidth - space);
        int width = Math.max(1, base / column);
        float ratio = style.getRatio();
        if (!(ratio > 0f)) ratio = 0.75f;   // getRatio() 已归一化到 0.75~1.78，此判断仅防御非法输入
        int height = Math.max(1, (int) (width / ratio));
        return new int[]{width, height};
    }
}
