package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.databinding.AdapterGroupBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Group> mItems;

    public GroupAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
    }

    public void addAll(List<Group> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void add(int position, Group item) {
        mItems.add(position, item);
        notifyItemInserted(position);
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public Group get(int position) {
        return mItems.get(position);
    }

    public int indexOf(Group item) {
        return mItems.indexOf(item);
    }

    public List<Group> unmodifiableList() {
        return Collections.unmodifiableList(mItems);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterGroupBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Group item = mItems.get(position);
        holder.binding.name.setText(item.getName());
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
        // 超长文字滚动显示而非盲目截断：name 是 ellipsize=marquee + singleLine，
        // 而 TextView 的 marquee 只由「自身 isFocused() || isSelected()」驱动。
        //
        // ⚠️ 早期版本为触发滚动，把行焦点「委托」给 name（setFocusable(true)+requestFocus()），
        // 这会破坏遥控器按键分发：焦点落到 name（TextView）后，行容器自身失去 FOCUSED，
        // ViewGroup.dispatchKeyEvent 只把事件交给焦点子视图 name，而 TextView 不消费任何
        // 方向键/确定键并直接 return false（不会回退给行容器）—— 上下键无法在分组间移动、
        // 确定键点不动。
        // 现改为：**焦点始终留在行容器 CustomLeftRightLayout 上**，只把 marquee 挂到 name 自身的 selected 上。
        holder.binding.name.setMarqueeRepeatLimit(-1);
        android.view.View root = holder.binding.getRoot();
        android.view.View name = holder.binding.name;
        name.setSelected(root.isFocused());
        root.setOnFocusChangeListener((v, hasFocus) -> name.setSelected(hasFocus));
    }

    public interface OnClickListener {
        void onItemClick(Group item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterGroupBinding binding;

        ViewHolder(@NonNull AdapterGroupBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
