/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** The pin.it lookup Plain pin links makes: one HEAD request, no redirect followed, no body read. */
public class PinTransferTest {
    static class Response extends HttpURLConnection {
        final int status;
        final String redirect;
        volatile boolean disconnected;
        long length = -1;
        InputStream input = new ByteArrayInputStream(new byte[0]);
        int inputOpens;

        Response(URI uri, int status, String redirect) throws IOException {
            super(uri.toURL()); this.status = status; this.redirect = redirect;
        }

        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? redirect : null; }
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
}
