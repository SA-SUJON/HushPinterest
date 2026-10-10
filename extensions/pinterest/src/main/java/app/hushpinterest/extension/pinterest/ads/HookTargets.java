/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ads;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.Logger;

/**
 * The separate targets behind a Hide ads capability, as the patch found them: each list holder
 * with how many of its list constructors were hooked, and each ad-only view with how many of its
 * two methods are held.
 *
 * <p>A capability's flag only says at least one of its targets went in. These records say which,
 * so a build that lost a holder or a view shows that by name instead of reading as complete. Like
 * the flags they're build facts: Pause, switches and imports don't change them.
 */
public final class HookTargets {
    private HookTargets() {}

    /**
     * Rewritten when patching to answer one {@code id=hooked/expected} entry per list holder,
     * {@code ;} between them. Empty in a build the feed list patch didn't touch.
     */
    public static String feedLists() { return ""; }

    /** The same for each ad-only view, counting its setVisibility and onMeasure. */
    public static String adViews() { return ""; }

    /** What a test says the patch recorded, by capability, instead of asking the stubs. */
    @Nullable
    static volatile Map<PatchFamily.Capability, String> recordedForTests;

    /**
     * Ad placements no hook of this build reaches on its own. The list filter only sees a page's
     * top-level items, so a group of promoted products inside one item stays as Pinterest sent it.
     * English, for the report.
     */
    static final List<String> NOT_COVERED = Collections.unmodifiableList(Arrays.asList(
            "Shopping ideas groups inside search results", "shopping wrappers under related pins"));

    /** One target and how much of it was hooked. */
    public static final class Target {
        public final String id;
        public final int hooked;
        public final int expected;

        Target(String id, int hooked, int expected) {
            this.id = id;
            this.hooked = hooked;
            this.expected = expected;
        }

        public boolean complete() {
            return hooked >= expected;
        }

        /**
         * The target's name in the phone's language, keyed by the id the patch records; an id this
         * version doesn't know stays as it is. Each name is a literal so the catalog scan sees it.
         */
        public String label() {
            switch (id) {
                case "feedList": return L10n.t("promoted pins in feed pages");
                case "pagedResponse": return L10n.t("promoted pins in paged results");
                case "modelList": return L10n.t("promoted pins in bookmarked lists");
                case "TextAdView": return L10n.t("text ads");
                case "LegacyPromotedCloseupActionButtonModule": return L10n.t("ad buttons on pin closeups");
                case "PromotedPinCloseupFloatingActionBarModule": return L10n.t("floating ad bars on pin closeups");
                case "BoardSponsoredCuratorView": return L10n.t("sponsor credits on boards");
                default: return L10n.isolate(id);
            }
        }

        /** The name for the settings screen, with how much of it went in when that's only part. */
        public String summaryLabel() {
            if (complete() || hooked == 0) return label();
            return L10n.f("%1$s (%2$d of %3$d parts)", label(), hooked, expected);
        }

        /** "feedList 2 of 2", then "partial" or "missing" when it isn't all there. English, for the report. */
        public String reportText() {
            String text = id + " " + hooked + " of " + expected;
            return hooked == 0 ? text + " missing" : complete() ? text : text + " partial";
        }
    }

    /** Whether the patch records separate targets for [capability]. */
    public static boolean tracks(PatchFamily.Capability capability) {
        return capability == PatchFamily.Capability.FEED_ADS || capability == PatchFamily.Capability.AD_VIEWS;
    }

    /** The surfaces [capability] has no hook of its own for, in English for the report. */
    public static List<String> notCovered(PatchFamily.Capability capability) {
        return capability == PatchFamily.Capability.FEED_ADS ? NOT_COVERED : Collections.emptyList();
    }

    /** The targets recorded for [capability] in the patch's order; empty when it has none or none were recorded. */
    public static List<Target> of(PatchFamily.Capability capability) {
        if (!tracks(capability)) return Collections.emptyList();
        Map<PatchFamily.Capability, String> forced = recordedForTests;
        String recorded;
        if (forced != null) {
            recorded = forced.get(capability);
        } else {
            try {
                recorded = capability == PatchFamily.Capability.FEED_ADS ? feedLists() : adViews();
            } catch (RuntimeException failure) {
                Logger.printException(() -> "Could not read which " + capability.label + " targets this build has", failure);
                recorded = null;
            }
        }
        return parse(recorded);
    }

    /** True when every target in [targets] went in whole. An empty list adds nothing to know, so it's true too. */
    public static boolean allComplete(List<Target> targets) {
        for (Target target : targets) {
            if (!target.complete()) return false;
        }
        return true;
    }

    /** Reads the patch's record. An entry it can't read is left out rather than guessed at. */
    static List<Target> parse(@Nullable String recorded) {
        if (recorded == null || recorded.isEmpty()) return Collections.emptyList();
        List<Target> targets = new ArrayList<>();
        for (String entry : recorded.split(";")) {
            int equals = entry.indexOf('=');
            int slash = entry.indexOf('/', equals + 1);
            if (equals <= 0 || slash < 0) continue;
            try {
                int hooked = Integer.parseInt(entry.substring(equals + 1, slash));
                int expected = Integer.parseInt(entry.substring(slash + 1));
                if (hooked < 0 || expected < 1 || hooked > expected) continue;
                targets.add(new Target(entry.substring(0, equals), hooked, expected));
            } catch (NumberFormatException unreadable) {
                // Not an entry this version wrote.
            }
        }
        return Collections.unmodifiableList(targets);
    }
}
