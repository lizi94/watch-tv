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
     * 正在等待网络就绪的那次自动续播（与电视版同语义）。
     *
     * <p>{@code onNewIntent} 会把 {@code bootDirectDone} 重置后重新判定一次（软件还活着就允许续播），
     * 若上一次等待还挂着，就会在旧回调里再发起一次 —— 表现为「开机后跳两次」或
     * 「用户已经在操作了，页面又被抢走」。因此每次重新判定前必须先撤销上一次等待。</p>
     */
    private NetAwait.Handle bootDirectAwait = NetAwait.Handle.NONE;
    /** 待续播项：仅在「本次应当续播、但网络 / 数据源还没就绪」时非空；就绪跳转后立即清空。 */
    private History mBootDirect;
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
        initFragment(savedInstanceState);
        // ⚠⚠ 2026-10-16（R62，与电视端同口径）：先用本地缓存的配置 JSON 同步重建内存态。
        // 判据 S = !getSites().isEmpty()，而 sites 只能由一次全量配置 JSON 的网络往返填上 ——
        // 没有缓存时「上次加载成功过、但本次开机没网」会被判成数据源没就绪 ⇒ 干等。
        // 有了缓存则 S 立刻为真，电视端/手机端行为一致（见 VodConfig#loadFromCache）。
        VodConfig.get().loadFromCache();
        initConfig();
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
                // 首次进入：登记续播资格；数据源重载完成：推进已在等待的续播（与电视版收敛点一致）。
                if (bootDirectDone) awaitBootDirect();
                else checkBootDirect();
            }

            @Override
            public void error(String msg) {
                checkAction(getIntent());
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
     * 推进自动续播：<b>两级门控</b> —— 先等网络就绪，再等数据源就绪，全绿才跳转。
     *
     * <p><b>为什么必须分两级</b>：{@link NetAwait#whenReady} 在「当前已经联网」时会<b>立即</b>回调，
     * 若在「有网但数据源还没好」时再挂一次网络等待，就会立刻被唤醒又立刻判失败，形成空转自旋。
     * 因此网络阶段只挂一次、用 {@link #bootNetReady} 记下已过关；数据源阶段完全被动 ——
     * 等配置加载成功的回调（{@code getCallback().success()}）来推进，不重新注册网络回调。</p>
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
        // 第二级：网络已就绪，只看数据源。没就绪就直接返回 —— 由 success() 回调再推进。
        if (!isBootDirectReady()) return;
        fireBootDirect();
    }

    /**
     * 自动续播的「放行」判据：三条全绿才允许跳转详情页。
     *
     * <p>第三条（该 siteKey 在当前配置里存在）是关键一环：老人换过数据源后，历史记录里的
     * siteKey 可能已不在新配置的站点列表中，此时即便网络与配置都正常，详情页依然取不到站点、
     * 依然会失败。少这一条就堵不住这个口子。</p>
     */
    private boolean isBootDirectReady() {
        if (!NetAwait.isOnline()) return false;
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
        History item = mBootDirect;
        mBootDirect = null;
        bootDirectAwait.cancel();
        bootDirectAwait = NetAwait.Handle.NONE;
        if (item == null || bootInteracted) return;
        if (isFinishing() || isDestroyed()) return;
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
        bootNetReady = false;
        // 还是一次新的「打开软件」，允许续播；已操作标记随之复位。
        bootInteracted = false;
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
                LiveActivity.start(this);
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
        LiveConfig.get().clear();
        VodConfig.get().clear();
        BackupManager.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}
