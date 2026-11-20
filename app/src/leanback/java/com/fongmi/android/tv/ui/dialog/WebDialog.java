package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class WebDialog {

    private final AlertDialog dialog;

    private WebDialog(View view) {
        this.dialog = new MaterialAlertDialogBuilder(App.activity()).setView(view).create();
        this.dialog.setOnDismissListener((DialogInterface.OnDismissListener) view);
    }

    public static WebDialog create(View view) {
        return new WebDialog(view);
    }

    public WebDialog show() {
        initDialog();
        return this;
    }

    public void dismiss() {
        dialog.setOnDismissListener(null);
        dialog.dismiss();
    }

    private void initDialog() {
        WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
        // R157：原来用无参 getScreenHeight()/getScreenWidth() —— 走 App.get()，内部靠 isLand(App)
        //   判横竖屏；App 的 orientation 不跟随 Activity 的 sensorLandscape，一旦判成竖屏就返回短边，
        //   弹窗只剩 0.8×1080=864 宽（应 1536）。改用弹窗自己的 Context（= Activity 侧）。
        params.height = (int) (ResUtil.getScreenHeight(dialog.getContext()) * 0.8f);
        params.width = (int) (ResUtil.getScreenWidth(dialog.getContext()) * 0.8f);
        dialog.getWindow().setAttributes(params);
        dialog.show();
    }
}
