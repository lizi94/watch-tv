package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.AppInfo;
import com.fongmi.android.tv.databinding.ActivityAppListBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.dialog.ConfirmDialog;
import com.fongmi.android.tv.ui.presenter.AppPresenter;
import com.fongmi.android.tv.utils.AppQuery;
import com.fongmi.android.tv.utils.LauncherCapability;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用列表（R101）：首页功能行「应用」的落点，也是「看电视已是默认桌面」形态下唯一的出口。
 *
 * <p><b>第一格固定「系统设置」是硬需求</b>：第三方桌面把用户困住的事故几乎都死于同一个原因 ——
 * 桌面里没有任何通往系统设置的入口（详见方案 5.4）。系统设置的包名在拼音序里很靠后，
 * 老人翻不到，所以钉在第一格。</p>
 *
 * <p><b>管理动作走「两步式」</b>：长按某一格 → 顶部出现管理行（放到最前 / 隐藏起来 / 应用信息），
 * 再选动作；按返回先退出管理模式。这与收藏页 / 历史页的「先进入批量模式再操作」是同一套交互
 * （老人不会在长按的那一刻就被改掉东西），也避免了新造一套弹窗样式（弹窗样式铁律 ★S）。</p>
 *
 * <p><b>不做的两件事，都是刻意的</b>：
 * ① **不做卸载**：{@code ACTION_DELETE} 对别的包发起卸载在 API 26+ 必须声明
 *    {@code REQUEST_DELETE_PACKAGES}，不加则静默失败（部分 ROM 连提示都没有），
 *    加上又多一个敏感权限面。而「应用信息」页本来就有卸载按钮，且系统页自带二次确认 ——
 *    对老人反而更稳。净收益：少一条权限、少一套回调查询、少一类误删风险。
 * ② **不做强制停止**：需要特权 / root，且对老人只有害处。</p>
 */
public class AppListActivity extends BaseActivity implements AppPresenter.OnClickListener {

    /** 筛选：全部 / 我的应用（非系统）/ 已隐藏。 */
    private static final int MODE_ALL = 0;
    private static final int MODE_MINE = 1;
    private static final int MODE_HIDDEN = 2;

    /** 置顶上限：一排正好一格不多，超出时提示先取消一个。 */
    private static final int PIN_LIMIT = 6;

