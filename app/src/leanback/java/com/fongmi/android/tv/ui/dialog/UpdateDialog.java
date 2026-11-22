package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUpdateBinding;
import com.fongmi.android.tv.update.UpdateInfo;
import com.fongmi.android.tv.update.UpdateService;
import com.fongmi.android.tv.update.UpdateState;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 「发现新版本」弹窗（电视版）。
 *
 * <p>只做信息展示与动作分发：不自己发请求、不自己下载 —— 数据全部读 {@link UpdateState}，
 * 这样弹窗被重建（切字号档位 / HDMI 重协商）后内容仍然正确。</p>
 */
public class UpdateDialog extends BaseAlertDialog {

    private DialogUpdateBinding binding;

    public static UpdateDialog create() {
        return new UpdateDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogUpdateBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        UpdateInfo info = UpdateState.getInfo();
        if (info == null) return;
        UpdateInfo.Asset asset = info.getAsset();
        // 当前版本直接显示 VERSION_NAME：guard 变体带 -guard 后缀，正好让用户确认自己装的是哪个形态
        binding.current.setText(getString(R.string.update_current) + " " + BuildConfig.VERSION_NAME);
        binding.latest.setText(getString(R.string.update_latest) + " " + info.getVersion());
        binding.size.setText(getString(R.string.update_size, UpdateService.size(asset == null ? 0 : asset.getSize())));
    }

    @Override
    protected void initEvent() {
        binding.cancel.setOnClickListener(v -> dismiss());
        binding.log.setOnClickListener(v -> showLog());
        binding.positive.setOnClickListener(v -> {
            dismiss();
            // 选源 → 选完立即开始下载，不再多一步确认
            MirrorDialog.create().show(requireActivity());
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.42f);
        // 极端时序：检查结果已被清空（例如另一处点了「稍后」后又重新检查失败）时不要留空弹窗
        if (UpdateState.getInfo() == null) dismiss();
    }

    private void showLog() {
        UpdateInfo info = UpdateState.getInfo();
        if (info == null || info.getBody().isEmpty()) return;
        UpdateLogDialog.create().subtitle(info.getVersion() + " · " + info.getDate()).content(info.getBody()).show(requireActivity());
    }
}
