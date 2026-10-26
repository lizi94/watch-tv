package com.fongmi.android.tv.ui.dialog;

import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogContentBinding;
import com.fongmi.android.tv.ui.custom.CustomMovement;
import com.github.bassaer.library.MDColor;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ContentDialog extends BaseAlertDialog {

    private static final String KEY_CONTENT = "content";

    private DialogContentBinding binding;
    private CharSequence content;

    public static ContentDialog create() {
        return new ContentDialog();
    }

    public ContentDialog content(CharSequence content) {
        this.content = content;
        persist(KEY_CONTENT, content);
        return this;
    }

    /** 重建后把正文读回来，否则弹窗会变成空白卡片。 */
    @Override
    protected void restoreArguments() {
        content = persistedText(KEY_CONTENT, content);
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogContentBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.text.setText(content, TextView.BufferType.SPANNABLE);
        binding.text.setLinkTextColor(MDColor.YELLOW_500);
        CustomMovement.bind(binding.text);
    }
}