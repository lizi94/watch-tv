package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.databinding.AdapterEmptyCardBinding;
import com.fongmi.android.tv.utils.ResUtil;

/**
 * 首页空态卡 presenter：作为首页内容流中的一行（非 ListRow），
 * 宽撑满内容区，高固定为屏幕可视高度的 {@link #CARD_HEIGHT_RATIO}（不延伸屏底），
 * 随内容流自然滚动（占位效果，不漂移）。内容在卡片内水平+垂直居中。
 *
 * <h3>2026-10-17：去掉「重新加载」按钮，只留指路文案</h3>
 * <p>按真实使用场景核对后确认该按钮是无效控件：</p>
 * <ul>
 *   <li><b>未配置 / 未导入数据源</b>（电视端只会出现在首次安装或手动清空之后）：
 *       此时重试仍然没有数据源可拉，必然失败 —— 文案改为直接指路「去 设置 → 数据源 导入」。</li>
 *   <li><b>有数据源但连不上</b>：网络恢复会自动重载（{@code HomeActivity#getCallback().error}
 *       里补挂 {@code awaitConfigNetwork}），从设置页返回也会刷新 —— 手动重试是冗余。</li>
 *   <li>对老人而言，<b>按了没反应的按钮比没有按钮更糟</b>：会反复按、以为软件坏了。</li>
 * </ul>
 * <p>因此卡片不再可聚焦、可点击（没有可执行动作就不该抢焦点），也不再持有回调；
 * 文案由 {@link Item} 带入（未导入数据源 / 暂时连接不上 / 该分类暂无数据 三种）。</p>
 *
 * <p><b>2026-10-15 起就已取消「焦点自动落到按钮上」</b>；现在连按钮一起移除，
 * 空态卡变成纯展示行，焦点只在 功能区 ⇄ 分类 ⇄ 内容区 之间流转。</p>
 */
public class EmptyCardPresenter extends Presenter {

    /**
     * 空态卡在首页 adapter 中的标记项（供 selector 按类分发，与 "progress" 这类 String 标记同构）。
     *
     * @param textRes 文案：一句话说清「现在是什么情况」以及**该怎么办**
     *                （未导入数据源 → 指路去设置；暂时连不上 → 说明会自动重试；该分类没内容 → 直说）。
     */
    public static final class Item {

        private final int textRes;

        public Item(int textRes) {
            this.textRes = textRes;
        }

        /** 纯文案空态（现在唯一的形态）：卡片不可聚焦、不可点，只负责把「情况 + 该怎么办」说清楚。 */
        public static Item plain(int textRes) {
            return new Item(textRes);
        }
    }

    /** 卡片高度占屏幕可视高度的比例：固定比例、不随字号档位变化，任何屏幕都自适应且不会顶到屏底。 */
    private static final float CARD_HEIGHT_RATIO = 0.55f;

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        AdapterEmptyCardBinding binding = AdapterEmptyCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        ViewGroup.LayoutParams lp = binding.getRoot().getLayoutParams();
        if (lp == null) {
            lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            binding.getRoot().setLayoutParams(lp);
        }
        lp.height = Math.round(ResUtil.getScreenHeight(parent.getContext()) * CARD_HEIGHT_RATIO);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        ViewHolder holder = (ViewHolder) viewHolder;
        if (object instanceof Item item) holder.bind(item);
    }

    /**
     * {@code Presenter#onUnbindViewHolder} 是抽象方法，必须覆写。
     *
     * <p>这里<b>刻意留空</b>：空态卡是纯展示行（不可聚焦、不可点击、没有监听器），
     * 解绑时没有任何「上一个 Item 的残留状态」需要清理 —— 与改造前那个「必须清掉点击与焦点」的
     * 版本不同（那时卡片可点，复用后会带着上一张卡的回调）。</p>
     */
    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterEmptyCardBinding binding;

        public ViewHolder(@NonNull AdapterEmptyCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Item item) {
            binding.text.setText(item.textRes);
        }
    }
}
