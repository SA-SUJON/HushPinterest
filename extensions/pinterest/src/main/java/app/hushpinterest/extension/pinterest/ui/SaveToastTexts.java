/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ui;

import android.content.Context;
import android.content.res.Resources;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells a save confirmation by its words, for the two kinds of toast that carry nothing else to go
 * by: a Pinterest toast that shows whatever text it was handed, and an Android toast. The words are
 * Pinterest's own strings, looked up by name in the language the app is showing, so a board called
 * Recipes matches "Saved to Recipes" and its German or Japanese wording alike.
 *
 * <p>Only toasts from the screens that save a pin are asked. "Saved to" also starts messages that
 * aren't a pin save, a collage draft or a photo saved to the phone among them, and those are shown
 * through toasts that never come here.
 */
final class SaveToastTexts {
    private SaveToastTexts() {}

    /**
     * Pinterest's save confirmations by resource name, each a string or a plural: Saved!, Saved 3
     * Pins!, Saved to a board, Saved to a board's section, several pins saved to a section, and
     * Pin saved.
     */
    static final String[] NAMES = {
            "pinned", "pinned_multiple", "saved_onto_board", "saved_to_board_section",
            "saved_multiple_to_board_section", "pin_saved",
    };

    /** Enough counts to draw out every plural form a language has. */
    private static final int[] QUANTITIES = {0, 1, 2, 3, 5, 7, 11, 20, 21, 100};

    /**
     * The fewest letters a wording with a value in it needs outside its values. The thinnest one
     * Pinterest ships has four. A wording that is all value, or a value and a sign, says nothing a
     * save could be told by, so it matches no toast.
     */
    static final int MIN_LETTERS = 3;

    private static final Pattern TAG = Pattern.compile("</?[a-zA-Z][a-zA-Z0-9]*\\s*/?>");
    private static final Pattern SPACE = Pattern.compile("[\\s\\u00A0]+");
    private static final Pattern VALUE = Pattern.compile("%%|%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]");

    /** The wordings by app package and language. A handful at most: one per language the app shows. */
    static final Map<String, List<Pattern>> KNOWN = new ConcurrentHashMap<>();

    /** The text fields of each toast class asked about, opened for reading once. */
    private static final Map<Class<?>, Field[]> TEXTS = new ConcurrentHashMap<>();

    /** True when [text] is a save confirmation as one of [sources] words it. A null source is skipped. */
    static boolean matches(CharSequence text, Context... sources) {
        if (text == null) return false;
        String plain = plain(text.toString());
        if (plain.isEmpty()) return false;
        for (Context source : sources) {
            if (source == null) continue;
            for (Pattern wording : wordings(source)) if (wording.matcher(plain).matches()) return true;
        }
        return false;
    }

    /** True when a text [toast] was handed is a save confirmation. */
    static boolean holdsSave(Object toast, Context... sources) {
        if (toast == null) return false;
        for (Field field : texts(toast.getClass())) {
            Object value;
            try {
                value = field.get(toast);
            } catch (IllegalAccessException | RuntimeException closed) {
                continue;
            }
            if (value instanceof CharSequence && matches((CharSequence) value, sources)) return true;
        }
        return false;
    }

    private static Field[] texts(Class<?> type) {
        Field[] known = TEXTS.get(type);
        if (known != null) return known;
        List<Field> found = new ArrayList<>();
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !CharSequence.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    found.add(field);
                } catch (RuntimeException closed) {
                    // A field the runtime won't open can't be read. The toast shows as Pinterest meant.
                }
            }
        }
        Field[] fields = found.toArray(new Field[0]);
        TEXTS.put(type, fields);
        return fields;
    }

    private static List<Pattern> wordings(Context source) {
        Resources resources = source.getResources();
        String owner = source.getPackageName();
        String key = owner + ' ' + resources.getConfiguration().getLocales().toLanguageTags();
        List<Pattern> known = KNOWN.get(key);
        if (known != null) return known;
        Set<String> formats = new LinkedHashSet<>();
        for (String name : NAMES) {
            int string = resources.getIdentifier(name, "string", owner);
            if (string != 0) {
                formats.add(resources.getString(string));
                continue;
            }
            int plural = resources.getIdentifier(name, "plurals", owner);
            if (plural != 0) for (int quantity : QUANTITIES) formats.add(resources.getQuantityString(plural, quantity));
        }
        List<Pattern> wordings = new ArrayList<>();
        for (String format : formats) {
            Pattern wording = wording(format);
            if (wording != null) wordings.add(wording);
        }
        if (KNOWN.size() >= 8) KNOWN.clear();
        KNOWN.put(key, wordings);
        return wordings;
    }

    /**
     * A format string as a pattern for the whole of a shown text: its words as they are, and
     * anything at all where a value goes. Null when it has too few words to tell a save by.
     */
    static Pattern wording(String format) {
        String text = plain(format);
        StringBuilder pattern = new StringBuilder();
        int letters = 0, values = 0, at = 0;
        Matcher value = VALUE.matcher(text);
        while (value.find()) {
            boolean percent = value.group().equals("%%");
            String words = text.substring(at, value.start()) + (percent ? "%" : "");
            at = value.end();
            letters += letters(words);
            pattern.append(Pattern.quote(words));
            if (percent) continue;
            pattern.append(".+");
            values++;
        }
        String rest = text.substring(at);
        letters += letters(rest);
        pattern.append(Pattern.quote(rest));
        if (letters == 0 || values > 0 && letters < MIN_LETTERS) return null;
        return Pattern.compile(pattern.toString(), Pattern.DOTALL);
    }

    /**
     * A text as it reads on screen: markup such as the bold around a board's name dropped, and each
     * run of spaces and line breaks one space. A format and the text shown from it come out alike
     * whether the toast kept the markup or had Android turn it into styling first.
     */
    static String plain(String text) {
        return SPACE.matcher(TAG.matcher(text).replaceAll("")).replaceAll(" ").trim();
    }

    private static int letters(String text) {
        int letters = 0;
        for (int at = 0; at < text.length(); at += Character.charCount(text.codePointAt(at))) {
            if (Character.isLetter(text.codePointAt(at))) letters++;
        }
        return letters;
    }
}
