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

import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.preference.Preference;
import android.preference.TwoStatePreference;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.hushpinterest.extension.shared.settings.preference.ImmediateAction;

/**
 * The colours of the HushPinterest screen, its rows and its dialogs: a black page with dark Material
 * rows, whatever the host activity's theme is. Every pair meets WCAG 2.2 AA, which ScreenColorsTest
 * works out.
 */
final class ScreenColors {
    static final int SIDE_DP = 16;
    static final int RADIUS_DP = 12;
    /** Half of the space between standalone cards. */
    static final int GAP_DP = 3;
    static final int CONTENT_DP = 12;

    /** The page, behind the title bar and every row. */
    final int background;
    /** A dialog's surface. */
    final int dialog;
    /** Row and dialog titles, the title bar and its back arrow. */
    final int title;
    /** Row summaries and a dialog's message. */
    final int summary;
    /** Section titles. */
    final int heading;
    /** A switch that's on, a selected choice and a focused field. */
    final int accent;
    /** A slightly darker blue keeps small white action labels above 4.5:1. */
    final int actionFill;
    /** A switch that's off. */
    final int switchOff;
    /** Standalone preference cards and their edge. */
    final int card;
    final int outline;
    /** A control's boundary, distinct from the quieter edge around decorative cards. */
    final int controlOutline;
    final int selectedCard;
    final int statusEnd;
    final int iconBadge;
    /** Text on the filled primary dialog action. */
    final int onAccent;

    /** The one palette the screen is drawn in. */
    static final ScreenColors DEFAULT = new ScreenColors();

    /** The framework theme rows and dialogs are built with, so what this class doesn't paint still reads. */
    static final int THEME = android.R.style.Theme_Material_NoActionBar;

    private ScreenColors() {
        background = Color.BLACK;
        dialog = 0xFF0C111B;
        title = 0xFFF3F5FA;
        summary = 0xFFA9B4C7;
        heading = 0xFF78A9FF;
        accent = 0xFF3478E8;
        actionFill = 0xFF2E70D9;
        switchOff = 0xFF858D9C;
        card = 0xFF10151F;
        outline = 0xFF3A4A66;
        controlOutline = 0xFF697C9E;
        selectedCard = 0xFF17294B;
        statusEnd = 0xFF172B4A;
        iconBadge = 0xFF1C2C48;
        onAccent = Color.WHITE;
    }

    /**
     * The thumb of a switch that's off, read against its track: the white that fills the on thumb
     * clears the 3:1 a control's state needs there.
     */
    int offThumb() {
        return onAccent;
    }

