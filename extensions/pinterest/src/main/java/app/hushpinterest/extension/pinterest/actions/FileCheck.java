/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.graphics.BitmapFactory;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Media details' Check the file. Pinterest's width and height can describe a display size rather
 * than the file a download saves, and it never says how big that file is. On the user's tap only,
 * this reads the start of the file a download would save (one ranged GET through
 * {@link PinTransfer#prefix}) and reports what the bytes show: the picture's width, height and
 * type, and the whole file's size when the answer states it. Worker threads only.
 *
 * <p>What a check found stays for {@link #FRESH_MS}, for the {@link #CAPACITY} addresses used
 * last, so reopening the details shows it without asking the media host again.
 */
final class FileCheck {
    private FileCheck() {}

    /** Tests replace it so they never reach the network. */
    static volatile PinTransfer.Connection connection = PinTransfer.NETWORK;

    /** Milliseconds that only move forward. Tests replace it to age what's remembered. */
    static volatile LongSupplier clock = SystemClock::elapsedRealtime;

    static final int CAPACITY = 32;
    static final long FRESH_MS = 15 * 60 * 1000L;

    /** What the start of one file showed. */
    static final class Facts {
        /** The address that was read. */
        final String url;
        final int width, height;
        final String mime;
        /** The whole file in bytes, or null when the answer didn't state it. */
        @Nullable final Long size;

        Facts(String url, int width, int height, String mime, @Nullable Long size) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.mime = mime;
            this.size = size;
        }
    }

    private static final class Remembered {
        final Facts facts;
        final long at;

        Remembered(Facts facts, long at) {
            this.facts = facts;
            this.at = at;
        }
    }

    /** Access order, so the address used longest ago goes first. Guarded by FileCheck.class. */
    private static final LinkedHashMap<String, Remembered> REMEMBERED =
            new LinkedHashMap<String, Remembered>(CAPACITY + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Remembered> eldest) {
                    return size() > CAPACITY;
                }
            };

    /** An image a download would save. Videos and pins without a usable address aren't checked. */
    static boolean checkable(@Nullable PinMedia.Resolution media) {
        return media != null && media.source != null && media.source.mime.startsWith("image/");
    }

    /** What an earlier check found for [media] while it's fresh, or null. Never asks the network. */
    @Nullable
    static Facts known(@Nullable PinMedia.Resolution media) {
        return checkable(media) ? remembered(media.source.url) : null;
    }

    /**
     * Checks the file a download of [media] would save: for a stand-in size, the original when the
     * media host has one ({@link OriginalLookup}, the downloads' own lookup), else the supplied
     * address. Throws when the answer can't vouch for anything. When the lookup couldn't get an
     * answer, the supplied size is checked but not remembered, so the next tap asks again, as the
     * next download would.
     */
    static Facts check(PinMedia.Resolution media) throws IOException {
        if (!checkable(media)) throw new IOException("Only a supplied image can be checked");
        Facts known = remembered(media.source.url);
        if (known != null) return known;
        OriginalLookup.Found found = PinMedia.standIn(media)
                ? OriginalLookup.look(media.source) : new OriginalLookup.Found(media.source, true);
        PinMedia.Source chosen = found.source;
        Facts facts = remembered(chosen.url);
        if (facts == null) {
            facts = read(chosen.url, PinTransfer.prefix(chosen, connection, clock));
            if (!found.settled) return facts;
            remember(chosen.url, facts);
        }
        // The supplied address leads to the checked one, so reopening the details needs no lookup.
        if (!chosen.url.equals(media.source.url)) remember(media.source.url, facts);
        return facts;
    }

    /**
     * What an answer shows. A 206 has to say it starts at the file's first byte, or its bytes
     * aren't the start of the file; its total is the file's size. A 200 is the whole file, so its
     * Content-Length is the size when it gives one. The bytes have to decode as a picture's
     * bounds, which also keeps an error page's length from passing for the file's.
     */
    static Facts read(String url, PinTransfer.Prefix answer) throws IOException {
        if (answer.status != 200 && answer.status != 206) throw new IOException("The media host answered " + answer.status);
        if (answer.encoding != null && !answer.encoding.trim().equalsIgnoreCase("identity")) {
            throw new IOException("The media host sent the file encoded");
        }
        Long size = answer.status == 206 ? total(answer.range) : answer.length > 0 ? Long.valueOf(answer.length) : null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        // Reads only the header: no pixels are decoded and no bitmap is made.
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(answer.bytes, 0, answer.bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null) {
            throw new IOException("The start of the file isn't a picture Android can read");
        }
        return new Facts(url, bounds.outWidth, bounds.outHeight, bounds.outMimeType, size);
    }

    private static final Pattern RANGE = Pattern.compile("bytes 0-([0-9]{1,18})/([^/ ]+)", Pattern.CASE_INSENSITIVE);

    /**
     * The whole file's size from a 206 answer's Content-Range ("bytes 0-N/TOTAL"). Null when the
     * total is "*" or can't be right: not a positive number, or not past the last byte sent. A
     * range that doesn't start at byte 0 throws, as those bytes aren't the file's start.
     */
    @Nullable
    static Long total(@Nullable String range) throws IOException {
        Matcher matcher = range == null ? null : RANGE.matcher(range.trim());
        if (matcher == null || !matcher.matches()) throw new IOException("The answer isn't the start of the file");
        String total = matcher.group(2);
        if (!total.matches("[0-9]{1,18}")) return null;
        long whole = Long.parseLong(total);
        return whole > 0 && Long.parseLong(matcher.group(1)) < whole ? Long.valueOf(whole) : null;
    }

    @Nullable
    static synchronized Facts remembered(String url) {
        Remembered entry = REMEMBERED.get(url);
        if (entry == null) return null;
        long age = clock.getAsLong() - entry.at;
        if (age >= 0 && age < FRESH_MS) return entry.facts;
        REMEMBERED.remove(url);
        return null;
    }

    static synchronized void remember(String url, Facts facts) {
        REMEMBERED.put(url, new Remembered(facts, clock.getAsLong()));
    }

    static synchronized void forgetForTests() {
        REMEMBERED.clear();
    }
}
