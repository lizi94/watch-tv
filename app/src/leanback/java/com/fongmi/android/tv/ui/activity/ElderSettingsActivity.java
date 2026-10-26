package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityElderSettingsBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.playback.vod.LineHealth;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.ConfirmDialog;
import com.fongmi.android.tv.utils.ElderLock;
import com.fongmi.android.tv.utils.ElderMode;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.SoundFx;

/**
 * 长辈设置（电视版）：适老化相关设置的<b>唯一入口</b>，设置页只保留一行跳转。
 *
 * <p>页内按四组排布：锁定（一键锁定，跳转 {@link ElderLockActivity}）／播放（点播自动全屏、
 * 退出自动续播、依赖预下载、按键音、首页快速搜索）／显示（字号大小、图片尺寸）／数据（恢复默认设置）。</p>
 *
 * <p>命名统一走 {@code elder_setting_*} 文案，不再借用通用设置项的字符串，
 * 避免改动长辈文案时误伤普通用户的设置页。</p>
 */
public class ElderSettingsActivity extends BaseActivity {

    private ActivityElderSettingsBinding mBinding;
    private String[] size;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, ElderSettingsActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityElderSettingsBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.lock.requestFocus();
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.lock.setOnClickListener(this::onLock);
        mBinding.full.setOnClickListener(this::setAutoFullscreen);
        mBinding.resume.setOnClickListener(this::setAutoResume);
        mBinding.sound.setOnClickListener(this::setKeySound);
        mBinding.indexSearch.setOnClickListener(this::setIndexQuickSearch);
        mBinding.font.setOnClickListener(this::onFont);
        mBinding.size.setOnClickListener(this::setSize);
        mBinding.restore.setOnClickListener(this::onRestore);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从锁定页返回后同步最新的锁定状态
        if (mBinding != null) refresh();
    }

    /** 刷新各项当前值。 */
    private void refresh() {
        Site locked = ElderLock.home();
        mBinding.lockText.setText(locked == null ? getString(R.string.elder_lock_none) : getString(R.string.elder_lock_on, locked.getName()));
        mBinding.fullText.setText(Setting.getSwitch(Setting.isAutoFullscreen()));
        mBinding.resumeText.setText(Setting.getSwitch(Setting.isAutoResume()));
        mBinding.soundText.setText(Setting.getSwitch(Setting.isKeySound()));
        mBinding.indexSearchText.setText(Setting.getSwitch(Setting.isIndexQuickSearch()));
        mBinding.fontText.setText(getFontName());
        mBinding.sizeText.setText((size = ResUtil.getStringArray(R.array.select_size))[PlayerSetting.getSize()]);
    }

    /** 当前字号档位的名称（标准 / 大 / 特大 / 超大 / 极大）。 */
    private String getFontName() {
        return getString(switch (Setting.getFontSize()) {
            case 1 -> R.string.elder_setting_font_large;
            case 2 -> R.string.elder_setting_font_extra;
            case 3 -> R.string.elder_setting_font_extra_large;
            case 4 -> R.string.elder_setting_font_huge;
            default -> R.string.elder_setting_font_standard;
        });
    }

    /** 一键锁定：进入锁定页挑选数据源、直播源与播放线路。 */
    private void onLock(View view) {
        ElderLockActivity.start(this);
    }

    /** 点播自动全屏开关：关闭后恢复原生「仅手动选集才全屏」的行为。 */
    private void setAutoFullscreen(View view) {
        Setting.putAutoFullscreen(!Setting.isAutoFullscreen());
        mBinding.fullText.setText(Setting.getSwitch(Setting.isAutoFullscreen()));
    }

    /** 退出自动续播开关：关闭后退出播放器不再保存/恢复播放位置，下次进入从头播。 */
    private void setAutoResume(View view) {
        Setting.putAutoResume(!Setting.isAutoResume());
        mBinding.resumeText.setText(Setting.getSwitch(Setting.isAutoResume()));
    }

    /**
     * 遥控器按键音开关：控制焦点移动 / 确定 / 出错三类操作反馈音（{@link SoundFx}）。
     * 门控在 SoundFx 内部读实时偏好，因此立即生效。
     */
    private void setKeySound(View view) {
        Setting.putKeySound(!Setting.isKeySound());
        boolean on = Setting.isKeySound();
        mBinding.soundText.setText(Setting.getSwitch(on));
        // 打开时当场给一声确认音，让用户立刻听到效果；关闭时自然静音。
        if (on) SoundFx.confirm();
    }

    /**
     * 首页快速搜索开关：关闭后 index 站源（豆瓣类）首页内容点击/长按直接进详情页，
     * 由自动择源择线把可播放线路找出来；开启则回到原生「跳搜索页跨源搜同名」的行为。
     * 门控点在首页的点击/长按回调，读实时偏好，改完下次点击即生效。
     */
    private void setIndexQuickSearch(View view) {
        Setting.putIndexQuickSearch(!Setting.isIndexQuickSearch());
        mBinding.indexSearchText.setText(Setting.getSwitch(Setting.isIndexQuickSearch()));
    }

    /**
     * 全局字号大小：循环 标准→大→特大→超大→极大→标准。
     * 档位系数作用于全局 density/scaledDensity，文字、按钮、边框、圆角、间距等比缩放。
     * 切换后重建当前页让新倍率立即生效；其余页面下次打开自动应用。
     */
    private void onFont(View view) {
        Setting.putFontSize((Setting.getFontSize() + 1) % 5);
        refresh();
        recreate();
    }

    /** 图片尺寸：循环切换列表卡片大小。 */
    private void setSize(View view) {
        int index = (PlayerSetting.getSize() + 1) % size.length;
        mBinding.sizeText.setText(size[index]);
        PlayerSetting.putSize(index);
        RefreshEvent.size();
    }

    private void onRestore(View view) {
        ConfirmDialog.create().message(R.string.elder_setting_restore_confirm).callback(this::restoreDefault).show(this);
    }

    /**
     * 恢复默认设置：<b>只把本页（长辈设置）上的各项恢复到出厂默认</b>，
     * 数据源配置、收藏与观看记录、以及本页没暴露的上游设置项一律保留。
     *
     * <p><b>2026-10-12 调整</b>：</p>
     * <ul>
     *   <li>重置范围改为 {@link ElderMode#restoreDefault()} 里的<b>精确白名单</b>（此前是
     *       「删掉除 {@code config_} 以外的全部键」，会把字幕、弹幕、解码、代理等上游设置一起清掉）；</li>
     *   <li><b>不再清缓存</b>：{@code FileUtil.clearCache} 会删掉整个 {@code cacheDir}
     *       （含图片缓存、ExoPlayer 缓存、mpv 缓存）。「恢复设置」顺带把老人攒下的图片缓存清空，
     *       下次进首页所有封面都要重新下载 —— 设置项的语义里没有这一层，故移出本流程；
     *       需要清缓存仍可在系统设置页的「缓存」项单独操作。</li>
     *   <li>{@link ElderLock#clear()} 保留：它写的是与 {@code ElderMode} 白名单里
     *       {@code elder_lock_*} 同一批键，此处补齐「选中源 / 直播源」这类需要写成空串而非删除的项
     *       （删除后 {@code getString} 返回 null，{@code ElderLock} 内部有判空，但显式写空更干净）。</li>
     *   <li>{@link LineHealth#clear()} 保留：线路健康度是「学出来的」数据，
     *       与偏好无关，但恢复默认后用户期望线路选择也回到初始状态，故一并清空。</li>
     * </ul>
     *
     * <p>本页是适老化设置的<b>唯一入口</b>（设置页只有一行跳转），
     * 因此「恢复默认设置」在电视端也只有这一个入口：设置页的「恢复」是
     * <b>备份还原</b>（{@code RestoreDialog} → {@code BackupManager.restore}，从备份文件读回），
     * 与「恢复出厂默认」是两件事，不构成重复入口。</p>
     */
    private void restoreDefault() {
        ElderMode.restoreDefault();
        ElderLock.clear();
        LineHealth.clear();
        Notify.show(R.string.elder_setting_restore_done);
        // 字号档位与图片尺寸都可能被重置：重建本页让新倍率/新档位立即生效，
        // 并重新加载配置、广播刷新，让首页与历史页同步到默认外观。
        recreate();
        initConfig();
        RefreshEvent.home();
        RefreshEvent.history();
    }

    private void initConfig() {
        VodConfig.get().init().load(new Callback() {
            @Override
            public void start() {
                Notify.progress(getActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        });
        LiveConfig.get().init().load();
        WallConfig.get().init().load();
    }
}
