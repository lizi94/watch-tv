package com.fongmi.android.tv.utils;

import static android.widget.ImageView.ScaleType.CENTER_CROP;
import static android.widget.ImageView.ScaleType.FIT_CENTER;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.bumptech.glide.request.target.Target;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.server.Server;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Json;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.net.HttpHeaders;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jahirfiquitiva.libs.textdrawable.TextDrawable;

public class ImgUtil {

    private static final int MAX_CACHE_SIZE = 16 * 1024 * 1024;
    /**
     * 播放页默认底图（artwork）的解码缩放系数：底图按「屏幕尺寸 × 本系数」解码。
     * 0.5F ⇒ 面积 1/4、内存占用 1/4。取 0.5 的理由见
     * {@link #load(Context, String, CustomTarget)} 的 javadoc。
     */
    private static final float ARTWORK_SCALE = 0.5F;
    private static final int MAX_DATA_URI_LENGTH = 8 * 1024 * 1024;
    private static final Pattern IMAGE_MIME = Pattern.compile("image/[a-z0-9.+-]+");
    private static final Set<String> failed = Collections.synchronizedSet(new HashSet<>());
    private static final Cache<String, Image> CACHE = CacheBuilder.newBuilder().maximumWeight(MAX_CACHE_SIZE).weigher((String key, Image value) -> value.data().length).build();

    /** 台标 logo 的解码上限（2026-10-26 低配卡死治理 P0-3）：配置里塞一张超大 logo 时兜底。 */
    private static final int LOGO_MAX_DP = 256;

