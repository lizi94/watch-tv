package com.fongmi.android.tv.utils;

import android.media.AudioManager;
import android.media.ToneGenerator;

import com.fongmi.android.tv.setting.Setting;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 电视版操作反馈音（适老化）：焦点移动 / 确定 / 出错时用 ToneGenerator 内置音源发声，
 * 无需打包音频资源，也不受系统按键音设置影响。
 *
 * <p>只在电视版调用（BaseActivity 全局焦点监听 + 播放页出错路径），手机版不注册。</p>
 *
 * <p><b>总开关</b>：长辈设置页的「按键音」（{@link Setting#isKeySound()}，默认开启）。
 * 三个方法统一走 {@link #play(int, int)}，门控只写一处；读取的是实时偏好，
 * 因此在设置页关掉后立刻静音、不需要重启或重建页面。</p>
 *
 * <h3>⛔ 为什么发声必须在后台线程（2026-10-28 遥控器假死排查）</h3>
 * <p>{@code ToneGenerator.startTone()} 是到 AudioFlinger 的<b>同步 Binder 调用，没有超时</b>。
 * 它的调用点是 {@code BaseActivity#dispatchKeyEvent}（每次按确定键）
 * 与全局焦点监听（每次焦点移动）—— 都在<b>主线程</b>。播放时 ExoPlayer 正占着音频通道，
 * 电视在 HDMI 重协商 / 音频格式切换时只要抖动一下，主线程就会被卡在这个调用上，
 * 期间所有按键都在输入队列里排队 ⇒ 表现为「遥控器失灵，只有电源/音量键能用」。
 * 因此这里统一挪到单线程执行器：主线程只入队后立即返回。</p>
 *
 * <h3>两条自保策略</h3>
 * <ul>
 *   <li><b>丢弃积压</b>：上一声还没发完就直接丢掉新请求 —— 连按方向键时迟到的滴声毫无意义，
 *       若全部排队反而会让提示音滞后到与操作无关。按键音只要「有」就够。</li>
 *   <li><b>一次熔断</b>：{@code startTone} 抛任何 Throwable 就把实例丢弃并永久关闭本次进程的发声，
 *       不再反复重试。音频服务一旦坏掉，重试只会一次次付 Binder 超时代价。</li>
 * </ul>
 */
public class SoundFx {

    /** 发声专用单线程：ToneGenerator 的构造 / startTone / release 全部落在同一个线程上。 */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "elder-key-sound");
        thread.setDaemon(true);
        return thread;
    });

    /** 已入队、尚未发完的请求数。> 0 时新请求直接丢弃（见类注释「丢弃积压」）。 */
    private static final AtomicInteger pending = new AtomicInteger();

    /** 熔断标记：一旦发声抛异常就置位，本进程内不再尝试。 */
    private static volatile boolean broken;

    private static ToneGenerator tone;

    private SoundFx() {
    }

    /** 焦点移动：短促滴声（40ms，几乎不打断当前声音）。 */
    public static void tick() {
        play(ToneGenerator.TONE_DTMF_4, 40);
    }

    /** 确定：清脆一声。 */
    public static void confirm() {
        play(ToneGenerator.TONE_PROP_ACK, 60);
    }

    /** 出错：低沉的否定音（播放失败、加载失败等）。 */
    public static void error() {
        play(ToneGenerator.TONE_PROP_NACK, 120);
    }

    /** 唯一发声出口：「按键音」关闭时直接返回，连 ToneGenerator 都不初始化。 */
    private static void play(int type, int duration) {
        if (broken || !Setting.isKeySound()) return;
        if (pending.get() > 0) return;
        pending.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    // 入队到真正发声之间可能已过了一段时间：开关被关掉 / 已熔断则跳过
                    if (broken || !Setting.isKeySound()) return;
                    ToneGenerator generator = get();
                    if (generator != null) generator.startTone(type, duration);
                } catch (Throwable e) {
                    fail();
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (Throwable e) {
            // 执行器已关闭等极端情况：保证计数不泄漏，否则按键音会被永久静音
            pending.decrementAndGet();
        }
    }

    /** 懒建 ToneGenerator（只在发声线程调用）。音频服务不可用时构造会抛异常 → 熔断。 */
    private static ToneGenerator get() {
        if (tone != null) return tone;
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            return tone;
        } catch (Throwable e) {
            fail();
            return null;
        }
    }

    /** 熔断并释放实例（只在发声线程调用）。 */
    private static void fail() {
        broken = true;
        ToneGenerator old = tone;
        tone = null;
        if (old == null) return;
        try {
            old.release();
        } catch (Throwable ignored) {
        }
    }
}
