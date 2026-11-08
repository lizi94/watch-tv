package com.fongmi.android.tv.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.utils.BootSession;

/**
 * 开机后预载直播配置；R137（F3）起额外处理「待机唤醒式快速开机」。
 *
 * <p>部分「快速开机」固件的 {@code QUICKBOOT_POWERON} 实为待机唤醒：
 * {@code elapsedRealtime} 未重置（不在开机 5 分钟窗内），待机时又可能不发
 * {@code SCREEN_OFF} 广播 —— 首页的熄屏/亮屏快照判据拿不到，唤醒直达会漏判。
 * 在开机窗之外收到 QUICKBOOT 时打上快速开机唤醒标记（90 秒内有效、用掉即清），
 * 首页把它也算进开机会话，与自启树行为对齐。</p>
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !isBootAction(intent.getAction())) return;
        // R137（F3）：见类注释 —— 窗外送达的 QUICKBOOT = 待机唤醒式快速开机，打唤醒标记
        if ("android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())
                && SystemClock.elapsedRealtime() > BootSession.BOOT_PHASE_MS) {
            BootSession.markQuickBootWake();
        }
        registerCallback();
    }

    private boolean isBootAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action) || "android.intent.action.QUICKBOOT_POWERON".equals(action);
    }

    private void registerCallback() {
        ((ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(new Callback());
    }

    static class Callback extends ConnectivityManager.NetworkCallback {

        @Override
        public void onAvailable(@NonNull Network network) {
            doJob();
        }

        @Override
        public void onLost(@NonNull Network network) {
        }

        private void doJob() {
            LiveConfig.get().init().load();
            ((ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(this);
        }
    }
}
