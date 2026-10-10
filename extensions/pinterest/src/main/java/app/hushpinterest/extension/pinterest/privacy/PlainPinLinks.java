/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.privacy;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.hushpinterest.extension.pinterest.actions.PinTransfer;
import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/**
 * Plain pin links: a pin.it short link in copied or shared text becomes the pin's own
 * pinterest.com link. The short link's slug tells Pinterest who shared it, the plain link doesn't.
 * A bare pin link on a country site (de.pinterest.com, www.pinterest.co.uk) or on pinterest.com
 * itself becomes the same www.pinterest.com link, with no lookup. Other hosts and paths, sign-in
 * and account pages among them, stay as they are.
 *
 * <p>Pinterest logs each invite link it makes, with the pin it made it for, just before it copies
 * or shares that link. The {@link #invite} hook records the pair from that one call, so the link
 * copied or shared a moment later becomes the plain link with no request. A share straight to one
 * app logs its invite only after that app is open, so {@link #directShare} records the pair and
 * fixes the share's text just before. A slug seen with two different pins is never rewritten from
 * the record.
 *
 * <p>A copied short link with no recorded pin is copied as it is, then asked of Pinterest off the
 * main thread: HEAD requests to pin.it that follow redirects only on Pinterest's own hosts, 4
 * seconds at most. The clipboard gets the plain link only when the answer is a pin page and the
 * clipboard still holds what was copied. Shared text never waits on the network, so it keeps the
 * short link unless the pin is already known.
 */
public final class PlainPinLinks {
    private PlainPinLinks() {}

    static final long BUDGET_MS = 4000;
    private static final int HOP_TIMEOUT_MS = 2000;
    private static final int MAX_HOPS = 4;
    private static final int REMEMBERED = 64;
    private static final int LOOKUPS_PER_COPY = 4;
    /** A recorded slug that came with two different pins: never rewritten from the record. */
    private static final String CONFLICT = "";

