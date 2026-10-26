package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 收藏适配器（手机版）：普通态点击进入详情；
 * 选择态（长按或菜单进入）点击切换选中标记，配合工具栏「删除所选」批量删除。
 * 与电视版同一交互模式。
 */
public class KeepAdapter extends BaseDiffAdapter<Keep, KeepAdapter.ViewHolder> {

    private final OnClickListener listener;
    private int width, height;
    private boolean delete;
    private final Set<String> selected;
    private Runnable selectionCallback;

    public KeepAdapter(OnClickListener listener) {
        this.listener = listener;
        this.selected = new HashSet<>();
    }

    /** 选中集合变化时回调（用于工具栏标题实时刷新）。 */
    public void setSelectionCallback(Runnable callback) {
        this.selectionCallback = callback;
    }

    private void notifySelectionChanged() {
        if (selectionCallback != null) selectionCallback.run();
    }

    public interface OnClickListener {

        void onItemClick(Keep item);

        boolean onLongClick();
    }

    public void setSize(int[] size) {
        this.width = size[0];
        this.height = size[1];
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.delete = delete;
        if (!delete) selected.clear();
        notifyItemRangeChanged(0, getItemCount());
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public boolean isSelected(Keep item) {
        return selected.contains(item.getKey());
    }

    public void toggle(Keep item) {
        if (selected.contains(item.getKey())) selected.remove(item.getKey());
        else selected.add(item.getKey());
        notifyItemChanged(getItems().indexOf(item));
        notifySelectionChanged();
    }

    public void selectAll() {
        selected.clear();
        for (Keep item : getItems()) selected.add(item.getKey());
        notifyItemRangeChanged(0, getItemCount());
        notifySelectionChanged();
    }

    public void selectNone() {
        selected.clear();
        notifyItemRangeChanged(0, getItemCount());
        notifySelectionChanged();
    }

    /** 选中的 Keep 对象（按当前列表顺序）。 */
    private List<Keep> getSelected() {
        return getItems().stream().filter(item -> selected.contains(item.getKey())).collect(Collectors.toList());
    }

    /** 删除选中项并清理选中状态。 */
    public void removeSelected() {
        for (Keep item : getSelected()) item.delete();
        List<Keep> remain = getItems().stream().filter(item -> !selected.contains(item.getKey())).collect(Collectors.toList());
        selected.clear();
        setItems(remain);
    }

    @Override
    public void clear() {
        super.clear();
        setDelete(false);
        Keep.deleteAll();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ViewHolder holder = new ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        holder.binding.image.getLayoutParams().height = height;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Keep item = getItem(position);
        holder.binding.name.setText(item.getVodName());
        holder.binding.remark.setVisibility(View.GONE);
        holder.binding.site.setVisibility(View.VISIBLE);
        holder.binding.site.setText(item.getSiteName());
        holder.binding.progress.setVisibility(View.GONE);
        boolean marked = delete && isSelected(item);
        holder.binding.delete.setVisibility(marked ? View.VISIBLE : View.GONE);
        // width / height 是 onMeasure 前就算好的卡片尺寸（Product#getSpec），在绑定期锁定解码尺寸，
        // 避免「先按原图解码、布局后再重解一次」的瞬时双份位图（与电视版同一做法）。
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image, true, width, height);
        setClickListener(holder.binding.getRoot(), item);
    }

    private void setClickListener(View root, Keep item) {
        root.setOnLongClickListener(view -> listener.onLongClick());
        root.setOnClickListener(view -> {
            if (isDelete()) toggle(item);
            else listener.onItemClick(item);
        });
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterVodBinding binding;

        ViewHolder(@NonNull AdapterVodBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
