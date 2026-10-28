package com.fongmi.android.tv.ui.presenter;

import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.utils.CoverText;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.Objects;

public class HistoryPresenter extends Presenter {

    private final OnClickListener listener;
    /** 封面尺寸（px，[宽, 高]）。null = 尚未计算，见 {@link #spec(ViewGroup)}。 */
    private int[] spec;
    private boolean delete;

    public HistoryPresenter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(History item);

        void onItemDelete(History item);

        boolean onLongClick();
    }

    /**
     * 封面尺寸（px）：<b>直接复用 {@link Product#getSpec}</b>，与首页分类 / 搜索结果逐像素同源。
     *
     * <p>⛔ 原先这里是手写的一份公式，行距占位用 {@code ResUtil.dp2px(48)} —— 那走的是
     * {@code App.get()} 的 Resources，即 <b>Application 侧 density</b>，<b>不随「字号档位」与大屏补偿缩放</b>；
     * 而功能性行距（{@code CustomRowPresenter}）与分类/搜索封面早就改用 Activity 侧 density
     * （{@code BaseActivity#hackResources()} 按「屏宽/1920 × 字号倍率 × 大屏补偿」缩放过）。
     * 于是档位越高，本行的左右边距相对越被压缩 —— 封面比分类/搜索那行更宽、更靠左，
     * 与 {@code adapter_vod_rect} 的卡片对不齐（同一个「两个 density 域」坑的残留副本）。</p>
     *
     * <p>尺寸只依赖 {@code parent.getContext()}（Activity 侧），故首次创建 ViewHolder 时算一次并缓存；
     * 字号档位变化时调用方会重建 presenter（{@code getHistory(true)}），缓存随之失效。</p>
     */
    private int[] spec(ViewGroup parent) {
        if (spec == null) spec = Product.getSpec(parent.getContext(), Style.rect());
        return spec;
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
        return new ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false)).size(spec(parent));
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
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image, true, holder.width, holder.height);
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ViewHolder holder = (ViewHolder) viewHolder;
        ImgUtil.clear(holder.binding.image);
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterVodBinding binding;
        /** 本 holder 的封面显示尺寸（px）：尺寸由 presenter 统一算，holder 负责落地并在绑定期回读。 */
        private int width, height;

        public ViewHolder(@NonNull AdapterVodBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public ViewHolder size(int[] size) {
            width = size[0];
            height = size[1];
            binding.getRoot().getLayoutParams().width = width;
            binding.image.getLayoutParams().height = height;
            // 封面文字跟随封面宽度并叠加字号档位增益（CoverText）：档位越高字越大，图文比例恒定
            int sub = CoverText.sub(width);
            binding.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, CoverText.title(width));
            binding.site.setTextSize(TypedValue.COMPLEX_UNIT_PX, sub);
            binding.remark.setTextSize(TypedValue.COMPLEX_UNIT_PX, sub);
            // year 在历史卡片恒 GONE，仍一并设定：与 VodRectHolder 保持同一套字号来源，
            // 避免以后某处误让它可见时字号来源分叉
            binding.year.setTextSize(TypedValue.COMPLEX_UNIT_PX, sub);
            // 角标超长兜底：框在封面宽度内，不溢到相邻列（常规短角标不触发）
            CoverText.clamp(binding.remark, binding.site, width);
            return this;
        }
    }
}