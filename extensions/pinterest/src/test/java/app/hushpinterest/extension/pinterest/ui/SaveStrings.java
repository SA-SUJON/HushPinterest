/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.robolectric.RuntimeEnvironment;

/**
 * Contexts whose resources hold Pinterest's save wording in one language, under the names
 * Pinterest gives it. The English, German and Japanese here are Pinterest 14.39.0's own strings.
 * A name with two texts is a plural, its "one" form first.
 */
final class SaveStrings {
    private SaveStrings() {}

    static Map<String, String[]> english() {
        Map<String, String[]> texts = new LinkedHashMap<>();
        texts.put("pinned", new String[] {"Saved!"});
        texts.put("pinned_multiple", new String[] {"Saved %1$,d Pin!", "Saved %1$,d Pins!"});
        texts.put("saved_onto_board", new String[] {"Saved to %1$s"});
        texts.put("saved_to_board_section", new String[] {"Saved to <b>%1$s</b> section!"});
        texts.put("saved_multiple_to_board_section", new String[] {"Saved %1$,d Pins to <b>%2$s</b> section!"});
        texts.put("pin_saved", new String[] {"Pin saved"});
        return texts;
    }

    static Map<String, String[]> german() {
        Map<String, String[]> texts = new LinkedHashMap<>();
        texts.put("pinned", new String[] {"Gemerkt!"});
        texts.put("pinned_multiple", new String[] {"%1$,d Pin gemerkt!", "%1$,d Pins gemerkt!"});
        texts.put("saved_onto_board", new String[] {"Gemerkt auf „%1$s“"});
        texts.put("saved_to_board_section", new String[] {"Gemerkt in Ordner <b>%1$s</b>!"});
        texts.put("saved_multiple_to_board_section", new String[] {"%1$,d Pins im Pinnwand-Ordner <b>%2$s</b> gemerkt"});
        texts.put("pin_saved", new String[] {"Pin gemerkt"});
        return texts;
    }

    static Map<String, String[]> japanese() {
        Map<String, String[]> texts = new LinkedHashMap<>();
        texts.put("pinned", new String[] {"保存しました！"});
        texts.put("pinned_multiple", new String[] {"%1$,d 件のピンを保存しました！", "%1$,d 件のピンを保存しました！"});
        texts.put("saved_onto_board", new String[] {"「%1$s」に保存しました"});
        texts.put("saved_to_board_section", new String[] {"サブボード「<b>%1$s</b>」に保存しました！"});
        texts.put("saved_multiple_to_board_section", new String[] {"サブボード「<b>%2$s</b>」に %1$,d 件のピンを保存しました！"});
        texts.put("pin_saved", new String[] {"ピンを保存しました"});
        return texts;
    }

    /**
     * Resources in [language] that know [texts] by name and nothing else of Pinterest's. Their ids
     * count up from 1, and any other id is answered by the test application's own resources.
     * The wordings a test before this one left behind are dropped, since they may be for the same
     * language with other strings.
     */
    static Resources resources(Locale language, Map<String, String[]> texts) {
        SaveToastTexts.KNOWN.clear();
        Resources real = RuntimeEnvironment.getApplication().getResources();
        Configuration configuration = new Configuration(real.getConfiguration());
        configuration.setLocale(language);
        List<String> names = new ArrayList<>(texts.keySet());
        return new Resources(real.getAssets(), real.getDisplayMetrics(), real.getConfiguration()) {
            private boolean named(int id) {
                return id > 0 && id <= names.size();
            }

            @Override public Configuration getConfiguration() {
                return configuration;
            }

            @Override public int getIdentifier(String name, String type, String owner) {
                String[] forms = texts.get(name);
                if (forms == null) return real.getIdentifier(name, type, owner);
                return type.equals("plurals") == (forms.length > 1) ? names.indexOf(name) + 1 : 0;
            }

            @Override public String getString(int id) {
                return named(id) ? texts.get(names.get(id - 1))[0] : real.getString(id);
            }

            @Override public CharSequence getText(int id) {
                return named(id) ? getString(id) : real.getText(id);
            }

            @Override public String getQuantityString(int id, int quantity) {
                return named(id) ? texts.get(names.get(id - 1))[quantity == 1 ? 0 : 1] : real.getQuantityString(id, quantity);
            }
        };
    }

    /** A context over [resources], as a save screen would hand to a toast. */
    static Context context(Resources resources) {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Resources getResources() {
                return resources;
            }
        };
    }

    static Context context(Locale language, Map<String, String[]> texts) {
        return context(resources(language, texts));
    }
}
