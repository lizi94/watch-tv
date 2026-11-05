package com.fongmi.android.tv.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import com.fongmi.android.tv.bean.AppInfo;
import com.fongmi.android.tv.setting.Setting;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 查询「这台电视上能打开的应用」并做排序 / 置顶 / 过滤（R101）。
 *
 * <p><b>⛔ 必须阻塞调用，禁止在主线程跑</b>：一次要遍历几十上百个包、还要读 label。
 * 调用方（{@code AppListActivity}）放到 {@link com.fongmi.android.tv.utils.Task} 之外的普通线程里，
 * 回来再切主线程 setItems。</p>
 *
 * <p><b>为什么 MAIN+LAUNCHER 与 MAIN+LEANBACK_LAUNCHER 都要查</b>：电视应用的入口通常只声明
 * {@code LEANBACK_LAUNCHER}（不声明 {@code LAUNCHER}），只查前者会漏掉一大半电视应用；
 * 两者并集再按包名去重才是全的。**这也是 Android 11+ 包可见性能查到应用的前提** ——
 * Manifest 的 {@code <queries>} 里必须同样声明这两条，否则 {@code queryIntentActivities} 直接返回空。</p>
 *
 * <p><b>为什么不用 {@code getInstalledApplications}</b>：那会返回大量没有启动入口的系统组件
 * （服务、Provider 等），点开必然失败；我们要的是「能点开的东西」。</p>
 *
 * <p><b>⛔ 图标不在这里读</b>：{@code getApplicationIcon} 每个包都要解一次资源，
 * 近百个应用一次性读完会让 982MB 的机器抖到掉帧甚至被系统杀进程。
 * 图标在 {@code AppPresenter} 里按需加载 + LruCache。</p>
 */
public final class AppQuery {

    /** 排序用：中文按拼音。构造一次复用（Collator 不轻量）。 */
    private static final Collator COLLATOR = Collator.getInstance(Locale.CHINA);

    private AppQuery() {
    }

    /** 第一格固定项：系统设置。它不是「别人的应用」，但必须在列表里且不可隐藏。 */
    public static final String PKG_SETTINGS = "com.android.settings";

    public static List<AppInfo> load(Context context) {
        Context app = context.getApplicationContext();
        PackageManager pm = app.getPackageManager();
        String self = app.getPackageName();
        Map<String, AppInfo> map = new LinkedHashMap<>();

        collect(pm, self, Intent.CATEGORY_LAUNCHER, map);
        collect(pm, self, Intent.CATEGORY_LEANBACK_LAUNCHER, map);

        // 隐藏项过滤（只影响本列表可见性，不禁用、不卸载）
        Set<String> hidden = split(Setting.getHiddenApps());
        List<AppInfo> all = new ArrayList<>();
        for (AppInfo info : map.values()) if (!hidden.contains(info.getPkg())) all.add(info);

        // 置顶（顺序即展示顺序）。⚠ 必须用 splitOrdered：置顶是**有序**的，
        // 用 HashSet 会把顺序吃掉 ⇒ 老人点了「放到最前」却排到了第二个 = 「操作了但没生效」。
        List<String> pinned = splitOrdered(Setting.getPinnedApps());
        for (int i = 0; i < pinned.size(); i++) {
            String pkg = pinned.get(i);
            for (AppInfo info : all) if (info.getPkg().equals(pkg)) info.setPinned(i + 1);
        }

        all.sort((a, b) -> {
            int pa = a.getPinned() == 0 ? Integer.MAX_VALUE : a.getPinned();
            int pb = b.getPinned() == 0 ? Integer.MAX_VALUE : b.getPinned();
            if (pa != pb) return Integer.compare(pa, pb);
            return COLLATOR.compare(a.getLabel(), b.getLabel());
        });
        return all;
    }

    /** 全部可启动应用（含被隐藏的），用于「已隐藏」筛选页。 */
    public static List<AppInfo> loadAll(Context context) {
        Context app = context.getApplicationContext();
        PackageManager pm = app.getPackageManager();
        String self = app.getPackageName();
        Map<String, AppInfo> map = new LinkedHashMap<>();
        collect(pm, self, Intent.CATEGORY_LAUNCHER, map);
        collect(pm, self, Intent.CATEGORY_LEANBACK_LAUNCHER, map);
        List<AppInfo> all = new ArrayList<>(map.values());
        all.sort((a, b) -> COLLATOR.compare(a.getLabel(), b.getLabel()));
        return all;
    }

