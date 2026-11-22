package com.fongmi.android.tv.ui.dialog;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUpdateBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 更新日志弹窗（手机版）。
 *
 * <p>刻意不建布局：{@code setMessage} 本身就带滚动，长日志交给系统 Material 对话框处理，
 * 零新增文件、零新增样式。副标题（版本 · 日期）拼进正文首行。</p>
 */
public class UpdateLogDialog extends BaseAlertDialog {

    private static final String KEY_SUBTITLE = "subtitle";
    private static final String KEY_CONTENT = "content";

    private String subtitle;
    private CharSequence content;

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

    @Override
    protected void restoreArguments() {
        subtitle = persistedString(KEY_SUBTITLE, "");
        content = persistedText(KEY_CONTENT, content);
    }

    @Override
    protected ViewBinding getBinding() {
        return DialogUpdateBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        String head = subtitle == null || subtitle.isEmpty() ? "" : subtitle + "\n\n";
        CharSequence body = (head + (content == null ? "" : content.toString())).trim();
        return builder()
                .setTitle(R.string.update_log_title)
                .setMessage(body)
                .setPositiveButton(R.string.update_close, null);
    }
}
