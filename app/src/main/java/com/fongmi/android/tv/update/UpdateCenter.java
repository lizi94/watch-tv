package com.fongmi.android.tv.update;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;

/**
 * 版本检查：拉 GitHub Releases 列表 → 挑出「属于本变体」的最新一条 → 与本地 versionCode 比较。
 *
 * <h3>为什么按 tag 后缀挑，而不是直接比最大版本号</h3>
 * 仓库里并存 4 个 Release：{@code -mobile} / {@code -leanback} / {@code -home} / {@code -guard}。
 * 它们的版本号本来就不相同（标准树 6.5.2、自启树 6.5.3），直接取全局最大值会让
 * **自启版的用户被提示升级到标准版**（同 applicationId，装上去就是把功能换掉）。
 * 因此必须靠后缀锁定自己的那条线。
 *
 * <h3>为什么要多源</h3>
 * {@code api.github.com} 在部分网络下不可达（这也是本次做加速节点的初衷）。这里只做
 * **小体量 JSON** 的多源重试：直连 → 3 个加速前缀。JSON 只有几十 KB，走加速收益有限，
 * 真正的 94MB 安装包才需要用户在弹窗里挑节点。
 */
public class UpdateCenter {

    /** 与 release-upload.py 的 OWNER/REPO 必须一致，改一处就要改另一处。 */
    public static final String REPO = "lizi94/watch-tv";

    private static final String API = "https://api.github.com/repos/" + REPO + "/releases?per_page=30";

    /** 加速前缀（与 {@link Mirror} 里的节点同源，这里只取最能穿透的 3 个做 JSON 兜底）。 */
    private static final String[] API_MIRRORS = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
    };

    /**
     * 本变体对应的 Release tag 后缀。
     *
     * <p>⛔ 必须用穷举 if 而不是 {@code BuildConfig.FLAVOR.equals("leanback")}：
     * 拆 flavor 后电视端有 3 个变体（leanback / leanbackHome / leanbackGuard），
     * 精确匹配会让后两个恒判为手机版（R153 同类事故）。</p>
     */
    public static String suffix() {
        String flavor = BuildConfig.FLAVOR == null ? "" : BuildConfig.FLAVOR;
        if (flavor.startsWith("leanbackHome")) return "home";
        if (flavor.startsWith("leanbackGuard")) return "guard";
        if (flavor.startsWith("leanback")) return "leanback";
        if (flavor.startsWith("mobile")) return "mobile";
        return Util.isLeanback() ? "leanback" : "mobile";
    }

    /** 手动检查入口：不缓存结果，每次点击都重新请求（用户选定了「仅手动」）。 */
    public static void check() {
        if (UpdateState.getPhase() == UpdateState.PHASE_CHECKING) return;
        UpdateState.checking();
        Task.submit(() -> {
            String json = "";
            UpdateInfo found = null;
            for (String url : sources()) {
                String body = OkHttp.string(url);
                if (TextUtils.isEmpty(body)) continue;
                json = body;
                found = pick(body);
                // 拿到属于本变体的有效条目就停：不再无谓地打后面的源
                if (found != null) break;
            }
            if (TextUtils.isEmpty(json)) {
                UpdateState.failed(ResUtil.getString(R.string.update_check_failed_network));
                return;
            }
            if (found == null) {
                UpdateState.failed(ResUtil.getString(R.string.update_check_failed_empty));
                return;
            }
            if (found.isNewer()) UpdateState.available(found);
            else UpdateState.latest();
        });
    }

    private static String[] sources() {
        String[] urls = new String[API_MIRRORS.length + 1];
        urls[0] = API;
        for (int i = 0; i < API_MIRRORS.length; i++) urls[i + 1] = API_MIRRORS[i] + API;
        return urls;
    }

    /**
     * 用选定的下载源开始下载。
     *
     * <p>先把 info / total / mirror 写进 {@link UpdateState}，再启动服务：
     * 这样界面在服务启动前后都能立刻显示「正在下载 xxx」，不会出现一瞬间的空白弹窗。</p>
     */
    public static void download(Mirror mirror) {
        UpdateInfo info = UpdateState.getInfo();
        if (info == null || info.getAsset() == null || TextUtils.isEmpty(info.getAsset().getUrl())) {
            UpdateState.failed(ResUtil.getString(R.string.update_check_failed_empty));
            return;
        }
        UpdateInfo.Asset asset = info.getAsset();
        UpdateState.downloading(info, mirror, asset.getSize());
        UpdateService.start(App.get(), mirror.wrap(asset.getUrl()), info.getVersion());
    }

    /**
     * 从 Releases 列表里挑出本变体、版本最高、且带 apk 资产的那一条。
     *
     * <p>用 Gson 直接映射成数组：GitHub 的 {@code /releases} 恒为 JSON 数组，
     * 不需要引入 JsonParser，也避免了对 Gson 版本的额外假设。</p>
     */
    private static UpdateInfo pick(String json) {
        try {
            UpdateInfo[] list = App.gson().fromJson(json, UpdateInfo[].class);
            if (list == null) return null;
            String target = suffix();
            UpdateInfo best = null;
            for (UpdateInfo info : list) {
                if (info == null) continue;
                if (!info.getTag().toLowerCase().endsWith("-" + target)) continue;
                if (info.versionCode() <= 0) continue;
                if (info.getAsset() == null) continue;
                if (best == null || info.versionCode() > best.versionCode()) best = info;
            }
            return best;
        } catch (Throwable e) {
            // 上游返回非 JSON（被劫持/限流页）时走到这里，交给外层的「换源重试」
            return null;
        }
    }
}
