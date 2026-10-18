/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** PinTransfer.fetch: one GET of a whole file into a path, bounded in size and time. */
public class PinTransferFetchTest {
    private static final String URL = "https://i.pinimg.com/originals/source.png";
    private static final PinMedia.Source SOURCE = new PinMedia.Source(URL, "image/png", ".png");

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private final AtomicLong now = new AtomicLong(5_000L);

    private PinTransfer.Connection answering(Function<java.net.URL, FileHostForTests.Answer> answer) {
        return uri -> answer.apply(uri.toURL());
    }

    private static byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 7);
        return bytes;
    }

    @Test public void aWholeFileIsWrittenAndItsStatusAndTypeReturned() throws Exception {
        byte[] body = bytes(40_000);
        File into = temporary.newFile("a.part");
        PinTransfer.Fetched got = PinTransfer.fetch(SOURCE, into, 100_000, type -> true,
                answering(url -> new FileHostForTests.Answer(url, 200, new FileHostForTests.Body(body))
                        .header("Content-Type", "image/png").header("Content-Length", "40000")), now::get, 30_000);
        assertEquals(200, got.status);
        assertEquals("image/png", got.type);
        assertEquals(40_000, got.written);
        assertArrayEquals(body, Files.readAllBytes(into.toPath()));
    }

    @Test public void aBodyThatGrowsPastTheLimitStopsAtItAndLeavesNoFile() throws Exception {
        File into = temporary.newFile("b.part");
        FileHostForTests.Body body = new FileHostForTests.Body(bytes(500_000));
        try {
            PinTransfer.fetch(SOURCE, into, 100_000, type -> true,
                    answering(url -> new FileHostForTests.Answer(url, 200, body).header("Content-Type", "image/png")),
                    now::get, 30_000);
            fail("sent more than the limit with no length given");
        } catch (PinTransfer.TooLarge expected) {
            assertTrue("it read little past the limit: " + body.served, body.served <= 100_000 + 16_384);
        }
        assertFalse(into.exists());
    }

    @Test public void aDeclaredLengthOverTheLimitIsRefusedUnread() throws Exception {
        File into = temporary.newFile("c.part");
        FileHostForTests.Body body = new FileHostForTests.Body(bytes(10));
        try {
            PinTransfer.fetch(SOURCE, into, 100_000, type -> true,
                    answering(url -> new FileHostForTests.Answer(url, 200, body)
                            .header("Content-Type", "image/png").header("Content-Length", "100001")), now::get, 30_000);
            fail("took a file over the limit");
        } catch (PinTransfer.TooLarge expected) {
            assertEquals(0, body.served);
        }
    }

    @Test public void aStatusOrTypeThatIsntWantedReadsNothing() throws Exception {
        File into = temporary.newFile("d.part");
        FileHostForTests.Body body = new FileHostForTests.Body(bytes(10));
        PinTransfer.Fetched notFound = PinTransfer.fetch(SOURCE, into, 100_000, type -> true,
                answering(url -> new FileHostForTests.Answer(url, 404, body).header("Content-Type", "image/png")), now::get, 30_000);
        assertEquals(404, notFound.status);
        assertEquals(0, notFound.written);
        PinTransfer.Fetched wrongType = PinTransfer.fetch(SOURCE, into, 100_000, "image/png"::equals,
                answering(url -> new FileHostForTests.Answer(url, 200, body).header("Content-Type", "text/html")), now::get, 30_000);
        assertEquals(200, wrongType.status);
        assertEquals("text/html", wrongType.type);
        assertEquals(0, wrongType.written);
        assertEquals(0, body.served);
    }

    @Test public void aSlowBodyRunsOutOfTheBudgetAndLeavesNoFile() throws Exception {
        File into = temporary.newFile("e.part");
        FileHostForTests.Body body = new FileHostForTests.Body(bytes(500_000), 4096, () -> now.addAndGet(1_000));
        try {
            PinTransfer.fetch(SOURCE, into, 1_000_000, type -> true,
                    answering(url -> new FileHostForTests.Answer(url, 200, body).header("Content-Type", "image/png")),
                    now::get, 30_000);
            fail("kept reading past the budget");
        } catch (SocketTimeoutException expected) {
            assertTrue(body.served < 500_000);
        }
        assertFalse(into.exists());
    }

    @Test public void aNonPinterestAddressIsRefusedBeforeAnyRequest() throws Exception {
        File into = temporary.newFile("f.part");
        int[] opened = {0};
        try {
            PinTransfer.fetch(new PinMedia.Source("https://example.com/x.png", "image/png", ".png"), into, 100, type -> true,
                    uri -> {
                        opened[0]++;
                        throw new IOException("should not open");
                    }, now::get, 30_000);
            fail("fetched from a host that isn't Pinterest's");
        } catch (IOException expected) {
            assertEquals(0, opened[0]);
        }
    }
}
