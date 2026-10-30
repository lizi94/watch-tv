package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.utils.Prefers;

public class Setting {

    private static final int MIN_WALL = 0;
    /** 内置壁纸数量上限（必须与 CustomWallView#WALL_PAPERS 的最后一个下标一致）。 */
    public static final int MAX_WALL = 8;
    private static final int MIN_WALL_TYPE = 0;
    private static final int MAX_WALL_TYPE = 2;
    private static final int MIN_SYNC_MODE = 0;
    private static final int MAX_SYNC_MODE = 2;

    public static String getSwitch(boolean value) {
        return ResUtil.getString(value ? R.string.setting_on : R.string.setting_off);
    }

    public static String getDoh() {
        return Prefers.getString("doh");
    }

    public static void putDoh(String doh) {
        Prefers.put("doh", doh);
    }

    public static String getUa() {
        return Prefers.getString("ua");
    }

    public static void putUa(String ua) {
        Prefers.put("ua", ua);
    }

    public static String getKeyword() {
        return Prefers.getString("keyword");
    }

    public static void putKeyword(String keyword) {
        Prefers.put("keyword", keyword);
    }

    public static String getHot() {
        return Prefers.getString("hot");
    }

    public static void putHot(String hot) {
        Prefers.put("hot", hot);
    }

    public static int getWall() {
        return Math.clamp(Prefers.getInt("wall", 1), MIN_WALL, MAX_WALL);
    }

    public static void putWall(int wall) {
        Prefers.put("wall", Math.clamp(wall, MIN_WALL, MAX_WALL));
    }

    public static int getWallType() {
        return Math.clamp(Prefers.getInt("wall_type", 0), MIN_WALL_TYPE, MAX_WALL_TYPE);
    }

    public static void putWallType(int type) {
        Prefers.put("wall_type", Math.clamp(type, MIN_WALL_TYPE, MAX_WALL_TYPE));
    }

    public static int getThemeColor() {
        return Prefers.getInt("theme_color", -1);
    }

    public static void putThemeColor(int color) {
        Prefers.put("theme_color", color);
    }

    public static int getWallColor() {
        return Prefers.getInt("wall_color", 0);
    }

    public static void putWallColor(int color) {
        Prefers.put("wall_color", color);
    }

    public static int getDynamicColor() {
        int color = getThemeColor();
        if (color == -1) return 0;
        return color != 0 ? color : getWallColor();
    }

    public static int getSyncMode() {
        return Math.clamp(Prefers.getInt("sync_mode"), MIN_SYNC_MODE, MAX_SYNC_MODE);
    }

    public static void putSyncMode(int mode) {
        Prefers.put("sync_mode", Math.clamp(mode, MIN_SYNC_MODE, MAX_SYNC_MODE));
    }

    public static boolean isIncognito() {
        return Prefers.getBoolean("incognito");
    }

    public static void putIncognito(boolean incognito) {
        Prefers.put("incognito", incognito);
    }

    public static boolean isAdblock() {
        return Prefers.getBoolean("adblock", true);
    }

    public static void putAdblock(boolean adblock) {
        Prefers.put("adblock", adblock);
    }

    public static boolean isZhuyin() {
        return Prefers.getBoolean("zhuyin");
    }

    public static void putZhuyin(boolean zhuyin) {
        Prefers.put("zhuyin", zhuyin);
    }

    /**
     * 点播起播即进全屏。适老化默认开启。
     *
     * <p>原生只对「手动点选的那一集」进全屏（见 VideoActivity#onItemClick），
     * 自动连播下一集时不会进；对老人来说等于看着看着画面又缩小了。
     * 关闭本项即完全恢复原生行为。</p>
     */
    public static boolean isAutoFullscreen() {
        return Prefers.getBoolean("auto_fullscreen", true);
    }

    public static void putAutoFullscreen(boolean autoFullscreen) {
        Prefers.put("auto_fullscreen", autoFullscreen);
    }

