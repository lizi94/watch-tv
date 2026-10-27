package com.fongmi.android.tv.ui.custom;

import android.annotation.SuppressLint;
import android.util.DisplayMetrics;

import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.RowPresenter;

import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.TouchGridAlign;

public class CustomRowPresenter extends ListRowPresenter {

    private final int spacing;
    private final int strategy;

    /**
     * 默认<b>关闭</b> leanback 的焦点放大高亮（{@link FocusHighlight#ZOOM_FACTOR_NONE}）。
     *
     * <p><b>为什么全站统一关闭（2026-10-12 定案）</b>：焦点反馈统一为「3dp 蓝色焦点框」
     * （{@code ag_stroke_focus} + 各 {@code shape_*_focused}），不再叠加缩放，理由是缩放会在
     * 卡片比邻座之间 16dp 的间距更大时越界压住邻居 —— 而媒体封面是 3:4 竖版，
     * 卡片高 ≈ 1.4 倍宽，放大时上下外扩量是左右的 1.33 倍，4 列档位下卡片高约 654dp，
     * 单侧上下外扩 {@code 0.05 × 654 ≈ 32.7dp}，远超 16dp。</p>
     *
     * <p>关闭后四周间距恒为 16dp、横竖一致；放大带来的"醒目感"由焦点框承担，
     * 与详情页海报框（{@code shape_video_focus_ring}，同一 {@code ag_primary} + {@code ag_stroke_focus}）、
     * 设置项、按钮完全同一套观感。</p>
     *
     * <p>调用点可继续显式传 {@link FocusHighlight#ZOOM_FACTOR_NONE}：写法冗余，
     * 但能在有人改动本默认值时防止回归。</p>
     */
    public CustomRowPresenter(int spacing) {
        this(spacing, FocusHighlight.ZOOM_FACTOR_NONE);
    }

    @SuppressLint("RestrictedApi")
    public CustomRowPresenter(int spacing, int focusZoomFactor) {
        this(spacing, focusZoomFactor, HorizontalGridView.FOCUS_SCROLL_ITEM);
    }

    public CustomRowPresenter(int spacing, int focusZoomFactor, int strategy) {
        super(focusZoomFactor);
        this.spacing = spacing;
        this.strategy = strategy;
        setShadowEnabled(false);
        setSelectEffectEnabled(false);
        setKeepChildForeground(false);
    }

    @Override
    @SuppressLint("RestrictedApi")
    protected void initializeRowViewHolder(RowPresenter.ViewHolder holder) {
        super.initializeRowViewHolder(holder);
        ViewHolder vh = (ViewHolder) holder;
        vh.getGridView().setFocusScrollStrategy(strategy);
        // 行间距必须与「本行所在页面的 XML dp」同源，所以取 View 自己的 Resources，不要用全局
        // ResUtil 的无参重载 —— 那走的是 Application 侧 Resources，历史上会被 AndroidAutoSize
        // 的 InitProvider / 生命周期回调覆写成「屏宽/1920」（不含字号档位与大屏补偿）。后果是
        // 首帧创建的功能行、分类 chips 行间距和其余行不一致，重建一次才恢复。
        // 写法与 CustomKeyboard#density() 一致。
        DisplayMetrics metrics = ResUtil.getDisplayMetrics(vh.getGridView().getContext());
        vh.getGridView().setHorizontalSpacing(Math.round(spacing * metrics.density));
        // 触屏滑动停稳修正（详见 TouchGridAlign），遥控器路径不受影响
        TouchGridAlign.attach(vh.getGridView());
    }
}