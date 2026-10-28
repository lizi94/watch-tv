package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.databinding.ActivityKeepBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.adapter.KeepAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.dialog.ConfirmDialog;
import com.fongmi.android.tv.utils.Notify;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * 收藏页：网格展示全部收藏，支持批量删除。
 *
 * <p>与历史记录页同一「两步式」防误触交互：先按「批量删除」进入选择模式，
 * 点选要删除的收藏（可全选），再按「删除所选」并二次确认。
 * 返回键先退出选择模式，再退出页面。</p>
 */
public class KeepActivity extends BaseActivity implements KeepAdapter.OnClickListener {

    private ActivityKeepBinding mBinding;
    private KeepAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, KeepActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityKeepBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        getKeep();
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
        mBinding.recycler.setAdapter(mAdapter = new KeepAdapter(this));
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, Product.getColumn()));
        // 间距必须与卡片宽度同域（Activity 侧）：Product.getSpec 按 16dp×列数预留了间距，
        // 这里若用 dp 重载（Application 侧 density）实占会小于预留，差额堆到行尾 → 右边空一条。
        mBinding.recycler.addItemDecoration(SpaceItemDecoration.ofPx(Product.getColumn(), Product.gap(this)));
    }

    private void getKeep() {
        mAdapter.setItems(Keep.getVod(), () -> mBinding.progressLayout.showContent(true, mAdapter.getItemCount()));
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
            Notify.show(R.string.keep_none_selected);
            return;
        }
        ConfirmDialog.create().message(getString(R.string.keep_confirm_batch, count)).callback(this::deleteSelected).show(this);
    }

    private void deleteSelected() {
        mAdapter.removeSelected();
        refreshToolbar();
        mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
        // 删空后没有任何可聚焦项（recycler 被切成 INVISIBLE）⇒ 焦点必须交回工具栏，
        // 否则老人按遥控器像「失灵」，得先按方向键才有反应。
        if (mAdapter.getItemCount() == 0) mBinding.batch.requestFocus();
        // 收藏可能在首页「更新推荐」、详情页收藏按钮等处展示，删除后广播刷新
        RefreshEvent.keep();
    }

    /**
     * 外部变化同步：在详情页取消收藏后返回，本页必须跟着移除那一条（原来只在删除后广播，自己不订阅 ⇒
     * 返回时列表还是旧的，点进去竟然还能播）。手机端 KeepActivity 一直有这段订阅。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.KEEP) getKeep();
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

    private void loadConfig(Config config, Keep item) {
        VodConfig.load(config, new Callback() {
            @Override
            public void success() {
                VideoActivity.start(getActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        });
    }

    @Override
    public void onItemClick(Keep item) {
        if (mAdapter.isSelectMode()) {
            mAdapter.toggle(item);
            refreshToolbar();
            return;
        }
        Config config = Config.find(item.getCid());
        if (config == null) CollectActivity.start(this, item.getVodName());
        else if (item.getCid() != VodConfig.getCid()) loadConfig(config, item);
        else VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
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
