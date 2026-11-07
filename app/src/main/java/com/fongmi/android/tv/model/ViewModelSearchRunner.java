package com.fongmi.android.tv.model;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.Task;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

final class ViewModelSearchRunner {

    /**
     * 跨源搜索专用线程池（R128）。
     *
     * <p>⛔ <b>不要改回 {@code Task#largeExecutor()}</b>：那是 20 线程的全局池，
     * {@link #start} 会对每个站源各提交一个请求且无并发上限 ⇒ 站源多时瞬间几十个并发，
     * 把正在播放的视频流带宽打光（现象：换源那一刻播放速率骤降到几十 KB/s）。
     * 搜索是「并发越少越省带宽」的典型场景，6 线程足够在 1~2 秒内搜完常见配置。</p>
     *
     * <p>静态共享而非每 ViewModel 一个：详情页 ViewModel 生命周期短，频繁建池开销大；
     * 同时最多一两个页面在搜索，不会互相饿死。</p>
     */
    private static final ListeningExecutorService SEARCH_POOL = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(6));

    private final List<Future<?>> futures;
    private final AtomicInteger epoch;

    ViewModelSearchRunner() {
        futures = new CopyOnWriteArrayList<>();
        epoch = new AtomicInteger(0);
    }

    void start(List<Site> sites, Function<Site, Callable<Result>> taskFactory, Consumer<Result> onResult) {
        int current = nextEpoch();
        cancelFutures();
        sites.forEach(site -> execute(taskFactory.apply(site), current, onResult));
    }

    void stop() {
        nextEpoch();
        cancelFutures();
    }

    private int nextEpoch() {
        return epoch.incrementAndGet();
    }

    private void cancelFutures() {
        futures.forEach(future -> future.cancel(true));
        futures.clear();
    }

    private void execute(Callable<Result> callable, int current, Consumer<Result> onResult) {
        FluentFuture<Result> future = FluentFuture.from(SEARCH_POOL.submit(callable)).withTimeout(Constant.TIMEOUT_SEARCH, TimeUnit.MILLISECONDS, Task.scheduler());
        futures.add(future);
        future.addCallback(Task.callback(
                result -> {
                    if (epoch.get() == current) onResult.accept(result);
                }
        ), MoreExecutors.directExecutor());
    }
}
