package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterArrayPickBinding;

/**
 * 通用单选列表（手机版）：供 ArrayPickDialog 使用（DoH / 播放比例 / 播放背景）。
 * 行样式与主数据源弹窗选择模式同构，选中项行尾标注「使用中」。
 */
public class ArrayPickAdapter extends RecyclerView.Adapter<ArrayPickAdapter.ViewHolder> {

    private final OnClickListener listener;
    private String[] mItems = new String[0];
    private int select;

    public ArrayPickAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(int position);
    }

    public ArrayPickAdapter setItems(String[] items) {
        mItems = items == null ? new String[0] : items;
        return this;
    }

    public ArrayPickAdapter setSelect(int select) {
        this.select = select;
        return this;
    }

    @Override
    public int getItemCount() {
        return mItems.length;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterArrayPickBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.binding.text.setText(mItems[position]);
        // 选中项行尾标注「使用中」，与主数据源选择模式（SiteManageAdapter）同一文案。
        if (select == position) holder.binding.state.setText(R.string.site_pick_in_use);
        else holder.binding.state.setText("");
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(position));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterArrayPickBinding binding;

        public ViewHolder(@NonNull AdapterArrayPickBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
