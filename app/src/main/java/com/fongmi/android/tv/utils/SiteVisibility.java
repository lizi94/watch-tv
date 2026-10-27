package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 站源启用状态（2026-10-27 新增，双端共用）。
 *
 * <p><b>为什么由用户决定而不是自动识别</b>：网盘源 / 私有库在不同配置里的 spider 命名毫无规律
 * （同一个「木偶 4K」，配置 A 叫 {@code FishCloud}、配置 B 叫 {@code NewMuOuGuard}），
 * 任何静态识别规则换一份配置就失效，猜错还会把正常源一并干掉。因此这里<b>不做任何自动判断</b>：
 * 导入后全部启用，由用户在「选择能看的站」里逐个关掉。</p>
 *
 * <p><b>为什么按配置分桶</b>：如果全局只存一份「已关掉的 key」，
 * 换配置时旧 key 全部失效（用户白选一遍），更糟的是新旧配置的<b>同名 key</b>
 * （{@code AList} / {@code Local} / {@code push_agent}）会被误关。
 * 所以存储键带上「本次导入使用的原始 URL」的指纹 —— 换配置自动隔离，
 * 切回旧配置还能恢复上次的选择。</p>
 *
 * <p><b>指纹用 URL 而不是 Config#getId()</b>：ID 是 Room 自增，重新导入会变，设置就丢了；
 * URL 是用户视角的「这是哪份配置」，同一地址重新导入能沿用。
 * depot（配置里含 {@code urls} 多源集合）按<b>depot 原始 URL</b>算，
 * 这样切换子源时选择不会变来变去。</p>
 *
 * <p>只存「已关掉」的集合（默认空 = 全部启用）：用户通常只关少数几个，
 * 存黑名单体积更小，且新配置新增的源天然是启用状态。</p>
 */
public final class SiteVisibility {

    /** 存已关掉 key 列表的键前缀（后面接配置指纹）。 */
    private static final String KEY_PREFIX = "site_disabled.";

    /** 记录出现过的配置指纹（按首次使用顺序），用于桶上限淘汰。 */
    private static final String KEY_FINGERPRINTS = "site_disabled_fingerprints";

    /** 最多保留的配置桶数量：超出的按最久未用淘汰，防 Prefs 无限膨胀。 */
    private static final int MAX_BUCKETS = 10;

    /** depot 形态下导入的原始 URL（由 VodConfig 在解析 depot 前写入）。 */
    private static volatile String depotUrl;

    private SiteVisibility() {
    }

    /** 由 {@code VodConfig#parseDepot} 在解析前记录原始 URL，保证 depot 整体共用一份选择。 */
    public static void setDepotUrl(String url) {
        depotUrl = url;
    }

    /** 当前配置的指纹：depot 原始 URL 优先，其次当前配置 URL；都没有时用 {@code default}。 */
    public static String fingerprint() {
        String url = depotUrl;
        if (TextUtils.isEmpty(url)) url = VodConfig.getUrl();
        if (TextUtils.isEmpty(url)) return "default";
        String md5 = com.github.catvod.utils.Crypto.md5(url);
        return TextUtils.isEmpty(md5) ? "default" : md5;
    }

    private static String key() {
        return KEY_PREFIX + fingerprint();
    }

    /** 读当前桶的「已关掉」集合；解析失败一律当空集（默认全部启用）。 */
    private static Set<String> load() {
        try {
            String json = Prefers.getString(key());
            if (TextUtils.isEmpty(json)) return new HashSet<>();
            List<String> list = App.gson().fromJson(json, new TypeToken<List<String>>() {
            }.getType());
            return list == null ? new HashSet<>() : new HashSet<>(list);
        } catch (Throwable e) {
            return new HashSet<>();
        }
    }

    private static void save(Set<String> disabled) {
        try {
            Prefers.put(key(), App.gson().toJson(new ArrayList<>(disabled)));
            touchBucket();
        } catch (Throwable ignored) {
            // 写盘失败不影响内存态：本次选择仍生效，只是下次启动可能丢，绝不因此崩溃。
        }
    }

    /** 记录当前指纹并按上限淘汰最旧的桶。 */
    private static void touchBucket() {
        try {
            String current = key();
            List<String> keys = readBuckets();
            keys.remove(current);
            keys.add(current);
            while (keys.size() > MAX_BUCKETS) Prefers.put(keys.remove(0), "");
            Prefers.put(KEY_FINGERPRINTS, App.gson().toJson(keys));
        } catch (Throwable ignored) {
        }
    }

