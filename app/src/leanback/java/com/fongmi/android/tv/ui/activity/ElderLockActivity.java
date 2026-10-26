package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityElderLockBinding;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.LiveDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.utils.ElderLock;
import com.fongmi.android.tv.utils.Notify;

/**
 * 一键锁定（全站唯一的换源入口）。
 *
 * <p>页面固定 4 项，一对一对应「换哪个源 / 锁哪种行为」：</p>
 * <ul>
 *   <li><b>数据源</b>（{@code @id/site}）：点开 {@link SiteDialog} 的操作栏形态（{@code .action()}）——
 *       一窗两用：点一行即换数据源；右侧竖栏的放大镜 / 换源图标切到「可搜索」「可切换」逐项勾选，
 *       配合「全选 / 取消」批量。</li>
 *   <li><b>播放线路</b>（{@code @id/route}）：自动择优换线开关。</li>
 *   <li><b>搜索优先源</b>（{@code @id/search}）：开启后搜索结果里优先展示锁定的数据源。</li>
 *   <li><b>直播源</b>（{@code @id/live}）：点开 {@link LiveDialog} 的操作栏形态，
 *       一窗两用 —— 点一行即换直播源；启停/屏蔽图标 + 长按批量。</li>
 * </ul>
 *
 * <p>按钮<b>合一</b>：页面只有一个「锁定/解锁」按钮——未锁定时显示「一键锁定」，
 * 点一次即把 数据源 · 直播源 · 搜索优先源 三项一起锁上；已锁定时同一按钮变为「解锁」，
 * 点一次全部回到完全原生状态。</p>
 *
 * <p>⚠ {@code @id/site} 与 {@code @id/live} 是「锁的是哪个源」，必须先解锁才能改；
 * {@code @id/route} 与 {@code @id/search} 是「锁定后的行为开关」，随时可切换、不受解锁约束。</p>
 */
public class ElderLockActivity extends BaseActivity implements SiteListener, LiveListener {

