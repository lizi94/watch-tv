package com.fongmi.android.tv.ui.adapter;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.utils.CoverText;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 收藏页适配器：普通态点击进入详情；选择态（批量删除）点击切换选中标记。
 * 选中集合以 Keep.key 为准，删除后自动清理，避免悬挂引用。
 */
public class KeepAdapter extends BaseDiffAdapter<Keep, KeepAdapter.ViewHolder> {

    private final OnClickListener listener;
    private int width, height;
    private boolean selectMode;
    private final Set<String> selected;

    public KeepAdapter(OnClickListener listener) {
        this.listener = listener;
        this.selected = new HashSet<>();
    }

    public interface OnClickListener {

        void onItemClick(Keep item);
    }

    /**
     * 封面尺寸（px）：与首页分类 / 搜索结果 / 「最近观看」共用 {@link Product#getSpec}。
     *
     * <p>⛔ 原先这里是手写公式，行距占位用 {@code ResUtil.dp2px(48)} —— Application 侧 density，
     * 不随字号档位与大屏补偿缩放；而分类/搜索封面走 Activity 侧 density。
     * 档位越高两者差得越远，同屏两种卡片大小不同。详见 {@code HistoryPresenter#spec}。</p>
     */
    private void setLayoutSize(Context context) {
        int[] size = Product.getSpec(context, Style.rect());
        width = size[0];
        height = size[1];
    }

    public boolean isSelectMode() {
        return selectMode;
    }

    public void setSelectMode(boolean selectMode) {
        this.selectMode = selectMode;
        if (!selectMode) selected.clear();
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
    }

    public void selectAll() {
        selected.clear();
        for (Keep item : getItems()) selected.add(item.getKey());
        notifyItemRangeChanged(0, getItemCount());
    }

    public void selectNone() {
        selected.clear();
        notifyItemRangeChanged(0, getItemCount());
    }

    /** 选中的 Keep 对象（按当前列表顺序）。 */
    public List<Keep> getSelected() {
        return getItems().stream().filter(item -> selected.contains(item.getKey())).collect(Collectors.toList());
    }

    public void removeSelected() {
        for (Keep item : getSelected()) item.delete();
        List<Keep> remain = getItems().stream().filter(item -> !selected.contains(item.getKey())).collect(Collectors.toList());
        selected.clear();
        setItems(remain);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // 尺寸依赖 Activity 侧 density，只能拿到 parent 之后才算（width == 0 表示尚未计算）
        if (width <= 0) setLayoutSize(parent.getContext());
        ViewHolder holder = new ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        holder.binding.image.getLayoutParams().height = height;
        // 封面文字跟随封面宽度并叠加字号档位增益（CoverText）：档位越高字越大，图文比例恒定
        int sub = CoverText.sub(width);
        holder.binding.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, CoverText.title(width));
        holder.binding.site.setTextSize(TypedValue.COMPLEX_UNIT_PX, sub);
        holder.binding.remark.setTextSize(TypedValue.COMPLEX_UNIT_PX, sub);
        // 角标超长兜底：框在封面宽度内，不溢到相邻列（常规短角标不触发）
        CoverText.clamp(holder.binding.remark, holder.binding.site, width);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Keep item = getItem(position);
        holder.itemView.setOnClickListener(view -> listener.onItemClick(item));
        holder.binding.name.setText(item.getVodName());
        holder.binding.site.setVisibility(View.VISIBLE);
        holder.binding.site.setText(item.getSiteName());
        holder.binding.remark.setVisibility(View.GONE);
        holder.binding.year.setVisibility(View.GONE);
        boolean marked = selectMode && isSelected(item);
        holder.binding.delete.setVisibility(marked ? View.VISIBLE : View.GONE);
        // 锁定解码尺寸到封面实际显示大小（详见 ImgUtil 带尺寸重载说明）
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image, true, width, height);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterVodBinding binding;

        public ViewHolder(@NonNull AdapterVodBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            setFocusListener();
        }

        private void setFocusListener() {
            // 焦点反馈只保留蓝色焦点框（selector_vod，粗细 = ag_stroke_focus）：不缩放、也不做阴影抬升，
            // 与首页 / 搜索 / 分类封面的焦点态完全一致。
            // setSelected 驱动片名 marquee 滚动（marquee 条件 = isFocused() || isSelected()），必须保留。
            itemView.setOnFocusChangeListener((v, hasFocus) -> v.setSelected(hasFocus));
        }
    }
}
