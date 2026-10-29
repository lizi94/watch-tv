package com.fongmi.android.tv.utils;

import android.widget.TextView;

import com.fongmi.android.tv.App;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class Clock {

    private DateTimeFormatter format;
    private Callback callback;
    private TextView view;
    private Timer timer;

    public static Clock create() {
        return new Clock();
    }

    public static Clock create(TextView view) {
        return new Clock().view(view).format("HH:mm:ss");
    }

    public Clock() {
    }

    public Clock view(TextView view) {
        this.view = view;
        return this;
    }

    public Clock format(String format) {
        this.format = DateTimeFormatter.ofPattern(format, Locale.getDefault());
        return this;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /**
     * 启动走秒。
     *
     * <p><b>2026-10-29（R99）：把 start() 做成幂等</b> —— 先 cancel 掉上一个 Timer 再新建。
     * 原实现直接 {@code timer = new Timer()} 覆盖字段：一旦有调用点忘记先 {@code stop()}，
     * 老的 Timer 线程与其 {@link TimerTask} 就再也无法停止（引用被丢掉），于是多条定时链
     * **同时**每秒 {@code App.post} 一次、同时往同一个 TextView 写时间。</p>
     *
     * <p>⚠ 现状核对（不要据此认为已经存在线上泄漏）：本仓库所有调用点目前都是成对的 ——
     * {@code HomeActivity} 在 {@code onResume()} 里 start、{@code onPause()} 里 stop；
     * {@code LiveActivity}/{@code CastActivity} 显式写 {@code stop().start()}。
     * 所以这是一次**防御性加固**（让「忘记 stop」这种失误不再产生隐蔽的常驻定时链），
     * 而不是在修一个已存在的 bug。{@code stop()} 自身对 null 安全，重复 cancel 无副作用。</p>
     */
    public void start() {
        stop();
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                App.post(() -> doJob());
            }
        }, 0, 1000);
    }

    private void doJob() {
        try {
            long time = System.currentTimeMillis();
            if (callback != null) callback.onTimeChanged(time);
            if (view != null) view.setText(format.format(LocalDateTime.now()));
        } catch (Exception ignored) {
        }
    }

    public Clock stop() {
        if (timer != null) timer.cancel();
        return this;
    }

    public void release() {
        if (timer != null) timer.cancel();
        if (callback != null) callback = null;
    }

    public interface Callback {

        void onTimeChanged(long time);
    }
}
