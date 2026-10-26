package com.fongmi.android.tv.ui.holder;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterVodOvalBinding;
import com.fongmi.android.tv.ui.adapter.VodAdapter;
import com.fongmi.android.tv.ui.base.BaseVodHolder;
import com.fongmi.android.tv.utils.ImgUtil;

public class VodOvalHolder extends BaseVodHolder {

    private final VodAdapter.OnClickListener listener;
    private final AdapterVodOvalBinding binding;
    /**
     * 本 holder 的封面显示尺寸（px）：由 {@link #size(int[])} 写入，绑定期传给
     * {@link ImgUtil#load(String, String, android.widget.ImageView, boolean, int, int)} 锁定解码尺寸。
     * 与电视版同名 holder 同一套做法；0 表示「未指定」，Glide 会退回按视图尺寸决定。
     */
    private int coverWidth;
    private int coverHeight;

    public VodOvalHolder(@NonNull AdapterVodOvalBinding binding, VodAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
    }

    public VodOvalHolder size(int[] size) {
        coverWidth = size[0];
        coverHeight = size[1];
        binding.image.getLayoutParams().width = size[0];
        binding.image.getLayoutParams().height = size[1];
        return this;
    }

    @Override
    public void initView(Vod item) {
        binding.name.setText(item.getName());
        binding.name.setVisibility(item.getNameVisible());
        binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
        binding.getRoot().setOnLongClickListener(v -> listener.onLongClick(item));
        // 锁定解码尺寸到封面实际显示大小（详见 ImgUtil#load 六参重载的说明）
        ImgUtil.load(item.getName(), item.getPic(), binding.image, true, coverWidth, coverHeight);
    }

    @Override
    public void unbind() {
        Glide.with(binding.image).clear(binding.image);
    }
}
