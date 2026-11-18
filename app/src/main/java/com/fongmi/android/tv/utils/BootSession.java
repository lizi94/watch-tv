package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.util.Log;

import com.github.catvod.utils.Prefers;

/**
 * 「开机会话」的唯一真相源（R124）。
 *
 * <p><b>为什么需要它</b>：R123 用「{@code SystemClock.elapsedRealtime() < 90 秒}」判断「是不是开机阶段」，
 * 这条判据在两类真实场景里<b>必然判错</b>：</p>
 *
 * <ul>
 *   <li><b>待机唤醒</b> —— 中国老人最常见的「开机」是按遥控器上的关机键，那是<b>待机（STR）</b>而不是拔插头。
 *       {@code elapsedRealtime} 从开机起算且<b>包含深睡</b>，第二天早上这个值早就过了 90 秒
 *       ⇒ 被判成「不是开机」⇒ 默认桌面形态下的点播/直播直达全部失效。</li>
 *   <li><b>慢机型</b> —— 实测有电视把 BOOT_COMPLETED 拖到开机 80 秒以后，90 秒窗口太窄。</li>
 * </ul>
 *
 * <p><b>本类只记四件事，全是事实，不做推断</b>：</p>
 * <ol>
 *   <li><b>进程启动时刻</b>（{@link #onProcessStart()}，App 启动时记）：进程是不是在开机后
 *       {@link #BOOT_PHASE_MS} 内启动的；</li>
 *   <li><b>熄屏时刻</b>（{@link #onScreenOff()}，系统 SCREEN_OFF 广播）：墙钟时间，<b>并持久化</b>；</li>
 *   <li><b>首页可见时刻</b>（{@link #onHomeVisible()}）：首页最后一次在前台的墙钟时间；</li>
 *   <li><b>首页隐藏时刻</b>（{@link #onHomeHidden()}）：首页最后一次退到后台的墙钟时间。</li>
 * </ol>
 *
 * <p><b>唤醒会话</b>（{@link #isWakeSession()}）要四个条件同时成立（R134）：① 熄屏那一刻<b>首页在前台</b>
 * （见 {@link #onScreenOff()} 里对「熄屏瞬间前台归属」的快照）；② 待机已满 {@link #WAKE_GAP_MS}（60 秒）；
 * ③ 已亮屏（亮屏时刻晚于熄屏时刻）；④ 距亮屏不超过 {@link #WAKE_FRESH_MS}（60 秒）。</p>
 *
 * <p><b>R134 更新</b>：熄屏/亮屏快照已<b>持久化</b>（Prefers），进程在待机期间被系统杀掉
 * （实测小米电视快速待机会杀进程）后的唤醒冷启动<b>也能识别</b>唤醒会话 —— {@link #onProcessStart()}
 * 会把上一次熄屏/亮屏快照读回来。旧版只存内存、进程一死快照即丢，导致「快速待机唤醒」
 * 既不在开机 5 分钟窗口内、又不满足熄屏 10 分钟，开机直达 100% 失效（2026-11-07 实测定案）。</p>
 *
 * <p>⚠ 防误判：条件④「距亮屏 ≤ {@link #WAKE_FRESH_MS}」——若进程在<b>亮屏状态下</b>被杀、
 * 很久之后用户按主页键才冷启动，持久化的还是几小时前的快照，绝不能当成唤醒去抢屏。</p>
 */
public final class BootSession {

    private static final String TAG = "BootSession";

    /**
     * 开机阶段的长度（毫秒）：进程启动落在这之内 ⇒ 这次启动属于「开机阶段」。
     *
     * <p>5 分钟能覆盖实测最慢的机型（BOOT_COMPLETED 约 80 秒），又不至于把「开机半小时后
     * 用户自己点图标」误判成开机（那种情况由 {@code CATEGORY_LAUNCHER} 单独排除）。</p>
     *
     * <p>R137（F3）：BootReceiver 也用它区分「真重启的 QUICKBOOT」（窗内送达，无需标记）
     * 与「待机唤醒式快速开机」（窗外送达 ⇒ 打快速开机标记）。</p>
     */
    public static final long BOOT_PHASE_MS = 5 * 60 * 1000L;

