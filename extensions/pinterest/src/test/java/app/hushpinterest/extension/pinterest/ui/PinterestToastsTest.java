/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ui;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowToast;

import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, shadows = PinterestToastsTest.Stubs.class,
        instrumentedPackages = "app.hushpinterest.extension.pinterest.ui")
public class PinterestToastsTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();

    /** What the patch writes into the stubs: Pinterest's toast layer, when one is set, and the toasts handed to it. */
    @Implements(value = PinterestToasts.class, isInAndroidSdk = false)
    public static class Stubs {
        static View layer;
        static final List<Object> posted = new ArrayList<>();

        @Implementation protected static View container(Activity activity) { return layer; }

        @Implementation protected static boolean post(Activity activity, String message, int durationMs) {
            posted.add(message);
            posted.add(durationMs);
            return true;
        }
    }

    private ActivityController<Activity> screen;

    @Before public void prepare() {
        PauseForTests.resume();
        screen = Robolectric.buildActivity(Activity.class).setup();
        Utils.setActivity(screen.get());
        PinterestToasts.install();
    }

    @After public void reset() {
        Utils.setToastPresenter(null);
        Utils.setActivity(null);
        Stubs.layer = null;
        Stubs.posted.clear();
    }

    @Test public void aShownToastLayerTakesOneLineMessagesAndAndroidKeepsTheRest() {
        FrameLayout layer = new FrameLayout(screen.get());
        screen.get().setContentView(layer);
        screen.windowFocusChanged(true);
        Stubs.layer = layer;

        Utils.showToastLong("Download started");
        Utils.showToastShort("Queued: 1\nSkipped: 1");
        screen.windowFocusChanged(false);
        Utils.showToastShort("Link copied");

        assertEquals(Arrays.asList("Download started", PinterestToasts.LONG_MS), Stubs.posted);
        assertEquals(2, ShadowToast.shownToastCount());
        assertEquals("Link copied", ShadowToast.getTextOfLatestToast());
    }

    @Test public void unpatchedEveryMessageIsAnAndroidToast() {
        screen.windowFocusChanged(true);

        Utils.showToastShort("Download started");

        assertEquals(Arrays.asList(), Stubs.posted);
        assertEquals("Download started", ShadowToast.getTextOfLatestToast());
    }
}
