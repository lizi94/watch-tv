package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySelfCheckBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.LauncherCapability;
import com.fongmi.android.tv.utils.Notify;

/**
 * 启动与桌面体检（R101）：老人 / 子女能看懂的一页。
 *
 * <p>四行 = 开机自动打开 / 后台不被清理 / 按主页键回到看电视 / 设为电视主页。
 * 每行都只写「现在怎么样」，点一下就去做那一件事；顶部一颗「一键检查并修复」按
 * 「设为电视主页 → 按主页键回到看电视 → 后台不被清理」的顺序逐步引导 ——
 * 每步只做一件事，做完回本页刷新，不一次弹三个系统页面（那会把人绕晕）。</p>
 *
 * <p><b>为什么第 3 行在「已是默认主页」时必须明说「不用开」</b>：那种形态下
 * {@code HomeGuardService} 是<b>空转</b>的（DesktopResolver 把自己排除后集合为空），
 * 开关打开也不会有任何效果。不明说就又是一个「开关是骗人的」（复审 I3）。</p>
 */
public class SelfCheckActivity extends BaseActivity {

    private ActivitySelfCheckBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SelfCheckActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySelfCheckBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.fix.requestFocus();
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.fix.setOnClickListener(view -> onFix());
        mBinding.boot.setOnClickListener(view -> {
            Setting.putBootAutostart(!Setting.isBootAutostart());
            refresh();
        });
        mBinding.battery.setOnClickListener(view -> {
            if (!LauncherCapability.requestBattery(this)) Notify.show(R.string.self_check_need_manual);
            else refresh();
        });
        mBinding.guard.setOnClickListener(view -> onGuard());
        mBinding.home.setOnClickListener(view -> onHome());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统页面回来必须重算 —— 用户可能刚在系统里把「默认主页」或那个开关改掉了。
        LauncherCapability.refresh(this);
        refresh();
    }

    private void refresh() {
        if (mBinding == null) return;
        mBinding.bootText.setText(Setting.getSwitch(Setting.isBootAutostart()));
        mBinding.batteryText.setText(Setting.getSwitch(LauncherCapability.isBatteryOk(this)));

        boolean selfHome = LauncherCapability.isSelfHome(this);
        if (selfHome) {
            // 默认桌面形态：守护空转，必须明说「不用开」，并把这一行视觉上弱化
            mBinding.guardText.setText(R.string.self_check_guard_by_home);
            mBinding.guard.setAlpha(0.6f);
        } else {
            mBinding.guard.setAlpha(1f);
            boolean guard = Setting.isHomeGuard();
            boolean a11y = LauncherCapability.isA11yOn(this);
            if (!guard) mBinding.guardText.setText(R.string.self_check_off);
            else mBinding.guardText.setText(a11y ? R.string.self_check_ok : R.string.self_check_todo);
        }

        mBinding.homeText.setText(selfHome ? R.string.self_check_ok : R.string.self_check_todo);
        mBinding.warn.setVisibility(LauncherCapability.isOnlyHome(this) ? View.VISIBLE : View.GONE);
    }

    /**
     * 第 3 行：开关 + 无障碍授权引导。
     *
     * <p>原先只有「写一个布尔值」，等于骗用户 —— 开关打开了但系统里那个服务没开，
     * 主页键该怎样还是怎样。现在：打开的同一时刻若发现没授权，立刻说明并把他带到系统那一页。</p>
     */
    private void onGuard() {
        if (LauncherCapability.isSelfHome(this)) {
            // 默认桌面形态下这一行是空转的，点了只解释一句，不做任何状态改动
            Notify.show(R.string.self_check_guard_by_home);
            return;
        }
        boolean next = !Setting.isHomeGuard();
        Setting.putHomeGuard(next);
        refresh();
        if (next && !LauncherCapability.isA11yOn(this)) {
            Notify.show(R.string.a11y_guide);
            if (!LauncherCapability.openA11ySettings(this)) Notify.show(R.string.self_check_need_manual);
        }
    }

    /** 第 4 行：去系统里设默认主页（已设时这里就是「换回电视自带的主页」）。 */
    private void onHome() {
        if (!LauncherCapability.openHomeSettings(this)) Notify.show(R.string.self_check_need_manual);
    }

    /**
     * 一键检查并修复：按顺序只做**下一件没做的事**。
     *
     * <p>顺序不是随便排的：设成默认主页之后，第 3 行（按主页键回到看电视）本来就是空转的，
     * 所以默认桌面形态下无障碍那一步要一并跳过 —— 引导老人去开一个不会生效的开关，
     * 比不引导更糟。</p>
     */
    private void onFix() {
        if (!LauncherCapability.isSelfHome(this)) {
            if (!LauncherCapability.openHomeSettings(this)) Notify.show(R.string.self_check_need_manual);
            return;
        }
        if (!LauncherCapability.isBatteryOk(this)) {
            if (!LauncherCapability.requestBattery(this)) Notify.show(R.string.self_check_need_manual);
            return;
        }
        Notify.show(R.string.self_check_all_ok);
    }
}
