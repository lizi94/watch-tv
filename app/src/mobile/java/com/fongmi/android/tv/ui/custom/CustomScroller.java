package com.fongmi.android.tv.ui.custom;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Result;

/**
 * 列表分页滚动器（手机版）：滑到底自动加载下一页。
 *
 * <p><b>2026-10-27 列表加载治理（R96，与电视版同一套规则）</b>：</p>
 * <ol>
 *   <li><b>滑动期间暂停封面解码</b>：{@link #onScrollStateChanged} 里用
 *       {@code Glide.pauseRequests()/resumeRequests()}。一路下滑时每张封面都在解码 + 提交，
 *       主线程被占满 —— 这是「越滑越卡」的主因；</li>
 *   <li><b>空页即到底</b>：原先遇到空页只 {@code page--} 却仍保持 enable，下一轮滑到底又会
 *       请求同一个页码，反复空转；</li>
 *   <li><b>累计条目上限</b>：{@link #DEFAULT_LIMIT}。站源不返回总页数（{@code pageCount == 0}）时
 *       本来可以无限翻，列表会无限累积。</li>
 * </ol>
 */
public class CustomScroller extends RecyclerView.OnScrollListener {

    /** 累计已加载条目上限：超过即不再翻页（0 = 不限制）。 */
    private static final int DEFAULT_LIMIT = 300;

    private final Callback callback;
    private boolean loading;
    private boolean enable;
    private int page;
    private int total;
    private boolean limitNotified;

    public CustomScroller(Callback callback) {
        this.callback = callback;
        this.enable = true;
        this.page = 1;
    }

    @Override
    public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
        if (view.getScrollState() == RecyclerView.SCROLL_STATE_IDLE || view.canScrollVertically(1) || dy <= 0) return;
        loadMore();
    }

    @Override
    public void onScrollStateChanged(@NonNull RecyclerView view, int newState) {
        // 滑动期间暂停本页封面解码，停稳后恢复（详见 pauseImages 的说明）
        pauseImages(view, newState != RecyclerView.SCROLL_STATE_IDLE);
    }

    /**
     * 滑动期间暂停本页所有 Glide 请求，停稳后恢复。
     *
     * <p>⛔ 用 {@code view.getContext()}（Activity 级 RequestManager）而不是全局
     * {@code Glide.with(App.get())}：只影响当前页面，不会把其它页面的图片一起冻住。
     * 暂停 ≠ 取消：已经在飞的请求会被挂起，停稳后照常回调，不会丢图。</p>
     */
    private void pauseImages(RecyclerView view, boolean pause) {
        Context context = view.getContext();
        try {
            if (pause) Glide.with(context).pauseRequests();
            else Glide.with(context).resumeRequests();
        } catch (Throwable e) {
            // 纯性能优化，绝不能因为 Glide 的运行时异常把列表搞崩
        }
    }

    public void checkMore(RecyclerView view) {
        if (view != null && view.canScrollVertically(1)) return;
        loadMore();
    }

    private void loadMore() {
        if (isDisable() || isLoading() || callback == null) return;
        if (isLimitReached()) {
            notifyLimitOnce();
            return;
        }
        if (callback.onLoadMore(String.valueOf(page + 1))) {
            page++;
            setLoading(true);
        }
    }

    /** 累计条目是否已达上限。 */
    public boolean isLimitReached() {
        return total >= DEFAULT_LIMIT;
    }

    /** 只提示一次：否则每滑到底都会被同一句 Toast 打扰。 */
    private void notifyLimitOnce() {
        if (limitNotified || callback == null) return;
        limitNotified = true;
        callback.onLimitReached();
    }

    public void reset() {
        loading = false;
        enable = true;
        page = 1;
        total = 0;
        limitNotified = false;
    }

    public boolean first() {
        return page == 1;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public boolean isLoading() {
        return loading;
    }

    private void setLoading(boolean loading) {
        this.loading = loading;
    }

    public boolean isDisable() {
        return !enable;
    }

    public void setEnable(int pageCount) {
        // pageCount <= 0 = 站源没给总页数：只能继续翻，到底由 endLoading 的「空页即到底」兜底。
        this.enable = pageCount <= 0 || page < pageCount;
    }

    public void endLoading(Result result) {
        total += result.getList().size();
        if (result.getList().isEmpty()) {
            // ⛔ 原实现只 page-- 却仍保持 enable：下一轮滑到底又会请求同一个页码，反复空转。
            page--;
            enable = false;
        } else {
            setEnable(result.getPageCount());
        }
        setLoading(false);
    }

    public void endLoading(boolean hasMore) {
        enable = hasMore;
        setLoading(false);
    }

    public interface Callback {

        boolean onLoadMore(String page);

        /** 累计条目已达上限（首次触发回调一次）：给页面一次提示「已经到底了」的机会。 */
        default void onLimitReached() {
        }
    }
}
