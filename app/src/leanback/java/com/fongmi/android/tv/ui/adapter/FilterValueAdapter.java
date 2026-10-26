package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.databinding.AdapterFilterValueBinding;

import java.util.List;

/**
 * 筛选值的横排列表（一组条件里的所有可选值，如「全部 / 动作 / 喜剧」）。
 *
 * <p>电视版的关键点：<b>上下键要跨组跳转</b>。普通 RecyclerView 的焦点搜索在这种情况下
 * 会按几何位置猜，跨行落点不可控（可能跳到隔组、也可能卡在本行末尾），所以这里
 * 把上下键<b>拦下来交给外层弹窗显式处理</b>（见 {@link Listener#onValueKey}），
 * 左右键保持默认行为（横向 RecyclerView 自己处理）。</p>
 */
public class FilterValueAdapter extends RecyclerView.Adapter<FilterValueAdapter.ViewHolder> {

    public interface Listener {

        void onValueClick(int groupIndex, Filter filter, Value value);

        /** 返回 true 表示已消费该按键（上下键跨组跳转）。 */
        boolean onValueKey(int groupIndex, int valueIndex, int keyCode);
    }

    private final Listener listener;
    private final List<Value> mItems;
    private final Filter filter;
    private final int groupIndex;

    public FilterValueAdapter(Listener listener, Filter filter, int groupIndex) {
        this.listener = listener;
        this.filter = filter;
        this.mItems = filter.getValue();
        this.groupIndex = groupIndex;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterFilterValueBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Value item = mItems.get(position);
        // 畸形源可能只给 v 不给 n：用 v 兜底，绝不显示一个空胶囊（老人会以为按坏了）
        holder.binding.text.setText(TextUtils.isEmpty(item.getN()) ? item.getV() : item.getN());
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.text.setOnClickListener(v -> listener.onValueClick(groupIndex, filter, item));
        holder.binding.text.setOnKeyListener((v, keyCode, event) -> {
            // 只拦「按下」且是上下键：抬起事件放行，避免吞掉系统的按键反馈
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            int index = holder.getBindingAdapterPosition();
            if (index == RecyclerView.NO_POSITION) return true;
            return listener.onValueKey(groupIndex, index, keyCode);
        });
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterFilterValueBinding binding;

        ViewHolder(@NonNull AdapterFilterValueBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

}
