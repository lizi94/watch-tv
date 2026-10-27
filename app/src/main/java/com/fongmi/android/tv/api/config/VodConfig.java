package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.SiteVisibility;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Doh;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class VodConfig extends BaseConfig {

    private static final String TAG = VodConfig.class.getSimpleName();

    private Site home;
    private String wall;
    /** 离线解析标记（loadFromCache 置位）：initSite 据此跳过 parseJar，防主线程同步下载 jar ⇒ ANR。 */
    private boolean offlineParsing;
    private Parse parse;
    private List<Doh> doh;
    private List<Rule> rules;
    /** 配置里的全量站点（不做任何过滤：站源管理页要用它，否则被关掉的源再也找不回来）。 */
    private List<Site> sites;
    /** 用户「已打开」的可见站点：{@code sites} 减去被关掉的。所有取内容的入口都读它。 */
    private List<Site> visibleSites;
    /** sites 的 key → Site 索引（配置装载时一次性建好，见 setSites）。 */
    private Map<String, Site> siteIndex;
    private List<String> ads;
    private List<String> flags;
    private List<Parse> parses;

    public static VodConfig get() {
        return Loader.INSTANCE;
    }

    public static int getCid() {
        return get().getConfig().getId();
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    public static String getDesc() {
        return get().getConfig().getDesc();
    }

    public static int getHomeIndex() {
        return get().getSites().indexOf(get().getHome());
    }

    public static boolean hasParse() {
        return !get().getParses().isEmpty();
    }

    public static void load(Config config, Callback callback) {
        get().clear().config(config).load(callback);
    }

    public VodConfig init() {
        return config(Config.vod());
    }

    public VodConfig config(Config config) {
        this.config = config;
        return this;
    }

    public VodConfig clear() {
        ads = null;
        doh = null;
        home = null;
        wall = null;
        parse = null;
        sites = null;
        visibleSites = null;
        siteIndex = null;
        flags = null;
        rules = null;
        parses = null;
        BaseLoader.get().clear();
        RuleConfig.get().invalidate();
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected Config defaultConfig() {
        return Config.vod();
    }

    @Override
    protected void postEvent() {
        super.postEvent();
        ConfigEvent.vod();
    }

    @Override
    protected void load(Config config) throws Throwable {
        String json = Decoder.getJson(UrlUtil.convert(config.getUrl()), TAG);
        checkJson(config, Json.parse(json).getAsJsonObject());
        saveCache(config, json);
    }

    /**
     * 把这次成功拉到的配置 JSON 落盘，供 {@link #loadFromCache()} 冷启动时零网络重建。
     *
     * <p>⛔ <b>只在非 depot 形态时落盘</b>：depot（带 {@code urls} 字段）解析时会
     * {@code Config.delete(config.getUrl())} 换掉主配置行，写进去的 json 与新行对不上号，
     * 冷启动读出来会用错源。</p>
     *
     * <p>⛔ <b>写盘失败绝不影响本次加载</b>：缓存只是加速手段，丢了只是慢一点，
     * 绝不能因为写库失败把已经成功的配置判成失败（否则老人白等一次）。</p>
     */
    private void saveCache(Config config, String json) {
        try {
            if (json == null) return;
            JsonObject object = Json.parse(json).getAsJsonObject();
            if (object.has("urls")) return;
            Config item = Config.find(config.getUrl(), VOD);
            if (item == null || item.isEmpty()) return;
            item.json(json).update();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 从本地缓存的配置 JSON 直接重建内存态 —— <b>不发起任何网络请求</b>。
     *
     * <p>2026-10-16（R62）：冷启动时 {@link #isLoaded()} 只看内存里的 {@code sites}，
     * 而 {@code sites} 只能由 {@link #load(Config)} 走完一次网络往返才填得上。
     * 于是「上次成功加载过、但本次开机没网」时 {@code S=false} ⇒ {@code evaluateBoot()}
     * 判成「数据源没就绪」⇒ <b>在启动画面干等一个不会来的网络</b>。</p>
     *
     * <p>解法就是启用 {@code Config} 早就存在的 {@code json} 列（Room 列已建、零迁移）。
     * 本方法在冷启动早期同步调用，让 {@code S} 立刻为真 ⇒ <b>门控代码一行都不用改</b>，
     * 后台仍会照常走 {@code load()} 刷新。</p>
     *
     * @return 是否真的用缓存重建成功（没有缓存 / 缓存已失效 / 解析失败都返回 false）
     */
    public boolean loadFromCache() {
        if (isLoaded()) return true;
        // ⚠ 不能用 Config.vod()：它 findOne 不到时会 create 一个**空壳**（json 为 null），
        // 必须自己判 json 非空，否则会把「没有缓存」误当成「有缓存但解析不出」。
        Config item = Config.findOne(VOD);
        if (item == null || TextUtils.isEmpty(item.getJson())) return false;
        // 离线解析标记：initSite 据此跳过 parseJar（防主线程同步下载 spider jar ⇒ ANR）。
        offlineParsing = true;
        try {
            JsonObject object = Json.parse(item.getJson()).getAsJsonObject();
            if (object.has("urls")) return false;   // depot 形态不支持就地复用，走正常网络流程
            // ⛔ R63 修正：必须把缓存行赋给 this.config（R62 漏了）——否则本方法返回后
            // this.config 仍为 null，getConfig() 只能每次 fallback 到 defaultConfig() 新建对象，
            // 而 initView 末尾的 checkBootDirect() 正好落在这个窗口内。
            // ⛔ 不要把缓存 JSON 再写回数据库（读缓存时避免无谓写放大），只重建内存态即可。
            if (this.config == null) config(item);
            parseConfig(this.config, object);
            return isLoaded();
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        } finally {
            offlineParsing = false;
        }
    }

    /**
     * 配置是否装载成功。
     *
     * <p>⛔ 必须看<b>全量</b> {@link #getRawSites()}，不能看过滤后的 {@link #getSites()}：
     * 用户可能把所有站点都关掉（或配置里全是用不了的网盘源），此时可见列表为空，
     * 但配置本身是好的 —— 用可见列表判空会让启动门控永远认为「数据源没就绪」，
     * 卡在启动画面转圈出不来。</p>
     */
    @Override
    protected boolean isLoaded() {
        return !getRawSites().isEmpty();
    }

    private void checkJson(Config config, JsonObject object) throws Throwable {
        if (object.has("msg")) {
            throw new Exception(object.get("msg").getAsString());
        } else if (object.has("urls")) {
            parseDepot(config, object);
        } else {
            // ⛔ 普通配置必须清掉 depot 标记：否则「先导 depot、再导普通配置」时指纹仍沿用
            // 旧 depot 的 URL ⇒ 两份配置的站源选择互相串味。
            // 但不能在 depot 递归加载子配置时清（那条路径正需要保留），所以用 inDepot 区分。
            if (!inDepot) SiteVisibility.setDepotUrl(null);
            parseConfig(config, object);
        }
    }

    /** depot 递归加载子配置期间为 true：此时不得清空 depotUrl（见 checkJson）。 */
    private boolean inDepot;

    private void parseDepot(Config config, JsonObject object) throws Throwable {
        // depot 多源集合按「导入时这份配置的 URL」整体共用一份站源选择：
        // 否则切换子源时选择会整个变掉，用户会以为设置丢了。
        SiteVisibility.setDepotUrl(config.getUrl());
        List<Depot> items = Depot.arrayFrom(object.getAsJsonArray("urls").toString());
        List<Config> configs = new ArrayList<>();
        for (Depot item : items) configs.add(Config.find(item, VOD));
        if (configs.isEmpty()) throw new Exception("Depot urls is empty");
        inDepot = true;
        try {
            load(this.config = configs.get(0));
        } finally {
            inDepot = false;
        }
        // ⚠ 2026-10-16 审计修复（P1）：findOne 按 time DESC 取「当前配置」，而 Config.create()
        // 新建的子行 time=0。原先子行的激活时间戳全靠 saveCache() 的 update() 顺手补上——
        // 激活语义寄生在另一条路径的副作用上；一旦 saveCache 提前 return，冷启动就会
        // 解析到 time 更大的【旧配置行】，表现为「导入 depot 后重启回到了换之前的源」。
        // 在这里显式 update()，激活语义自洽。
        this.config.update();
        Config.delete(config.getUrl(), VOD);
    }

    private void parseConfig(Config config, JsonObject object) {
        initList(object);
        initLive(config, object);
        initWall(config, object);
        initSite(config, object);
        initParse(config, object);
        config.setLogo(Json.safeString(object, "logo"));
        config.setAssrt(Json.safeString(object, "assrt"));
        config.setNotice(Json.safeString(object, "notice"));
        config.setDanmaku(Json.safeString(object, "danmaku"));
    }

    private void initList(JsonObject object) {
        // offlineParsing（loadFromCache）期间禁止联网：这四个字段写成 URL 时会同步下载，
        // 而本方法的调用链顶端是 HomeActivity#initView 主线程 ⇒ 最坏 4×30s 完全无响应。
        // 后台 load() 成功后会以 allowNetwork=true 重建，URL 形式的规则届时补齐。
        boolean net = !offlineParsing;
        setHeaders(Header.arrayFrom(fetchArray(object, "headers", net)));
        setProxy(Proxy.arrayFrom(fetchArray(object, "proxy", net)));
        setRules(Rule.arrayFrom(fetchArray(object, "rules", net)));
        setDoh(Doh.arrayFrom(fetchArray(object, "doh", net)));
        setFlags(Json.safeListString(object, "flags"));
        setHosts(Json.safeListString(object, "hosts"));
        setAds(Json.safeListString(object, "ads"));
    }

    private void initLive(Config config, JsonObject object) {
        if (Json.isEmpty(object, "lives")) return;
        Config temp = Config.find(config, LIVE).save();
        boolean sync = LiveConfig.get().needSync(config.getUrl());
        // ⛔ 离线重建时必须把 allowLoader 关掉：LiveConfig#initLive 里的 parseJar 在 jar
        //    未缓存/md5 不符时会同步下载整个 spider jar（数十 MB、OkHttp 超时 30s），
        //    而这条链也在主线程上。直播频道列表仍照常解析，Spider 等后台 load() 再装载。
        if (sync) LiveConfig.get().config(temp.update()).parse(object, !offlineParsing);
    }

    private void initWall(Config config, JsonObject object) {
        if (Json.isEmpty(object, "wallpaper")) return;
        this.wall = Json.safeString(object, "wallpaper");
        Config temp = Config.find(wall, config.getName(), WALL).save();
        boolean sync = WallConfig.get().needSync(wall);
        if (sync) WallConfig.get().config(temp.update());
    }

    private void initSite(Config config, JsonObject object) {
        String spider = Json.safeString(object, "spider");
        // ⚠ 2026-10-16 审计修复（P1）：离线解析（loadFromCache）时跳过 parseJar ——
        // JarLoader.parseJar 在 jar 未缓存/md5 变化时会**同步下载整个 spider jar**
        // （可达数十 MB、OkHttp 30s 超时），调用链顶端是 HomeActivity#initView 主线程 ⇒ ANR。
        // 内存态重建不需要 Spider 可用，后台 load() 刷新时（offlineParsing=false）会正常装载。
        if (!offlineParsing) BaseLoader.get().parseJar(spider, true);
        setSites(Json.safeListElement(object, "sites").stream().map(e -> Site.objectFrom(e, spider)).distinct().collect(Collectors.toCollection(ArrayList::new)));
        Map<String, Site> items = Site.findAll().stream().collect(Collectors.toMap(Site::getKey, Function.identity()));
        getRawSites().forEach(site -> site.sync(items.get(site.getKey())));
        setHome(config, findHome(config), false);
    }

    private void initParse(Config config, JsonObject object) {
        setParses(Json.safeListElement(object, "parses").stream().map(Parse::objectFrom).distinct().collect(Collectors.toCollection(ArrayList::new)));
        setParse(config, getParses().isEmpty() ? new Parse() : getParses().stream().filter(item -> item.getName().equals(config.getParse())).findFirst().orElse(getParses().get(0)), false);
    }

    /**
     * 装载配置时决定「当前首页源」。
     *
     * <p><b>2026-10-15 修复静默漂移</b>：原实现只按 {@code key} 匹配 ——
     * 而配置里没写 {@code key} 的源，{@link Site#getKey()} 返回<b>空串</b>，
     * {@code setHome(site)} 落到数据库里的也是空串，下次装载时
     * {@code filter(key.equals(""))} 会命中<b>第一个无 key 的源</b>，
     * 首页源就这样被静默换成了别的站（表现为「首页快速搜索对某些 index 源失效」：
     * 判据 {@code getHome().isIndex()} 看的是被换掉的那个源）。</p>
     *
     * <p>因此把匹配口径放宽为「先按 key 精确匹配，再按 name 兜底」；
     * 两轮都没有才退回第一个源（与原实现的兜底一致）。{@code saved} 为空串时不走 name 兜底，
     * 避免匹配到同样没有名字的源。</p>
     */
    private Site findHome(Config config) {
        List<Site> sites = getSites();
        if (sites.isEmpty()) return new Site();
        String saved = config.getHome() == null ? "" : config.getHome();
        for (Site item : sites) if (item.getKey().equals(saved)) return item;
        if (!saved.isEmpty()) for (Site item : sites) if (item.getName().equals(saved)) return item;
        return sites.get(0);
    }

    /** 全量站点（配置原样，含用户关掉的）。仅「选择能看的站」与管理类逻辑使用。 */
    public List<Site> getRawSites() {
        return sites == null ? Collections.emptyList() : sites;
    }

    /**
     * 可见站点 = 全量 − 用户关掉的。首页、分类、搜索、换源、锁定、历史、收藏全部经由这里取源，
     * 所以「一处过滤，全部入口生效」。
     */
    public List<Site> getSites() {
        return visibleSites == null ? Collections.emptyList() : visibleSites;
    }

    /** 用户在「选择能看的站」里改完选择后调用：重建可见列表，让新选择立刻生效。 */
    public void applyVisibility() {
        this.visibleSites = SiteVisibility.filter(this.sites);
    }

    private void setSites(List<Site> sites) {
        this.sites = sites;
        // 装载时顺手修剪：配置里已删掉的 key 从「已关掉」集合中移除，避免桶无限堆积。
        SiteVisibility.prune(sites);
        this.visibleSites = SiteVisibility.filter(sites);
        // key → Site 索引：getSite(key) 是全应用最高频的配置查询之一（详情页取源、播放页取源、
        // 搜索、弹幕、锁定校验、历史取名等十余处都调），原实现是线性 stream 扫描 ——
        // 站点数上百时每次都是 O(n) 的字符串比较。这里在配置装载时一次性建索引，查询降为 O(1)。
        // 重复 key 保留先出现的那个，与原 findFirst 语义一致。
        this.siteIndex = sites.stream().filter(item -> !item.getKey().isEmpty())
                .collect(Collectors.toMap(Site::getKey, Function.identity(), (first, ignored) -> first));
    }

    public List<Parse> getParses() {
        return parses == null ? Collections.emptyList() : parses;
    }

    private void setParses(List<Parse> parses) {
        if (!parses.isEmpty()) parses.add(0, Parse.god());
        this.parses = parses;
    }

    public List<Doh> getDoh() {
        List<Doh> items = Doh.get(App.get());
        if (doh == null) return items;
        items.removeAll(doh);
        items.addAll(doh);
        return items;
    }

    private void setDoh(List<Doh> doh) {
        this.doh = doh;
    }

    public List<Rule> getRules() {
        return rules == null ? Collections.emptyList() : rules;
    }

    private void setRules(List<Rule> rules) {
        this.rules = rules;
        RuleConfig.get().invalidate();
    }

    public List<Parse> getParses(int type) {
        return getParses().stream().filter(item -> item.getType() == type).toList();
    }

    public List<Parse> getParses(int type, String flag) {
        List<Parse> items = getParses(type);
        List<Parse> filter = items.stream().filter(item -> item.getExt().getFlag().contains(flag)).toList();
        return filter.isEmpty() ? items : filter;
    }

    public List<String> getFlags() {
        return flags == null ? Collections.emptyList() : flags;
    }

    private void setFlags(List<String> flags) {
        this.flags = flags;
    }

    public List<String> getAds() {
        return ads == null ? Collections.emptyList() : ads;
    }

    private void setAds(List<String> ads) {
        this.ads = ads;
        RuleConfig.get().invalidate();
    }

    public Parse getParse() {
        return parse == null ? new Parse() : parse;
    }

    public void setParse(Parse parse) {
        setParse(getConfig(), parse, true);
    }

    public Site getHome() {
        return home == null ? new Site() : home;
    }

    public void setHome(Site site) {
        setHome(getConfig(), site, true);
        RefreshEvent.home();
    }

    public String getWall() {
        return TextUtils.isEmpty(wall) ? "" : wall;
    }

    public Parse getParse(String name) {
        return getParses().stream().filter(item -> item.getName().equals(name)).findFirst().orElse(new Parse());
    }

    public Site getSite(String key) {
        // 索引就绪时 O(1)；未就绪（配置尚未装载完成 / clear 之后）退回线性扫描，
        // 结果与原实现完全等价 —— 只是查得快，没有任何语义变化。
        Site indexed = siteIndex == null ? null : siteIndex.get(key);
        if (indexed != null) return indexed;
        return getSites().stream().filter(item -> item.getKey().equals(key)).findFirst().orElse(new Site());
    }

    private void setParse(Config config, Parse parse, boolean save) {
        this.parse = parse;
        this.parse.setSelected(true);
        config.setParse(parse.getName());
        getParses().forEach(item -> item.setSelected(parse));
        if (save) config.save();
    }

    private void setHome(Config config, Site site, boolean save) {
        home = site;
        home.setSelected(true);
        config.setHome(home.getKey());
        if (save) config.save();
        getRawSites().forEach(item -> item.setSelected(home));
    }

    private static class Loader {
        static volatile VodConfig INSTANCE = new VodConfig();
    }
}
