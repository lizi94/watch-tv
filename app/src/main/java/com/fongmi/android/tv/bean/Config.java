package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.db.AppDatabase;
import com.github.catvod.utils.Prefers;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

@Entity(indices = @Index(value = {"url", "type"}, unique = true))
public class Config {

    @PrimaryKey(autoGenerate = true)
    @SerializedName("id")
    private int id;
    @SerializedName("type")
    private int type;
    @SerializedName("time")
    private long time;
    @SerializedName("url")
    private String url;
    @SerializedName("json")
    private String json;
    @SerializedName("name")
    private String name;
    @SerializedName("logo")
    private String logo;
    @SerializedName("home")
    private String home;
    @SerializedName("parse")
    private String parse;
    // 启停标记（true=启用）：不加 @SerializedName，Gson 序列化/推送协议不受影响。
    // 历史配置弹窗用它做「停用=软删除」；停用的配置不进入直播页切换器等只读场景。
    private boolean checked = true;

    @Ignore
    @SerializedName("notice")
    private String notice;
    @Ignore
    @SerializedName("danmaku")
    private String danmaku;
    @Ignore
    @SerializedName("assrt")
    private String assrt;

    public static List<Config> arrayFrom(String str) {
        Type listType = TypeToken.getParameterized(List.class, Config.class).getType();
        List<Config> items = App.gson().fromJson(str, listType);
        return items == null ? Collections.emptyList() : items;
    }

    public static Config objectFrom(String str) {
        return App.gson().fromJson(str, Config.class);
    }

    public static Config create(int type) {
        return new Config().type(type);
    }

    public static Config create(int type, String url) {
        return new Config().type(type).url(url).insert();
    }

    public static Config create(int type, String url, String name) {
        return new Config().type(type).url(url).name(name).insert();
    }

    public static List<Config> getAll(int type) {
        return AppDatabase.get().getConfigDao().findByType(type);
    }

    public static List<Config> findUrls() {
        return AppDatabase.get().getConfigDao().findUrlByType(0);
    }

    public static void delete(String url) {
        AppDatabase.get().getConfigDao().delete(url);
    }

    public static void delete(String url, int type) {
        AppDatabase.get().getConfigDao().delete(url, type);
    }

    public static Config vod() {
        Config item = AppDatabase.get().getConfigDao().findOne(0);
        return item == null ? create(0) : item;
    }

    /**
     * 取最近一条指定 type 的配置，<b>找不到返回 {@code null}、绝不自动创建空壳</b>。
     *
     * <p>{@link #vod()} / {@link #live()} / {@link #wall()} 在查不到时都会 {@code create} 一个
     * 空 {@code Config}，对「是否存在」的判断是错的（空壳与「有记录但字段为空」无法区分）。
     * {@code VodConfig.loadFromCache()} 要判「本地到底有没有缓存 JSON」必须用本方法。</p>
     */
    public static Config findOne(int type) {
        return AppDatabase.get().getConfigDao().findOne(type);
    }

    public static Config live() {
        Config item = AppDatabase.get().getConfigDao().findOne(1);
        return item == null ? create(1) : item;
    }

    public static Config wall() {
        Config item = AppDatabase.get().getConfigDao().findOne(2);
        return item == null ? create(2) : item;
    }

    public static Config find(int id) {
        return AppDatabase.get().getConfigDao().findById(id);
    }

    public static Config find(String url, int type) {
        Config item = AppDatabase.get().getConfigDao().find(url, type);
        return item == null ? create(type, url) : item.type(type);
    }

    public static Config find(String url, String name, int type) {
        Config item = AppDatabase.get().getConfigDao().find(url, type);
        return item == null ? create(type, url, name) : item.type(type).name(name);
    }

    /**
     * 按 Config 查库（查不到就按它的 url 建一条）。
     *
     * <p>⚠ 入参可能为 null：{@link #objectFrom(String)} 在参数为 null / 空串 / 非 JSON 时
     * 由 Gson 直接返回 null，而局域网 HTTP 接口（{@code /action?do=cast}、{@code do=sync}）
     * 的 {@code config} 字段是外部请求带进来的，缺参即 null ⇒ 原实现 NPE。
     * 该接口跑在 NanoHTTPD 工作线程上，异常会冒到全局兜底 ⇒ 用户观感是闪退。
     */
    public static Config find(Config config) {
        if (config == null) return Config.vod();
        return find(config, config.getType());
    }

