package com.fongmi.android.tv.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.util.Log;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomeActivity;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootRetry";
    /** 内部重试 action：显式 Intent 自派发，不需要在 Manifest 里声明。 */
    private static final String ACTION_RETRY = "com.fongmi.android.tv.action.BOOT_RETRY";
    private static final String EXTRA_STEP = "step";
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
        if (!retry && !isBootAction(action)) return;
        // 直播配置预加载只做一次；重试不再重复注册网络回调，否则每次都会多挂一个 Callback
        if (!retry) registerCallback();
        // 「开机自启动」开关（长辈设置）：关闭后开机不再拉起界面，停留在电视原桌面。
        // 与「退出自动续播」（Setting.isAutoResume）分工不同：那项只管拉起后是否直进上次观看。
        if (!Setting.isBootAutostart()) return;
        int step = retry ? intent.getIntExtra(EXTRA_STEP, 0) : 0;
        // 已经有可见界面就不再抢，避免用户刚进软件又被重复拉起
        if (hasVisibleUi()) Log.i(TAG, "界面已在前台，跳过 step=" + step);
        else launchHome(context, step);
        scheduleNext(context, step);
    }

    /**
     * 开机拉起主界面（2026-10-29 新增）：实现「开机自启动看电视」。
     *
     * <p><b>为什么放在这里</b>：HomeActivity 已内置「开机直达」门控
     * （HomeActivity#checkBootDirect，受 Setting.isAutoResume() 控制、默认开启），
     * 但它只会被「用户打开软件」触发；而本 Receiver 原本收到 BOOT_COMPLETED 后仅做直播配置预加载，
     * 不会拉起界面。补上这一次 startActivity，开机直达链路才真正生效。</p>
     *
     * <p><b>Intent 必须是「普通启动」</b>：不带 data、不带 EXTRA_BACK_HOME/EXTRA_REFRESH，
     * 否则 HomeActivity#isPlainLaunch 会拒绝 ⇒ 开机直达失效。用 NEW_TASK 是 Receiver 启 Activity
     * 的硬性要求；CLEAR_TOP + SINGLE_TOP 避免重复实例、复用已有首页实例。</p>
     */
    private void launchHome(Context context, int step) {
        try {
            // ⛔ 2026-10-29（R99）：开机是「全新开始」，必须清掉「用户主动退出」的抑制标记。
            // 理由：万一这次 startActivity 被固件扣住（本 Receiver 的重试机制处理的就是这种情况），
            // 而标记还留着，{@code HomeGuardService} 也会跟着一直不开火 ⇒ 两个兜底手段同时失效，
            // 电视会停在桌面且再也回不去本软件。清理放在这里（launchHome 每次重试都会走到，幂等）。
            // 标记的语义与置位/清零全链路见 Setting#isUserExited()。
            Setting.putUserExited(false);
            Intent i = new Intent(context, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(i);
            Log.i(TAG, "startActivity 已发出 step=" + step);
        } catch (Throwable t) {
            Log.w(TAG, "startActivity 失败 step=" + step, t);
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

    /** 本软件是否已经有可见界面（App 在 Activity 生命周期回调里维护当前前台 Activity）。 */
    private boolean hasVisibleUi() {
        try {
            return App.activity() != null;
        } catch (Throwable t) {
            return false;
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
