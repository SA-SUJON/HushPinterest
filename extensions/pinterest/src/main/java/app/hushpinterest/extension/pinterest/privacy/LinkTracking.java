/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.privacy;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/** Only outgoing shared or copied text is cleaned. Navigation and sign-in URLs stay untouched. */
public final class LinkTracking {
    private LinkTracking() {}

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"\\u0000-\\u001f]+", Pattern.CASE_INSENSITIVE);
    private static final Set<String> TRACKING = new HashSet<>(Arrays.asList(
            "fbclid", "gclid", "dclid", "msclkid", "gbraid", "wbraid", "igshid",
            "mc_cid", "mc_eid", "_ga", "_gl", "epik", "srsltid"));
    private static final Set<String> PINTEREST_TRACKING = new HashSet<>(Arrays.asList(
            "sender", "sender_id", "tracking_id", "share_uid"));
    private static final Set<String> SIGNED = new HashSet<>(Arrays.asList(
            "signature", "sig", "token", "access_token", "auth", "authorization", "code",
            "x-amz-signature", "x-goog-signature", "oauth_signature"));

    private static boolean active() {
        try {
            return Utils.settingsReady() && Settings.STRIP_LINK_TRACKING.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STRIP_LINK_TRACKING, "switch read", failure);
            return false;
        }
    }

    /**
     * Preserves labels, non-link text, nulls and styled text when no URL changes. With Plain pin
     * links on, a pin.it link whose pin is known and a bare country-site pin link become the pin's
     * own www.pinterest.com link.
     */
    public static CharSequence cleanText(CharSequence text) {
        HookStatus.invoked(FamilyNames.STRIP_LINK_TRACKING);
        if (text == null) return text;
        boolean strip = active();
        boolean plain = PlainPinLinks.active();
        if (!strip && !plain) return text;
        try {
            CharSequence cleaned = rewrite(text, strip, plain);
            if (cleaned != text) HookStatus.counted(FamilyNames.STRIP_LINK_TRACKING, "shared or copied URL cleaned");
            return cleaned;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STRIP_LINK_TRACKING, "outgoing URL", failure);
            return text;
        }
    }

    /**
     * [text] itself when no URL changes, else the rewritten string. Matching goes on from where each
     * link ends, so a second link glued on after text, as in "見て、https://...", is cleaned too.
     */
    static CharSequence rewrite(CharSequence text, boolean strip, boolean plain) {
        Links links = new Links(text);
        StringBuilder result = null;
        int copied = 0;
        while (links.next()) {
            String link = links.link;
            String core = strip ? cleanUrl(link) : link;
            String pin = plain ? PlainPinLinks.plainLink(core) : null;
            String cleaned = pin != null ? pin : core;
            if (!cleaned.equals(link)) {
                if (result == null) result = new StringBuilder(text.length());
                result.append(text, copied, links.start).append(cleaned);
                copied = links.start + link.length();
            }
        }
        if (result == null) return text;
        return result.append(text, copied, text.length()).toString();
    }

    /** The URLs in [text], each without the punctuation that ends a sentence around it. */
    static List<String> urls(CharSequence text) {
        List<String> found = new ArrayList<>();
        Links links = new Links(text);
        while (links.next()) found.add(links.link);
        return found;
    }

    /** The longest link cleaned. A longer one is left as it is, so a huge pasted text can't stall a share. */
    static final int LONGEST_LINK = 4096;

    private static final Pattern START = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern RUN = Pattern.compile("[^\\s<>\\\"\\u0000-\\u001f]*");

    /**
     * Walks the links in a text in order. Each search starts where the last link ended, and a
     * match is read no further than {@link #LONGEST_LINK}, so each link costs at most that much
     * however long the run it sits in, rather than a read to the run's end. A match cut there is
     * still a link when text glued onto it starts inside the cut; otherwise the whole run is
     * passed over.
     */
    private static final class Links {
        private final CharSequence text;
        private final Matcher scheme, whole, rest;
        private int from;
        /** Where the current link starts in the text. */
        int start;
        String link;

        Links(CharSequence text) {
            this.text = text;
            scheme = START.matcher(text);
            whole = URL.matcher(text);
            rest = RUN.matcher(text);
        }

        boolean next() {
            while (from < text.length() && scheme.find(from)) {
                int at = scheme.start();
                whole.region(at, Math.min(text.length(), at + LONGEST_LINK + 1));
                if (!whole.lookingAt()) {
                    from = at + 1;
                    continue;
                }
                String match = text.subSequence(at, whole.end()).toString();
                int glued = gluedAt(match);
                if (whole.end() - at > LONGEST_LINK && glued < 0) {
                    // Too long to be a link anyone shares: passed over whole.
                    rest.region(whole.end(), text.length());
                    from = rest.lookingAt() ? rest.end() : whole.end();
                    continue;
                }
                start = at;
                link = match.substring(0, withoutPunctuation(match, glued >= 0 ? glued : match.length()));
                from = Math.max(at + link.length(), at + 1);
                return true;
            }
            return false;
        }
    }

    /**
     * Where text glued onto the link in a match starts, or -1 when the link runs to the end of the
     * match. URL runs to the next space, so text glued onto a link, as in
     * "?utm_source=x見て", would go with the tag it follows. Past the host, a run of characters outside
     * ASCII stays in the link when it's Latin, Greek or Cyrillic letters, as in "/wiki/São_Tomé" or
     * "?q=Tōkyō", when it starts a value right after an =, as in "?utm_campaign=夏セール", or when the
     * link carries on after it, as in "?q=見て&utm_source=x" or "?q=2024年10月&utm_source=x".
     * Otherwise the run is text after the link, and so is one a new link starts right after, as in
     * "見て、https://...". Punctuation that ends a sentence doesn't count, so a closing "?" is no
     * separator. A host name has no value to swallow text into, so an international one stays
     * whole. Plain char checks, since ICU and the JVM read \s differently.
     */
    private static int gluedAt(String url) {
        int end = withoutPunctuation(url, url.length());
        int at = url.indexOf("://") + 3;
        while (at < end && "/?#".indexOf(url.charAt(at)) < 0) at++;
        int query = url.indexOf('?', at);
        for (; at < end; at++) {
            if (url.charAt(at) < 0x80) continue;
            int glued = at;
            boolean letters = true;
            for (; at < end && url.charAt(at) >= 0x80; at++) letters &= wordLetter(url.charAt(at));
            if (linkStarts(url, at) ? insideQuery(url, at, end, query)
                    : letters || url.charAt(glued - 1) == '=' || carriesOn(url, at, end, query)) continue;
            return glued;
        }
        return -1;
    }

    /**
     * Whether the link starting at [at] is a value in the query at [query] rather than a link of its
     * own: the query began before it, and an & comes before any ? or # in it, as in
     * "tweet?text=見てhttps://pin.it/abc&utm_source=x", where the & is the first link's.
     */
    private static boolean insideQuery(String url, int at, int end, int query) {
        if (query < 0 || query > at) return false;
        for (int next = at; next < end; next++) {
            char c = url.charAt(next);
            if (c == '&') return true;
            if (c == '?' || c == '#') return false;
        }
        return false;
    }

    /**
     * Whether the link goes on after a run outside ASCII that ends at [from]: a + or a percent escape
     * straight after it, a separator in the ASCII before the next run, or a / ? # or & after later
     * runs in the path. Past a later run in the query only an & counts, so a date or a tag in glued
     * text, as in "x見てAI画像10/12", isn't swallowed into the value. An = past a later run is
     * text, as in "x見てAI画像=最高", and so is a ? once the query at [query] has begun, a # with
     * text outside ASCII right after it, as in "見て#ピン", and anything after a new link begins,
     * unless that link is a value in the query ({@link #insideQuery}).
     */
    private static boolean carriesOn(String url, int from, int end, int query) {
        if (from < end && "+%".indexOf(url.charAt(from)) >= 0) return true;
        String separators = "/?#&=";
        for (int next = from; next < end; next++) {
            char c = url.charAt(next);
            if (c >= 0x80) {
                separators = query >= 0 && next > query ? "&" : "/?#&";
            } else if (linkStarts(url, next)) {
                return insideQuery(url, next, end, query);
            } else if (separators.indexOf(c) >= 0 && (c != '?' || next == query)
                    && (c != '#' || next + 1 >= end || url.charAt(next + 1) < 0x80)) {
                return true;
            }
        }
        return false;
    }

    /** True when an http or https link starts at [at]. */
    private static boolean linkStarts(String url, int at) {
        return url.regionMatches(true, at, "http://", 0, 7) || url.regionMatches(true, at, "https://", 0, 8);
    }

    /**
     * A Latin, Greek or Cyrillic letter or accent. Those scripts put spaces between words, so a run of
     * them is part of the link it sits in, never text glued onto it. Greek punctuation in the same
     * blocks, such as the Greek question mark, isn't a letter.
     */
    private static boolean wordLetter(char c) {
        boolean block = (c >= 0xC0 && c <= 0x24F) || (c >= 0x300 && c <= 0x52F) || (c >= 0x1E00 && c <= 0x1FFF);
        if (!block) return false;
        int type = Character.getType(c);
        return Character.isLetter(c) || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static int withoutPunctuation(String url, int end) {
        while (end > 0 && ".,!?;:)]}".indexOf(url.charAt(end - 1)) >= 0) end--;
        return end;
    }

    /** Keeps raw parameter spelling and order. Signed links are preserved in full. */
    static String cleanUrl(String original) {
        int question = original.indexOf('?');
        int hash = original.indexOf('#');
        if (question < 0 || (hash >= 0 && hash < question)) return original;
        Uri uri = Uri.parse(original);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) return original;
        host = host.toLowerCase(Locale.ROOT);
        // A short-link token itself isn't a removable query parameter. Plain pin links handles pin.it.
        boolean pinterest = host.endsWith(".pinterest.com") || host.equals("pin.it") || PlainPinLinks.pinterestSite(host);
        int queryEnd = hash < 0 ? original.length() : hash;
        String[] parts = original.substring(question + 1, queryEnd).split("&", -1);
        for (String part : parts) {
            String name = name(part);
            if (SIGNED.contains(name) || name.startsWith("x-amz-") || name.startsWith("x-goog-")) return original;
        }
        ArrayList<String> kept = new ArrayList<>();
        boolean changed = false;
        for (String part : parts) {
            String name = name(part);
            if (name.startsWith("utm_") || TRACKING.contains(name) || (pinterest && PINTEREST_TRACKING.contains(name))) {
                changed = true;
            } else {
                kept.add(part);
            }
        }
        if (!changed) return original;
        return original.substring(0, question) + (kept.isEmpty() ? "" : "?" + String.join("&", kept))
                + original.substring(queryEnd);
    }

    private static String name(String part) {
        int equals = part.indexOf('=');
        return Uri.decode(equals < 0 ? part : part.substring(0, equals)).toLowerCase(Locale.ROOT);
    }

    public static Intent putStringExtra(Intent intent, String key, String value) {
        CharSequence clean = Intent.EXTRA_TEXT.equals(key) ? cleanText(value) : value;
        return intent.putExtra(key, clean == value ? value : clean == null ? null : clean.toString());
    }

    public static Intent putTextExtra(Intent intent, String key, CharSequence value) {
        return intent.putExtra(key, Intent.EXTRA_TEXT.equals(key) ? cleanText(value) : value);
    }

    /**
     * A pin.it link Plain pin links can't name the pin for yet is copied as it is, and looked up in
     * the background. The clipboard swap that may follow is posted to the main thread, so it runs
     * after Pinterest has set this clip.
     */
    public static ClipData newPlainText(CharSequence label, CharSequence text) {
        CharSequence cleaned = cleanText(text);
        CharSequence safeLabel = label != null && text != null && label.toString().equals(text.toString())
                ? cleaned : label;
        PlainPinLinks.resolveLater(cleaned);
        return ClipData.newPlainText(safeLabel, cleaned);
    }
}
