/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.PatchFamilyForTests;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;
import app.hushpinterest.extension.shared.settings.HushPinterestPause;
import app.hushpinterest.extension.shared.settings.PauseForTests;

/**
 * Filter feed's Muted words on the shared list hook, against stand-ins shaped like 14.41.0's pin
 * (pe) and a board, each field named the way Pinterest's renamed Gson annotation names it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MutedWordsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Json {
        String value();
    }

    static final class Pin {
        @Json("id") String id;
        @Json("title") String title = "";
        @Json("grid_title") String gridTitle = "";
        @Json("description") String description = "";
        @Json("is_video") Boolean video;

        Pin(String id) {
            this.id = id;
        }

        @Override public String toString() {
            return id;
        }
    }

    /** A board: a title and a description, but no grid title, so it isn't a pin. */
    static final class Board {
        @Json("id") String id = "board";
        @Json("title") String title = "Cake ideas";
        @Json("description") String description = "Every cake I like";

        @Override public String toString() {
            return id;
        }
    }

    /** A feed module the way FeedFilterTest's is: its pins in a list with no JSON name. */
    static final class Module {
        @Json("id") String id;
        @Json("story_type") String storyType = "ideas_for_you";
        List<Object> objects;

        Module(String id, Object... objects) {
            this.id = id;
            this.objects = Arrays.asList(objects);
        }

        @Override public String toString() {
            return id;
        }
    }

    private static Pin titled(String id, String title) {
        Pin pin = new Pin(id);
        pin.title = title;
        return pin;
    }

    @Before
    public void install() {
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.FEED_ADS, PatchFamily.Capability.FEED_MUTED_WORDS));
        Settings.HIDE_ADS.save(false);
    }

    @After
    public void restore() {
        PatchFamilyForTests.capabilities(null);
        PauseForTests.resume();
        Settings.HIDE_ADS.resetToDefault();
        Settings.HIDE_VIDEO_PINS.resetToDefault();
        Settings.MUTED_WORDS.resetToDefault();
        ModelFields.clearForTests();
        ModuleItems.clearForTests();
        HookStatus.clear();
    }

    /** The report's line for [family], or "" when it has none. */
    private static String line(String family) {
        for (String line : HookStatus.report()) {
            if (line.startsWith(family + ":")) return line;
        }
        return "";
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    @Test
    public void anEmptyListTakesNothing() {
        assertEquals("", Settings.MUTED_WORDS.get());
        List<Object> page = new ArrayList<>(Arrays.asList(titled("cake", "Wedding cake"), new Board()));
        assertSame(page, FeedFilter.filter(page));
    }

    /**
     * Title, grid title and description are read, whatever their case and spacing, and an entry
     * only counts as whole words: "cake" isn't in "cupcakes" or "weddingcake", and "wedding dress"
     * isn't in "wedding guest dress". A board named after a muted word stays.
     */
    @Test
    public void aWordOrPhraseTakesThePinsThatSayItAsWholeWords() {
        Settings.MUTED_WORDS.save("cake, wedding dress");
        Pin cake = titled("cake", "Wedding Cake Ideas");
        Pin cupcakes = titled("cupcakes", "Easy cupcakes");
        Pin joined = new Pin("joined");
        joined.gridTitle = "weddingcake topper";
        Pin dress = new Pin("dress");
        dress.description = "Lace WEDDING\n   dress for spring";
        Pin guest = titled("guest", "Wedding guest dress");
        Pin tagged = new Pin("tagged");
        tagged.description = "so good #cake";
        Pin photo = titled("photo", "Sunset");
        List<Object> page = new ArrayList<>(Arrays.asList(cake, cupcakes, joined, dress, guest, tagged, photo, new Board()));
        assertEquals("[cupcakes, joined, guest, photo, board]", FeedFilter.filter(page).toString());
        assertTrue(report(), line(FamilyNames.FILTER_FEED).contains("muted word pin removed 3"));
    }

    /** Turkish İ and ı read as a plain i, so either spelling of a muted word finds the other. */
    @Test
    public void dottedAndDotlessIReadAsOne() {
        Settings.MUTED_WORDS.save("kırmızı\nİstanbul");
        Pin upper = titled("upper", "KIRMIZI elbise");
        Pin plain = titled("plain", "Kirmizi ruj");
        Pin city = titled("city", "istanbul sokakları");
        Pin longer = titled("longer", "Kırmızıbiber salçası");
        List<Object> page = new ArrayList<>(Arrays.asList(upper, plain, city, longer));
        assertEquals("[longer]", FeedFilter.filter(page).toString());
    }

    /** A title's curly apostrophe matches the straight one a keyboard types. */
    @Test
    public void aCurlyApostropheReadsAsAStraightOne() {
        Settings.MUTED_WORDS.save("mom's kitchen");
        Pin curly = titled("curly", "Mom’s Kitchen Tour");
        Pin moms = titled("moms", "Moms kitchen");
        List<Object> page = new ArrayList<>(Arrays.asList(curly, moms));
        assertEquals("[moms]", FeedFilter.filter(page).toString());
    }

    /**
     * Chinese, Japanese and Thai put no spaces between words, so a muted word there counts inside a
     * run of text, and one of their letters next to a Latin word is a boundary for it. A spaced
     * script keeps its boundary, and a tone mark on the phrase's last letter makes it another word.
     */
    @Test
    public void aScriptWithoutSpacesMatchesInsideARun() {
        Settings.MUTED_WORDS.save("蛋糕\nケーキ\nเค้ก\ncake");
        Pin chinese = titled("chinese", "生日蛋糕食谱");
        Pin japanese = titled("japanese", "誕生日ケーキの作り方");
        Pin thai = titled("thai", "สูตรเค้กวันเกิด");
        Pin mixed = titled("mixed", "生日cake");
        Pin cupcakes = titled("cupcakes", "Mini cupcakes");
        List<Object> page = new ArrayList<>(Arrays.asList(chinese, japanese, thai, mixed, cupcakes));
        assertEquals("[cupcakes]", FeedFilter.filter(page).toString());

        // "เค" with the tone mark ้ after it is the start of "เค้ก", not the word "เค".
        Settings.MUTED_WORDS.save("เค");
        List<Object> marked = new ArrayList<>(Arrays.asList(titled("thai", "สูตรเค้กวันเกิด")));
        assertEquals("[thai]", FeedFilter.filter(marked).toString());

        // A word ending in the long vowel mark ー is still Japanese, so the 2 after it doesn't join it.
        Settings.MUTED_WORDS.save("コーヒー");
        List<Object> counted = new ArrayList<>(Arrays.asList(titled("coffee", "コーヒー2杯の淹れ方"), titled("tea", "紅茶")));
        assertEquals("[tea]", FeedFilter.filter(counted).toString());
    }

    @Test
    public void mutedPinsLeaveAModuleAndAModuleOfOnlyMutedPinsGoes() {
        Settings.MUTED_WORDS.save("cake");
        Pin photo = titled("photo", "Sunset");
        Module mixed = new Module("mixed", photo, titled("cake1", "Cake"));
        Module muted = new Module("muted", titled("cake2", "Lemon cake"));
        assertEquals("[mixed]", FeedFilter.filter(new ArrayList<>(Arrays.asList(mixed, muted))).toString());
        assertEquals("[photo]", mixed.objects.toString());
        String filterFeed = line(FamilyNames.FILTER_FEED);
        assertTrue(report(), filterFeed.contains("muted word pin removed from a module 2"));
        assertTrue(report(), filterFeed.contains("module left empty removed 1"));
    }

    /** With Hide video pins on too, a muted video counts as a video, the earlier one in the order. */
    @Test
    public void aMutedVideoCountsAsAVideoWhenBothAreOn() {
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.FEED_ADS,
                PatchFamily.Capability.FEED_VIDEO_PINS, PatchFamily.Capability.FEED_MUTED_WORDS));
        Settings.HIDE_VIDEO_PINS.save(true);
        Settings.MUTED_WORDS.save("cake");
        Pin video = titled("video", "Cake decorating");
        video.video = true;
        assertEquals("[photo]", FeedFilter.filter(new ArrayList<>(Arrays.asList(titled("photo", "Sunset"), video))).toString());
        String filterFeed = line(FamilyNames.FILTER_FEED);
        assertTrue(report(), filterFeed.contains("video pin removed 1"));
        assertFalse(report(), filterFeed.contains("muted word pin removed"));
    }

    @Test
    public void pauseOrAMissingCapabilityKeepsEveryPin() {
        Settings.MUTED_WORDS.save("cake");
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        List<Object> paused = new ArrayList<>(Collections.singletonList(titled("cake", "Cake")));
        assertSame(paused, FeedFilter.filter(paused));
        assertEquals("cake", Settings.MUTED_WORDS.savedValue());
        PauseForTests.resume();
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.FEED_ADS));
        List<Object> missing = new ArrayList<>(Collections.singletonList(titled("cake", "Cake")));
        assertSame(missing, FeedFilter.filter(missing));
    }

    @Test
    public void aListIsNormalizedOnceAndReadAgainOnlyWhenItChanges() {
        List<String> first = MutedWords.phrasesOf("Cake\nWedding Dress");
        assertEquals(Arrays.asList("cake", "wedding dress"), first);
        assertSame(first, MutedWords.phrasesOf("Cake\nWedding Dress"));
        assertEquals(Collections.singletonList("kirmizi"), MutedWords.phrasesOf("Kırmızı"));
        assertEquals(Collections.emptyList(), MutedWords.phrasesOf(""));
        assertFalse(MutedWords.isMuted(new Board(), MutedWords.phrasesOf("cake")));
        assertFalse(MutedWords.isMuted(null, MutedWords.phrasesOf("cake")));
    }
}
