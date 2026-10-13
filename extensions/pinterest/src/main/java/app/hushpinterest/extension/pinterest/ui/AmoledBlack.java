/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ui;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/**
 * Paints the bottom bar black in dark mode. Pinterest fills it with the same warm gray theme color
 * its menus, popovers and floating buttons use, so the color is changed on the bar's own views
 * rather than in the theme. Only opaque solid colors change: the bar's background, the plain row
 * that holds its tabs, each tab's rounded background where the bar shows its tabs apart, the search
 * pill where a tab is one, and the strip Pinterest draws behind the gesture handle. Where those
 * tabs are frosted glass they have no fill, and the color Pinterest washes the glass with is
 * answered black instead. Gradients stay, so the shadow strip above the bar and the scrim behind a
 * floating bar keep their look, and so do the icons, each tab's outline and the selected-tab marker.
 */
public final class AmoledBlack {
    private AmoledBlack() {}

    /** The fill or tint each painted view had before, so off, Pause or light mode gives it back. */
    private static final Map<GradientDrawable, ColorStateList> shapes = new WeakHashMap<>();
    private static final Map<ColorDrawable, Integer> colors = new WeakHashMap<>();
    private static final Map<View, ColorStateList> tints = new WeakHashMap<>();
    /** The bars drawn so far. Each one repaints after its layout passes, and glass under one washes black. */
    private static final Map<View, Boolean> bars = new WeakHashMap<>();
    /** The gesture strip found under each window's root view. */
    private static final Map<View, WeakReference<View>> strips = new WeakHashMap<>();
    /** How many bar draws found no strip under each root view so far. */
    private static final Map<View, Integer> misses = new WeakHashMap<>();
    /** Bar draws that look for a strip Pinterest may add after the bar's first draw, before a window counts as having none. */
    private static final int SEARCHES = 8;

    // Pinterest's ids, read by name the first time: -1 before that, 0 when this build has none.
    static int tabRoot = -1;
    static int searchText = -1;
    static int searchContainer = -1;
    static int gestureStrip = -1;