    /**
     * 播放不出来时，是否自动跳到其它数据源的线路继续试。适老化默认开启。
     *
     * <p>偏好键与「一键锁定 › 播放线路」共用同一项，一处配置两处生效：
     * 线路项打开 = 优先用锁定源自己的线路，全部放不出来再自动跳别的源；
     * 线路项关闭 = 完全按原生行为（站源不可切换时不做任何回退）。</p>
     */
    public static boolean isAutoFallback() {
        return Prefers.getBoolean("elder_lock_route", true);
    }

    public static void putAutoFallback(boolean autoFallback) {
        Prefers.put("elder_lock_route", autoFallback);
    }

    /**
     * 首页快速搜索：index 站源（豆瓣类 —— 只有推荐/分类、没有播放线路）首页内容被点击时，
     * 是否像原生一样跳到搜索页跨源搜同名内容。适老化默认<b>关闭</b>。
     *
     * <p>关闭时点击/长按 index 源内容<b>直接进详情页</b>，由 {@code VodFallbackPolicy}
     * 自动择优选源、择优换线把可播放的线路找出来 —— 老人少一步「挑选搜索结果」，一按就播。
     * 开启时完全回到原生行为（跳搜索页让用户自己挑）。</p>
     *
     * <p>只作用于 index 站源：非 index 站源无论开关如何都直接进详情页，不受本项影响。</p>
     */
    public static boolean isIndexQuickSearch() {
        return Prefers.getBoolean("index_quick_search", false);
    }

    public static void putIndexQuickSearch(boolean indexQuickSearch) {
        Prefers.put("index_quick_search", indexQuickSearch);
    }

    /**
     * 手机版底部导航栏是否显示「直播」入口。适老化默认开启。
     *
     * <p>关闭后首页底部导航不再显示直播 tab，直播只能通过设置页进入；
     * 电视版无底部导航，不使用此项。</p>
     */
    public static boolean isNavLive() {
        return Prefers.getBoolean("elder_nav_live", true);
    }

    public static void putNavLive(boolean navLive) {
        Prefers.put("elder_nav_live", navLive);
    }

    /**
     * 退出自动续播：<b>只门控冷启动时的「开机直达上次节目」</b>。
     * 适老化默认开启（老人开机就能接着看）；关闭后开机停在首页，不再自动跳进播放页。
     *
     * <p><b>为什么只管冷启动</b>：只要还在本次软件会话内（进程存活），最近观看 / 历史记录 /
     * 投屏 / 普通点播（历史里有记录）一律续播 —— 这是用户明确定下的两层语义，
     * 关掉开关不应影响「人已经在软件里、主动点了某一集」的续播预期。</p>
     *
     * <p><b>唯一门控点 = {@code HomeActivity.checkBootDirect()}</b>（leanback 与 mobile 各一份）。
     * 播放链路（{@code VodPlaybackController.startPositionMs()} / {@code seamless()} /
     * {@code VodHistoryPolicy} / {@code VodBrowse}）<b>都不再做开关判断</b>；
     * 保存侧（{@code onStop()} / {@code onDestroy()} / {@code onTimeChanged()}）也无条件写库，
     * 否则软件内退出再进就找不到进度了。</p>
     *
     * <p>「跳过片头」的 opening 是独立配置，与开关和续播位置都无关。</p>
     */
    public static boolean isAutoResume() {
        return Prefers.getBoolean("auto_resume", true);
    }

    public static void putAutoResume(boolean autoResume) {
        Prefers.put("auto_resume", autoResume);
    }

    /**
     * 开机自启动（适老化）：电视开机后是否自动打开「看电视」。默认开启。
     *
     * <p><b>门控点 = {@code BootLauncher#forBoot}</b>（2026-10-30 / R103 起收敛到单一出口，
     * 调用方有 {@code BootReceiver} 的四条开机入口与 {@code RevivalListener}）：
     * 关闭后开机不再拉起 HomeActivity，停留在电视原桌面。</p>
     *
     * <p>与 {@link #isAutoResume()} 分工：本项决定「开机要不要打开 App」，
     * {@code isAutoResume} 决定「打开后要不要直接进上次观看」，两者互不干扰。</p>
     */
    public static boolean isBootAutostart() {
        return Prefers.getBoolean("boot_autostart", true);
    }

    public static void putBootAutostart(boolean bootAutostart) {
        Prefers.put("boot_autostart", bootAutostart);
    }

