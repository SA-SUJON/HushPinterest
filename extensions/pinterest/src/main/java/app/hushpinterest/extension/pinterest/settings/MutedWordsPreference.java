/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.Logger;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.settings.Setting;

/**
 * Muted words: a row on the Feed page that opens a text box holding the list, one word or phrase
 * per line. Save keeps what was typed, cleaned ({@link MutedWordsSetting#clean}), and a toast says
 * how many words it kept and what it left out. Clear empties the list at once.
 */
@SuppressWarnings("deprecation") // Framework preferences are what the shared settings page builds on.
public final class MutedWordsPreference extends Preference {
    /** The rows on the page right now, so a save or an import shows on them at once. */
    private static final List<WeakReference<MutedWordsPreference>> ROWS = new CopyOnWriteArrayList<>();

    @Nullable private static SharedPreferences observedStore;
    private static final SharedPreferences.OnSharedPreferenceChangeListener LISTENER = (store, key) -> {
        if (key == null || key.equals(Settings.MUTED_WORDS.key)) Utils.runOnMainThread(MutedWordsPreference::refreshRows);
    };

    /** The dialog on screen, for tests. */
    @Nullable static AlertDialog shown;

    MutedWordsPreference(Context context) {
        super(HushPinterestPreferenceFragment.themed(context));
        setKey("action_muted_words");
        setPersistent(false);
        setTitle(L10n.t(context, "Muted words"));
        setOnPreferenceClickListener(preference -> {
            edit();
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

    /** What the row says: what the list does, or how many words it holds. Saved, so a pause doesn't empty it. */
    static String summary() {
        int count = MutedWordsSetting.entries(Settings.MUTED_WORDS.savedValue()).size();
        if (count == 0) {
            return L10n.t("Hides pins whose title or description has a word or phrase you add here. None yet.");
        }
        return L10n.quantity(count, "Hiding pins that mention %1$d word or phrase. Tap to change the list.",
                "Hiding pins that mention any of %1$d words or phrases. Tap to change the list.", count);
    }

    private void edit() {
        Context context = HushPinterestPreferenceFragment.themed(getContext());
        EditText field = new EditText(context);
        field.setId(android.R.id.edit);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setMinLines(3);
        field.setMaxLines(8);
        field.setHint(L10n.t("cake, wedding dress"));
        List<String> entries = MutedWordsSetting.entries(Settings.MUTED_WORDS.savedValue());
        field.setText(String.join("\n", entries));
        field.setSelection(field.getText().length());
        FrameLayout holder = new FrameLayout(context);
        int side = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, ScreenColors.SIDE_DP,
                context.getResources().getDisplayMetrics()));
        holder.setPaddingRelative(side, side / 2, side, 0);
        holder.addView(field, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(getTitle())
                .setMessage(L10n.f("Put each word or phrase on its own line or after a comma. Up to %1$d, "
                        + "each up to %2$d characters. Capitals don't matter, and only whole words count.",
                        MutedWordsSetting.MAX_ENTRIES, MutedWordsSetting.MAX_LENGTH))
                .setView(holder)
                .setPositiveButton(L10n.t("Save"), (dialog, which) -> save(String.valueOf(field.getText())))
                .setNegativeButton(L10n.t("Cancel"), null);
        if (!entries.isEmpty()) {
            builder.setNeutralButton(L10n.t("Clear"), (dialog, which) -> save(""));
        }
        try {
            AlertDialog dialog = builder.create();
            dialog.setOnDismissListener(gone -> {
                if (shown == gone) shown = null;
            });
            dialog.create();
            ScreenColors.dialog(dialog);
            dialog.show();
            shown = dialog;
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Could not show the muted words: " + failure.getClass().getSimpleName());
            Utils.showToastLong(L10n.t("Couldn't open the muted words. Try again."));
        }
    }

    /** Saves [typed], cleaned, and says what was kept. */
    static void save(String typed) {
        MutedWordsSetting.Cleaned cleaned = MutedWordsSetting.clean(typed);
        try {
            Settings.MUTED_WORDS.save(cleaned.saved);
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Could not save the muted words: " + failure.getClass().getSimpleName());
            Utils.showToastLong(L10n.t("Couldn't save the muted words. Try again."));
            return;
        }
        refreshRows();
        Utils.showToastLong(savedMessage(cleaned));
    }

    /** The toast after a save: how many words the list holds and what was left out of it. */
    static String savedMessage(MutedWordsSetting.Cleaned cleaned) {
        int kept = cleaned.entries.size();
        StringBuilder said = new StringBuilder(kept == 0 && cleaned.tooLong == 0
                ? L10n.t("No muted words. Every pin shows again.")
                : L10n.quantity(kept, "%1$d muted word saved.", "%1$d muted words saved.", kept));
        if (cleaned.tooLong > 0) {
            said.append(' ').append(L10n.quantity(cleaned.tooLong,
                    "%1$d was longer than %2$d characters, so it was left out.",
                    "%1$d were longer than %2$d characters, so they were left out.",
                    cleaned.tooLong, MutedWordsSetting.MAX_LENGTH));
        }
        if (cleaned.overLimit > 0) {
            said.append(' ').append(L10n.quantity(cleaned.overLimit,
                    "%1$d more word didn't fit, since the list holds %2$d.",
                    "%1$d more words didn't fit, since the list holds %2$d.",
                    cleaned.overLimit, MutedWordsSetting.MAX_ENTRIES));
        }
        return said.toString();
    }

    private static void refreshRows() {
        for (WeakReference<MutedWordsPreference> reference : ROWS) {
            MutedWordsPreference row = reference.get();
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
