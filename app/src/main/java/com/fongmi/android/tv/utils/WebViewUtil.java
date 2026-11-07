package com.fongmi.android.tv.utils;

import android.content.pm.PackageManager;
import android.webkit.CookieManager;

import com.fongmi.android.tv.App;

public class WebViewUtil {

    public static boolean support() {
        try {
            CookieManager.getInstance();
            return App.get().getPackageManager().hasSystemFeature(PackageManager.FEATURE_WEBVIEW);
        } catch (Throwable e) {
            return false;
        }
    }
}
