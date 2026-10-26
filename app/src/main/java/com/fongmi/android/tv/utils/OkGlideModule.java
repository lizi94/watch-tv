package com.fongmi.android.tv.utils;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
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
 * 电视端与手机端封面列表都较长，这是最直接的加载速度瓶颈。</p>
 *
 * <p>此处给 256MB 上限（内部缓存目录，随应用卸载清理）。注意 Glide 5.0.9 **已移除**
 * {@code setMemoryCategory()}，内存策略本轮不动，仅补磁盘缓存。</p>
 */
@GlideModule
public class OkGlideModule extends AppGlideModule {

    /** Glide 磁盘缓存上限：256MB。 */
    private static final long DISK_CACHE_BYTES = 256L * 1024 * 1024;

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setLogLevel(Log.ERROR);
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, DISK_CACHE_BYTES));
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, Registry registry) {
        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(OkHttp.client()));
    }
}