package com.fongmi.android.tv.service;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.BootLauncher;
import com.fongmi.android.tv.utils.BootSession;
import com.fongmi.android.tv.utils.DesktopResolver;

/**
 * 桌面守护：按主页键回到电视桌面时，自动把「看电视」切回前台（2026-10-29 新增）。
 *
 * <p><b>为什么需要它</b>：部分固件（TCL / 雷鸟等）在开机阶段会扣住第三方 App 的启动，
 * 实测本机 <b>桌面在开机后 11 秒就出现，但本 App 进程要到开机后 133 秒才被创建</b>
 * （数据来源 {@code /proc/<pid>/stat} 第 22 字段），用户体感就是「桌面出来还要干等 2 分钟」。
 * 只靠 {@code BOOT_COMPLETED} 广播无法突破，因为广播本身就是靠系统创建进程来接收的。</p>
 *
 * <p><b>为什么无障碍服务能更早</b>：{@code AccessibilityManagerService} 在
 * {@code systemReady} 阶段就会绑定已启用的无障碍服务，绑定动作本身会把本 App 进程拉起来，
 * 这早于 {@code finishBooting()} 发出 {@code BOOT_COMPLETED}。此外无障碍服务启动 Activity
 * 不受后台启动（BAL）限制，不会被系统拦。思路来自 aerial-tv 的 {@code HomeGuardService}。</p>
 *
 * <p><b>为什么监听 {@code TYPE_WINDOW_STATE_CHANGED}</b>：桌面回到前台时一定会产生该事件，
 * 且事件频率远低于 {@code TYPE_WINDOW_CONTENT_CHANGED}，对这台 982MB 内存的机器负担最小。
 * 配置里不写 {@code packageNames} 限定，是为了固件升级改了桌面包名时仍能工作；
 * 实际过滤在下面的 {@link #onAccessibilityEvent} 里做，候选集合由 {@link DesktopResolver} 提供。</p>
 *
 * <p><b>2026-10-30（R103）跨品牌改造</b>：原先这里写死 {@code com.tcl.cyberui}，
 * 「接管主页键」实际只在 TCL 上生效。现在改为向系统要「所有声明了 HOME 的应用」，
 * 小米 / 海信 / 创维 / 索尼 / 雷鸟 / 当贝 / IPTV 盒子等只要遵循 Android 标准即可识别，
 * 固件升级改名也不会失效。</p>
 *
 * <p><b>2026-11-08（R137）抢屏修复 + 唤醒直达</b>：修复「打开其他应用被抢屏拉回」——
 * 兜底模式下桌面候选集合混入误声明 HOME 的 IPTV/市场类应用，用户点开就被当成
 * 「回到桌面」拉回。新增<b>窗口来源判定</b>（见 {@link #lastWindowPkg}）：只有
 * 「本应用/其他应用 → 桌面」的切换才算按主页键；同包驻留事件、桌面 → 应用 的切换一律放行。
 * 另外待机唤醒会话期间拉回升级为 {@code BootLauncher.forBoot}（允许开机直达），
 * 平时仍为 {@code forGuard}（不触发续播）。</p>
 */
public class HomeGuardService extends AccessibilityService {

    private static final String TAG = "HomeGuard";

    /**
     * 两次「回到桌面」相隔多久算「是同一意图下的连按」（毫秒）。
     *
     * <p>用于实现「第二次放行」：老人误按一下主页键，我们拉回来；如果他紧接着又按了一次，
     * 说明是<b>真的想用桌面</b>，这时候再抢就是在跟人较劲了。</p>
     */
    private static final long PAIR_WINDOW_MS = 10_000L;

    /**
     * 「第二次按主页键」后的放行时长（毫秒）。
     *
     * <p>3 分钟足够老人去桌面里点个应用、看个设置；到期后自动恢复接管，
     * 避免有人不小心连按两下就永久失去守护。</p>
     */
    private static final long GRACE_MS = 3 * 60 * 1000L;

    /**
     * 「这次 HomeActivity 被打开，是我们自己拉起来的」判定窗口（毫秒）。
     *
     * <p><b>为什么必须有</b>（2026-10-30 自查发现的死循环）：守护把界面拉回前台后，
     * {@code HomeActivity#onNewIntent} 会调用 {@link #onUserOpened()} 清计数 —— 于是老人
     * 「按两下主页键」这个动作永远凑不满 2 次：第一下被拉回并清零，第二下又从 1 开始，
     * 结果就是<b>桌面永远进不去</b>，正好把「要用系统自带应用」的路堵死。
     * 所以「自己拉的」不能算「用户主动打开」，计数必须保留。</p>
     */
    private static final long SELF_PULL_WINDOW_MS = 5_000L;

    // ---- 以下五个是「跨重建」的状态，必须是静态的 ----------------------------------
    // 无障碍服务可能被系统销毁后重建（内存压力、用户切换无障碍开关），实例字段会丢，
    // 丢了之后「第二次放行」就会退化成「每次按主页都拉回」，体验反而更差。

