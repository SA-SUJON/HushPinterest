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
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.hushpinterest.extension.pinterest.settings;

/**
 * Which patches and individual targets this build carries.
 *
 * <p>Every method answers false here. A patch that adds a feature rewrites its method to answer
 * true, so the settings screen offers only the switches this APK backs and the diagnostic report
 * lists only the patches it carries. Target flags are set only after their hook was inserted.
 * These are build facts, not preferences: Pause, switch changes and settings imports don't alter
 * them.
 */
@SuppressWarnings({"unused", "SameReturnValue"})
public final class SettingsStatus {
    private SettingsStatus() {
    }

    public static boolean hideAds() { return false; }
    public static boolean feedAds() { return false; }
    public static boolean adViews() { return false; }

    public static boolean hideAiPins() { return false; }
    public static boolean feedAiPins() { return false; }
}