    /**
     * [color], at Material's 38% on a disabled row. A plain colour replaced the theme's own
     * disabled state, so a row that ignored taps, such as Import while an export runs, looked
     * the same as one that would take them.
     */
    static ColorStateList dimmedWhenDisabled(int color) {
        int dimmed = (color & 0x00FFFFFF) | 0x61000000;
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{dimmed, color});
    }

    /**
     * The text of a dialog's outlined actions, such as Cancel, takes the lighter heading colour.
     */
    int secondaryActionText() {
        return heading;
    }

    /** A row's title and summary, its switch if it has one, and its chevron if a tap opens something. */
    void paintRow(View row, Preference preference) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(dimmedWhenDisabled(this.title));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            title.setSingleLine(false);
            title.setMaxLines(Integer.MAX_VALUE);
            if (title.getParent() instanceof View) {
                View text = (View) title.getParent();
                text.setPadding(0, 0, 0, 0);
                text.setMinimumHeight(0);
            }
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextColor(dimmedWhenDisabled(this.summary));
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            summary.setLineSpacing(dp(row, 2), 1);
            summary.setSingleLine(false);
            summary.setMaxLines(Integer.MAX_VALUE);
        }
        View widget = row.findViewById(android.R.id.switch_widget);
        if (widget instanceof Switch) {
            int[][] states = {{-android.R.attr.state_enabled, android.R.attr.state_checked},
                    {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}};
            Switch toggle = (Switch) widget;
            GradientDrawable thumb = new GradientDrawable();
            thumb.setCornerRadius(dp(row, 4));
            thumb.setSize(dp(row, 20), dp(row, 20));
            thumb.setColor(onAccent);
            GradientDrawable track = new GradientDrawable();
            track.setCornerRadius(dp(row, 6));
            track.setSize(dp(row, 42), dp(row, 24));
            track.setColor(onAccent);
            toggle.setThumbDrawable(thumb);
            toggle.setTrackDrawable(track);
            toggle.setSwitchMinWidth(dp(row, 48));
            toggle.setMinimumWidth(dp(row, 48));
            toggle.setMinimumHeight(dp(row, 48));
            toggle.setThumbTintList(new ColorStateList(states, new int[]{half(onAccent), half(offThumb()), onAccent, offThumb()}));
            toggle.setTrackTintList(new ColorStateList(states, new int[]{half(accent), half(switchOff), accent, switchOff}));
        }
        paintChevron(row, preference);
        ImageView icon = row.findViewById(android.R.id.icon);
        if (icon != null && preference.getIcon() != null) {
            ViewGroup.LayoutParams imageSize = icon.getLayoutParams();
            imageSize.width = dp(row, 40);
            imageSize.height = dp(row, 40);
            icon.setLayoutParams(imageSize);
            // From 2x text Samsung lays a switch row out from tw_preference_switch_large, which puts
            // the gap on the icon itself and nests its frame in a separate line. Replace both native
            // shapes' padding and margins so the 24dp glyph stays centred in the same 40dp badge.
            GradientDrawable badge = new GradientDrawable();
            badge.setColor(dimmedWhenDisabled(iconBadge));
            badge.setCornerRadius(dp(row, 8));
            icon.setBackground(badge);
            icon.setPadding(dp(row, 8), dp(row, 8), dp(row, 8), dp(row, 8));
            icon.setImageTintList(dimmedWhenDisabled(heading));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (icon.getParent() instanceof ViewGroup) {
                ViewGroup frame = (ViewGroup) icon.getParent();
                frame.setPaddingRelative(0, 0, dp(row, 12), 0);
                frame.setMinimumWidth(0);
                ViewGroup.LayoutParams size = frame.getLayoutParams();
                size.width = dp(row, 52);
                size.height = dp(row, 40);
                if (size instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) size).gravity = android.view.Gravity.TOP;
                } else if (size instanceof android.widget.FrameLayout.LayoutParams) {
                    ((android.widget.FrameLayout.LayoutParams) size).gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                }
                frame.setLayoutParams(size);
                View line = frame;
                while (line.getParent() instanceof View && line.getParent() != row) line = (View) line.getParent();
                if (line.getParent() == row) {
                    startMargin(frame, 0);
                    startMargin(line, 0);
                }
            }
        }
        paintSurface(row, preference, true, true);
    }

    /** Every visible row is its own card, including rows in a filtered search result. */
    void paintSurface(View row, Preference preference, boolean first, boolean last) {
        GradientDrawable surface = cardSurface(row, preference.isSelectable());
        Drawable inset = cardInset(row, surface);
        row.setBackground(preference.isSelectable()
                ? new RippleDrawable(ColorStateList.valueOf(half(accent)), inset, null) : inset);
        // Explicit padding after the inset background prevents its padding from moving the text.
        boolean nameOnly = preference.getIcon() != null && android.text.TextUtils.isEmpty(preference.getSummary());
        int vertical = preference.getIcon() == null ? 16 : nameOnly ? 8 : 12;
        row.setPaddingRelative(dp(row, SIDE_DP + CONTENT_DP), dp(row, vertical + GAP_DP),
                dp(row, SIDE_DP + CONTENT_DP), dp(row, vertical + GAP_DP));
        row.setMinimumHeight(dp(row, nameOnly ? 62 : 72));
    }

    /**
     * The overview's hero keeps the same card geometry with a quiet blue edge. Its blue gathers
     * toward the end side, so it mirrors in a right-to-left language as the text does.
     */
    void paintStatusSurface(View row) {
        int start = row.getPaddingStart();
        int top = row.getPaddingTop();
        int end = row.getPaddingEnd();
        int bottom = row.getPaddingBottom();
        GradientDrawable surface = cardSurface(row, false);
        surface.setOrientation(rightToLeft(row)
                ? GradientDrawable.Orientation.RIGHT_LEFT : GradientDrawable.Orientation.LEFT_RIGHT);
        surface.setColors(new int[]{card, statusEnd});
        row.setBackground(cardInset(row, surface));
        row.setPaddingRelative(start, top, end, bottom);
    }

    /**
     * Whether [row]'s text runs right to left: its own direction once it's resolved, so a row kept
     * left to right stays so, and before that the language's, only when the app lays out right to
     * left at all.
     */
    static boolean rightToLeft(View row) {
        if (row.isLayoutDirectionResolved()) return row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        boolean supported = (row.getContext().getApplicationInfo().flags & ApplicationInfo.FLAG_SUPPORTS_RTL) != 0;
        return supported && row.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private static Drawable cardInset(View row, Drawable surface) {
        return new InsetDrawable(surface, dp(row, SIDE_DP), dp(row, GAP_DP),
                dp(row, SIDE_DP), dp(row, GAP_DP));
    }

    /** ListView gives keyboard selection to its row rather than focus to the row's children. */
    private static final class FocusSurface extends GradientDrawable {
        private final ColorStateList edges;
        private final int focusColor;
        private final int normalWidth;
        private final int focusWidth;
        private int edge;
        private int width;

        FocusSurface(View view, ColorStateList edges, int focusColor, int radius) {
            this.edges = edges;
            this.focusColor = focusColor;
            normalWidth = dp(view, 1);
            focusWidth = dp(view, 2);
            edge = edges.getDefaultColor();
            width = normalWidth;
            setCornerRadius(dp(view, radius));
            setStroke(width, edge);
        }

        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] states) {
            boolean changed = super.onStateChange(states);
            boolean enabled = false, focused = false;
            for (int state : states) {
                if (state == android.R.attr.state_enabled) enabled = true;
                if (state == android.R.attr.state_focused || state == android.R.attr.state_selected
                        || state == android.R.attr.state_pressed) focused = true;
            }
            int nextEdge = enabled && focused ? focusColor : edges.getColorForState(states, edges.getDefaultColor());
            int nextWidth = enabled && focused ? focusWidth : normalWidth;
            if (nextEdge == edge && nextWidth == width) return changed;
            edge = nextEdge;
            width = nextWidth;
            setStroke(width, edge);
            return true;
        }
    }

    GradientDrawable cardSurface(View view, boolean interactive) {
        GradientDrawable surface = new FocusSurface(view, dimmedWhenDisabled(interactive ? controlOutline : outline),
                heading, RADIUS_DP);
        surface.setColor(card);
        return surface;
    }

    GradientDrawable choiceSurface(View view) {
        ColorStateList edges = new ColorStateList(new int[][]{
                {-android.R.attr.state_enabled, android.R.attr.state_checked},
                {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
                new int[]{half(accent), half(controlOutline), accent, controlOutline});
        GradientDrawable surface = new FocusSurface(view, edges, heading, RADIUS_DP);
        surface.setColor(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{selectedCard, card}));
        return surface;
    }

    private GradientDrawable buttonSurface(View view, boolean primary) {
        GradientDrawable surface = new FocusSurface(view,
                dimmedWhenDisabled(primary ? actionFill : controlOutline), primary ? onAccent : heading, RADIUS_DP);
        surface.setColor(dimmedWhenDisabled(primary ? actionFill : dialog));
        return surface;
    }

    private Drawable buttonBackground(View view, boolean primary) {
        GradientDrawable surface = buttonSurface(view, primary);
        return new RippleDrawable(ColorStateList.valueOf(half(primary ? onAccent : accent)), surface, null);
    }

    static Drawable actionBackground(View view, boolean primary) {
        return DEFAULT.buttonBackground(view, primary);
    }

    static Drawable iconButtonBackground(View view) {
        GradientDrawable surface = new FocusSurface(view, ColorStateList.valueOf(Color.TRANSPARENT),
                DEFAULT.heading, 8);
        surface.setColor(Color.TRANSPARENT);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(DEFAULT.onAccent);
        mask.setCornerRadius(dp(view, 8));
        return new RippleDrawable(ColorStateList.valueOf(half(DEFAULT.accent)), surface, mask);
    }

    /** The search field and its clear button share one focused boundary. */
    static void searchContainer(ViewGroup container) {
        container.setAddStatesFromChildren(true);
        container.setBackground(DEFAULT.fieldSurface(container, 10));
    }

    static void searchField(EditText field) {
        field.setHighlightColor(half(DEFAULT.accent));
        DEFAULT.paintCursorAndHandles(field);
    }

    /** A section title. */
    void paintHeading(View row) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(heading);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            title.setLetterSpacing(0.07f);
            title.setAllCaps(true);
        }
        row.setPaddingRelative(dp(row, SIDE_DP + 4), dp(row, 20), dp(row, SIDE_DP + 4), dp(row, 8));
    }

    /** Marks the chevron a row was given, so a recycled row's can be found and taken off. */
    static final String CHEVRON = "hushpinterest_chevron";

    /**
     * Whether a tap on [preference] opens something: a dialog, a file picker, the browser. A
     * switch says what a tap does with its switch, and a row that acts the moment it's tapped
     * goes without a chevron, as the shared ImmediateAction asks, so the chevron keeps one meaning.
     */
    static boolean opensSomething(Preference preference) {
        return preference.isSelectable() && !(preference instanceof TwoStatePreference)
                && !(preference instanceof ImmediateAction && ((ImmediateAction) preference).actsOnTap());
    }

    /**
     * The chevron at the end of every row a tap opens something from. Only its title's colour set
     * such a row apart before, which is how "Licenses" and "Version" looked alike (WCAG 1.4.1). It
     * sits in the row's widget frame, where a switch row keeps its switch. Rows are recycled, and
     * one Row class draws plain lines too, so a row that shouldn't have one has it taken off.
     */
    private void paintChevron(View row, Preference preference) {
        View found = row.findViewById(android.R.id.widget_frame);
        if (!(found instanceof ViewGroup)) return;
        ViewGroup frame = (ViewGroup) found;
        View chevron = frame.findViewWithTag(CHEVRON);
        if (!opensSomething(preference)) {
            if (chevron != null) {
                frame.removeView(chevron);
                // Preference hides an empty frame only when it builds the row, which a recycled row
                // skips, and an empty frame still takes its padding's width from the text.
                if (frame.getChildCount() == 0) frame.setVisibility(View.GONE);
            }
            return;
        }
        ImageView image;
        if (chevron instanceof ImageView) {
            image = (ImageView) chevron;
        } else {
            image = new ImageView(row.getContext());
            image.setTag(CHEVRON);
            // The row already tells a screen reader it's a button; the shape adds nothing to hear.
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            frame.addView(image, new LinearLayout.LayoutParams(dp(row, 16), dp(row, 24)));
        }
        Drawable drawn = image.getDrawable();
        if (!(drawn instanceof Chevron) || ((Chevron) drawn).color != summary) {
            image.setImageDrawable(new Chevron(summary, dp(row, 2)));
        }
        image.setEnabled(preference.isEnabled());
        frame.setVisibility(View.VISIBLE);
    }

    /**
     * A chevron drawn in code, since Pinterest's APK has no resource for one. It points the way
     * the row reads, so it mirrors in a right-to-left layout, and it dims with its row.
     */
    static final class Chevron extends Drawable {
        /** The colour it's drawn in on a row that can be tapped now. */
        final int color;
        private final ColorStateList colors;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Chevron(int color, float stroke) {
            this.color = color;
            colors = dimmedWhenDisabled(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
        }

        /**
         * One arm's end, the tip and the other arm's end, as x and y pairs. The tip is at the end
         * the row reads towards: the right, or the left in a right-to-left layout. Material's own
         * chevron is 4.6 dp wide and 9.2 dp tall in a 24 dp icon, and this keeps that shape.
         */
        float[] points() {
            Rect bounds = getBounds();
            float half = bounds.height() * 0.19f;
            float reach = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -half / 2 : half / 2;
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            return new float[]{x - reach, y - half, x + reach, y, x - reach, y + half};
        }

        /** The colour it draws in now, which follows its row's state. */
        int currentColor() {
            return paint.getColor();
        }

        @Override
        public void draw(Canvas canvas) {
            float[] at = points();
            path.rewind();
            path.moveTo(at[0], at[1]);
            path.lineTo(at[2], at[3]);
            path.lineTo(at[4], at[5]);
            canvas.drawPath(path, paint);
        }

        @Override
        public boolean isAutoMirrored() {
            return true;
        }

        @Override
        public boolean onLayoutDirectionChanged(int layoutDirection) {
            invalidateSelf();
            return true;
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            int now = colors.getColorForState(state, color);
            if (now == paint.getColor()) return false;
            paint.setColor(now);
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Recovery is a choice, not another pair of settings rows. */
    private void paintRecoveryAction(View row, boolean primary) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(primary ? onAccent : secondaryActionText());
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setGravity(android.view.Gravity.CENTER);
            ViewGroup.LayoutParams size = title.getLayoutParams();
            size.width = ViewGroup.LayoutParams.MATCH_PARENT;
            title.setLayoutParams(size);
        }
        Drawable surface = buttonSurface(row, primary);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(half(primary ? onAccent : accent)),
                cardInset(row, surface), null));
        row.setMinimumHeight(dp(row, 56));
        row.setPadding(dp(row, 32), dp(row, 8), dp(row, 32), dp(row, 8));
    }

    static void recoveryMessage(View row) {
        ScreenColors palette = DEFAULT;
        HushPinterestPreferenceFragment.showAllText(row);
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        // Centred across the row: text wrapped to its own width has nothing to centre in.
        if (title != null) {
            fillWidth(title);
            title.setGravity(android.view.Gravity.CENTER);
            title.setTextSize(22);
            title.setTextColor(palette.title);
            title.setTypeface(Typeface.create("sans-serif-medium", 0));
            Drawable icon = SettingsIcons.icon(row.getContext(), SettingsIcons.ABOUT, palette.heading);
            icon.setBounds(0, 0, dp(row, 56), dp(row, 56));
            title.setCompoundDrawablesRelative(null, icon, null, null);
            title.setCompoundDrawablePadding(dp(row, 24));
        }
        if (summary != null) {
            fillWidth(summary);
            summary.setGravity(android.view.Gravity.CENTER);
            summary.setTextColor(palette.summary);
            summary.setTextSize(16);
            summary.setPadding(0, dp(row, 12), 0, 0);
        }
        row.setPadding(dp(row, 32), dp(row, 100), dp(row, 32), dp(row, 56));
    }

    private static void fillWidth(View text) {
        ViewGroup.LayoutParams size = text.getLayoutParams();
        if (size == null) return;
        size.width = ViewGroup.LayoutParams.MATCH_PARENT;
        text.setLayoutParams(size);
    }

    /**
     * A dialog on show: its surface, title, message, buttons and text field. Native choice rows
     * use the same cards, with their adapter prepared before measurement when possible.
     */
    void paint(@Nullable AlertDialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window != null) {
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(this.dialog);
            panel.setCornerRadius(dp(window.getDecorView(), RADIUS_DP));
            panel.setStroke(dp(window.getDecorView(), 1), outline);
            window.setBackgroundDrawable(panel);
            int width = Math.min(dialog.getContext().getResources().getDisplayMetrics().widthPixels
                    - dp(window.getDecorView(), SIDE_DP * 2), dp(window.getDecorView(), 560));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        @SuppressWarnings("DiscouragedApi")
        int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title != null) paintTitle(title);
        // AlertDialog's own message, and the one a preference's dialog layout brings, which is the
        // one on show there: AlertDialog's own sits GONE above it, and findViewById finds that first.
        if (window != null) paintMessages(window.getDecorView());
        paintChoices(dialog);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            boolean primary = which == AlertDialog.BUTTON_POSITIVE;
            RippleDrawable background = (RippleDrawable) buttonBackground(button, primary);
            // The framework button's own insets: the 48dp touch height keeps a 36dp fill, clear of
            // the dialog's edge like every Material button.
            background.setLayerInsetRelative(0, dp(button, 4), dp(button, 6), dp(button, 4), dp(button, 6));
            button.setBackground(background);
            button.setTextColor(dimmedWhenDisabled(primary ? onAccent : secondaryActionText()));
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            button.setAllCaps(false);
            button.setSingleLine(false);
            button.setMaxLines(Integer.MAX_VALUE);
            button.setMinHeight(dp(button, 48));
            button.setPaddingRelative(dp(button, 16), dp(button, 6), dp(button, 16), dp(button, 6));
        }
        View field = dialog.findViewById(android.R.id.edit);
        if (field instanceof EditText) paintField((EditText) field);
        keepButtonsWhole(dialog);
    }

    /**
     * Large text stacks a dialog's buttons, and AlertDialogLayout then promises the button strip
     * only its first button and a peek at the next. A long list or message takes the rest, and the
     * other buttons are left to scrolling the strip. The strip asks for all of its buttons instead,
     * up to half the screen, and the list or message above scrolls.
     */
    private static void keepButtonsWhole(AlertDialog dialog) {
        Button button = null;
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            if (button == null) button = dialog.getButton(which);
        }
        if (button == null || !(button.getParent() instanceof ViewGroup)) return;
        ViewGroup bar = (ViewGroup) button.getParent();
        if (!(bar.getParent() instanceof ScrollView)) return;
        ScrollView strip = (ScrollView) bar.getParent();
        bar.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int most = view.getResources().getDisplayMetrics().heightPixels / 2;
            int wanted = Math.min(buttonsHeight(bar), most);
            if (wanted > 0 && strip.getMinimumHeight() != wanted) strip.post(() -> strip.setMinimumHeight(wanted));
        });
        bar.requestLayout();
    }

    /**
     * How tall a dialog's buttons are from the first one's top to the last one's bottom, with the
     * bar's padding. The bar's own height won't do: the strip stretches it to fill, so once a minimum
     * is set, buttons that go back to one row would keep the stacked height.
     */
    private static int buttonsHeight(ViewGroup bar) {
        int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;
        for (int index = 0; index < bar.getChildCount(); index++) {
            View child = bar.getChildAt(index);
            if (child.getVisibility() != View.VISIBLE) continue;
            ViewGroup.LayoutParams size = child.getLayoutParams();
            ViewGroup.MarginLayoutParams margins = size instanceof ViewGroup.MarginLayoutParams
                    ? (ViewGroup.MarginLayoutParams) size : null;
            top = Math.min(top, child.getTop() - (margins == null ? 0 : margins.topMargin));
            bottom = Math.max(bottom, child.getBottom() + (margins == null ? 0 : margins.bottomMargin));
        }
        return bottom < top ? 0 : bottom - top + bar.getPaddingTop() + bar.getPaddingBottom();
    }

    /** A dialog's title, the framework's own or one the dialog draws itself. */
    void paintTitle(TextView title) {
        title.setTextColor(this.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(false);
        title.setMaxLines(Integer.MAX_VALUE);
    }

    /**
     * A dialog's text field. The framework theme draws its underline, cursor and selection in its
     * own teal, so they take the accent the buttons have.
     */
    void paintField(EditText field) {
        field.setHintTextColor(dimmedWhenDisabled(summary));
        field.setBackgroundTintList(null);
        field.setBackground(fieldSurface(field, RADIUS_DP));
        field.setPaddingRelative(dp(field, CONTENT_DP), dp(field, 12), dp(field, CONTENT_DP), dp(field, 12));
        field.setMinimumHeight(dp(field, 48));
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setTextColor(dimmedWhenDisabled(title));
        field.setHighlightColor(half(accent));
        paintCursorAndHandles(field);
    }

    /**
     * A dialog's own controls beside its choices, such as a filter and a count. Radio buttons take
     * the rows' text and indicator colours with a 48 dp target, other text the message colour.
     */
    void paintControls(View view) {
        if (view instanceof RadioButton) {
            RadioButton option = (RadioButton) view;
            option.setTextColor(dimmedWhenDisabled(title));
            option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            option.setButtonTintList(new ColorStateList(new int[][]{
                    {-android.R.attr.state_enabled, android.R.attr.state_checked},
                    {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
                    new int[]{half(accent), half(summary), accent, summary}));
            option.setMinHeight(dp(option, 48));
        } else if (view instanceof TextView) {
            ((TextView) view).setTextColor(summary);
            ((TextView) view).setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) paintControls(group.getChildAt(index));
        }
    }

    private GradientDrawable fieldSurface(View field, int radius) {
        GradientDrawable surface = new FocusSurface(field, dimmedWhenDisabled(controlOutline), heading, radius);
        surface.setColor(card);
        return surface;
    }

    /** Wrap before the list's first measurement, preserving the framework's choice state. */
    private void paintChoices(AlertDialog dialog) {
        android.widget.ListView list = dialog.getListView();
        if (list == null || list.getAdapter() == null || list.getAdapter() instanceof DialogChoices) return;
        int mode = list.getChoiceMode();
        if (mode != android.widget.ListView.CHOICE_MODE_SINGLE
                && mode != android.widget.ListView.CHOICE_MODE_MULTIPLE) return;
        SparseBooleanArray selected = list.getCheckedItemPositions().clone();
        list.setAdapter(new DialogChoices(list.getAdapter(), this));
        for (int index = 0; index < selected.size(); index++) {
            list.setItemChecked(selected.keyAt(index), selected.valueAt(index));
        }
        list.setDivider(null);
    }

    private void paintCursorAndHandles(EditText field) {
        Drawable cursor = field.getTextCursorDrawable();
        if (cursor != null) {
            cursor = cursor.mutate();
            cursor.setTint(accent);
            field.setTextCursorDrawable(cursor);
        }
        Drawable handle = field.getTextSelectHandle();
        if (handle != null) {
            handle = handle.mutate();
            handle.setTint(accent);
            field.setTextSelectHandle(handle);
        }
        Drawable left = field.getTextSelectHandleLeft();
        if (left != null) {
            left = left.mutate();
            left.setTint(accent);
            field.setTextSelectHandleLeft(left);
        }
        Drawable right = field.getTextSelectHandleRight();
        if (right != null) {
            right = right.mutate();
            right.setTint(accent);
            field.setTextSelectHandleRight(right);
        }
    }

    private void paintMessages(View view) {
        if (view instanceof TextView && view.getId() == android.R.id.message) ((TextView) view).setTextColor(summary);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) paintMessages(group.getChildAt(index));
        }
    }

    /** Paints a row in the screen's colours. */
    static void row(View row, Preference preference) {
        DEFAULT.paintRow(row, preference);
    }

    /** Paints a section title in the screen's colours. */
    static void heading(View row) {
        DEFAULT.paintHeading(row);
    }

    /** Paints both recovery actions even if the ordinary settings page failed to initialize. */
    static void recoveryAction(View row, boolean primary) {
        DEFAULT.paintRecoveryAction(row, primary);
    }

    /** Paints a dialog in the screen's colours. */
    static void dialog(@Nullable AlertDialog dialog) {
        DEFAULT.paint(dialog);
    }

    /** Styles native radio and checkbox choices after create(), before show() measures them. */
    static void choices(@Nullable AlertDialog dialog) {
        if (dialog != null) DEFAULT.paintChoices(dialog);
    }

    /** Paints a dialog's own controls in the screen's colours. */
    static void controls(@Nullable View view) {
        if (view != null) DEFAULT.paintControls(view);
    }

    /** Paints a title a dialog draws itself in the screen's colours. */
    static void title(@Nullable TextView title) {
        if (title != null) DEFAULT.paintTitle(title);
    }

    private static void startMargin(View view, int margin) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams size = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            size.setMarginStart(margin);
            view.setLayoutParams(size);
        }
    }

    private static int dp(View view, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                view.getResources().getDisplayMetrics()));
    }

    /** A switch's track: the thumb's colour at half strength, as Material's own switch draws it. */
    static int half(int color) {
        return (color & 0x00FFFFFF) | 0x80000000;
    }
}
