package com.fongmi.android.tv.ui.dialog;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogArrayPickBinding;
import com.fongmi.android.tv.ui.adapter.ArrayPickAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 通用单选弹窗（手机版）：替代系统 setSingleChoiceItems 的行列表单选，
 * 行样式与主数据源弹窗（SitePickDialog）同构。供 DoH / 播放比例 / 播放背景共用。
 * title / items / select 全部走 arguments 持久化，重建后可完整恢复。
 */
public class ArrayPickDialog extends BaseAlertDialog implements ArrayPickAdapter.OnClickListener {

    private static final String KEY_TITLE = "title";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_SELECT = "select";

    private DialogArrayPickBinding binding;
    private ArrayPickAdapter adapter;
    private int title;
    private String[] items = new String[0];
    private int select;
    private Listener listener;

    public interface Listener {

        void onPick(int which);
    }

    public static ArrayPickDialog create() {
        return new ArrayPickDialog();
    }

    public ArrayPickDialog title(int resId) {
        title = resId;
        persist(KEY_TITLE, resId);
        return this;
    }

    public ArrayPickDialog items(String[] values) {
        items = values == null ? new String[0] : values;
        persist(KEY_ITEMS, items);
        return this;
    }

    public ArrayPickDialog select(int index) {
        select = index;
        persist(KEY_SELECT, select);
        return this;
    }

    public ArrayPickDialog listener(Listener value) {
        listener = value;
        return this;
    }

    public void show(androidx.fragment.app.Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected void restoreArguments() {
        title = persistedInt(KEY_TITLE, 0);
        items = persistedArray(KEY_ITEMS);
        if (items == null) items = new String[0];
        select = persistedInt(KEY_SELECT, 0);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogArrayPickBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        // 重建后 title 可能回落 0：只有非 0 才设标题，避免 setText(0) 闪退。
        MaterialAlertDialogBuilder builder = builder().setView(getBinding().getRoot());
        if (title != 0) builder.setTitle(title);
        return builder;
    }

    @Override
    protected void initView() {
        adapter = new ArrayPickAdapter(this);
        adapter.setItems(items).setSelect(select);
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(true);
    }

    @Override
    public void onStart() {
        super.onStart();
        // 防御：极端情况下 items 仍为空（如重建后无法恢复）时放弃显示，不弹空窗。
        if (adapter.getItemCount() == 0) dismiss();
    }

    @Override
    public void onItemClick(int position) {
        if (listener != null) listener.onPick(position);
        dismiss();
    }
}
