package com.fongmi.android.tv.ui.custom;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySearchBinding;
import com.fongmi.android.tv.ui.adapter.KeyboardAdapter;

public class CustomKeyboard implements KeyboardAdapter.OnClickListener {

    /** 与布局 `app:spanCount="6"` 保持一致。R157：7 → 8；R158：8 → 6。 */
    private static final int SPAN_COUNT = 6;
    /**
     * 求解键高时统一按「两种键盘里最多的行数」计算，与当前键盘的实际行数无关。
     *
     * <p>R157 之前行数直接取自 {@code adapter.getItemCount()}：英文 7 图标 + 36 = 43 项要 7 行、
     * 注音 7 图标 + 47 = 54 项要 8 行 —— 点一次中/英，行数 7↔8，键边长跟着掉 13%
     * （实测 1080p/28″：125px ↔ 109px），这就是「切换键盘时键盘布局样式变了」的真因。
     * 改成常量后两种键盘共用同一组分母，切换时键尺寸逐像素不变。</p>
     *
     * <p>R158：6 列 + 去「换源」后，英文 6 图标 + 36 = 42 项要 7 行、注音 6 图标 + 47 = 53 项要 9 行，
     * 名义行数 7 → 9。行数更多、键更扁（宽 165 / 高 100），但「键面积」是三种列数里最大的（见 PLAN-R158）。</p>
     */
    private static final int NOMINAL_ROWS = 9;
    /**
     * 单键高度上限（dp），只作超宽屏天花；正常 1080p/4K 都由高度或宽度夹取决定。
     *
     * <p>R157 删掉了原来的 {@code KEY_CURVE_DP = {104, 88, 78, 68, 60}} 递减曲线 —— 那条曲线是
     * <b>死逻辑</b>：它恒大于 {@code byHeight}，{@code Math.min} 永远取后者的值，也就是说
     * 「字号档位」从未真正参与过键的尺寸计算；真正决定大小的是行数与页面留白，
     * 而这两者都随档位反向变化，于是档位越高键越小（1080p 实测 125 → 112）。</p>
     *
     * <p>现口径：<b>键高由屏幕高度定、键宽由左列宽度定，档位通过键内的字体现</b>
     * （{@code ag_text_body} 仍是 sp，照样随档位线性放大：1080p 上 29px → 48px，+66%）。</p>
     */
    private static final int MAX_KEY_DP = 104;
    /**
     * 键间距与键高的比例（R159 由一条拆成横/纵两条）。
     *
     * <p>R158 只有一条 {@code SPACING_RATIO = 8/104} 同时作用于横纵。但键是约 1.6:1 的宽矩形，
     * 同一条比例下「横缝 / 键宽 ≈ 4.9%」远小于「纵缝 / 键高 = 8.0%」—— 观感是横向挤、纵向松。
     * 拆开后纵缝 10/104、横缝 14/104，两条各自对「键边长」的占比回到同一量级。</p>
     *
     * <p>纵向那条同时是键高求解的分母（{@code avail = nom*keyH + (nom-1)*spacingV}）：
     * 横向比例不参与键高求解，所以 <b>R157 立下的「切换键盘键尺寸零抖动」不被破坏</b>。</p>
     */
    private static final float SPACING_V_RATIO = 10f / 104f;
    private static final float SPACING_H_RATIO = 14f / 104f;
    /**
     * 图标键里图标边长与键高的比例（键是宽矩形，图标按键高定、始终保持正方形）。
     *
     * <p>R157/R158 取 40/104（图标 = 键高的 38%），图标在宽矩形键里显得又小又空、
     * 与字母键的视觉重量对不上；R159 提到 52/104（1080p 上 38 → 49px）。</p>
     */
    private static final float GLYPH_RATIO = 52f / 104f;
    /** 键宽 / 键高 的上限（1.9）与下限（0.9）——键宽由左列宽度反解，理论上可以任意扁，这里兜住极端情况
     *  （极小屏 + 极大字号把左列压窄时，键不会变成横条；超宽左列时键不会窄到字都放不下）。 */
    private static final float MAX_ASPECT = 1.9f;
    private static final float MIN_ASPECT = 0.9f;
    /** 屏幕高度里不属于键盘的部分：页上下 padding(16dp×2) + 顶部一行(@id/keyword 固定 56dp) + 键盘上边距(8dp)。
     *  <p>R157：24dp/16dp → 16dp/8dp。这组留白本身也随档位线性放大（px(96) 在档位 0/4 是 115px/192px），
     *  留白多占一分、键盘就少一分 —— 收紧后 1080p 上键盘可用高度不再随档位缩水，键边长得以恒定。
     *  顶部一行原由 56dp 的麦克风图标撑起，语音功能移除后改由 @id/keyword 自己声明 56dp。</p>
     *  ⚠ 必须与 activity_search.xml 的 padding / layout_marginTop 同步（静态断言 A4 校验）。 */
    private static final int RESERVE_DP = 16 * 2 + 56 + 8;

