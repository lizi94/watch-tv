package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「本应用现在是不是这台电视的默认主页」以及「本机还有几个别的 HOME 候选」（R101 / 2026-11-04）。
 *
 * <p><b>为什么单独一个类，而不往 {@link DesktopResolver} 里加</b>：{@code DesktopResolver} 是 R103
 * 定案的「守护该不该抢屏」的唯一判据，它的 {@code get()} 有一处<b>刻意的语义</b> ——
 * 「自己是默认桌面时返回空集」（见该类注释的「集合为空时的语义」）。安全阀需要的是另一个问题
 * 「本机一共还有几个桌面」，两者若混在一个方法里，很容易在下次维护时被顺手「优化」成一处，
 * 于是「自己是默认桌面」的机器全都会误判成「本机没有别的桌面」。分开写，语义各自独立、互不影响。</p>
 *
 * <p><b>与 {@code DesktopResolver} 的关系</b>：{@code isHome} 判定与它同源（都用
 * {@code resolveActivity(MAIN+HOME, MATCH_DEFAULT_ONLY)}），但<b>不共享缓存</b> ——
 * 这里的有效期只有 {@link #TTL_MS}，因为 {@code onBackInvoked} 会用到它，
 * 用户可能刚在系统设置里改完默认主页就回来按键。</p>
 */
public final class LauncherInfo {

    /** 缓存有效期：足够挡住「连按返回键」的密集调用，又能在改完系统设置回来后立刻生效。 */
    private static final long TTL_MS = 3_000L;

    private static volatile boolean selfHome;
    private static volatile Set<String> others = Collections.emptySet();
    private static volatile long at;

    private LauncherInfo() {
    }

    /** 强制重算。{@code HomeActivity#onResume} 里调一次，保证从系统设置回来后立刻是新的。 */
    public static void refresh(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        boolean home = false;
        Set<String> set = new LinkedHashSet<>();
        try {
            PackageManager pm = app.getPackageManager();
            String self = app.getPackageName();
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> all = pm.queryIntentActivities(intent, 0);
            if (all != null) {
                for (ResolveInfo info : all) {
                    if (info == null || info.activityInfo == null) continue;
                    String pkg = info.activityInfo.packageName;
                    if (pkg == null || pkg.isEmpty() || pkg.equals(self)) continue;
                    set.add(pkg);
                }
            }
            ResolveInfo resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            String def = resolved == null || resolved.activityInfo == null
                    ? null : resolved.activityInfo.packageName;
            // 未选择「始终」时解析出来的是系统选择器（ResolverActivity / android 包），不能算默认主页
            home = def != null && def.equals(self) && !isChooser(resolved);
        } catch (Throwable ignored) {
            // 查询失败：一律按「不是默认主页」处理 —— 这样返回键保留原有退出语义，
            // 最坏情况只是「明明是桌面却能退出」，绝不会变成「退不出去」。
        }
        selfHome = home;
        others = Collections.unmodifiableSet(set);
        at = SystemClock.elapsedRealtime();
    }

    /** 本应用是否系统默认主页。 */
    public static boolean isSelfHome(Context context) {
        if (SystemClock.elapsedRealtime() - at > TTL_MS) refresh(context);
        return selfHome;
    }

    /** 本机除自己以外的 HOME 候选包名（安全阀判据：为空 ⇒ 原厂桌面已被禁/卸）。 */
    public static Set<String> getOthers(Context context) {
        if (SystemClock.elapsedRealtime() - at > TTL_MS) refresh(context);
        return others;
    }

    private static boolean isChooser(ResolveInfo info) {
        if (info == null || info.activityInfo == null) return true;
        if ("android".equals(info.activityInfo.packageName)) return true;
        String name = info.activityInfo.name;
        return name != null && name.contains("Resolver");
    }
}
