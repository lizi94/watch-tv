package com.fongmi.android.tv.ui.base;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.custom.CustomWallView;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.SoundFx;
import com.fongmi.android.tv.utils.Util;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public abstract class BaseActivity extends AppCompatActivity {

    /** 双击退出的判定间隔（毫秒）。 */
    private static final long EXIT_INTERVAL = 2000L;
    /**
     * 全局适配的设计宽度（dp）。按宽度等比：density = 实时屏幕宽px ÷ 1920。
     * 16:9 的 4K/1080p 屏幕上 UI 占屏比例恒定；不再使用 AndroidAutoSize 的
     * 进程启动快照 —— 那会在输出模式变化（4K↔1080p、HDMI 重协商、开机直达
     * 时显示管线未就绪）后给出过期的宽度，导致同一台设备两次打开比例不同。
     */
    private static final float DESIGN_WIDTH = 1920f;

    private long lastBackTime;
    /** 首页「连按三次返回键退出」：单次按键的最大间隔（超过则重新计数）。 */
    private static final long EXIT_TAP_WINDOW = 2500L;
    /** 退出所需连按次数。 */
    private static final int EXIT_TAPS = 3;
    /** 已连按次数。 */
    private int backTaps;
    /** 本页创建时的字号倍率：用于回前台时检测「字号档位是否已变」，变了就重建以应用新倍率。 */
    private float scaleOnCreate = 1f;

    /** 本页创建时的图片尺寸档位：用于回前台时检测「图片尺寸是否已变」，变了就重建以应用新列数。 */
    private int sizeOnCreate;

    /** 全局焦点监听：电视遥控器导航移动焦点时给出短促滴声反馈（适老化操作反馈音）。 */
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusSoundListener = (oldFocus, newFocus) -> {
        if (oldFocus != null && newFocus != null && oldFocus != newFocus) SoundFx.tick();
    };

    protected abstract ViewBinding getBinding();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scaleOnCreate = Setting.getFontScale();
        sizeOnCreate = PlayerSetting.getSize();
        setContentView(getBinding().getRoot());
        EventBus.getDefault().register(this);
        initView(savedInstanceState);
        Util.hideSystemUI(this);
        setBackCallback();
        initEvent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 字号档位 / 图片尺寸在别处（长辈设置页）切换后，回到前台的页面重建以应用新的全局倍率 / 新列数。
        // 播放页覆写 autoRecreateOnFontChange() / autoRecreateOnSizeChange() 为 false，不重建以免打断播放；
        // 电视首页自己响应 RefreshEvent.SIZE，同样覆写为 false —— 重建只会白丢焦点与滚动位置。
        boolean scaleChanged = autoRecreateOnFontChange() && Setting.getFontScale() != scaleOnCreate;
        boolean sizeChanged = autoRecreateOnSizeChange() && PlayerSetting.getSize() != sizeOnCreate;
        if (scaleChanged || sizeChanged) {
            scaleOnCreate = Setting.getFontScale();
            sizeOnCreate = PlayerSetting.getSize();
            onBeforeRecreate();
            recreate();
        }
        // 操作反馈音：只在本页前台期间监听焦点移动
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(focusSoundListener);
    }

    @Override
    protected void onPause() {
        getWindow().getDecorView().getViewTreeObserver().removeOnGlobalFocusChangeListener(focusSoundListener);
        super.onPause();
    }

    /**
     * 遥控器「确定」键按下时给一声确认音（适老化操作反馈音）。
     * 子类（如播放页）覆写 dispatchKeyEvent 时会先处理自己的按键再交给本方法。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && KeyUtil.isEnterKey(event)) SoundFx.confirm();
        return super.dispatchKeyEvent(event);
    }

    /** 字号档位变化后是否自动重建本页以应用新倍率（默认 true；播放页覆写为 false，避免打断播放）。 */
    protected boolean autoRecreateOnFontChange() {
        return true;
    }

    /**
     * 图片尺寸档位变化后是否自动重建本页以应用新列数（默认 true）。
     *
     * <p>与 {@link #autoRecreateOnFontChange()} 同一机制：图片尺寸在长辈设置页被切换后，
     * 回前台时按需重建。<b>列数</b>（{@code GridLayoutManager} 的 spanCount、
     * {@code SpaceItemDecoration} 的 spanCount、{@code Lists.partition} 的分组长度）由
     * {@code Product#getColumn()}（← {@link PlayerSetting#getSize()}）在 {@code initView} 期定下，
     * 已 inflate 的列表不会自己改列数 —— 所以必须重建。</p>
     *
     * <p>注意<b>封面尺寸</b>（{@code Product#getSpec}）已经不靠重建兜底了：presenter / adapter
     * 改成在每次 {@code onCreateViewHolder} 时按当前档位重算（见 {@code VodPresenter}）。
     * 但「每行几列」变了必须重新 group 数据，重建仍是唯一正确的做法。</p>
     *
     * <p>播放页覆写为 false 以免打断播放；电视首页覆写为 false —— 它自己响应
     * {@code RefreshEvent.SIZE}（重建推荐区与「最近观看」行），再重建一次只会白丢焦点与滚动位置。</p>
     */
    protected boolean autoRecreateOnSizeChange() {
        return true;
    }

    /**
     * 重建（字号档位切换触发）之前的钩子。首页用它声明「这是内部重建、不是用户打开软件」，
     * 否则重建出的新实例会被开机直达误判成一次普通启动。
     */
    protected void onBeforeRecreate() {
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        if (!customWall()) return;
        ((ViewGroup) findViewById(android.R.id.content)).addView(new CustomWallView(this, null), 0, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    protected FragmentActivity getActivity() {
        return this;
    }

    protected boolean customWall() {
        return true;
    }

    protected void initView(Bundle savedInstanceState) {
    }

    protected void initEvent() {
    }

    protected boolean isVisible(View view) {
        return view.getVisibility() == View.VISIBLE;
    }

    protected boolean isGone(View view) {
        return view.getVisibility() == View.GONE;
    }

    protected void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        view.post(() -> adapter.notifyDataSetChanged());
    }

    private void setBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackInvoked();
            }
        });
    }

    /**
     * 全局密度自适应（方案 B + 字号档位 + 大屏补偿）：每次取 Resources 时用<b>实时</b>
     * 窗口宽度重算 density，不再依赖 AndroidAutoSize 在进程启动时缓存的屏幕宽度快照。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>density = 当前 widthPixels ÷ 1920 × 字号倍率 × 大屏补偿 —— 输出模式随时变化都跟随；
     *       字号倍率（{@link Setting#getFontScale()}）让 文字/按钮/边框/圆角/间距 等全部 dp/sp
     *       元素等比缩放；大屏补偿（{@link #screenSizeFactor}）解决「同样 1080p、越大的屏幕字越小」
     *       的问题（28 寸以上按物理尺寸放大，系统 dpi 不可靠时自动回退）；</li>
     *   <li>sp 字号保持系统字体缩放比例不变（scaledDensity 按原 ratio 等比换算）；</li>
     *   <li>只在主线程写入（后台线程跳过，沿用上次主线程的值，与 AutoSize 行为一致）；</li>
     *   <li>失败不再静默：打日志，便于以后排查。</li>
     * </ul>
     */
    private Resources hackResources(Resources resources) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) return resources;
            DisplayMetrics metrics = resources.getDisplayMetrics();
            float width = metrics.widthPixels;
            if (width <= 0 || metrics.density <= 0) return resources;
            float targetDensity = width / DESIGN_WIDTH * Setting.getFontScale() * screenSizeFactor(metrics);
            if (Math.abs(metrics.density - targetDensity) < 0.001f) return resources;
            float fontRatio = metrics.scaledDensity / metrics.density;
            metrics.density = targetDensity;
            metrics.densityDpi = Math.round(targetDensity * 160);
            metrics.scaledDensity = targetDensity * fontRatio;
        } catch (Exception e) {
            Log.w("ElderScale", "density adapt failed", e);
        }
        return resources;
    }

    /**
     * 28 寸以上电视的屏幕尺寸补偿：按系统上报的 xdpi/ydpi 估算物理对角线（英寸），
     * 以 28 寸为基准（不放大），越大的屏幕放大越多 —— 43 寸≈×1.17、55 寸≈×1.31、
     * 65 寸≈×1.43、75 寸≈×1.54，封顶 ×1.6。
     *
     * <p>系统 dpi 在多数电视/盒子上不可靠（常固定 160dpi，会算出十几寸的离谱对角线），
     * 此时回退 1.0、不做补偿，绝不误放大。</p>
     */
    private float screenSizeFactor(DisplayMetrics metrics) {
        try {
            float xdpi = metrics.xdpi;
            float ydpi = metrics.ydpi;
            if (xdpi <= 0 || ydpi <= 0) return 1f;
            float widthIn = metrics.widthPixels / xdpi;
            float heightIn = metrics.heightPixels / ydpi;
            float diagonal = (float) Math.sqrt(widthIn * widthIn + heightIn * heightIn);
            if (diagonal < 26 || diagonal > 120) return 1f;
            return Math.max(1f, Math.min(1.6f, 1f + (diagonal - 28f) * 0.0115f));
        } catch (Exception e) {
            return 1f;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSubscribe(Object o) {
    }

    @Override
    public Resources getResources() {
        return hackResources(super.getResources());
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Util.hideSystemUI(this);
    }

    protected void onBackInvoked() {
        if (backToHome()) goHome();
        else if (confirmExit()) finish();
    }

    /**
     * 适老化：非首页按返回键一律回首页，而不是退出应用，
     * 避免老人误按返回键后「不知道跑到哪里去了」。首页返回 false。
     */
    protected boolean backToHome() {
        return true;
    }

    /**
     * 适老化退出确认：2 秒内连按两次返回键才真正退出。
     *
     * @return true 表示已达到退出条件，可以退出
     */
    /** 清零「连按退出」计数：离开首页再回来、或走了不计数的分支（启动画面/删除模式）时调用。 */
    protected void resetBackTaps() {
        backTaps = 0;
        lastBackTime = 0L;
    }

    /**
     * 首页连按退出计数。
     *
     * <p>改成「三次」而不是两次，是为了让首页的返回键<b>只有一个含义</b>：
     * 不再需要判断焦点在不在第一行、列表有没有滚动、播放服务在不在跑 ——
     * 那些分支正是过去「按两次不退出、反而后退/回后台」的根源。
     * 2.5 秒内连按三次，老人误触几乎不可能触发。</p>
     *
     * @return 0 = 达到退出条件；1 = 本轮第一次（调用方需提示一次）；2 = 已提示过（静默）
     */
    protected int countBackTap() {
        long now = System.currentTimeMillis();
        if (now - lastBackTime > EXIT_TAP_WINDOW) backTaps = 0;
        lastBackTime = now;
        if (++backTaps >= EXIT_TAPS) {
            resetBackTaps();
            return 0;
        }
        return backTaps;
    }

    protected boolean confirmExit() {
        long now = System.currentTimeMillis();
        if (now - lastBackTime < EXIT_INTERVAL) {
            lastBackTime = 0L;
            return true;
        }
        lastBackTime = now;
        Notify.show(R.string.elder_exit_hint);
        return false;
    }

    /**
     * 「非首页按返回」→ 回首页。
     *
     * <p><b>回退标记不再写进 Intent</b>：改为进程内静态标记（{@code HomeActivity.markBackHome()}）。
     * 写进 Intent 时，若首页实例已被系统回收，这次新建的首页会带着该标记成为任务根，
     * AMS 会把这份 Intent 记进任务的 baseIntent，导致之后每一次「重新打开软件」（待机唤醒 / 最近任务恢复）
     * 都被误判成内部回退，开机直达长期失效。</p>
     */
    private void goHome() {
        HomeActivity.markBackHome();
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
