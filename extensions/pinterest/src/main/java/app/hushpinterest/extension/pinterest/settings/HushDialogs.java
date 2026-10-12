/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;

/** The shared dialog theme for HushPinterest actions outside the settings package. */
public final class HushDialogs {
    private HushDialogs() {}

    public static Context context(Context base) {
        return new ContextThemeWrapper(base, ScreenColors.THEME);
    }

    public static AlertDialog.Builder builder(Context base) {
        return new AlertDialog.Builder(context(base));
    }

    /** Styles native choice rows before the dialog's first measurement. */
    public static void prepareChoices(AlertDialog dialog) {
        dialog.create();
        ScreenColors.choices(dialog);
    }

    /** Styles a dialog's own controls, such as a filter under its choices, before it's shown. */
    public static void paintControls(View controls) {
        ScreenColors.controls(controls);
    }

    /** Styles a title a dialog draws itself, as its own title would be. */
    public static void paintTitle(android.widget.TextView title) {
        ScreenColors.title(title);
    }

    /** Paints a shown dialog without changing its listeners or actions. */
    public static void paint(AlertDialog dialog) {
        ScreenColors.dialog(dialog);
    }
}
