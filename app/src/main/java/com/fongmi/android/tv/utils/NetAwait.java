package com.fongmi.android.tv.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;

/**
 * 「等网络就绪再干活」的小工具。
 *
 * <p><b>为什么需要它</b>：电视盒子冷启动后，网络（尤其是本地网络 / WiFi）往往还没就绪，
 * 而应用在 {@code onCreate} 之后很快就会发起依赖网络的请求。上游对这种场景只处理了一半 ——
 * {@code BootReceiver} 里给<b>直播源配置</b>加了「注册 {@code registerDefaultNetworkCallback}，
 * 在 {@code onAvailable} 里再 load」的逻辑，但<b>点播侧（含开机直达续播）没有对应处理</b>，
 * 结果是续播在网络就绪前就发起：详情请求抛 {@code UnknownHostException} → 被
 * {@code VideoViewModel#handleError} 归一成 {@code Result.empty()} → 被当成「源失效」
 * → 静默自动换源 → 搜索同样失败 → 停在空白详情页。老人看不到任何原因。</p>
 *
 * <p><b>语义</b>：
 * <ul>
 *   <li>已经连网（有 {@code VALIDATED} 或至少 {@code INTERNET} 能力的活动网络）→ <b>立即</b>执行，
 *       不多等一个回调周期；</li>
 *   <li>未连网 → 注册默认网络回调，等 {@code onAvailable} 时执行并自动注销；</li>
 *   <li>超过 {@code timeoutMs} 仍未就绪 → 执行 {@code onTimeout}（调用方应退化为「不自动续播、
 *       落在首页让用户自己点」，绝不能无限期等下去）；</li>
 *   <li>任务只执行一次 —— 无论走的是「立即」「就绪」还是「超时」路径，其余路径全部作废。</li>
 * </ul>
 *
 * <p><b>线程</b>：{@code NetworkCallback} 的回调不一定在主线程，{@code onTimeout} 由主线程
 * Looper 投递，因此这里统一把「执行」投递到主线程，调用方不必关心线程。
 */
public final class NetAwait {

    private NetAwait() {
    }

