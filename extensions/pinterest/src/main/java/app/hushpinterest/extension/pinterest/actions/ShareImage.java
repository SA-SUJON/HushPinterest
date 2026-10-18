/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/**
 * Share image (#11): sends a pin's full-size picture itself through Android's share menu, so a chat
 * or gallery app gets the image rather than a link. The picture is fetched the way Copy image fetches
 * it ({@link CopyImage#fetch}, same limits and checks) into Share image's own cache folder, and goes
 * out as {@code ACTION_SEND} with a read grant on a {@link SharePreviewProvider} address. The app
 * picked may read it a while later, so the newest few shared pictures stay.
 *
 * <p>Nothing here runs unless Download pins is installed and on, as for its other rows.
 */
final class ShareImage {
    private ShareImage() {}

    /** Shared pictures kept, the newest one included. */
    static final int KEEP = 3;

    /** One share at a time: a second tap while the first fetches is told so. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    /** Whether the Share image row may be offered or used. */
    static boolean active() {
        return PinDownloads.active() && PatchFamily.Capability.SHARE_IMAGE.installed() && Settings.SHARE_IMAGE.get();
    }

    /** True when the menu row should show for a pin: an image Download pin can save. */
    static boolean offered(PinMedia.Resolution media) {
        return active() && media != null && media.source != null && !media.source.mime.startsWith("video/");
    }

    /**
     * Starts a share of the picture [pin] carries, read again now. True when the work was queued, so
     * the menu may close. Every failure says so in a toast and opens no share menu.
     */
    static boolean start(Object pin, Context context) {
        if (!active()) return false;
        try {
            PinMedia.Resolution media = PinMedia.resolve(pin);
            if (media == null || context == null) return false;
            if (!offered(media)) {
                report(CopyImage.Result.FAILED);
                return false;
            }
            Context app = context.getApplicationContext();
            if (!SharePreviewProvider.serves(app)) {
                HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "share image provider missing");
                report(CopyImage.Result.FAILED);
                return false;
            }
            if (!RUNNING.compareAndSet(false, true)) {
                report(CopyImage.Result.BUSY);
                return false;
            }
            boolean queued = Utils.runOnBackgroundThread(() -> {
                CopyImage.Fetch made;
                try {
                    made = CopyImage.fetch(media, CopiedImages.sharedDir(app));
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "fetch image to share", failure);
                    made = new CopyImage.Fetch(CopyImage.Result.FAILED, null, null);
                }
                CopyImage.Fetch done = made;
                Utils.runOnMainThread(() -> {
                    boolean handed = false;
                    try {
                        handed = publish(app, done);
                    } finally {
                        // Deleting is disk work, so it goes back to a worker, and the next share waits for it.
                        boolean settled = handed;
                        if (!Utils.runOnBackgroundThread(() -> {
                            try {
                                settle(done, settled);
                            } finally {
                                RUNNING.set(false);
                            }
                        })) {
                            RUNNING.set(false);
                        }
                    }
                });
            });
            if (!queued) {
                RUNNING.set(false);
                report(CopyImage.Result.FAILED);
            }
            return queued;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "start image share", failure);
            report(CopyImage.Result.FAILED);
            return false;
        }
    }

    /**
     * Opens Android's share menu with a finished picture, or says why not. Main thread, and no disk
     * work: {@link #settle} deletes files afterwards on a worker. True when the menu was opened.
     */
    static boolean publish(Context app, CopyImage.Fetch fetched) {
        File file = fetched.file;
        if (fetched.result != CopyImage.Result.COPIED || file == null) {
            report(fetched.result);
            return false;
        }
        try {
            // Switched off or paused while it fetched: no share menu.
            if (!active()) return false;
            Uri uri = SharePreviewProvider.sharedUri(app, file.getName());
            if (uri == null || fetched.mime == null) throw new IllegalStateException("No share target");
            Intent send = new Intent(Intent.ACTION_SEND).setType(fetched.mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // The chooser passes the read grant on only for a URI it finds in the clip.
            send.setClipData(ClipData.newRawUri("", uri));
            app.startActivity(Intent.createChooser(send, L10n.t("Share image")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "image sent to Android share sheet");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "open image share sheet", failure);
            report(CopyImage.Result.FAILED);
            return false;
        }
    }

    /**
     * Worker threads only, after {@link #publish}. A picture handed to the share menu joins the newest
     * {@link #KEEP} in the folder and older ones go; one that never went out is deleted itself.
     */
    static void settle(CopyImage.Fetch fetched, boolean handed) {
        File file = fetched.file;
        if (file == null) return;
        try {
            File dir = file.getParentFile();
            if (handed) {
                if (dir != null) CopiedImages.keepNewest(dir, file.getName(), KEEP);
            } else if (file.exists() && !file.delete()) {
                file.deleteOnExit();
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "tidy shared images", failure);
        }
    }

    private static void report(CopyImage.Result result) {
        switch (result) {
            case TOO_LARGE:
                Utils.showToastLong(L10n.t("That image is too big to share."));
                break;
            case NOT_IMAGE:
                Utils.showToastLong(L10n.t("Pinterest didn't send a usable image."));
                break;
            case TIMED_OUT:
                Utils.showToastLong(L10n.t("Getting the image took too long. Try again."));
                break;
            case BUSY:
                Utils.showToastShort(L10n.t("Still getting the last image ready."));
                break;
            case FAILED:
                Utils.showToastLong(L10n.t("Couldn't share the image. Check your connection and try again."));
                break;
            default:
                break;
        }
    }
}
