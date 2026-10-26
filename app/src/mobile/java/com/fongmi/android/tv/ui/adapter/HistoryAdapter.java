package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 历史记录适配器（手机版）：普通态点击继续播放；
 * 选择态（长按或菜单进入）点击切换选中标记，配合工具栏「删除所选」批量删除。
 * 与电视版同一交互模式。
 */
public class HistoryAdapter extends BaseDiffAdapter<History, HistoryAdapter.ViewHolder> {

    private final OnClickListener listener;
    private int width, height;
    private boolean animate;
    private boolean delete;
    private final Set<String> selected;
    private Runnable selectionCallback;

    public HistoryAdapter(OnClickListener listener) {
        this.listener = listener;
        this.animate = true;
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

        void onItemClick(History item);

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
        this.animate = false;
        this.delete = delete;
        if (!delete) selected.clear();
        notifyItemRangeChanged(0, getItemCount());
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public boolean isSelected(History item) {
        return selected.contains(item.getKey());
    }

    public void toggle(History item) {
        if (selected.contains(item.getKey())) selected.remove(item.getKey());
        else selected.add(item.getKey());
        notifyItemChanged(getItems().indexOf(item));
        notifySelectionChanged();
    }

    public void selectAll() {
        selected.clear();
        for (History item : getItems()) selected.add(item.getKey());
        notifyItemRangeChanged(0, getItemCount());
        notifySelectionChanged();
    }

    public void selectNone() {
        selected.clear();
        notifyItemRangeChanged(0, getItemCount());
        notifySelectionChanged();
    }

    /** 选中的 History 对象（按当前列表顺序）。 */
    private List<History> getSelected() {
        return getItems().stream().filter(item -> selected.contains(item.getKey())).collect(Collectors.toList());
    }

    /** 删除选中项并退出其选中状态，返回删除数量。 */
    public int removeSelected() {
        int count = selected.size();
        for (History item : getSelected()) item.delete();
        List<History> remain = getItems().stream().filter(item -> !selected.contains(item.getKey())).collect(Collectors.toList());
        selected.clear();
        setItems(remain);
        return count;
    }

    @Override
    public void clear() {
        super.clear();
        setDelete(false);
        History.clear(VodConfig.getCid());
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
        History item = getItem(position);
        boolean same = item.getVodName().equals(item.getVodRemarks());
        holder.binding.name.setText(item.getVodName());
        holder.binding.site.setText(item.getSiteName());
        holder.binding.remark.setText(item.getVodRemarks());
        holder.binding.site.setVisibility(item.getSiteVisible());
        holder.binding.progress.setMax((int) item.getDuration());
        holder.binding.progress.setProgress((int) item.getPosition(), animate);
        boolean marked = delete && isSelected(item);
        holder.binding.delete.setVisibility(marked ? View.VISIBLE : View.GONE);
        holder.binding.remark.setVisibility(delete || same ? View.GONE : View.VISIBLE);
        // width / height 是 onMeasure 前就算好的卡片尺寸（Product#getSpec），在绑定期锁定解码尺寸，
        // 避免「先按原图解码、布局后再重解一次」的瞬时双份位图（与电视版同一做法）。
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image, true, width, height);
        setClickListener(holder.binding.getRoot(), item);
    }

    private void setClickListener(View root, History item) {
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
