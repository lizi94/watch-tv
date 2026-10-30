package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.util.Log;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「这台设备的桌面包名是谁」的解析器（2026-10-30 新增 / R103）。
 *
 * <p><b>为什么需要它</b>：{@code HomeGuardService} 原先写死 {@code TCL_LAUNCHER = "com.tcl.cyberui"}，
 * 意味着「接管主页键」这个功能<b>只在 TCL 上生效</b>；换到小米 / 海信 / 创维 / 索尼 / 雷鸟 / 当贝 /
 * 各类 IPTV 盒子上，包名对不上就永远不触发 —— 用户装了自启版却等于没这个功能。
 * 这里改为运行时向系统要「所有声明了 HOME 的应用」，与具体品牌解耦。</p>
 *
 * <p><b>为什么用 {@code queryIntentActivities} 而不是猜包名</b>：任何 ROM 只要遵循
 * Android 标准（桌面 = 一个带 {@code ACTION_MAIN + CATEGORY_HOME} 的 Activity），
 * 无论叫什么名字都会被系统列出来。固件升级改了桌面包名也不会失效。</p>
 *
 * <p><b>为什么 flags 传 0 而不是 {@code MATCH_DEFAULT_ONLY}</b>：后者只返回「当前默认」的那一个。
 * 但<b>不能只认默认</b> —— 见下面「两级识别」。</p>
 *
 * <p><b>两级识别（R103 定案，宁可漏不可误伤）</b>：</p>
 * <ol>
 *   <li><b>首选</b>：{@code resolveActivity(MAIN+HOME, MATCH_DEFAULT_ONLY)} —— 这正是「按主页键
 *       系统会把我带到哪里」，precision 最高。只认它，能避开一类真实的误伤：某些 ROM 的
 *       <b>开机向导 / 恢复出厂预置页</b>（{@code com.android.provision} 等）也声明了 HOME，
 *       但它们不是默认桌面，用户永远不会按主页键进到那里；若把它们算作桌面，
 *       一旦固件在开机阶段把它们顶到前台，我们就会反复抢屏，把开机流程搞坏。</li>
 *   <li><b>兜底</b>：解析不到具体应用时（用户从未选过「始终」→ HOME 会弹选择器，
 *       或某些电视的桌面 Activity 没声明 {@code CATEGORY_DEFAULT} → 解析返回 null），
 *       退回「所有声明了 HOME 的应用」。此时无法判断谁是桌面，把候选都算上，
 *       只会在「确实有应用带着 HOME 身份来到前台」时才动作，误伤面仍然有限。</li>
 * </ol>
 *
 * <p><b>为什么排除自己</b>：本应用曾声明过 {@code CATEGORY_HOME}（2026-10-30 晚已删除，理由见
 * leanback/AndroidManifest.xml），但这段排除<b>保留</b>：只要有人用「把 HOME 指向本应用」的方式
 * 启动（个别桌面备份工具、或将来重新声明 HOME），本应用就会出现在结果里。
 * 若把自己算进「桌面集合」，{@code HomeGuardService} 会认为「回到自己的首页 = 回到桌面」而反复拉起，
 * 形成自激。这是一条零成本的不变量，不随 manifest 变化。</p>
 *
 * <p><b>为什么不做持久化缓存</b>：一次查询只有几毫秒，而用户随时可能安装/卸载第三方桌面。
 * 落盘缓存只会在这种时候变成「过期数据导致漏识别」，得不偿失。这里只保留进程内缓存，
 * 并在三处更新：① 无障碍服务每次连接（含开机）{@link #refresh(Context)}；
 * ② {@link #get(Context)} 内部按 {@link #REFRESH_INTERVAL_MS} 限流重算
 * （覆盖「用户运行中装了第三方桌面，但无障碍服务没断过」这种不重连的情况）；
 * ③ 查询失败后不锁定，下一轮自动重试。</p>
 *
 * <p><b>集合为空时的语义</b>：说明系统里除了本应用没有别的桌面（通常意味着本应用已被设为默认桌面）。
 * 这种情况<b>不接管</b> —— 调用方 {@link #isDesktop} 恒返回 false。理由：此时「拉回首页」等于
 * 「从桌面拉回桌面」，毫无意义且会打断用户；宁可什么都不做，也不能乱拉。</p>
 */
public final class DesktopResolver {

    private static final String TAG = "DesktopResolver";

    /**
     * 缓存有效期。超过这个时长后，下一次 {@link #get} 会重算一次。
     *
     * <p>取 60 秒的理由：{@code HomeGuardService} 只在「窗口切到桌面」时才问一次集合，
     * 频率本来就很低；即便用户疯狂按主页键，也不过是每秒一次 {@code PackageManager} 查询，
     * 而查询本身是内存中的 Intent 解析、耗时几毫秒。真正要防的是「每次事件都查」这种无谓开销，
     * 同时又不能太长 —— 用户装完第三方桌面、再从那个桌面按主页键回前台，
     * 60 秒内必然被重算到，不会出现「装了新桌面却永远不接管」。</p>
     */
    private static final long REFRESH_INTERVAL_MS = 60 * 1000L;

    /** 进程内缓存；{@code null} 表示还没查过。 */
    private static volatile Set<String> cache;

    /** 上次重算的 {@link SystemClock#elapsedRealtime()}；与缓存一起在锁内读写。 */
    private static long lastRefreshAt;

    private DesktopResolver() {
    }

    /**
     * 判断某个包名是不是本机的桌面。
     *
     * @param pkg 前台窗口的包名，允许为 {@code null}
     * @return 是桌面返回 true；是自己、为空、或集合为空时一律 false
     */
    public static boolean isDesktop(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        // 自己永远不算桌面：否则「回到自己的首页」会被当成「回到桌面」而自激拉起
        if (pkg.equals(context.getPackageName())) return false;
        return get(context).contains(pkg);
    }

    /**
     * 取桌面集合（进程内缓存，超过 {@link #REFRESH_INTERVAL_MS} 自动重算一次）。
     *
     * <p>注意：这条路径会在主线程（无障碍事件回调）里跑，所以重算必须限流；
     * 而 {@link #query} 只是一次内存中的 Intent 解析，60 秒一次完全无感。</p>
     */
    public static Set<String> get(Context context) {
        Set<String> local = cache;
        if (local != null && SystemClock.elapsedRealtime() - lastRefreshAt < REFRESH_INTERVAL_MS) return local;
        synchronized (DesktopResolver.class) {
            // 进锁后再判一次：并发的第二个调用者不该重复查
            if (cache != null && SystemClock.elapsedRealtime() - lastRefreshAt < REFRESH_INTERVAL_MS) return cache;
            lastRefreshAt = SystemClock.elapsedRealtime();
            cache = query(context);
            return cache;
        }
    }

    /** 强制重算：无障碍服务每次连接（含开机）时调用，覆盖「用户刚换了桌面」的情况。 */
    public static Set<String> refresh(Context context) {
        synchronized (DesktopResolver.class) {
            lastRefreshAt = SystemClock.elapsedRealtime();
            cache = query(context);
            return cache;
        }
    }

    private static Set<String> query(Context context) {
        Set<String> set = new LinkedHashSet<>();
        try {
            PackageManager pm = context.getPackageManager();
            String self = context.getPackageName();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            String def = resolved == null || resolved.activityInfo == null
                    ? null : resolved.activityInfo.packageName;
            if (def != null && !def.isEmpty() && !isChooser(resolved)) {
                // ① 首选：HOME 键真正会去的那一个（唯一，precision 最高，避开开机向导之类的非默认 HOME）
                //    自己就是默认桌面 ⇒ 什么都不加 ⇒ 空集 ⇒ 不接管（理由见类注释）
                if (!def.equals(self)) set.add(def);
            } else {
                // ② 兜底：解析不到具体桌面（用户从未选过「始终」；或电视桌面 Activity 未声明 DEFAULT）
                List<ResolveInfo> list = pm.queryIntentActivities(home, 0);
                if (list != null) {
                    for (ResolveInfo info : list) {
                        if (info == null || info.activityInfo == null) continue;
                        String pkg = info.activityInfo.packageName;
                        if (pkg == null || pkg.isEmpty() || pkg.equals(self)) continue;
                        set.add(pkg);
                    }
                }
                Log.i(TAG, "未解析出默认桌面，退回全部 HOME 候选");
            }
        } catch (Throwable t) {
            // 查询失败（极少数 ROM 权限异常）：返回空集 ⇒ 不接管，宁可不做事也不能乱拉
            Log.w(TAG, "查询桌面包名失败，本次不接管桌面", t);
        }
        Log.i(TAG, "本机桌面包名集合 = " + set);
        return Collections.unmodifiableSet(set);
    }

    /**
     * 是不是系统的「选择一个应用打开」选择器。
     *
     * <p>用户从未对 HOME 点过「始终」时，{@code resolveActivity} 返回的是
     * {@code com.android.internal.app.ResolverActivity}（部分 ROM 会换成自家实现）。
     * 这种状态下「桌面是谁」还没定，按主页键会弹选择器 —— 此时不能把它当桌面去抢屏，
     * 也不能当成「本机没有桌面」，交给兜底分支按候选集合处理。</p>
     */
    private static boolean isChooser(ResolveInfo info) {
        if (info.activityInfo == null) return true;
        if ("android".equals(info.activityInfo.packageName)) return true;
        String name = info.activityInfo.name;
        return name != null && name.contains("Resolver");
    }
}
