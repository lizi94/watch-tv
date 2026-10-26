package com.fongmi.android.tv.utils;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ViewProgressBinding;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class Notify {

    public static final String DEFAULT = "default";
    public static final int ID = 9527;
    private AlertDialog mDialog;
    private Toast mToast;

    private static class Loader {
        static volatile Notify INSTANCE = new Notify();
    }

    private static Notify get() {
        return Loader.INSTANCE;
    }

    public static void createChannel() {
        NotificationManagerCompat notifyMgr = NotificationManagerCompat.from(App.get());
        notifyMgr.createNotificationChannel(new NotificationChannelCompat.Builder(DEFAULT, NotificationManagerCompat.IMPORTANCE_LOW).setName("TV").build());
    }

    public static String getError(int resId, Throwable e) {
        if (TextUtils.isEmpty(e.getMessage())) return ResUtil.getString(resId);
        return ResUtil.getString(resId) + "\n" + e.getMessage();
    }

    public static void show(Notification notification) {
        if (ContextCompat.checkSelfPermission(App.get(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManagerCompat.from(App.get()).notify(ID, notification);
    }

    public static void show(int resId) {
        if (resId != 0) show(ResUtil.getString(resId));
    }

    public static void show(String text) {
        if (!TextUtils.isEmpty(text)) get().makeText(text);
    }

    public static void progress(Context context) {
        dismiss();
        get().create(context);
    }

    public static void dismiss() {
        try {
            if (get().mDialog != null) get().mDialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    private void create(Context context) {
        // 这个进度框跑在「恢复默认设置 / 重新加载配置」的异步回调里，回调回来时用户很可能
        // 已经按返回离开了本页。此时对已 finish 的 Activity 弹框会抛 BadTokenException
        // （Unable to add window -- token ... is not valid），直接闪退 —— 只是个进度框，
        // 弹不出来就静默跳过，绝不能因此崩。
        if (!isAlive(context)) return;
        try {
            ViewProgressBinding binding = ViewProgressBinding.inflate(LayoutInflater.from(context));
            mDialog = new MaterialAlertDialogBuilder(context).setView(binding.getRoot()).create();
            Window window = mDialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawableResource(android.R.color.transparent);
            mDialog.show();
        } catch (Exception e) {
            mDialog = null;
        }
    }

    /** 只有仍存活、未销毁的 Activity 才允许挂弹窗。 */
    private static boolean isAlive(Context context) {
        if (context == null) return false;
        if (!(context instanceof Activity)) return true;
        Activity activity = (Activity) context;
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    private void makeText(String text) {
        // ⚠ 2026-10-16 加固：提示是老人获取信息的唯一渠道，任何异常都不能让它「静默消失」——
        // 整体兜底，出问题只打日志，绝不让异常冒到调用方（调用方多在事件回调/播放链路里）。
        try {
            makeTextInternal(text);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void makeTextInternal(String text) {
        if (mToast != null) mToast.cancel();
        // 电视版：app 自绘浮层（view_toast.xml 用 sp 字号），用当前 Activity 的 Resources 加载 ——
        // 只有 Activity 的 Resources 应用了「字号档位 × 屏幕补偿」的密度缩放，
        // 提示文字才会随字号档位等比放大。
        // ⚠ 2026-10-16 修「Toast 字号不一致」：App.activity() 为 null（App 退后台后由
        // 服务 / 回调触发的提示）时原先直接退回 App.get() 的 Resources —— 那是
        // **Application 侧 density（不随档位缩放）**，同一档位下前台弹的 Toast 大、
        // 后台弹的小，这就是「字号不一致」的来源。现在退路也按同一公式手动缩放字号
        // （公式与 BaseActivity#hackResources 严格同步，见 scaledTextPx）。
        // ⛔⛔ 2026-10-16 回退「CountDownTimer 续显 5 秒」（实测导致提示完全不显示）：
        //   原方案在 makeText 里 show() 一次后立刻 `new CountDownTimer(5000, 2800).start()`，
        //   想靠「周期重新 show」把显示时间续到 5 秒。但 **CountDownTimer.start() 会在同一帧
        //   立即回调一次 onTick**（AOSP：millisLeft >= mCountdownInterval 时先 onTick 再延时），
        //   于是每条提示实际在瞬间连续 show() 了两次、2.8 秒后再来一次 ——
        //   同一个 Toast 连续入队会被系统的 toast 队列/节流保护排队甚至丢弃
        //   ⇒ 实测表现就是「提示都不显示了」。**时长延长这条路用 Toast 走不通**：
        //   系统只给 LENGTH_SHORT(2s) / LENGTH_LONG(3.5s) 两档，且禁不起重复 show。
        //   ⇒ 现在回到**单次 show()**（可靠显示），可读性由 32sp 大字（ag_text_title）承担。
        //   真需要「更长停留」时的正解是**自绘浮层**（往当前 Activity 的 decorView 里 addView +
        //   postDelayed 移除，时长完全自控、不受 toast 队列限制），而不是重复 show 同一个 Toast。
        // 手机版：直接用系统 Toast，字号与时长保持系统正常状态。
        if (BuildConfig.FLAVOR.equals("leanback")) {
            Context context = App.activity() != null ? App.activity() : App.get();
            View view = LayoutInflater.from(context).inflate(R.layout.view_toast, null);
            TextView tv = view.findViewById(R.id.text);
            tv.setText(text);
            if (App.activity() == null) tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, scaledTextPx(context));
            mToast = new Toast(context);
            mToast.setDuration(Toast.LENGTH_LONG);
            mToast.setView(view);
            mToast.show();
        } else {
            mToast = Toast.makeText(App.get(), text, Toast.LENGTH_LONG);
            mToast.show();
        }
    }

    /**
     * 「无 Activity 退路」下的 Toast 字号（px）：基线 24sp（与 leanback 覆盖版 view_toast 的
     * {@code ag_text_body=24sp} 严格一致；⛔ 不能引用 {@code R.dimen.ag_text_body} ——
     * 该令牌只在 leanback 的 dimens 里，mobile 变体编译 main 源码时没有这个符号），
     * 乘上与 {@code BaseActivity#hackResources} **完全同一公式**算出的缩放比
     * （屏宽÷1920 × 字号档位 × 大屏补偿），再乘系统用户字体缩放（scaledDensity/density），
     * 与 Activity 内 inflate 出来的 sp→px 换算路径完全一致。
     *
     * <p>⛔ 公式两处必须同步维护（此处与 BaseActivity#hackResources / screenSizeFactor）：
     * 抽公共方法会让「全站缩放根基」暴露给非 Activity 调用方，风险大于收益，故刻意复刻。
     * 改任一处时另一处必须同步。</p>
     */
    private static float scaledTextPx(Context context) {
        try {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            if (metrics.widthPixels <= 0 || metrics.density <= 0) return 24f * metrics.density;
            float targetDensity = metrics.widthPixels / 1920f * Setting.getFontScale() * screenSizeFactor(metrics);
            return 24f * targetDensity * (metrics.scaledDensity / metrics.density);
        } catch (Exception e) {
            return 24f * context.getResources().getDisplayMetrics().density;
        }
    }

    /** 28 寸以上屏幕补偿，公式与 {@code BaseActivity#screenSizeFactor} 逐行等价（必须同步维护）。 */
    private static float screenSizeFactor(DisplayMetrics metrics) {
        try {
            float xdpi = metrics.xdpi;
            float ydpi = metrics.ydpi;
            if (xdpi <= 0 || ydpi <= 0) return 1f;
            float widthIn = metrics.widthPixels / xdpi;
            float heightIn = metrics.heightPixels / ydpi;
            float diagonal = (float) Math.sqrt(widthIn * widthIn + heightIn * heightIn);
            if (diagonal < 26 || diagonal > 120) return 1f;
            return Math.max(1f, Math.min(1.6f, 1f + (diagonal - 28f) * 0.0115f));
        } catch (Exception e) {
            return 1f;
        }
    }
}
