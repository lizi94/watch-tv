package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textview.MaterialTextView;

/**
 * 分类标签（{@code adapter_type.xml} 的根节点）。
 *
 * <p><b>2026-10-13 死代码清理</b>：本类原本还带一套「长按上键 → 触发 listener.onRefresh()」
 * 的交互（{@code listener} + {@code coolDown} 冷却 + {@code dispatchKeyEvent}/{@code onFocusChanged}
 * 两个重写）。那是电视端「分类页」时代的产物 —— 分类页整链已在 R38 移除，
 * 而全仓库从未有人调用过 {@code setListener(...)}，{@code listener} 恒为 {@code null}，
 * 于是那条 {@code dispatchKeyEvent} 分支永远不可达（等价于直接走 {@code super}），
 * {@code coolDown} 也只为它服务。删掉后行为与删除前完全一致。</p>
 *
 * <p>类本身<b>必须保留</b>：{@code app/src/leanback/res/layout/adapter_type.xml} 以它为根节点，
 * 该布局经 {@code AdapterTypeBinding} 被 {@code CollectAdapter} 使用（搜索结果页的站源标签），
 * 删除会导致搜索结果页站源行崩溃。</p>
 */
public class CustomTypeView extends MaterialTextView {

    public CustomTypeView(@NonNull Context context) {
        super(context);
    }

    public CustomTypeView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }
}
