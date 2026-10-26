package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.playback.PlaybackResult;

import java.util.Collections;
import java.util.List;

public class VodPlaybackController {

    private static final String PUSH_PREFIX = "push://";
    private static final String SEARCH_PREFIX = "msearch:";
    /**
     * 起播看门狗超时：发出线路请求后 10 秒仍未出首帧即判定该线路失败。
     * 有些线路会永远停在缓冲态而不触发播放器错误事件，若只依赖 onError，
     * 老人就会面对一个一直转圈的播放页 —— 看门狗保证这种情况也会自动换线/换源。
     */
    private static final long START_TIMEOUT_MS = 10000L;

    private final VodPlaybackHost host;
    private final VodDataSource dataSource;
    private final VodPlaybackState state;
    private final VodHistoryPolicy historyPolicy;
    private final VodFallbackPolicy fallbackPolicy;
    private final VodPreloader preloader;
    private History lastHistory;
    /** 本次线路请求的发出时刻，用于统计起播耗时（0 表示当前没有待统计的请求）。 */
    private long playRequestTime;
    /** 首帧是否已出（本次起播会话内）。用于区分「起播失败」与「播放中途失败」。 */
    private boolean playbackHasStarted;
    /** 起播看门狗任务：到点仍未出首帧就走 playbackError 同链路自动换线/换源。 */
    private final Runnable startWatchdog = this::onStartTimeout;

    public VodPlaybackController(VodPlaybackHost host, VodDataSource dataSource, VodPlaybackState state) {
        this.host = host;
        this.dataSource = dataSource;
        this.state = state;
        this.historyPolicy = new VodHistoryPolicy();
        this.fallbackPolicy = new VodFallbackPolicy(this, state, host, dataSource);
        this.preloader = new VodPreloader(host, dataSource, state);
    }

    public void reset() {
        playRequestTime = 0L;
        cancelStartWatchdog();
        preloader.clear();
        // ⚠ 2026-10-16 审计修复（P2）：playbackHasStarted 是控制器字段，reset() 清不掉 ⇒
        // onNewIntent 换片复用同一控制器时，新片「起播阶段」的失败会被误判成「播放中失败」，
        // 走 tryMidPlayRetry 原地重试，而不是设计好的「起播失败直接换线，老人不等」。
        playbackHasStarted = false;
        state.reset();
    }

    /** 起播看门狗：每次发出线路请求都重新计时。 */
    private void armStartWatchdog() {
        App.removeCallbacks(startWatchdog);
        App.post(startWatchdog, START_TIMEOUT_MS);
    }

    private void cancelStartWatchdog() {
        App.removeCallbacks(startWatchdog);
    }

    /** 看门狗到点：仍未出首帧（playRequestTime 未清零）按播放错误处理，走同一条换线/换源链路。 */
    private void onStartTimeout() {
        if (playRequestTime == 0L) return;
        // ⚠ 2026-10-16 审计修复（P10）：原文案是「线路加载超时，正在自动换源…」，但实际动作是
        // 「**先换线路**，换不动了才换源」（fallbackToNextLineOrSource → 先 nextLine 再 nextSource）
        // ⇒ 提示与行为不符，老人会以为已经换源了。改成与实际一致的表述。
        playbackError("线路加载超时，正在尝试其它线路…");
    }

    /**
     * 播放真正开始（首帧已出）。这是线路健康度的<b>成功样本</b>来源：
     * 用「请求发出 → 首帧显示」的耗时给线路打分。
     *
     * <p>只在紧随一次线路请求之后记一次 —— {@code playRequestTime} 用完即清，
     * 因此暂停后再恢复不会重复计入。</p>
     */
    public void playbackStarted() {
        if (playRequestTime == 0L) return;
        long cost = System.currentTimeMillis() - playRequestTime;
        playRequestTime = 0L;
        // 首帧已出，撤销起播看门狗
        cancelStartWatchdog();
        playbackHasStarted = true;
        if (!state.hasFlags()) return;
        LineHealth.success(host.getVodKey(), state.getFlag().getFlag(), cost);
        // 已经能放了，重置「已试线路」—— 下一集若出问题，应从全部线路里重新择优，
        // 而不是被上一集的失败记录锁住可选范围。
        state.clearTriedFlags();
    }

    public void checkId() {
        String id = resolveVodId();
        if (id.isEmpty() || id.startsWith(SEARCH_PREFIX)) detailEmpty(false);
        else if (!state.isDetailRequested(host.getVodKey(), id)) requestDetail();
    }

