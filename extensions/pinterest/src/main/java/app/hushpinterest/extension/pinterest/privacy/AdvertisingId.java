/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.privacy;

import java.util.Collections;
import java.util.List;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/**
 * Filters what Google's advertising ID info object hands to every reader in the app. While the
 * switch is on, readers get the answer Android gives after you delete your ad ID: all zeros, with
 * ad tracking limited.
 *
 * <p>The same switch keeps Pinterest's browser ID, its {@code _b} cookie, out of Google's Block
 * Store. Pinterest keeps that cookie there in a record of its own, which outlives an uninstall
 * and can ride a cloud backup to a new phone, so a reinstall would send the old ID before anyone
 * signs in. While the switch is on, Pinterest's read of that record finds nothing, and its save
 * becomes Pinterest's own delete of the record, so a record already there goes the next time
 * Pinterest tries to save one.
 */
public final class AdvertisingId {
    private AdvertisingId() {}

    static final String ZERO = "00000000-0000-0000-0000-000000000000";

    /** The Block Store key of Pinterest's browser ID record. */
    static final String BROWSER_ID = "pid";

    /** Pinterest's Block Store delete as a test stands in for it: the store, the keys, the continuation. */
    interface BrowserIdDelete {
        Object delete(Object store, List<String> keys, Object continuation);
    }

    /** What a test stands in for the rewritten delete with. Null calls the stub the patch writes. */
    static volatile BrowserIdDelete deleteForTests;

    private static boolean active() {
        try {
            return Utils.settingsReady() && PatchFamily.HIDE_ADVERTISING_ID.inBuild() && Settings.HIDE_ADVERTISING_ID.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_ADVERTISING_ID, "switch read", failure);
            return false;
        }
    }

    /** Placed before the ID getter's return. */
    public static String id(String original) {
        HookStatus.invoked(FamilyNames.HIDE_ADVERTISING_ID);
        if (!active()) return original;
        HookStatus.counted(FamilyNames.HIDE_ADVERTISING_ID, "advertising ID read");
        return ZERO;
    }

    /** Placed before the limit-tracking getter's return. */
    public static boolean limitTracking(boolean original) {
        HookStatus.invoked(FamilyNames.HIDE_ADVERTISING_ID);
        if (!active()) return original;
        HookStatus.counted(FamilyNames.HIDE_ADVERTISING_ID, "ad tracking limit read");
        return true;
    }

    /**
     * Placed at the head of Pinterest's Block Store read, with the key it reads. True has the read
     * answer that there's no record, the same answer a phone that never had Pinterest gets. Any
     * other key reads as it always did, and so does a coroutine resume, which passes no key.
     */
    public static boolean skipBrowserId(String key) {
        HookStatus.invoked(FamilyNames.HIDE_ADVERTISING_ID);
        try {
            if (!BROWSER_ID.equals(key) || !active()) return false;
            HookStatus.counted(FamilyNames.HIDE_ADVERTISING_ID, "browser ID read skipped");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_ADVERTISING_ID, "browser ID read", failure);
            return false;
        }
    }

    /**
     * Placed at the head of Pinterest's Block Store save, with the store, the key, the record and
     * the coroutine's continuation. For the browser ID it answers what Pinterest's own delete of
     * that key answers, on the same continuation, so nothing is saved and a record already there
     * goes. Null runs Pinterest's save: any other key, a coroutine resume (which passes no key),
     * the switch off or HushPinterest paused.
     */
    public static Object saveBrowserId(Object store, String key, byte[] record, Object continuation) {
        HookStatus.invoked(FamilyNames.HIDE_ADVERTISING_ID);
        try {
            if (!BROWSER_ID.equals(key) || !active()) return null;
            Object answer = delete(store, Collections.singletonList(key), continuation);
            if (answer != null) HookStatus.counted(FamilyNames.HIDE_ADVERTISING_ID, "browser ID save deleted");
            return answer;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_ADVERTISING_ID, "browser ID save", failure);
            return null;
        }
    }

    private static Object delete(Object store, List<String> keys, Object continuation) {
        BrowserIdDelete forced = deleteForTests;
        return forced != null ? forced.delete(store, keys, continuation) : deleteBrowserId(store, keys, continuation);
    }

    /**
     * Rewritten to hand [keys] to Pinterest's own Block Store delete on [store], on the save's
     * [continuation], and return what the delete returns: its Boolean, or the coroutine's
     * suspended marker while Block Store answers.
     */
    private static Object deleteBrowserId(Object store, List<String> keys, Object continuation) { return null; }
}
