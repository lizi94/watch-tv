package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public abstract class BaseAlertDialog extends DialogFragment {

    protected abstract ViewBinding getBinding();

    protected abstract MaterialAlertDialogBuilder getBuilder();

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        // 必须排在 initView() 之前：配置变更 / 进程被回收后恢复时 Fragment 会被重建，
        // 子类用 setter 设置的字段会回落成默认值，得先把它们还原回来再建视图。
        restoreArguments();
        Dialog dialog = getBuilder().create();
        initView();
        initEvent();
        return dialog;
    }

    protected MaterialAlertDialogBuilder builder() {
        return new MaterialAlertDialogBuilder(requireActivity());
    }

    protected void initView() {
    }

    protected void initEvent() {
    }

    // ==================== 配置字段持久化约定 ====================
    //
    // DialogFragment 在「配置变更（HDMI 重协商、切换字号档位、旋转、改显示大小）」或「进程被
    // 系统回收后从最近任务恢复」时，会被 FragmentManager 用**无参构造**重建：子类里由 setter
    // 传进来的字段全部回落成默认值（int = 0、boolean = false、String = null），随后
    // onCreateDialog 会重跑 initView()，于是出现
    //   ① Resources.NotFoundException —— 如 ConfirmDialog 的 binding.message.setText(0)，
    //      这是必然闪退；
    //   ② 更隐蔽的数据错误 —— 如 ConfigDialog.type 退成 0，会显示、甚至写入另一种类型的配置。
    //
    // 约定：字段的 setter 里调一次 persist(key, value) 写进 arguments，再覆写
    // restoreArguments() 把它读回来。arguments 由系统自动保存 / 恢复，是 Fragment 重建
    // 唯一可靠的通道。本约定不改动任何布局与交互，只在重建这条路径上把值还原。
    //
    // 注意：persist 只能在 show() 之前（即 setter 阶段）调用，此时 Fragment 尚未 attach。

    /** 子类覆写：从 arguments 还原自己的配置字段（在 initView() 之前执行）。 */
    protected void restoreArguments() {
    }

    protected void persist(String key, int value) {
        arguments().putInt(key, value);
    }

    protected void persist(String key, boolean value) {
        arguments().putBoolean(key, value);
    }

    protected void persist(String key, String value) {
        arguments().putString(key, value);
    }

    protected void persist(String key, CharSequence value) {
        arguments().putCharSequence(key, value);
    }

    protected void persist(String key, String[] value) {
        arguments().putStringArray(key, value);
    }

    protected int persistedInt(String key, int def) {
        return arguments().getInt(key, def);
    }

    protected boolean persistedBoolean(String key, boolean def) {
        return arguments().getBoolean(key, def);
    }

    protected String persistedString(String key, String def) {
        return arguments().getString(key, def);
    }

    protected CharSequence persistedText(String key, CharSequence def) {
        return arguments().getCharSequence(key, def);
    }

    protected String[] persistedArray(String key) {
        return arguments().getStringArray(key);
    }

    private Bundle arguments() {
        Bundle bundle = getArguments();
        if (bundle != null) return bundle;
        bundle = new Bundle();
        try {
            setArguments(bundle);
        } catch (IllegalStateException e) {
            // Fragment 已 attach，系统不再接受 arguments；本次显示走内存字段即可，
            // 只是这一次显示无法跨重建保活 —— 不是错误，无需上报。
        }
        return bundle;
    }

    // ==================== 宿主状态防御 ====================
    // DialogFragment 的 show() / dismiss() 内部都会提交 FragmentTransaction，而 commit() 在
    // 「宿主 Activity 已销毁」或「已 onSaveInstanceState」时**同步抛 IllegalStateException**，
    // 表现就是闪退。这类时序在电视上并不罕见（异步回调晚到、按返回键离开页面后回调才回来），
    // 此时正确做法是放弃这次弹出 / 关闭，而不是崩。

    @Override
    public void show(@NonNull FragmentManager manager, @Nullable String tag) {
        try {
            super.show(manager, tag);
        } catch (IllegalStateException e) {
            // 宿主已销毁或状态已保存：静默放弃本次显示
        }
    }

    @Override
    public void showNow(@NonNull FragmentManager manager, @Nullable String tag) {
        try {
            super.showNow(manager, tag);
        } catch (IllegalStateException e) {
            // 同上
        }
    }

    @Override
    public void dismiss() {
        try {
            super.dismiss();
        } catch (IllegalStateException e) {
            // FragmentManager 已销毁，无需（也无法）真正 dismiss
        }
    }

    protected void setWidth(float factor) {
        if (getDialog() == null || getDialog().getWindow() == null) return;
        WindowManager.LayoutParams params = getDialog().getWindow().getAttributes();
        params.width = (int) (ResUtil.getScreenWidth() * factor);
        getDialog().getWindow().setAttributes(params);
    }

    // 注：内容过高时「按钮行被挤成 0 高」的问题不在窗口高度上，而是竖向 LinearLayout 的
    // 高度分配顺序所致（内容滚动区把可用高度吃满）。修法在自定义根容器里：
    // com.fongmi.android.tv.ui.custom.DialogPanel —— 它在测量阶段给滚动区限高。
    // 曾经的 capHeight(在 onGlobalLayout 里事后改滚动区高度) 拿不到框架真正给的高度规格、
    // 也拦不住那一次测量，已废弃。
}
