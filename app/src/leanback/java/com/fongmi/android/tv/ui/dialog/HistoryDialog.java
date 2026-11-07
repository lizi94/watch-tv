package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogHistoryBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.ui.adapter.ConfigAdapter;
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
        binding.recycler.setAdapter(adapter.readOnly(readOnly).addAll(type));
    }

    @Override
    public void onTextClick(Config item) {
        ((ConfigListener) requireActivity()).setConfig(item);
        dismiss();
    }

    /** 启停切换（原删除按钮功能替换）：就地翻转落库并刷新，不关弹窗。 */
    @Override
    public void onActionClick(Config item) {
        adapter.toggle(item);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else setWidth(0.4f);
    }
}