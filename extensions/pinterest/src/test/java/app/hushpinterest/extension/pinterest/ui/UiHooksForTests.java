/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ui;

import android.widget.Toast;

import java.util.Locale;

import org.robolectric.shadows.ShadowToast;

/** Lets tests in other packages name the class the save toast hook treats as a save toast. */
public final class UiHooksForTests {
    private UiHooksForTests() {}

    public static void saveToast(Class<?> type) {
        UiHooks.saveToastForTests = type;
    }

    /**
     * An English "Saved!" from a screen that saves pins, made through the stand-in for
     * Toast.makeText and shown the way Pinterest shows it: true when it never showed.
     */
    public static boolean quietsSavedToast() {
        int shown = ShadowToast.shownToastCount();
        UiHooks.androidToast(SaveStrings.context(Locale.ENGLISH, SaveStrings.english()), "Saved!", Toast.LENGTH_SHORT).show();
        return ShadowToast.shownToastCount() == shown;
    }
}
