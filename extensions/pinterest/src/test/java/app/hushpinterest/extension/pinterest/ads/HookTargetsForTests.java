/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ads;

import java.util.Map;

import app.hushpinterest.extension.pinterest.settings.PatchFamily;

/** Lets a test in another package say what the patch recorded for each capability's targets. */
public final class HookTargetsForTests {
    private HookTargetsForTests() {}

    /** The record for each capability, in the patch's own format, or null to read the stubs again. */
    public static void recorded(Map<PatchFamily.Capability, String> recorded) {
        HookTargets.recordedForTests = recorded;
    }
}