    private String resolveVodId() {
        String id = host.getVodId();
        if (!id.startsWith(PUSH_PREFIX)) return id;
        host.usePushId(id.substring(PUSH_PREFIX.length()));
        return host.getVodId();
    }

    public void requestDetail() {
        String key = host.getVodKey();
        String id = host.getVodId();
        state.setDetailRequest(key, id);
        dataSource.detailContent(key, id);
    }

    public void onDetailResult(VodDetailResult detail) {
        if (detail == null || !detail.matches(host.getVodKey(), host.getVodId())) return;
        Result result = detail.result();
        if (result.getList().isEmpty()) detailEmpty(result.hasMsg());
        else detailLoaded(result.getVod());
        host.showDetailMessage(result.getMsg());
    }

    public void updateVod(Vod item) {
        if (preloader.isRequestPending()) return;
        History history = state.getHistory();
        replaceVodId(history, item.getId());
        mergeFlags(item.getFlags());
        updateHistory(history, item);
        host.renderVodUpdate(item);
        publishActivePlaybackMetadata();
    }

    private void updateHistory(History history, Vod item) {
        String pic = item.getPic();
        String name = item.getName();
        boolean hasPic = !pic.isEmpty();
        boolean hasName = !name.isEmpty();
        if (hasPic) history.setVodPic(pic);
        if (hasName) history.setVodName(name);
        if (hasName || hasPic) historyPolicy.saveCurrent(history);
    }

    private void publishActivePlaybackMetadata() {
        Episode episode = findEpisode(state.getActiveRequest());
        if (episode != null) publishPlaybackMetadata(episode);
    }

    private void replaceVodId(History history, String id) {
        if (id.isEmpty() || id.equals(host.getVodId())) return;
        String oldKey = host.getHistoryKey();
        host.setVodId(id);
        String newKey = host.getHistoryKey();
        history.replace(newKey);
        Keep.replace(oldKey, newKey);
    }

    public void onPlaybackResult(PlaybackResult<VodPlayRequest> playback) {
        if (playback == null || cannotApply(playback)) return;
        applyPlaybackResult(playback.result(), playback.request());
    }

    private void applyPlaybackResult(Result result, VodPlayRequest request) {
        Episode episode = findEpisode(request);
        if (episode == null) return;
        applyPlaybackState(result, request);
        renderPlaybackResult(result);
        updatePlaybackPosition(result);
        host.loadDanmaku(result, state.getHistory(), episode);
        startPlayback(result, startPositionMs(), episode);
        preloader.update(result);
    }

    private void applyPlaybackState(Result result, VodPlayRequest request) {
        state.setQuality(result);
        state.setPlayingRequest(request);
        state.setUseParse(result.isUseParse());
        result.getUrl().set(state.getQualityPosition());
    }

    private void renderPlaybackResult(Result result) {
        host.renderUseParse(state.isUseParse());
        host.renderQuality(result, result.getUrl().isMulti());
        if (result.hasDesc()) host.renderDescription(result.getDesc());
        if (result.hasArtwork()) host.renderArtwork(result.getArtwork());
    }

    private void updatePlaybackPosition(Result result) {
        if (result.hasPosition()) state.getHistory().setPosition(result.getPosition());
    }

    private void startPlayback(Result result, long startPositionMs, Episode episode) {
        MediaMetadata metadata = publishPlaybackMetadata(episode);
        host.startPlayback(result, state.isUseParse(), startPositionMs, metadata);
    }

    public void onSearchResult(Result result) {
        fallbackPolicy.onSearchResult(result);
    }

    public void selectFlag(Flag item) {
        selectFlag(item, false);
    }

    private void selectFlag(Flag item, boolean force) {
        if (!state.hasFlags()) return;
        Flag selected = resolveFlag(item);
        if (!force && selected.isSelected()) return;
        preloader.clear();
        for (Flag flag : state.getFlags()) flag.setSelected(selected);
        host.renderFlagSelection(selected);
        host.renderEpisodes(selected.getEpisodes());
        host.renderQualityVisible(false);
        seamless(selected);
    }

    public void selectEpisode(Episode item) {
        if (!state.hasFlags()) return;
        saveCurrentHistory();
        Flag selected = state.getFlag();
        for (Flag flag : state.getFlags()) flag.toggle(flag == selected, item);
        historyPolicy.updateEpisode(state.getHistory(), state.getFlag(), item);
        host.renderEpisodeSelection(item);
        if (host.isFullscreenForPlayback()) host.showEpisodeReady(item);
        playEpisode(item);
    }

