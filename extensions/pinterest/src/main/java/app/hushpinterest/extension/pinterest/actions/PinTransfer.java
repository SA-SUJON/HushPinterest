/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.content.Context;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/** Streams into a chosen document and validates every redirect before connecting. */
final class PinTransfer {
    private PinTransfer() {}

    interface Connection {
        HttpURLConnection open(URI uri) throws IOException;
    }

    static void save(Context context, Uri destination, String source) throws IOException {
        if (!"content".equals(destination.getScheme())) throw new IOException("Save location is not a document");
        HttpURLConnection connection = connect(source, uri -> (HttpURLConnection) uri.toURL().openConnection());
        try {
            try (InputStream input = connection.getInputStream();
                 OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("Save location unavailable");
                long bytes = copy(input, output);
                long expected = connection.getContentLengthLong();
                if (bytes == 0 || (expected >= 0 && bytes != expected)) throw new IOException("Incomplete pin media");
            }
        } finally {
            connection.disconnect();
        }
    }

    static HttpURLConnection connect(String source, Connection factory) throws IOException {
        URI uri = PinMedia.mediaUri(source);
        if (uri == null) throw new IOException("Not a public Pinterest media URL");
        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpURLConnection connection = factory.open(uri);
            boolean returned = false;
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    returned = true;
                    return connection;
                }
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                    throw new IOException("Media response " + status);
                }
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Media redirect without location");
                try {
                    uri = PinMedia.mediaUri(uri.resolve(location).toString());
                } catch (IllegalArgumentException malformed) {
                    throw new IOException("Malformed media redirect", malformed);
                }
                if (uri == null) throw new IOException("Media redirected away from Pinterest CDN");
            } finally {
                if (!returned) connection.disconnect();
            }
        }
        throw new IOException("Too many media redirects");
    }

    static long copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32768];
        long total = 0;
        for (int size; (size = input.read(buffer)) != -1;) {
            output.write(buffer, 0, size);
            total += size;
        }
        return total;
    }
}
