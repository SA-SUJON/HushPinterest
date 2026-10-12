/*
 * Forked from https://github.com/SysAdminDoc/HushTelegram at 8c54a1d (GPL-3.0),
 * modified for HushPinterest (Pinterest), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushThreads at b141524 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at c15d4f79 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.hushpinterest.extension.pinterest.settings;

import android.content.res.ColorStateList;
import android.database.DataSetObserver;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.ListAdapter;

/** Styles native radio and checkbox rows without replacing their selection behavior. */
final class DialogChoices extends BaseAdapter {
    private final ListAdapter source;
    private final ScreenColors colors;

    DialogChoices(ListAdapter source, ScreenColors colors) {
        this.source = source;
        this.colors = colors;
    }

    @Override public int getCount() { return source.getCount(); }
    @Override public Object getItem(int position) { return source.getItem(position); }
    @Override public long getItemId(int position) { return source.getItemId(position); }
    @Override public boolean hasStableIds() { return source.hasStableIds(); }
    @Override public boolean isEnabled(int position) { return source.isEnabled(position); }
    @Override public boolean areAllItemsEnabled() { return source.areAllItemsEnabled(); }
    @Override public int getItemViewType(int position) { return source.getItemViewType(position); }
    @Override public int getViewTypeCount() { return source.getViewTypeCount(); }
    @Override public void registerDataSetObserver(DataSetObserver observer) { source.registerDataSetObserver(observer); }
    @Override public void unregisterDataSetObserver(DataSetObserver observer) { source.unregisterDataSetObserver(observer); }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
        View row = source.getView(position, recycled, parent);
        if (row instanceof CheckedTextView) {
            CheckedTextView choice = (CheckedTextView) row;
            float density = row.getResources().getDisplayMetrics().density;
            choice.setEnabled(source.isEnabled(position));
            choice.setTextColor(ScreenColors.dimmedWhenDisabled(colors.title));
            choice.setTextSize(16);
            choice.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            choice.setSingleLine(false);
            choice.setMaxLines(Integer.MAX_VALUE);
            choice.setMinHeight(Math.round((48 + 2 * ScreenColors.GAP_DP) * density));
            ColorStateList indicator = new ColorStateList(new int[][]{
                    {-android.R.attr.state_enabled, android.R.attr.state_checked},
                    {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
                    new int[]{ScreenColors.half(colors.accent), ScreenColors.half(colors.summary),
                            colors.accent, colors.summary});
            choice.setCheckMarkTintList(indicator);
            // Material's framework layout uses a start drawable for its radio indicator.
            for (android.graphics.drawable.Drawable drawable : choice.getCompoundDrawablesRelative()) {
                if (drawable != null) drawable.mutate().setTintList(indicator);
            }
            choice.setCompoundDrawablePadding(Math.round(ScreenColors.CONTENT_DP * density));
            StateListDrawable background = new StateListDrawable();
            background.addState(new int[]{android.R.attr.state_checked}, surface(density, 0xFF17294B, colors.accent));
            background.addState(new int[]{}, surface(density, colors.card, colors.outline));
            int side = Math.round(ScreenColors.SIDE_DP * density);
            int gap = Math.round(ScreenColors.GAP_DP * density);
            choice.setBackground(new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(colors.accent)),
                    new InsetDrawable(background, side, gap, side, gap), null));
            // Rounding both sides up at densities such as 450dpi exceeds the measured 48dp row.
            int verticalPadding = (int) (12 * density) + gap;
            choice.setPaddingRelative(side + Math.round(ScreenColors.CONTENT_DP * density), verticalPadding,
                    side + Math.round(ScreenColors.CONTENT_DP * density), verticalPadding);
        }
        return row;
    }

    private GradientDrawable surface(float density, int fill, int edge) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(fill);
        surface.setStroke(Math.round(density), ScreenColors.dimmedWhenDisabled(edge));
        surface.setCornerRadius(Math.round(ScreenColors.RADIUS_DP * density));
        return surface;
    }
}
