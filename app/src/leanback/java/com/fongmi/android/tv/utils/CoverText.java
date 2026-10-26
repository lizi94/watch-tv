package com.fongmi.android.tv.utils;

import android.widget.TextView;

import com.fongmi.android.tv.setting.Setting;

/**
 * 封面元数据字号曲线（电视版专用）。
 *
 * <p><b>改造背景</b>：封面上的标题 / 角标（站名、年份、评分 remark）原先是「封面宽度 × 固定比例」的
 * <b>绝对像素</b>字号，绕过 scaledDensity，因此完全不跟随「字号档位」；更糟的是
 * {@link com.fongmi.android.tv.Product#getSpec} 的行距占位项
 * （{@code dp2px(48) + dp2px(16 × (column - 1))}）随 density 一起放大，
 * 档位越高封面反而越窄 —— 两个力叠加，档位 3 的字号比档位 0 还小约 6%（方向相反）。</p>
 *
 * <p><b>曲线</b>：在「封面宽度 × 比例」之上再乘一个档位增益，使字号随档位单调变大：</p>
 * <pre>
 *   字号 = 封面宽度 × 比例 × (1 + 进度 × (最大增益 - 1))
 *   进度 = PROGRESS[档位]        // 见下表
 * </pre>
 *
 * <p>标题斜率 1.8（老人主要看片名），角标斜率 1.5（顶行角标要与封面宽度博弈，放大过猛会横向溢出）。
 * 前 4 档的进度就是原实现在同一档位上的 {@code (fontScale - 1.2) / 0.6} 取值，
 * <b>所以 0～3 档逐像素等于改造前的值</b>，不存在低档位回归；第 5 档「极大」再进 1.67 个标准步。</p>
 *
 * <p>实测（1920 宽 / 6 列 / 无大屏补偿）：标题 16 → 20 → 24 → 28 → 36px，
 * 角标 13 → 15 → 17 → 19 → 23px。列数越少封面越大、字号随之变大
 * （4 列：标题 25 → 31 → 37 → 43 → 55px），即「跟随图片大小档位」。</p>
 */
public final class CoverText {

    /** 标题字号 ÷ 封面宽度。 */
    private static final float TITLE_RATIO = 0.055f;
    /** 角标（站名 / 年份 / 评分 remark）字号 ÷ 封面宽度。 */
    private static final float SUB_RATIO = 0.045f;

    /** 标题在最高档位的增益。 */
    private static final float TITLE_GROW = 1.8f;
    /** 角标在最高档位的增益。 */
    private static final float SUB_GROW = 1.5f;

    /**
     * 各字号档位的曲线进度（下标 = {@link Setting#getFontSize()}）。
     *
     * <p>前 4 项刻意写成原实现里各档倍率的 {@code (fontScale - 1.2) / 0.6}，保证与上一版逐位等价；
     * 第 5 项是新增的「极大」档，再进 1.67 个标准步 —— 老人对封面上字号的感知偏弱，末档特意给足。</p>
     */
    private static final float[] PROGRESS = {0f, 0.2f / 0.6f, 0.4f / 0.6f, 1f, 5f / 3f};

    /** 评分角标自身左右内边距之和（shape_vod_remark 各 4dp）。 */
    private static final int REMARK_CLAMP_DP = 8;
    /** 站名角标右对齐，需扣掉外边距（ag_space_2 = 8dp，镜像两侧 16dp）+ 自身内边距（4dp × 2）。 */
    private static final int SITE_CLAMP_DP = 24;

    private CoverText() {
    }

    /** 封面标题字号（px）。 */
    public static int title(int coverWidth) {
        return px(coverWidth, TITLE_RATIO, TITLE_GROW);
    }

    /** 封面角标字号（px）：站名 / 年份 / 评分（remark）。 */
    public static int sub(int coverWidth) {
        return px(coverWidth, SUB_RATIO, SUB_GROW);
    }

    /**
     * 把封面角标横向框在封面宽度内，超出即裁切，避免溢到相邻列。
     *
     * <p>只在内容确实超长时生效（站名超过约 12 个中文字、评分角标超过约 17 个中文字）。
     * 常规的「泥巴 / 9.2 / 1080p / 更新至24集」永远够用，不会触发。</p>
     *
     * <p>用 {@code setMaxWidth} 而非改 XML：不动任何布局文件，纯 Java 内部约束，
     * 避免影响已经稳定的版式。</p>
     *
     * @param remark     评分角标（{@code layout_above} 标题、wrap_content、无水平约束），可为 null
     * @param site       站名角标（{@code alignParentEnd|Top}），可为 null
     * @param coverWidth 封面宽度（px）
     */
    public static void clamp(TextView remark, TextView site, int coverWidth) {
        if (coverWidth <= 0) return;
        if (remark != null) remark.setMaxWidth(Math.max(0, coverWidth - ResUtil.dp2px(REMARK_CLAMP_DP)));
        if (site != null) site.setMaxWidth(Math.max(0, coverWidth - ResUtil.dp2px(SITE_CLAMP_DP)));
    }

    private static int px(int coverWidth, float ratio, float growMax) {
        if (coverWidth <= 0) return 0;
        return Math.round(coverWidth * ratio * (1f + progress() * (growMax - 1f)));
    }

    /** 档位进度：按下标查表，越界夹取（以后继续加档位也不会越界）。 */
    private static float progress() {
        return PROGRESS[Math.clamp(Setting.getFontSize(), 0, PROGRESS.length - 1)];
    }
}
