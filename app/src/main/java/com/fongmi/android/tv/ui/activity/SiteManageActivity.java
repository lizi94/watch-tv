package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivitySiteManageBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.adapter.SiteManageAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.ConfirmDialog;
import com.fongmi.android.tv.utils.ElderLock;
import com.fongmi.android.tv.utils.SiteVisibility;

import java.util.ArrayList;
import java.util.List;

/**
 * 「选择能看的站」（双端共用，2026-10-27 新增，586 优化）。
 *
 * <p><b>为什么由用户在这里决定</b>：网盘源 / 私有库在不同配置里的 spider 命名毫无规律，
 * 任何自动识别换一份配置就失效、猜错还会误杀正常源。所以导入后<b>全部默认打开</b>，
 * 由用户在这里关掉用不了的 —— 关掉即从首页、分类、搜索、换源、一键锁定、历史、收藏里一并消失
 * （过滤点只有一个：{@code VodConfig#getSites()}）。</p>
 *
 * <p><b>586 的交互优化（把「关掉 70 个」变成「打开 10 个」）</b>：
 * 「全部关掉」→「只看已关」→ 逐行打开要看的，<b>打开一个就从列表消失</b>，
 * 待办越来越少、进度看得见，不会重复翻已处理过的源。</p>
 *
 * <p>布局由两个 flavor 各提供一份同名 {@code activity_site_manage.xml}（{@code @id} 一致），
 * 所以这里一份逻辑双端通吃。</p>
 */
public class SiteManageActivity extends BaseActivity {

