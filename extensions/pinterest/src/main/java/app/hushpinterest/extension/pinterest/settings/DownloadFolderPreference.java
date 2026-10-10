/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.Preference;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Button;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import app.hushpinterest.extension.pinterest.actions.DownloadFolder;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.Logger;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.settings.Setting;

/**
 * Pick a download folder (#5): opens Android's folder picker, and downloads go to the folder picked
 * there. Once one is picked, a tap offers another folder or Download again.
 *
 * <p>The picker is an activity of its own, so the answer comes back through the page's
 * onActivityResult, the way Export and Import settings get theirs. Nothing about a request is kept
 * on the page: its code says what it was, and the folder comes with the result.
 */
@SuppressWarnings("deprecation") // Framework preferences are what the shared settings page builds on.
public final class DownloadFolderPreference extends Preference {
    static final int PICK = 7314;

    /** The rows on the page right now, so a picked folder or the Pinterest folder switch shows at once. */
    private static final List<WeakReference<DownloadFolderPreference>> ROWS = new CopyOnWriteArrayList<>();

    @Nullable private static SharedPreferences observedStore;
    private static final SharedPreferences.OnSharedPreferenceChangeListener LISTENER = (store, key) -> {
        if (key == null || key.equals(Settings.SAVE_IN_PINTEREST_FOLDER.key) || key.equals(DownloadFolder.PICKED.key)) {
            Utils.runOnMainThread(DownloadFolderPreference::refreshRows);
        }
    };

    private final HushPinterestPreferenceFragment page;

    DownloadFolderPreference(HushPinterestPreferenceFragment page, Context context) {
        super(HushPinterestPreferenceFragment.themed(context));
        this.page = page;
        setKey("action_download_folder");
        setPersistent(false);
        setTitle(L10n.t(context, "Pick a download folder"));
        setOnPreferenceClickListener(preference -> {
            tapped();
            return true;
        });
        ROWS.add(new WeakReference<>(this));
        observe();
    }

    @Override public CharSequence getSummary() {
        return summary();
    }

    @Override protected void onBindView(View view) {
        super.onBindView(view);
        HushPinterestPreferenceFragment.showAllText(view);
        ScreenColors.row(view, this);
        view.setAccessibilityDelegate(new HushPinterestPreferenceFragment.RowSemantics(this, Button.class));
    }

    /** What the row says: no folder yet, the folder in use, or a folder the Pinterest folder switch overrides. */
    static String summary() {
        String picked = DownloadFolder.picked();
        if (picked == null) {
            return L10n.t("Saves downloads in a folder you pick inside Pictures, DCIM, Download or Documents.");
        }
        if (DownloadFolder.PINTEREST.equals(DownloadFolder.current()) && !DownloadFolder.PINTEREST.equals(picked)) {
            return L10n.f("%s is picked, but Save in Pinterest's folder is on, so downloads go there.", picked);
        }
        return L10n.f("Saving downloads in %s. Tap to pick another folder or go back to Download.", picked);
    }

    private void tapped() {
        if (DownloadFolder.picked() == null) {
            pick(page);
            return;
        }
        try {
            AlertDialog dialog = new AlertDialog.Builder(HushPinterestPreferenceFragment.themed(getContext()))
                    .setTitle(getTitle())
                    .setItems(new CharSequence[]{L10n.t("Pick another folder"), L10n.t("Save in Download again")}, (shown, which) -> {
                        if (which == 0) pick(page);
                        else forget();
                    })
                    .setNegativeButton(L10n.t("Back"), null)
                    .show();
            ScreenColors.dialog(dialog);
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Could not show the folder choices: " + failure.getClass().getSimpleName());
            pick(page);
        }
    }

    /** Opens Android's folder picker at Pictures. */
    static void pick(HushPinterestPreferenceFragment page) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Pictures"));
        try {
            page.startActivityForResult(intent, PICK);
        } catch (ActivityNotFoundException missing) {
            Logger.printInfo(() -> "No folder picker");
            Utils.showToastLong(L10n.t("This phone has no folder picker, so there's no way to choose a folder here."));
        } catch (RuntimeException error) {
            Logger.printInfo(() -> "Could not open the folder picker: " + error.getClass().getSimpleName());
            Utils.showToastLong(L10n.t("Couldn't open the folder picker. Try again."));
        }
    }

    /**
     * The folder picker's answer, from the page's onActivityResult.
     *
     * @return whether the request was this row's.
     */
    static boolean onResult(HushPinterestPreferenceFragment page, int request, int result, @Nullable Intent data) {
        if (request != PICK) return false;
        Uri tree = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (tree == null) return true; // Cancelled: nothing changes.
        String folder = DownloadFolder.fromTree(tree);
        if (folder == null) {
            Utils.showToastLong(L10n.t("Pick a folder inside Pictures, DCIM, Download or Documents on the phone's own storage."));
            return true;
        }
        if (folder.equals(DownloadFolder.DOWNLOAD)) {
            forget();
            return true;
        }
        DownloadFolder.PICKED.save(folder);
        // The folder just picked is the one people expect, so Pinterest's folder stops overriding it.
        if (Settings.SAVE_IN_PINTEREST_FOLDER.savedValue()) {
            Settings.SAVE_IN_PINTEREST_FOLDER.save(false);
            page.refreshSwitches();
        }
        refreshRows();
        Utils.showToastLong(L10n.f("Downloads will be saved in %s.", folder));
        return true;
    }

    private static void forget() {
        DownloadFolder.PICKED.save("");
        refreshRows();
        Utils.showToastLong(DownloadFolder.current() == null ? L10n.t("Downloads will be saved in Download.")
                : L10n.f("Downloads will be saved in %s.", DownloadFolder.current()));
    }

    private static void refreshRows() {
        for (WeakReference<DownloadFolderPreference> reference : ROWS) {
            DownloadFolderPreference row = reference.get();
            if (row == null) ROWS.remove(reference);
            else row.notifyChanged();
        }
    }

    private static void observe() {
        SharedPreferences current = Setting.preferences.preferences;
        if (observedStore == current) return;
        if (observedStore != null) observedStore.unregisterOnSharedPreferenceChangeListener(LISTENER);
        observedStore = current;
        current.registerOnSharedPreferenceChangeListener(LISTENER);
    }
}
