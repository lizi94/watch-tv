package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;

import com.fongmi.android.tv.service.HomeGuardService;

import java.util.Set;

/**
 * 「这台电视现在允许我们做哪些桌面相关的事」+ 所有跳到系统页面的兜底（R101）。
 *
 * <p><b>为什么把跳转集中在这里</b>：电视固件（TCL / 雷鸟 / 部分小米）经常把系统设置里的
 * 某些页面裁掉或改名，裸 {@code startActivity} 会直接 {@code ActivityNotFoundException} 闪退。
 * 每一级都必须先 {@code resolveActivity} 判存在，再跳 —— 这套判断写三处必然漏一处，
 * 所以只有这一份实现，首页与应用列表、体检页共用。</p>
 *
 * <p><b>@return 语义</b>：{@code true} = 已经发起跳转（或至少有一个页面可用）；
 * {@code false} = 四级别都不可用，调用方负责提示「自己去系统设置里改」。
 * 调用方不要自己再 catch，异常已在这里吃掉。</p>
 */
public final class LauncherCapability {

    private LauncherCapability() {
    }

    // ------------------------------------------------------------------ 状态

    /** 本应用是不是系统默认桌面（HOME 键真正会去的那一个）。 */
    public static boolean isSelfHome(Context context) {
        return LauncherInfo.isSelfHome(context);
    }

    /** 本机除自己以外的 HOME 候选包名（安全阀判据的数据源）。 */
    public static Set<String> getOthers(Context context) {
        return LauncherInfo.getOthers(context);
    }

    /** 本机是否还存在别的桌面（R123：三源交叉，含被停用 / 官方接口，见 LauncherInfo#hasOtherLauncher）。 */
    public static boolean hasOtherLauncher(Context context) {
        return LauncherInfo.hasOtherLauncher(context);
    }

    /** 强制重算（{@code onResume} 用：用户可能刚在系统设置里改完默认桌面就回来了）。 */
    public static void refresh(Context context) {
        LauncherInfo.refresh(context);
    }

    /**
     * 安全阀判据：本机<b>只剩我们一个</b> HOME 候选 —— 这正是各类「第三方桌面把电视困住」事故的现场
     * （原厂桌面被禁用 / 卸载）。
     *
     * <p>⛔ 判据必须是「自己是默认桌面 <b>且</b> 本机确实没有别的桌面」。
     * 不能只看 {@code DesktopResolver#get()} 是否为空 —— 那个方法按设计在「自己是默认桌面」时
     * 就返回空集，拿它当判据会让每一台设了默认桌面的机器都误报（复审 R10）。</p>
     *
     * <p>⛔⛔ R123：第二条不能再用 {@code getOthers().isEmpty()}。单次
     * {@code queryIntentActivities(MAIN+HOME, 0)} 在「本应用刚被设为默认桌面」的 ROM 上会漏掉
     * 原厂桌面（被系统摘掉 / 被冻结），于是「设了默认桌面」的绝大多数正常机器都被判成
     * 「本机只剩我一个」⇒ 首页那条提示条每次都误报。改用 {@link #hasOtherLauncher(Context)}
     * 的三源交叉：任一源查到别的桌面就不算「只剩我」。</p>
     *
     * <p>⚠ R123 起首页那条提示条已整条删除，本方法只剩两处调用：应用列表的「换回电视自带的主页 /
     * 去设置」按钮文案、体检页的静态告知。两处都是用户主动打开才看到，误报代价远小于首页常驻。</p>
     */
    public static boolean isOnlyHome(Context context) {
        return LauncherInfo.isSelfHome(context) && !LauncherInfo.hasOtherLauncher(context);
    }

