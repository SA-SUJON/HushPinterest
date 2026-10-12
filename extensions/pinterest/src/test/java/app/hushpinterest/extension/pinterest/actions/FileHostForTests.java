/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Answers Check the file's GET from a table and keeps FileCheck off the network and off the real
 * clock. An address with no answer gets a 404. close() puts the real connection and clock back
 * and forgets what was checked.
 */
final class FileHostForTests implements PinTransfer.Connection, AutoCloseable {
    private final PinTransfer.Connection previous = FileCheck.connection;
    private final LongSupplier previousClock = FileCheck.clock;
    private final Map<String, Function<URL, Answer>> answers = new ConcurrentHashMap<>();
    final List<Answer> asked = new CopyOnWriteArrayList<>();
    final AtomicLong now = new AtomicLong(1_000_000L);

    static FileHostForTests install() {
        FileHostForTests host = new FileHostForTests();
        FileCheck.forgetForTests();
        FileCheck.connection = host;
        FileCheck.clock = host.now::get;
        return host;
    }

    FileHostForTests answer(String url, Function<URL, Answer> answer) {
        answers.put(url, answer);
        return this;
    }

    /** A 206 with [range] as its Content-Range and [bytes] as its body. */
    FileHostForTests partial(String url, String range, byte[] bytes) {
        return answer(url, address -> new Answer(address, 206, new Body(bytes))
                .header("Content-Range", range).header("Content-Length", String.valueOf(bytes.length)));
    }

    @Override public HttpURLConnection open(URI uri) throws IOException {
        Function<URL, Answer> answer = answers.get(uri.toString());
        Answer opened = answer == null ? new Answer(uri.toURL(), 404, new Body(new byte[0])) : answer.apply(uri.toURL());
        asked.add(opened);
        return opened;
    }

    @Override public void close() {
        FileCheck.connection = previous;
        FileCheck.clock = previousClock;
        FileCheck.forgetForTests();
    }

    /** A real picture's bytes, encoded the way the media host would serve it. */
    static byte[] picture(int width, int height, Bitmap.CompressFormat format) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(200, 30, 60));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bitmap.compress(format, 90, out)) throw new AssertionError("the test picture didn't encode");
        bitmap.recycle();
        return out.toByteArray();
    }

    /** [bytes] followed by zeros up to [length], as the rest of a larger file would follow its header. */
    static byte[] padded(byte[] bytes, int length) {
        return Arrays.copyOf(bytes, Math.max(length, bytes.length));
    }

    static final class Answer extends HttpURLConnection {
        private final int status;
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final Body body;
        IOException failure;
        volatile boolean disconnected;
        int inputOpens;

        Answer(URL url, int status, Body body) {
            super(url);
            this.status = status;
            this.body = body;
        }

        Answer header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        Answer failing(IOException failure) {
            this.failure = failure;
            return this;
        }

        String address() { return url.toString(); }

        String method() { return method; }

        boolean followsRedirects() { return instanceFollowRedirects; }

        @Override public int getResponseCode() throws IOException {
            if (failure != null) throw failure;
            return status;
        }

        @Override public String getHeaderField(String name) { return name == null ? null : headers.get(name); }

        @Override public InputStream getInputStream() {
            inputOpens++;
            return body;
        }

        @Override public void connect() {}

        @Override public void disconnect() { disconnected = true; }

        @Override public boolean usingProxy() { return false; }
    }

    /** A body that counts what was taken from it, handed out at most [chunk] bytes a read. */
    static final class Body extends InputStream {
        private final byte[] bytes;
        private final int chunk;
        private final Runnable eachRead;
        volatile int served;

        Body(byte[] bytes) { this(bytes, 8192, null); }

        Body(byte[] bytes, int chunk, Runnable eachRead) {
            this.bytes = bytes;
            this.chunk = chunk;
            this.eachRead = eachRead;
        }

        @Override public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] into, int offset, int length) {
            if (eachRead != null) eachRead.run();
            if (length == 0) return 0;
            if (served >= bytes.length) return -1;
            int count = Math.min(Math.min(length, chunk), bytes.length - served);
            System.arraycopy(bytes, served, into, offset, count);
            served += count;
            return count;
        }
    }
}
