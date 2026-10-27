package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放回退的唯一规则：一个顺序推进器。
 *
 * <p>进详情页跟随媒体卡片源起播；失败（播放器错误或 10 秒起播看门狗超时）后按固定顺序推进：
 * <b>顺序换下一条线路 → 线路试完顺序换下一个源 → 新源再从线路 1 顺序试</b>，循环直到起播成功；
 * 候选源与线路全部试完 → 用最后一次真实错误提示用户。没有健康分、没有择优、没有原地重试。</p>
 *
 * <ul>
 *   <li><b>候选源</b> = 站点管理可见列表（{@code VodConfig#getSites()}，用户关掉的源天然不参与）
 *       中可搜索的源；跨源搜索一次，候选按「片名与关键字逐字相等」过滤（同 id / 已失败源剔除），
 *       宁可换不动也绝不放错片；</li>
 *   <li><b>index 站源特例</b>：它只有数据、没有播放线路，详情必然为空 —— 无视开关直接跨源，
 *       是整条链里唯一不看「自动换源」开关的路径；换源过程中若换到另一个 index 候选，
 *       它的空详情同样触发「继续下一个」，顺序机自然跳过；</li>
 *   <li><b>总闸</b>：「自动换源」关闭（且非 index）= 完全原生 —— 失败即停，展示真实错误，
 *       不换线不跨源；</li>
 *   <li><b>手动换源</b>与自动同一顺序链：跳过当前源取下一个候选（无候选先搜索），明确意图不受开关限制；</li>
 *   <li><b>用户主动操作</b>（返回 / 选集 / 手动选线路 / 手动换源）立即终止自动链（现有机制）。</li>
 * </ul>
 */
class VodFallbackPolicy {

    private final VodPlaybackController controller;
    private final VodPlaybackState state;
    private final VodPlaybackHost host;
    private final VodDataSource dataSource;

    VodFallbackPolicy(VodPlaybackController controller, VodPlaybackState state, VodPlaybackHost host, VodDataSource dataSource) {
        this.controller = controller;
        this.state = state;
        this.host = host;
        this.dataSource = dataSource;
    }

    /** 播放失败（播放器 onError 或看门狗超时）。msg = 底层真实错误，最终终止时原样展示。 */
    void playbackError(String msg) {
        advance(msg);
    }

    /** 线路列表为空：该源当前没有可试的线路。 */
    void emptyFlag() {
        advance("");
    }

    /** 详情为空：该源当前整体不可用。 */
    void emptyDetail() {
        advance("");
    }

    /**
     * 跨源搜索的结算窗口。
     *
     * <p>搜索是「每个站源各回调一次」，没有一个「全部完成」的信号（多数站源靠超时结束，
     * 最长 {@code TIMEOUT_SEARCH} = 30 秒）。所以另开一个较短的定时器兜底：
     * 到点若一个候选都没攒到，才判定「真的没有同一部片」并向用户报错。
     * 有候选时<b>不等</b>这个定时器 —— 第一个非空结果到达就立即换源。</p>
     */
    private static final long SEARCH_SETTLE_MS = 15000L;

    /** 本轮搜索是否已经换过源（一次搜索只换一次，其余结果只入候选池）。 */
    private boolean searchConsumed;
    /** 本轮搜索是不是用户<b>手动</b>点「换源」发起的：手动意图必须放行，见 {@link #onSearchResult}。 */
    private boolean manualSearch;
    /** 搜索结算任务：见 {@link #SEARCH_SETTLE_MS}。 */
    private final Runnable searchSettle = this::settleSearch;

    /**
     * 手动换源（双端「换源」按钮 / 错误页「重试」）：清空本轮记录重新找一遍。
     *
     * <p>⛔ 旧写法 {@code if (hasSources()) nextSource(); else search();} 有三个死角，合起来就是
     * 「换源按钮有时点了没反应」：</p>
     * <ol>
     *   <li>池里只剩已失败过的源 → {@code nextSource()} 空手而归、<b>静默 return</b>，界面毫无变化；</li>
     *   <li>走 {@code search()} 时若画面早已播起来，{@code onSearchResult} 的
     *       {@code hasPlaybackStarted} 守卫会把结果全部丢掉 —— 锁定了能播的源、
     *       或从搜索 / 历史 / 收藏进详情页时必中；</li>
     *   <li>自动链换了一圈全失败后，所有候选都被 failedIds 标记，再点换源同样一个候选都找不到。</li>
     * </ol>
     *
     * <p>现在一律「清干净 + 重新搜」：清空候选池、失败记录、已试线路，再用「手动」标记发起搜索，
     * 保证每次点击都有可见反馈（先弹「正在查找其它站源…」，找到后换过去，找不到再明确提示）。
     * 当前源由 {@link #mismatch} 按 id 排除，不会原地打转。</p>
     */
    void manualSwitchSource() {
        state.clearSources();
        state.clearFailedIds();
        state.clearTriedFlags();
        host.showDetailMessage(ResUtil.getString(R.string.detail_switch_searching));
        search(host.getVodName(), true);
    }

    void search(String keyword) {
        search(keyword, false);
    }

    private void search(String keyword, boolean manual) {
        manualSearch = manual;
        searchConsumed = false;
        state.setSearchKeyword(keyword);
        host.onSearchStarted(keyword);
        // ⚠ 先撤上一轮残留的结算再排新的：顺序链会连着搜好几次，
        // 定时器叠加会让上一轮的结算在本轮中途到点，凭空多换一次源。
        App.removeCallbacks(searchSettle);
        App.post(searchSettle, SEARCH_SETTLE_MS);
        // ⚠ quick 必须是 false：SearchTask 里 `if (quick && !site.isQuickSearch()) return Result.empty()`，
        // 只有配了 quickSearch=1 的站源才真的去搜，其余一律空结果 ——
        // 那就是「明明有片却一直提示找不到」。搜索页（CollectActivity）用的也是 false。
        dataSource.searchContent(getSearchableSites(), keyword, false);
    }

    /** 页面销毁 / 会话重置时撤掉结算定时器，避免在死掉的 Activity 上弹提示。 */
    void cancelSearch() {
        App.removeCallbacks(searchSettle);
        manualSearch = false;
    }

    /**
     * 跨源搜索的<b>单个站源</b>结果。
     *
     * <p>⛔ 这里不是「一次汇总」，而是每个站源各来一次（{@code ViewModelSearchRunner} 逐个 submit
     * 并各自回调）。按汇总处理会出两件事：① 后到的结果把先攒的候选覆盖掉；
     * ② 某个站源返回空就立刻弹「未找到同一影片」—— 而其它站源其实马上就有结果。
     * 表现就是「不停换源 + 一直提示找不到」。</p>
     */
    void onSearchResult(Result result) {
        // ① 页面已经关了 —— 不该再动
        if (host.isHostFinishing()) return;
        // ② 画面早就播起来了：自动链不该抢（用户没要求换），
        //    但<b>手动</b>换源必须放行 —— 否则「播得好好的，点换源却没反应」。
        if (!manualSearch && controller.hasPlaybackStarted()) return;
        List<Vod> items = new ArrayList<>(result.getList());
        items.removeIf(this::mismatch);
        if (items.isEmpty()) return;   // 这个站源没匹配上：忽略，等其它站源
        state.addSources(items);       // 累加，绝不覆盖
        // 已有线路正在起播（还没判成败）：只入池，别打断它
        if (controller.isPlayPending()) return;
        if (searchConsumed) return;    // 本轮搜索已经换过一次源了
        searchConsumed = true;
        nextSource();
    }

    /** 结算：一个候选都没攒到才报错。手动换源只提示，绝不停掉正在播的画面。 */
    private void settleSearch() {
        if (host.isHostFinishing()) return;
        boolean manual = manualSearch;
        if (!manual && controller.hasPlaybackStarted()) return;
        if (controller.isPlayPending()) return;
        if (searchConsumed) return;
        searchConsumed = true;
        manualSearch = false;
        if (state.hasSources()) {
            nextSource();
            return;
        }
        String msg = ResUtil.getString(R.string.error_no_matching_source);
        if (manual) host.showDetailMessage(msg);
        else host.resetPlaybackForError(msg);
    }

    /**
     * 顺序推进器（唯一的回退规则）：
     * ① 总闸：「自动换源」关闭且不是 index 站源 → 完全原生，失败即停，展示真实错误；
     * ② 顺序换下一条未试线路（无多条线路 / 全部试过 → 转换源）；
     * ③ 顺序换下一个候选源（队列为空先发起一次跨源搜索）；
     * ④ 候选耗尽（第二次搜索必无新候选，见 failedIds 单调性）→ 终止，展示真实错误。
     */
    private void advance(String msg) {
        // 页面已经关了就别再折腾：此时换线/换源/弹提示都是在死掉的 Activity 上操作。
        if (host.isHostFinishing()) return;
        if (!Setting.isAutoFallback() && !host.getSite().isIndex()) {
            // msg 为空 = 这个源压根没有可播的东西（详情空 / 没有线路），不是超时也不是网络问题，
            // 提示「连接超时」会把老人往错误的方向上引，所以要分开说。
            host.resetPlaybackForError(msg.isEmpty() ? ResUtil.getString(R.string.error_no_playable_line) : msg);
            return;
        }
        if (fallbackToNextLine()) return;
        if (state.hasSources()) {
            nextSource();
            return;
        }
        search(host.getVodName());
    }

    /**
     * 顺序换线：按线路列表取下一条未试的。
     *
     * <p>无多条线路（≤1 条，用户规则：没有多条线路就直接换源）或全部试过 → false 转换源。
     * 并列无优先级，第一次使用与原生顺序一致。</p>
     */
    private boolean fallbackToNextLine() {
        List<Flag> flags = state.getFlags();
        if (flags.size() < 2) return false;
        Flag current = state.getFlag();
        state.addTriedFlag(current.getFlag());
        for (Flag flag : flags) {
            if (state.getTriedFlags().contains(flag.getFlag())) continue;
            host.showSwitchLine(flag);
            controller.selectFlag(flag);
            return true;
        }
        return false;
    }

    /** 顺序换源：候选队列取第一个还没试过失败的；换源后从新源第一条线路重新顺序试。 */
    private void nextSource() {
        // 候选池是跨轮次累加的，里面可能残留上一轮已经证明播不了的源
        // （正常路径下 mismatch 过滤过一遍，这里是防御：绝不把一次起播浪费在已知坏源上）。
        Vod item = null;
        while (state.hasSources()) {
            Vod first = state.getSources().get(0);
            state.removeSource(first);
            if (!state.hasFailedId(first.getId())) {
                item = first;
                break;
            }
        }
        if (item == null) {
            // ⛔ 这里原来是「静默 return」：候选池里全是已试过的源时（自动链换过一圈之后），
            // 手动换源会一路走到这里然后什么都不做 —— 按钮看起来就是坏的。
            // 改成再搜一轮：本轮搜索结果按 failedIds 过滤，若真一个新源都没有，
            // 15 秒后的结算会给出明确提示（手动=弹提示，自动=展示错误并停）。
            search(host.getVodName(), manualSearch);
            return;
        }
        host.renderSources(state.getSources());
        host.showSwitchSource(item);
        state.addFailedId(host.getVodId());
        // 换了数据源，线路完全是另一套，已试线路重新计
        state.clearTriedFlags();
        controller.fallbackSource(item);
    }

    /**
     * 跨源搜索的源列表：站点管理可见列表（{@code getSites()} 已过滤用户关掉的源）中可搜索的源。
     */
    private List<Site> getSearchableSites() {
        List<Site> sites = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) if (site.isSearchable()) sites.add(site);
        return sites;
    }

    /**
     * 同一部片的候选过滤：同 id / 已失败过的源剔除；片名与关键字<b>逐字相等</b> ——
     * 顺序链会把候选逐个真的拿去起播，宁可换不动也绝不放错片。
     *
     * <p>⛔ 不做任何归一化（不繁转简、不去标点、不去「更新至N集」这类后缀）：
     * 与上游 {@code VodFallbackPolicy#mismatch} 的自动档保持一致。
     * 放宽就会把「庆余年」匹配到「庆余年2」，老人看很久才发现放错了片，那种伤害远大于换源失败。</p>
     */
    private boolean mismatch(Vod item) {
        if (host.getVodId().equals(item.getId())) return true;
        if (state.hasFailedId(item.getId())) return true;
        return !item.getName().equals(state.getSearchKeyword());
    }
}
