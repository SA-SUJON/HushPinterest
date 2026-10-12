/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Locale;

import app.hushpinterest.extension.pinterest.settings.NoCookies;

/**
 * HEAD requests for downloads and Plain pin links: whether the media host has an original behind a
 * supplied size, and where a pin.it short link leads. Neither follows a redirect or reads a body,
 * and neither carries a cookie ({@link NoCookies}). The downloads themselves go through Android's
 * DownloadManager.
 */
public final class PinTransfer {
    private PinTransfer() {}

    interface Connection {
        HttpURLConnection open(URI uri) throws IOException;
    }

    static final Connection NETWORK = uri -> (HttpURLConnection) uri.toURL().openConnection();

    /**
     * The connection for a HEAD request, unless the process's cookie handler would put a cookie on
     * it. What a CookieManager keeps for pin.it or the media host is dropped first, but never for a
     * pinterest.com hop of the short-link redirector, where Pinterest's sign-in lives: a cookie
     * there stops the request instead.
     */
    private static HttpURLConnection open(URI uri, Connection factory) throws IOException {
        CookieHandler cookies = CookieHandler.getDefault();
        if (forgettable(uri)) NoCookies.forget(cookies, uri);
        String refusal = NoCookies.refusal(cookies, uri);
        if (refusal != null) throw new IOException(refusal);
        return factory.open(uri);
    }

    /**
     * Closes [head] and drops what its answer stored: everything on pin.it and the media host, and
     * on a pinterest.com hop only what wasn't in [kept], or that cookie would stop every later lookup.
     */
    private static void close(HttpURLConnection head, URI uri, @Nullable List<HttpCookie> kept) {
        head.disconnect();
        CookieHandler cookies = CookieHandler.getDefault();
        if (forgettable(uri)) NoCookies.forget(cookies, uri);
        else NoCookies.forgetSince(cookies, uri, kept);
    }

    private static boolean forgettable(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("pin.it") || host.equals("pinimg.com") || host.endsWith(".pinimg.com");
    }

    /**
     * True when the media host answers a HEAD request for the address with a 200 and the image
     * type the address names. It follows no redirect.
     */
    static boolean present(PinMedia.Source source, Connection factory) throws IOException {
        URI uri = PinMedia.mediaUri(source.url);
        if (uri == null) throw new IOException("Not a public Pinterest media URL");
        List<HttpCookie> kept = NoCookies.kept(CookieHandler.getDefault(), uri);
        HttpURLConnection head = open(uri, factory);
        try {
            head.setRequestMethod("HEAD");
            head.setInstanceFollowRedirects(false);
            head.setUseCaches(false);
            head.setConnectTimeout(5000);
            head.setReadTimeout(5000);
            head.setRequestProperty("Accept-Encoding", "identity");
            if (head.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            String type = head.getContentType();
            return type != null && type.split(";", 2)[0].trim().equalsIgnoreCase(source.mime);
        } finally {
            close(head, uri, kept);
        }
    }

    /**
     * Where a redirect at the address points: its Location header, or null when the answer isn't a
     * redirect. A HEAD request with no redirect followed and no body read. The caller checks the
     * address and the answer.
     */
    public static String location(URI uri, int timeoutMs) throws IOException {
        return location(uri, timeoutMs, NETWORK);
    }

    static String location(URI uri, int timeoutMs, Connection factory) throws IOException {
        List<HttpCookie> kept = NoCookies.kept(CookieHandler.getDefault(), uri);
        HttpURLConnection head = open(uri, factory);
        try {
            head.setRequestMethod("HEAD");
            head.setInstanceFollowRedirects(false);
            head.setUseCaches(false);
            head.setConnectTimeout(timeoutMs);
            head.setReadTimeout(timeoutMs);
            head.setRequestProperty("Accept-Encoding", "identity");
            int status = head.getResponseCode();
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) return null;
            return head.getHeaderField("Location");
        } finally {
            close(head, uri, kept);
        }
    }
}