    private ActivityElderLockBinding mBinding;
    private String draftSite;
    private String draftLive;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, ElderLockActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityElderLockBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        draftSite = ElderLock.getSiteKey();
        // 未锁定时草稿跟随当前生效的数据源：老人看到的即可锁定的
        if (draftSite.isEmpty()) draftSite = VodConfig.get().getHome().getKey();
        draftLive = LiveConfig.get().getHome().getName();
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.site.setOnClickListener(this::onPickSite);
        mBinding.route.setOnClickListener(this::onRoute);
        mBinding.search.setOnClickListener(this::onSearch);
        mBinding.live.setOnClickListener(this::onLive);
        mBinding.apply.setOnClickListener(this::onApply);
    }

    private void refresh() {
        boolean locked = ElderLock.isLocked();
        mBinding.siteText.setText(name(draftSite));
        mBinding.routeText.setText(ElderLock.isRouteLocked() ? R.string.elder_lock_route_auto : R.string.elder_lock_route_manual);
        mBinding.searchText.setText(ElderLock.isSearchLocked() ? R.string.elder_lock_search_on : R.string.elder_lock_search_off);
        mBinding.liveText.setText(liveName());
        // 按钮合一：未锁=「一键锁定」可点；已锁=同一按钮变「解锁」，点击即解
        mBinding.apply.setText(locked ? R.string.elder_lock_unlock_entry : R.string.elder_lock_apply);
        mBinding.apply.setTextColor(getColor(locked ? R.color.ag_text_sub : R.color.ag_primary_text));
        mBinding.hint.setText(locked ? R.string.elder_lock_hint_on : R.string.elder_lock_hint_off);
    }

    /** 直播源行文案：显示当前草稿（= 待锁定）的直播源名字；没有可用直播源时显示「未导入」。 */
    private String liveName() {
        if (draftLive != null && !draftLive.isEmpty()) return draftLive;
        return getString(R.string.elder_lock_live_none);
    }

    private String name(String key) {
        Site site = key == null ? new Site() : VodConfig.get().getSite(key);
        return site.isEmpty() ? getString(R.string.elder_lock_none) : site.getName();
    }

    /** 「锁的是哪个源」两项（数据源/直播源）已锁定时不允许改动，必须先点「解锁」。 */
    private boolean needUnlockFirst() {
        if (!ElderLock.isLocked()) return false;
        Notify.show(R.string.elder_lock_need_unlock_first);
        return true;
    }

    /**
     * 数据源：操作栏形态的站源弹窗 —— 点一行即换源；右侧竖栏的放大镜 / 换源图标
     * 切到「可搜索」「可切换」逐项勾选（配合「全选 / 取消」批量）。
     */
    private void onPickSite(View view) {
        if (needUnlockFirst()) return;
        SiteDialog.create().action().show(this);
    }

    /** 播放线路：随时可切换的「锁定后行为开关」，不要求先解锁。 */
    private void onRoute(View view) {
        ElderLock.putRouteLocked(!ElderLock.isRouteLocked());
        refresh();
    }

    /** 搜索优先源：随时可切换；但未锁定数据源时没有「优先源」可优先，给出提示而不是默默无效。 */
    private void onSearch(View view) {
        if (!ElderLock.isLocked()) {
            Notify.show(R.string.elder_lock_search_need_lock);
            return;
        }
        ElderLock.putSearchLocked(!ElderLock.isSearchLocked());
        refresh();
    }

    /** 直播源：操作栏形态的直播弹窗 —— 点一行即换源，启停/屏蔽 + 长按批量由弹窗自己处理。 */
    private void onLive(View view) {
        if (needUnlockFirst()) return;
        if (LiveConfig.get().getLives().isEmpty()) {
            Notify.show(R.string.elder_live_need_config);
            return;
        }
        LiveDialog.create().action().show(this);
    }

    @Override
    public void setSite(Site item) {
        if (item == null || item.isEmpty()) return;
        draftSite = item.getKey();
        refresh();
    }

    @Override
    public void setLive(Live item) {
        if (item == null || item.isEmpty()) return;
        draftLive = item.getName();
        refresh();
    }

    /** 按钮合一：未锁定时执行锁定；已锁定时同一按钮直接解锁。 */
    private void onApply(View view) {
        if (ElderLock.isLocked()) {
            afterUnlock();
            return;
        }
        Site site = draftSite == null ? new Site() : VodConfig.get().getSite(draftSite);
        if (site.isEmpty()) {
            Notify.show(R.string.elder_lock_need_source);
            return;
        }
        doLock(site);
    }

    private void doLock(Site site) {
        ElderLock.lock(draftSite);
        // 复用设置页切换数据源的同一条原生路径，持久化与刷新逻辑完全一致。
        // 固定的是「生效的主数据源」：首页/点播分类/详情/播放线路全部跟随它
        Site pinned = ElderLock.main();
        if (pinned != null) VodConfig.get().setHome(pinned);
        // 一键锁定 = 页面上所有项一起锁：把草稿里的直播源一并固定为生效直播源（未选/未导入则跳过，只锁数据源）
        Live live = draftLive == null || draftLive.isEmpty() ? null : LiveConfig.get().getLive(draftLive);
        if (live == null || live.isEmpty()) {
            // 这个站源没有直播源（或草稿未选）：**绝不能顺手把当前那个直播配置锁住**。
            // 原实现在这里无条件写 ElderLock.putLiveKey(LiveConfig.getUrl())，于是：
            //   ① 用户从没配过直播源时，Config.live() 的 url 可能恰好等于 VodConfig.getUrl()
            //      ⇒ LiveConfig.sync=true ⇒ load() 开头 `if (sync) return;` 直播配置永不加载，
            //         直播页直接复用主数据源的解析器（就是「直播自动调用主数据源」）；
            //   ② 而且 isLiveLocked() 变 true，设置页的直播配置切换被永久拦截，老人再也换不回来。
            // 明确写空串清锁：没有独立直播源就不锁直播，走原生行为。
            ElderLock.putLiveKey("");
        } else {
            LiveConfig.get().setHome(live);
            String liveUrl = LiveConfig.getUrl();
            // 只锁「确实存在、且与主数据源不是同一份」的直播配置（同 url ⇒ sync ⇒ 复用主源，不能锁）。
            if (liveUrl != null && !liveUrl.isEmpty() && !liveUrl.equals(VodConfig.getUrl())) ElderLock.putLiveKey(liveUrl);
            else ElderLock.putLiveKey("");
        }
        Notify.show(getString(R.string.elder_lock_on, site.getName()));
        refresh();
    }

    /** 直接按钮解锁：点击即解锁，无需口令与长按。 */
    private void afterUnlock() {
        ElderLock.unlock();
        draftSite = VodConfig.get().getHome().getKey();
        draftLive = LiveConfig.get().getHome().getName();
        Notify.show(R.string.elder_lock_off);
        refresh();
    }
}
