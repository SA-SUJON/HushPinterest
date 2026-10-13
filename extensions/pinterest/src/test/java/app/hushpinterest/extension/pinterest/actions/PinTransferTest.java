/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The pin.it lookup Plain pin links makes: one HEAD request, no redirect followed, no body read. */
public class PinTransferTest {
    private static final URI SHORT_LINK = URI.create("https://pin.it/Zz9");

    private CookieHandler saved;
    private final List<URI> asked = new ArrayList<>();
    private final PinTransfer.Connection answering = uri -> {
        asked.add(uri);
        return new Response(uri, 308, "/next");
    };

    @Before public void saveCookies() { saved = CookieHandler.getDefault(); }

    @After public void restoreCookies() { CookieHandler.setDefault(saved); }

    static class Response extends HttpURLConnection {
        final int status;
        final String redirect;
        volatile boolean disconnected;
        long length = -1;
        InputStream input = new ByteArrayInputStream(new byte[0]);
        int inputOpens;
        Map<String, List<String>> headers = Collections.emptyMap();

        Response(URI uri, int status, String redirect) throws IOException {
            super(uri.toURL()); this.status = status; this.redirect = redirect;
        }

        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? redirect : null; }
        @Override public Map<String, List<String>> getHeaderFields() { return headers; }
        @Override public long getContentLengthLong() { return length; }
        @Override public InputStream getInputStream() { inputOpens++; return input; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }

    @Test public void locationAsksOnceWithHeadAndReadsOnlyARedirect() throws Exception {
        List<Response> opened = new ArrayList<>();
        String next = PinTransfer.location(URI.create("https://pin.it/Zz9"), 1500, uri -> {
            Response response = new Response(uri, 308, "https://api.pinterest.com/url_shortener/Zz9/redirect/");
            opened.add(response);
            return response;
        });
        assertEquals("https://api.pinterest.com/url_shortener/Zz9/redirect/", next);
        assertEquals(1, opened.size());
        Response head = opened.get(0);
        assertEquals("HEAD", head.getRequestMethod());
        assertFalse(head.getInstanceFollowRedirects());
        assertFalse(head.getUseCaches());
        assertEquals(1500, head.getConnectTimeout());
        assertEquals(1500, head.getReadTimeout());
        assertEquals("identity", head.getRequestProperty("Accept-Encoding"));
        assertEquals(0, head.inputOpens);
        assertTrue(head.disconnected);
        for (int status : new int[]{301, 302, 303, 307}) {
            assertEquals(String.valueOf(status), "/next", PinTransfer.location(URI.create("https://pin.it/Zz9"), 1500,
                    uri -> new Response(uri, status, "/next")));
        }
        for (int status : new int[]{200, 304, 404, 500}) {
            Response answer = new Response(URI.create("https://pin.it/Zz9"), status, "/ignored");
            assertNull(String.valueOf(status), PinTransfer.location(URI.create("https://pin.it/Zz9"), 1500, uri -> answer));
            assertTrue(answer.disconnected);
        }
        try {
            PinTransfer.location(URI.create("https://pin.it/Zz9"), 1500, uri -> { throw new IOException("offline"); });
            fail("a failed connection answered");
        } catch (IOException expected) { assertEquals("offline", expected.getMessage()); }
    }

    /**
     * An app can make a java.net.CookieManager the process's default, and HttpURLConnection puts
     * what the default offers on every request, so each HEAD asks it first, as the release check does.
     */
    @Test public void aHeadRequestCarriesNoCookieWhateverThePhoneKeeps() throws Exception {
        CookieHandler.setDefault(null);
        assertEquals("/next", PinTransfer.location(SHORT_LINK, 1500, answering));

        // A cookie an answer from pin.it left behind is dropped, and the request carries none.
        CookieManager manager = new CookieManager();
        CookieHandler.setDefault(manager);
        manager.getCookieStore().add(SHORT_LINK, cookie("pin_sess", "x", "pin.it"));
        assertEquals("the control: the manager would have added it", Collections.singletonList("pin_sess=x"),
                manager.get(SHORT_LINK, Collections.<String, List<String>>emptyMap()).get("Cookie"));
        assertEquals("/next", PinTransfer.location(SHORT_LINK, 1500, answering));
        assertTrue(manager.getCookieStore().get(SHORT_LINK).isEmpty());

        // Pinterest's own cookies stay where they are, and on the redirector's pinterest.com hop
        // one stops the request instead of being dropped.
        URI pinterest = URI.create("https://www.pinterest.com/");
        manager.getCookieStore().add(pinterest, cookie("_pinterest_sess", "1", ".pinterest.com"));
        assertEquals("/next", PinTransfer.location(SHORT_LINK, 1500, answering));
        assertEquals(1, manager.getCookieStore().get(pinterest).size());
        assertRefused(() -> PinTransfer.location(URI.create("https://api.pinterest.com/url_shortener/Zz9/redirect/"), 1500, answering));
        assertEquals(1, manager.getCookieStore().get(pinterest).size());

        // A handler that would still add one stops the request, and one that adds none doesn't.
        CookieHandler.setDefault(handler(Collections.singletonMap("Cookie", Collections.singletonList("a=b"))));
        assertRefused(() -> PinTransfer.location(SHORT_LINK, 1500, answering));
        CookieHandler.setDefault(handler(Collections.singletonMap("cookie2", Collections.singletonList("$Version=1"))));
        assertRefused(() -> PinTransfer.location(SHORT_LINK, 1500, answering));
        CookieHandler.setDefault(handler(Collections.singletonMap("Cookie", Collections.<String>emptyList())));
        assertEquals("/next", PinTransfer.location(SHORT_LINK, 1500, answering));
        CookieHandler.setDefault(handler(Collections.<String, List<String>>emptyMap()));
        assertEquals("/next", PinTransfer.location(SHORT_LINK, 1500, answering));
    }

