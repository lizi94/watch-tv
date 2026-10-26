package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.LiveApi;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.api.parser.LiveParser;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.LiveSetting;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class LiveConfig extends BaseConfig {

    private static final String TAG = LiveConfig.class.getSimpleName();

    private Live home;
    /** volatile：Task 线程写（load→setLives）、主线程读（hasData/isLoaded），需 happens-before（见 getLives）。
     *  ⚠ 字段声明处直接初始化（R64 补丁）：init() 不经过 clear()，单例创建后若依赖 clear/懒赋值
     *  兜底，首次安装（数据从未加载）时 getLives() 会返回 null ⇒ hasData() NPE ⇒ EventBus 吞掉
     *  订阅者异常 ⇒ setFunc() 中断 ⇒ **首页功能区永久空白**（R64 真实回归，用户实测复现）。 */
    private volatile List<Live> lives = new ArrayList<>();
    private List<Rule> rules;
    private List<String> ads;

    public static LiveConfig get() {
        return Loader.INSTANCE;
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    public static String getDesc() {
        return get().getConfig().getDesc();
    }

    public static String getResp() {
        return get().getHome().getCore().getResp();
    }

    public static int getHomeIndex() {
        return get().getLives().indexOf(get().getHome());
    }

    public static boolean isOnly() {
        return get().getLives().size() == 1;
    }

    public static boolean isEmpty() {
        return get().getHome().isEmpty();
    }

    public static boolean hasUrl() {
        return !TextUtils.isEmpty(getUrl());
    }

    /**
     * 「真的有直播源数据」——{@link #hasUrl()} <b>不能</b>用来判断这件事。
     *
     * <p><b>为什么</b>：上游用「主配置 JSON 里带 {@code lives} 字段」来承载内嵌直播，
     * {@code VodConfig#initLive()} 会执行 {@code Config.find(config, LIVE).save()} ——
     * 即<b>拿主数据源的 url 建一条 type=1（直播）的 Config 行</b>。于是：
     * <ul>
     *   <li>{@code hasUrl()} 为 true，但 {@code getLives()} 是<b>空的</b>（还没 load / 没解析出 lives）；</li>
     *   <li>同时 {@link #config(Config)} 算出 {@code sync = true}（url 与主数据源相同）
     *       ⇒ {@link #load()} 开头 {@code if (sync) return;} ⇒ 直播配置<b>永不独立加载</b>。</li>
     * </ul>
     * 老人看到的现象就是：设置页「直播源」输入框里躺着一个点不开的地址、
     * 首页功能行冒出「直播」入口，点进去却一条频道都没有。</p>
     *
     * <p>因此凡是<b>面向老人决定「要不要显示直播入口 / 有没有直播可用」</b>的判据，
     * 一律用本方法（{@code lives} 非空 <b>且</b> home 非空），不要用 {@code hasUrl()}。
     * {@code hasUrl()} 只保留给「配置层」用途（设置页展示当前配置 url、{@code ConfigDialog} 回填）。</p>
     */
    public static boolean hasData() {
        return !get().getLives().isEmpty() && !get().getHome().isEmpty();
    }

    /**
     * 面向老人展示的「直播源」当前状态文案。
     *
     * <p>设置页的「直播源」输入框原先直接显示 {@link #getDesc()}，而 {@code getDesc()} 在
     * {@code Config} 行存在时就返回它的 url/name —— 哪怕那条行其实是 {@code VodConfig#initLive()}
     * 拿<b>主数据源 url</b>建的占位行。于是老人看到「直播源已填成点播的地址」，
     * 切换字号（页面 recreate）或重启（重新读库）都会让它再次出现，却始终没有直播数据。</p>
     *
     * <p>因此这里以 {@link #hasData()} 为准：有直播数据才显示真实 desc；没有就返回空串
     * —— 输入框为空，配合页面已有的「未导入」类文案，语义就是「还没导入直播源」，
     * 不会误导老人去点一个用不了的地址。<b>注意这只改显示，不动数据库</b>：
     * {@code hasUrl()} 仍保留给「配置层」用途（{@code ConfigDialog} 回填当前 url）。</p>
     */
    public static String getDisplayDesc() {
        return hasData() ? getDesc() : "";
    }

    public static void load(Config config, Callback callback) {
        get().clear().config(config).load(callback);
    }

    public LiveConfig init() {
        return config(Config.live());
    }

    public LiveConfig config(Config config) {
        this.config = config;
        if (config.isEmpty()) return this;
        this.sync = config.getUrl().equals(VodConfig.getUrl());
        return this;
    }

    public LiveConfig clear() {
        ads = null;
        home = null;
        // ⚠ 2026-10-16 审计修复：getter 不再懒赋值（见 getLives 注释），clear 时直接初始化，
        // 保证任何线程读到的都是非 null 列表。
        lives = new ArrayList<>();
        rules = null;
        RuleConfig.get().invalidate();
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected Config defaultConfig() {
        return Config.live();
    }

    @Override
    protected void postEvent() {
        super.postEvent();
        ConfigEvent.live();
    }

    @Override
    protected void load(Config config) throws Throwable {
        String json = Decoder.getJson(UrlUtil.convert(config.getUrl()), TAG);
        if (Json.isObj(json)) checkJson(config, Json.parse(json).getAsJsonObject());
        else parseText(config, json);
    }

    @Override
    protected boolean isLoaded() {
        return !getLives().isEmpty() && !getHome().getGroups().isEmpty();
    }

    @Override
    public synchronized void ensureLoaded() {
        try {
            if (isLoaded()) return;
            super.ensureLoaded();
            LiveApi.parse(getHome());
            LiveApi.parseXml(getHome());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public void load() {
        if (sync) return;
        load(new Callback());
    }

    private void parseText(Config config, String text) {
        Live live = new Live(UrlUtil.getName(config.getUrl()), config.getUrl()).sync();
        lives = new ArrayList<>(List.of(live));
        LiveParser.text(live, text);
        setHome(config, live, false);
    }

    private void checkJson(Config config, JsonObject object) throws Throwable {
        if (object.has("msg")) {
            throw new Exception(object.get("msg").getAsString());
        } else if (object.has("urls")) {
            parseDepot(config, object);
        } else {
            parseConfig(config, object);
        }
    }

    private void parseDepot(Config config, JsonObject object) throws Throwable {
        List<Depot> items = Depot.arrayFrom(object.getAsJsonArray("urls").toString());
        List<Config> configs = new ArrayList<>();
        for (Depot item : items) configs.add(Config.find(item, LIVE));
        if (configs.isEmpty()) throw new Exception("Depot urls is empty");
        load(this.config = configs.get(0));
        Config.delete(config.getUrl());
    }

    private void parseConfig(Config config, JsonObject object) {
        initList(object);
        initLive(config, object, true);
    }

    public void parse(JsonObject object) {
        parse(object, true);
    }

    /**
     * @param allowLoader false = 离线重建（{@code VodConfig#loadFromCache}）：
     *                    跳过 {@code BaseLoader.parseJar} —— 它在 jar 未缓存/md5 变化时会
     *                    <b>同步下载数十 MB 的 spider jar</b>（OkHttp 30s 超时），而这条链在主线程上。
     *                    频道列表照常解析，需要 Spider 的能力等后台 load() 完成后再具备。
     */
    public void parse(JsonObject object, boolean allowLoader) {
        initLive(getConfig(), object, allowLoader);
    }

    private void initList(JsonObject object) {
        setHeaders(Header.arrayFrom(fetchArray(object, "headers")));
        setProxy(Proxy.arrayFrom(fetchArray(object, "proxy")));
        setRules(Rule.arrayFrom(fetchArray(object, "rules")));
        setHosts(Json.safeListString(object, "hosts"));
        setAds(Json.safeListString(object, "ads"));
    }

    private void initLive(Config config, JsonObject object, boolean allowLoader) {
        String spider = Json.safeString(object, "spider");
        // 离线态跳过：见 parse(JsonObject, boolean) 的说明（同步下载 jar ⇒ 主线程 ANR）
        if (allowLoader) BaseLoader.get().parseJar(spider, false);
        setLives(Json.safeListElement(object, "lives").stream().map(e -> Live.objectFrom(e, spider)).distinct().collect(Collectors.toCollection(ArrayList::new)));
        Map<String, Live> items = Live.findAll().stream().collect(Collectors.toMap(Live::getName, Function.identity()));
        getLives().forEach(live -> live.sync(items.get(live.getName())));
        setHome(config, getLives().isEmpty() ? new Live() : getLives().stream().filter(item -> item.getName().equals(config.getHome())).findFirst().orElse(getLives().get(0)), false);
    }

    public void setKeep(Channel channel) {
        if (home != null && !channel.getGroup().isHidden()) home.keep(channel).save();
    }

    public void applyKeepsToGroups(List<Group> items) {
        Set<String> key = Keep.getLive().stream().map(Keep::getKey).collect(Collectors.toSet());
        items.stream().filter(group -> !group.isKeep())
                .flatMap(group -> group.getChannel().stream())
                .filter(channel -> key.contains(channel.getName()))
                .forEach(channel -> items.get(0).add(channel));
    }

    public int[] findKeepPosition(List<Group> items) {
        String[] splits = getHome().getKeep().split(AppDatabase.SYMBOL);
        if (splits.length < 3) return new int[]{1, 0};
        for (int i = 0; i < items.size(); i++) {
            Group group = items.get(i);
            if (group.getName().equals(splits[0])) {
                int j = group.find(splits[1]);
                if (j != -1) {
                    group.getChannel().get(j).setIndex(splits[2]);
                    return new int[]{i, j};
                }
            }
        }
        return new int[]{1, 0};
    }

    public int[] findByChannelNumber(String number, List<Group> items) {
        int num = Integer.parseInt(number);
        for (int i = 0; i < items.size(); i++) {
            int j = items.get(i).find(num);
            if (j != -1) return new int[]{i, j};
        }
        return new int[]{-1, -1};
    }

    public List<Live> getLives() {
        // ⚠ 2026-10-16 审计修复（P2）：getter 内懒赋值有竞态 —— 写入发生在 Task 线程，
        // hasData()/isLoaded() 在主线程读，无 happens-before；两线程同进 getter 还会各自
        // new 一个列表（其中一个被静默丢弃）。改为 clear() 时就初始化、getter 纯读、字段 volatile。
        return lives;
    }

    private void setLives(List<Live> lives) {
        this.lives = lives == null ? new ArrayList<>() : lives;
    }

    public List<Rule> getRules() {
        return rules == null ? Collections.emptyList() : rules;
    }

    private void setRules(List<Rule> rules) {
        this.rules = rules;
        RuleConfig.get().invalidate();
    }

    public List<String> getAds() {
        return ads == null ? Collections.emptyList() : ads;
    }

    private void setAds(List<String> ads) {
        this.ads = ads;
        RuleConfig.get().invalidate();
    }

    public Live getHome() {
        return home == null ? new Live() : home;
    }

    public void setHome(Live home) {
        setHome(getConfig(), home, true);
    }

    public Live getLive(String key) {
        return getLives().stream().filter(item -> item.getName().equals(key)).findFirst().orElse(new Live());
    }

    private void setHome(Config config, Live live, boolean save) {
        home = live;
        home.setSelected(true);
        config.setHome(home.getName());
        if (save) config.save();
        getLives().forEach(item -> item.setSelected(home));
        if (!save && (home.isBoot() || LiveSetting.isBoot())) ConfigEvent.boot();
    }

    private static class Loader {
        static volatile LiveConfig INSTANCE = new LiveConfig();
    }
}