    private static List<String> readBuckets() {
        try {
            String json = Prefers.getString(KEY_FINGERPRINTS);
            if (TextUtils.isEmpty(json)) return new ArrayList<>();
            List<String> list = App.gson().fromJson(json, new TypeToken<List<String>>() {
            }.getType());
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    /** 该站源是否启用（默认启用）。 */
    public static boolean isEnabled(Site site) {
        return site == null || isKeyEnabled(site.getKey());
    }

    /**
     * 当前桶的一次性快照（不可变）。
     *
     * <p>⚠ 批量过滤必须用这个：{@link #isKeyEnabled(String)} 每次都读 Prefs + 解析一次 JSON，
     * 历史 100 条就会解析 100 次 —— 那是实打实的卡顿。凡是「一次处理一批记录」的地方，
     * 先取一次快照再在内存里判断。</p>
     */
    public static Set<String> disabledSnapshot() {
        return new HashSet<>(load());
    }

    /** 按 key 判定是否启用；key 为空视为启用（老记录没有 key，不能因此把它们藏起来）。 */
    public static boolean isKeyEnabled(String key) {
        if (key == null || key.isEmpty()) return true;
        return !load().contains(key);
    }

    /** 设置启用状态：启用 = 从「已关掉」集合移除；关掉 = 加入。 */
    public static void setEnabled(Site site, boolean enabled) {
        if (site == null || site.getKey().isEmpty()) return;
        Set<String> disabled = load();
        if (enabled) disabled.remove(site.getKey());
        else disabled.add(site.getKey());
        save(disabled);
    }

    public static void toggle(Site site) {
        if (site == null) return;
        setEnabled(site, !isEnabled(site));
    }

    /**
     * 批量启用 / 关掉。
     *
     * <p>启用（「全部打开」）= 直接清空桶，等价恢复出厂；关掉（「全部关掉」）就是把
     * 当前可见的全部 key 记进桶 —— 一次性写入，不在循环里逐条 save。</p>
     */
    public static void setAll(List<Site> sites, boolean enabled) {
        if (enabled) {
            save(new HashSet<>());
            return;
        }
        Set<String> disabled = new LinkedHashSet<>();
        if (sites != null) for (Site site : sites) if (!site.getKey().isEmpty()) disabled.add(site.getKey());
        if (disabled.equals(load())) return;   // 已经是全关：不重复写盘
        save(disabled);
    }

    /**
     * 清空当前配置的站源启停选择（保留 API）。
     *
     * <p>目前「恢复默认设置」<b>不调用</b>它：站源启停属于「数据源配置」范畴，
     * 与收藏/观看记录同口径保留。未来若要在恢复默认时一并清空站源选择，调用这里。</p>
     */
    public static void reset() {
        try {
            Prefers.put(key(), "");
        } catch (Throwable ignored) {
        }
    }

    /** 过滤出可见站点：保持原顺序，只剔除用户关掉的。 */
    public static List<Site> filter(List<Site> all) {
        if (all == null || all.isEmpty()) return new ArrayList<>();
        Set<String> disabled = load();
        if (disabled.isEmpty()) return new ArrayList<>(all);
        List<Site> visible = new ArrayList<>(all.size());
        for (Site site : all) if (!disabled.contains(site.getKey())) visible.add(site);
        return visible;
    }

    /** 已启用数量（用于「已开 20 / 共 80」）。 */
    public static int countEnabled(List<Site> all) {
        if (all == null || all.isEmpty()) return 0;
        Set<String> disabled = load();
        int count = 0;
        for (Site site : all) if (!disabled.contains(site.getKey())) count++;
        return count;
    }

    /**
     * 修剪当前桶里「配置中已不存在」的 key —— 配置删源后桶自动收缩，避免无限堆积。
     * 由 {@code VodConfig} 装载完成后调用。
     */
    public static void prune(List<Site> all) {
        try {
            Set<String> disabled = load();
            if (disabled.isEmpty()) return;
            Set<String> keys = new HashSet<>();
            if (all != null) for (Site site : all) keys.add(site.getKey());
            if (disabled.retainAll(keys)) save(disabled);
        } catch (Throwable ignored) {
        }
    }
}