    /**
     * 熄屏多久算「重新开电视」（毫秒）。R134：10 分钟 → 60 秒；R137：60 秒 → 10 秒。
     *
     * <p><b>为什么一再放宽</b>：实测小米电视「快速待机」（1~3 分钟）会杀掉本进程，唤醒后的冷启动
     * 落在开机 5 分钟窗口之外 ⇒ 旧阈值下直达全部失效（用户 2026-11-07 实测反馈）；R137 进一步
     * 覆盖「几秒~几十秒的快速待机」（老人最常用的开关电视方式）。看直播/点播时熄屏唤醒恢复的是
     * 原界面（不经过首页，不抢屏）；只有「熄屏时停在本应用前台」才会重跳，而那正是
     * 「待机快速开机 = 重新开机直达」的产品语义。</p>
     *
     * <p><b>为什么仍留 10 秒下限</b>：误触电源键 / 屏保闪断通常在 10 秒内，短待机不重跳，
     * 避免老人本来就在首页却被突然拽进影片。</p>
     */
    public static final long WAKE_GAP_MS = 10 * 1000L;

    /**
     * 距亮屏多久之内才算「这次唤醒」（毫秒）。R134 新增。
     *
     * <p><b>为什么需要</b>：持久化的熄屏快照可能写入于几小时前。进程在<b>亮屏状态</b>下被杀、
     * 之后用户按主页键冷启动时，若只看「距熄屏很久」就会把普通的主页键启动误判成唤醒 ⇒ 抢屏。
     * 唤醒后的冷启动/onResume 发生在亮屏后几秒内，60 秒窗口足够覆盖慢 ROM；超窗一律不算。</p>
     */
    public static final long WAKE_FRESH_MS = 60 * 1000L;

    /**
     * 「熄屏导致的退到后台」与「熄屏广播」之间允许的最大间隔（毫秒）。
     *
     * <p><b>为什么需要它</b>：熄屏时系统会先让窗口失去可见性（{@code onPause}）、
     * 再（或同时）发出 {@code ACTION_SCREEN_OFF} 广播，<b>两者的先后顺序在不同 ROM 上并不一致</b>。
     * 若只按「广播到达时的前台归属」判断，广播晚到时首页已经被判成后台 ⇒ 真正的唤醒被误杀；
     * 若只按「首页最近一次可见/隐藏的时刻」判断，用户自己切走时留下的隐藏时刻又会被当成
     * 「熄屏导致」⇒ 切到别的应用看了十分钟再回来被误判成开机 ⇒ 抢屏。
     * 所以：<b>隐藏时刻落在熄屏前后 {@code SCREEN_OFF_GRACE_MS} 之内</b>的，一律按
     * 「熄屏导致首页退到后台」处理（那时首页确实在前台）。</p>
     */
    public static final long SCREEN_OFF_GRACE_MS = 2000L;

    /**
     * {@code QUICKBOOT_POWERON} 唤醒标记的有效期（毫秒）。R137 新增。
     *
     * <p><b>为什么需要它（G3 缺口）</b>：部分「快速开机」电视的 QUICKBOOT_POWERON 实为待机唤醒 ——
     * {@code elapsedRealtime} 未重置（不在开机 5 分钟窗内），若这台机器待机时又不发
     * {@code SCREEN_OFF} 广播（或进程先死收不到），R134 的唤醒三判据全部落空 ⇒ 直达必失效。
     * BootReceiver 收到 QUICKBOOT 且明显不是真重启时打上本标记，首页把「快速开机唤醒」
     * 也算进开机会话。标记 90 秒内有效、用掉即清，防残留误判。</p>
     */
    private static final long QUICK_BOOT_FRESH_MS = 90 * 1000L;

