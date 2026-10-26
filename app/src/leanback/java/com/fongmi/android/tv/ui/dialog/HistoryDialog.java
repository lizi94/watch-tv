package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogHistoryBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.ui.adapter.ConfigAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class HistoryDialog extends BaseAlertDialog implements ConfigAdapter.OnClickListener {

    private static final String KEY_TYPE = "type";
    private static final String KEY_READ_ONLY = "readOnly";

    private DialogHistoryBinding binding;
    private ConfigAdapter adapter;
    private boolean readOnly;
    private int type;

    public static HistoryDialog create() {
        return new HistoryDialog();
    }

    public HistoryDialog vod() {
        type = 0;
        persist(KEY_TYPE, type);
        return this;
    }

    public HistoryDialog live() {
        type = 1;
        persist(KEY_TYPE, type);
        return this;
    }

    public HistoryDialog wall() {
        type = 2;
        persist(KEY_TYPE, type);
        return this;
    }

    public HistoryDialog readOnly() {
        readOnly = true;
        persist(KEY_READ_ONLY, readOnly);
        return this;
    }

    /** 重建后把列表类型读回来：否则 type 退成 0，弹窗会列出另一种类型的记录。 */
    @Override
    protected void restoreArguments() {
        type = persistedInt(KEY_TYPE, type);
        readOnly = persistedBoolean(KEY_READ_ONLY, readOnly);
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new ConfigAdapter(this);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        binding.recycler.setAdapter(adapter.readOnly(readOnly).addAll(type));
    }

    @Override
    public void onTextClick(Config item) {
        ((ConfigListener) requireActivity()).setConfig(item);
        dismiss();
    }

    @Override
    public void onDeleteClick(Config item) {
        // 这里是列表条目的回调，宿主 Fragment / Activity 可能已经被销毁：
        // requireActivity() 在未 attach 时会抛 IllegalStateException 而闪退，改用可空版本并判空。
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        ConfirmDialog.create().message(R.string.elder_confirm_delete_config).callback(() -> delete(item)).show(activity);
    }

    private void delete(Config item) {
        if (adapter.remove(item) == 0) dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else setWidth(0.4f);
    }
}