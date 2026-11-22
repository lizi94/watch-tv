package com.fongmi.android.tv.ui.dialog;

import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogUpdateLogBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 更新日志弹窗（电视版）。
 *
 * <p>与 ContentDialog 的区别：本弹窗有**固定标题**和**一个可见的「关闭」按钮**。
 * ContentDialog 只有正文，只能靠返回键退出 —— 老人不知道该按哪里，
 * 所以更新日志单独做一个带按钮的版本。</p>
 *
 * <p>正文长度由 Release 的 body 决定，可能很长；限高交给根容器 DialogPanel 在测量阶段处理，
 * 保证「关闭」按钮永不被挤掉。</p>
 */
public class UpdateLogDialog extends BaseAlertDialog {

    private static final String KEY_SUBTITLE = "subtitle";
    private static final String KEY_CONTENT = "content";

    private DialogUpdateLogBinding binding;
    private CharSequence content;
    private String subtitle;

    public static UpdateLogDialog create() {
        return new UpdateLogDialog();
    }

    public UpdateLogDialog subtitle(String subtitle) {
        this.subtitle = subtitle;
        persist(KEY_SUBTITLE, subtitle);
        return this;
    }

    public UpdateLogDialog content(CharSequence content) {
        this.content = content;
        persist(KEY_CONTENT, content);
        return this;
    }

    /** 重建后把日志读回来，否则弹窗会变成只有标题的空卡片。 */
    @Override
    protected void restoreArguments() {
        subtitle = persistedString(KEY_SUBTITLE, "");
        content = persistedText(KEY_CONTENT, content);
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogUpdateLogBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.subtitle.setText(subtitle == null ? "" : subtitle);
        binding.text.setText(content == null ? "" : content, TextView.BufferType.SPANNABLE);
    }

    @Override
    protected void initEvent() {
        binding.close.setOnClickListener(v -> dismiss());
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.44f);
    }
}