    private ActivityAppListBinding mBinding;
    private AppPresenter mAdapter;
    private int mode = MODE_ALL;
    /** 正在管理的应用（长按进入管理模式）；null = 普通模式。 */
    private AppInfo manageTarget;
    /** 全部可启动应用（含被隐藏的），label 序 —— 三个筛选页都从这一份派生。 */
    private final List<AppInfo> raw = new ArrayList<>();
    /** 视图正在重建中（避免重复 reload）。 */
    private boolean loading;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, AppListActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityAppListBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        mBinding.progressLayout.showProgress();   // 查询要遍历几十上百个包，先给个转圈，别让老人以为坏了
        mBinding.chipAll.requestFocus();
        reload(true);
    }

    @Override
    protected void initEvent() {
        mBinding.chipAll.setOnClickListener(view -> switchMode(MODE_ALL));
        mBinding.chipMine.setOnClickListener(view -> switchMode(MODE_MINE));
        mBinding.chipHidden.setOnClickListener(view -> switchMode(MODE_HIDDEN));
        mBinding.homeSwitch.setOnClickListener(view -> onHomeSwitch());
        mBinding.pinButton.setOnClickListener(view -> onPin());
        mBinding.hideButton.setOnClickListener(view -> onHide());
        mBinding.infoButton.setOnClickListener(view -> onInfo());
        mBinding.uninstallButton.setOnClickListener(view -> onUninstall());
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setAdapter(mAdapter = new AppPresenter(this));
        // span 必须与 AppPresenter 的格宽公式用**同一个夹取后的值**，否则行尾会空半格
        //（KeepActivity 注释里记过同款坑：间距与卡片宽度必须同域同值）。
        int span = AppPresenter.column();
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, span));
        mBinding.recycler.addItemDecoration(SpaceItemDecoration.ofPx(span, Product.gap(this)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「应用信息」页 / 别的应用回来时重查一次（可能有安装/卸载）。只有集合真的变了才刷新，
        // 无变化时不动 —— 否则每次回来焦点都会丢。
        reload(false);
        refreshHomeSwitch();
    }

    // ------------------------------------------------------------------ 数据

    /**
     * 重新查询并刷新列表。
     *
     * @param force true = 无论变没变都 setItems（首次进入、切换筛选时用）
     */
    private void reload(boolean force) {
        if (loading) return;
        loading = true;
        Task.execute(() -> {
            // ⛔ 必须在后台线程：一次要遍历几十上百个包、还要读 label。
            List<AppInfo> loaded = AppQuery.loadAll(getApplicationContext());
            App.post(() -> {
                loading = false;
                raw.clear();
                raw.addAll(loaded);
                refreshChips();
                applyList(force);
            });
        });
    }

    /** 按当前筛选把 raw 整理成要展示的列表。 */
    private void applyList(boolean force) {
        applyList(force, null);
    }

    /**
     * @param focusAfter 刷新完成后要把焦点落到哪一格（位置）；null = 不动焦点。
     *                   隐藏 / 取消隐藏后原来那一格就没了，焦点必须有个去处，否则遥控器像「失灵」。
     */
    private void applyList(boolean force, Integer focusAfter) {
        List<AppInfo> list = arrange();
        if (!force && sameAs(list)) {
            if (focusAfter != null && focusAfter < mAdapter.getItemCount()) mBinding.recycler.scrollToPosition(focusAfter);
            return;
        }
        boolean empty = list.isEmpty();
        // 空态用本页自己的一句话（「这台电视上没找到别的应用」），不用 ProgressLayout 的通用
        // error_empty —— 那句对老人来说等于没说。为空时整块内容区让位，只留这一行字。
        mBinding.empty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mBinding.empty.setText(mode == MODE_HIDDEN ? R.string.app_empty_hidden : R.string.app_empty);
        mBinding.progressLayout.setVisibility(empty ? View.GONE : View.VISIBLE);
        mAdapter.setItems(list);
        mBinding.progressLayout.showContent();
        if (focusAfter != null) {
            int target = Math.min(focusAfter, Math.max(0, list.size() - 1));
            mBinding.recycler.post(() -> mBinding.recycler.scrollToPosition(target));
        }
    }

    /** 现有展示项是否与新列表完全一致（按包名逐个比）。 */
    private boolean sameAs(List<AppInfo> list) {
        List<AppInfo> current = mAdapter.getItems();
        if (current.size() != list.size()) return false;
        for (int i = 0; i < list.size(); i++) {
            if (!current.get(i).getPkg().equals(list.get(i).getPkg())) return false;
        }
        return true;
    }

    private List<AppInfo> arrange() {
        Set<String> hidden = AppQuery.split(Setting.getHiddenApps());
        List<AppInfo> out = new ArrayList<>();
        for (AppInfo info : raw) {
            boolean isHidden = hidden.contains(info.getPkg());
            if (mode == MODE_HIDDEN) {
                if (isHidden) out.add(info);
            } else if (mode == MODE_MINE) {
                if (!isHidden && !info.isSystem()) out.add(info);
            } else {
                if (!isHidden) out.add(info);
            }
        }
        if (mode == MODE_ALL) {
            // 置顶优先级 + 「系统设置」恒第一格
            sortPinned(out);
            out = AppQuery.withSettingsFirst(this, out, getString(R.string.app_system_settings));
        }
        return out;
    }

    /**
     * 置顶排序：按设置里的顺序排前面，其余保持 label 序（raw 已是拼音序）。
     * ⚠ 用 splitOrdered：置顶是**有序**的，HashSet 会把顺序吃掉 ⇒ 老人点了「放到最前」却排到第二个。
     */
    private void sortPinned(List<AppInfo> list) {
        List<String> pinned = AppQuery.splitOrdered(Setting.getPinnedApps());
        for (int i = 0; i < pinned.size(); i++) {
            String pkg = pinned.get(i);
            for (AppInfo info : list) if (info.getPkg().equals(pkg)) info.setPinned(i + 1);
        }
        list.sort((a, b) -> {
            int pa = a.getPinned() == 0 ? Integer.MAX_VALUE : a.getPinned();
            int pb = b.getPinned() == 0 ? Integer.MAX_VALUE : b.getPinned();
            return pa == pb ? 0 : Integer.compare(pa, pb);
        });
    }

    // ------------------------------------------------------------------ 筛选

    private void switchMode(int next) {
        if (mode == next) return;
        mode = next;
        exitManage();
        refreshChips();
        applyList(true);
    }

    private void refreshChips() {
        mBinding.chipAll.setSelected(mode == MODE_ALL);
        mBinding.chipMine.setSelected(mode == MODE_MINE);
        mBinding.chipHidden.setSelected(mode == MODE_HIDDEN);
        int count = hiddenCount();
        mBinding.chipHidden.setVisibility(count == 0 && mode != MODE_HIDDEN ? View.GONE : View.VISIBLE);
        mBinding.chipHidden.setText(getString(R.string.app_chip_hidden, count));
    }

    /** 已隐藏数量。用当前 raw 现数，比读偏好更准（偏好里的包可能已被卸载）。 */
    private int hiddenCount() {
        Set<String> hidden = new HashSet<>(AppQuery.split(Setting.getHiddenApps()));
        int count = 0;
        for (AppInfo info : raw) if (hidden.contains(info.getPkg())) count++;
        return count;
    }

    /**
     * 右上角桌面胶囊（只在「本应用是默认桌面」时出现）。
     * 本机只剩我们一个 HOME 候选时，文案改成「打开系统设置」—— 那时候「换回默认桌面」无处可选。
     */
    private void refreshHomeSwitch() {
        if (!LauncherCapability.isSelfHome(this)) {
            mBinding.homeSwitch.setVisibility(View.GONE);
            return;
        }
        mBinding.homeSwitch.setVisibility(View.VISIBLE);
        mBinding.homeSwitch.setText(LauncherCapability.isOnlyHome(this) ? R.string.app_open_settings : R.string.app_switch_home);
    }

    private void onHomeSwitch() {
        boolean ok = LauncherCapability.isOnlyHome(this)
                ? LauncherCapability.openSettings(this)
                : LauncherCapability.openHomeSettings(this);
        if (!ok) Notify.show(R.string.app_switch_home_failed);
    }

    // ------------------------------------------------------------------ 打开 / 管理

    @Override
    public void onItemClick(AppInfo item) {
        if (manageTarget != null) {
            // 管理模式下的单击只是「换个目标」，不打开应用 —— 防止老人以为在选目标却跳走了
            enterManage(item);
            return;
        }
        if (!AppQuery.launch(this, item)) Notify.show(R.string.app_open_failed);
    }

    @Override
    public void onItemLongClick(AppInfo item) {
        enterManage(item);
    }

    /** 进入/切换管理模式。固定项（系统设置）不允许隐藏，但仍可「应用信息」。 */
    private void enterManage(AppInfo item) {
        manageTarget = item;
        mBinding.manageRow.setVisibility(View.VISIBLE);
        mBinding.manageText.setText(getString(R.string.app_manage_target, item.getLabel()));
        boolean pinned = AppQuery.isPinned(Setting.getPinnedApps(), item.getPkg());
        mBinding.pinButton.setText(pinned ? R.string.app_menu_unpin : R.string.app_menu_pin);
        boolean hidden = AppQuery.split(Setting.getHiddenApps()).contains(item.getPkg());
        mBinding.hideButton.setText(hidden ? R.string.app_menu_unhide : R.string.app_menu_hide);
        mBinding.hideButton.setVisibility(AppQuery.PKG_SETTINGS.equals(item.getPkg()) ? View.GONE : View.VISIBLE);
        // 系统应用不可卸载（系统卸载页自己也会拒绝）：直接不给入口，含固定第一格的「系统设置」
        mBinding.uninstallButton.setVisibility(item.isSystem() ? View.GONE : View.VISIBLE);
        mBinding.pinButton.requestFocus();
    }

    private void exitManage() {
        manageTarget = null;
        mBinding.manageRow.setVisibility(View.GONE);
    }

    private void onPin() {
        if (manageTarget == null) return;
        List<String> pinned = AppQuery.splitOrdered(Setting.getPinnedApps());
        if (pinned.remove(manageTarget.getPkg())) {
            Setting.putPinnedApps(AppQuery.join(pinned));
        } else {
            if (pinned.size() >= PIN_LIMIT) {
                Notify.show(R.string.app_pin_hint);
                return;
            }
            pinned.add(0, manageTarget.getPkg());
            Setting.putPinnedApps(AppQuery.join(pinned));
        }
        exitManage();
        // 置顶会改变顺序 ⇒ 必须 force，否则 sameAs 认为「没变化」而不刷新
        applyList(true, 0);
        // 焦点留在网格里（刚置顶那一项就在第一排），不要把人甩回筛选行
        mBinding.recycler.requestFocus();
    }

    private void onHide() {
        if (manageTarget == null) return;
        String pkg = manageTarget.getPkg();
        List<String> list = AppQuery.splitOrdered(Setting.getHiddenApps());
        if (list.remove(pkg)) {
            // 已在「已隐藏」里 ⇒ 这次是「显示出来」
            Setting.putHiddenApps(AppQuery.join(list));
            doneHide();
            return;
        }
        ConfirmDialog.create()
                .message(getString(R.string.app_hide_confirm, manageTarget.getLabel()))
                .callback(() -> {
                    List<String> next = AppQuery.splitOrdered(Setting.getHiddenApps());
                    if (!next.contains(pkg)) next.add(pkg);
                    Setting.putHiddenApps(AppQuery.join(next));
                    doneHide();
                })
                .show(this);
    }

    /** 隐藏/恢复之后的统一收尾：刷新 chips 与列表，并把焦点交给第一格（原来那格已不在）。 */
    private void doneHide() {
        exitManage();
        refreshChips();
        applyList(true, 0);
        mBinding.chipAll.requestFocus();
    }

    private void onInfo() {
        if (manageTarget == null) return;
        boolean ok = LauncherCapability.openAppDetails(this);
        exitManage();
        if (!ok) Notify.show(R.string.app_info_failed);
    }

    /**
     * 卸载（R122）：确认弹窗 → 系统卸载页（自带二次确认，两道确认防误触）→ 返回后
     * onResume 的 reload 自动重查列表（应用已消失），这里只需把管理态收掉。
     */
    private void onUninstall() {
        if (manageTarget == null) return;
        String pkg = manageTarget.getPkg();
        ConfirmDialog.create()
                .message(getString(R.string.app_uninstall_confirm, manageTarget.getLabel()))
                .callback(() -> {
                    if (LauncherCapability.uninstall(this, pkg)) {
                        // 卸载页接管前台，管理态里那个目标随时会消失 —— 统一退出管理态
                        exitManage();
                    } else {
                        Notify.show(R.string.app_uninstall_failed);
                    }
                })
                .show(this);
    }

    @Override
    protected void onBackInvoked() {
        // 返回键语义：先退出管理模式，再回首页（继承 BaseActivity 的 backToHome() → goHome()）。
        // ⛔ 不要在这里覆写 backToHome() 为 false —— 那会走「三连退退出应用」的语义，
        //    与本页「回去上一层」的期望相反（复审时改过这一处）。
        if (manageTarget != null) {
            exitManage();
            mBinding.chipAll.requestFocus();
            return;
        }
        super.onBackInvoked();
    }
}
