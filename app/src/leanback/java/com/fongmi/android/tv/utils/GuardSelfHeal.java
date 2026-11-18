package com.fongmi.android.tv.utils;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.service.HomeGuardService;

/**
 * 无障碍守护的<b>自愈执行器</b>（R150 / R153）。
 *
 * <p><b>要解决的现象</b>：TCL 上每重启一次，守护就静默失效一次 —— 设置值、{@code dumpsys accessibility}
 * 都显示「已启用」，但 {@code dumpsys activity services} 里本服务 {@code app=null}
 * （ServiceRecord 在，进程从未被绑定）。无障碍服务<b>依赖进程存在才能绑定</b>，开机早期
 * 进程还没起来时绑定失败，系统之后就<b>不再重试</b>，只能靠再改一次设置把系统踹一下。</p>
 *
 * <p><b>为什么需要 {@code WRITE_SECURE_SETTINGS}</b>：这是 {@code signature|privileged} 权限，
 * 侧载应用装完默认没有，必须一次性 {@code adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS}。
 * 没授权时本类<b>全程静默返回</b>（不崩、不弹窗、不误导用户），行为与改动前完全一致。</p>
 *
 * <p><b>三条纪律</b>：</p>
 * <ol>
 *   <li>正常工作的服务绝不去碰 —— 只在 {@link GuardState} 说没绑上时才动作：
 *       设置里<b>有</b>本服务 ⇒ {@link #rebind(Context, String)} 强制重绑；
 *       设置里<b>没有</b>且 {@link GuardState#isEverOn()} 为真 ⇒ 写回（被 force-stop 摘掉了）；
 *       设置里没有且从没开过 ⇒ 什么都不做（尊重用户意愿）；</li>
 *   <li>{@link #REBIND_THROTTLE_MS} 60 秒防抖：移除/加回会把服务打断一下，
 *       绝不能因为连续几次调用就把好好的守护反复重启；</li>
 *   <li>写入前<b>重新读一次</b>当前值，只增删自己那一段 —— 用户自己开的讯飞等无障碍服务必须原样保留。</li>
 * </ol>
 *
 * <p>⚠ 调用点全部在 leanback 源集（{@code BootReceiver} / {@code HomeGuardService} / {@code HomeActivity}）。
 * 不放 {@code App#onCreate}：{@code App} 属于 main 源集，引用不了 leanback 专有类
 *（标准树/手机端根本没有 {@code HomeGuardService}）。</p>
 */
public final class GuardSelfHeal {

    private static final String TAG = "GuardSelfHeal";

    /** 移除 → 加回之间的间隔。太短系统来不及感知，太长用户会看到无障碍开关闪一下。 */
    private static final long REBIND_GAP_MS = 1500L;

    /** 强制重绑的防抖窗口：窗口内重复调用直接忽略。 */
    private static final long REBIND_THROTTLE_MS = 60_000L;

    private static long lastHealAt;

    private GuardSelfHeal() {
    }

    /**
     * 自愈入口：**幂等**，可以随便多处调用。
     *
     * <p>决策顺序：没授权 → 什么都不做；已绑定 → 什么都不做；
     * 设置里没有自己 → 看 {@link GuardState#isEverOn()} 决定「写回」还是「放弃」
     *（详见下面 {@code !contains} 分支的两条注释）；设置里有但没绑上 → {@link #rebind(Context, String)}。</p>
     *
     * @param reason 仅用于日志，便于真机上分辨是哪条链路触发的
     * @return true = 本次真的动过设置（写回或强制重绑，体检页据此提示「正在重连」）；
     *         false = 没做任何事（未授权 / 已绑上 / 用户从没开过 / 正在防抖）
     */
    public static boolean ensure(Context context, String reason) {
        if (context == null) return false;
        if (!hasWriteSecure(context)) return false;
        if (GuardState.isConnected()) return false;
        try {
            ComponentName self = new ComponentName(context, HomeGuardService.class);
            String current = read(context);
            if (!contains(current, self)) {
                // 列表里没有本服务有两种完全不同的成因，必须分开处理（R154 / BUG-001）：
                //   ① 用户从来就没开过 → **不擅自打开**，否则等于绕过用户意愿，由体检页引导；
                //   ② 开过、但被系统整条摘掉 → am force-stop / 固件「一键清理」/ 系统设置里
                //      「强行停止」都会触发（Android 原生行为；实测 TCL 上摘得干干净净，
                //      而 accessibility_enabled 仍是 1）。此时若也放弃，守护就变成一次性用品，
                //      且用户完全无从察觉 —— 他看到的只是「按主页键不再回来了」。
                if (!GuardState.isEverOn()) {
                    Log.i(TAG, "用户从未开通守护，不自愈（" + reason + "）");
                    return false;
                }
                Log.i(TAG, "守护开过但被系统摘除，主动写回（" + reason + "）");
                // 复用重绑防抖：写回后系统要几百毫秒才回调 onServiceConnected，
                // 这段空窗里若再次触发 ensure()，不该马上去做 remove→add 的强制重绑。
                lastHealAt = SystemClock.elapsedRealtime();
                write(context, append(current, self.flattenToString()));
                enableAccessibility();
                return true;
            }
            return rebind(context, reason);
        } catch (Throwable t) {
            Log.w(TAG, "自愈失败（" + reason + "）", t);
            return false;
        }
    }

