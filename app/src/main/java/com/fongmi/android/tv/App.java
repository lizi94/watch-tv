package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.hook.DelegateHook;
import com.fongmi.hook.Hook;
import com.github.catvod.Init;
import com.google.gson.Gson;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private Activity activity;
    /** 全局转发代理：覆写 Android 13+ 新增具体方法，转发系统实现，避免基类抛 UnsupportedOperationException 崩溃。 */
    private Hook global;

    public App() {
        instance = this;
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Init.set(base);
        // 挂载转发代理：Android 13+ 的具体方法（Flags 重载）基类默认实现抛异常，必须转发系统实现防崩溃
        global = new DelegateHook(base.getPackageName(), base.getPackageManager());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.createChannel();
        registerActivityLifecycleCallbacks(this);
        // 后台预热数据库：首次安装 / 版本升级后的首启要建库并跑 5 个 Migration
        // （MIGRATION_31_32 含 History 全表复制），慢闪存上可达 200ms~2s，原来会在首页首个查询处
        // 同步发生、正好压在首帧之前。这里提前到后台线程建库，首帧不再等它。
        // Room 对同一数据库有内部锁，与主线程的首次访问并发是安全的；失败也不影响后续（下次访问会重试）。
        new Thread(() -> {
            try {
                AppDatabase.get();
            } catch (Exception ignored) {
            }
        }, "db-warmup").start();
    }

    @Override
    public PackageManager getPackageManager() {
        if (global != null) return global;
        return getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }
}