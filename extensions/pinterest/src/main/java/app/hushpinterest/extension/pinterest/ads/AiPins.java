/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ads;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;
import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.Settings;

/**
 * Hide AI-labeled pins: pins that Pinterest itself labels as made or changed with AI leave the lists
 * Pinterest shows.
 *
 * <p>Pinterest 14.40.0 draws its "AI modified" line from the pin's {@code digital_media_source_type_label}
 * and treats any {@code digital_media_source_type} as an AI pin. Every value that enum has names
 * trained algorithmic media or a synthetic performer, and a value the app doesn't know reads as
 * null, so a set type counts, and so does label text on its own. Most of what it marks is
 * {@code DETECTED_TRAINED_ALGORITHMIC_MEDIA}, Pinterest's own detection, which often has no label in
 * the grid. Older builds labeled from the {@code ai_disclosures} list (1 for "AI modified", 2 for a
 * synthetic performer); 14.40.0 still parses it, but its feeds and search send none, so on its own it
 * caught nothing there. Any entry still counts. An AI image Pinterest hasn't marked carries nothing
 * to go by, and stays.
 *
 * <p>Hide pins that say they're AI is a second switch beside it for what's left: a pin Pinterest
 * doesn't mark whose own title, grid title or description uses one of a few phrases, or that comes
 * from a site that only hosts AI images. It goes by the pin's words, so it's a guess Pinterest's
 * label isn't: a pin that just says "AI" stays, and so does an AI image that says nothing.
 */
public final class AiPins {
    private AiPins() {}

    static final String AI_DISCLOSURES = "ai_disclosures";
    static final String SOURCE_TYPE = "digital_media_source_type";
    static final String SOURCE_LABEL = "digital_media_source_type_label";

    /** What a pin that says it's AI calls itself, already {@link PinWords#normalize normalized}. */
    static final List<String> PHRASES = Collections.unmodifiableList(Arrays.asList(
            "ai art", "ai generated", "ai-generated", "#aiart", "#aigenerated", "made with ai",
            "midjourney", "stable diffusion", "dall-e", "nijijourney", "pixai"));

    /** Sites that only host AI images, with any subdomain. */
    static final List<String> AI_SITES = Collections.unmodifiableList(Arrays.asList(
            "pixai.art", "civitai.com", "openart.ai", "nightcafe.studio", "seaart.ai", "tensor.art"));

    /** What the list filter names a pin that says it's AI, which Hide AI-labeled pins counts apart. */
    static final String SELF_DESCRIBED = "self-described AI pin";

    /** Whether the switch is on and the settings can be read; never throws. */
    static boolean active() {
        try {
            return Utils.settingsReady() && Settings.HIDE_AI_PINS.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.HIDE_AI_PINS, "switch read", t);
            return false;
        }
    }

    /** Whether Hide pins that say they're AI is on and the settings can be read; never throws. */
    static boolean wordsActive() {
        try {
            return Utils.settingsReady() && Settings.HIDE_AI_SELF_DESCRIBED.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.HIDE_AI_PINS, "switch read", t);
            return false;
        }
    }

    /** True when [item] is a pin whose own words or site say it's AI. */
    static boolean saysItIsAi(Object item) {
        if (item == null) return false;
        Map<String, Field> fields = ModelFields.of(item.getClass());
        return PinWords.isPin(fields)
                && (PinWords.says(fields, item, PHRASES) || PinWords.onHost(fields, item, AI_SITES));
    }

    /** True when Pinterest labels [item] as made or changed with AI. */
    static boolean isLabeled(Object item) {
        if (item == null) return false;
        Map<String, Field> fields = ModelFields.of(item.getClass());
        return !fields.isEmpty() && (ModelFields.isSet(fields, item, SOURCE_TYPE)
                || ModelFields.hasText(fields, item, SOURCE_LABEL)
                || ModelFields.hasEntries(fields, item, AI_DISCLOSURES));
    }
}
