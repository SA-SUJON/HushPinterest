/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;
import app.hushpinterest.extension.shared.settings.HushPinterestPause;
import app.hushpinterest.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CloseupButtonsTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();

    @After public void restore() {
        PauseForTests.resume();
        Settings.HIDE_CLOSEUP_BACK.resetToDefault();
        Settings.HIDE_CLOSEUP_LENS.resetToDefault();
        HookStatus.clear();
    }

    private static View button() {
        return new View(RuntimeEnvironment.getApplication());
    }

    @Test public void offByDefaultBothButtonsShowAsPinterestAsks() {
        View back = button();
        View lens = button();
        CloseupButtons.backArrow(back);
        CloseupButtons.lens(lens);
        assertEquals(View.VISIBLE, back.getVisibility());
        assertEquals(View.VISIBLE, lens.getVisibility());
        assertEquals(View.VISIBLE, CloseupButtons.visibility(back, View.VISIBLE));
        assertEquals(View.INVISIBLE, CloseupButtons.visibility(lens, View.INVISIBLE));
    }

    @Test public void eachSwitchHidesOnlyItsOwnButtonAndHoldsItThroughPinterestsShowCalls() {
        Settings.HIDE_CLOSEUP_BACK.save(true);
        View back = button();
        View lens = button();
        CloseupButtons.backArrow(back);
        CloseupButtons.lens(lens);
        assertEquals(View.GONE, back.getVisibility());
        assertEquals(View.VISIBLE, lens.getVisibility());
        assertEquals("the arrow shown again as the closeup scrolls", View.GONE, CloseupButtons.visibility(back, View.VISIBLE));
        assertEquals("INVISIBLE would still take room", View.GONE, CloseupButtons.visibility(back, View.INVISIBLE));
        assertEquals(View.VISIBLE, CloseupButtons.visibility(lens, View.VISIBLE));

        Settings.HIDE_CLOSEUP_LENS.save(true);
        View nextLens = button();
        CloseupButtons.lens(nextLens);
        assertEquals(View.GONE, nextLens.getVisibility());
        assertEquals(View.GONE, CloseupButtons.visibility(nextLens, View.VISIBLE));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("back arrow hidden"));
        assertTrue(report, report.contains("visual search hidden"));
    }

    @Test public void iconButtonsThatWerentHandedOverAreLeftAlone() {
        Settings.HIDE_CLOSEUP_BACK.save(true);
        Settings.HIDE_CLOSEUP_LENS.save(true);
        // Held here, so the marked arrow stays in the weak map and the lookup below is really made.
        View marked = button();
        CloseupButtons.backArrow(marked);
        View other = button();
        assertEquals(View.VISIBLE, CloseupButtons.visibility(other, View.VISIBLE));
        assertEquals(View.INVISIBLE, CloseupButtons.visibility(other, View.INVISIBLE));
        assertEquals(View.VISIBLE, CloseupButtons.visibility(null, View.VISIBLE));
        assertEquals(View.GONE, CloseupButtons.visibility(marked, View.VISIBLE));
        CloseupButtons.backArrow(null);
        CloseupButtons.lens(null);
    }

    @Test public void offOrPausedPinterestCanShowTheButtonAndTheNextCloseupKeepsIt() {
        Settings.HIDE_CLOSEUP_BACK.save(true);
        View back = button();
        CloseupButtons.backArrow(back);
        assertEquals(View.GONE, back.getVisibility());

        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        assertEquals(View.VISIBLE, CloseupButtons.visibility(back, View.VISIBLE));
        View next = button();
        CloseupButtons.backArrow(next);
        assertEquals("paused, the next closeup's arrow shows", View.VISIBLE, next.getVisibility());
        PauseForTests.resume();

        Settings.HIDE_CLOSEUP_BACK.save(false);
        assertEquals(View.VISIBLE, CloseupButtons.visibility(back, View.VISIBLE));
        View after = button();
        CloseupButtons.backArrow(after);
        assertEquals("off, the next closeup's arrow shows", View.VISIBLE, after.getVisibility());
    }

    @Test public void pinterestsOwnGoneAlwaysGoesThrough() {
        View back = button();
        CloseupButtons.backArrow(back);
        assertEquals(View.GONE, CloseupButtons.visibility(back, View.GONE));
        Settings.HIDE_CLOSEUP_BACK.save(true);
        assertEquals(View.GONE, CloseupButtons.visibility(back, View.GONE));
        assertFalse("the lens switch stays off", Settings.HIDE_CLOSEUP_LENS.savedValue());
    }
}
