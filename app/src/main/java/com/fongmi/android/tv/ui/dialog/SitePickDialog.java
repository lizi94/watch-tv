package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogSitePickBinding;
import com.fongmi.android.tv.ui.adapter.SiteManageAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 「选择数据源」（2026-10-27 新增，586 初版 → 587 重构为 DialogFragment）。
 *
 * <p><b>为什么必须继承 {@link BaseAlertDialog}</b>：App 内其它弹窗全部是
 * {@code DialogFragment} + {@code MaterialAlertDialogBuilder} 这一套。初版直接
 * {@code create().show()} 裸弹窗，导致两个问题：① 外观与其它弹窗不同源；
 * ② <b>配置变更（切字号档位 / HDMI 重协商 / 改显示大小）时弹窗被系统重建后直接消失</b>，
 * 用户选源到一半就白选了。</p>
 *
 * <p><b>只列「已启用」的站源</b>（{@code VodConfig#getSites()}）：已停用的源在首页 / 搜索 / 换源里
 * 都看不到，能锁到它就是自相矛盾。全部停用时弹「没有可用的站源」，只给一个「知道了」。</p>
 *
 * <p><b>结果如何回传</b>：用 {@code setFragmentResult}（Fragment 官方推荐方式），
 * 而不是持有 Activity 引用 —— 后者在配置变更重建后会失效，点了没反应。</p>
 */
public class SitePickDialog extends BaseAlertDialog {

    /** FragmentResult 的请求键与数据键（双端一致）。 */
    public static final String RESULT_KEY = "site_pick";
    public static final String ARG_KEY = "key";

    private DialogSitePickBinding binding;
    private SiteManageAdapter adapter;
    /** 选择模式下标「使用中」的站源 key：锁定页传入当前锁定/生效源；空 = 不标。 */
    private String currentKey;
    private boolean empty;

    public static SitePickDialog create() {
        return new SitePickDialog();
    }

    /** 把某行标成「使用中」。走 persist 约定：弹窗被系统重建后标记不丢。 */
    public SitePickDialog current(String key) {
        currentKey = key == null ? "" : key;
        persist("currentKey", currentKey);
        return this;
    }

    @Override
    protected void restoreArguments() {
        currentKey = persistedString("currentKey", "");
    }

    /** 与 App 内其它弹窗一致的调用方式（{@code BaseAlertDialog} 本身没有这个重载，是各弹窗自带的便捷方法）。 */
    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSitePickBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        // ⚠ 空态在这里算：getBuilder() 在 onCreateDialog 里先于 initView() 执行，
        // 按钮是 builder 的一部分，必须在此之前决定加不加「知道了」。
        List<Site> sites = VodConfig.get().getSites();
        empty = sites.isEmpty();

        MaterialAlertDialogBuilder builder = builder()
                .setTitle(R.string.site_pick_title)
                .setView(getBinding().getRoot());
        // 点一行即选中并关闭：底部不放任何按钮（「取消」对触屏只是原样关闭，没有实际作用；
        // 关闭弹窗 = 系统返回手势 / 点击弹窗外区域，已是通用习惯）。
        // 只有空态（没有列表可点）才给一个「知道了」当出口。
        if (empty) {
            builder.setPositiveButton(R.string.dialog_positive, (dialog, which) -> dismiss());
        }
        return builder;
    }

    @Override
    protected void initView() {
        adapter = new SiteManageAdapter();
        adapter.setPickMode(true);
        adapter.setCurrentKey(currentKey);
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setAdapter(adapter);
        adapter.setItems(VodConfig.get().getSites());
        binding.empty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        // 列表高度不再在这里手算：交给布局里的 CustomRecyclerView + app:maxHeight，
        // 与「历史记录 / 收藏」弹窗同一套规则（内容少就收紧，超过上限才内部滚动）。
    }

    @Override
    protected void initEvent() {
        adapter.setOnPickListener(site -> {
            Bundle result = new Bundle();
            result.putString(ARG_KEY, site.getKey());
            getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
            dismiss();
        });
    }
}
