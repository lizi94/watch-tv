package com.fongmi.android.tv.ui.adapter;

import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeBinding;

import java.util.ArrayList;
import java.util.List;

public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Episode> mItems;
    private int nextFocusDown;
    private int nextFocusUp;

    public EpisodeAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
    }

    /**
     * 选集按钮最大宽度（px）= 屏宽 − 左右各 24dp 的页面边距。
     *
     * <p><b>⛔ 为什么在绑定期按 View 的 Resources 现算，而不是构造期算一次</b>：
     * 原写法是 {@code ResUtil.getScreenWidth() - ResUtil.dp2px(48)}，两个都是
     * <b>Application 侧</b>：前者靠 {@code isLand(App)} 判「取长边还是短边」，
     * 部分机型 Application 的 orientation 与 Activity 的 {@code sensorLandscape} 不同步，
     * 一旦被判成竖屏就返回<b>短边</b> —— 最大宽度只剩约 56%，长一点的集名会被提前 ellipsize。
     * 这与「大屏图片尺寸调到特大时少一列」是同一个根因；
     * 后者（density）还漏掉了字号档位与大屏补偿。改用 View 自己的 Resources，
     * 两点一起解决，且天然跟随当前窗口宽度。</p>
     */
    private static int maxWidth(View view) {
        DisplayMetrics metrics = view.getResources().getDisplayMetrics();
        return Math.max(1, metrics.widthPixels - Math.round(48 * metrics.density));
    }

    public void addAll(List<Episode> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public int getPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    public Episode getActivated() {
        return mItems.isEmpty() ? new Episode() : mItems.get(getPosition());
    }

    public Episode getNext() {
        // 空列表时 max = -1 ⇒ mItems.get(-1) 抛越界；这里返回空对象比抛异常安全。
        if (mItems.isEmpty()) return new Episode();
        int current = getPosition();
        int max = getItemCount() - 1;
        current = ++current > max ? max : current;
        return mItems.get(current);
    }

    public Episode getPrev() {
        if (mItems.isEmpty()) return new Episode();
        int current = getPosition();
        current = --current < 0 ? 0 : current;
        return mItems.get(current);
    }

    public void setNextFocusDown(int nextFocusDown) {
        this.nextFocusDown = nextFocusDown;
        notifyDataSetChanged();
    }

    public void setNextFocusUp(int nextFocusUp) {
        this.nextFocusUp = nextFocusUp;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterEpisodeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Episode item = mItems.get(position);
        holder.binding.text.setMaxWidth(maxWidth(holder.binding.text));
        holder.binding.text.setNextFocusUpId(nextFocusUp);
        holder.binding.text.setNextFocusDownId(nextFocusDown);
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.text.setText(item.getDesc().concat(item.getName()));
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
    }

    public interface OnClickListener {

        void onItemClick(Episode item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterEpisodeBinding binding;

        ViewHolder(@NonNull AdapterEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