    /** 上次 {@link #markQuickBootWake()} 的时刻（elapsedRealtime）；0 = 无标记。 */
    private static long quickBootAt;

    /**
     * 「系统拉起」标记的 key（R137：从 leanback 的 HomeActivity 收口到 main）。
     *
     * <p><b>为什么收口</b>：待机唤醒时 App（main flavor）需要主动拉起本应用
     * （非默认桌面电视上系统不会拉第三方 App），而 main 无法引用 leanback 的
     * {@code HomeActivity} 常量 —— 唯一来源放在 main 的本类里，flavor 侧引用它。</p>
     */
    public static final String EXTRA_BOOT_LAUNCH = "boot_launch";

    /** 进程启动时刻（elapsedRealtime）。 */
    private static long processStartAt = -1L;
    /** 系统熄屏时刻（墙钟）。 */
    private static long screenOffAt;
    /**
     * 熄屏那一刻本应用是否在前台（快照，随 {@link #screenOffAt} 一起作废）。
     *
     * <p>R137：从「首页在前台」泛化为「本应用任意页面前台」。持久化 key 名保持不变
     * （{@code boot_session_screen_off_with_home}），旧值语义兼容、无需迁移 ——
     * 这修复了「熄屏时正在看直播/点播 + 进程被杀 → 唤醒冷启动落在首页却不算唤醒」的缺口（G2）。</p>
     */
    private static boolean screenOffWithSelf;
    /** 系统亮屏时刻（墙钟）。R134：与 screenOffAt 一起构成「待机时长」与「唤醒新鲜度」判据。 */
    private static long screenOnAt;

    /** 持久化键名（R134）：跨进程死亡存活，唤醒冷启动时由 {@link #onProcessStart()} 恢复。 */
    private static final String KEY_SCREEN_OFF = "boot_session_screen_off_at";
    private static final String KEY_SCREEN_ON = "boot_session_screen_on_at";
    private static final String KEY_WITH_HOME = "boot_session_screen_off_with_home";

    private static SharedPreferences prefs() {
        return Prefers.getPrefers();
    }
    /** 首页最后一次在前台的时刻（墙钟）。 */
    private static long homeVisibleAt;
    /** 首页最后一次退到后台的时刻（墙钟）。 */
    private static long homeHiddenAt;
    /** 本应用（任意页面）最后一次在前台的时刻（墙钟）。R137：由 App 生命周期回调维护。 */
    private static long selfVisibleAt;
    /** 本应用（任意页面）最后一次退到后台的时刻（墙钟）。R137：由 App 生命周期回调维护。 */
    private static long selfHiddenAt;
    /**
     * 本进程自创建以来「界面是否显示过」（R150 摘回，进程级一次性事实）。
     *
     * <p><b>为什么需要它</b>：守护/开机链路要区分「开机阶段的主动拉起」与「运行期的守护拉回」，
     * 仅靠时间窗（elapsedRealtime ≤ 开机 5 分钟）有一个漏洞 —— 窗口内本应用从未打开过时，
     * 用户在其他应用里按主页键也会被当成开机语境。{@code selfShown} 把「界面出现过」
     * 这一事实记下来（App 生命周期回调维护，置位后进程内不撤销），
     * 让时间窗判据在「界面已显示过」时一律让位（见 {@code HomeGuardService#isBootContext}）。</p>
     */
    private static boolean selfShown;

    private BootSession() {
    }

