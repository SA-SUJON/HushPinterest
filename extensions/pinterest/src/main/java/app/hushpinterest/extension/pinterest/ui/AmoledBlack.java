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
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/**
 * Paints the bottom bar black in dark mode. Pinterest fills it with the same warm gray theme color
 * its menus, popovers and floating buttons use, so the color is changed on the bar's own views
 * rather than in the theme. Only opaque solid colors change: the bar's background, the plain row
 * that holds its tabs, each tab's rounded background where the bar shows its tabs apart, and the
 * strip Pinterest draws behind the gesture handle. Gradients stay, so the shadow strip above the
 * bar and the scrim behind a floating bar keep their look, and so do the icons and the selected-tab
 * marker.
 */
public final class AmoledBlack {
    private AmoledBlack() {}

    /** The fill or tint each painted view had before, so off, Pause or light mode gives it back. */
    private static final Map<GradientDrawable, ColorStateList> shapes = new WeakHashMap<>();
    private static final Map<ColorDrawable, Integer> colors = new WeakHashMap<>();
    private static final Map<View, ColorStateList> tints = new WeakHashMap<>();
    /** The gesture strip found under each window's root view. */
    private static final Map<View, WeakReference<View>> strips = new WeakHashMap<>();
    /** How many bar draws found no strip under each root view so far. */
    private static final Map<View, Integer> misses = new WeakHashMap<>();
    /** Bar draws that look for a strip Pinterest may add after the bar's first draw, before a window counts as having none. */
    private static final int SEARCHES = 8;

    // Pinterest's ids, read by name the first time: -1 before that, 0 when this build has none.
    static int tabRoot = -1;
    static int gestureStrip = -1;

    /**
     * Called as the bar starts drawing its children. A ViewGroup with no background of its own skips
     * draw() but never dispatchDraw(). The tab row draws after this, so its fill changes in the same
     * frame. The bar's own background was drawn already, and setColor asks for the next frame.
     */
    public static void bottomBar(View bar) {
        try {
            if (bar == null) return;
            boolean black = UiHooks.enabled(FamilyNames.AMOLED_BLACK, Settings.BLACK_BOTTOM_BAR) && night(bar);
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
            paint(gestureStrip(bar), black);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "bottom bar", failure);
        }
    }

    /** Where the bar shows its tabs apart, each tab tints its own rounded background gray. */
    private static void tabs(ViewGroup row, boolean black) {
        if (tabRoot < 0) tabRoot = id(row, "tab_item_root");
        if (tabRoot == 0) return;
        for (int index = 0; index < row.getChildCount(); index++) {
            View tab = row.getChildAt(index);
            View background = tab == null ? null : tab.findViewById(tabRoot);
            if (background != null) paint(background, black);
        }
    }

    /**
     * The strip Pinterest draws behind the gesture handle, kept once found. A window that shows none
     * on the bar's first draws is searched again on its next few, then counts as having none.
     */
    private static View gestureStrip(View bar) {
        View root = bar.getRootView();
        WeakReference<View> found = strips.get(root);
        View known = found == null ? null : found.get();
        if (known != null) return known;
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
