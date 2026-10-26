package com.fongmi.android.tv.ui.dialog;

import androidx.annotation.StringRes;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogConfirmBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 适老化二次确认弹窗：大字号提示 + 两个大按钮，焦点默认落在「取消」上。
 * 用于删除、清空、恢复默认等不可逆操作，避免老人误触。
 * 弹窗跑在独立 Window 中，焦点天然不会穿透到背后的界面。
 */
public class ConfirmDialog extends BaseAlertDialog {

    private static final String KEY_MESSAGE = "message";
    private static final String KEY_MESSAGE_TEXT = "messageText";

    private DialogConfirmBinding binding;
    private Runnable callback;
    private int message;
    private CharSequence messageText;

    public static ConfirmDialog create() {
        return new ConfirmDialog();
    }

    public ConfirmDialog message(@StringRes int message) {
        this.message = message;
        persist(KEY_MESSAGE, message);
        return this;
    }

    /** 动态文案（如带数量的批量删除确认）。 */
    public ConfirmDialog message(CharSequence text) {
        this.messageText = text;
        persist(KEY_MESSAGE_TEXT, text);
        return this;
    }

    /**
     * 确认后的动作。
     *
     * <p>Runnable 无法进 arguments（不可序列化），因此这个弹窗若遇上配置变更 / 进程被回收后
     * 重建，动作会丢失 —— 此时「确定」只关闭弹窗、不执行任何操作。<b>这是刻意选择的兜底</b>：
     * 确认框对应的都是删除 / 清空 / 恢复默认这类不可逆操作，宁可让用户重新点一次，
     * 也绝不能因为回调丢失而去执行一个语义不明的动作。</p>
     */
    public ConfirmDialog callback(Runnable callback) {
        this.callback = callback;
        return this;
    }

    @Override
    protected void restoreArguments() {
        message = persistedInt(KEY_MESSAGE, message);
        messageText = persistedText(KEY_MESSAGE_TEXT, messageText);
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogConfirmBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        // message 兜底判 0：Resource ID 0 会让 setText() 抛 Resources.NotFoundException。
        if (messageText != null) binding.message.setText(messageText);
        else if (message != 0) binding.message.setText(message);
    }

    @Override
    protected void initEvent() {
        binding.cancel.setOnClickListener(v -> dismiss());
        binding.positive.setOnClickListener(v -> onPositive());
    }

    private void onPositive() {
        dismiss();
        if (callback != null) callback.run();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.45f);
        // 适老化：默认焦点落在「取消」。确认类操作多为删除 / 清空等不可逆行为，
        // 老人连按 OK 时不应直接命中危险项，必须主动按右键才能移到「确定」。
        binding.cancel.requestFocus();
    }
}
