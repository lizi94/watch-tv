package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.BackupManager;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.BaseDiffCallback;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.CustomTitleView;
import com.fongmi.android.tv.ui.dialog.ConfirmDialog;

import com.fongmi.android.tv.ui.dialog.HomeFilterDialog;
import com.fongmi.android.tv.ui.presenter.EmptyCardPresenter;
import com.fongmi.android.tv.ui.presenter.FuncPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.HomeChipPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.NetAwait;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.TouchGridAlign;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;
import com.google.common.collect.Lists;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

public class HomeActivity extends BaseActivity implements CustomTitleView.Listener, VodPresenter.OnClickListener, FuncPresenter.OnClickListener, HistoryPresenter.OnClickListener, HomeChipPresenter.OnClickListener, HomeFilterDialog.Listener {

    /** 内部「按返回键回首页」使用的标记：用于把这种回退和开机/启动器唤起区分开。 */
    public static final String EXTRA_BACK_HOME = "back_home";

    /** 内部重建标记（字号档位切换后 recreate）：重建不是用户打开软件，不得触发开机直达。 */
    public static final String EXTRA_REFRESH = "refresh";

    /**
     * 「非首页按返回」的进程内瞬时标记：<b>只走静态字段，不写进 Intent</b>。
     *
     * <p>为什么不能写进 Intent：首页实例被系统回收后，{@code BaseActivity#goHome()} 新建的首页会带着这个
     * 标记成为任务根，AMS 随之把该 Intent 记进<b>任务的 baseIntent</b> —— 于是待机唤醒 / 最近任务恢复时，
     * 每一次「重新打开软件」都会被误判成「从播放页返回首页」，开机直达<b>长期失效</b>。
     * 电视盒子「关机」多是待机，这条路径命中率很高。</p>
     */
    private static boolean backHomePending;
    private static long backHomeStamp;

    /** 内部重建（字号档位切换触发的 recreate）瞬时标记：语义同上，只走静态字段，不进 Intent。 */
    private static boolean internalRecreatePending;
    private static long internalRecreateStamp;

    /** 瞬时标记的有效窗口（ms）：只认紧接着到来的那一次首页显示，避免异常路径下标记永久残留。 */
    private static final long PENDING_TIMEOUT = 5000L;

    /**
     * 启动画面的等待策略：<b>不设固定时长</b>，完全由数据源状态驱动。
     *
     * <p>电视冷启动时 WiFi 常常还没关联上，配置（数据源）必然拉取失败。过去这一刻就切到首页并显示
     * 空态 —— 老人先看到一个「网络未连接」的页面，等网络真好了又不会自动续播，体验上等于
     * 「开机就是坏的」。现在改为<b>保持启动画面</b>，并把「什么时候结束等待」交给数据源本身：
     * 无网失败就挂着网络恢复监听、网络一通自动重载，<b>数据源一就绪立刻进入</b>，不等任何固定时长；
     * 有网仍失败则立刻进入首页（那种情况压根没有可等的对象）；老人等不及也可按返回键退出启动画面。</p>
     *
     * <p>这样「等多久」恒等于「数据源多久就绪」—— 既不会为了等而等，也不会出现早就加载好了
     * 却还停在转圈的情况。</p>
     */

    /** 由「非首页按返回」的页面调用：声明随即到来的这次首页显示是内部回退，不是用户打开软件。 */
    public static void markBackHome() {
        backHomePending = true;
        backHomeStamp = System.currentTimeMillis();
    }

    /** 由基类在「字号档位变化触发的重建」之前调用：声明这次重建不是用户打开软件。 */
    public static void markInternalRecreate() {
        internalRecreatePending = true;
        internalRecreateStamp = System.currentTimeMillis();
    }

    /** 消费回退标记（读取即清除），保证一次性；超出有效窗口视为失效并丢弃。 */
    private static boolean consumeBackHome() {
        boolean value = backHomePending && System.currentTimeMillis() - backHomeStamp <= PENDING_TIMEOUT;
        backHomePending = false;
        return value;
    }

    /** 消费内部重建标记（读取即清除），同样带有效窗口。 */
    private static boolean consumeInternalRecreate() {
        boolean value = internalRecreatePending && System.currentTimeMillis() - internalRecreateStamp <= PENDING_TIMEOUT;
        internalRecreatePending = false;
        return value;
    }

    /** chip 焦点防抖窗口（ms）：遥控器划过时只加载最终停下的那一项。 */
    private static final long CHIP_FOCUS_DELAY = 250L;

    /** 开机直达在每个界面实例的首次显示时只判定一次，避免多次加载回调重复触发。 */
    private boolean bootDirectDone;

    /**
     * 正在等待网络就绪的那次开机直达。
     *
     * <p>{@code onNewIntent} 会把 {@code bootDirectDone} 重置后重新判定一次（软件还活着就允许续播），
     * 若上一次等待还挂着，就会在旧回调里再发起一次 —— 表现为「开机后突然跳进详情页两次」或
     * 「用户已经在首页操作了，页面却又被抢走」。因此每次重新判定前必须先撤销上一次等待。</p>
     */
    private NetAwait.Handle bootDirectAwait = NetAwait.Handle.NONE;

    /**
     * 续播第二级的重试计数 + 定时任务（P2）。
     * 用「固定间隔有限次轮询」而非再次 whenReady —— 后者在已联网时会立即回调、递归自旋。
     */
    private int bootDirectRetry;
    private static final int BOOT_DIRECT_MAX_RETRY = 15;
    private final Runnable mBootDirectRetry = this::tryBootDirect;

    /** 待续播项：仅在「本次应当续播、但数据源还没就绪」时非空；就绪后立即消费掉。 */
    private History mBootDirect;

    /** 网络阶段是否已过关：避免在「有网但数据源还没加载好」时反复注册网络回调（会空转自旋）。 */
    private boolean bootNetReady;

    /** 老人在首页是否已经操作过（点过任何条目）：已操作则不再自动抢屏跳转。 */
    /** 冷启动落焦后第二次回顶的延时：覆盖「内容行异步插入 → 重新布局」这个时间窗。 */
    private static final long HOME_TOP_DELAY = 300L;

    /** 内容区重建后补落焦的延时：等新内容布局完成再判断「焦点是不是真的丢了」。 */
    private static final long HOME_FOCUS_RESTORE_DELAY = 400L;

    private boolean bootInteracted;

    /**
     * 配置（数据源）是否处于「加载失败」状态。
     *
     * <p>由 {@link #getCallback()} 的成功 / 失败回调维护，供 {@link #showContentEmpty()}
     * 决定空态文案：失败时说「网络没有连接 / 电视源暂时连不上」并允许重试，
     * 成功但列表为空时才说「暂无推荐内容」。</p>
     */
    private boolean configFailed;

    /**
     * 配置加载失败后挂着的「等网络恢复就重载」监听。
     *
     * <p>与 {@link #bootDirectAwait} 分开：两者的生命周期与触发语义都不同 —— 配置等待负责
     * 「把数据源拉回来」，续播等待负责「数据源回来之后跳转」；两者都不设超时（等到就绪为止），
     * 且配置等待在 {@code onNewIntent} 补检成功时也要撤销。</p>
     */
    private NetAwait.Handle configAwait = NetAwait.Handle.NONE;

    private ActivityHomeBinding mBinding;
    private ArrayObjectAdapter mHistoryAdapter;
    private ArrayObjectAdapter mFuncAdapter;
    private ArrayObjectAdapter mChipAdapter;
    private ArrayObjectAdapter mAdapter;
    private ListRow mChipRow;
    private ListRow mHistoryRow;
    /**
     * 当前分类的筛选参数（key = {@code Filter.getKey()}，value = {@code Value.getV()}）。
     *
     * <p>这是「筛选能力」真正的开关：{@code loadChip} 请求分类内容时把它传给
     * {@code categoryContent(..., extend)} —— 之前这里恒传 {@code new HashMap<>()}，
     * 所以电视端从来没有筛选能力（手机端 TypeFragment 一直有）。</p>
     *
     * <p>生命周期：<b>切换分类时清空</b>（不能让 A 分类的「2024 年」带到 B 分类）；
     * 同一分类内调整条件时保留并覆盖。从详情页返回首页不清（老人调好的条件不该丢）。</p>
     */
    private HashMap<String, String> mFilterExtend = new HashMap<>();
    private HistoryPresenter mPresenter;
    private HomeChipPresenter mChipPresenter;
    private SiteViewModel mViewModel;
    private Result mResult;
    /**
     * 首页分类（chips）列表 —— 与 {@link #mResult} 分开存。
     *
     * <p>⛔ 2026-10-24 修：{@code mResult} 原来同时承担「首页 types 来源」与「当前内容结果」两个职责，
     * 而 {@code onContentResult} 会把<b>分类/翻页内容</b>（本就不带 types）也写进 mResult
     * ⇒ 之后任何一次 {@code ConfigEvent.COMMON → setFunc() → buildChips()} 都读到空 types：
     * 分类 chips 整行消失（只剩「推荐」）、{@code resetFilterSelection()} 一个勾都不清。
     * 现在 types 只在真的带了分类（homeContent 响应）时更新，mResult 只表示「当前内容」。</p>
     */
    private List<Class> mHomeTypes = new ArrayList<>();
    private Clock mClock;
    /** 当前选中的 chip（「推荐」为 String，分类为 Class 对象），用于选中高亮与刷新后保持；空串 = 不选中。 */
    private Object mChipSelected = "";
    /** 已加载（或正在加载）的 chip：点按与焦点防抖都走 loadChip，用它去重，避免无谓的重复请求与闪烁。
     *  首次加载由 getVideo() 预置为「推荐」（推荐内容此时已经在路上）。 */
    private Object mShownChip = "";
    /** 焦点停在 chip 上、等待防抖到期的那一项。 */
    private Object mChipPending;
    /** 内容区分页加载器：滚动到底自动加载分类下一页（推荐 homeContent 无分页接口，不参与翻页）。 */
    private CustomScroller mScroller;
    /** 内容区最后一行：跨页接续余数用，避免每页各自分行在滚动中出现成组空列位。 */
    private ArrayObjectAdapter mLast;
    /** 内容行 Presenter：页面级复用，避免每翻一页多堆一个（见 {@link #vodPresenter}）。 */
    private VodPresenter mVodPresenter;
    /** 内容区空态占位卡标记项（EmptyCardPresenter.Item）：当前正显示的空态行，数据到达或内容区重建时移除。 */
    private EmptyCardPresenter.Item mEmptyItem;
    /** chip 焦点防抖：App.post(r, delay) 内部先 removeCallbacks，因此快速划过整行只会发最后一次请求。 */
    private final Runnable mChipRunnable = () -> loadChip(mChipPending, false);
    /**
     * 内容请求的<b>代际令牌</b>：每发起一次 chip 加载就自增一次，响应回来时只有「我这一代仍是最新」
     * 才允许渲染。
     *
     * <p>为什么 {@code mShownChip} 去重还不够：翻页走 {@link #onLoadMore}，它<b>不改</b> mShownChip，
     * 所以「分类 A 滚到底触发第 2 页（在途）」→ 用户切到分类 B → B 的第 1 页先渲染 →
     * A 的第 2 页姗姗来迟，此时 @{code mShownChip} 已经是 B，但迟到的响应照样会被
     * {@code observe} 无条件 {@code addVideo} 追加进 B 的列表里，表现为「B 分类里混进了 A 的条目」。
     *
     * <p>ViewModel 侧（{@code ViewModelTaskRunner}）本身有 taskId 校验并会 cancel 旧任务，但
     * {@code MutableLiveData.postValue} 一旦投递就已进入主线程队列，取消来不及，所以必须在 Activity
     * 侧再兜一层。用「代际」而非「chip 相等」判断：后者会让「同一分类点按强制重载」的旧响应
     * 与新响应因 chip 相同而无法区分。
     */
    private int mContentToken;

