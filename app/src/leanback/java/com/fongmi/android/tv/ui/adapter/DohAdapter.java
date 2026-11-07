package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.databinding.AdapterDohBinding;
import com.github.catvod.bean.Doh;

import java.util.List;

public class DohAdapter extends RecyclerView.Adapter<DohAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Doh> mItems;
    private int select;

    public DohAdapter(OnClickListener listener) {
        this.mItems = VodConfig.get().getDoh();
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(Doh item);
    }

    public void setSelect(int select) {
        this.select = select;
    }

    public int getSelect() {
        return select;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterDohBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Doh item = mItems.get(position);
        holder.binding.text.setText(item.getName());
        holder.binding.text.setSelected(select == position);
        // 选中项行尾标注「使用中」，与主数据源选择模式（SiteManageAdapter）同一文案。
        if (select == position) holder.binding.state.setText(R.string.site_pick_in_use);
        else holder.binding.state.setText("");
        // 单动作行：点击绑整行根布局 —— Ag.ListItem 的 focusable 在根布局上，遥控器焦点落整行，
        // 绑在 text 上按 OK 键不会触发（R119 改行样式时引入的回归，R122 修复）。
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterDohBinding binding;

        public ViewHolder(@NonNull AdapterDohBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
