/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.settings;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.hushpinterest.extension.pinterest.ads.PinWords;
import app.hushpinterest.extension.shared.settings.StringSetting;

/**
 * The muted words list, saved as one word or phrase per line.
 *
 * <p>Whatever reaches it, typed in its dialog or read from a settings file, is cleaned the same way
 * first ({@link #clean}): split at commas and line breaks, trimmed, each run of spaces made one, and
 * blanks and repeats dropped, along with anything past {@link #MAX_ENTRIES} entries or longer than
 * {@link #MAX_LENGTH} characters. So what's saved is always a list Filter feed can read, and two
 * spellings that only differ in case are one entry.
 */
public final class MutedWordsSetting extends StringSetting {
    public static final int MAX_ENTRIES = 50;
    /**
     * In code points, so 🎂 is one, but an emoji built from several, like a flag or one with a skin
     * tone, counts each part.
     */
    public static final int MAX_LENGTH = 40;

    public MutedWordsSetting(String key) {
        super(key, "");
    }

    @NonNull
    @Override
    protected String coerce(@NonNull String newValue) {
        return clean(newValue).saved;
    }

    /** What a typed list came to. */
    public static final class Cleaned {
        /** The text to save: the kept entries, one per line. */
        public final String saved;
        /** The kept entries as typed, trimmed. */
        public final List<String> entries;
        /** Entries left out for being longer than {@link #MAX_LENGTH}. */
        public final int tooLong;
        /** Entries left out because the list already held {@link #MAX_ENTRIES}. */
        public final int overLimit;

        Cleaned(List<String> entries, int tooLong, int overLimit) {
            this.entries = Collections.unmodifiableList(entries);
            this.saved = String.join("\n", entries);
            this.tooLong = tooLong;
            this.overLimit = overLimit;
        }
    }

    public static Cleaned clean(@NonNull String typed) {
        List<String> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int tooLong = 0, overLimit = 0;
        for (String part : typed.split("[,\n\r،、，]")) {
            String entry = PinWords.collapseSpaces(part);
            if (entry.isEmpty()) continue;
            // A repeat is dropped first, so a long entry typed twice is one left out, not two.
            if (!seen.add(PinWords.normalize(entry))) continue;
            if (entry.codePointCount(0, entry.length()) > MAX_LENGTH) {
                tooLong++;
                continue;
            }
            if (kept.size() == MAX_ENTRIES) {
                overLimit++;
                continue;
            }
            kept.add(entry);
        }
        return new Cleaned(kept, tooLong, overLimit);
    }

    /** The entries [saved] holds, cleaned. */
    public static List<String> entries(@NonNull String saved) {
        return clean(saved).entries;
    }
}