    /** App 启动时记一次：这次进程是在开机后多久启动的。 */
    public static void onProcessStart() {
        processStartAt = SystemClock.elapsedRealtime();
        // R137（P2 加固）：恢复快照的读取包 try —— 无障碍等组件可能在「设备尚未解锁」时把进程
        // 拉起（directBootAware 广播阶段），那时凭据加密存储不可读，直接读会抛 IllegalStateException
        // 把 Application#onCreate 带崩。读不到就按「无快照」处理，行为与旧进程一致。
        try {
            SharedPreferences p = prefs();
            screenOffAt = p.getLong(KEY_SCREEN_OFF, 0L);
            screenOnAt = p.getLong(KEY_SCREEN_ON, 0L);
            screenOffWithSelf = p.getBoolean(KEY_WITH_HOME, false);
        } catch (Throwable t) {
            Log.w(TAG, "解锁前读不到待机快照，按无快照处理", t);
            screenOffAt = 0L;
            screenOnAt = 0L;
            screenOffWithSelf = false;
        }
        Log.i(TAG, "process start, elapsed=" + processStartAt
                + ", screenOff=" + screenOffAt + ", screenOn=" + screenOnAt + ", withSelf=" + screenOffWithSelf);
    }

    /**
     * 系统熄屏（SCREEN_OFF 广播）：记墙钟时刻，<b>并当场快照「这一刻本应用在前台吗」</b>。
     *
     * <p>为什么必须「当场」快照：熄屏之后本应用会被判成后台，等 {@code isWakeSession()} 再回头看
     * 「可见/隐藏时刻」已经分不清「是熄屏导致的」还是「用户自己切走的」——
     * 那正是 R124 之前「切到别的应用后熄屏再回来被误判成开机、进而抢屏」的根因。</p>
     */
    public static void onScreenOff() {
        screenOffAt = System.currentTimeMillis();
        // R137：判定泛化为「本应用任意页面前台」—— 首页的可见/隐藏时刻之外，
        // 再参考 App 生命周期回调维护的全应用可见/隐藏时刻（含直播/点播/设置等所有页面）。
        // 两条判据任一成立即算「熄屏时本应用在前台」；隐藏时刻仍受 SCREEN_OFF_GRACE_MS 宽限。
        boolean homeInFront = homeVisibleAt > 0
                && (homeVisibleAt > homeHiddenAt || screenOffAt - homeHiddenAt <= SCREEN_OFF_GRACE_MS);
        boolean selfInFront = selfVisibleAt > 0
                && (selfVisibleAt > selfHiddenAt || screenOffAt - selfHiddenAt <= SCREEN_OFF_GRACE_MS);
        screenOffWithSelf = homeInFront || selfInFront;
        Log.i(TAG, "screen off at " + screenOffAt + ", selfForeground=" + screenOffWithSelf);
        // R134：快照持久化 —— 进程可能在待机期间被系统杀掉，唤醒后的新进程要能读到这份快照。
        prefs().edit().putLong(KEY_SCREEN_OFF, screenOffAt)
                .putBoolean(KEY_WITH_HOME, screenOffWithSelf).apply();
    }

    /**
     * 系统亮屏（SCREEN_ON 广播）：记墙钟时刻并持久化（R134）。
     *
     * <p>亮屏时刻是两个判据的基准：「待机时长 = 亮屏 − 熄屏」与「唤醒新鲜度 = 当前 − 亮屏」。
     * 没有它，持久化的熄屏快照既算不出待机多久，也挡不住亮屏状态下进程被杀后的误判。</p>
     */
    public static void onScreenOn() {
        screenOnAt = System.currentTimeMillis();
        prefs().edit().putLong(KEY_SCREEN_ON, screenOnAt).apply();
    }

    /** 本应用任意页面回到前台：记墙钟时刻（R137，由 App 生命周期回调维护）；同时置位「界面已显示」。 */
    public static void onSelfVisible() {
        selfShown = true;
        selfVisibleAt = System.currentTimeMillis();
    }

    /** 本进程自创建以来「本应用界面是否显示过」（置位后不撤销 —— 见 {@link #selfShown}）。 */
    public static boolean hasSelfShown() {
        return selfShown;
    }

