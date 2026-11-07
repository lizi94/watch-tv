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
 * 「本应用现在是不是这台电视的默认桌面」以及「本机还有几个别的 HOME 候选」（R101 / 2026-11-04）。
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
 * 用户可能刚在系统设置里改完默认桌面就回来按键。</p>
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
            // 未选择「始终」时解析出来的是系统选择器（ResolverActivity / android 包），不能算默认桌面
            home = def != null && def.equals(self) && !isChooser(resolved);
        } catch (Throwable ignored) {
            // 查询失败：一律按「不是默认桌面」处理 —— 这样返回键保留原有退出语义，
            // 最坏情况只是「明明是桌面却能退出」，绝不会变成「退不出去」。
        }
        selfHome = home;
        others = Collections.unmodifiableSet(set);
        at = SystemClock.elapsedRealtime();
        // R123 取证用（真机 adb logcat -s LauncherInfo）：只在「自己是默认桌面、却一个别的桌面都没查到」
        // 这个可疑状态下打一条，进程生命周期内最多打一次 —— 下一次误报排查不用再猜是哪一层查询没结果。
        if (home && set.isEmpty() && !warnLogged) {
            warnLogged = true;
            android.util.Log.i("LauncherInfo", "默认桌面=自己，且未查到任何其它 HOME 候选（将用三源交叉复核）");
        }
    }

    /** 上面那条诊断日志的「只打一次」标记。 */
    private static volatile boolean warnLogged;

    /** 本应用是否系统默认桌面。 */
    public static boolean isSelfHome(Context context) {
        if (SystemClock.elapsedRealtime() - at > TTL_MS) refresh(context);
        return selfHome;
    }

    /** 本机除自己以外的 HOME 候选包名（安全阀判据的数据源）。 */
    public static Set<String> getOthers(Context context) {
        if (SystemClock.elapsedRealtime() - at > TTL_MS) refresh(context);
        return others;
    }

    /**
     * 本机<b>是否还存在别的桌面</b>（R123 新增，两源交叉）。
     *
     * <p><b>为什么不能只看 {@link #getOthers()}</b>：它依赖一次
     * {@code queryIntentActivities(MAIN+HOME, 0)}。而「把本应用设为默认桌面」这个动作本身，
     * 在部分 ROM（小米 / 雷鸟 / 部分盒子）上会让原厂桌面从该查询结果里消失；原厂桌面被系统
     * <b>冻结 / 停用</b>时，{@code flags = 0} 也查不到它 —— 两种情况下集合都会为空，
     * 于是「只剩我一个」的判定被误触发，首页那条提示条就成了每次回首页都糊一脸的错误提示。</p>
     *
     * <p>这里用两条独立证据，<b>任一源查到「别的桌面」就认定本机不止我一个</b>：</p>
     * <ol>
     *   <li>{@code queryIntentActivities(MAIN+HOME, 0)} —— 可见且启用的（= {@link #getOthers()}）；</li>
     *   <li>{@code queryIntentActivities(..., MATCH_DISABLED_COMPONENTS)} —— <b>装了但被停用</b>的原厂
     *       桌面。它仍然是「可恢复的出口」（用户能在系统设置里重新启用），所以必须算作存在。</li>
     * </ol>
     *
     * <p>⚠ 本来还想要第三条源（系统自身的 {@code PackageManager#getHomeActivities}），但它<b>不在公开
     * SDK</b> 里（编译期直接「找不到符号」，本轮实测），不能用 {@code @SystemApi} / 反射去碰 ——
     * 那会让编译与 R8 都变脆。两条源已经覆盖实测里的那两种误报成因，够用。</p>
     *
     * <p><b>取舍</b>：两条都为「否」才返回 false，也就是<b>宁可少报一次，也不再误报</b>。
     * 真正困死的场景（原厂桌面被卸载）两条都为空，仍然会正确报出来。</p>
     *
     * <p>⚠ 这里有 2 次 PackageManager 查询，<b>不要放进 onResume / onBindViewHolder</b> 这类热路径；
     * 现在只有应用列表、体检页两处按需调用（首页那条提示条已在 R123 整条删除）。</p>
     */
    public static boolean hasOtherLauncher(Context context) {
        if (context == null) return false;
        if (!getOthers(context).isEmpty()) return true;
        Context app = context.getApplicationContext();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        return existsOther(app.getPackageManager(), intent,
                PackageManager.MATCH_DISABLED_COMPONENTS, app.getPackageName());
    }

    /** 按指定 flags 查一次 HOME 候选，只要有一个「不是自己」的就算存在。 */
    private static boolean existsOther(PackageManager pm, Intent intent, int flags, String self) {
        try {
            List<ResolveInfo> all = pm.queryIntentActivities(intent, flags);
            if (all == null) return false;
            for (ResolveInfo info : all) {
                if (info == null || info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg == null || pkg.isEmpty() || pkg.equals(self)) continue;
                return true;
            }
        } catch (Throwable ignored) {
            // 查询失败按「没查到」处理：交给下一个数据源，最终仍可能返回 false（宁可不报）
        }
        return false;
    }

    private static boolean isChooser(ResolveInfo info) {
        if (info == null || info.activityInfo == null) return true;
        if ("android".equals(info.activityInfo.packageName)) return true;
        String name = info.activityInfo.name;
        return name != null && name.contains("Resolver");
    }
}
