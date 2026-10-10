/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/** Adds a native Download row to the pin overflow menu, using media the pin already carries. */
public final class PinDownloads {
    private PinDownloads() {}

    static final String ROW_TAG = "hushpinterest_download_pin";
    private static final String DETAILS_TAG = "hushpinterest_pin_media_details";
    static final String COPY_TAG = "hushpinterest_copy_media_link";

    /** Rewritten by the patch to read the controller's private pin through a generated bridge. */
    private static Object menuPin(Object controller) { return null; }

    /** Rewritten to return Pinterest's native overflow menu layout. */
    private static ViewGroup menuView(Object controller) { return null; }

    private static View menuOrigin(Object controller) { return null; }

    private static boolean menuCloseup(Object controller) { return true; }

    /** Rewritten to read only the typed pin model held by a native grid cell. */
    static Object cellPin(View cell) { return null; }

    /** Rewritten to call the native row factory with Pinterest's Download icon. */
    private static View menuRow(ViewGroup layout, String title) { return null; }

    /** Rewritten to call the menu presenter's own dismissal event through a generated bridge. */
    private static void dismissMenu(Object controller) {}

    static boolean active() {
        return Utils.settingsReady() && PatchFamily.Capability.PIN_DOWNLOADS.installed() && Settings.DOWNLOAD_PINS.get();
    }

