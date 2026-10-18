/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ads;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds whole words and phrases in what a pin says about itself: its title, grid title and description.
 *
 * <p>Text and phrase are compared lowercased with {@link Locale#ROOT}, with Turkish İ and ı read as a
 * plain i and every run of spaces as one. A phrase only counts where it stands on its own: next
 * to an end of it that is a letter or digit there can't be another letter, digit or accent mark. So
 * "ai art" finds "AI Art | PixAI" but not "Thai artist", and since nothing here folds accents,
 * Portuguese "aí" never reads as "ai". An end that is something else, like the # of "#aiart", needs
 * no boundary on that side, and neither does a letter of a script written without spaces between
 * words, such as Chinese or Thai.
 *
 * <p>Only pins are read. Boards, users and stories carry titles and descriptions too, but no grid
 * title, so a board named after a phrase never leaves a profile.
 */
public final class PinWords {
    private PinWords() {}

    static final String GRID_TITLE = "grid_title";
    static final String DOMAIN = "domain";
    private static final String[] TEXT = {"title", GRID_TITLE, "description"};

    /** True when [fields] are a pin's: the one model that carries a grid title. */
    static boolean isPin(Map<String, Field> fields) {
        return fields.containsKey(GRID_TITLE);
    }

    /** True when [model]'s title, grid title or description holds one of [phrases], each already {@link #normalize normalized}. */
    static boolean says(Map<String, Field> fields, Object model, List<String> phrases) {
        for (String json : TEXT) {
            Object value = ModelFields.read(fields, model, json);
            if (!(value instanceof String)) continue;
            String text = normalize((String) value);
            for (String phrase : phrases) {
                if (contains(text, phrase)) return true;
            }
        }
        return false;
    }

    /** True when [model]'s domain is one of [hosts], lowercase, or a subdomain of one. */
    static boolean onHost(Map<String, Field> fields, Object model, List<String> hosts) {
        Object value = ModelFields.read(fields, model, DOMAIN);
        if (!(value instanceof String)) return false;
        String domain = ((String) value).trim().toLowerCase(Locale.ROOT);
        for (String host : hosts) {
            if (domain.equals(host) || domain.endsWith("." + host)) return true;
        }
        return false;
    }

    /**
     * [text] lowercased, Turkish İ and ı as i, a curly apostrophe as a straight one, and each run of
     * spaces as one space, with none at either end. Folding ı lets a muted "kırmızı" find "KIRMIZI",
     * which Locale.ROOT lowercases to "kirmizi".
     */
    public static String normalize(String text) {
        return squeeze(text.toLowerCase(Locale.ROOT), true);
    }

    /** [text] as written, but with each run of spaces as one space and none at either end. */
    public static String collapseSpaces(String text) {
        return squeeze(text, false);
    }

    private static String squeeze(String lower, boolean fold) {
        StringBuilder out = new StringBuilder(lower.length());
        boolean space = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            // Locale.ROOT lowercases İ to i and a combining dot, which would read as part of a longer word.
            if (fold && c == '̇' && i > 0 && lower.charAt(i - 1) == 'i') continue;
            if (fold && c == 'ı') c = 'i';
            // Titles often have the curly apostrophe a keyboard doesn't type, as in "Mom’s".
            if (fold && (c == '’' || c == '‘')) c = '\'';
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = true;
                continue;
            }
            if (space && out.length() > 0) out.append(' ');
            space = false;
            out.append(c);
        }
        return out.toString();
    }

    /** True when normalized [text] holds normalized [phrase] as whole words. */
    static boolean contains(String text, String phrase) {
        if (phrase.isEmpty()) return false;
        boolean wordStart = joinsWords(phrase.codePointAt(0));
        boolean wordEnd = joinsWords(phrase.codePointBefore(phrase.length()));
        for (int at = text.indexOf(phrase); at >= 0; at = text.indexOf(phrase, at + 1)) {
            int end = at + phrase.length();
            if (wordStart && at > 0 && joinsWords(text.codePointBefore(at))) continue;
            if (wordEnd && end < text.length() && joinsWords(text.codePointAt(end))) continue;
            // A mark after the phrase belongs to its last letter, even in a script without spaces.
            if (end < text.length() && isMark(text.codePointAt(end))) continue;
            return true;
        }
        return false;
    }

    /**
     * A letter, digit or accent mark of a script that puts spaces between words. Chinese, Japanese,
     * Thai, Lao, Khmer and Burmese don't, so "蛋糕" stands on its own in "生日蛋糕" and needs no
     * boundary, and one of their letters next to a Latin word is a boundary for it.
     */
    private static boolean joinsWords(int codePoint) {
        if (!Character.isLetterOrDigit(codePoint) && !isMark(codePoint)) return false;
        // The long vowel mark ending many Japanese loanwords ("コーヒー") is a common-script letter.
        if (codePoint == 0x30FC || codePoint == 0xFF70) return false;
        switch (Character.UnicodeScript.of(codePoint)) {
            case HAN: case HIRAGANA: case KATAKANA: case THAI: case LAO: case KHMER: case MYANMAR:
                return false;
            default:
                return true;
        }
    }

    private static boolean isMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }
}
