package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.Setting;

import java.util.ArrayList;
import java.util.List;

class VodFallbackPolicy {

    private final VodPlaybackController controller;
    private final VodPlaybackState state;
    private final VodPlaybackHost host;
    private final VodDataSource dataSource;
    /** 播放中途失败的「原地重试」是否已用掉：每个数据源会话只重试一次，避免与自动换线叠加造成反复横跳。 */
    private boolean midPlayRetryUsed;

    VodFallbackPolicy(VodPlaybackController controller, VodPlaybackState state, VodPlaybackHost host, VodDataSource dataSource) {
        this.controller = controller;
        this.state = state;
        this.host = host;
        this.dataSource = dataSource;
    }

    void playbackError() {
        // 先记账：这条线路放不出来，削弱它的健康分，下次不会被优先选中。
        // 成功样本由 VodPlaybackController#playbackStarted() 在首帧出现时记录。
        recordLineFailure();
        if (tryMidPlayRetry()) return;
        fallbackToNextLineOrSource();
    }

    /**
     * 播放中途（首帧已出）网络波动导致的失败兜底：先在<b>原线路</b>原地重试一次，
     * 重试也失败才走换线/换源。起播阶段的失败（首帧从未出现）不重试，直接换，
     * 老人不等。每个数据源会话只重试一次，防止「重试-失败-再重试」来回横跳。
     */
    private boolean tryMidPlayRetry() {
        if (midPlayRetryUsed || !controller.hasPlaybackStarted()) return false;
        midPlayRetryUsed = true;
        controller.refresh();
        return true;
    }

    /** 换数据源后重置原地重试预算。 */
    void resetMidPlayRetry() {
        midPlayRetryUsed = false;
    }

    void emptyFlag() {
        fallbackToNextLineOrSource();
    }

    void emptyDetail() {
        // 详情都取不到，说明这个源当前整体不可用 —— 自动换源打开时直接换下一个源，
        // 不必再等用户手动操作。失败过的源会记入 failedIds，因此不会来回死循环。
        fallbackToNextSource(Setting.isAutoFallback());
    }

    void manualSwitchSource() {
        fallbackToNextSource(true);
    }

    void search(String keyword, boolean autoFallback) {
        state.setSearchKeyword(keyword);
        // ⛔ 2026-10-24 修：这里原来还写 state.setAutoFallback(autoFallback) ——
        // 手动换一次源就会把「自动换源」永久置 true，之后用户即便把「播放线路」设成手动，
        // 任何播放错误仍会自动跨源（与该开关「关闭即完全按原生、不做回退」的语义矛盾）。
        // 跨源能力由 selectFirstSource（本次搜索意图）保证，不需要污染用户配置值。
        state.setSelectFirstSource(autoFallback);
        host.onSearchStarted(keyword);
        dataSource.searchContent(getSearchableSites(), keyword, true);
    }

    void onSearchResult(Result result) {
        List<Vod> items = new ArrayList<>(result.getList());
        items.removeIf(this::mismatch);
        state.setSources(items);
        if (state.isSelectFirstSource() && state.hasSources()) nextSource();
        else host.renderSources(state.getSources());
        if (items.isEmpty()) return;
        host.onSearchResult();
    }

    private void fallbackToNextLineOrSource() {
        // 原生：站源不可切换时直接放弃回退。适老化「自动换源」打开后放宽这一限制，
        // 否则一键锁定成单一数据源的老人一旦碰到某个源整体失效，就只能停在错误页。
        boolean auto = Setting.isAutoFallback();
        if (!host.isSiteChangeable() && !auto) return;
        if (fallbackToNextLine()) return;
        fallbackToNextSource(auto);
    }

    /**
     * 源内换线。原实现是「下标 +1」的机械顺延；现改为在<b>尚未试过</b>的线路里
     * 挑健康分最高的一条 —— 也就是「实时检测后自动择优」。
     *
     * <p>没有历史记录的线路得分同为中性分，并列时取原顺序靠前的，
     * 因此首次使用时与原生行为完全一致。</p>
     */
    private boolean fallbackToNextLine() {
        List<Flag> flags = state.getFlags();
        if (flags.size() < 2) return false;
        Flag current = state.getFlag();
        state.addTriedFlag(current.getFlag());
        Flag next = LineHealth.nextLine(host.getVodKey(), flags, state.getTriedFlags());
        if (next == null || next.equals(current)) return false;
        host.showSwitchLine(next);
        controller.selectFlag(next);
        return true;
    }

    private void fallbackToNextSource(boolean force) {
        // 搜索意图由「本次调用意图」决定，不再硬编码 true（见 search() 的说明）
        if (!state.hasSources()) search(host.getVodName(), force || state.isAutoFallback());
        else if (state.isAutoFallback() || force) nextSource();
    }

    /**
     * 换到其它数据源。原实现固定取搜索结果的第一个；现改为按「该源已知线路的最高健康分」
     * 择优 —— 有可用记录的源优先，明显不可用的源排到最后，都没记录过的保持搜索原序。
     */
    private void nextSource() {
        if (!state.hasSources()) return;
        Vod item = LineHealth.bestSource(state.getSources());
        if (item == null) return;
        state.removeSource(item);
        host.renderSources(state.getSources());
        host.showSwitchSource(item);
        state.addFailedId(host.getVodId());
        state.setSelectFirstSource(false);
        // 换了源，线路列表完全是另一套，已试线路要重新计
        state.clearTriedFlags();
        controller.fallbackSource(item);
    }

    /** 把当前线路的本次失败计入健康度。 */
    private void recordLineFailure() {
        if (!state.hasFlags()) return;
        LineHealth.fail(host.getVodKey(), state.getFlag().getFlag());
    }

    private List<Site> getSearchableSites() {
        List<Site> sites = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) if (isPass(site)) sites.add(site);
        return sites;
    }

    private boolean isPass(Site item) {
        if (state.isAutoFallback() && !item.isChangeable()) return false;
        return item.isSearchable();
    }

    private boolean mismatch(Vod item) {
        if (host.getVodId().equals(item.getId())) return true;
        if (state.hasFailedId(item.getId())) return true;
        if (state.isAutoFallback()) return !item.getName().equals(state.getSearchKeyword());
        return !item.getName().contains(state.getSearchKeyword());
    }
}
