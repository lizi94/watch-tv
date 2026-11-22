# TV
-keep class androidx.leanback.widget.** { *; }
-keep class com.fongmi.quickjs.method.** { *; }
-keep class com.fongmi.android.tv.bean.** { *; }

# Gson
-keep class com.google.gson.** { *; }

# 在线更新（R161）：GitHub Release 的 JSON 由 Gson 反射解析，字段名全在 @SerializedName 上。
# ⛔ 这两个类放在 com.fongmi.android.tv.update，**不在上面 bean.** 的 keep 范围内**；
#    Gson 自带的 consumer 规则（META-INF/proguard/gson.pro）只保 @Expose / @JsonAdapter / @Since，
#    **不保 @SerializedName**。缺这两条时 R8 会删掉/改名这些字段，表现为线上「检查更新恒失败」。
-keep class com.fongmi.android.tv.update.UpdateInfo { *; }
-keep class com.fongmi.android.tv.update.UpdateInfo$Asset { *; }

# SimpleXML
-keep interface org.simpleframework.xml.core.Label { public *; }
-keep class * implements org.simpleframework.xml.core.Label { public *; }
-keep interface org.simpleframework.xml.core.Parameter { public *; }
-keep class * implements org.simpleframework.xml.core.Parameter { public *; }
-keep interface org.simpleframework.xml.core.Extractor { public *; }
-keep class * implements org.simpleframework.xml.core.Extractor { public *; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Path <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Root <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Text <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Element <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Attribute <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.ElementList <fields>; }

# OkHttp
-dontwarn okhttp3.**
-keep class okio.** { *; }
-keep class okhttp3.** { *; }

# SLF4J
-keeppackagenames org.slf4j.**
-keep class org.slf4j.** { *; }

# Kotlin
-keeppackagenames kotlin.**
-keep class kotlin.** { *; }

# CatVod
-keep class com.github.catvod.Proxy { *; }
-keep class com.github.catvod.crawler.** { *; }
-keep class * extends com.github.catvod.crawler.Spider

# Jianpian
-keep class com.p2p.** { *; }

# JUPnP
-dontwarn org.jupnp.**
-keep class org.jupnp.** { *; }
-keep class javax.xml.** { *; }

# Nano
-keep class fi.iki.elonen.** { *; }

# NewPipeExtractor
-keep class javax.script.** { *; }
-keep class jdk.dynalink.** { *; }
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn com.google.re2j.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Sardine
-keep class com.thegrizzlylabs.sardineandroid.** { *; }

# TVBus
-keep class com.tvbus.engine.** { *; }

# XunLei
-keep class com.xunlei.downloadlib.** { *; }

# Zxing QRCode ABI
-keep class com.google.zxing.* { *; }
-keep class com.google.zxing.common.BitMatrix { *; }
-keep class com.google.zxing.common.HybridBinarizer { *; }
-keep class com.google.zxing.qrcode.QRCodeReader { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.qrcode.decoder.ErrorCorrectionLevel { *; }