    private void playEpisode(Episode item) {
        Result result = preloader.consume(item);
        host.stopPlaybackForRefresh();
        if (result == null) requestSelectedEpisode();
        else {
            // ⚠ 2026-10-16 审计修复（P5）：预加载命中的这条路原先**直接 applyPlaybackResult**，
            // 绕过了 requestPlayer 里的看门狗与首帧标记复位 ⇒ 两个后果：
            //   ① 预加载的下一集若「永远缓冲不出画」且不触发 onError，**没有任何兜底 ⇒ 无限转圈**
            //      （看门狗本就是为这种场景设计的，见 START_TIMEOUT_MS 的注释）；
            //   ② playbackHasStarted 仍是上一集的 true ⇒ 起播失败被误判成「播放中途失败」，
            //      走 tryMidPlayRetry 原地重试而不是设计好的「起播失败直接换线」。
            armStartGuard();
            applyPlaybackResult(result, VodPlayRequest.create(host.getVodKey(), state.getFlag(), item));
        }
    }

    /**
     * 起播守卫：记录请求时刻 + 复位首帧标记 + 起看门狗。
     *
     * <p>{@link #requestPlayer} 与 {@link #playEpisode}（预加载命中）两条起播路径<b>必须</b>共用本方法 ——
     * 少一处就会出现「无兜底无限转圈」或「起播失败被当成中途失败」（P5）。</p>
     */
    private void armStartGuard() {
        playRequestTime = System.currentTimeMillis();
        playbackHasStarted = false;
        armStartWatchdog();
    }

    public void selectQuality(Result result) {
        if (!state.hasEpisode()) return;
        state.setQuality(result);
        state.setQualityPosition(result.getUrl().getPosition());
        preloader.clear();
        startPlayback(result, host.getPlayerPosition(), state.getEpisode());
        preloader.preloadNext();
    }

    public void selectParse(Parse item) {
        VodConfig.get().setParse(item);
        refresh();
    }

    private void mergeFlags(List<Flag> items) {
        if (items.isEmpty()) return;
        if (state.hasFlags()) {
            Flag activated = state.getFlag();
            for (Flag item : items) mergeFlag(activated, item);
            host.renderFlags(state.getFlags());
        } else {
            state.setFlags(items);
            host.renderFlags(state.getFlags());
        }
    }

    public void selectSource(Vod item) {
        switchSource(item, false);
    }

    void fallbackSource(Vod item) {
        switchSource(item, true);
    }

    private void switchSource(Vod item, boolean autoFallback) {
        state.setAutoFallback(autoFallback);
        saveCurrentHistory();
        preloader.clear();
        state.clearPlayRequest();
        state.clearTriedFlags();
        playRequestTime = 0L;
        cancelStartWatchdog();
        // 换了数据源，原地重试预算重新计
        fallbackPolicy.resetMidPlayRetry();
        host.prepareSource(item);
        requestDetail();
    }

    public void search(String keyword) {
        fallbackPolicy.search(keyword, false);
    }

    public void manualSwitchSource() {
        fallbackPolicy.manualSwitchSource();
    }

    public void playbackError(String msg) {
        playRequestTime = 0L;
        cancelStartWatchdog();
        preloader.clear();
        host.resetPlaybackForError(msg);
        fallbackPolicy.playbackError();
    }

    /** 首帧是否已出（供换源策略区分「起播失败」与「播放中途失败」）。 */
    boolean hasPlaybackStarted() {
        return playbackHasStarted;
    }

    public void playbackEnded() {
        nextEpisode(true);
    }

    public void replay() {
        if (state.getHistory() != null) state.getHistory().setPosition(C.TIME_UNSET);
        if (host.isPlayerEmpty()) refresh();
        else host.replay(startPositionMs());
    }

    public void refresh() {
        saveCurrentHistory();
        preloader.clear();
        host.stopPlaybackForRefresh();
        restorePlaybackSelection(state.getPlayingRequest());
        requestSelectedEpisode();
    }

    public void onPlaybackServiceReady() {
        MediaMetadata metadata = state.getPlaybackMetadata();
        if (metadata != null) host.renderPlaybackMetadata(metadata);
        VodPlayRequest request = state.getPlayingRequest();
        if (request == null || host.hasPlaybackSession()) return;
        restorePlaybackSelection(request);
        requestSelectedEpisode();
    }

    private void requestSelectedEpisode() {
        if (state.hasEpisode()) requestPlayer(state.getFlag(), state.getEpisode());
    }

    public void nextEpisode(boolean notify) {
        boolean reversed = state.getHistory() != null && state.getHistory().isRevPlay();
        moveEpisode(reversed ? -1 : 1, notify, reversed);
    }