    /**
     * Pinterest fills the row again, styles its tabs and builds new ones whenever the screen above
     * the bar changes, and each of those asks for a layout. A change inside the bar redraws only
     * the view that changed, so the bar's own draw doesn't run again and a new tab would keep
     * Pinterest's gray. The layout pass comes before that tab's first frame.
     */
    private static final View.OnLayoutChangeListener REPAINT =
            (bar, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                try {
                    paintBar(bar, wanted(bar), false);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.AMOLED_BLACK, "bottom bar layout", failure);
                }
            };

    /**
     * Called as the bar starts drawing its children. A ViewGroup with no background of its own skips
     * draw() but never dispatchDraw(). The tab row draws after this, so its fill changes in the same
     * frame. The bar's own background was drawn already, and setColor asks for the next frame.
     */
    public static void bottomBar(View bar) {
        try {
            if (bar == null) return;
            if (bars.put(bar, Boolean.TRUE) == null) bar.addOnLayoutChangeListener(REPAINT);
            paintBar(bar, UiHooks.enabled(FamilyNames.AMOLED_BLACK, Settings.BLACK_BOTTOM_BAR) && night(bar), true);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "bottom bar", failure);
        }
    }

    /**
     * Pinterest's frosted-glass view is about to wash its blurred backdrop with a color, the last
     * thing it draws on every frame. Where the bar's tabs and search pill are glass, that wash is
     * their gray. Glass under a bottom bar is washed black in dark mode with the switch on. Any
     * other glass, and the bar's own with the switch off or paused, keeps Pinterest's color.
     */
    public static int blurFill(View glass, int color) {
        try {
            return glass != null && underBar(glass) && wanted(glass) ? Color.BLACK : color;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "bottom bar glass", failure);
            return color;
        }
    }

    private static boolean underBar(View view) {
        if (bars.isEmpty()) return false;
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (bars.containsKey(parent)) return true;
        }
        return false;
    }

    /**
     * The switch read again for a layout pass or a frame of glass, which come far more often than
     * the bar's own draw, so it isn't counted as another call.
     */
    private static boolean wanted(View view) {
        return Utils.settingsReady() && Settings.BLACK_BOTTOM_BAR.get() && night(view);
    }

    /** Only the bar's own draw, the searching pass, looks for a gesture strip it hasn't found yet. */
    private static void paintBar(View bar, boolean black, boolean searching) {
        paint(bar, black);
        if (!(bar instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) bar;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            // The tab row is a plain LinearLayout. Pinterest's own views beside it keep their colors.
            if (child == null || child.getClass() != LinearLayout.class) continue;
            paint(child, black);
            tabs((ViewGroup) child, black);
        }
        paint(gestureStrip(bar, searching), black);
    }

    /**
     * Where the bar shows its tabs apart, each tab tints its own rounded background gray. A search
     * tab is a plain frame around Pinterest's search pill instead, and the pill carries the gray
     * itself or on the container inside it.
     */
    private static void tabs(ViewGroup row, boolean black) {
        if (tabRoot < 0) tabRoot = id(row, "tab_item_root");
        if (searchText < 0) searchText = id(row, "static_search_text");
        if (searchContainer < 0) searchContainer = id(row, "static_search_bar_container");
        for (int index = 0; index < row.getChildCount(); index++) {
            View tab = row.getChildAt(index);
            if (tab == null) continue;
            View background = tabRoot == 0 ? null : tab.findViewById(tabRoot);
            if (background != null) {
                paint(background, black);
                continue;
            }
            View text = searchText == 0 ? null : tab.findViewById(searchText);
            if (text == null) continue;
            paint(childHolding(tab, text), black);
            if (searchContainer != 0) paint(tab.findViewById(searchContainer), black);
        }
    }

    /** The direct child of the tab that the inner view sits in, or null when that view isn't under the tab. */
    private static View childHolding(View tab, View inner) {
        View child = inner;
        while (child != null && child.getParent() != tab) {
            child = child.getParent() instanceof View ? (View) child.getParent() : null;
        }
        return child;
    }

    /**
     * The strip Pinterest draws behind the gesture handle, kept once found. A window that shows none
     * on the bar's first draws is searched again on its next few, then counts as having none.
     */
    private static View gestureStrip(View bar, boolean searching) {
        View root = bar.getRootView();
        WeakReference<View> found = strips.get(root);
        View known = found == null ? null : found.get();
        if (known != null || !searching) return known;
        Integer missed = misses.get(root);
        if (missed != null && missed >= SEARCHES) return null;
        if (gestureStrip < 0) gestureStrip = id(bar, "navigation_bar_background");
        View strip = gestureStrip == 0 ? null : root.findViewById(gestureStrip);
        if (strip != null) {
            strips.put(root, new WeakReference<>(strip));
            misses.remove(root);
        } else {
            misses.put(root, missed == null ? 1 : missed + 1);
        }
        return strip;
    }

    private static int id(View view, String name) {
        return view.getResources().getIdentifier(name, "id", view.getContext().getPackageName());
    }

    static boolean night(View view) {
        return (view.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static void paint(View view, boolean black) {
        if (view == null) return;
        // A tint colors the whole background over its own fill, so the tint is what changes.
        if (view.getBackgroundTintList() != null || tints.containsKey(view)) {
            tint(view, black);
            return;
        }
        Drawable background = view.getBackground();
        if (background instanceof GradientDrawable) paint((GradientDrawable) background, black);
        else if (background instanceof ColorDrawable) paint((ColorDrawable) background, black);
    }

    private static void paint(GradientDrawable shape, boolean black) {
        if (!black) {
            ColorStateList original = shapes.remove(shape);
            if (original != null) shape.setColor(original);
            return;
        }
        ColorStateList fill = shape.getColor();
        if (fill == null || shape.getColors() != null || !opaque(fill.getDefaultColor()) || isBlack(fill)) return;
        // Drawables loaded from one resource share their state until one is mutated, and the bar's
        // shape comes from a resource other views could load too.
        shape.mutate();
        shapes.put(shape, fill);
        shape.setColor(Color.BLACK);
    }

    private static void tint(View view, boolean black) {
        if (!black) {
            ColorStateList original = tints.remove(view);
            if (original != null) view.setBackgroundTintList(original);
            return;
        }
        ColorStateList tint = view.getBackgroundTintList();
        if (tint == null || !opaque(tint.getDefaultColor()) || isBlack(tint)) return;
        tints.put(view, tint);
        view.setBackgroundTintList(ColorStateList.valueOf(Color.BLACK));
    }

    private static void paint(ColorDrawable fill, boolean black) {
        if (!black) {
            Integer original = colors.remove(fill);
            if (original != null) fill.setColor(original);
            return;
        }
        int color = fill.getColor();
        if (!opaque(color) || color == Color.BLACK) return;
        fill.mutate();
        colors.put(fill, color);
        fill.setColor(Color.BLACK);
    }

    private static boolean opaque(int color) {
        return Color.alpha(color) == 0xFF;
    }

    private static boolean isBlack(ColorStateList fill) {
        return !fill.isStateful() && fill.getDefaultColor() == Color.BLACK;
    }
}
