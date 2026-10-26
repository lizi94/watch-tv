package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogRestoreBinding;
import com.fongmi.android.tv.db.BackupManager;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.adapter.RestoreAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;

import java.io.File;

public class RestoreDialog extends BaseBottomSheetDialog implements RestoreAdapter.OnClickListener {

    private DialogRestoreBinding binding;
    private RestoreAdapter adapter;
    private Callback callback;

    public static RestoreDialog create() {
        return new RestoreDialog();
    }

    public void show(FragmentActivity activity, Callback callback) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof RestoreDialog) return;
        show(activity.getSupportFragmentManager(), null);
        this.callback = callback;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogRestoreBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.setAdapter(adapter = new RestoreAdapter(this));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        binding.recycler.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onItemClick(File item) {
        // ⛔ 2026-10-24 上线前修：恢复会先 AppDatabase.clearAllTables() 再覆盖 ——
        // 收藏、历史、数据源配置会被全部清空。原来单击即执行，误触一次就没了。
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        new AlertDialog.Builder(activity)
                .setMessage(R.string.elder_confirm_restore_backup)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    BackupManager.restore(item, callback);
                    dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onDeleteClick(File item) {
        // 删除备份文件不可恢复，先二次确认（电视端早已有这道确认，手机端漏了）。
        // 宿主 Fragment / Activity 可能已销毁：requireActivity() 会抛 IllegalStateException，用可空版本。
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        new AlertDialog.Builder(activity)
                .setMessage(R.string.elder_confirm_delete_backup)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (adapter.remove(item) == 0) dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