    /**
     * 强制重绑：把本服务从启用列表里摘掉 → 等 {@link #REBIND_GAP_MS} → 再写回，
     * 最后确保无障碍总开关为 1。系统感知到设置变化后会重新绑定，这是唯一不需要 root 的踹法。
     *
     * <p>⚠ 带 60 秒防抖；窗口内重复调用直接返回。</p>
     *
     * @return true = 已发起重绑；false = 处在防抖窗口内，什么都没做
     */
    private static boolean rebind(Context context, String reason) {
        long now = SystemClock.elapsedRealtime();
        if (lastHealAt != 0L && now - lastHealAt < REBIND_THROTTLE_MS) {
            Log.i(TAG, "重绑防抖中，跳过（" + reason + "）");
            return false;
        }
        lastHealAt = now;
        ComponentName self = new ComponentName(context, HomeGuardService.class);
        String flat = self.flattenToString();
        Log.i(TAG, "开始强制重绑（" + reason + "）");
        write(context, removeSelf(read(context), self));
        // 加回时**重新读**当前值：这 1.5 秒里用户可能刚好在系统设置里改过别的无障碍服务。
        App.post(() -> {
            try {
                String latest = read(context);
                if (!contains(latest, self)) {
                    write(context, append(latest, flat));
                }
                enableAccessibility();
                Log.i(TAG, "强制重绑已提交，等待系统回调 onServiceConnected");
            } catch (Throwable t) {
                Log.w(TAG, "重绑写回失败", t);
            }
        }, REBIND_GAP_MS);
        return true;
    }

    // ------------------------------------------------------------------ 读写

    /** 本应用是否拿到了 {@code WRITE_SECURE_SETTINGS}（侧载应用默认没有，需一次性 adb grant）。 */
    private static boolean hasWriteSecure(Context context) {
        try {
            return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ContentResolver cr(Context context) {
        return context.getApplicationContext().getContentResolver();
    }

    private static String read(Context context) {
        try {
            String v = Settings.Secure.getString(cr(context), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    private static void write(Context context, String value) {
        try {
            Settings.Secure.putString(cr(context), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
        } catch (Throwable t) {
            Log.w(TAG, "写 ENABLED_ACCESSIBILITY_SERVICES 失败（可能未授权）", t);
        }
    }

    /** 无障碍总开关：写完服务列表后必须确保它是 1，否则列表再对也不会生效。 */
    private static void enableAccessibility() {
        try {
            Settings.Secure.putInt(App.get().getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Throwable t) {
            Log.w(TAG, "写 ACCESSIBILITY_ENABLED 失败（可能未授权）", t);
        }
    }

    /** 列表里是否已有本服务。系统可能写全长名也可能写短名，两种都比。 */
    private static boolean contains(String list, ComponentName self) {
        if (TextUtils.isEmpty(list)) return false;
        String flat = self.flattenToString();
        String shortFlat = self.flattenToShortString();
        for (String item : list.split(":")) {
            if (item == null) continue;
            String one = item.trim();
            if (one.equalsIgnoreCase(flat) || one.equalsIgnoreCase(shortFlat)) return true;
        }
        return false;
    }

    /** 只删自己那一段，其余（讯飞等）原样保留。 */
    private static String removeSelf(String list, ComponentName self) {
        if (TextUtils.isEmpty(list)) return "";
        String flat = self.flattenToString();
        String shortFlat = self.flattenToShortString();
        StringBuilder sb = new StringBuilder();
        for (String item : list.split(":")) {
            if (item == null) continue;
            String one = item.trim();
            if (one.isEmpty()) continue;
            if (one.equalsIgnoreCase(flat) || one.equalsIgnoreCase(shortFlat)) continue;
            if (sb.length() > 0) sb.append(':');
            sb.append(one);
        }
        return sb.toString();
    }

    /** 追加自己（去重，空列表时不会留下前导冒号）。 */
    private static String append(String list, String flat) {
        if (TextUtils.isEmpty(list)) return flat;
        return list + ":" + flat;
    }
}
