package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.AdapterConfigBinding;
import com.fongmi.android.tv.utils.Notify;

import java.util.List;

public class ConfigAdapter extends RecyclerView.Adapter<ConfigAdapter.ViewHolder> {

    private final OnClickListener listener;
    private List<Config> mItems;
    private boolean readOnly;
    private int usedId = -1;

    public ConfigAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onTextClick(Config item);

        /** 行尾启停按钮（原删除按钮）：切换该配置的启用/停用。 */
        void onActionClick(Config item);
    }

    public ConfigAdapter readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    /**
     * 加载某类型的历史配置。
     *
     * <p>原来会 remove(0) 把「使用中」的配置剔出列表，表现为点选谁谁就消失；
     * 现在保留它并显示「使用中」标注（选中后不再消失）。</p>
     *
     * <p>readOnly（直播页切换器）：只列启用的配置——看电视场景不该出现停用源。</p>
     */
    public ConfigAdapter addAll(int type) {
        mItems = Config.getAll(type);
        Config used = Config.findOne(type);
        usedId = used == null ? -1 : used.getId();
        if (readOnly) mItems.removeIf(item -> item.isDisabled());
        return this;
    }

    /**
     * 就地启停：翻转落库并刷新整表（state 与按钮文字都随 checked 变化）。
     *
     * <p>逻辑层不变量：<b>使用中的配置不允许停用</b>。UI 上按钮已隐藏，这里再兜一层，
     * 保证任何调用方都绕不过（否则会停掉正在用的配置，点不动也换不掉 ⇒ 僵死）。</p>
     */
    public void toggle(Config item) {
        if (item.isChecked() && !item.canDisable()) {
            Notify.show(R.string.config_in_use_cannot_off);
            return;
        }
        item.toggleChecked().save();
        notifyDataSetChanged();
    }

    /** 是否当前使用中：findOne（时间最新）与该行同 id。 */
    private boolean isUsed(Config item) {
        return item.getId() == usedId;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterConfigBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Config item = mItems.get(position);
        holder.binding.text.setText(item.getDesc());
        // 「使用中」标注：当前正在使用的配置（主色），一眼可辨。
        boolean inUse = isUsed(item);
        // R128：停用态不再用「已停用」文字重复表达 —— 那时行尾按钮显示的正是「启用」，
        //   看到「启用」就知道当前是停用。这里只保留按钮表达不了的信息：「使用中」。
        holder.binding.state.setVisibility(inUse ? View.VISIBLE : View.GONE);
        if (inUse) holder.binding.state.setText(R.string.site_pick_in_use);
        // 启停按钮：使用中且启用 → 隐藏（禁停）；使用中却已停用（异常态）→ 显示「启用」以便恢复；
        // 其余启用行 → 「停用」，停用行 → 「启用」。
        boolean showAction = !readOnly && (!inUse || item.isDisabled());
        holder.binding.action.setVisibility(showAction ? View.VISIBLE : View.GONE);
        holder.binding.action.setText(item.isChecked() ? R.string.site_manage_off : R.string.site_manage_on);
        holder.binding.action.setOnClickListener(v -> listener.onActionClick(item));
        // 点行 = 切换使用：绑在 text 上。
        // ⛔ 双焦点目标结构（见 adapter_config.xml 注释）：本行是「双动作行」，行根必须不可聚焦
        //（否则行内启停按钮永远不可达），焦点由 text 与 action 各自承接 —— text 已在布局里
        // 设为 focusable + selector_item，绑 text 遥控器即可触发（R122 从绑根改回绑 text）。
        holder.binding.text.setOnClickListener(v -> onRowClick(item));
    }

    /** 点行：停用项当场拦下并提示，不切换使用（选用停用配置会加载失效地址而报错）。 */
    private void onRowClick(Config item) {
        if (item.isDisabled()) {
            Notify.show(R.string.config_disabled_tip);
            return;
        }
        listener.onTextClick(item);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterConfigBinding binding;

        public ViewHolder(@NonNull AdapterConfigBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
