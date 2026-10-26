package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 播放线路健康度：实时记录每条线路的真实起播表现，用于自动择优。
 *
 * <p>记录维度是「数据源 key + 线路名」（线路名如「线路1」「HD」在所有影片间通用），
 * 统计两件事：起播是否成功、起播花了多久。</p>
 *
 * <p>得分规则（0 ~ 100）：</p>
 * <ul>
 *   <li>未知线路 = 60 分。不奖不罚，保证没测过的线路仍有合理机会被选中；</li>
 *   <li>一次成功起播 = 按耗时给 100（≤1.5 秒）到 55（≥8 秒）分，并与旧分平滑合并，
 *       避免一次网络抖动就把排序翻过来；</li>
 *   <li>一次失败 = 扣 35 分；连续失败 2 次直接归 0，后续不再被优先选中
 *       （线路仍留在列表里，用户想手动选依然选得到）。</li>
 * </ul>
 *
 * <p>记录持久化在偏好里，条目上限 {@value #MAX_ENTRIES} 条，超出按最久未用淘汰，
 * 不会无限增长。</p>
 */
public class LineHealth {

    private static final String KEY = "vod_line_health";
    private static final int MAX_ENTRIES = 240;
    private static final int MAX_FAILS = 2;
    /** 未知线路的中性分。 */
    private static final double BASE = 60d;
    private static final long FAST_MS = 1500L;
    private static final long SLOW_MS = 8000L;
    private static final double SLOWEST_SCORE = 55d;
    private static final double FAIL_PENALTY = 35d;

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static boolean loaded;

    private LineHealth() {
    }

    /** 单条线路的健康记录。字段名即 JSON 键名，改名会导致旧数据读不出来。 */
    public static class Entry {

        public String site = "";
        public String flag = "";
        public double score = BASE;
        public int fails;
        public long used;
    }

    private static String id(String site, String flag) {
        return site + "|" + flag;
    }

    private static Entry create(String site, String flag) {
        Entry item = new Entry();
        item.site = site;
        item.flag = flag;
        item.score = BASE;
        item.used = System.currentTimeMillis();
        return item;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        String json = Prefers.getString(KEY);
        if (json == null || json.isEmpty()) return;
        try {
            Entry[] items = App.gson().fromJson(json, Entry[].class);
            if (items == null) return;
            for (Entry item : items) {
                if (item == null || item.site == null || item.flag == null) continue;
                if (item.site.isEmpty() || item.flag.isEmpty()) continue;
                CACHE.put(id(item.site, item.flag), item);
            }
        } catch (Exception e) {
            // 记录损坏时丢弃重建，不能让统计影响正常播放
            CACHE.clear();
        }
    }

    private static void save() {
        try {
            List<Entry> items = new ArrayList<>(CACHE.values());
            Collections.sort(items, Comparator.comparingLong((Entry item) -> item.used).reversed());
            if (items.size() > MAX_ENTRIES) items = new ArrayList<>(items.subList(0, MAX_ENTRIES));
            Prefers.put(KEY, App.gson().toJson(items));
        } catch (Exception e) {
            // 存不进去也不影响本次播放
        }
    }

    /** 线路健康分。未知线路返回中性分 {@value #BASE}。 */
    public static double score(String site, String flag) {
        if (isEmpty(site) || isEmpty(flag)) return BASE;
        ensureLoaded();
        Entry item = CACHE.get(id(site, flag));
        return item == null ? BASE : item.score;
    }

    /** 起播成功：按起播耗时给分，连续失败计数清零。 */
    public static void success(String site, String flag, long costMs) {
        if (isEmpty(site) || isEmpty(flag)) return;
        ensureLoaded();
        Entry item = CACHE.get(id(site, flag));
        if (item == null) item = create(site, flag);
        long cost = costMs < FAST_MS ? FAST_MS : Math.min(costMs, SLOW_MS);
        double ratio = (double) (cost - FAST_MS) / (double) (SLOW_MS - FAST_MS);
        double target = 100d - (100d - SLOWEST_SCORE) * ratio;
        item.score = item.score * 0.4d + target * 0.6d;
        item.fails = 0;
        item.used = System.currentTimeMillis();
        CACHE.put(id(site, flag), item);
        save();
    }

    /** 起播失败：直接降权；连续失败达到 {@value #MAX_FAILS} 次后归零。 */
    public static void fail(String site, String flag) {
        if (isEmpty(site) || isEmpty(flag)) return;
        ensureLoaded();
        Entry item = CACHE.get(id(site, flag));
        if (item == null) item = create(site, flag);
        item.fails++;
        item.score = item.fails >= MAX_FAILS ? 0d : Math.max(0d, item.score - FAIL_PENALTY);
        item.used = System.currentTimeMillis();
        CACHE.put(id(site, flag), item);
        save();
    }

    /** 清空全部记录（供「恢复默认设置」调用）。 */
    public static void clear() {
        CACHE.clear();
        loaded = true;
        Prefers.put(KEY, "");
    }

    /**
     * 源内换线：在「尚未试过」的线路里挑健康分最高的。
     *
     * <p>并列时保留 {@code flags} 的原始顺序，因此从未记录过的源行为与原生一致。</p>
     */
    public static Flag nextLine(String site, List<Flag> flags, Set<String> tried) {
        Flag best = null;
        double bestScore = -1d;
        for (Flag flag : flags) {
            String name = flag.getFlag();
            if (tried != null && tried.contains(name)) continue;
            double score = score(site, name);
            if (score > bestScore) {
                bestScore = score;
                best = flag;
            }
        }
        return best;
    }

    /**
     * 跨源择优：优先选择「已知线路健康分更高」的数据源；都没记录过时按搜索结果原序。
     *
     * <p>取该源下所有已记录线路的最高分作为源分。全部线路都失败的源得 0 分，
     * 会排到最后，等价于把明显不可用的源降级。</p>
     */
    public static Vod bestSource(List<Vod> items) {
        if (items == null || items.isEmpty()) return null;
        if (items.size() == 1) return items.get(0);
        Vod best = null;
        double bestScore = -1d;
        for (Vod item : items) {
            double score = sourceScore(siteKey(item));
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best == null ? items.get(0) : best;
    }

    /** 某个数据源的整体健康分：该源下已记录线路里的最高分。 */
    private static double sourceScore(String site) {
        if (isEmpty(site)) return BASE;
        ensureLoaded();
        double best = BASE;
        boolean found = false;
        for (Entry item : CACHE.values()) {
            if (!site.equals(item.site)) continue;
            if (!found || item.score > best) {
                best = item.score;
                found = true;
            }
        }
        return found ? best : BASE;
    }

    /** 搜索结果里的数据源 key。取不到时返回空串，调用方按中性分处理。 */
    public static String siteKey(Vod item) {
        if (item == null) return "";
        Site site = item.getSite();
        return site == null ? "" : site.getKey();
    }

    private static boolean isEmpty(String text) {
        return text == null || text.isEmpty();
    }
}
