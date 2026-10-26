package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.widget.NestedScrollView;

import com.fongmi.android.tv.R;

/**
 * 弹窗根容器（电视版专用）：在<b>测量阶段</b>给「可伸缩的内容滚动区」按弹窗真实可用高度限高，
 * 把它下方的按钮行需要的高度留出来，保证「选择 / 确定 / 取消」永远可见。
 *
 * <h3>问题现象</h3>
 * 导入点播源 / 直播源 / 壁纸源的弹窗（以及同构的自定义 UA、mpv 配置弹窗），底部按钮行被
 * 挤成 0 高、始终看不到；手机上装同一个 APK 却正常。
 *
 * <h3>根因</h3>
 * 这些弹窗把「内容区 + 按钮行」一起放进自定义布局交给 MaterialAlertDialog。框架会把它挂到
 * {@code AlertDialogLayout} 的 {@code customPanel} 下，并以 {@code AT_MOST(可用高度)} 测量我们的
 * 根容器（可用高度 = 窗口高度 − 标题栏 − 按钮栏，我们没走框架按钮，所以 ≈ 窗口高度）。
 * 竖向 LinearLayout 是<b>按子项顺序</b>分配高度的：
 * <ol>
 *   <li>第一个子项是内容滚动区，高度 {@code wrap_content}，量它时父容器剩余空间还是全部可用高度，
 *       于是 {@code AT_MOST(剩余空间)} 让它把可用高度<b>吃满</b>；</li>
 *   <li>紧随其后的按钮行再量时，可用高度已经是 0，拿到 {@code AT_MOST(0)} → 被量成 <b>0 高</b>。</li>
 * </ol>
 * 字号档位 + 大屏补偿把内容整体放大后更容易越过可用高度，所以电视上（大屏 + 高档位）必现；
 * 手机端 density 不做改写、总高远小于屏高、滚动区不会吃满可用高度，因此不会出现。
 *
 * <h3>为什么不能在窗口 / 全局布局监听里事后改高度</h3>
 * 那时候布局已经按 0 高把按钮行量完并放好了，改高度拦不住那一次测量；而且外层拿不到
 * 框架真正给的高度规格。本类 {@code onMeasure} 收到的 {@code size} 就是框架真正给的可用高度。
 *
 * <h3>修法</h3>
 * 每次测量都按当时的真实规格重算，共三趟：
 * <ol>
 *   <li>先把滚动区高度置回 {@code wrap_content}，用 {@code UNSPECIFIED} 量一次自己 ——
 *       父规格不受裁剪，得到「内容区自然高」与「整卡自然高」；</li>
 *   <li>两者之差 {@code other} 就是按钮行 + 内边距 + margin 的固定开销，必须留给它们。
 *       整卡自然高超过可用高度时，把滚动区高度<b>显式</b>设成 {@code 可用高度 − other}
 *       ——显式尺寸会让子项测量变成 {@code EXACTLY}，按钮行随后拿到的是自己需要的高度，
 *       不再被裁剪；</li>
 *   <li>按框架真实规格正式测一次。</li>
 * </ol>
 * 不溢出时恢复布局里声明的高度，一个像素都不动（低档位/小内容零回归）。
 *
 * <h3>刻意的取舍</h3>
 * <b>不做最小高度兜底</b>：任何「内容区至少留 N dp」的兜底都会让整卡再次超过可用高度，
 * 按钮行重新拿到 {@code AT_MOST(0)} 被挤成 0 高 —— 正好是本类要修的故障。
 * 极端情况下内容区只剩很矮的一条（仍然可以滚动），优先保证三个按钮可点。
 *
 * <p>全程按实测、不含任何 dp/sp 常量，因此与字号档位（含大屏补偿）、屏幕尺寸、图片尺寸档位
 * 全都解耦，以后继续加字号档位也不会复发。子项顺序无关：公式用的是「整卡 − 内容区」，
 * 按钮行在内容区之前或之后都成立。</p>
 */
@Keep
public class DialogPanel extends LinearLayoutCompat {

    private View scroll;
    /** 布局里声明的原始高度只认第一次：之后 {@code getLayoutParams().height} 是本类自己写进去的限高值。 */
    private int declaredHeight = Integer.MIN_VALUE;

    public DialogPanel(Context context) {
        this(context, null);
    }

    public DialogPanel(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DialogPanel(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        View scroller = findScroller();
        if (scroller == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        ViewGroup.LayoutParams params = scroller.getLayoutParams();
        if (declaredHeight == Integer.MIN_VALUE) declaredHeight = params.height;

        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int available = MeasureSpec.getSize(heightMeasureSpec);
        // UNSPECIFIED（父容器让我们自己定高）或尺寸未知：没有可用上界，按原样测量
        if (mode == MeasureSpec.UNSPECIFIED || available <= 0) {
            params.height = declaredHeight;
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        // 1) 按自然高量一次自己：父规格给 UNSPECIFIED，滚动区不会被可用高度裁剪
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int naturalScroll = scroller.getMeasuredHeight();
        int naturalTotal = getMeasuredHeight();
        // 整卡自然高 − 内容区自然高 = 按钮行 + 内边距 + margin，这是按钮行的「必要开销」
        int other = naturalTotal - naturalScroll;

        if (naturalScroll > 0 && naturalTotal > available) {
            // 2) 溢出：显式限高内容区，把 other 留给按钮行
            int limit = Math.max(0, available - other);
            if (declaredHeight >= 0) limit = Math.min(limit, declaredHeight);
            params.height = limit;
        } else {
            params.height = declaredHeight;
        }
        // 3) 按框架真实规格正式测量
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /** 内容滚动区：优先按布局里声明的 id 找，找不到再退化为「第一个 NestedScrollView 子项」。 */
    private View findScroller() {
        if (scroll != null && scroll.getParent() == this) return scroll;
        View byId = findViewById(R.id.dialog_scroll);
        if (byId != null) return scroll = byId;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof NestedScrollView) return scroll = child;
        }
        return null;
    }
}
