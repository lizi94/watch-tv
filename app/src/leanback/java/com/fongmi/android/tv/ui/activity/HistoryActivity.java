package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.ActivityHistoryBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.adapter.HistoryAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.dialog.ConfirmDialog;
import com.fongmi.android.tv.utils.Notify;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * 历史记录页：网格展示全部观看记录。
 *
 * <p>批量删除采用「两步式」防误触：先按「批量删除」进入选择模式，
 * 点选要删除的记录（可全选），再按「删除所选」并二次确认。
 * 返回键先退出选择模式，再退出页面。</p>
 */
public class HistoryActivity extends BaseActivity implements HistoryAdapter.OnClickListener {

    private ActivityHistoryBinding mBinding;
    private HistoryAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, HistoryActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        getHistory();
    }

    @Override
    protected void initEvent() {
        mBinding.batch.setOnClickListener(this::onBatch);
        mBinding.all.setOnClickListener(this::onSelectAll);
        mBinding.delete.setOnClickListener(this::onDeleteSelected);
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setAdapter(mAdapter = new HistoryAdapter(this));
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, Product.getColumn()));
        mBinding.recycler.addItemDecoration(new SpaceItemDecoration(Product.getColumn(), 16));
    }

    private void getHistory() {
        mAdapter.setItems(History.get(), () -> mBinding.progressLayout.showContent(true, mAdapter.getItemCount()));
    }

    /** 进入/退出批量删除模式。 */
    private void onBatch(View view) {
        boolean enter = !mAdapter.isSelectMode();
        mAdapter.setSelectMode(enter);
        refreshToolbar();
        if (enter) mBinding.all.requestFocus();
        else mBinding.batch.requestFocus();
    }

    private void onSelectAll(View view) {
        boolean selectAll = mAdapter.getSelectedCount() < mAdapter.getItemCount();
        if (selectAll) mAdapter.selectAll();
        else mAdapter.selectNone();
        refreshToolbar();
    }

    private void onDeleteSelected(View view) {
        int count = mAdapter.getSelectedCount();
        if (count == 0) {
            Notify.show(R.string.history_none_selected);
            return;
        }
        ConfirmDialog.create().message(getString(R.string.history_confirm_batch, count)).callback(this::deleteSelected).show(this);
    }

    private void deleteSelected() {
        mAdapter.removeSelected();
        refreshToolbar();
        mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
        // 删空后补落焦：recycler 被切成 INVISIBLE 时焦点会彻底丢失（遥控器像失灵）
        if (mAdapter.getItemCount() == 0) mBinding.batch.requestFocus();
        // 批量删除后广播刷新：首页「最近观看」行需要同步移除已删记录
        RefreshEvent.history();
    }

    /**
     * 外部变化同步：播放进度推进 / 别的页面删记录后，本页要跟着刷新
     * （原来只发不订阅 ⇒ 从详情页回来顺序与内容都是旧的）。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.HISTORY) getHistory();
    }

    /** 切换按钮显隐与文案：普通态只有「批量删除」；选择态显示「全选/取消全选」与「删除所选(n)」。 */
    private void refreshToolbar() {
        boolean selectMode = mAdapter.isSelectMode();
        int count = mAdapter.getSelectedCount();
        mBinding.batch.setText(selectMode ? R.string.history_batch_on : R.string.history_batch);
        mBinding.all.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        mBinding.all.setText(selectAllText());
        mBinding.delete.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        mBinding.delete.setText(getString(R.string.history_delete_selected_n, count));
        mBinding.hint.setVisibility(selectMode ? View.VISIBLE : View.GONE);
    }

    private int selectAllText() {
        return mAdapter.getSelectedCount() < mAdapter.getItemCount() ? R.string.history_select_all : R.string.history_select_none;
    }

    @Override
    public void onItemClick(History item) {
        if (mAdapter.isSelectMode()) {
            mAdapter.toggle(item);
            refreshToolbar();
            return;
        }
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic(), null, false, false, true);
    }

    @Override
    protected void onBackInvoked() {
        if (mAdapter.isSelectMode()) {
            mAdapter.setSelectMode(false);
            refreshToolbar();
        } else {
            super.onBackInvoked();
        }
    }
}
