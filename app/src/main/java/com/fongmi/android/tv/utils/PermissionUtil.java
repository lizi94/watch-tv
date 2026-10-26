package com.fongmi.android.tv.utils;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.impl.PermissionCallback;
import com.permissionx.guolindev.PermissionMediator;
import com.permissionx.guolindev.PermissionX;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class PermissionUtil {

    public static void requestFile(FragmentActivity activity, Consumer<Boolean> callback) {
        boolean requestAllFiles = canRequestAllFiles(activity);
        if (hasFilePermission(activity, requestAllFiles)) callback.accept(true);
        else post(activity, () -> requestFile(PermissionX.init(activity), requestAllFiles, callback));
    }

    public static void requestFile(Fragment fragment, Consumer<Boolean> callback) {
        FragmentActivity activity = fragment.requireActivity();
        boolean requestAllFiles = canRequestAllFiles(activity);
        if (hasFilePermission(activity, requestAllFiles)) callback.accept(true);
        else post(fragment, () -> requestFile(PermissionX.init(fragment), requestAllFiles, callback));
    }

    public static void requestNotify(FragmentActivity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (isGranted(activity, PermissionX.permission.POST_NOTIFICATIONS)) return;
        post(activity, () -> PermissionX.init(activity).permissions(PermissionX.permission.POST_NOTIFICATIONS).request(new PermissionCallback()));
    }

    /**
     * 冷启动一次性申请：通知权限（Android 13+）与设备文件权限，两者<b>串行</b>执行。
     *
     * <p><b>为什么必须在冷启动就拿到文件权限</b>：手机端「导入数据源 / 选择本地文件」走系统
     * SAF 选择器，{@code FileChooser.resolveFileUri} 只在 {@code File.canRead()} 成立时才采用
     * 真实文件路径，否则会把文件拷进 App 缓存目录，最终写进配置的 url 变成缓存绝对路径
     * （{@code file:///data/user/0/…/cache/chooser/<md5>/xxx.json}），而不是用户选择的
     * {@code file://Download/xxx.json}。等到选完文件再申请是<b>无效</b>的 —— 路径在 SAF
     * 回调时就已经定型，授权不会让路径重新解析。</p>
     *
     * <p><b>为什么串行</b>：PermissionX 同一时刻只处理一个请求，而 requestNotify / requestFile
     * 都经 {@code decorView.post} 投递，同帧并发发起会互相覆盖请求。</p>
     */
    public static void requestOnLaunch(FragmentActivity activity) {
        AtomicBoolean started = new AtomicBoolean();
        Runnable fileTask = () -> {
            if (started.compareAndSet(false, true)) requestFile(activity, granted -> {
            });
        };
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isGranted(activity, PermissionX.permission.POST_NOTIFICATIONS)) {
            post(activity, fileTask);
            return;
        }
        post(activity, () -> PermissionX.init(activity).permissions(PermissionX.permission.POST_NOTIFICATIONS).request(new PermissionCallback(allGranted -> fileTask.run())));
    }

    private static void requestFile(PermissionMediator mediator, boolean requestAllFiles, Consumer<Boolean> callback) {
        PermissionCallback permissionCallback = new PermissionCallback(callback);
        if (requestAllFiles) mediator.permissions().requestManageExternalStoragePermissionNow(permissionCallback);
        else mediator.permissions(storagePermission()).request(permissionCallback);
    }

    private static boolean canRequestAllFiles(FragmentActivity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        Uri uri = Uri.parse("package:" + activity.getPackageName());
        Intent app = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri);
        Intent all = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        return resolves(activity, app) || resolves(activity, all);
    }

    private static boolean hasAllFiles() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager();
    }

    private static boolean hasFilePermission(FragmentActivity activity, boolean requestAllFiles) {
        return hasAllFiles() || (!requestAllFiles && hasStoragePermission(activity));
    }

    private static boolean hasStoragePermission(FragmentActivity activity) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || isGranted(activity, storagePermission());
    }

    private static String storagePermission() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q ? Manifest.permission.WRITE_EXTERNAL_STORAGE : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private static boolean isGranted(FragmentActivity activity, String permission) {
        return PermissionX.isGranted(activity, permission);
    }

    private static boolean resolves(FragmentActivity activity, Intent intent) {
        return intent.resolveActivity(activity.getPackageManager()) != null;
    }

    private static void post(FragmentActivity activity, Runnable runnable) {
        post(activity, null, runnable);
    }

    private static void post(Fragment fragment, Runnable runnable) {
        post(fragment.requireActivity(), fragment, runnable);
    }

    private static void post(FragmentActivity activity, Fragment fragment, Runnable runnable) {
        activity.getWindow().getDecorView().post(() -> {
            if (isActive(activity, fragment)) runnable.run();
        });
    }

    private static boolean isActive(FragmentActivity activity, Fragment fragment) {
        return !activity.isFinishing() && !activity.isDestroyed() && (fragment == null || fragment.isAdded());
    }
}
