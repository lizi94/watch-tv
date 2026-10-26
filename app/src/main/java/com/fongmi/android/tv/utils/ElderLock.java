package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.List;

/**
 * 一键锁定（按 TVBox 原生逻辑收敛）：
 * 原生只有两个「激活对象」——点播数据源（{@code VodConfig} 的 home site）与直播源（{@code LiveConfig} 的当前配置）。
 * 因此锁定只固定这几个对象，外加「自动择优换线」开关：
 * <ul>
 *   <li>数据源锁：固定当前生效的数据源，拦截一切换源入口；</li>
 *   <li>搜索源锁：锁定搜索源的切换/配置入口，搜索范围保持跨源不变（一键锁定时自动开启）；</li>
 *   <li>直播源锁：固定当前生效的直播源配置；</li>
 *   <li>线路开关：自动择优换线（与 {@code Setting#isAutoFallback()} 共用偏好键），保证播放成功率。</li>
 * </ul>
 *
 * <p>设计原则 —— <b>锁定只是「加一层默认值 + 隐藏选择入口」，不删除任何原有代码路径</b>：
 * <ul>
 *   <li>未锁定时，所有方法与原生行为完全一致，等同于本类不存在；</li>
 *   <li>锁定的源在配置里失效时，一律自动回退为原生行为，绝不把功能锁死；</li>
 *   <li>解锁入口固定存在（锁定页唯一的按钮：未锁时「一键锁定」、已锁时「解锁」，点击即切换）。</li>
 * </ul>
 *
 * <p>锁定的数据源本身通过现有的 {@code VodConfig.setHome()} 持久化，
 * 因此重启后由原生加载逻辑自动恢复，无需额外的开机钩子，避免与配置加载流程互相触发。</p>
 */
public class ElderLock {

    /** 是否处于锁定状态。 */
    private static final String KEY_ON = "elder_lock_on";
    /** 锁定的数据源 key（即 TVBox 原生「激活的首页源」，锁定后通过 {@code VodConfig.setHome} 持久化）。 */
    private static final String KEY_SITE = "elder_lock_site";
    /** 锁定的直播源配置 url（直播源为独立锁，不受数据源锁影响；恢复默认/解锁时一并清除）。 */
    private static final String KEY_LIVE = "elder_lock_live";
    /** 锁定搜索数据源开关：锁定时搜索页的「搜索源」入口静默隐藏，搜索范围保持跨源不变。 */
    private static final String KEY_SEARCH = "elder_lock_search";

    private ElderLock() {
    }

    public static boolean isLocked() {
        return Prefers.getBoolean(KEY_ON);
    }

    public static String getSiteKey() {
        return Prefers.getString(KEY_SITE);
    }

    /**
     * 自动择优换线是否开启。默认开启。
     *
     * <p>语义（与 {@code Setting#isAutoFallback()} 共用同一偏好键）：</p>
     * <ul>
     *   <li>开启 —— 隐藏线路选择条；优先用当前数据源自己的线路，逐条试；
     *       全部放不出来时自动跳其它数据源的线路继续试；</li>
     *   <li>关闭 —— 完全按原生行为，线路放不出来时不做任何回退。</li>
     * </ul>
     */
    public static boolean isRouteLocked() {
        return Setting.isAutoFallback();
    }

    public static void putRouteLocked(boolean locked) {
        Setting.putAutoFallback(locked);
    }

    /** 写入锁定状态：固定当前数据源（home key），首页/搜索/播放全部跟随这一个源。 */
    public static void lock(String siteKey) {
        if (siteKey == null || siteKey.isEmpty()) return;
        Prefers.put(KEY_SITE, siteKey);
        Prefers.put(KEY_ON, true);
        // 一键锁定 = 页面上所有项一起锁：搜索源一并锁定（搜索范围保持跨源，只锁换源入口）
        Prefers.put(KEY_SEARCH, true);
    }

