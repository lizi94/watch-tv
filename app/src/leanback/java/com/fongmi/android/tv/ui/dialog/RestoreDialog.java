package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogRestoreBinding;
import com.fongmi.android.tv.db.BackupManager;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.adapter.RestoreAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

public class RestoreDialog extends BaseAlertDialog implements RestoreAdapter.OnClickListener {

    private DialogRestoreBinding binding;
    private RestoreAdapter adapter;
    private Callback callback;

    public static RestoreDialog create() {
        return new RestoreDialog();
    }

    public RestoreDialog callback(Callback callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogRestoreBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new RestoreAdapter(this);
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
    }

    @Override
    public void onItemClick(File item) {
        // ⛔ 2026-10-24 上线前修：恢复会先 AppDatabase.clearAllTables() 再覆盖 ——
        // 也就是**把收藏、历史、数据源配置全部清空**。原来单击即执行，误触一次就没了。
        // 与「删除备份」保持一致，先二次确认。
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        ConfirmDialog.create().message(R.string.elder_confirm_restore_backup)
                .callback(() -> {
                    BackupManager.restore(item, callback);
                    dismiss();
                }).show(activity);
    }

    @Override
    public void onDeleteClick(File item) {
        // 这里是列表条目的回调，宿主 Fragment / Activity 可能已经被销毁：
        // requireActivity() 在未 attach 时会抛 IllegalStateException 而闪退，改用可空版本并判空。
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        ConfirmDialog.create().message(R.string.elder_confirm_delete_backup).callback(() -> delete(item)).show(activity);
    }

    private void delete(File item) {
        if (adapter.remove(item) == 0) dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else setWidth(0.4f);
    }
}