    /** 本应用任意页面退到后台：记墙钟时刻（R137，由 App 生命周期回调维护）。 */
    public static void onSelfHidden() {
        selfHiddenAt = System.currentTimeMillis();
    }

    /**
     * 打「快速开机唤醒」标记（R137，G3 缺口）：BootReceiver 收到 QUICKBOOT_POWERON 且
     * {@code elapsedRealtime} 已明显不是真重启（超出开机 5 分钟窗）时调用。
     */
    public static void markQuickBootWake() {
        quickBootAt = SystemClock.elapsedRealtime();
        Log.i(TAG, "quick boot wake marked");
    }

    /**
     * 「快速开机唤醒」标记是否仍有效（90 秒内）。
     *
     * <p>⛔ 与 {@link #isWakeSession()} 并列：首页判定开机会话时两者任一成立即可。
     * 标记由 {@link #consumeWake()} 一并清掉，防残留误判。</p>
     */
    public static boolean isQuickBootWake() {
        return quickBootAt > 0 && SystemClock.elapsedRealtime() - quickBootAt <= QUICK_BOOT_FRESH_MS;
    }

    /** 首页回到前台：记墙钟时刻（用来判断熄屏那一刻首页是否在前台）。 */
    public static void onHomeVisible() {
        homeVisibleAt = System.currentTimeMillis();
    }

    /**
     * 首页退到后台：记墙钟时刻。
     *
     * <p>⚠ <b>熄屏也会让首页退到后台</b>，所以这个时刻不能单独用来否定「熄屏时首页在前台」——
     * 真正的判据在 {@link #onScreenOff()} 里做（那里有熄屏瞬间的快照）。</p>
     */
    public static void onHomeHidden() {
        homeHiddenAt = System.currentTimeMillis();
    }

    /** 本次进程启动是否落在「开机阶段」内。 */
    public static boolean inBootPhase() {
        return processStartAt >= 0 && processStartAt <= BOOT_PHASE_MS;
    }

    /**
     * 是不是「熄屏很久之后被唤醒」的那一次（R134 四条件，见类注释）。
     *
     * <p>R137：条件① 泛化为「熄屏时本应用在前台（任意页面）」。</p>
     *
     * <p>⚠ 时序兜底：亮屏瞬间 {@code onResume}/冷启动可能跑在 {@code ACTION_SCREEN_ON}
     * 广播送达之前，此时 {@code screenOnAt} 还是上一轮亮屏的旧值（早于熄屏时刻）。
     * 而「有判定在跑」本身就意味着屏幕已经亮了 —— 所以广播未达时直接以当前时刻充当亮屏时刻，
     * 待机时长按「now − 熄屏」计，新鲜度天然为 0。否则每次唤醒的第一跳都会被漏掉。</p>
     */
    public static boolean isWakeSession() {
        if (screenOffAt <= 0) return false;
        if (!screenOffWithSelf) return false;
        long now = System.currentTimeMillis();
        // R134：待机时长 = 亮屏时刻 − 熄屏时刻。旧式「now − 熄屏」在亮屏状态下会一直变大，
        //   配合持久化快照会把几小时后的普通启动误判成唤醒。
        //   ⛔ 例外（时序兜底）：SCREEN_ON 广播还没送达（screenOnAt 仍是上轮旧值）但判定已在跑
        //   ⇒ 屏幕此刻必已点亮，以 now 充当亮屏时刻（新鲜度天然为 0，不会被 freshness 拦下）。
        long onAt = screenOnAt > screenOffAt ? screenOnAt : now;
        if (onAt - screenOffAt < WAKE_GAP_MS) return false;
        // R134：新鲜度 —— 距亮屏太久说明这不是「这次唤醒」，而是普通的主页键/图标启动。
        return now - onAt <= WAKE_FRESH_MS;
    }

