package com.fongmi.android.tv.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomeActivity;

/**
 * 桌面守护：按主页键回到电视桌面时，自动把「看电视」切回前台（2026-10-29 新增）。
 *
 * <p><b>为什么需要它</b>：部分固件（TCL / 雷鸟等）在开机阶段会扣住第三方 App 的启动，
 * 实测本机 <b>桌面在开机后 11 秒就出现，但本 App 进程要到开机后 133 秒才被创建</b>
 * （数据来源 {@code /proc/<pid>/stat} 第 22 字段），用户体感就是「桌面出来还要干等 2 分钟」。
 * 只靠 {@code BOOT_COMPLETED} 广播无法突破，因为广播本身就是靠系统创建进程来接收的。</p>
 *
 * <p><b>为什么无障碍服务能更早</b>：{@code AccessibilityManagerService} 在
 * {@code systemReady} 阶段就会绑定已启用的无障碍服务，绑定动作本身会把本 App 进程拉起来，
 * 这早于 {@code finishBooting()} 发出 {@code BOOT_COMPLETED}。此外无障碍服务启动 Activity
 * 不受后台启动（BAL）限制，不会被系统拦。思路来自 aerial-tv 的 {@code HomeGuardService}。</p>
 *
 * <p><b>为什么监听 {@code TYPE_WINDOW_STATE_CHANGED}</b>：桌面回到前台时一定会产生该事件，
 * 且事件频率远低于 {@code TYPE_WINDOW_CONTENT_CHANGED}，对这台 982MB 内存的机器负担最小。
 * 配置里不写 {@code packageNames} 限定，是为了固件升级改了桌面包名时仍能工作；
 * 实际过滤在下面的 {@link #onAccessibilityEvent} 里做。</p>
 *
 * <p><b>⚠ 这是「完整接管」模式</b>：开启后只要回到 TCL 桌面就会被立刻切回本软件，
 * 包括用户主动按主页键想用桌面的时候。长辈设置里有独立开关可随时关闭；
 * 若软件本身进不去，可用 {@code adb shell settings put secure enabled_accessibility_services ...}
 * 把本服务从列表中移除。</p>
 */
public class HomeGuardService extends AccessibilityService {

    private static final String TAG = "HomeGuard";
    /** TCL 电视的原桌面包名（实测 dumpsys window 得到 com.tcl.cyberui/com.tcl.cyberui.MainActivity）。 */
    private static final String TCL_LAUNCHER = "com.tcl.cyberui";
    /** 两次拉起之间的最小间隔：桌面窗口可能连续抖动多次，避免重复 startActivity。 */
    private static final long DEBOUNCE_MS = 800L;

    private long lastLaunchAt;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "桌面守护已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // 「接管 Home 键」开关：关闭后完全不管，主页键正常回到电视桌面
        if (!Setting.isHomeGuard()) return;
        // ⛔⛔ 2026-10-29（R99）用户主动退出 ⇒ 不许拉回。
        // 没有这一条时，首页「连按三次返回键退出」是个假动作：finish() 之后桌面窗口立刻出现，
        // 800ms 后被这里重新拉起 —— 而且因为原实例已 finish，拉起来的是**全新冷启动**的实例
        //（无缓存、要重新走一遍配置网络往返，失败就会显示「还没有设置数据源」）。
        // 标记由 HomeActivity#onBackInvoked 在真正退出前置位，
        // 由 HomeActivity#initView 在下一次「真正打开本软件」时清零 ⇒ 守护随即恢复。
        if (Setting.isUserExited()) return;
        CharSequence name = event.getPackageName();
        if (name == null || !TCL_LAUNCHER.equals(name.toString())) return;
        // ⛔ 这里不能再用 App.activity() 判空做「已在前台」的短路（2026-10-29 实测）：
        // 按主页键时「本 Activity 的 onPause」与「桌面窗口事件」在同一主线程消息队列里，
        // 事件常常先于 onPause 到达，此刻 activity 仍非 null ⇒ 该次事件被整个跳过，
        // 要等下一个窗口事件才会拉起。实测这样会让「按主页键回来看电视」慢到 4 秒，
        // 而 am start -W 实测本 Activity 从后台恢复只要 262ms，说明慢的是这段判断而非渲染。
        // 而包名命中 com.tcl.cyberui 本身即说明桌面已在前台，本软件必然不在前台，无需额外判断。
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunchAt < DEBOUNCE_MS) return;
        lastLaunchAt = now;
        launchHome();
    }

    /**
     * 拉起主界面。Intent 必须是「普通启动」（不带 data、不带 EXTRA_BACK_HOME/EXTRA_REFRESH），
     * 否则 {@code HomeActivity#isPlainLaunch} 会拒绝 ⇒ 开机直达/续播判定失效。
     */
    private void launchHome() {
        try {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            Log.i(TAG, "检测到回到桌面，已拉起看电视");
        } catch (Throwable t) {
            Log.w(TAG, "拉起失败", t);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
