package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.exception.ExtractException;
import com.github.catvod.utils.Trans;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;

public class SiteViewModel extends ViewModel {

    private final MutableLiveData<Result> result;
    private final MutableLiveData<Result> search;
    private final MutableLiveData<Result> action;

    private final ViewModelTaskRunner<TaskType> tasks;
    private final ViewModelSearchRunner searches;

    public SiteViewModel() {
        result = new MutableLiveData<>();
        search = new MutableLiveData<>();
        action = new MutableLiveData<>();
        tasks = new ViewModelTaskRunner<>(TaskType.class);
        searches = new ViewModelSearchRunner();
    }

    public LiveData<Result> getResult() {
        return result;
    }

    public LiveData<Result> getSearch() {
        return search;
    }

    public LiveData<Result> getAction() {
        return action;
    }

    public SiteViewModel init() {
        search.setValue(null);
        result.setValue(null);
        action.setValue(null);
        return this;
    }

    /**
     * 清掉「上一次失败留下的空壳结果」。
     *
     * <p>2026-11-05（R110）：{@link #execute} 在请求失败 / 超时时会 {@code postValue(Result.empty())}，
     * 于是空结果会**沉淀**在 LiveData 里。同进程重建（切主题色 / 改图片尺寸）时 ViewModel 被保留，
     * 新页面的 observe 一注册就会立即重放这个空壳，把「暂时没取到」画成「这里没有内容」——
     * 首页推荐的空态卡就是这么来的（分类页用的是 Fragment 作用域的另一个实例，所以不空）。</p>
     *
     * <p>必须在 observe **注册之前**调用：那时还没有观察者，{@code setValue(null)} 不会触发任何回调。
     * 之后由调用方按「没有数据」走正常请求流程（转圈 → 内容），而不是先闪一下空态。</p>
     */
    public void clearResultIfEmpty() {
        Result value = result.getValue();
        if (value != null && value.getList().isEmpty()) result.setValue(null);
    }

    public void homeContent() {
        execute(TaskType.RESULT, result, () -> SiteApi.homeContent(VodConfig.get().getHome()));
    }

    public void categoryContent(String key, String tid, String page, boolean filter, HashMap<String, String> extend) {
        execute(TaskType.RESULT, result, () -> SiteApi.categoryContent(key, tid, page, filter, extend));
    }

    public void action(String key, String act) {
        execute(TaskType.ACTION, action, () -> SiteApi.action(key, act));
    }

    public void detailContent(String key, String id) {
        execute(TaskType.RESULT, result, () -> SiteApi.detailContent(key, id));
    }

    public void searchContent(Site site, String keyword, boolean quick, String page) {
        execute(TaskType.RESULT, result, SearchTask.create(site, keyword, quick, page));
    }

    public void searchContent(List<Site> sites, String keyword, boolean quick) {
        searches.start(sites, site -> SearchTask.create(site, keyword, quick), search::postValue);
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable) {
        tasks.execute(type, Constant.TIMEOUT_VOD, callable, liveData::postValue, error -> {
            // ⛔ 2026-11-05（R111）：失败必须**带原因**，不能 postValue(Result.empty())。
            //   ① empty 与「源侧确实没内容」在 UI 上无法区分 ⇒ 首页会把「没取到」显示成
            //      「这里什么都没有」，把老人劝退（实际再点一次就好了）。
            //   ② 这个空壳会**沉淀**在 LiveData 里，同进程重建（切主题色 / 改字号）时
            //      被新页面的 observe 立即重放 ⇒ 重建后直接看到空态。
            //   改成 Result.error(msg)：列表仍为空，但 getMsg() 非空，UI 据此走「暂时连接不上 + 重试」。
            String msg = error == null ? "" : (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            liveData.postValue(Result.error(msg));
            if (error != null) error.printStackTrace();
        });
    }

    public void stopSearch() {
        searches.stop();
    }

    @Override
    protected void onCleared() {
        stopSearch();
        tasks.cancelAll();
    }

    private record SearchTask(Site site, String keyword, boolean quick, String page) implements Callable<Result> {

        private static final String FIRST_PAGE = "1";

        SearchTask {
            keyword = Trans.t2s(keyword);
        }

        private static SearchTask create(Site site, String keyword, boolean quick) {
            return create(site, keyword, quick, FIRST_PAGE);
        }

        private static SearchTask create(Site site, String keyword, boolean quick, String page) {
            return new SearchTask(site, keyword, quick, page);
        }

        @Override
        public Result call() throws Exception {
            if (quick && !site.isQuickSearch()) return Result.empty();
            return SiteApi.searchContent(site, keyword, quick, page);
        }
    }

    private enum TaskType {RESULT, ACTION}
}
