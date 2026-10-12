/*
 * Forked from https://github.com/SysAdminDoc/HushTelegram at 8c54a1d (GPL-3.0),
 * modified for HushPinterest (Pinterest), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushThreads at b141524 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at c15d4f79 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.hushpinterest.extension.pinterest.settings;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps the process's cookies off the extension's own requests: the release check and the HEAD
 * requests behind downloads and Plain pin links.
 *
 * <p>HttpURLConnection doesn't let a caller turn the cookie off. It puts on every request whatever
 * the process's default CookieHandler offers for the address, and an app can make a
 * java.net.CookieManager that default when it builds an HttpURLConnection request handler of its
 * own. So the handler is asked first what it would add, and a request it would put a cookie on
 * doesn't go out. What an answer stores is dropped again when the exchange closes.
 */
public final class NoCookies {
    private NoCookies() {}

    /** Why [handler] would put a cookie on a request to [uri], or null when it adds none. */
    @Nullable
    public static String refusal(@Nullable CookieHandler handler, URI uri) {
        if (handler == null) return null;
        Map<String, List<String>> added;
        try {
            added = handler.get(uri, Collections.<String, List<String>>emptyMap());
        } catch (IOException | RuntimeException failure) {
            return "the phone's cookie handler couldn't be asked";
        }
        if (added == null) return null;
        for (Map.Entry<String, List<String>> header : added.entrySet()) {
            String name = header.getKey();
            List<String> values = header.getValue();
            if (name == null || values == null || !name.toLowerCase(Locale.ROOT).startsWith("cookie")) continue;
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) return "the phone's cookie handler would add a cookie";
            }
        }
        return null;
    }

    /**
     * Drops what a CookieManager keeps for [uri]. Only for a host that keeps none of Pinterest's
     * sign-in, so nothing but an answer from there is lost.
     */
    public static void forget(@Nullable CookieHandler handler, URI uri) {
        if (!(handler instanceof CookieManager)) return;
        try {
            CookieStore store = ((CookieManager) handler).getCookieStore();
            for (HttpCookie cookie : store.get(uri)) store.remove(uri, cookie);
        } catch (RuntimeException ignored) {
            // The check before a request still stops one that would carry a cookie.
        }
    }

    /** What a CookieManager keeps for [uri] now, for {@link #forgetSince}, or null when it can't say. */
    @Nullable
    public static List<HttpCookie> kept(@Nullable CookieHandler handler, URI uri) {
        if (!(handler instanceof CookieManager)) return null;
        try {
            return new ArrayList<>(((CookieManager) handler).getCookieStore().get(uri));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Drops what a CookieManager stored for [uri] after [before] was taken, and nothing it kept
     * already, so a host that holds Pinterest's sign-in loses only what one answer from it left.
     */
    public static void forgetSince(@Nullable CookieHandler handler, URI uri, @Nullable List<HttpCookie> before) {
        if (before == null || !(handler instanceof CookieManager)) return;
        try {
            CookieStore store = ((CookieManager) handler).getCookieStore();
            for (HttpCookie cookie : store.get(uri)) {
                if (!before.contains(cookie)) store.remove(uri, cookie);
            }
        } catch (RuntimeException ignored) {
            // The check before the next request still stops one that would carry it.
        }
    }
}
