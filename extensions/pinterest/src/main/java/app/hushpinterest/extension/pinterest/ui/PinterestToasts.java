/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ui;

import android.app.Activity;
import android.view.View;
import android.widget.Toast;

import app.hushpinterest.extension.shared.Logger;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.settings.HushPinterestPause;

/**
 * HushPinterest's messages as Pinterest's own text toasts, in the toast layer of the Pinterest
 * screen in front. A message goes out as an Android toast instead while HushPinterest is paused,
 * when no Pinterest screen has the window focus (a dialog such as the HushPinterest page covers
 * it), when its toast layer isn't showing, when the message is long or has more than one line, or
 * when the patch found no text toast to build in this build. The settings patch fills in
 * {@link #container} and {@link #post}; unpatched, they answer nothing and every message stays an
 * Android toast. Pinterest skips a text it showed a moment ago, so the toast is marked as one to
 * show anyway.
 */
public final class PinterestToasts {
    /** Pinterest's toast layer keeps a toast at least this long. */
    static final int SHORT_MS = 5000;
    /** What Pinterest's own text toast helper asks for. */
    static final int LONG_MS = 7000;
    /** About two lines of a Pinterest toast on a phone. Longer messages stay Android toasts. */
    static final int LONGEST = 80;

    /** Set once a patched stub fails, so the rest of the session uses Android toasts. */
    private static volatile boolean failed;

    private PinterestToasts() {
    }

    /** Called when Pinterest starts. */
    public static void install() {
        Utils.setToastPresenter(PinterestToasts::show);
    }

    /** True when Pinterest's toast took the message. Runs on the main thread. */
    static boolean show(String message, int duration) {
        if (failed || HushPinterestPause.isPaused() || !fits(message)) return false;
        Activity activity = Utils.getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) {
            return false;
        }
        try {
            View layer = container(activity);
            if (layer == null || !layer.isShown()) return false;
            return post(activity, message, duration == Toast.LENGTH_LONG ? LONG_MS : SHORT_MS);
        } catch (Exception | LinkageError ex) {
            failed = true;
            Logger.printException(() -> "Pinterest's toast failed; Android toasts from now on", ex);
            return false;
        }
    }

    /** Text short enough for a Pinterest toast, with no line breaks. */
    static boolean fits(String message) {
        return !message.isEmpty() && message.length() <= LONGEST && message.indexOf('\n') < 0;
    }

    /**
     * Answers null here. Patching has it set up the activity's Pinterest toast layer, as Pinterest
     * does before its own toasts, and answer that layer, or null for an activity that has none.
     */
    public static View container(Activity activity) {
        return null;
    }

    /**
     * Answers false here. Patching has it build Pinterest's text toast with the message and
     * duration and hand it to the activity, answering true.
     */
    public static boolean post(Activity activity, String message, int durationMs) {
        return false;
    }
}