    /**
     * 无障碍服务是否<b>真的</b>已启用（不是「开关打开了」，而是系统里那个开关也开了）。
     *
     * <p>{@link HomeGuardService} 没有对外暴露连接状态，而「服务连没连上」在进程重启、
     * 用户中途关开关时会与偏好里的布尔值不一致 —— 所以直接读系统的启用列表，
     * 这一项是唯一事实源。读不到（部分 ROM 抛异常）时按「未启用」处理：宁可多引导一次，
     * 也不能显示「已开启」却根本不生效（那才是真正的骗人开关）。</p>
     */
    public static boolean isA11yOn(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) return false;
            ComponentName self = new ComponentName(context, HomeGuardService.class);
            String flat = self.flattenToString();
            String shortFlat = self.flattenToShortString();
            for (String item : enabled.split(":")) {
                if (item == null) continue;
                String one = item.trim();
                if (one.equalsIgnoreCase(flat) || one.equalsIgnoreCase(shortFlat)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已在电池优化白名单里（= 系统不太会把这个应用冻掉）。 */
    public static boolean isBatteryOk(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 跳转

    /**
     * 跳到「默认桌面」相关的系统设置页，四级兜底：
     *
     * <pre>
     * ① android.settings.HOME_SETTINGS                  —— 标准页
     * ② android.settings.MANAGE_DEFAULT_APPS_SETTINGS   —— 部分 TV 固件只开这一个
     * ③ ACTION_APPLICATION_DETAILS_SETTINGS(package:self) —— 「默认打开」里可清默认
     * ④ 都不可用 → 返回 false，调用方提示「自己去系统设置里改」
     * </pre>
     *
     * @return true = 已发起跳转；false = 这台电视一个可用页面都没有
     */
    public static boolean openHomeSettings(Activity activity) {
        if (activity == null) return false;
        if (start(activity, new Intent(Settings.ACTION_HOME_SETTINGS))) return true;
        if (start(activity, new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))) return true;
        return openAppDetails(activity);
    }

    /** 直接打开「系统设置」首页（安全阀态下的出口：本机没有别的主页可选，只能自己去翻）。 */
    public static boolean openSettings(Activity activity) {
        if (activity == null) return false;
        if (start(activity, new Intent(Settings.ACTION_SETTINGS))) return true;
        return openAppDetails(activity);
    }

    /** 本应用的系统详情页（「默认打开」「卸载」都在这一页）。 */
    public static boolean openAppDetails(Activity activity) {
        if (activity == null) return false;
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", activity.getPackageName(), null));
        return start(activity, intent);
    }

    /**
     * 发起卸载（R122）：跳系统卸载页，页面自带二次确认。
     * ⚠ manifest 必须声明 REQUEST_DELETE_PACKAGES，否则 API 26+ 对别的包静默失败。
     */
    public static boolean uninstall(Activity activity, String pkg) {
        if (activity == null || pkg == null || pkg.isEmpty()) return false;
        return start(activity, new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)));
    }

    /** 系统的「无障碍 / 已安装的服务」页（让用户打开看电视的那个开关）。 */
    public static boolean openA11ySettings(Activity activity) {
        if (activity == null) return false;
        if (start(activity, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) return true;
        return openAppDetails(activity);
    }

    /**
     * 申请加入电池优化白名单。
     *
     * <p>优先用 {@code ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS}（带包名，直接弹系统确认框，
     * 老人只需按一次「允许」）；固件裁掉该页时退回「电池优化列表」页，再不行退回应用详情页。
     * 三步都不成只有 {@code false} —— 调用方提示一句即可，绝不能因此崩溃。</p>
     */
    public static boolean requestBattery(Activity activity) {
        if (activity == null) return false;
        Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.fromParts("package", activity.getPackageName(), null));
        if (start(activity, request)) return true;
        if (start(activity, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return true;
        return openAppDetails(activity);
    }

    /** 先判「有没有 Activity 接这个 Intent」再跳 —— 这一步就是为了不吃 ActivityNotFoundException。 */
    private static boolean start(Activity activity, Intent intent) {
        try {
            if (intent.resolveActivity(activity.getPackageManager()) == null) return false;
            activity.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