    static final String PLAIN_PREFIX = "https://www.pinterest.com/pin/";
    private static final Pattern SHORT = Pattern.compile("https?://pin\\.it/([A-Za-z0-9_-]{1,64})/?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PIN_ID = Pattern.compile("\\d{1,30}");
    private static final Pattern PIN_PATH = Pattern.compile("/pin/(\\d{1,30})(?:/(?:sent/?)?)?");
    /**
     * A whole web pin link with no port, user or fragment: group 1 is the host, group 2 the pin. Only
     * a sent link (a share invite) may carry a query, the invite's own.
     */
    private static final Pattern WEB_PIN = Pattern.compile(
            "https?://([A-Za-z0-9.-]{1,64})/pin/(\\d{1,30})(?:/|/sent/?(?:\\?[^#]*)?)?", Pattern.CASE_INSENSITIVE);
    /** The xx.pinterest.com country sites Pinterest 14.39.0 claims as app links. */
    private static final Set<String> COUNTRY_SUBDOMAINS = new HashSet<>(Arrays.asList(
            "au", "br", "ca", "ch", "cl", "co", "cz", "de", "dk", "es", "fi", "fr", "gr", "hu", "id", "ie",
            "in", "it", "jp", "kr", "mx", "nl", "no", "nz", "ph", "pl", "pt", "ro", "ru", "se", "sk", "tr",
            "uk", "za"));
    /** The pinterest.xx country domains Pinterest 14.39.0 claims as app links, after "www.pinterest.". */
    private static final Set<String> COUNTRY_DOMAINS = new HashSet<>(Arrays.asList(
            "at", "ca", "ch", "cl", "co.kr", "co.uk", "com.au", "com.mx", "de", "dk", "es", "fr", "ie", "it",
            "jp", "nz", "ph", "pt", "se"));

    /** One redirect hop: the Location the address answers with, or null. */
    interface Lookup {
        String location(URI uri, int timeoutMs) throws IOException;
    }

    static volatile Lookup lookup = PinTransfer::location;
    static volatile LongSupplier clock = () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

    private static final Map<String, String> invited = lru();
    private static final Map<String, String> resolved = lru();

    private static Map<String, String> lru() {
        return new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > REMEMBERED;
            }
        };
    }

    static boolean active() {
        try {
            return Utils.settingsReady() && Settings.PLAIN_PIN_LINKS.get()
                    && PatchFamily.Capability.PLAIN_PIN_LINKS.installed();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STRIP_LINK_TRACKING, "plain pin links switch read", failure);
            return false;
        }
    }

    /**
     * Called from Pinterest's invite logger, with the kind and id of the object it shares and the
     * invite link it made for it. Records the pair only for a pin and a pin.it link.
     */
    public static void invite(Object kind, String objectId, String inviteUrl) {
        HookStatus.invoked(FamilyNames.STRIP_LINK_TRACKING);
        try {
            if (active() && record(kind, objectId, inviteUrl)) {
                HookStatus.counted(FamilyNames.STRIP_LINK_TRACKING, "pin invite link recorded");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STRIP_LINK_TRACKING, "pin invite link", failure);
        }
    }

    /**
     * Called just before Pinterest starts another app with a share it built for that app alone, with
     * the object it shares, the invite link it made for it and the share intent. Pinterest logs that
     * invite only once the app is open, after the link is in the intent's text, so this records the
     * pair itself and then turns the link in the text into the plain one. Never asks the network: a
     * short link whose pin isn't known stays as it is.
     */
    public static void directShare(Object shared, Object inviteUrl, Intent intent) {
        HookStatus.invoked(FamilyNames.STRIP_LINK_TRACKING);
        try {
            if (!active() || intent == null || !(inviteUrl instanceof String)) return;
            record(sharedKind(shared), sharedId(shared), (String) inviteUrl);
            if (plainText(intent)) HookStatus.counted(FamilyNames.STRIP_LINK_TRACKING, "direct share pin.it link made plain");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STRIP_LINK_TRACKING, "direct share pin link", failure);
        }
    }

    /** Swaps the known pin.it links in the intent's text for plain links. True when one changed. */
    static boolean plainText(Intent intent) {
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text == null) return false;
        CharSequence plain = LinkTracking.rewrite(text, false, true);
        if (plain == text) return false;
        intent.putExtra(Intent.EXTRA_TEXT, plain.toString());
        return true;
    }

    /** Records a pin's invite link. True when [kind] is a pin, [objectId] a pin id and the link a whole pin.it link. */
    static boolean record(Object kind, String objectId, String inviteUrl) {
        if (!(kind instanceof Enum) || !"PIN".equals(((Enum<?>) kind).name())) return false;
        if (objectId == null || !PIN_ID.matcher(objectId).matches()) return false;
        String slug = slug(inviteUrl == null ? null : inviteUrl.trim());
        if (slug == null) return false;
        synchronized (invited) {
            String earlier = invited.get(slug);
            invited.put(slug, earlier == null || earlier.equals(objectId) ? objectId : CONFLICT);
        }
        return true;
    }

    /** Rewritten to read the shared object's kind, Pinterest's enum constant, through the getter its invite log uses. */
    private static Object sharedKind(Object shared) { return null; }

    /** Rewritten to read the shared object's id through the getter its invite log uses. */
    private static String sharedId(Object shared) { return null; }

    /** The slug of a whole pin.it link, or null for anything else. */
    static String slug(String url) {
        if (url == null) return null;
        Matcher matcher = SHORT.matcher(url);
        return matcher.matches() ? matcher.group(1) : null;
    }

    static String plain(String pinId) {
        return PLAIN_PREFIX + pinId + "/";
    }

    /**
     * The plain link for [url], or null when it stays as it is: a whole pin.it link whose pin is
     * known, or a whole pin link on one of Pinterest's own sites. Never asks the network.
     */
    static String plainLink(String url) {
        String pin = known(url);
        return pin != null ? pin : canonical(url);
    }

    /**
     * The plain link for a bare pin link on pinterest.com, www.pinterest.com or a country site, or
     * null. A sent link loses its invite query, which only says who sent it. Any other link with a
     * query, and one with a fragment, a port or a user, stays as it is, and so does any other host or
     * path. A link already in canonical form gives null, since nothing changes.
     */
    static String canonical(String url) {
        if (url == null) return null;
        Matcher matcher = WEB_PIN.matcher(url);
        if (!matcher.matches() || !pinterestSite(matcher.group(1).toLowerCase(Locale.ROOT))) return null;
        String plain = plain(matcher.group(2));
        return plain.equals(url) ? null : plain;
    }

    /** True for pinterest.com, www.pinterest.com and the country sites Pinterest claims as its own. */
    static boolean pinterestSite(String host) {
        if (host.equals("pinterest.com") || host.equals("www.pinterest.com")) return true;
        if (host.endsWith(".pinterest.com")) {
            return COUNTRY_SUBDOMAINS.contains(host.substring(0, host.length() - ".pinterest.com".length()));
        }
        String site = host.startsWith("www.") ? host.substring(4) : host;
        return site.startsWith("pinterest.") && COUNTRY_DOMAINS.contains(site.substring("pinterest.".length()));
    }

    /** The plain link for a whole pin.it link whose pin is known, or null. Never asks the network. */
    static String known(String url) {
        String slug = slug(url);
        if (slug == null) return null;
        String id;
        synchronized (resolved) {
            id = resolved.get(slug);
        }
        if (id == null) {
            synchronized (invited) {
                id = invited.get(slug);
            }
        }
        return id == null || CONFLICT.equals(id) ? null : plain(id);
    }

    /**
     * For [copied], on its way to the clipboard: looks up its pin.it links whose pins aren't known,
     * in the background, and swaps the plain links in if the clipboard then holds [copied].
     */
    static void resolveLater(CharSequence copied) {
        try {
            if (copied == null || !active()) return;
            String expected = copied.toString();
            List<String> slugs = new ArrayList<>();
            for (String url : LinkTracking.urls(expected)) {
                String slug = slug(url);
                if (slug != null && known(url) == null && !slugs.contains(slug)) slugs.add(slug);
            }
            if (slugs.isEmpty()) return;
            if (slugs.size() > LOOKUPS_PER_COPY) slugs = slugs.subList(0, LOOKUPS_PER_COPY);
            long deadline = clock.getAsLong() + BUDGET_MS;
            List<String> wanted = slugs;
            if (!Utils.runOnBackgroundThread(() -> resolveAndSwap(expected, wanted, deadline))) {
                HookStatus.counted(FamilyNames.STRIP_LINK_TRACKING, "copied pin.it link kept, no worker free");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STRIP_LINK_TRACKING, "pin.it lookup start", failure);
        }
    }

    private static void resolveAndSwap(String expected, List<String> slugs, long deadline) {
        try {
            for (String slug : slugs) {
                String id = resolve(slug, deadline);
                if (id == null) continue;
                synchronized (resolved) {
                    resolved.put(slug, id);
                }
            }
            String replaced = LinkTracking.rewrite(expected, false, true).toString();
            if (replaced.equals(expected) || clock.getAsLong() >= deadline) {
                HookStatus.counted(FamilyNames.STRIP_LINK_TRACKING, "copied pin.it link kept");
                return;
            }
            Utils.runOnMainThread(() -> swap(expected, replaced, deadline));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STRIP_LINK_TRACKING, "pin.it lookup", failure);
        }
    }

    /** The pin id a pin.it slug leads to, or null on any failure, a non-pin page or the deadline. */
    static String resolve(String slug, long deadline) {
        URI next;
        try {
            next = new URI("https", "pin.it", "/" + slug, null);
        } catch (URISyntaxException malformed) {
            return null;
        }
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            long left = deadline - clock.getAsLong();
            if (left <= 0) return null;
            String location;
            try {
                location = lookup.location(next, (int) Math.max(1, Math.min(HOP_TIMEOUT_MS, left / 2)));
            } catch (IOException | RuntimeException failure) {
                return null;
            }
            if (location == null || clock.getAsLong() >= deadline) return null;
            URI target;
            try {
                target = next.resolve(new URI(location.trim()));
            } catch (URISyntaxException | IllegalArgumentException malformed) {
                return null;
            }
            String host = pinterestHost(target);
            if (host == null) return null;
            String path = target.getRawPath() == null ? "" : target.getRawPath();
            if (!host.equals("pin.it")) {
                Matcher pin = PIN_PATH.matcher(path);
                if (pin.matches()) return pin.group(1);
                // Only Pinterest's short-link redirector is asked again, never a page.
                if (!path.startsWith("/url_shortener/")) return null;
            }
            next = target;
        }
        return null;
    }

    /** The lowercase host of an https address on pin.it, a pinterest.com host or a country site, or null. */
    private static String pinterestHost(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) return null;
        if (uri.getPort() != -1 && uri.getPort() != 443) return null;
        String host = uri.getHost();
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("pin.it") || host.endsWith(".pinterest.com") || pinterestSite(host) ? host : null;
    }

    private static void swap(String expected, String replaced, long deadline) {
        try {
            if (!active() || clock.getAsLong() >= deadline) return;
            Context context = Utils.getContext();
            if (context == null) return;
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            ClipData current = clipboard.getPrimaryClip();
            if (current == null || current.getItemCount() != 1) return;
            CharSequence text = current.getItemAt(0).getText();
            if (text == null || !expected.equals(text.toString())) return;
            CharSequence label = current.getDescription().getLabel();
            ClipData plain = ClipData.newPlainText(
                    label != null && expected.equals(label.toString()) ? replaced : label, replaced);
            PersistableBundle extras = current.getDescription().getExtras();
            if (extras != null) plain.getDescription().setExtras(extras);
            clipboard.setPrimaryClip(plain);
            HookStatus.counted(FamilyNames.STRIP_LINK_TRACKING, "copied pin.it link made plain");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STRIP_LINK_TRACKING, "pin.it clipboard swap", failure);
        }
    }

    static void forgetForTests() {
        synchronized (invited) {
            invited.clear();
        }
        synchronized (resolved) {
            resolved.clear();
        }
        lookup = PinTransfer::location;
        clock = () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }
}
