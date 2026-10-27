package com.fongmi.android.tv.playback.vod;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class VodPlaybackState {

    private final Set<String> failedIds;
    private final Set<String> triedFlags;
    private final List<Vod> sources;
    private final List<Flag> flags;
    private VodPlayRequest pendingRequest;
    private VodPlayRequest playingRequest;
    private MediaMetadata playbackMetadata;
    private VodPlayRequest preloadRequest;
    private Result preloadResult;
    private Result quality;
    private History history;
    private String detailKey;
    private String detailId;
    private String searchKeyword;
    private int qualityPosition;
    private boolean useParse;

    public VodPlaybackState() {
        this.failedIds = new HashSet<>();
        this.triedFlags = new HashSet<>();
        this.sources = new ArrayList<>();
        this.flags = new ArrayList<>();
        this.quality = Result.empty();
        this.detailKey = "";
        this.detailId = "";
        this.searchKeyword = "";
    }

    public void reset() {
        failedIds.clear();
        triedFlags.clear();
        sources.clear();
        flags.clear();
        clearPlayRequest();
        playbackMetadata = null;
        clearPreload();
        quality = Result.empty();
        history = null;
        detailKey = "";
        detailId = "";
        searchKeyword = "";
        qualityPosition = 0;
        useParse = false;
    }

    void addFailedId(String id) {
        if (id != null && !id.isEmpty()) failedIds.add(id);
    }

    boolean hasFailedId(String id) {
        return id != null && failedIds.contains(id);
    }

    /**
     * 清空「已失败源」记录（手动换源专用）。
     *
     * <p>自动链靠 failedIds 防止原地打转；但用户<b>主动</b>点「换源」时，上一次的失败不该
     * 挡住他 —— 否则「换了一圈全失败后，再点换源就永远找不到候选」，按钮看起来就是坏的。</p>
     */
    void clearFailedIds() {
        failedIds.clear();
    }

    /** 清空候选源（重新搜索前先清，避免新旧候选混在一起重复换到同一个源）。 */
    void clearSources() {
        sources.clear();
    }

    List<Vod> getSources() {
        return sources;
    }

    /**
     * 追加候选源（跨源搜索是<b>每个站源各回调一次</b>，结果必须累加而不是覆盖）。
     *
     * <p>按「源 + id」判重：同一个站源可能对同一个站点的多次回调给出同一条，
     * 无脑 addAll 会让同一个候选被反复换到 —— 那就是「换源风暴」。</p>
     */
    void addSources(List<Vod> items) {
        for (Vod item : items) {
            if (item == null || contains(item)) continue;
            sources.add(item);
        }
    }

    private boolean contains(Vod item) {
        for (Vod exist : sources) {
            if (exist.getId().equals(item.getId()) && exist.getSiteKey().equals(item.getSiteKey())) return true;
        }
        return false;
    }

    /** 移除指定的候选源（跨源择优时用，不必恰好是第一个）。 */
    boolean removeSource(Vod item) {
        return sources.remove(item);
    }

    boolean hasSources() {
        return !sources.isEmpty();
    }

    /** 本次播放已经试过（失败过）的线路名，避免择优时又挑回同一条。 */
    Set<String> getTriedFlags() {
        return triedFlags;
    }

    void addTriedFlag(String flag) {
        if (flag != null && !flag.isEmpty()) triedFlags.add(flag);
    }

    void clearTriedFlags() {
        triedFlags.clear();
    }

    List<Flag> getFlags() {
        return flags;
    }

    void setFlags(List<Flag> items) {
        flags.clear();
        flags.addAll(items);
    }

    boolean hasFlags() {
        return !flags.isEmpty();
    }

    int getFlagPosition() {
        for (int i = 0; i < flags.size(); i++) if (flags.get(i).isSelected()) return i;
        return 0;
    }

    Flag getFlag() {
        return flags.get(getFlagPosition());
    }

    Episode getEpisode() {
        Flag flag = getFlag();
        int position = flag.getPosition();
        return flag.getEpisodes().get(position >= 0 && position < flag.getEpisodes().size() ? position : 0);
    }

    boolean hasEpisode() {
        return hasFlags() && !getFlag().getEpisodes().isEmpty();
    }

    Episode getRelativeEpisode(int offset) {
        List<Episode> episodes = getFlag().getEpisodes();
        int position = Math.clamp(getFlag().getPosition() + offset, 0, episodes.size() - 1);
        return episodes.get(position);
    }

    @Nullable
    Episode findEpisode(String key, VodPlayRequest request) {
        if (request == null) return null;
        return flags.stream().map(flag -> findEpisode(key, flag, request)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    @Nullable
    Episode findEpisode(String key, Flag flag, VodPlayRequest request) {
        if (flag == null || request == null) return null;
        return flag.getEpisodes().stream().filter(episode -> request.matches(key, flag, episode)).findFirst().orElse(null);
    }

    @Nullable
    Flag findFlag(String key, VodPlayRequest request) {
        if (request == null) return null;
        return flags.stream().filter(flag -> findEpisode(key, flag, request) != null).findFirst().orElse(null);
    }

    Result getQuality() {
        return quality;
    }

    void setQuality(Result quality) {
        this.quality = quality;
    }

    int getQualityPosition() {
        return qualityPosition;
    }

    void setQualityPosition(int qualityPosition) {
        this.qualityPosition = qualityPosition;
    }

    History getHistory() {
        return history;
    }

    void setHistory(History history) {
        this.history = history;
    }

    boolean isDetailRequested(String key, String id) {
        return detailKey.equals(key) && detailId.equals(id);
    }

    void setDetailRequest(String key, String id) {
        detailKey = key == null ? "" : key;
        detailId = id == null ? "" : id;
    }

    VodPlayRequest getPendingRequest() {
        return pendingRequest;
    }

    void setPendingRequest(VodPlayRequest pendingRequest) {
        this.pendingRequest = pendingRequest;
        this.playingRequest = null;
    }

    VodPlayRequest getPlayingRequest() {
        return playingRequest;
    }

    void setPlayingRequest(VodPlayRequest playingRequest) {
        this.playingRequest = playingRequest;
        this.pendingRequest = null;
    }

    @Nullable
    VodPlayRequest getActiveRequest() {
        return pendingRequest != null ? pendingRequest : playingRequest;
    }

    void clearPlayRequest() {
        pendingRequest = null;
        playingRequest = null;
    }

    @Nullable
    VodPlayRequest getPreloadRequest() {
        return preloadRequest;
    }

    @Nullable
    Result getPreloadResult() {
        return preloadResult;
    }

    void beginPreload(VodPlayRequest request) {
        preloadRequest = request;
        preloadResult = null;
    }

    void completePreload(Result result) {
        preloadResult = result;
    }

    @Nullable
    Result consumePreload(String key, Flag flag, Episode episode) {
        if (preloadRequest == null || preloadResult == null || !preloadRequest.matches(key, flag, episode)) return null;
        Result result = preloadResult;
        clearPreload();
        return result;
    }

    void clearPreload() {
        preloadRequest = null;
        preloadResult = null;
    }

    @Nullable
    MediaMetadata getPlaybackMetadata() {
        return playbackMetadata;
    }

    void setPlaybackMetadata(MediaMetadata playbackMetadata) {
        this.playbackMetadata = playbackMetadata;
    }

    boolean isUseParse() {
        return useParse;
    }

    void setUseParse(boolean useParse) {
        this.useParse = useParse;
    }

    String getSearchKeyword() {
        return searchKeyword == null ? "" : searchKeyword;
    }

    void setSearchKeyword(String searchKeyword) {
        this.searchKeyword = searchKeyword == null ? "" : searchKeyword;
    }
}
