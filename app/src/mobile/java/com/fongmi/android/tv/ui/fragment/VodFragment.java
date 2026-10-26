package com.fongmi.android.tv.ui.fragment;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.databinding.FragmentVodBinding;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.FilterListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.HistoryActivity;
import com.fongmi.android.tv.ui.activity.KeepActivity;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.FilterDialog;
import com.fongmi.android.tv.ui.dialog.ReceiveDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.NetAwait;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class VodFragment extends BaseFragment implements ConfigListener, SiteListener, FilterListener, TypeAdapter.OnClickListener {

    private FragmentVodBinding mBinding;
    private SiteViewModel mViewModel;
    private TypeAdapter mAdapter;
    private Result mResult;
    /** 网络恢复等待句柄（只在空态判定为故障时挂上，页面销毁即撤销）。 */
    private NetAwait.Handle mNetworkAwait = NetAwait.Handle.NONE;
    /** 本页面实例是否已经挂过一次「等网络恢复」——一次性，防止极端机型上注册即回调导致自转。 */
    private boolean networkAwaited;

    public static VodFragment newInstance() {
        return new VodFragment();
    }

    private FolderFragment getFragment() {
        return (FolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, mBinding.pager.getCurrentItem());
    }

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentVodBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        mBinding.title.setSelected(true);
        setRecyclerView();
        setViewModel();
        showProgress();
        setTitle();
        setLogo();
    }

    @Override
    protected void initEvent() {
        mBinding.emptyRetry.setOnClickListener(v -> onEmptyRetry());
        mBinding.top.setOnClickListener(this::onTop);
        mBinding.filter.setOnClickListener(this::onFilter);
        mBinding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        mBinding.appBar.addOnOffsetChangedListener((appBarLayout, verticalOffset) -> {
            float factor = Math.abs(verticalOffset * 1f / appBarLayout.getTotalScrollRange());
            int padding = (int) (ResUtil.dp2px(12) * factor);
            if (mBinding.type.getPaddingTop() == padding) return;
            mBinding.type.setPadding(mBinding.type.getPaddingStart(), padding, mBinding.type.getPaddingEnd(), mBinding.type.getPaddingBottom());
        });
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mBinding.type.smoothScrollToPosition(position);
                mAdapter.setSelected(position);
                setFabVisible(position);
            }
        });
    }

    private void setRecyclerView() {
        mBinding.type.setHasFixedSize(true);
        mBinding.type.setItemAnimator(null);
        mBinding.type.setAdapter(mAdapter = new TypeAdapter(this));
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
    }

    private void setAdapter(Result result) {
        mAdapter.addAll(mResult = result);
        mBinding.pager.getAdapter().notifyDataSetChanged();
        setFabVisible(0);
        hideProgress();
        showContent();
        syncEmpty();
    }

    private void setFabVisible(int position) {
        if (mAdapter.getItemCount() == 0) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.filter.setVisibility(View.GONE);
        } else if (!mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.filter.show();
        } else {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.filter.setVisibility(View.GONE);
        }
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void onTop(View view) {
        getFragment().scrollToTop();
        mBinding.top.setVisibility(View.INVISIBLE);
        if (mBinding.filter.getVisibility() == View.INVISIBLE) mBinding.filter.show();
    }

    private void onFilter(View view) {
        if (mAdapter.getItemCount() > 0) FilterDialog.create().filter(mAdapter.get(mBinding.pager.getCurrentItem()).getFilters()).show(this);
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.keep) KeepActivity.start(requireActivity());
        else if (item.getItemId() == R.id.search) SearchActivity.start(requireActivity());
        else if (item.getItemId() == R.id.history) HistoryActivity.start(requireActivity());
        return true;
    }

    private void showProgress() {
        // 转圈与空态互斥：重试 / 换源时先把空态收起来，避免「转圈 + 一句暂时连接不上」同时出现。
        hideEmpty();
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
    }

    private void hideContent() {
        mBinding.type.setVisibility(View.INVISIBLE);
        mBinding.pager.setVisibility(View.INVISIBLE);
    }

    private void showContent() {
        mBinding.type.setVisibility(View.VISIBLE);
        mBinding.pager.setVisibility(View.VISIBLE);
    }

    private void homeContent() {
        showProgress();
        setFabVisible(0);
        mAdapter.clear();
        mViewModel.homeContent();
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
    }

    // ==================== 首页空态（2026-10-13） ====================

    /**
     * 首页「没有分类」时的空态判定。
     *
     * <p><b>在此之前的表现</b>：配置加载失败时 {@code HomeActivity} 会派发 {@code StateEvent.empty()}，
     * 手机版 {@link #onStateEvent} 只做 {@code hideProgress()} —— 于是转圈结束、页面彻底空白，
     * 老人看到的是一个「什么都没发生」的白屏，既不知道原因也没有可操作的东西。</p>
     *
     * <p><b>判据见 {@link #isBroken()}</b>：故障（数据源没就绪 / 当前没网）→「暂时连接不上」+ 显示
     * 「重新加载」，并在确实没网时等网络恢复自动重试；真·空（有源有网、源侧就是没内容）→ 保持
     * 默认文案、不给按钮。文案与电视版（{@code home_empty_source} / {@code home_empty_retry}）逐字一致。</p>
     */
    private void syncEmpty() {
        if (mAdapter.getItemCount() > 0) {
            hideEmpty();
            return;
        }
        boolean canRetry = isBroken();
        mBinding.emptyText.setText(canRetry ? R.string.empty_source : R.string.error_empty);
        mBinding.emptyRetry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
        mBinding.empty.setVisibility(View.VISIBLE);
        // 自动重试每个页面实例只挂一次（networkAwaited 一次翻转、不在重试后复位）：
        // 极端情况下（系统从不上报 NET_CAPABILITY_VALIDATED 的少量老机型 → isBroken() 恒为真、
        // watchNetwork() 的 isOnline() 守卫也恒不成立）注册回调会被同步补发 onAvailable，
        // 于是「挂 → 立刻回调 → 重试 → 仍空 → 再挂」会自转成忙循环。一次性的等待彻底堵死这条路：
        // 网络恢复替他试一次就够了，再不行也还有手动按钮。
        if (canRetry && !networkAwaited) {
            networkAwaited = true;
            watchNetwork();
        }
    }

    /**
     * 「拿不到分类」是否属于故障（而不是「源侧确实没内容」）。
     *
     * <p>两个维度，任一成立都算故障、都值得给一次「重新加载」：</p>
     * <ul>
     *   <li><b>数据源没就绪</b>（{@link VodConfig#get()} 的站点列表为空）—— 配置压根没加载成功。
     *       这个判据取自 {@code VodConfig#isLoaded()} 的定义，<b>与有没有网无关</b>：本地导入的源、
     *       缓存里已有的源，在完全没网时同样算就绪；</li>
     *   <li><b>当前没网</b> —— 有源也一样拿不到内容，而这一类比上一类更会「自己好起来」，
     *       也正是「网络恢复自动重试」要覆盖的场景（老人实测的「有数据源 + 完全没网」就落在这里）。</li>
     * </ul>
     *
     * <p>反之（有源 + 有网 + 仍然是空）才是「源侧确实没内容」：只给一句文案、不给按钮 ——
     * 给了也是按了没反应，反而让老人反复按。</p>
     */
    private boolean isBroken() {
        return VodConfig.get().getSites().isEmpty() || !NetAwait.isOnline();
    }

    /** 收起空态（有内容 / 进入转圈时调用）。 */
    private void hideEmpty() {
        mBinding.empty.setVisibility(View.GONE);
    }

    /**
     * 空态「重新加载」：按数据源是否就绪分流。
     *
     * <p>数据源没就绪说明配置根本没加载成功，此时重发首页内容毫无意义（没有站点可取），
     * 必须重走一遍配置加载；数据源就绪、只是首页接口没回来，重发一次即可。</p>
     *
     * <p>{@code getView()} 判空是因为本方法也会被「网络恢复」回调唤起 —— 那时页面可能已经销毁。</p>
     */
    private void onEmptyRetry() {
        cancelNetworkWait();
        if (!isAdded() || getView() == null) return;
        hideEmpty();
        if (VodConfig.get().getSites().isEmpty()) reloadConfig();
        else homeContent();
    }

    /**
     * 重走一遍配置加载。
     *
     * <p>成功后 {@code VodConfig#postEvent()} 的 {@code finally} 会派发 {@code ConfigEvent.vod()}，
     * 由 {@link #onConfigEvent} → {@code RefreshEvent.home()} 驱动首页重新取内容 ——
     * 所以这里不需要（也不应该）自己再调一次 {@link #homeContent()}，否则会发两遍请求。</p>
     */
    private void reloadConfig() {
        VodConfig.get().init().load(new Callback() {
            @Override
            public void start() {
                showProgress();
                hideContent();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                showContent();
                // 仍然失败 → 回到空态，形成「按一次试一次」的闭环；有网仍失败时不会自动重试。
                syncEmpty();
            }
        });
    }

    /**
     * 网络恢复后替用户按一次「重新加载」。
     *
     * <p>手机冷启动时网络通常已就绪，但「刚开机 WiFi 还在关联」「刚关掉飞行模式」这些情况同样存在，
     * 不该让老人对着「暂时连接不上」干等。{@code timeoutMs = 0} 表示不设超时：回调本身没有副作用，
     * 只是等网络通的那一刻重试一次；页面销毁（{@link #onDestroyView}）即撤销。</p>
     *
     * <p>已经联网时不挂 —— {@code NetAwait} 在联网状态下会立即执行回调，那会变成忙循环。</p>
     */
    private void watchNetwork() {
        cancelNetworkWait();
        if (NetAwait.isOnline()) return;
        mNetworkAwait = NetAwait.whenReady(0L, this::onEmptyRetry, () -> {
        });
    }

    private void cancelNetworkWait() {
        mNetworkAwait.cancel();
        mNetworkAwait = NetAwait.Handle.NONE;
    }

    public Result getResult() {
        return mResult == null ? new Result() : mResult;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.VOD) setLogo();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                setTitle();
            case SIZE:
                homeContent();
                break;
            case CATEGORY:
                getFragment().onRefresh();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStateEvent(StateEvent event) {
        switch (event.type()) {
            case EMPTY:
                hideProgress();
                break;
            case PROGRESS:
                showProgress();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        ReceiveDialog.create().event(event).show(this);
    }

    @Override
    public void setConfig(Config config) {
        VodConfig.load(config, new Callback() {
            @Override
            public void start() {
                showProgress();
                hideContent();
                setTitle();
                setLogo();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
                showContent();
            }
        });
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void onItemClick(int position, Class item) {
        mBinding.pager.setCurrentItem(position);
        mAdapter.setSelected(position);
    }

    @Override
    public void setFilter(String key, Value value) {
        getFragment().setFilter(key, value);
    }

    @Override
    public boolean canBack() {
        if (mBinding.pager.getAdapter() == null || mBinding.pager.getAdapter().getCount() == 0) return true;
        if (!getFragment().canBack()) return true;
        getFragment().goBack();
        return false;
    }

    @Override
    public void onDestroyView() {
        // 页面销毁必须撤销网络等待：否则回调会在视图已销毁后跑起来（getView() 判空只是兜底，
        // 真正干净的做法是根本不让它触发）。
        cancelNetworkWait();
        super.onDestroyView();
        EventBus.getDefault().unregister(this);
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            Class type = mAdapter.get(position);
            return FolderFragment.newInstance(getHome().getKey(), type, 4);
        }

        @Override
        public int getCount() {
            return mAdapter.getItemCount();
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        }
    }
}
