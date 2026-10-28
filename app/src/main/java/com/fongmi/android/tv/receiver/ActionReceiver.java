package com.fongmi.android.tv.receiver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.fongmi.android.tv.service.PlaybackService;

public class ActionReceiver extends BroadcastReceiver {

    public static PendingIntent getPendingIntent(Context context, String action) {
        return PendingIntent.getBroadcast(context, 0, new Intent(action).setPackage(context.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!PlaybackService.isRunning() || intent.getAction() == null) return;
        Intent serviceIntent = new Intent(context, PlaybackService.class);
        serviceIntent.setAction(intent.getAction());
        // 触发源是 PiP 遥控按钮：此时应用通常处于后台，Android 8+ 的后台 startService
        // 会抛 IllegalStateException ⇒ 走前台服务通道。
        try {
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
