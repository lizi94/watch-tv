package com.fongmi.android.tv.bean;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.UrlUtil;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class Url {

    @SerializedName("values")
    private List<Value> values;
    @SerializedName("position")
    private int position;

    public static Url objectFrom(JsonElement element) {
        try {
            return App.gson().fromJson(element, Url.class);
        } catch (Exception e) {
            return create();
        }
    }

    public static Url create() {
        return new Url();
    }

    public List<Value> getValues() {
        return values = values == null ? new ArrayList<>() : values;
    }

    public int getPosition() {
        return position;
    }

    public Uri uri() {
        return UrlUtil.uri(v());
    }

    public String v() {
        return v(getPosition());
    }

    /**
     * 按下标取播放地址。
     *
     * <p>⚠ 下标可能为负：{@link #set(int)} 在 {@code values} 为空时会算出 {@code Math.min(0, -1) = -1}，
     * 而 {@code ArrayList.get(-1)} 抛 IndexOutOfBoundsException。站源返回空地址（VIP 集、失效集、
     * 或 {@code code != 0} 且无 msg）时必走这条路，且恰好落在「本该提示播放地址错误」的兜底分支上
     * ⇒ 用户看到的是点一下就闪退，而不是提示。故上下界都要判。
     */
    public String v(int position) {
        return position < 0 || position >= getValues().size() ? "" : getValues().get(position).getV();
    }

    public String n(int position) {
        return position < 0 || position >= getValues().size() ? "" : getValues().get(position).getN();
    }

    public Url add(String v) {
        getValues().add(Value.create(v));
        return this;
    }

    public Url add(String n, String v) {
        getValues().add(Value.create(n, v));
        return this;
    }

    public Url replace(String url) {
        // position 是 Gson 直接写字段进来的，可能为负或越界 ⇒ 越界时退化为追加一条。
        if (getValues().isEmpty() || getPosition() < 0 || getPosition() >= getValues().size()) add(url);
        else getValues().get(getPosition()).setV(url);
        return this;
    }

    public Url set(int position) {
        // values 为空时 size()-1 = -1，会把 position 钳成 -1（见 v(int) 的 javadoc）。
        if (getValues().isEmpty()) this.position = 0;
        else this.position = Math.max(0, Math.min(position, getValues().size() - 1));
        return this;
    }

    public boolean isEmpty() {
        return getValues().isEmpty() || TextUtils.isEmpty(v());
    }

    public boolean isMulti() {
        return getValues().size() > 1;
    }
}
