package com.fongmi.android.tv.utils;

import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 触屏滑动对齐修正（电视版在触屏设备上使用时）：
 * leanback 网格的对齐引擎始终把「选中位置」拉回对齐线。遥控器操作下焦点随按键移动，无感；
 * 触屏则不同 —— 手指把内容滑走后选中位置还停在原地，松手/惯性结束的瞬间列表会被整体拉回
 * 焦点所在处，表现为「向下滑动后漂移回弹」。
 *
 * <p>修法：<b>仅当</b>一次触摸会话内确实发生了滑动（而非轻点）时，滚动停稳后把选中位置
 * 同步到可视区对齐锚点方向第一个「完整可见」的子项 —— 对齐引擎的目标就在眼前，无处可拉，
 * 停哪稳哪。</p>
 *
 * <p><b>兼容性关键</b>：轻点（按下-抬起、无任何滚动）必须立即结束触摸会话、不留下任何状态，
 * 否则标志残留会把后续<b>遥控器按键导航</b>和代码里的程序化滚动都误判成触摸滚动 —— 每次按键
 * 停稳都会把选中位置拽回可视区开头，遥控器彻底失灵。本实现只在「手指按着且本会话真的滚动过」
 * 时才对齐，其它来源的滚动一律跳过。</p>
 */
public final class TouchGridAlign {

    private TouchGridAlign() {
    }

    public static void attach(BaseGridView grid) {
        boolean[] touching = {false};
        boolean[] scrolled = {false};

        grid.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touching[0] = true;
                        scrolled[0] = false;
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // 手指离开时若列表仍在拖动/惯性滚动中，保留会话等它停稳再对齐；
                        // 纯轻点（此刻没有任何滚动）立即结束会话，绝不污染后续遥控器导航。
                        if (rv.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) touching[0] = false;
                        break;
                }
                return false;
            }
        });

        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) {
                    // 拖动/惯性中：只要处于触摸会话，就记下「本会话发生过滚动」
                    if (touching[0]) scrolled[0] = true;
                    return;
                }
                // 停稳：只有「触摸会话且确实滚动过」才对齐。轻点残留、遥控器按键、
                // 代码 scrollToPosition 等任何非触摸来源都不会触发。
                if (touching[0] && scrolled[0]) alignToVisible(grid);
                touching[0] = false;
                scrolled[0] = false;
            }
        });
    }

    /**
     * 把选中位置同步到可视区对齐锚点方向第一个「完整可见」的子项。
     * 纵向取起点对齐线下第一个完整在可视区内的行，横向取最左完整可见项；
     * 避免选到被顶部/左侧裁掉一半的项，否则对齐引擎会再次回拉造成可见跳动。
     */
    private static void alignToVisible(BaseGridView grid) {
        RecyclerView.Adapter<?> adapter = grid.getAdapter();
        if (adapter == null || adapter.getItemCount() == 0) return;
        boolean vertical = grid instanceof VerticalGridView && !(grid instanceof HorizontalGridView);
        int start = vertical ? grid.getPaddingTop() : grid.getPaddingStart();
        int target = RecyclerView.NO_POSITION;
        int first = RecyclerView.NO_POSITION;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            if (child == null) continue;
            int pos = grid.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION) continue;
            if (first == RecyclerView.NO_POSITION || pos < first) first = pos;
            // 对齐锚点方向第一个完整可见：顶部/左侧未被裁剪。
            int offset = vertical ? child.getTop() : child.getLeft();
            if (target == RecyclerView.NO_POSITION && offset >= start) target = pos;
        }
        if (target == RecyclerView.NO_POSITION) target = first;
        if (target != RecyclerView.NO_POSITION && grid.getSelectedPosition() != target) grid.setSelectedPositionSmooth(target);
    }
}
