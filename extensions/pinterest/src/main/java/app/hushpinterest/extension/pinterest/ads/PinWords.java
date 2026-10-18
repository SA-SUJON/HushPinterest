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
 * <p>Text and phrase are compared lowercased with {@link Locale#ROOT}, with a Turkish capital İ read
 * as a plain i and every run of spaces as one. A phrase only counts where it stands on its own: next
 * to an end of it that is a letter or digit there can't be another letter, digit or accent mark. So
 * "ai art" finds "AI Art | PixAI" but not "Thai artist", and since nothing here folds accents,
 * Portuguese "aí" never reads as "ai". An end that is something else, like the # of "#aiart", needs
 * no boundary on that side.
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

    /** [text] lowercased, İ as i, and each run of spaces as one space, with none at either end. */
    static String normalize(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean space = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            // Locale.ROOT lowercases İ to i and a combining dot, which would read as part of a longer word.
            if (c == '̇' && i > 0 && lower.charAt(i - 1) == 'i') continue;
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
        boolean wordStart = isWordPart(phrase.codePointAt(0));
        boolean wordEnd = isWordPart(phrase.codePointBefore(phrase.length()));
        for (int at = text.indexOf(phrase); at >= 0; at = text.indexOf(phrase, at + 1)) {
            int end = at + phrase.length();
            if (wordStart && at > 0 && isWordPart(text.codePointBefore(at))) continue;
            if (wordEnd && end < text.length() && isWordPart(text.codePointAt(end))) continue;
            return true;
        }
        return false;
    }

    /** Letters, digits and the accent marks written on them: what a word is made of here. */
    private static boolean isWordPart(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint) || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }
}