    public static void attach(Object controller) {
        HookStatus.invoked(FamilyNames.DOWNLOAD_PINS);
        if (!active()) return;
        try {
            Object pin = menuPin(controller);
            ViewGroup layout = menuView(controller);
            PinMedia.Resolution media = PinMedia.resolve(pin);
            if (layout == null || layout.findViewWithTag(ROW_TAG) != null || media == null) return;
            boolean supported = media.source != null;
            String title = supported ? L10n.t("Download pin") : L10n.t("Download unavailable");
            View row = menuRow(layout, title);
            if (row == null) return;
            row.setTag(ROW_TAG);
            row.setFocusable(true);
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            row.setContentDescription(supported ? title : title + ". " + refusalMessage(media.refusal));
            row.setOnClickListener(ignored -> {
                if (!active()) return;
                if (!supported) { showDetails(media); return; }
                try {
                    if (start(pin, layout.getContext())) dismissMenu(controller);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "dismiss download menu", failure);
                }
            });
            layout.addView(row, 0);
            ViewGroup grid = menuCloseup(controller) ? null : GridDownloads.grid(menuOrigin(controller), pin);
            if (grid != null) {
                String batchTitle = L10n.t("Download visible pins");
                View batch = menuRow(layout, batchTitle);
                if (batch != null) {
                    batch.setFocusable(true);
                    batch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    batch.setContentDescription(batchTitle);
                    batch.setOnClickListener(ignored -> {
                        if (active() && GridDownloads.grid(grid, pin) == grid && GridDownloads.show(grid)) dismissMenu(controller);
                    });
                    layout.addView(batch, 1);
                }
            }
            if (supported) {
                String detailsTitle = L10n.t("Supplied media details");
                View details = menuRow(layout, detailsTitle);
                if (details != null) {
                    details.setTag(DETAILS_TAG);
                    details.setFocusable(true);
                    details.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    details.setContentDescription(detailsTitle);
                    details.setOnClickListener(ignored -> { if (active()) showDetails(media); });
                    layout.addView(details, 1);
                }
                String copyTitle = L10n.t("Copy media link");
                View copy = menuRow(layout, copyTitle);
                if (copy != null) {
                    copy.setTag(COPY_TAG);
                    copy.setFocusable(true);
                    copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    copy.setContentDescription(copyTitle);
                    copy.setOnClickListener(ignored -> {
                        try {
                            if (copyLink(pin, layout.getContext())) dismissMenu(controller);
                        } catch (Throwable failure) {
                            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "dismiss copy menu", failure);
                        }
                    });
                    layout.addView(copy, 1);
                }
            }
            HookStatus.counted(FamilyNames.DOWNLOAD_PINS, supported ? "download row added to pin menu" : "download explanation added to pin menu");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "attach download row", failure);
        }
    }

    private static void showDetails(PinMedia.Resolution media) {
        try {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                throw new IllegalStateException("Activity unavailable");
            }
            String text = L10n.t("These details come from the link and file information Pinterest gave the app. "
                + "HushPinterest hasn't opened the file to check them.")
                    + "\n\n" + L10n.f("Supplied width: %s", media.width == null ? L10n.t("Unknown") : L10n.f("%d pixels", media.width))
                    + "\n" + L10n.f("Supplied height: %s", media.height == null ? L10n.t("Unknown") : L10n.f("%d pixels", media.height))
                    + "\n" + L10n.f("Link type: %s", media.urlType == null ? L10n.t("Unknown") : media.urlType);
            if (media.source != null && media.size != null) text += "\n" + (PinMedia.standIn(media)
                    ? L10n.f("Supplied size: %s. Downloads look for the original first.", media.size)
                    : L10n.t("Supplied size: the original image"));
            if (media.refusal != null) text = refusalMessage(media.refusal) + "\n\n" + text;
            new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("Supplied media details"))
                    .setMessage(text)
                    .setPositiveButton(L10n.t("Close"), (dialog, which) -> dialog.dismiss())
                    .show();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "show supplied media details", failure);
            Utils.showToastLong(L10n.t("Couldn't show the supplied media details. Open the pin again and try again."));
        }
    }

    private static String refusalMessage(PinMedia.Refusal refusal) {
        switch (refusal) {
            case ADAPTIVE_VIDEO: return L10n.t("Pinterest only offered a streaming video for this pin, not a file "
                + "that can be downloaded.");
            case MP4_MISSING: return L10n.t("Pinterest didn't offer a downloadable video file for this pin.");
            case IMAGE_MISSING: return L10n.t("Pinterest hasn't supplied an image to download.");
            case IMAGE_TYPE: return L10n.t("HushPinterest can't download this type of image.");
            case PUBLIC_LINK: return L10n.t("This media link isn't a public Pinterest link HushPinterest can use.");
            default: throw new IllegalArgumentException("Unknown media refusal");
        }
    }

    static boolean start(Object pin, Context context) {
        return start(pin, context, null);
    }

    /**
     * Copies the address of the media the Download row would save, read from the pin again: a
     * supplied original or MP4, or for a stand-in size the original the media host has behind it,
     * else that size. Never an address off Pinterest's media host.
     */
    static boolean copyLink(Object pin, Context context) {
        if (!active()) return false;
        try {
            PinMedia.Resolution media = PinMedia.resolve(pin);
            if (media == null || context == null) return false;
            if (media.source == null) {
                Utils.showToastLong(refusalMessage(media.refusal));
                return false;
            }
            if (!PinMedia.standIn(media)) return copy(context, media.source.url);
            PinMedia.Source standIn = media.source;
            // The lookup asks the network, so the menu closes now and the copy follows it.
            boolean queued = Utils.runOnBackgroundThread(() -> {
                PinMedia.Source chosen = OriginalLookup.find(standIn);
                Utils.runOnMainThread(() -> {
                    try {
                        if (active()) copy(context, chosen.url);
                    } catch (Throwable failure) {
                        copyFailed(failure);
                    }
                });
            });
            return queued || copy(context, standIn.url);
        } catch (Throwable failure) {
            copyFailed(failure);
            return false;
        }
    }

    private static boolean copy(Context context, String url) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(L10n.t("Media link"), url));
        HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "media link copied");
        // Android 13 and newer show their own confirmation for every copy.
        if (Build.VERSION.SDK_INT < 33) Utils.showToastShort(L10n.t("Media link copied."));
        return true;
    }

    private static void copyFailed(Throwable failure) {
        HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "copy media link", failure);
        Utils.showToastLong(L10n.t("Couldn't copy the media link. Open the pin again and try again."));
    }

    enum Result { QUEUED, QUEUED_UNTRACKED, SKIPPED, UNSUPPORTED, FAILED }

    /** The callback reports the actual native enqueue outcome, not worker submission. */
    static boolean start(Object pin, Context context, Consumer<Result> listener) {
        AtomicBoolean reported = new AtomicBoolean();
        Consumer<Result> after = listener == null ? null : result -> {
            if (reported.compareAndSet(false, true)) listener.accept(result);
        };
        if (!active()) { report(after, Result.SKIPPED); return false; }
        try {
            PinMedia.Resolution media = PinMedia.resolve(pin);
            if (media == null || context == null) { report(after, Result.SKIPPED); return false; }
            PinMedia.Source source = media.source;
            if (source == null) {
                if (after == null) Utils.showToastLong(refusalMessage(media.refusal));
                report(after, Result.UNSUPPORTED);
                return false;
            }
            String id = media.id;
            boolean standIn = PinMedia.standIn(media);
            Context app = context.getApplicationContext();
            boolean queued = Utils.runOnBackgroundThread(() -> {
                if (!active()) { report(after, Result.SKIPPED); return; }
                try {
                    PinMedia.Source chosen = standIn ? OriginalLookup.find(source) : source;
                    if (!active()) { report(after, Result.SKIPPED); return; }
                    DownloadManager manager = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
                    if (manager == null) throw new IllegalStateException("Download service unavailable");
                    long request = manager.enqueue(request(chosen, fileName(id, chosen)));
                    if (request < 0) throw new IllegalStateException("Download service rejected pin");
                    boolean tracked = DownloadLedger.record(app, request, id);
                    HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "pin queued in Downloads");
                    report(after, tracked ? Result.QUEUED : Result.QUEUED_UNTRACKED);
                    if (after == null) Utils.showToastLong(L10n.t(tracked ? "Download started. Check Downloads."
                            : "Download started, but its history couldn't be saved. Check Downloads."));
                } catch (Throwable failure) {
                    failed(QUEUE_FAILED, failure);
                    report(after, Result.FAILED);
                }
            });
            if (!queued) {
                failed(QUEUE_FAILED, new IllegalStateException("Worker unavailable"));
                report(after, Result.FAILED);
            }
            return queued;
        } catch (Throwable failure) {
            failed("start pin download", failure);
            report(after, Result.FAILED);
            return false;
        }
    }

    private static String fileName(String id, PinMedia.Source source) {
        return "Pinterest_" + id + "_" + Long.toUnsignedString(System.nanoTime()) + source.suffix;
    }

    private static void report(Consumer<Result> after, Result result) {
        if (after != null) Utils.runOnMainThread(() -> after.accept(result));
    }

    static DownloadManager.Request request(PinMedia.Source source, String fileName) {
        if (PinMedia.mediaUri(source.url) == null) throw new IllegalArgumentException("Not a public Pinterest media URL");
        return new DownloadManager.Request(Uri.parse(source.url))
                .setMimeType(source.mime)
                .setTitle(L10n.t("Download pin"))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
    }

    /** The diagnostics name for a download Android wouldn't queue; its toast says to try again. */
    private static final String QUEUE_FAILED = "queue pin download";

    private static void failed(String action, Throwable failure) {
        HookStatus.threw(FamilyNames.DOWNLOAD_PINS, action, failure);
        Utils.showToastLong(action.equals(QUEUE_FAILED)
                ? L10n.t("Couldn't start the download. Check system Downloads and try again.")
                : L10n.t("Couldn't prepare this pin for download. Open the pin again and try again."));
    }
}
