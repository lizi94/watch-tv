package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityVideoBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.model.VideoViewModel;
import com.fongmi.android.tv.playback.PlaybackAction;
import com.fongmi.android.tv.playback.PlaybackIntent;
import com.fongmi.android.tv.playback.PlaybackReset;
import com.fongmi.android.tv.playback.PlaybackResult;
import com.fongmi.android.tv.playback.vod.VodDetailResult;
import com.fongmi.android.tv.playback.vod.VodPlayRequest;
import com.fongmi.android.tv.playback.vod.VodPlaybackController;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;
import com.fongmi.android.tv.playback.vod.VodPlaybackMedia;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SpeedSetting;
import com.fongmi.android.tv.ui.adapter.ArrayAdapter;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.FlagAdapter;
import com.fongmi.android.tv.ui.adapter.QualityAdapter;import com.fongmi.android.tv.ui.custom.CustomKeyDownVod;
import com.fongmi.android.tv.ui.custom.CustomMovement;
import com.fongmi.android.tv.ui.dialog.ChapterDialog;
import com.fongmi.android.tv.ui.dialog.ContentDialog;
import com.fongmi.android.tv.ui.dialog.DanmakuDialog;
import com.fongmi.android.tv.ui.dialog.EditionDialog;
import com.fongmi.android.tv.ui.dialog.ParseDialog;
import com.fongmi.android.tv.ui.dialog.PlayerEngineDialog;
import com.fongmi.android.tv.ui.dialog.SpeedSettingDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.ElderLock;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.TouchGridAlign;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.Traffic;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class VideoActivity extends PlaybackActivity implements VodPlaybackHost, CustomKeyDownVod.Listener, ParseDialog.Listener, ArrayAdapter.OnClickListener, FlagAdapter.OnClickListener, EpisodeAdapter.OnClickListener, QualityAdapter.OnClickListener, Clock.Callback {

    /** R-A′：index 源详情为空时的兜底等待时长（20 秒给自动择源留足余量）。 */
    private static final long EMPTY_DETAIL_FALLBACK_MS = 20000L;

    private ActivityVideoBinding mBinding;
    private VideoViewModel mViewModel;
    private VodPlaybackController mVod;
    private FlagAdapter mFlagAdapter;
    private EpisodeAdapter mEpisodeAdapter;
    private QualityAdapter mQualityAdapter;
    private ArrayAdapter mArrayAdapter;
    private ViewGroup.LayoutParams mFrameParams;
    private CustomKeyDownVod mKeyDown;
    private Clock mClock;
    private View mFocus1;
    private View mFocus2;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;
    private Runnable mR4;
    /**
     * R-A′ 落点：延时检查「详情页是否仍然没有任何线路」，是则转搜索同名。
     * 仅 index 源 + 20 秒（豆瓣一类本身不提供可播线路的站源）。
     */
    private Runnable mR5;
    /** 当前详情页已渲染的线路条数 —— R-A′ 的判据用「数据事实」而非 View 可见性。 */
    private int mFlagCount;
    /** 用户是否已手动点过「换源」：置位后永久取消 mR5 自动跳搜索页（见 {@link #onSwitchSource}）。 */
    private boolean userSwitchedSource;
    private History mHistory;
    private boolean fullscreen;
    /**
     * 进入全屏时被收起的「非播放区」视图 → 进入前的可见性。
     *
     * <p>⛔ 为什么必须有这份快照：{@code activity_video.xml} 里元数据（name/remark/row1/director/actor）、
     * 操作行（row2）与选集区（scroll）都声明在播放容器 {@code @id/video} <b>之后</b>，
     * 而 {@code enterFullscreen()} 只是把 video 放大到 MATCH_PARENT —— 这些视图于是直接盖在画面上
     * （用户看到的就是「全屏后元数据列表压在播放器里」）。退出全屏时必须按<b>进入前的原状</b>还原，
     * 不能一律设 VISIBLE（行本身可能因数据缺失而被别处置 GONE）。</p>
     */
    private final Map<View, Integer> fullscreenHidden = new LinkedHashMap<>();
    private boolean useParse;
    /** 本次详情页会话「自动全屏」是否已经触发过（自动触发只允许一次）。 */
    private boolean autoFullscreenUsed;
    /** 用户主动按返回退出过全屏后，本次会话不再自动进全屏（手动点集仍可）。 */
    private boolean userExitedFullscreen;

    public static void push(FragmentActivity activity, String text) {
        Uri uri = UrlUtil.uri(text);
        if (FileChooser.isFileSource(uri)) FileChooser.getFileUri(uri, fileUri -> file(activity, fileUri));
        else start(activity, Sniffer.getUrl(text));
    }

    public static void file(FragmentActivity activity, Uri fileUri) {
        if (fileUri == null || activity.isFinishing() || activity.isDestroyed()) return;
        start(activity, SiteApi.PUSH, fileUri.toString(), FileUtil.getDisplayName(fileUri));
    }

    public static void cast(Activity activity, History history) {
        start(activity, history.getSiteKey(), history.getVodId(), history.getVodName(), history.getVodPic(), null, true);
    }

    public static void start(Activity activity, String url) {
        start(activity, SiteApi.PUSH, url, url);
    }

    public static void start(Activity activity, String key, String id, String name) {
        start(activity, key, id, name, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark) {
        start(activity, key, id, name, pic, mark, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean cast) {
        // TV 端语义：(cast)。7 参一律 resume=false —— 与改动前行为完全一致。
        // 手机端的 6 参末位是 resume（那边没有 cast 维度），两端刻意不同，勿混用。
        start(activity, key, id, name, pic, mark, cast, false);
    }

    /**
     * 8 参完整版：{@code (cast, resume)}。
     *
     * <p>{@code resume=true} 表示「这是从历史记录/首页续播进来的」。
     * TV 端当前只写入不读取（读取方在手机端 {@code VideoActivity:232}），
     * 但保留该维度是为了与手机端调用点（{@code HomeActivity:1227} / {@code HistoryActivity:124}
     * 传多参）保持签名一致 —— 这两处是跨端对齐的调用方，不能为了省一个参数改它们。</p>
     *
     * <p>⚠ 2026-10-27 删掉了原来的 {@code collect} 参数：它唯一的作用是写 {@code "collect"} extra，
     * 而读取方 {@code isFromCollect()} 已随「详情为空不再强制关页」一并删除（见
     * {@code VodPlaybackController#detailEmpty}），留着就是写了没人读的死数据。</p>
     */
    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean cast, boolean resume) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("cast", cast);
        intent.putExtra("resume", resume);
        intent.putExtra("mark", mark);
        intent.putExtra("name", name);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        putPic(intent, pic);
        activity.startActivity(intent);
    }

    private static void putPic(Intent intent, String pic) {
        intent.removeExtra("pic");
        pic = ImgUtil.cache(pic);
        if (!TextUtils.isEmpty(pic)) intent.putExtra("pic", pic);
    }

    private boolean isCast() {
        return getIntent().getBooleanExtra("cast", false);
    }

    private String getName() {
        return Objects.toString(getIntent().getStringExtra("name"), "");
    }

    private String getPic() {
        return Objects.toString(getIntent().getStringExtra("pic"), "");
    }

    private String getMark() {
        return Objects.toString(getIntent().getStringExtra("mark"), "");
    }

    private String getKey() {
        return Objects.toString(getIntent().getStringExtra("key"), "");
    }

    private String getId() {
        return Objects.toString(getIntent().getStringExtra("id"), "");
    }

    private boolean isSameVideo(Intent intent) {
        String key = Objects.toString(intent.getStringExtra("key"), "");
        String id = Objects.toString(intent.getStringExtra("id"), "");
        return TextUtils.equals(key, getKey()) && TextUtils.equals(id, getId());
    }

    @Override
    public String getHistoryKey() {
        return getKey().concat(AppDatabase.SYMBOL).concat(getId()).concat(AppDatabase.SYMBOL) + VodConfig.getCid();
    }

    @Override
    public Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private int getScale() {
        return mHistory != null && mHistory.getScale() != -1 ? mHistory.getScale() : PlayerSetting.getScale();
    }

    private void setScale(int scale) {
        mVod.setScale(scale);
        mBinding.player.setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityVideoBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return mNavigationCallback;
    }

    @Override
    protected PlayerView getPlayerView() {
        return mBinding.player;
    }

    @Override
    protected PlayerSeekView getSeekView() {
        return mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        mVod.onPlaybackServiceReady();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (TextUtils.isEmpty(intent.getStringExtra("id")) || isSameVideo(intent)) return;
        saveHistory(true);
        mVod.reset();
        // ⚠ 2026-10-16 审计修复（P2/P9）：换片复用同一 Activity 实例（singleTop），三个会话级
        // 标记必须复位，否则前一部片退出过全屏（userExitedFullscreen=true）时，推送来的
        // 新片永远不自动全屏 —— 与 checkAutoFullscreen 的文档语义直接矛盾。
        // ⚠ 必须走 restoreWindowedLayout() 真正还原视图，**不能**只调 setFullscreen(false) 翻布尔
        // （否则全屏布局残留 + isFullscreen()==false ⇒ 按一次返回直接离开播放页）。
        // 注意它不改 userExitedFullscreen，下面紧接着显式复位为 false。
        if (isFullscreen()) restoreWindowedLayout();
        userExitedFullscreen = false;
        autoFullscreenUsed = false;
        setIntent(intent);
        updateNavigationKey();
        checkId();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        mFrameParams = mBinding.video.getLayoutParams();
        mClock = Clock.create(mBinding.widget.clock);
        mKeyDown = CustomKeyDownVod.create(this);
        mR1 = this::hideControl;
        mR2 = this::updateFocus;
        mR3 = this::setTraffic;
        mR4 = this::showEmpty;
        mR5 = this::fallbackToSearch;
        setRecyclerView();
        setVideoView();
        setupVideoFocusRing();
        setViewModel();
        checkCast();
        checkId();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.keep.setOnClickListener(view -> onKeep());
        mBinding.video.setOnClickListener(view -> onVideo());
        mBinding.change.setOnClickListener(view -> onChange());
        mBinding.content.setOnClickListener(view -> onContent());
        mBinding.control.action.text.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.video.setOnClickListener(this::onTrack);
        mBinding.control.action.ending.setUpListener(this::onEndingAdd);
        mBinding.control.action.ending.setDownListener(this::onEndingSub);
        mBinding.control.action.opening.setUpListener(this::onOpeningAdd);
        mBinding.control.action.opening.setDownListener(this::onOpeningSub);
        mBinding.control.action.next.setOnClickListener(view -> checkNext());
        mBinding.control.action.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.replay.setOnClickListener(view -> onReplay());
        mBinding.control.action.parse.setOnClickListener(view -> onParse());
        mBinding.control.action.player.setOnClickListener(view -> onPlayer());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.ending.setOnClickListener(view -> onEnding());
        mBinding.control.action.repeat.setOnClickListener(view -> onRepeat());
        mBinding.control.action.danmaku.setOnClickListener(view -> onDanmaku());
        mBinding.control.action.edition.setOnClickListener(view -> onEdition());
        mBinding.control.action.chapter.setOnClickListener(view -> onChapter());
        mBinding.control.action.opening.setOnClickListener(view -> onOpening());
        mBinding.control.action.speed.setOnLongClickListener(view -> onSpeedLong());
        mBinding.control.action.ending.setOnLongClickListener(view -> onEndingReset());
        mBinding.control.action.opening.setOnLongClickListener(view -> onOpeningReset());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
        mBinding.flag.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mFlagAdapter.getItemCount() > 0) onItemClick(mFlagAdapter.get(position));
            }
        });
        mBinding.episode.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (child != null && mBinding.video != mFocus1) mFocus1 = child.itemView;
            }
        });
        mBinding.array.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mEpisodeAdapter.getItemCount() > 20 && position > 1) mBinding.episode.setSelectedPosition((position - 2) * 20);
            }
        });
    }

    private void setRecyclerView() {
        mBinding.flag.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.flag.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        TouchGridAlign.attach(mBinding.flag);
        mBinding.flag.setAdapter(mFlagAdapter = new FlagAdapter(this));
        mBinding.episode.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.episode.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        TouchGridAlign.attach(mBinding.episode);
        mBinding.episode.setAdapter(mEpisodeAdapter = new EpisodeAdapter(this));
        mBinding.quality.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.quality.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        TouchGridAlign.attach(mBinding.quality);
        mBinding.quality.setAdapter(mQualityAdapter = new QualityAdapter(this));
        mBinding.array.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.array.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        TouchGridAlign.attach(mBinding.array);
        mBinding.array.setAdapter(mArrayAdapter = new ArrayAdapter(this));
    }

    private void setVideoView() {
        setSeekNextFocusDown(R.id.next);
        setActionFocusBoundary(mBinding.control.action.getRoot());
        PlayerEngineDialog.setText(mBinding.control.action.player);
        mBinding.control.action.danmaku.setVisibility(DanmakuSetting.isLoad() ? View.VISIBLE : View.GONE);
    }

    private void setPlaybackMode() {
        PlaybackAction.setPlaybackMode(player(), mBinding.control.action.player, mBinding.control.action.decode);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(VideoViewModel.class);
        mVod = mViewModel.createPlaybackController(this);
        observeWhenServiceReady(mViewModel.getDetail(), this::onDetailObserved);
        observeWhenServiceReady(mViewModel.getSearch(), this::onSearchObserved);
        observeWhenServiceReady(mViewModel.getPreload(), this::onPreloadObserved);
        observeWhenServiceReady(mViewModel.getPlayback(), this::onPlaybackObserved);
    }

    private void onDetailObserved(VodDetailResult result) {
        mVod.onDetailResult(result);
    }

    private void onPlaybackObserved(PlaybackResult<VodPlayRequest> result) {
        mVod.onPlaybackResult(result);
    }

    private void onPreloadObserved(PlaybackResult<VodPlayRequest> result) {
        mVod.onPreloadResult(result);
    }

    private void onSearchObserved(Result result) {
        mVod.onSearchResult(result);
    }

    @Override
    public String getVodKey() {
        return getKey();
    }

    @Override
    public String getVodId() {
        return getId();
    }

    @Override
    public void setVodId(String id) {
        getIntent().putExtra("id", id);
    }

    @Override
    public String getVodName() {
        String name = mBinding.name.getText().toString();
        return name.isEmpty() ? getName() : name;
    }

    @Override
    public String getVodPic() {
        return getPic();
    }

    @Override
    public String getVodMark() {
        return getMark();
    }

    @Override
    public boolean isHostFinishing() {
        return isFinishing() || isDestroyed();
    }

    @Override
    public boolean isPlayerEmpty() {
        return player().isEmpty();
    }

    @Override
    public boolean hasPlaybackSession() {
        return service() != null && isOwner() && player().hasPlaySpec();
    }

    @Override
    public boolean isFullscreenForPlayback() {
        return isFullscreen();
    }

    @Override
    public boolean isLivePlayback() {
        return service() != null && isOwner() && player().isLive();
    }

    @Override
    public boolean canTrackPlaybackProgress() {
        return service() != null && isOwner() && player().isVod();
    }

    @Override
    public boolean canPreloadNext() {
        return service() != null && isOwner() && player().canPreloadNext();
    }

    @Override
    public long getPlayerPosition() {
        return player().getPosition();
    }

    @Override
    public long getPlayerDuration() {
        return player().getDuration();
    }

    @Override
    public void usePushId(String id) {
        getIntent().putExtra("key", SiteApi.PUSH).putExtra("id", id);
    }

    @Override
    public void onPlaybackRequested() {
        showProgress();
    }

    @Override
    public void prepareSource(Vod item) {
        // ⚠ key/id 的更新必须排在 service() 判空之前：原来判空就整段 return，
        // key/id 不落地，紧随其后的 requestDetail() 会拿**旧 key 旧 id** 再去请求同一个源
        // ⇒ 详情回来还是那个播不了的源 ⇒ 又失败又换源，一直原地打转。
        getIntent().putExtra("key", item.getSiteKey());
        getIntent().putExtra("id", item.getId());
        putPic(getIntent(), item.getPic());
        mBinding.scroll.scrollTo(0, 0);
        mClock.setCallback(null);
        updateNavigationKey();
        if (service() == null) return;
        player().reset();
        player().stop();
    }

    @Override
    public void stopPlaybackForRefresh() {
        if (service() == null) return;
        player().stop();
        player().clear();
        mClock.setCallback(null);
    }

    @Override
    public void resetPlaybackForError(String msg) {
        if (service() != null) PlaybackReset.afterError(player(), () -> mClock.setCallback(null));
        showError(msg);
    }

    @Override
    public void replay(long position) {
        player().replay(position);
    }

    @Override
    public void startPlayback(Result result, boolean useParse, long startPositionMs, MediaMetadata metadata) {
        startPlayer(getHistoryKey(), result, useParse, getSite().getTimeout(), startPositionMs, metadata);
    }

    @Override
    public boolean preloadPlayback(Result result, long startPositionMs, MediaMetadata metadata) {
        return player().preload(PlaySpec.from(result, getHistoryKey(), metadata), startPositionMs);
    }

    @Override
    public void clearPreload() {
        if (service() != null && isOwner()) player().clearPreload();
    }

    @Override
    public void loadDanmaku(Result result, History history, Episode episode) {
        VodPlaybackMedia.searchDanmaku(result, history, episode, player()::setDanmaku, player()::addDanmaku);
    }

    @Override
    public void renderDetail(Vod item, History history) {
        mHistory = history;
        mBinding.progressLayout.showContent();
        mBinding.name.setText(item.getName());
        mBinding.video.requestFocus();
        App.removeCallbacks(mR4);
        App.removeCallbacks(mR5);        // 详情真的拿到内容了 → 取消 R-A′ 的空页兜底
        setArtwork(item.getPic());
        checkKeepImg();
        setText(item);
        updateKeep();
    }

    @Override
    public void renderVodUpdate(Vod item) {
        if (!item.getId().isEmpty()) updateNavigationKey();
        renderVodMetadata(item.getName(), item.getPic());
        setText(item);
    }

    private void renderVodMetadata(String name, String pic) {
        if (name.isEmpty() && pic.isEmpty()) return;
        if (!name.isEmpty()) mBinding.name.setText(name);
        if (!pic.isEmpty()) setArtwork();
        updateKeep();
    }

    @Override
    public void renderEmptyDetail() {
        showEmpty();
    }

    @Override
    public void renderFallbackName(String name) {
        mBinding.name.setText(name);
    }

    @Override
    public void renderFlags(List<Flag> items) {
        // 已锁定播放线路时隐藏线路选择条，交给原生自动择优 + 失败顺延逻辑处理，
        // 老人不会被问到「用哪条线路」。未锁定时与原版完全一致。
        mBinding.flag.setVisibility(items.isEmpty() || ElderLock.hideRoute() ? View.GONE : View.VISIBLE);
        mBinding.flagTitle.setVisibility(mBinding.flag.getVisibility());
        mFlagCount = items.size();
        mFlagAdapter.addAll(items);
        // 线路出来了 → 取消 R-A′ 的空页兜底（择源成功，不该再跳搜索）
        if (!items.isEmpty()) App.removeCallbacks(mR5);
    }

    @Override
    public void renderEpisodes(List<Episode> items) {
        setEpisodeAdapter(items);
    }

    @Override
    public void renderFlagSelection(Flag item) {
        mBinding.flag.setSelectedPosition(mFlagAdapter.indexOf(item));
        notifyItemChanged(mBinding.flag, mFlagAdapter);
    }

    @Override
    public void renderEpisodeSelection(Episode item) {
        notifyItemChanged(mBinding.episode, mEpisodeAdapter);
        mBinding.episode.setSelectedPosition(mEpisodeAdapter.getPosition());
    }

    @Override
    public void renderReverseEpisodes(List<Episode> items, boolean scroll) {
        setEpisodeAdapter(items);
        if (scroll) mBinding.episode.setSelectedPosition(mEpisodeAdapter.getPosition());
    }

    @Override
    public void renderQuality(Result result, boolean visible) {
        mQualityAdapter.addAll(result);
        setQualityVisible(visible);
    }

    @Override
    public void renderQualityVisible(boolean visible) {
        setQualityVisible(visible);
    }

    @Override
    public void renderSources(List<Vod> items) {
        // 换源候选列表 UI 已移除（剧名搜索/候选 chip 对老人是噪音）：
        // 自动换源由 VodFallbackPolicy 内部维护候选并自动切换，不依赖本行的展示。
    }

    @Override
    public void renderHistory(History history) {
        mHistory = history;
        mBinding.control.action.opening.setText(history.getOpening() <= 0 ? getString(R.string.play_op) : Util.timeMs(history.getOpening()));
        mBinding.control.action.ending.setText(history.getEnding() <= 0 ? getString(R.string.play_ed) : Util.timeMs(history.getEnding()));
        player().setSpeed(SpeedSetting.getPlayback());
        setScale(getScale());
    }

    @Override
    public void renderUseParse(boolean useParse) {
        setUseParse(useParse);
        mBinding.control.action.parse.setVisibility(isUseParse() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void renderArtwork(String url) {
        setArtwork(url);
    }

    @Override
    public void renderDescription(String desc) {
        mBinding.content.setTag(desc);
    }

    @Override
    public void renderPlaybackMetadata(MediaMetadata metadata) {
        if (service() != null && isOwner()) player().setMetadata(metadata);
        mBinding.widget.title.setText(metadata.displayTitle);
        mBinding.widget.title.setSelected(true);
    }

    @Override
    public void onDetailFallbackScheduled() {
        // index 源上同时撤掉既有的 10s 空态看门狗：否则老人会先看到「什么都没有」再被跳走，
        // 中间那 10 秒是一次无意义的闪烁。这里直接等 mR5 到点转搜索。
        App.removeCallbacks(mR4);
        App.removeCallbacks(mR5);
        // ⛔ 只对 index 源生效：这类源（豆瓣等）本来就不提供可播线路，点进来必然是「只有片名的空页」；
        // 而普通源的「详情为空」是偶发事件、且此刻自动择源正在处理它 —— 绝不能抢在它前面关页面。
        if (!getSite().isIndex()) return;
        // ⛔ 用户已经自己点过换源：自动兜底必须让位（详见 onSwitchSource 的说明）。
        if (userSwitchedSource) return;
        App.post(mR5, EMPTY_DETAIL_FALLBACK_MS);
    }

    /**
     * R-A′ 落点：延时检查「详情页是否仍然没有任何线路」，是则转搜索同名。
     *
     * <p>判据用「已渲染的线路条数」（{@code mFlagCount}，由 {@code renderFlags} 同步），
     * 而不是某行 View 的可见性 —— 可见性会随「隐藏线路条」等设置变化，用它判会误伤。
     * 自动择源一旦成功，本方法自然什么都不做，不侵入既有的自动择源逻辑。</p>
     */
    private void fallbackToSearch() {
        if (isFinishing() || isDestroyed()) return;
        // 择源成功，线路已经出来了；或用户已手动介入 —— 两种都不再自动跳走
        if (mFlagCount > 0 || userSwitchedSource) return;
        String name = mBinding.name.getText().toString().trim();
        if (name.isEmpty()) return;
        Notify.show(getString(R.string.elder_detail_empty_search, name));
        CollectActivity.start(this, name);
        finish();
    }

    @Override
    public void onDetailFallbackCancelled() {
        App.removeCallbacks(mR4);
        App.removeCallbacks(mR5);
    }

    @Override
    public void onSearchStarted(String keyword) {
        // 分词搜索行（part）已移除；保留空实现以满足宿主接口
    }

    @Override
    public void showDetailMessage(String msg) {
        Notify.show(msg);
    }

    @Override
    public void showSwitchLine(Flag flag) {
        Notify.show(getString(R.string.play_switch_flag, flag.getFlag()));
    }

    @Override
    public void showSwitchSource(Vod item) {
        Notify.show(getString(R.string.play_switch_site, item.getSiteName()));
    }

    @Override
    public void showEpisodeReady(Episode item) {
        Notify.show(getString(R.string.play_ready, item.getName()));
    }

    @Override
    public void showNoNext(boolean reversed) {
        Notify.show(reversed ? R.string.error_play_prev : R.string.error_play_next);
    }

    @Override
    public void showNoPrev(boolean reversed) {
        Notify.show(reversed ? R.string.error_play_next : R.string.error_play_prev);
    }

    /**
     * 用户手动换源：一旦用户自己介入，就<b>永久取消</b>「20 秒后自动跳搜索页」的兜底。
     *
     * <p>⛔ 原来没有这个标记：{@code onDetailFallbackScheduled()} 在<b>每一次</b>详情为空时都会
     * 重新 post mR5，所以「用户一直手动换源」时，20 秒兜底会在他还在挑源的途中把页面跳走 ——
     * 观感就是「闪退回搜索页」。自动兜底绝不能抢在用户的选择前面。</p>
     */
    private void onSwitchSource() {
        userSwitchedSource = true;
        App.removeCallbacks(mR5);
        mVod.manualSwitchSource();
    }

    private void checkCast() {
        if (isCast() && !isFullscreen()) enterFullscreen();
        else mBinding.progressLayout.showProgress();
    }

    private void checkId() {
        mVod.checkId();
    }

    private void showEmpty() {
        mBinding.progressLayout.showEmpty();
    }

    private void setText(Vod item) {
        mBinding.content.setTag(item.getContent());
        setText(mBinding.year, R.string.detail_year, item.getYear());
        setText(mBinding.area, R.string.detail_area, item.getArea());
        setText(mBinding.type, R.string.detail_type, item.getTypeName());
        setText(mBinding.site, R.string.detail_site, getSite().getName());
        setText(mBinding.director, R.string.detail_director, item.getDirector());
        setText(mBinding.actor, R.string.detail_actor, item.getActor());
        setText(mBinding.remark, 0, item.getRemarks());
    }

    private void setText(TextView view, int resId, String text) {
        if (TextUtils.isEmpty(text) && !TextUtils.isEmpty(view.getText())) return;
        // 2026-10-12：正文里形如 [a=cr:{…}/]文字[/a] 的是站点给的「分类跳转」标记。
        // 分类页（VodActivity）已随 R38 移除，这里只把标记剥掉、保留可见文字，
        // 不再染蓝、不再可点 —— 避免留下「点了没反应」的死链接与「找不到符号」的编译错误。
        String plain = Sniffer.CLICKER.matcher(resId > 0 ? getString(resId, text) : text).replaceAll("$2");
        view.setText(plain, TextView.BufferType.SPANNABLE);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        CustomMovement.bind(view);
    }

    @Override
    public void onItemClick(Flag item) {
        mVod.selectFlag(item);
    }

    private void setEpisodeAdapter(List<Episode> items) {
        mBinding.episode.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.episodeTitle.setVisibility(mBinding.episode.getVisibility());
        mEpisodeAdapter.addAll(items);
        setArrayAdapter(items.size());
        setR2Callback();
    }

    @Override
    public void onItemClick(Episode item) {
        // ⛔⚠ 2026-10-16 修「详情页无法正常选集」：原实现
        //   if (shouldEnterFullscreen(item)) return; mVod.selectEpisode(item);
        // 在「非全屏 + 自动全屏开启」时 shouldEnterFullscreen 必然返回 true（点任何一集都进全屏），
        // 提前 return ⇒ **selectEpisode 根本没执行** —— 画面进全屏了，播的还是原来的集。
        // 这就是用户看到的「无法正常选集」。
        // ✅ 现在：先切集（点「当前集」仍保持原语义 = 不重播、只进全屏），再按需进全屏。
        boolean replay = item.isSelected();
        if (!replay) mVod.selectEpisode(item);
        shouldEnterFullscreen(item);
    }

    private void setQualityVisible(boolean visible) {
        mBinding.quality.setVisibility(visible ? View.VISIBLE : View.GONE);
        setR2Callback();
    }

    @Override
    public void onItemClick(Result result) {
        mVod.selectQuality(result);
    }

    private void setArrayAdapter(int size) {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.play_reverse));
        items.add(getString(mHistory.getRevPlayText()));
        mBinding.array.setVisibility(size > 1 ? View.VISIBLE : View.GONE);
        mBinding.arrayTitle.setVisibility(mBinding.array.getVisibility());
        if (mHistory.isRevSort()) for (int i = size; i > 0; i -= 20) items.add(i + "-" + Math.max(i - 19, 1));
        else for (int i = 0; i < size; i += 20) items.add((i + 1) + "-" + Math.min(i + 20, size));
        mArrayAdapter.addAll(items);
    }

    private int findFocusDown(int index) {
        List<Integer> orders = Arrays.asList(R.id.flag, R.id.quality, R.id.episode, R.id.array);
        for (int i = 0; i < orders.size(); i++) if (i > index) if (isVisible(findViewById(orders.get(i)))) return orders.get(i);
        return 0;
    }

    private int findFocusUp(int index) {
        List<Integer> orders = Arrays.asList(R.id.flag, R.id.quality, R.id.episode, R.id.array);
        for (int i = orders.size() - 1; i >= 0; i--) if (i < index) if (isVisible(findViewById(orders.get(i)))) return orders.get(i);
        return 0;
    }

    private void updateFocus() {
        mEpisodeAdapter.setNextFocusUp(findFocusUp(2));
        mFlagAdapter.setNextFocusDown(findFocusDown(0));
        mEpisodeAdapter.setNextFocusDown(findFocusDown(2));
        notifyItemChanged(mBinding.episode, mEpisodeAdapter);
        notifyItemChanged(mBinding.flag, mFlagAdapter);
    }

    @Override
    public void onRevSort() {
        mVod.setRevSort(!mHistory.isRevSort());
        mVod.reverseEpisode(false);
    }

    @Override
    public void onRevPlay(TextView view) {
        mVod.setRevPlay(!mHistory.isRevPlay());
        view.setText(mHistory.getRevPlayText());
        Notify.show(mHistory.getRevPlayHint());
    }

    private boolean shouldEnterFullscreen(Episode item) {
        // 原生只对「手动点选的这一集」进全屏，自动连播下一集时画面会又缩回去。
        // 适老化「点播自动全屏」默认开启，起播即进全屏；关闭后与原生完全一致。
        boolean enter = !isFullscreen() && (item.isSelected() || Setting.isAutoFullscreen());
        if (enter) {
            autoFullscreenUsed = true;
            enterFullscreen();
        }
        return enter;
    }

    /**
     * 起播自动全屏（首帧回调驱动）：无论从哪条路径进入播放 ——
     * 重启软件直达上次节目、点历史记录、打开新详情页自动起播 ——
     * 只要「点播自动全屏」开着，出画就拉进全屏。
     *
     * <p>会话级防打扰判断：自动触发在一个详情页会话内只发生一次；
     * 用户按返回退出全屏后，自动换线/换源/连播重新起播<b>不会</b>再把人拽回全屏
     * （在详情页手动点选集数仍然会进全屏，那是用户主动行为）。
     * 每次新开详情页 / 重启直达 / 点历史记录都是新的 Activity 实例，两个标记复位，照常自动全屏。</p>
     */
    private void checkAutoFullscreen() {
        if (!Setting.isAutoFullscreen()) return;
        if (autoFullscreenUsed || userExitedFullscreen) return;
        if (isFullscreen()) return;
        autoFullscreenUsed = true;
        enterFullscreen();
    }

    /**
     * 初始化海报焦点环。
     *
     * <p><b>为什么焦点框要单独做一个 View、还要向外扩</b>：{@code @id/video} 里是视频渲染层
     * （PlayerView / mpv 的 surface），起播后 surface 会盖住同区域的 View 内容 ——
     * 画在容器上的焦点描边会被"吃掉"，老人根本看不见焦点。以前是给容器留内边距把描边挤到外面，
     * 代价是画面被裁。现在改成独立的 {@code @id/videoFocusRing}：与海报四边对齐后，
     * 用**负边距向外扩出一圈描边宽度**，框完全落在海报外的空白上 —— 既不遮画面，也不受 surface 影响。</p>
     *
     * <p>负边距的量取 {@code @dimen/ag_stroke_focus}（随字号档位 5~10dp），与描边宽度严格相等，
     * 所以环的内缘永远贴着海报边界。</p>
     */
    private void setupVideoFocusRing() {
        int stroke = getResources().getDimensionPixelSize(R.dimen.ag_stroke_focus);
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mBinding.videoFocusRing.getLayoutParams();
        params.setMarginStart(-stroke);
        params.topMargin = -stroke;
        params.setMarginEnd(-stroke);
        params.bottomMargin = -stroke;
        mBinding.videoFocusRing.setLayoutParams(params);
        mBinding.video.setOnFocusChangeListener((view, hasFocus) -> updateVideoFocusRing());
        updateVideoFocusRing();
    }

    /** 按「是否聚焦 + 是否全屏」同步焦点环的显隐（全屏时画面铺满，环会错位，必须隐藏）。 */
    private void updateVideoFocusRing() {
        if (mBinding == null) return;
        boolean show = !isFullscreen() && mBinding.video.isFocused();
        mBinding.videoFocusRing.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void enterFullscreen() {
        // 防重入：重复进入会覆盖可见性快照（此时视图已是 GONE，快照记成 GONE ⇒ 退出后元数据永久消失）
        if (isFullscreen()) return;
        mFocus1 = getCurrentFocus();
        mBinding.video.requestFocus();
        // 焦点框已改由 @id/videoFocusRing 承担，容器不再需要 foreground（见 setupVideoFocusRing）
        mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        mBinding.flag.setSelectedPosition(mFlagAdapter.getPosition());
        mKeyDown.setFull(true);
        // 收起元数据 / 操作行 / 选集区：它们声明在播放容器之后，不收起就会盖在全屏画面上
        hideNonPlayerViews();
        setFullscreen(true);
        updateVideoFocusRing();
        mFocus2 = null;
    }

    private void exitFullscreen() {
        // 用户主动退出全屏：本次详情页会话内不再自动拉回全屏
        userExitedFullscreen = true;
        restoreWindowedLayout();
    }

    /**
     * 把播放区从「全屏布局」还原成「窗口化布局」。
     *
     * <p>⚠ 2026-10-16 审计修复（P9）：{@code onNewIntent}（推送/投屏复用实例换片）原先只调
     * {@code setFullscreen(false)} **翻个布尔**，没有走这里的视图还原 ⇒ 若老人此前手动进过全屏、
     * 且「自动全屏」已关闭，新片进来后画面仍是全屏布局却 {@code isFullscreen()==false}，
     * 按一次返回会直接离开播放页（而不是「先退全屏」）。抽成本方法供两处共用。</p>
     */
    private void restoreWindowedLayout() {
        // 焦点框由 @id/videoFocusRing 负责，这里不再设 foreground
        mBinding.video.setLayoutParams(mFrameParams);
        getFocus1().requestFocus();
        mKeyDown.setFull(false);
        setFullscreen(false);
        mFocus2 = null;
        // 先还原非播放区（按进入全屏前的可见性），再收时间浮层
        restoreNonPlayerViews();
        updateVideoFocusRing();
        hideInfo();
    }

    /** 进入全屏：收起所有「非播放区」视图，并记下它们原来的可见性。 */
    private void hideNonPlayerViews() {
        View[] views = {
                mBinding.name, mBinding.remark, mBinding.row1,
                mBinding.director, mBinding.actor, mBinding.row2, mBinding.scroll
        };
        fullscreenHidden.clear();
        for (View view : views) {
            fullscreenHidden.put(view, view.getVisibility());
            view.setVisibility(View.GONE);
        }
    }

    /** 退出全屏：按进入前的可见性逐个还原（幂等，快照为空时什么都不做）。 */
    private void restoreNonPlayerViews() {
        if (fullscreenHidden.isEmpty()) return;
        for (Map.Entry<View, Integer> entry : fullscreenHidden.entrySet()) {
            entry.getKey().setVisibility(entry.getValue());
        }
        fullscreenHidden.clear();
    }

    private void onContent() {
        if (mBinding.content.getTag() == null) return;
        ContentDialog.create().content(mBinding.content.getTag().toString()).show(this);
    }

    private void onKeep() {
        Keep keep = Keep.find(getHistoryKey());
        Notify.show(keep != null ? R.string.keep_del : R.string.keep_add);
        if (keep != null) keep.delete();
        else createKeep();
        checkKeepImg();
    }

    private void onVideo() {
        if (!isFullscreen()) enterFullscreen();
    }

    private void onChange() {
        onSwitchSource();
    }

    private void onRepeat() {
        player().setRepeatOne(!player().isRepeatOne());
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    private void checkNext() {
        checkNext(true);
    }

    private void checkNext(boolean notify) {
        mVod.nextEpisode(notify);
    }

    private void checkPrev() {
        mVod.prevEpisode(true);
    }

    private void onNext(boolean notify) {
        Episode item = mEpisodeAdapter.getNext();
        if (!item.isSelected()) onItemClick(item);
        else if (notify) Notify.show(mHistory.isRevPlay() ? R.string.error_play_prev : R.string.error_play_next);
    }

    private void onPrev(boolean notify) {
        Episode item = mEpisodeAdapter.getPrev();
        if (!item.isSelected()) onItemClick(item);
        else if (notify) Notify.show(mHistory.isRevPlay() ? R.string.error_play_next : R.string.error_play_prev);
    }

    private void onScale() {
        int index = getScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        setScale(index == array.length - 1 ? 0 : ++index);
    }

    private void onSpeed() {
        SpeedSettingDialog.create().player(player()).save(true).show(this);
        hideControl();
    }

    private boolean onSpeedLong() {
        SpeedSetting.putPlayback(PlaybackAction.toggleSpeed(player(), mBinding.widget.message));
        return true;
    }

    private void onReset() {
        onRefresh();
    }

    private void onParse() {
        ParseDialog.create().show(this);
        hideControl();
    }

    private void onReplay() {
        mVod.replay();
    }

    private void onRefresh() {
        mVod.refresh();
    }

    private void onOpening() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetOpening(position, duration)) setOpening(position);
    }

    private void onOpeningAdd() {
        setOpening(Math.max(0, Math.max(0, mHistory.getOpening()) + 1000));
    }

    private void onOpeningSub() {
        setOpening(Math.max(0, Math.max(0, mHistory.getOpening()) - 1000));
    }

    private boolean onOpeningReset() {
        setOpening(0);
        return true;
    }

    private void setOpening(long opening) {
        mVod.setOpening(opening);
        mBinding.control.action.opening.setText(opening <= 0 ? getString(R.string.play_op) : Util.timeMs(mHistory.getOpening()));
    }

    private void onEnding() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetEnding(position, duration)) setEnding(duration - position);
    }

    private void onEndingAdd() {
        setEnding(Math.max(0, Math.max(0, mHistory.getEnding()) + 1000));
    }

    private void onEndingSub() {
        setEnding(Math.max(0, Math.max(0, mHistory.getEnding()) - 1000));
    }

    private boolean onEndingReset() {
        setEnding(0);
        return true;
    }

    private void setEnding(long ending) {
        mVod.setEnding(ending);
        mBinding.control.action.ending.setText(ending <= 0 ? getString(R.string.play_ed) : Util.timeMs(mHistory.getEnding()));
    }

    private void onPlayer() {
        PlayerEngineDialog.show(this, mBinding.control.action.player, player());
        hideControl();
    }

    private void onDecode() {
        mClock.setCallback(null);
        player().toggleDecode();
    }

    private void onTrack(View view) {
        TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).view(mBinding.player.getSubtitleView()).show(this);
        hideControl();
    }

    private void onEdition() {
        EditionDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onChapter() {
        ChapterDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onDanmaku() {
        DanmakuDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onToggle() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl(getFocus2());
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mR3, 0);
        hideCenter();
        hideError();
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR3);
        Traffic.reset();
    }

    private void showError(String text) {
        PlaybackAction.hideSpeedHint(mBinding.widget.message);
        mBinding.widget.error.setVisibility(View.VISIBLE);
        // 电视端不放可聚焦按钮（会抢播放页的焦点），用「按返回键退出」明确出口；
        // 遥控器「确定」在播放页仍有原用途（呼出控制条），不抢用。
        mBinding.widget.text.setText(text + "\n" + getString(R.string.error_exit_hint));
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.text.setText("");
    }

    private void showInfo() {
        mBinding.widget.top.setVisibility(View.VISIBLE);
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(0));
    }

    private void hideInfo() {
        mBinding.widget.top.setVisibility(View.GONE);
        mBinding.widget.center.setVisibility(View.GONE);
    }

    private void showControl(View view) {
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        view.requestFocus();
        setR1Callback();
    }

    private void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR1);
    }

    private void hideCenter() {
        mBinding.widget.action.setImageResource(R.drawable.ic_widget_play);
        hideInfo();
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mR3, 1000);
    }

    private void setR1Callback() {
        if (isScrubbing()) return;
        App.post(mR1, Constant.INTERVAL_HIDE);
    }

    @Override
    protected void onScrubbingChanged(boolean scrubbing) {
        if (scrubbing) App.removeCallbacks(mR1);
        else if (isVisible(mBinding.control.getRoot())) setR1Callback();
    }

    private void setR2Callback() {
        App.post(mR2, 500);
    }

    private void setArtwork(String url) {
        mHistory.setVodPic(url);
        setArtwork();
    }

    private void setArtwork() {
        ImgUtil.load(this, mHistory.getVodPic(), new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                mBinding.player.setDefaultArtwork(resource);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                mBinding.player.setDefaultArtwork(errorDrawable);
            }
        });
    }

    private void saveHistory(boolean exit) {
        PlaybackService service = service();
        boolean owner = service != null && getPlaybackKey().equals(service.player().getKey());
        long position = owner ? service.player().getPosition() : C.TIME_UNSET;
        long duration = owner ? service.player().getDuration() : C.TIME_UNSET;
        if (mVod != null) mVod.saveHistory(exit, System.currentTimeMillis(), position, duration);
    }

    private void checkKeepImg() {
        mBinding.keep.setCompoundDrawablesWithIntrinsicBounds(Keep.find(getHistoryKey()) == null ? R.drawable.ic_detail_keep_off : R.drawable.ic_detail_keep_on, 0, 0, 0);
    }

    private void createKeep() {
        Keep keep = new Keep();
        keep.setKey(getHistoryKey());
        keep.setCid(VodConfig.getCid());
        keep.setVodPic(mHistory.getVodPic());
        keep.setVodName(mHistory.getVodName());
        keep.setSiteName(getSite().getName());
        keep.setCreateTime(System.currentTimeMillis());
        keep.save();
    }

    private void updateKeep() {
        Keep keep = Keep.find(getHistoryKey());
        if (keep != null) {
            keep.setVodName(mHistory.getVodName());
            keep.setVodPic(mHistory.getVodPic());
            keep.save();
        }
    }

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onNext() {
            checkNext();
        }

        @Override
        public void onPrev() {
            checkPrev();
        }

        @Override
        public void onStop() {
            finish();
        }

        @Override
        public void onReplay() {
            VideoActivity.this.onReplay();
        }
    };

    @Override
    protected String getPlaybackKey() {
        return getHistoryKey();
    }

    @Override
    protected void onPrepare() {
        setPlaybackMode();
    }

    @Override
    protected void onDecodeChanged() {
        setPlaybackMode();
    }

    @Override
    protected void onTracksChanged() {
        setTrackVisible();
    }

    @Override
    protected void onMediaOptionsChanged() {
        setMediaOptionVisible();
    }

    @Override
    protected void onError(String msg) {
        mVod.playbackError(msg);
    }

    @Override
    protected void onReclaim() {
        mVod.refresh();
    }

    @Override
    protected void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                showProgress();
                mClock.setCallback(null);
                break;
            case Player.STATE_READY:
                hideProgress();
                player().reset();
                mClock.setCallback(this);
                break;
            case Player.STATE_ENDED:
                hideProgress();
                mVod.playbackEnded();
                mClock.setCallback(null);
                break;
        }
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            // 首帧真正出来了 —— 这一条线路的起播耗时作为健康度样本记下来，
            // 供后续自动择优排序使用。暂停后再恢复不会重复计入。
            mVod.playbackStarted();
            hideCenter();
            // 起播自动全屏（含重启直达 / 历史记录 / 新详情页等所有进入路径）
            checkAutoFullscreen();
        } else if (isPaused()) {
            if (isFullscreen()) showInfo();
            else hideInfo();
        }
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        mBinding.widget.size.setText(player().getSizeText());
    }

    @Override
    public void onTimeChanged(long time) {
        if (!isOwner() || !player().isVod()) return;
        long position = player().getPosition();
        long duration = player().getDuration();
        if (position < 0 || duration <= 0) return;
        mVod.onTimeChanged(time, position, duration);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (isRedirect()) return;
        if (event.getType() == RefreshEvent.Type.DETAIL) mVod.requestDetail();
        else if (event.getType() == RefreshEvent.Type.PLAYER) mVod.refresh();
        else if (event.getType() == RefreshEvent.Type.VOD) mVod.updateVod(event.getVod());
        else if (event.getType() == RefreshEvent.Type.SUBTITLE) player().setSub(Sub.from(event.getPath()));
        else if (event.getType() == RefreshEvent.Type.DANMAKU) player().setDanmaku(Danmaku.from(event.getPath()));
    }

    @Override
    protected long startPositionMs() {
        return mVod == null ? C.TIME_UNSET : mVod.startPositionMs();
    }

    private void setTrackVisible() {
        PlaybackAction.setTracks(player(), mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video);
    }

    private void setMediaOptionVisible() {
        PlaybackAction.setMediaOptions(player(), mBinding.control.action.edition, mBinding.control.action.chapter);
    }

    @Override
    public void onParse(Parse item) {
        mVod.selectParse(item);
    }

    private void onPaused() {
        // ⚠ 2026-10-16 审计修复（P1）：mController 由 buildAsync() 异步赋值，service() 已回调
        // 而 controller 仍为 null 的窗口内按 OK/媒体键会 NPE。全链路判空。
        if (controller() == null) return;
        controller().pause();
    }

    private void onPlay() {
        if (controller() == null) return;
        if (mHistory != null && isEnded()) controller().seekTo(mHistory.getOpening());
        if (!player().isEmpty() && isIdle()) controller().prepare();
        controller().play();
    }

    private boolean onSeekBack() {
        if (controller() == null) return true;
        controller().seekBack();
        return true;
    }

    private boolean onSeekForward() {
        if (controller() == null) return true;
        controller().seekForward();
        return true;
    }

    private boolean isFullscreen() {
        return fullscreen;
    }

    private void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    public boolean isUseParse() {
        return useParse;
    }

    public void setUseParse(boolean useParse) {
        this.useParse = useParse;
    }

    private View getFocus1() {
        return mFocus1 == null || mFocus1.getVisibility() != View.VISIBLE ? mBinding.video : mFocus1;
    }

    private View getFocus2() {
        return mFocus2 == null || mFocus2.getVisibility() != View.VISIBLE || mFocus2 == mBinding.control.action.opening || mFocus2 == mBinding.control.action.ending ? mBinding.control.action.next : mFocus2;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isFullscreen() && KeyUtil.isMenuKey(event)) onToggle();
        if (isVisible(mBinding.control.getRoot())) setR1Callback();
        if (isVisible(mBinding.control.getRoot())) mFocus2 = getCurrentFocus();
        if (isFullscreen() && isGone(mBinding.control.getRoot()) && mKeyDown.hasEvent(event) && service() != null) return mKeyDown.onKeyDown(event);
        if (KeyUtil.isMediaFastForward(event)) return onSeekForward();
        if (KeyUtil.isMediaRewind(event)) return onSeekBack();
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onSeeking(long time) {
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(time));
        mBinding.widget.action.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        if (seekTo(time)) hideCenter();
        mKeyDown.reset();
    }

    @Override
    public void onSpeedUp() {
        if (!player().isPlaying()) return;
        PlaybackAction.startSpeedPress(player(), mBinding.widget.message);
    }

    @Override
    public void onSpeedEnd() {
        PlaybackAction.hideSpeedHint(mBinding.widget.message);
        player().setSpeed(SpeedSetting.getPlayback());
    }

    @Override
    public void onKeyUp() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetOpening(position, duration)) {
            showControl(mBinding.control.action.opening);
        } else if (player().canSetEnding(position, duration)) {
            showControl(mBinding.control.action.ending);
        } else {
            showControl(getFocus2());
        }
    }

    @Override
    public void onKeyDown() {
        showControl(getFocus2());
    }

    @Override
    public void onKeyCenter() {
        if (player().isPlaying()) onPaused();
        else if (player().isEmpty()) onRefresh();
        else onPlay();
        hideControl();
    }

    @Override
    public void onSingleTap() {
        if (isFullscreen()) onToggle();
    }

    @Override
    public void onDoubleTap() {
        if (isFullscreen()) onKeyCenter();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == 1001 && service() != null) PlaybackIntent.onExternalResult(data, service()::dispatchNext, controller()::seekTo);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveHistory(false);
        if (PlayerSetting.isBackgroundOff()) mClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isVisible(mBinding.widget.center)) {
            hideCenter();
        } else if (isFullscreen()) {
            exitFullscreen();
        } else {
            mViewModel.stopSearch();
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        mClock.release();
        saveHistory(true);
        DanmakuApi.cancel();
        RefreshEvent.keep();
        App.removeCallbacks(mR1, mR2, mR3, mR4, mR5);
        // ⚠ 2026-10-16 审计修复（P1）：起播看门狗是挂在 App 主线程 Handler 上的 10s 定时器，
        // 原先这里只清了自己的 mR1..mR5，没有撤销看门狗 ⇒ 老人「转圈中按返回离开」后，
        // onStartTimeout 仍会在死亡实例上跑完整的「自动换线 → 跨站搜索 → Toast」链路
        // （Toast 是应用级单例，会弹在用户当前所在的任何界面），还会 recordLineFailure 污染线路健康分。
        // reset() 会 playRequestTime=0 + cancelStartWatchdog()，onStartTimeout 首行即 return。
        if (mVod != null) mVod.reset();
        super.onDestroy();
    }

    /** 播放页字号档位变化不重建：recreate 会打断播放并丢掉全屏态/焦点/会话标记（见 BaseActivity:onResume）。 */
    @Override
    protected boolean autoRecreateOnFontChange() {
        return false;
    }

    /** 播放中不因图片尺寸变化而重建：会打断播放。 */
    @Override
    protected boolean autoRecreateOnSizeChange() {
        return false;
    }
}
