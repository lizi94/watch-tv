package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomeActivity;

/**
 * 「把界面顶到前台」的唯一出口（2026-10-30 新增 / R103）。
 *
 * <p><b>为什么要把这件事抽出来</b>：开机被拉起的入口现在有四条 ——
 * {@code LOCKED_BOOT_COMPLETED}/{@code USER_UNLOCKED} 广播、无障碍服务被系统绑定、
 * 通知监听被系统绑定、媒体库服务被系统绑定。四条链路原先各自写一遍 {@code startActivity}，
 * 参数、去重、开关判断全靠抄，一旦有一条忘了加「界面已在前台就别抢」就会把正在用软件的用户
 * 弹回首页。统一到这里之后，语义只有两个：</p>
 *
 * <ul>
 *   <li>{@link #forBoot} —— 开机链路：受「开机自启动」开关约束，界面已在前台就不抢。</li>
 *   <li>{@link #forGuard} —— 桌面守护链路：代表「用户按了主页键，要切回来」，
 *       不受「开机自启动」约束（那是另一件事，由 {@code Setting#isHomeGuard} 单独管）。</li>
 * </ul>
 *
 * <p><b>为什么要内部去重</b>：一次开机里四条入口可能在几百毫秒内先后命中，
 * 每条都 {@code startActivity} 会让首页连续重建/重放动画。这里用 1 秒的硬闸门兜底；
 * 各入口自己的业务去重（例如守护的「两次按主页键」逻辑）仍然留在调用方。</p>
 *
 * <p><b>Intent 必须是「普通启动」</b>：不带 data、不带 {@code EXTRA_BACK_HOME}/{@code EXTRA_REFRESH}，
 * 否则 {@code HomeActivity#isPlainLaunch} 会判定为「带着别的事来」，开机直达（续播）就失效了。</p>
 */
public final class BootLauncher {

    private static final String TAG = "BootLauncher";
    /** 两条链路共用的硬闸门：1 秒内不重复拉起（四条开机入口可能集中在几百毫秒内）。 */
    private static final long MIN_INTERVAL_MS = 1000L;

    /**
     * 「这还算开机阶段吗」的时间窗（毫秒）。
     *
     * <p><b>给谁用</b>：媒体库服务被绑定、通知监听被绑定 —— 这两个都是<b>运行期也会发生</b>的事件
     * （用户点播放、切换通知使用权、服务被系统重连）。若不加窗口限制，用户正在别的应用里
     * 就会被我们突然抢屏。120 秒覆盖绝大多数机型的开机阶段，超过就只当保活信号，不再抢界面。</p>
     *
     * <p><b>为什么不复用 BroadcastReceiver 里的 5 分钟</b>：那 5 分钟是给 {@code USER_UNLOCKED} 的
     * —— 它只在「开机 / 切用户 / 重装」时发，本身就是罕见事件，窗口可以宽。而这两个绑定事件
     * 在运行期很常见，窗口必须收紧。</p>
     */
    public static final long BOOT_WINDOW_MS = 120 * 1000L;

    private static long lastLaunchAt;

    private BootLauncher() {
    }

    /**
     * 开机链路入口：广播 / 服务被绑定 / 媒体库绑定，统统走这里。
     *
     * <p>受「开机自启动」开关约束；界面已经在前台时直接放弃，避免把正在看片的人弹回首页。</p>
     *
     * @param reason 仅用于日志，便于在真机上分辨到底是哪条链路抢到的
     */
    public static void forBoot(Context context, String reason) {
        if (!isBootAutostartEnabled()) return;
        if (hasVisibleUi()) {
            Log.i(TAG, "界面已在前台，跳过（" + reason + "）");
            return;
        }
        start(context, reason);
    }

    /**
     * 桌面守护链路入口：用户按主页键回到桌面后切回来。
     *
     * <p><b>刻意不判断「界面是否在前台」</b>：按主页键那一刻，本 Activity 的 {@code onPause}
     * 与桌面窗口事件在同一个主线程消息队列里，事件常常先到，此时 {@code App.activity()} 仍非空。
     * 用它做短路会让这次事件被整个跳过，只能等下一个窗口事件 —— 实测「按主页键回来看电视」
     * 会慢到 4 秒（详见 {@code HomeGuardService} 里的取证注释）。</p>
     */
    public static void forGuard(Context context, String reason) {
        start(context, reason);
    }

    private static void start(Context context, String reason) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunchAt < MIN_INTERVAL_MS) return;
        lastLaunchAt = now;
        // 真正打开软件 = 全新开始，必须清掉「用户主动退出」的抑制标记；
        // 否则万一这次 startActivity 被固件扣住，守护也会跟着不开火，两个兜底同时失效。
        // 解锁前凭据加密存储不可读，putUserExited 会抛 IllegalStateException —— 单独 try，不能中断拉起。
        try {
            Setting.putUserExited(false);
        } catch (Throwable ignore) {
            // 解锁前读不到偏好属正常，忽略即可
        }
        try {
            Intent i = new Intent(context, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(i);
            Log.i(TAG, "已拉起界面（" + reason + "）");
        } catch (Throwable t) {
            Log.w(TAG, "拉起失败（" + reason + "）", t);
        }
    }

    /**
     * 读「开机自启动」开关。
     *
     * <p>解锁前 SharedPreferences（凭据加密存储）不可读，会抛 {@code IllegalStateException} ——
     * 此时按「开」处理，与默认值保持一致（否则开机早期的广播会被静默吞掉）。</p>
     *
     * <p>开放给 {@code BootReceiver} 复用：它需要知道「开关关了就别再排补偿重试」，
     * 与「这次要不要真的 startActivity」是两件事，但读的是同一个开关，不应该各写一份。</p>
     */
    public static boolean isBootAutostartEnabled() {
        try {
            return Setting.isBootAutostart();
        } catch (Throwable t) {
            Log.w(TAG, "解锁前读不到开机自启动开关，按默认(开)处理", t);
            return true;
        }
    }

    /** 本软件是否已有可见界面（App 在 Activity 生命周期回调里维护当前前台 Activity）。 */
    private static boolean hasVisibleUi() {
        try {
            return App.activity() != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
