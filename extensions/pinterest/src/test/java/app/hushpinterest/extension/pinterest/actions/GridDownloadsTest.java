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
import android.os.Looper;
import android.view.View;
import android.widget.Checkable;
import android.widget.ListView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
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
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Queued: 1\nSkipped: 0\nUnsupported: 1\nFailed: 0"));
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
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Queued: 0\nSkipped: 0\nUnsupported: 0\nFailed: 1"));
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
            assertTrue(ShadowToast.getTextOfLatestToast().contains("Failed: 1"));
            assertTrue(ShadowToast.getTextOfLatestToast().contains("History could not be saved for 1 results"));
        }
        assertTrue(new DownloadLedger(activity).reconcile().isEmpty());
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
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().contains("Queued: 1\nSkipped: 1\n"));
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
