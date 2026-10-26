package com.fongmi.android.tv.server;

import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Proxy;
import com.github.catvod.utils.Util;

public class Server {

    private volatile PlaybackService service;
    private volatile Nano nano;

    private static class Loader {
        static volatile Server INSTANCE = new Server();
    }

    public static Server get() {
        return Loader.INSTANCE;
    }

    public PlaybackService getService() {
        return service;
    }

    public void setService(PlaybackService service) {
        this.service = service;
    }

    public String getAddress() {
        return getAddress(false);
    }

    public String getAddress(int tab) {
        return getAddress(false) + "?tab=" + tab;
    }

    public String getAddress(String path) {
        return getAddress(true) + path;
    }

    public String getAddress(boolean local) {
        return "http://" + (local ? "127.0.0.1" : Util.getIp()) + ":" + Proxy.getPort();
    }

    public synchronized void start() {
        if (nano != null) return;
        for (int i = 9978; i < 9999; i++) {
            try {
                nano = new Nano(i);
                nano.start(500);
                Proxy.set(i);
                break;
            } catch (Throwable e) {
                nano = null;
            }
        }
    }

    public void stop() {
        Task.execute(() -> {
            // ⚠ 2026-10-16 审计修复（P2）：start() 是 synchronized 而 stop 的任务不持锁 ——
            // 交错序列「start() 检查 nano!=null 直接 return → stop 任务把刚建的 nano 置 null」
            // 会丢失运行中服务的引用（端口/线程泄漏，Proxy 端口也被二次改写）。
            // stop 任务内对同一把锁同步，保证 stop/start 对 nano 的读写互斥。
            synchronized (this) {
                if (nano != null) nano.stop();
                service = null;
                nano = null;
            }
        });
    }
}
