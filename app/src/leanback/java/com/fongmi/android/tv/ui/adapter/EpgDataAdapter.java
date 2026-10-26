package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.databinding.AdapterEpgDataBinding;

import java.util.ArrayList;
import java.util.List;

public class EpgDataAdapter extends RecyclerView.Adapter<EpgDataAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<EpgData> mItems;

    public EpgDataAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
    }

    public void addAll(List<EpgData> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public EpgData get(int position) {
        return mItems.get(position);
    }

    public void setSelected(EpgData selected) {
        for (EpgData item : mItems) item.setSelected(selected);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterEpgDataBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        EpgData item = mItems.get(position);
        holder.binding.time.setText(item.getTime());
        holder.binding.title.setText(item.getTitle());
        holder.binding.getRoot().setSelected(item.isSelected());
        holder.binding.getRoot().setLeftListener(mListener::hideEpg);
        holder.binding.getRoot().setOnClickListener(v -> {
            if (!item.isFuture()) mListener.onItemClick(item);
        });
        // 超长文字滚动显示而非盲目截断：title/time 是 ellipsize=marquee + singleLine，
        // 而 TextView 的 marquee 只由「自身 isFocused() || isSelected()」驱动。
        //
        // ⚠️ 早期版本为触发滚动，把行焦点「委托」给 title（setFocusable(true)+requestFocus()），
        // 这会破坏遥控器按键分发：焦点落到 title（TextView）后，行容器自身失去 FOCUSED，
        // ViewGroup.dispatchKeyEvent 只把事件交给焦点子视图 title，而 TextView 不消费任何
        // 方向键/确定键并直接 return false（不会回退给行容器）—— 上下键无法在节目间移动、
        // 确定键点不动，用户表现为「节目单里不能换台」。
        // 现改为：**焦点始终留在行容器 CustomLeftRightLayout 上**（左右键拦截、上下键换行、
        // 确定键 onClick 全部走原有路径），只把 marquee 挂到 title/time 自身的 selected 上。
        holder.binding.title.setMarqueeRepeatLimit(-1);
        holder.binding.time.setMarqueeRepeatLimit(-1);
        android.view.View root = holder.binding.getRoot();
        android.view.View title = holder.binding.title;
        android.view.View time = holder.binding.time;
        title.setSelected(root.isFocused() || item.isSelected());
        time.setSelected(root.isFocused() || item.isSelected());
        root.setOnFocusChangeListener((v, hasFocus) -> {
            title.setSelected(hasFocus || item.isSelected());
            time.setSelected(hasFocus || item.isSelected());
        });
    }

    public interface OnClickListener {

        void hideEpg();

        void onItemClick(EpgData item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterEpgDataBinding binding;

        ViewHolder(@NonNull AdapterEpgDataBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
