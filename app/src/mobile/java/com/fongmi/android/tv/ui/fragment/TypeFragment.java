package com.fongmi.android.tv.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentTypeBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.adapter.VodAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.utils.NetAwait;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.HashMap;

public class TypeFragment extends BaseFragment implements CustomScroller.Callback, VodAdapter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

    private HashMap<String, String> mExtends;
    private FragmentTypeBinding mBinding;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private VodAdapter mAdapter;

    // R132：首页内容级自动重试（与电视端 HomeActivity 同口径）：阶梯 5s/10s/20s 共 3 次。
    private static final long[] CONTENT_RETRY_STEPS = {5_000L, 10_000L, 20_000L};
    private int contentRetryCount;

    /**
     * R141 下拉刷新保留旧内容：true 时请求前不清列表，旧内容一直显示到新结果到达，
     * 由 DiffUtil 静默替换（内容未变零重绘）—— 消除「刷新先闪空再转圈」的视觉断裂。
     * 仅用户手动下拉置 true；切分类 / 筛选 / 首次进入仍是清空重建（语义变了必须重置）。
     */
    private boolean mRefreshKeep;

    public static TypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder, int y) {
        Bundle args = new Bundle();
        args.putInt("y", y);
        args.putString("key", key);
        args.putString("typeId", typeId);
        args.putBoolean("folder", folder);
        args.putParcelable("style", style);
        args.putSerializable("extend", extend);
        TypeFragment fragment = new TypeFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    private String getTypeId() {
        return getArguments().getString("typeId");
    }

    private Style getStyle() {
        return isFolder() ? Style.list() : getSite().getStyle(getArguments().getParcelable("style"));
    }

    private HashMap<String, String> getExtend() {
        return (HashMap<String, String>) getArguments().getSerializable("extend");
    }

    private int getY() {
        return getArguments().getInt("y");
    }

    private boolean isFolder() {
        return getArguments().getBoolean("folder");
    }

    private boolean isHome() {
        return "home".equals(getTypeId());
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private FolderFragment getParent() {
        return (FolderFragment) getParentFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.progressLayout.showProgress();
        mScroller = new CustomScroller(this);
        mExtends = getExtend();
        setRecyclerView();
        setViewModel();
        getVideo();
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.recycler.addOnScrollListener(mScroller);
    }

    private void setRecyclerView() {
        mBinding.recycler.setTranslationY(-ResUtil.dp2px(getY()));
        mBinding.recycler.setHasFixedSize(true);
        setStyle(getStyle());
    }

    private void setStyle(Style style) {
        mBinding.recycler.setAdapter(mAdapter = new VodAdapter(this, style, Product.getSpec(requireActivity(), style)));
        mBinding.recycler.setLayoutManager(style.isList() ? new LinearLayoutManager(requireActivity()) : new GridLayoutManager(getContext(), Product.getColumn(requireActivity(), style)));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        // ⛔ 2026-11-05（R111）：与 VodFragment 同一口径 —— 注册 observe 之前先清掉
        //   「上次请求失败留下的空壳」。否则同进程重建后 observe 会立即重放它，
        //   分类页也画出一张「这里什么都没有」。
        mViewModel.clearResultIfEmpty();
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
        mViewModel.getAction().observe(getViewLifecycleOwner(), result -> {
            // R138b：action LiveData 同样可能沉淀 null（重放时防御），先判空再取 msg。
            if (result == null) return;
            Notify.show(result.getMsg());
        });
    }

    private void getHome() {
        mAdapter.clear(() -> mViewModel.homeContent());
    }

    private void getVideo() {
        getVideo(false);
    }

    /**
     * R141 双路径：{@code keepContent=false} 为既有行为（先清列表再请求）；
     * {@code keepContent=true} 为下拉刷新专用 —— 不预清列表、不转圈，旧内容保留到新结果到达。
     */
    private void getVideo(boolean keepContent) {
        mRefreshKeep = keepContent;
        mScroller.reset();
        if (keepContent) {
            if (isHome()) mViewModel.homeContent();
            else getVideo(getTypeId(), "1");
            return;
        }
        mAdapter.clear(() -> {
            if (!mBinding.swipeLayout.isRefreshing()) mBinding.progressLayout.showProgress();
            if (isHome()) {
                // ⛔ 2026-11-06（R113）：warm 重建时自己拉一次 —— 父级 result 这一刻可能还是旧的，
                //   而父级刷新完也不会通知本页重渲染，导致「推荐」tab 停在旧内容。
                //   冷启动仍走老路径（取父级 result），避免在配置没就绪时发一个必然失败的请求。
                if (VodConfig.get().getRawSites().isEmpty()) setAdapter(getParent().getResult());
                else mViewModel.homeContent();
            } else {
                getVideo(getTypeId(), "1");
            }
        });
    }

    private void getVideo(String typeId, String page) {
        mViewModel.categoryContent(getKey(), typeId, page, true, mExtends);
    }

    private void setAdapter(Result result) {
        // R138b：切主题/重建时序下 observe 重放 null 时不崩——null 视为「还没拿到数据」，
        // 保持转圈原状（与 CollectFragment 同款判空风格），由后续请求的正常回调接管 UI。
        if (result == null) return;
        boolean keep = mRefreshKeep;
        mRefreshKeep = false;
        boolean first = mScroller.first();
        int size = result.getList().size();
        // R141 下拉刷新拿回空结果且旧内容还在：保持 CONTENT 原状，不闪空态（翻页状态照常收口）。
        if (keep && size == 0 && mAdapter.getItemCount() > 0) {
            mScroller.endLoading(result);
            mBinding.swipeLayout.setRefreshing(false);
            return;
        }
        mBinding.progressLayout.showContent(first, size);
        mBinding.swipeLayout.setRefreshing(false);
        mScroller.endLoading(result);
        if (size > 0) {
            contentRetryCount = 0;   // R132：拿到内容即清零，下次空态重新计满
            addVideo(result, keep);
            return;
        }
        // R132：首页内容级自动重试 —— 配置成功但站点内容请求失败（开机头几秒 DNS/站点服务器
        // 未就绪很常见）时，本页原本画完空态就结束，再无人重试。阶梯 5s/10s/20s 共 3 次，
        // 拿到内容即停并清零；全空停在空态（下拉刷新仍可手动）。真没内容的源会白试 3 次 ——
        // 有界、静默、可接受。回调挂在 view 上并带 isAdded 守卫，页面销毁后不会误发。
        if (first && contentRetryCount < CONTENT_RETRY_STEPS.length) {
            long delay = CONTENT_RETRY_STEPS[contentRetryCount++];
            mBinding.recycler.postDelayed(() -> {
                if (!isAdded() || getContext() == null) return;
                if (!NetAwait.isOnline()) return;
                if (isHome()) mViewModel.homeContent();
                else getVideo(getTypeId(), "1");
            }, delay);
        }
    }

    private void addVideo(Result result, boolean keep) {
        Style style = result.getVod().getStyle(getStyle());
        if (!style.equals(mAdapter.getStyle())) {
            // 版式变了（列表⇄网格）：setStyle 会换全新空 adapter，直接重建即可
            setStyle(style);
            mAdapter.addAll(result.getList(), this::checkMore);
            return;
        }
        // R141：keep=true 时旧内容仍在 adapter 里，用 setItems 走 DiffUtil 整体替换
        //（内容没变时零重绘）；否则 append 到已清空的 adapter 上。
        if (keep && mAdapter.getItemCount() > 0) mAdapter.setItems(result.getList(), this::checkMore);
        else mAdapter.addAll(result.getList(), this::checkMore);
    }

    private void checkMore() {
        mBinding.recycler.post(() -> {
            if (isHome()) return;
            mScroller.checkMore(mBinding.recycler);
        });
    }

    public void scrollToTop() {
        mBinding.recycler.smoothScrollToPosition(0);
    }

    public void setFilter(String key, Value value) {
        if (value.isSelected()) mExtends.put(key, value.getV());
        else mExtends.remove(key);
        // R141：筛选条件变了语义重置，走清空重建路径（不走下拉刷新的「保留旧内容」）
        if (isHome()) getHome();
        else getVideo();
    }

    @Override
    public void onRefresh() {
        // R141：手动下拉 → 保留旧内容请求，新结果到达后 DiffUtil 替换，不闪空不转圈
        getVideo(true);
    }

    @Override
    public boolean onLoadMore(String page) {
        if (isHome()) return false;
        getVideo(getTypeId(), page);
        return true;
    }

    /** 累计条目已达分页上限：只提示一次。 */
    @Override
    public void onLimitReached() {
        Notify.show(R.string.list_no_more);
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) {
            mViewModel.action(getKey(), item.getAction());
        } else if (item.isFolder()) {
            getParent().openFolder(item.getId(), mExtends);
        } else {
            // 全站源统一受「首页快速搜索」开关控制：开启时跳搜索页跨源搜同名（快搜）；
            // 关闭时直接进详情页，交给自动择源择线把可播放线路找出来。
            if (Setting.isIndexQuickSearch()) SearchActivity.start(requireActivity(), item.getName());
            else VideoActivity.start(requireActivity(), getKey(), item.getId(), item.getName(), item.getPic(), isFolder() ? item.getName() : null);
        }
    }

    @Override
    public boolean onLongClick(Vod item) {
        // 长按不承载功能，防老人误触
        return false;
    }
}
