package com.fongmi.android.tv.utils;

import android.content.SharedPreferences;

import com.github.catvod.utils.Prefers;

/**
 * 老人友好的辅助能力（双端共享）。
 *
 * <p><b>一键恢复默认（2026-10-12 重做）</b>：把偏好里<b>本改版自己引入的设置项</b>逐个删回出厂默认，
 * 使「长辈设置」页上所有的项回到初始值；<b>页面上没有暴露的项一律不动</b>。</p>
 *
 * <h3>为什么不再用「删掉除 config_ 以外的全部键」</h3>
 * <p>旧实现按前缀反选：只保留 {@code config_} 前缀，其余键全删。电视盒子上用户往往手动配过
 * 字幕样式、弹幕、解码方式、代理、UA 等，这些项在长辈设置页上<b>根本看不到</b>，
 * 却在「恢复默认」时被一起清掉 —— 老人按一下就丢配置，且无从恢复，属于实打实的误伤。</p>
 *
 * <h3>白名单口径</h3>
 * <p>取值口径与「长辈设置」页（{@link com.fongmi.android.tv.ui.activity.ElderSettingsActivity}）
 * 一一对应：页面上出现几项，这里就重置几项。新增设置项时必须同步登记，否则会「恢复不干净」。</p>
 *
 * <h3>刻意不动的项</h3>
 * <ul>
 *   <li>{@code config_*} —— 数据源 / 直播源 / 壁纸配置，清掉老人就找不回节目源了；</li>
 *   <li>收藏、观看记录 —— 存在数据库里，本类从不触碰；</li>
 *   <li>{@code VodConfig} 当前选中的首页源 —— 由 {@code VodConfig.setHome()} 持久化，
 *       与数据源配置同源，一并保留；</li>
 *   <li>上游原生设置（{@code subtitle_*} / {@code danmaku_*} / {@code audio_*} /
 *       {@code player_engine} / {@code decode_*} / {@code doh} / {@code proxy} / {@code ua} …）
 *       —— 改版没有动过它们的入口，也不该由长辈设置页来决定去留。</li>
 * </ul>
 *
 * <p>删除而非写入默认值：删掉后各 getter 自然返回自己的默认值，与「从未设置过」完全等价，
 * 不会出现「写了一份默认值、以后改默认可不生效」的隐患。</p>
 */
public class ElderMode {

    /**
     * 需要恢复默认的偏好键 —— <b>精确白名单</b>，与长辈设置页逐项对应。
     *
     * <ul>
     *   <li>{@code elder_lock_on} / {@code elder_lock_site} / {@code elder_lock_live}
     *       / {@code elder_lock_search} / {@code elder_lock_route} —— 一键锁定与其下各项；</li>
     *   <li>{@code auto_fullscreen} —— 点播自动全屏；</li>
     *   <li>{@code auto_resume} —— 退出自动续播；</li>
     *   <li>{@code index_quick_search} —— 首页快速搜索（index 站源点击是否跳搜索）；</li>
     *   <li>{@code elder_key_sound} —— 按键音；</li>
     *   <li>{@code elder_font_size} —— 字号档位；</li>
     *   <li>{@code size} —— 图片尺寸（决定首页 / 分类页的列数与卡片大小）；</li>
     *   <li>{@code elder_nav_live} —— 手机版底部导航「直播」入口（电视版不读，登记以保持双端口径一致）；</li>
     *   <li>{@code site_disabled} —— 用户关掉的站源（站源选择，见 {@link com.fongmi.android.tv.utils.SiteVisibility}）。</li>
     * </ul>
     */
    private static final String[] RESET_KEYS = {
            "elder_lock_on",
            "elder_lock_site",
            "elder_lock_live",
            "elder_lock_search",
            "elder_lock_route",
            "auto_fullscreen",
            "auto_resume",
            "index_quick_search",
            "elder_key_sound",
            "elder_font_size",
            "size",
            "elder_nav_live",
            "site_disabled",
    };

    private ElderMode() {
    }

    /**
     * 把改版引入的设置项全部恢复为出厂默认，其余偏好（含数据源配置与上游原生设置）原样保留。
     *
     * <p>调用后需重新加载配置并刷新界面：字号档位（{@code elder_font_size}）与图片尺寸
     * （{@code size}）都会改变布局，调用方（长辈设置页）负责 {@code initConfig()} 与广播刷新。</p>
     */
    public static void restoreDefault() {
        SharedPreferences prefers = Prefers.getPrefers();
        SharedPreferences.Editor editor = prefers.edit();
        for (String key : RESET_KEYS) editor.remove(key);
        editor.apply();
    }
}
