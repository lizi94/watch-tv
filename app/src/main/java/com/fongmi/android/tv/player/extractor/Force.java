package com.fongmi.android.tv.player.extractor;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.forcetech.Util;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;

import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;

public class Force implements Source.Extractor, ServiceConnection {

    private static final Pattern PATTERN = Pattern.compile("(?i)(p[2-9]p|mitv)");
    /** 绑定引擎服务的等待上限（毫秒）。超时就放弃本次解析，绝不无限等。 */
    private static final long BIND_TIMEOUT_MS = 3000L;

    private final HashSet<String> set = new HashSet<>();

    @Override
    public boolean match(Uri uri) {
        return PATTERN.matcher(UrlUtil.scheme(uri)).find();
    }

    /**
     * 绑定对应协议的引擎服务。
     *
     * @return 绑定是否成功（服务不存在 / 被系统拒绝时 bindService 直接返回 false，无需等待）
     */
    private boolean init(String scheme) {
        try {
            return App.get().bindService(Util.intent(App.get(), scheme), this, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 绑定并在上限内等引擎连上。
     *
     * <p>原先这里是 {@code while (!set.contains(scheme)) SystemClock.sleep(10);} —— 无上限忙等。
     * 而这三个协议依赖的 {@code libmitv.so / libp2p.so / libp3p.so} 只在 armeabi-v7a 包里存在
     * （forcetech 的 aar 是单 ABI），64 位包里并没有：引擎服务进程起不来，
     * {@code onServiceConnected} 永不回调，播放页就会永久卡住转圈。
     * 改成有上限的等待后，等不到就抛可读异常，由上层按「播放失败」处理。</p>
     */
    private boolean bind(String scheme) {
        if (!init(scheme)) return false;
        long deadline = SystemClock.uptimeMillis() + BIND_TIMEOUT_MS;
        while (!set.contains(scheme) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10);
        return set.contains(scheme);
    }

    @Override
    public String fetch(String url) throws Exception {
        String scheme = Util.scheme(url);
        if (!set.contains(scheme) && !bind(scheme)) throw new ExtractException(ResUtil.getString(R.string.error_play_url));
        Uri uri = UrlUtil.uri(url);
        int port = Util.port(scheme);
        String id = uri.getLastPathSegment();
        String cmd = "http://127.0.0.1:" + port + "/cmd.xml?cmd=switch_chan&server=" + uri.getHost() + ":" + uri.getPort() + "&id=" + id;
        OkHttp.string(cmd, Map.of(HttpHeaders.USER_AGENT, "MTV"));
        return "http://127.0.0.1:" + port + "/" + id;
    }

    @Override
    public void stop() {
    }

    @Override
    public void exit() {
        try {
            if (!set.isEmpty()) App.get().unbindService(this);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            set.clear();
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        set.add(Util.trans(name));
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        set.remove(Util.trans(name));
    }
}
