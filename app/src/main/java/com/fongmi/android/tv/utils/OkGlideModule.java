package com.fongmi.android.tv.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.github.catvod.net.OkHttp;

import java.io.InputStream;

/**
 * Glide 全局配置（main 层，**电视端与手机端共享**）。
 *
 * <p>2026-10-11 新增磁盘缓存：此前本模块只设置了日志级别，Glide 未配置任何
 * {@link com.bumptech.glide.load.engine.cache.DiskCache}，磁盘缓存退化为「不落盘」
 * —— 同一进程内可命中内存缓存，但**退出重进 / 进程被杀后所有封面必须重新联网下载**。
 * 电视端与手机端封面列表都较长，这是最直接的加载速度瓶颈。此处给 256MB 上限
 * （内部缓存目录，随应用卸载清理）。</p>
 *
 * <h3>2026-10-26 内存缓存分档（低配电视卡死治理 P0-1）</h3>
 * <p>Glide 的默认内存策略是「2 屏资源缓存 + 1 屏位图池」（ARGB_8888 计），
 * 4K 屏合计约 100MB —— 在 1GB 内存、heap 只有 256~512MB 的低配电视上，
 * 进入详情页 / 播放页时叠加首页残留与播放器，峰值直接顶到上限，
 * 之后每一次大图分配都触发 full GC，主线程被停顿切成碎片，观感就是「卡死」。</p>
 *
 * <p>本应用封面已统一 {@code PREFER_RGB_565}（内存减半）且绝大多数走 {@code override}
 * 按显示尺寸解码，「多缓存封面」的收益本来就有限；而「内存不超限」对低配设备是生死线。
 * 因此按 {@code memoryClass} 分档：小内存设备压到「1/2 屏缓存 + 1/2 屏池」，
 * 高内存设备维持 Glide 默认。观感差异只是低配机上稍多几次封面重解码，可接受。</p>
 */
@GlideModule
public class OkGlideModule extends AppGlideModule {

    /** Glide 磁盘缓存上限：256MB。 */
    private static final long DISK_CACHE_BYTES = 256L * 1024 * 1024;

    /**
     * 小内存分档线：设备总内存低于此值视为低配设备。
     *
     * <p>⚠ 用 {@code MemoryInfo.totalMem} 而不是 {@code getMemoryClass()}：后者是
     * <b>标准</b> heap 上限，本应用 {@code largeHeap=true} 时实际可用上限是
     * {@code getLargeMemoryClass()} —— 用 memoryClass 会把几乎所有设备都误判成低配。
     * totalMem 直接反映整机档次：1GB 的电视盒子按低配处理，2GB 及以上维持默认。</p>
     */
    private static final long LOW_RAM_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setLogLevel(Log.ERROR);
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, DISK_CACHE_BYTES));
        try {
            MemorySizeCalculator calculator = new MemorySizeCalculator.Builder(context).build();
            if (!isLowRamDevice(context)) return;
            // 小内存设备：资源缓存压到默认的一半（≈1 屏）、位图池压到默认的一半（≈1/2 屏）。
            // 封面本身是 RGB_565（每像素 2 字节），实际占用还要再减半。
            builder.setMemoryCache(new LruResourceCache(Math.round(calculator.getMemoryCacheSize() / 2f)));
            builder.setBitmapPool(new LruBitmapPool(Math.round(calculator.getBitmapPoolSize() / 2f)));
        } catch (Throwable e) {
            // 配置失败不致命：回退 Glide 默认策略即可，绝不能让全局初始化崩掉。
            Log.w("OkGlideModule", "memory sizing fallback to default", e);
        }
    }

    /** 与 {@link com.fongmi.android.tv.player.PlayerManager} 的低配判定保持同一口径。 */
    private static boolean isLowRamDevice(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return info.totalMem > 0 && info.totalMem < LOW_RAM_TOTAL_BYTES;
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, Registry registry) {
        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(OkHttp.client()));
    }
}
