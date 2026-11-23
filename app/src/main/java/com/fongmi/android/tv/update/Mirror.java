package com.fongmi.android.tv.update;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个下载源（加速节点 / 自动 / 直连）。
 *
 * <p>加速 URL 的拼装方式固定为 {@code <节点> + "/" + 原始 GitHub 地址}，这是
 * github.akams.cn 列举的所有镜像代理的统一约定；不要改成只替换 host，
 * 那样会把 {@code https://github.com/a/b.apk} 变成 {@code https://ghproxy.net/a/b.apk}（404）。</p>
 *
 * <p>测速结果直接挂在实例上（volatile），列表刷新时由 {@link MirrorProbe} 回填，
 * 不另建映射表 —— 一处数据源，避免「列表里显示的值」和「实际选中的值」对不上。</p>
 */
public class Mirror {

    public static final int KIND_AUTO = 0;
    public static final int KIND_NODE = 1;
    public static final int KIND_DIRECT = 2;

    /** 还没测过。 */
    public static final int DELAY_UNKNOWN = -1;
    /** 测速失败 / 超时（连不上、DNS 失败、超过超时时间）。 */
    public static final int DELAY_TIMEOUT = -2;
    /**
     * 节点有响应但拒绝了本次探测（403 / 404 / 429 等）。
     *
     * <p>与 {@link #DELAY_TIMEOUT} 必须分开：超时是「连不上」，不可用是「连得上但此刻不给包」。
     * 只显示一个「超时」会让用户以为网络坏了，实际多为节点限流或该路径不被代理。</p>
     */
    public static final int DELAY_BLOCKED = -3;

    /**
     * 8 个精选加速节点（来源 github.akams.cn 的「贡献」类节点）。
     * ⛔ 只放长期在线的：该页面同时列出大量个人「测绘」节点，存活期常常只有几天，
     *    放进默认列表会让老人反复遇到「下载失败」。新增/替换只改这个数组即可。
     */
    private static final String[] NODES = {
            "https://gh.dpik.top",
            "https://ghproxy.net",
            "https://gh-proxy.com",
            "https://cdn.gh-proxy.com",
            "https://ghfast.top",
            "https://ghfile.geekertao.top",
            "https://github.tbap.top",
            "https://gh.ddlc.top",
    };

    private final int kind;
    private final String name;
    private final String base;

    private volatile int delay = DELAY_UNKNOWN;
    /**
     * 探测的原始往返耗时（ms）；连不上为 {@link Integer#MAX_VALUE}。
     *
     * <p>与 {@link #delay} 分开记：{@code delay} 是判定结果（可用 / 不可用 / 超时），
     * 这个值无论成败都记，用于「全部不可用时挑响应最快的一个」。</p>
     */
    private volatile int cost = Integer.MAX_VALUE;

    /**
     * 全局共享的下载源列表（含测速结果）。
     *
     * <p>必须是单例：切字号档位 / HDMI 重协商会让弹窗走 Fragment 重建流程，若每次
     * {@link #create()} 都新建列表，重建后的弹窗会看到「一片未测速」，而上一轮的测速结果
     * 写在了被丢弃的旧列表上 —— 表现为「转个身回来延迟全没了、又得等 3 秒」。</p>
     */
    private static volatile List<Mirror> shared;

    public static List<Mirror> shared() {
        List<Mirror> local = shared;
        if (local == null) {
            synchronized (Mirror.class) {
                local = shared;
                if (local == null) shared = local = create();
            }
        }
        return local;
    }

    private Mirror(int kind, String name, String base) {
        this.kind = kind;
        this.name = name;
        this.base = base;
    }

    /** 默认列表：自动 → 8 个节点 → 直连，共 10 项，与设计稿一致。 */
    public static List<Mirror> create() {
        List<Mirror> list = new ArrayList<>();
        list.add(new Mirror(KIND_AUTO, ResUtil.getString(R.string.update_mirror_auto), null));
        for (String node : NODES) list.add(new Mirror(KIND_NODE, host(node), node));
        list.add(new Mirror(KIND_DIRECT, ResUtil.getString(R.string.update_mirror_direct), null));
        return list;
    }

    /** {@code https://gh.dpik.top} → {@code gh.dpik.top}：行内显示名用 host，不带协议头。 */
    private static String host(String base) {
        String value = base;
        int index = value.indexOf("://");
        if (index >= 0) value = value.substring(index + 3);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    public int getKind() {
        return kind;
    }

    /**
     * 持久化用的稳定标识（存进 Prefers，不能存下标 —— 节点数组一旦增删，下标就指错人）。
     * 自动 = 空串、直连 = {@code "direct"}、节点 = 完整 base 地址。
     */
    public String getKey() {
        if (kind == KIND_AUTO) return "";
        if (kind == KIND_DIRECT) return "direct";
        return base == null ? "" : base;
    }

    /** 按 key 找源；找不到（用户存过已下线的节点）回落到「自动」。 */
    public static Mirror byKey(List<Mirror> list, String key) {
        String target = key == null ? "" : key;
        for (Mirror mirror : list) if (mirror.getKey().equals(target)) return mirror;
        return list.isEmpty() ? null : list.get(0);
    }

    /** 直连那一项。自动模式在所有节点都超时的时候回落到它。 */
    public static Mirror direct(List<Mirror> list) {
        for (Mirror mirror : list) if (mirror.isDirect()) return mirror;
        return null;
    }

    public String getName() {
        return name;
    }

    public boolean isAuto() {
        return kind == KIND_AUTO;
    }

    public boolean isDirect() {
        return kind == KIND_DIRECT;
    }

    public int getDelay() {
        return delay;
    }

    public void setDelay(int delay) {
        this.delay = delay;
    }

    public int getCost() {
        return cost;
    }

    public void setCost(int cost) {
        this.cost = cost;
    }

    public boolean isBlocked() {
        return delay == DELAY_BLOCKED;
    }

    public boolean isTimeout() {
        return delay == DELAY_TIMEOUT;
    }

    /** 只有真实节点需要测速；自动与直连不参与，但它们也可能「无可用节点」。 */
    public boolean isProbeable() {
        return kind == KIND_NODE;
    }

    /**
     * 把原始 GitHub 地址套上加速前缀。
     *
     * @param url 形如 {@code https://github.com/lizi94/watch-tv/releases/download/v6.5.2/x.apk}
     */
    public String wrap(String url) {
        if (base == null || url == null || url.isEmpty()) return url;
        return base + "/" + url;
    }

    /**
     * 行尾延迟文案。颜色不在这里决定：电视版分级 + 手机版明暗都是适配器里按状态算的
     * （见两个源集各自的 {@code MirrorAdapter}），文案只负责给值：
     * 未知 → {@code —}、超时 → {@code 超时}、不可用 → {@code 不可用}、正常 → {@code 123 ms}。
     */
    public String getDelayText() {
        if (delay == DELAY_UNKNOWN) return ResUtil.getString(R.string.update_delay_unknown);
        if (delay == DELAY_TIMEOUT) return ResUtil.getString(R.string.update_delay_timeout);
        if (delay == DELAY_BLOCKED) return ResUtil.getString(R.string.update_delay_blocked);
        return ResUtil.getString(R.string.update_delay_ms, delay);
    }

    /** 按延迟升序排列（未知/超时排最后），供「自动」模式挑最快节点。 */
    public static List<Mirror> sorted(List<Mirror> list) {
        List<Mirror> copy = new ArrayList<>(list);
        copy.sort((left, right) -> {
            int a = left.delay <= 0 ? Integer.MAX_VALUE : left.delay;
            int b = right.delay <= 0 ? Integer.MAX_VALUE : right.delay;
            return Integer.compare(a, b);
        });
        return copy;
    }

    /**
     * 「自动」模式下真正要用的节点：延迟最小且测速成功的那个。
     * 一个都没有（全部超时）时返回 {@code null}，由调用方回落到直连并提示。
     */
    public static Mirror fastest(List<Mirror> list) {
        Mirror best = null;
        for (Mirror mirror : list) {
            if (!mirror.isProbeable() || mirror.delay <= 0) continue;
            if (best == null || mirror.delay < best.delay) best = mirror;
        }
        return best;
    }

    /**
     * 「自动」的兜底：有响应（含 4xx/5xx）的节点里响应最快的一个。
     *
     * <p>为什么需要它：{@link #fastest(List)} 只认测速成功的节点，一旦全部「不可用」就返回 null，
     * 旧调用方随即回落<b>直连</b> —— 而直连在国内基本不通，等于必然下载失败。
     * 现在先试「连得上但被拒」里最快的那一个（限流往往是偶发的），一个都没响应才回落直连。</p>
     */
    public static Mirror fastestAny(List<Mirror> list) {
        Mirror best = null;
        for (Mirror mirror : list) {
            if (!mirror.isProbeable()) continue;
            if (mirror.cost == Integer.MAX_VALUE) continue;
            if (best == null || mirror.cost < best.cost) best = mirror;
        }
        return best;
    }
}
