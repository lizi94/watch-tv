package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
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
import com.fongmi.android.tv.ui.dialog.ConfirmDialog;

import com.fongmi.android.tv.ui.dialog.HomeFilterDialog;
import com.fongmi.android.tv.ui.presenter.EmptyCardPresenter;
import com.fongmi.android.tv.ui.presenter.FuncPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.HomeChipPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.BootSession;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.NetAwait;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.TouchGridAlign;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;
import com.google.common.collect.Lists;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class HomeActivity extends BaseActivity implements VodPresenter.OnClickListener, FuncPresenter.OnClickListener, HistoryPresenter.OnClickListener, HomeChipPresenter.OnClickListener, HomeFilterDialog.Listener {

    /** 内部「按返回键回首页」使用的标记：用于把这种回退和开机/启动器唤起区分开。 */
    public static final String EXTRA_BACK_HOME = "back_home";

    /** 内部重建标记（字号档位切换后 recreate）：重建不是用户打开软件，不得触发开机直达。 */
    public static final String EXTRA_REFRESH = "refresh";

    /**
     * 「系统/自启拉起」标记（R124）：{@code BootLauncher} 拉起首页时打上。
     *
     * <p>为什么要有它：系统把本应用当默认桌面拉起时用的是 <b>HOME 意图</b>，而开机自启、
     * 桌面守护拉起用的是「什么都不带」的普通 Intent。两者在 {@link #isPlainLaunch} 里原本走同一条
     * 分支，于是「守护把用户从别的应用拉回来」也会被当成「用户主动打开软件」⇒ 触发续播。</p>
     */
    public static final String EXTRA_BOOT_LAUNCH = "boot_launch";

    /** 「桌面守护拉回」标记（R124）：用户按主页键离开后被守护拉回来 ⇒ 不该触发续播。 */
    public static final String EXTRA_GUARD_PULL = "guard_pull";

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

    // ── R132：直播直达的独立重试预算与限频参数 ──
    //   · 预算比点播的 15 次更长：开机后手动打开时，头几秒 DNS / 网关常常还没就绪，
    //     直播配置的首次加载会失败，而「打开后必进直播界面」是用户明确要求的行为，多等一会儿；
    //   · 限频 3 秒：重试 tick 是 1 秒，不能把还在路上的下载反复取消
    //     （BaseConfig#load 新一轮会取消上一轮，见 taskId 机制）。
    private static final int LIVE_BOOT_DIRECT_MAX_RETRY = 30;
    private static final long LIVE_RELOAD_MIN_INTERVAL_MS = 3000L;
    private long liveDirectLoadAt;

    // ── R132：首页内容级自动重试（配置成功但站点内容请求失败的空态自愈）──
    private static final long[] CONTENT_RETRY_STEPS = {5_000L, 10_000L, 20_000L};
    private int contentRetryCount;
    private final Runnable mContentRetry = this::retryContentOnce;

    // ── R132：快速待机补偿 ── 退后台超过这个时长且直达目标还没消费，回来就重新驱动一次。
    private static final long QUICK_RESUME_REDRIVE_MS = 60_000L;
    private long mPausedAt;

    /** 待续播项：仅在「本次应当续播、但数据源还没就绪」时非空；就绪后立即消费掉。 */
    private History mBootDirect;

    /** 网络阶段是否已过关：避免在「有网但数据源还没加载好」时反复注册网络回调（会空转自旋）。 */
    private boolean bootNetReady;

    /** 老人在首页是否已经操作过（点过任何条目）：已操作则不再自动抢屏跳转。 */
    /** 冷启动落焦后第二次回顶的延时：覆盖「内容行异步插入 → 重新布局」这个时间窗。 */
    private static final long HOME_TOP_DELAY = 300L;

    /** 内容区重建后补落焦的延时：等新内容布局完成再判断「焦点是不是真的丢了」。 */
    private static final long HOME_FOCUS_RESTORE_DELAY = 400L;

    /**
     * {@link #onNewIntent} 里「站点为空就补检一次配置」的最小间隔（2026-10-29 / R99）。
     *
     * <p>该补检原先无节流：播放页返回、设置页返回、桌面拉起都会命中，每次都是一轮完整的
     * 配置网络往返。而 {@code BaseConfig#load} 每轮都会 cancel 上一轮的 future 并自增 taskId
     * ⇒ 短时间内多轮并发会自己打断自己，反而更容易「拉一半失败」，最后落到空态卡上。
     * 10 秒是个保守值：既挡住连点/反复返回造成的风暴，又不影响「网络刚恢复就该自己好」的体验
     *（失败后还有 {@code configAwait} 在网络恢复时立刻重载，不依赖这里）。</p>
     */
    private static final long CONFIG_RECHECK_INTERVAL = 10_000L;

    /** 上一次「onNewIntent 补检配置」的时刻（elapsedRealtime）：仅用于上面那条节流。 */
    private long lastConfigRecheckAt;

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

    // ------------------------------------------------------------------ R123：启动场景 / 门控宽限 / 自动重试

    /**
     * 本次界面是怎么被拉起来的（R123）。
     *
     * <p><b>为什么必须分开</b>：同一个 {@code HomeActivity} 会被多种完全不同的入口拉起，
     * 而这些场景对「要不要等网络」「要不要自动续播」的期望是<b>相反</b>的 ——
     * 一张判据表糊在一起，必然有一边被误伤（R123 之前就是：点播续播在默认桌面场景失效、
     * 门控在开机场景被瞬间放行）。</p>
     *
     * <table border="1">
     *   <tr><th>场景</th><th>入口</th><th>走哪个方法</th><th>门控宽限</th><th>点播续播</th></tr>
     *   <tr><td>{@link #COLD_BOOT_DESKTOP} 冷启动·默认桌面</td>
     *       <td>系统开机用 HOME 意图拉起（本树默认不声明 HOME，仅当用户用别的方式把 HOME 指向本应用）</td>
     *       <td>{@code initView}</td><td><b>走</b>（最多 12 秒）</td><td><b>允许</b></td></tr>
     *   <tr><td>{@link #COLD_BOOT} 冷启动·开机自启</td>
     *       <td>BootReceiver 在开机阶段拉起（进程已死）</td>
     *       <td>{@code initView}</td><td><b>走</b></td><td><b>允许</b></td></tr>
     *   <tr><td>{@link #COLD_NORMAL} 冷启动·日常</td>
     *       <td>开机很久之后点图标 / 进程被回收后重新打开（R134：待机唤醒杀进程后的冷启动仍归此类，
     *       但 {@code mBootSession} 可另置 true ⇒ 直达允许；门控宽限仍不吃）</td>
     *       <td>{@code initView}</td><td><b>不走</b>（0 等待，保持 R62 口径）</td><td><b>允许</b></td></tr>
     *   <tr><td>{@link #WARM_REOPEN} 热启动·重新打开</td>
     *       <td>进程还活着，用户点图标 / 遥控器快捷键 → {@code onNewIntent}</td>
     *       <td>{@code onNewIntent}</td><td>不参与（启动画面早就收了）</td><td><b>允许</b></td></tr>
     *   <tr><td>{@link #WARM_HOME} 热启动·按主页键回来</td>
     *       <td>HOME 意图投递给已有实例；或进程刚被回收、按主页键让系统重新拉起我们</td>
     *       <td>{@code onNewIntent}</td><td>不参与</td><td><b>禁止</b>（用户只想回桌面）</td></tr>
     *   <tr><td>待机唤醒（电视 STR / 关机再开）</td>
     *       <td><b>不产生新 intent</b>，只走 {@code onResume}；实例还在、内容还在</td>
     *       <td>{@code onResume}</td><td>不参与（{@code isProgress()} 已为 false）</td>
     *       <td><b>允许</b>（R124/R134：熄屏时首页在前台、待机 ≥60 秒、距亮屏 ≤60 秒 ⇒ 算一次开机会话，重新发起直达；
     快照已持久化，进程被杀也能识别）</td></tr>
     * </table>
     *
     * <p><b>待机唤醒为什么不需要额外分支</b>：① 实例活着 → {@code onResume}，两种判定都不跑；
     * ② 进程在待机期间被杀 → 走 {@code initView}，但 {@code SystemClock.elapsedRealtime()} 从
     * 开机起算且<b>包含深睡</b>，唤醒时必然早已超过 {@link #BOOT_PHASE_MS} ⇒ 落 {@link #COLD_NORMAL}
     * ⇒ 零等待进首页（有缓存就用缓存），正是期望行为。</p>
     */
    private enum LaunchKind {
        COLD_BOOT_DESKTOP, COLD_BOOT, COLD_NORMAL, WARM_REOPEN, WARM_HOME;

        /** 是否属于「开机阶段的一次冷启动」—— 只有这一类才吃门控宽限。 */
        boolean isBootCold() {
            return this == COLD_BOOT_DESKTOP || this == COLD_BOOT;
        }
    }

    /** 本次界面的启动场景，{@code initView} 里定一次，{@code onNewIntent} 里更新。 */
    private LaunchKind mLaunchKind = LaunchKind.COLD_NORMAL;

    /**
     * R124：本次启动是不是「开机 / 唤醒」会话。
     *
     * <p><b>为什么要和 {@link LaunchKind} 分开</b>：① 待机唤醒（老人按遥控器开机）<b>不产生新 intent</b>，
     * 只能由 {@code onResume} 置位；② {@code onNewIntent} 复用实例时<b>不能</b>把它清掉 ——
     * 系统在开机时可能重复投递 HOME 意图，那不是「用户按了主页键」。</p>
     *
     * <p>它决定三件事：① 默认桌面形态下要不要续播；② 要不要吃 12 秒门控宽限；
     * ③ 要不要跳「开机直达直播」。</p>
     */
    private boolean mBootSession;

    /**
     * R124：本次直达的<b>目标</b>。
     *
     * <p>旧实现用「点播续播项（History）是否为空」表达「要不要续播」，于是直播直达只能靠
     * {@code ConfigEvent.BOOT} 直接 {@code LiveActivity.start()} 旁路出去 —— 没有闸门、没有去重，
     * 还会把运行期的直播配置重载也当成开机。改成显式的目标后，
     * 「点播还是直播」由这里<b>二选一</b>，两者天然互斥。</p>
     */
    private enum BootTarget { NONE, VOD, LIVE }

    private BootTarget mBootTarget = BootTarget.NONE;

    /** R124：直达只跳一次（直播配置一次开机里会被解析两次 ⇒ 旧实现会跳两次）。 */
    private boolean bootDirectFired;

    /** R124：本次页面创建时刻（elapsedRealtime），用于「系统重复投递 HOME」的窄窗口判定。 */
    private long mCreatedAt;

    /**
     * R124：「系统重复投递 HOME 意图」的保护窗口（毫秒）。
     *
     * <p><b>为什么需要这个窗口</b>：默认桌面形态下，系统开机投递的 HOME 意图与用户按主页键
     * 投递的 HOME 意图<b>完全一样</b>，无法从 intent 区分。唯一的差别是时机：系统那一次发生在
     * 开机后的几秒内，用户那一次发生在他主动操作时。</p>
     *
     * <p>所以只有「页面刚创建不久 + 用户还没操作 + 还有待跳的直达」时才把新的 HOME 意图
     * 当成「系统重复投递」而不清掉进行中的续播。窗口取 10 秒 —— 足够覆盖 ROM 的重复 resolve，
     * 又足够窄，不至于把用户开机后按主页键误判成重复投递。</p>
     */
    private static final long REPEAT_HOME_WINDOW_MS = 10_000L;

    /**
     * 开机阶段窗口（R124 已迁到 {@link BootSession}）：原值是「{@code elapsedRealtime} < 90 秒」，
     * 而 {@code elapsedRealtime} <b>包含深睡</b> ⇒ 老人按遥控器开机（待机唤醒）时必然判错。
     * 新判据见 {@link BootSession#inBootPhase()}：<b>进程启动时刻</b>是否落在开机 5 分钟内。
     */

    /**
     * 门控宽限上限（用户 2026-11-06 定案 12 秒）：开机阶段冷启动时，即便「有缓存」也先留在
     * 启动画面「正在连接网络…」，每 {@link #GATE_RETRY_STEP_MS} 重试一次配置，
     * 任一次成功立刻进首页；12 秒仍未成功才进首页（有缓存显示内容，没内容显示空态卡）。
     *
     * <p>⛔ 只在「开机阶段 + 冷启动」生效：日常打开软件 / 待机唤醒 / 同进程重建一律 0 等待，
     * 不能为了开机几秒钟的体感把 R62「别让老人干等」的成果退回去。</p>
     */
    private static final long GATE_GRACE_MS = 12_000L;

    /** 门控期的重试间隔：短间隔，因为此时老人正盯着「正在连接网络…」看。 */
    private static final long GATE_RETRY_STEP_MS = 3_000L;

    /**
     * 门控结束后的退避重试间隔（R123 #3 的真修复）。
     *
     * <p>原来只有 {@code NetAwait.whenReady} 一种等待：<b>「网络从无到有」</b>。
     * 一旦系统说「已联网」（开机早期 VALIDATED 先于 DNS 就绪是常态），就什么监听都不挂 ⇒
     * 首页空态卡写着「网络好了会自动重试」，实际永远不会重试。现在补一条「有网但这次没成功」
     * 的重试链：10 → 30 → 60 → 120 秒，封顶后按 120 秒一直重试（直到页面销毁 / 成功），
     * 让那句文案第一次真正兑现。</p>
     */
    private static final long[] SOURCE_RETRY_STEPS = {10_000L, 30_000L, 60_000L, 120_000L};

    /**
     * 无网时「兜底轮询」的固定间隔（R129）。
     *
     * <p>R129 当时 {@code NetAwait#isOnline()} 还要求 {@code NET_CAPABILITY_VALIDATED}，而国内
     * 相当多电视/盒子无法完成系统的联网校验 ⇒ {@code whenReady} 与守卫<b>一起</b>恒 false，
     * 于是 {@link #armSourceRetry()} 只挂 {@code whenReady} 就 return ⇒ 首次导入源之后一直转圈、
     * 永不自愈。</p>
     *
     * <p>⛔ R131 已删掉那条 VALIDATED 判据（见 {@code NetAwait#isOnline()}），{@code whenReady}
     * 恢复正常。这条轮询<b>仍然保留</b>：它兜的是另一半 —— 网络能力回调压根不上报
     * （{@code onCapabilitiesChanged} 收不到）或回调早于监听注册的机型，那是「等事件」永远
     * 覆盖不到的场景，只有定时真试一次才行。</p>
     */
    private static final long RESILIENT_RETRY_MS = 20_000L;

    /**
     * 无网等待上限（R129）：还停在启动画面、数据源为空、又迟迟等不到网络时，到点先放行进首页
     * （空态卡自带「重试」与「去设置」），不让老人对着一个永不结束的转圈发呆。
     */
    private static final long BOOT_WAIT_MAX_MS = 30_000L;

    /** 退避重试走到的档位（成功后清零）。 */
    private int sourceRetryStep;

    /** 门控宽限是否已经用尽（用尽后不再拦首页，但仍继续后台退避重试）。 */
    private boolean gateExpired;

    /**
     * 宽限的<b>绝对</b>截止时刻（elapsedRealtime；0 = 还没开始）。
     *
     * <p>⛔ 必须是绝对时刻，不能每次 {@code holdGate()} 都重新 {@code post(12s)}：
     * 门控期每 3 秒失败一次就会重新进一次 {@code evaluateBoot()}，若每次都把 12 秒重新计时，
     * 定时器会被无限续期 —— 门控永远不放行，老人被锁在启动画面里。</p>
     */
    private long gateDeadline;

    /**
     * 本次进程里配置<b>真的</b>通过网络拉成功过（R123）。
     * 缓存重建（{@code loadFromCache}）刻意不算 —— 它只能证明「硬盘上有旧配置」，
     * 而门控要回答的是「这次开机，数据源到底通没通」。
     */
    private boolean configLoadedThisRun;

    /** 出口④ 第一次开始等待网络的时刻（0 = 未在等），用于 {@link #BOOT_WAIT_MAX_MS} 超时兜底。 */
    private long bootWaitStart;

    private final Runnable mSourceRetry = this::runSourceRetry;

    /**
     * 门控宽限到点：放行首页（不改变后台重试）。
     *
     * <p>⛔ 用方法引用而不是直接写 lambda：lambda 里要读 {@code mBinding}，而它声明在本段字段之后
     * —— Java 的「非法前向引用」规则不允许在字段初始化式里直接引用后面声明的实例字段（编译期就报错）。
     * 方法引用只受「方法已存在」约束，不受此限。</p>
     */
    private final Runnable mGateTimeout = this::onGateTimeout;

    private ActivityHomeBinding mBinding;
    private ArrayObjectAdapter mHistoryAdapter;
    private ArrayObjectAdapter mFuncAdapter;
    private ArrayObjectAdapter mChipAdapter;
    private ArrayObjectAdapter mAdapter;
    /**
     * 功能区（首页列表的第 0 行：搜索 / 直播 / 历史 / 收藏 / 设置 五个大按钮）。
     *
     * <p>2026-10-29（R100）新增：它是「冷启动 / 退出后重进首页」的**落焦锚点**。
     * 之前靠 {@code recycler.requestFocus()} 隐式落焦，结果落点由 Leanback 的默认规则决定，
     * 实测会滑到下面的分类 chips 行（表现为「一进首页就停在『推荐』上」）。</p>
     */
    private ListRow mFuncRow;
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
        // R124：开机会话期间系统可能重复投递 HOME 意图（部分 ROM 开机时会多次 resolve 桌面）。
        //   那不是「用户按了主页键」：不能因此把场景降成 WARM_HOME，更不能把进行中的续播清掉。
        //   ⚠ 默认桌面形态下「系统开机投递的 HOME」与「用户按主页键投递的 HOME」在 intent 上完全一样，
        //   只能靠时机与状态区分：① 页面刚创建 10 秒内；② 用户还没按过任何键；③ 还有待跳的直达
        //   且还没跳过。三条同时成立才当作「系统重复投递」，否则按「用户按主页键」处理（取消续播）。
        boolean repeatHome = isHomeIntent(intent)
                && mBootSession
                && !intent.getBooleanExtra(EXTRA_GUARD_PULL, false)
                && !bootInteracted
                && !bootDirectFired
                && mBootTarget != BootTarget.NONE
                && SystemClock.elapsedRealtime() - mCreatedAt < REPEAT_HOME_WINDOW_MS;
        // ⛔ R123：热启动也要重新定性 —— 同一个实例可能先被图标拉起（COLD_*），
        //   之后又被「主页键」投递（WARM_HOME）⇒ 不更新就会拿上一次的场景去判续播。
        if (!repeatHome) classifyLaunch(intent, true, false);
        // 回退标记必须在这里消费掉：实例复用不会走 checkBootDirect，留着的标记会污染下一次真正冷启动。
        boolean backHome = consumeBackHome() || intent.getBooleanExtra(EXTRA_BACK_HOME, false);
        consumeInternalRecreate();
        checkAction(intent);
        // 从播放页 / 设置页返回首页走 CLEAR_TOP 复用，<b>不会</b>再走 onCreate → initConfig()。
        // 若启动时配置就因无网络失败，首页会一直是空状态且永不恢复 —— 这里补检一次：
        // 站点列表为空（= 配置没加载成功）且现在网络可用，就重载配置并刷新首页。
        // 仅在「确实没加载成功」时触发，正常返回首页（有站点）零开销、不闪。
        // ⛔ 2026-10-29（R99）追加 10 秒节流：本方法在「播放页返回 / 设置页返回 / 桌面拉起」
        // 都会跑一遍，站点为空时每次都发一轮配置请求 ⇒ 反复进出会短时间内叠出多轮并发拉取，
        // 而 BaseConfig#load 每轮都会 cancel 上一轮的 future、taskId 自增 ⇒ 自己打断自己，
        // 反而更容易「拉一半失败」并显示空态。节流后最坏只是「晚 10 秒补检一次」，
        // 且失败后还有 configAwait（网络恢复即重载）兜底，不丢恢复能力。
        if (VodConfig.get().getSites().isEmpty() && NetAwait.isOnline() && canRecheckConfig()) initConfig();
        // 不是「从播放页/设置页返回首页」= 用户在软件内重新打开（图标 / 遥控器自定义按键 / 桌面拉起）。
        // 按定下的两层语义：只要软件还活着就允许续播，所以重新判定一次开机直达。
        if (!backHome && !repeatHome) {
            resetBootDirectState();
            bootDirectDone = false;
            checkBootDirect(true);
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
        // ⛔ R123：启动场景必须在**第一次裁决之前**定下来 —— 门控宽限、点播续播两处判据都读它。
        //   warm 参与判定：同进程重建（切主题/转屏/字号）既不是冷启动，也不是开机拉起，
        //   不能因为「恰好还在开机 90 秒内」就让它吃 12 秒门控宽限（那是纯误伤）。
        classifyLaunch(getIntent(), false, warm);
        if (VodConfig.get().loadFromCache()) evaluateBoot();
        if (warm) {
            WallConfig.get().init();   // 壁纸配置是本地的（读偏好 + 读文件），重建后重建内存态即可
            // ⛔ 2026-11-05（R107）：warm 重建不重载配置，但整页启动画面只有 showContent() 能收
            //   （它挂在配置加载成功的回调里），分类 chip 的高亮与焦点落点又依赖 getVideo()。
            //   这里补一次，走 RefreshEvent.home() → getVideo() 的完整链路：改完字号 / 图片尺寸
            //   回到首页时，启动画面收掉、焦点落回、内容与 chip 高亮一致。
            //   代价是内容重拉一次（约 1-2 秒），换来的是不再重载整份配置 JSON。
            App.post(() -> RefreshEvent.home(), 100);
        } else {
            initConfig();
        }
        // ⛔ 2026-10-29（R100）：这里原本调 setLogo()，即用「数据源配置里的 logo」覆盖软件图标。
        //   现在整块删掉 —— 首页标题区**恒显示软件自己的 logo**（activity_home.xml 里的
        //   android:src="@drawable/ic_logo"），代码里再没有任何地方写这个 ImageView，
        //   与「标题恒为 @string/app_name」是同一个口径：标题区三件套（logo + 软件名 + 时间）
        //   与数据源、网络状态完全无关。
        //   更换 logo 的唯一途径 = 换 ic_logo 资源，不需要也不允许运行时按配置切换。
        // 开机直达的资格判定放在这里，而不是等 showContent() 之后再判 ——
        // 现在「数据源没就绪」时首页会保持启动画面继续等，showContent() 可能很晚才被调用；
        // 而续播应当从进入首页那一刻就开始并行等（先等网络、再等数据源），不能拖到那时才起步。
        checkBootDirect(false);
        // R124：记下页面创建时刻（重复 HOME 的窄窗口判定 + 门控宽限起算点都要用）。
        mCreatedAt = SystemClock.elapsedRealtime();
        // R124：门控宽限从「页面创建」起算，而不是从第一次裁决起算 ——
        //   否则慢机型上老人要等 20~32 秒（配置加载慢 + 12 秒宽限叠加）。
        gateDeadline = SystemClock.elapsedRealtime() + GATE_GRACE_MS;
    }

    @Override
    protected void initEvent() {
        // ⚠ 2026-10-17 取消标题的点击注册：标题与头像一律**纯装饰、无功能**。
        // 换源入口全站唯一 = 「设置 → 长辈设置 → 一键锁定」（ElderLockActivity，入口见 ElderSettingsActivity）。
        // ⛔ 2026-10-29（R99）：标题已退化为纯 MaterialTextView（CustomTitleView 整个类已删除），
        //   连「可注册点击」这个能力都不存在了 —— 标题从此只有展示职责。
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                // ⛔⛔ 2026-10-29（R99）：删掉 `toolbar.setVisibility(position == 0 ? VISIBLE : GONE)`。
                //   原逻辑「焦点在第 0 行才显示标题栏」是「无数据源 / 无网络时标题时间区域消失」的
                //   第二个根因：那两种情况下首行不再是功能区行（是空态卡或 chips 行）⇒ position 永远
                //   不为 0 ⇒ 标题栏被永久隐藏。标题栏现在**恒显示**，不随选中行变化。
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
        // 行间距走 Product.gap(this)：取「本页 Resources」的密度（BaseActivity#hackResources 保证的那一份），
        // 与 Product.getSpec 里按 16dp×(列数−1) 预留的间距同源。⛔ 不要用 ResUtil.dp2px：
        // 它走 Application 侧 Resources，历史上会被 AndroidAutoSize 覆写成「屏宽/1920」
        // （不含字号档位与大屏补偿），造成首屏行间距与其他组件不同源。
        mBinding.recycler.setVerticalSpacing(Product.gap(this));
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
                else {
                    // R132：内容级有限自动重试 —— 配置成功但站点内容请求失败（开机头几秒
                    // DNS / 站点服务器未就绪很常见）时，旧逻辑只显示空态卡，重试全靠手动点按；
                    // 配置层的 armSourceRetry 只在配置失败时才转，覆盖不到这里。
                    scheduleContentRetry();
                    showContentEmpty();
                }
            }
        } else {
            removeContentEmpty();
            contentRetryCount = 0;   // R132：拿到内容即清零，下次空态重新计满
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
        mAdapter.add(mFuncRow = new ListRow(mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this))));
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

    // ⛔⛔ 2026-10-29（R99）：原 setTitle() 已整体删除。它的取值链是
    //   getHome().getName() → getConfig().getName() → app_name ⇒ 标题会**随站源变动**
    //   （换源后标题变成站源名、无站源时变成配置名），与「永远只显示软件名」的产品口径冲突。
    //   现在标题恒为 XML 里的 @string/app_name（activity_home.xml），**代码不再写它**。
    //   随之删除的私方法：getConfig()（唯一调用点就是 setTitle()）。

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    /**
     * {@link #onNewIntent()} 里「站点为空就补检一次配置」的节流闸（见
     * {@link #CONFIG_RECHECK_INTERVAL}）。
     *
     * @return true = 距离上次补检已超过间隔（或从未补检过），本次放行并记账
     */
    private boolean canRecheckConfig() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastConfigRecheckAt < CONFIG_RECHECK_INTERVAL) return false;
        lastConfigRecheckAt = now;
        return true;
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                // 配置加载成功 —— 注意这不等于「网络通」：本地导入的源、缓存里已有的源，
                // 在完全没网时同样会走到这里（判据 VodConfig#isLoaded() 只看站点列表是否非空）。
                // 所以「转圈后直接进首页」在无网时出现是正确行为，不是漏判网络。
                configFailed = false;
                // R123：标记「本次真的拉成功过」——门控宽限的放行条件之一；
                // 同时把退避档位清零，下一轮（换源 / 断网）从最短间隔重新开始。
                configLoadedThisRun = true;
                sourceRetryStep = 0;
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
                //   ① armSourceRetry()：网络恢复 / 退避到点后自动重载配置 → ConfigEvent.VOD →
                //      getVideo() 刷新首页。⛔ 这不是「再判一次网络决定进退」（原注释禁止的那种污染）：
                //      它内部的 isOnline 只是「选哪种等待形态」，有网走退避、没网走事件驱动，
                //      两条都不会形成「失败→立即重载→又失败」的忙循环。
                //   ② awaitBootDirect()：与出口①对称（P4），让续播第二级仍有驱动。
                if (!mBinding.progressLayout.isProgress()) {
                    armSourceRetry();
                    awaitBootDirect();
                    return;
                }
                evaluateBoot();
            }
        };
    }

    /**
     * 启动门控的<b>唯一收敛点</b>（2026-10-13 定案，R123 加「开机宽限」）。
     *
     * <p><b>两个原始判据互相正交</b>：</p>
     * <ul>
     *   <li>{@code S = !VodConfig.get().getSites().isEmpty()} —— 数据源就绪，<b>与网络无关</b>
     *       （本地导入的源、缓存里已有的源，没网也是就绪的）；</li>
     *   <li>{@code N = NetAwait.isOnline()} —— 网络就绪，只在「数据源还没就绪」时决定是等、还是放弃。</li>
     * </ul>
     *
     * <p>⛔⛔ <b>R123 补的第三个维度：这次是不是「开机阶段的一次冷启动」</b>（{@link #gateHold()}）。
     * 原实现只有 S / N 两个维度，而 {@code S} 会被 {@code loadFromCache()} 顶成 true ⇒
     * 只要硬盘上有旧配置，门控就<b>瞬间放行</b>，{@code bootHint}「正在连接网络…」永远没机会出现。
     * 电视开机时的真实体感是：「一开机就亮出首页 → 内容区一张『暂时连接不上』的空态卡」，
     * 正是老人最怕的「坏了的脸」。现在开机阶段冷启动额外要求「本次真的拉成功过」
     * （{@link #configLoadedThisRun}），否则先留在启动画面最多 {@link #GATE_GRACE_MS} 秒，
     * 每 3 秒重试一次，成功即进首页。</p>
     *
     * <p><b>四个出口，语义互不重叠</b>：</p>
     * <ol>
     *   <li>{@code S && 已放行} → 进首页（正常）。「有缓存 + 非开机阶段」走的正是这条，
     *       日常打开软件 / 待机唤醒 / 同进程重建都是 0 等待，R62 的成果一点没退；</li>
     *   <li>{@code !S && N && 无需宽限} → 进首页 + 空态卡。有网仍拿不到数据源 = 数据源侧的问题，
     *       <b>不能把老人锁死在转圈里</b>；但<b>后台退避重试照挂</b>（见 {@link #armSourceRetry()}），
     *       空态卡那句「网络好了会自动重试」从此是真的；</li>
     *   <li>{@code !S && !N && 无需宽限} → 留在启动画面显示「正在连接网络…」，挂网络恢复监听；</li>
     *   <li>{@code 需要宽限}（开机阶段冷启动、本次还没真拉成功）→ 留在启动画面，
     *       显示「正在连接网络…」+ 每 3 秒重试，最长 12 秒；到点后按上面三条出口重新裁决。</li>
     * </ol>
     *
     * <p><b>防回退</b>：首行的 {@code isProgress()} 守卫保证「一旦进过首页就永不再回退」，
     * 因此换源、返回首页等场景重复触发本方法也不会把页面打回启动画面。</p>
     */
    private void evaluateBoot() {
        if (!mBinding.progressLayout.isProgress()) return;
        cancelConfigAwait();
        // ⓪ 开机宽限：只要还在「开机阶段 + 冷启动 + 本次没真拉成功」，一律先留在启动画面。
        //    注意它排在 S 判定之前 —— 这就是 R123 的关键：缓存不再等于放行。
        if (gateHold()) {
            holdGate();
            return;
        }
        if (!VodConfig.get().getSites().isEmpty()) {
            // R124：直达目标已经就绪时，「收启动画面（不设焦点）+ 立即跳」——
            //   全程只有一次画面变化，老人不会看到「首页闪一下再跳走」。
            //   ⚠ 不能先跳再收：progressLayout 没收到内容态，用户按返回回来看到的是启动画面。
            if (tryFireBootDirect()) return;
            showContent();
            awaitBootDirect();
            return;
        }
        if (NetAwait.isOnline()) {
            showContent();
            // 出口② 的补丁：有网却拿不到源，照样挂后台退避重试。
            armSourceRetry();
            return;
        }
        // 出口④：数据源没就绪、也还没网。
        // R129：等太久就先放行进首页 —— 空态卡会给出「暂时连接不上 + 重试」，
        //   比让老人对着一个永不结束的转圈强。R131 前的极端成因是「固件 VALIDATED 恒 false、
        //   网络判据永不放行」（已随 isOnline 放宽而消除）；现在它兜的是「真没网 / DNS 坏」
        //   —— 这两种 whenReady 同样等不到，到点放行 + 空态卡重试仍是唯一体面出路。
        if (prepareBootWaitOverflow()) {
            showContent();
            armSourceRetry();
            return;
        }
        showBootHint();
        armSourceRetry();
    }

    /**
     * 出口④ 的等待超时判定：首次进来记时间，超过 {@link #BOOT_WAIT_MAX_MS} 返回 true 并清零。
     */
    private boolean prepareBootWaitOverflow() {
        long now = SystemClock.elapsedRealtime();
        if (bootWaitStart == 0L) {
            bootWaitStart = now;
            return false;
        }
        if (now - bootWaitStart < BOOT_WAIT_MAX_MS) return false;
        bootWaitStart = 0L;
        return true;
    }

    /**
     * 是否要吃「开机宽限」：开机阶段的一次冷启动，且本次还没真正把配置拉成功过。
     *
     * <p>四条同时成立才算：① 启动场景是 {@link LaunchKind#COLD_BOOT_DESKTOP}/{@link LaunchKind#COLD_BOOT}；
     * ② 本次配置还没真拉成功；③ 宽限没用尽；④ 当前确实还停在启动画面。</p>
     *
     * <p>⛔ 明确排除的三种情况：日常打开（{@link LaunchKind#COLD_NORMAL}）、热启动（WARM_*）、
     * 同进程重建（分类时归到 COLD_NORMAL）—— 它们都在开机窗口之外或内存态完好，不该多等一毫秒。</p>
     */
    private boolean gateHold() {
        if (gateExpired) return false;
        if (configLoadedThisRun) return false;
        if (!mLaunchKind.isBootCold()) return false;
        return mBinding.progressLayout.isProgress();
    }

    /** 进入宽限等待：显示「正在连接网络…」、每 3 秒重试一次、12 秒后到点放行（幂等）。 */
    private void holdGate() {
        showBootHint();
        armSourceRetry();
        if (gateExpired) return;
        long now = SystemClock.elapsedRealtime();
        if (gateDeadline == 0L) gateDeadline = now + GATE_GRACE_MS;
        long left = gateDeadline - now;
        if (left <= 0) {
            gateExpired = true;
            showContent();
            return;
        }
        // 只按「剩余时间」重新排队：App.post 内部先 removeCallbacks 再 post，
        // 所以重复调用不会叠出多个定时器，也不会把 12 秒无限续期。
        App.post(mGateTimeout, left);
    }

    /** 门控宽限到点：放行首页（不改变后台重试）。 */
    private void onGateTimeout() {
        gateExpired = true;
        if (!mBinding.progressLayout.isProgress()) return;
        showContent();
    }

    /**
     * 本进程是否仍处在「开机阶段」。
     *
     * <p>R124：判据已从「{@code elapsedRealtime < 90 秒}」换成「<b>进程启动时刻</b>在开机 5 分钟内」
     * （见 {@link BootSession#inBootPhase()}）。旧判据在「待机唤醒」这一中国老人最常见的开机方式上
     * 必然判错 —— {@code elapsedRealtime} 包含深睡，第二天早上它早就过了 90 秒。</p>
     */
    private static boolean inBootPhase() {
        return BootSession.inBootPhase();
    }

    /** 是否是「系统把本应用当默认桌面拉起」的 HOME 意图。 */
    private static boolean isHomeIntent(Intent intent) {
        return intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME);
    }

    /**
     * 给本次界面定性（R123）：冷启动 / 热启动 / 开机默认桌面 / 日常打开。
     *
     * @param intent 触发本次启动的意图
     * @param reuse  true = 来自 {@code onNewIntent}（进程还活着，实例复用）
     * @param warm   true = 内存里已有配置（同进程重建：切主题 / 转屏 / 字号档位）
     */
    private void classifyLaunch(Intent intent, boolean reuse, boolean warm) {
        boolean home = isHomeIntent(intent);
        // R124：系统/自启拉起（{@code BootLauncher}）会打这个标记，用的是不带 HOME 的普通 Intent。
        boolean bootLaunch = intent != null && intent.getBooleanExtra(EXTRA_BOOT_LAUNCH, false);
        if (reuse) {
            mLaunchKind = home ? LaunchKind.WARM_HOME : LaunchKind.WARM_REOPEN;
        } else if (warm) {
            // 同进程重建：既不是冷启动也不是开机拉起，硬归 COLD_NORMAL（零等待、可续播）
            mLaunchKind = LaunchKind.COLD_NORMAL;
        } else if (bootLaunch || home) {
            // R124：只有「系统/自启拉起」与「默认桌面（HOME）」才是开机会话。
            //   图标 / 遥控器快捷键启动一律 COLD_NORMAL —— 即便恰好落在开机 5 分钟内，
            //   也不能让老人点一下图标也吃 12 秒门控宽限。
            //   HOME 且不在开机窗口 = 进程刚被系统回收、用户按主页键让系统新建了实例
            //   （WARM_HOME 语义：用户只是想回桌面，不能把他弹进上次那部剧里）。
            //   R134 例外：若持久化快照证明这是「待机唤醒」后的冷启动，mBootSession 会另行置 true
            //   （见下方赋值处）—— LaunchKind 仍是 WARM_HOME（不吃门控宽限），但直达允许。
            if (inBootPhase()) mLaunchKind = home ? LaunchKind.COLD_BOOT_DESKTOP : LaunchKind.COLD_BOOT;
            else mLaunchKind = home ? LaunchKind.WARM_HOME : LaunchKind.COLD_NORMAL;
        } else {
            mLaunchKind = LaunchKind.COLD_NORMAL;
        }
        // R124：「本次是不是开机会话」与 LaunchKind 分开记 —— 唤醒会话没有新 intent（只能由 onResume 置位），
        //   而 onNewIntent 复用实例时不能把它清掉（系统在开机时可能重复投递 HOME 意图）。
        // R134：唤醒会话也算开机会话 —— 快速待机（<10 分钟）杀进程后的唤醒冷启动走不到
        //   inBootPhase()（早已过开机 5 分钟窗口），但持久化的熄屏/亮屏快照能证明这是唤醒。
        //   ⚠ 唤醒资格用掉即清（consumeWake）：否则随后的 onResume isWakeSession 分支会再 rearm 一次。
        //   inBootPhase() 命中时不清 —— 那是开机窗口内的普通冷启动，唤醒标记与它无关。
        if (!reuse && !warm && (bootLaunch || home)) {
            mBootSession = inBootPhase() || BootSession.isWakeSession();
            if (mBootSession && !inBootPhase()) BootSession.consumeWake();
        }
    }

    /**
     * 统一的「配置没拉成功就自己再试」调度器（R123：替换原先只认「网络从无到有」的
     * {@code awaitConfigNetwork()}）。
     *
     * <p><b>为什么原来的实现等于没有重试</b>：它第一行就是 {@code if (NetAwait.isOnline()) return;}
     * —— 只处理「网络从无到有」。而开机早期最常见的状态恰恰是「系统说已联网（VALIDATED）、
     * 但 DNS / 首包还没就绪」，于是它<b>什么都不挂</b>就返回了；首页空态卡那句
     * 「网络好了会自动重试」从来没有任何代码在兑现。</p>
     *
     * <p><b>现在两种形态二选一</b>：</p>
     * <ul>
     *   <li>真没网 → 仍用 {@link NetAwait#whenReady}（事件驱动，不通就不动，不会耗电空转）；</li>
     *   <li>有网但这次失败 → 退避重试 10 / 30 / 60 / 120 秒（封顶后每 120 秒一次），
     *       直到成功或页面销毁。间隔足够长，不会形成「失败 → 立即重载 → 又失败」的忙循环。</li>
     * </ul>
     *
     * <p>门控期（还停在启动画面）用 {@link #GATE_RETRY_STEP_MS} 的短间隔，因为老人正盯着看。</p>
     */
    private void armSourceRetry() {
        cancelConfigAwait();
        if (isFinishing() || isDestroyed()) return;
        if (!NetAwait.isOnline()) {
            // 真没网：事件驱动。网络一通立即重载，且把退避档位清零（下一轮从最短间隔重新开始）。
            sourceRetryStep = 0;
            configAwait = NetAwait.whenReady(0L, this::reloadConfig, () -> {
            });
            // R129：再挂一条固定间隔的兜底轮询。R131 之前是因为 whenReady 依赖 VALIDATED
            //   （坏固件恒 false ⇒ 只靠它永远不触发）；R131 已删该判据，本条改为兜「网络能力
            //   回调压根不上报 / 回调早于监听注册」的机型 —— 保证仍会周期性真的试一次。
            App.post(mSourceRetry, RESILIENT_RETRY_MS);
            return;
        }
        App.post(mSourceRetry, retryDelay());
    }

    /** 下一次重试间隔：门控期固定 3 秒；门控结束后按退避阶梯，封顶 120 秒。 */
    private long retryDelay() {
        if (mBinding.progressLayout.isProgress()) return GATE_RETRY_STEP_MS;
        int index = Math.min(sourceRetryStep, SOURCE_RETRY_STEPS.length - 1);
        return SOURCE_RETRY_STEPS[index];
    }

    /** 退避定时器到点：先加一档，再判网络是否可用，可用就真去重载。 */
    private void runSourceRetry() {
        if (isFinishing() || isDestroyed()) return;
        sourceRetryStep++;
        // R129：不再用 isOnline() 二次拦截 —— R131 前它要求 VALIDATED、坏固件上恒 false
        //   ⇒ 每次都在这里退回、永远不真正重试；R131 后判据虽已放宽，这里**仍不**二次拦截：
        //   真没网时请求自己会失败（OkHttp 30s 超时），失败后又回到 armSourceRetry 的
        //   兜底轮询，间隔固定 20s，不会形成忙循环。
        reloadConfig();
    }

    /**
     * 网络恢复 / 退避到点后的重载。
     *
     * <p>门控期（还停在启动画面）只重试点播配置 —— 每 3 秒把直播配置、壁纸配置也全量拉一遍
     * 是纯浪费，还会让 {@code ConfigEvent.COMMON} 反复刷新功能区。进了首页之后再走全量。</p>
     */
    private void reloadConfig() {
        configAwait = NetAwait.Handle.NONE;
        if (isFinishing() || isDestroyed()) return;
        if (mBinding.progressLayout.isProgress()) {
            VodConfig.get().init().load(getCallback());
            // R124：门控期也要把直播配置拉回来 —— 直播直达依赖 LiveConfig 就绪，
            //   而 R123 的门控期只重试点播配置 ⇒ 无网开机超过 12 秒后，直播配置永远拿不到，直达必失效。
            //   ⚠ 必须先判「还没就绪」：LiveConfig#load() 没有 isLoaded 守卫，每次调用都会重新下载一遍。
            if (LiveConfig.get().getHome().getGroups().isEmpty()) LiveConfig.get().init().load();
            return;
        }
        // 配置成功后 success() 会 showContent()；失败则 error() 再判一次 —— 有网了就不再挂，
        // 仍是没网才重新挂上，形成「断网就等、通了就试一次」的闭环，不会忙循环。
        initConfig();
    }

    private void cancelConfigAwait() {
        configAwait.cancel();
        configAwait = NetAwait.Handle.NONE;
        // R123：退避定时器必须一起撤销，否则「成功之后又冒一次重载」「销毁后回调已死的 Activity」。
        App.removeCallbacks(mSourceRetry);
    }

    /** 切到「内容态」（结束启动画面）。开机直达的判定不在这里 —— 见 {@code initView} 末尾的说明。 */
    private void showContent() {
        showContentSilent();
        checkAction(getIntent());
        setFocus();
    }

    /**
     * 只收启动画面，<b>不设焦点</b>（R124）。
     *
     * <p>直达跳转前用它：焦点框在首页上闪一下再跳走，是老人最反感的那种「画面乱动」。</p>
     */
    private void showContentSilent() {
        mBinding.progressLayout.showContent();
        hideBootHint();
    }

    /**
     * 直达目标已经就绪时：收启动画面（不设焦点）并立即跳转。
     *
     * @return true = 已经跳转（调用方不要再 {@code showContent()}，否则会再闪一次）
     */
    private boolean tryFireBootDirect() {
        if (mBootTarget == BootTarget.NONE || bootInteracted) return false;
        if (isFinishing() || isDestroyed()) return false;
        if (!isBootDirectReady()) return false;
        showContentSilent();
        fireBootDirect();
        return true;
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
    private void checkBootDirect(boolean reuse) {
        if (bootDirectDone) return;
        bootDirectDone = true;
        // 两个瞬时标记必须先消费再判断：若门控提前 return，标记会残留到下一次冷启动，误伤直达。
        if (consumeBackHome()) return;
        if (consumeInternalRecreate()) return;
        // R125（P0）：**直播分支必须排在「点播续播关闭」早退之前**。
        //   两个开关在设置页是互斥的（见 ElderSettingsActivity#setBootLive / #setAutoResume），
        //   「直播直达开」必然意味着「点播续播关」—— 而旧代码把 !isAutoResume() 的早退放在前面，
        //   于是直播分支<b>永远走不到</b>（100% 不可达）：开关打开的效果只是「把点播续播关掉」，
        //   直播页一次也不会跳。这正是「设成默认桌面后直播开机直达好像有问题」的直接根因。
        //   现在直播优先：两个开关若因历史脏数据同时为真，以直播为准（直播是更具体的那一个）。
        if (Setting.isBootLive()) {
            // 与点播续播同一套「普通启动」判据：排除回退 / 内部重建 / 桌面守护拉回 / 带 data / 分享 / 搜索。
            //   HOME 意图在 isPlainLaunch 里另有约束（必须是开机会话）⇒ 用户按主页键回桌面不会抢屏。
            if (!isPlainLaunch(getIntent(), reuse)) return;
            mBootTarget = BootTarget.LIVE;
            awaitBootDirect();
            return;
        }
        // 退出自动续播关闭：不再自动跳入上次观看的节目，落在首页
        if (!Setting.isAutoResume()) return;
        // ⛔ R123：是否「普通启动」必须带上下文 —— 冷启动的 HOME 是「开机把桌面拉起来」，
        //   热启动的 HOME 是「用户按主页键回来」，两者对续播的期望正好相反。见 isPlainLaunch 注释。
        if (!isPlainLaunch(getIntent(), reuse)) return;
        List<History> items = History.get();
        if (items.isEmpty()) return;
        History item = items.get(0);
        if (TextUtils.isEmpty(item.getVodId())) return;
        // 只登记「本次应当续播」；什么时候跳、能不能跳，交给两级门控。
        mBootDirect = item;
        mBootTarget = BootTarget.VOD;
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
        // R124：主控从「待续播项」换成「直达目标」—— 直播目标没有 History，只有目标本身。
        if (mBootTarget == BootTarget.NONE) return;
        // 老人已经在操作了 → 彻底放弃本次抢屏（清掉待续播项，之后配置成功也不会再被拉起）。
        if (bootInteracted) {
            clearBootDirect();
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
     * 第二级的 {@code isBootDirectReady()} 当时还要求 {@code NET_CAPABILITY_VALIDATED}
     * —— 开机头几秒 available 已 true 但 VALIDATED 还没就绪是常态 ⇒ 那一次必然判 false，
     * 而 {@code onReady} 只触发一次、{@code success()} 又会被 isProgress 守卫挡掉
     * ⇒ <b>慢网开机续播被静默吞掉</b>（同一台电视有时播、有时不播）。</p>
     *
     * <p>⛔ R131：那条 VALIDATED 判据已从 {@code isBootDirectReady()} 删除（国内固件恒 false
     * ⇒ 续播/直达 100% 不触发，比「慢网吞一次」严重得多）。本方法的有限重试<b>仍然保留</b>：
     * 它兜的是「配置还没拉完」这一级（站点列表为空 / 历史 siteKey 缺失），与网络判据无关。</p>
     *
     * <p>⛔ 这里**不能**再次调用 {@code NetAwait.whenReady} 来实现重试：它在「已认为联网」时
     * 会**立即**回调 ⇒ 递归自旋。改用固定 1 秒间隔的有限次轮询（最多
     * {@link #BOOT_DIRECT_MAX_RETRY} 次），超时就彻底放弃 —— 老人开机十几秒后还没就绪，
     * 就不该再抢屏了。</p>
     */
    private void tryBootDirect() {
        if (mBootTarget == BootTarget.NONE || bootInteracted) return;
        if (isFinishing() || isDestroyed()) return;
        if (isBootDirectReady()) {
            bootDirectRetry = 0;
            fireBootDirect();
            return;
        }
        // R132：直播目标未就绪时必须**真的重新拉配置** —— LiveConfig 只在 initConfig() 里
        // 加载一次且不带回调；BaseConfig#loadConfig 失败也会派发 ConfigEvent（finally 里），
        // 于是本方法会被推进，但旧逻辑只「复查」分组列表、从不重新加载 ⇒ 开机头几秒 DNS/网关
        // 没就绪导致首次加载失败时，15 次复查全是空转，直播直达必然失效（实测 100% 复现）。
        //   · 限频 {@link #LIVE_RELOAD_MIN_INTERVAL_MS}：不能把还在路上的下载反复取消；
        //   · BaseConfig#load 自带 taskId 去重（新一轮取消上一轮），不会叠加并发请求；
        //   · 就绪即停：fireBootDirect 消费目标后本方法首行即早退；用户一按键（bootInteracted）同样停。
        //   预算 {@link #LIVE_BOOT_DIRECT_MAX_RETRY} 比点播长：直达直播是明确要求的行为。
        if (mBootTarget == BootTarget.LIVE) {
            // R136：数据「根本不可得」时立即放弃，不再空转 30 秒：
            //   · 直播源从未配置（url 为空）——没有任何可加载的数据；
            //   · sync（直播配置与主数据源同源）：直播数据只能由主数据源 JSON 内嵌的 lives
            //     灌入（见 VodConfig#initLive），独立重拉会被 LiveConfig#load() 的 sync 早退吞掉，
            //     主数据源已加载完成仍无 lives ⇒ 永远等不到。
            //   「独立直播源 + 网络慢」不在此列：那种场景重拉有意义，仍走下面的限频重试预算。
            if (LiveConfig.isUnreachable()) {
                abandonLiveDirect();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (liveDirectLoadAt == 0L || now - liveDirectLoadAt >= LIVE_RELOAD_MIN_INTERVAL_MS) {
                liveDirectLoadAt = now;
                LiveConfig.get().init().load();
            }
            if (bootDirectRetry++ < LIVE_BOOT_DIRECT_MAX_RETRY) App.post(mBootDirectRetry, 1000);
                // R136：预算用尽不再静默——旧版这里自然停摆，老人只看到「没跳」，不知道发生了什么。
            else abandonLiveDirect();
            return;
        }
        if (bootDirectRetry++ < BOOT_DIRECT_MAX_RETRY) App.post(mBootDirectRetry, 1000);
    }

    /**
     * R136：直播直达的统一「放弃出口」——数据不可得（{@link LiveConfig#isUnreachable()}）
     * 或限频重试预算用尽时走这里：清掉等待与登记（clearBootDirect），并给出一次明确提示。
     * 旧版两处都静默：直播不跳、续播又被互斥开关关掉，老人两头落空且毫无线索。
     * 提示后落回首页；下次打开软件时重新判定。本方法<b>不降级续播</b>——两个开关互斥，
     * 「直播直达开」意味着用户明确选择了直播，擅自转跳点播违背设置。
     */
    private void abandonLiveDirect() {
        if (mBootTarget != BootTarget.LIVE) return;
        clearBootDirect();
        Notify.show(R.string.boot_live_unready);
    }

    /**
     * 开机续播的「放行」判据：三条全绿才允许跳转详情页。
     *
     * <p>第三条（该 siteKey 在当前配置里存在）是这轮补上的关键一环：老人换过数据源之后，
     * 历史记录里的 siteKey 可能已经不在新配置的站点列表里，此时即便网络与配置都正常，
     * 详情页依然取不到站点、依然会失败。少这一条就堵不住这个口子。</p>
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
        //   （R124 时以 groups 为等价公开表达式的推断对内嵌型源成立，对 URL 型源不成立。）
        if (mBootTarget == BootTarget.LIVE) return LiveConfig.hasData();
        // R125（P0）：**待续播项可能已被消费**（fireBootDirect 置空后 mBootTarget 还没清的旧逻辑，
        //   或 clearBootDirect 之后又被重新驱动）—— 这里必须先判空，否则 mBootDirect.getSiteKey() 直接 NPE。
        if (mBootDirect == null) return false;
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
        // R124：一次性。直播配置在一次开机里会被解析两次（冷启动 initConfig 一次 + BootReceiver 预加载一次），
        //   旧实现每次解析都发一次 BOOT 事件 ⇒ 直播页被 start 两次（singleTop 也会重进一次，表现为闪一下）。
        if (bootDirectFired) return;
        if (mBootTarget == BootTarget.NONE || bootInteracted) return;
        if (isFinishing() || isDestroyed()) return;
        bootDirectFired = true;
        bootDirectAwait.cancel();
        bootDirectAwait = NetAwait.Handle.NONE;
        // R125（P0）：**目标一次性消费**。旧逻辑只把 mBootDirect 置空、mBootTarget 仍留着 VOD，
        //   于是后续任何一次 awaitBootDirect / tryBootDirect 都会走到 isBootDirectReady()，
        //   在 mBootDirect == null 上崩（mBootDirect.getSiteKey() NPE）。
        //   现在发完就把目标清成 NONE：后续驱动自然早退，不会二次跳、也不会空指针。
        BootTarget target = mBootTarget;
        mBootTarget = BootTarget.NONE;
        if (target == BootTarget.LIVE) {
            LiveActivity.start(this);
            return;
        }
        History item = mBootDirect;
        mBootDirect = null;
        if (item == null) return;
        // siteKey 失效时用当前主站点打开（与 isBootDirectReady 的降级判据配套，与手机端同口径）
        String key = item.getSiteKey();
        if (VodConfig.get().getSite(key).isEmpty()) key = VodConfig.get().getHome().getKey();
        VideoActivity.start(this, key, item.getVodId(), item.getVodName(), item.getVodPic());
    }

    /** 重新判定开机直达前清掉上一轮的等待与进度标记（{@code onNewIntent} 会走到）。 */
    private void resetBootDirectState() {
        clearBootDirect();
    }

    /** 撤销一次直达的所有等待与登记（R124：新增「目标」与「已跳转」两个状态）。 */
    private void clearBootDirect() {
        bootDirectAwait.cancel();
        bootDirectAwait = NetAwait.Handle.NONE;
        App.removeCallbacks(mBootDirectRetry);
        bootDirectRetry = 0;
        liveDirectLoadAt = 0L;
        mBootDirect = null;
        bootNetReady = false;
        bootInteracted = false;
        mBootTarget = BootTarget.NONE;
        bootDirectFired = false;
    }

    /**
     * 唤醒会话：把直达状态清零并重新判定一次（R124）。
     *
     * <p>一次唤醒只跳一次：{@code bootDirectFired} 也要清零，否则上次开机已经跳过就再也不会跳。</p>
     */
    private void rearmBootDirect() {
        clearBootDirect();
        bootDirectDone = false;
        mBootSession = true;
        // 电视刚亮，用户还没按任何键（clearBootDirect 已把 bootInteracted 重置）。
        checkBootDirect(false);
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
     *
     * <p>⛔⛔ R123：<b>带 CATEGORY_HOME 的启动要分两种情况</b>。本树（标准版）默认不声明 HOME
     * intent-filter，所以这一分支平时不会命中；但两树这段判定必须同款 —— autostart 树声明了 HOME
     * （R101），系统开机就用 HOME 意图把它拉起来，旧代码一律 {@code return false} 导致
     * <b>默认桌面场景下点播开机直达永远不触发</b>（而直播直达走
     * {@code ConfigEvent.BOOT → LiveActivity.start()}，不经过本方法 ⇒ 「点播不生效、直播生效」）。</p>
     *
     * <ul>
     *   <li><b>冷启动 + 开机窗口内</b>（{@link LaunchKind#COLD_BOOT_DESKTOP}）→ 允许续播。
     *       这就是「开机自动进桌面，顺手接着上次那部剧」，正是产品要的；</li>
     *   <li><b>热启动</b>（{@link LaunchKind#WARM_HOME}，用户按主页键回来）→ 禁止续播。
     *       用户只是想回桌面，不能把他弹进详情页；</li>
     *   <li><b>冷启动但已过开机窗口</b>（进程刚被回收、用户按主页键让系统新建实例）→ 禁止。
     *       归到 WARM_HOME 语义，见 {@code classifyLaunch}。R134 例外：待机唤醒后的冷启动
     *       会在 classifyLaunch 里把 mBootSession 置 true，此处照样放行。</li>
     * </ul>
     *
     * <p>⚠ 只排除 HOME，不动 LAUNCHER：图标 / 遥控器快捷键启动（ACTION_MAIN + CATEGORY_LAUNCHER）
     * 仍然要续播，那是「用户主动打开软件」的正常语义。</p>
     *
     * @param reuse true = 来自 {@code onNewIntent}（进程还活着）。只有这种「按主页键回来」才拦 HOME。
     */
    private boolean isPlainLaunch(Intent intent, boolean reuse) {
        if (intent == null) return true;
        if (intent.getBooleanExtra(EXTRA_BACK_HOME, false)) return false;
        if (intent.getBooleanExtra(EXTRA_REFRESH, false)) return false;
        // R124：桌面守护把用户从别的应用拉回来 ⇒ 用户只想回首页，不该被弹进上次那部剧。
        if (intent.getBooleanExtra(EXTRA_GUARD_PULL, false)) return false;
        if (intent.getData() != null) return false;
        String action = intent.getAction();
        if (Intent.ACTION_MAIN.equals(action) && intent.hasCategory(Intent.CATEGORY_HOME)) {
            // R124：HOME（默认桌面形态）只有「本次是开机会话」才续播。
            //   开机会话 = 冷启动且进程落在开机窗口内（系统开机把桌面拉起来），或待机唤醒会话；
            //   其余（热启动 / 已过开机窗口）= 用户按主页键回桌面 ⇒ 不续播。
            //   ⚠ 判据用 mBootSession 而不是 mLaunchKind：唤醒会话没有新 intent，LaunchKind 还是旧的。
            return !reuse && mBootSession;
        }
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
     * （{@code getVideo()} + {@code getHistory(true)}，推荐区整体重建、历史行换新 presenter，
     * 封面尺寸在 {@code onCreateViewHolder} 里按当前档位现算），内容与尺寸都已刷新；再走一次 recreate 只会白丢焦点与滚动位置 —— 对老人来说
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
        // 会在 500ms 后把标题改成可聚焦，于是：
        //   ① 老人在内容区按「上」时焦点会跑到右上角标题上，而标题的焦点态是**闪烁动画**（不是
        //      全站统一的 6dp 描边）⇒ 看着像「焦点消失了」，必须再按「下」才回得来（迷路）；
        //   ② 标题上那两个隐藏操作（「上」键刷新数据源、OK 键打开数据源弹窗）老人根本不会用。
        // ⇒ 现在标题**永不可聚焦**，方向键只在 功能区 ⇄ 分类 chips ⇄ 内容区 之间移动，不会跑丢。
        // ✅ 标题与头像现为**纯装饰**：不注册点击（见 initEvent）、不可聚焦、无任何动作。
        // 换源入口全站唯一 = 「设置 → 长辈设置 → 一键锁定」（ElderLockActivity）。
        // ⚠ 2026-10-29（R99）：标题已由 CustomTitleView 换成 MaterialTextView（该自定义类已删除），
        //   `setSelected(true)` 仍然保留 —— 它是 TextUtils.TruncateAt.MARQUEE 跑马灯的开关，
        //   与焦点无关；标题文字现为固定软件名，通常不超宽，但换语言/超长名称时仍靠它兜底。
        //
        // ⛔⛔ 2026-10-29（R100）落焦**改成确定性的**：恒落「功能区」第 0 行。
        //   旧实现在这里写的是 `if (focusChipRow()) return;` —— 也就是**主动**把焦点送到分类 chips 行，
        //   于是每次冷启动 / 退出后重进首页，焦点都停在「推荐」那颗 chip 上（用户反馈的现象）。
        //   为什么老人场景要落功能区、而不是「推荐」chips：
        //     ① chips 有「移到即加载」的副作用（onItemFocus → loadChip）⇒ 刚进首页就额外发一轮请求，
        //        和首页自己的 homeContent 撞车，历史上还触发过「chips 重绑 → 焦点抖动 → 再加载」的环；
        //        功能区是纯入口、点了才有动作，落上去零副作用。
        //     ② 视觉上焦点从左上标题区往下第一个就是功能区，符合「从上往下」的扫描顺序；
        //        老人一眼看到高亮的「搜索/直播/历史…」就知道遥控器能按什么。
        //     ③ 功能区和「推荐内容」只差一次「下」键 —— 代价极小，却换来一个不会误触发加载的锚点。
        //   守卫也从「焦点在 recycler 内就不动」收窄成「焦点已在**功能区**里才不动」：
        //   否则「焦点已经滑到 chips 上」这种情况会被原样放行，正是本次要修的现象。
        //   （本方法只在 showContent() 里被调用，而 showContent 的三个调用点全是「进内容态」的
        //     一次性转换，此时老人还没有机会在内容里移动焦点 ⇒ 强制落焦不会把他拽走。）
        if (!isFocusInFuncRow()) {
            // 功能区还没绑上/还没有项时返回 false ⇒ 退回列表默认落点，至少保证遥控器有反应。
            if (!focusFuncRow()) mBinding.recycler.requestFocus();
        }
        // ⚠ 2026-10-22 修「冷启动后焦点在功能区、但视角看不到最上方标题区域」：
        // requestFocus() 只保证「焦点项可见」，**不保证可视区从第 0 行开始**；
        // 内容行又是异步插入的（onContentResult → addAll），插入后重新布局还会再把标题挤出去。
        // 所以这里校正**两次**：落焦后立即一次 + 内容插入完成后再一次。
        // ⛔ 原实现里那次 post 会先用 getCurrentFocus() 判「焦点是否已在 recycler 内」再决定滚不滚 ——
        //    requestFocus 之后焦点到达子项的时机不固定，经常判不到 ⇒ 什么都不做 ⇒ 冷启动仍然看不到标题。
        //    现在改为不看焦点视图，改为看「选中位置」：只要焦点还停在功能区（index 0/1，含 -1 未定），
        //    就把列表回顶；老人一旦往下移动（index >= 2），两次校正都自动失效，不会把他拽回顶部。
        mBinding.recycler.post(this::scrollHomeToTop);
        mBinding.recycler.postDelayed(this::scrollHomeToTop, HOME_TOP_DELAY);
    }

    /** 焦点是否正停在第 0 行「功能区」里（含「行视图本身获得焦点」这种情形）。 */
    private boolean isFocusInFuncRow() {
        if (mFuncRow == null) return false;
        View focused = getCurrentFocus();
        if (focused == null) return false;
        View row = findAttachedRow(mFuncRow);
        return row != null && isDescendantOf(focused, row);
    }

    /**
     * 把焦点送到功能区（第 0 行）的第一项。
     *
     * <p>与 {@link #focusChipRow()} 同一套「行没绑上就滚动+下一帧再落焦」的处理：
     * {@code scrollToPosition} 触发的布局尚未完成，此刻取不到行视图，必须 post 到下一帧。</p>
     *
     * @return true = 落焦已发出（含已安排到下一帧）；false = 功能区此刻没有可落焦的项，调用方需兜底
     */
    private boolean focusFuncRow() {
        if (mFuncRow == null || mFuncAdapter == null || mFuncAdapter.size() <= 0) return false;
        View row = findAttachedRow(mFuncRow);
        if (row == null) {
            int index = mAdapter.indexOf(mFuncRow);
            if (index < 0) return false;
            mBinding.recycler.scrollToPosition(index);
            mBinding.recycler.post(() -> {
                View target = firstFocusableIn(findAttachedRow(mFuncRow));
                if (target != null) target.requestFocus();
            });
            return true;
        }
        View target = firstFocusableIn(row);
        if (target == null) return false;
        target.requestFocus();
        return true;
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

    /**
     * 内容行 Presenter（懒建一次）。
     *
     * <p>⛔ 这里<b>只缓存 presenter 实例本身</b>；封面尺寸由 presenter 在每次创建 holder 时
     * 按当前「图片尺寸」档位重算（见 {@code VodPresenter#onCreateViewHolder}）。<b>不能连尺寸一起缓存</b> ——
     * 本页在档位变化时<b>不重建</b>（{@link #autoRecreateOnSizeChange()} 返回 false，
     * 只走 {@code RefreshEvent.SIZE → getVideo()}），presenter 会被一直复用，
     * 缓存尺寸的后果就是「改了图片尺寸，分类/推荐列表毫无变化」。</p>
     */
    private VodPresenter vodPresenter(Style style) {
        if (mVodPresenter == null) mVodPresenter = new VodPresenter(this, style);
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
     *  且 {@code isOnline()} 本身有异步窗口（网络能力上报有先后），有网的头一两秒仍可能
     *  误报无网，文案会跳变（R131 前还要叠加「等系统 VALIDATED」的更长延迟）。
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
     * <p>保持 progress 后不会「一直转」：网络恢复时 {@code armSourceRetry()} 会重载配置，
     * 成功回调里的 {@code getVideo()} 会重新请求内容并替换掉进度行。</p>
     */
    private boolean keepLoadingInsteadOfEmpty() {
        return configFailed && VodConfig.get().getSites().isEmpty() && !NetAwait.isOnline();
    }

    /**
     * R132：首页内容级自动重试的调度端。
     *
     * <p>阶梯 5s/10s/20s 共 3 次；tick 里走 {@link #loadChip(Object, boolean)} 强制路径
     * （等价于老人手动点了一下当前分类）。拿到内容由 onContentResult 成功分支清零计数；
     * 3 次全空停在空态卡，手动点按仍然可用。真没内容的源会白试 3 次 —— 有界、静默、可接受，
     * 换取「开机头几秒站点没就绪」场景的自愈。</p>
     */
    private void scheduleContentRetry() {
        if (contentRetryCount >= CONTENT_RETRY_STEPS.length) return;
        App.post(mContentRetry, CONTENT_RETRY_STEPS[contentRetryCount++]);
    }

    private void retryContentOnce() {
        if (isFinishing() || isDestroyed()) return;
        if (!NetAwait.isOnline()) return;   // 没网不空转；空态卡与手动重试仍在
        loadChip(mShownChip, true);
    }

    private void showContentEmpty() {
        if (mAdapter.indexOf(mChipRow) == -1) return;
        removeContent();
        // ⛔⛔ 2026-10-29（R99）：判据从「rawSites 是否为空」改为「配置里到底有没有数据源地址」。
        //   原实现用 `getRawSites().isEmpty()` 区分「从没导入过」与「导入过但都被关掉」。
        //   但**配置拉取失败**时 config 为 null（BaseConfig#getConfig 回落 defaultConfig()）
        //   ⇒ rawSites 同样为空 ⇒ 被误判成「还没有设置数据源」。这就是那条假报的真正根因：
        //   冷启动（depot 型配置不落本地缓存，见 VodConfig#loadFromCache）必须走一次网络往返，
        //   这一趟失败就会走到这里；「反复退出 / 反复冷启动」只是把命中概率放大。
        //   真实判据只有一个：getConfig().getUrl() 为空 ⇒ 从来没配置过数据源地址。
        if (VodConfig.get().getSites().isEmpty()) {
            int textRes;
            if (TextUtils.isEmpty(VodConfig.get().getConfig().getUrl())) {
                // 从没导入过数据源 —— 唯一的出路是去设置里添加，措辞必须指向设置页。
                textRes = R.string.home_empty_no_source;
            } else if (VodConfig.get().getRawSites().isEmpty()) {
                // 地址是有的，只是这一趟没解析出站点（拉取失败 / 网络不通）⇒
                // 说「暂时连接不上，会自动重试」，绝不能说「还没有设置数据源」（会误导老人去重配）。
                textRes = R.string.home_empty_source;
            } else {
                // 配置完好，是用户把所有站源都关掉了 —— 不能说成「没设置过」，要指路到站源选择。
                textRes = R.string.home_empty_no_site;
            }
            mEmptyItem = EmptyCardPresenter.Item.plain(textRes);
            mAdapter.add(mEmptyItem);
            return;
        }
        if (configFailed) {
            // 数据源拿到过（sites 非空），但这次内容请求失败 ⇒ 「暂时连接不上」。
            // ⚠ 原先这里还留了一句 `noSource = getSites().isEmpty()` 的三元判断，但上面
            //   `sites.isEmpty()` 已经 return ⇒ 该变量恒为 false、三元恒取 home_empty_source，
            //   属于死逻辑，一并清掉。
            mEmptyItem = EmptyCardPresenter.Item.plain(R.string.home_empty_source);
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
                // ⛔ 2026-10-29（R100）：这里原本还有一句 setLogo()（换源后把标题区 logo 换成新站源的）。
                //   标题区恒显示软件自己的 logo，换源不再影响它 ⇒ 该调用已删除，别再补回来。
                break;
            case COMMON:
                setFunc();
                break;
            case LIVE:
                // R126：与手机端同口径 —— 直播配置加载完成（LiveConfig#postEvent）后再推进一次。
                //   BOOT 在 setHome 那一刻就派发，此时频道列表可能还没填完 ⇒ isBootDirectReady()
                //   会判 false；若只有 BOOT 一条驱动，电视端就只能等 1 秒轮询（手机端有本分支，不用等）。
                //   补这一条让双端严格一致：直播配置一就绪就跳，不依赖轮询次数。
                if (mBootTarget == BootTarget.LIVE) awaitBootDirect();
                break;
            case BOOT:
                // R124/R125：BOOT 事件不再直接跳直播页，改由统一出口裁决。
                //   BOOT 现在只有一个来源：直播配置解析完成 + 「直播开机直达」开关打开（LiveConfig#setHome）。
                //   它<b>不新建</b>目标，只推进已经登记好的直播目标 —— 否则运行期任何一次直播配置重载
                //   （换源、重试）都会把老人从正在看的页面上拽走（这是 R123 之前的老问题）。
                //   R125：原先还有一个 tvbus 分量（「用户刚换了直播内核」）—— 该语义已由
                //   TVBus 原地重建引擎取代（换内核不再需要杀进程再跳回来），故一并删除。
                if (mBootTarget == BootTarget.LIVE) {
                    awaitBootDirect();
                }
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                getVideo();
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
            // 2026-10-29（R99）标题换成 MaterialTextView 后依然 focusable="false"，本分支仍不命中，
            // 但判据只看 mBinding.title 是否存在，与具体控件类型无关 ⇒ 无需随 CustomTitleView 一并改动。
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
        // R124：待机唤醒 —— 中国老人最常见的「开机」是按遥控器上的关机键（待机 STR），不是拔插头。
        //   熄屏时首页在前台、待机 ≥60 秒、距亮屏 ≤60 秒（R134）⇒ 这一次算「开机会话」，重新发起一次直达。
        //   ⚠ 判定必须在 onHomeVisible() 之前：后者会把「首页最后一次可见时刻」推到当前，
        //   熄屏时刻就变成「早于首页可见」⇒ 判定恒为 false。
        if (BootSession.isWakeSession()) {
            BootSession.consumeWake();
            rearmBootDirect();
        }
        BootSession.onHomeVisible();
        // R132：退后台补偿 —— isWakeSession 要求待机满 60 秒（R134），更短的快速待机（几秒~几十秒）
        // 回来什么都不做；而「开机那次没跳成」的直达目标（例如直播配置当时没就绪）会一直挂着，
        // 用户感知就是「直达失效」。这里放宽为：退后台超过 60 秒回来，只要还有未消费、未抢屏、
        // 用户未操作的直达目标，就重新驱动一次。awaitBootDirect 内部有去重（bootDirectAwait 已挂
        // 就 return）与放弃判据（bootInteracted / isFinishing），不会重复抢屏；直播目标未就绪时
        // 会经 tryBootDirect 的 R132 重载逻辑自动重拉直播配置。
        if (mBootTarget != BootTarget.NONE && !bootDirectFired && !bootInteracted
                && mPausedAt > 0 && SystemClock.elapsedRealtime() - mPausedAt >= QUICK_RESUME_REDRIVE_MS) {
            awaitBootDirect();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mClock.stop();
        // R132：记「退后台/熄屏」时刻 —— 快速待机补偿（见 onResume）用它量间隔。
        mPausedAt = SystemClock.elapsedRealtime();
        // R125（P1）：记「首页退到后台」的时刻 —— 唤醒会话要靠它区分
        //   「熄屏时首页在前台」与「用户自己切走了首页」。
        //   熄屏本身也会让首页退到后台，所以 BootSession 里对「隐藏时刻落在熄屏前后 2 秒内」
        //   做了容错，不会把真正的唤醒误杀。
        BootSession.onHomeHidden();
    }

    @Override
    protected void onBackInvoked() {
        // ① 连按计数：窗口期内第三次按，任何状态都直接退出
        int taps = countBackTap();
        if (taps == 0) {
            finish();
            return;
        }
        // ② 启动画面：这一按只是「别等了，进首页」，但仍**计入**退出连按
        //（2026-10-29 / R99 改：原先这里 resetBackTaps() 把计数清零，导致「无数据源、一直停在
        //  启动画面」时每按一次都被清零 ⇒ 永远不会退出。启动画面按一次就进内容态，不存在
        //  「按多了误退出」的风险，计数没必要作废。）
        if (mBinding.progressLayout.isProgress()) {
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
        // R123：门控宽限的 12 秒定时器同理必须撤销 —— 否则页面已销毁它还会回来 showContent()。
        App.removeCallbacks(mGateTimeout);
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
        if (isFinishing() && !isChangingConfigurations()) DLNARendererService.stop(this);
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
