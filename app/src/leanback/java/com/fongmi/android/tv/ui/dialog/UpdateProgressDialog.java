package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
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
 * 下载进度弹窗（电视版）。
 *
 * <p>一个弹窗两种状态，靠文案与按钮含义切换，不另建布局：</p>
 * <ul>
 *   <li>下载中：进度条 + 已下载/总量 + 速度与剩余时间，按钮「取消下载 / 转到后台」；</li>
 *   <li>已完成：进度 100% + 包体大小，按钮「关闭 / 立即安装」。</li>
 * </ul>
 *
 * <p>数据全部来自 {@link UpdateState}（由前台服务写入），所以弹窗重建、甚至关掉再开，
 * 都能接上当前进度 —— 下载本身不在这个弹窗里。</p>
 *
 * <p>⚠ 返回键 / 点击外部关闭 = 「转到后台」，**不会**中断下载（中断只有「取消下载」一个入口）。</p>
 */
public class UpdateProgressDialog extends BaseAlertDialog implements UpdateState.Listener {

    private DialogUpdateProgressBinding binding;

    public static UpdateProgressDialog create() {
        return new UpdateProgressDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogUpdateProgressBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        UpdateState.addListener(this);
        if (!isActive()) {
            // 弹窗创建时任务已经结束（成功装完 / 被取消 / 失败）：不要在 onCreateDialog 里
            // 同步 dismiss 自己，延后一帧更稳
            App.post(this::dismiss);
            return;
        }
        render();
    }

    @Override
    protected void initEvent() {
        binding.cancel.setOnClickListener(v -> {
            // 已完成时左键是「关闭」，不要顺手把安装包删了
            if (UpdateState.isDownloaded()) {
                dismiss();
                return;
            }
            UpdateService.cancel(App.get());
            dismiss();
        });
        binding.positive.setOnClickListener(v -> {
            if (UpdateState.isDownloaded()) {
                UpdateService.install(requireContext(), UpdateState.getFile());
                return;
            }
            // 「转到后台」：只关窗，服务继续下载
            dismiss();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.42f);
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

        binding.title.setText(downloaded ? getString(R.string.update_download_done) : getString(R.string.update_downloading, version));
        binding.source.setText(downloaded ? getString(R.string.update_download_done_desc, version) : getString(R.string.update_from_source, mirrorName()));
        binding.progress.setProgressCompat(progress, true);
        binding.size.setText(getString(R.string.update_progress_text, UpdateService.size(done), UpdateService.size(total)));
        binding.percent.setText(progress + "%");
        binding.speed.setText(speedText(downloaded, total, done, speed));
        binding.hint.setText(downloaded ? "" : getString(R.string.update_background_hint));
        binding.cancel.setText(downloaded ? R.string.update_close : R.string.update_cancel_download);
        binding.positive.setText(downloaded ? R.string.update_install_now : R.string.update_to_background);
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