    public void prevEpisode(boolean notify) {
        boolean reversed = state.getHistory() != null && state.getHistory().isRevPlay();
        moveEpisode(reversed ? 1 : -1, notify, reversed);
    }

    private void moveEpisode(int offset, boolean notify, boolean reversed) {
        if (!state.hasEpisode()) return;
        Episode item = state.getRelativeEpisode(offset);
        if (!item.isSelected()) selectEpisode(item);
        else if (notify && offset > 0) host.showNoNext(reversed);
        else if (notify) host.showNoPrev(reversed);
    }

    public void reverseEpisode(boolean scroll) {
        if (!state.hasFlags()) return;
        for (Flag flag : state.getFlags()) reverseEpisodes(flag);
        host.renderReverseEpisodes(state.getFlag().getEpisodes(), scroll);
    }

    private void reverseEpisodes(Flag flag) {
        List<Episode> episodes = flag.getEpisodes();
        int position = flag.getPosition();
        Collections.reverse(episodes);
        if (position >= 0 && position < episodes.size()) flag.setPosition(episodes.size() - position - 1);
    }

    private void saveCurrentHistory() {
        if (state.getPlayingRequest() == null || !host.canTrackPlaybackProgress()) historyPolicy.save(currentHistory());
        else saveHistory(false, System.currentTimeMillis(), host.getPlayerPosition(), host.getPlayerDuration());
    }

    public void saveHistory(boolean exit, long time, long position, long duration) {
        History history = exit ? historyForExit() : currentHistory();
        if (host.isLivePlayback()) historyPolicy.saveVisit(history, exit, time);
        else historyPolicy.saveProgress(history, exit, time, position, duration);
    }

    public void onTimeChanged(long time, long position, long duration) {
        History history = currentHistory();
        historyPolicy.updateProgress(history, time, position, duration);
        if (history != null && history.getEnding() > 0 && history.getEnding() + position >= duration) nextEpisode(false);
    }

    /**
     * 起播位置。
     *
     * <p><b>续播规则（分两层）</b>：只要还在<b>本次软件会话内</b>（进程存活），任何入口都续播 ——
     * 最近观看 / 历史记录 / 投屏 / 普通点播只要历史里有记录，一律接着上次的位置看。
     * 「退出自动续播」开关只在<b>冷启动那一刻</b>起作用，它的作用是「开机不自动跳进上次的节目」
     * （门控在 {@code HomeActivity.checkBootDirect()}），所以这里不做任何开关判断。</p>
     */
    public long startPositionMs() {
        return historyPolicy.startPositionMs(state.getHistory());
    }

    private History currentHistory() {
        History history = state.getHistory();
        if (history != null) lastHistory = history;
        return history;
    }

    private History historyForExit() {
        History history = currentHistory();
        return history == null ? lastHistory : history;
    }

    public void onPreloadResult(PlaybackResult<VodPlayRequest> preload) {
        preloader.onResult(preload);
    }

    public void setOpening(long opening) {
        if (state.getHistory() != null) state.getHistory().setOpening(opening);
    }

    public void setEnding(long ending) {
        if (state.getHistory() != null) state.getHistory().setEnding(ending);
    }

    public void setScale(int scale) {
        if (state.getHistory() != null) state.getHistory().setScale(scale);
    }

    public void setRevSort(boolean revSort) {
        if (state.getHistory() != null) state.getHistory().setRevSort(revSort);
    }

    public void setRevPlay(boolean revPlay) {
        if (state.getHistory() != null) state.getHistory().setRevPlay(revPlay);
    }

    private void detailEmpty(boolean shouldFinish) {
        if (shouldFinish || host.isFromCollect()) {
            host.finishVod();
            return;
        }
        String name = host.getVodName();
        if (name.isEmpty()) {
            host.renderEmptyDetail();
        } else {
            host.renderFallbackName(name);
            host.onDetailFallbackScheduled();
            fallbackPolicy.emptyDetail();
        }
    }

    private void detailLoaded(Vod item) {
        VodPlayRequest activeRequest = state.getActiveRequest();
        History history = applyDetail(item);
        if (state.hasFlags()) restoreDetail(activeRequest, history);
        else fallbackPolicy.emptyFlag();
    }

    private History applyDetail(Vod item) {
        item.checkPic(host.getVodPic());
        item.checkName(host.getVodName());
        List<Flag> flags = item.getFlags();
        state.setFlags(flags);
        History history = historyPolicy.findOrCreate(host.getHistoryKey(), host.getVodMark(), item);
        state.setHistory(history);
        lastHistory = history;
        host.renderDetail(item, history);
        host.renderFlags(flags);
        host.renderHistory(history);
        host.onDetailFallbackCancelled();
        return history;
    }

