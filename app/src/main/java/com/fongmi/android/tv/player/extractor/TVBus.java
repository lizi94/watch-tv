package com.fongmi.android.tv.player.extractor;

import android.net.Uri;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Core;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.setting.LiveSetting;
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

    @Override
    public boolean match(Uri uri) {
        return "tvbus".equals(UrlUtil.scheme(uri));
    }

    private void init(Core core) {
        try {
            tvcore = new TVCore(getPath(core.getSo())).listener(this).auth(core.getAuth()).name(core.getName()).pass(core.getPass()).domain(core.getDomain()).broker(core.getBroker());
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

    private void change() throws Exception {
        LiveSetting.putBoot(true);
        // 上游原实现在这里是 App.post(() -> System.exit(0), 100)：换了直播源后 core 变了，需要用
        // 新 core 重新初始化引擎，而它选的是「延迟 100ms 硬杀进程」这种最粗暴的方式 —— 不分界面、
        // 不分前后台，用户感知就是「看着看着突然闪退」，还会丢掉当前播放进度。
        // 改成原地重建引擎：停掉旧引擎、清空引用（core 置空后下一次 fetch() 会用新 core 重新 init），
        // 语义和重启一致，但不再退出应用。
        if (tvcore != null) tvcore.stop();
        tvcore = null;
        core = null;
        throw new ExtractException(ResUtil.getString(R.string.error_play_url));
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

    @Override
    public void onPrepared(String result) {
        Logger.t(TAG).d(result);
        JsonObject json = App.gson().fromJson(result, JsonObject.class);
        if (json.get("hls") == null) return;
        hls = json.get("hls").getAsString();
        latch.countDown();
    }

    @Override
    public void onStop(String result) {
        Logger.t(TAG).d(result);
        JsonObject json = App.gson().fromJson(result, JsonObject.class);
        hls = json.get("errno").getAsString();
        if (hls.startsWith("-")) latch.countDown();
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
