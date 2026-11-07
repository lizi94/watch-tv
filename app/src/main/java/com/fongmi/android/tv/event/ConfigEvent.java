package com.fongmi.android.tv.event;

import org.greenrobot.eventbus.EventBus;

/**
 * 配置事件。
 *
 * <p><b>BOOT 的语义（R125 收敛）</b>：现在只剩一个来源 ——
 * 直播配置解析完成、且用户打开了「直播开机直达」开关（见 {@code LiveConfig#setHome}）。
 * 它<b>不新建</b>直达目标，只推进首页里<b>已经登记好</b>的直播目标；
 * 否则运行期任何一次直播配置重载（换源、重试）都会把老人从正在看的页面上拽走。</p>
 *
 * <p>R124 时这里还有一个 {@code tvbus} 分量，表示「这次 BOOT 是用户刚换了直播内核（TVBus）触发的」。
 * R125 删掉了它：换内核改成<b>原地重建引擎</b>（见 {@code TVBus#change}），
 * 不再需要「杀进程 → 重开 → 跳回直播页」那条链路，标记也就没有存在意义了。</p>
 */
public record ConfigEvent(Type type) {

    public static void common() {
        EventBus.getDefault().post(new ConfigEvent(Type.COMMON));
    }

    public static void vod() {
        EventBus.getDefault().post(new ConfigEvent(Type.VOD));
    }

    public static void live() {
        EventBus.getDefault().post(new ConfigEvent(Type.LIVE));
    }

    public static void wall() {
        EventBus.getDefault().post(new ConfigEvent(Type.WALL));
    }

    /**
     * 「直播配置就绪，且开着直播开机直达」：由 {@code LiveConfig#setHome} 在解析完成时派发。
     *
     * <p>接收方（首页）据此推进直播直达目标；<b>没有</b>接收方登记目标时这条事件什么也不做。</p>
     */
    public static void boot() {
        EventBus.getDefault().post(new ConfigEvent(Type.BOOT));
    }

    public boolean isVod() {
        return type == Type.VOD;
    }

    public boolean isLive() {
        return type == Type.LIVE;
    }

    public enum Type {
        COMMON, VOD, LIVE, WALL, BOOT
    }
}
