package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.databinding.DialogHomeFilterBinding;
import com.fongmi.android.tv.ui.adapter.FilterGroupAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 首页「筛选」弹窗（电视版）：复刻手机端首页分类的筛选能力（类型 / 地区 / 年份…）。
 *
 * <p><b>与手机端一致的数据语义</b>：</p>
 * <ul>
 *   <li>组内<b>单选</b>：选中某个值会把同组其它值取消（{@link Value#setSelected(Value)} 自带 toggle：
 *       再点同一项 = 取消该条件，回到「全部」——老人有办法自己撤销）。</li>
 *   <li>参数组装：选中 → {@code extend.put(filter.key, value.v)}；取消 → {@code extend.remove(key)}。</li>
 *   <li>选择<b>立即生效</b>（手机端也是选了就刷新），不需要再按「确定」；面板保持打开，可连调多组。</li>
 * </ul>
 *
 * <p><b>电视端适配</b>：</p>
 * <ul>
 *   <li>焦点：组内左右键（横向列表自带）；<b>组间上下键由本类显式桥接</b>
 *       （{@link #onValueKey}），不依赖 FocusFinder 的几何猜测。</li>
 *   <li>令牌化 + 字号档位：布局全用 ag_* 令牌，Context 是 Activity（{@code builder()} 用
 *       {@code requireActivity()}）⇒ 随「字号大小」档与大屏补偿等比放大。</li>
 *   <li>重建安全：{@code filters}(Parcelable) 与 {@code extend}(Serializable) 都写进 arguments，
 *       切字号档位导致 DialogFragment 被重建时条件不丢。</li>
 * </ul>
 */
public class HomeFilterDialog extends BaseAlertDialog implements FilterGroupAdapter.Listener {

    /**
     * 取回调方（含重建兜底）：{@code listener} 只在 {@code show(activity)} 里赋值，
     * DialogFragment 被系统重建后不会重新走 show() ⇒ 原来会「选了筛选值但列表不刷新、焦点也不送回」。
     */
    private Listener listener() {
        if (listener == null && requireActivity() instanceof Listener) listener = (Listener) requireActivity();
        return listener;
    }

    public static final String TAG = "HomeFilter";
    private static final String KEY_FILTERS = "filters";
    private static final String KEY_EXTEND = "extend";
    private static final String KEY_CATEGORY = "category";
    /** 弹窗宽度占屏宽比例：够宽能一排放 4~5 个值，又不至于把内容区完全遮住。 */
    private static final float WIDTH_FACTOR = 0.64f;

    public interface Listener {

        /** 筛选条件变化（已组装好的 extend 副本）——宿主据此用 force 重新请求当前分类。 */
        void onFilterChanged(HashMap<String, String> extend);

        /** 弹窗关闭——宿主据此把焦点送回分类行（内容区已被重建，原焦点视图不在了）。 */
        void onFilterClosed();
    }

    private DialogHomeFilterBinding binding;
    private FilterGroupAdapter groupAdapter;
    private ArrayList<Filter> filters;
    private HashMap<String, String> extend;
    private String category;
    private Listener listener;

    public static HomeFilterDialog create() {
        return new HomeFilterDialog();
    }

    /** ⚠ 必须在 show() 之前调用（Fragment 尚未 attach，才能写 arguments）。 */
    public HomeFilterDialog filters(List<Filter> filters) {
        this.filters = new ArrayList<>(filters);
        Bundle bundle = getArguments() == null ? new Bundle() : getArguments();
        bundle.putParcelableArrayList(KEY_FILTERS, this.filters);
        setArguments(bundle);
        return this;
    }

    public HomeFilterDialog extend(HashMap<String, String> extend) {
        this.extend = new HashMap<>(extend);
        Bundle bundle = getArguments() == null ? new Bundle() : getArguments();
        bundle.putSerializable(KEY_EXTEND, this.extend);
        setArguments(bundle);
        return this;
    }

    /**
     * 当前分类名（如「电影」）—— 标题会显示成「筛选 · 电影」。
     *
     * <p>⚠ 2026-10-17：用户反馈「弹窗里不清楚自己在筛哪个分类」。筛选条件本身是**跟着分类走**的
     * （不同分类的 filters 完全不同），不写分类名老人根本无法确认自己调的是哪一页的条件。
     * 传空串时标题回退成纯「筛选」（理论上不会发生：没有分类就没有筛选入口）。</p>
     */
    public HomeFilterDialog category(String category) {
        this.category = category;
        Bundle bundle = getArguments() == null ? new Bundle() : getArguments();
        bundle.putString(KEY_CATEGORY, category);
        setArguments(bundle);
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), TAG);
        if (activity instanceof Listener) listener = (Listener) activity;
    }

    /** 重建（切字号档位 / 进程恢复）后把条件与选中值读回来，否则面板会变空。 */
    @SuppressWarnings("unchecked")
    @Override
    protected void restoreArguments() {
        ArrayList<Filter> saved = getArguments() == null ? null : getArguments().getParcelableArrayList(KEY_FILTERS);
        if (saved != null) filters = saved;
        if (getArguments() != null) {
            Object savedExtend = getArguments().getSerializable(KEY_EXTEND);
            if (savedExtend instanceof HashMap) extend = (HashMap<String, String>) savedExtend;
        }
        if (extend == null) extend = new HashMap<>();
        if (filters == null) filters = new ArrayList<>();
        if (getArguments() != null) category = getArguments().getString(KEY_CATEGORY);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogHomeFilterBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        // 标题带上分类名：老人一眼确认「我在调哪个分类的条件」
        binding.title.setText(TextUtils.isEmpty(category)
                ? getString(R.string.home_filter_title)
                : getString(R.string.home_filter_title_of, category));
        groupAdapter = new FilterGroupAdapter(this, filters);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setAdapter(groupAdapter);
        binding.reset.setOnClickListener(v -> resetAll());
        binding.recycler.post(this::focusInitial);
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(WIDTH_FACTOR);
        // 一个条件组都没有时直接关闭（正常不会走到：宿主在打开前已判空）
        if (groupAdapter != null && groupAdapter.getItemCount() == 0) dismiss();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        Listener target = listener();
        if (target != null) target.onFilterClosed();
    }

    // ==================== 值选择 ====================

    @Override
    public void onValueClick(int groupIndex, Filter filter, Value value) {
        // 组内单选（Value#setSelected(Value) 自带 toggle：同项再点 = 取消该条件）
        for (Value item : filter.getValue()) item.setSelected(value);
        if (value.isSelected()) extend.put(filter.getKey(), value.getV());
        else extend.remove(filter.getKey());
        groupAdapter.refreshGroup(binding.recycler, groupIndex);   // 只刷值，不换 adapter ⇒ 焦点不丢
        Listener target = listener();
        if (target != null) target.onFilterChanged(new HashMap<>(extend));
    }

    /** 重置：清空所有条件（等同每项都选回「全部」）。焦点留在重置按钮上，不动。 */
    private void resetAll() {
        for (Filter filter : filters) for (Value item : filter.getValue()) item.setSelected(false);
        extend.clear();
        for (int i = 0; i < groupAdapter.getItemCount(); i++) groupAdapter.refreshGroup(binding.recycler, i);
        Listener target = listener();
        if (target != null) target.onFilterChanged(new HashMap<>(extend));
    }

    // ==================== 上下键跨组 ====================

    @Override
    public boolean onValueKey(int groupIndex, int valueIndex, int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (groupIndex <= 0) return false;   // 第一组再按上：交给系统（可移到重置按钮）
            focusGroup(groupIndex - 1, valueIndex);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (groupIndex >= groupAdapter.getItemCount() - 1) return false;
            focusGroup(groupIndex + 1, valueIndex);
            return true;
        }
        return false;
    }

    /**
     * 把焦点送到「第 groupIndex 组、第 valueIndex 个值」。
     *
     * <p>⛔ 不能用 FocusFinder 让它自己猜：横向列表之间按上下键，几何落点取决于两边列表的实际宽度，
     * 结果不可预期（可能跳到隔组、也可能卡在当前行末尾）。这里显式落焦，行为完全可预测。</p>
     */
    private void focusGroup(int groupIndex, int valueIndex) {
        RecyclerView parent = binding.recycler;
        parent.scrollToPosition(groupIndex);   // 目标组若在可视区外，先滚进来（否则拿不到 ViewHolder）
        parent.post(() -> {
            RecyclerView child = groupAdapter.getChildRecycler(parent, groupIndex);
            if (child == null || child.getAdapter() == null) return;
            int count = child.getAdapter().getItemCount();
            if (count == 0) return;
            int target = Math.min(Math.max(valueIndex, 0), count - 1);
            child.scrollToPosition(target);
            child.post(() -> {
                RecyclerView.ViewHolder holder = child.findViewHolderForAdapterPosition(target);
                if (holder != null) holder.itemView.requestFocus();
            });
        });
    }

    /**
     * 初始落焦：优先落在「当前已生效的条件值」上（老人一打开就看到自己选的是什么），
     * 没有条件则落第一组第一个值。
     */
    private void focusInitial() {
        for (int g = 0; g < filters.size(); g++) {
            List<Value> values = filters.get(g).getValue();
            for (int v = 0; v < values.size(); v++) {
                if (values.get(v).isSelected()) {
                    focusGroup(g, v);
                    return;
                }
            }
        }
        focusGroup(0, 0);
    }

}
