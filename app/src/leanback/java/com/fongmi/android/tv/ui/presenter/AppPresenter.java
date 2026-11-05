package com.fongmi.android.tv.ui.presenter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.bean.AppInfo;
import com.fongmi.android.tv.databinding.AdapterAppBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用列表的网格适配器（R101）：一个格子 = 图标 + 名称。
 *
 * <p><b>为什么是普通 {@link RecyclerView.Adapter} 而不是 leanback {@code Presenter}</b>：
 * 本页是普通 {@code RecyclerView + GridLayoutManager}（与收藏页 / 历史页同构），
 * leanback 的 Presenter 需要 {@code PresenterAdapter} 再包一层，白多一层间接。
 * 首页那种 {@code VerticalGridView + ListRow} 结构才必须用 Presenter。</p>
 *
 * <p><b>尺寸每次都算，不缓存到字段</b>：本页会随「图片尺寸」「字号档位」重建（列数随档位变），
 * 若把 cellWidth / iconSize 缓存成字段，就会出现「改了档位但格子大小没变」
 * —— 这是 {@code KeepAdapter} / {@code VodPresenter} 都记过的同款坑。</p>
 *
 * <p><b>⛔ 图标不进 {@link AppInfo}</b>：查询阶段一次性读近百个 {@code getApplicationIcon}
 * 会把这台 982MB 的机器直接压到抖动。这里按需读 + {@link #ICONS} 兜住来回滚动。
 * 也不走 Glide —— 应用图标已经是适配好的 mipmap，二次解码纯属浪费，还会引入异步回填错位。</p>
 */
public class AppPresenter extends RecyclerView.Adapter<AppPresenter.ViewHolder> {

    /**
     * 列数夹取范围（R101）：跟随「图片尺寸」档，但夹在 4~6。
     * 极大档下 8 列会让图标缩成邮票、名称必然被截断；而应用名比影片名更需要完整的横向空间。
     */
    public static final int MIN_COLUMN = 4;
    public static final int MAX_COLUMN = 6;

    /** 网格左右内边距的 dp 值 —— 必须与 activity_app_list.xml 外层 LinearLayoutCompat 的 padding 一致。 */
    private static final int PAD_DP = 24;

    /** 卡片内边距（ag_space_3 = 12dp），与 adapter_app.xml 的 android:padding 一致。 */
    private static final int INSET_DP = 12;

    /** 图标缓存（约 40 项）：一屏最多 6×4 = 24 格，40 足够覆盖一次滚动往返。 */
    private static final LruCache<String, Drawable> ICONS = new LruCache<>(40);

    public interface OnClickListener {

        void onItemClick(AppInfo item);

        void onItemLongClick(AppInfo item);
    }

    private final OnClickListener listener;
    private final List<AppInfo> items = new ArrayList<>();

    public AppPresenter(OnClickListener listener) {
        this.listener = listener;
        setHasStableIds(false);
    }

    /** 本页固定使用的列数（夹取后）。RecyclerView 的 span 与格宽公式必须用**同一个值**，否则行尾空半格。 */
    public static int column() {
        return Math.max(MIN_COLUMN, Math.min(MAX_COLUMN, Product.getColumn()));
    }

    public void setItems(List<AppInfo> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public List<AppInfo> getItems() {
        return items;
    }

    public AppInfo getItem(int position) {
        return position < 0 || position >= items.size() ? null : items.get(position);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ViewHolder holder = new ViewHolder(AdapterAppBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        Context context = parent.getContext();
        DisplayMetrics metrics = ResUtil.getDisplayMetrics(context);
        int span = column();
        int pad = Math.round(PAD_DP * metrics.density);
        int gap = Product.gap(context);
        // 可用宽度 = 屏宽 − 左右 24dp×2；再减 (span−1) 个列间距，均分给 span 列。
        // ⚠ 必须用 Activity 侧 metrics（ResUtil.getDisplayMetrics(context)）：无参 ResUtil.* 落在
        //   Application 侧 —— 不随字号档位缩放，orientation 判定还可能取短边（★C 记过的坑）。
        int avail = Math.max(1, metrics.widthPixels - pad * 2);
        int cell = Math.max(1, (avail - gap * (span - 1)) / span);
        int inset = Math.round(INSET_DP * metrics.density);
        int icon = Math.max(1, cell - inset * 2);
        holder.binding.getRoot().getLayoutParams().width = cell;
        ViewGroup.LayoutParams iconParams = holder.binding.icon.getLayoutParams();
        iconParams.width = icon;
        iconParams.height = icon;
        // 名称宽度跟随格子（卡片内边距已扣），超出即 ellipsize=end，不溢到相邻列
        holder.binding.text.getLayoutParams().width = Math.max(1, cell - inset * 2);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo item = getItem(position);
        if (item == null) return;
        holder.binding.text.setText(item.getLabel());
        holder.binding.icon.setImageDrawable(icon(holder.binding.icon.getContext(), item));
        holder.binding.getRoot().setOnClickListener(view -> listener.onItemClick(item));
        holder.binding.getRoot().setOnLongClickListener(view -> {
            listener.onItemLongClick(item);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** 取图标（带缓存）。取不到时返回 null —— 空格子比崩溃好。 */
    private Drawable icon(Context context, AppInfo item) {
        Drawable cached = ICONS.get(item.getPkg());
        if (cached != null) return cached;
        try {
            Drawable drawable = context.getPackageManager().getApplicationIcon(item.getPkg());
            if (drawable != null) ICONS.put(item.getPkg(), drawable);
            return drawable;
        } catch (Throwable t) {
            return null;
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterAppBinding binding;

        public ViewHolder(@NonNull AdapterAppBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