    @Test public void theMediaHostIsAskedWithoutCookiesToo() throws Exception {
        PinMedia.Source original = new PinMedia.Source("https://i.pinimg.com/originals/pin.jpg", "image/jpeg", ".jpg");
        URI media = URI.create(original.url);
        CookieManager manager = new CookieManager();
        CookieHandler.setDefault(manager);
        manager.getCookieStore().add(media, cookie("cdn", "x", ".pinimg.com"));
        PinTransfer.present(original, answering);
        assertEquals(Collections.singletonList(media), asked);
        assertTrue(manager.getCookieStore().get(media).isEmpty());

        CookieHandler.setDefault(handler(Collections.singletonMap("Cookie", Collections.singletonList("a=b"))));
        assertRefused(() -> PinTransfer.present(original, answering));
    }

    @Test public void whatAnAnswerStoresIsDroppedWhenItCloses() throws Exception {
        CookieManager manager = new CookieManager();
        CookieHandler.setDefault(manager);
        List<Integer> stored = new ArrayList<>();
        String next = PinTransfer.location(SHORT_LINK, 1500, uri -> new Response(uri, 308, "/next") {
            @Override public int getResponseCode() {
                try {
                    manager.put(uri, Collections.singletonMap("Set-Cookie", Collections.singletonList("left=1; Path=/")));
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
                stored.add(manager.getCookieStore().get(uri).size());
                return super.getResponseCode();
            }
        });
        assertEquals("/next", next);
        assertEquals("the control: the answer stored one", Collections.singletonList(1), stored);
        assertTrue(manager.getCookieStore().get(SHORT_LINK).isEmpty());
    }

    /**
     * On the redirector's pinterest.com hop only what that answer stored goes. Left there, it would
     * stop every later lookup, and what Pinterest kept on the host before has to stay.
     */
    @Test public void theRedirectorHopDropsOnlyWhatItsOwnAnswerStored() throws Exception {
        CookieManager manager = new CookieManager();
        CookieHandler.setDefault(manager);
        URI hop = URI.create("https://api.pinterest.com/url_shortener/Zz9/redirect/");
        HttpCookie before = cookie("csrftoken", "1", "api.pinterest.com");
        before.setPath("/v3/");
        manager.getCookieStore().add(hop, before);
        HttpCookie meanwhile = cookie("sess", "2", "api.pinterest.com");
        meanwhile.setPath("/v3/");
        List<Integer> stored = new ArrayList<>();
        PinTransfer.Connection storing = answering(manager, stored, "redir=1; Path=/", () ->
                // Pinterest's own request to the same host stores a cookie while the lookup runs.
                manager.getCookieStore().add(hop, meanwhile));
        for (int lookup = 0; lookup < 2; lookup++) {
            assertEquals("https://www.pinterest.com/pin/1/", PinTransfer.location(hop, 1500, storing));
            assertEquals(Arrays.asList(before, meanwhile), manager.getCookieStore().get(hop));
        }
        assertEquals("the control: each answer stored one", Arrays.asList(3, 3), stored);
    }

    @Test public void aKeptCookieTheRedirectorChangesOrEndsGetsItsValueBack() throws Exception {
        CookieManager manager = new CookieManager();
        CookieHandler.setDefault(manager);
        URI hop = URI.create("https://api.pinterest.com/url_shortener/Zz9/redirect/");
        HttpCookie before = cookie("csrftoken", "1", "api.pinterest.com");
        before.setPath("/v3/");
        manager.getCookieStore().add(hop, before);
        List<Integer> stored = new ArrayList<>();
        PinTransfer.location(hop, 1500, answering(manager, stored, "csrftoken=9; Path=/v3/", () -> {
            assertEquals("the control: the answer changed it", "9", manager.getCookieStore().get(hop).get(0).getValue());
        }));
        assertEquals(1, manager.getCookieStore().get(hop).size());
        assertEquals("1", manager.getCookieStore().get(hop).get(0).getValue());
        PinTransfer.location(hop, 1500, answering(manager, stored, "csrftoken=; Max-Age=0; Path=/v3/", () ->
                assertTrue("the control: the answer ended it", manager.getCookieStore().get(hop).isEmpty())));
        assertEquals(Collections.singletonList(before), manager.getCookieStore().get(hop));
        assertEquals("1", manager.getCookieStore().get(hop).get(0).getValue());
    }

    /** A 302 whose answer sets [setCookie] in [manager], as HttpURLConnection does, then runs [after]. */
    private static PinTransfer.Connection answering(CookieManager manager, List<Integer> stored, String setCookie,
                                                    Runnable after) {
        return uri -> {
            Response response = new Response(uri, 302, "https://www.pinterest.com/pin/1/") {
                @Override public int getResponseCode() {
                    try {
                        manager.put(uri, headers);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                    after.run();
                    stored.add(manager.getCookieStore().get(uri).size());
                    return super.getResponseCode();
                }
            };
            response.headers = Collections.singletonMap("Set-Cookie", Collections.singletonList(setCookie));
            return response;
        };
    }

    @Test(timeout = 4000) public void aHeadDeadlineIncludesTheWaitForHeaders() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        Response head = new Response(SHORT_LINK, 308, "/late") {
            @Override public int getResponseCode() {
                try {
                    released.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                return super.getResponseCode();
            }

            @Override public void disconnect() {
                super.disconnect();
                released.countDown();
            }
        };
        long started = System.nanoTime();
        try {
            PinTransfer.location(SHORT_LINK, 80, uri -> head);
            fail("a stalled HEAD returned its late redirect");
        } catch (SocketTimeoutException expected) {
            assertTrue("the timed-out connection was dropped", head.disconnected);
            assertTrue("socket timeouts must not extend the total deadline",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1800);
        } finally {
            released.countDown();
        }
    }

    @Test(timeout = 4000) public void aHeadDeadlineIncludesOpeningTheConnection() throws Exception {
        Response head = new Response(SHORT_LINK, 308, "/late");
        try {
            PinTransfer.location(SHORT_LINK, 80, uri -> {
                try {
                    new CountDownLatch(1).await(2, TimeUnit.SECONDS);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                return head;
            });
            fail("a blocked opener escaped the HEAD deadline");
        } catch (SocketTimeoutException expected) {
            assertTrue("a connection opened after cancellation must be dropped", head.disconnected);
        }
    }

    @Test(timeout = 4000) public void progressingHeadersCannotKeepExtendingTheDeadline() throws Exception {
        AtomicInteger progress = new AtomicInteger();
        Response head = new Response(SHORT_LINK, 308, "/late") {
            @Override public int getResponseCode() {
                while (!disconnected && progress.incrementAndGet() < 100) {
                    try {
                        new CountDownLatch(1).await(5, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return super.getResponseCode();
            }
        };
        try {
            PinTransfer.location(SHORT_LINK, 80, uri -> head);
            fail("progressing headers escaped the total deadline");
        } catch (SocketTimeoutException expected) {
            assertTrue("headers progressed before the deadline", progress.get() > 1);
            assertTrue(head.disconnected);
        }
    }

    @Test(timeout = 5000) public void stuckOpenersAdmitOnlyTwoTransportWorkers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(2);
        AtomicInteger opened = new AtomicInteger();
        PinTransfer.Connection factory = uri -> {
            opened.incrementAndGet();
            boolean waiting = true;
            while (waiting) {
                try {
                    release.await();
                    waiting = false;
                } catch (InterruptedException ignored) {
                    // Model a resolver which doesn't support interruption.
                }
            }
            return new Response(SHORT_LINK, 308, "/late") {
                @Override public void disconnect() {
                    super.disconnect();
                    closed.countDown();
                }
            };
        };
        try {
            for (int i = 0; i < 2; i++) {
                try {
                    PinTransfer.location(SHORT_LINK, 40, factory);
                    fail("a stuck opener returned");
                } catch (SocketTimeoutException expected) { }
            }
            try {
                PinTransfer.location(SHORT_LINK, 40, factory);
                fail("a third stuck transport worker was admitted");
            } catch (IOException expected) {
                assertEquals("No media lookup worker is free", expected.getMessage());
            }
            assertEquals(2, opened.get());
        } finally {
            release.countDown();
            assertTrue("late connections are closed", closed.await(1, TimeUnit.SECONDS));
        }
    }

    private interface Request {
        Object send() throws IOException;
    }

    /** [request] fails before anything is asked, and says a cookie was the reason. */
    private void assertRefused(Request request) {
        int before = asked.size();
        try {
            request.send();
            fail("a request went out that would carry a cookie");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("cookie"));
        }
        assertEquals(before, asked.size());
    }

    private static HttpCookie cookie(String name, String value, String domain) {
        HttpCookie cookie = new HttpCookie(name, value);
        cookie.setDomain(domain);
        cookie.setPath("/");
        cookie.setVersion(0);
        return cookie;
    }

    private static CookieHandler handler(Map<String, List<String>> adds) {
        return new CookieHandler() {
            @Override
            public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
                return adds;
            }

            @Override
            public void put(URI uri, Map<String, List<String>> headers) {
            }
        };
    }
}
