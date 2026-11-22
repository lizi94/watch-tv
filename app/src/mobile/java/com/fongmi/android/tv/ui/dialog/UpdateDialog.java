package com.fongmi.android.tv.ui.dialog;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUpdateBinding;
import com.fongmi.android.tv.update.UpdateInfo;
import com.fongmi.android.tv.update.UpdateService;
import com.fongmi.android.tv.update.UpdateState;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 「发现新版本」弹窗（手机版）。
 *
 * <p>标题与两个动作按钮都交给 MaterialAlertDialogBuilder（M3 默认 TextButton），
 * 与 dialog_confirm、ArrayPickDialog 的观感完全一致，没有新增按钮样式。</p>
 */
public class UpdateDialog extends BaseAlertDialog {

    private DialogUpdateBinding binding;

    public static UpdateDialog create() {
        return new UpdateDialog();
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogUpdateBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder()
                .setTitle(R.string.update_title)
                .setView(getBinding().getRoot())
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(R.string.update_download_install, (dialog, which) -> {
                    // 按钮点击会自动关掉本弹窗；下一层弹窗延后一帧再 show，避免同一帧内
                    // 一边 dismiss 一边 show 导致的 FragmentManager 状态竞争
                    App.post(() -> MirrorDialog.create().show(getParentFragmentManager(), null));
                });
    }

    @Override
    protected void initView() {
        UpdateInfo info = UpdateState.getInfo();
        if (info == null) {
            App.post(this::dismiss);
            return;
        }
        UpdateInfo.Asset asset = info.getAsset();
        binding.current.setText(getString(R.string.update_current) + " " + BuildConfig.VERSION_NAME);
        binding.latest.setText(getString(R.string.update_latest) + " " + info.getVersion());
        binding.size.setText(getString(R.string.update_size, UpdateService.size(asset == null ? 0 : asset.getSize())));
    }

    @Override
    protected void initEvent() {
        binding.log.setOnClickListener(v -> {
            UpdateInfo info = UpdateState.getInfo();
            if (info == null || info.getBody().isEmpty()) return;
            UpdateLogDialog.create().subtitle(info.getVersion() + " · " + info.getDate()).content(info.getBody()).show(getParentFragmentManager(), null);
        });
    }
}
