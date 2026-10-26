package com.fongmi.android.tv.bean;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.ui.base.ViewType;
import com.google.gson.annotations.SerializedName;

public class Style implements Parcelable {

    @SerializedName("type")
    private String type;
    @SerializedName("ratio")
    private float ratio;

    public Style(String type) {
        this.type = type;
    }

    public Style(String type, float ratio) {
        this.type = type;
        this.ratio = ratio;
    }

    protected Style(Parcel in) {
        this.type = in.readString();
        this.ratio = in.readFloat();
    }

    public static Style rect() {
        return new Style("rect", 0.75f);
    }

    public static Style list() {
        return new Style("list");
    }

    public static Style get(int land, int circle, float ratio) {
        if (land == 1) return new Style("rect", ratio == 0 ? 1.33f : ratio);
        if (circle == 1) return new Style("oval", ratio == 0 ? 1.0f : ratio);
        return null;
    }

    public String getType() {
        return TextUtils.isEmpty(type) ? "rect" : type;
    }

    /**
     * 封面比例归一化：无论源站声明什么值，一律吸附到三种规范比例之一，
     * 避免不同源的封面在同一列表里高低不齐 ——
     * <ul>
     *   <li>圆形 → 1:1；</li>
     *   <li>声明 &lt; 0.875（竖版海报及更瘦）→ 0.75，即 3:4；</li>
     *   <li>声明 0.875 ~ 1.125（近似方形）→ 1.0；</li>
     *   <li>声明 &gt; 1.125（横版剧照及更宽）→ 1.78，即 16:9。</li>
     * </ul>
     * 只影响「绘制高度」，{@link #isLand()}（&gt;1.0 判横版）与
     * {@code Product.getColumn()}（四档封面大小 = 列数）均不受影响。
     */
    public float getRatio() {
        if (isOval()) return 1.0f;
        if (ratio <= 0) return 0.75f;
        if (ratio < 0.875f) return 0.75f;
        if (ratio <= 1.125f) return 1.0f;
        return Math.min(1.78f, ratio);
    }

    public boolean isRect() {
        return "rect".equals(getType());
    }

    public boolean isOval() {
        return "oval".equals(getType());
    }

    public boolean isList() {
        return "list".equals(getType());
    }

    public boolean isLand() {
        return isRect() && getRatio() > 1.0f;
    }

    public int getViewType() {
        return switch (getType()) {
            case "oval" -> ViewType.OVAL;
            case "list" -> ViewType.LIST;
            default -> ViewType.RECT;
        };
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Style it)) return false;
        return getType().equals(it.getType()) && getRatio() == it.getRatio();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.type);
        dest.writeFloat(this.ratio);
    }

    public static final Creator<Style> CREATOR = new Creator<>() {
        @Override
        public Style createFromParcel(Parcel source) {
            return new Style(source);
        }

        @Override
        public Style[] newArray(int size) {
            return new Style[size];
        }
    };
}
