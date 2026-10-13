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

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import app.hushpinterest.extension.pinterest.NativeDialogCapture;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.settings.BaseSettings;
import app.hushpinterest.extension.shared.settings.FailingStore;
import app.hushpinterest.extension.shared.settings.HushPinterestPause;
import app.hushpinterest.extension.shared.settings.PauseForTests;
import app.hushpinterest.extension.shared.settings.Setting;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/** Exercises the actual dialog host and its filtered view of the original preference model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w390dp-h844dp-night-xhdpi")
@SuppressWarnings("deprecation")
public class SettingsNavigationTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private SettingsDialog dialog;
    private HushPinterestPreferenceFragment page;

    @Before public void open() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        PauseForTests.resume();
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = SettingsL10nTest.show(controller.get());
        page = page(dialog);
    }

    @After public void close() {
        controller.close();
        PatchFamily.inBuildForTests = null;
        PatchFamily.capabilitiesForTests = null;
        Settings.HIDE_ADS.resetToDefault();
        Settings.HIDE_AI_PINS.resetToDefault();
        BaseSettings.PAUSED.resetToDefault();
        app.hushpinterest.extension.shared.settings.preference.AbstractPreferenceFragment.restartPending.remove(
                Settings.HIDE_ADS.key);
        app.hushpinterest.extension.shared.settings.preference.AbstractPreferenceFragment.restartPending.remove(
                BaseSettings.DEBUG.key);
        PauseForTests.resume();
    }

    @Test @Config(sdk = {29, 30, 33, 36})
    public void homeAndEveryCategoryAreReachableWithoutRemovingTheModel() {
        assertNotNull(page.navigation);
        // Status, Quick setups, Browse settings, four feature categories, More settings, Report a
        // problem and Support HushPinterest.
        assertEquals(10, list().getCount());
        assertEquals(10, page.sections().size());
        int total = page.getPreferenceScreen().getRootAdapter().getCount();
        for (Preference section : page.sections()) {
            assertTrue(page.navigation.open(section));
            assertEquals(((PreferenceCategory) section).getPreferenceCount(), list().getCount());
            assertEquals(total, page.getPreferenceScreen().getRootAdapter().getCount());
            assertNotNull(page.findPreference(Settings.HIDE_ADS.key));
            assertTrue(page.navigation.back());
            while (page.navigation.back()) { }
        }
        assertEquals(10, list().getCount());
    }

    @Test public void theHomePageEndsWithSupportWhichOpensKoFi() {
        Map<String, Object> before = savedValues();
        int last = list().getCount() - 1;
        Preference row = (Preference) list().getItemAtPosition(last);
        assertEquals(HushPinterestPreferenceFragment.SUPPORT, row.getKey());
        assertEquals("Support HushPinterest", String.valueOf(row.getTitle()));
        assertEquals("Buy me a coffee on Ko-fi", String.valueOf(row.getSummary()));
        assertEquals("Report a problem", String.valueOf(((Preference) list().getItemAtPosition(last - 1)).getTitle()));
        assertEquals("More settings", String.valueOf(((Preference) list().getItemAtPosition(last - 2)).getTitle()));
        assertTrue(list().getAdapter().isEnabled(last));

        tap(HushPinterestPreferenceFragment.SUPPORT);
        Intent started = org.robolectric.Shadows.shadowOf(controller.get()).getNextStartedActivity();
        assertNotNull("nothing opened", started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("https://ko-fi.com/X8K126YVER", started.getDataString());
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertTrue("the page would open inside Pinterest's task",
                (started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertNull(started.getComponent());
        assertEquals("the tap left the home page", last + 1, list().getCount());

        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).checkActivities(true);
        tap(HushPinterestPreferenceFragment.SUPPORT);
        assertEquals("No app can open " + L10n.isolate("ko-fi.com/X8K126YVER") + ".",
                ShadowToast.getTextOfLatestToast());
        assertFalse(controller.get().isFinishing());
        assertEquals(before, savedValues());
    }

    /** The Notifications page leads to Android's page for this app's notifications, and a search finds the way. */
    @Test public void theNotificationsPageOpensAndroidsNotificationSettingsAndChangesNothing() {
        Map<String, Object> before = savedValues();
        page.navigation.navigate("Notifications");
        assertTrue(contains(Settings.FIX_PUSH_NOTIFICATIONS.key));
        tap(HushPinterestPreferenceFragment.NOTIFICATION_SETTINGS);
        Intent started = org.robolectric.Shadows.shadowOf(controller.get()).getNextStartedActivity();
        assertNotNull("nothing opened", started);
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.getAction());
        assertEquals(controller.get().getPackageName(), started.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE));
        assertEquals(before, savedValues());

        while (page.navigation.back()) { }
        findSearch(dialog.getView()).setText("notification settings");
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(contains(HushPinterestPreferenceFragment.NOTIFICATION_SETTINGS));
    }

    @Test public void categoryClickChangesOnlyTheSettingWhoseRowWasTapped() {
        assertTrue(page.navigation.open(page.findPreference(Settings.HIDE_AI_PINS.key)));
        // It joined Manager's default selection with its switch off.
        assertFalse(Settings.HIDE_AI_PINS.savedValue());
        tap(Settings.HIDE_AI_PINS.key);
        assertTrue(Settings.HIDE_AI_PINS.savedValue());
        assertTrue(Settings.HIDE_ADS.savedValue());
    }

    @Test public void overviewPauseAndUndoUpdateTheSavedSwitchAndRestartNotice() {
        layout(dialog.getView());
        android.widget.Button action = statusAction();
        assertEquals("Pause", action.getText().toString());
        assertTrue(action.getMinimumHeight() >= Math.round(48 * action.getResources().getDisplayMetrics().density));
        action.performClick();
        ShadowLooper.idleMainLooper();
        layout(dialog.getView());
        assertTrue(BaseSettings.PAUSED.savedValue());
        assertTrue(((Preference) list().getItemAtPosition(0)).getSummary().toString()
                .contains("pauses when Pinterest restarts"));
        action = statusAction();
        assertEquals("Undo", action.getText().toString());
        action.performClick();
        ShadowLooper.idleMainLooper();
        layout(dialog.getView());
        assertFalse(BaseSettings.PAUSED.savedValue());
        assertEquals("Pause", statusAction().getText().toString());
        assertTrue(Settings.HIDE_ADS.savedValue());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atNormalTextTheOverviewActionHasAFullWidthLineBelowItsSummary() {
        assertEquals(1f, controller.get().getResources().getConfiguration().fontScale, 0f);
        layout(dialog.getView());
        View row = list().getChildAt(0);
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        android.widget.Button action = statusAction();
        assertEquals("Pause", action.getText().toString());
        assertEquals(summary.getParent(), action.getParent());
        assertTrue("the action overlaps its summary", action.getTop() >= summary.getBottom());
        android.view.ViewGroup column = (android.view.ViewGroup) summary.getParent();
        assertEquals("the action doesn't start at the text column's edge", column.getPaddingLeft(), action.getLeft());
        assertEquals("the action doesn't fill the text column", column.getWidth() - column.getPaddingRight(), action.getRight());
        int touchTarget = Math.round(48 * action.getResources().getDisplayMetrics().density);
        assertTrue("the action is shorter than a 48 dp touch target", action.getHeight() >= touchTarget);
        assertEquals("the status name was squeezed onto extra lines", 1, title.getLineCount());
        NativeDialogCapture.assertReadable(title);
        NativeDialogCapture.assertReadable(summary);
        NativeDialogCapture.assertReadable(action);
        assertTrue("the action's label exceeds its available width", action.getLayout().getWidth()
                <= action.getWidth() - action.getCompoundPaddingLeft() - action.getCompoundPaddingRight());
    }

    /** A Resume the store can't keep leaves the pause on screen, in storage and in the switch, and says so. */
    @Test public void aResumeTheStoreCanNotKeepLeavesThePauseAndSaysSo() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        recreate();
        layout(dialog.getView());
        assertEquals("Resume", statusAction().getText().toString());
        try (FailingStore ignored = FailingStore.install(FailingStore.Fault.COMMIT_THROWS, FailingStore.Fault.COMMIT_THROWS)) {
            statusAction().performClick();
            ShadowLooper.idleMainLooper();
        }
        layout(dialog.getView());
        assertEquals("Couldn't turn HushPinterest back on. Try again.", ShadowToast.getTextOfLatestToast());
        assertTrue(BaseSettings.PAUSED.savedValue());
        assertTrue(Setting.preferences.preferences.getBoolean(BaseSettings.PAUSED.key, false));
        assertTrue(((SwitchPreference) page.findPreference(BaseSettings.PAUSED.key)).isChecked());
        assertEquals("Resume", statusAction().getText().toString());

        // The same tap with the store working turns it back on from the next start.
        statusAction().performClick();
        ShadowLooper.idleMainLooper();
        layout(dialog.getView());
        assertFalse(BaseSettings.PAUSED.savedValue());
        assertFalse(((SwitchPreference) page.findPreference(BaseSettings.PAUSED.key)).isChecked());
        assertEquals("Undo", statusAction().getText().toString());
    }

    /** A marker Resume can't remove keeps the Pause switch on, and the card says both steps. */
    @Test public void aMarkerResumeCanNotRemoveKeepsTheSwitchAndSaysWhatToDo() throws Exception {
        File marker = HushPinterestPause.markerFile(controller.get());
        File held = new File(marker, "held");
        // A folder with something in it can't be deleted.
        assertTrue(marker.mkdirs() && held.createNewFile());
        try {
            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
            recreate();
            layout(dialog.getView());
            statusAction().performClick();
            ShadowLooper.idleMainLooper();
            layout(dialog.getView());
            assertTrue(BaseSettings.PAUSED.savedValue());
            assertEquals("Resume", statusAction().getText().toString());
            String line = String.valueOf(((Preference) list().getItemAtPosition(0)).getSummary());
            assertTrue(line, line.startsWith("Delete ") && line.endsWith("then tap Resume again."));
        } finally {
            held.delete();
            marker.delete();
        }
    }

    private static final String PAUSED_LINE = "Until you resume, every switch but Debug logging acts as if it "
            + "were off. Changes made when you patched stay in.";

    /**
     * Paused, a category page opens with a short line that says so, and the saved switches keep
     * showing what was chosen. Resume and Undo act from that line, the page stays where it was, and
     * Back returns to the overview where it was.
     */
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h600dp-night-xhdpi")
    public void aPausedCategoryPageSaysSoAndKeepsItsPlaceThroughResumeAndUndo() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        recreate();
        layout(dialog.getView(), 1200);
        list().scrollListBy(120);
        int homePosition = list().getFirstVisiblePosition();
        int homeOffset = list().getChildAt(0).getTop();

        page.navigation.navigate("Feed");
        layout(dialog.getView(), 1200);
        Preference line = (Preference) list().getItemAtPosition(0);
        assertEquals("HushPinterest is paused", String.valueOf(line.getTitle()));
        assertEquals(PAUSED_LINE, String.valueOf(line.getSummary()));
        assertFalse("the line reads as a button of its own", list().getAdapter().isEnabled(0));
        assertTrue("a saved choice was changed to look paused",
                ((SwitchPreference) page.findPreference(Settings.HIDE_ADS.key)).isChecked());
        assertTrue(Settings.HIDE_ADS.savedValue());

        list().scrollListBy(40);
        layout(dialog.getView(), 1200);
        int position = list().getFirstVisiblePosition();
        int offset = list().getChildAt(0).getTop();
        android.widget.Button action = pageAction();
        assertEquals("Resume", action.getText().toString());
        assertTrue(action.getMinimumHeight() >= Math.round(48 * action.getResources().getDisplayMetrics().density));
        action.performClick();
        ShadowLooper.idleMainLooper();
        layout(dialog.getView(), 1200);
        assertFalse(BaseSettings.PAUSED.savedValue());
        assertEquals("HushPinterest turns back on when Pinterest restarts.",
                String.valueOf(((Preference) list().getItemAtPosition(0)).getSummary()));
        assertTrue("Resume left the page", contains(Settings.HIDE_ADS.key));
        assertEquals(position, list().getFirstVisiblePosition());
        assertEquals(offset, list().getChildAt(0).getTop());

        action = pageAction();
        assertEquals("Undo", action.getText().toString());
        action.performClick();
        ShadowLooper.idleMainLooper();
        layout(dialog.getView(), 1200);
        assertTrue(BaseSettings.PAUSED.savedValue());
        assertEquals(PAUSED_LINE, String.valueOf(((Preference) list().getItemAtPosition(0)).getSummary()));
        assertEquals("Resume", pageAction().getText().toString());
        assertEquals(position, list().getFirstVisiblePosition());
        assertEquals(offset, list().getChildAt(0).getTop());

        assertTrue(page.navigation.back());
        layout(dialog.getView(), 1200);
        assertEquals(homePosition, list().getFirstVisiblePosition());
        assertEquals(homeOffset, list().getChildAt(0).getTop());
    }

    /** Cancelling a pending Pause must not clamp a page after its status line disappears. */
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h600dp-night-xhdpi")
    public void undoPendingPauseKeepsTheCategoryOffset() throws Exception {
        pendingPauseUndoKeepsOffset("Pause, backup and diagnostics", null, false);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h600dp-night-xhdpi")
    public void undoPendingPauseKeepsTheSearchOffset() throws Exception {
        pendingPauseUndoKeepsOffset(null, "backup", false);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h600dp-night-xhdpi")
    public void undoPendingPauseKeepsTheOffsetWithAnExistingRestartNotice() throws Exception {
        pendingPauseUndoKeepsOffset("Pause, backup and diagnostics", null, true);
    }

    private void pendingPauseUndoKeepsOffset(String route, String search, boolean restart) throws Exception {
        int normalBottomPadding = list().getPaddingBottom();
        if (restart) app.hushpinterest.extension.shared.settings.preference.AbstractPreferenceFragment.restartPending.add(
                BaseSettings.DEBUG.key);
        BaseSettings.PAUSED.save(true);
        if (search == null) page.navigation.navigate(route);
        else findSearch(dialog.getView()).setText(search);
        layout(dialog.getView(), 1200);
        list().scrollListBy(40);
        layout(dialog.getView(), 1200);
        int position = list().getFirstVisiblePosition();
        int offset = list().getChildAt(0).getTop();
        assertTrue("regression needs a genuinely scrolled page", offset < 0);
        assertEquals("Undo", pageAction().getText().toString());
        pageAction().performClick();
        ShadowLooper.idleMainLooper();
        layout(dialog.getView(), 1200);
        assertFalse(BaseSettings.PAUSED.savedValue());
        assertFalse(HushPinterestPause.isPaused());
        assertEquals(position, list().getFirstVisiblePosition());
        assertEquals(offset, list().getChildAt(0).getTop());
        if (restart) assertEquals("Restart Pinterest to apply this change",
                String.valueOf(((Preference) list().getItemAtPosition(0)).getTitle()));
        else assertFalse(titles().contains("HushPinterest is on"));
        capture("undo-pending-" + (restart ? "restart" : search == null ? "category" : "search"), 1200);

        page.navigation.navigate("About");
        layout(dialog.getView(), 1200);
        assertEquals(normalBottomPadding, list().getPaddingBottom());
        assertEquals(0, list().getFirstVisiblePosition());
        findSearch(dialog.getView()).setText("Hide");
        layout(dialog.getView(), 1200);
        assertEquals(normalBottomPadding, list().getPaddingBottom());
        assertFalse(titles().contains("HushPinterest is on"));
    }

    /** Search results say it too, and a page with nothing Pause turns off, like About, doesn't. */
    @Test public void pausedSearchSaysSoAndPagesPauseDoesNotReachStayAsTheyAre() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.CRASH_LOOP);
        recreate();
        findSearch(dialog.getView()).setText("Hide ads");
        ShadowLooper.idleMainLooper();
        assertEquals(PAUSED_LINE, String.valueOf(((Preference) list().getItemAtPosition(0)).getSummary()));
        assertTrue(contains(Settings.HIDE_ADS.key));
        page.navigation.back();
        page.navigation.navigate("Links");
        // The optional Visit browser switch follows Pause, alongside Android's link settings.
        assertEquals("Links has a Pause-controlled browser switch", categoryCount("Links") + 1, list().getCount());
        assertTrue(contains(Settings.EXTERNAL_BROWSER.key));
        assertEquals(PAUSED_LINE, String.valueOf(((Preference) list().getItemAtPosition(0)).getSummary()));
        assertFalse(contains(Settings.HIDE_ADS.key));
        while (page.navigation.back()) { }
        page.navigation.navigate("About");
        assertEquals("About", categoryCount("About"), list().getCount());
        while (page.navigation.back()) { }
        // The Feed page holds switches Pause turns off, so it starts with the line.
        page.navigation.navigate("Feed");
        assertEquals("Feed has Pause-controlled switches", categoryCount("Feed") + 1, list().getCount());
        assertEquals("HushPinterest is paused", String.valueOf(((Preference) list().getItemAtPosition(0)).getTitle()));
        assertEquals(PAUSED_LINE, String.valueOf(((Preference) list().getItemAtPosition(0)).getSummary()));
        assertTrue(contains(Settings.HIDE_ADS.key));
        assertTrue(contains(Settings.HIDE_AI_PINS.key));
    }

    /** The Feed row names only what its page holds in this build, down to the hooks inserted. */
    @Test public void homeLinesNameOnlyWhatThisBuildPutOnTheirPages() {
        assertEquals("Promoted and AI-labeled pins", homeLine("Feed"));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        recreate();
        assertEquals("Promoted pins", homeLine("Feed"));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_AI_PINS);
        recreate();
        assertEquals("AI-labeled pins", homeLine("Feed"));
        // Without either family there is no Feed page at all.
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        recreate();
        assertFalse(hasHomeRow("Feed"));
        // Status, Quick setups, Browse settings, More settings, Report a problem and Support.
        assertEquals(6, list().getCount());
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        PatchFamily.capabilitiesForTests = EnumSet.of(PatchFamily.Capability.FEED_ADS, PatchFamily.Capability.AD_VIEWS);
        recreate();
        assertEquals("Promoted pins", homeLine("Feed"));
        // Ad-only views are not a list: a build with only them names nothing on the home row.
        PatchFamily.capabilitiesForTests = EnumSet.of(PatchFamily.Capability.AD_VIEWS);
        recreate();
        assertNull(homeLine("Feed"));
        PatchFamily.capabilitiesForTests = EnumSet.of(PatchFamily.Capability.FEED_AI_PINS);
        recreate();
        assertEquals("AI-labeled pins", homeLine("Feed"));
        PatchFamily.capabilitiesForTests = EnumSet.of(PatchFamily.Capability.FEED_ADS, PatchFamily.Capability.FEED_AI_PINS);
        recreate();
        assertEquals("Promoted and AI-labeled pins", homeLine("Feed"));
        PatchFamily.capabilitiesForTests = EnumSet.noneOf(PatchFamily.Capability.class);
        recreate();
        assertNull(homeLine("Feed"));
    }

    /**
     * Pause switched on from its own page is owed a restart: the page says so, the restart row
     * sits under that line, and Undo takes both back. A change owed a restart brings the row up
     * on every page, first on a page without a pause line and under the home page's status card.
     */
    @Test public void aPauseOrAChangeWaitingOnARestartBringsUpTheRestartRow() {
        page.navigation.navigate("Pause, backup and diagnostics");
        tap(BaseSettings.PAUSED.key);
        layout(dialog.getView());
        Preference line = (Preference) list().getItemAtPosition(0);
        assertEquals("HushPinterest is on", String.valueOf(line.getTitle()));
        assertEquals("HushPinterest pauses when Pinterest restarts.", String.valueOf(line.getSummary()));
        Preference restart = (Preference) list().getItemAtPosition(1);
        assertEquals(SettingsNavigation.RESTART, restart.getKey());
        assertEquals("Restart Pinterest to apply this change", String.valueOf(restart.getTitle()));
        assertEquals("Pinterest closes and reopens on this page.", String.valueOf(restart.getSummary()));
        assertTrue("the restart row can't be tapped", list().getAdapter().isEnabled(1));
        assertNull("a restart saved message went to a toast instead of the row", ShadowToast.getLatestToast());
        pageAction().performClick();
        ShadowLooper.idleMainLooper();
        layout(dialog.getView());
        assertFalse(BaseSettings.PAUSED.savedValue());
        assertEquals(BaseSettings.PAUSED.key, ((Preference) list().getItemAtPosition(0)).getKey());
        assertFalse(contains(SettingsNavigation.RESTART));

        page.navigation.back();
        app.hushpinterest.extension.shared.settings.preference.AbstractPreferenceFragment.restartPending.add(
                Settings.HIDE_ADS.key);
        page.navigation.navigate("Feed");
        layout(dialog.getView());
        assertEquals(SettingsNavigation.RESTART, ((Preference) list().getItemAtPosition(0)).getKey());
        assertEquals(categoryCount("Feed") + 1, list().getCount());
        page.navigation.back();
        page.navigation.navigate("Links");
        assertEquals(SettingsNavigation.RESTART, ((Preference) list().getItemAtPosition(0)).getKey());
        assertEquals(categoryCount("Links") + 1, list().getCount());
        while (page.navigation.back()) { }
        assertEquals(SettingsNavigation.RESTART, ((Preference) list().getItemAtPosition(1)).getKey());
        assertEquals(11, list().getCount());

        // Two changes are counted, and the row goes once neither is owed.
        app.hushpinterest.extension.shared.settings.preference.AbstractPreferenceFragment.restartPending.add(
                Settings.FIX_PUSH_NOTIFICATIONS.key);
        page.navigation.restartChanged();
        assertEquals("Restart Pinterest to apply 2 changes",
                String.valueOf(((Preference) list().getItemAtPosition(1)).getTitle()));
        app.hushpinterest.extension.shared.settings.preference.AbstractPreferenceFragment.restartPending.clear();
        page.navigation.restartChanged();
        assertFalse(contains(SettingsNavigation.RESTART));
        assertEquals(10, list().getCount());
    }

    /** A switch that needs a restart brings the row up the moment it's flipped, and flipping it back takes it away. */
    @Test public void flippingARestartGatedSwitchShowsTheRowAtOnce() {
        page.navigation.navigate("Interface");
        assertFalse(contains(SettingsNavigation.RESTART));
        tap(Settings.HIDE_SCREENSHOT_SHARE.key);
        assertTrue(Settings.HIDE_SCREENSHOT_SHARE.savedValue());
        assertEquals(SettingsNavigation.RESTART, ((Preference) list().getItemAtPosition(0)).getKey());
        assertNull("the restart was said in a toast with nothing to tap", ShadowToast.getLatestToast());
        tap(Settings.HIDE_SCREENSHOT_SHARE.key);
        assertFalse(Settings.HIDE_SCREENSHOT_SHARE.savedValue());
        assertFalse(contains(SettingsNavigation.RESTART));
        Settings.HIDE_SCREENSHOT_SHARE.resetToDefault();
    }

    /** The row restarts Pinterest and leaves a marker that opens the screen again on this page. */
    @Test public void theRestartRowRestartsIntoThePageItWasTappedOn() {
        List<android.content.Context> restarted = new ArrayList<>();
        app.hushpinterest.extension.shared.settings.preference.ReopenSettingsAfterRestart.setRestarterForTests(restarted::add);
        app.hushpinterest.extension.shared.settings.preference.AbstractPreferenceFragment.restartPending.add(
                Settings.FIX_PUSH_NOTIFICATIONS.key);
        page.navigation.navigate("Notifications");
        Map<String, Object> before = savedValues();
        tap(SettingsNavigation.RESTART);
        assertEquals(1, restarted.size());
        assertEquals(before, savedValues());
        assertEquals("Notifications", app.hushpinterest.extension.shared.settings.preference.ReopenSettingsAfterRestart
                .takeFresh(Setting.preferences.preferences, android.os.SystemClock.elapsedRealtime()));
    }

    /** Asked to open on a page, as after that restart, the screen starts there with its title and no search. */
    @Test public void aScreenAskedToStartOnAPageOpensThere() {
        dialog.dismissAllowingStateLoss();
        ShadowLooper.idleMainLooper();
        dialog = SettingsDialog.onPage("Links");
        dialog.show(controller.get().getFragmentManager(), "hushpinterest_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        page = page(dialog);
        assertTrue(contains(Settings.EXTERNAL_BROWSER.key));
        assertEquals(categoryCount("Links"), list().getCount());
        assertEquals(View.GONE, ((View) findSearch(dialog.getView()).getParent()).getVisibility());
        assertTrue(page.navigation.back());
        assertTrue("Back from a More settings page goes to More settings", contains("section_Links"));

        // A page this build doesn't have opens the first page.
        dialog.dismissAllowingStateLoss();
        ShadowLooper.idleMainLooper();
        dialog = SettingsDialog.onPage("No such page");
        dialog.show(controller.get().getFragmentManager(), "hushpinterest_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        page = page(dialog);
        assertEquals(10, list().getCount());
    }

    /** Each Quick setups choice changes its switches through their rows and says how many changed. */
    @Test public void quickSetupsChangeTheirSwitchesAndSayHowMany() {
        Preference row = (Preference) list().getItemAtPosition(1);
        assertEquals(SettingsNavigation.QUICK_SETUPS, row.getKey());
        assertEquals("Quick setups", String.valueOf(row.getTitle()));
        tap(SettingsNavigation.QUICK_SETUPS);
        AlertDialog choices = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull("Quick setups opened no dialog", choices);
        android.widget.ListAdapter cards = choices.getListView().getAdapter();
        assertEquals(3, cards.getCount());
        assertTrue(String.valueOf(cards.getItem(0)).startsWith("Quiet\n"));
        assertTrue(String.valueOf(cards.getItem(1)).startsWith("Privacy\n"));
        assertTrue(String.valueOf(cards.getItem(2)).startsWith("Back to defaults\n"));
        try {
            // Hide ads starts on, so Quiet turns on the other six.
            choices.getListView().performItemClick(null, 0, 0);
            ShadowLooper.idleMainLooper();
            for (app.hushpinterest.extension.shared.settings.BooleanSetting setting : QuickSetupKeys.QUIET) {
                assertTrue(setting.key, setting.savedValue());
            }
            assertEquals("Changed 6 settings", ShadowToast.getTextOfLatestToast());
            // No screenshot share menu waits for a restart, so the row is up under the status card.
            assertEquals(SettingsNavigation.RESTART, ((Preference) list().getItemAtPosition(1)).getKey());

            assertEquals(0, page.applyQuickSetup(QuickSetupKeys.quiet()));
            assertEquals("Changed 0 settings", ShadowToast.getTextOfLatestToast());
            assertEquals(6, page.applyQuickSetup(QuickSetupKeys.defaults()));
            for (app.hushpinterest.extension.shared.settings.BooleanSetting setting : QuickSetupKeys.DEFAULTS) {
                assertEquals(setting.key, setting.defaultValue, setting.savedValue());
            }
            assertFalse("putting the switch back left the restart owed", contains(SettingsNavigation.RESTART));
        } finally {
            for (app.hushpinterest.extension.shared.settings.BooleanSetting setting : QuickSetupKeys.DEFAULTS) {
                setting.resetToDefault();
            }
        }
    }

    /** Report a problem opens GitHub's bug form in a browser with the versions and switches filled in. */
    @Test public void reportAProblemOpensAFilledInGitHubForm() throws Exception {
        Map<String, Object> before = savedValues();
        tap(SettingsNavigation.REPORT);
        Intent started = org.robolectric.Shadows.shadowOf(controller.get()).getNextStartedActivity();
        assertNotNull("nothing opened", started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        String url = started.getDataString();
        assertTrue(url, url.startsWith("https://github.com/SysAdminDoc/HushPinterest/issues/new?template=bug_report.yml&"));
        assertTrue(url, url.length() <= app.hushpinterest.extension.shared.settings.preference.ReportProblem.MAX_LENGTH);
        String decoded = java.net.URLDecoder.decode(url, "UTF-8");
        assertTrue(decoded, decoded.contains("&versions=HushPinterest "));
        assertTrue(decoded, decoded.contains(" on Pinterest "));
        assertTrue(decoded, decoded.contains("&device="));
        assertTrue(decoded, decoded.contains("What happened:"));
        assertTrue(decoded, decoded.contains("What I expected:"));
        // Hide ads starts on, Hide AI-labeled pins starts off.
        assertTrue(decoded, decoded.contains("Switches on: Hide ads"));
        assertFalse(decoded, decoded.contains("Hide AI-labeled pins"));
        assertEquals(before, savedValues());

        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).checkActivities(true);
        tap(SettingsNavigation.REPORT);
        assertEquals("No app can open " + L10n.isolate("github.com/SysAdminDoc/HushPinterest") + ".",
                ShadowToast.getTextOfLatestToast());
        assertFalse(controller.get().isFinishing());
    }

    /** The paused line on real pages, dark and light, for a look before the phone does. */
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderPausedPages() throws Exception {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        recreate();
        page.navigation.navigate("Feed");
        capture("paused-feed");
        assertUncutText(dialog.getView());
        pageAction().performClick();
        ShadowLooper.idleMainLooper();
        capture("paused-feed-resume-pending");
        page.navigation.back();
        findSearch(dialog.getView()).setText("links");
        capture("paused-search");
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = {29, 30, 33, 36}, qualifiers = "ar-rXB-ldrtl-w390dp-h844dp-night-xhdpi")
    public void aPausedPageKeepsItsLineWholeAtLargeRightToLeftText() throws Exception {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        org.robolectric.RuntimeEnvironment.setFontScale(2f);
        try {
            recreate();
            page.navigation.navigate("Feed");
            layout(dialog.getView());
            assertUncutText(dialog.getView());
            View row = list().getChildAt(0);
            TextView summary = row.findViewById(android.R.id.summary);
            android.widget.Button action = pageAction();
            assertEquals(summary.getParent(), action.getParent());
            assertTrue("the large-text page action isn't under its summary", action.getTop() >= summary.getBottom());
            NativeDialogCapture.assertReadable(action);
            capture("large-rtl-paused-feed");
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f);
        }
    }

    private android.widget.Button pageAction() {
        android.widget.Button action = firstButton(list().getChildAt(0));
        assertNotNull("no action in the page's status row", action);
        return action;
    }

    private int categoryCount(String title) {
        for (Preference section : page.sections()) {
            if (title.contentEquals(section.getTitle())) return ((PreferenceCategory) section).getPreferenceCount();
        }
        throw new AssertionError("No section " + title);
    }

    /** Paused, the overview says what to do in order. It used to read as if a restart came before Resume. */
    @Test public void aPausedOverviewSaysToTapResumeThenRestart() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        recreate();
        layout(dialog.getView());
        TextView summary = list().getChildAt(0).findViewById(android.R.id.summary);
        assertEquals("Your choices are saved. Tap Resume, then restart Pinterest.", String.valueOf(summary.getText()));
    }

    /**
     * The overview keeps its separate action line at twice the text size, so the status name
     * doesn't break inside a word and the explanation still has the whole column to wrap in.
     */
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atLargeTextTheStatusActionSitsUnderItsText() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f);
        try {
            recreate();
            layout(dialog.getView());
            View row = list().getChildAt(0);
            TextView title = row.findViewById(android.R.id.title);
            TextView summary = row.findViewById(android.R.id.summary);
            android.text.Layout lines = title.getLayout();
            for (int line = 0; line + 1 < lines.getLineCount(); line++) {
                char last = title.getText().charAt(lines.getLineEnd(line) - 1);
                assertTrue("\"" + title.getText() + "\" breaks inside a word after line " + line, Character.isWhitespace(last));
            }
            assertEquals(View.GONE, row.findViewById(android.R.id.widget_frame).getVisibility());
            android.widget.Button action = firstButton(row);
            assertNotNull("no Pause button in the status row", action);
            assertEquals(summary.getParent(), action.getParent());
            assertTrue("the button isn't under the text", action.getTop() >= summary.getBottom());
            assertEquals("Pause", action.getText().toString());
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f);
        }
    }

    private static android.widget.Button firstButton(View view) {
        if (view instanceof android.widget.Button) return (android.widget.Button) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.Button found = firstButton(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private android.widget.Button statusAction() {
        android.widget.Button action = firstButton(list().getChildAt(0));
        assertNotNull("no action in the overview's status row", action);
        return action;
    }

    @Test public void searchUsesRealControlsAndClearReturnsHome() {
        EditText search = findSearch(dialog.getView());
        assertNotNull(search);
        search.setText("ai-labeled");
        ShadowLooper.idleMainLooper();
        assertTrue(contains(Settings.HIDE_AI_PINS.key));
        tap(Settings.HIDE_AI_PINS.key);
        assertTrue(Settings.HIDE_AI_PINS.savedValue());
        search.setText("noSuchSetting987654");
        assertEquals(1, list().getCount());
        assertFalse(list().getAdapter().isEnabled(0));
        assertEquals("No matching settings", ((Preference) list().getItemAtPosition(0)).getTitle());
        assertTrue(page.navigation.back());
        assertEquals("", search.getText().toString());
        assertEquals(10, list().getCount());
    }

    /** A row with no summary used to carry the word "null" into the search text and come up for it. */
    @Test public void aRowWithoutASummaryDoesNotMatchTheWordNull() {
        PreferenceCategory category = null;
        for (int i = 0; i < page.getPreferenceScreen().getPreferenceCount() && category == null; i++) {
            Preference entry = page.getPreferenceScreen().getPreference(i);
            if (entry instanceof PreferenceCategory) category = (PreferenceCategory) entry;
        }
        assertNotNull("a category to put a bare row in", category);
        Preference bare = new Preference(page.getContext());
        bare.setKey("bareRow");
        bare.setTitle("Bare row");
        category.addPreference(bare);
        EditText search = findSearch(dialog.getView());
        search.setText("null");
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(contains("bareRow"));
        assertEquals("No matching settings", ((Preference) list().getItemAtPosition(0)).getTitle());
        search.setText("bare row");
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(contains("bareRow"));
    }

    /**
     * The hint labels the field; a content description on top of it would replace the typed text
     * in a screen reader's reading. Clear hides itself once the field is empty, so it hands focus
     * back to the field instead of dropping it.
     */
    @Test public void theSearchFieldIsLabeledByItsHintAndClearHandsFocusBack() {
        EditText search = findSearch(dialog.getView());
        assertNull(search.getContentDescription());
        assertEquals("Search settings", search.getHint().toString());
        View clear = ((android.view.ViewGroup) search.getParent()).getChildAt(2);
        assertEquals("Clear search", String.valueOf(clear.getContentDescription()));
        assertEquals(View.INVISIBLE, clear.getVisibility());
        search.setText("ads");
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(View.VISIBLE, clear.getVisibility());
        assertTrue(clear.performClick());
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("", search.getText().toString());
        assertEquals(View.INVISIBLE, clear.getVisibility());
        assertTrue("the field has focus after Clear", search.isFocused());
        assertEquals(10, list().getCount());
    }

    /**
     * A tapped Check now changes its row's text when the check ends, and a screen reader hears that
     * text too. The daily check settings starts on its own ends through the same listener and stays
     * quiet, since nobody is waiting on it.
     */
    @Test public void aTappedCheckNowIsAnnouncedAndTheAutomaticCheckIsNot() throws Exception {
        android.view.accessibility.AccessibilityManager a11y = controller.get().getSystemService(android.view.accessibility.AccessibilityManager.class);
        org.robolectric.Shadows.shadowOf(a11y).setEnabled(true);
        java.util.List<CharSequence> announced = new java.util.ArrayList<>();
        android.view.ViewGroup container = (android.view.ViewGroup) page.getView().getParent();
        container.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public boolean onRequestSendAccessibilityEvent(android.view.ViewGroup host, View child,
                    android.view.accessibility.AccessibilityEvent event) {
                if (event.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT) {
                    announced.addAll(event.getText());
                }
                return super.onRequestSendAccessibilityEvent(host, child, event);
            }
        });
        Preference checkNow = page.findPreference(HushPinterestPreferenceFragment.CHECK_NOW);

        // The automatic check ending: the row changes, nothing is spoken.
        page.releaseCheckFinished();
        String idle = ReleaseCheck.checkNowSummary();
        assertNotNull("Check now always has something to say", idle);
        assertEquals(idle, String.valueOf(checkNow.getSummary()));
        assertEquals("a check nobody tapped is not spoken", java.util.Collections.emptyList(), announced);

        // A tap: the check runs against a GitHub that answers with a newer release.
        ReleaseCheck.Transport original = ReleaseCheck.transport;
        ReleaseCheck.transport = new FakeGitHub().then(FakeGitHub.Reply.release("v0.2.0", null));
        String result;
        try {
            assertTrue(checkNow.getOnPreferenceClickListener().onPreferenceClick(checkNow));
            ReleaseCheckForTests.settle();
            result = ReleaseCheck.checkNowSummary();
            assertEquals(java.util.Collections.singletonList(result), announced);
            assertEquals(result, String.valueOf(checkNow.getSummary()));
            // The next automatic ending is quiet again.
            page.releaseCheckFinished();
            assertEquals(1, announced.size());
        } finally {
            ReleaseCheck.transport = original;
            ReleaseCheckForTests.forget();
        }
    }

    @Test public void interfaceRowsNameTheirOwnSurfaceAndRefreshBoundary() {
        summaryContains(Settings.HIDE_SCREENSHOT_SHARE.key, "share a screenshot", "Restart Pinterest", "Screenshots still work");
        summaryContains(Settings.HIDE_SEARCH_HISTORY.key, "recent searches", "next time the screen updates", "doesn't delete your account's search history");
        summaryContains(Settings.HIDE_NAV_CREATE.key, "Create in the bottom bar", "next time the bar updates");
        summaryContains(Settings.HIDE_NAV_NOTIFICATIONS.key, "Notifications in the bottom bar", "next time the bar updates");
        summaryContains(Settings.HIDE_NAV_SEARCH.key, "Search in the bottom bar", "next time the bar updates");
        summaryContains(Settings.HIDE_HEADER_BUTTONS.key, "icon buttons at the end of the top bar", "next time the bar updates", "account controls stay");
        summaryContains(Settings.BLACK_BOTTOM_BAR.key, "In dark mode", "bottom bar pure black", "Light mode stays", "next time the bar redraws");
        summaryContains(Settings.HIDE_CLOSEUP_BACK.key, "back arrow that floats over a pin", "back gesture or button", "next pin you open");
        summaryContains(Settings.HIDE_CLOSEUP_LENS.key, "visual search button", "Search image in the pin menu still works", "next pin you open");
        summaryContains(Settings.HIDE_PIN_MENU_COLLAGE.key, "Add to collage and Remix collage", "pin menus you open from now on", "already open won't change");
        summaryContains(Settings.HIDE_PIN_MENU_VISUAL_SEARCH.key, "Search image", "pin menus you open from now on", "already open won't change");
        summaryContains(Settings.HIDE_PIN_MENU_PIN_BOOST.key, "Promote pin", "pin menus you open from now on", "already open won't change");
        summaryContains(Settings.HIDE_COMMENTS.key, "comments and comment previews under pins", "next time the screen updates", "doesn't change who can comment");
        summaryContains(Settings.HIDE_TOPIC_SUGGESTIONS.key, "\"Ideas you might love\" row of topic bubbles under pins", "next time Pinterest shows it", "Comments and related pins stay");
        summaryContains(Settings.QUIET_EMAIL_REMINDER.key, "optional reminder to confirm your email", "already open stays", "sign-in still work as usual");
        summaryContains(Settings.HIDE_SURVEY_PROMPTS.key, "\"Got a minute?\" survey invite", "the way Maybe later does",
                "sponsored polls don't pop up", "already open stays");
    }

    @Test public void aliasesKeepAccentMatchingGroupingAndTheSpokenResultCount() {
        Map<String, Object> before = savedValues();
        EditText search = findSearch(dialog.getView());
        search.setText("  ÍCONS  toolbar   interface  ");
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(2, list().getCount());
        assertTrue(list().getItemAtPosition(0) instanceof PreferenceCategory);
        assertEquals("Interface", ((Preference) list().getItemAtPosition(0)).getTitle());
        assertSame(page.findPreference(Settings.HIDE_HEADER_BUTTONS.key), list().getItemAtPosition(1));
        assertEquals("1 setting found", resultCount(dialog.getView()).getText().toString());
        assertEquals(before, savedValues());
        search.setText("toolbar icons noSuchTerm987654");
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("0 settings found", resultCount(dialog.getView()).getText().toString());
        assertFalse(contains(Settings.HIDE_HEADER_BUTTONS.key));
    }

    @Test public void aliasesCannotAddRowsForPatchesThisBuildDoesNotHave() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        recreate();
        for (Map.Entry<String, String> alias : SettingsNavigation.localizedSearchAliases().entrySet()) {
            assertNull(page.findPreference(alias.getKey()));
            findSearch(dialog.getView()).setText(alias.getValue());
            ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(alias.getKey(), contains(alias.getKey()));
            assertEquals("0 settings found", resultCount(dialog.getView()).getText().toString());
            assertEquals(1, list().getCount());
            assertEquals("No matching settings", ((Preference) list().getItemAtPosition(0)).getTitle());
        }
    }

    @Test public void alternateSearchWordsFindTheActualRowsInEveryShippedLanguage() {
        String[][] languages = {{"en", null}, {"de", "de"}, {"es", "es"}, {"in-rID", "in"}, {"pt-rBR", "pt-rbr"}, {"tr", "tr"}};
        Map<String, String> english = SettingsNavigation.localizedSearchAliases();
        for (String[] language : languages) {
            controller.close();
            org.robolectric.RuntimeEnvironment.setQualifiers("+" + language[0]);
            controller = Robolectric.buildActivity(Activity.class).setup().visible();
            dialog = SettingsL10nTest.show(controller.get());
            page = page(dialog);
            Map<String, String> table = language[1] == null ? new TreeMap<>() : SettingsL10nTest.TranslationsForTests.of(language[1]);
            for (Map.Entry<String, String> alias : english.entrySet()) {
                String words = table.getOrDefault(alias.getValue(), alias.getValue());
                // All words must match together, whatever order they were typed in.
                List<String> terms = Arrays.asList(words.split("\\s+"));
                java.util.Collections.reverse(terms);
                findSearch(dialog.getView()).setText(String.join("  ", terms));
                ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(language[0] + ": " + words + " missed " + alias.getKey(), contains(alias.getKey()));
                assertSame(page.findPreference(alias.getKey()), list().getItemAtPosition(position(alias.getKey())));
            }
        }
    }

    private void summaryContains(String key, String... details) {
        Preference row = page.findPreference(key);
        assertNotNull(key, row);
        String summary = String.valueOf(row.getSummary());
        for (String detail : details) assertTrue(key + ": " + summary, summary.contains(detail));
    }

    /**
     * On the S22 with TalkBack, typing a search said only "Edit box": the list changed without a
     * word. The count now sits in a polite live region, written once the typing settles.
     */
    @Test public void searchSpeaksItsResultCountOnceTheTypingSettles() {
        EditText search = findSearch(dialog.getView());
        TextView before = resultCount(dialog.getView());
        assertTrue(before == null || before.getVisibility() == View.GONE);
        java.util.List<String> spoken = new java.util.ArrayList<>();
        search.setText("h");
        TextView count = resultCount(dialog.getView());
        assertNotNull("a live region for the result count", count);
        count.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int before, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int after) { spoken.add(s.toString()); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        search.setText("hi");
        search.setText("hide");
        search.setText("hide ads");
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        int rows = 0;
        for (int i = 0; i < list().getCount(); i++) if (!(list().getItemAtPosition(i) instanceof PreferenceCategory)) rows++;
        assertTrue(rows > 0);
        assertEquals(java.util.Collections.singletonList(rows + (rows == 1 ? " setting found" : " settings found")), spoken);
        assertEquals(View.VISIBLE, count.getVisibility());
        search.setText("noSuchSetting987654");
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("0 settings found", count.getText().toString());
        search.setText("");
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(View.GONE, count.getVisibility());
    }

    /**
     * On the S22, Back left TalkBack's focus on the whole screen, so a screen reader user lost
     * their place in the list. It goes back to the row the page was opened from. Robolectric's
     * window has no surface and drops accessibility focus on its next traversal, so the test
     * reads the focus event the row sent rather than the row's state afterwards.
     */
    @Test public void backPutsScreenReaderFocusOnTheRowThePageCameFrom() {
        android.view.accessibility.AccessibilityManager a11y = controller.get().getSystemService(android.view.accessibility.AccessibilityManager.class);
        org.robolectric.Shadows.shadowOf(a11y).setEnabled(true);
        org.robolectric.Shadows.shadowOf(a11y).setTouchExplorationEnabled(true);
        layout(dialog.getView());
        page.navigation.navigate("Feed");
        layout(dialog.getView());
        page.navigation.back();
        layout(dialog.getView());
        assertEquals("Feed", focusedTitle(a11y));
        page.navigation.navigate("more");
        layout(dialog.getView());
        page.navigation.navigate("About");
        layout(dialog.getView());
        page.navigation.back();
        layout(dialog.getView());
        assertEquals("About", focusedTitle(a11y));
        page.navigation.back();
        layout(dialog.getView());
        assertEquals("More settings", focusedTitle(a11y));
    }

    /** The first text of the last row that took accessibility focus. */
    private static String focusedTitle(android.view.accessibility.AccessibilityManager a11y) {
        String title = null;
        for (android.view.accessibility.AccessibilityEvent event : org.robolectric.Shadows.shadowOf(a11y).getSentAccessibilityEvents()) {
            if (event.getEventType() != android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) continue;
            title = event.getText().isEmpty() ? null : String.valueOf(event.getText().get(0));
        }
        return title;
    }

    private static TextView resultCount(View view) {
        if (view instanceof TextView && !(view instanceof EditText)
                && view.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE) return (TextView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = resultCount(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test @Config(sdk = {29, 30, 33, 36})
    public void recreationKeepsTheCategoryAndSearchQuery() {
        page.navigation.open(page.findPreference(Settings.CHECK_FOR_RELEASES.key));
        recreate();
        assertTrue(contains(Settings.CHECK_FOR_RELEASES.key));
        assertEquals(categoryCount("Updates"), list().getCount());
        assertTrue(contains(Settings.DISABLE_UPDATE_NAG.key));
        page.navigation.back();
        findSearch(dialog.getView()).setText("new releases");
        recreate();
        assertEquals("new releases", findSearch(dialog.getView()).getText().toString());
        assertTrue(contains(Settings.CHECK_FOR_RELEASES.key));
    }

    @Test public void bothBackPathsReturnToTheIndexBeforeClosingTheDialog() {
        page.navigation.open(page.findPreference(Settings.HIDE_ADS.key));
        SettingsL10nTest.backOf(dialog).performClick();
        assertTrue(dialog.getDialog().isShowing());
        assertEquals(10, list().getCount());
        page.navigation.navigate("About");
        dialog.getDialog().onBackPressed();
        // More settings: Links, Notifications, Pinterest's own settings, Updates, Pause, backup and diagnostics,
        // and About.
        assertEquals(6, list().getCount());
        dialog.getDialog().onBackPressed();
        assertEquals(10, list().getCount());
        dialog.getDialog().onBackPressed();
        ShadowLooper.idleMainLooper();
        assertFalse(controller.get().isFinishing());
    }

    // A window as short as the layouts below, 400 px, so the window's own layout leaves both the
    // overview and More settings taller than the list and neither snaps back to the top.
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h200dp-night-xhdpi")
    public void aNewPageStartsAtTheTopAfterThePreviousPageWasScrolled() {
        Object root = org.robolectric.util.ReflectionHelpers.callInstanceMethod(
                dialog.getDialog().getWindow().getDecorView(), "getViewRootImpl");
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(root, "ensureTouchMode",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, true));
        try {
            layout(dialog.getView(), 400);
            assertTrue(list().isInTouchMode());
            list().scrollListBy(120);
            int homePosition = list().getFirstVisiblePosition();
            int homeOffset = list().getChildAt(0).getTop();
            assertTrue(homePosition > 0 || homeOffset < 0);
            page.navigation.navigate("more");
            layout(dialog.getView(), 400);
            assertEquals(0, list().getFirstVisiblePosition());
            assertEquals(list().getPaddingTop(), list().getChildAt(0).getTop());
            list().scrollListBy(100);
            int morePosition = list().getFirstVisiblePosition();
            int moreOffset = list().getChildAt(0).getTop();
            assertTrue(morePosition > 0 || moreOffset < 0);
            page.navigation.navigate("Pause, backup and diagnostics");
            // Preference updates can arrive after navigation but before the next layout.
            ((android.widget.BaseAdapter) page.getPreferenceScreen().getRootAdapter()).notifyDataSetChanged();
            layout(dialog.getView(), 400);
            assertEquals(0, list().getFirstVisiblePosition());
            assertEquals(list().getPaddingTop(), list().getChildAt(0).getTop());
            assertTrue(page.navigation.back());
            layout(dialog.getView(), 400);
            assertEquals(morePosition, list().getFirstVisiblePosition());
            assertEquals(moreOffset, list().getChildAt(0).getTop());
            assertTrue(page.navigation.back());
            layout(dialog.getView(), 400);
            assertEquals(homePosition, list().getFirstVisiblePosition());
            assertEquals(homeOffset, list().getChildAt(0).getTop());
        } finally {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(root, "ensureTouchMode",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, false));
        }
    }

    @Test public void visibleNavigationAndSearchKeepTheirAccessibilityRoles() {
        layout(dialog.getView());
        View heading = list().getChildAt(2);
        assertTrue(heading.createAccessibilityNodeInfo().isHeading());
        View feed = list().getChildAt(3);
        assertEquals(android.widget.Button.class.getName(), feed.createAccessibilityNodeInfo().getClassName());
        assertTrue(feed.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals("Hide ads", ((Preference) list().getItemAtPosition(0)).getTitle());
        page.navigation.back();
        findSearch(dialog.getView()).setText("Hide ads");
        layout(dialog.getView());
        View toggle = list().getChildAt(position(Settings.HIDE_ADS.key) - list().getFirstVisiblePosition());
        assertEquals(android.widget.Switch.class.getName(), toggle.createAccessibilityNodeInfo().getClassName());
        assertTrue(toggle.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
        assertFalse(Settings.HIDE_ADS.savedValue());
    }

    /**
     * The switches live in one application-wide store, and Pinterest can hold several accounts. The
     * About row and both backup rows say so, and each shipped language's word for accounts finds
     * all three in search.
     */
    @Test public void theAccountScopeIsSearchableInEveryShippedLanguage() {
        String[][] languages = {{"en", null}, {"de", "de"}, {"es", "es"}, {"in-rID", "in"}, {"pt-rBR", "pt-rbr"}, {"tr", "tr"}};
        for (String[] language : languages) {
            controller.close();
            org.robolectric.RuntimeEnvironment.setQualifiers("+" + language[0]);
            controller = Robolectric.buildActivity(Activity.class).setup().visible();
            dialog = SettingsL10nTest.show(controller.get());
            page = page(dialog);
            Map<String, String> table = language[1] == null ? new TreeMap<>() : SettingsL10nTest.TranslationsForTests.of(language[1]);
            String word = table.getOrDefault("Accounts", "Accounts").toLowerCase(java.util.Locale.ROOT);
            findSearch(dialog.getView()).setText(word);
            ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
            List<String> found = new ArrayList<>();
            for (int i = 0; i < list().getCount(); i++) {
                Object item = list().getItemAtPosition(i);
                if (item instanceof Preference && !(item instanceof PreferenceCategory)) found.add(String.valueOf(((Preference) item).getTitle()));
            }
            for (String title : Arrays.asList("Accounts", "Export settings", "Import settings")) {
                assertTrue(language[0] + ": searching " + word + " misses " + title + " in " + found,
                        found.contains(table.getOrDefault(title, title)));
            }
        }
    }

    /** All pages are rendered with the same viewport as the design reference. */
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderEveryPageAndSearchOffscreen() throws Exception {
        capture("00-overview");
        for (Preference section : page.sections()) {
            page.navigation.open(section);
            capture(section.getTitle().toString().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]+", "-"));
        }
        page.navigation.navigate("more");
        capture("more-settings");
        page.navigation.back();
        findSearch(dialog.getView()).setText("links");
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        capture("search-results");
        findSearch(dialog.getView()).setText("noSuchSetting987654");
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        capture("search-empty");
        page.navigation.back();
        page.navigation.navigate("Pause, backup and diagnostics");
        Preference export = page.findPreference("action_export_diagnostic_report");
        export.getOnPreferenceClickListener().onPreferenceClick(export);
        AlertDialog report = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        captureDialog("dialog-report", report);
        report.dismiss();
        page.pendingImport = SettingsBackup.parse("{\"format\":\"hushpinterest-settings\",\"schema\":1,\"settings\":{\"hushpinterest_hide_ads\":false}}").toBundle();
        SettingsBackupPreference.onPageResumed(page);
        captureDialog("dialog-import", page.importPreview);
        page.importPreview.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        page.navigation.navigate("About");
        PreferenceCategory about = (PreferenceCategory) page.sections().get(page.sections().size() - 1);
        Preference licenses = null;
        for (int i = 0; i < about.getPreferenceCount(); i++) {
            if ("Licenses".contentEquals(about.getPreference(i).getTitle())) licenses = about.getPreference(i);
        }
        assertNotNull("no Licenses row on About", licenses);
        licenses.getOnPreferenceClickListener().onPreferenceClick(licenses);
        AlertDialog notice = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        captureDialog("dialog-licenses", notice);
        notice.dismiss();
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-rXB-ldrtl-w390dp-h844dp-night-xhdpi")
    public void largeRightToLeftPagesKeepTheirCompleteText() throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(2f);
        try {
            recreate();
            capture("large-rtl-overview");
            for (Preference section : page.sections()) {
                page.navigation.open(section);
                layout(dialog.getView());
                assertUncutText(dialog.getView());
            }
            capture("large-rtl-about");
            captureWholeInterface("large-rtl-interface");
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f);
        }
    }

    /**
     * Brazilian Portuguese runs longer than English. Every page still wraps its
     * whole text at twice the text size, and the table is the one on screen.
     */
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "pt-rBR-w390dp-h844dp-night-xhdpi")
    public void brazilianPortuguesePagesKeepTheirCompleteText() throws Exception {
        assertEquals("Atualizações", String.valueOf(page.findPreference(Settings.CHECK_FOR_RELEASES.key)
                .getParent().getTitle()));
        capture("pt-br-overview");
        page.navigation.navigate("Feed");
        capture("pt-br-feed");
        org.robolectric.RuntimeEnvironment.setFontScale(2f);
        try {
            recreate();
            for (Preference section : page.sections()) {
                page.navigation.open(section);
                layout(dialog.getView());
                assertUncutText(dialog.getView());
            }
            page.navigation.navigate("Feed");
            capture("pt-br-large-feed");
            page.navigation.navigate("Links");
            capture("pt-br-large-links");
            page.navigation.navigate("Pause, backup and diagnostics");
            capture("pt-br-large-pause");
            captureWholeInterface("pt-br-large-interface");
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f);
        }
    }

    private void captureWholeInterface(String name) throws Exception {
        page.navigation.navigate("Interface");
        for (int i = 0; i < list().getCount(); i++) {
            list().setSelectionFromTop(i, 0);
            layout(dialog.getView());
            assertUncutText(dialog.getView());
        }
        capture(name + "-bottom");
        list().setSelectionFromTop(0, 0);
        capture(name);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void recoveryKeepsRetryAndBackVisible() throws Exception {
        dialog.dismiss();
        controller.get().getFragmentManager().executePendingTransactions();
        HushPinterestPreferenceFragment.failNextInitialization = new IllegalStateException("render recovery");
        dialog = SettingsL10nTest.show(controller.get());
        page = page(dialog);
        capture("recovery");
        assertNull(page.navigation);
        assertEquals(3, list().getCount());
        assertUncutText(dialog.getView());
    }

    private static void assertUncutText(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof android.widget.TextView && !(view instanceof EditText)) {
            android.text.Layout text = ((android.widget.TextView) view).getLayout();
            if (text != null) for (int line = 0; line < text.getLineCount(); line++) assertEquals(0, text.getEllipsisCount(line));
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertUncutText(group.getChildAt(i));
        }
    }

    private void captureDialog(String name, AlertDialog modal) throws Exception {
        assertNotNull(modal);
        layout(dialog.getView());
        ShadowLooper.idleMainLooper();
        View content = modal.getWindow().getDecorView();
        assertTrue(content.getWidth() > 0 && content.getHeight() > 0);
        Bitmap image = Bitmap.createBitmap(780, 1688, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        dialog.getView().draw(canvas);
        canvas.drawColor(0x99000000);
        canvas.translate((780 - content.getWidth()) / 2f, (1688 - content.getHeight()) / 2f);
        content.draw(canvas);
        try (FileOutputStream out = new FileOutputStream(new File("build/reports/settings-design", name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        image.recycle();
    }

    private void capture(String name) throws Exception {
        capture(name, 1688);
    }

    private void capture(String name, int height) throws Exception {
        File folder = new File("build/reports/settings-design");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        View root = dialog.getView();
        layout(root, height);
        Bitmap image = Bitmap.createBitmap(780, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(image));
        try (FileOutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        image.recycle();
    }

    private static void layout(View root) {
        layout(root, 1688);
    }

    private static void layout(View root, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 780, height);
        ShadowLooper.idleMainLooper();
        root.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 780, height);
    }

    private void recreate() {
        controller.recreate();
        ShadowLooper.idleMainLooper();
        dialog = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag("hushpinterest_settings");
        assertNotNull(dialog);
        page = page(dialog);
    }

    private static HushPinterestPreferenceFragment page(SettingsDialog dialog) {
        return (HushPinterestPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
    }

    private ListView list() { return dialog.getView().findViewById(android.R.id.list); }

    /** The line under a home row, or null when it has none. */
    private String homeLine(String id) {
        for (int i = 0; i < list().getCount(); i++) {
            Object item = list().getItemAtPosition(i);
            if (item instanceof Preference && ("section_" + id).equals(((Preference) item).getKey())) {
                CharSequence summary = ((Preference) item).getSummary();
                return summary == null ? null : summary.toString();
            }
        }
        throw new AssertionError("no home row for " + id);
    }

    private boolean hasHomeRow(String id) {
        for (int i = 0; i < list().getCount(); i++) {
            Object item = list().getItemAtPosition(i);
            if (item instanceof Preference && ("section_" + id).equals(((Preference) item).getKey())) return true;
        }
        return false;
    }

    private boolean contains(String key) {
        return position(key) >= 0;
    }

    private int position(String key) {
        for (int i = 0; i < list().getCount(); i++) {
            if (key.equals(((Preference) list().getItemAtPosition(i)).getKey())) return i;
        }
        return -1;
    }

    private List<String> titles() {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < list().getCount(); i++) titles.add(String.valueOf(((Preference) list().getItemAtPosition(i)).getTitle()));
        return titles;
    }

    private static Map<String, Object> savedValues() {
        Map<String, Object> values = new TreeMap<>();
        for (Setting<?> setting : Setting.allLoadedSettings()) values.put(setting.key, setting.savedValue());
        return values;
    }

    private void tap(String key) {
        for (int i = 0; i < list().getCount(); i++) {
            if (!key.equals(((Preference) list().getItemAtPosition(i)).getKey())) continue;
            View row = list().getAdapter().getView(i, null, list());
            assertTrue(list().performItemClick(row, i, list().getAdapter().getItemId(i)));
            ShadowLooper.idleMainLooper();
            return;
        }
        fail("No visible row " + key);
    }

    private static EditText findSearch(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findSearch(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