    /**
     * 判定「现在能否上网」。
     *
     * <p><b>为什么优先认 VALIDATED</b>：{@code NET_CAPABILITY_INTERNET} 只表示「这张网声称能上网」，
     * 盒子刚连上路由器、但光猫还没拨通时它就已经为 true —— 这正是我们要躲开的那段时间；
     * {@code NET_CAPABILITY_VALIDATED} 才是「系统真的探测通了外网」。所以只要系统给了 VALIDATED
     * 就以它为准，没给（部分老盒子不报）再退回 INTERNET，避免把用户卡死在等待里。</p>
     */
    public static boolean isOnline() {
        ConnectivityManager manager = manager();
        if (manager == null) return false;
        try {
            Network network = manager.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities == null) return false;
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
            // VALIDATED 为 true → 确定通了；为 false 时区分「系统明确说没通」与「系统没给这个位」。
            // API 23+ 起 NET_CAPABILITY_VALIDATED 恒存在（未通网时为 false），
            // 因此这里把 false 视为「还没通」是安全的保守判断。
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 网络就绪后（或确认已经就绪时）执行 {@code onReady}；等待超时则执行 {@code onTimeout}。
     *
     * <p><b>关于 {@code onTimeout} 传 {@code null}</b>：表示「超时就当成就绪」—— 即超时也执行
     * {@code onReady}。想「超时就放弃、什么都不做」，必须显式传一个空操作
     * （{@code () -> {}}），不要传 {@code null}。</p>
     *
     * @param timeoutMs 等多久算超时；传 {@code 0} 或负数表示不设超时（一直等，直到网络就绪）
     * @return 句柄，可 {@link Handle#cancel()} 取消（取消后两个回调都不会再执行）；已立即执行的场合返回的句柄为 no-op
     */
    public static Handle whenReady(long timeoutMs, @NonNull Runnable onReady, @Nullable Runnable onTimeout) {
        if (isOnline()) {
            post(onReady);
            return Handle.NONE;
        }
        ConnectivityManager manager = manager();
        if (manager == null) {
            // 取不到系统服务（极罕见）：不能把用户卡住，直接按超时处理。
            post(onTimeout != null ? onTimeout : onReady);
            return Handle.NONE;
        }
        Waiter waiter = new Waiter(manager, timeoutMs, onReady, onTimeout);
        return waiter.fired() ? Handle.NONE : waiter;
    }

    /** 等待句柄：允许调用方在页面销毁 / 语义变更时撤销这次等待。 */
    public interface Handle {

        Handle NONE = () -> {
        };

        void cancel();
    }

    @Nullable
    private static ConnectivityManager manager() {
        try {
            return (ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    private static void post(@Nullable Runnable runnable) {
        if (runnable != null) App.post(runnable);
    }

    /**
     * 一次性的等待器：保证「就绪」与「超时」两条路径只有一条能把任务放出去。
     *
     * <p>{@code fired} 用 {@code synchronized} 保护 —— {@code onAvailable} 来自 Connectivity 线程、
     * 超时来自主线程、{@code cancel()} 可能来自 Activity 销毁，三者会竞争同一个判定。</p>
     */
    private static final class Waiter extends ConnectivityManager.NetworkCallback implements Runnable, Handle {

        private final ConnectivityManager manager;
        private final Runnable onReady;
        @Nullable
        private final Runnable onTimeout;
        private final boolean timed;
        private boolean fired;

        Waiter(ConnectivityManager manager, long timeoutMs, Runnable onReady, Runnable onTimeout) {
            this.manager = manager;
            this.onReady = onReady;
            this.onTimeout = onTimeout;
            this.timed = timeoutMs > 0;
            // 先把超时排上，再注册回调：某些 ROM 会在 register 时<b>同步</b>派发
            // onAvailable/onCapabilitiesChanged，若顺序反过来，就会出现「已经 fire 了又把超时补上」
            // 的脏状态（虽有 fired 兜底，但没必要留）。
            if (timed) App.post(this, timeoutMs);
            try {
                manager.registerDefaultNetworkCallback(this);
            } catch (Exception e) {
                // 注册失败（个别 ROM 的系统服务异常）⇒ 退化为「超时」路径。
                //
                // ⚠ 这里刻意 fire **onTimeout** 而不是 onReady：当前全部调用点（leanback/mobile 的
                // HomeActivity 两处、VodFragment 一处）传的 onTimeout 都是空操作，于是注册失败时
                // 结果是「本次等待作废、什么也不做」——首页会停在启动画面「正在连接网络…」，
                // 但 onBackInvoked 仍可退出。
                //
                // ⛔ 不改成 fire(onReady)：那会造成**确定性忙循环**——立即 reloadConfig → 仍失败 →
                // error() → 再次 awaitConfigNetwork → 注册又失败 → 再立即 reloadConfig……
                // 「停在启动画面且有返回键出口」优于「CPU 与网络被打满」。注册失败在 API 24+
                // 除权限问题外实际不会发生，故不为它引入自旋风险。
                fire(onTimeout != null ? onTimeout : onReady);
            }
        }

        synchronized boolean fired() {
            return fired;
        }

        @Override
        public synchronized void cancel() {
            // 标记为已触发 + 注销回调：之后 onAvailable / 超时到达都是 no-op。
            if (fired) return;
            fired = true;
            App.removeCallbacks(this);
            unregister();
        }

        @Override
        public void onAvailable(@NonNull Network network) {
            // ⛔ 这里刻意<b>不再</b>校验 isOnline()：onAvailable 触发时系统的 VALIDATED 校验往往还没完成
            // （它是异步的），若在此处要求 VALIDATED，会导致回调被白白忽略、一路等到超时 ——
            // 那比原来「过早续播」更糟（续播永远不发生）。能收到 onAvailable 就说明默认网络已建立，
            // 此刻发起请求是合理的：最坏情况是详情请求再失败一次，由既有换源逻辑兜底。
            fire(onReady);
        }

        /**
         * 能力变化兜底。
         *
         * <p>注册回调的那一刻，网络可能<b>已经</b> available（只是尚未 VALIDATED）—— 此时系统的
         * {@code onAvailable} 不会再补发一次，只有 {@code onCapabilitiesChanged} 会随着校验完成到达。
         * 少了这条，就会出现「网明明通了、续播却一直等到超时」。</p>
         */
        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return;
            fire(onReady);
        }

        /** 超时：没等到网络就绪，交给调用方退化处理。 */
        @Override
        public void run() {
            fire(onTimeout != null ? onTimeout : onReady);
        }

        private synchronized void fire(@Nullable Runnable runnable) {
            if (fired) return;
            fired = true;
            App.removeCallbacks(this);
            unregister();
            post(runnable);
        }

        private void unregister() {
            try {
                manager.unregisterNetworkCallback(this);
            } catch (Exception ignored) {
            }
        }
    }
}
