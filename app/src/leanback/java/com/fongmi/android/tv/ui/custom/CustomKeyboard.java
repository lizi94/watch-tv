package com.fongmi.android.tv.ui.custom;

import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySearchBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.KeyboardAdapter;
import com.fongmi.android.tv.utils.ResUtil;

public class CustomKeyboard implements KeyboardAdapter.OnClickListener {

    /** 与布局 `app:spanCount="7"` 保持一致。 */
    private static final int SPAN_COUNT = 7;
    /**
     * 单键边长的「字号档位曲线」（dp，对应档位 0..4）。
     *
     * <p>density 已含「字号倍率 × 大屏补偿」，所以曲线的 dp 值随档位递减，
     * 让键的<b>物理像素边长</b>稳定在约 110~125px：既比原来的固定 80dp（96px）大，
     * 又保证 7 行在最大档位下仍能完整显示 —— 原来固定 80dp 时键盘块 48+6×80
     * 在档位 2 起就会顶出屏幕，末行被裁掉。</p>
     *
     * <p>档位 → dp × 倍率 ≈ 物理边长：档0 104×1.2≈125px、档1 88×1.4≈123px、
     * 档2 78×1.6≈125px、档3 68×1.8≈122px、档4 60×2.0≈120px（空间不足时统一按比例再缩）。
     * 键上的字母仍是 sp，照样随档位线性放大，「字更大」的诉求不受影响。</p>
     */
    private static final int[] KEY_CURVE_DP = {104, 88, 78, 68, 60};
    /** 单键最小边长（dp）兜底，正常不会生效。 */
    private static final int MIN_KEY_DP = 56;
    /** 键间横/纵向间距与键边长的比例（= 原 8dp / 104dp）：间距与键同比，整块键盘缩放时比例恒定。 */
    private static final float SPACING_RATIO = 8f / 104f;
    /** 图标键里图标边长与键边长的比例（= 原 40dp / 104dp）：图标也必须与键同比，否则键变小时图标会撑满整键。 */
    private static final float GLYPH_RATIO = 40f / 104f;
    /** 屏幕高度里不属于键盘的部分：页上下 padding(24dp×2) + 顶部一行(@id/keyword 固定 56dp) + 键盘上边距(16dp)。
     *  顶部一行原由 56dp 的麦克风图标撑起，语音功能移除后改由 @id/keyword 自己声明 56dp，行高与预留值保持不变。 */
    private static final int RESERVE_DP = 24 * 2 + 56 + 16;
    /** 键盘左侧列最宽占屏宽的比例，给右侧热词/历史留位置。 */
    private static final float COLUMN_WIDTH_RATIO = 0.5f;

    private final ActivitySearchBinding binding;
    private final Callback callback;
    private KeyboardAdapter adapter;
    private SpaceItemDecoration decoration;

    public static void init(Callback callback, ActivitySearchBinding binding) {
        new CustomKeyboard(callback, binding).initView();
    }

    public CustomKeyboard(Callback callback, ActivitySearchBinding binding) {
        this.callback = callback;
        this.binding = binding;
    }

