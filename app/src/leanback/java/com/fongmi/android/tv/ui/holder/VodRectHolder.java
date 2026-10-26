package com.fongmi.android.tv.ui.holder;

import android.util.TypedValue;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterVodRectBinding;
import com.fongmi.android.tv.ui.base.BaseVodHolder;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.CoverText;
import com.fongmi.android.tv.utils.ImgUtil;

public class VodRectHolder extends BaseVodHolder {

    private final VodPresenter.OnClickListener listener;
    private final AdapterVodRectBinding binding;
    /** 本 holder 的封面显示尺寸（px），由 {@link #size(int[])} 写入，供绑定期锁定解码尺寸用。 */
    private int coverWidth;
    private int coverHeight;

    public VodRectHolder(@NonNull AdapterVodRectBinding binding, VodPresenter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
    }

    public VodRectHolder size(int[] size) {
        coverWidth = size[0];
        coverHeight = size[1];
        binding.image.getLayoutParams().height = size[1];
        binding.getRoot().getLayoutParams().width = size[0];
        // 封面文字跟随封面宽度，并叠加字号档位增益：档位越高字越大，且图文比例恒定不截断。
        // 全部走 CoverText —— 首页 / 分类页 / 搜索结果（CollectFragment 用同一个 holder）一处生效、全站同步。
        int sub = CoverText.sub(size[0]);
        binding.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, CoverText.title(size[0]));
        binding.remark.setTextSize(TypedValue.COMPLEX_UNIT_PX, sub);
        binding.year.setTextSize(TypedValue.COMPLEX_UNIT_PX, sub);
        binding.site.setTextSize(TypedValue.COMPLEX_UNIT_PX, sub);
        // 角标超长兜底：框在封面宽度内，不溢到相邻列（常规短角标不触发）
        CoverText.clamp(binding.remark, binding.site, size[0]);
        return this;
    }

    @Override
    public void initView(Vod item) {
        binding.name.setText(item.getName());
        binding.year.setText(item.getYear());
        binding.site.setText(item.getSiteName());
        binding.remark.setText(item.getRemarks());
        binding.site.setVisibility(item.getSiteVisible());
        binding.year.setVisibility(item.getYearVisible());
        binding.name.setVisibility(item.getNameVisible());
        binding.remark.setVisibility(item.getRemarkVisible());
        binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
        binding.getRoot().setOnLongClickListener(v -> listener.onLongClick(item));
        // 锁定解码尺寸到封面实际显示大小：避免首帧布局未完成时按原图解码，
        // 「极大字号 + 图片尺寸大」叠加时不会出现瞬时双份大位图（详见 ImgUtil 重载说明）。
        ImgUtil.load(item.getName(), item.getPic(), binding.image, true, coverWidth, coverHeight);
    }

    @Override
    public void unbind() {
        ImgUtil.clear(binding.image);
    }
}
