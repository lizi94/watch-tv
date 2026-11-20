package com.fongmi.android.tv.ui.custom;

import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySearchBinding;
import com.fongmi.android.tv.ui.adapter.KeyboardAdapter;

public class CustomKeyboard implements KeyboardAdapter.OnClickListener {

    /** 与布局 `app:spanCount="8"` 保持一致。R157：7 → 8。 */
    private static final int SPAN_COUNT = 8;
    /**
     * 求解键边长时统一按「两种键盘里最多的行数」计算，与当前键盘的实际行数无关。
     *
     * <p>R157 之前行数直接取自 {@code adapter.getItemCount()}：英文 7 图标 + 36 = 43 项要 7 行、
     * 注音 7 图标 + 47 = 54 项要 8 行 —— 点一次中/英，行数 7↔8，键边长跟着掉 13%
     * （实测 1080p/28″：125px ↔ 109px），这就是「切换键盘时键盘布局样式变了」的真因。
     * 改成常量后两种键盘共用同一组分母，切换时键边长逐像素不变。</p>
     */
    private static final int NOMINAL_ROWS = 7;
    /**
     * 单键边长上限（dp），只作超宽屏天花；正常 1080p/4K 都由宽度或高度夹取决定。
     *
     * <p>R157 删掉了原来的 {@code KEY_CURVE_DP = {104, 88, 78, 68, 60}} 递减曲线 —— 那条曲线是
     * <b>死逻辑</b>：它恒大于 {@code byHeight}，{@code Math.min} 永远取后者的值，也就是说
     * 「字号档位」从未真正参与过键的尺寸计算；真正决定大小的是行数与页面留白，
     * 而这两者都随档位反向变化，于是档位越高键越小（1080p 实测 125 → 112）。</p>
     *
     * <p>现口径：<b>键边长由屏幕宽高决定，档位通过键内的字体现</b>
     * （{@code ag_text_body} 仍是 sp，照样随档位线性放大：1080p 上 29px → 48px，+66%）。</p>
     */
    private static final int MAX_KEY_DP = 104;
    /** 键间横/纵向间距与键边长的比例（= 原 8dp / 104dp）：间距与键同比，整块键盘缩放时比例恒定。 */
    private static final float SPACING_RATIO = 8f / 104f;
    /** 图标键里图标边长与键边长的比例（= 原 40dp / 104dp）：图标也必须与键同比，否则键变小时图标会撑满整键。 */
    private static final float GLYPH_RATIO = 40f / 104f;
    /** 屏幕高度里不属于键盘的部分：页上下 padding(16dp×2) + 顶部一行(@id/keyword 固定 56dp) + 键盘上边距(8dp)。
     *  <p>R157：24dp/16dp → 16dp/8dp。这组留白本身也随档位线性放大（px(96) 在档位 0/4 是 115px/192px），
     *  留白多占一分、键盘就少一分 —— 收紧后 1080p 上键盘可用高度不再随档位缩水，键边长得以恒定。
     *  顶部一行原由 56dp 的麦克风图标撑起，语音功能移除后改由 @id/keyword 自己声明 56dp。</p>
     *  ⚠ 必须与 activity_search.xml 的 padding / layout_marginTop 同步（静态断言 A4 校验）。 */
    private static final int RESERVE_DP = 16 * 2 + 56 + 8;
    /** 页面左右 padding（dp）——算「半屏宽」上限时先扣掉，免得键盘比左列真实可用宽度还宽。 */
    private static final int PAGE_PADDING_DP = 16;
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

    /** 当前窗口宽（px）——与 {@link #density()} 同源，同样是 Activity 侧。 */
    private int screenWidth() {
        return binding.keyboard.getResources().getDisplayMetrics().widthPixels;
    }

    /** 当前窗口高（px）——同上。 */
    private int screenHeight() {
        return binding.keyboard.getResources().getDisplayMetrics().heightPixels;
    }

    private int px(float dp) {
        return Math.round(dp * density());
    }

    // R157：原 tier()（读 Setting.getFontSize() 去查递减曲线）已删除 —— 键边长不再依赖档位。

    /** 布局未完成时的估算：可用高度 ≈ 屏高 − 页上下 padding − 顶部一行 − 键盘上边距。 */
    private int availFromScreen() {
        return screenHeight() - px(RESERVE_DP);
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
        // 真实行数：只用于下面的防溢出兜底，不参与尺寸求解（否则切换键盘就会改尺寸）
        int rows = (adapter.getItemCount() + SPAN_COUNT - 1) / SPAN_COUNT;
        // 求解用「名义行数」与「真实行数」的较大者：常态恒为 NOMINAL_ROWS(7)；将来若出现行数更多的
        // 键盘，就按真实行数求解 —— 既不随切换抖动，也永不溢出。
        int nominal = Math.max(NOMINAL_ROWS, rows);
        // 整块高 = nominal*k + (nominal-1)*k*ratio = k*(nominal + (nominal-1)*ratio)
        float byHeight = avail / (nominal + (nominal - 1) * SPACING_RATIO);
        // 半屏宽要先扣掉页面左右 padding，否则 8 列会比左列真实可用宽度还宽、挤掉右侧热词
        float byWidth = (screenWidth() - px(PAGE_PADDING_DP * 2)) * COLUMN_WIDTH_RATIO / (SPAN_COUNT + (SPAN_COUNT - 1) * SPACING_RATIO);
        int want = px(MAX_KEY_DP);
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