    private final ActivitySearchBinding binding;
    private final Callback callback;
    private KeyboardAdapter adapter;
    private KeyboardSpaceDecoration decoration;

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
        binding.keyboard.addItemDecoration(decoration = new KeyboardSpaceDecoration(SPAN_COUNT));
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

    /** 当前窗口高（px）——与 {@link #density()} 同源，同样是 Activity 侧。 */
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

    /** 左列真实可用宽度（px）：键盘所在列宽 − 其左右 padding。键宽由此反解、撑满整列（R158 55:45 布局）。 */
    private int columnWidth() {
        if (!(binding.keyboard.getParent() instanceof View)) return 0;
        View column = (View) binding.keyboard.getParent();
        return column.getWidth() - column.getPaddingLeft() - column.getPaddingRight();
    }

    /**
     * 一次算清键盘的三项尺寸：单键宽、单键高、横/纵键间距、键内图标边长。
     *
     * <p><b>键宽与键高解耦（R158）</b>：键高由屏幕高度定（行数是硬约束，列越少行越多、键越矮）；
     * 键宽由左列宽度反解（55:45 布局里左列 1019px）。两者各自受 {@code MAX_ASPECT} /
     * {@code MIN_ASPECT} 夹取，防极端屏出现「横条键」或「竖条键」。图标按键高定、保持正方形。</p>
     *
     * <p><b>横/纵间距分别求解（R159）</b>：纵缝决定键高（高度是硬约束），横缝只参与键宽反解。
     * 两者都"随键高同比"，因此档位 / 屏幕变化时整块键盘的间距比例恒定。</p>
     */
    private void applyKeySize(int avail) {
        if (adapter == null || adapter.getItemCount() == 0 || avail <= 0) return;
        // 真实行数：只用于下面的防溢出兜底，不参与尺寸求解（否则切换键盘就会改尺寸）
        int rows = (adapter.getItemCount() + SPAN_COUNT - 1) / SPAN_COUNT;
        // 求解用「名义行数」与「真实行数」的较大者：常态恒为 NOMINAL_ROWS(9)；将来若出现行数更多的
        // 键盘，就按真实行数求解 —— 既不随切换抖动，也永不溢出。
        int nom = Math.max(NOMINAL_ROWS, rows);
        // 整块高 = nom*keyH + (nom-1)*spacingV ⇒ keyH = avail / (nom + (nom-1)*SPACING_V_RATIO)
        int keyH = Math.min(px(MAX_KEY_DP), Math.round(avail / (nom + (nom - 1) * SPACING_V_RATIO)));
        int spacingV = Math.max(2, Math.round(keyH * SPACING_V_RATIO));
        // 间距取整后整块高可能超出 1px，逐像素回退保证「一定放得下」。
        // ⛔ R158 缺陷 A：这里必须用名义行数 nom，不是真实行数 rows —— 用 rows 会导致中/英各自
        //   按自己的行数回退、切换时键高又差 1px（R157 修掉的正是这个病）。
        while (keyH > 1 && nom * keyH + (nom - 1) * spacingV > avail) {
            keyH--;
            spacingV = Math.max(2, Math.round(keyH * SPACING_V_RATIO));
        }
        // 键宽由左列反解：每行真实占宽 = SPAN*keyW + spacingH*(SPAN-1)（键盘自带装饰器的横向总间距
        // = spacingH*(SPAN-1)，首列 left=0 / 末列 right=0 / 中间列均摊，见 KeyboardSpaceDecoration）。
        // ⚠ 用向下取整而非四舍五入：保证 SPAN*keyW + spacingH*(SPAN-1) 恒 ≤ colW，最右一列键绝不溢出左列。
        int spacingH = Math.max(2, Math.round(keyH * SPACING_H_RATIO));
        int colW = columnWidth();
        int keyW = colW > 0
                ? (colW - spacingH * (SPAN_COUNT - 1)) / SPAN_COUNT
                : keyH;
        // 宽高比夹取：防横条键（> MAX_ASPECT）或竖条键（< MIN_ASPECT）
        keyW = Math.min(keyW, Math.round(keyH * MAX_ASPECT));
        keyW = Math.max(keyW, Math.round(keyH * MIN_ASPECT));
        if (keyH <= 0 || keyW <= 0) return;
        int glyph = Math.max(1, Math.round(keyH * GLYPH_RATIO));
        boolean changed = decoration.setSpacing(spacingH, spacingV);
        changed |= adapter.setKeySize(keyW, keyH, glyph);
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
        if (resId == R.drawable.ic_keyboard_remote) callback.onRemote();
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

    /**
     * 键盘专用网格间距（R159 / D7-A）。
     *
     * <p><b>为什么不复用 {@code SpaceItemDecoration}</b>：那个类在 {@code app/src/main/}，
     * 被历史 / 追剧 / 章节 / 弹幕 / 字幕等约 20 处（含手机端）共用，而且只有一个 {@code spacing}
     * 同时作用于横纵。键盘需要「横 ≠ 纵」两条间距，改它必然牵动手机端 —— 因此键盘自带一份，
     * 作用域收敛在本类内部。</p>
     *
     * <p>横向每条缝 = {@code spacingH}（首列 left=0 / 末列 right=0 / 中间列按列序均摊），与
     * {@link #applyKeySize} 的键宽反解公式 {@code keyW = (colW - spacingH*(span-1)) / span} 严格配套；
     * 纵向从第 2 行起每行上边距 = {@code spacingV}（首行 top=0）。两者都由 setSpacing 一次性下发。</p>
     */
    private static final class KeyboardSpaceDecoration extends RecyclerView.ItemDecoration {

        private final int spanCount;
        private int spacingH;
        private int spacingV;

        KeyboardSpaceDecoration(int spanCount) {
            this.spanCount = spanCount;
        }

        /** 下发横/纵间距（px）。@return true 表示确实变了，调用方需要 invalidateItemDecorations() */
        boolean setSpacing(int hPx, int vPx) {
            int h = Math.max(0, hPx);
            int v = Math.max(0, vPx);
            if (h == spacingH && v == spacingV) return false;
            spacingH = h;
            spacingV = v;
            return true;
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view);
            if (position < 0 || spanCount <= 0) {
                outRect.set(0, 0, 0, 0);
                return;
            }
            int column = position % spanCount;
            outRect.left = column * spacingH / spanCount;
            outRect.right = spacingH - (column + 1) * spacingH / spanCount;
            outRect.top = position >= spanCount ? spacingV : 0;
            outRect.bottom = 0;
        }
    }

    public interface Callback {

        void onRemote();

        /** 点击键盘上的放大镜（发起搜索）。与 Activity 自身的 onSearch 区分开，便于在空输入时给提示。 */
        void onSearchClick();
    }
}
