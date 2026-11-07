package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.os.HandlerCompat;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.utils.BootSession;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.hook.DelegateHook;
import com.fongmi.hook.Hook;
import com.github.catvod.Init;
import com.google.gson.Gson;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    /**
     * 「进程已被系统拉起」的包内预热广播（2026-10-30 新增 / R103）。
     *
     * <p><b>为什么需要</b>：部分厂商固件会拦掉第三方应用的 {@code BOOT_COMPLETED}，
     * 但系统仍会主动绑定本应用的媒体库服务（{@code MediaLibraryService}，见 {@code PlaybackService}）。
     * 绑定发生时进程已经被拉起，正好是「抢界面」的时机。{@code PlaybackService} 在 {@code onCreate}
     * 里发出这条广播，电视端的 {@code BootReceiver} 收到后走统一的开机拉起流程。</p>
     *
     * <p><b>为什么是包内广播</b>：发送时用 {@code setPackage(getPackageName())} 限定本应用，
     * 外部应用无法伪造/监听；且显式指定 package 的广播不受 Android 8+ 隐式广播限制。
     * 手机端（{@code mobile} flavor）没有接收者，发送方已用 {@code BuildConfig.FLAVOR} 跳过。</p>
     *
     * <p><b>为什么用 {@code APPLICATION_ID} 拼接而不是写字面量</b>：Manifest 里声明的是
     * {@code ${applicationId}.action.BOOT_PREHEAT}，两边必须严格一致；写死字面量在将来
     * 改包名（例如做双版本共存加后缀）时会静默失配，且这类失配极难排查。与
     * {@code PlaybackService#LOCAL_BIND_ACTION} 保持同一种写法。</p>
     */
    public static final String ACTION_PREHEAT = BuildConfig.APPLICATION_ID.concat(".action.BOOT_PREHEAT");

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
        // R124：记下「本次进程是在开机后多久启动的」。开机阶段的判据（默认桌面被系统用 HOME 拉起、
        // 开机自启被 BootLauncher 拉起）都靠它，替代了 R123 那条在待机唤醒时必然判错的
        // 「elapsedRealtime < 90 秒」绝对时间窗（elapsedRealtime 包含深睡）。
        BootSession.onProcessStart();
        registerScreenOff();
        registerScreenOn();
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

    /**
     * 监听系统熄屏（R124）。
     *
     * <p><b>为什么必须在这里（App）而不是首页</b>：系统熄屏时窗口会先失去可见性
     * （首页走 {@code onPause → onStop}），若把监听器绑在 Activity 生命周期上，onStop 时就注销了，
     * 永远收不到熄屏时刻 ⇒「待机唤醒算不算开机」这条判据整个失效。</p>
     *
     * <p><b>为什么用 {@code RECEIVER_EXPORTED}</b>（R125 更正注释）：要收到<b>系统</b>发出的广播，
     * 注册时就必须用 EXPORTED；用 NOT_EXPORTED 注册的接收器收不到系统广播。
     * 这里安全的原因是 {@code ACTION_SCREEN_OFF} 属于 <b>protected broadcast</b> ——
     * 只有系统能发，第三方应用伪造不了，所以「导出」并不等于「谁都能指挥本应用」。
     * （R124 的注释写的是「系统保护广播，用 NOT_EXPORTED 注册收不到」，把因果说反了：
     * 恰恰是要 EXPORTED 才收得到。）</p>
     */
    private void registerScreenOff() {
        try {
            ContextCompat.registerReceiver(this, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    BootSession.onScreenOff();
                }
            }, new IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_EXPORTED);
        } catch (Throwable e) {
            android.util.Log.w("BootSession", "监听熄屏失败", e);
        }
    }

    /**
     * 监听系统亮屏（R134）。
     *
     * <p><b>为什么需要</b>：{@code isWakeSession()} 的「待机时长」= 亮屏时刻 − 熄屏时刻，
     * 「唤醒新鲜度」= 当前时刻 − 亮屏时刻。没有亮屏时刻，持久化的熄屏快照既算不出待机多久，
     * 也挡不住「进程在亮屏状态下被杀、很久后按主页键」的误判。注册方式与熄屏相同：
     * {@code ACTION_SCREEN_ON} 同样是系统 protected broadcast，必须 EXPORTED 才收得到；
     * 在 App 注册（而非 Activity）才能保证收屏时一定活着。</p>
     */
    private void registerScreenOn() {
        try {
            ContextCompat.registerReceiver(this, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    BootSession.onScreenOn();
                }
            }, new IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_EXPORTED);
        } catch (Throwable e) {
            android.util.Log.w("BootSession", "监听亮屏失败", e);
        }
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

    /**
     * 系统内存吃紧时主动收缩图片缓存（2026-10-26 低配电视卡死治理 P0-2）。
     *
     * <p><b>为什么必须有它</b>：此前全工程没有任何 onTrimMemory 实现 —— 低配电视上进入
     * 详情页 / 播放页把内存顶到上限后，系统只能走 LMK 直接杀进程，用户看到的就是
     * 「卡死 → 黑屏 → 回到首页」。响应本回调后，Glide 会先收缩资源缓存与位图池，
     * 封面重新联网加载一次即可，比整个进程被杀温和得多。</p>
     *
     * <p>{@code TRIM_MEMORY_RUNNING_*} 是「本进程在前台但系统内存不足」；
     * {@code TRIM_MEMORY_BACKGROUND/UI_HIDDEN} 是「本进程已退到后台」，此时更应清。
     * {@code trimMemory} 必须在主线程调用（本回调本身就在主线程）。</p>
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_RUNNING_MODERATE && level < TRIM_MEMORY_BACKGROUND) return;
        try {
            Glide.get(this).trimMemory(level);
        } catch (Throwable ignored) {
        }
    }
}