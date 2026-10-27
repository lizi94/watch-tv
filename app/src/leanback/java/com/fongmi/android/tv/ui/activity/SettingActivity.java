package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivitySettingBinding;
import com.fongmi.android.tv.db.BackupManager;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.ConfirmDialog;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.dialog.DohDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.RestoreDialog;
import com.fongmi.android.tv.utils.ElderLock;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class SettingActivity extends BaseActivity implements ConfigListener, LiveListener, DohDialog.Listener {

    private ActivitySettingBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingActivity.class));
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.elderSettings.requestFocus();
        refreshView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从长辈设置页返回后同步入口处的锁定状态
        if (mBinding != null) setElderSettingsText();
    }

    /**
     * 数据源 / 直播源两行的值文本。
     *
     * <p>锁定后**不再显示源地址**，直接换成「已锁定 · 如需更换请先解锁」——
     * 让老人一眼看出这里动不了，而不是看到一个看似可点的入口、点了才弹提示。</p>
     */
    private void refreshConfigText() {
        mBinding.vodUrl.setText(ElderLock.isLocked() ? getString(R.string.elder_site_locked_hint) : VodConfig.getDesc());
        mBinding.liveUrl.setText((ElderLock.isLiveLocked() || ElderLock.isLocked()) ? getString(R.string.elder_live_locked_hint) : LiveConfig.getDisplayDesc());
    }

    /** 数据源锁生效时提示并拦截（返回 true 表示已拦下）。 */
    private boolean blockedBySiteLock() {
        if (!ElderLock.isLocked()) return false;
        Notify.show(R.string.elder_site_blocked);
        return true;
    }

    /**
     * 直播源锁生效时提示并拦截（返回 true 表示已拦下）。
     *
     * <p>⛔ 2026-10-24 修：原来只看 {@code isLiveLocked()}（单独的直播源锁）。但
     * {@code ElderLockActivity#doLock()} 在「该数据源没有独立直播配置」时会**刻意不锁直播源**
     * （避免把人锁死在直播上）—— 于是一键锁定之后，设置页里的直播源入口仍然可以换，
     * 与「锁定 = 这个源固定住」的语义矛盾。
     * 现在改为：<b>数据源已锁定 或 直播源已锁定，任一成立就拦设置页的管理入口</b>。</p>
     *
     * <p>⚠ 只拦设置页：直播页自己的换源判定仍然只用 {@code isLiveLocked()}，
     * 所以「没有独立直播源」时直播功能照常可用，不会被锁死。</p>
     */
    private boolean blockedByLiveLock() {
        if (!ElderLock.isLiveLocked() && !ElderLock.isLocked()) return false;
        Notify.show(R.string.elder_live_blocked);
        return true;
    }

    /** 刷新各设置项当前值的显示。 */
    private void refreshView() {
        refreshConfigText();
        mBinding.wallUrl.setText(WallConfig.getDesc());
        mBinding.versionText.setText(BuildConfig.VERSION_NAME);
        setElderSettingsText();
        setCacheText();
        setOtherText();
    }

    /** 长辈设置入口的摘要：直接显示当前锁定状态，老人一眼可见。 */
    private void setElderSettingsText() {
        Site locked = ElderLock.home();
        mBinding.elderSettingsText.setText(locked == null ? getString(R.string.elder_lock_none) : getString(R.string.elder_lock_on, locked.getName()));
    }

    private void setOtherText() {
        String[] dohList = getDohList();
        mBinding.dohText.setText(dohList.length == 0 ? "" : dohList[getDohIndex()]);
        mBinding.incognitoText.setText(Setting.getSwitch(Setting.isIncognito()));
    }

    private void setCacheText() {
        FileUtil.getCacheSize(new Callback() {
            @Override
            public void success(String result) {
                mBinding.cacheText.setText(result);
            }
        });
    }

    @Override
    protected void initEvent() {
        mBinding.vod.setOnClickListener(this::onVod);
        mBinding.doh.setOnClickListener(this::setDoh);
        mBinding.live.setOnClickListener(this::onLive);
        mBinding.wall.setOnClickListener(this::onWall);
        mBinding.cache.setOnClickListener(this::onCache);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.player.setOnClickListener(this::onPlayer);
        mBinding.danmaku.setOnClickListener(this::onDanmaku);
        mBinding.restore.setOnClickListener(this::onRestore);
        mBinding.version.setOnClickListener(this::onVersion);
        mBinding.vod.setOnLongClickListener(this::onVodEdit);
        mBinding.live.setOnLongClickListener(this::onLiveEdit);
        mBinding.wall.setOnLongClickListener(this::onWallEdit);
        mBinding.incognito.setOnClickListener(this::setIncognito);
        mBinding.elderSettings.setOnClickListener(this::onElderSettings);
        mBinding.vodHistory.setOnClickListener(this::onVodHistory);
        mBinding.liveHistory.setOnClickListener(this::onLiveHistory);
        mBinding.wallDefault.setOnClickListener(this::setWallDefault);
        mBinding.wallRefresh.setOnClickListener(this::setWallRefresh);
        mBinding.wallRefresh.setOnLongClickListener(this::onWallHistory);
    }

    @Override
    public void setConfig(Config config) {
        // ⛔ 2026-10-24 修：电视端 SettingsActivity 之前**完全没有锁定校验** ——
        // 「一键锁定」锁住数据源后，从「数据源 / 长按编辑 / 数据源历史 / 直播源历史」任意一条都能换掉，
        // 锁定等于没做（手机端 2026-10-22 已修，电视端漏了）。这里一处收口全部入口。
        if (config.getType() == 1 && ElderLock.isLiveLocked() && !config.getUrl().equals(ElderLock.getLiveKey())) {
            Notify.show(R.string.elder_live_blocked);
            return;
        }
        if (config.getType() == 0 && ElderLock.isLocked()) {
            Config current = VodConfig.get().getConfig();
            if (current != null && !config.getUrl().equals(current.getUrl())) {
                Notify.show(R.string.elder_site_blocked);
                return;
            }
        }
        if (config.getUrl().startsWith("file")) {
            PermissionUtil.requestFile(this, allGranted -> load(config));
        } else {
            load(config);
        }
    }

    private void load(Config config) {
        switch (config.getType()) {
            case 0:
                VodConfig.load(config, getCallback());
                break;
            case 1:
                LiveConfig.load(config, getCallback());
                break;
            case 2:
                Setting.putWall(0);
                WallConfig.load(config, getCallback());
                break;
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(getActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
                setCacheText();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
    }

    @Override
    public void setLive(Live item) {
        LiveConfig.get().setHome(item);
    }

    private void onVod(View view) {
        if (blockedBySiteLock()) return;
        ConfigDialog.create().vod().show(this);
    }

    private void onLive(View view) {
        if (blockedByLiveLock()) return;
        ConfigDialog.create().live().show(this);
    }

    private void onWall(View view) {
        ConfigDialog.create().wall().show(this);
    }

    private boolean onVodEdit(View view) {
        if (blockedBySiteLock()) return true;
        ConfigDialog.create().vod().edit().show(this);
        return true;
    }

    private boolean onLiveEdit(View view) {
        if (blockedByLiveLock()) return true;
        ConfigDialog.create().live().edit().show(this);
        return true;
    }

    private boolean onWallEdit(View view) {
        ConfigDialog.create().wall().edit().show(this);
        return true;
    }

    private void onVodHistory(View view) {
        if (blockedBySiteLock()) return;
        HistoryDialog.create().vod().show(this);
    }

    private void onLiveHistory(View view) {
        if (blockedByLiveLock()) return;
        HistoryDialog.create().live().show(this);
    }

    private void onPlayer(View view) {
        SettingPlayerActivity.start(this);
    }

    private void onDanmaku(View view) {
        SettingDanmakuActivity.start(this);
    }

    /**
     * 版本项：版本检查更新已移除，点击打开作者主页。
     * 电视上多数没有浏览器 —— {@code Util.openUrl} 内部会捕获 ActivityNotFoundException
     * 并提示 {@code R.string.open_url_fail}，所以不会出现「点了毫无反应」。
     */
    private void onVersion(View view) {
        // 版本检查更新已移除：点击直达作者官网
        Util.openUrl(this, "https://logyu.cc/");
    }

    private void setWallDefault(View view) {
        // 上限跟随内置壁纸数量：写死 4 时新增的壁纸永远轮不到（getWall() 的 clamp 也会把它压回旧值）
        Setting.putWall(Setting.getWall() >= Setting.MAX_WALL ? 1 : Setting.getWall() + 1);
        Setting.putWallType(0);
        ConfigEvent.wall();
    }

    private void setWallRefresh(View view) {
        Setting.putWall(0);
        WallConfig.get().load(getCallback());
    }

    private boolean onWallHistory(View view) {
        HistoryDialog.create().wall().show(this);
        return true;
    }

    private void setIncognito(View view) {
        Setting.putIncognito(!Setting.isIncognito());
        mBinding.incognitoText.setText(Setting.getSwitch(Setting.isIncognito()));
    }

    private void setDoh(View view) {
        DohDialog.create().index(getDohIndex()).show(this);
    }

    @Override
    public void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        Setting.putDoh(doh.toString());
        mBinding.dohText.setText(doh.getName());
    }

    private void onCache(View view) {
        // 与「删除历史/收藏」保持一致的二次确认：清掉的是全部播放/图片/EPG 缓存，
        // 老人误触后下次看片要重新缓冲，体感是「变慢了」。
        ConfirmDialog.create().message(getString(R.string.setting_cache_confirm)).callback(this::clearCache).show(this);
    }

    private void clearCache() {
        FileUtil.clearCache(new Callback() {
            @Override
            public void success() {
                setCacheText();
            }
        });
    }

    private void onBackup(View view) {
        PermissionUtil.requestFile(this, allGranted -> BackupManager.backup(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.backup_success);
            }

            @Override
            public void error() {
                Notify.show(R.string.backup_fail);
            }
        }));
    }

    private void onRestore(View view) {
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create().callback(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.restore_success);
                setOtherText();
                initConfig();
            }

            @Override
            public void error() {
                Notify.show(R.string.restore_fail);
            }
        }).show(this));
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init().load();
    }

    /**
     * 长辈设置：适老化相关选项（一键锁定、点播自动全屏、退出自动续播、字号大小、
     * 图片尺寸、恢复默认设置）的<b>唯一入口</b>，全部收在 {@link ElderSettingsActivity} 页内。
     */
    private void onElderSettings(View view) {
        ElderSettingsActivity.start(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() != ConfigEvent.Type.COMMON) return;
        refreshConfigText();
        mBinding.wallUrl.setText(WallConfig.getDesc());
    }

}