    public static void logo(ImageView view) {
        try {
            int size = ResUtil.dp2px(LOGO_MAX_DP);
            Glide.with(view).load(UrlUtil.convert(VodConfig.get().getConfig().getLogo())).circleCrop().override(size, size).error(R.drawable.ic_logo).into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 安全地清掉某个 ImageView 上挂着的 Glide 请求。
     *
     * <p><b>⛔ 2026-10-16 崩溃根因（真机堆栈取证）</b>：各 {@code *Holder#unbind()} 原先直接写
     * {@code Glide.with(binding.image).clear(binding.image)}，没有任何保护。而 ViewHolder 的
     * 回收会发生在 <b>Activity 的销毁路径</b>上（recreate / 退出页面时，leanback 的
     * {@code ListRowPresenter#onUnbindRowViewHolder} → 子 RecyclerView 回收子视图 → holder.unbind()）。
     * Glide 4 的 {@code Glide.with(Activity)} 在 {@code activity.isDestroyed()} 时
     * <b>直接抛 IllegalArgumentException("You cannot start a load for a destroyed activity")</b>
     * ⇒ 异常从 {@code HomeActivity.onDestroy} 里冒出去 ⇒
     * {@code RuntimeException: Unable to destroy activity} ⇒ 崩溃闪退。</p>
     *
     * <p>正确语义：视图都在被回收了，本来也不需要再「清请求」——Activity 已销毁时直接跳过。
     * 同时保留 try/catch 兜住 Glide 的其它运行时异常（与 {@link #load} 系列口径一致）。</p>
     */
    public static void clear(View view) {
        if (view == null) return;
        Context context = view.getContext();
        // isDestroyed() 需 API 17+（本项目 minSdk 24），与 Glide 内部判据完全一致。
        if (context instanceof android.app.Activity && ((android.app.Activity) context).isDestroyed()) return;
        try {
            Glide.with(view).clear(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String url, CustomTarget<Bitmap> target) {
        try {
            Glide.with(App.get()).asBitmap().load(getUrl(url)).override(ResUtil.dp2px(96), ResUtil.dp2px(96)).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 播放页「默认底图（artwork）」的加载入口：视频首帧渲染出来之前铺满播放器的过渡图
     * ——直播页 = 当前频道 logo，点播页 = 影片封面。
     *
     * <p><b>R30 降采样</b>：此前 override 直接给「屏幕物理像素」，4K 屏下
     * {@code 3840 × 2160 × 4B ≈ 33MB} 一张；而 Glide 的默认内存缓存目标是
     * {@code 屏幕像素 × 4B × 2}（约 66MB，仅够放 2 张），首页刷一圈封面就能把它挤爆，
     * 导致这张大图在详情页反复解码、反复占内存。</p>
     *
     * <p>这张图只是起播前的短暂过渡底图，<b>没有必要按物理屏幕分辨率解码</b>：
     * 取屏幕的 {@value #ARTWORK_SCALE} 分之一，4K 下 33MB → 8.3MB（内存降 75%），
     * 点播常看的 1080p 下 8.3MB → 2.1MB。放大回全屏后观感差异极小（无锐利文字、纯色块为主）。</p>
     *
     * <p>本方法在 {@code main} 层，<b>双端共享</b>：手机端与电视端一并受益。
     * 手机端 1080p 屏降采样后仍有 960×540 的底图，观感无差异。</p>
     */
    public static void load(Context context, String url, CustomTarget<Drawable> target) {
        try {
            int width = Math.max(1, (int) (ResUtil.getScreenWidth() * ARTWORK_SCALE));
            int height = Math.max(1, (int) (ResUtil.getScreenHeight() * ARTWORK_SCALE));
            Glide.with(context).load(getUrl(url)).override(width, height).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String text, String url, ImageView view) {
        load(text, url, view, true);
    }

    public static void load(String text, String url, ImageView view, boolean vod) {
        load(text, url, view, vod, 0, 0);
    }

    /**
     * 带「目标解码尺寸」的封面加载。
     *
     * <p><b>为什么需要这一版</b>：网格封面（首页 / 分类 / 收藏 / 历史）的宽高由
     * {@link com.fongmi.android.tv.Product#getSpec} 在运行时算出，视图在首帧绑定前还没跑过
     * measure，Glide 只能等布局完成再回退到「按视图实测尺寸」解码。走显式 override 有两个好处：
     * <ul>
     *   <li>解码尺寸不再依赖布局时序 —— 首次绑定就按最终显示尺寸降采样，不会出现
     *       「先按原图解码、布局后再重解一次」的瞬时双份位图；</li>
     *   <li>「图片尺寸」档位调大（列数变少 → 单张封面变大）、字号档位把密度整体放大时，
     *       解码目标始终跟随实际显示尺寸，峰值内存可预期。</li>
     * </ul>
     * 尺寸传 0（或负值）时<b>完全退回原行为</b>（不 override，交给 Glide 按视图尺寸决定），
     * 因此旧的 {@code load(text, url, view)} / {@code load(text, url, view, vod)} 语义不变。</p>
     *
     * @param targetWidth  目标解码宽度（px），≤0 表示不指定
     * @param targetHeight 目标解码高度（px），≤0 表示不指定
     */
    public static void load(String text, String url, ImageView view, boolean vod, int targetWidth, int targetHeight) {
        view.setScaleType(vod ? CENTER_CROP : FIT_CENTER);
        if (!vod) view.setVisibility(TextUtils.isEmpty(url) ? View.GONE : View.VISIBLE);
        if (TextUtils.isEmpty(url) || failed.contains(url)) view.setImageDrawable(getTextDrawable(text, vod));
        else try {
            RequestBuilder<Drawable> builder = Glide.with(view).load(getUrl(url)).listener(getListener(text, url, view, vod));
            if (targetWidth > 0 && targetHeight > 0) builder.override(targetWidth, targetHeight);
            if (vod) {
                // R33 内存 + 滚动优化（仅影片封面，vod == false 的频道 logo 不适用 —— 见下）：
                //
                // ① format(PREFER_RGB_565)：封面原图是海报照片，没有透明通道；
                //    默认的 ARGB_8888 每像素 4 字节，RGB_565 只需 2 字节 → 位图内存直接减半，
                //    长列表能多缓存一倍封面。圆角由 ShapeableImageView 的 shapeAppearanceOverlay
                //    做遮罩裁切，不依赖 bitmap 的 alpha，所以不受影响。
                // ② dontAnimate()：列表滚动时省掉淡入动画的每帧重绘，弱 CPU 的电视盒子更跟手。
                //
                // ⚠️ 必须用 vod 门控：vod == false 的唯一调用点是 Channel 的频道 logo
                //    （main/.../bean/Channel.java），台标常见透明 PNG，转 RGB_565 会把透明区压成黑块。
                builder.format(DecodeFormat.PREFER_RGB_565).dontAnimate().centerCrop().into(view);
            } else {
                builder.fitCenter().into(view);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static Object getUrl(String url) {
        String param = null;
        url = UrlUtil.convert(url);
        if (url.startsWith("data:")) return url;
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        if (url.contains("@Headers=")) addHeader(builder, param = url.split("@Headers=")[1].split("@")[0]);
        if (url.contains("@Cookie=")) builder.addHeader(HttpHeaders.COOKIE, param = url.split("@Cookie=")[1].split("@")[0]);
        if (url.contains("@Referer=")) builder.addHeader(HttpHeaders.REFERER, param = url.split("@Referer=")[1].split("@")[0]);
        if (url.contains("@User-Agent=")) builder.addHeader(HttpHeaders.USER_AGENT, param = url.split("@User-Agent=")[1].split("@")[0]);
        url = param == null ? url : url.split("@")[0];
        return TextUtils.isEmpty(url) ? null : new GlideUrl(url, builder.build());
    }

    public static String cache(String url) {
        if (!isData(url)) return url;
        if (url.length() > MAX_DATA_URI_LENGTH) return "";
        String key = Crypto.md5(url);
        if (TextUtils.isEmpty(key)) return "";
        if (CACHE.asMap().computeIfAbsent(key, ignored -> decode(url)) == null) return "";
        String address = Server.get().getAddress("/image/" + key);
        failed.remove(address);
        return address;
    }

    private static boolean isData(String url) {
        return !TextUtils.isEmpty(url) && url.regionMatches(true, 0, "data:", 0, 5);
    }

    private static Image decode(String url) {
        try {
            int comma = url.indexOf(',');
            if (comma == -1) return null;
            String metadata = url.substring(5, comma).toLowerCase(Locale.ROOT);
            String mime = metadata.split(";", 2)[0];
            if (!IMAGE_MIME.matcher(mime).matches() || !metadata.endsWith(";base64")) return null;
            byte[] data = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
            return data.length == 0 ? null : new Image(mime, data);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Image getImage(String key) {
        return CACHE.getIfPresent(key);
    }

    public record Image(String mime, byte[] data) {
    }

    private static void addHeader(LazyHeaders.Builder builder, String header) {
        Map<String, String> map = Json.toMap(Json.parse(header));
        for (Map.Entry<String, String> entry : map.entrySet()) builder.addHeader(UrlUtil.fixHeader(entry.getKey()), entry.getValue());
    }

    private static Drawable getTextDrawable(String text, boolean vod) {
        TextDrawable.Builder builder = new TextDrawable.Builder();
        text = TextUtils.isEmpty(text) ? "！" : text.substring(0, 1);
        if (vod) builder.buildRect(text, ColorGenerator.get400(text));
        return builder.buildRoundRect(text, ColorGenerator.get400(text), ResUtil.dp2px(4));
    }

    private static RequestListener<Drawable> getListener(String text, String url, ImageView view, boolean vod) {
        return new RequestListener<>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                view.setImageDrawable(getTextDrawable(text, vod));
                failed.add(url);
                return true;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                return false;
            }
        };
    }
}
