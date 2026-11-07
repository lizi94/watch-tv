package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.databinding.DialogLiveBinding;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.ui.adapter.LiveAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class LiveDialog extends BaseAlertDialog implements LiveAdapter.OnClickListener {

    private DialogLiveBinding binding;
    private LiveAdapter adapter;

    public static LiveDialog create() {
        return new LiveDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogLiveBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new LiveAdapter(this);
        binding.recycler.setAdapter(adapter);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        binding.recycler.post(() -> binding.recycler.scrollToPosition(LiveConfig.getHomeIndex()));
    }

    @Override
    public void onItemClick(Live item) {
        ((LiveListener) requireActivity()).setLive(item);
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else setWidth(0.4f);
    }
}