package com.fongmi.android.tv.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.utils.BootLauncher;

/**
 * 开机拉起界面（2026-10-29 新增，2026-10-30 / R103 收敛为「四入口之一」）。
 *
 * <p>本 Receiver 现在只负责「把某条系统事件翻译成一次拉起请求」，真正的拉起动作统一交给
 * {@link BootLauncher}。四条件入口的分工：</p>
 *
 * <ul>
 *   <li>{@code LOCKED_BOOT_COMPLETED} —— 解锁前最早的一次机会，进程先起来；已解锁则直接拉起。</li>
 *   <li>{@code USER_UNLOCKED} —— 解锁完成，此时才能安全读偏好、正常显示界面。</li>
 *   <li>{@code BOOT_COMPLETED} / {@code QUICKBOOT_POWERON} —— 传统兜底，部分固件会拖到很久之后。</li>
 *   <li>{@link App#ACTION_PREHEAT} —— 本应用自己的媒体库服务被系统绑定时发出的预热信号，
 *       用于「广播被厂商拦掉」的机型（详见 {@code PlaybackService#onCreate}）。</li>
 * </ul>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootRetry";
    /** 内部重试 action：显式 Intent 自派发，不需要在 Manifest 里声明。 */
    private static final String ACTION_RETRY = "com.fongmi.android.tv.action.BOOT_RETRY";
    private static final String EXTRA_STEP = "step";
    /** 用户解锁完成（相当于「已经能显示界面了」），见 Manifest 里给本 Receiver 加的 filter。 */
    private static final String ACTION_USER_UNLOCKED = "android.intent.action.USER_UNLOCKED";
    /**
     * 解锁后拉起只在这个时间窗内生效（毫秒）。
     *
     * <p>USER_UNLOCKED 不只在开机时发：切换用户、重装、某些固件刷新用户状态都会再发一次。
     * 若不加窗口限制，用户半夜正在看别的应用时会突然被拉进本软件。5 分钟内属于「开机期」，
     * 超过就当普通运行期，交给 {@code HomeGuardService} 的桌面守护处理。</p>
     */
    private static final long UNLOCK_WINDOW_MS = 5 * 60 * 1000L;
    /**
     * 首次尝试之后的补偿重试时间表（毫秒，累进延迟）。
     *
     * <p><b>为什么需要重试</b>：部分固件（TCL / 雷鸟等）在开机阶段有自己的流程（开机广告、
     * 预装应用自检），这期间会「扣住」第三方 App 的启动请求 —— 用户的实测现象就是
     * 「桌面已经出来，但本软件要再等约 2 分钟才自己弹出来」。只发一次 startActivity
     * 撞不上固件放行的那个窗口，多次尝试才有机会命中。</p>
     *
     * <p>思路参考 aerial-tv（https://github.com/pengchujin/aerial-tv）的桌面守护：
     * 它用常驻无障碍服务持续抢占；这里刻意<b>不引入常驻服务</b>（本机内存只有 982MB），
     * 只做开机后 30 秒内的一次性定时补偿，代价小得多。</p>
     */
    private static final long[] RETRY_DELAYS = {3_000L, 8_000L, 15_000L, 30_000L};
    /** PendingIntent 的 requestCode 基址，用 step 偏移区分，避免几次重试互相覆盖。 */
    private static final int REQ_BASE = 0x0B07;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean retry = ACTION_RETRY.equals(action);
        boolean lockedBoot = Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
        boolean userUnlocked = ACTION_USER_UNLOCKED.equals(action);
        boolean preheat = App.ACTION_PREHEAT.equals(action);
        if (!retry && !lockedBoot && !userUnlocked && !preheat && !isBootAction(action)) return;
        // ★2026-10-30（R100）实测：TCL 把 BOOT_COMPLETED 拖到开机约 79~81 秒才处理完，
        //   「桌面 11.5 秒就绪、本进程 28.6 秒才被无障碍绑定创建、界面 41 秒才显示」。
        //   所以补一条更早的路径：LOCKED_BOOT_COMPLETED（directBootAware，开机十几秒就发）
        //   → 若用户已解锁就立刻拉起；未解锁则等 USER_UNLOCKED 再拉。
        //   注意：解锁前读不到凭据加密存储（SharedPreferences 会抛 IllegalStateException），
        //   这条路径上的所有开关读取都已在 BootLauncher 内部 try/catch 兜住。
        if (lockedBoot) {
            Log.i(TAG, "LOCKED_BOOT_COMPLETED，提前预热进程");
            if (isUserUnlocked(context)) BootLauncher.forBoot(context, "LOCKED_BOOT");
            return;
        }
        if (userUnlocked) {
            // 只在开机时间窗内响应，避免运行期切用户时把用户从别的应用里拽走
            if (SystemClock.elapsedRealtime() > UNLOCK_WINDOW_MS) return;
            BootLauncher.forBoot(context, "USER_UNLOCKED");
            return;
        }
        if (preheat) {
            // 进程已被系统绑定媒体库服务拉起，趁这次机会把界面顶上（窗口比解锁窄，见 BootLauncher 注释）
            if (SystemClock.elapsedRealtime() > BootLauncher.BOOT_WINDOW_MS) return;
            BootLauncher.forBoot(context, "媒体库绑定");
            return;
        }
        // 直播配置预加载只做一次；重试不再重复注册网络回调，否则每次都会多挂一个 Callback
        if (!retry) registerCallback();
        // 「开机自启动」开关（长辈设置）：关闭后不再拉起，也就不必再排补偿重试。
        // 与「退出自动续播」（Setting.isAutoResume）分工不同：那项只管拉起后是否直进上次观看。
        if (!BootLauncher.isBootAutostartEnabled()) return;
        int step = retry ? intent.getIntExtra(EXTRA_STEP, 0) : 0;
        BootLauncher.forBoot(context, (retry ? "补偿重试 step=" : "BOOT_COMPLETED step=") + step);
        scheduleNext(context, step);
    }

    /** 用户是否已解锁（解锁后才能安全读偏好、也才能正常显示界面）。 */
    private boolean isUserUnlocked(Context context) {
        try {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            return um == null || um.isUserUnlocked();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 挂下一次补偿重试。
     *
     * <p>用 {@link AlarmManager#setAndAllowWhileIdle} 而不是 {@code setExactAndAllowWhileIdle}：
     * 后者在 Android 12+ 需要 {@code SCHEDULE_EXACT_ALARM} 运行时权限，普通侧载应用拿不到会直接抛
     * SecurityException；前者无需权限，开机阶段设备并不处于 doze，实际误差只有一两秒，足够。</p>
     */
    private void scheduleNext(Context context, int step) {
        if (step >= RETRY_DELAYS.length) {
            Log.i(TAG, "补偿重试结束，共尝试 " + (step + 1) + " 次");
            return;
        }
        try {
            Intent i = new Intent(context, BootReceiver.class);
            i.setAction(ACTION_RETRY);
            i.putExtra(EXTRA_STEP, step + 1);
            PendingIntent pi = PendingIntent.getBroadcast(context, REQ_BASE + step, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RETRY_DELAYS[step], pi);
        } catch (Throwable t) {
            Log.w(TAG, "挂重试失败 step=" + step, t);
        }
    }

    private boolean isBootAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action) || "android.intent.action.QUICKBOOT_POWERON".equals(action);
    }

    private void registerCallback() {
        ((ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(new Callback());
    }

    static class Callback extends ConnectivityManager.NetworkCallback {

        @Override
        public void onAvailable(@NonNull Network network) {
            doJob();
        }

        @Override
        public void onLost(@NonNull Network network) {
        }

        private void doJob() {
            LiveConfig.get().init().load();
            ((ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(this);
        }
    }
}
