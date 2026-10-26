package com.fongmi.android.tv.ui.presenter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterVodListBinding;
import com.fongmi.android.tv.databinding.AdapterVodOvalBinding;
import com.fongmi.android.tv.databinding.AdapterVodRectBinding;
import com.fongmi.android.tv.ui.base.BaseVodHolder;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.holder.VodListHolder;
import com.fongmi.android.tv.ui.holder.VodOvalHolder;
import com.fongmi.android.tv.ui.holder.VodRectHolder;

public class VodPresenter extends Presenter {

    private final OnClickListener listener;
    private final Style style;
    private final int[] size;

    /**
     * @param context 用于算封面尺寸的 <b>Activity 侧</b> Resources 上下文（见 {@link Product#getSpec}）。
     *                必须传页面本身而不是 {@code App.get()}，否则边距与列间距会与功能区错开。
     */
    public VodPresenter(Context context, OnClickListener listener) {
        this(context, listener, Style.rect());
    }

    /**
     * @param context 用于算封面尺寸的 <b>Activity 侧</b> Resources 上下文（见 {@link Product#getSpec}）。
     *                必须传页面本身而不是 {@code App.get()}，否则边距与列间距会与功能区错开。
     */
    public VodPresenter(Context context, OnClickListener listener, Style style) {
        this.listener = listener;
        this.style = style;
        this.size = Product.getSpec(context, style);
    }

    public interface OnClickListener {

        void onItemClick(Vod item);

        boolean onLongClick(Vod item);
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        return switch (style.getViewType()) {
            case ViewType.LIST -> new VodListHolder(AdapterVodListBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
            case ViewType.OVAL -> new VodOvalHolder(AdapterVodOvalBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener).size(size);
            default -> new VodRectHolder(AdapterVodRectBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener).size(size);
        };
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        ((BaseVodHolder) viewHolder).initView((Vod) object);
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ((BaseVodHolder) viewHolder).unbind();
    }
}