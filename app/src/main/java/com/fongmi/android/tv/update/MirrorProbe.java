package com.fongmi.android.tv.update;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 加速节点测速。
 *
 * <h3>探针为什么是「本版 APK 地址 + Range 1 字节」</h3>
 *
 * <p>R163 之前探的是 {@code https://github.com/robots.txt}。实测（2026-11-24）8 个节点对它分别返回
 * 404 / 403 / 429 / 302 —— 镜像只代理 release 下载路径，robots.txt 一律被拒；而旧实现把「非 2xx」
 * 直接判成超时 ⇒ <b>8 行全部显示「超时」</b>，而同一时刻 6 个节点其实可下载。
 * 现在改为探测<b>本次真正要下载的那个 APK 地址</b>，带 {@code Range: bytes=0-0} 且只读响应头就关闭 ——
 * 既不拉全量，又能测出「这个节点此刻能不能给包」。</p>
 *
 * <h3>四态判定</h3>
 *
 * <ul>
 *   <li>2xx → 可用，记往返毫秒；</li>
 *   <li>3xx → 允许重定向（{@code cdn.gh-proxy.com} 这类），跟随后按最终状态再判；</li>
 *   <li>4xx / 5xx → {@link Mirror#DELAY_BLOCKED}「不可用」（连得上但拒绝本次请求，常见 403/429）；</li>
 *   <li>连不上 / 超时 → {@link Mirror#DELAY_TIMEOUT}「超时」，并<b>复测一次</b>取较优。</li>
 * </ul>
 *
 * <p>⛔ 不能用 {@code OkHttp.client(timeout)}：catvod 的实现把 {@code followRedirects} 关掉了
 * （见 {@code OkHttp.java}），302 型节点会被误判成失败。这里基于 {@code OkHttp.client()} 复制一份
 * 并显式打开重定向。</p>
 *
 * <p>测速全程在后台线程池里跑，结果写回 {@link Mirror} 实例本身；全部结束后回主线程回调，
 * UI 直接读实例上的值刷新即可。</p>
 */
public class MirrorProbe {

    private static final String TAG = "MirrorProbe";
    /** 单节点超时。实测 TTFB 0.3~2.7s，留到 4s 以覆盖境外节点首次 TLS 握手。 */
    private static final long TIMEOUT_MS = 4000;
    /** 复测间隔：首次握手抖动在国内宽带上很常见，失败一次不代表节点不可用。 */
    private static final long RETRY_DELAY_MS = 1200;
    /** 取消标记用独立的 tag，避免和播放器/其它下载的 OkHttp.cancel 互相误伤。 */
    private static final String CALL_TAG = "update-probe";
    private static final String UA = "watch-tv-update-probe";

    private static volatile boolean running;
    private static volatile OkHttpClient client;
    /** 测速进行中又来了新的调用方：排队，测完一起回调，绝不丢回调。 */
    private static final List<Runnable> WAITERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static boolean isRunning() {
        return running;
    }

    /**
     * 并发探测所有「可探测」节点。
     *
     * @param list     下载源列表，测速结果原地写回
     * @param probeUrl 探针目标（本版 APK 的下载地址）；为空时退化为「节点根路径」，只判可达
     * @param onDone   全部结束后的主线程回调（可能为零个节点，也会回调）
     */
    public static void probe(List<Mirror> list, String probeUrl, Runnable onDone) {
        if (running) {
            // 已有测速在跑：排队等它结束再回调。
            // ⛔ 不能直接 return —— 重建后的弹窗会永远停在「正在测速」。
            if (onDone != null) WAITERS.add(onDone);
            return;
        }
        List<Mirror> targets = new ArrayList<>();
        for (Mirror mirror : list) {
            if (!mirror.isProbeable()) continue;
            mirror.setDelay(Mirror.DELAY_UNKNOWN);
            mirror.setCost(Integer.MAX_VALUE);
            targets.add(mirror);
        }
        if (targets.isEmpty()) {
            App.post(onDone);
            return;
        }
        running = true;
        AtomicInteger left = new AtomicInteger(targets.size());
        for (Mirror mirror : targets) {
            // 用 largeExecutor（20 线程）：8 个节点并行才有意义，串行测完要 20 秒以上，
            // 老人等不了，「自动」模式也就失去意义。
            Task.submitLarge(() -> {
                try {
                    int state = measure(mirror, probeUrl);
                    // 失败复测一次取较优：可用优先于不可用；同为失败时「有响应」优于「没响应」。
                    if (state < 0) {
                        sleep(RETRY_DELAY_MS);
                        int again = measure(mirror, probeUrl);
                        if (again > 0 || (state == Mirror.DELAY_TIMEOUT && again == Mirror.DELAY_BLOCKED)) state = again;
                    }
                    mirror.setDelay(state);
                } catch (Throwable e) {
                    mirror.setDelay(Mirror.DELAY_TIMEOUT);
                } finally {
                    if (left.decrementAndGet() == 0) {
                        running = false;
                        App.post(() -> {
                            if (onDone != null) onDone.run();
                            for (Runnable waiter : WAITERS) waiter.run();
                            WAITERS.clear();
                        });
                    }
                }
            });
        }
    }

    private static int measure(Mirror mirror, String probeUrl) {
        boolean rootOnly = TextUtils.isEmpty(probeUrl);
        String url = rootOnly ? mirror.getKey() : mirror.wrap(probeUrl);
        long start = SystemClock.elapsedRealtime();
        Request.Builder builder = new Request.Builder().url(url).tag(CALL_TAG).header("User-Agent", UA);
        // 只取 1 字节：镜像普遍支持 Range；个别不支持会回 200 + 全量，但我们只读响应头、立即 close。
        if (!rootOnly) builder.header("Range", "bytes=0-0");
        try (Response response = client().newCall(builder.build()).execute()) {
            int cost = (int) Math.min(Integer.MAX_VALUE, SystemClock.elapsedRealtime() - start);
            if (cost < mirror.getCost()) mirror.setCost(cost);
            if (response.isSuccessful()) return cost;
            Log.d(TAG, "probe " + mirror.getName() + " http " + response.code());
            return Mirror.DELAY_BLOCKED;
        } catch (Throwable e) {
            Log.d(TAG, "probe " + mirror.getName() + " failed: " + e.getMessage());
            return Mirror.DELAY_TIMEOUT;
        }
    }

    /** 复用同一个 client：newBuilder 出来的实例共享连接池与调度器，避免每次探测都新建。 */
    private static OkHttpClient client() {
        if (client == null) {
            synchronized (MirrorProbe.class) {
                if (client == null) {
                    client = OkHttp.client().newBuilder()
                            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build();
                }
            }
        }
        return client;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 主动放弃全部在途探测（离开弹窗时调用，避免回调打到已销毁的界面）。 */
    public static void cancel() {
        running = false;
        OkHttp.cancel(CALL_TAG);
    }
}
