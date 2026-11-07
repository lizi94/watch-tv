package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.databinding.AdapterLiveBinding;

import java.util.List;

public class LiveAdapter extends RecyclerView.Adapter<LiveAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Live> mItems;

    public LiveAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = LiveConfig.get().getLives();
    }

    public interface OnClickListener {

        void onItemClick(Live item);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterLiveBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Live item = mItems.get(position);
        holder.binding.text.setText(item.getName());
        holder.binding.text.setSelected(item.isSelected());
        // 「使用中」标注：当前生效的直播源（LiveConfig 加载时已对该项标记 selected），一眼可辨。
        holder.binding.state.setVisibility(item.isSelected() ? View.VISIBLE : View.GONE);
        if (item.isSelected()) holder.binding.state.setText(R.string.site_pick_in_use);
        // 点行 = 换直播源：**绑在整行根布局上**（与主数据源弹窗 SiteManageAdapter 同构）。
        // ⚠ 不能只绑 text：电视端 Ag.ListItem 的 focusable 在根布局上，遥控器焦点落整行，
        // 绑在 text 上按 OK 键根本不会触发 —— R119 改行样式时引入的回归，R122 修复。
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterLiveBinding binding;

        public ViewHolder(@NonNull AdapterLiveBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
