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
import com.fongmi.android.tv.utils.GuardSelfHeal;
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
 * <p><b>为什么第 3 行在「已是默认桌面」时必须明说「不用开」</b>：那种形态下
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
        mBinding.boot.setOnClickListener(view -> onBoot());
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
        // 从系统页面回来必须重算 —— 用户可能刚在系统里把「默认桌面」或那个开关改掉了。
        LauncherCapability.refresh(this);
        refresh();
    }

    private void refresh() {
        if (mBinding == null) return;
        boolean selfHome = LauncherCapability.isSelfHome(this);
        // 第 1 行「开机就打开看电视」：默认桌面形态下这一行是**关不掉**的 ——
        // 系统一定会在开机时把本应用当桌面拉起来，这个开关只对「需要主动拉起」的形态有效。
        // 旧实现照样让用户点一下翻转，结果是「显示已关闭、开机照样打开」—— 一个骗人的开关。
        // 现在默认桌面形态下只陈述事实、整行弱化，点击也只提示（与第 3 行守护同一口径）。
        if (selfHome) {
            mBinding.bootText.setText(R.string.self_check_boot_by_home);
            mBinding.boot.setAlpha(0.6f);
        } else {
            mBinding.boot.setAlpha(1f);
            mBinding.bootText.setText(Setting.getSwitch(Setting.isBootAutostart()));
        }
        mBinding.batteryText.setText(Setting.getSwitch(LauncherCapability.isBatteryOk(this)));

        if (selfHome) {
            // 默认桌面形态：守护空转，必须明说「不用开」，并把这一行视觉上弱化
            mBinding.guardText.setText(R.string.self_check_guard_by_home);
            mBinding.guard.setAlpha(0.6f);
        } else {
            mBinding.guard.setAlpha(1f);
            if (!Setting.isHomeGuard()) {
                mBinding.guardText.setText(R.string.self_check_off);
            } else if (LauncherCapability.isA11yOn(this)) {
                mBinding.guardText.setText(R.string.self_check_ok);
            } else if (LauncherCapability.isA11yListed(this)) {
                // R150：设置里写着本服务、但系统没把服务绑上进程（TCL 每次重启后的现场）。
                // 旧实现在这一态显示「已设置」—— 那个骗人的开关就是这里。
                mBinding.guardText.setText(R.string.self_check_guard_stalled);
            } else {
                mBinding.guardText.setText(R.string.self_check_todo);
            }
        }

        // 第 4 行：能不能/有没有设成默认桌面。guard 变体的 Manifest 里没有任何 MAIN+HOME 声明,
        // 系统「默认桌面」列表里根本不会出现本应用 —— 对这一行只能陈述事实并整行弱化，
        // 否则点进去是一个找不到自己的列表（比不引导更糟）。
        if (!LauncherCapability.canBeHome(this)) {
            mBinding.home.setAlpha(0.6f);
            mBinding.homeText.setText(R.string.self_check_home_unavailable);
        } else {
            mBinding.home.setAlpha(1f);
            mBinding.homeText.setText(LauncherCapability.isSelfHome(this) ? R.string.self_check_ok : R.string.self_check_todo);
        }
        mBinding.warn.setVisibility(LauncherCapability.isOnlyHome(this) ? View.VISIBLE : View.GONE);
    }

    /**
     * 第 1 行：开机自动打开。
     *
     * <p><b>默认桌面形态下这一行不能改</b>：系统开机必然把本应用当桌面拉起来，
     * 这个开关只对「需要主动拉起」的形态有效。旧实现照样翻转它，表现为
     * 「显示已关闭、开机照样打开」—— 又一个骗人的开关。
     * 现在这一形态下只提示一句事实、不动底层值（万一以后换回非默认桌面，
     * 尊重他当初的选择）。</p>
     */
    private void onBoot() {
        if (LauncherCapability.isSelfHome(this)) {
            Notify.show(R.string.self_check_boot_by_home);
            return;
        }
        Setting.putBootAutostart(!Setting.isBootAutostart());
        refresh();
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
        // R150：「开关开着、设置里也写着本服务，但系统没把服务绑上进程」—— 这是 TCL 每次
        //   重启后的真实状态，也是旧实现里那个「显示已设置却根本不生效」的骗人开关。
        //   这一态下先尝试自愈（需 WRITE_SECURE_SETTINGS；未授权时静默失败，落到下面的引导）。
        if (Setting.isHomeGuard() && LauncherCapability.isA11yListed(this) && !LauncherCapability.isA11yOn(this)) {
            if (GuardSelfHeal.ensure(this, "体检页点击修复")) {
                Notify.show(R.string.self_check_guard_repairing);
                return;
            }
        }
        boolean next = !Setting.isHomeGuard();
        Setting.putHomeGuard(next);
        refresh();
        if (next && !LauncherCapability.isA11yOn(this)) {
            Notify.show(R.string.a11y_guide);
            if (!LauncherCapability.openA11ySettings(this)) Notify.show(R.string.self_check_need_manual);
        }
    }

    /** 第 4 行：去系统里设默认桌面（已设时这里就是「换回电视自带的主页」）。 */
    private void onHome() {
        // guard 变体没有桌面声明，系统页面里不会出现本应用 —— 直接说明，不做无意义的跳转
        if (!LauncherCapability.canBeHome(this)) {
            Notify.show(R.string.self_check_home_unavailable);
            return;
        }
        if (!LauncherCapability.openHomeSettings(this)) Notify.show(R.string.self_check_need_manual);
    }

    /**
     * 一键检查并修复：按顺序只做**下一件没做的事**。
     *
     * <p>顺序不是随便排的：设成默认桌面之后，第 1 行（开机就打开）与第 3 行（按主页键回到看电视）
     * 本来就是空转的 —— 系统自己会把本应用当桌面拉起来，所以这两步要一并跳过。
     * 引导老人去开一个不会生效的开关，比不引导更糟。</p>
     *
     * <p>R153 起要区分两种形态：{@code leanbackHome} 能当桌面，第一步是去开「默认桌面」；
     * {@code leanbackGuard} 的 Manifest 里<b>没有</b>桌面声明，那个页面里压根不会出现本应用 ——
     * 对 guard 版必须跳过第一步，否则「一键修复」会永远卡在一个打不开的死路上，
     * 后面真正需要的「开机自启 / 守护授权」永远轮不到（旧实现在 guard 版上的真实现象）。</p>
     */
    private void onFix() {
        boolean selfHome = LauncherCapability.isSelfHome(this);

        // ① 能当桌面、但还没设成默认桌面 —— 这一步最优先（设完 ②④ 会自动空转）
        if (!selfHome && LauncherCapability.canBeHome(this)) {
            if (!LauncherCapability.openHomeSettings(this)) Notify.show(R.string.self_check_need_manual);
            return;
        }
        // ② 电池白名单（两种形态都要）
        if (!LauncherCapability.isBatteryOk(this)) {
            if (!LauncherCapability.requestBattery(this)) Notify.show(R.string.self_check_need_manual);
            return;
        }
        // ③ 开机自启：只有「不靠桌面拉起」的形态才需要（已是默认桌面时系统必然拉起，跳过）
        if (!selfHome && !Setting.isBootAutostart()) {
            Setting.putBootAutostart(true);
            refresh();
            return;
        }
        // ④ 守护开关 + 无障碍授权：同上，默认桌面形态下空转
        if (!selfHome) {
            if (!Setting.isHomeGuard()) {
                Setting.putHomeGuard(true);
                refresh();
                if (!LauncherCapability.isA11yOn(this)) {
                    Notify.show(R.string.a11y_guide);
                    if (!LauncherCapability.openA11ySettings(this)) Notify.show(R.string.self_check_need_manual);
                }
                return;
            }
            if (!LauncherCapability.isA11yOn(this)) {
                Notify.show(R.string.a11y_guide);
                if (!LauncherCapability.openA11ySettings(this)) Notify.show(R.string.self_check_need_manual);
                return;
            }
        }
        Notify.show(R.string.self_check_all_ok);
    }
}
