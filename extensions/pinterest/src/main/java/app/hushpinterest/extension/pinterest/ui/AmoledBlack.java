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

import java.util.Map;
import java.util.WeakHashMap;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/**
 * Paints the bottom bar black in dark mode. Pinterest fills it with the same warm gray theme color
 * its menus, popovers and floating buttons use, so the color is changed on the bar's own drawables
 * rather than in the theme. Only opaque solid fills change: the bar's background and the plain row
 * that holds its tabs. Gradients stay, so the shadow strip above the bar and the scrim behind a
 * floating bar keep their look, and so do the tabs and the selected-tab marker.
 */
public final class AmoledBlack {
    private AmoledBlack() {}

    /** The fill each painted drawable had before, so off, Pause or light mode gives it back. */
    private static final Map<GradientDrawable, ColorStateList> shapes = new WeakHashMap<>();
    private static final Map<ColorDrawable, Integer> colors = new WeakHashMap<>();

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
                if (child != null && child.getClass() == LinearLayout.class) paint(child, black);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AMOLED_BLACK, "bottom bar", failure);
        }
    }

    static boolean night(View view) {
        return (view.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static void paint(View view, boolean black) {
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