    private ActivitySiteManageBinding mBinding;
    private SiteManageAdapter mAdapter;
    /** 「只看已关」是否激活。 */
    private boolean onlyDisabled;
    /** 本次进入是否改过选择：改过才在离开时统一重建可见列表并广播（避免连点 N 次触发 N 次首页请求）。 */
    private boolean dirty;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SiteManageActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySiteManageBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mAdapter = new SiteManageAdapter();
        mAdapter.setOnToggleListener(this::onToggled);
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(this));
        mBinding.recycler.setAdapter(mAdapter);

        mBinding.toggleAll.setOnClickListener(v -> onToggleAll());
        mBinding.filter.setOnClickListener(v -> toggleFilter());
        mBinding.toolbar.setNavigationOnClickListener(v -> onBackInvoked());

        reload();
    }

    @Override
    protected void initEvent() {
    }

    // ==================== 列表 ====================

    private List<Site> rawAll() {
        return VodConfig.get().getRawSites();
    }

    /** 按当前筛选重建列表（数据源始终是全量，关掉的也必须列得出来才能恢复）。 */
    private void reload() {
        List<Site> shown = new ArrayList<>();
        for (Site site : rawAll()) {
            if (onlyDisabled && SiteVisibility.isEnabled(site)) continue;
            shown.add(site);
        }
        mAdapter.setItems(shown);
        refreshCount();
        refreshToggle();
        refreshFilter();
        refreshEmpty();
    }

    private void refreshCount() {
        List<Site> all = rawAll();
        mBinding.count.setText(getString(R.string.site_manage_count, SiteVisibility.countEnabled(all), all.size()));
    }

    /**
     * 合并按钮的文案随状态切换：全开时显示「全部停用」，否则显示「全部启用」。
     * （像全选/取消全选：按钮文字本身就是「接下来会发生什么」）
     */
    private void refreshToggle() {
        List<Site> all = rawAll();
        boolean allEnabled = !all.isEmpty() && SiteVisibility.countEnabled(all) == all.size();
        mBinding.toggleAllText.setText(allEnabled ? R.string.site_manage_all_off : R.string.site_manage_all_on);
    }

    private void refreshFilter() {
        List<Site> all = rawAll();
        int off = all.size() - SiteVisibility.countEnabled(all);
        mBinding.filter.setSelected(onlyDisabled);
        mBinding.filterText.setText(onlyDisabled
                ? getString(R.string.site_manage_filter_on, off)
                : getString(R.string.site_manage_filter));
    }

    private void refreshEmpty() {
        boolean empty = mAdapter.getItemCount() == 0;
        mBinding.empty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mBinding.recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) return;
        if (rawAll().isEmpty()) mBinding.empty.setText(R.string.site_manage_empty_no_config);
        else if (onlyDisabled) mBinding.empty.setText(R.string.site_manage_empty_all_on);
        else mBinding.empty.setText(R.string.site_manage_empty_no_config);
    }

    // ==================== 交互 ====================

    private void toggleFilter() {
        onlyDisabled = !onlyDisabled;
        reload();
        // 列表可能变空：焦点先从列表收回筛选行，避免留在已不可见的行上
        mBinding.filter.post(mBinding.filter::requestFocus);
    }

    /**
     * 合并按钮：全开 → 全部停用（破坏性，先确认）；非全开 → 全部启用（安全，直接执行）。
     *
     * <p><b>用 {@link ConfirmDialog} 而不是裸 {@code MaterialAlertDialogBuilder}</b>：
     * 「停用全部站源」与「删除收藏 / 清空历史」是同一类不可逆操作，必须和它们在历史记录、
     * 收藏页里的确认框长成一样 —— 电视端是适老化大按钮版，手机版是系统对话框观感版（本类在 main，
     * 双端共用，观感由各 flavor 的 {@code dialog_confirm.xml} 决定）。顺带它是
     * {@code DialogFragment}：切字号档位 / 旋转时不会像裸弹窗那样直接消失。</p>
     */
    private void onToggleAll() {
        List<Site> all = rawAll();
        if (all.isEmpty()) return;
        boolean allEnabled = SiteVisibility.countEnabled(all) == all.size();
        if (!allEnabled) {
            applyAll(true);
            return;
        }
        ConfirmDialog.create()
                .message(R.string.site_manage_disable_confirm)
                .callback(() -> applyAll(false))
                .show(this);
    }

    private void applyAll(boolean enabled) {
        SiteVisibility.setAll(rawAll(), enabled);
        dirty = true;
        reload();
        // 整批操作后列表整体变化：焦点回到顶部按钮，绝不留在可能已被移除的行上
        mBinding.toggleAll.post(mBinding.toggleAll::requestFocus);
    }

    private void onToggled(Site site, int position) {
        dirty = true;
        refreshCount();
        refreshFilter();
        // 筛选态下「打开一个就少一个」：该行已完成使命，从待办里移除
        if (onlyDisabled && SiteVisibility.isEnabled(site)) {
            mAdapter.removeAt(position);
            refreshEmpty();
            focusAfterRemove(position);
        }
    }

    /**
     * 行被移除后接住焦点。
     *
     * <p>⚠ 这是 586 最容易出问题的地方：筛选态下点「确定」会把当前行删掉，
     * 若不管焦点，焦点会落在已移除的 view 上 ⇒ 遥控器「按了没反应」。
     * 所以移除后必须主动把焦点交给<b>同位置的下一项</b>（已是最后一项则交给上一项），
     * 列表空了就交回筛选行。</p>
     *
     * <p>必须在下一帧（{@code post}）做：{@code notifyItemRemoved} 之后布局还没跑完，
     * 此时 {@code findViewByPosition} 拿到的可能是即将被回收的旧 view。</p>
     */
    private void focusAfterRemove(int position) {
        int count = mAdapter.getItemCount();
        if (count == 0) {
            mBinding.filter.post(mBinding.filter::requestFocus);
            return;
        }
        int next = Math.min(position, count - 1);
        mBinding.recycler.post(() -> {
            View child = findChild(next);
            if (child != null) {
                child.requestFocus();
                return;
            }
            mBinding.recycler.scrollToPosition(next);
            mBinding.recycler.post(() -> {
                View again = findChild(next);
                if (again != null) again.requestFocus();
                else mBinding.filter.requestFocus();
            });
        });
    }

    private View findChild(int position) {
        RecyclerView.LayoutManager manager = mBinding.recycler.getLayoutManager();
        return manager == null ? null : manager.findViewByPosition(position);
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onPause() {
        super.onPause();
        // ⚠ 统一在离开页面时应用一次（不在每次点击里广播）：
        // 否则连点 10 个源 = 10 次 RefreshEvent.home() = 首页 10 次网络请求。
        if (!dirty) return;
        dirty = false;
        VodConfig.get().applyVisibility();
        // 锁定的源可能刚被关掉：这时要清锁，否则锁定页会出现
        // 「按钮显示解锁、文字显示未锁定」的自相矛盾状态。
        ElderLock.dropIfHidden();
        RefreshEvent.home();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (getCurrentFocus() == null) mBinding.toggleAll.post(mBinding.toggleAll::requestFocus);
    }
}
