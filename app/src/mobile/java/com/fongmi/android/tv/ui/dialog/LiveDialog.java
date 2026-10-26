package com.fongmi.android.tv.ui.dialog;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.databinding.DialogLiveBinding;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.ui.adapter.LiveAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class LiveDialog extends BaseAlertDialog implements LiveAdapter.OnClickListener {

    /** 启停 / 屏蔽按钮的显式开关（见 {@link #action()}）。 */
    private static final String KEY_ACTION = "action";

    private DialogLiveBinding binding;
    private LiveListener listener;
    private LiveAdapter adapter;
    /** 强制显示启停 / 屏蔽按钮（与电视端 `.action()` 同义）：一键锁定页从 Activity 打开时也要能管理。 */
    private boolean action;

    public static LiveDialog create() {
        return new LiveDialog();
    }

    /**
     * 显示启停 / 屏蔽按钮。
     *
     * <p>原本只有「从 Fragment 打开」才会显示（{@code adapter.setAction(!isFull())}）——
     * 因为一体化对话框里没有空间放这两个按钮。一键锁定页是 Activity，
     * 需要在同一个窗里既能换直播源、又能启停/屏蔽，所以补一个显式开关，
     * 语义与电视端 {@code LiveDialog.create().action()} 完全一致。</p>
     */
    public LiveDialog action() {
        action = true;
        persist(KEY_ACTION, action);
        return this;
    }

    @Override
    protected void restoreArguments() {
        action = persistedBoolean(KEY_ACTION, action);
    }

    public static void show(FragmentActivity activity) {
        new LiveDialog().show(activity.getSupportFragmentManager(), null);
    }

    public static void show(Fragment fragment) {
        new LiveDialog().show(fragment.getChildFragmentManager(), null);
    }

    private boolean isFull() {
        return getParentFragment() == null;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // 守卫：宿主没实现 LiveListener 时不强转（与 SiteDialog 同口径），
        // 否则直接 (LiveListener) context 会抛 ClassCastException 闪退。
        Object host = isFull() ? context : getParentFragment();
        listener = host instanceof LiveListener ? (LiveListener) host : null;
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
        // action 为显式开启时同样显示启停 / 屏蔽（一键锁定页），否则沿用原生「一体化对话框不显示」的规则
        adapter.setAction(action || !isFull());
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(true);
        if (isFull()) binding.recycler.setMaxHeight(ResUtil.dp2px(264));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 8));
        binding.recycler.post(() -> binding.recycler.scrollToPosition(LiveConfig.getHomeIndex()));
    }

    @Override
    public void onItemClick(Live item) {
        // 宿主未实现 LiveListener 时不再强转（见 onAttach 的守卫）—— 退化为「只是看看列表」，
        // 绝不因为少一个回调就把进程崩掉。
        if (listener != null) listener.setLive(item);
        dismiss();
    }

    @Override
    public void onBootClick(int position, Live item) {
        item.boot(!item.isBoot()).save();
        adapter.notifyItemChanged(position);
    }

    @Override
    public void onPassClick(int position, Live item) {
        item.pass(!item.isPass()).save();
        adapter.notifyItemChanged(position);
    }

    @Override
    public boolean onBootLongClick(Live item) {
        boolean result = !item.isBoot();
        LiveConfig.get().getLives().forEach(live -> live.boot(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }

    @Override
    public boolean onPassLongClick(Live item) {
        boolean result = !item.isPass();
        LiveConfig.get().getLives().forEach(live -> live.pass(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else if (ResUtil.isLand(requireContext())) setWidth(0.5f);
    }
}