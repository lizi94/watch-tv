package com.fongmi.android.tv;

import android.content.Context;
import android.os.Build;
import android.view.DisplayCutout;

import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;

public class Product {

    public static int getDeviceType() {
        return 1;
    }

    /**
     * 每行封面列数。
     *
     * <p>列数同时是 {@code GridLayoutManager} 与 {@code SpaceItemDecoration} 的 spanCount，
     * 为 0 会直接抛异常。档位已被 {@link PlayerSetting#getSize()} 夹在 0~3，正常路径恒为正，
     * 这里加下限保护只为防「偏好被外部写坏 / 以后调整档位表」。</p>
     */
    public static int getColumn(Context context) {
        int count = ResUtil.isLand(context) ? 7 : 5;
        count = count + (ResUtil.isPad() ? 1 : 0);
        return Math.max(1, Math.abs(PlayerSetting.getSize() - count));
    }

    /** 横版封面少一列，同样带下限保护。 */
    public static int getColumn(Context context, Style style) {
        int column = getColumn(context);
        return style.isLand() ? Math.max(1, column - 1) : column;
    }

    public static int[] getSpec(Context context) {
        return getSpec(context, Style.rect());
    }

    public static int[] getSpec(Context context, Style style) {
        int column = getColumn(context, style);
        int space = ResUtil.dp2px(32) + ResUtil.dp2px(16 * (column - 1)) + getCutout(context);
        if (style.isOval()) space += ResUtil.dp2px(column * 16);
        return getSpec(context, space, column, style);
    }

    /**
     * 由「行距占位 + 列数」算出单个卡片尺寸（px）。
     *
     * <p>下限保护：行距占位里含 {@code dp2px} 项与挖孔安全区，屏宽读取异常（0）或
     * 「极窄屏 + 挖孔 + 少列」叠加时 {@code base / column} 可能落到 0 或负值，
     * 写进 {@code LayoutParams} 即非法尺寸。这里统一兜到「至少 1px」。</p>
     */
    private static int[] getSpec(Context context, int space, int column, Style style) {
        column = Math.max(1, column);
        int base = ResUtil.getScreenWidth(context) - space;
        int width = Math.max(1, base / column);
        float ratio = style.getRatio();
        if (!(ratio > 0f)) ratio = 0.75f;   // getRatio() 已归一化到 0.75~1.78，此判断仅防御非法输入
        int height = Math.max(1, (int) (width / ratio));
        return new int[]{width, height};
    }

    private static int getCutout(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0;
        DisplayCutout cutout = ResUtil.getDisplay(context).getCutout();
        if (cutout == null) return 0;
        int left = cutout.getSafeInsetLeft();
        int right = cutout.getSafeInsetRight();
        return left | right;
    }
}
