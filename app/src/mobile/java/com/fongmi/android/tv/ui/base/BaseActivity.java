package com.fongmi.android.tv.ui.base;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.SystemBarStyle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.custom.CustomWallView;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public abstract class BaseActivity extends AppCompatActivity {

    /** 本页创建时的图片尺寸档位：用于回前台时检测「图片尺寸是否已变」，变了就重建以应用新列数。 */
    private int sizeOnCreate;

    protected abstract ViewBinding getBinding();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        enableEdgeToEdge();
        enableDynamicColor();
        super.onCreate(savedInstanceState);
        sizeOnCreate = PlayerSetting.getSize();
        setContentView(getBinding().getRoot());
        EventBus.getDefault().register(this);
        initView(savedInstanceState);
        setBackCallback();
        initEvent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 图片尺寸在长辈设置页被切换后，回到前台的页面重建以应用新列数（与电视端 BaseActivity 同机制）。
        // 之所以需要它：卡片宽高由 Product#getColumn()（← PlayerSetting#getSize()）在 presenter /
        // adapter 构造时算定并锁定，已 inflate 的列表不会自行重排；而手机端 RefreshEvent.SIZE
        // 只有 VodFragment 响应，历史页 / 收藏页 / 直播页的尺寸会一直停在旧档位直到下次冷启动。
        // 播放页覆写 autoRecreateOnSizeChange() 为 false，不重建以免打断播放。
        if (autoRecreateOnSizeChange() && PlayerSetting.getSize() != sizeOnCreate) {
            sizeOnCreate = PlayerSetting.getSize();
            onBeforeRecreate();
            recreate();
        }
    }

    /**
     * 图片尺寸档位变化后是否自动重建本页以应用新列数（默认 true）。
     *
     * <p>播放页覆写为 false 以免打断播放。注意重建首页的实现必须覆写
     * {@link #onBeforeRecreate()} 打上「内部重建」标记 —— 否则重建出的新实例会被开机直达
     * （自动续播）误判成一次用户启动而抢屏。</p>
     */
    protected boolean autoRecreateOnSizeChange() {
        return true;
    }

    /** 因设置项变化而重建本页前的钩子：首页在此声明「这是内部重建」，避免被误判成用户打开软件。 */
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

    protected void setPadding(ViewGroup layout) {
        setPadding(layout, false);
    }

    protected void setPadding(ViewGroup layout, boolean leftOnly) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        DisplayCutout cutout = ResUtil.getDisplay(this).getCutout();
        if (cutout == null) return;
        int top = cutout.getSafeInsetTop();
        int left = cutout.getSafeInsetLeft();
        int right = cutout.getSafeInsetRight();
        int bottom = cutout.getSafeInsetBottom();
        int padding = left | right | top | bottom;
        layout.setPadding(padding, 0, leftOnly ? 0 : padding, 0);
    }

    protected void noPadding(ViewGroup layout) {
        layout.setPadding(0, 0, 0, 0);
    }

    private void setBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackInvoked();
            }
        });
    }

    private void enableEdgeToEdge() {
        EdgeToEdge.enable(this, SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
    }

    private void enableDynamicColor() {
        int color = Setting.getDynamicColor();
        if (color != 0) DynamicColors.applyToActivityIfAvailable(this, new DynamicColorsOptions.Builder().setContentBasedSource(color).build());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSubscribe(Object o) {
    }

    protected void onBackInvoked() {
        finish();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
