package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

public class LiveSetting {

    /**
     * R125：原先这里有 {@code isBoot()} / {@code putBoot()}（「换内核 ⇒ 回直播页」的一次性标记）。
     * 它写的是 {@code Prefers}（持久化），于是标记会跨天残留：某天换了一次内核，
     * 第二天打开 App 还会被弹进直播页。
     * 现在换内核改成 {@code TVBus#change} <b>原地重建引擎</b>，不再需要杀进程再跳回来，
     * 这个标记连同它的唯一写入点一起删除（详见 {@code TVBus#change} 的注释）。</p>
     *
     * <p>注意：{@code Prefers} 里可能还留着旧的 {@code "boot_live"} 键，它已无人读取，属无害残留。</p>
     */

    public static boolean isAcross() {
        return Prefers.getBoolean("across", true);
    }

    public static void putAcross(boolean across) {
        Prefers.put("across", across);
    }

    public static boolean isChange() {
        return Prefers.getBoolean("change", true);
    }

    public static void putChange(boolean change) {
        Prefers.put("change", change);
    }

    public static boolean isInvert() {
        return Prefers.getBoolean("invert");
    }

    public static void putInvert(boolean invert) {
        Prefers.put("invert", invert);
    }

    public static int getScale() {
        return Math.clamp(Prefers.getInt("scale_live", PlayerSetting.getScale()), PlayerSetting.MIN_SCALE, PlayerSetting.MAX_SCALE);
    }

    public static void putScale(int scale) {
        Prefers.put("scale_live", Math.clamp(scale, PlayerSetting.MIN_SCALE, PlayerSetting.MAX_SCALE));
    }
}
