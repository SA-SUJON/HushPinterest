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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class PinTransferTest {
    static final class Response extends HttpURLConnection {
        final int status;
        final String redirect;
        boolean disconnected;

        Response(URI uri, int status, String redirect) throws IOException {
            super(uri.toURL()); this.status = status; this.redirect = redirect;
        }

        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? redirect : null; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }

    @Test public void followsOnlyValidatedCdnRedirectsAndClosesIntermediateConnections() throws Exception {
        List<Response> opened = new ArrayList<>();
        HttpURLConnection last = PinTransfer.connect("https://v.pinimg.com/start.mp4", uri -> {
            Response response = new Response(uri, opened.isEmpty() ? 302 : 200, "/final.mp4");
            opened.add(response);
            return response;
        });
        assertEquals(2, opened.size());
        assertTrue(opened.get(0).disconnected);
        assertFalse(opened.get(1).disconnected);
        assertEquals("https://v.pinimg.com/final.mp4", last.getURL().toString());
        assertFalse(last.getInstanceFollowRedirects());
        assertEquals(20000, last.getConnectTimeout());
        assertEquals(30000, last.getReadTimeout());
        last.disconnect();
    }

    @Test public void rejectsRedirectOffCdnBeforeAnotherConnectionAndBoundsLoops() throws Exception {
        List<Response> opened = new ArrayList<>();
        try {
            PinTransfer.connect("https://i.pinimg.com/start.jpg", uri -> {
                Response response = new Response(uri, 302, "https://evil.test/steal");
                opened.add(response); return response;
            });
            fail("off-CDN redirect accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("CDN")); }
        assertEquals(1, opened.size());
        assertTrue(opened.get(0).disconnected);
        opened.clear();
        try {
            PinTransfer.connect("https://i.pinimg.com/start.jpg", uri -> {
                Response response = new Response(uri, 307, "/loop.jpg");
                opened.add(response); return response;
            });
            fail("redirect loop accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("redirects")); }
        assertEquals(6, opened.size());
        for (Response response : opened) assertTrue(response.disconnected);
    }

    @Test public void rejectsInvalidSourceWithoutConnectingAndDisconnectsServerErrors() throws Exception {
        try {
            PinTransfer.connect("file:///private", uri -> { throw new AssertionError("connection attempted"); });
            fail("invalid source accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("Pinterest")); }
        Response response = new Response(new URI("https://i.pinimg.com/missing.jpg"), 404, null);
        try {
            PinTransfer.connect(response.getURL().toString(), ignored -> response);
            fail("server error accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("404")); }
        assertTrue(response.disconnected);
    }

    @Test public void copiesLargeMediaInChunksWithoutChangingBytes() throws Exception {
        byte[] source = new byte[2 * 1024 * 1024 + 11];
        for (int index = 0; index < source.length; index++) source[index] = (byte) (index % 251);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(source.length, PinTransfer.copy(new ByteArrayInputStream(source), output));
        assertArrayEquals(source, output.toByteArray());
    }
}
