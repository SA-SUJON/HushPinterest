/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/** Replaces the pin recipient picker with Android's ordinary text sharing sheet. */
public final class SystemShare {
    private SystemShare() {}

    public static boolean open(Object pin, Object source) {
        HookStatus.invoked(FamilyNames.SYSTEM_SHARE);
        return openUrl(PinMedia.pinUrl(pin), source);
    }

    public static boolean openSendable(Object sendable, Object source) {
        HookStatus.invoked(FamilyNames.SYSTEM_SHARE);
        return openUrl(sendablePinUrl(sendable), source);
    }

    private static boolean openUrl(String url, Object source) {
        if (!Utils.settingsReady() || !PatchFamily.Capability.PIN_SHARE.installed() ||
                !Settings.SYSTEM_SHARE.get()) return false;
        try {
            if (source instanceof Enum<?>) {
                String name = ((Enum<?>) source).name();
                if ("SCREENSHOT".equals(name) || "DOWNLOAD".equals(name)) return false;
            }
            Activity activity = Utils.getActivity();
            if (url == null || activity == null || activity.isFinishing() || activity.isDestroyed() ||
                    Looper.myLooper() != Looper.getMainLooper()) return false;
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, url);
            activity.startActivity(Intent.createChooser(send, L10n.t("Share pin")));
            HookStatus.counted(FamilyNames.SYSTEM_SHARE, "pin sent to Android share sheet");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SYSTEM_SHARE, "open share sheet", failure);
            return false;
        }
    }

    private static String sendablePinUrl(Object sendable) {
        try {
            if (sendable == null || sendableType(sendable) != 0) return null;
            String id = sendableId(sendable);
            if (id == null || !id.matches("[0-9]{1,30}")) return null;
            return "https://www.pinterest.com/pin/" + id + "/";
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SYSTEM_SHARE, "read sendable pin", failure);
            return null;
        }
    }

    /**
     * The sendable's id: the String its (String, int) constructor stores. The patch fills this in
     * with the target build's field, whose name and accessors change every build.
     */
    private static String sendableId(Object sendable) {
        return null;
    }

    /** The sendable's kind, 0 for a pin: the int its (String, int) constructor stores. Filled in when patching. */
    private static int sendableType(Object sendable) {
        return -1;
    }
}
