package com.fongmi.android.tv.utils;

import android.media.AudioManager;
import android.media.ToneGenerator;

import com.fongmi.android.tv.setting.Setting;

/**
 * 电视版操作反馈音（适老化）：焦点移动 / 确定 / 出错时用 ToneGenerator 内置音源发声，
 * 无需打包音频资源，也不受系统按键音设置影响。
 *
 * <p>只在电视版调用（BaseActivity 全局焦点监听 + 播放页出错路径），手机版不注册。</p>
 *
 * <p><b>总开关</b>：长辈设置页的「按键音」（{@link Setting#isKeySound()}，默认开启）。
 * 三个方法统一走 {@link #play(int, int)}，门控只写一处；读取的是实时偏好，
 * 因此在设置页关掉后立刻静音、不需要重启或重建页面。</p>
 */
public class SoundFx {

    private static ToneGenerator tone;

    private SoundFx() {
    }

    private static ToneGenerator get() {
        if (tone == null) {
            try {
                tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            } catch (Exception e) {
                return null;
            }
        }
        return tone;
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
        if (!Setting.isKeySound()) return;
        ToneGenerator generator = get();
        if (generator == null) return;
        // 调用点在 BaseActivity.dispatchKeyEvent 与全局焦点监听里：
        // 音频服务重启（HDMI 重协商、媒体服务崩溃）后 startTone 会抛 RuntimeException，
        // 异常直接从按键分发冒出 ⇒ 整页闪退。这里吞掉并丢弃实例，下次按键重新构造。
        try {
            generator.startTone(type, duration);
        } catch (Throwable e) {
            tone = null;
        }
    }
}
