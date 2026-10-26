package com.fongmi.android.tv.ui.presenter;

import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.databinding.AdapterFuncBinding;
import com.fongmi.android.tv.utils.ResUtil;

public class FuncPresenter extends Presenter {

    private final OnClickListener listener;

    public FuncPresenter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {
        void onItemClick(Func item);
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        ViewHolder holder = new ViewHolder(AdapterFuncBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        ViewGroup.LayoutParams lp = holder.binding.getRoot().getLayoutParams();
        // 宽度铺满整行：屏宽 − 左右边距(24×2) − 4×间距(16)，均分 5 个按钮 —— 任何封面/字号档位恒定铺满不溢出。
        // 这里的 dp→px 与下面的 height 一样，取「本页 Resources」的密度：全局 ResUtil 的无参重载走
        // Application 侧 Resources，历史上会被 AndroidAutoSize 覆写成「屏宽/1920」（不含字号档位与
        // 大屏补偿），导致首屏按钮按错误密度算宽度、与行间距不同源（重建一次才恢复）。
        DisplayMetrics metrics = ResUtil.getDisplayMetrics(parent.getContext());
        int spacing = Math.round(16 * metrics.density);
        // ⚠ 2026-10-16 加固：加下限保护 —— 极大字号档位（density 可到数倍）下
        // `屏宽 − 边距 − 4×间距` 理论上可能被压到 0/负，负宽传入 getChildMeasureSpec
        // 属未定义行为（此处虽不抛异常，但会写出非法宽度）；与 Product.getSpec 的
        // `Math.max(1, …)` 同口径，正常档位下与原值完全一致。
        lp.width = Math.max(1, (ResUtil.getScreenWidth() - Math.round(48 * metrics.density) - spacing * 4) / 5);
        // 高度：120dp 语义，用 Activity 密度（随字号档位等比放大），大字档位下图标+文字不溢出
        lp.height = Math.round(120f * metrics.density);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        Func item = (Func) object;
        ViewHolder holder = (ViewHolder) viewHolder;
        holder.binding.text.setText(item.getText());
        holder.binding.icon.setImageResource(item.getDrawable());
        setOnClickListener(holder, view -> listener.onItemClick(item));
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterFuncBinding binding;

        public ViewHolder(@NonNull AdapterFuncBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}