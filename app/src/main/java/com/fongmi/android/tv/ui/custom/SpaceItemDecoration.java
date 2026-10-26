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