/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ads;

import java.util.ArrayList;
import java.util.List;

import app.hushpinterest.extension.shared.Logger;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;
import app.hushpinterest.extension.shared.settings.BaseSettings;
import app.hushpinterest.extension.pinterest.actions.BoardDownloads;
import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.PatchFamily;

/**
 * The one place both list families take items out.
 *
 * <p>Pinterest hands every page of a feed, search, related pins and boards to one of three list
 * holders before anything is drawn. The patch passes the list each holder is built with through
 * {@link #filter}, which drops what Hide ads and Hide AI-labeled pins each say to and hands back the
 * rest in order. A module that stays, such as a row of ideas or a group of products, has the same
 * done to the pins inside it, and goes when none are left ({@link ModuleItems}). With every switch
 * off, or paused, the list goes through untouched.
 *
 * <p>Download board reads what's left, untouched, to keep the pins of a board's own pages. It never
 * throws and never changes the list, so the filtering is the same with it or without it.
 */
public final class FeedFilter {
    private FeedFilter() {}

    /**
     * Injected at the start of each list holder's constructor, on its list parameter. Returns the
     * same list when nothing goes, or a new one without the items that do. Never throws.
     */
    public static List<?> filter(List<?> items) {
        // Each family's patch marks its list capability once the hook is in.
        boolean ads = PatchFamily.Capability.FEED_ADS.installed();
        boolean ai = PatchFamily.Capability.FEED_AI_PINS.installed();
        boolean shopping = PatchFamily.Capability.FEED_SHOPPING.installed();
        if (ads) HookStatus.invoked(FamilyNames.HIDE_ADS);
        if (ai) HookStatus.invoked(FamilyNames.HIDE_AI_PINS);
        if (shopping) HookStatus.invoked(FamilyNames.HIDE_SHOPPING);
        if (items == null || items.isEmpty()) return items;
        ads = ads && Ads.active();
        ai = ai && AiPins.active();
        shopping = shopping && Shopping.active();
        if (!ads && !ai && !shopping) {
            BoardDownloads.record(items);
            return items;
        }
        boolean hideAds = ads, hideAi = ai, hideShopping = shopping;
        ModuleItems.Verdict verdict = model -> family(model, hideAds, hideAi, hideShopping);
        String owner = ads ? FamilyNames.HIDE_ADS : ai ? FamilyNames.HIDE_AI_PINS : FamilyNames.HIDE_SHOPPING;
        boolean debug = debugLogging();
        List<?> shown;
        try {
            List<Object> kept = null;
            int size = items.size();
            for (int i = 0; i < size; i++) {
                Object item = items.get(i);
                String family = verdict.family(item);
                // A module that stays loses what's inside it that goes, and goes too when that's everything.
                if (family == null && ModuleItems.isModule(item)) {
                    String emptied = ModuleItems.prune(item, verdict, owner);
                    if (emptied != null) {
                        family = emptied;
                        HookStatus.counted(emptied, "module left empty removed");
                    }
                } else if (family != null) {
                    HookStatus.counted(family, removal(family));
                }
                if (family != null) {
                    if (kept == null) kept = new ArrayList<>(items.subList(0, i));
                } else if (kept != null) {
                    kept.add(item);
                }
                // A kept item with commercial fields set is what a report of an ad that got through needs (#6).
                if (family == null && debug) {
                    String marks = ModuleItems.markers(item);
                    if (!marks.isEmpty()) Logger.printDebug(() -> "Kept " + item.getClass().getSimpleName() + marks);
                }
            }
            shown = kept == null ? items : kept;
        } catch (Throwable t) {
            HookStatus.threw(owner, "list filter", t);
            return items;
        }
        // Outside the filter's own failure handling: what's kept for a board never decides what's shown.
        BoardDownloads.record(shown);
        return shown;
    }

    /** Whether Debug logging is on, read once a page; never throws. */
    private static boolean debugLogging() {
        try {
            return Utils.settingsReady() && BaseSettings.DEBUG.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The first switch on that takes [item] out, in the order ads, AI, shopping, or null to keep it. */
    private static String family(Object item, boolean ads, boolean ai, boolean shopping) {
        return ads && Ads.isAd(item) ? FamilyNames.HIDE_ADS
                : ai && AiPins.isLabeled(item) ? FamilyNames.HIDE_AI_PINS
                : shopping && Shopping.isShopping(item) ? FamilyNames.HIDE_SHOPPING : null;
    }

    /** What a removal by [family] is counted as. English, for the report. */
    static String removal(String family) {
        return family.equals(FamilyNames.HIDE_ADS) ? "promoted pin removed"
                : family.equals(FamilyNames.HIDE_AI_PINS) ? "AI-labeled pin removed" : "shopping placement removed";
    }
}
