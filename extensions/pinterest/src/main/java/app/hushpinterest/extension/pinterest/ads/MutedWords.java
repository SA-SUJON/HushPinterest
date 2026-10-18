/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ads;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.MutedWordsSetting;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/**
 * Muted words: a pin whose title, grid title or description holds a word or phrase on the person's
 * list leaves the lists Pinterest shows, matched the way {@link PinWords} matches, so "cake" takes
 * out "Wedding Cake Ideas" but not "cupcakes". Boards, users and stories stay. An empty list, like
 * Pause, takes nothing out.
 */
public final class MutedWords {
    private MutedWords() {}

    /** The family token a muted pin is taken out under, counted apart in Filter feed's report. */
    static final String MUTED = "muted word pin";

    /** The list last read and its normalized phrases, as one value so a reader never gets half of each. */
    private static final class Read {
        final String saved;
        final List<String> phrases;

        Read(String saved, List<String> phrases) {
            this.saved = saved;
            this.phrases = phrases;
        }
    }

    private static volatile Read last = new Read("", Collections.emptyList());

    /** The saved list's phrases, normalized, or none while paused or unreadable; never throws. */
    static List<String> phrases() {
        try {
            return Utils.settingsReady() ? phrasesOf(Settings.MUTED_WORDS.get()) : Collections.emptyList();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.FILTER_FEED, "muted words read", t);
            return Collections.emptyList();
        }
    }

    /** [saved]'s entries, normalized. A page reads the same list the one before did, so it's kept. */
    static List<String> phrasesOf(String saved) {
        Read read = last;
        if (read.saved.equals(saved)) return read.phrases;
        List<String> phrases = new ArrayList<>();
        for (String entry : MutedWordsSetting.entries(saved)) phrases.add(PinWords.normalize(entry));
        read = new Read(saved, Collections.unmodifiableList(phrases));
        last = read;
        return read.phrases;
    }

    /** True when [item] is a pin that says one of [phrases]. */
    static boolean isMuted(Object item, List<String> phrases) {
        if (item == null || phrases.isEmpty()) return false;
        Map<String, Field> fields = ModelFields.of(item.getClass());
        return PinWords.isPin(fields) && PinWords.says(fields, item, phrases);
    }
}
