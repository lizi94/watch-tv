package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.VerticalGridView;

import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.TouchGridAlign;

/**
 * 分类页与搜索结果页共用的海报网格。
 *
 * <p>2026-10-12 定案：<b>移除「收起 header」机制</b>。原先在构造期注册的
 * {@code OnChildViewHolderSelectedListener} 会在「焦点进入网格后按向下走到第 2 行」时把
 * {@code setHeader(...)} 传进来的视图（Activity 级的结果标题 + 站源行）设成 {@code GONE}，
 * 按向上回到第 1 行再设回 {@code VISIBLE}。这两个视图是 {@code pager} 的上方兄弟节点，
 * {@code GONE} 会让高度归零、下方海报网格整体上移，{@code VISIBLE} 又整体下移 ——
 * 表现为「站源行与封面列表第一行的上下间距在切换站源时跳变」。
 *
 * <p>改为方案「恒显」：结果标题与站源行始终显示，网格位置恒定（零位移、零留白），
 * 站源也随时可见可切。因此 {@code hideHeader}/{@code showHeader}/{@code isHeaderVisible}
 * 与 {@code pressUp}/{@code pressDown} 两个粘滞标志一并删除。
 *
 * <p>保留的能力：网格内按 BACK 先把列表滚回顶部（{@code moveToTop}），再按一次才退出页面 ——
 * 与改动前一致（原实现里的 {@code requestFocus()} 目标是 Activity 的站源行，header 收起时
 * 该视图是 GONE 本来就拿不到焦点，故「焦点留在网格」是既有行为，未回归）。
 */
public class CustomVerticalGridView extends VerticalGridView {

    private boolean moveTop;

    public CustomVerticalGridView(@NonNull Context context) {
        this(context, null);
    }

    public CustomVerticalGridView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CustomVerticalGridView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setMoveTop(true);
        TouchGridAlign.attach(this);
    }

    public void setMoveTop(boolean moveTop) {
        this.moveTop = moveTop;
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if (!KeyUtil.isActionDown(event)) return super.dispatchKeyEvent(event);
        if (KeyUtil.isBackKey(event)) return moveTop && moveToTop();
        return super.dispatchKeyEvent(event);
    }

    public boolean moveToTop() {
        if (getSelectedPosition() == 0 || getAdapter() == null || getAdapter().getItemCount() == 0) return false;
        scrollToPosition(0);
        return true;
    }
}
