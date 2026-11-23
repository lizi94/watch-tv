package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogMirrorBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.MirrorAdapter;
import com.fongmi.android.tv.update.Mirror;
import com.fongmi.android.tv.update.MirrorProbe;
import com.fongmi.android.tv.update.UpdateCenter;
import com.fongmi.android.tv.update.UpdateInfo;
import com.fongmi.android.tv.update.UpdateState;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 下载源选择弹窗（电视版）。
 *
 * <p>打开即并发测速 8 个节点，点任意一行**立即生效并开始下载** —— 与主数据源 / DoH 弹窗
 * 「点一下就用这个」的交互一致，不再让用户多按一次确定。</p>
 *
 * <p>列表来自 {@link Mirror#shared()}（进程内单例）：弹窗被重建时测速结果还在，
 * 不会出现「回来又得重新等 3 秒」。</p>
 */
public class MirrorDialog extends BaseAlertDialog implements MirrorAdapter.OnClickListener {

    private DialogMirrorBinding binding;
    private MirrorAdapter adapter;
    private final List<Mirror> mirrors = Mirror.shared();

    public static MirrorDialog create() {
        return new MirrorDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogMirrorBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new MirrorAdapter(this, mirrors, Setting.getUpdateMirror());
        binding.recycler.setAdapter(adapter);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        probe();
    }

    @Override
    protected void initEvent() {
        binding.retest.setOnClickListener(v -> probe());
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.44f);
        // 焦点直接落在「当前选中」那一行，老人不用一个个往下推
        binding.recycler.post(() -> binding.recycler.scrollToPosition(adapter.getSelectPosition()));
    }

    private void probe() {
        if (binding == null) return;
        binding.summary.setText(R.string.update_source_measuring);
        binding.retest.setEnabled(false);
        MirrorProbe.probe(mirrors, probeUrl(), () -> {
            // 回调可能晚于弹窗销毁（用户已经去下载了），此处必须防御，不能直接摸 binding
            if (!isAdded() || binding == null) return;
            adapter.notifyDataSetChanged();
            binding.retest.setEnabled(true);
            binding.summary.setText(getString(R.string.update_source_ready, adapter.getMeasuredDelayCount(), adapter.getProbeCount()));
        });
    }

    /**
     * 探针目标 = 本次真正要下载的那个 APK 地址（R163）。
     *
     * <p>⛔ 别再改回 robots.txt 或节点根路径：镜像只代理 release 下载路径，其它路径会被
     * 403 / 404 / 429 拒绝，测出来的状态与「能否下载」无关（2026-11-24 实测 8 个节点全灭）。
     * 取不到 asset 时返回 null，由 {@link MirrorProbe} 退化为根路径模式（只判可达）。</p>
     */
    private String probeUrl() {
        UpdateInfo info = UpdateState.getInfo();
        return info == null || info.getAsset() == null ? null : info.getAsset().getUrl();
    }

    @Override
    public void onItemClick(Mirror item) {
        Mirror chosen = item;
        if (item.isAuto()) {
            // 「自动」：先用测速成功的节点里最快的；
            // 一个都没有时，选「连得上但被拒」里响应最快的那个（直连在国内基本不通，不再优先回落）。
            Mirror fastest = Mirror.fastest(mirrors);
            if (fastest == null) fastest = Mirror.fastestAny(mirrors);
            chosen = fastest != null ? fastest : Mirror.direct(mirrors);
        }
        if (chosen == null) {
            Notify.show(R.string.update_check_failed_network);
            return;
        }
        Setting.putUpdateMirror(item.getKey());
        dismiss();
        UpdateCenter.download(chosen);
        UpdateProgressDialog.create().show(requireActivity());
    }
}
