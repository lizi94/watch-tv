package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogConfigBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ConfigDialog extends BaseAlertDialog {

    private static final String KEY_TYPE = "type";
    private static final String KEY_EDIT = "edit";

    private DialogConfigBinding binding;
    private boolean append = true;
    private boolean edit;
    private String ori;
    private int type;

    public static ConfigDialog create() {
        return new ConfigDialog();
    }

    public ConfigDialog vod() {
        type = 0;
        persist(KEY_TYPE, type);
        return this;
    }

    public ConfigDialog live() {
        type = 1;
        persist(KEY_TYPE, type);
        return this;
    }

    public ConfigDialog wall() {
        type = 2;
        persist(KEY_TYPE, type);
        return this;
    }

    public ConfigDialog edit() {
        edit = true;
        persist(KEY_EDIT, edit);
        return this;
    }

    /**
     * 重建后把类型与编辑态读回来。
     * 这里不还原会出两类问题：轻则弹窗显示成另一种类型的配置，重则在编辑态下把内容
     * 写进错误类型的配置里（getConfig()/Config.find 都按 type 取）。
     */
    @Override
    protected void restoreArguments() {
        type = persistedInt(KEY_TYPE, type);
        edit = persistedBoolean(KEY_EDIT, edit);
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogConfigBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(type == 0 ? R.string.setting_vod_source : type == 1 ? R.string.setting_live_source : R.string.setting_wall_source).setView(getBinding().getRoot()).setPositiveButton(edit ? R.string.dialog_edit : R.string.dialog_positive, this::onPositive).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        binding.name.setText(getConfig().getName());
        binding.url.setText(ori = getConfig().getUrl());
        binding.input.setVisibility(edit ? View.VISIBLE : View.GONE);
        binding.url.setSelection(TextUtils.isEmpty(ori) ? 0 : ori.length());
    }

    @Override
    protected void initEvent() {
        binding.choose.setEndIconOnClickListener(this::onChoose);
        binding.url.addTextChangedListener(new CustomTextListener() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                detect(s.toString());
            }
        });
        binding.url.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onPositive(null, 0);
            return true;
        });
    }

    private Config getConfig() {
        return switch (type) {
            case 0 -> VodConfig.get().getConfig();
            case 1 -> LiveConfig.get().getConfig();
            case 2 -> WallConfig.get().getConfig();
            default -> null;
        };
    }

    /**
     * 先确保设备文件权限在手，再打开选择器。
     *
     * <p>这是路径正确性的前提：未授权时 {@code FileChooser} 的 {@code File.canRead()} 判定失败，
     * 会把文件拷贝到 App 缓存目录并返回缓存路径，配置里存下的就成了
     * {@code file:///data/user/0/…/cache/chooser/<md5>/xxx.json}，而不是用户选择的
     * {@code file://Download/xxx.json}。授权必须发生在 SAF 解析路径<b>之前</b>。</p>
     */
    private void onChoose(View view) {
        PermissionUtil.requestFile(this, allGranted -> FileChooser.from(launcher).show());
    }

    private void detect(String s) {
        if (append && "h".equalsIgnoreCase(s)) {
            append = false;
            binding.url.append("ttp://");
        } else if (append && "f".equalsIgnoreCase(s)) {
            append = false;
            binding.url.append("ile://");
        } else if (append && "a".equalsIgnoreCase(s)) {
            append = false;
            binding.url.append("ssets://");
        } else if (s.length() > 1) {
            append = false;
        } else if (s.isEmpty()) {
            append = true;
        }
    }

    private void onPositive(DialogInterface dialog, int which) {
        String url = binding.url.getText().toString().trim();
        String name = binding.name.getText().toString().trim();
        if (edit) Config.find(ori, type).url(url).name(name).update();
        if (url.isEmpty()) Config.delete(ori, type);
        ((ConfigListener) requireParentFragment()).setConfig(Config.find(url, type));
        dismiss();
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> FileChooser.getUri(result, this::setConfig));

    private void setConfig(Uri uri) {
        if (!isAdded()) return;
        ((ConfigListener) requireParentFragment()).setConfig(Config.find(UrlUtil.toLocalUrl(uri), type));
        dismiss();
    }
}