    public static Config find(Config config, int type) {
        Config item = AppDatabase.get().getConfigDao().find(config.getUrl(), type);
        return item == null ? create(type, config.getUrl(), config.getName()) : item.type(type).name(config.getName());
    }

    public static Config find(Depot depot, int type) {
        Config item = AppDatabase.get().getConfigDao().find(depot.getUrl(), type);
        return item == null ? create(type, depot.getUrl(), depot.getName()) : item.type(type).name(depot.getName());
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getJson() {
        return json;
    }

    public void setJson(String json) {
        this.json = json;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLogo() {
        return logo;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getHome() {
        return home;
    }

    public void setHome(String home) {
        this.home = home;
    }

    public String getParse() {
        return parse;
    }

    public void setParse(String parse) {
        this.parse = parse;
    }

    public boolean isChecked() {
        return checked;
    }

    /** Room 实体要求的标准 setter（字段 checked）。 */
    public void setChecked(boolean value) {
        checked = value;
    }

    public Config putChecked(boolean value) {
        checked = value;
        return this;
    }

    /** 启停切换：只在内存里翻转，调用方自行 save() 落库。 */
    public Config toggleChecked() {
        return putChecked(!checked);
    }

    /**
     * 是否处于「停用」态。
     *
     * <p>停用 = 软删除（保留历史记录但不再可用）。停用项<b>不可被选用</b>：
     * 它的地址可能已失效，选用会直接加载失败并报错。</p>
     */
    public boolean isDisabled() {
        return !checked;
    }

    /**
     * 是否当前「使用中」：{@link #findOne(int)}（时间最新一条）与自己同 id。
     *
     * <p>与 {@code ConfigAdapter} 的「使用中」标注同源（同一判定口径），
     * 保证「标注」「能不能停用」「能不能选用」三处不会打架。</p>
     */
    public boolean isInUse() {
        Config used = findOne(getType());
        return used != null && used.getId() == getId();
    }

    /**
     * 逻辑层不变量：<b>使用中的配置不允许停用</b>。
     *
     * <p>放在 bean 层而不是只靠 UI 隐藏按钮 —— 任何调用方都绕不过，
     * 不会出现「停用了正在用的配置 ⇒ 点不动、也换不掉」的僵死状态。</p>
     *
     * @return true = 允许停用
     */
    public boolean canDisable() {
        return !isInUse();
    }

    /**
     * 重新选用：停用过的记录恢复为启用并落库；本来就是启用状态时不动数据库。
     *
     * <p>用于「用户手动添加 / 编辑配置」这种<b>明确意图</b>的入口
     * （{@code ConfigDialog}）：用户就是要用这个地址，不该被历史的停用状态拦住。
     * 与「在历史列表里点一条停用项」不同 —— 那种是误触，必须拦（见 ConfigAdapter）。</p>
     */
    public Config enable() {
        if (checked) return this;
        putChecked(true);
        save();
        return this;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public String getNotice() {
        return notice;
    }

    public void setNotice(String notice) {
        this.notice = notice;
    }

    public String getDanmaku() {
        return danmaku;
    }

    public void setDanmaku(String danmaku) {
        this.danmaku = danmaku;
    }

    public String getAssrt() {
        return assrt;
    }

    public void setAssrt(String assrt) {
        this.assrt = assrt;
    }

    public Config type(int type) {
        setType(type);
        return this;
    }

    public Config url(String url) {
        setUrl(url);
        return this;
    }

    public Config json(String json) {
        setJson(json);
        return this;
    }

    public Config name(String name) {
        setName(name);
        return this;
    }

    public boolean isEmpty() {
        return TextUtils.isEmpty(getUrl());
    }

    public String getDesc() {
        if (!TextUtils.isEmpty(getName())) return getName();
        if (!TextUtils.isEmpty(getUrl())) return getUrl();
        return "";
    }

    public Config insert() {
        if (isEmpty()) return this;
        setId(Math.toIntExact(AppDatabase.get().getConfigDao().insert(this)));
        return this;
    }

    public Config save() {
        if (isEmpty()) return this;
        AppDatabase.get().getConfigDao().insertOrUpdate(this);
        return this;
    }

    public Config update() {
        if (isEmpty()) return this;
        setTime(System.currentTimeMillis());
        Prefers.put("config_" + getType(), getUrl());
        return save();
    }

    public void delete() {
        AppDatabase.get().getConfigDao().delete(getUrl(), getType());
        History.delete(getId());
        Keep.delete(getId());
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Config it)) return false;
        return getId() == it.getId();
    }
}
