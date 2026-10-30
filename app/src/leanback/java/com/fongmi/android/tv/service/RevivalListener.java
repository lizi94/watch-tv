package com.fongmi.android.tv.service;

import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import com.fongmi.android.tv.utils.BootLauncher;

/**
 * 通知监听保活（2026-10-29 新增，思路来自 aerial-tv 的 {@code RevivalListener}）。
 *
 * <p><b>它不读任何通知内容。</b>声明这个服务的目的只有一个：让系统把我们「绑定住」。
 * 系统绑定通知监听服务时会拉起本 App 进程；进程被清理后，系统还会重新绑定，
 * 等于多了一条「系统会主动来找我」的通道。</p>
 *
 * <p><b>为什么需要它</b>：部分固件（TCL 等）会在待机或后台清理第三方 App。
 * 无障碍守护能抢回前台，但如果进程已经被清掉就无从抢起。通知监听与无障碍服务互补：
 * 一个负责「被清理后把进程拉回来」，一个负责「回到桌面就把界面顶上去」。</p>
 *
 * <p><b>隐私说明</b>：本类刻意<b>不覆写</b> {@code onNotificationPosted} / {@code onNotificationRemoved}，
 * 不读取、不存储、不上传任何通知内容。授权只是为了拿到绑定本身。</p>
 */
public class RevivalListener extends NotificationListenerService {

    private static final String TAG = "Revival";

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "通知监听已连接（仅用于保活，不读取通知内容）");
        // 2026-10-30（R103）：开机阶段顺手把界面顶上 —— 这条绑定早于 BOOT_COMPLETED，
        // 且是「系统主动来找我」，不依赖厂商自启白名单。运行期重连（切换通知使用权、
        // 服务被清理后系统重连）只保留保活意义，绝不抢屏，否则用户正看着别的应用会被弹走。
        if (SystemClock.elapsedRealtime() > BootLauncher.BOOT_WINDOW_MS) return;
        BootLauncher.forBoot(this, "通知监听连接");
    }
}
