package com.fongmi.android.tv.player.extractor;

import android.net.Uri;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Core;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Path;
import com.google.gson.JsonObject;
import com.orhanobut.logger.Logger;
import com.tvbus.engine.Listener;
import com.tvbus.engine.TVCore;

import java.io.File;
import java.util.concurrent.CountDownLatch;

public class TVBus implements Source.Extractor, Listener {

    private static final String TAG = TVBus.class.getSimpleName();
    private CountDownLatch latch;
    private TVCore tvcore;
    private String hls;
    private Core core;
    /**
     * 引擎代号（R125）：每「作废一个引擎」或「建一个引擎」就 +1。
     *
     * <p>用途只有一个：让<b>旧引擎迟到的回调</b>被丢弃（见 {@link EngineListener}）。</p>
     */
    private int gen;

    @Override
    public boolean match(Uri uri) {
        return "tvbus".equals(UrlUtil.scheme(uri));
    }

    private void init(Core core) {
        try {
            final int id = ++gen;
            tvcore = new TVCore(getPath(core.getSo())).listener(new EngineListener(id)).auth(core.getAuth()).name(core.getName()).pass(core.getPass()).domain(core.getDomain()).broker(core.getBroker());
            for (Core.Option option : core.getOption()) tvcore.option(option.getKey(), option.getValues());
            tvcore.serv(0).play(8902).mode(1).init();
        } catch (Throwable ignored) {
            // 这里必须抓 Throwable：TVCore 构造里是 System.load(path)（见 tvbus/engine/TVCore.java），
            // 引擎二进制下不到 / 加载不了时抛的是 UnsatisfiedLinkError —— 那是 Error 而非 Exception，
            // 只抓 Exception 会直接穿到上层变成闪退。getPath() 里同步下载失败抛的 RuntimeException
            // 也在同一处兜住。失败后把 tvcore 明确置空，交给 fetch() 给出「播放失败」提示，
            // 而不是留一个半死的实例在后面空指针。
            tvcore = null;
        }
    }

    private String getPath(String url) {
        File so = new File(Path.so(), UrlUtil.path(url));
        if (!Path.exists(so)) Download.create(url, so).get();
        return so.getAbsolutePath();
    }

    @Override
    public String fetch(String url) throws Exception {
        Core c = LiveConfig.get().getHome().getCore();
        if (core != null && !core.equals(c)) change();
        if (tvcore == null) init(core = c);
        // 引擎初始化失败（二进制文件不存在 / 无法加载）时给出可读的失败提示，不要崩在空指针上。
        if (tvcore == null) throw new ExtractException(ResUtil.getString(R.string.error_play_url));
        latch = new CountDownLatch(1);
        tvcore.start(url);
        latch.await();
        return check();
    }

    /**
     * 换内核：停掉旧引擎、清空引用，让 {@link #fetch} 用新 core 重建引擎并<b>继续播放</b>。
     *
     * <p><b>R125 改法（这一段原来是上游原样搬过来的）</b>：上游写的是
     * 「{@code LiveSetting.putBoot(true)} + {@code App.post(() -> System.exit(0), 100)}」——
     * 置一个持久化的一次性标记，再延迟 100ms <b>硬杀进程</b>，等用户重开 App 时靠那个标记
     * 跳回直播页。两个问题：</p>
     * <ul>
     *   <li>用户感知是「看着看着突然闪退」，还得自己重开、重进直播、重选频道，播放进度也丢了；</li>
     *   <li>标记写在 {@code Prefers} 里 ⇒ <b>跨天残留</b>：某天换了一次内核，第二天打开 App
     *       还会莫名其妙被弹进直播页（P1）。</li>
     * </ul>
     *
     * <p>现在改成<b>原地重建</b>：停旧引擎 → 清引用 → {@link #fetch} 里用新 core 重新
     * {@link #init} → 继续播放。语义与「重启后用新内核播放」一致，但用户无感、不丢进度、
     * 不产生任何跨天残留；「换内核后还能不能回到直播页」也天然闭环了（人本来就在直播页上）。</p>
     */
    private void change() {
        // 先作废旧引擎的回调资格，再停它 —— 否则它迟到的 onPrepared / onStop 会打到新引擎的等待上。
        gen++;
        if (tvcore != null) tvcore.stop();
        tvcore = null;
        core = null;
        latch = null;
        hls = null;
    }

    private String check() throws Exception {
        if (hls == null) return "";
        if (!hls.startsWith("-")) return hls;
        throw new ExtractException(ResUtil.getString(R.string.error_play_tvbus, hls));
    }

    @Override
    public void stop() {
        if (tvcore != null) tvcore.stop();
        hls = null;
    }

    @Override
    public void exit() {
        if (tvcore != null) tvcore.stop();
        hls = null;
    }

    /**
     * 引擎监听器：把「这是第几代引擎」带进回调。
     *
     * <p><b>为什么必须有它</b>：{@link #latch} 是实例字段，换内核之后它已经指向<b>新</b>引擎的等待；
     * 而旧引擎在 {@code stop()} 之后仍可能<b>迟到</b>一个 {@code onPrepared} / {@code onStop}
     * （原生侧的消息队列里已经排着的那一条）。旧回调会把新引擎的等待提前唤醒，
     * 于是「刚换完内核就报播放失败」—— 正好是这次要修的那类症状。</p>
     */
    private class EngineListener implements Listener {

        private final int id;

        EngineListener(int id) {
            this.id = id;
        }

        private boolean stale() {
            return id != gen;
        }

        @Override
        public void onPrepared(String result) {
            if (stale()) return;
            TVBus.this.onPrepared(result);
        }

        @Override
        public void onStop(String result) {
            if (stale()) return;
            TVBus.this.onStop(result);
        }

        @Override
        public void onInited(String result) {
            if (stale()) return;
            TVBus.this.onInited(result);
        }

        @Override
        public void onStart(String result) {
            if (stale()) return;
            TVBus.this.onStart(result);
        }

        @Override
        public void onInfo(String result) {
            if (stale()) return;
            TVBus.this.onInfo(result);
        }

        @Override
        public void onQuit(String result) {
            if (stale()) return;
            TVBus.this.onQuit(result);
        }
    }

    @Override
    public void onPrepared(String result) {
        Logger.t(TAG).d(result);
        JsonObject json = App.gson().fromJson(result, JsonObject.class);
        if (json.get("hls") == null) return;
        hls = json.get("hls").getAsString();
        if (latch != null) latch.countDown();
    }

    @Override
    public void onStop(String result) {
        Logger.t(TAG).d(result);
        JsonObject json = App.gson().fromJson(result, JsonObject.class);
        hls = json.get("errno").getAsString();
        if (latch != null && hls.startsWith("-")) latch.countDown();
    }

    @Override
    public void onInited(String result) {
        Logger.t(TAG).d(result);
    }

    @Override
    public void onStart(String result) {
        Logger.t(TAG).d(result);
    }

    @Override
    public void onInfo(String result) {
    }

    @Override
    public void onQuit(String result) {
    }
}
