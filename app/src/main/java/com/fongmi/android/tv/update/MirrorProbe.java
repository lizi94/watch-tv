package com.fongmi.android.tv.update;

import android.os.SystemClock;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Response;

/**
 * 加速节点测速：并发请求一个**极小且稳定**的 GitHub 文件，取往返耗时作为延迟。
 *
 * <h3>为什么不直接 HEAD 安装包</h3>
 * 部分镜像对 HEAD 直接返回 405、或对 APK 走特殊处理，测出来的延迟与真实下载能力无关；
 * 更危险的是某些镜像**忽略 Range 头**，拿 APK 当探针会把 94MB 整个拉下来。
 * 因此固定探 {@code https://github.com/robots.txt}（几百字节，GitHub 永远有），
 * 既能验证「节点通不通」，也能体会「走节点的额外开销」，且流量可忽略。
 *
 * <p>测速全程在后台线程池里跑，结果写回 {@link Mirror} 实例本身；
 * 全部结束后回主线程回调，UI 直接读实例上的值刷新即可。</p>
 */
public class MirrorProbe {

    private static final String TAG = "MirrorProbe";
    private static final String PROBE_URL = "https://github.com/robots.txt";
    /** 单节点超时：大于 3 秒的节点即便可用，下载体验也远差于直连，没必要等。 */
    private static final long TIMEOUT_MS = 3000;
    /** 取消标记用独立的 tag，避免和播放器/其它下载的 OkHttp.cancel 互相误伤。 */
    private static final String CALL_TAG = "update-probe";

    private static volatile boolean running;
    /** 测速进行中又来了新的调用方：排队，测完一起回调，绝不丢回调。 */
    private static final List<Runnable> WAITERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static boolean isRunning() {
        return running;
    }

    /**
     * 并发探测所有「可探测」节点。
     *
     * @param list   下载源列表，测速结果原地写回
     * @param onDone 全部结束后的主线程回调（可能为零个节点，也会回调）
     */
    public static void probe(List<Mirror> list, Runnable onDone) {
        if (running) {
            // 已有测速在跑：排队等它结束再回调。
            // ⛔ 不能直接 return —— 重建后的弹窗会永远停在「正在测速…」。
            if (onDone != null) WAITERS.add(onDone);
            return;
        }
        List<Mirror> targets = new ArrayList<>();
        for (Mirror mirror : list) {
            if (!mirror.isProbeable()) continue;
            mirror.setDelay(Mirror.DELAY_UNKNOWN);
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
                    mirror.setDelay(measure(mirror));
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

    private static int measure(Mirror mirror) {
        long start = SystemClock.elapsedRealtime();
        try (Response response = OkHttp.newCall(OkHttp.client(TIMEOUT_MS), mirror.wrap(PROBE_URL), CALL_TAG).execute()) {
            long cost = SystemClock.elapsedRealtime() - start;
            if (!response.isSuccessful()) {
                Log.d(TAG, "probe " + mirror.getName() + " http " + response.code());
                return Mirror.DELAY_TIMEOUT;
            }
            // Long 强转前先夹住：极端慢节点可能超过 int 上限，按超时处理更安全
            return cost > Integer.MAX_VALUE ? Mirror.DELAY_TIMEOUT : (int) cost;
        } catch (Exception e) {
            Log.d(TAG, "probe " + mirror.getName() + " failed: " + e.getMessage());
            return Mirror.DELAY_TIMEOUT;
        }
    }

    /** 主动放弃全部在途探测（离开弹窗时调用，避免回调打到已销毁的界面）。 */
    public static void cancel() {
        running = false;
        OkHttp.cancel(CALL_TAG);
    }
}
