/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;

import app.hushpinterest.extension.pinterest.settings.NoCookies;

/**
 * HEAD requests for downloads and Plain pin links: whether the media host has an original behind a
 * supplied size, and where a pin.it short link leads. Neither follows a redirect or reads a body,
 * and neither carries a cookie ({@link NoCookies}). The downloads themselves go through Android's
 * DownloadManager. Media details' Check the file adds one GET for the start of a file, with the
 * same limits and a bounded read ({@link #prefix}).
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
     * on a pinterest.com hop only what the answer set ([answered]) over what was [kept], or that
     * cookie would stop every later lookup.
     */
    private static void close(HttpURLConnection head, URI uri, @Nullable List<HttpCookie> kept, Set<String> answered) {
        head.disconnect();
        CookieHandler cookies = CookieHandler.getDefault();
        if (forgettable(uri)) NoCookies.forget(cookies, uri);
        else NoCookies.forgetSince(cookies, uri, kept, answered);
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
        Set<String> answered = Collections.emptySet();
        try {
            head.setRequestMethod("HEAD");
            head.setInstanceFollowRedirects(false);
            head.setUseCaches(false);
            head.setConnectTimeout(5000);
            head.setReadTimeout(5000);
            head.setRequestProperty("Accept-Encoding", "identity");
            int status = head.getResponseCode();
            answered = NoCookies.setBy(head.getHeaderFields());
            if (status != HttpURLConnection.HTTP_OK) return false;
            String type = head.getContentType();
            return type != null && type.split(";", 2)[0].trim().equalsIgnoreCase(source.mime);
        } finally {
            close(head, uri, kept, answered);
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
        Set<String> answered = Collections.emptySet();
        try {
            head.setRequestMethod("HEAD");
            head.setInstanceFollowRedirects(false);
            head.setUseCaches(false);
            head.setConnectTimeout(timeoutMs);
            head.setReadTimeout(timeoutMs);
            head.setRequestProperty("Accept-Encoding", "identity");
            int status = head.getResponseCode();
            answered = NoCookies.setBy(head.getHeaderFields());
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) return null;
            return head.getHeaderField("Location");
        } finally {
            close(head, uri, kept, answered);
        }
    }

    /** The most of a file a check reads. Image headers sit well inside it. */
    static final int PREFIX_BYTES = 65536;

    /** How long reading the start of a file may take in all, however slowly its bytes arrive. */
    static final long PREFIX_DEADLINE_MS = 15000;

    /** What the media host answered a ranged GET with, and at most {@link #PREFIX_BYTES} of its body. */
    static final class Prefix {
        final int status;
        /** The Content-Range header, or null. */
        @Nullable final String range;
        /** The Content-Length header, or -1. For a 206 it's the part sent, never the whole file. */
        final long length;
        /** The Content-Encoding header, or null. */
        @Nullable final String encoding;
        final byte[] bytes;

        Prefix(int status, @Nullable String range, long length, @Nullable String encoding, byte[] bytes) {
            this.status = status;
            this.range = range;
            this.length = length;
            this.encoding = encoding;
            this.bytes = bytes;
        }
    }

    /**
     * One GET for the first {@link #PREFIX_BYTES} bytes of the media file at [source], for Media
     * details' Check the file. Like the HEAD requests it follows no redirect and carries no cookie.
     * It reads no more than {@link #PREFIX_BYTES} bytes of the body, whatever the host sends, and
     * gives up after {@link #PREFIX_DEADLINE_MS} however slowly they come. An answer other than
     * 200 or 206 comes back with no body read.
     */
    static Prefix prefix(PinMedia.Source source, Connection factory, LongSupplier clock) throws IOException {
        URI uri = PinMedia.mediaUri(source.url);
        if (uri == null) throw new IOException("Not a public Pinterest media URL");
        long deadline = clock.getAsLong() + PREFIX_DEADLINE_MS;
        List<HttpCookie> kept = NoCookies.kept(CookieHandler.getDefault(), uri);
        HttpURLConnection get = open(uri, factory);
        Set<String> answered = Collections.emptySet();
        try {
            get.setRequestMethod("GET");
            get.setInstanceFollowRedirects(false);
            get.setUseCaches(false);
            get.setConnectTimeout(5000);
            get.setReadTimeout(5000);
            get.setRequestProperty("Accept-Encoding", "identity");
            get.setRequestProperty("Range", "bytes=0-" + (PREFIX_BYTES - 1));
            int status = get.getResponseCode();
            answered = NoCookies.setBy(get.getHeaderFields());
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                return new Prefix(status, null, -1, null, new byte[0]);
            }
            return new Prefix(status, get.getHeaderField("Content-Range"), get.getContentLengthLong(),
                    get.getContentEncoding(), read(get.getInputStream(), clock, deadline));
        } finally {
            // Disconnecting drops what a 200 answer still had to send, without reading it.
            close(get, uri, kept, answered);
        }
    }

    private static byte[] read(InputStream body, LongSupplier clock, long deadline) throws IOException {
        byte[] buffer = new byte[PREFIX_BYTES];
        int filled = 0;
        while (filled < PREFIX_BYTES) {
            if (clock.getAsLong() > deadline) throw new SocketTimeoutException("The media host sent the file too slowly");
            int read = body.read(buffer, filled, PREFIX_BYTES - filled);
            if (read < 0) break;
            filled += read;
        }
        return Arrays.copyOf(buffer, filled);
    }
}
