package com.fongmi.android.tv.ui.custom;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.utils.ResUtil;

public class SpaceItemDecoration extends RecyclerView.ItemDecoration {

    private int spacing;
    private final int spanCount;

    public SpaceItemDecoration(int spacing) {
        this(-1, spacing);
    }

    public SpaceItemDecoration(int spanCount, int spacing) {
        this.spanCount = spanCount;
        this.spacing = ResUtil.dp2px(spacing);
    }

    /**
     * 以<b>已经换算好的像素</b>构造间距 —— 专供「间距必须与卡片宽度同域」的封面网格。
     *
     * <p>背景：卡片宽度由 {@code Product#getSpec} 按 <b>Activity 侧</b> density 算出
     * （{@code BaseActivity#hackResources} 按「屏宽/1920 × 字号倍率 × 大屏补偿」改过），
     * 而 {@link #SpaceItemDecoration(int, int)} 的 dp 重载走 {@code ResUtil.dp2px}，那是
     * <b>Application 侧</b> density —— 两者不等域，档位越高差得越远：卡片按 16dp 预留了间距，
     * 装饰器实际只吃掉「App 侧 16dp」，差额全堆在行尾，表现为「右边空出一条」。</p>
     *
     * <p>调用方用 {@code Product.gap(activityContext)} 换算即可，两端同源。</p>
     *
     * @param spanCount 列数（&lt;= 0 时退化为单行水平排布，与原构造器语义一致）
     * @param spacingPx 间距（px），负值夹到 0
     */
    public static SpaceItemDecoration ofPx(int spanCount, int spacingPx) {
        SpaceItemDecoration decoration = new SpaceItemDecoration(spanCount, 0);
        decoration.spacing = Math.max(0, spacingPx);
        return decoration;
    }

    /**
     * 直接以像素设置间距。电视版搜索键盘的键尺寸由字号档位曲线在运行时算出，
     * 间距必须和键同比例（同样随档位收敛），不能再由 dp 二次换算，所以提供这个像素入口。
     *
     * @return true 表示间距确实变了，调用方需要 invalidateItemDecorations()
     */
    public boolean setSpacingPx(int px) {
        int value = Math.max(0, px);
        if (value == spacing) return false;
        this.spacing = value;
        return true;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position >= 0 && spanCount == -1) {
            outRect.left = position == 0 ? 0 : spacing / 2;
            outRect.right = position == parent.getAdapter().getItemCount() - 1 ? 0 : spacing / 2;
        } else if (position >= 0 && spanCount > 0) {
            int column = position % spanCount;
            outRect.left = column * spacing / spanCount;
            outRect.right = spacing - (column + 1) * spacing / spanCount;
            if (position >= spanCount) outRect.top = spacing;
        } else {
            outRect.left = 0;
            outRect.right = 0;
            outRect.top = 0;
            outRect.bottom = 0;
        }
    }
}