    /**
     * 在途内容的代际快照：由 {@link #loadChip} / {@link #onLoadMore} 在<b>发起请求前</b>同步为当时的
     * {@link #mContentToken}，{@link #onContentResult} 回调时两者相等才认这条响应。
     */
    private int mResponseToken;

    /** chip 行当前绑定的项（首项「推荐」为 String，其后为站源分类 Class）：内容未变化时跳过重绑，
     *  避免 setItems 引起的焦点抖动被「移到即加载」误判成切了分类。 */
    private List<Object> mChipItems;

    /**
     * 内容请求用的站点。
     *
     * <p>2026-10-26：主源若是网盘源（开关开启），这里返回「第一个非网盘可用源」——
     * 首页推荐、分类列表、点击媒体进入全部走这个方法，所以一处替换即覆盖所有入口。
     * <b>只影响取源，不写回配置</b>；全站都是网盘源时原样返回（兜底不空白）。</p>
     */
    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 首页是 singleTop：实例被复用时 getIntent() 会一直停在「创建那一刻」的那份，
        // 必须同步过来，否则后面所有基于 getIntent() 的判断（含开机直达）都在拿旧数据。
        setIntent(intent);
        // 回退标记必须在这里消费掉：实例复用不会走 checkBootDirect，留着的标记会污染下一次真正冷启动。
        boolean backHome = consumeBackHome() || intent.getBooleanExtra(EXTRA_BACK_HOME, false);
        consumeInternalRecreate();
        checkAction(intent);
        // 从播放页 / 设置页返回首页走 CLEAR_TOP 复用，<b>不会</b>再走 onCreate → initConfig()。
        // 若启动时配置就因无网络失败，首页会一直是空状态且永不恢复 —— 这里补检一次：
        // 站点列表为空（= 配置没加载成功）且现在网络可用，就重载配置并刷新首页。
        // 仅在「确实没加载成功」时触发，正常返回首页（有站点）零开销、不闪。
        if (VodConfig.get().getSites().isEmpty() && NetAwait.isOnline()) initConfig();
        // 不是「从播放页/设置页返回首页」= 用户在软件内重新打开（图标 / 遥控器自定义按键 / 桌面拉起）。
        // 按定下的两层语义：只要软件还活着就允许续播，所以重新判定一次开机直达。
        if (!backHome) {
            resetBootDirectState();
            bootDirectDone = false;
            checkBootDirect();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mResult = Result.empty();
        mClock = Clock.create(mBinding.clock);
        mBinding.progressLayout.showProgress();
        // 电视端只申请通知权限：本端 FileChooser 走自带 FileActivity（useInternal 恒 true），
        // 选文件直接返回真实路径 Uri.fromFile(file)，不存在「拷进缓存导致路径不对」的问题；
        // 而 Android 11+ 申请「所有文件访问」会跳出 App 到系统设置页，对老人是纯打扰。
        PermissionUtil.requestNotify(this);
        DLNARendererService.start(this);
        setRecyclerView();
        setViewModel();
        setAdapter();
        // ⚠⚠ 2026-10-16（R65 修复）：setFunc() 原先只有 ConfigEvent.COMMON 一个触发点，而
        // COMMON 在配置网络往返**完成之后**（finally 里）才派发 ⇒ 缓存进首页（R63）后、
        // 慢网/数据源失效时，功能行要空白好几秒甚至数十秒——「首页没有功能区」的第二个成因。
        // 这里首帧先填一次（LiveConfig.hasData() 读内存态，同步无网络；无直播数据时显示
        // 「推送」占位，语义正确），COMMON 到达时再刷新（setFunc 幂等）。
        setFunc();
        // ⚠⚠ 2026-10-16（R62）：**必须在 initConfig() 之前**同步用本地缓存重建内存态。
        // 门控判据 S = !VodConfig.get().getSites().isEmpty()，而 sites 只能由一次全量配置 JSON
        // 的网络往返填上 ⇒ 「上次加载成功过、但本次开机没网」时 S=false ⇒ evaluateBoot()
        // 判成「数据源没就绪」⇒ 在启动画面「正在连接网络…」**干等一个不会来的网络**。
        // 冷启动先用已有的 Config.json 列（Room 列早就在、零迁移）就地重建 ⇒ S 立刻为真 ⇒
        // 门控一行不改就走出口①，后台照常走 initConfig() 刷新。
        // ⚠ 同步读 Room 有主线程耗时，但只有一行小记录（findOne 单行），且这是「不干等」的前提。
        // ⚠⚠ 2026-10-16（R63 补刀）：光让 S 为 true 还**不够** —— evaluateBoot() 的三个触发点
        // （getCallback().success() / error() / onConfigEvent(VOD)）全部发生在配置网络往返
        // **完成之后**，没有任何人会「提前」调它 ⇒ 有网冷启动仍然要等网络往返才显示首页，
        // R62 的缓存在有网场景**没有生效**。缓存重建成功就必须立刻裁决一次：
        // EventBus 在 BaseActivity.onCreate 里先于 initView 注册，ConfigEvent.VOD 稍后抵达时
        // RefreshEvent.home() → getVideo() 会照常填充内容，不丢链路；evaluateBoot 首行
        // isProgress 守卫 + success/error 回调里的重复调用都幂等，重复裁决无害。
        if (VodConfig.get().loadFromCache()) evaluateBoot();
        initConfig();
        setTitle();
        setLogo();
        // 开机直达的资格判定放在这里，而不是等 showContent() 之后再判 ——
        // 现在「数据源没就绪」时首页会保持启动画面继续等，showContent() 可能很晚才被调用；
        // 而续播应当从进入首页那一刻就开始并行等（先等网络、再等数据源），不能拖到那时才起步。
        checkBootDirect();
    }

