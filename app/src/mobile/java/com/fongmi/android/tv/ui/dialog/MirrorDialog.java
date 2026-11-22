package com.fongmi.android.tv.ui.dialog;

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
 * 下载源选择弹窗（手机版）。
 *
 * <p>点任意一行立即生效并开始下载，与 ArrayPickDialog / 主数据源弹窗「点一下就换」的
 * 交互一致，不多按一次确定。</p>
 */
public class MirrorDialog extends BaseAlertDialog implements MirrorAdapter.OnClickListener {

    private DialogMirrorBinding binding;
    private MirrorAdapter adapter;
    private final List<Mirror> mirrors = Mirror.shared();

    public static MirrorDialog create() {
        return new MirrorDialog();
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogMirrorBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.update_source_title).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new MirrorAdapter(this, mirrors, Setting.getUpdateMirror());
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(true);
        probe();
    }

    @Override
    protected void initEvent() {
        binding.retest.setOnClickListener(v -> probe());
    }

    @Override
    public void onStart() {
        super.onStart();
        binding.recycler.post(() -> binding.recycler.scrollToPosition(adapter.getSelectPosition()));
    }

    private void probe() {
        if (binding == null) return;
        binding.summary.setText(R.string.update_source_measuring);
        binding.retest.setEnabled(false);
        MirrorProbe.probe(mirrors, () -> {
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
        UpdateProgressDialog.create().show(getParentFragmentManager(), null);
    }
}
