/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;
import app.hushpinterest.extension.shared.settings.HushPinterestPause;
import app.hushpinterest.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AdvertisingIdTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final String REAL = "38400000-8cf0-11bd-b23e-10b96e40000d";

    private static final Object STORE = new Object();
    private static final Object CONTINUATION = new Object();
    private static final byte[] RECORD = {1, 2, 3};
    /** What Pinterest's delete answers in these tests, a value no real save returns. */
    private static final Object DELETED = new Object();

    /** Every delete the save hook made: the store, the keys and the continuation it handed over. */
    private final List<Object[]> deletes = new ArrayList<>();

    @Before public void installedBuild() throws ReflectiveOperationException {
        installed(true);
        HookStatus.clear();
        AdvertisingId.deleteForTests = (store, keys, continuation) -> {
            deletes.add(new Object[]{store, keys, continuation});
            return DELETED;
        };
    }

    @After public void restore() throws ReflectiveOperationException {
        AdvertisingId.deleteForTests = null;
        HookStatus.clear();
        PauseForTests.resume();
        Settings.HIDE_ADVERTISING_ID.resetToDefault();
        installed(null);
    }

    @Test public void readersGetTheDeletedIdAnswerWhileTheSwitchIsOn() {
        assertEquals("00000000-0000-0000-0000-000000000000", AdvertisingId.id(REAL));
        assertEquals(AdvertisingId.ZERO, AdvertisingId.id(null));
        assertTrue(AdvertisingId.limitTracking(false));
        assertTrue(AdvertisingId.limitTracking(true));
    }

    @Test public void pausedDisabledColdStartAndMissingBuildsHandBackTheRealAnswer() throws ReflectiveOperationException {
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        assertEquals(REAL, AdvertisingId.id(REAL));
        assertFalse(AdvertisingId.limitTracking(false));
        PauseForTests.resume();
        Settings.HIDE_ADVERTISING_ID.save(false);
        assertEquals(REAL, AdvertisingId.id(REAL));
        assertNull(AdvertisingId.id(null));
        assertFalse(AdvertisingId.limitTracking(false));
        Settings.HIDE_ADVERTISING_ID.save(true);
        SettingsContextRule.withoutContext(() -> assertEquals(REAL, AdvertisingId.id(REAL)));
        installed(false);
        assertEquals(REAL, AdvertisingId.id(REAL));
        assertFalse(AdvertisingId.limitTracking(false));
    }

    @Test public void theBrowserIdRecordReadsAsMissingAndItsSaveBecomesPinterestsDelete() {
        assertTrue(AdvertisingId.skipBrowserId("pid"));
        assertSame(DELETED, AdvertisingId.saveBrowserId(STORE, "pid", RECORD, CONTINUATION));
        assertEquals(1, deletes.size());
        assertSame(STORE, deletes.get(0)[0]);
        assertEquals(Collections.singletonList("pid"), deletes.get(0)[1]);
        assertSame(CONTINUATION, deletes.get(0)[2]);
        String report = String.join("
", HookStatus.report());
        assertTrue(report, report.contains("browser ID read skipped 1"));
        assertTrue(report, report.contains("browser ID save deleted 1"));
    }

    @Test public void otherKeysAndCoroutineResumesTakePinterestsOwnPath() {
        for (String key : new String[]{null, "other", "PID", ""}) {
            assertFalse(String.valueOf(key), AdvertisingId.skipBrowserId(key));
            assertNull(String.valueOf(key), AdvertisingId.saveBrowserId(STORE, key, RECORD, CONTINUATION));
        }
        // A resume passes null for every object argument.
        assertNull(AdvertisingId.saveBrowserId(null, null, null, CONTINUATION));
        assertEquals(0, deletes.size());
        String report = String.join("
", HookStatus.report());
        assertFalse(report, report.contains("browser ID"));
    }

    @Test public void pausedDisabledColdStartAndMissingBuildsLeaveTheBrowserIdRecordAlone() throws ReflectiveOperationException {
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        assertFalse(AdvertisingId.skipBrowserId("pid"));
        assertNull(AdvertisingId.saveBrowserId(STORE, "pid", RECORD, CONTINUATION));
        PauseForTests.resume();
        Settings.HIDE_ADVERTISING_ID.save(false);
        assertFalse(AdvertisingId.skipBrowserId("pid"));
        assertNull(AdvertisingId.saveBrowserId(STORE, "pid", RECORD, CONTINUATION));
        Settings.HIDE_ADVERTISING_ID.save(true);
        SettingsContextRule.withoutContext(() -> {
            assertFalse(AdvertisingId.skipBrowserId("pid"));
            assertNull(AdvertisingId.saveBrowserId(STORE, "pid", RECORD, CONTINUATION));
        });
        installed(false);
        assertFalse(AdvertisingId.skipBrowserId("pid"));
        assertNull(AdvertisingId.saveBrowserId(STORE, "pid", RECORD, CONTINUATION));
        assertEquals(0, deletes.size());
    }

    @Test public void aDeleteThatThrowsFallsBackToPinterestsSaveAndIsReported() {
        AdvertisingId.deleteForTests = (store, keys, continuation) -> {
            throw new IllegalStateException("no Block Store");
        };
        assertNull(AdvertisingId.saveBrowserId(STORE, "pid", RECORD, CONTINUATION));
        String report = String.join("
", HookStatus.report());
        assertTrue(report, report.contains("browser ID save"));
        assertFalse(report, report.contains("browser ID save deleted"));
    }

    @Test public void theUnpatchedDeleteStubAnswersNothingSoPinterestsSaveRuns() {
        AdvertisingId.deleteForTests = null;
        assertNull(AdvertisingId.saveBrowserId(STORE, "pid", RECORD, CONTINUATION));
        assertFalse(String.join("
", HookStatus.report()).contains("browser ID save deleted"));
    }

    private static void installed(Boolean present) throws ReflectiveOperationException {
        Field field = PatchFamily.class.getDeclaredField("inBuildForTests");
        field.setAccessible(true);
        field.set(null, present == null ? null : present ? EnumSet.of(PatchFamily.HIDE_ADVERTISING_ID) : Collections.emptySet());
    }
}
