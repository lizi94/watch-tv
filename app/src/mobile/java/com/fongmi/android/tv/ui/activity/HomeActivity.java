package com.fongmi.android.tv.ui.activity;

import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.BackupManager;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.receiver.ShortcutReceiver;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.FragmentStateManager;
import com.fongmi.android.tv.ui.fragment.SettingDanmakuFragment;
import com.fongmi.android.tv.ui.fragment.SettingDecodeFragment;
import com.fongmi.android.tv.ui.fragment.SettingFragment;
import com.fongmi.android.tv.ui.fragment.SettingPlayerFragment;
import com.fongmi.android.tv.ui.fragment.SettingPreloadFragment;
import com.fongmi.android.tv.ui.fragment.VodFragment;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.NetAwait;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;

import java.util.List;
import com.github.catvod.net.OkHttp;
import com.google.android.material.navigation.NavigationBarView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class HomeActivity extends BaseActivity implements NavigationBarView.OnItemSelectedListener {

    public static final String EXTRA_BACK_HOME = "back_home";
    /** 内部重建标记（切壁纸/主题色后 recreate）：重建不是用户打开软件，不得触发自动续播。 */
    public static final String EXTRA_REFRESH = "refresh";

    /**
     * 「从播放页按返回键回首页」的进程内瞬时标记：<b>只走静态字段，不写进 Intent</b>。
     *
     * <p>为什么不能写进 Intent：播放页用 {@code NEW_TASK} 拉起首页时，若首页实例不存在，
     * 这个带标记的 Intent 会成为<b>新任务的 baseIntent</b> —— 之后每次「重新打开软件」都被误判成
     * 内部回退，自动续播长期失效。</p>
     */
    private static boolean backHomePending;
    private static long backHomeStamp;

    /** 内部重建（切壁纸 / 主题色触发的 recreate）瞬时标记：语义同上，只走静态字段。 */
    private static boolean internalRecreatePending;
    private static long internalRecreateStamp;

    /** 瞬时标记的有效窗口（ms）：只认紧接着到来的那一次首页显示，避免异常路径下标记永久残留。 */
    private static final long PENDING_TIMEOUT = 5000L;

    /** 由播放页调用：声明随即到来的这次首页显示是内部回退，不是用户打开软件。 */
    public static void markBackHome() {
        backHomePending = true;
        backHomeStamp = System.currentTimeMillis();
    }

    /** 声明这次重建是内部重建（切壁纸 / 主题色 / 图片尺寸），不是用户打开软件。 */
    public static void markInternalRecreate() {
        internalRecreatePending = true;
        internalRecreateStamp = System.currentTimeMillis();
    }

    /**
     * 图片尺寸变化触发的重建：先声明「这是内部重建」。
     *
     * <p>手机端首页同时承载点播列表（{@code VodFragment}）与分类列表（{@code TypeFragment}），
     * 而 {@code RefreshEvent.SIZE} 只有 {@code VodFragment} 响应，分类列表的卡片宽高同样是在
     * adapter 构造时按 {@code Product#getColumn()} 算定的 —— 只靠事件刷新覆盖不全，
     * 所以这里走整页重建（与切壁纸/主题色的既有处理同一手法）。</p>
     *
     * <p>⚠ 不打这个标记的话，重建出的新实例会被 {@code checkBootDirect()} 误判成
     * 「用户在软件里重新打开」，从而重新走一遍自动续播判定并抢屏。</p>
     */
    @Override
    protected void onBeforeRecreate() {
        markInternalRecreate();
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

    private FragmentStateManager mManager;
    /** 上次按返回键的时间戳（双击退出确认用，长辈防误退）。 */
    private long lastBackTime;
    /** 自动续播在每个界面实例的首次显示时只判定一次，避免多次加载回调重复触发。 */
    private boolean bootDirectDone;

    /**
     * R137（F6）：「历史查空但配置还没就绪」的补判标记（与电视版同语义）。
     *
     * <p>{@code History.get()} 按 {@code VodConfig} 当前配置的 cid 过滤 —— 冷启动早期配置
     * 未就绪时 cid 为空 ⇒ 历史查询必空 ⇒ 点播目标没登记，而 {@code bootDirectDone} 已消费。
     * 之后 {@code success()} 只推进「已有目标」的等待，不会重新判定 ⇒ 慢网冷启动点播直达
     * <b>静默失效一次</b>。标记在 {@code success()}/{@code error()} 两个收敛点里消费：
     * 配置就绪后补查一次历史，登记目标走原有两级门控。</p>
     */
    private boolean pendingHistoryCheck;
    /**
     * 正在等待网络就绪的那次自动续播（与电视版同语义）。
     *
     * <p>{@code onNewIntent} 会把 {@code bootDirectDone} 重置后重新判定一次（软件还活着就允许续播），
     * 若上一次等待还挂着，就会在旧回调里再发起一次 —— 表现为「开机后跳两次」或
     * 「用户已经在操作了，页面又被抢走」。因此每次重新判定前必须先撤销上一次等待。</p>
     */
    private NetAwait.Handle bootDirectAwait = NetAwait.Handle.NONE;
    /** 待续播项：仅在「本次应当续播、但网络 / 数据源还没就绪」时非空；就绪跳转后立即清空。 */
    private History mBootDirect;
    /**
     * 本次直达的目标（R125）。
     *
     * <p>与电视版同构：<b>目标</b>与<b>待续播项</b>分成两件事 ——
     * 直播直达没有 History（目标是「直播页」本身），点播直达才有；
     * 目标一旦消费（跳转成功）就清成 {@link BootTarget#NONE}，避免二次跳转与空指针。</p>
     */
    private BootTarget mBootTarget = BootTarget.NONE;
    /** 直达目标类型。 */
    private enum BootTarget {
        NONE, VOD, LIVE
    }
    /** 网络阶段是否已过关：避免在「有网但数据源还没加载好」时反复注册网络回调（会空转自旋）。 */
    private boolean bootNetReady;
    /** 用户是否已操作过首页（真实触摸 / 按键，见 {@link #onUserInteraction()}）：已操作则放弃本次自动抢屏跳转。 */
    private boolean bootInteracted;
    /**
     * 冷启动时刻。用于过滤「系统派发的残留输入」。
     *
     * <p>手机端冷启动早期，系统会向本窗口派发输入事件：点桌面图标后的抬手、权限弹窗关闭后的回焦、
     * 下拉通知栏、锁屏/指纹解锁残留……这些都不是老人真的要操作，却会把
     * {@link #bootInteracted} 打上 ⇒ 刚判定出的续播资格立刻被放弃。</p>
     */
    private long bootStartTime;
    /** 冷启动后的输入宽限期：这段时间内到达的输入视为系统残留（见 bootStartTime 的说明）。 */
    private static final long BOOT_INPUT_GRACE_MS = 1500L;

    /**
     * 无网时「兜底轮询」的固定间隔（R129，与电视端同口径）。
     *
     * <p>R129 当时 {@code NetAwait#isOnline()} 还要求 {@code NET_CAPABILITY_VALIDATED}，部分固件
     * 该位恒 false ⇒ 只挂 {@code whenReady} 会永远等不到，配置失败后永不自愈。⛔ R131 已删该判据，
     * {@code whenReady} 恢复正常；这条轮询保留，兜「网络能力回调压根不上报 / 回调早于监听注册」的机型。</p>
     */
    private static final long RESILIENT_RETRY_MS = 20_000L;

    // R132：直播直达的有限重试（与电视端 tryBootDirect 同口径）。
    //   手机端原本没有第二级轮询：直播配置只在 initConfig() 里加载一次且不带回调，
    //   首次加载失败（开机头几秒 DNS/网关未就绪很常见）后就再无人重拉 ⇒ 直达必然失效。
    private static final int LIVE_BOOT_DIRECT_MAX_RETRY = 30;
    private static final long LIVE_RELOAD_MIN_INTERVAL_MS = 3000L;
    private long liveDirectLoadAt;
    private int liveDirectRetry;
    private final Runnable mLiveDirectRetry = this::tryLiveDirectRetry;
    /** 配置加载失败后挂着的「等网络恢复就重载」监听（与 {@link #bootDirectAwait} 生命周期不同）。 */
    private NetAwait.Handle configAwait = NetAwait.Handle.NONE;
    private ActivityHomeBinding mBinding;
    private int orientation;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 首页是 singleTop：实例被复用时 getIntent() 会一直停在「创建那一刻」的那份，必须同步过来。
        setIntent(intent);
        // 回退标记必须在这里消费掉：实例复用不会走 checkBootDirect，留着的标记会污染下一次真正冷启动。
        boolean backHome = consumeBackHome() || intent.getBooleanExtra(EXTRA_BACK_HOME, false);
        consumeInternalRecreate();
        checkAction(intent);
        // 从播放页 / 设置页返回首页走 CLEAR_TOP 复用，不会再走 onCreate → initConfig()。
        // 若启动时配置因无网失败，这里补检一次：站点列表为空且现在有网就重载配置。
        if (VodConfig.get().getSites().isEmpty() && NetAwait.isOnline()) initConfig();
        // 不是「从播放页返回首页」= 用户在软件内重新打开（图标 / 桌面快捷方式 / 最近任务拉起），
        // 按定下的两层语义：只要软件还活着就允许续播，所以重新判定一次。
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
        bootStartTime = System.currentTimeMillis();
        orientation = getResources().getConfiguration().orientation;
        mBinding.navigation.setOnItemSelectedListener(this);
        // 冷启动就把通知与设备文件权限要到手：手机端「导入数据源 / 选择本地文件」走系统 SAF
        // 选择器，未授权时 FileChooser 拿不到真实路径（会把文件拷进缓存目录），写进配置的
        // 就变成缓存绝对路径；等到选完文件再申请已来不及（路径在 SAF 回调时已定型）。
        PermissionUtil.requestOnLaunch(this);
        // ⛔ 2026-11-05（R108）：必须在 initFragment 之前 —— menu_nav.xml 里三项默认 visible=false，
        //   唯一打开它们的是 setNavigation()，而它原来只挂在 ConfigEvent.COMMON 上。
        //   同进程重建（切主题色 / 改图片尺寸）不重载配置 ⇒ 不会有 COMMON ⇒ 底部导航整条是空的。
        //   与电视端 initView 里首帧调 setFunc() 同一个思路：功能入口不能依赖配置事件。
        setNavigation();
        initFragment(savedInstanceState);
        // ⚠⚠ 2026-10-16（R62，与电视端同口径）：先用本地缓存的配置 JSON 同步重建内存态。
        // 判据 S = !getSites().isEmpty()，而 sites 只能由一次全量配置 JSON 的网络往返填上 ——
        // 没有缓存时「上次加载成功过、但本次开机没网」会被判成数据源没就绪 ⇒ 干等。
        // 有了缓存则 S 立刻为真，电视端/手机端行为一致（见 VodConfig#loadFromCache）。
        //
        // ⛔⛔ 2026-11-04（R101 / P0）：**同进程重建不再重载数据源**。
        //   判据只有一条：「进 initView 那一刻，进程内存里还有没有配置」。
        //     · 有（getRawSites() 非空）⇒ 同进程重建（切主题色 / 切壁纸 / 转屏 / 改字号）：
        //       内存态完好，再走 initConfig() 会重新网络拉一遍配置 JSON，并派发 RefreshEvent.home()
        //       把首页内容一起清空重拉 —— 用户只是换了个外观，不该看到「数据源重新加载」。
        //     · 没有 ⇒ 冷启动 / 进程被回收后的恢复 ⇒ 照旧全量加载。
        //   为什么不用 savedInstanceState != null：它分不清「recreate」与「进程被杀后恢复」，
        //   后者必须重新拉配置（depot 型配置本来就不落缓存）。内存判据天然正确。
        //   WallConfig 是本地的（读偏好 + 读文件），重建后仍需重建内存态，所以暖启动也调它。
        boolean warm = !VodConfig.get().getRawSites().isEmpty();
        VodConfig.get().loadFromCache();
        if (warm) WallConfig.get().init();
        else initConfig();
    }

    @Override
    protected void initEvent() {
        mBinding.navigation.findViewById(R.id.live).setOnLongClickListener(this::addShortcut);
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            FileChooser.getUri(intent, uri -> loadLive(UrlUtil.toLocalUrl(uri)));
        } else {
            FileChooser.getUri(intent, uri -> VideoActivity.file(this, uri));
        }
    }

    private void initFragment(Bundle savedInstanceState) {
        mManager = new FragmentStateManager(mBinding.container, getSupportFragmentManager(), position -> switch (position) {
            case 0 -> VodFragment.newInstance();
            case 1 -> SettingFragment.newInstance();
            case 2 -> SettingPlayerFragment.newInstance();
            case 3 -> SettingDanmakuFragment.newInstance();
            case 4 -> SettingPreloadFragment.newInstance();
            case 5 -> SettingDecodeFragment.newInstance();
            default -> null;
        });
        if (savedInstanceState == null) change(0);
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
                cancelConfigAwait();
                checkAction(getIntent());
                // R137（F6）：数据源就绪后补判一次没登记上的点播续播目标（与电视版收敛点一致）
                recheckPendingHistory();
                // 首次进入：登记续播资格；数据源重载完成：推进已在等待的续播（与电视版收敛点一致）。
                if (bootDirectDone) awaitBootDirect();
                else checkBootDirect();
            }

            @Override
            public void error(String msg) {
                checkAction(getIntent());
                // R137（F6）：error 里也尝试补判 —— recheckPendingHistory 内部会因配置未就绪自行跳过
                recheckPendingHistory();
                if (bootDirectDone) awaitBootDirect();
                else checkBootDirect();
                StateEvent.empty();
                Notify.show(msg);
                // 配置没拉到：若当前没网，挂网络恢复监听，网络一通自动重载配置；
                // 有网仍失败 = 数据源侧问题，不做自动轮询（避免「失败 → 重载 → 再失败」的忙循环）。
                awaitConfigNetwork();
            }
        };
    }

    /**
     * ⑧ 自动续播（与电视版开机直达同语义）：打开软件时直接进入上次观看的点播节目，
     * 老人不必再找历史记录。
     *
     * <p><b>本方法只做「资格判定」，不发起跳转</b>：判定成立时把待续播项记到 {@link #mBootDirect}，
     * 由 {@link #awaitBootDirect()} 用「网络 → 数据源」两级门控决定何时真正跳。旧版在这里直接
     * {@code VideoActivity.start(...)}：数据源还没加载好就跳，详情页取不到站点 ⇒ 请求失败 ⇒
     * 停在空白页（与电视版改造前同一个坑）。</p>
     */
    private void checkBootDirect() {
        if (bootDirectDone) return;
        bootDirectDone = true;
        // 两个瞬时标记必须先消费再判断：若门控提前 return，标记会残留到下一次冷启动，误伤续播。
        if (consumeBackHome()) return;
        if (consumeInternalRecreate()) return;
        // R125（P1）：**直播分支必须排在「点播续播关闭」早退之前**。
        //   两个开关在设置页互斥（见 ElderSettingsActivity#setBootLive / #setAutoResume），
        //   「直播直达开」必然意味着「点播续播关」—— 旧代码把 !isAutoResume() 的早退放在前面，
        //   于是直播分支<b>永远走不到</b>（100% 不可达）：开关打开的效果只是「把点播续播关掉」，
        //   手机端就是一个纯断功能的空开关（P1）。现在直播优先，两个开关都不再是空开关。
        if (Setting.isBootLive()) {
            // 与点播续播同一套「普通启动」判据：排除回退 / 内部重建 / 带 data / 分享 / 搜索。
            if (!isPlainLaunch(getIntent())) return;
            mBootTarget = BootTarget.LIVE;
            awaitBootDirect();
            return;
        }
        // 退出自动续播关闭：不再自动跳入上次观看的节目，落在首页
        if (!Setting.isAutoResume()) return;
        if (!isPlainLaunch(getIntent())) return;
        List<History> items = History.get();
        if (items.isEmpty()) {
            // R137（F6）：History.get() 按当前配置 cid 过滤 —— 冷启动早期配置未就绪时 cid 为空，
            // 查空不代表「真没历史」。挂起补判标记，等配置就绪回调再查一次。
            if (VodConfig.get().getSites().isEmpty()) pendingHistoryCheck = true;
            return;
        }
        History item = items.get(0);
        if (TextUtils.isEmpty(item.getVodId())) return;
        // 只登记「本次应当续播」；什么时候跳、能不能跳，交给两级门控。
        mBootDirect = item;
        mBootTarget = BootTarget.VOD;
        awaitBootDirect();
    }

    /**
     * R137（F6）：数据源就绪后补判一次「没登记上的点播续播目标」（与电视版同语义）。
     *
     * <p>由 {@code success()}/{@code error()} 两个收敛点调用；配置仍未就绪时保留标记
     * 等下一次成功回调。用户已操作（{@link #bootInteracted}）或已有目标时立即放弃，
     * 绝不把用户从当前页面拽走。</p>
     */
    private void recheckPendingHistory() {
        if (!pendingHistoryCheck) return;
        // 配置仍未就绪（error 回调也会进来）：保留标记，等下一次成功回调再补
        if (VodConfig.get().getSites().isEmpty()) return;
        pendingHistoryCheck = false;
        if (bootInteracted) return;
        if (mBootTarget != BootTarget.NONE) return;
        List<History> items = History.get();
        if (items.isEmpty()) return;
        History item = items.get(0);
        if (TextUtils.isEmpty(item.getVodId())) return;
        mBootDirect = item;
        mBootTarget = BootTarget.VOD;
        awaitBootDirect();
    }

    /**
     * 推进自动续播：<b>两级门控</b> —— 先等网络就绪，再等数据源就绪，全绿才跳转。
     *
     * <p><b>为什么必须分两级</b>：{@link NetAwait#whenReady} 在「当前已经联网」时会<b>立即</b>回调，
     * 若在「有网但数据源还没好」时再挂一次网络等待，就会立刻被唤醒又立刻判失败，形成空转自旋。
     * 因此网络阶段只挂一次、用 {@link #bootNetReady} 记下已过关；数据源阶段完全被动 ——
     * 等配置加载成功的回调（{@code getCallback().success()}）来推进，不重新注册网络回调。</p>
     */
    private void awaitBootDirect() {
        // R125：主控从「待续播项」换成「直达目标」—— 直播目标没有 History，只有目标本身。
        if (mBootTarget == BootTarget.NONE) return;
        // 老人已经在操作了 → 彻底放弃本次抢屏（清掉待续播项，之后配置成功也不会再被拉起）。
        if (bootInteracted) {
            resetBootDirectState();
            return;
        }
        if (isFinishing() || isDestroyed()) return;
        if (!bootNetReady) {
            // 第一级：等网络。已联网时 whenReady 会立即回调。不设超时：网络一直不来就一直不跳，
            // 页面停在首页让老人自己操作，绝不乱跳。已在等待中则不重复注册（避免多个回调抢跳）。
            if (bootDirectAwait != NetAwait.Handle.NONE) return;
            bootDirectAwait = NetAwait.whenReady(0L, () -> {
                bootNetReady = true;
                awaitBootDirect();
            }, () -> {
            });
            return;
        }
        // 第二级：网络已就绪，只看数据源。点播未就绪仍交给 success() 回调推进；
        // 直播未就绪必须主动重拉 —— 旧逻辑这里直接 return，直播配置一次加载失败后
        // 再无人重拉，直播直达必然失效（R132，与电视端 tryBootDirect 同口径）。
        if (!isBootDirectReady()) {
            if (mBootTarget == BootTarget.LIVE) tryLiveDirectRetry();
            return;
        }
        fireBootDirect();
    }

    /**
     * R132：直播直达的有限重试（电视端由 tryBootDirect 的 1 秒轮询承担，手机端在这里补齐）。
     *
     * <p>每次 tick：就绪即跳（fireBootDirect 自带一次性与防重入）；未就绪则限频
     * {@link #LIVE_RELOAD_MIN_INTERVAL_MS} 重新拉一次直播配置，再排下一次 tick。
     * {@code App.post} 对同一个 Runnable 先 remove 再 post，事件驱动的重复调用不会叠出多个定时器。
     * 预算 {@link #LIVE_BOOT_DIRECT_MAX_RETRY}；就绪 / 用户按键 / 页面销毁都会停。</p>
     */
    private void tryLiveDirectRetry() {
        if (mBootTarget != BootTarget.LIVE || bootInteracted) return;
        if (isFinishing() || isDestroyed()) return;
        if (isBootDirectReady()) {
            fireBootDirect();
            return;
        }
        // R136：数据「根本不可得」时立即放弃，不再空转 30 秒：
        //   · 直播源从未配置（url 为空）——没有任何可加载的数据；
        //   · sync（直播配置与主数据源同源）：直播数据只能由主数据源 JSON 内嵌的 lives 灌入
        //     （见 VodConfig#initLive），独立重拉会被 LiveConfig#load() 的 sync 早退吞掉，
        //     主数据源已加载完成仍无 lives ⇒ 永远等不到。
        //   「独立直播源 + 网络慢」不在此列：那种场景重拉有意义，仍走下面的限频重试预算。
        if (LiveConfig.isUnreachable()) {
            abandonLiveDirect();
            return;
        }
        long now = System.currentTimeMillis();
        if (liveDirectLoadAt == 0L || now - liveDirectLoadAt >= LIVE_RELOAD_MIN_INTERVAL_MS) {
            liveDirectLoadAt = now;
            LiveConfig.get().init().load();
        }
        if (liveDirectRetry++ < LIVE_BOOT_DIRECT_MAX_RETRY) App.post(mLiveDirectRetry, 1000);
            // R136：预算用尽不再静默——旧版这里自然停摆，老人只看到「没跳」，不知道发生了什么。
        else abandonLiveDirect();
    }

    /**
     * R136：直播直达的统一「放弃出口」——数据不可得（LiveConfig#isUnreachable()）
     * 或限频重试预算用尽时走这里：清掉等待与登记（resetBootDirectState），并给出一次明确提示。
     * 旧版两处都静默：直播不跳、续播又被互斥开关关掉，老人两头落空且毫无线索。
     * 提示后落回首页；下次打开软件时重新判定。本方法不降级续播——两个开关互斥，
     * 「直播直达开」意味着用户明确选择了直播，擅自转跳点播违背设置。
     */
    private void abandonLiveDirect() {
        if (mBootTarget != BootTarget.LIVE) return;
        resetBootDirectState();
        Notify.show(R.string.boot_live_unready);
    }

    /**
     * 自动续播的「放行」判据：三条全绿才允许跳转详情页。
     *
     * <p>第三条（该 siteKey 在当前配置里存在）是关键一环：老人换过数据源后，历史记录里的
     * siteKey 可能已不在新配置的站点列表中，此时即便网络与配置都正常，详情页依然取不到站点、
     * 依然会失败。少这一条就堵不住这个口子。</p>
     */
    private boolean isBootDirectReady() {
        // ⛔ R131：这里原本是 `if (!NetAwait.isOnline()) return false;` —— **已删**。
        //   原因见 NetAwait#isOnline()：它曾要求 NET_CAPABILITY_VALIDATED，该位在国内固件上
        //   恒 false ⇒ 本方法恒返回 false ⇒ 点播续播与直播直达 100% 不触发。
        //   现在判据只留「数据源是否就绪」—— 配置能拉下来本身就证明网是通的，不必再判一次。
        // R133：直播目标 —— 判「直播配置已就绪」= lives 非空且已选定源（LiveConfig#hasData()）。
        //   ⛔ 不能用 getHome().getGroups().isEmpty()：URL 型直播源（type≥1）的频道分组要等
        //   LiveActivity 打开后自行拉取才会填充，配置解析完成时恒为空 ⇒ 旧判据对这类源永远为假，
        //   直播直达永远不跳（小米电视实测：手动进直播正常播放，开机直达永不触发，BDiag 全程
        //   liveGroups=0）。跳转后的频道拉取由 LiveActivity 自身负责，与手动进入同一条路径。
        if (mBootTarget == BootTarget.LIVE) return LiveConfig.hasData();
        // R125：待续播项可能已被消费（跳转后置空）—— 必须先判空，否则 mBootDirect.getSiteKey() NPE。
        if (mBootDirect == null) return false;
        if (VodConfig.get().getSites().isEmpty()) return false;
        if (!VodConfig.get().getSite(mBootDirect.getSiteKey()).isEmpty()) return true;
        // ⛔ 2026-10-24 修「冷启动/被杀进程后退出自动续播失效」：
        // 历史记录里的 siteKey 一旦不在当前配置里（换过数据源、被「一键锁定」固定到别的源、
        // 那个站点被移除），这里就会恒为 false —— 于是**每次冷启动都不续播**，而且没有任何提示。
        // 现在退化为「当前主站点可用就放行」，由 fireBootDirect 用主站点打开。
        return !VodConfig.get().getHome().isEmpty();
    }

    /** 三绿后真正发起续播。此时详情页必定能取到站点，不存在「跳进去再失败」。 */
    private void fireBootDirect() {
        // R125：目标一次性消费 —— 发完就清成 NONE，后续驱动自然早退，不会二次跳、不会空指针。
        BootTarget target = mBootTarget;
        if (target == BootTarget.NONE) return;
        if (bootInteracted) return;
        if (isFinishing() || isDestroyed()) return;
        mBootTarget = BootTarget.NONE;
        bootDirectAwait.cancel();
        bootDirectAwait = NetAwait.Handle.NONE;
        if (target == BootTarget.LIVE) {
            LiveActivity.start(this);
            return;
        }
        History item = mBootDirect;
        mBootDirect = null;
        if (item == null) return;
        // siteKey 失效时用当前主站点打开（与 isBootDirectReady 的降级判据配套）
        String key = item.getSiteKey();
        if (VodConfig.get().getSite(key).isEmpty()) key = VodConfig.get().getHome().getKey();
        VideoActivity.start(this, key, item.getVodId(), item.getVodName(), item.getVodPic());
    }

    /** 重新判定自动续播前清掉上一轮的等待与进度标记（{@code onNewIntent} 会走到）。 */
    private void resetBootDirectState() {
        bootDirectAwait.cancel();
        bootDirectAwait = NetAwait.Handle.NONE;
        mBootDirect = null;
        mBootTarget = BootTarget.NONE;
        bootNetReady = false;
        // 还是一次新的「打开软件」，允许续播；已操作标记随之复位。
        bootInteracted = false;
        // R132：直播直达的限频时间戳 / 重试计数一并清零，重试定时器一并撤销。
        liveDirectLoadAt = 0L;
        liveDirectRetry = 0;
        App.removeCallbacks(mLiveDirectRetry);
    }

    /**
     * 配置加载失败后挂网络恢复监听：网络一就绪就自动重新加载配置。
     *
     * <p><b>为什么必须有它</b>：软件冷启动时网络可能还没连上，配置拉取失败后首页停在空状态；
     * 旧实现下这状态永远不会自愈，老人只能「关掉重开」，而那时网络可能更糟。</p>
     *
     * <p><b>⚠ 必须只在「现在没网」时挂</b>：{@link NetAwait} 在已联网时会<b>立即</b>执行回调，
     * 若网络正常、只是数据源服务器挂了，配置同样会失败 —— 此时还挂监听就会变成
     * 「失败 → 立即重载 → 又失败」的忙循环。数据源侧异常只提示错误，重试交给用户手动操作。</p>
     */
    private void awaitConfigNetwork() {
        cancelConfigAwait();
        if (NetAwait.isOnline()) return;
        configAwait = NetAwait.whenReady(0L, this::reloadConfig, () -> {
        });
        // R129：兜底轮询（理由同电视端）。R131 前 whenReady 依赖 VALIDATED、坏固件永不触发；
        //   R131 后保留，兜「网络能力回调不上报 / 回调早于监听注册」的机型。
        App.post(this::reloadConfig, RESILIENT_RETRY_MS);
    }

    /** 网络恢复后的重载：先撤销等待（一次即够），再重走一遍配置加载。 */
    private void reloadConfig() {
        configAwait = NetAwait.Handle.NONE;
        if (isFinishing() || isDestroyed()) return;
        initConfig();
    }

    private void cancelConfigAwait() {
        configAwait.cancel();
        configAwait = NetAwait.Handle.NONE;
    }

    /**
     * 是否属于「用户主动打开软件」这类普通启动。
     *
     * <p><b>为什么用排除法而不是白名单</b>：原先只认 {@code action == null || ACTION_MAIN}，
     * 但桌面快捷方式、厂商「一键直达」、第三方启动器拉起应用时，intent 常用
     * {@code ACTION_VIEW} 或自定义 action + 显式 component —— 会被白名单判成「非普通启动」，
     * 自动续播随即失效，而图标启动（标准 {@code ACTION_MAIN}）却正常。</p>
     *
     * <p>现在只排除<b>确实带着别的事来</b>的启动：内部回退 / 内部重建 / 携带 data（打开文件或链接）/
     * 分享 / 搜索；其余一律视为用户主动打开软件。</p>
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

    private void loadLive(String url) {
        if (isFinishing() || isDestroyed()) return;
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                openLive();
            }
        });
    }

    private void setNavigation() {
        mBinding.navigation.getMenu().findItem(R.id.vod).setVisible(true);
        mBinding.navigation.getMenu().findItem(R.id.setting).setVisible(true);
        // 底部导航直播入口：无直播源或设置里关闭「底部导航直播」时隐藏，直播仍可从设置页使用
        // ⚠ 2026-10-16：判据从 hasUrl() 换成 hasData()，与电视端同口径。主配置 JSON 里带 lives 字段时
        // VodConfig.initLive() 会拿主数据源 url 建一条 type=1 的 Config 行，hasUrl() 因此为 true ——
        // 底部导航于是冒出「直播」入口，点了却一条频道都没有（sync=true 又让直播配置永不加载）。
        mBinding.navigation.getMenu().findItem(R.id.live).setVisible(LiveConfig.hasData() && Setting.isNavLive());
    }

    private boolean openLive() {
        LiveActivity.start(this);
        return false;
    }

    private boolean addShortcut(View view) {
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(this, getString(R.string.nav_live)).setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher)).setIntent(new Intent(Intent.ACTION_VIEW, null, this, LiveActivity.class)).setShortLabel(getString(R.string.nav_live)).build();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(this, ShortcutReceiver.class).setAction(ShortcutReceiver.ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ShortcutManagerCompat.requestPinShortcut(this, info, pendingIntent.getIntentSender());
        return true;
    }

    public void change(int position) {
        if (position < 2) mBinding.navigation.setSelectedItemId(position == 0 ? R.id.vod : R.id.setting);
        else mManager.change(position);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                // 与电视端收敛点对齐（2026-10-15 门控同步）：
                // ConfigEvent.vod() 在 BaseConfig#loadConfig 的 finally 里派发（成功失败都会发），
                // 是「数据源状态可能变了」的唯一可靠信号 —— 换源 / 网络恢复后的自动重载 /
                // 首次加载都可能发生在这里，续播门控必须在这一刻也推进一次，否则本次续播
                // 会一直等不到推进而静默失效。
                if (bootDirectDone) awaitBootDirect();
                else checkBootDirect();
                RefreshEvent.home();
                break;
            case COMMON:
                setNavigation();
                break;
            case BOOT:
            case LIVE:
                // R125：BOOT 只有一个来源（直播配置解析完成 + 「直播开机直达」开关打开，见 LiveConfig#setHome），
                //   LIVE 是「直播配置就绪」。两者都只用来**推进已登记的直播目标**，不新建目标 ——
                //   否则运行期任何一次直播配置重载（换源、重试）都会把老人从正在看的页面上拽走。
                //   R125：原先这里还有一个 tvbus 分量（「用户刚换了直播内核」）—— 该语义已由
                //   TVBus 原地重建引擎取代（换内核不再需要杀进程再跳回来），故一并删除。
                if (mBootTarget == BootTarget.LIVE) awaitBootDirect();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.THEME) {
            // 切壁纸/主题色后重建界面让新样式全局生效。重建是内部操作而非用户启动软件，
            // 用进程内静态标记声明（不再写进 Intent：Intent 会被 AMS 记进任务 baseIntent 长期残留）。
            markInternalRecreate();
            recreate();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() == ServerEvent.Type.PUSH) VideoActivity.push(this, event.text());
        if (event.type() == ServerEvent.Type.SEARCH) SearchActivity.start(this, event.text());
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        // ⛔ 这里<b>不能</b>置 bootInteracted：首页初始化时的 change(0) 是程序化选中
        // （setSelectedItemId 会同步触发本回调），若在此打标记，配置还没加载完它就已经是 true，
        // 冷启动的自动续播必然被放弃 —— 改为用 onUserInteraction() 只认真实用户输入。
        if (item.getItemId() == R.id.setting) return mManager.change(1);
        if (item.getItemId() == R.id.vod) return mManager.change(0);
        if (item.getItemId() == R.id.live) return openLive();
        return false;
    }

    /**
     * 真实用户操作（触摸 / 按键 / 滚动）＝老人已经在用软件了 → 放弃本次自动抢屏续播。
     *
     * <p><b>为什么用这个方法而不是 {@code onNavigationItemSelected}</b>：后者会被「首页初始化」
     * 的程序化选中（{@link #change(int)} → {@code setSelectedItemId}）同步触发，导致冷启动时
     * 配置尚未加载、标记已被打上，自动续播永远失效。{@code onUserInteraction} 只在系统向本窗口
     * 派发真实输入事件时回调，程序化调用不会触发。</p>
     *
     * <p><b>2026-10-15 修「退出后自动续播失效」</b>：原来的写法无条件置位，而手机端冷启动早期
     * 系统会向本窗口派发输入事件 —— 点桌面图标后的抬手、权限弹窗关闭后的回焦、下拉通知栏、
     * 锁屏/指纹解锁残留事件都会走到这里。此时续播资格都还没判定（配置还在加载），标记就已经是
     * true，等配置就绪后 {@code awaitBootDirect()} 首行即放弃 ⇒ 续播<b>必然失效</b>。
     * 电视端没有这个风险：它只在 5 个明确的点击入口置位。
     * 现在改成「只有续播资格已判定完成之后到来的操作才算数」，既不误伤冷启动，又守住
     * 「老人已经在用软件就别抢屏」。</p>
     */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (!bootDirectDone) return;
        // 冷启动后极短时间内的输入视为系统残留，不算「老人在操作」，不能用来取消续播。
        if (System.currentTimeMillis() - bootStartTime < BOOT_INPUT_GRACE_MS) return;
        bootInteracted = true;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        App.post(() -> checkOrientation(newConfig), 100);
    }

    private void checkOrientation(Configuration newConfig) {
        if (orientation != newConfig.orientation) {
            orientation = newConfig.orientation;
            RefreshEvent.home();
        }
    }

    @Override
    protected void onBackInvoked() {
        if (!mBinding.navigation.getMenu().findItem(R.id.vod).isVisible()) {
            setNavigation();
        } else if (mManager.isVisible(4) || mManager.isVisible(5)) {
            change(2);
        } else if (mManager.isVisible(3) || mManager.isVisible(2)) {
            change(1);
        } else if (mManager.isVisible(1)) {
            change(0);
        } else if (mManager.canBack(0)) {
            if (PlaybackService.isRunning()) Util.moveToBackground(this);
            else if (System.currentTimeMillis() - lastBackTime < 2000) super.onBackInvoked();
            else {
                lastBackTime = System.currentTimeMillis();
                Notify.show(R.string.elder_exit_hint);
            }
        }
    }

    @Override
    protected void onDestroy() {
        cancelConfigAwait();
        bootDirectAwait.cancel();
        bootDirectAwait = NetAwait.Handle.NONE;
        BackupManager.backup();
        // ⛔ 2026-11-05（R107）：**只有真正退出才拆进程级资源**。
        //   原来这几行无条件执行，而 recreate（切主题色 / 改图片尺寸 / 改字号）必然走 onDestroy
        //   ⇒ 紧接着的 initView 里 warm 判据（getRawSites() 非空）必然为 false
        //   ⇒ 照样 initConfig() 全量重载。R101 那轮「同进程重建不重载数据源」被这一处彻底抵消。
        //   为什么不能反过来「重建后补 Server.start()」：OkHttp 的 dns / selector / 响应拦截器
        //   是在 loadConfig() 里 addAll 的，Source.exit() 还会把解析器置成已退出 —— 清掉之后
        //   只有重载配置才能恢复，那就回到原点了。所以这里只能「不清」。
        //   recreate / 配置变更销毁时 isFinishing() 为 false，资源与内存态原样留给下一个实例。
        if (isFinishing()) {
            LiveConfig.get().clear();
            VodConfig.get().clear();
            OkHttp.get().clear();
            Source.get().exit();
            Server.get().stop();
        }
        super.onDestroy();
    }
}
