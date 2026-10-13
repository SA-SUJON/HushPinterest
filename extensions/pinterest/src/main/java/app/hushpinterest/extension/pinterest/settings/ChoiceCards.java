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

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A dialog's choices as inset cards, each with its name on top and what it does under it.
 *
 * <p>A card leaves getView() with its padding and its surface already on it, so the dialog's list
 * measures every row at the height it shows. Styling Android's own rows after the dialog was shown
 * (tried on 2026-09-26) reached rows the list had already measured without it: a 72 dp row cut the
 * second choice off at the normal text size, and at twice the size the buttons covered it.
 */
final class ChoiceCards extends BaseAdapter {
    private final ScreenColors colors;
    private final CharSequence[] names;
    private final CharSequence[] details;
    /** Per choice, whether a tap opens something else, or null when every choice does. */
    private final boolean[] opens;

    ChoiceCards(ScreenColors colors, CharSequence[] names, CharSequence[] details) {
        this(colors, names, details, null);
    }

    /**
     * [opens] says which choices open another screen, picker or app. A chevron means that on the
     * settings pages, so a choice that acts at once, such as Remove from history, shows none.
     */
    ChoiceCards(ScreenColors colors, CharSequence[] names, CharSequence[] details, boolean[] opens) {
        if (names.length != details.length || (opens != null && opens.length != names.length)) {
            throw new IllegalArgumentException(names.length + " choices, " + details.length + " details and "
                    + (opens == null ? "no" : String.valueOf(opens.length)) + " chevron flags");
        }
        this.colors = colors;
        this.names = names.clone();
        this.details = details.clone();
        this.opens = opens == null ? null : opens.clone();
    }

    @Override
    public int getCount() {
        return names.length;
    }

    /** A choice's name, and what it does on the line under it. */
    @Override
    public CharSequence getItem(int position) {
        if (TextUtils.isEmpty(details[position])) return names[position];
        return new SpannableStringBuilder(names[position]).append('\n').append(details[position]);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Card card = convertView instanceof Card ? (Card) convertView : new Card(parent.getContext(), colors);
        card.name.setText(names[position]);
        card.detail.setText(details[position]);
        card.detail.setVisibility(TextUtils.isEmpty(details[position]) ? View.GONE : View.VISIBLE);
        // Invisible, not gone: the text keeps the same width on every card.
        card.arrow.setVisibility(opens == null || opens[position] ? View.VISIBLE : View.INVISIBLE);
        return card;
    }

    /** One choice on a card set in from the dialog's edges. */
    static final class Card extends LinearLayout {
        /** How far the card sits in from the list's sides, and from the next card. */
        static final int SIDE_INSET_DP = ScreenColors.SIDE_DP;
        static final int GAP_INSET_DP = ScreenColors.GAP_DP;

        final TextView name;
        final TextView detail;
        final android.widget.ImageView arrow;

        Card(Context context, ScreenColors colors) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int side = dp(context, SIDE_INSET_DP);
            int gap = dp(context, GAP_INSET_DP);
            GradientDrawable surface = colors.cardSurface(this, true);
            // The shared row surface, with its outline separating it from the dialog.
            setBackground(new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(colors.accent)),
                    new InsetDrawable(surface, side, gap, side, gap), null));
            // Set after the background, which puts its insets in as the padding.
            setPaddingRelative(side + dp(context, ScreenColors.CONTENT_DP), gap + dp(context, 12),
                    side + dp(context, ScreenColors.CONTENT_DP), gap + dp(context, 12));
            // 48 dp of card to tap at the least, at any text size.
            setMinimumHeight(2 * gap + dp(context, 48));

            LinearLayout text = new LinearLayout(context);
            text.setOrientation(VERTICAL);
            addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));

            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            name.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            name.setTextColor(ScreenColors.dimmedWhenDisabled(colors.title));
            name.setSingleLine(false);
            name.setMaxLines(Integer.MAX_VALUE);
            ScreenColors.Chevron chevron = new ScreenColors.Chevron(colors.summary, dp(context, 2));
            chevron.setBounds(0, 0, dp(context, 24), dp(context, 24));
            text.addView(name, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            detail = new TextView(context);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            detail.setTextColor(ScreenColors.dimmedWhenDisabled(colors.summary));
            detail.setSingleLine(false);
            detail.setMaxLines(Integer.MAX_VALUE);
            detail.setLineSpacing(dp(context, 2), 1);
            LayoutParams under = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            under.topMargin = dp(context, 2);
            text.addView(detail, under);
            arrow = new android.widget.ImageView(context);
            arrow.setImageDrawable(chevron);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams trailing = new LayoutParams(dp(context, 24), dp(context, 24));
            trailing.setMarginStart(dp(context, 8));
            addView(arrow, trailing);
        }

        private static int dp(Context context, int value) {
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    context.getResources().getDisplayMetrics()));
        }
    }
}
