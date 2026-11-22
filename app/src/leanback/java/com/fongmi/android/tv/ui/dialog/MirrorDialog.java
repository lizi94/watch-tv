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
        MirrorProbe.probe(mirrors, () -> {
            // 回调可能晚于弹窗销毁（用户已经去下载了），此处必须防御，不能直接摸 binding
            if (!isAdded() || binding == null) return;
            adapter.notifyDataSetChanged();
            binding.retest.setEnabled(true);
            binding.summary.setText(getString(R.string.update_source_ready, adapter.getMeasuredDelayCount()));
        });
    }

    @Override
    public void onItemClick(Mirror item) {
        Mirror chosen = item;
        if (item.isAuto()) {
            // 「自动」= 此刻延迟最小的那个；全部不通时回落直连
            Mirror fastest = Mirror.fastest(mirrors);
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
