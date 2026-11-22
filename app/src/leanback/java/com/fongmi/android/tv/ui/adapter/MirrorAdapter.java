package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterMirrorBinding;
import com.fongmi.android.tv.update.Mirror;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;

/**
 * 下载源列表适配器（电视版）。
 *
 * <p>选中态用 {@code getRoot().setSelected()}：Ag.ListItem 的背景 selector_item 里
 * {@code state_selected} 映射到 shape_item_activated（淡蓝底 + 2dp 主色边），
 * 与全站列表「当前项」的视觉完全一致，不需要新增勾选图标或单选圆点。</p>
 *
 * <p>⚠ 点击必须绑在**根布局**上：Ag.ListItem 的 focusable 在根上，遥控器焦点落整行，
 * 绑在文字上按 OK 键不会触发（R122 踩过的回归）。</p>
 */
public class MirrorAdapter extends RecyclerView.Adapter<MirrorAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Mirror> items;
    private String select;

    public MirrorAdapter(OnClickListener listener, List<Mirror> items, String select) {
        this.listener = listener;
        this.items = items;
        this.select = select == null ? "" : select;
    }

    public interface OnClickListener {

        void onItemClick(Mirror item);
    }

    public void setSelect(String select) {
        this.select = select == null ? "" : select;
    }

    public List<Mirror> getItems() {
        return items;
    }

    /** 当前选中项在列表里的位置，用于打开弹窗时滚到它。找不到则回 0（自动）。 */
    public int getSelectPosition() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).getKey().equals(select)) return i;
        return 0;
    }

    public int getMeasuredDelayCount() {
        int count = 0;
        for (Mirror mirror : items) if (mirror.isProbeable() && mirror.getDelay() > 0) count++;
        return count;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterMirrorBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Mirror item = items.get(position);
        holder.binding.name.setText(item.getName());
        holder.binding.delay.setText(delayText(item));
        holder.binding.delay.setTextColor(delayColor(item));
        holder.binding.getRoot().setSelected(item.getKey().equals(select));
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    /**
     * 「自动」那一行不显示延迟，而是显示它当前会选中的节点名 —— 让用户点之前就知道会走谁。
     * 一个节点都没测通时显示破折号，此时自动会回落到直连。
     */
    private String delayText(Mirror item) {
        if (item.isAuto()) {
            Mirror fastest = Mirror.fastest(items);
            return fastest == null ? ResUtil.getString(R.string.update_delay_unknown) : fastest.getName();
        }
        return item.getDelayText();
    }

    /**
     * 延迟三档配色，色值全部取自现有 ag_* 令牌：
     * 快 = 主色、中 = 警告橙、慢 = 危险红、未知/超时 = 提示灰。
     */
    private int delayColor(Mirror item) {
        if (item.isAuto()) return ResUtil.getColor(R.color.ag_primary);
        if (item.isDirect()) return ResUtil.getColor(R.color.ag_text_hint);
        int delay = item.getDelay();
        if (delay == Mirror.DELAY_UNKNOWN || delay == Mirror.DELAY_TIMEOUT) return ResUtil.getColor(R.color.ag_text_hint);
        if (delay <= 300) return ResUtil.getColor(R.color.ag_primary);
        if (delay <= 800) return ResUtil.getColor(R.color.ag_warn_text);
        return ResUtil.getColor(R.color.ag_danger);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterMirrorBinding binding;

        public ViewHolder(@NonNull AdapterMirrorBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