    /**
     * 把「系统设置」钉到第一格（R101，硬需求而非锦上添花）。
     *
     * <p><b>为什么必须钉</b>：第三方桌面把用户困住的实际事故几乎都死于同一个原因 ——
     * 桌面里没有任何通往系统设置的入口。而系统设置的包名在拼音序里排在很靠后，
     * 老人不可能翻到，所以必须在第一格。
     *
     * <p>本机若没把系统设置列出（少数固件把它从 LAUNCHER 里摘掉了），这里**合成一条**：
     * {@code component} 为 null，点击时走 {@code LauncherCapability#openSettings}
     *（{@code Settings.ACTION_SETTINGS}），一样能到。</p>
     */
    /**
     * 这台电视是否<b>真的</b>能打开「系统设置」。
     *
     * <p>为什么要有这个判据：{@link #withSettingsFirst} 在没有列出系统设置时是<b>凭空合成</b>一条的。
     * 少数固件把 Settings 从 LAUNCHER 摘掉、甚至连 {@code ACTION_SETTINGS} 都没人接，
     * 那格「系统设置」就是纯死格 —— 老人点一次没反应、再点一次还是没反应，
     * 结论是「这个软件坏了」。宁可不显示，也不能显示一个点不动的格子。</p>
     *
     * <p>判定口径与 {@code LauncherCapability#openSettings} 的实际跳转路径对齐：
     * 先看有没有 Activity 接 {@code ACTION_SETTINGS}，再看包管理器能不能给出系统设置的启动意图。
     * 两样都没有才算「打不开」。</p>
     */
    public static boolean canOpenSettings(Context context) {
        if (context == null) return false;
        PackageManager pm = context.getApplicationContext().getPackageManager();
        try {
            if (pm.resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0) != null) return true;
        } catch (Throwable ignored) {
        }
        try {
            return pm.getLaunchIntentForPackage(PKG_SETTINGS) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static List<AppInfo> withSettingsFirst(Context context, List<AppInfo> items, String settingsLabel) {
        List<AppInfo> out = new ArrayList<>();
        AppInfo settings = null;
        for (AppInfo info : items) {
            if (PKG_SETTINGS.equals(info.getPkg())) {
                settings = info;
                break;
            }
        }
        // 列表里本来就有系统设置 ⇒ 它是真的能启动的，直接用；
        // 只有在「没列出」时才需要合成，而合成前必须先确认这台机器打得开（见 canOpenSettings）。
        if (settings == null && canOpenSettings(context)) {
            settings = new AppInfo(settingsLabel, PKG_SETTINGS, null, true);
        }
        if (settings != null) out.add(settings);
        for (AppInfo info : items) if (!PKG_SETTINGS.equals(info.getPkg())) out.add(info);
        return out;
    }

    private static void collect(PackageManager pm, String self, String category, Map<String, AppInfo> map) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> list;
        try {
            list = pm.queryIntentActivities(intent, 0);
        } catch (Throwable t) {
            return;
        }
        if (list == null) return;
        for (ResolveInfo info : list) {
            if (info == null || info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg == null || pkg.isEmpty() || pkg.equals(self)) continue;
            if (map.containsKey(pkg)) continue;
            String label;
            try {
                label = String.valueOf(info.loadLabel(pm)).trim();
            } catch (Throwable t) {
                label = pkg;
            }
            if (label.isEmpty()) label = pkg;
            boolean system = false;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            } catch (Throwable ignored) {
            }
            ComponentName cn = new ComponentName(pkg, info.activityInfo.name);
            map.put(pkg, new AppInfo(label, pkg, cn, system));
        }
    }

    /** 打开某个应用。失败返回 false（调用方提示「这个应用打不开」）。 */
    public static boolean launch(Context context, AppInfo info) {
        if (info == null || info.getPkg() == null) return false;
        // 合成条目（系统设置）没有具体 Activity：先走 ACTION_SETTINGS，
        // 被裁掉的固件再试一次系统设置包的启动意图，两条都不成才返回 false。
        if (info.getComponent() == null) {
            try {
                Intent settings = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(settings);
                return true;
            } catch (Throwable ignored) {
            }
            try {
                Intent fallback = context.getPackageManager().getLaunchIntentForPackage(info.getPkg());
                if (fallback == null) return false;
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(info.getComponent())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            // 少数 ROM 的入口 Activity 不允许被外部以 LAUNCHER 类别启动，退一步用 getLaunchIntentForPackage
            try {
                Intent fallback = context.getPackageManager().getLaunchIntentForPackage(info.getPkg());
                if (fallback == null) return false;
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    public static Set<String> split(String csv) {
        if (csv == null || csv.isEmpty()) return new HashSet<>();
        return new HashSet<>(Arrays.asList(csv.split(",")));
    }

    /** 保序版本：置顶顺序必须原样保留（见 {@link #load} 里置顶那一段）。 */
    public static List<String> splitOrdered(String csv) {
        List<String> list = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return list;
        for (String item : csv.split(",")) {
            if (item == null) continue;
            String one = item.trim();
            if (!one.isEmpty() && !list.contains(one)) list.add(one);
        }
        return list;
    }

    /** 有序集合 → 逗号串（写回偏好时用；空集合得到空串，而不是 "null"）。 */
    public static String join(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String item : list) {
            if (item == null || item.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(item);
        }
        return sb.toString();
    }

    /** 是否在置顶列表里（应用列表页的「放到最前 / 取消置顶」用）。 */
    public static boolean isPinned(String csv, String pkg) {
        return pkg != null && splitOrdered(csv).contains(pkg);
    }
}