    /**
     * R156：home 形态的唤醒会话 —— 与 {@link #isWakeSession()} 逐条相同，<b>只去掉「熄屏那一刻本应用在前台」</b>。
     *
     * <p><b>为什么需要它</b>：默认桌面形态（{@code leanbackHome}）在小米等机型上，「关机」走的是快速开机快照，
     * 唤醒时系统恢复的是<b>关机前的应用栈</b> —— 前台常常不是本应用，{@code screenOffWithSelf} 必然为 false，
     * 于是 {@link #isWakeSession()} 永远不成立，桌面再也不会被顶回来。</p>
     *
     * <p><b>为什么只去掉这一条、而不是「亮屏就拉」</b>：其余三条（存在真实熄屏快照、待机 ≥ {@link #WAKE_GAP_MS}、
     * 距亮屏 ≤ {@link #WAKE_FRESH_MS}）必须保留 —— 它们保证「这是一次真实的待机→唤醒」，
     * 不会把「刚开机」「切应用」「亮屏状态下进程被杀重启」误当成唤醒。</p>
     */
    public static boolean isLaunchWakeSession() {
        if (screenOffAt <= 0) return false;
        long now = System.currentTimeMillis();
        long onAt = screenOnAt > screenOffAt ? screenOnAt : now;
        if (onAt - screenOffAt < WAKE_GAP_MS) return false;
        return now - onAt <= WAKE_FRESH_MS;
    }

    /**
     * R156：本应用此刻是不是系统的默认桌面（只解析 MAIN + HOME，不依赖 leanback 源集的 LauncherInfo）。
     *
     * <p><b>为什么要自己解析</b>：本类在 {@code main} 源集，而 {@code LauncherInfo/LauncherCapability}
     * 在 {@code leanback} 源集 —— main 编译期引用不到 flavor 类（★BK-g 同型坑）。
     * 用 PackageManager 解析 {@code ACTION_MAIN + CATEGORY_HOME} 是纯 main 能力。</p>
     *
     * <p><b>为什么它能当 flavor 判据用</b>：{@code leanbackGuard} 的 manifest 没有 HOME category，
     * 不可能是默认桌面 ⇒ 本方法对 guard <b>恒返回 false</b>，同一份 Java 里天然按形态分叉，
     * 不需要 {@code BuildConfig.FLAVOR}。</p>
     */
    public static boolean isSelfDefaultHome(Context context) {
        if (context == null) return false;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = context.getPackageManager()
                    .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return info != null && info.activityInfo != null
                    && context.getPackageName().equals(info.activityInfo.packageName);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 唤醒会话只认一次：用掉即清零，否则每次 {@code onResume} 都会重判。 */
    public static void consumeWake() {
        screenOffAt = 0L;
        screenOffWithSelf = false;
        screenOnAt = 0L;
        homeVisibleAt = 0L;
        homeHiddenAt = 0L;
        quickBootAt = 0L;
        // R134：持久化快照一起清 —— 否则同一亮屏期内进程被杀又被拉起（崩溃重启等），
        // 恢复出来的旧快照会把普通启动误判成唤醒。
        prefs().edit().remove(KEY_SCREEN_OFF).remove(KEY_SCREEN_ON).remove(KEY_WITH_HOME).apply();
    }

    // ── 「开机拉起」标记（R150 摘回）：BootReceiver 收到开机类广播这一独立事实 ──

    /** 「开机拉起」标记：打标时的墙钟时刻（持久化）。 */
    private static final String KEY_BOOT_INTENT_WALL = "boot_session_boot_intent_wall";
    /** 「开机拉起」标记：打标时的 elapsedRealtime（持久化，仅随墙钟一起记录备查）。 */
    private static final String KEY_BOOT_INTENT_ELAPSED = "boot_session_boot_intent_elapsed";
    /** 开机拉起标记的有效期：10 分钟 —— 覆盖「广播送达了但拉起链路把定性判错」的所有慢机型窗口。 */
    private static final long BOOT_INTENT_FRESH_MS = 10 * 60 * 1000L;

    /**
     * BootReceiver 收到任何「开机类系统广播」（LOCKED_BOOT_COMPLETED / USER_UNLOCKED /
     * BOOT_COMPLETED / QUICKBOOT_POWERON）时打标 —— 记录「系统确实认为这是开机」这一事实。
     *
     * <p><b>为什么需要第四判据</b>：首页 classifyLaunch 现有的三条开机会话判据（开机 5 分钟窗 /
     * 熄屏唤醒快照 / 快速开机标记）各自都有覆盖不到的机型 —— 广播迟到、待机不发 SCREEN_OFF、
     * 快照被 ROM 清掉…… 而 BootReceiver <b>确实收到了广播</b>本身就是一条独立、可信的证据。
     * 实测 TCL 开机 08:37:42 → 进程 08:43:01 才起（<b>5 分 19 秒</b>），单单超了 {@link #BOOT_PHASE_MS}
     * 窗口 19 秒就让「开机直达」整条链路不触发，正是这条判据要兜的场景。</p>
     *
     * <p>墙钟 + elapsedRealtime 双时间戳<b>持久化</b>：快恢复机型收完广播进程即被杀，
     * 新建进程仍能读到这条证据（与 R134 熄屏快照同样的跨进程存活思路）。
     * 有效期 10 分钟、首页<b>读取即消费</b>（{@link #hasBootIntent()}）：
     * 防止残留标记把开机后前几分钟内的普通「按主页键回桌面」误判成开机会话（抢屏进续播）。</p>
     */
    public static void markBootIntent() {
        try {
            prefs().edit()
                    .putLong(KEY_BOOT_INTENT_WALL, System.currentTimeMillis())
                    .putLong(KEY_BOOT_INTENT_ELAPSED, SystemClock.elapsedRealtime())
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "markBootIntent failed", t);
            return;
        }
        Log.i(TAG, "boot intent marked");
    }

