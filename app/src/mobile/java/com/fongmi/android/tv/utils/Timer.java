package com.fongmi.android.tv.utils;

import android.content.Intent;
import android.os.CountDownTimer;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.event.ActionEvent;
import com.fongmi.android.tv.service.PlaybackService;

import java.util.concurrent.TimeUnit;

public class Timer {

    private CountDownTimer timer;
    private Callback callback;
    private long tick;

    private static class Loader {
        static volatile Timer INSTANCE = new Timer();
    }

    public static Timer get() {
        return Loader.INSTANCE;
    }

    public boolean isRunning() {
        return timer != null;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public long getTick() {
        return tick;
    }

    public void set(long t) {
        // 连续设置两次睡眠定时时，旧定时器必须取消：否则它会先到点把播放提前暂停。
        if (timer != null) timer.cancel();
        timer = new CountDownTimer(t, 1000) {
            @Override
            public void onTick(long tick) {
                Timer.this.onTick(tick);
            }

            @Override
            public void onFinish() {
                Timer.this.onFinish();
            }
        }.start();
    }

    private void onTick(long tick) {
        this.tick = tick;
        if (callback != null) callback.onTick(tick);
    }

    private void onFinish() {
        if (callback != null) callback.onFinish();
        if (PlaybackService.isRunning()) sendPause();
        reset();
    }

    private void sendPause() {
        // 睡眠定时到点时应用多半已在后台：Android 8+ 后台 startService 抛 IllegalStateException
        // ⇒ 走前台服务通道（服务本身已在播，通知栏常驻，不会多出一个通知）。
        Intent intent = new Intent(App.get(), PlaybackService.class);
        intent.setAction(ActionEvent.PAUSE);
        try {
            androidx.core.content.ContextCompat.startForegroundService(App.get(), intent);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public void delay() {
        cancel();
        set(TimeUnit.MINUTES.toMillis(5) + tick);
    }

    public void reset() {
        tick = 0;
        cancel();
    }

    public void cancel() {
        if (timer != null) timer.cancel();
        timer = null;
    }

    public interface Callback {

        void onTick(long tick);

        void onFinish();
    }
}
