package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.AdapterSiteManageBinding;
import com.fongmi.android.tv.utils.SiteVisibility;

import java.util.ArrayList;
import java.util.List;

/**
 * 站源列表（双端共用，2026-10-27 新增，586 重构）。
 *
 * <p>两种模式共用一套行样式（双端各一份同名 {@code adapter_site_manage.xml}，
 * {@code @id/text} / {@code @id/state} 一致）：</p>
 * <ul>
 *   <li><b>管理模式</b>：右侧显示「已开 / 已关」，点击=切换启用状态；</li>
 *   <li><b>选择模式</b>（{@link #setPickMode}，供一键锁定选源用）：隐藏状态文字，
 *       点击=直接回调选中（<b>不改变启用状态</b>）。</li>
 * </ul>
 */
public class SiteManageAdapter extends RecyclerView.Adapter<SiteManageAdapter.ViewHolder> {

    /** 管理模式下切换后回调，交给宿主决定「筛选态下是否把该行从列表移除」。 */
    public interface OnToggleListener {
        void onToggled(Site site, int position);
    }

    /** 选择模式下点中某行。 */
    public interface OnPickListener {
        void onPick(Site site);
    }

    /** 电视版：背景/焦点框全部由布局里的 Ag.ListItem 提供，代码不碰。 */
    private static final boolean LEANBACK = "leanback".equals(com.fongmi.android.tv.BuildConfig.FLAVOR);

    private final List<Site> items = new ArrayList<>();
    /** 选择模式下标「使用中」的站源 key（当前锁定/生效的源）；空 = 不标。 */
    private String currentKey = "";
    private OnToggleListener onToggle;
    private OnPickListener onPick;
    private boolean pickMode;

    public void setOnToggleListener(OnToggleListener listener) {
        this.onToggle = listener;
    }

    public void setOnPickListener(OnPickListener listener) {
        this.onPick = listener;
    }

    /** 弹窗行背景：与弹窗深色面板同源的淡白圆角行，按下/聚焦主色高亮（见 shape_site_pick_row）。 */
    private static final int DIALOG_BG = R.drawable.shape_site_pick_row;

    /** 选择模式下把某行标成「使用中」。 */
    public void setCurrentKey(String key) {
        this.currentKey = key == null ? "" : key;
    }

    public void setPickMode(boolean pickMode) {
        this.pickMode = pickMode;
        notifyDataSetChanged();
    }

    public void setItems(List<Site> sites) {
        items.clear();
        if (sites != null) items.addAll(sites);
        notifyDataSetChanged();
    }

    /** 当前列表里已启用的数量（不是全量）。 */
    public int getEnabledCount() {
        return SiteVisibility.countEnabled(items);
    }

    public int getTotalCount() {
        return items.size();
    }

    public Site getItem(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    /**
     * 移除一行。
     *
     * <p>筛选态下「打开一个就少一个」靠它实现。⚠ 焦点要由宿主在布局完成后再接
     * （见 {@code SiteManageActivity#focusAfterRemove}），否则会拿到已回收的 itemView。</p>
     */
    public void removeAt(int position) {
        if (position < 0 || position >= items.size()) return;
        items.remove(position);
        notifyItemRemoved(position);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ViewHolder holder = new ViewHolder(AdapterSiteManageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        // 选择模式换成系统 ripple 背景后，背景自带的 12/8dp 内边距就没了（ripple 没有 padding），
        // 会让弹窗里的文字贴到弹窗边缘 —— 这里补回来，保证与手机端管理页逐行对齐。
        // ⚠ 只在创建时设一次：放到 onBindViewHolder 会让每次绑定都 requestLayout 一次。
        if (!LEANBACK && pickMode) {
            float density = holder.binding.getRoot().getResources().getDisplayMetrics().density;
            int h = Math.round(12 * density);
            int v = Math.round(8 * density);
            holder.binding.getRoot().setPadding(h, v, h, v);
        }
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Site item = items.get(position);
        holder.binding.text.setText(item.getName());
        if (pickMode) {
            // 选择模式：列表里全是已启用的源，「已开/已关」没有意义；当前正在使用的源
            // 用主色「使用中」标出来，老人一眼知道哪个是正在看的站。
            boolean inUse = !currentKey.isEmpty() && currentKey.equals(item.getKey());
            holder.binding.state.setVisibility(inUse ? View.VISIBLE : View.GONE);
            if (inUse) {
                holder.binding.state.setText(R.string.site_pick_in_use);
                holder.binding.state.setTextColor(com.google.android.material.color.MaterialColors.getColor(holder.binding.getRoot(), androidx.appcompat.R.attr.colorPrimary));
            }
        } else {
            holder.binding.state.setVisibility(View.VISIBLE);
            boolean on = SiteVisibility.isEnabled(item);
            holder.binding.state.setText(on ? R.string.site_manage_on : R.string.site_manage_off);
            holder.binding.state.setSelected(on);
        }
        applyBackground(holder.binding.getRoot());
        holder.binding.getRoot().setOnClickListener(v -> onClick(holder.getBindingAdapterPosition()));
    }

    /**
     * 列表项背景。
     *
     * <p>手机端：管理页沿用布局自带的 <code>shape_item</code>（卡片 + 4dp 圆角 + ripple），
     * <b>这里一个字都不改</b>；弹窗里换成系统 <code>selectableItemBackground</code>（透明行 + 按下波纹）——
     * 原来弹窗里也套 shape_item，条目像一张张堆叠的卡片，与弹窗整体观感不搭，按下高亮也偏弱。</p>
     *
     * <p>⚠ 电视版必须不碰背景：它的焦点框来自布局的 selector_item，
     * 换掉背景等于把焦点框一起弄丢，遥控器就没法用了。</p>
     *
     * <p>⚠ 不得引用 flavor 专属 drawable（如 mobile 的 shape_item）：本类在 main 层，
     * leanback 编译时资源表里没有它，会直接编译失败。</p>
     */
    private void applyBackground(View root) {
        if (LEANBACK) return;
        // 管理页：背景由 adapter_site_manage.xml 自带；弹窗（选择模式）换淡白圆角行。
        if (!pickMode) return;
        root.setBackgroundResource(DIALOG_BG);
    }

    private void onClick(int position) {
        if (position == RecyclerView.NO_POSITION || position >= items.size()) return;
        Site item = items.get(position);
        if (pickMode) {
            if (onPick != null) onPick.onPick(item);
            return;
        }
        SiteVisibility.toggle(item);
        notifyItemChanged(position);
        if (onToggle != null) onToggle.onToggled(item, position);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSiteManageBinding binding;

        public ViewHolder(@NonNull AdapterSiteManageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
