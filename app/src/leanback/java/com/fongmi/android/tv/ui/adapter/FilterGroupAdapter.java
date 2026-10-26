package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.databinding.AdapterFilterGroupBinding;

import java.util.List;

/**
 * 筛选条件组列表（竖排）：每组一行 = 组名 + 该组的值横排。
 *
 * <p>组名不可聚焦（纯标签），值胶囊可聚焦；上下键跨组跳转由外层的
 * {@link FilterValueAdapter.Listener#onValueKey} 显式桥接（见弹窗实现）。</p>
 */
public class FilterGroupAdapter extends RecyclerView.Adapter<FilterGroupAdapter.ViewHolder> implements FilterValueAdapter.Listener {

    public interface Listener {

        void onValueClick(int groupIndex, Filter filter, Value value);

        boolean onValueKey(int groupIndex, int valueIndex, int keyCode);
    }

    private final Listener listener;
    private final List<Filter> mItems;

    public FilterGroupAdapter(Listener listener, List<Filter> items) {
        this.listener = listener;
        this.mItems = items;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterFilterGroupBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Filter item = mItems.get(position);
        holder.binding.name.setText(item.getName());
        holder.binding.recycler.setHasFixedSize(true);
        holder.binding.recycler.setItemAnimator(null);
        holder.binding.recycler.setAdapter(new FilterValueAdapter(this, item, position));
    }

    @Override
    public void onValueClick(int groupIndex, Filter filter, Value value) {
        // ⛔ 这里**不能** notifyItemChanged(groupIndex)：那会让本组重新 onBind、把内层横向列表
        // 的 adapter 整个换掉 ⇒ 值胶囊全部重建 ⇒ 老人刚点的那个胶囊焦点丢失（表现为「焦点跳走」）。
        // 选中态由弹窗用 refreshGroup() 只对「值」发局部通知刷新（不换 adapter，焦点不动）。
        listener.onValueClick(groupIndex, filter, value);
    }

    /** 只刷新某组「值」的选中态：不重建内层 adapter，焦点保持在原胶囊上。 */
    public void refreshGroup(RecyclerView parent, int groupIndex) {
        RecyclerView child = getChildRecycler(parent, groupIndex);
        if (child == null || child.getAdapter() == null) return;
        int count = child.getAdapter().getItemCount();
        if (count > 0) child.getAdapter().notifyItemRangeChanged(0, count);
    }

    @Override
    public boolean onValueKey(int groupIndex, int valueIndex, int keyCode) {
        return listener.onValueKey(groupIndex, valueIndex, keyCode);
    }

    /** 供弹窗做跨组落焦：拿到某组内层的横向列表。 */
    public RecyclerView getChildRecycler(RecyclerView parent, int groupIndex) {
        RecyclerView.ViewHolder holder = parent.findViewHolderForAdapterPosition(groupIndex);
        return holder instanceof ViewHolder ? ((ViewHolder) holder).binding.recycler : null;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterFilterGroupBinding binding;

        ViewHolder(@NonNull AdapterFilterGroupBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
