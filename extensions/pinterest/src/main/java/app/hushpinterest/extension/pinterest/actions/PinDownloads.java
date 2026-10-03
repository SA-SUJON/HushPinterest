/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;

import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final String SAVE_TAG = "hushpinterest_save_pin";
    private static final AtomicBoolean SAVING = new AtomicBoolean();

    /** Rewritten by the patch to read the controller's private pin through a generated bridge. */
    private static Object menuPin(Object controller) { return null; }

    /** Rewritten to return Pinterest's native overflow menu layout. */
    private static ViewGroup menuView(Object controller) { return null; }

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
            if (layout == null || layout.findViewWithTag(ROW_TAG) != null || PinMedia.source(pin) == null) return;
            View row = menuRow(layout, L10n.t("Download pin"));
            if (row == null) return;
            row.setTag(ROW_TAG);
            row.setOnClickListener(ignored -> {
                if (!active()) return;
                try {
                    if (start(pin, layout.getContext())) dismissMenu(controller);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "dismiss download menu", failure);
                }
            });
            layout.addView(row, 0);
            HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "download row added to pin menu");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "attach download row", failure);
        }
    }

    static boolean start(Object pin, Context context) {
        if (!active()) return false;
        try {
            PinMedia.Source source = PinMedia.source(pin);
            String id = PinMedia.id(pin);
            if (source == null || id == null || context == null) return false;
            String fileName = "Pinterest_" + id + "_" + Long.toUnsignedString(System.nanoTime()) + source.suffix;
            Context app = context.getApplicationContext();
            if (Build.VERSION.SDK_INT >= 29) {
                boolean queued = Utils.runOnBackgroundThread(() -> {
                    if (!active()) return;
                    try {
                        DownloadManager manager = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
                        if (manager == null) throw new IllegalStateException("Download service unavailable");
                        long request = manager.enqueue(request(source, fileName));
                        if (request < 0) throw new IllegalStateException("Download service rejected pin");
                        HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "pin queued in Downloads");
                        Utils.showToastLong(L10n.t("Download started. Check Downloads."));
                    } catch (Throwable failure) {
                        failed("queue pin download", failure);
                    }
                });
                if (!queued) failed("queue pin download", new IllegalStateException("Worker unavailable"));
                return queued;
            }
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed() ||
                    activity.getFragmentManager().isStateSaved()) {
                failed("open save location", new IllegalStateException("Activity unavailable"));
                return false;
            }
            if (activity.getFragmentManager().findFragmentByTag(SAVE_TAG) != null || !SAVING.compareAndSet(false, true)) {
                Utils.showToastLong(L10n.t("Another pin is being saved. Try again when it's finished."));
                return false;
            }
            Bundle args = new Bundle();
            args.putString("url", source.url);
            args.putString("mime", source.mime);
            args.putString("suffix", source.suffix);
            args.putString("name", fileName);
            SaveFragment fragment = new SaveFragment();
            fragment.setArguments(args);
            try {
                activity.getFragmentManager().beginTransaction().add(fragment, SAVE_TAG).commitNow();
                return true;
            } catch (Throwable failure) {
                SAVING.set(false);
                throw failure;
            }
        } catch (Throwable failure) {
            failed("start pin download", failure);
            return false;
        }
    }

    static DownloadManager.Request request(PinMedia.Source source, String fileName) {
        if (PinMedia.mediaUri(source.url) == null) throw new IllegalArgumentException("Not a public Pinterest media URL");
        return new DownloadManager.Request(Uri.parse(source.url))
                .setMimeType(source.mime)
                .setTitle(L10n.t("Download pin"))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
    }

    private static void failed(String action, Throwable failure) {
        HookStatus.threw(FamilyNames.DOWNLOAD_PINS, action, failure);
        Utils.showToastLong(L10n.t("Couldn't save this pin."));
    }

    static void failedDocument(Context app, Uri destination, Throwable failure) {
        if (failure instanceof PinTransfer.SaveFailure && ((PinTransfer.SaveFailure) failure).incomplete) {
            failed("save pin document", failure);
            removeDocument(app, destination);
        } else {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "save pin document", failure);
            Utils.showToastLong(L10n.t("The file may have saved. Check your chosen save location."));
        }
    }

    private static void removeDocument(Context app, Uri destination) {
        try {
            if (!"content".equals(destination.getScheme())) throw new IllegalArgumentException("Save location is not a document");
            if (!DocumentsContract.deleteDocument(app.getContentResolver(), destination)) {
                throw new IllegalStateException("Document provider refused deletion");
            }
        } catch (Exception cannotDelete) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "remove incomplete document", cannotDelete);
            Utils.showToastLong(L10n.t("Couldn't remove the incomplete file. Check your chosen save location."));
        }
    }

    /** A refused transfer still removes the empty file the save picker just created. */
    private static void discardDocument(Context app, Uri destination) {
        Runnable cleanup = () -> {
            try { removeDocument(app, destination); }
            finally { SAVING.set(false); }
        };
        // One save may be pending. If the shared queue is full, this short cleanup gets its own
        // daemon so a remote document provider can never block the UI thread.
        if (!Utils.runOnBackgroundThread(cleanup)) {
            try {
                Thread cleanupThread = new Thread(cleanup, "HushPinterest document cleanup");
                cleanupThread.setDaemon(true);
                cleanupThread.start();
            } catch (RuntimeException unavailable) {
                SAVING.set(false);
                HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "schedule document cleanup", unavailable);
            }
        }
    }

    /** Android 9 grants access only to the file the person chooses in the system save dialog. */
    @SuppressWarnings("deprecation")
    public static final class SaveFragment extends Fragment {
        private static final int SAVE = 48122;
        private boolean launched;
        private boolean transferring;

        @Override public void onCreate(Bundle saved) {
            super.onCreate(saved);
            launched = saved != null && saved.getBoolean("launched");
            SAVING.set(true);
        }

        @Override public void onSaveInstanceState(Bundle saved) {
            super.onSaveInstanceState(saved);
            saved.putBoolean("launched", launched);
        }

        @Override public void onDestroy() {
            Activity activity = getActivity();
            if (!transferring && (isRemoving() || (activity != null && activity.isFinishing()))) SAVING.set(false);
            super.onDestroy();
        }

        @Override public void onResume() {
            super.onResume();
            if (launched) return;
            launched = true;
            try {
                if (!active() || getArguments() == null) { finish(); return; }
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(getArguments().getString("mime"))
                        .putExtra(Intent.EXTRA_TITLE, getArguments().getString("name")), SAVE);
            } catch (Throwable failure) {
                finish();
                failed("open save location", failure);
            }
        }

        @Override public void onActivityResult(int request, int result, Intent data) {
            super.onActivityResult(request, result, data);
            if (request != SAVE) return;
            Context app = getActivity() == null ? null : getActivity().getApplicationContext();
            Uri destination = data == null ? null : data.getData();
            Bundle args = getArguments();
            if (result != Activity.RESULT_OK || destination == null || app == null || args == null) {
                finish();
                return;
            }
            transferring = true;
            if (!active()) {
                discardDocument(app, destination);
                remove();
                return;
            }
            PinMedia.Source source = new PinMedia.Source(args.getString("url"), args.getString("mime"), args.getString("suffix"));
            try {
                boolean queued = Utils.runOnBackgroundThread(() -> {
                    try {
                        if (!active()) {
                            removeDocument(app, destination);
                            return;
                        }
                        PinTransfer.save(app, destination, source.url);
                        HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "pin saved to chosen document");
                        Utils.showToastLong(L10n.t("Pin saved."));
                    } catch (Throwable failure) {
                        failedDocument(app, destination, failure);
                    } finally {
                        SAVING.set(false);
                    }
                });
                // Removing the fragment doesn't revoke the provider grant; the worker uses this URI.
                remove();
                if (!queued) {
                    discardDocument(app, destination);
                    failed("save pin document", new IllegalStateException("Worker unavailable"));
                }
            } catch (Throwable failure) {
                discardDocument(app, destination);
                remove();
                failed("save pin document", failure);
            }
        }

        private void remove() {
            if (getFragmentManager() != null) getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        }

        private void finish() {
            SAVING.set(false);
            remove();
        }
    }
}