    /**
     * 「系统确实广播过开机」的标记是否仍有效 —— <b>读取即消费</b>。
     *
     * <p>判据只有一条：墙钟距打标 ≤ {@link #BOOT_INTENT_FRESH_MS}（真重启后墙钟必然前跳，
     * 旧标记天然过期，无需再比 elapsedRealtime；后者仅随写随存备查）。读不到或已过期
     * 一律 false 并顺手清掉残留。</p>
     */
    public static boolean hasBootIntent() {
        try {
            SharedPreferences p = prefs();
            long wall = p.getLong(KEY_BOOT_INTENT_WALL, 0L);
            if (wall <= 0L) return false;
            boolean fresh = System.currentTimeMillis() - wall <= BOOT_INTENT_FRESH_MS;
            p.edit().remove(KEY_BOOT_INTENT_WALL).remove(KEY_BOOT_INTENT_ELAPSED).apply();
            Log.i(TAG, "boot intent consumed: " + fresh);
            return fresh;
        } catch (Throwable t) {
            Log.w(TAG, "hasBootIntent failed", t);
            return false;
        }
    }

    /**
     * 「系统确实广播过开机」的<b>只读窥探</b>（守护链路区分开机语境用）。
     *
     * <p>与 {@link #hasBootIntent()}（读取即消费）刻意不同：窥探<b>不清残留</b> ——
     * 「消费」语义只属于首页 classifyLaunch，守护若提前消费会把首页要用的证据偷走。
     * 判据与 {@link #hasBootIntent()} 相同：墙钟距打标 ≤ {@link #BOOT_INTENT_FRESH_MS}
     * （真重启后墙钟必然前跳，旧标记天然过期）。读不到或已过期一律 false。</p>
     */
    public static boolean peekBootIntent() {
        try {
            long wall = prefs().getLong(KEY_BOOT_INTENT_WALL, 0L);
            return wall > 0L && System.currentTimeMillis() - wall <= BOOT_INTENT_FRESH_MS;
        } catch (Throwable t) {
            return false;
        }
    }
}
