package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterKeyboardIconBinding;
import com.fongmi.android.tv.databinding.AdapterKeyboardTextBinding;
import com.fongmi.android.tv.setting.Setting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public class KeyboardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<Integer> icons = Arrays.asList(R.drawable.ic_keyboard_remote, R.drawable.ic_keyboard_left, R.drawable.ic_keyboard_right, R.drawable.ic_keyboard_back, R.drawable.ic_keyboard_search, R.drawable.ic_keyboard);
    private final List<String> enList = Arrays.asList("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    private final List<String> twList = Arrays.asList("ㄅ", "ㄆ", "ㄇ", "ㄈ", "ㄉ", "ㄊ", "ㄋ", "ㄌ", "ㄍ", "ㄎ", "ㄏ", "ㄐ", "ㄑ", "ㄒ", "ㄓ", "ㄔ", "ㄕ", "ㄖ", "ㄗ", "ㄘ", "ㄙ", "ㄧ", "ㄨ", "ㄩ", "ㄚ", "ㄛ", "ㄜ", "ㄝ", "ㄞ", "ㄟ", "ㄠ", "ㄡ", "ㄢ", "ㄣ", "ㄤ", "ㄥ", "ㄦ", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    private final OnClickListener listener;
    private final List<Object> mItems;
    /** 运行时由 CustomKeyboard 算出的单键宽（px）；0 表示沿用布局里的 @dimen/ag_key_size。 */
    private int keyW;
    /** 单键高（px）。R158 起键宽高解耦：宽由左列反解、高由屏幕高度反解，故分开存。 */
    private int keyH;
    /** 同一条曲线派生的图标键内图标边长（px），按键高定，保证图标始终保持正方形。 */
    private int glyphSize;

    public KeyboardAdapter(OnClickListener listener) {
        this.mItems = new ArrayList<>();
        this.listener = listener;
        this.mItems.addAll(icons);
        this.mItems.addAll(Setting.isZhuyin() ? twList : enList);
    }

    /**
     * 设置单键宽、单键高与键内图标边长（都是 px，宽高解耦）。
     * 必须在键盘拿到真实可用高度后由 {@link com.fongmi.android.tv.ui.custom.CustomKeyboard} 下发，
     * 否则 GridLayoutManager 会按布局里声明的固定宽度测量，第一行图标键会和字母键宽度不一致。
     *
     * @return true 表示尺寸确实变了（用于避免「重算 → 重布局 → 再重算」的循环）
     */
    public boolean setKeySize(int w, int h, int glyph) {
        if (w <= 0 || h <= 0 || (w == keyW && h == keyH && glyph == glyphSize)) return false;
        keyW = w;
        keyH = h;
        glyphSize = glyph;
        notifyItemRangeChanged(0, getItemCount());
        return true;
    }

    private void applyKeySize(View view, boolean icon) {
        if (keyW <= 0 || keyH <= 0) return;
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null) return;
        boolean changed = false;
        if (lp.width != keyW || lp.height != keyH) {
            lp.width = keyW;
            lp.height = keyH;
            changed = true;
        }
        if (icon) {
            // 图标用 fitCenter，靠内边距把它压到 glyphSize（方形）。键是宽矩形时图标不能按「边」同缩，
            // 所以横竖内距分开算：横内距 = (键宽 − 图标)/2、竖内距 = (键高 − 图标)/2，图标始终居中且不拉扁。
            int padH = Math.max(0, (keyW - glyphSize) / 2);
            int padV = Math.max(0, (keyH - glyphSize) / 2);
            if (view.getPaddingLeft() != padH || view.getPaddingTop() != padV) {
                view.setPadding(padH, padV, padH, padV);
                changed = true;
            }
        }
        if (changed) view.setLayoutParams(lp);
    }

    public interface OnClickListener {

        void onTextClick(String text);

        void onIconClick(int resId);

        boolean onLongClick(int resId);
    }

    public void toggle() {
        Setting.putZhuyin(!Setting.isZhuyin());
        mItems.removeAll(Setting.isZhuyin() ? enList : twList);
        mItems.addAll(icons.size(), Setting.isZhuyin() ? twList : enList);
        // R157：原来连发 notifyItemRangeRemoved + notifyItemRangeInserted，而两次通知之间 mItems
        //   已经同时完成「删 36/47」和「加 47/36」—— 移除区间与适配器当时的实际项数对不上，
        //   部分 ROM 会 IndexOutOfBounds，也必然让 GridLayoutManager 重新定位（焦点/滚动位置跳）。
        //   键盘只有 40 多项、且 setItemAnimator(null) 已关，直接整体刷新最稳，没有动画损失。
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return mItems.get(position) instanceof String ? 0 : 1;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == 0) return new TextHolder(AdapterKeyboardTextBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        else return new IconHolder(AdapterKeyboardIconBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        switch (getItemViewType(position)) {
            case 0:
                TextHolder text = (TextHolder) holder;
                text.binding.text.setText(mItems.get(position).toString());
                applyKeySize(text.itemView, false);
                break;
            case 1:
                IconHolder icon = (IconHolder) holder;
                icon.binding.icon.setImageResource((int) mItems.get(position));
                applyKeySize(icon.itemView, true);
                break;
        }
    }

    class TextHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private final AdapterKeyboardTextBinding binding;

        TextHolder(@NonNull AdapterKeyboardTextBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            listener.onTextClick(mItems.get(getLayoutPosition()).toString());
        }
    }

    class IconHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {

        private final AdapterKeyboardIconBinding binding;

        IconHolder(@NonNull AdapterKeyboardIconBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnClickListener(this);
            itemView.setOnLongClickListener(this);
        }

        @Override
        public void onClick(View view) {
            listener.onIconClick((int) mItems.get(getLayoutPosition()));
        }

        @Override
        public boolean onLongClick(View view) {
            return listener.onLongClick((int) mItems.get(getLayoutPosition()));
        }
    }
}
