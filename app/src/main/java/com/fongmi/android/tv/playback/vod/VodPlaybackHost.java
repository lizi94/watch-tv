package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import java.util.List;

public interface VodPlaybackHost {

    String getVodKey();

    String getVodId();

    void setVodId(String id);

    String getVodName();

    /**
     * 当前详情页所属的站点。
     *
     * <p>用于区分「index 片单源」—— 它只有数据、没有播放线路，
     * 播放失败时必须直接跨源（不能套「源不可切换就不换」的护栏）。</p>
     */
    default Site getSite() {
        return new Site();
    }

    String getVodPic();

    String getVodMark();

    String getHistoryKey();

    /**
     * 是否为「显式续播」入口（历史记录页 / 首页最近观看 / 投屏恢复）。
     *
     * <p><b>注意</b>：自「分两层」续播规则落地后，该标记<b>不再参与任何续播门控</b> ——
     * 软件内（进程存活）一律续播，与入口类型无关；「退出自动续播」开关只门控冷启动的
     * 「开机直达」（见 {@code Setting.isAutoResume()}）。保留它只是为了继续记录
     * 「用户是从哪条入口点进来的」，默认 false。</p>
     */
    default boolean isExplicitResume() {
        return false;
    }

    boolean isHostFinishing();

    boolean isPlayerEmpty();

    boolean hasPlaybackSession();

    boolean isFullscreenForPlayback();

    boolean isLivePlayback();

    boolean canTrackPlaybackProgress();

    boolean canPreloadNext();

    long getPlayerPosition();

    long getPlayerDuration();

    void usePushId(String id);

    void onPlaybackRequested();

    void prepareSource(Vod item);

    void stopPlaybackForRefresh();

    void resetPlaybackForError(String msg);

    void replay(long position);

    void startPlayback(Result result, boolean useParse, long startPositionMs, MediaMetadata metadata);

    boolean preloadPlayback(Result result, long startPositionMs, MediaMetadata metadata);

    void clearPreload();

    void loadDanmaku(Result result, History history, Episode episode);

    void renderDetail(Vod item, History history);

    void renderVodUpdate(Vod item);

    void renderEmptyDetail();

    void renderFallbackName(String name);

    void renderFlags(List<Flag> items);

    void renderEpisodes(List<Episode> items);

    void renderFlagSelection(Flag item);

    void renderEpisodeSelection(Episode item);

    void renderReverseEpisodes(List<Episode> items, boolean scroll);

    void renderQuality(Result result, boolean visible);

    void renderQualityVisible(boolean visible);

    void renderSources(List<Vod> items);

    void renderHistory(History history);

    void renderUseParse(boolean useParse);

    void renderArtwork(String url);

    void renderDescription(String desc);

    void renderPlaybackMetadata(MediaMetadata metadata);

    void onDetailFallbackScheduled();

    void onDetailFallbackCancelled();

    void onSearchStarted(String keyword);

    void showDetailMessage(String msg);

    void showSwitchLine(Flag flag);

    void showSwitchSource(Vod item);

    void showEpisodeReady(Episode item);

    void showNoNext(boolean reversed);

    void showNoPrev(boolean reversed);
}