    private void restoreDetail(VodPlayRequest activeRequest, History history) {
        if (restorePlaybackSelection(activeRequest)) publishPlaybackMetadata(state.getEpisode());
        else selectFlag(history.getFlag(), true);
        if (history.isRevSort()) reverseEpisode(true);
        if (state.getPlayingRequest() != null && !host.hasPlaybackSession()) requestSelectedEpisode();
    }

    private boolean restorePlaybackSelection(VodPlayRequest request) {
        Flag flag = state.findFlag(host.getVodKey(), request);
        Episode episode = state.findEpisode(host.getVodKey(), flag, request);
        if (flag == null || episode == null) return false;
        for (Flag item : state.getFlags()) item.setSelected(flag);
        for (Flag item : state.getFlags()) item.toggle(item == flag, episode);
        historyPolicy.updateEpisode(state.getHistory(), flag, episode);
        host.renderFlagSelection(flag);
        host.renderEpisodes(flag.getEpisodes());
        host.renderEpisodeSelection(episode);
        host.renderQualityVisible(state.getQuality().getUrl().isMulti());
        return true;
    }

    private void requestPlayer(Flag flag, Episode episode) {
        historyPolicy.updateEpisode(state.getHistory(), flag, episode);
        VodPlayRequest request = VodPlayRequest.create(host.getVodKey(), flag, episode);
        state.setPendingRequest(request);
        armStartGuard();   // 与预加载路径共用（见 armStartGuard 的说明）
        publishPlaybackMetadata(episode);
        dataSource.playerContent(request);
        host.onPlaybackRequested();
    }

    private Episode findEpisode(VodPlayRequest request) {
        return state.findEpisode(host.getVodKey(), request);
    }

    private MediaMetadata publishPlaybackMetadata(Episode episode) {
        MediaMetadata metadata = VodPlaybackMedia.metadata(state.getHistory(), episode);
        state.setPlaybackMetadata(metadata);
        host.renderPlaybackMetadata(metadata);
        return metadata;
    }

    private void seamless(Flag flag) {
        History history = state.getHistory();
        // 续播规则见 startPositionMs()：软件内一律续播（无历史时才退化为第一集），
        // 「退出自动续播」开关只门控冷启动的「开机直达」，不参与这里。
        Episode episode = history != null ? flag.find(history.getVodRemarks(), host.getVodMark().isEmpty()) : null;
        host.renderQualityVisible(episode != null && episode.isSelected() && state.getQuality().getUrl().isMulti());
        if (episode == null) {
            // 集名完全对不上（新线路用纯文字集名、position=-1）：退化为新线路第 1 集。
            // ⛔ 不能静默 return —— 此时 selectFlag() 已把高亮切到新线路，静默返回会让
            //    「提示已切换到线路 X + 高亮在 X」但画面还是旧流，老人会认为「换线没用」。
            List<Episode> episodes = flag.getEpisodes();
            if (episodes == null || episodes.isEmpty()) return;
            episode = episodes.get(0);
            if (history != null) history.setVodRemarks(episode.getName());
            host.renderQualityVisible(episode.isSelected() && state.getQuality().getUrl().isMulti());
            selectEpisode(episode);
            return;
        }
        if (episode.isSelected()) return;
        if (history != null) history.setVodRemarks(episode.getName());
        selectEpisode(episode);
    }

    private void mergeFlag(Flag activated, Flag item) {
        Flag target = findFlag(item);
        if (target == null) {
            state.getFlags().add(item);
        } else {
            target.mergeEpisodes(item.getEpisodes(), state.getHistory() != null && state.getHistory().isRevSort());
            if (target.equals(activated)) host.renderEpisodes(target.getEpisodes());
        }
    }

    private Flag resolveFlag(Flag item) {
        Flag flag = findFlag(item);
        if (flag != null) return flag;
        return state.getFlags().get(0);
    }

    private Flag findFlag(Flag item) {
        if (item != null) for (Flag flag : state.getFlags()) if (flag.equals(item)) return flag;
        return null;
    }

    private boolean cannotApply(PlaybackResult<VodPlayRequest> playback) {
        VodPlayRequest pending = state.getPendingRequest();
        VodPlayRequest request = playback.request();
        if (host.isHostFinishing() || pending == null || request == null) return true;
        return !pending.matches(request) || findEpisode(request) == null || !request.accepts(playback.result());
    }
}
