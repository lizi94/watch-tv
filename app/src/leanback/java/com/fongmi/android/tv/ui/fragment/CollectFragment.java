package com.fongmi.android.tv.ui.fragment;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentTypeBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Notify;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.List;

public class CollectFragment extends BaseFragment implements CustomScroller.Callback, VodPresenter.OnClickListener {

    private FragmentTypeBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private ArrayObjectAdapter mLast;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    /** 搜索结果行 Presenter：页面级复用，避免每翻一页多堆一个（见 {@link #vodPresenter}）。 */
    private VodPresenter mVodPresenter;
    private Collect mCollect;
    private String mKeyword;

    public static CollectFragment newInstance(String keyword, Collect collect) {
        Bundle args = new Bundle();
        args.putString("keyword", keyword);
        CollectFragment fragment = new CollectFragment().setCollect(collect);
        fragment.setArguments(args);
        return fragment;
    }

    private String getKeyword() {
        return mKeyword = mKeyword == null ? getArguments().getString("keyword") : mKeyword;
    }

    private CollectFragment setCollect(Collect collect) {
        this.mCollect = collect;
        return this;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        // 搜索结果页沿用同一份布局；刷新按钮已从布局移除，无需再隐藏
        setRecyclerView();
        setViewModel();
        addVideo(mCollect);
    }

    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        // 搜索结果页封面：关闭焦点放大高亮，理由同首页（3:4 竖版上下外扩会压到相邻行）。
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_NONE), VodPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.addOnScrollListener(mScroller = new CustomScroller(this));
        // 2026-10-12：原先这里是 setHeader(getActivity(), R.id.result, R.id.recycler)，用来在
        // 「向下进入第 2 行」时收起 Activity 的结果标题 + 站源行。但 GONE 会让 pager 与其上方
        // 两个兄弟节点的总高变化 → 海报网格整体上下位移，切换站源时表现为「站源行与封面第一行的
        // 上下间距跳变」。现改为「恒显」：两个视图始终显示，网格位置恒定（见 CustomVerticalGridView）。
        // 行间距与首页同源（Activity 侧 density）：原先用 ResUtil.dp2px(16)（Application 侧），
        // 搜索结果行比首页行更挤，同一份封面在两页的纵向节奏不一致。
        mBinding.recycler.setVerticalSpacing(Product.gap(requireContext()));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, result -> {
            mScroller.endLoading(result);
            addVideo(result.getList());
        });
    }

    private boolean checkLastSize(List<Vod> items) {
        if (mLast == null || items.isEmpty()) return false;
        // size <= 0 一律早退：0 = 上一页刚好排满；负值 = 列数档位变小后 mLast 比新列数还长
        // （subList(0, 负值) 会抛 IndexOutOfBoundsException）。负值时直接走分区重建，丢弃旧 mLast。
        int size = Product.getColumn() - mLast.size();
        if (size <= 0) return false;
        size = Math.min(size, items.size());
        mLast.addAll(mLast.size(), items.subList(0, size));
        addVideo(items.subList(size, items.size()));
        return true;
    }

    private void addVideo(Collect collect) {
        if (collect != null) addVideo(collect.getList());
    }

    public void addVideo(List<Vod> items) {
        // 搜索只列可播放影片：文件夹条目（Vod.isFolder）在电视端没有对应页面（分类页已随本轮改动移除），
        // 直接过滤掉，避免出现「点了没反应」的死条目。过滤放在分区之前，checkLastSize 的拼接语义不变。
        List<Vod> playable = new ArrayList<>();
        for (Vod item : items) if (!item.isFolder()) playable.add(item);
        if (checkLastSize(playable) || getActivity() == null || getActivity().isFinishing()) return;
        List<ListRow> rows = new ArrayList<>();
        // ⚠ presenter 页面级复用（2026-10-27）：原来每翻一页就 new 一个，翻得越多堆得越多。
        VodPresenter presenter = vodPresenter();
        for (List<Vod> part : Lists.partition(playable, Product.getColumn())) {
            mLast = new ArrayObjectAdapter(presenter);
            mLast.addAll(0, part);
            rows.add(new ListRow(mLast));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    /** 搜索结果行 Presenter（懒建一次）。封面尺寸在创建 holder 时按当前档位重算，跨页复用安全。 */
    private VodPresenter vodPresenter() {
        if (mVodPresenter == null) mVodPresenter = new VodPresenter(this);
        return mVodPresenter;
    }

    /** 累计条目已达分页上限：只提示一次。 */
    @Override
    public void onLimitReached() {
        Notify.show(R.string.list_no_more);
    }

    @Override
    public void onItemClick(Vod item) {
        requireActivity().setResult(Activity.RESULT_OK);
        VideoActivity.start(requireActivity(), item.getSiteKey(), item.getId(), item.getName(), item.getPic());
    }

    @Override
    public boolean onLongClick(Vod item) {
        return false;
    }

    @Override
    public boolean onLoadMore(String page) {
        if (mCollect == null || "all".equals(mCollect.getSite().getKey())) return false;
        mViewModel.searchContent(mCollect.getSite(), getKeyword(), false, page);
        return true;
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (mBinding != null && !isVisibleToUser) mBinding.recycler.moveToTop();
    }
}