    /**
     * 接管 Home 键（桌面守护）：开启后按主页键回到电视桌面时，自动切回本软件。
     *
     * <p>默认开启。关闭后 {@code HomeGuardService} 不做任何干预，主页键正常回到电视桌面。
     * 适用场景：想临时用电视自带的桌面 / 应用商店时先关掉它。</p>
     */
    public static boolean isHomeGuard() {
        return Prefers.getBoolean("home_guard", true);
    }

    public static void putHomeGuard(boolean homeGuard) {
        Prefers.put("home_guard", homeGuard);
    }

    /**
     * 用户是否**主动退出**过本软件（2026-10-29 / R99 新增）。
     *
     * <p><b>为什么需要它</b>：{@link #isHomeGuard()} 打开时，{@code HomeGuardService} 只要看到
     * 桌面窗口回到前台（2026-10-30 / R103 起按系统 HOME 名单动态识别，不再写死某个品牌），
     * 就立刻把本软件拉回来 —— 这会让首页「连按三次返回键退出」**永远退不出去**：
     * {@code finish()} → 桌面窗口事件到达（800ms 防抖后）→ 被拉回，而且是**全新冷启动**的实例
     * （正是「反复退出几次后偶发显示还没有设置数据源」的放大器）。用户主动连按三次返回键是一次
     * 明确无误的意图，必须被尊重。</p>
     *
     * <p><b>语义</b>：置位期间桌面守护**不再自动拉起**；直到用户下一次真正打开本软件
     * （桌面图标 / 遥控器快捷键 / 开机自启的 {@code BootReceiver}）时，由
     * {@code HomeActivity#initView} 清零，守护随即恢复正常接管。</p>
     *
     * <p><b>为什么用 Prefers 持久化而不是内存静态标记</b>：退出后进程随时可能被系统回收，
     * 内存标记会跟着消失 ⇒ 守护下一次又把人拉回来。持久化才能真正「记住用户不想用了」。
     * 读写都在主线程，值只有一个 boolean，开销可忽略。</p>
     */
    public static boolean isUserExited() {
        return Prefers.getBoolean("user_exited", false);
    }

    public static void putUserExited(boolean userExited) {
        Prefers.put("user_exited", userExited);
    }

    /**
     * 遥控器按键音（适老化操作反馈音）：焦点移动、按确定、出错时是否发出提示音。
     * 适老化默认开启（老人靠声音确认「遥控器收到了」）；关闭后
     * {@link com.fongmi.android.tv.utils.SoundFx} 的三类音效全部静音。
     *
     * <p>门控点在 SoundFx 内部（tick/confirm/error 统一走一个发声口），因此本项改动
     * 立刻生效、无需重启或重建页面。开关入口在长辈设置页。手机版不注册 SoundFx，
     * 本项对手机端无影响。</p>
     */
    public static boolean isKeySound() {
        return Prefers.getBoolean("elder_key_sound", true);
    }

    public static void putKeySound(boolean keySound) {
        Prefers.put("elder_key_sound", keySound);
    }

    /**
     * 电视版全局字号档位（0=标准 / 1=大 / 2=特大 / 3=超大 / 4=极大）。适老化默认标准。
     *
     * <p>档位系数作用在全局 density 与 scaledDensity 上，文字、按钮、边框、
     * 圆角、间距等全部 dp/sp 元素（含提示文字）等比缩放、比例不变
     * （见 BaseActivity#hackResources）。手机版不读取本项。</p>
     */
    public static int getFontSize() {
        return Prefers.getInt("elder_font_size", 0);
    }

    public static void putFontSize(int size) {
        Prefers.put("elder_font_size", Math.max(0, Math.min(4, size)));
    }

    /** 字号档位对应的全局缩放倍率：0→1.2 / 1→1.4 / 2→1.6 / 3→1.8 / 4→2.0（整体上调一档，标准即 1.2）。 */
    public static float getFontScale() {
        return switch (getFontSize()) {
            case 1 -> 1.4f;
            case 2 -> 1.6f;
            case 3 -> 1.8f;
            case 4 -> 2.0f;
            default -> 1.2f;
        };
    }
}
