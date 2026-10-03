/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Fragment;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.PatchFamilyForTests;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.settings.PauseForTests;

/** Exercises Android 9's user-selected document flow without a storage permission. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class DownloadPickerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private Activity activity;
    private final Map<String, Object> pin = Map.of("id", "123456", "images", Map.of(
            "orig", Map.of("url", "https://i.pinimg.com/originals/pin.jpg")));

    @Before public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        Utils.setActivity(activity);
        PauseForTests.resume();
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.PIN_DOWNLOADS));
        Settings.DOWNLOAD_PINS.save(true);
    }

    @After public void reset() {
        Fragment fragment = activity.getFragmentManager().findFragmentByTag("hushpinterest_save_pin");
        if (fragment instanceof PinDownloads.SaveFragment) {
            fragment.onActivityResult(48122, Activity.RESULT_CANCELED, null);
            activity.getFragmentManager().executePendingTransactions();
        }
        Settings.DOWNLOAD_PINS.save(false);
        PatchFamilyForTests.capabilities(null);
        PauseForTests.resume();
        Utils.setActivity(null);
    }

    @Test public void savePickerHasCorrectMimeFilenameAndDoesNotRequestStoragePermission() {
        assertTrue(PinDownloads.start(pin, activity));
        Intent picker = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.getAction());
        assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals("image/jpeg", picker.getType());
        assertTrue(picker.getStringExtra(Intent.EXTRA_TITLE).matches("Pinterest_123456_[0-9]+\\.jpg"));
        assertFalse(PinDownloads.start(pin, activity));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        cancel();
        assertTrue(PinDownloads.start(pin, activity));
    }

    @Test public void launchedPickerIsNotLaunchedAgainAfterFragmentStateRestoration() {
        assertTrue(PinDownloads.start(pin, activity));
        Shadows.shadowOf(activity).getNextStartedActivity();
        PinDownloads.SaveFragment original = fragment();
        Fragment.SavedState state = activity.getFragmentManager().saveFragmentInstanceState(original);
        Bundle args = original.getArguments();
        cancel();
        PinDownloads.SaveFragment restored = new PinDownloads.SaveFragment();
        restored.setArguments(args);
        restored.setInitialSavedState(state);
        activity.getFragmentManager().beginTransaction().add(restored, "hushpinterest_save_pin").commitNow();
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void leavingActivityReleasesPendingSaveForTheNextActivity() {
        assertTrue(PinDownloads.start(pin, activity));
        activity.finish();
        controller.pause().stop().destroy();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        Utils.setActivity(activity);
        assertTrue(PinDownloads.start(pin, activity));
    }

    @Test public void rejectedWorkerCleansEmptyDocumentAndReleasesSaveWithoutNetwork() throws Exception {
        assertTrue(PinDownloads.start(pin, activity));
        PinDownloads.SaveFragment fragment = fragment();
        CountDownLatch deleted = new CountDownLatch(1);
        ContentProvider provider = new ContentProvider() {
            @Override public boolean onCreate() { return true; }
            @Override public Bundle call(String method, String argument, Bundle extras) {
                if ("android:deleteDocument".equals(method)) deleted.countDown();
                return new Bundle();
            }
            @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
            @Override public String getType(Uri uri) { return "image/jpeg"; }
            @Override public Uri insert(Uri uri, ContentValues values) { return null; }
            @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
            @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
        };
        ShadowContentResolver.registerProviderInternal("test.documents", provider);
        Field worker = Utils.class.getDeclaredField("backgroundThreadPool");
        worker.setAccessible(true);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) worker.get(null);
        CountDownLatch release = new CountDownLatch(1);
        try {
            boolean full = false;
            for (int count = 0; count < pool.getMaximumPoolSize() + 33; count++) {
                if (!Utils.runOnBackgroundThread(() -> {
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                })) { full = true; break; }
            }
            assertTrue("worker pool wasn't filled", full);
            fragment.onActivityResult(48122, Activity.RESULT_OK,
                    new Intent().setData(Uri.parse("content://test.documents/document/new")));
            activity.getFragmentManager().executePendingTransactions();
            assertTrue("empty document wasn't deleted", deleted.await(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        // The cleanup daemon releases the claim after the provider answered.
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        Field saving = PinDownloads.class.getDeclaredField("SAVING");
        saving.setAccessible(true);
        while (((java.util.concurrent.atomic.AtomicBoolean) saving.get(null)).get() && System.nanoTime() < end) Thread.yield();
        assertTrue(PinDownloads.start(pin, activity));
    }

    private PinDownloads.SaveFragment fragment() {
        return (PinDownloads.SaveFragment) activity.getFragmentManager().findFragmentByTag("hushpinterest_save_pin");
    }

    private void cancel() {
        fragment().onActivityResult(48122, Activity.RESULT_CANCELED, null);
        activity.getFragmentManager().executePendingTransactions();
    }
}
