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
 * Modified for Hushfacebook (Facebook), 2026.
 * Forked from MorpheApp/morphe-patches (GPL-3.0), by way of
 * icysymmetra/tiktok-patches-for-morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Imported carrying no notice of its own. Morphe hard forked ReVanced, so parts of
 * this file may originate there.
 */
package app.hushpinterest.extension.shared.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static app.hushpinterest.extension.shared.settings.Setting.parent;

import app.hushpinterest.extension.shared.Logger;

/**
 * Settings shared across multiple apps.
 * <p>
 * To ensure this class is loaded when the UI is created, app specific setting bundles should extend
 * or reference this class.
 */
public class BaseSettings {
    public static final BooleanSetting DEBUG = new BooleanSetting("morphe_debug", FALSE);
    public static final StringSetting DEBUG_LOG_FILTERS = new StringSetting("morphe_debug_log_filters", "all", false, false);
    public static final BooleanSetting CAPTURE_JAVA_CRASHES =
            new BooleanSetting("morphe_capture_java_crashes", FALSE, true);
    public static final BooleanSetting DEBUG_STACKTRACE = new BooleanSetting("morphe_debug_stacktrace", FALSE, parent(DEBUG));
    /**
     * Whether a failure inside the bundle is also put on screen while {@link #DEBUG} is on.
     * No confirmation dialog: the message it carried named a string resource this bundle does
     * not ship, and the row that now exposes the switch would have raised an empty dialog.
     */
    public static final BooleanSetting DEBUG_TOAST_ON_ERROR = new BooleanSetting("morphe_debug_toast_on_error", TRUE);

    public static final IntegerSetting CHECK_ENVIRONMENT_WARNINGS_ISSUED = new IntegerSetting("morphe_check_environment_warnings_issued", 0, true, false);

    public static final EnumSetting<AppLanguage> MORPHE_LANGUAGE = new EnumSetting<>("morphe_language", AppLanguage.DEFAULT, true, "morphe_language_user_dialog_message");

    /**
     * Use the icons declared in the preferences created during patching. If no icons or styles are declared then this setting does nothing.
     */
    public static final BooleanSetting SHOW_MENU_ICONS = new BooleanSetting("morphe_show_menu_icons", TRUE, true);

    /**
     * The first time the app was launched with no previous app data (either a clean install, or after wiping app data).
     */
    public static final LongSetting FIRST_TIME_APP_LAUNCHED = new LongSetting("morphe_last_time_app_was_launched", -1L, false, false);

    public static final StringSetting EXPERIMENTAL_APP_CONFIRMED = new StringSetting("morphe_experimental_app_target_confirmed", "", false, false);

    /**
     * Pause HushPinterest: from the next start every hook a setting controls takes Pinterest's own
     * path, and every saved value stays as it is. Edits made at patch time have no setting to ask
     * and stay in. Left out of backups, so restoring one never pauses a phone by surprise.
     */
    public static final BooleanSetting PAUSED = new BooleanSetting("hushpinterest_paused", FALSE, true, false, null, null);

    /** Set by {@link HushPinterestPause} after three starts in a row crashed within a minute. */
    public static final BooleanSetting SAFE_MODE = new BooleanSetting("hushpinterest_safe_mode", FALSE, true, false, null, null);

    /**
     * The language of HushPinterest's own text: {@link AppLanguage#DEFAULT} follows Pinterest, and
     * {@link AppLanguage#EN} reads English whatever Pinterest is in. Only {@code L10n} reads it, so
     * Pinterest's own language and {@link #MORPHE_LANGUAGE}, which changes the context Pinterest's
     * strings are read from, are left alone. It takes effect on the next string built, so no
     * restart, and it stays out of settings files: a file carries switches.
     */
    public static final EnumSetting<AppLanguage> HUSH_LANGUAGE =
            new EnumSetting<>("hushpinterest_language", AppLanguage.DEFAULT, false, false);

    /**
     * Whether the Quick setups row is off the settings home page. A long press on that row hides
     * it, and a row on the Pause, backup and diagnostics page brings it back. Like the language,
     * it's the screen's own state: no restart, and it stays out of settings files and Quick setups.
     */
    public static final BooleanSetting HIDE_QUICK_SETUPS =
            new BooleanSetting("hushpinterest_hide_quick_setups", FALSE, false, false, null, null);

    static {
        // HushPinterest's own state and its diagnostics, which keep working while it is paused.
        Setting.keepWhenPaused(DEBUG, DEBUG_LOG_FILTERS, CAPTURE_JAVA_CRASHES, DEBUG_STACKTRACE,
                DEBUG_TOAST_ON_ERROR, CHECK_ENVIRONMENT_WARNINGS_ISSUED, MORPHE_LANGUAGE, SHOW_MENU_ICONS,
                FIRST_TIME_APP_LAUNCHED, EXPERIMENTAL_APP_CONFIRMED, PAUSED, SAFE_MODE, HUSH_LANGUAGE,
                HIDE_QUICK_SETUPS);

        final long now = System.currentTimeMillis();

        if (FIRST_TIME_APP_LAUNCHED.savedValue() < 0) {
            Logger.printInfo(() -> "First launch of installation with no prior app data");
            FIRST_TIME_APP_LAUNCHED.save(now);
        }
    }
}
