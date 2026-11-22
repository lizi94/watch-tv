package com.fongmi.android.tv.update;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub Release 的解析结果，只保留在线更新需要的字段。
 *
 * <p>VersionCode 与 VersionName 的换算走全项目统一规则（见 {@code app/build.gradle} 注释）：
 * {@code major = code / 100}、{@code minor = (code % 100) / 10}、{@code patch = code % 10}
 * ⇒ {@code 652} 对应 {@code 6.5.2}。本类反向解析 tag 拿到 code，再与
 * {@code BuildConfig.VERSION_CODE} 比较，**不使用 versionName 字符串比较**
 * —— 否则 guard 变体的 {@code -guard} 后缀、以及将来两位数 patch 都会踩坑。</p>
 */
public class UpdateInfo {

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    @SerializedName("tag_name")
    private String tag;

    @SerializedName("body")
    private String body;

    @SerializedName("published_at")
    private String publishedAt;

    @SerializedName("assets")
    private List<Asset> assets;

    public static class Asset {

        @SerializedName("name")
        private String name;

        @SerializedName("size")
        private long size;

        @SerializedName("browser_download_url")
        private String url;

        public String getName() {
            return name == null ? "" : name;
        }

        public long getSize() {
            return size;
        }

        public String getUrl() {
            return url == null ? "" : url;
        }
    }

    public String getTag() {
        return tag == null ? "" : tag;
    }

    /**
     * tag 里的人类可读版本号：{@code "v6.5.2-leanback"} → {@code "6.5.2"}。
     * 只用于展示（通知标题、弹窗文案），版本新旧一律用 {@link #versionCode()} 判。
     */
    public String getVersion() {
        String value = getTag();
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        int dash = value.indexOf('-');
        if (dash > 0) value = value.substring(0, dash);
        return value;
    }

    public String getBody() {
        return body == null ? "" : body.trim();
    }

    /** 发布日期（yyyy-MM-dd）。GitHub 给的是 ISO8601，截前 10 位即可，解析失败返回空串。 */
    public String getDate() {
        if (publishedAt == null || publishedAt.length() < 10) return "";
        return publishedAt.substring(0, 10);
    }

    /** 安装包资产：同类 Release 里只有一个 apk，取第一个以 .apk 结尾的即可。 */
    public Asset getAsset() {
        if (assets == null) return null;
        for (Asset asset : assets) if (asset.getUrl().toLowerCase().endsWith(".apk")) return asset;
        return null;
    }

    public int versionCode() {
        return parse(getTag());
    }

    public boolean isNewer() {
        return versionCode() > com.fongmi.android.tv.BuildConfig.VERSION_CODE;
    }

    /**
     * {@code "v6.5.2-leanback"} → {@code 652}。
     *
     * <p>解析不出三段数字时返回 0：调用方据此把该条 Release 判为无效并跳过，
     * 不会因为格式异常的上游 tag 误判成「有新版」。</p>
     */
    public static int parse(String tag) {
        if (tag == null || tag.isEmpty()) return 0;
        Matcher matcher = VERSION.matcher(tag);
        if (!matcher.find()) return 0;
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = Integer.parseInt(matcher.group(3));
            // ⛔ 不要对 patch 做 min(9) 之类裁剪：那会把 6.5.10 悄悄变成 6.5.9，
            //    表现为「明明有新版本却提示已是最新」。拆位规则本身限定 patch 为一位数，
            //    真出现两位数时宁可算出更大的 code（提示有新版），也不能少算。
            return major * 100 + minor * 10 + patch;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
