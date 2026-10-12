/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.ListView;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import app.hushpinterest.extension.pinterest.NativeDialogCapture;
import app.hushpinterest.extension.pinterest.actions.DownloadFolder;
import app.hushpinterest.extension.shared.SettingsContextRule;

/** #5: Pick a download folder, through Android's folder picker and back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class DownloadFolderPreferenceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final String AUTHORITY = "com.android.externalstorage.documents";

    @After public void reset() {
        DownloadFolder.PICKED.resetToDefault();
        Settings.SAVE_IN_PINTEREST_FOLDER.resetToDefault();
    }

    private interface Check {
        void run(Activity activity, DownloadFolderPreference row) throws Exception;
    }

    private static void onRow(Check check) throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushPinterestPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            check.run(activity, new DownloadFolderPreference(page, activity));
        }
    }

    private static ShadowActivity.IntentForResult tap(Activity activity, DownloadFolderPreference row) {
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowLooper.idleMainLooper();
        return shadowOf(activity).getNextStartedActivityForResult();
    }

    private static void answer(Activity activity, ShadowActivity.IntentForResult started, int result, Uri tree) {
        shadowOf(activity).receiveResult(started.intent, result, tree == null ? null : new Intent().setData(tree));
        ShadowLooper.idleMainLooper();
    }

    private static Uri tree(String id) {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, id);
    }

    @Test public void withNoFolderATapOpensTheFolderPickerAtPictures() throws Exception {
        onRow((activity, row) -> {
            assertEquals("action_download_folder", row.getKey());
            assertEquals("Pick a download folder", row.getTitle());
            assertEquals("Saves downloads in a folder you pick inside Pictures, DCIM, Download or Documents.", row.getSummary());
            ShadowActivity.IntentForResult started = tap(activity, row);
            assertNotNull("no picker opened", started);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, started.intent.getAction());
            assertEquals(DownloadFolderPreference.PICK, started.requestCode);
            assertEquals(DocumentsContract.buildDocumentUri(AUTHORITY, "primary:Pictures"),
                    started.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
        });
    }

    @Test public void aPickedFolderIsSavedNamedAndTakesOverFromPinterestsFolder() throws Exception {
        Settings.SAVE_IN_PINTEREST_FOLDER.save(true);
        onRow((activity, row) -> {
            answer(activity, tap(activity, row), Activity.RESULT_OK, tree("primary:Pictures/Art"));
            assertEquals("Pictures/Art", DownloadFolder.PICKED.get());
            assertFalse("the folder just picked is the one that's used", Settings.SAVE_IN_PINTEREST_FOLDER.savedValue());
            assertEquals("Pictures/Art", DownloadFolder.current());
            assertEquals("Downloads will be saved in Pictures/Art.", ShadowToast.getTextOfLatestToast());
            assertEquals("Saving downloads in Pictures/Art. Tap to pick another folder or go back to the Download folder.", row.getSummary());
            // Turning Pinterest's folder back on wins, and the row says so.
            Settings.SAVE_IN_PINTEREST_FOLDER.save(true);
            assertEquals(DownloadFolder.PINTEREST, DownloadFolder.current());
            assertEquals("Pictures/Art is picked, but Save in Pinterest's folder is on, so downloads go to Pictures/100PINT/Pins.", row.getSummary());
        });
    }

    @Test public void aFolderDownloadManagerCanNotWriteToChangesNothing() throws Exception {
        DownloadFolder.PICKED.save("DCIM/Pins");
        onRow((activity, row) -> {
            ShadowActivity.IntentForResult started = pickAnother(activity, row);
            answer(activity, started, Activity.RESULT_OK, tree("primary:Music/Pins"));
            assertEquals("DCIM/Pins", DownloadFolder.PICKED.get());
            assertEquals("Pick a folder in Pictures, DCIM, Download or Documents.",
                    ShadowToast.getTextOfLatestToast());
            ShadowToast.reset();
            answer(activity, pickAnother(activity, row), Activity.RESULT_CANCELED, null);
            assertEquals("DCIM/Pins", DownloadFolder.PICKED.get());
            assertNull("a cancelled picker says nothing", ShadowToast.getTextOfLatestToast());
        });
    }

    @Test public void theTopOfDownloadOrTheMenuGoesBackToDownload() throws Exception {
        DownloadFolder.PICKED.save("Pictures/Art");
        onRow((activity, row) -> {
            answer(activity, pickAnother(activity, row), Activity.RESULT_OK, tree("primary:Download"));
            assertEquals("", DownloadFolder.PICKED.get());
            assertNull(DownloadFolder.current());
            assertEquals("Downloads will be saved in the Download folder.", ShadowToast.getTextOfLatestToast());

            DownloadFolder.PICKED.save("Pictures/Art");
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
            ListView items = menu.getListView();
            assertEquals("Pick another folder", items.getAdapter().getItem(0).toString());
            assertEquals("Use Download again", items.getAdapter().getItem(1).toString());
            shadowOf(menu).clickOnItem(1);
            ShadowLooper.idleMainLooper();
            assertEquals("", DownloadFolder.PICKED.get());
            assertEquals("Downloads will be saved in the Download folder.", ShadowToast.getTextOfLatestToast());
            assertNull("going back opened no picker", shadowOf(activity).getNextStartedActivityForResult());
            assertEquals("Saves downloads in a folder you pick inside Pictures, DCIM, Download or Documents.", row.getSummary());
        });
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-rUS-w390dp-h844dp-night-xhdpi")
    public void nativeFolderChoicesStayReadableAndBackPreservesThePickedFolder() throws Exception {
        DownloadFolder.PICKED.save("Pictures/Art references");
        onRow((activity, row) -> {
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            ShadowLooper.idleMainLooper();
            AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(menu);
            assertEquals(2, menu.getListView().getAdapter().getCount());
            assertEquals("Pick another folder", menu.getListView().getAdapter().getItem(0).toString());
            assertEquals("Use Download again", menu.getListView().getAdapter().getItem(1).toString());
            assertEquals("Cancel", menu.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            assertTrue(menu.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());
            SettingsDialog settings = (SettingsDialog) activity.getFragmentManager().findFragmentByTag("hushpinterest_settings");
            NativeDialogCapture.capture("download-folder-choices", settings.getView(), menu);
            menu.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertFalse(menu.isShowing());
            assertEquals("Pictures/Art references", DownloadFolder.PICKED.get());
            assertNull("Back opened an external picker", shadowOf(activity).getNextStartedActivityForResult());
        });
    }

    /** With a folder already picked, a tap offers a menu; its first item opens the picker. */
    private static ShadowActivity.IntentForResult pickAnother(Activity activity, DownloadFolderPreference row) {
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no menu", menu);
        shadowOf(menu).clickOnItem(0);
        ShadowLooper.idleMainLooper();
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull("the menu opened no picker", started);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, started.intent.getAction());
        return started;
    }
}
