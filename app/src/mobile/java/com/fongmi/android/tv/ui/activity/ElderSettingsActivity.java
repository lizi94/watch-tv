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
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.playback.vod.LineHealth;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ElderLock;
import com.fongmi.android.tv.utils.ElderMode;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 长辈设置（手机版）：适老化相关设置的<b>唯一入口</b>，设置页只保留一行跳转。
 *
 * <p>页内按四组排布：锁定（一键锁定，跳转 {@link ElderLockActivity}）／播放（点播自动全屏、
 * 退出自动续播、依赖预下载、首页快速搜索）／显示（图片尺寸）／数据（底部导航直播入口、恢复默认设置）。</p>
 *
 * <p>与电视版的差异只有两处：手机端字号跟随系统，故无「字号大小」；手机端有底部导航，
 * 故多「底部导航直播入口」。</p>
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
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.lock.setOnClickListener(this::onLock);
        mBinding.full.setOnClickListener(this::setAutoFullscreen);
        mBinding.resume.setOnClickListener(this::setAutoResume);
        mBinding.indexSearch.setOnClickListener(this::setIndexQuickSearch);
        mBinding.size.setOnClickListener(this::setSize);
        mBinding.navLive.setOnClickListener(this::setNavLive);
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
        mBinding.indexSearchText.setText(Setting.getSwitch(Setting.isIndexQuickSearch()));
        mBinding.sizeText.setText((size = ResUtil.getStringArray(R.array.select_size))[PlayerSetting.getSize()]);
        mBinding.navLiveText.setText(Setting.getSwitch(Setting.isNavLive()));
    }

    /** 一键锁定：进入锁定页挑选数据源、直播源与播放线路。 */
    private void onLock(View view) {
        ElderLockActivity.start(this);
    }

    /** 点播自动全屏开关：与电视版同一偏好键，起播出画即进全屏。 */
    private void setAutoFullscreen(View view) {
        Setting.putAutoFullscreen(!Setting.isAutoFullscreen());
        mBinding.fullText.setText(Setting.getSwitch(Setting.isAutoFullscreen()));
    }

    /** 退出自动续播开关：与电视版同一偏好键，退出播放器后是否保存/恢复播放位置。 */
    private void setAutoResume(View view) {
        Setting.putAutoResume(!Setting.isAutoResume());
        mBinding.resumeText.setText(Setting.getSwitch(Setting.isAutoResume()));
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

    /** 底部导航栏直播入口开关：关闭后首页底部导航不再显示直播 tab。 */
    private void setNavLive(View view) {
        Setting.putNavLive(!Setting.isNavLive());
        mBinding.navLiveText.setText(Setting.getSwitch(Setting.isNavLive()));
        // 通知 HomeActivity 立即刷新底部导航显隐
        ConfigEvent.common();
    }

    /** 图片尺寸：单选弹窗切换列表卡片大小。 */
    private void setSize(View view) {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.elder_setting_size_entry).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(size, PlayerSetting.getSize(), (dialog, which) -> {
            mBinding.sizeText.setText(size[which]);
            PlayerSetting.putSize(which);
            RefreshEvent.size();
            dialog.dismiss();
        }).show();
    }

    /** 恢复默认设置（与电视版同语义）：设置全部回出厂默认，数据源与收藏、观看记录保留。 */
    private void onRestore(View view) {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.elder_setting_restore_entry).setMessage(R.string.elder_setting_restore_confirm).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> restoreDefault()).show();
    }

    /**
     * 恢复出厂默认（与电视端逐行同语义）。
     *
     * <p>2026-10-15 对齐电视端：原先这里额外调 {@code FileUtil.clearCache} 且只 {@code refresh()}，
     * 与电视端「不缓存 + recreate 立即生效」分叉 —— ①「恢复设置」顺带清空老人攒下的图片缓存
     * 不在设置项语义内；② 不重建页面时图片尺寸档位要等下次进页面才生效。改为与电视端一致。</p>
     */
    private void restoreDefault() {
        ElderMode.restoreDefault();
        ElderLock.clear();
        // 线路健康度是「学出来的」数据，偏好被清空后内存缓存也得一起清，
        // 否则旧分数会继续影响择优排序。
        LineHealth.clear();
        Notify.show(R.string.elder_setting_restore_done);
        // 重建本页让新档位立即生效，并重新加载配置、广播刷新，让首页与历史页同步到默认外观。
        recreate();
        initConfig();
        RefreshEvent.home();
        RefreshEvent.history();
    }

    private void initConfig() {
        VodConfig.get().init().load(new Callback() {
            @Override
            public void start() {
                Notify.progress(ElderSettingsActivity.this);
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
