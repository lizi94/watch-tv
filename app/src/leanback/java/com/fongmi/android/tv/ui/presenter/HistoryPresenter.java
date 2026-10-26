package com.fongmi.android.tv.ui.presenter;

import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.utils.CoverText;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.Objects;

public class HistoryPresenter extends Presenter {

    private final OnClickListener listener;
    private int width, height;
    private boolean delete;

    public HistoryPresenter(OnClickListener listener) {
        this.listener = listener;
        setLayoutSize();
    }

    public interface OnClickListener {

        void onItemClick(History item);

        void onItemDelete(History item);

        boolean onLongClick();
    }

    private void setLayoutSize() {
        // 与 Product.getSpec 同一套公式（3:4 竖版）。列数下限 1、可用宽度下限 1px：
        // 「极大字号」档把全局 density 放大到 2 倍、大屏补偿再乘最多 1.6 倍，行距占位里的
        // dp2px 项随之变大；屏宽读取异常（返回 0）时 base 会落到 0/负 —— 非法尺寸不能进 LayoutParams。
        int column = Math.max(1, Product.getColumn());
        int space = ResUtil.dp2px(48) + ResUtil.dp2px(16 * (column - 1));
        int base = ResUtil.getScreenWidth() - space;
        width = Math.max(1, base / column);
        height = Math.max(1, (int) (width / 0.75f));
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.delete = delete;
    }

    private void setClickListener(View root, History item) {
        root.setOnLongClickListener(view -> listener.onLongClick());
        root.setOnClickListener(view -> {
            if (isDelete()) listener.onItemDelete(item);
            else listener.onItemClick(item);
        });
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        ViewHolder holder = new ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        holder.binding.image.getLayoutParams().height = height;
        // 封面文字跟随封面宽度并叠加字号档位增益（CoverText）：档位越高字越大，图文比例恒定
        int sub = CoverText.sub(width);
        holder.binding.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, CoverText.title(width));
        holder.binding.site.setTextSize(TypedValue.COMPLEX_UNIT_PX, sub);
        holder.binding.remark.setTextSize(TypedValue.COMPLEX_UNIT_PX, sub);
        // 角标超长兜底：框在封面宽度内，不溢到相邻列（常规短角标不触发）
        CoverText.clamp(holder.binding.remark, holder.binding.site, width);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        History item = (History) object;
        // ⚠ 2026-10-16 审计修复（P1，会闪退）：getVodName() 无 null 兜底（Room 列 / 畸形备份导入可为 null），
        // 裸 .equals 抛 NPE。本方法在**布局期**执行（setItems→notify 后的下一次 layout），
        // 不在 EventBus 订阅方法内 ⇒ 异常不会被 EventBus 吞掉，直接闪退。
        // 与 History#isSameContent（同文件已用 Objects.equals）同口径。
        boolean same = Objects.equals(item.getVodName(), item.getVodRemarks());
        ViewHolder holder = (ViewHolder) viewHolder;
        setClickListener(holder.view, item);
        holder.binding.name.setText(item.getVodName());
        holder.binding.site.setText(item.getSiteName());
        holder.binding.remark.setText(item.getVodRemarks());
        holder.binding.site.setVisibility(item.getSiteVisible());
        holder.binding.delete.setVisibility(!delete ? View.GONE : View.VISIBLE);
        holder.binding.remark.setVisibility(delete || same ? View.GONE : View.VISIBLE);
        // 锁定解码尺寸到封面实际显示大小（详见 ImgUtil 带尺寸重载说明）
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image, true, width, height);
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ViewHolder holder = (ViewHolder) viewHolder;
        ImgUtil.clear(holder.binding.image);
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterVodBinding binding;

        public ViewHolder(@NonNull AdapterVodBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}