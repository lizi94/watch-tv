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
 * 下载源列表适配器（手机版）。
 *
 * <p>选中反馈走 {@code name.setSelected()} + {@code @color/site_state_color}（白 / 60% 白），
 * 这是手机版列表「当前项」的既有表达方式（同 adapter_array_pick 的 site_state_color 用法），
 * 不新增勾选图标、不新增色值。</p>
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

    public List<Mirror> getItems() {
        return items;
    }

    public int getSelectPosition() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).getKey().equals(select)) return i;
        return 0;
    }

    public int getMeasuredDelayCount() {
        int count = 0;
        for (Mirror mirror : items) if (mirror.isProbeable() && mirror.getDelay() > 0) count++;
        return count;
    }

    /** 可探测节点的总数（「自动 / 直连」不计），与 {@link #getMeasuredDelayCount()} 组成「x/y」。 */
    public int getProbeCount() {
        int count = 0;
        for (Mirror mirror : items) if (mirror.isProbeable()) count++;
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
        holder.binding.name.setSelected(item.getKey().equals(select));
        holder.binding.delay.setText(delayText(item));
        holder.binding.delay.setTextColor(delayColor(item));
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    /** 「自动」行显示它当前会选中的节点名；一个都没测通时显示破折号（此时会用直连）。 */
    private String delayText(Mirror item) {
        if (item.isAuto()) {
            Mirror fastest = Mirror.fastest(items);
            return fastest == null ? ResUtil.getString(R.string.update_mirror_auto_none) : fastest.getName();
        }
        return item.getDelayText();
    }

    /**
     * 手机版只做两档明暗：正常 = white_60、超时/未知 = white_30。
     *
     * <p>刻意不做电视版那样的三色分级：手机端没有橙 / 红这类文字色令牌
     * （Material3 深色只有 colorError），硬造会偏离「不新造令牌」的约定。</p>
     */
    private int delayColor(Mirror item) {
        if (item.isAuto() || item.isDirect()) return ResUtil.getColor(R.color.white_60);
        int delay = item.getDelay();
        if (delay == Mirror.DELAY_UNKNOWN || delay == Mirror.DELAY_TIMEOUT || delay == Mirror.DELAY_BLOCKED) return ResUtil.getColor(R.color.white_30);
        return ResUtil.getColor(R.color.white_60);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterMirrorBinding binding;

        public ViewHolder(@NonNull AdapterMirrorBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
