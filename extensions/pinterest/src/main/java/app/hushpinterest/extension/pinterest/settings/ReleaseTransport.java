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
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;

/**
 * The release check's connection, through the platform's HttpURLConnection.
 *
 * <p>{@link ReleaseCheck} decides what may be asked and reads what comes back. This sends one GET
 * and hands back the answer: no redirect followed, no cache, the connection closed after it, and
 * no cookie ({@link NoCookies}). Whatever a CookieManager keeps for api.github.com is dropped,
 * since only an answer from there could have left it, and a handler that would still add a cookie
 * stops the request before it goes out. What an answer stores is dropped again when the exchange
 * closes. The request goes out when the check asks for its status, and closing the exchange then
 * disconnects it, which ends a wait for headers or for the body.
 */
final class ReleaseTransport implements ReleaseCheck.Transport {
    /** The most any one connect or read waits. Each gets less when less of the whole check is left. */
    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 10_000;

    /** A request refused before it went out. The message says why, and names no address. */
    static final class Refused extends IOException {
        Refused(String reason) {
            super(reason);
        }
    }

    @Override
    public ReleaseCheck.Exchange get(URL url, Map<String, String> headers, int timeoutMs) throws IOException {
        URI uri;
        try {
            uri = url.toURI();
        } catch (URISyntaxException malformed) {
            throw new Refused("the address isn't a URI");
        }
        CookieHandler cookies = CookieHandler.getDefault();
        String refusal = cookieRefusal(cookies, uri);
        if (refusal != null) throw new Refused(refusal);

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            // Never 0, which HttpURLConnection reads as no timeout at all.
            int left = Math.max(1, timeoutMs);
            connection.setConnectTimeout(Math.min(CONNECT_TIMEOUT_MS, left));
            connection.setReadTimeout(Math.min(READ_TIMEOUT_MS, left));
            // Nothing is left open in the pool for the rest of Pinterest's run.
            connection.setRequestProperty("Connection", "close");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            return new Answer(connection, uri);
        } catch (IOException | RuntimeException failure) {
            connection.disconnect();
            NoCookies.forget(CookieHandler.getDefault(), uri);
            throw failure;
        }
    }

    /**
     * Why [handler] would put a cookie on a request to [uri], or null when it adds none. Anything a
     * CookieManager holds for [uri] is dropped first.
     */
    @Nullable
    static String cookieRefusal(@Nullable CookieHandler handler, URI uri) {
        NoCookies.forget(handler, uri);
        return NoCookies.refusal(handler, uri);
    }

    /** The answer on one connection, closed with it. */
    private static final class Answer implements ReleaseCheck.Exchange {
        private final HttpURLConnection connection;
        private final URI uri;
        private volatile boolean closed;

        Answer(HttpURLConnection connection, URI uri) {
            this.connection = connection;
            this.uri = uri;
        }

        @Override
        public int status() throws IOException {
            // A connection disconnected before it was sent would connect afresh when asked.
            if (closed) throw new IOException("closed before it was sent");
            return connection.getResponseCode();
        }

        @Nullable
        @Override
        public String header(String name) {
            return connection.getHeaderField(name);
        }

        @Override
        public long length() {
            return connection.getContentLengthLong();
        }

        @Override
        public InputStream body() throws IOException {
            return connection.getInputStream();
        }

        @Override
        public void close() {
            closed = true;
            try {
                connection.disconnect();
            } catch (Throwable ignored) {
                // Nothing useful to do.
            }
            NoCookies.forget(CookieHandler.getDefault(), uri);
        }
    }
}
