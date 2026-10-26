package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Class;

/**
 * 首页站源分类 chips（推荐 + 各分类）：焦点落入即加载对应内容，点按仍可强制走同一入口。
 * 首项为「推荐」（String），其余为站源分类（{@link Class}，含 typeId 用于 categoryContent）。
 * 选中态由 HomeActivity 通过 {@link #setSelected(Object)} 驱动，随 bind 刷新。
 *
 * <p><b>为什么用 setOnFocusChangeListener 而不是 leanback 的选中监听：</b>
 * chip 是 ListRow 内的普通电视焦点控件，{@code selector_home_chip} 的 {@code state_focused}
 * 分支已经生效（说明焦点事件可靠），用原生焦点回调最简单、且完全不碰现有焦点行为。
 * 防抖与去重由 HomeActivity 负责，本类只负责把「焦点落入哪一项」如实抛出去。</p>
 */
public class HomeChipPresenter extends Presenter {

    public interface OnClickListener {

        void onItemClick(Object item);
    }

    /** 焦点落入某个 chip（遥控器移到即加载）。与点击分开成两个单方法接口，两者都能用方法引用传入。 */
    public interface OnFocusListener {

        void onItemFocus(Object item);
    }

    private final OnClickListener listener;
    private final OnFocusListener focusListener;
    /** 当前选中的 chip（推荐为 String，分类为 Class 对象），用于选中高亮。 */
    private Object selected = "";

    public HomeChipPresenter(OnClickListener listener, OnFocusListener focusListener) {
        this.listener = listener;
        this.focusListener = focusListener;
    }

    public void setSelected(Object selected) {
        this.selected = selected == null ? "" : selected;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_home_chip, parent, false), listener, focusListener);
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        ((ViewHolder) viewHolder).bind(item, selected);
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
    }

    static class ViewHolder extends Presenter.ViewHolder implements View.OnClickListener, View.OnFocusChangeListener {

        private final OnClickListener listener;
        private final OnFocusListener focusListener;

        ViewHolder(View view, OnClickListener listener, OnFocusListener focusListener) {
            super(view);
            this.listener = listener;
            this.focusListener = focusListener;
            view.setOnClickListener(this);
            view.setOnFocusChangeListener(this);
        }

        void bind(Object item, Object selected) {
            String text = item instanceof Class type ? type.getTypeName() : String.valueOf(item);
            ((TextView) view).setText(text);
            view.setTag(item);
            view.setSelected(item.equals(selected));
        }

        @Override
        public void onClick(View v) {
            listener.onItemClick(v.getTag());
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            // view 会被复用，所以取事件发生时刻的 tag（bind 时写入），而不是构造时的快照
            if (hasFocus) focusListener.onItemFocus(v.getTag());
        }
    }
}
