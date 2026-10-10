/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.PatchFamilyForTests;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;
import app.hushpinterest.extension.shared.settings.HushPinterestPause;
import app.hushpinterest.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PlainPinLinksTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Pinterest's kind of shared object, as its enum names it. */
    enum Kind { NONE, PIN, BOARD }

    private static final String SHORT = "https://pin.it/AbC_1-x";
    private static final String PLAIN = "https://www.pinterest.com/pin/123456/";

    private final List<URI> asked = new ArrayList<>();

    @Before public void on() {
        PlainPinLinks.forgetForTests();
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.LINK_TRACKING, PatchFamily.Capability.PLAIN_PIN_LINKS));
        Settings.PLAIN_PIN_LINKS.save(true);
        // Nothing here may reach the network unless a test answers for it.
        PlainPinLinks.lookup = (uri, timeout) -> {
            asked.add(uri);
            throw new AssertionError("asked " + uri);
        };
    }

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        shadowOf(Looper.getMainLooper()).idle();
        PauseForTests.resume();
        Settings.PLAIN_PIN_LINKS.resetToDefault();
        Settings.STRIP_LINK_TRACKING.resetToDefault();
        PatchFamilyForTests.capabilities(null);
        PlainPinLinks.forgetForTests();
        HookStatus.clear();
    }

    @Test public void aPinsOwnInviteLinkBecomesThePlainLinkWithNoRequest() {
        PlainPinLinks.invite(Kind.PIN, "123456", SHORT);
        assertEquals(PLAIN, LinkTracking.cleanText(SHORT).toString());
        assertEquals(PLAIN, LinkTracking.cleanText("http://PIN.IT/AbC_1-x/").toString());
        assertEquals("Look at this " + PLAIN + ".", LinkTracking.cleanText("Look at this " + SHORT + ".").toString());
        assertEquals("(" + PLAIN + ")", LinkTracking.cleanText("(" + SHORT + ")").toString());
        assertEquals("Recipe\n\n" + PLAIN, LinkTracking.cleanText("Recipe\n\n" + SHORT).toString());
        // Strip link tracking takes the tracking off first, and what's left is the short link itself.
        assertEquals(PLAIN, LinkTracking.cleanText(SHORT + "?utm_source=share").toString());
        assertEquals(PLAIN, LinkTracking.putStringExtra(new Intent(), Intent.EXTRA_TEXT, SHORT).getStringExtra(Intent.EXTRA_TEXT));
        assertEquals(PLAIN, String.valueOf(LinkTracking.putTextExtra(new Intent(), Intent.EXTRA_TEXT, SHORT)
                .getCharSequenceExtra(Intent.EXTRA_TEXT)));
        ClipData clip = LinkTracking.newPlainText(SHORT, SHORT);
        assertEquals(PLAIN, clip.getItemAt(0).getText().toString());
        assertEquals(PLAIN, clip.getDescription().getLabel().toString());
        assertEquals(Collections.emptyList(), asked);
    }

    @Test public void slugsAreMatchedExactlyAndOtherShortLinksStayAsTheyAre() {
        PlainPinLinks.invite(Kind.PIN, "123456", SHORT);
        for (String other : new String[]{"https://pin.it/abc_1-x", "https://pin.it/AbC_1-xy", "https://pin.it/AbC_1",
                "https://pin.it/AbC_1-x/more", "https://pin.it/AbC_1-x#top"}) {
            assertEquals(other, LinkTracking.cleanText(other).toString());
        }
        assertEquals(Collections.emptyList(), asked);
    }

    @Test public void textThatIsNotAPinterestShortLinkIsNeverTouched() {
        PlainPinLinks.invite(Kind.PIN, "123456", SHORT);
        Settings.STRIP_LINK_TRACKING.save(false);
        for (String text : new String[]{"no links at all", "pin.it/AbC_1-x", "https://example.org/pin.it/AbC_1-x",
                "https://pin.it.example.org/AbC_1-x", "https://pin.itx/AbC_1-x", "https://evil.example/?u=https://pin.it/AbC_1-x",
                "ftp://pin.it/AbC_1-x", "https://www.pinterest.com/pin/1/"}) {
            assertSame(text, LinkTracking.cleanText(text));
            ClipData clip = LinkTracking.newPlainText("Copied", text);
            assertEquals(text, clip.getItemAt(0).getText().toString());
        }
        assertNull(LinkTracking.cleanText(null));
        assertEquals(Collections.emptyList(), asked);
    }

    @Test public void onlyAPinWithANumericIdAndAPinItLinkIsRecorded() {
        PlainPinLinks.invite(Kind.BOARD, "1", SHORT);
        PlainPinLinks.invite("PIN", "2", SHORT);
        PlainPinLinks.invite(null, "3", SHORT);
        PlainPinLinks.invite(Kind.PIN, "12a", SHORT);
        PlainPinLinks.invite(Kind.PIN, null, SHORT);
        PlainPinLinks.invite(Kind.PIN, "4", "https://www.pinterest.com/pin/4/");
        PlainPinLinks.invite(Kind.PIN, "5", null);
        assertNull(PlainPinLinks.known(SHORT));
        PlainPinLinks.invite(Kind.PIN, "6", " " + SHORT + " ");
        assertEquals("https://www.pinterest.com/pin/6/", PlainPinLinks.known(SHORT));
    }

    @Test public void aSlugSeenWithTwoPinsIsNeverRewrittenFromTheRecord() {
        PlainPinLinks.invite(Kind.PIN, "1", SHORT);
        PlainPinLinks.invite(Kind.PIN, "1", SHORT);
        assertEquals("https://www.pinterest.com/pin/1/", PlainPinLinks.known(SHORT));
        PlainPinLinks.invite(Kind.PIN, "2", SHORT);
        assertNull(PlainPinLinks.known(SHORT));
        PlainPinLinks.invite(Kind.PIN, "1", SHORT);
        assertNull(PlainPinLinks.known(SHORT));
        assertEquals(SHORT, LinkTracking.putStringExtra(new Intent(), Intent.EXTRA_TEXT, SHORT).getStringExtra(Intent.EXTRA_TEXT));
    }

    @Test public void aShareStraightToOneAppGetsThePlainLinkBeforeTheAppOpens() {
        // On a phone the patch fills the stubs that read the shared object's kind and id. Here they
        // answer null, so record stands in for them.
        assertTrue(PlainPinLinks.record(Kind.PIN, "123456", SHORT));
        Intent share = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "Look at this " + SHORT);
        PlainPinLinks.directShare(new Object(), SHORT, share);
        assertEquals("Look at this " + PLAIN, share.getStringExtra(Intent.EXTRA_TEXT));

        // A short link whose pin isn't known stays as it is, and nothing is asked of the network.
        Intent unknown = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://pin.it/Direct1");
        PlainPinLinks.directShare(new Object(), "https://pin.it/Direct1", unknown);
        assertEquals("https://pin.it/Direct1", unknown.getStringExtra(Intent.EXTRA_TEXT));

        // Off or paused, the share keeps Pinterest's text.
        Intent off = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, SHORT);
        Settings.PLAIN_PIN_LINKS.save(false);
        PlainPinLinks.directShare(new Object(), SHORT, off);
        assertEquals(SHORT, off.getStringExtra(Intent.EXTRA_TEXT));
        Settings.PLAIN_PIN_LINKS.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        PlainPinLinks.directShare(new Object(), SHORT, off);
        assertEquals(SHORT, off.getStringExtra(Intent.EXTRA_TEXT));
        PauseForTests.resume();

        // Missing pieces change nothing and throw nothing.
        PlainPinLinks.directShare(null, null, null);
        PlainPinLinks.directShare(null, 42, share);
        Intent empty = new Intent(Intent.ACTION_SEND);
        PlainPinLinks.directShare(null, SHORT, empty);
        assertNull(empty.getStringExtra(Intent.EXTRA_TEXT));
        assertEquals(Collections.emptyList(), asked);
    }

    @Test public void recordTakesOnlyAPinWithANumericIdAndAWholePinItLink() {
        assertFalse(PlainPinLinks.record(Kind.BOARD, "1", SHORT));
        assertFalse(PlainPinLinks.record(null, "1", SHORT));
        assertFalse(PlainPinLinks.record(Kind.PIN, null, SHORT));
        assertFalse(PlainPinLinks.record(Kind.PIN, "1", null));
        assertFalse(PlainPinLinks.record(Kind.PIN, "1", SHORT + "?invite_code=abc"));
        assertNull(PlainPinLinks.known(SHORT));
        assertTrue(PlainPinLinks.record(Kind.PIN, "9", SHORT));
        assertEquals("https://www.pinterest.com/pin/9/", PlainPinLinks.known(SHORT));
    }

    @Test public void offPausedOrNotInThisBuildLeavesLinksAsTheyWere() {
        PlainPinLinks.invite(Kind.PIN, "123456", SHORT);
        Settings.PLAIN_PIN_LINKS.save(false);
        assertSame(SHORT, LinkTracking.cleanText(SHORT));
        assertEquals(SHORT, LinkTracking.newPlainText("Copied", SHORT).getItemAt(0).getText().toString());

        Settings.PLAIN_PIN_LINKS.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        assertSame(SHORT, LinkTracking.cleanText(SHORT));
        assertEquals(SHORT, LinkTracking.newPlainText("Copied", SHORT).getItemAt(0).getText().toString());
        // An invite seen while paused isn't recorded either.
        String other = "https://pin.it/Paused1";
        PlainPinLinks.invite(Kind.PIN, "77", other);
        PauseForTests.resume();
        assertNull(PlainPinLinks.known(other));

        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.LINK_TRACKING));
        assertSame(SHORT, LinkTracking.cleanText(SHORT));
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.LINK_TRACKING, PatchFamily.Capability.PLAIN_PIN_LINKS));
        assertEquals(PLAIN, LinkTracking.cleanText(SHORT).toString());

        // Plain pin links has its own switch: with Strip link tracking off it still makes the link plain,
        // and tracking stays in the rest of the text.
        Settings.STRIP_LINK_TRACKING.save(false);
        assertEquals(PLAIN + " https://example.org/?utm_source=x",
                LinkTracking.cleanText(SHORT + " https://example.org/?utm_source=x").toString());
        assertEquals(Collections.emptyList(), asked);
    }

    @Test public void aCopiedUnknownShortLinkIsLookedUpAndSwappedWhileTheClipboardStillHoldsIt() throws Exception {
        answer(Map.of(
                "https://pin.it/Zz9", "https://api.pinterest.com/url_shortener/Zz9/redirect/",
                "https://api.pinterest.com/url_shortener/Zz9/redirect/",
                "https://www.pinterest.com/pin/987/sent/?invite_code=abc&sender=55&sfo=1"));
        ClipboardManager clipboard = clipboard();
        ClipData copied = LinkTracking.newPlainText("Pin link", "https://pin.it/Zz9");
        assertEquals("https://pin.it/Zz9", copied.getItemAt(0).getText().toString());
        clipboard.setPrimaryClip(copied);
        settle();
        assertEquals("https://www.pinterest.com/pin/987/", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals("Pin link", clipboard.getPrimaryClip().getDescription().getLabel().toString());
        assertEquals(Arrays.asList(URI.create("https://pin.it/Zz9"), URI.create("https://api.pinterest.com/url_shortener/Zz9/redirect/")), asked);
        // Later shares of the same link use the answer without asking again.
        assertEquals("https://www.pinterest.com/pin/987/",
                LinkTracking.putStringExtra(new Intent(), Intent.EXTRA_TEXT, "https://pin.it/Zz9").getStringExtra(Intent.EXTRA_TEXT));
        assertEquals(2, asked.size());
    }

    @Test public void aClipboardThatChangedMeanwhileIsLeftAlone() throws Exception {
        answer(Map.of("https://pin.it/Zz9", "https://www.pinterest.com/pin/987/"));
        ClipboardManager clipboard = clipboard();
        clipboard.setPrimaryClip(LinkTracking.newPlainText("Pin link", "https://pin.it/Zz9"));
        clipboard.setPrimaryClip(ClipData.newPlainText("Note", "something else"));
        settle();
        assertEquals("something else", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
    }

    @Test public void sharedTextNeverWaitsOnTheNetwork() throws Exception {
        String unknown = "https://pin.it/Shared1";
        assertEquals(unknown, LinkTracking.putStringExtra(new Intent(), Intent.EXTRA_TEXT, unknown).getStringExtra(Intent.EXTRA_TEXT));
        assertEquals(unknown, String.valueOf(LinkTracking.putTextExtra(new Intent(), Intent.EXTRA_TEXT, unknown)
                .getCharSequenceExtra(Intent.EXTRA_TEXT)));
        settle();
        assertEquals(Collections.emptyList(), asked);
    }

    @Test public void anyFailureKeepsTheOriginalShortLink() throws Exception {
        Map<String, String> wrong = new HashMap<>();
        wrong.put("https://pin.it/Board1", "https://www.pinterest.it/someone/recipes/");
        wrong.put("https://pin.it/Board2", "https://www.pinterest.com/someone/recipes/");
        wrong.put("https://pin.it/Http1", "http://www.pinterest.com/pin/1/");
        wrong.put("https://pin.it/Away1", "https://evil.example/pin/1/");
        wrong.put("https://pin.it/User1", "https://someone@www.pinterest.com/pin/1/");
        wrong.put("https://pin.it/Port1", "https://www.pinterest.com:8443/pin/1/");
        wrong.put("https://pin.it/Spoof1", "https://www.pinterest.com.evil.example/pin/1/");
        wrong.put("https://pin.it/Bad1", "https://www.pinterest.com/pin/ 1/");
        wrong.put("https://pin.it/Loop1", "https://api.pinterest.com/url_shortener/Loop1/redirect/");
        wrong.put("https://api.pinterest.com/url_shortener/Loop1/redirect/", "https://pin.it/Loop1");
        answer(wrong);
        long deadline = PlainPinLinks.clock.getAsLong() + PlainPinLinks.BUDGET_MS;
        for (String slug : new String[]{"Board1", "Board2", "Http1", "Away1", "User1", "Port1", "Spoof1", "Bad1", "Loop1", "None1"}) {
            assertNull(slug, PlainPinLinks.resolve(slug, deadline));
        }
        // The redirect loop stops after four requests.
        assertEquals(4, asked.stream().filter(uri -> uri.getPath().contains("Loop1")).count());
        // Nothing went anywhere but Pinterest's short link hosts.
        for (URI uri : asked) assertTrue(uri.toString(), uri.getHost().equals("pin.it") || uri.getHost().equals("api.pinterest.com"));

        PlainPinLinks.lookup = (uri, timeout) -> { throw new IOException("offline"); };
        assertNull(PlainPinLinks.resolve("Offline1", deadline));

        // A copy whose lookup fails keeps the short link on the clipboard.
        ClipboardManager clipboard = clipboard();
        clipboard.setPrimaryClip(LinkTracking.newPlainText("Pin link", "https://pin.it/Offline1"));
        settle();
        assertEquals("https://pin.it/Offline1", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertNull(PlainPinLinks.known("https://pin.it/Offline1"));
    }

    @Test public void aLookupPastFourSecondsIsDropped() {
        AtomicLong now = new AtomicLong(1_000);
        PlainPinLinks.clock = now::get;
        List<Integer> timeouts = new ArrayList<>();
        PlainPinLinks.lookup = (uri, timeout) -> {
            timeouts.add(timeout);
            now.addAndGet(3_000);
            return uri.getHost().equals("pin.it") ? "https://api.pinterest.com/url_shortener/Slow1/redirect/"
                    : "https://www.pinterest.com/pin/5/";
        };
        assertNull(PlainPinLinks.resolve("Slow1", now.get() + PlainPinLinks.BUDGET_MS));
        // Each hop gets at most two seconds, and the second only what's left of the four.
        assertEquals(Arrays.asList(2_000, 500), timeouts);

        now.set(1_000);
        timeouts.clear();
        PlainPinLinks.lookup = (uri, timeout) -> {
            timeouts.add(timeout);
            now.addAndGet(5_000);
            return "https://www.pinterest.com/pin/5/";
        };
        assertNull(PlainPinLinks.resolve("Slow2", now.get() + PlainPinLinks.BUDGET_MS));
        assertEquals(Collections.singletonList(2_000), timeouts);
    }

    @Test public void theRequestStartsAtThePinItLinkItself() {
        answer(Map.of("https://pin.it/Zz9", "/pin/42/"));
        // A relative answer from pin.it names pin.it, which isn't a pin page, so it's asked again.
        assertNull(PlainPinLinks.resolve("Zz9", PlainPinLinks.clock.getAsLong() + PlainPinLinks.BUDGET_MS));
        assertEquals(URI.create("https://pin.it/Zz9"), asked.get(0));
        asked.clear();
        answer(Map.of("https://pin.it/Zz8", "https://pinterest.com/pin/43"));
        assertEquals("43", PlainPinLinks.resolve("Zz8", PlainPinLinks.clock.getAsLong() + PlainPinLinks.BUDGET_MS));
    }

    private void answer(Map<String, String> redirects) {
        PlainPinLinks.lookup = (uri, timeout) -> {
            asked.add(uri);
            assertTrue("timeout " + timeout, timeout > 0 && timeout <= 2_000);
            return redirects.get(uri.toString());
        };
    }

    private static ClipboardManager clipboard() {
        return (ClipboardManager) RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE);
    }

    private static void settle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        shadowOf(Looper.getMainLooper()).idle();
    }
}