    /** 放行截止时刻（elapsedRealtime）；0 表示不在放行期。 */
    private static long graceUntil;
    /** 上一次收到「桌面回到前台」事件的时刻。 */
    private static long lastDesktopAt;
    /** 当前这一轮连按已经发生了几次（1 = 刚按过一下，2 = 连按两下，触发放行）。 */
    private static int desktopHits;
    /** 上一次「我们自己把界面拉到前台」的时刻，用于区分「用户主动打开」与「被拉回来」。 */
    private static long lastPullAt;
    /**
     * 上一条窗口事件的前台包名（2026-11-08 / R137 来源判定）。
     *
     * <p><b>为什么必须记它</b>：兜底模式下桌面候选集合里会混进「也声明了 HOME 的普通应用」
     * （IPTV / 市场类最常见）。只看当前事件包名无法区分「用户按主页键回到桌面」和
     * 「用户在桌面里点开了这个应用」，后者会被当成回到桌面而反复拉回 —— 用户体感就是
     * 「打开其他应用被抢屏」。</p>
     *
     * <p><b>方向区分法</b>：窗口事件是有顺序的 ——</p>
     * <ul>
     *   <li>本应用/其他应用 → 桌面候选：按主页键回桌面，<b>要拉回</b>；</li>
     *   <li>桌面候选 → 另一个桌面候选：用户从桌面点开了它，<b>放行</b>；</li>
     *   <li>同包连续事件：该应用驻留（弹窗、翻页、动画），<b>忽略</b> ——
     *       顺带修掉旧版缺陷：部分 ROM 一次主页键连发多条桌面事件，
     *       第二条会被凑成「连按两次」而误触发 3 分钟放行。</li>
     * </ul>
     *
     * <p>判定用的是<b>事件前的旧值</b>，所以本字段必须在桌面命中判定之前更新。
     * 服务被系统销毁重建后它会归 null：第一次事件按「未知来源」保守处理（拉回），
     * 与「宁可漏不可误伤」的原则一致。</p>
     */
    private static String lastWindowPkg;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * HomeActivity 每次打开时调用，清空「连按计数」与「放行期」—— 但只认用户主动打开。
     *
     * <p>由 {@code HomeActivity} 在 {@code initView}（冷启动）与 {@code onNewIntent}（实例复用）
     * 时调用 —— 这两处是「用户回来」的全部入口。不能放在 {@code onResume}：退出前的生命周期
     * 也会走 onResume，会把刚置位的抑制状态提前解掉。</p>
     *
     * <p><b>为什么要区分「谁打开的」</b>：这两处也会被我们自己 {@code startActivity} 触发
     * （拉回前台）。若不分青红皂白地清零，用户「连按两下主页键去用桌面」永远凑不满两次，
     * 详见 {@link #SELF_PULL_WINDOW_MS} 的注释。</p>
     */
    public static void onUserOpened() {
        if (SystemClock.elapsedRealtime() - lastPullAt < SELF_PULL_WINDOW_MS) return; // 自己拉的，保留计数
        graceUntil = 0L;
        lastDesktopAt = 0L;
        desktopHits = 0;
    }

    /** 记一笔「这次是守护自己把界面拉到前台的」，供 {@link #onUserOpened()} 区分来源。 */
    private static void markSelfPull() {
        lastPullAt = SystemClock.elapsedRealtime();
    }

    /**
     * 「服务一连上就主动拉起一次」。
     *
     * <p><b>为什么需要</b>（2026-10-30 实测）：开机时本服务被系统绑定、进程被创建是开机后 28.6 秒
     * （改造后 18.7 秒），但界面直到 41 秒（改造后 24.0 秒）才显示 —— 中间这段是在等下一条
     * 「桌面窗口状态变化」事件。开机那一刻桌面早就在前台，不会因为我们的到来再发一次状态变化，
     * 只能干等它下一次抖动。</p>
     *
     * <p>所以绑定成功即主动补一枪。用 {@link App#activity()} 判「已在前台」在这里是安全的：
     * 这是连接回调、不是窗口事件回调，不存在「onPause 与事件同队列」的竞态
     *（那个竞态只影响 {@link #onAccessibilityEvent}，见那里的注释）。</p>
     */
    private final Runnable pullBackOnConnect = new Runnable() {
        @Override
        public void run() {
            try {
                if (!Setting.isHomeGuard()) return;
                if (Setting.isUserExited()) return;
                // 放行期内（用户连按两次主页键想去用桌面）不抢
                if (SystemClock.elapsedRealtime() < graceUntil) return;
                if (App.activity() != null) return;
                markSelfPull();
                // 唤醒会话期间的拉起属于「开机直达」语义（R137）：允许进直播/续播，
                // 平时按主页键拉回仍走 forGuard（打 EXTRA_GUARD_PULL，不触发续播）。
                if (BootSession.isWakeSession()) BootLauncher.forBoot(HomeGuardService.this, "无障碍连接·待机唤醒");
                else BootLauncher.forGuard(HomeGuardService.this, "无障碍连接");
            } catch (Throwable t) {
                Log.w(TAG, "连接后主动拉起失败", t);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // 每次连接（含每次开机）重算一次桌面包名：用户可能刚装了第三方桌面，也可能刚做过固件升级
        DesktopResolver.refresh(this);
        Log.i(TAG, "桌面守护已连接");
        // 稍等 600ms 让系统把窗口栈稳定下来，再决定要不要拉；期间若已有界面则上面会自行跳过
        handler.removeCallbacks(pullBackOnConnect);
        handler.postDelayed(pullBackOnConnect, 600L);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(pullBackOnConnect);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // 「接管 Home 键」开关：关闭后完全不管，主页键正常回到电视桌面
        if (!Setting.isHomeGuard()) return;
        // ⛔⛔ 2026-10-29（R99）用户主动退出 ⇒ 不许拉回。
        // 没有这一条时，首页「连按三次返回键退出」是个假动作：finish() 之后桌面窗口立刻出现，
        // 800ms 后被这里重新拉起 —— 而且因为原实例已 finish，拉起来的是**全新冷启动**的实例
        //（无缓存、要重新走一遍配置网络往返，失败就会显示「还没有设置数据源」）。
        // 标记由 HomeActivity#onBackInvoked 在真正退出前置位、
        // 由 HomeActivity#initView 在下一次「真正打开本软件」时清零 ⇒ 守护随即恢复。
        if (Setting.isUserExited()) return;
        CharSequence name = event.getPackageName();
        String pkg = name == null ? null : name.toString();
        // R137：先记下「上一个前台包」再做桌面判定 —— 下面的来源判定依赖事件顺序，
        // 所以无论这次事件是什么包，都必须在这里把窗口流记下来。
        String prev = lastWindowPkg;
        lastWindowPkg = pkg;
        // 是否为桌面由 DesktopResolver 判定（集合里不含自己，也不用再判 App.activity()）
        if (!DesktopResolver.isDesktop(this, pkg)) return;
        // ⛔ 这里不能再用 App.activity() 判空做「已在前台」的短路（2026-10-29 实测）：
        // 按主页键时「本 Activity 的 onPause」与「桌面窗口事件」在同一主线程消息队列里，
        // 事件常常先于 onPause 到达，此刻 activity 仍非 null ⇒ 该次事件被整个跳过，
        // 要等下一个窗口事件才会拉起。实测这样会让「按主页键回来看电视」慢到 4 秒，
        // 而 am start -W 实测本 Activity 从后台恢复只要 262ms，说明慢的是这段判断而非渲染。
        // 而包名命中桌面集合本身即说明桌面已在前台，本软件必然不在前台，无需额外判断。
        // ⛔⛔ R137 来源判定（修「打开其他应用被抢屏拉回」）：
        // 只看当前包名命中桌面集合，无法区分「用户按主页键回桌面」和「用户从桌面点开了
        // 另一个也声明 HOME 的应用（IPTV/市场类）」——后者在兜底模式下会被反复拉回。
        // 用窗口事件的顺序区分方向：
        //   ① 同包连续事件 = 该应用驻留（弹窗/翻页/动画），忽略 —— 不拉、也不计入连按，
        //      顺带修掉「一次主页键连发多条事件被凑成连按两次」的旧缺陷；
        //   ② 上一个前台包也是桌面候选 = 用户从桌面切过来的，是他主动打开的应用，放行；
        //   ③ 上一个前台包是本应用/其他应用/null = 按主页键回到桌面，走下面的拉回逻辑。
        // 放行不更新 lastDesktopAt：这不是「回到桌面」，不该参与连按计数。
        if (pkg != null && pkg.equals(prev)) return;
        if (prev != null && DesktopResolver.isDesktop(this, prev)) return;
        long now = SystemClock.elapsedRealtime();
        // ① 放行期内：用户已经明确表示要用桌面，这时再抢就是在跟人较劲
        if (now < graceUntil) return;
        // ② 「第二次放行」：10 秒内第二次回到桌面 = 真想用桌面，放行 GRACE_MS
        if (now - lastDesktopAt <= PAIR_WINDOW_MS) desktopHits++;
        else desktopHits = 1;
        lastDesktopAt = now;
        if (desktopHits >= 2) {
            desktopHits = 0;
            graceUntil = now + GRACE_MS;
            Log.i(TAG, "10 秒内第二次回到桌面，放行 " + (GRACE_MS / 1000) + " 秒");
            return;
        }
        // ③ 第一次：老人误按了主页键，切回来。唤醒会话期间升级为 forBoot（允许直达），
        //    平时仍是 forGuard（EXTRA_GUARD_PULL，首页不触发续播）。
        markSelfPull();
        if (BootSession.isWakeSession()) BootLauncher.forBoot(this, "回桌面·待机唤醒");
        else BootLauncher.forGuard(this, "回桌面");
    }

    @Override
    public void onInterrupt() {
    }
}
