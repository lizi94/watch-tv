package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.databinding.AdapterTypeBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class TypeAdapter extends RecyclerView.Adapter<TypeAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Class> mItems;

    public TypeAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
    }

    public interface OnClickListener {

        void onItemClick(int position, Class item);
    }

    private Class home() {
        Class type = new Class();
        type.setTypeName(ResUtil.getString(R.string.vod_home));
        type.setTypeId("home");
        return type;
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public void addAll(Result result) {
        // ⛔ 2026-11-06（R114）：**先清空再填**。本方法的语义是「用 result 替换当前分类列表」，
        //   但原来直接 addAll 追加、且每次都 add(0, home()) —— 调用方一旦没有先 clear()
        //   （例如 VodFragment 同进程重建后直接刷新内容），就会变成
        //   「两个推荐 + 分类翻倍」：mItems = [推荐, 推荐, A, B, 推荐, A, B]。
        //   修在数据源头，所有调用方都安全；不要再依赖「调用前记得 clear」这种口头约定。
        mItems.clear();
        mItems.addAll(result.getTypes());
        if (!result.getList().isEmpty()) mItems.add(0, home());
        if (!mItems.isEmpty()) mItems.get(0).setSelected(true);
        notifyDataSetChanged();
    }

    public void setSelected(int position) {
        for (Class item : mItems) item.setSelected(false);
        mItems.get(position).setSelected(true);
        notifyItemRangeChanged(0, mItems.size());
    }

    public Class get(int position) {
        return mItems.get(position);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterTypeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Class item = mItems.get(position);
        holder.binding.text.setText(item.getTypeName());
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.text.setOnClickListener(v -> listener.onItemClick(position, item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterTypeBinding binding;

        ViewHolder(@NonNull AdapterTypeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}