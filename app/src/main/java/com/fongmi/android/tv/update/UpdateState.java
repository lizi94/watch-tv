package com.fongmi.android.tv.update;

import com.fongmi.android.tv.App;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 在线更新的**唯一**状态源（进程内单例，无持久化）。
 *
 * <p>为什么要有这一层：检查更新、后台下载服务、设置页版本行、各个弹窗分别活在不同组件里，
 * 谁都不能保证自己是最后一个存活的。把它们之间的数据流收敛到一个静态状态 + 监听器列表，
 * 好处是：</p>
 * <ul>
 *   <li>下载在 Service 里继续，页面重建后重新读状态就能接上进度（不需要绑定 Service）；</li>
 *   <li>版本行的六种状态和弹窗内容来自同一份数据，不会出现「行里说下载中、弹窗说已取消」；</li>
 *   <li>不需要引入 EventBus 事件类（那会动到 eventBusIndex 的生成）。</li>
 * </ul>
 *
 * <p>⚠ 所有写操作都在主线程执行（内部已 {@code App.post}），读操作任意线程可读
 * （字段 volatile）。</p>
 */
public class UpdateState {

    public static final int PHASE_IDLE = 0;
    /** 正在请求 Release 信息（可能叠加多个源重试）。 */
    public static final int PHASE_CHECKING = 1;
    /** 已是最新。 */
    public static final int PHASE_LATEST = 2;
    /** 有新版本，info 有效。 */
    public static final int PHASE_AVAILABLE = 3;
    /** 检查失败（无网 / 全部源不可用 / JSON 异常）。 */
    public static final int PHASE_FAILED = 4;
    /** 下载中，进度字段有效。 */
    public static final int PHASE_DOWNLOADING = 5;
    /** 下载完成，file 有效。 */
    public static final int PHASE_DOWNLOADED = 6;

    public interface Listener {

        void onUpdateStateChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile int phase = PHASE_IDLE;
    private static volatile UpdateInfo info;
    private static volatile Mirror mirror;
    private static volatile int progress;
    private static volatile long downloaded;
    private static volatile long total;
    /** 已下载字节/秒，用于「1.8 MB/s」这类展示；0 表示还没算出来。 */
    private static volatile long speed;
    private static volatile String error = "";
    private static volatile File file;
    /** 上次进度上报的时刻（elapsedRealtime），用于节流。 */
    private static volatile long lastProgressAt;

    public static int getPhase() {
        return phase;
    }

    public static UpdateInfo getInfo() {
        return info;
    }

    public static Mirror getMirror() {
        return mirror;
    }

    public static int getProgress() {
        return progress;
    }

    public static long getDownloaded() {
        return downloaded;
    }

    public static long getTotal() {
        return total;
    }

    public static long getSpeed() {
        return speed;
    }

    public static String getError() {
        return error == null ? "" : error;
    }

    public static File getFile() {
        return file;
    }

    public static boolean isBusy() {
        return phase == PHASE_CHECKING || phase == PHASE_DOWNLOADING;
    }

    public static boolean isDownloading() {
        return phase == PHASE_DOWNLOADING;
    }

    public static boolean isDownloaded() {
        return phase == PHASE_DOWNLOADED;
    }

    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    // ==================== 写入入口：全部收敛在这里，保证「改状态 = 必定通知」 ====================

    public static void checking() {
        mutate(() -> {
            phase = PHASE_CHECKING;
            error = "";
        });
    }

    public static void latest() {
        mutate(() -> {
            phase = PHASE_LATEST;
            info = null;
            error = "";
        });
    }

    public static void available(UpdateInfo value) {
        mutate(() -> {
            phase = PHASE_AVAILABLE;
            info = value;
            error = "";
        });
    }

    public static void failed(String message) {
        mutate(() -> {
            phase = PHASE_FAILED;
            error = message == null ? "" : message;
        });
    }

    public static void downloading(UpdateInfo value, Mirror selected, long totalBytes) {
        lastProgressAt = 0;
        mutate(() -> {
            phase = PHASE_DOWNLOADING;
            info = value;
            mirror = selected;
            total = totalBytes;
            progress = 0;
            downloaded = 0;
            speed = 0;
            error = "";
            file = null;
        });
    }

    /**
     * 进度更新（由下载线程高频调用）。
     *
     * <p>⚠ 必须节流：Download 的写盘缓冲是 16KB，94MB 安装包会产生约 6000 次回调。
     * 每次都 {@code App.post} 到主线程不仅白烧 CPU，还会把主线程消息队列塞满、
     * 导致列表滚动与焦点移动卡顿。这里只在「百分比变了」或「距上次刷新超过 300ms」时上报。</p>
     */
    public static void progress(long downloadedBytes, long totalBytes, long bytesPerSecond) {
        int next = totalBytes > 0 ? (int) Math.min(100, downloadedBytes * 100 / totalBytes) : 0;
        long now = android.os.SystemClock.elapsedRealtime();
        if (next == progress && now - lastProgressAt < 300) return;
        lastProgressAt = now;
        mutate(() -> {
            phase = PHASE_DOWNLOADING;
            downloaded = downloadedBytes;
            speed = bytesPerSecond;
            if (totalBytes > 0) total = totalBytes;
            if (total > 0) progress = next;
        });
    }

    public static void downloaded(File apk) {
        mutate(() -> {
            phase = PHASE_DOWNLOADED;
            progress = 100;
            file = apk;
            speed = 0;
            if (total > 0) downloaded = total;
        });
    }

    public static void canceled() {
        mutate(() -> {
            phase = PHASE_IDLE;
            progress = 0;
            downloaded = 0;
            total = 0;
            speed = 0;
            file = null;
        });
    }

    /**
     * 统一的状态写入口：在主线程改字段，改完再通知。
     *
     * <p>⚠ 这里**不能**改成「子类各自 App.post」—— 后台服务与网络回调都不在主线程，
     * 漏掉一层 post 就会出现「字段改了但界面不刷新」，而且只在部分机型上偶发。</p>
     */
    private static void mutate(Runnable action) {
        App.post(() -> {
            try {
                action.run();
            } finally {
                notifyChanged();
            }
        });
    }

    private static void notifyChanged() {
        for (Listener listener : LISTENERS) {
            try {
                listener.onUpdateStateChanged();
            } catch (Throwable e) {
                // 单个监听器（已销毁的弹窗等）出错不能影响其它监听器
                e.printStackTrace();
            }
        }
    }
}
