/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.View;
import android.widget.Checkable;
import android.widget.CheckedTextView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import app.hushpinterest.extension.pinterest.NativeDialogCapture;
import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.PatchFamilyForTests;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.WorkerPoolForTests;
import app.hushpinterest.extension.shared.settings.HushPinterestPause;
import app.hushpinterest.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, shadows = GridDownloadsTest.Cells.class,
        instrumentedPackages = "app.hushpinterest.extension.pinterest.actions")
public class GridDownloadsTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private Activity activity;
    private RecyclerView grid;

    @Implements(value = PinDownloads.class, isInAndroidSdk = false)
    public static class Cells {
        @Implementation protected static Object cellPin(View cell) { return cell.getTag(); }
    }

    @Before public void prepare() {
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        Utils.setActivity(activity);
        PauseForTests.resume();
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.PIN_DOWNLOADS));
        Settings.DOWNLOAD_PINS.save(true);
        grid = new RecyclerView(activity);
        activity.setContentView(grid);
        grid.layout(0, 0, 400, 600);
    }

    @After public void reset() throws Exception {
        if (ShadowAlertDialog.getLatestAlertDialog() != null) ShadowAlertDialog.getLatestAlertDialog().dismiss();
        var running = GridDownloads.class.getDeclaredField("RUNNING");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(null)).set(false);
        settle();
        RuntimeEnvironment.setFontScale(1f);
        Settings.DOWNLOAD_PINS.save(false);
        PatchFamilyForTests.capabilities(null);
        PauseForTests.resume();
        Utils.setActivity(null);
    }

    private Object pin(String id) { return Map.of("id", id, "title", "Picture " + id, "images",
            Map.of("orig", Map.of("url", "https://i.pinimg.com/originals/" + id + ".jpg"))); }

    private View cell(Object pin, int top) {
        View cell = new View(activity);
        cell.setTag(pin);
        grid.addView(cell);
        cell.layout(0, top, 200, top + 100);
        return cell;
    }

    private AlertDialog choose(View origin, int... positions) {
        assertTrue(GridDownloads.show(origin));
        AlertDialog picker = ShadowAlertDialog.getLatestAlertDialog();
        assertFalse(picker.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        ListView list = picker.getListView();
        for (int position : positions) list.performItemClick(list.getChildAt(position), position, position);
        return picker;
    }

    private void settle() throws Exception {
        Method await = Utils.class.getDeclaredMethod("awaitBackgroundTasksForTests");
        await.setAccessible(true);
        for (int i = 0; i < 10; i++) {
            await.invoke(null);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-rUS-w390dp-h844dp-night-xhdpi")
    public void nativePickerShowsEmptyAndSelectedChoicesWithoutQueueing() throws Exception {
        View origin = cell(previewPin("101", "A landscape study with warm evening light and a detailed foreground"), 0);
        cell(previewPin("102", "A second reference for the same collection"), 100);
        AlertDialog picker = choose(origin);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ListView choices = picker.getListView();
        assertEquals(2, choices.getAdapter().getCount());
        assertEquals(0, choices.getCheckedItemCount());
        assertFalse(picker.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertTrue(picker.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());
        NativeDialogCapture.capture("visible-picker-empty", grid, picker);
        TextView label = choices.getChildAt(0).findViewById(android.R.id.text1);
        assertNotNull("the first choice has no label", label);
        int titleEnd = label.getText().toString().indexOf('\n');
        assertTrue("the pin ID must stay on its own line", titleEnd > 0);
        assertTrue("the longer title never wrapped before the pin ID line",
                label.getLayout().getLineEnd(0) < titleEnd);

        assertTrue(choices.performItemClick(choices.getChildAt(0), 0, choices.getItemIdAtPosition(0)));
        assertTrue(choices.isItemChecked(0));
        assertTrue(picker.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertTrue(choices.performItemClick(choices.getChildAt(0), 0, choices.getItemIdAtPosition(0)));
        assertEquals(0, choices.getCheckedItemCount());
        assertFalse("clearing the last choice must disable Queue selected",
                picker.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertTrue(choices.performItemClick(choices.getChildAt(1), 1, choices.getItemIdAtPosition(1)));
        assertFalse(choices.isItemChecked(0));
        assertTrue(choices.isItemChecked(1));
        assertEquals(1, choices.getCheckedItemCount());
        NativeDialogCapture.capture("visible-picker-selected", grid, picker);
        assertTrue("the selected row has no checked indicator", choices.getChildAt(1) instanceof Checkable);
        assertTrue("the visible checkbox lost the native list's selection", ((Checkable) choices.getChildAt(1)).isChecked());
        picker.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(picker.isShowing());
        assertEquals(0, Shadows.shadowOf((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE)).getRequestCount());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-rUS-w390dp-h844dp-night-xhdpi")
    public void nativeBatchProgressKeepsItsStopActionAndReportsUnsupportedSources() throws Exception {
        List<Object> pins = new ArrayList<>(List.of(previewPin("201", "First reference"), previewPin("202", "Second reference")));
        Utils.awaitBackgroundTasksForTests();
        ThreadPoolExecutor workers = ReflectionHelpers.getStaticField(Utils.class, "backgroundThreadPool");
        int originalCoreSize = workers.getCorePoolSize();
        CountDownLatch parked = new CountDownLatch(workers.getMaximumPoolSize());
        CountDownLatch release = new CountDownLatch(1);
        AlertDialog progress;
        try {
            // Leave the queue available for the result write, but hold every worker. The main
            // looper can attach and lay out the dialog without advancing the batch to completion.
            workers.setCorePoolSize(workers.getMaximumPoolSize());
            for (int i = 0; i < workers.getMaximumPoolSize(); i++) {
                assertTrue(Utils.runOnBackgroundThread(() -> {
                    parked.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            assertTrue("workers didn't pause before showing progress", parked.await(10, TimeUnit.SECONDS));
            assertTrue(GridDownloads.queue(activity, pins, "Download visible pins", null, 0, true));
            progress = ShadowAlertDialog.getLatestAlertDialog();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
            assertTrue("the progress window wasn't attached", progress.getWindow().getDecorView().isAttachedToWindow());
            assertTrue(GridDownloads.running());
            assertEquals("Stop selection", progress.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            assertTrue(progress.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());
            assertFalse("a tap beside the progress dialog must not stop the batch",
                    Shadows.shadowOf(progress).isCancelableOnTouchOutside());
            NativeDialogCapture.capture("batch-progress", grid, progress);
        } finally {
            release.countDown();
            workers.setCorePoolSize(originalCoreSize);
        }
        settle();
        assertFalse(GridDownloads.running());
        assertEquals("Close", progress.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        assertTrue(Shadows.shadowOf(progress).getMessage().toString().contains("Unsupported: 2"));
        assertEquals(0, Shadows.shadowOf((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE)).getRequestCount());
        NativeDialogCapture.capture("batch-progress-complete", grid, progress);
    }

    private Object previewPin(String id, String title) {
        return Map.of("id", id, "title", title, "images", Map.of("orig", Map.of(
                "url", "https://example.invalid/public-reference-" + id + ".jpg")));
    }

    private static Object video(String id, String file) {
        return Map.of("id", id, "title", "Clip " + id, "images", Map.of(),
                "videos", Map.of("video_list", Map.of("V_720P", Map.of("url", "https://v1.pinimg.com/videos/mc/" + file,
                        "width", 720, "height", 1280))));
    }

    private static <T extends View> List<T> views(View root, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(root)) found.add(type.cast(root));
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) found.addAll(views(group.getChildAt(i), type));
        }
        return found;
    }

    private static List<RadioButton> filters(AlertDialog picker) {
        return views(picker.getWindow().getDecorView(), RadioButton.class);
    }

    /** The selection count under the filters: the one text the dialog says aloud as it changes. */
    private static TextView count(AlertDialog picker) {
        return views(picker.getWindow().getDecorView(), TextView.class).stream()
                .filter(view -> view.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE)
                .findFirst().orElseThrow();
    }

    private static String row(ListView list, int position) {
        return list.getAdapter().getItem(position).toString();
    }

    /** A row's id line, the id isolated from the text around it as the row writes it. */
    private static String pinLine(String id) {
        return L10n.f("Pin %s", L10n.isolate(id));
    }

    /**
     * Images and videos come from the media Pinterest supplied, unsupported ones are named, and a
     * choice belongs to its pin, not its row, so it survives the filter changing what's shown.
     */
    @Test public void filtersShowImagesOrVideosAndAChoiceStaysWithItsPin() throws Exception {
        View origin = cell(pin("1"), 0);
        cell(video("2", "2.mp4"), 100);
        cell(video("3", "hls/3.m3u8"), 200);
        cell(Map.of("id", "4", "images", Map.of()), 300);
        AlertDialog picker = choose(origin);
        ListView list = picker.getListView();
        List<RadioButton> filters = filters(picker);
        assertEquals(List.of("All (4)", "Images (2)", "Videos (2)"),
                filters.stream().map(option -> option.getText().toString()).toList());
        assertTrue(filters.get(0).isChecked());
        assertTrue(row(list, 0), row(list, 0).endsWith(pinLine("1") + "\nImage"));
        assertTrue(row(list, 1), row(list, 1).endsWith(pinLine("2") + "\nVideo"));
        assertTrue(row(list, 2), row(list, 2).endsWith(pinLine("3") + "\nUnsupported video"));
        assertTrue(row(list, 3), row(list, 3).endsWith(pinLine("4") + "\nUnsupported image"));
        assertEquals("Selected: 0 (unsupported: 0)", count(picker).getText().toString());

        list.performItemClick(list.getChildAt(0), 0, list.getItemIdAtPosition(0));
        filters.get(2).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, list.getAdapter().getCount());
        assertTrue(row(list, 0).contains(pinLine("2")));
        assertEquals("ids follow the pin, not the row", 1, list.getItemIdAtPosition(0));
        assertEquals(0, list.getCheckedItemCount());
        assertTrue("a pin chosen under All stays chosen", picker.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        list.performItemClick(list.getChildAt(1), 1, list.getItemIdAtPosition(1));
        assertEquals("Selected: 2 (unsupported: 1)", count(picker).getText().toString());

        filters.get(1).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, list.getAdapter().getCount());
        assertTrue(list.isItemChecked(0));
        assertFalse(list.isItemChecked(1));
        filters.get(0).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(4, list.getAdapter().getCount());
        assertTrue(list.isItemChecked(0));
        assertTrue(list.isItemChecked(2));
        assertEquals(2, list.getCheckedItemCount());

        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        var downloads = Shadows.shadowOf((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE));
        assertEquals(1, downloads.getRequestCount());
        List<DownloadLedger.Job> history = new DownloadLedger(activity).reconcile();
        assertEquals(List.of("3", "1"), history.stream().map(job -> job.pinId).toList());
        assertEquals(DownloadLedger.State.UNSUPPORTED, history.get(0).state);
    }

    @Test public void aFilterWithNoPinsCantBeChosen() {
        View origin = cell(pin("1"), 0);
        List<RadioButton> filters = filters(choose(origin));
        assertTrue(filters.get(1).isEnabled());
        assertEquals("Videos (0)", filters.get(2).getText().toString());
        assertFalse(filters.get(2).isEnabled());
    }

    /** A cell's own picture becomes a small copy. A cell without one keeps a row of text, and nothing is fetched. */
    @Test public void rowsShowASmallCopyOfThePictureACellAlreadyShows() {
        PinTransfer.Connection previous = OriginalLookup.connection;
        List<Object> asked = new ArrayList<>();
        OriginalLookup.connection = uri -> {
            asked.add(uri);
            throw new IOException("the chooser must not fetch anything");
        };
        try {
            android.graphics.Bitmap source = android.graphics.Bitmap.createBitmap(200, 300, android.graphics.Bitmap.Config.ARGB_8888);
            android.widget.FrameLayout pictured = new android.widget.FrameLayout(activity);
            pictured.setTag(pin("1"));
            ImageView picture = new ImageView(activity);
            picture.setImageDrawable(new BitmapDrawable(activity.getResources(), source));
            pictured.addView(picture);
            grid.addView(pictured);
            pictured.layout(0, 0, 200, 300);
            picture.layout(0, 0, 200, 300);
            cell(pin("2"), 300);

            ListView list = choose(pictured).getListView();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            CheckedTextView first = (CheckedTextView) list.getChildAt(0);
            assertTrue("the checkbox leads the row", first.getCompoundDrawablesRelative()[0].isStateful());
            BitmapDrawable thumbnail = (BitmapDrawable) first.getCompoundDrawablesRelative()[2];
            assertNotSame("the chooser keeps its own copy", source, thumbnail.getBitmap());
            int box = Math.round(48 * activity.getResources().getDisplayMetrics().density);
            assertEquals(box, thumbnail.getBitmap().getHeight());
            assertEquals(Math.round(box * 2 / 3f), thumbnail.getBitmap().getWidth());
            assertNull("no picture, no stand-in", ((CheckedTextView) list.getChildAt(1)).getCompoundDrawablesRelative()[2]);
            assertTrue(asked.isEmpty());
        } finally {
            OriginalLookup.connection = previous;
        }
    }

    /** TalkBack hears each row's kind and checked state and the count as it changes; rows mirror and grow with the text. */
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-rXB-ldrtl-w390dp-h844dp-night-xhdpi")
    public void choicesReadAloudMirrorAndShowWholeAtTwiceTheTextSize() {
        RuntimeEnvironment.setFontScale(2f);
        prepare();
        assertEquals(2f, activity.getResources().getConfiguration().fontScale, 0f);
        android.widget.FrameLayout pictured = new android.widget.FrameLayout(activity);
        pictured.setTag(Map.of("id", "1", "title", "A landscape study with warm evening light and a detailed foreground",
                "images", Map.of("orig", Map.of("url", "https://i.pinimg.com/originals/1.jpg"))));
        ImageView picture = new ImageView(activity);
        picture.setImageDrawable(new BitmapDrawable(activity.getResources(),
                android.graphics.Bitmap.createBitmap(120, 90, android.graphics.Bitmap.Config.ARGB_8888)));
        pictured.addView(picture);
        grid.addView(pictured);
        pictured.layout(0, 0, 200, 150);
        picture.layout(0, 0, 200, 150);
        cell(video("2", "2.mp4"), 150);

        AlertDialog picker = choose(pictured);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ListView list = picker.getListView();
        assertEquals(View.LAYOUT_DIRECTION_RTL, list.getLayoutDirection());
        CheckedTextView first = (CheckedTextView) list.getChildAt(0);
        assertTrue("the picture sits at the row's end, on the left", first.getCompoundDrawables()[0] instanceof BitmapDrawable);
        assertTrue("the checkbox sits at the row's start, on the right", first.getCompoundDrawables()[2].isStateful());
        for (int position = 0; position < list.getChildCount(); position++) {
            CheckedTextView row = (CheckedTextView) list.getChildAt(position);
            assertTrue("row " + position + " was cut off", row.getLayout().getHeight()
                    <= row.getHeight() - row.getCompoundPaddingTop() - row.getCompoundPaddingBottom());
            android.view.accessibility.AccessibilityNodeInfo node = row.createAccessibilityNodeInfo();
            assertTrue(node.isCheckable());
            assertFalse(node.isChecked());
            assertTrue(node.getText().toString().contains(position == 0 ? "Image" : "Video"));
        }
        assertTrue("nothing wrapped at twice the text size, so this proves little", first.getLineCount() > 3);
        for (RadioButton option : filters(picker)) {
            assertTrue(option.getText() + " was cut off", option.getLayout().getHeight()
                    <= option.getHeight() - option.getCompoundPaddingTop() - option.getCompoundPaddingBottom());
            assertTrue(option.getHeight() >= Math.round(48 * activity.getResources().getDisplayMetrics().density));
        }
        list.performItemClick(list.getChildAt(0), 0, list.getItemIdAtPosition(0));
        assertTrue(list.getChildAt(0).createAccessibilityNodeInfo().isChecked());
        assertEquals(L10n.f("Selected: %1$d (unsupported: %2$d)", 1, 0), count(picker).getText().toString());
    }

    @Test public void snapshotIncludesOnlyVisibleAttachedPinCellsAndDeduplicatesIds() {
        View first = cell(pin("1"), 0);
        cell(pin("1"), 100);
        cell(pin("2"), 900);
        cell(pin("3"), 200).setVisibility(View.GONE);
        cell(Map.of("id", "4"), 300);
        cell(pin("5"), 350);
        assertSame(grid, GridDownloads.grid(first));
        assertEquals(List.of("1", "5"), GridDownloads.snapshot(grid).stream().map(PinMedia::id).toList());
        grid.removeView(first);
        assertNull(GridDownloads.grid(first));
    }

    @Test public void visibleSelectionIsBoundedToHistoryCapacity() {
        for (int i = 1; i <= 50; i++) cell(pin("" + i), 0);
        assertEquals(32, GridDownloads.snapshot(grid).size());
    }

    @Test public void missingNativeOriginMatchesOnlyOneVisibleGridContainingTheMenuPin() {
        cell(pin("1"), 0);
        assertSame(grid, GridDownloads.grid(null, pin("1")));
        assertNull(GridDownloads.grid(null, pin("2")));
        grid.setVisibility(View.GONE);
        assertNull(GridDownloads.grid(null, pin("1")));
        grid.setVisibility(View.VISIBLE);
        android.widget.FrameLayout roots = new android.widget.FrameLayout(activity);
        ((android.view.ViewGroup) grid.getParent()).removeView(grid);
        RecyclerView duplicate = new RecyclerView(activity);
        View second = new View(activity);
        second.setTag(pin("1"));
        duplicate.addView(second);
        roots.addView(grid);
        roots.addView(duplicate);
        activity.setContentView(roots);
        roots.layout(0, 0, 400, 400);
        grid.layout(0, 0, 200, 400);
        grid.getChildAt(0).layout(0, 0, 100, 100);
        duplicate.layout(200, 0, 400, 400);
        second.layout(0, 0, 100, 100);
        assertNull(GridDownloads.grid(null, pin("1")));
    }

    @Test public void onlyCheckedPinsQueueAndUnsupportedSelectionsGetHistoryResults() throws Exception {
        View origin = cell(pin("1"), 0);
        cell(pin("2"), 100);
        cell(Map.of("id", "3", "images", Map.of()), 200);
        AlertDialog picker = choose(origin, 1, 2);
        assertTrue(picker.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        var downloads = Shadows.shadowOf((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE));
        assertEquals(1, downloads.getRequestCount());
        assertEquals("https://i.pinimg.com/originals/2.jpg",
                org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowDownloadManager.ShadowRequest>extract(downloads.getRequest(0)).getUri().toString());
        List<DownloadLedger.Job> history = new DownloadLedger(activity).reconcile();
        assertEquals(2, history.size());
        assertEquals("3", history.get(0).pinId);
        assertEquals(DownloadLedger.State.UNSUPPORTED, history.get(0).state);
        assertTrue(result().contains("Queued: 1\nSkipped: 0\nUnsupported: 1\nFailed: 0"));
        assertNull("the open dialog already says it", ShadowToast.getTextOfLatestToast());
    }

    /** What the progress dialog says once the batch is over: the result, with Close. */
    private static String result() {
        AlertDialog progress = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("the progress dialog went", progress.isShowing());
        assertEquals("Close", progress.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        return Shadows.shadowOf(progress).getMessage().toString();
    }

    /** A grid selection and a board's batch queue through the same request builder, network limits and all. */
    @Test public void networkLimitsReachEveryPinOfAGridSelectionAndABoardBatch() throws Exception {
        Settings.DOWNLOAD_UNMETERED_ONLY.save(true);
        Settings.DOWNLOAD_NO_ROAMING.save(true);
        try {
            View origin = cell(pin("1"), 0);
            cell(pin("2"), 100);
            choose(origin, 0, 1).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            // Download board hands its pins to the same batch.
            assertTrue(GridDownloads.queue(activity, new ArrayList<>(List.of(pin("3"), pin("4"))), "Download board", null, 0, false));
            settle();
            var downloads = Shadows.shadowOf((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE));
            assertEquals(4, downloads.getRequestCount());
            for (long id = 0; id < 4; id++) {
                org.robolectric.shadows.ShadowDownloadManager.ShadowRequest request =
                        org.robolectric.shadow.api.Shadow.extract(downloads.getRequest(id));
                assertEquals("https://i.pinimg.com/originals/" + (id + 1) + ".jpg", request.getUri().toString());
                assertFalse(request.getAllowedOverMetered());
                assertFalse(request.getAllowedOverRoaming());
            }
        } finally {
            Settings.DOWNLOAD_UNMETERED_ONLY.save(false);
            Settings.DOWNLOAD_NO_ROAMING.save(false);
        }
    }

    @Test public void pauseBetweenSelectionAndQueueSkipsEverySelectedPin() throws Exception {
        View origin = cell(pin("1"), 0);
        cell(pin("2"), 100);
        AlertDialog picker = choose(origin, 0, 1);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertEquals(0, Shadows.shadowOf((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE)).getRequestCount());
        List<DownloadLedger.Job> history = new DownloadLedger(activity).reconcile();
        assertEquals(2, history.size());
        assertTrue(history.stream().allMatch(job -> job.state == DownloadLedger.State.SKIPPED && job.id < 0));
    }

    @Test public void localResultsSurviveReloadAndNeverBecomeRetryableRequests() {
        for (DownloadLedger.State state : List.of(DownloadLedger.State.SKIPPED,
                DownloadLedger.State.UNSUPPORTED, DownloadLedger.State.FAILED)) assertTrue(DownloadLedger.recordResult(activity, "5", state));
        List<DownloadLedger.Job> history = new DownloadLedger(activity).reconcile();
        assertEquals(3, history.size());
        assertTrue(history.stream().allMatch(job -> job.id < 0 && !job.canRetry()));
    }

    @Test @Config(shadows = DownloadLedgerTest.NativeDownloads.class)
    public void rejectedNativeEnqueueReportsFailedAndMixedHistoryQueriesOnlyNativeIds() throws Exception {
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadLedgerTest.NativeDownloads downloads = org.robolectric.shadow.api.Shadow.extract(manager);
        downloads.rejectEnqueue = true;
        View origin = cell(pin("1"), 0);
        choose(origin, 0).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        List<DownloadLedger.Job> local = new DownloadLedger(activity).reconcile();
        assertEquals(DownloadLedger.State.FAILED, local.get(0).state);
        assertTrue(downloads.queries.isEmpty());
        assertTrue(result().contains("Queued: 0\nSkipped: 0\nUnsupported: 0\nFailed: 1"));
        downloads.rejectEnqueue = false;
        assertTrue(PinDownloads.start(pin("2"), activity));
        settle();
        List<DownloadLedger.Job> mixed = new DownloadLedger(activity).reconcile();
        assertEquals(2, mixed.size());
        assertTrue(downloads.queries.stream().allMatch(ids -> ids.length == 1 && ids[0] >= 0));
        assertTrue(DownloadLedger.removeHistory(activity, local.get(0).id, null));
        settle();
        assertEquals(1, new DownloadLedger(activity).reconcile().size());
        assertEquals(0, downloads.removes);
    }

    @Test public void unavailableWorkerReportsFailedWithoutInventingQueueOrHistorySuccess() throws Exception {
        View origin = cell(pin("1"), 0);
        AlertDialog picker = choose(origin, 0);
        try (WorkerPoolForTests ignored = WorkerPoolForTests.fill()) {
            picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            String result = result();
            assertTrue(result, result.contains("Failed: 1"));
            assertTrue(result, result.contains("Couldn't save history for one result."));
        }
        assertTrue(new DownloadLedger(activity).reconcile().isEmpty());
    }

    /** A batch says its failures once, in the summary. A pin downloaded on its own still says what went wrong. */
    @Test @Config(shadows = DownloadLedgerTest.NativeDownloads.class)
    public void aBatchCountsFailuresWithoutAToastForEachPin() throws Exception {
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadLedgerTest.NativeDownloads downloads = org.robolectric.shadow.api.Shadow.extract(manager);
        downloads.rejectEnqueue = true;
        View origin = cell(pin("1"), 0);
        cell(pin("2"), 100);
        ShadowToast.reset();
        choose(origin, 0, 1).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertEquals("the open dialog says the result, so no toast does", 0, ShadowToast.shownToastCount());
        assertTrue(result().contains("Queued: 0\nSkipped: 0\nUnsupported: 0\nFailed: 2"));

        ShadowToast.reset();
        assertTrue(PinDownloads.start(pin("3"), activity));
        settle();
        assertEquals("Couldn't start the download. Check Downloads and try again.", ShadowToast.getTextOfLatestToast());
    }

    /** Stop lands while the first pin's original lookup is still out, so that pin is queued and the rest skipped. */
    @Test public void stoppingSelectionKeepsTheStartedDownloadAndSkipsRemainingPins() throws Exception {
        CountDownLatch asked = new CountDownLatch(1), answer = new CountDownLatch(1);
        PinTransfer.Connection previous = OriginalLookup.connection;
        OriginalLookup.connection = uri -> {
            asked.countDown();
            try {
                answer.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("media host held for the test");
        };
        try {
            View origin = cell(Map.of("id", "1", "images", Map.of("736x", Map.of("url", MediaHostForTests.STAND_IN))), 0);
            cell(pin("2"), 100);
            choose(origin, 0, 1).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the first pin never asked the media host", asked.await(5, TimeUnit.SECONDS));
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        } finally {
            answer.countDown();
        }
        try {
            settle();
        } finally {
            OriginalLookup.connection = previous;
        }
        assertEquals(1, Shadows.shadowOf((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE)).getRequestCount());
        List<DownloadLedger.Job> history = new DownloadLedger(activity).reconcile();
        assertEquals(2, history.size());
        assertEquals("2", history.get(0).pinId);
        assertEquals(DownloadLedger.State.SKIPPED, history.get(0).state);
        // Stop closed the dialog, so a short toast says how it ended, and only that.
        String said = ShadowToast.getTextOfLatestToast();
        assertEquals("Queued: 1\nSkipped: 1\nUnsupported: 0\nFailed: 0", said);
        assertEquals(android.widget.Toast.LENGTH_SHORT, ShadowToast.getLatestToast().getDuration());
    }

    @Test public void aBatchThatFailsToContinueEndsSoTheNextSelectionCanStart() throws Exception {
        List<Object> broken = new AbstractList<Object>() {
            @Override public Object get(int index) {
                throw new IllegalStateException("pin list changed under the batch");
            }

            @Override public int size() {
                return 1;
            }
        };
        assertTrue(GridDownloads.queue(activity, broken, "Download visible pins", null, 0, true));
        AlertDialog progress = ShadowAlertDialog.getLatestAlertDialog();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(GridDownloads.running());
        assertFalse(progress.isShowing());
        assertTrue(GridDownloads.queue(activity, new ArrayList<>(List.of(pin("4"))), "Download visible pins", null, 0, true));
        settle();
        assertFalse(GridDownloads.running());
        assertEquals(1, Shadows.shadowOf((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE)).getRequestCount());
    }
}
