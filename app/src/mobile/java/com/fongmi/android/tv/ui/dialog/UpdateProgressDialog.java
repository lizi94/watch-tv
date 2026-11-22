package com.fongmi.android.tv.ui.dialog;

import androidx.appcompat.app.AlertDialog;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUpdateProgressBinding;
import com.fongmi.android.tv.update.Mirror;
import com.fongmi.android.tv.update.UpdateInfo;
import com.fongmi.android.tv.update.UpdateService;
import com.fongmi.android.tv.update.UpdateState;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 下载进度弹窗（手机版）。
 *
 * <p>一个对话框两种状态（下载中 / 已完成），标题与按钮文字在 {@link #render()} 里改写；
 * 下载本身由前台服务执行，所以关掉这个弹窗不影响下载，重进设置页点版本行还能接着看。</p>
 */
public class UpdateProgressDialog extends BaseAlertDialog implements UpdateState.Listener {

    private DialogUpdateProgressBinding binding;

    public static UpdateProgressDialog create() {
        return new UpdateProgressDialog();
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogUpdateProgressBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder()
                // 初始标题用中性文案，真正的「正在下载 6.5.2 / 下载完成」在 render() 里改写
                .setTitle(R.string.update_downloading_unknown)
                .setView(getBinding().getRoot())
                .setNegativeButton(R.string.update_cancel_download, (dialog, which) -> onNegative())
                .setPositiveButton(R.string.update_to_background, (dialog, which) -> onPositive());
    }

    @Override
    protected void initView() {
        UpdateState.addListener(this);
        if (!isActive()) {
            App.post(this::dismiss);
            return;
        }
        render();
    }

    @Override
    public void onStart() {
        super.onStart();
        // onCreateDialog 阶段 getDialog() 还没就绪，按钮文字的首次改写放到这里
        render();
    }

    @Override
    public void onDestroy() {
        UpdateState.removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onUpdateStateChanged() {
        render();
    }

    private void onNegative() {
        // 已完成时左键是「关闭」，不要顺手把安装包删了
        if (UpdateState.isDownloaded()) return;
        UpdateService.cancel(App.get());
    }

    private void onPositive() {
        if (UpdateState.isDownloaded()) UpdateService.install(requireContext(), UpdateState.getFile());
        // 下载中时「转到后台」：按钮点击本身就会关闭弹窗，这里不需要额外动作
    }

    private boolean isActive() {
        return UpdateState.isDownloading() || UpdateState.isDownloaded();
    }

    private void render() {
        if (binding == null) return;
        if (!isActive()) {
            App.post(this::dismiss);
            return;
        }
        boolean downloaded = UpdateState.isDownloaded();
        UpdateInfo info = UpdateState.getInfo();
        String version = info == null ? "" : info.getVersion();
        long total = UpdateState.getTotal();
        long done = UpdateState.getDownloaded();
        long speed = UpdateState.getSpeed();
        int progress = downloaded ? 100 : UpdateState.getProgress();

        binding.source.setText(downloaded ? getString(R.string.update_download_done_desc, version) : getString(R.string.update_from_source, mirrorName()));
        binding.progress.setProgressCompat(progress, true);
        binding.size.setText(getString(R.string.update_progress_text, UpdateService.size(done), UpdateService.size(total)));
        binding.percent.setText(progress + "%");
        binding.speed.setText(speedText(downloaded, total, done, speed));
        binding.hint.setText(downloaded ? "" : getString(R.string.update_background_hint));

        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog == null) return;
        dialog.setTitle(downloaded ? getString(R.string.update_download_done) : getString(R.string.update_downloading, version));
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(downloaded ? R.string.update_close : R.string.update_cancel_download);
        }
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(downloaded ? R.string.update_install_now : R.string.update_to_background);
        }
    }

    private String mirrorName() {
        Mirror mirror = UpdateState.getMirror();
        return mirror == null ? "" : mirror.getName();
    }

    private String speedText(boolean downloaded, long total, long done, long speed) {
        if (downloaded) return "";
        if (speed <= 0 || total <= done) return "";
        int left = (int) Math.max(1, (total - done) / speed);
        return getString(R.string.update_speed_left, UpdateService.size(speed), left);
    }
}
