package com.fongmi.android.tv.utils;

import com.github.catvod.utils.Prefers;

/**
 * 桌面守护的<b>真实</b>绑定状态（R150 / R153）。
 *
 * <p><b>为什么需要这个类</b>：{@code Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} 里写着本服务，
 * 只说明「用户曾经打开过那个开关」，<b>不等于</b>系统真的把服务绑上了进程。
 * 2026-11-15 在 TCL 上实测到的现场：设置值完全正确、{@code dumpsys accessibility} 也列出了本服务，
 * 但 {@code dumpsys activity services} 里 {@code app=null} —— <b>ServiceRecord 在、进程从未被绑定</b>，
 * 于是守护一次都没开火，而体检页却显示「已设置」。这就是那个「骗人的开关」。</p>
 *
 * <p>唯一能反映「是否真的绑上」的时刻只有 {@code AccessibilityService#onServiceConnected}，
 * 由服务自己上报，别的任何查询都替代不了。所以这个类的状态<b>只允许</b>由
 * {@code HomeGuardService} 写入。</p>
 *
 * <p>⚠ 进程级状态：进程被杀后自然归零，这是正确的 —— 新进程确实还没绑上，
 * 由 {@link GuardSelfHeal} 在那时决定要不要强制重绑。</p>
 *
 * <p><b>R154 / BUG-001</b>：{@link #connected} 是进程级内存态，但「用户到底开通过没有」这个
 * <b>事实</b>必须跨进程持久化 —— 否则 {@code am force-stop} / 固件「一键清理」把
 * {@code ENABLED_ACCESSIBILITY_SERVICES} 里本服务整条摘掉之后，{@link GuardSelfHeal}
 * 会把它误判成「用户从没开过」而永远放弃自愈，守护变成一次性用品。
 * 见 {@link #markEverOn()} / {@link #isEverOn()}。</p>
 */
public final class GuardState {

    /** 持久化键：本服务<b>至少成功连接过一次</b>（= 用户真的开通过守护）。 */
    private static final String KEY_EVER_ON = "guard_ever_on";

    private static volatile boolean connected;

    /** {@link #KEY_EVER_ON} 的进程内镜像：已置位就不再重复写盘（开机会话里会连接多次）。 */
    private static volatile boolean everOn;

    private GuardState() {
    }

    /** 本进程里无障碍服务是否真的被系统绑上过（且尚未断开）。 */
    public static boolean isConnected() {
        return connected;
    }

    /** 只由 {@code HomeGuardService#onServiceConnected} 调用。 */
    public static void onConnected() {
        connected = true;
    }

    /** 只由 {@code HomeGuardService#onDestroy} 调用（用户关开关 / 系统解绑 / 服务重启）。 */
    public static void onDisconnected() {
        connected = false;
    }

    /**
     * 登记「用户确实开通过守护」这个事实（持久化，只由 {@code onServiceConnected} 调用）。
     *
     * <p>这是 {@link GuardSelfHeal} 区分「用户主动关闭」与「系统把服务摘掉」的唯一依据：
     * 没有它就是后者，有它就是前者。</p>
     */
    public static void markEverOn() {
        everOn = true;
        try {
            Prefers.put(KEY_EVER_ON, true);
        } catch (Throwable ignore) {
            // 解锁前凭据加密存储不可读 —— 忽略即可，下一次连接会再置一次
        }
    }

    /** 用户是否曾经成功开通并连接过守护（持久化，跨进程重启有效）。 */
    public static boolean isEverOn() {
        if (everOn) return true;
        try {
            return everOn = Prefers.getBoolean(KEY_EVER_ON);
        } catch (Throwable t) {
            return false;
        }
    }
}