    @Override
    protected void initEvent() {
        // ⚠ 2026-10-17 取消标题的点击注册：标题与头像一律**纯装饰、无功能**。
        // 换源入口全站唯一 = 「设置 → 长辈设置 → 一键锁定」（ElderLockActivity，入口见 ElderSettingsActivity）。
        // （原先那句 `mBinding.title.setListener(this)` 指向的 showDialog() 早已是空实现，
        //   点击本就没有任何反应，属于残留；这里把注册一并去掉，让代码与意图一致。）
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                mBinding.toolbar.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
                if (mPresenter.isDelete()) setHistoryDelete(false);
            }
        });
        // 内容区滚动到底时自动加载分类下一页（CustomScroller 用通用 LayoutManager API 判定触底：
        // leanback VerticalGridView 的内部 GridLayoutManager 并非 androidx 的 LinearLayoutManager，
        // 之前用 instanceof LinearLayoutManager 判定恒为 false，导致分类永远无法翻页）
        // 到底 / 上限提示：onLimitReached 只回调一次，避免老人每滚到底都被同一句 Toast 打扰。
        // ⚠ 必须是 Callback 的匿名实现而不是 CustomScroller 的子类 —— onLimitReached 是
        // CustomScroller.Callback 的 default 方法，覆写它要落在 Callback 上。
        mBinding.recycler.addOnScrollListener(mScroller = new CustomScroller(new CustomScroller.Callback() {
            @Override
            public boolean onLoadMore(String page) {
                return HomeActivity.this.onLoadMore(page);
            }

            @Override
            public void onLimitReached() {
                Notify.show(R.string.list_no_more);
            }
        }));
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            FileChooser.getUri(intent, uri -> loadLive(UrlUtil.toLocalUrl(uri)));
        } else {
            FileChooser.getUri(intent, uri -> VideoActivity.file(this, uri));
        }
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Integer.class, new HeaderPresenter());
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(EmptyCardPresenter.Item.class, new EmptyCardPresenter());
        // 封面网格 / 最近观看：关闭 leanback 的焦点放大高亮（见 CustomRowPresenter 的说明）。
        // 媒体封面是 3:4 竖版，卡片高 ≈ 1.4 倍宽，放大时上下外扩量是左右的 1.33 倍，
        // 而横竖间距都只有 16dp —— 任何一档放大都会在垂直方向越出、压住相邻行。
        // 关闭后四周间距恒为 16dp，与焦点框（5dp，同 ag_stroke_focus）一起构成反馈。
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_NONE), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), FuncPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), HomeChipPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ALIGNED), HistoryPresenter.class);
        TouchGridAlign.attach(mBinding.recycler);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        // 行间距取「本页 Resources」的密度（BaseActivity#hackResources 保证的那一份），不要用
        // ResUtil 的无参重载：它走 Application 侧 Resources，历史上会被 AndroidAutoSize 覆写成
        // 「屏宽/1920」（不含字号档位与大屏补偿），造成首屏行间距与其他组件不同源。
        mBinding.recycler.setVerticalSpacing(Math.round(16 * ResUtil.getDisplayMetrics(this).density));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, this::onContentResult);
    }

    /**
     * 内容响应统一入口：<b>只接受「最新一代」请求的响应</b>。
     *
     * <p>代际由 {@link #mContentToken} 维护：{@link #loadChip} 与 {@link #onLoadMore} 每次发起请求都
     * {@code ++}，这里比对 {@link #mResponseToken} —— 两者不等说明这一条响应属于已被切掉的旧请求
     * （典型：分类 A 的翻页响应在用户切到分类 B 之后才回来），直接丢弃，既不追加条目也不动翻页状态。
     *
     * <p>注意「丢弃」必须发生在{@code endLoading}之前：迟到的分页响应会带着上一分类的 pageCount
     * 覆盖翻页状态机，导致当前分类的「还有下一页」判断错乱。
     */
    private void onContentResult(Result result) {
        if (mResponseToken != mContentToken) return;   // 迟到的旧响应：整条丢弃
        boolean first = mScroller.first();
        mAdapter.remove("progress");
        mScroller.endLoading(result);
        if (result.getList().isEmpty()) {
            // 内容区空态：在分类chips后插入一张「空态占位卡」行（复用 EmptyCardPresenter），
            // 保留功能区、分类chips、最近观看等组件 —— 不再隐藏整个 recycler。
            // 占位卡宽度撑满内容区、高度固定（屏幕可视高度的一定比例、不延伸屏底），
            // 随内容流自然滚动（占位效果，不漂移），文案在卡内水平+垂直居中。
            // 首次加载为空显示空态，翻页到底由 endLoading 静默停用。
            //
            // 注意：首屏为空时**不要**解锁去重键（mShownChip）。这里曾经清空过它，结果是
            // 「源返回了分类、但推荐列表为空」的源会死循环：清空去重键 → 同一分支 buildChips()
            // 重绑 chip 行 → 焦点抖动触发 onFocusChange → 250ms 后 loadChip → 因去重键已空而重新
            // 请求 → 又是空 → 再清空 …… 视觉上就是空态文案与进度行无限刷新。
            // 重试能力改由「点按」这个显式动作提供：onItemClick 走 loadChip(item, true) 强制路径。
            if (first) {
                if (keepLoadingInsteadOfEmpty()) mAdapter.add("progress");
                else showContentEmpty();
            }
        } else {
            removeContentEmpty();
            addVideo(mResult = result);
        }
        Cache.clear().put(result);
        // 推荐内容（homeContent）会携带站源分类 class 列表，用它重建 chips（分类/翻页内容不带 types）。
        // 部分源 homeContent 只返回分类、推荐列表为空，此时也必须采纳 result 的分类，
        // 否则 mResult 仍是空 Result，分类 chips 会丢失（手机版因无条件更新而无此问题）。
        if (!result.getTypes().isEmpty()) {
            // 只在这里更新分类列表（拷贝一份，避免 result 被复用后改动影响 chips）
            mHomeTypes = new ArrayList<>(result.getTypes());
            buildChips();
        }
    }

    private void setAdapter() {
        mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        mAdapter.add(new ListRow(mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this))));
        mChipRow = new ListRow(mChipAdapter = new ArrayObjectAdapter(mChipPresenter = new HomeChipPresenter(this::onItemClick, this::onItemFocus)));
        mAdapter.add(mChipRow);
        buildChips();
    }

    /** 重建分类 chips：首项「推荐」+ 推荐内容返回的站源分类（result.getTypes，含 typeId）。
     *  分类此前不显示的根因：依赖 site.getCategories()，多数数据源未配置该字段而为空；
     *  homeContent 返回的 class 列表才是权威分类来源。默认不选中任何 chip（空串），点选后才高亮。
     *
     *  <p>列表内容没变直接返回：setItems 会让 chip 行整行重绑，焦点抖动会被「移到即加载」
     *  误当成切换分类而重新请求（空态下就变成无限刷新）。{@link Class} 已按 typeId 实现
     *  equals，比较可靠。</p> */
    private void buildChips() {
        List<Object> items = new ArrayList<>();
        // ⚠ 2026-10-17：「推荐」分类 chip 只在**确实有数据源**时才加。
        // 原实现无条件加「推荐」，于是首次安装 / 未导入数据源时，chips 行只剩一个孤零零的
        // 「推荐」、下面却是空态卡（「暂时连接不上」+「重新加载」）——老人会以为
        // 「推荐里有东西但加载不出来」，或以为软件坏了。无数据源时这一行直接空着，语义更清楚。
        // 数据源导入后会自动出现（ConfigEvent.VOD → getVideo → buildChips 会重跑一遍）。
        if (!VodConfig.get().getSites().isEmpty()) items.add(getString(R.string.home_recommend));
        items.addAll(mHomeTypes);
        if (!items.equals(mChipItems)) {
            mChipItems = items;
            mChipAdapter.setItems(items, null);
            boolean keep = items.contains(mChipSelected);
            selectChip(keep ? mChipSelected : "");
        }
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                // 配置加载成功 —— 注意这不等于「网络通」：本地导入的源、缓存里已有的源，
                // 在完全没网时同样会走到这里（判据 VodConfig#isLoaded() 只看站点列表是否非空）。
                // 所以「转圈后直接进首页」在无网时出现是正确行为，不是漏判网络。
                configFailed = false;
                cancelConfigAwait();
                evaluateBoot();
            }

            @Override
            public void error(String msg) {
                // 配置（数据源）没拉到。不把「配置取得失败」这种术语抛给老人，也不弹 Toast
                //（3.5 秒自动消失、没有下一步指引）。
                // 这里只登记「失败」这一个事实，进退由 evaluateBoot() 按「数据源是否就绪」统一裁决 ——
                // ⛔ 绝不在本方法里再拿 NetAwait.isOnline() 判一次：那会让「网络」与「数据源」
                // 两个维度互相污染，同一个「连不上」被启动画面和首页空态各说一遍（2026-10-13 修正）。
                configFailed = true;
                // ⚠⚠ 2026-10-16 审计修复（P1/P4）：**已进首页时必须在这里直接补救**。
                // R62/R63 的本地缓存会让「有缓存」的用户**立即**进首页（isProgress 已变 false），
                // 于是 evaluateBoot() 首行的 isProgress 守卫把本次（以及后续所有）裁决整个挡掉
                // ⇒ 出口③（挂网络恢复监听）**永远不会执行** ⇒ 网好了首页也不会自己恢复，
                // 老人必须自己找到并按下「重新加载」。这是 R63 的副作用，在此就地补上：
                //   ① awaitConfigNetwork()：网络恢复后自动重载配置 → ConfigEvent.VOD → getVideo() 刷新首页。
                //      ⛔ 这不是「再判一次网络决定进退」（原注释禁止的那种污染）：
                //      它内部的 isOnline 守卫只是「有网就别挂监听」的**防忙循环**判据——
                //      有网但源挂时直接不挂，不会形成「失败→立即重载→又失败」的忙循环。
                //   ② awaitBootDirect()：与出口①对称（P4），让续播第二级仍有驱动。
                if (!mBinding.progressLayout.isProgress()) {
                    awaitConfigNetwork();
                    awaitBootDirect();
                    return;
                }
                evaluateBoot();
            }
        };
    }

    /**
     * 启动门控的<b>唯一收敛点</b>（2026-10-13 定案）：只问「数据源就绪了吗」，
     * 网络仅用来决定「还要不要继续等」，自己不再产出任何提示。
     *
     * <p><b>两个判据互相正交</b>：</p>
     * <ul>
     *   <li>{@code S = !VodConfig.get().getSites().isEmpty()} —— 数据源就绪，<b>与网络无关</b>
     *       （本地导入的源、缓存里已有的源，没网也是就绪的）；</li>
     *   <li>{@code N = NetAwait.isOnline()} —— 网络就绪，只在「数据源还没就绪」时决定是等、还是放弃。</li>
     * </ul>
     *
     * <p><b>三个出口，语义互不重叠</b>（本轮去重的核心）：</p>
     * <ol>
     *   <li>{@code S} → 进首页（正常）。“有数据源 + 完全没网 → 转圈后进首页”走的正是这条，
     *       它本来就是对的；</li>
     *   <li>{@code !S && N} → 进首页 + 空态卡「暂时连接不上」+「重新加载」。
     *       有网仍拿不到数据源 = 数据源侧的问题，<b>没有可等的对象</b>，不能把老人锁死在转圈里；</li>
     *   <li>{@code !S && !N} → <b>留在启动画面</b>，显示「正在连接网络…」，并挂网络恢复监听：
     *       网络一通自动重载配置，回到判据 ①。无网时<b>绝不降级成首页空态</b> —— 那会让老人
     *       一开机就先看到一张「坏了」的脸，而他家的路由器可能只是起得慢。</li>
     * </ol>
     *
     * <p><b>防回退</b>：首行的 {@code isProgress()} 守卫保证「一旦进过首页就永不再回退」，
     * 因此换源、返回首页等场景重复触发本方法也不会把页面打回启动画面。</p>
     */
    private void evaluateBoot() {
        if (!mBinding.progressLayout.isProgress()) return;
        cancelConfigAwait();
        if (!VodConfig.get().getSites().isEmpty()) {
            showContent();
            awaitBootDirect();
            return;
        }
        if (NetAwait.isOnline()) {
            showContent();
            return;
        }
        showBootHint();
        awaitConfigNetwork();
    }

    /**
     * 配置加载失败后挂网络恢复监听：网络一就绪就自动重新加载配置并刷新首页。
     *
     * <p><b>为什么需要它</b>：电视盒子冷启动时网络可能还没连上，此时配置拉取失败、首页停在
     * 「没有任何内容」的状态。<b>原实现在这种情况下永远不会自己恢复</b> —— 上游除了
     * {@code BootReceiver} 给直播源做过一次网络恢复重载，点播侧完全没有。老人能做的只有
     * 「关机重开」，而重开时的网络状态可能更糟。</p>
     *
     * <p><b>为什么不设超时</b>（{@code timeoutMs = 0}）：回调本身没有副作用，只是「等网络好了替我
     * 重载一次」，等到用户离开首页（{@code onDestroy}）就撤销。</p>
     *
     * <p><b>⚠ 必须只在「现在没网」时挂</b>：{@code NetAwait} 在已经联网时会<b>立即</b>执行回调。
     * 若网络正常、只是数据源服务器挂了，配置同样会失败 —— 此时若还挂监听，就会变成
     * 「失败 → 立即重载 → 又失败 → 立即重载」的忙循环，把 CPU 和网络都打满。
     * 数据源侧异常只提示「电视源暂时连不上」，重试交给老人按确定（或切源），不做自动轮询。</p>
     */
    private void awaitConfigNetwork() {
        cancelConfigAwait();
        // 有网仍失败 = 数据源那边的问题，自动重试只会忙循环，交给用户手动重试。
        if (NetAwait.isOnline()) return;
        configAwait = NetAwait.whenReady(0L, this::reloadConfig, () -> {
        });
    }

    /** 网络恢复后的重载：先撤销等待（一次即够），再重走一遍配置加载。 */
    private void reloadConfig() {
        configAwait = NetAwait.Handle.NONE;
        if (isFinishing() || isDestroyed()) return;
        // 配置成功后 success() 会 showContent()；失败则 error() 再判一次 —— 有网了就不再挂，
        // 仍是没网才重新挂上，形成「断网就等、通了就试一次」的闭环，不会忙循环。
        initConfig();
    }

    private void cancelConfigAwait() {
        configAwait.cancel();
        configAwait = NetAwait.Handle.NONE;
    }

    /** 切到「内容态」（结束启动画面）。开机直达的判定不在这里 —— 见 {@code initView} 末尾的说明。 */
    private void showContent() {
        mBinding.progressLayout.showContent();
        hideBootHint();
        checkAction(getIntent());
        setFocus();
    }

    /**
     * 显示「正在连接网络…」提示：只用于「配置加载失败 + 当前确实没网」这种需要长时间等待的场景。
     *
     * <p><b>为什么不放在任何转圈都显示</b>：正常情况下配置几秒就加载完，那点等待老人完全无感；
     * 只有真的没网、可能要等 WiFi 关联上（十几秒到几十秒）时，才需要一行字告诉老人
     * 「软件在努力，不是死机」。这也是「不设固定等待时长」这个取舍的对冲。</p>
     *
     * <p><b>⚠ 必须校验当前确实还停在启动画面</b>：老人按返回键进入首页之后，若再发生
     * 「无网 → error()」（例如他点了「重新加载」但仍然无网），这行字不该浮到首页中央去 ——
     * 那时首页自己的空态卡已经给出了正确反馈。</p>
     */
    private void showBootHint() {
        if (!mBinding.progressLayout.isProgress()) return;
        mBinding.bootHint.setVisibility(View.VISIBLE);
    }

    /** 隐藏启动等待提示。切到内容态时必然调用。 */
    private void hideBootHint() {
        mBinding.bootHint.setVisibility(View.GONE);
    }

    /**
     * ⑧ 开机直达：开机或从启动器打开时，直接进入上次观看的点播节目，方便老人开机即看。
     *
     * <p><b>本方法现在只做「资格判定」，不发起跳转</b>（2026-10-12 第二轮改造）。判定成立时把待续播项
     * 记到 {@link #mBootDirect}，由 {@link #awaitBootDirect()} 用「网络 → 数据源」两级门控决定何时真正跳。
     * 原因是旧版只等网络就绪：电视冷启动时网络一通，{@code bootDirectAwait} 就被唤醒并<b>立刻</b>跳转，
     * 而配置重载此时刚发出 HTTP 请求，{@code VodConfig#getSites()} 还是空的 ⇒ 详情页取不到站点
     * ⇒ 请求失败 ⇒ 停在空白页（剧名为空时是整页「这里什麽都没有」，非空时是自动换源后的空壳）。</p>
     *
     * <p>判定提前到 {@code initView} 而不是等 {@code showContent()}：现在数据源没就绪时首页会保持
     * 启动画面继续等，{@code showContent()} 可能很晚才被调用，而续播必须从进入首页那一刻就并行开始等。</p>
     */
    private void checkBootDirect() {
        if (bootDirectDone) return;
        bootDirectDone = true;
        // 两个瞬时标记必须先消费再判断：若门控提前 return，标记会残留到下一次冷启动，误伤直达。
        if (consumeBackHome()) return;
        if (consumeInternalRecreate()) return;
        // 退出自动续播关闭：不再自动跳入上次观看的节目，落在首页
        if (!Setting.isAutoResume()) return;
        if (!isPlainLaunch(getIntent())) return;
        List<History> items = History.get();
        if (items.isEmpty()) return;
        History item = items.get(0);
        if (TextUtils.isEmpty(item.getVodId())) return;
        // 只登记「本次应当续播」；什么时候跳、能不能跳，交给两级门控。
        mBootDirect = item;
        awaitBootDirect();
    }

    /**
     * 推进开机续播：<b>两级门控</b> —— 先等网络就绪，再等数据源就绪，全绿才跳转。
     *
     * <p><b>为什么必须分两级、不能写成「没就绪就再挂一次网络等待」</b>：
     * {@link NetAwait#whenReady} 在「当前已经联网」时会<b>立即</b>执行回调，所以「有网但配置还没加载好」
     * 时若再挂一次网络等待，就会立刻被唤醒、又立刻判失败，形成空转自旋。因此网络阶段<b>只挂一次</b>、
     * 用 {@link #bootNetReady} 记下已过关；数据源阶段完全被动 —— 等配置加载成功的回调
     * （{@code getCallback().success()}）来推进，不重新注册任何网络回调。</p>
     */
    private void awaitBootDirect() {
        if (mBootDirect == null) return;
        // 老人已经在操作了 → 彻底放弃本次抢屏（清掉待续播项，之后配置成功也不会再被拉起）。
        if (bootInteracted) {
            mBootDirect = null;
            return;
        }
        if (isFinishing() || isDestroyed()) return;
        if (!bootNetReady) {
            // 第一级：等网络。已经联网时 whenReady 会立即回调（onReady 里置标记后再走进来）。
            // 不设超时：网络一直不来就一直不跳，页面停在首页让老人自己操作，绝不乱跳。
            // ⛔ 已在等待中则不重复注册：本方法有 4 个入口（checkBootDirect / evaluateBoot 的源就绪分支 /
            // ConfigEvent.VOD / getCallback().success），每次进来都 new 一个 Waiter 会覆盖句柄，
            // 旧 Waiter 泄漏且其回调仍会在网络恢复时抵达已重置的实例（与手机端同款守卫）。
            if (bootDirectAwait != NetAwait.Handle.NONE) return;
            bootDirectAwait = NetAwait.whenReady(0L, () -> {
                bootNetReady = true;
                awaitBootDirect();
            }, () -> {
            });
            return;
        }
        // 第二级：网络已就绪，只看数据源。没就绪就交给延迟重试（见 tryBootDirect）与
        // success() 回调两条驱动共同推进。
        tryBootDirect();
    }

    /**
     * 续播第二级的**判决 + 有限重试**。
     *
     * <p>⚠ 2026-10-16 审计修复（P2）：原先第二级只在「一级网络回调触发的这一次」与
     * {@code success()} 里被推进。而一级的 {@code whenReady} 用的是「网络 available」，
     * 第二级的 {@code isBootDirectReady()} 却要求 {@code NET_CAPABILITY_VALIDATED}
     * —— 开机头几秒 available 已 true 但 VALIDATED 还没就绪是常态 ⇒ 那一次必然判 false，
     * 而 {@code onReady} 只触发一次、{@code success()} 又会被 isProgress 守卫挡掉
     * ⇒ <b>慢网开机续播被静默吞掉</b>（同一台电视有时播、有时不播）。</p>
     *
     * <p>⛔ 这里**不能**再次调用 {@code NetAwait.whenReady} 来实现重试：它在「已认为联网」时
     * 会**立即**回调 ⇒ 递归自旋。改用固定 1 秒间隔的有限次轮询（最多
     * {@link #BOOT_DIRECT_MAX_RETRY} 次），超时就彻底放弃 —— 老人开机十几秒后还没就绪，
     * 就不该再抢屏了。</p>
     */
    private void tryBootDirect() {
        if (mBootDirect == null || bootInteracted) return;
        if (isFinishing() || isDestroyed()) return;
        if (isBootDirectReady()) {
            bootDirectRetry = 0;
            fireBootDirect();
            return;
        }
        if (bootDirectRetry++ < BOOT_DIRECT_MAX_RETRY) App.post(mBootDirectRetry, 1000);
    }

    /**
     * 开机续播的「放行」判据：三条全绿才允许跳转详情页。
     *
     * <p>第三条（该 siteKey 在当前配置里存在）是这轮补上的关键一环：老人换过数据源之后，
     * 历史记录里的 siteKey 可能已经不在新配置的站点列表里，此时即便网络与配置都正常，
     * 详情页依然取不到站点、依然会失败。少这一条就堵不住这个口子。</p>
     */
    private boolean isBootDirectReady() {
        if (!NetAwait.isOnline()) return false;
        if (VodConfig.get().getSites().isEmpty()) return false;
        if (!VodConfig.get().getSite(mBootDirect.getSiteKey()).isEmpty()) return true;
        // ⛔ 2026-10-24 修（与手机端同口径）：历史记录里的 siteKey 一旦不在当前配置里
        // （换过数据源、被「一键锁定」固定到别的源、那个站点被移除），这里就会恒为 false ——
        // 电视端表现为**每次断电重启都不续播**，而且没有任何提示，老人只会觉得「上次看的没了」。
        // 现在退化为「当前主站点可用就放行」，由 fireBootDirect 用主站点打开。
        return !VodConfig.get().getHome().isEmpty();
    }

    /** 三绿后真正发起续播。此时详情页必定能取到站点，不存在「跳进去再失败」。 */
    private void fireBootDirect() {
        History item = mBootDirect;
        mBootDirect = null;
        bootDirectAwait.cancel();
        bootDirectAwait = NetAwait.Handle.NONE;
        if (item == null || bootInteracted) return;
        if (isFinishing() || isDestroyed()) return;
        // siteKey 失效时用当前主站点打开（与 isBootDirectReady 的降级判据配套，与手机端同口径）
        String key = item.getSiteKey();
        if (VodConfig.get().getSite(key).isEmpty()) key = VodConfig.get().getHome().getKey();
        VideoActivity.start(this, key, item.getVodId(), item.getVodName(), item.getVodPic());
    }

    /** 重新判定开机直达前清掉上一轮的等待与进度标记（{@code onNewIntent} 会走到）。 */
    private void resetBootDirectState() {
        bootDirectAwait.cancel();
        bootDirectAwait = NetAwait.Handle.NONE;
        App.removeCallbacks(mBootDirectRetry);
        bootDirectRetry = 0;
        mBootDirect = null;
        bootNetReady = false;
        // 还是一次新的「打开软件」，允许续播；已操作标记随之复位。
        bootInteracted = false;
    }

    /**
     * 是否属于「用户主动打开软件」这类普通启动。
     *
     * <p><b>为什么用排除法而不是白名单</b>：原先只认 {@code action == null || ACTION_MAIN}，
     * 但遥控器自定义按键（盒子「一键直达」/ 桌面快捷方式）拉起应用时，intent 往往用
     * {@code ACTION_VIEW} 或盒子自定义 action + 显式 component —— 被白名单判成「非普通启动」，
     * 开机直达直接失效，而软件图标启动（标准 {@code ACTION_MAIN}）却正常。
     * 这就是「遥控器按键启动不续播、图标启动正常」的根因。</p>
     *
     * <p>改为只排除<b>确实带着别的事来</b>的启动：内部回退 / 内部重建 / 携带 data（打开文件或链接）/
     * 分享 / 搜索；其余一律视为用户主动打开软件。这样任何厂商的自定义 action 都不会再误伤。</p>
     */
    private boolean isPlainLaunch(Intent intent) {
        if (intent == null) return true;
        if (intent.getBooleanExtra(EXTRA_BACK_HOME, false)) return false;
        if (intent.getBooleanExtra(EXTRA_REFRESH, false)) return false;
        if (intent.getData() != null) return false;
        String action = intent.getAction();
        return !Intent.ACTION_SEND.equals(action)
                && !Intent.ACTION_SENDTO.equals(action)
                && !Intent.ACTION_SEARCH.equals(action);
    }

    /** 字号档位变化触发的重建：先声明「这是内部重建」，避免重建被当成用户打开软件而自动续播。 */
    @Override
    protected void onBeforeRecreate() {
        markInternalRecreate();
    }

    /**
     * 图片尺寸变化<b>不</b>重建本页：首页自己响应 {@code RefreshEvent.SIZE}
     * （{@code getVideo()} + {@code getHistory(true)}，推荐区与「最近观看」行都会重建 presenter），
     * 内容与尺寸都已刷新；再走一次 recreate 只会白丢焦点与滚动位置 —— 对老人来说
     * 「焦点无缘无故跑掉」比任何东西都难受。字号档位变化仍走重建（倍率作用于全局 dp/sp，
     * 事件刷新覆盖不到标题栏等静态区）。
     */
    @Override
    protected boolean autoRecreateOnSizeChange() {
        return false;
    }

    private void loadLive(String url) {
        if (isFinishing() || isDestroyed()) return;
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                LiveActivity.start(getActivity());
            }
        });
    }

    /**
     * 把初始焦点交给内容区。
     *
     * <p><b>⚠ 2026-10-16 修「点重新加载后视角往上跳」的漏网路径</b>。本方法被
     * {@link #showContent()} 调用。原先是无条件 {@code mBinding.recycler.requestFocus()} ——
     * 焦点被甩到 recycler 的**第一个可聚焦子视图**（顶部功能区行）并 {@code scrollToPosition(0)}。
     * 而重试成功的时序必经本方法 ⇒ 前一步的落焦全被抵消。</p>
     *
     * <p><b>⚠ 本方法的守卫只在「空态卡重试」之外生效</b>：那条路径如今
     * <b>刻意不移动焦点</b>（空态卡已无可点入口、不需要也没有「原地保卡」机制），所以走到这里时
     * 焦点仍在 recycler 内 ⇒ 下面第一个守卫直接 return ⇒ 既不落焦也不滚动 ⇒ <b>不跳顶</b>。
     * 本方法只负责「首次从启动画面进入内容态」这一次初始落焦。</p>
     *
     * <p>⚠ 原先这里的 {@code if (mBinding.title.hasFocus()) return;} 是<b>恒假死判据</b>：
     * {@code App.post(() -> mBinding.title.setFocusable(true), 500)} 延迟 500ms 才让 title 可聚焦，
     * 而本方法是同步调用 ⇒ 此刻 title 必然还没焦点 ⇒ 该行从未生效。已删除。</p>
     *
     * <p>⚠ 2026-10-17：连那行「延迟可聚焦」也一并移除了 —— 让标题可聚焦只会把老人的焦点
     * 引到右上角一个「闪烁、无描边、操作隐蔽」的地方（详见方法内的说明）。</p>
     */
    private void setFocus() {
        mBinding.title.setSelected(true);
        // ⛔⛔ 2026-10-17 移除「让标题可聚焦」：原先这里 `App.post(() -> mBinding.title.setFocusable(true), 500)`
        // 会在 500ms 后把标题（CustomTitleView，XML 里本是 focusable="false"）改成可聚焦，于是：
        //   ① 老人在内容区按「上」时焦点会跑到右上角标题上，而标题的焦点态是**闪烁动画**（不是
        //      全站统一的 6dp 描边）⇒ 看着像「焦点消失了」，必须再按「下」才回得来（迷路）；
        //   ② 标题上那两个隐藏操作（「上」键刷新数据源、OK 键打开数据源弹窗）老人根本不会用。
        // ⇒ 现在标题**永不可聚焦**，方向键只在 功能区 ⇄ 分类 chips ⇄ 内容区 之间移动，不会跑丢。
        // ✅ 标题与头像现为**纯装饰**：不注册点击（见 initEvent）、不可聚焦、无任何动作。
        // 换源入口全站唯一 = 「设置 → 长辈设置 → 一键锁定」（ElderLockActivity）。
        // ⚠ `setSelected(true)` 必须保留：它是 TextUtils.TruncateAt.MARQUEE 跑马灯的开关，与焦点无关
        // （长站点名仍能完整滚出来给老人看，这是展示行为，不是交互）。
        // ⛔ 焦点已经落在 recycler 内 ⇒ 原样不动。这是「不跳顶」的关键守卫。
        View focused = getCurrentFocus();
        if (focused != null && isDescendantOf(focused, mBinding.recycler)) return;
        if (focusChipRow()) return;
        mBinding.recycler.requestFocus();
        // ⚠ 2026-10-22 修「冷启动后焦点在功能区、但视角看不到最上方标题区域」：
        // requestFocus() 只保证「焦点项可见」，**不保证可视区从第 0 行开始**；
        // 内容行又是异步插入的（onContentResult → addAll），插入后重新布局还会再把标题挤出去。
        // 所以这里校正**两次**：落焦后立即一次 + 内容插入完成后再一次。
        // ⛔ 原实现里那次 post 会先用 getCurrentFocus() 判「焦点是否已在 recycler 内」再决定滚不滚 ——
        //    requestFocus 之后焦点到达子项的时机不固定，经常判不到 ⇒ 什么都不做 ⇒ 冷启动仍然看不到标题。
        //    现在改为不看焦点视图，改为看「选中位置」：只要焦点还停在功能区（index 0/1，含 -1 未定），
        //    就把列表回顶；老人一旦往下移动（index >= 2），两次校正都自动失效，不会把他拽回顶部。
        // ⛔ 只在「焦点原本不在 recycler 内」这条**首次落焦**路径执行：上面两个守卫已把
        //    「重试 / 网络恢复刷新 / 空态」等路径挡在外面，所以不会重新引入「点重试后视角跳顶」。
        mBinding.recycler.post(this::scrollHomeToTop);
        mBinding.recycler.postDelayed(this::scrollHomeToTop, HOME_TOP_DELAY);
    }

    /**
     * 冷启动落焦后把首页列表回顶，确保第 0 行（标题区）可见。
     *
     * <p>只有「焦点仍停在功能区及之上」时才动作（{@link #HOME_TOP_DELAY} ms 后也可能被调用一次）：
     * 老人若已经按「下」移动到分类/内容区，这个校正必须失效，否则会把视角拽回顶部。</p>
     */
    private void scrollHomeToTop() {
        if (isFinishing() || isDestroyed() || mBinding == null) return;
        if (mBinding.recycler.getSelectedPosition() > 1) return;
        mBinding.recycler.scrollToPosition(0);
    }

    private void getVideo() {
        mResult = Result.empty();
        // 推荐内容直接加载：焦点随后落到「推荐」chip 时不再重复请求（去重键与 loadChip 同一套）
        App.removeCallbacks(mChipRunnable);
        mShownChip = getString(R.string.home_recommend);
        // ⚠ 2026-10-16 审计修复（P2）：配置事件链（ConfigEvent.VOD → RefreshEvent.home）可能在
        // 用户正浏览分类 B 时到来（网络恢复重载/投屏换源），内容被重置回推荐，但 chip 高亮
        // 却留在 B（buildChips 的 contains 判定）—— 高亮与内容脱节，老人会以为「选着 B 却
        // 放着别的东西」。高亮必须跟着内容走。
        selectChip(mShownChip);
        mResponseToken = ++mContentToken;   // 与 loadChip 同一套代际：避免旧分类响应插进推荐首屏
        // 网络恢复后自动刷新时，焦点可能正停在上一次的空态卡上；这张卡马上要被移除，
        // 先把焦点交回内容区，避免「遥控器突然失灵」。
        restoreFocusAfterEmpty();
        // 内容区即将被整块删除：若焦点正停在某个封面行上，先记下来 —— 内容回来后那个视图已经不存在，
        // 不补落点的话遥控器会「突然失灵」（得按方向键才有反应）。这里记的是「焦点在不在内容区」。
        boolean focusInContent = isFocusInRecycler();
        removeContent();
        mAdapter.add("progress");
        mScroller.reset(); // 推荐（homeContent）无分页接口，一次性全量；重置翻页状态供下次分类使用
        if (focusInContent) mBinding.recycler.postDelayed(this::refocusChipRowIfLost, HOME_FOCUS_RESTORE_DELAY);
        mViewModel.homeContent();
    }

    /** 焦点当前是否落在首页列表（recycler）内。 */
    private boolean isFocusInRecycler() {
        View focused = getCurrentFocus();
        return focused != null && isDescendantOf(focused, mBinding.recycler);
    }

    /**
     * 内容区重建后补落焦：只有「焦点彻底丢了」才把焦点交给分类行。
     *
     * <p>判据用 {@code getCurrentFocus() == null} —— 焦点还在（已经落到 chip 或新的封面行上）就一点都不动，
     * 避免把老人已经移动过的焦点拽走。</p>
     */
    private void refocusChipRowIfLost() {
        if (isFinishing() || isDestroyed() || mBinding == null) return;
        if (getCurrentFocus() != null) return;
        focusChipRow();
    }

    /** 切换 chips 选中高亮（同步刷新 chip 行的选中态）。 */
    private void selectChip(Object category) {
        mChipSelected = category;
        mChipPresenter.setSelected(category);
        if (mChipAdapter.size() > 0) mChipAdapter.notifyArrayItemRangeChanged(0, mChipAdapter.size());
    }

    private void addVideo(Result result) {
        // 封面统一 3:4 竖版；「封面大小」设置（列数）仍由 Product.getColumn 驱动，不失效
        addGrid(result.getList(), Style.rect());
        checkMore();
    }

    /**
     * 跨页接续：把上一页最后一行未满的列位补齐，避免每页各自分行在滚动中出现成组空列位。
     *
     * <p>首页分页是后加的，当时漏抄了 {@code TypeFragment} / {@code CollectFragment}（搜索结果页）
     * 基线里就有的这段逻辑，所以每页 20 条会渲染成 6/6/6/2 再 6/6/6/2，滚动时一组组空列。</p>
     */
    private boolean checkLastSize(List<Vod> items, Style style) {
        if (mLast == null || items.isEmpty()) return false;
        // size <= 0 一律早退：0 = 上一页刚好排满，没有空列位要接续；
        // 负值 = mLast 是按「更大的列数」排的（用户改了「图片尺寸」档位使列数变小），
        // 此时继续走 subList(0, 负值) 会抛 IndexOutOfBoundsException。
        int size = Product.getColumn(style) - mLast.size();
        if (size <= 0) return false;
        size = Math.min(size, items.size());
        mLast.addAll(mLast.size(), items.subList(0, size));
        addGrid(items.subList(size, items.size()), style);
        return true;
    }

    private void addGrid(List<Vod> items, Style style) {
        if (checkLastSize(items, style)) return;
        List<ListRow> rows = new ArrayList<>();
        // ⚠ presenter 页面级复用（2026-10-27）：原来每翻一页就 new 一个 VodPresenter，
        // 页数越多堆积越多（presenter 无状态，只负责 create/bind/unbind holder，复用安全）。
        VodPresenter presenter = vodPresenter(style);
        for (List<Vod> part : Lists.partition(items, Product.getColumn(style))) {
            mLast = new ArrayObjectAdapter(presenter);
            mLast.addAll(0, part);
            rows.add(new ListRow(mLast));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    /** 内容行 Presenter（懒建一次）。列表档位变化会重建 Activity，所以不必在这里处理失效。 */
    private VodPresenter vodPresenter(Style style) {
        if (mVodPresenter == null) mVodPresenter = new VodPresenter(this, this, style);
        return mVodPresenter;
    }

    private void setFunc() {
        // 「看电视」入口已移除：站源分类下沉到首页「最近观看」下方的分类 chips 行。
        //
        // 「直播」与「推送」互补占位，功能行恒为 5 个按钮（用户 2026-10-12 定案）：
        //   有直播数据（LiveConfig.hasData()，读本地缓存、同步返回、不依赖网络）
        //       → 直播 · 搜索 · 收藏 · 历史 · 设置
        //   无直播数据
        //       → 搜索 · 收藏 · 历史 · 推送 · 设置
        // 直播恒排最前、推送恒排「设置」之前；两者互斥使项数恒定，所以 FuncPresenter 的
        // 5 等分宽度公式无需随源变化调整（一旦项数不再恒为 5，那里必须同步改）。
        // 无直播数据时老人的内容入口靠「推送」从手机投递，故保留它；有直播数据时首页不再显示
        // 推送，但搜索页键盘上的「遥控器」图标仍是推送入口，功能不会丢失。
        //
        // ⚠ 2026-10-16：判据从 hasUrl() 换成 hasData()。主配置 JSON 里带 lives 字段时，
        // VodConfig.initLive() 会拿主数据源 url 建一条 type=1 的 Config 行 —— hasUrl() 因此为 true，
        // 于是功能行冒出「直播」入口，点了却一条频道都没有（sync=true 又让直播配置永不加载）。
        // 「有没有直播可用」必须看数据本身，不能看配置 url 非空。
        List<Func> items = new ArrayList<>();
        boolean hasLive = LiveConfig.hasData();
        if (hasLive) items.add(Func.create(R.string.home_live));
        items.add(Func.create(R.string.home_search));
        items.add(Func.create(R.string.home_keep));
        items.add(Func.create(R.string.home_history_entry));
        if (!hasLive) items.add(Func.create(R.string.home_push));
        items.add(Func.create(R.string.home_setting));
        mFuncAdapter.setItems(items, new BaseDiffCallback<Func>());
        buildChips();
    }

    private void getHistory() {
        getHistory(false);
    }

    /**
     * 刷新首页「最近观看」区。
     *
     * <p><b>只在结构真的发生变化时才增删行</b>（上游基线就是这个判据）。老人版一度改成「每次
     * 先无条件 remove 再 add」，后果是：播放页返回、删除一条历史等都会广播 {@code RefreshEvent.HISTORY}
     * → 即使内容完全没变，历史标题行与历史行也被移除重建 → 焦点若正停在历史行上会丢失、
     * 内容区滚动位置跳动。对老人来说「焦点无缘无故跑掉」比内容晚一拍更新更难受，所以恢复条件重建。</p>
     *
     * <p><b>取数走后台线程（2026-10-28 遥控器假死排查）</b>：{@link History#get()} 是 60 天全表查询
     * + Gson 反序列化 + 站点禁用快照，历史条数多时要几百毫秒。它原先在 {@code RefreshEvent.HISTORY}
     * 触发时于<b>主线程同步</b>执行 —— 而「退出播放页 / 删记录 / 换源」都会广播该事件，
     * 期间所有遥控器按键都排在输入队列里，是列表页瞬时「假死」的成因之一。
     * 现在改为后台取数、回主线程再动 UI；数据本身不变，只是不再占着主线程。</p>
     */
    private void getHistory(boolean renew) {
        Task.execute(() -> {
            List<History> items = History.get();
            App.post(() -> applyHistory(items, renew));
        });
    }

    /** 「最近观看」区的真正刷新（主线程）：items 已由 {@link #getHistory(boolean)} 在后台取好。 */
    private void applyHistory(List<History> items, boolean renew) {
        // 取数期间页面可能已销毁或正在结束：此时再动 adapter 会碰到已解绑的 RecyclerView
        if (isDestroyed() || isFinishing()) return;
        // 布局中增删行会抛 IllegalStateException（本项目 setHistoryDelete 已有同类教训）。
        // 异步取数让这个时机更不可控，故显式推迟到本次布局结束；items 是快照，推迟不丢数据。
        if (mBinding.recycler.isComputingLayout()) {
            mBinding.recycler.post(() -> applyHistory(items, renew));
            return;
        }
        boolean exist = mAdapter.indexOf(mHistoryRow) != -1;
        boolean headerExist = mAdapter.indexOf(R.string.home_history) != -1;
        // 数据源不可用（无网 / 站点列表为空）时「最近观看」整行隐藏：这些记录点开也只会撞进一个
        // 取不到站点的空白详情页，摆在那儿等于给老人挖坑。数据源一恢复就自动重新出现
        // （配置事件会带来 RefreshEvent.HISTORY）。
        boolean dataReady = NetAwait.isOnline() && !VodConfig.get().getSites().isEmpty();
        // 期望状态：有历史数据、且数据源可用，就应该有「标题行 + 列表行」这一对；否则都不该存在。
        boolean need = !items.isEmpty() && dataReady;
        // 封面大小变更（renew）必须换掉 presenter 让字号/列数重新生效，此时也走重建分支。
        if (renew) mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        // 结构一致且非强制重建：原地更新条目即可，不动布局、不丢焦点、不跳滚动位置。
        if (!renew && exist == need && headerExist == need) {
            mHistoryAdapter.setItems(items, new BaseDiffCallback<History>());
            return;
        }
        if (exist) mAdapter.removeItems(mAdapter.indexOf(mHistoryRow), 1);
        if (headerExist) mAdapter.removeItems(mAdapter.indexOf(R.string.home_history), 1);
        if (need) {
            int chipIndex = mAdapter.indexOf(mChipRow);
            mAdapter.add(chipIndex, R.string.home_history);
            mAdapter.add(chipIndex + 1, mHistoryRow = new ListRow(mHistoryAdapter));
        }
        mHistoryAdapter.setItems(items, new BaseDiffCallback<History>());
    }

    private void setHistoryDelete(boolean delete) {
        mPresenter.setDelete(delete);
        // ⚠ 2026-10-16 加固：本方法会被 onChildViewHolderSelected（布局/滚动过程中同步触发）
        // 调到，此时对子 RecyclerView 的 adapter 发通知会抛
        // IllegalStateException: Cannot call this method while RecyclerView is computing a layout or scrolling
        // —— 该异常在 bind 路径（非 EventBus）⇒ 会真闪退。布局中则跳过（下次绑定时状态自然生效）。
        if (mBinding.recycler.isComputingLayout()) return;
        mHistoryAdapter.notifyArrayItemRangeChanged(0, mHistoryAdapter.size());
    }

    private void clearHistory() {
        removeHistorySection();
        History.clear(VodConfig.getCid());
        mPresenter.setDelete(false);
        mHistoryAdapter.clear();
    }

    /** 移除「最近观看」标题行与其下的历史行（均位于分类 chips 行之前）。 */
    private void removeHistorySection() {
        if (mAdapter.indexOf(mHistoryRow) != -1) mAdapter.removeItems(mAdapter.indexOf(mHistoryRow), 1);
        if (mAdapter.indexOf(R.string.home_history) != -1) mAdapter.removeItems(mAdapter.indexOf(R.string.home_history), 1);
    }

    /** 内容区起点：分类 chips 行之后（推荐 / 各分类切换都重建这一区域）。 */
    private int getContentIndex() {
        return mAdapter.indexOf(mChipRow) + 1;
    }

    /** 清空分类 chips 行之后的内容区。 */
    private void removeContent() {
        // 内容区重建 → 行末接续状态归零。放在这里是因为切分类 / 刷新 / 空态三条路径都必经此方法，
        // 一次覆盖最全；否则会把上一个分类的余数补进新分类的首行。
        mLast = null;
        int index = getContentIndex();
        if (index >= 1 && index < mAdapter.size()) mAdapter.removeItems(index, mAdapter.size() - index);
        mEmptyItem = null;
    }

    /** 内容区空态：在分类 chips 行之后插入一张「空态占位卡」行（宽撑满、高固定、内容卡内居中），
     *  随内容流自然滚动（占位效果，不漂移）。不隐藏功能区/分类chips/最近观看等组件。
     *
     *  <p><b>2026-10-17：卡片只显示文案，不再有任何按钮</b>（原「重新加载」按钮已移除 ——
     *  它按了必然无效：没有数据源时重试还是没源，源挂/断网时会自动重载）。文案三种：</p>
     *  <ul>
     *    <li><b>从未导入数据源</b>（{@code configFailed && sites.isEmpty()}）→
     *        「还没有导入数据源，请到 设置 → 数据源 导入」：直接指路，这是唯一能让页面有内容的动作。</li>
     *    <li><b>有数据源但这次没连上</b> → 「暂时连接不上，网络恢复后会自动重试」：
     *        交付给自动恢复（{@link #getCallback()} 的 error 分支挂 {@code awaitConfigNetwork}）。</li>
     *    <li><b>配置正常、只是这个分类确实没内容</b> → 「暂无推荐内容 / 该分类暂无数据」。</li>
     *  </ul>
     *
     *  <p><b>刻意不按 {@code NetAwait.isOnline()} 细分</b>：无网时根本进不到首页（会停在启动画面
     *  显示「正在连接网络…」，见 {@link #evaluateBoot()}），那句「网络未连接」属于重复提示；
     *  且 {@code isOnline()} 要等系统 VALIDATED（异步），有网的头一两秒会误报无网，文案会跳变。
     *  这类空态绝不能显示成「暂无推荐内容」—— 那等于把故障说成「本来就没内容」。</p>
     */
    /**
     * 「暂时没拿到」不等于「没有」—— 这种时候保持加载动画，不显示空态文案。
     *
     * <p>⛔ 2026-10-24 修：冷启动时网络还没准备好（常见于电视刚上电、Wi-Fi 还在连），
     * 配置请求失败 ⇒ {@code configFailed=true} 且 {@code sites} 为空，于是首页会显示
     * 「还没有设置数据源，请按上面的设置按钮添加」—— 但老人**明明导入过数据源**，只是这次还没拿到。
     * 他会真的按这句话跑去设置里翻半天，而实际上只需要等。表现为「先弹一段错误文案、网络好了又消失」。</p>
     *
     * <p>判据三条同时成立才算「没拿到」：这次配置加载失败 + 当前站点为空 + <b>网络尚未就绪</b>。
     * 只要网络已就绪（确实有网、也确实没导入过源），仍然正常显示「去设置里添加」的指路文案。</p>
     *
     * <p>保持 progress 后不会「一直转」：网络恢复时 {@code awaitConfigNetwork()} 会重载配置，
     * 成功回调里的 {@code getVideo()} 会重新请求内容并替换掉进度行。</p>
     */
    private boolean keepLoadingInsteadOfEmpty() {
        return configFailed && VodConfig.get().getSites().isEmpty() && !NetAwait.isOnline();
    }

    private void showContentEmpty() {
        if (mAdapter.indexOf(mChipRow) == -1) return;
        removeContent();
        // 用户把所有站源都关掉了：配置本身是好的，只是没有「能看的站」——
        // 不能说成「还没有设置数据源」（那是没导入过），要指路到站源选择。
        if (VodConfig.get().getSites().isEmpty()) {
            mEmptyItem = EmptyCardPresenter.Item.plain(VodConfig.get().getRawSites().isEmpty() ? R.string.home_empty_no_source : R.string.home_empty_no_site);
            mAdapter.add(mEmptyItem);
            return;
        }
        if (configFailed) {
            // 数据源拿不到。2026-10-17 起文案分两种、且不再显示「重新加载」按钮（按钮必然无效）：
            //   · 从来没导入过数据源 → 直接指路去设置里导入（这是唯一能让页面有内容的动作）；
            //   · 有数据源但这次没连上 → 说明会自动重试，老人不用做任何事。
            boolean noSource = VodConfig.get().getSites().isEmpty();
            mEmptyItem = EmptyCardPresenter.Item.plain(noSource ? R.string.home_empty_no_source : R.string.home_empty_source);
        } else {
            // 推荐为空 → 「暂无推荐内容」；分类为空 → 「该分类暂无数据」（三语言）
            int textRes = (mShownChip instanceof Class) ? R.string.home_empty_category : R.string.home_empty_recommend;
            mEmptyItem = EmptyCardPresenter.Item.plain(textRes);
        }
        mAdapter.add(mEmptyItem);
        // 2026-10-15：按用户要求取消「空态卡自动抢焦点」（原 focusEmptyRetry / focusRetryInEmptyRow 已删）。
        // 空态卡是内容流的最后一行，焦点留在上方功能区时一路按「下」即可到达，
        // 卡片本身不可聚焦、不可点（纯展示行，见 EmptyCardPresenter）。
    }

    /**
     * 在当前已绑定的子视图里找到空态卡那一行的根视图（找不到返回 null）。
     *
     * <p>不用 {@code findViewHolderForAdapterPosition(mAdapter.indexOf(item))}：列表适配器外面还包了
     * 一层 {@code ItemBridgeAdapter}（见 {@link #setRecyclerView()}），两者的 position 未必一一对应。
     * 遍历 recycler 的直接子视图、用 {@code getChildViewHolder(child).getItem()} 比对是不是当前这张
     * 空态卡，与适配器层数无关，最稳。{@code ItemBridgeAdapter} 未设 {@code Wrapper}，
     * 所以 {@code getItem()} 返回的就是 {@code ArrayObjectAdapter} 里的原对象，可直接引用比较。</p>
     */
    private View currentEmptyRowView() {
        if (mEmptyItem == null) return null;
        for (int i = 0; i < mBinding.recycler.getChildCount(); i++) {
            View child = mBinding.recycler.getChildAt(i);
            if (child == null) continue;
            RecyclerView.ViewHolder holder = mBinding.recycler.getChildViewHolder(child);
            if (!(holder instanceof ItemBridgeAdapter.ViewHolder bridge)) continue;
            if (bridge.getItem() != mEmptyItem) continue;
            return child;
        }
        return null;
    }

    /**
     * 空态卡即将消失时把焦点交还内容区 —— <b>只在「焦点确实还停在这张卡里面」时动作</b>。
     *
     * <p>空态卡是可聚焦的（弱网 / 无网 / 数据源异常时），点按重试或数据到达后这张卡会被
     * {@code removeContent()} 移除。视图被移除时 Android 会让焦点回落到根布局，
     * 表现为「遥控器突然失灵」（要按好几次方向键才重新落到某处）。
     * 这里在移除前主动把焦点交回 recycler，避免老人遇到这种「卡住」。</p>
     *
     * <p><b>判据必须是「焦点在空态卡子树内」而不是「焦点不在 recycler 上」</b>：
     * 后者的范围太宽 —— 用户可能正把焦点停在顶部标题等别处，那种情况不该被本方法拽走。
     * 用 {@code isDescendantOf(currentEmptyView)} 精确判定，只处理真正会丢焦点的那一种情形。</p>
     *
     * <p><b>⚠ 唯一调用方是 {@link #getVideo()}（网络恢复后自动刷新 / 数据源事件触发的刷新）。</b>
     * 空态卡已于 2026-10-17 去掉「重新加载」按钮、改为纯文案，所以不再存在「点按钮重试」这条
     * 需要保住卡片与焦点的路径；而自动刷新一定会 {@code removeContent()} 删掉那张卡，
     * 所以仍需在此把焦点交回内容区，避免遥控器「失灵」（要按好几次方向键才重新落到某处）。</p>
     */
    private void restoreFocusAfterEmpty() {
        View emptyRow = currentEmptyRowView();
        if (emptyRow == null) return;
        View focused = getCurrentFocus();
        if (focused == null) return;
        if (!isDescendantOf(focused, emptyRow)) return;
        // ⚠ 2026-10-15/16：原先是 mBinding.recycler.requestFocus()，那会把焦点交给 recycler 的
        // **第一个可聚焦子视图**（顶部的功能区行），RecyclerView 同时 scrollToPosition(0) ——
        // 于是「点重新加载」后整页唰地跳回顶部，视觉上就是「视角往上切换」。
        //
        // 2026-10-16 二次定位（用户实测仍复现，且「点空白区域不跳」正好反证本判据链是对的）：
        // 上一版的 fallback 是「落到 chips 行」，但 chips 行此刻**根本不在屏上** ——
        // 空态卡高 CARD_HEIGHT_RATIO = 0.55 × 屏高，把 chips 行整个挤出了可视区，
        // 而 RecyclerView.getChildAt() 只遍历**已 attach 的可见子视图**，遍历不到就返回 false，
        // 于是又退回 requestFocus() → 依旧跳顶。
        //
        // 现在的口径：**优先落「空态卡下方/紧邻它的已可见内容行」**（自底向上找，视觉上原地不动），
        // 屏上确实一行都没有时才把 chips 行滚进来落焦，两者都失败才退回 recycler。
        if (!focusNearEmpty(emptyRow)) mBinding.recycler.requestFocus();
    }

    /**
     * 把焦点交还给「紧邻空态卡、且此刻已在屏上」的内容行；屏上无内容行时改为把 chips 行滚进来落焦。
     *
     * @param emptyRow 即将被移除的空态卡行视图
     * @return 是否已安排好落焦（{@code false} 表示调用方需要退回 {@code recycler.requestFocus()}）
     */
    private boolean focusNearEmpty(View emptyRow) {
        // ① 自底向上扫已 attach 的子视图，跳过空态卡本身，取第一个可聚焦的后代。
        //    「自底向上」是为了取到离空态卡最近的那一行 ⇒ scroll 距离≈0，视觉上完全不跳。
        for (int i = mBinding.recycler.getChildCount() - 1; i >= 0; i--) {
            View child = mBinding.recycler.getChildAt(i);
            if (child == null || child == emptyRow) continue;
            View target = firstFocusableIn(child);
            if (target != null) {
                target.requestFocus();
                return true;
            }
        }
        // ② 屏上只剩空态卡（该分类真的没有任何内容行）⇒ 把 chips 行滚进可视区再落焦。
        return focusChipRow();
    }

    /** 取行视图里第一个可聚焦的后代；行根本身可聚焦时返回自身。 */
    private View firstFocusableIn(View row) {
        if (row == null) return null;
        if (row.isFocusable()) return row;
        if (!(row instanceof ViewGroup group)) return null;
        for (int c = 0; c < group.getChildCount(); c++) {
            View v = group.getChildAt(c);
            if (v != null && v.isFocusable()) return v;
        }
        return null;
    }

    /**
     * 把焦点送到分类 chips 行里「当前选中」的那颗 chip（找不到任一 chip 则返回 false）。
     *
     * <p>chips 行不在屏上时（空态卡 0.55 屏高把它挤出去了）用 {@code scrollToPosition} 把它滚回来，
     * 再在 <b>下一次布局完成后</b>落焦 —— 不能要求它此刻已在屏上，见 {@link #focusNearEmpty} 的注释。</p>
     */
    private boolean focusChipRow() {
        int chipIndex = mAdapter.indexOf(mChipRow);
        if (chipIndex < 0) return false;
        View row = findAttachedRow(mChipRow);
        if (row == null) {
            // 滚进可视区；post 里再落焦（scrollToPosition 触发的布局尚未完成，此刻取不到视图）。
            // 返回 true：落焦已被安排，不能让调用方退回 requestFocus() —— 那才是跳顶的元凶。
            mBinding.recycler.scrollToPosition(chipIndex);
            mBinding.recycler.post(() -> {
                View target = firstFocusableIn(findAttachedRow(mChipRow));
                if (target != null) target.requestFocus();
            });
            return true;
        }
        View target = firstFocusableIn(row);
        if (target == null) return false;
        // 优先落在「当前选中」的 chip 上（就是老人刚才点的那个），次选任一可见 chip。
        if (!(row instanceof ViewGroup group)) return false;
        for (int c = 0; c < group.getChildCount(); c++) {
            View chip = group.getChildAt(c);
            if (chip != null && chip.isFocusable() && chip.isSelected()) {
                chip.requestFocus();
                return true;
            }
        }
        target.requestFocus();
        return true;
    }

    /** 按行对象找已 attach 的行视图；未在屏上返回 null。 */
    private View findAttachedRow(Object item) {
        for (int i = 0; i < mBinding.recycler.getChildCount(); i++) {
            View child = mBinding.recycler.getChildAt(i);
            if (child == null) continue;
            RecyclerView.ViewHolder holder = mBinding.recycler.getChildViewHolder(child);
            if (!(holder instanceof ItemBridgeAdapter.ViewHolder bridge)) continue;
            if (bridge.getItem() != item) continue;
            return child;
        }
        return null;
    }

    /** 焦点视图是否位于给定祖先视图的子树内（含自身）。 */
    private boolean isDescendantOf(View view, View ancestor) {
        for (View v = view; v != null; v = (v.getParent() instanceof View parent) ? parent : null) {
            if (v == ancestor) return true;
        }
        return false;
    }

    /** 移除内容区空态占位卡（当该分类加载到数据时调用）。 */
    private void removeContentEmpty() {
        if (mEmptyItem == null) return;
        if (mAdapter.indexOf(mEmptyItem) != -1) mAdapter.remove(mEmptyItem);
        mEmptyItem = null;
    }

    /** 点击分类 chip：取消待发的焦点防抖后立即加载，强制路径 —— 即使点的正是当前这一项也重载
     *  （空态 / 加载失败后重试靠这个显式动作，不再靠解锁去重键）。 */
    @Override
    public void onItemClick(Object item) {
        bootInteracted = true;   // 老人已经在操作了 → 放弃本次自动抢屏跳转
        App.removeCallbacks(mChipRunnable);
        loadChip(item, true);
    }

    /** chip 得到焦点（遥控器「移到即加载」，不需要点按）：防抖后加载，快速划过只会发最后一次。 */
    public void onItemFocus(Object item) {
        mChipPending = item;
        App.post(mChipRunnable, CHIP_FOCUS_DELAY);
    }

    /**
     * 加载某个 chip 的内容（「推荐」走 homeContent，分类走 categoryContent，支持滚动分页）。
     *
     * <p>点按与焦点防抖共用此入口，用 {@link #mShownChip} 去重：切到正在展示的那一项不发请求，
     * 避免误触 / 焦点回位造成重复加载与闪烁。</p>
     *
     * @param force true = 忽略去重强制重载（用户主动点按 chip / 筛选条件变化靠这个）
     */
    private void loadChip(Object item, boolean force) {
        if (item == null || isFinishing() || isDestroyed()) return;
        if (!force && item.equals(mShownChip)) return;
        // ⚠ 2026-10-17：原先这里有一段 keepEmpty 分支，专为「空态卡上的重试」保住卡片、
        // 让焦点不离开按钮。空态卡已改为纯文案、不再有可点的重试入口（见 EmptyCardPresenter），
        // 这段分支连同 retryPending 防抖一起删除 —— 现在所有入口（点 chip、切分类、筛选变化、
        // 网络恢复后刷新）都走同一条「removeContent + progress」路径，行为单一、无特例。
        // ⚠ 换到「另一个分类」时必须清掉上一分类的筛选参数与选中态：
        // 否则老人从「电影（2024 年）」切到「电视剧」，会带着 2024 去请求 ⇒ 列表莫名其妙变空。
        // 注意筛选条件变化走的是 loadChip(同一 item, force=true)，item 与 mShownChip 相等 ⇒ 这里不会误清。
        boolean chipChanged = !item.equals(mShownChip);
        if (chipChanged && item instanceof Class) {
            mFilterExtend.clear();
            resetFilterSelection();
        }
        mShownChip = item;
        mResponseToken = ++mContentToken;   // 新请求开始：此后只有本代响应会被采纳
        selectChip(item);
        removeContent();
        mAdapter.add("progress");
        mScroller.reset();
        if (item instanceof String) {
            mViewModel.homeContent();
        } else if (item instanceof Class type) {
            // filter 与 extend 传参对齐手机版 TypeFragment（true + 筛选条件）：
            // extend 传 null 会违反 SiteApi 的 @NonNull 契约，type=1 采集源在 !extend.isEmpty() 处直接 NPE
            // 导致分类永远无数据。mFilterExtend 为空表时行为与改造前完全一致（不筛选）。
            mViewModel.categoryContent(getHome().getKey(), type.getTypeId(), "1", true, mFilterExtend);
        }
    }

    /** CustomScroller 翻页回调：仅分类（categoryContent）支持分页；推荐（homeContent）无分页接口直接拒绝。
     *  用 mShownChip 而非 mChipSelected：后者只负责高亮，buildChips() 在分类缺失时会把它清成空串。 */
    private boolean onLoadMore(String page) {
        if (!(mShownChip instanceof Class type)) return false;
        if (mAdapter.indexOf("progress") != -1) return false;
        mAdapter.add("progress");
        // 翻页沿用当前代际快照：切分类会 ++mContentToken 使本代失效，迟到的分页响应将被整条丢弃，
        // 既不会把上一分类的第 N 页追加进新分类，也不会用旧 pageCount 覆盖新分类的翻页状态机。
        mResponseToken = mContentToken;
        // 翻页必须带同一份筛选条件：原来传空表 ⇒ 第 2 页起混进不符合筛选的条目
        // （首屏传的是 mFilterExtend，手机端 TypeFragment 也一直带）。传拷贝避免请求参数被后续修改。
        mViewModel.categoryContent(getHome().getKey(), type.getTypeId(), page, true, new HashMap<>(mFilterExtend));
        return true;
    }

    /** 数据回来后等布局完成再检查：内容不满一屏（最后一行仍可见）则继续补下一页，直到满屏或无更多数据。
     *  必须等布局完成——adapter 刚更新时新行尚未布局，isBottom 判定会失真。 */
    private void checkMore() {
        mBinding.recycler.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                mBinding.recycler.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                mScroller.checkMore(mBinding.recycler);
            }
        });
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                // 换源、网络恢复后的自动重载、以及首次加载，都可能在还停在启动画面时发生。
                // ConfigEvent.vod() 在 BaseConfig#loadConfig 的 finally 里派发（成功失败都会发），
                // 是「数据源状态可能变了」的唯一可靠信号 —— 所以门控必须在这里也收敛一次，
                // 否则换源后首页会一直停在旧状态。（已进首页时本方法首行即返回，零副作用。）
                evaluateBoot();
                RefreshEvent.history();
                RefreshEvent.home();
                setLogo();
                break;
            case COMMON:
                setFunc();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                getVideo();
                setTitle();
                break;
            case HISTORY:
                getHistory();
                break;
            case SIZE:
                getVideo();
                getHistory(true);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.type()) {
            case SEARCH:
                SearchActivity.start(this, event.text());
                break;
            case PUSH:
                VideoActivity.push(this, event.text());
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (VodConfig.get().getConfig().equals(event.config())) {
            VideoActivity.cast(this, event.history());
        } else {
            VodConfig.load(event.config(), getCallback(event));
        }
    }

    private Callback getCallback(CastEvent event) {
        return new Callback() {
            @Override
            public void success() {
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    @Override
    public void onItemClick(Func item) {
        bootInteracted = true;   // 老人已经在操作了 → 放弃本次自动抢屏跳转
        if (item.getResId() == R.string.home_live) LiveActivity.start(this);
        else if (item.getResId() == R.string.home_push) PushActivity.start(this);
        else if (item.getResId() == R.string.home_keep) KeepActivity.start(this);
        else if (item.getResId() == R.string.home_history_entry) HistoryActivity.start(this);
        else if (item.getResId() == R.string.home_search) SearchActivity.start(this);
        else if (item.getResId() == R.string.home_setting) SettingActivity.start(this);
    }

    @Override
    public void onItemClick(Vod item) {
        bootInteracted = true;   // 老人已经在操作了 → 放弃本次自动抢屏跳转
        if (item.isAction()) mViewModel.action(getHome().getKey(), item.getAction());
        // index 站源（豆瓣类）只有推荐/分类、没有播放线路：开启「首页快速搜索」时按原生跳搜索页跨源搜同名；
        // 关闭时直接进详情页，交给自动择源择线把可播放线路找出来。非 index 源不受开关影响，始终进详情页。
        else if (getHome().isIndex() && Setting.isIndexQuickSearch()) CollectActivity.start(this, item.getName());
        else VideoActivity.start(this, getHome().getKey(), item.getId(), item.getName(), item.getPic());
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction()) return false;
        // 长按与单击保持一致：index 源关闭「首页快速搜索」后，长按也直接进详情页自动择源，
        // 不再跳搜索页（否则老人长按会突然换到搜索页，与单击行为不一致）。
        if (getHome().isIndex() && !Setting.isIndexQuickSearch()) {
            VideoActivity.start(this, getHome().getKey(), item.getId(), item.getName(), item.getPic());
            return true;
        }
        CollectActivity.start(this, item.getName());
        return true;
    }

    @Override
    public void onItemClick(History item) {
        bootInteracted = true;   // 老人已经在操作了 → 放弃本次自动抢屏跳转
        // 首页「最近观看」行 = 显式续播：始终恢复上次播放，不受「退出自动续播」开关影响
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic(), null, false, true);
    }

    @Override
    public void onItemDelete(History item) {
        ConfirmDialog.create().message(R.string.elder_confirm_delete_history).callback(() -> deleteHistory(item)).show(this);
    }

    private void deleteHistory(History item) {
        mHistoryAdapter.remove(item.delete());
        if (mHistoryAdapter.size() > 0) return;
        removeHistorySection();
        mPresenter.setDelete(false);
    }

    @Override
    public boolean onLongClick() {
        if (mPresenter.isDelete()) ConfirmDialog.create().message(R.string.elder_confirm_clear_history).callback(this::clearHistory).show(this);
        else setHistoryDelete(true);
        return true;
    }

    @Override
    public void showDialog() {
        // ⛔ 2026-10-17：本页**不提供任何换源入口**（标题、头像、菜单键全部无功能）。
        // 换源入口全站唯一 = 「设置 → 长辈设置 → 一键锁定」（ElderLockActivity）。
        // 本方法仅为满足 CustomTitleView.Listener 的接口契约而保留（空实现）。
        // ⚠ 菜单键已改为「筛选」（见 dispatchKeyEvent），与换源无关。
    }

    /**
     * 打开当前分类的「筛选」面板。
     *
     * <p>只有真实分类（{@link Class}）且它自带 filters 时才有得筛；推荐是 String，
     * 没有分类元数据 ⇒ 不弹面板，只给一句提示（否则老人会对着空弹窗发愣）。</p>
     */
    private void showFilterDialog() {
        if (!(mShownChip instanceof Class type)) {
            Notify.show(R.string.home_filter_none);
            return;
        }
        List<Filter> filters = type.getFilters();
        if (filters.isEmpty()) {
            Notify.show(R.string.home_filter_none);
            return;
        }
        // 防重复打开：弹窗已在显示时再按菜单键不做任何事
        if (getSupportFragmentManager().findFragmentByTag(HomeFilterDialog.TAG) != null) return;
        HomeFilterDialog.create().filters(filters).extend(mFilterExtend).category(type.getTypeName()).show(this);
    }

    @Override
    public void onFilterChanged(HashMap<String, String> extend) {
        // 面板里选值「立即生效」（与手机端一致）：保存条件后用 **force** 重新请求当前分类。
        // ⛔ 必须 force：筛选不改分类，mShownChip 去重键会把请求拦掉。
        mFilterExtend = new HashMap<>(extend);
        loadChip(mShownChip, true);
    }

    @Override
    public void onFilterClosed() {
        // 筛选生效已经重建过内容区（原焦点所在的封面行被移除）⇒ 关闭弹窗后焦点会失去落点。
        // 显式送回「当前分类」的 chip 上：位置明确、不跳顶，也顺带让老人看到高亮确认。
        focusCurrentChip();
    }

    /** 把焦点送到 chips 行上「当前分类」那一项（与 {@link #focusChipRow()} 的区别：不是第一个，而是当前项）。 */
    private void focusCurrentChip() {
        if (mChipItems == null) return;
        int index = mChipItems.indexOf(mShownChip);
        if (index < 0) return;
        int rowIndex = mAdapter.indexOf(mChipRow);
        if (rowIndex < 0) return;
        mBinding.recycler.scrollToPosition(rowIndex);
        mBinding.recycler.post(() -> {
            View row = findAttachedRow(mChipRow);
            if (!(row instanceof RecyclerView)) return;
            RecyclerView rv = (RecyclerView) row;
            rv.scrollToPosition(index);
            rv.post(() -> {
                RecyclerView.ViewHolder holder = rv.findViewHolderForAdapterPosition(index);
                if (holder != null) holder.itemView.requestFocus();
            });
        });
    }

    /** 清掉所有分类的筛选选中态（切分类时调用，与 {@code mFilterExtend.clear()} 配对）。 */
    private void resetFilterSelection() {
        for (Class type : mHomeTypes) {
            for (Filter filter : type.getFilters()) {
                for (Value value : filter.getValue()) value.setSelected(false);
            }
        }
    }

    @Override
    public void onRefresh() {
        getVideo();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 2026-10-17：菜单键 = 打开「筛选」弹窗（复刻手机端首页的筛选能力）。
        // · 只在当前是**真实分类**且该分类带 filters 时才弹；推荐页 / 无 filters 的分类只提示一句。
        // · KeyUtil.isMenuKey 判的是 ACTION_UP ⇒ 长按不会连发。
        if (KeyUtil.isMenuKey(event)) {
            showFilterDialog();
            return true;
        }
        if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event) && getCurrentFocus() == mBinding.title) {
            // ⚠ 2026-10-17 起这是**保留的兜底分支**：标题已不再可聚焦（见 setFocus 的说明），
            // 正常情况下 getCurrentFocus() 不会是 title，此分支不会命中；保留它是为了万一
            // 将来恢复「标题可聚焦」时，这条「按『下』把焦点交回内容区第一行」的出路仍在。
            // 网格还没有任何子项（数据加载中）时 child 为 null，先判空再聚焦，避免空指针闪退
            View first = mBinding.recycler.getChildAt(0);
            if (first != null) return first.requestFocus();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从二级页 / 播放页回到首页：退出连按从头计，避免「按了一下又离开、回来再按一下就退」。
        resetBackTaps();
        mClock.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        // ① 连按计数：2.5 秒内第三次按，任何状态都直接退出
        int taps = countBackTap();
        if (taps == 0) {
            finish();
            return;
        }
        // ② 启动画面：第一次按只是「别等了，进首页」，不计入退出
        if (mBinding.progressLayout.isProgress()) {
            resetBackTaps();
            bootInteracted = true;
            showContent();
            return;
        }
        // ③ 历史删除模式：先退出删除模式，不计入退出
        if (mPresenter.isDelete()) {
            resetBackTaps();
            setHistoryDelete(false);
            return;
        }
        // ④ 焦点不在第一行：顺手回顶部（保留导航能力），本次按键仍计入退出连按
        if (mBinding.recycler.getSelectedPosition() != 0) mBinding.recycler.scrollToPosition(0);

        // ⑤ 只在「本轮第一次」提示一次：系统 Toast 撑不住连续 show（会被队列节流甚至丢弃），
        //    分级提示既看不见又白耗一次 inflate，所以三次里只提示这一下。
        if (taps == 1) Notify.center(R.string.elder_exit_count);
    }

    @Override
    protected boolean backToHome() {
        return false;
    }

    @Override
    protected void onDestroy() {
        // 撤销「等网络就绪再续播」的等待：否则用户已经离开首页（回退时本页用 CLEAR_TOP 被销毁），
        // 网络就绪的回调却把播放页拉起来 —— 表现为「人已在别处，屏幕却被抢走」。
        bootDirectAwait.cancel();
        bootDirectAwait = NetAwait.Handle.NONE;
        App.removeCallbacks(mBootDirectRetry);   // P2：撤销续播重试轮询
        bootDirectRetry = 0;
        // 同理撤销「等网络恢复就重载配置」的监听：本页已销毁，重载只会在一个死掉的实例上刷界面。
        cancelConfigAwait();
        App.removeCallbacks(mChipRunnable); // 防抖回调在页面销毁后不得再触发加载
        // ⛔⛔ 2026-10-16 R66 的「内存释放加固」已在 R67 **回退**（真机堆栈取证）：
        // 这里曾加 `mBinding.recycler.setAdapter(null)` + `Glide.get(this).clearMemory()`，
        // 目的是降低 recreate 峰值内存（当时误判崩溃为 OOM）。
        // 但真机堆栈（TCL ak30a5 / Android 9）显示：
        //   HomeActivity.onDestroy → RecyclerView.setAdapter
        //   → GridLayoutManager.removeAndRecycleAllViews → holder.unbind()
        //   → Glide.with(destroyedActivity) → IllegalArgumentException
        //   ⇒ RuntimeException: Unable to destroy activity ⇒ 闪退。
        // 即：这一行把「可能延后的崩溃」变成了**必然崩溃**。真正的根因是 holder.unbind() 里
        // 的 Glide 调用未防护，已在 ImgUtil#clear 统一根治（所有 holder 已改调它）。
        // 释放内存改由「不主动触发回收」实现 —— 系统销毁视图树时会自然回收。
        // ⚠ 同理 DLNA 的 isChangingConfigurations 守卫保留（与本次崩溃无关，但确为真实加固）。
        if (!isChangingConfigurations()) DLNARendererService.stop(this);
        LiveConfig.get().clear();
        VodConfig.get().clear();
        BackupManager.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}
