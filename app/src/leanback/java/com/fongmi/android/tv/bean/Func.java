package com.fongmi.android.tv.bean;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.utils.ResUtil;

public class Func implements Diffable<Func> {

    private final int resId;
    private int drawable;

    public static Func create(int resId) {
        return new Func(resId);
    }

    public Func(int resId) {
        this.resId = resId;
        this.setDrawable();
    }

    public int getResId() {
        return resId;
    }

    public int getDrawable() {
        return drawable;
    }

    public String getText() {
        return ResUtil.getString(resId);
    }

    public void setDrawable() {
        if (resId == R.string.home_live) this.drawable = R.drawable.ic_home_live;
        else if (resId == R.string.home_push) this.drawable = R.drawable.ic_home_push;
        else if (resId == R.string.home_keep) this.drawable = R.drawable.ic_home_keep;
        else if (resId == R.string.home_history_entry) this.drawable = R.drawable.ic_setting_history;
        else if (resId == R.string.home_search) this.drawable = R.drawable.ic_home_search;
        else if (resId == R.string.home_setting) this.drawable = R.drawable.ic_home_setting;
        // 「应用」（R101）：复用 ic_site_grid —— 既有 24dp 四宫格 vector，fillColor=@color/ag_text，
        // 与 adapter_func.xml 的 android:tint="@color/ag_text" 同色，与四个 PNG 图标视觉同族；
        // 「四宫格 = 全部应用 / 应用抽屉」也是通用认知，不必新建图标。
        else if (resId == R.string.home_apps) this.drawable = R.drawable.ic_site_grid;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Func it)) return false;
        return getResId() == it.getResId();
    }

    @Override
    public boolean isSameItem(Func other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(Func other) {
        return equals(other);
    }
}
