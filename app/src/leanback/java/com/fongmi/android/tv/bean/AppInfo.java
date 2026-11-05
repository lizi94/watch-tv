package com.fongmi.android.tv.bean;

import android.content.ComponentName;

/**
 * 应用列表里的一行（R101）。<b>只装纯数据，不装 Drawable</b> —— 图标一律在
 * {@code AppPresenter#onBindViewHolder} 里按需加载并走 LruCache，理由见 {@code AppQuery} 注释。
 */
public class AppInfo {

    private final String label;
    private final String pkg;
    private final ComponentName component;
    private final boolean system;
    /** 由 {@code Setting} 里的置顶顺序决定的展示位次；0 = 未置顶。 */
    private int pinned;

    public AppInfo(String label, String pkg, ComponentName component, boolean system) {
        this.label = label;
        this.pkg = pkg;
        this.component = component;
        this.system = system;
    }

    public String getLabel() {
        return label;
    }

    public String getPkg() {
        return pkg;
    }

    public ComponentName getComponent() {
        return component;
    }

    public boolean isSystem() {
        return system;
    }

    public int getPinned() {
        return pinned;
    }

    public void setPinned(int pinned) {
        this.pinned = pinned;
    }

    public boolean isPinned() {
        return pinned > 0;
    }

    /** 去重键：同一个包可能有多个入口 Activity，列表里只留一个。 */
    public String key() {
        return pkg;
    }
}
