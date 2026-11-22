package com.fongmi.android.tv.update;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 下载安装包的前台服务。
 *
 * <h3>为什么必须前台服务</h3>
 * 94MB 安装包在电视上要下 1 分钟以上，用户按返回离开设置页很常见。若把下载放在页面的
 * 生命周期里，退出即中断；放前台服务后**下载与界面解耦**，界面只是观察者
 * （见 {@link UpdateState}），回到设置页点版本行还能接着看进度。
 *
 * <h3>与系统约束的对应关系（targetSdk 37）</h3>
 * <ul>
 *   <li>Android 14+ 要求声明 {@code foregroundServiceType}，本服务用 {@code dataSync}，
 *       对应权限 {@code FOREGROUND_SERVICE_DATA_SYNC}（见 AndroidManifest）；</li>
 *   <li>Android 15+ 对 dataSync 类型有 6 小时上限，超时系统会回调
 *       {@link #onTimeout(int)} —— 必须停掉自己，否则进程被强杀会留下半个 apk；</li>
 *   <li>前台服务只能从「应用可见」时启动。本服务只由用户点击触发，天然满足；
 *       从通知栏「重新下载」也走 Activity，不在后台偷偷拉起。</li>
 * </ul>
 */
public class UpdateService extends Service {

    private static final String TAG = "UpdateService";

    public static final String ACTION_START = BuildConfig.APPLICATION_ID + ".action.UPDATE_START";
    public static final String ACTION_CANCEL = BuildConfig.APPLICATION_ID + ".action.UPDATE_CANCEL";

    private static final String EXTRA_URL = "url";
    private static final String EXTRA_VERSION = "version";

    /** 通知 ID：与 Notify.ID(9527) 错开，避免覆盖播放通知。 */
    public static final int NOTIFY_PROGRESS = 9528;
    public static final int NOTIFY_DONE = 9529;

    /** 进度通知的节流间隔：点播/下载的进度回调很密，太频繁 notify 会拖慢主线程。 */
    private static final long NOTIFY_INTERVAL = 400;

    private static volatile Call current;

    private volatile boolean canceled;
    private long lastNotifyAt;
    /** 本次下载的起始时刻（elapsedRealtime），用于算平均速度。 */
    private long startAt;
    private File target;

    // ==================== 对外入口 ====================

    /**
     * 启动下载。调用前应先通过 {@link UpdateState#downloading} 把 info / total 写进状态，
     * 这样界面切回来时能立刻显示「正在下载 xxx」而不是空白。
     */
    public static void start(Context context, String url, String version) {
        if (TextUtils.isEmpty(url)) return;
        Intent intent = new Intent(context, UpdateService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_VERSION, version);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable e) {
            // Android 12+ 后台启动前台服务会抛 ForegroundServiceStartNotAllowedException。
            // 本服务只由点击触发，正常不会走到这里；真走到了也不能崩，提示用户回前台再试。
            e.printStackTrace();
            UpdateState.failed(ResUtil.getString(R.string.update_check_failed_network));
        }
    }

    public static void cancel(Context context) {
        try {
            Intent intent = new Intent(context, UpdateService.class);
            intent.setAction(ACTION_CANCEL);
            context.startService(intent);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    // ==================== Service 生命周期 ====================

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (ACTION_CANCEL.equals(action)) {
            canceled = true;
            abortCurrent();
            finish();
            return START_NOT_STICKY;
        }
        String url = intent == null ? null : intent.getStringExtra(EXTRA_URL);
        String version = intent == null ? "" : String.valueOf(intent.getStringExtra(EXTRA_VERSION));
        if (TextUtils.isEmpty(url)) {
            finish();
            return START_NOT_STICKY;
        }
        // 必须尽快进入前台态（系统给 5 秒），所以先挂一条「准备中」通知再开线程
        enterForeground(buildProgress(version, 0, 0, 0));
        if (current != null) {
            // 理论上不会重复启动；真重入时先把旧的掐掉，避免两个线程写同一个文件
            abortCurrent();
        }
        canceled = false;
        download(url, version);
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId) {
        // Android 15+ dataSync 前台服务有累计时长上限，超时必须主动停，不能装作没发生
        Log.w(TAG, "foreground service timeout, stop");
        canceled = true;
        abortCurrent();
        UpdateState.failed(ResUtil.getString(R.string.update_check_failed_network));
        finish();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        abortCurrent();
        super.onDestroy();
    }

    // ==================== 下载 ====================

    private void download(String url, String version) {
        target = new File(Path.cache(), "update.apk");
        // ⛔ 用独立线程而不是 Task 线程池：Task.submit 只有 5 个线程，且被测速 / 检查更新共用，
        //    一旦被占满，下载会排在后面迟迟不开始，而前台通知已经挂出去了（用户看到 0% 卡住）。
        Thread worker = new Thread(() -> {
            Call call = null;
            try {
                call = OkHttp.newCall(url, TAG);
                current = call;
                try (Response response = call.execute()) {
                    if (!response.isSuccessful()) throw new IllegalStateException("HTTP " + response.code());
                    ResponseBody body = response.body();
                    write(body.byteStream(), body.contentLength(), version);
                }
                if (canceled) return;
                UpdateState.downloaded(target);
                // 先移除进度通知再发「下载完成」，否则两条通知会同时在通知栏里
                removeProgressForeground();
                showDone(version);
                stopSelf();
            } catch (Throwable e) {
                Log.e(TAG, "download failed: " + e.getMessage());
                if (canceled) {
                    UpdateState.canceled();
                } else {
                    Path.clear(target);
                    UpdateState.failed(ResUtil.getString(R.string.update_download_failed));
                }
                removeProgressForeground();
                stopSelf();
            } finally {
                if (current == call) current = null;
            }
        }, "update-download");
        worker.start();
    }

    private void write(InputStream stream, long length, String version) throws Exception {
        startAt = android.os.SystemClock.elapsedRealtime();
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(stream); FileOutputStream output = new FileOutputStream(Path.create(target))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (canceled || Thread.interrupted()) throw new InterruptedException("canceled");
                total += read;
                output.write(buffer, 0, read);
                UpdateState.progress(total, length, speedOf(total));
                notifyProgress(version, total, length);
            }
            if (total == 0) throw new IllegalStateException("empty download");
        }
    }

    /**
     * 平均速度（字节/秒）。
     *
     * <p>刻意用「累计字节 ÷ 累计耗时」而不是瞬时速度：瞬时值会因为 TCP 窗口、镜像缓存抖动
     * 在通知栏里反复跳变；平均速度单调平滑，用户对「还剩多久」的判断也更准。</p>
     */
    private long speedOf(long total) {
        long cost = android.os.SystemClock.elapsedRealtime() - startAt;
        if (cost < 500) return 0;
        return total * 1000 / cost;
    }

    private void notifyProgress(String version, long downloaded, long total) {
        long now = System.currentTimeMillis();
        if (now - lastNotifyAt < NOTIFY_INTERVAL && downloaded < total) return;
        lastNotifyAt = now;
        int progress = total > 0 ? (int) Math.min(100, downloaded * 100 / total) : 0;
        try {
            NotificationManagerCompat.from(this).notify(NOTIFY_PROGRESS, buildProgress(version, progress, downloaded, total));
        } catch (Throwable e) {
            // 缺 POST_NOTIFICATIONS 时通知发不出去，但下载必须继续
            e.printStackTrace();
        }
    }

    // ==================== 通知 ====================

    private void enterForeground(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFY_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFY_PROGRESS, notification);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private Notification buildProgress(String version, int progress, long downloaded, long total) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Notify.DEFAULT)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.update_downloading, version))
                .setContentText(progressText(progress, downloaded, total))
                .setProgress(100, progress, total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(shellIntent());
        NotificationCompat.BigTextStyle style = new NotificationCompat.BigTextStyle().bigText(progressText(progress, downloaded, total));
        builder.setStyle(style);
        builder.addAction(0, getString(R.string.update_cancel), cancelIntent());
        return builder.build();
    }

    private String progressText(int progress, long downloaded, long total) {
        if (total <= 0) return getString(R.string.update_downloading_unknown);
        return String.format(Locale.US, "%d%%  %s / %s", progress, size(downloaded), size(total));
    }

    private void showDone(String version) {
        try {
            boolean canInstall = canInstall();
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Notify.DEFAULT)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(getString(R.string.update_download_done))
                    .setContentText(getString(R.string.update_download_done_desc, version))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    // 有权限时点通知直接进系统安装器；没权限就回应用（在应用里引导授权，
                    // 直接从通知硬启安装只会静默失败，用户完全看不懂发生了什么）
                    .setContentIntent(canInstall ? installPending() : shellIntent());
            if (canInstall) builder.addAction(0, getString(R.string.update_install), installPending());
            NotificationManagerCompat.from(this).notify(NOTIFY_DONE, builder.build());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private PendingIntent shellIntent() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent == null) intent = new Intent();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, intent, pendingFlags());
    }

    private PendingIntent cancelIntent() {
        Intent intent = new Intent(this, UpdateService.class);
        intent.setAction(ACTION_CANCEL);
        return PendingIntent.getService(this, 1, intent, pendingFlags());
    }

    /**
     * 「安装」动作 → 系统安装器。
     *
     * <p>用 {@code PendingIntent.getActivity} 而不是「广播 → 再 startActivity」：
     * 用户点通知动作本身就是前台交互，系统允许直接起安装界面；而广播里再起 Activity
     * 在 Android 10+ 会被后台启动限制拦掉。</p>
     */
    private PendingIntent installPending() {
        return PendingIntent.getActivity(this, 2, installViewIntent(), pendingFlags());
    }

    private Intent installViewIntent() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri(), "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private Uri apkUri() {
        return FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", target);
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls();
    }

    private int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    /** 摘掉前台态并同时移除进度通知（下载完成后由「下载完成」通知接管）。 */
    private void removeProgressForeground() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void abortCurrent() {
        Call call = current;
        if (call != null) {
            try {
                call.cancel();
            } catch (Throwable ignored) {
            }
        }
        current = null;
    }

    private void finish() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        try {
            NotificationManagerCompat.from(this).cancel(NOTIFY_PROGRESS);
        } catch (Throwable ignored) {
        }
        stopSelf();
    }

    // ==================== 安装 ====================

    /**
     * 拉起系统安装器。
     *
     * <p>走 FileProvider（authority 与 Manifest 里的 {@code ${applicationId}.provider} 一致），
     * 因为 targetSdk 37 下不允许用 {@code file://} 分享。</p>
     *
     * <p>Android 8+ 还需「安装未知应用」授权：没授权时**不要**直接 try 安装（会静默失败），
     * 而是跳到系统授权页，让用户点一下回来再装。</p>
     */
    public static void install(Context context, File apk) {
        if (apk == null || !apk.exists()) {
            Notify.show(R.string.update_file_missing);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.getPackageManager().canRequestPackageInstalls()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Notify.show(R.string.update_need_install_permission);
                return;
            }
            Uri uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
        } catch (Throwable e) {
            e.printStackTrace();
            Notify.show(R.string.update_install_failed);
        }
    }

    /** 字节数 → 「94.6 MB」。用于通知与弹窗里的体量展示。 */
    public static String size(long bytes) {
        if (bytes <= 0) return "0 MB";
        double mb = bytes / 1024.0 / 1024.0;
        if (mb >= 1) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%d KB", Math.max(1, bytes / 1024));
    }
}