    public static void unlock() {
        Prefers.put(KEY_ON, false);
        putLiveKey("");
        Prefers.put(KEY_SEARCH, true);
    }

    /** 清除锁定相关偏好（供「恢复默认设置」调用）。 */
    public static void clear() {
        Prefers.put(KEY_ON, false);
        Prefers.put(KEY_SITE, "");
        putLiveKey("");
        Prefers.put(KEY_SEARCH, true);
        Setting.putAutoFallback(true);
    }

    /**
     * 直播源是否被锁定。独立于数据源锁：只锁「直播源配置」的切换入口，
     * 锁定的配置被删除时自动回退为原生行为，绝不把直播功能锁死。
     */
    public static boolean isLiveLocked() {
        return !getLiveKey().isEmpty();
    }

    /** 锁定的直播源配置 url。为空表示未锁定。 */
    public static String getLiveKey() {
        return Prefers.getString(KEY_LIVE);
    }

    public static void putLiveKey(String url) {
        Prefers.put(KEY_LIVE, url == null ? "" : url);
    }

    /**
     * 锁定的数据源对象。返回 {@code null} 表示未锁定或锁定的源已失效，调用方应保持原生行为。
     */
    public static Site main() {
        if (!isLocked()) return null;
        Site site = VodConfig.get().getSite(getSiteKey());
        return site.isEmpty() ? null : site;
    }

    /** 与 {@link #main()} 同义：锁定后首页/搜索/播放跟随同一个源。 */
    public static Site home() {
        return main();
    }

    /**
     * 搜索使用的源列表。搜索保持 TVBox 原生「跨全部可搜索源」——
     * 单锁一个源只会让老人更容易搜不到内容，因此锁定与否都不限制搜索范围。
     *
     * <p><b>差别只在顺序</b>：数据源锁 + 搜索优先开关同时生效时，锁定的源被排到列表首位。
     * 搜索结果按源的<b>提交顺序</b>追加（{@code SiteViewModel#searchContent} 把列表交给
     * {@code ViewModelSearchRunner#start} 逐个 submit，而 {@code Task.largeExecutor()}
     * 是固定 20 线程池，源数 ≤20 时按提交顺序启动），因此锁定源的结果天然排在最前面，
     * 达到「搜索优先展示锁定的源」的效果，同时完全不牺牲跨源搜索范围。</p>
     */
    public static List<Site> searchSites() {
        List<Site> all = new ArrayList<>();
        Site preferred = preferredSite();
        if (preferred != null) all.add(preferred);
        for (Site site : VodConfig.get().getSites()) {
            if (!site.isSearchable()) continue;
            if (preferred != null && preferred.getKey().equals(site.getKey())) continue;
            all.add(site);
        }
        return all;
    }

    /**
     * 搜索优先展示的数据源。仅当「数据源已锁定」且「搜索优先开关开启」且「该源本身可搜索」时返回该源，
     * 否则返回 {@code null}（= 原生顺序）。开关关掉后一切回到原生行为。
     */
    public static Site preferredSite() {
        if (!isSearchLocked()) return null;
        Site site = main();
        return site != null && site.isSearchable() ? site : null;
    }

    /**
     * 搜索数据源是否锁定。随数据源锁一并生效（一键锁定时自动开启）。
     *
     * <p>锁定的只是「搜索源的切换/配置入口」（搜索页换源按钮、换搜索源弹窗），
     * 搜索范围保持跨源不变 —— 老人不会被问「搜哪个站」，但内容仍然来自全部可搜源。
     * 未锁定时本方法恒为 {@code false}，与原生行为一致。</p>
     */
    public static boolean isSearchLocked() {
        return isLocked() && Prefers.getBoolean(KEY_SEARCH, true);
    }

    public static void putSearchLocked(boolean locked) {
        Prefers.put(KEY_SEARCH, locked);
    }

    /** 播放页是否隐藏线路选择条。 */
    public static boolean hideRoute() {
        return isLocked() && isRouteLocked();
    }
}
