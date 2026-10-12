/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import static app.hushpinterest.extension.pinterest.actions.MediaHostForTests.ORIGINALS;
import static app.hushpinterest.extension.pinterest.actions.MediaHostForTests.STAND_IN;
import static org.junit.Assert.*;

import android.graphics.Bitmap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Check the file: one ranged GET to the address a download would save, a body read no further
 * than 64 KiB, sizes only from what the answer can vouch for, and bounds decoded from real
 * picture bytes. Every answer comes from a table; nothing reaches the network.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FileCheckTest {
    private static final String ORIGINAL = "https://i.pinimg.com/originals/source.png";

    private FileHostForTests host;
    private CookieHandler cookies;

    @Before public void install() {
        cookies = CookieHandler.getDefault();
        host = FileHostForTests.install();
    }

    @After public void restore() {
        host.close();
        CookieHandler.setDefault(cookies);
    }

    @Test public void a206WithAWholeRangeGivesTheFileSizeAndTheBoundsTheBytesShow() throws Exception {
        host.partial(ORIGINAL, "bytes 0-65535/1234567",
                FileHostForTests.padded(FileHostForTests.picture(300, 200, Bitmap.CompressFormat.PNG), 65536));
        FileCheck.Facts facts = FileCheck.check(original(3000, 2000));
        assertEquals(ORIGINAL, facts.url);
        assertEquals(300, facts.width);
        assertEquals(200, facts.height);
        assertEquals("image/png", facts.mime);
        assertEquals(Long.valueOf(1234567), facts.size);

        assertEquals(1, host.asked.size());
        FileHostForTests.Answer get = host.asked.get(0);
        assertEquals(ORIGINAL, get.address());
        assertEquals("GET", get.method());
        assertEquals("bytes=0-65535", get.getRequestProperty("Range"));
        assertEquals("identity", get.getRequestProperty("Accept-Encoding"));
        assertFalse(get.followsRedirects());
        assertFalse(get.getUseCaches());
        assertEquals(5000, get.getConnectTimeout());
        assertEquals(5000, get.getReadTimeout());
        assertTrue(get.disconnected);
    }

    @Test public void aJpegSmallerThanTheRangeIsReadWholeAndDecoded() throws Exception {
        byte[] jpeg = FileHostForTests.picture(120, 90, Bitmap.CompressFormat.JPEG);
        host.partial(ORIGINAL, "bytes 0-" + (jpeg.length - 1) + "/" + jpeg.length, jpeg);
        FileCheck.Facts facts = FileCheck.check(original(3000, 2000));
        assertEquals(120, facts.width);
        assertEquals(90, facts.height);
        assertEquals("image/jpeg", facts.mime);
        assertEquals(Long.valueOf(jpeg.length), facts.size);
    }

    /** A 206's Content-Length is the part sent. Only a total the range states is the file's size. */
    @Test public void aRangeWithoutAUsableTotalLeavesTheSizeUnknown() throws Exception {
        byte[] png = FileHostForTests.padded(FileHostForTests.picture(64, 48, Bitmap.CompressFormat.PNG), 65536);
        for (String range : new String[]{"bytes 0-65535/*", "bytes 0-65535/abc", "bytes 0-65535/0",
                "bytes 0-65535/65535", "bytes 0-65535/-7", "bytes 0-65535/1e9"}) {
            FileCheck.forgetForTests();
            host.partial(ORIGINAL, range, png);
            FileCheck.Facts facts = FileCheck.check(original(3000, 2000));
            assertNull(range, facts.size);
            assertEquals(range, 64, facts.width);
            assertEquals(range, 48, facts.height);
        }
        assertEquals("the control: the same answer with a total gives it", Long.valueOf(65537),
                FileCheck.total("bytes 0-65535/65537"));
        assertEquals(Long.valueOf(9), FileCheck.total("BYTES 0-8/9"));
    }

    @Test public void aRangeThatIsntTheStartOfTheFileFailsTheCheck() throws Exception {
        byte[] png = FileHostForTests.padded(FileHostForTests.picture(64, 48, Bitmap.CompressFormat.PNG), 65536);
        for (String range : new String[]{null, "", "bytes 1-65536/1234567", "bytes 0-/1234567", "bytes=0-65535/1234567",
                "items 0-65535/1234567", "bytes 0-65535", "bytes 0-65535/12/34", "bytes  0-65535/1234567"}) {
            host.answer(ORIGINAL, url -> {
                FileHostForTests.Answer answer = new FileHostForTests.Answer(url, 206, new FileHostForTests.Body(png));
                return range == null ? answer : answer.header("Content-Range", range);
            });
            assertCheckFails(String.valueOf(range), original(3000, 2000));
        }
        assertNull(FileCheck.known(original(3000, 2000)));
    }

    @Test public void a200GivesItsContentLengthOnlyWhenItIsPositive() throws Exception {
        byte[] png = FileHostForTests.picture(80, 60, Bitmap.CompressFormat.PNG);
        host.answer(ORIGINAL, url -> new FileHostForTests.Answer(url, 200, new FileHostForTests.Body(png))
                .header("Content-Length", "48213"));
        assertEquals(Long.valueOf(48213), FileCheck.check(original(1, 1)).size);
        for (String length : new String[]{null, "0", "-1", "lots"}) {
            FileCheck.forgetForTests();
            host.answer(ORIGINAL, url -> {
                FileHostForTests.Answer answer = new FileHostForTests.Answer(url, 200, new FileHostForTests.Body(png));
                return length == null ? answer : answer.header("Content-Length", length);
            });
            FileCheck.Facts facts = FileCheck.check(original(1, 1));
            assertNull(String.valueOf(length), facts.size);
            assertEquals(80, facts.width);
        }
    }

    @Test public void anEncodedAnswerOrBytesThatArentAPictureFailTheCheck() throws Exception {
        byte[] png = FileHostForTests.picture(80, 60, Bitmap.CompressFormat.PNG);
        host.answer(ORIGINAL, url -> new FileHostForTests.Answer(url, 200, new FileHostForTests.Body(png))
                .header("Content-Length", "48213").header("Content-Encoding", "gzip"));
        assertCheckFails("gzip", original(1, 1));
        host.answer(ORIGINAL, url -> new FileHostForTests.Answer(url, 200,
                new FileHostForTests.Body("<html>Not here</html>".getBytes())).header("Content-Length", "21"));
        assertCheckFails("an error page's length isn't the file's", original(1, 1));
        host.partial(ORIGINAL, "bytes 0-65535/1234567", new byte[65536]);
        assertCheckFails("zeros", original(1, 1));
        host.answer(ORIGINAL, url -> new FileHostForTests.Answer(url, 200, new FileHostForTests.Body(png))
                .header("Content-Encoding", "identity"));
        assertEquals("the control: identity is the file as it is", 80, FileCheck.check(original(1, 1)).width);
    }

    @Test public void theBodyIsNeverReadPastTheFirst64KiB() throws Exception {
        byte[] large = FileHostForTests.padded(FileHostForTests.picture(500, 400, Bitmap.CompressFormat.PNG), 4 * 1024 * 1024);
        host.answer(ORIGINAL, url -> new FileHostForTests.Answer(url, 200, new FileHostForTests.Body(large, 1000, null))
                .header("Content-Length", String.valueOf(large.length)));
        FileCheck.Facts facts = FileCheck.check(original(1, 1));
        assertEquals(500, facts.width);
        assertEquals(Long.valueOf(large.length), facts.size);
        FileHostForTests.Answer get = host.asked.get(0);
        assertEquals(65536, get.body.served);
        assertEquals(1, get.inputOpens);
        assertTrue(get.disconnected);
    }

    @Test public void aBodyThatTricklesInGivesUpAtTheDeadline() {
        byte[] png = FileHostForTests.padded(FileHostForTests.picture(50, 50, Bitmap.CompressFormat.PNG), 65536);
        host.answer(ORIGINAL, url -> new FileHostForTests.Answer(url, 200,
                new FileHostForTests.Body(png, 1, () -> host.now.addAndGet(6000))));
        IOException failure = assertCheckFails("trickle", original(1, 1));
        assertTrue(failure instanceof SocketTimeoutException);
        FileHostForTests.Answer get = host.asked.get(0);
        assertTrue("read " + get.body.served, get.body.served <= 3);
        assertTrue(get.disconnected);
    }

    /** The deadline counts the wait for headers too: a host that never answers is dropped when it's up. */
    @Test public void aHostThatNeverAnswersIsDroppedAtTheDeadline() throws Exception {
        CountDownLatch dropped = new CountDownLatch(1);
        PinTransfer.Connection silent = uri -> new HttpURLConnection(uri.toURL()) {
            @Override public int getResponseCode() throws IOException {
                try {
                    dropped.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw new SocketException("Socket closed");
            }

            @Override public void disconnect() { dropped.countDown(); }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() {}
        };
        long started = System.nanoTime();
        try {
            PinTransfer.prefix(new PinMedia.Source(ORIGINAL, "image/png", ".png"), silent, () -> 0L, 300);
            fail("a host that never answered gave an answer");
        } catch (SocketTimeoutException expected) {
            assertEquals("The media host took too long", expected.getMessage());
        }
        assertTrue("waited out the host", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000);
        assertEquals("the waiting request was dropped", 0, dropped.getCount());
    }

    @Test public void aRedirectIsNotFollowedAndOtherAnswersReadNoBody() {
        host.answer(ORIGINAL, url -> new FileHostForTests.Answer(url, 302,
                new FileHostForTests.Body(FileHostForTests.picture(10, 10, Bitmap.CompressFormat.PNG)))
                .header("Location", "https://i.pinimg.com/originals/elsewhere.png"));
        assertCheckFails("302", original(1, 1));
        assertEquals(1, host.asked.size());
        assertFalse(host.asked.get(0).followsRedirects());
        assertEquals(0, host.asked.get(0).inputOpens);
        for (int status : new int[]{301, 304, 403, 404, 416, 500}) {
            host.asked.clear();
            host.answer(ORIGINAL, url -> new FileHostForTests.Answer(url, status, new FileHostForTests.Body(new byte[0])));
            assertCheckFails(String.valueOf(status), original(1, 1));
            assertEquals(0, host.asked.get(0).inputOpens);
            assertTrue(host.asked.get(0).disconnected);
        }
    }

    @Test public void aTimeoutOrAFailedConnectionFailsTheCheckAndIsntRemembered() {
        host.answer(ORIGINAL, url -> new FileHostForTests.Answer(url, 206, new FileHostForTests.Body(new byte[0]))
                .failing(new SocketTimeoutException("slow")));
        assertEquals("slow", assertCheckFails("timeout", original(1, 1)).getMessage());
        assertTrue(host.asked.get(0).disconnected);
        FileCheck.connection = uri -> { throw new IOException("offline"); };
        assertEquals("offline", assertCheckFails("offline", original(1, 1)).getMessage());
        assertNull(FileCheck.known(original(1, 1)));
    }

    @Test public void aCookieTheHandlerWouldAddStopsTheCheckBeforeItAsks() throws Exception {
        CookieHandler.setDefault(new CookieHandler() {
            @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
                return Collections.singletonMap("Cookie", Collections.singletonList("a=b"));
            }

            @Override public void put(URI uri, Map<String, List<String>> headers) {}
        });
        host.partial(ORIGINAL, "bytes 0-65535/1234567",
                FileHostForTests.padded(FileHostForTests.picture(10, 10, Bitmap.CompressFormat.PNG), 65536));
        IOException refused = assertCheckFails("cookie", original(1, 1));
        assertTrue(refused.getMessage(), refused.getMessage().contains("cookie"));
        assertTrue(host.asked.isEmpty());

        // What a CookieManager kept for the media host is dropped, and the check goes ahead without it.
        CookieManager manager = new CookieManager();
        CookieHandler.setDefault(manager);
        HttpCookie left = new HttpCookie("cdn", "x");
        left.setDomain(".pinimg.com");
        left.setPath("/");
        left.setVersion(0);
        manager.getCookieStore().add(URI.create(ORIGINAL), left);
        assertEquals(10, FileCheck.check(original(1, 1)).width);
        assertEquals(1, host.asked.size());
        assertTrue(manager.getCookieStore().get(URI.create(ORIGINAL)).isEmpty());
    }

    @Test public void aStandInChecksTheOriginalTheMediaHostHasAsADownloadWould() throws Exception {
        PinMedia.Resolution media = standIn();
        try (MediaHostForTests lookup = MediaHostForTests.install()) {
            lookup.answer(ORIGINALS + ".png", 200, "image/png");
            host.partial(ORIGINALS + ".png", "bytes 0-65535/2000000",
                    FileHostForTests.padded(FileHostForTests.picture(1200, 1800, Bitmap.CompressFormat.PNG), 65536));
            FileCheck.Facts facts = FileCheck.check(media);
            assertEquals(ORIGINALS + ".png", facts.url);
            assertEquals(1200, facts.width);
            assertEquals(1, host.asked.size());
            assertEquals(ORIGINALS + ".png", host.asked.get(0).address());

            // Reopening the details finds it by the supplied address, with no lookup and no GET.
            lookup.asked.clear();
            assertSame(facts, FileCheck.known(media));
            assertSame(facts, FileCheck.check(media));
            assertTrue(lookup.asked.isEmpty());
            assertEquals(1, host.asked.size());
        }
    }

    @Test public void a403OnTheOriginalChecksTheLargestSuppliedSizeInstead() throws Exception {
        PinMedia.Resolution media = standIn();
        try (MediaHostForTests lookup = MediaHostForTests.install()) {
            host.partial(STAND_IN, "bytes 0-65535/98765",
                    FileHostForTests.padded(FileHostForTests.picture(736, 1104, Bitmap.CompressFormat.JPEG), 65536));
            FileCheck.Facts facts = FileCheck.check(media);
            assertEquals(4, lookup.asked.size());
            assertEquals(STAND_IN, facts.url);
            assertEquals(Collections.singletonList(STAND_IN), addresses());
            assertEquals("image/jpeg", facts.mime);
            assertEquals(Long.valueOf(98765), facts.size);
        }
    }

    /** A lookup that timed out checks the supplied size, but the next tap asks again, as the next download would. */
    @Test public void aCheckAfterAnUnansweredLookupIsntRemembered() throws Exception {
        PinMedia.Resolution media = standIn();
        try (MediaHostForTests lookup = MediaHostForTests.install()) {
            lookup.fail(ORIGINALS + ".jpg", new SocketTimeoutException("slow"));
            host.partial(STAND_IN, "bytes 0-65535/98765",
                    FileHostForTests.padded(FileHostForTests.picture(736, 1104, Bitmap.CompressFormat.JPEG), 65536));
            host.partial(ORIGINALS + ".png", "bytes 0-65535/2000000",
                    FileHostForTests.padded(FileHostForTests.picture(1200, 1800, Bitmap.CompressFormat.PNG), 65536));
            assertEquals(STAND_IN, FileCheck.check(media).url);
            assertNull(FileCheck.known(media));

            lookup.answer(ORIGINALS + ".png", 200, "image/png").answer(ORIGINALS + ".jpg", 404, "application/xml");
            FileCheck.Facts facts = FileCheck.check(media);
            assertEquals(ORIGINALS + ".png", facts.url);
            assertEquals(1200, facts.width);
            assertSame(facts, FileCheck.known(media));
        }
    }

    @Test public void aCheckIsRememberedForFifteenMinutesThenAskedAgain() throws Exception {
        host.partial(ORIGINAL, "bytes 0-65535/1234567",
                FileHostForTests.padded(FileHostForTests.picture(30, 20, Bitmap.CompressFormat.PNG), 65536));
        PinMedia.Resolution media = original(1, 1);
        assertNull(FileCheck.known(media));
        FileCheck.Facts facts = FileCheck.check(media);
        assertSame(facts, FileCheck.check(media));
        assertEquals(1, host.asked.size());
        host.now.addAndGet(FileCheck.FRESH_MS - 1);
        assertSame(facts, FileCheck.known(media));
        host.now.addAndGet(1);
        assertNull(FileCheck.known(media));
        assertNotSame(facts, FileCheck.check(media));
        assertEquals(2, host.asked.size());
        assertEquals(15 * 60 * 1000L, FileCheck.FRESH_MS);
    }

    @Test public void onlyThe32AddressesUsedLastAreRemembered() {
        for (int i = 0; i < FileCheck.CAPACITY; i++) FileCheck.remember(address(i), facts(i));
        assertNotNull("touching it makes it the newest", FileCheck.remembered(address(0)));
        FileCheck.remember(address(FileCheck.CAPACITY), facts(FileCheck.CAPACITY));
        assertNotNull(FileCheck.remembered(address(0)));
        assertNull("the one used longest ago went", FileCheck.remembered(address(1)));
        for (int i = 2; i <= FileCheck.CAPACITY; i++) assertNotNull(address(i), FileCheck.remembered(address(i)));
        assertEquals(32, FileCheck.CAPACITY);
    }

    @Test public void videosAndPinsWithoutAnImageAreNeverChecked() {
        PinMedia.Resolution video = PinMedia.resolve(Map.of("id", "123", "videos", Map.of("video_list", Map.of(
                "large", Map.of("url", "https://v.pinimg.com/large.mp4", "width", 1920, "height", 1080)))));
        PinMedia.Resolution missing = PinMedia.resolve(Map.of("id", "123", "images", Map.of()));
        for (PinMedia.Resolution media : new PinMedia.Resolution[]{video, missing, null}) {
            assertFalse(FileCheck.checkable(media));
            assertNull(FileCheck.known(media));
        }
        assertCheckFails("video", video);
        assertCheckFails("missing", missing);
        assertTrue(host.asked.isEmpty());
        assertTrue("the control", FileCheck.checkable(original(1, 1)));
    }

    private static PinMedia.Resolution original(int width, int height) {
        return PinMedia.resolve(Map.of("id", "123", "images", Map.of("orig", Map.of(
                "url", ORIGINAL, "width", width, "height", height))));
    }

    private static PinMedia.Resolution standIn() {
        PinMedia.Resolution media = PinMedia.resolve(Map.of("id", "123", "images", Map.of(
                "736x", Map.of("url", STAND_IN, "width", 736, "height", 1104))));
        assertTrue(PinMedia.standIn(media));
        return media;
    }

    private static String address(int i) { return "https://i.pinimg.com/originals/" + i + ".png"; }

    private static FileCheck.Facts facts(int i) { return new FileCheck.Facts(address(i), i + 1, i + 1, "image/png", null); }

    private List<String> addresses() {
        return host.asked.stream().map(FileHostForTests.Answer::address).collect(java.util.stream.Collectors.toList());
    }

    private static IOException assertCheckFails(String why, PinMedia.Resolution media) {
        try {
            FileCheck.check(media);
        } catch (IOException expected) {
            return expected;
        }
        throw new AssertionError("the check passed: " + why);
    }
}
