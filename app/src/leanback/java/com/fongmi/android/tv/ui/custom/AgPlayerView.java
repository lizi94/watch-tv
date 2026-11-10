package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

/**
 * 2026-11-10 R135：播放器渲染层的「纯黑无框」守卫版 PlayerView。
 *
 * <p><b>为什么需要子类：</b>视频用 SurfaceView 渲染时会在窗口上挖一个洞，洞外那一圈
 * 由 View 自己的背景上色。只要 PlayerView 上有任何带 &lt;padding&gt; 的背景
 * （例如 selector_chip_dark 里的 shape_chip_dark_normal：实心 #3A3D44 + 2dp 描边
 * #5A5F6A + 8dp 圆角 + 12dp/8dp padding），就会出现两个症状：
 * ① 四周多出一圈深灰蓝「圆角边框」；② 背景自带的 padding 把画面整体内缩（实测 24/16px）。</p>
 *
 * <p><b>为什么不能只在 initView 里清一次：</b>实测 6.3.7/6.3.8 上，initView 里设成黑色之后
 * 约 300ms，背景又被人换成了 StateListDrawable 并带回 24/16 的内边距。源码里没有任何一处
 * 对 player 调用 setBackground*，所以只能在这一层收口 —— 无论谁、什么时候来设，最终都强制
 * 回到「纯黑 + 零内边距」，并把第一次的调用方堆栈打到 R135 日志里便于定位。</p>
 *
 * <p><b>⚠ 实现忌坑（踩过一次）：</b>Android 9 上 {@code View.setBackground} 内部就是转发到
 * {@code setBackgroundDrawable}，两者若都重写、又在里面调 {@code super.setBackground}，
 * 会在 View 构造函数里形成无限递归直接 StackOverflow。所以这里①只用 {@code apply()} 一个收口；
 * ②加 {@code guarding} 重入保护；③先试 {@code super.setBackgroundDrawable}，若没生效
 * （API 30+ 该方法反过来转发给 setBackground）再用 {@code super.setBackground} 兜底。</p>
 *
 * <p>⚠ 只接管背景与内边距，不动 resize_mode / surface_type / 字幕 / 弹幕等任何媒体行为。</p>
 */
public class AgPlayerView extends PlayerView {

    private static final String TAG = "R135";
    /** 同一个实例最多打几条定位日志，避免真被周期性调用时刷爆 logcat。 */
    private static final int MAX_LOG = 3;

    private boolean guarding;
    private int logged;

    public AgPlayerView(@NonNull Context context) {
        super(context);
    }

    public AgPlayerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public AgPlayerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setBackground(Drawable background) {
        apply(background);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void setBackgroundDrawable(Drawable background) {
        apply(background);
    }

    @Override
    public void setBackgroundResource(int resid) {
        apply(null);
    }

    @Override
    public void setBackgroundColor(int color) {
        apply(null);
    }

    /**
     * 唯一收口：丢弃传入的任意背景，恒定落到纯黑 + 零内边距。
     * {@code guarding} 用于切断 setBackground / setBackgroundDrawable 之间的相互转发。
     */
    @SuppressWarnings("deprecation")
    private void apply(Drawable incoming) {
        if (guarding) return;
        guarding = true;
        try {
            if (incoming != null && !(incoming instanceof ColorDrawable) && logged < MAX_LOG) {
                logged++;
                Log.i(TAG, "player 背景被替换 -> " + incoming.getClass().getName(), new Throwable());
            }
            ColorDrawable black = new ColorDrawable(Color.BLACK);
            // 先走 deprecated 实现：Android 9 及以下它是真正的赋值入口
            super.setBackgroundDrawable(black);
            // Android 10+ 上 setBackgroundDrawable 反过来转发给 setBackground，上面那次会被吞掉，
            // 这里检查一下有没有真的落上，没有就换另一条路再设一次。
            if (getBackground() != black) super.setBackground(black);
            super.setPadding(0, 0, 0, 0);
        } finally {
            guarding = false;
        }
    }
}
