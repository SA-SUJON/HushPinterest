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

import android.content.ClipData;
import android.content.Intent;

import java.util.Arrays;
import java.util.Collections;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.settings.HushPinterestPause;
import app.hushpinterest.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LinkTrackingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() {
        PauseForTests.resume();
        Settings.STRIP_LINK_TRACKING.resetToDefault();
    }

    @Test public void cleansKnownParametersAndKeepsRawDestinationParametersAndFragment() {
        assertEquals("https://example.org/a?filter=a%2Bb&filter=c#preview",
                LinkTracking.cleanUrl("https://example.org/a?utm_source=pin&filter=a%2Bb&fbclid=abc&filter=c#preview"));
        assertEquals("https://example.org/a?&filter=1",
                LinkTracking.cleanUrl("https://example.org/a?&utm_medium=app&filter=1"));
        assertEquals("https://example.org/a#preview", LinkTracking.cleanUrl("https://example.org/a?utm_source=pin#preview"));
    }

    @Test public void matchesDecodedNamesWithoutDecodingFunctionalValues() {
        assertEquals("https://example.org/a?id=4%26foo%3Dbar",
                LinkTracking.cleanUrl("https://example.org/a?%75tm_source=x&id=4%26foo%3Dbar&UTM_MEDIUM=app"));
    }

    @Test public void pinterestSenderTrackingIsHostScopedAndShortTokensStayIntact() {
        assertEquals("https://www.pinterest.com/pin/123/?foo=bar",
                LinkTracking.cleanUrl("https://www.pinterest.com/pin/123/?sender=999&foo=bar&tracking_id=5"));
        assertEquals("https://example.org/pin/123?sender=999",
                LinkTracking.cleanUrl("https://example.org/pin/123?sender=999&utm_source=pin"));
        assertEquals("https://pin.it/OpaqueToken", LinkTracking.cleanUrl("https://pin.it/OpaqueToken?utm_source=app"));
        String spoof = "https://pinterest.com.example.org/a?sender=123";
        assertEquals(spoof, LinkTracking.cleanUrl(spoof));
    }

    @Test public void signedLinksAndSignInParametersArePreservedExactly() {
        for (String marker : new String[]{"signature=secret", "code=sign-in", "X-Amz-Signature=secret", "token=secret"}) {
            String original = "https://example.org/a?utm_source=pin&" + marker;
            assertEquals(original, LinkTracking.cleanUrl(original));
        }
    }

    @Test public void keepsTextAndPunctuationAroundMultipleUrls() {
        assertEquals("First https://example.org/a. Then (https://www.pinterest.com/pin/123/).",
                LinkTracking.cleanText("First https://example.org/a. Then (https://www.pinterest.com/pin/123/?utm_source=pin)."));
        assertEquals("https://example.org/a and https://example.org/b",
                LinkTracking.cleanText("https://example.org/a?utm_source=1 and https://example.org/b?fbclid=2"));
        assertNull(LinkTracking.cleanText(null));
    }

    @Test public void textGluedOntoALinkStaysWhenItsTagGoes() {
        assertEquals("https://www.pinterest.com/pin/123/見て",
                LinkTracking.cleanText("https://www.pinterest.com/pin/123/?utm_source=x見て"));
        assertEquals("Look https://example.org/a.見てOK!",
                LinkTracking.cleanText("Look https://example.org/a?fbclid=1.見てOK!"));
        assertEquals(Collections.singletonList("https://pin.it/OpaqueToken"), LinkTracking.urls("https://pin.it/OpaqueToken見て"));
    }

    @Test public void anIriQueryPathOrHostStaysWhole() {
        assertEquals("https://example.org/search?q=見て",
                LinkTracking.cleanText("https://example.org/search?q=見て&utm_source=x"));
        assertEquals("https://example.org/wiki/東京?q=東京%20駅 next",
                LinkTracking.cleanText("https://example.org/wiki/東京?q=東京%20駅&gclid=2 next"));
        assertEquals("https://bücher.example/a", LinkTracking.cleanText("https://bücher.example/a?utm_source=x"));
    }

    /** A value outside ASCII that runs on into ASCII before the next parameter keeps the link whole. */
    @Test public void anIriValueRunningIntoAsciiKeepsTheLinkWholeAndItsTagGoes() {
        String shared = "https://example.com/p?q=東京2024&utm_source=x";
        assertEquals("https://example.com/p?q=東京2024", LinkTracking.cleanText(shared));
        assertEquals(Collections.singletonList(shared), LinkTracking.urls(shared));
        assertEquals("See https://example.com/p?q=東京2024 now", LinkTracking.cleanText("See " + shared + " now"));

        // Glued text with no separator after it is still text after the link.
        assertEquals("https://www.pinterest.com/pin/123/見てね",
                LinkTracking.cleanText("https://www.pinterest.com/pin/123/?utm_source=x見てね"));
        assertEquals("https://example.org/a見てOK", LinkTracking.cleanText("https://example.org/a?utm_source=x見てOK"));
        // A question mark that ends the sentence isn't the link carrying on.
        assertEquals("https://example.org/a見て?", LinkTracking.cleanText("https://example.org/a?utm_source=x見て?"));
    }

    /** Values with letters outside ASCII in more than one place, which 15165bf cut at the first one. */
    @Test public void aValueWithSeveralRunsOutsideAsciiKeepsTheLinkWhole() {
        String tokyo = "https://example.com/p?q=Tōkyō&utm_source=x";
        assertEquals("https://example.com/p?q=Tōkyō", LinkTracking.cleanText(tokyo));
        assertEquals(Collections.singletonList(tokyo), LinkTracking.urls(tokyo));
        assertEquals("https://example.com/p?q=東京2024年", LinkTracking.cleanText("https://example.com/p?q=東京2024年&utm_source=x"));
        assertEquals("https://example.com/p?q=2024年10月", LinkTracking.cleanText("https://example.com/p?q=2024年10月&utm_source=x"));
        assertEquals("See https://en.wikipedia.org/wiki/São_Tomé now",
                LinkTracking.cleanText("See https://en.wikipedia.org/wiki/São_Tomé?fbclid=1 now"));

        // A tag whose value is outside ASCII goes with it, as it did before 15165bf.
        assertEquals("https://example.com/p", LinkTracking.cleanText("https://example.com/p?utm_campaign=夏セール"));
        assertEquals("https://example.com/p", LinkTracking.cleanText("https://example.com/p?utm_campaign=soldes_été"));
        assertEquals("https://example.com/p?q=x", LinkTracking.cleanText("https://example.com/p?q=x&utm_campaign=Tōkyō"));
    }

    @Test public void aSecondLinkGluedOnAfterTextIsCleanedOnItsOwn() {
        String shared = "https://a.example/p?utm_source=x見て、https://b.example/q?fbclid=1";
        assertEquals("https://a.example/p見て、https://b.example/q", LinkTracking.cleanText(shared));
        assertEquals(Arrays.asList("https://a.example/p?utm_source=x", "https://b.example/q?fbclid=1"), LinkTracking.urls(shared));
        // Straight after an = it's still a new link, not the tag's value.
        assertEquals("https://a.example/p見てhttps://b.example/q",
                LinkTracking.cleanText("https://a.example/p?utm_source=見てhttps://b.example/q?fbclid=1"));
        assertEquals("https://a.example/p見てOKhttps://b.example/q",
                LinkTracking.cleanText("https://a.example/p?utm_source=x見てOKhttps://b.example/q?gclid=2"));
    }

    /** A run past the longest link is left whole, and the links after it are still cleaned. */
    @Test public void anOverlongLinkIsLeftAsItIsAndTheNextOneIsStillCleaned() {
        String overlong = "https://example.com/p?q=" + "a".repeat(LinkTracking.LONGEST_LINK) + "&utm_source=x";
        String shared = overlong + " https://example.com/q?utm_source=y";
        assertEquals(overlong + " https://example.com/q", LinkTracking.cleanText(shared));
        assertEquals(Collections.singletonList("https://example.com/q?utm_source=y"), LinkTracking.urls(shared));
        String start = "https://example.com/p?utm_source=x&q=";
        String longest = start + "a".repeat(LinkTracking.LONGEST_LINK - start.length());
        assertEquals(longest.replace("utm_source=x&", ""), LinkTracking.cleanText(longest));
        // Cut at the longest link, a match is still a link when glued text starts inside the cut.
        String gluedOn = "https://example.com/p?utm_source=x見て" + "あ".repeat(LinkTracking.LONGEST_LINK);
        assertEquals("https://example.com/p見て" + "あ".repeat(LinkTracking.LONGEST_LINK), LinkTracking.cleanText(gluedOn));
    }

    /** Thousands of links glued into one run are each read once, not to the end of the run every time. */
    @Test(timeout = 20_000) public void thousandsOfGluedLinksAreWalkedInOnePass() {
        StringBuilder glued = new StringBuilder("https://x.com/p?q=1");
        for (int i = 0; i < 20_000; i++) glued.append("見https://a.example/?utm_source=").append(i);
        String cleaned = LinkTracking.cleanText(glued.toString()).toString();
        assertTrue(cleaned, cleaned.endsWith("見https://a.example/"));
        assertFalse(cleaned.contains("utm_source"));
    }

    /** A link written into a query value, with the outer query going on after it, is one link: its & is the outer one's. */
    @Test public void aLinkInsideAQueryValueLeavesTheQuerysTagsToBeStripped() {
        String shared = "https://x.com/intent/tweet?text=見てhttps://pin.it/abc&utm_source=pinterest";
        assertEquals(Collections.singletonList(shared), LinkTracking.urls(shared));
        assertEquals("https://x.com/intent/tweet?text=見てhttps://pin.it/abc", LinkTracking.cleanText(shared));
        assertEquals("https://x.com/intent/tweet?text=x見てOKhttps://pin.it/abc",
                LinkTracking.cleanText("https://x.com/intent/tweet?text=x見てOKhttps://pin.it/abc&utm_medium=social"));
        // Without an outer query the glued link is still its own.
        assertEquals(Arrays.asList("https://a.example/p", "https://b.example/q&x=1"),
                LinkTracking.urls("https://a.example/p見てhttps://b.example/q&x=1"));
    }

    @Test public void aHashtagAfterGluedTextIsntAFragment() {
        assertEquals("https://example.org/a見て#ピン", LinkTracking.cleanText("https://example.org/a?utm_source=x見て#ピン"));
        // A fragment in ASCII after a value still belongs to the link.
        assertEquals("https://example.org/a?q=東京#top", LinkTracking.cleanText("https://example.org/a?q=東京&fbclid=1#top"));
    }

    @Test public void aDateOrTagLaterInGluedTextStaysText() {
        assertEquals("https://example.org/a見てAI画像10/12", LinkTracking.cleanText("https://example.org/a?utm_source=x見てAI画像10/12"));
        assertEquals("https://example.org/a見てAI画像#tag", LinkTracking.cleanText("https://example.org/a?utm_source=x見てAI画像#tag"));
        // In the path, a / past a later run still carries the link on.
        assertEquals("https://example.org/タグ/夏2024年/b", LinkTracking.cleanText("https://example.org/タグ/夏2024年/b?utm_source=x"));
    }

    @Test public void greekPunctuationIsntPartOfTheLink() {
        assertEquals("https://example.org/a;", LinkTracking.cleanText("https://example.org/a?utm_source=x;"));
        assertEquals("https://example.org/a?q=Αθήνα", LinkTracking.cleanText("https://example.org/a?q=Αθήνα&utm_source=x"));
    }

    @Test public void gluedTextWithAnEqualsSignOrQuestionStaysOutOfTheLink() {
        assertEquals("https://example.org/a見てAI画像=最高", LinkTracking.cleanText("https://example.org/a?utm_source=x見てAI画像=最高"));
        assertEquals("https://example.org/a見て?次は", LinkTracking.cleanText("https://example.org/a?utm_source=x見て?次は"));
        assertEquals("https://example.org/a見てOK", LinkTracking.cleanText("https://example.org/a?utm_source=x見てOK"));
    }

    @Test public void onlySharedTextExtrasChangeAndCopyLabelsStayTheSame() {
        String original = "https://example.org/a?utm_source=pin";
        Intent intent = new Intent(Intent.ACTION_SEND);
        assertSame(intent, LinkTracking.putStringExtra(intent, "login_url", original));
        assertEquals(original, intent.getStringExtra("login_url"));
        LinkTracking.putStringExtra(intent, Intent.EXTRA_TEXT, original);
        assertEquals("https://example.org/a", intent.getStringExtra(Intent.EXTRA_TEXT));
        ClipData clip = LinkTracking.newPlainText("Copy link", original);
        assertEquals("Copy link", clip.getDescription().getLabel());
        assertEquals("https://example.org/a", clip.getItemAt(0).getText());
        assertEquals("https://example.org/a", LinkTracking.newPlainText(original, original).getDescription().getLabel());
    }

    @Test public void pauseAndDisabledSwitchAndColdStartKeepOriginalText() {
        String original = "https://example.org/a?utm_source=pin";
        Settings.STRIP_LINK_TRACKING.save(false);
        assertSame(original, LinkTracking.cleanText(original));
        Settings.STRIP_LINK_TRACKING.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        assertSame(original, LinkTracking.cleanText(original));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame(original, LinkTracking.cleanText(original)));
    }
}