    private void initView() {
        binding.keyboard.setItemAnimator(null);
        binding.keyboard.setHasFixedSize(false);
        binding.keyboard.addItemDecoration(decoration = new SpaceItemDecoration(SPAN_COUNT, 0));
        binding.keyboard.setAdapter(adapter = new KeyboardAdapter(this));
        // 首帧先用屏幕高度估算，避免第一帧按布局默认尺寸闪一下；布局跑完再用真实高度精确校正
        applyKeySize(availFromScreen());
        binding.keyboard.post(() -> applyKeySize(availFromLayout()));
        // 输出模式变化（1080p↔4K）不会重建本页，尺寸变了要重算一次曲线
        binding.keyboard.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot || r - l != or - ol) applyKeySize(availFromLayout());
        });
    }

    /**
     * 取 View 所在 Resources 的 density。
     * 用 View 自己的 Resources 而不是全局工具类，确保拿到的就是
     * BaseActivity.hackResources 按「字号档位 × 大屏补偿」改过的那一份。
     */
    private float density() {
        return binding.keyboard.getResources().getDisplayMetrics().density;
    }

    private int px(float dp) {
        return Math.round(dp * density());
    }

    private int tier() {
        return Math.max(0, Math.min(KEY_CURVE_DP.length - 1, Setting.getFontSize()));
    }

    /** 布局未完成时的估算：可用高度 ≈ 屏高 − 页上下 padding − 顶部一行 − 键盘上边距。 */
    private int availFromScreen() {
        return ResUtil.getScreenHeight() - px(RESERVE_DP);
    }

    /** 布局完成后取真实可用高度（左列高度已扣掉页面 padding）。 */
    private int availFromLayout() {
        if (!(binding.keyboard.getParent() instanceof View)) return 0;
        View column = (View) binding.keyboard.getParent();
        int avail = column.getHeight() - column.getPaddingTop() - column.getPaddingBottom();
        if (avail <= 0) return 0;
        if (binding.keyword.getParent() instanceof View) avail -= ((View) binding.keyword.getParent()).getHeight();
        ViewGroup.LayoutParams lp = binding.keyboard.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) avail -= ((ViewGroup.MarginLayoutParams) lp).topMargin;
        return avail;
    }

    /**
     * 一次算清键盘的三项尺寸：单键边长、键间距、键内图标边长。
     *
     * <p>三者由同一个 k 派生（间距、图标都与键同比），所以整块键盘在任何档位/屏幕下都是等比例缩放，
     * 不会出现「键被空间压小、间距反而等比变大」「图标比键还大」这类比例走样。
     * 键边长再被两个上限夹住：可用高度、左列最大宽度（半个屏宽）。</p>
     */
    private void applyKeySize(int avail) {
        if (adapter == null || adapter.getItemCount() == 0 || avail <= 0) return;
        int rows = (adapter.getItemCount() + SPAN_COUNT - 1) / SPAN_COUNT;
        // 整块高 = rows*k + (rows-1)*k*ratio = k*(rows + (rows-1)*ratio)
        float byHeight = avail / (rows + (rows - 1) * SPACING_RATIO);
        float byWidth = ResUtil.getScreenWidth() * COLUMN_WIDTH_RATIO / (SPAN_COUNT + (SPAN_COUNT - 1) * SPACING_RATIO);
        int want = Math.max(px(KEY_CURVE_DP[tier()]), px(MIN_KEY_DP));
        int key = (int) Math.min(want, Math.min(byHeight, byWidth));
        if (key <= 0) return;
        int spacing = Math.max(2, Math.round(key * SPACING_RATIO));
        // 间距取整后整块高可能超出 1px，逐像素回退保证「一定放得下」
        while (key > 1 && rows * key + (rows - 1) * spacing > avail) {
            key--;
            spacing = Math.max(2, Math.round(key * SPACING_RATIO));
        }
        int glyph = Math.max(1, Math.round(key * GLYPH_RATIO));
        boolean changed = decoration.setSpacingPx(spacing);
        changed |= adapter.setKeySize(key, glyph);
        if (changed) binding.keyboard.invalidateItemDecorations();
    }

    @Override
    public void onTextClick(String text) {
        StringBuilder sb = new StringBuilder(binding.keyword.getText().toString());
        int cursor = binding.keyword.getSelectionStart();
        if (binding.keyword.length() > 19) return;
        sb.insert(cursor, text);
        binding.keyword.setText(sb.toString());
        binding.keyword.setSelection(cursor + 1);
    }

    @Override
    public void onIconClick(int resId) {
        StringBuilder sb = new StringBuilder(binding.keyword.getText().toString());
        int cursor = binding.keyword.getSelectionStart();
        if (resId == R.drawable.ic_search_source) callback.showDialog();
        else if (resId == R.drawable.ic_keyboard_remote) callback.onRemote();
        else if (resId == R.drawable.ic_keyboard_search) callback.onSearchClick();
        else if (resId == R.drawable.ic_keyboard_left) onMoveLeft(cursor);
        else if (resId == R.drawable.ic_keyboard_right) onMoveRight(cursor);
        else if (resId == R.drawable.ic_keyboard_back) onBackspace(sb, cursor);
        else if (resId == R.drawable.ic_keyboard) adapter.toggle();
    }

    private void onMoveLeft(int cursor) {
        binding.keyword.setSelection(--cursor < 0 ? 0 : cursor);
    }

    private void onMoveRight(int cursor) {
        binding.keyword.setSelection(++cursor > binding.keyword.length() ? binding.keyword.length() : cursor);
    }

    private void onBackspace(StringBuilder sb, int cursor) {
        if (cursor <= 0) return;
        sb.deleteCharAt(cursor - 1);
        binding.keyword.setText(sb.toString());
        binding.keyword.setSelection(cursor - 1);
    }

    @Override
    public boolean onLongClick(int resId) {
        if (resId != R.drawable.ic_keyboard_back) return false;
        binding.keyword.setText("");
        return true;
    }

    public interface Callback {

        void showDialog();

        void onRemote();

        /** 点击键盘上的放大镜（发起搜索）。与 Activity 自身的 onSearch 区分开，便于在空输入时给提示。 */
        void onSearchClick();
    }
}
