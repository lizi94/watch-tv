package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.databinding.AdapterChannelBinding;

import java.util.ArrayList;
import java.util.List;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Channel> mItems;

    public ChannelAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
    }

    public void addAll(List<Channel> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void remove(Channel item) {
        int index = mItems.indexOf(item);
        if (index < 0) return;
        mItems.remove(index);
        notifyItemRemoved(index);
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public Channel get(int position) {
        return mItems.get(position);
    }

    public void setSelected(Channel selected) {
        for (Channel item : mItems) item.setSelected(selected);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterChannelBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Channel item = mItems.get(position);
        item.loadLogo(holder.binding.logo);
        holder.binding.name.setText(item.getShow());
        holder.binding.number.setText(item.getNumber());
        holder.binding.getRoot().setSelected(item.isSelected());
        holder.binding.getRoot().setRightListener(() -> mListener.showEpg(item));
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
        holder.binding.getRoot().setOnLongClickListener(v -> mListener.onLongClick(item));
        // 超长文字滚动显示而非盲目截断：name 是 ellipsize=marquee + singleLine，
        // 而 TextView 的 marquee 只由「自身 isFocused() || isSelected()」驱动。
        //
        // ⚠️ 早期版本为触发滚动，把行焦点「委托」给 name（setFocusable(true)+requestFocus()），
        // 这会破坏遥控器按键分发：焦点落到 name（TextView）后，行容器自身失去 FOCUSED，
        // ViewGroup.dispatchKeyEvent 只把事件交给焦点子视图 name，而 TextView 不消费任何
        // 方向键/确定键并直接 return false（不会回退给行容器）—— 上下键无法在频道间移动、
        // 确定键点不动（右键 setRightListener 打开节目单也会一并失效）。
        // 现改为：**焦点始终留在行容器 CustomLeftRightLayout 上**，只把 marquee 挂到 name 自身的 selected 上。
        holder.binding.name.setMarqueeRepeatLimit(-1);
        android.view.View root = holder.binding.getRoot();
        android.view.View name = holder.binding.name;
        name.setSelected(root.isFocused() || item.isSelected());
        root.setOnFocusChangeListener((v, hasFocus) -> name.setSelected(hasFocus || item.isSelected()));
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        ImgUtil.clear(holder.binding.logo);
    }

    public interface OnClickListener {

        void showEpg(Channel item);

        void onItemClick(Channel item);

        boolean onLongClick(Channel item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterChannelBinding binding;

        ViewHolder(@NonNull AdapterChannelBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
