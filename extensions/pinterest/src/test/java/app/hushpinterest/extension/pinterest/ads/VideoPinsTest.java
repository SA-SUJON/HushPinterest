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
import java.util.Map;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.PatchFamilyForTests;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;
import app.hushpinterest.extension.shared.settings.HushPinterestPause;
import app.hushpinterest.extension.shared.settings.PauseForTests;

/**
 * Filter feed's Hide video pins on the shared list hook, against stand-ins shaped like 14.41.0's pin
 * (pe), its video model (vr) and its idea pin data (am), each field named the way Pinterest's
 * renamed Gson annotation names it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class VideoPinsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Json {
        String value();
    }

    /** vr: the stream and file addresses by format, which a pin's videos field holds. */
    static final class Videos {
        @Json("id") String id = "video";
        @Json("video_list") Map<String, Object> list;
    }

    /** am: an idea pin's pages, with their running time in seconds as text. */
    static final class StoryData {
        @Json("total_video_duration") String duration;
        @Json("page_count") Integer pages = 3;
    }

    static final class Pin {
        @Json("id") String id;
        @Json("grid_title") String gridTitle = "";
        @Json("is_promoted") Boolean promoted;
        @Json("is_video") Boolean video;
        @Json("videos") Videos videos;
        @Json("story_pin_data") StoryData story;

        Pin(String id) {
            this.id = id;
        }

        @Override public String toString() {
            return id;
        }
    }

    /** Fields of the right names holding the wrong kinds of value. */
    static final class OddPin {
        @Json("id") String id = "odd";
        @Json("grid_title") String gridTitle = "";
        @Json("is_video") Object video = "true";
        @Json("videos") Object videos = Collections.singletonMap("V_HLSV3_MOBILE", "x");
        @Json("story_pin_data") Object story = "12.5";

        @Override public String toString() {
            return id;
        }
    }

    /** A model that isn't a pin (no grid title) but says it's a video, like a creator's intro. */
    static final class Intro {
        @Json("id") String id = "intro";
        @Json("is_video") Boolean video = true;

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

    private final Pin photo = new Pin("photo");
    private final Pin gif = new Pin("gif");
    private final Pin marked = new Pin("marked");
    private final Pin streamOnly = new Pin("streamOnly");
    private final Pin idea = new Pin("idea");
    private final Pin stillIdea = new Pin("stillIdea");
    private final Pin promotedVideo = new Pin("promotedVideo");

    @Before
    public void shapeTheModels() {
        gif.video = false;
        gif.videos = new Videos();
        gif.videos.list = Collections.emptyMap();
        marked.video = true;
        streamOnly.videos = new Videos();
        streamOnly.videos.list = Collections.singletonMap("V_HLSV3_MOBILE", new Object());
        idea.story = new StoryData();
        idea.story.duration = "14.2";
        stillIdea.story = new StoryData();
        stillIdea.story.duration = "0.0";
        promotedVideo.video = true;
        promotedVideo.promoted = true;
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.FEED_ADS, PatchFamily.Capability.FEED_VIDEO_PINS));
    }

    @After
    public void restore() {
        PatchFamilyForTests.capabilities(null);
        PauseForTests.resume();
        Settings.HIDE_ADS.resetToDefault();
        Settings.HIDE_VIDEO_PINS.resetToDefault();
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

    private List<Object> page() {
        return new ArrayList<>(Arrays.asList(photo, marked, gif, streamOnly, idea, stillIdea, new Intro()));
    }

    @Test
    public void itStartsOff() {
        assertFalse(Settings.HIDE_VIDEO_PINS.get());
        Settings.HIDE_ADS.save(false);
        List<Object> page = page();
        assertSame(page, FeedFilter.filter(page));
    }

    /**
     * Marked, stream-only and running idea pins go, in the order Pinterest gave the rest; a GIF with
     * an empty video list, a photo, an idea pin of still pages and a non-pin video stay.
     */
    @Test
    public void eachWayAPinPlaysAVideoTakesItAndTheRestKeepTheirOrder() {
        Settings.HIDE_ADS.save(false);
        Settings.HIDE_VIDEO_PINS.save(true);
        assertEquals("[photo, gif, stillIdea, intro]", FeedFilter.filter(page()).toString());
        assertTrue(report(), line(FamilyNames.FILTER_FEED).contains("video pin removed 3"));
    }

    @Test
    public void onlyARunningTimeAboveZeroRuns() {
        for (String runs : Arrays.asList("14.2", "1", " 3.5 ", "0.01", "0:13", "PT12S")) assertTrue(runs, VideoPins.runs(runs));
        for (String still : Arrays.asList("0", "0.0", "00:00", "-2", "", "  ", "twelve", "NaN", "Infinity",
                "12345678901234567890123456789012345")) {
            assertFalse(still, VideoPins.runs(still));
        }
    }

    @Test
    public void valuesOfTheWrongKindAndModelsThatArentPinsStay() {
        assertFalse(VideoPins.isVideo(new OddPin()));
        assertFalse(VideoPins.isVideo(new Intro()));
        assertFalse(VideoPins.isVideo("a string"));
        assertFalse(VideoPins.isVideo(null));
        Pin blank = new Pin("blank");
        blank.story = new StoryData();
        assertFalse(VideoPins.isVideo(blank));
        blank.videos = new Videos();
        assertFalse(VideoPins.isVideo(blank));
    }

    /** With Hide ads on too, a promoted video counts as an ad, the earlier family in the order. */
    @Test
    public void aPromotedVideoCountsAsAnAdWhenBothAreOn() {
        Settings.HIDE_VIDEO_PINS.save(true);
        List<Object> page = new ArrayList<>(Arrays.asList(photo, promotedVideo, marked));
        assertEquals("[photo]", FeedFilter.filter(page).toString());
        assertTrue(report(), line(FamilyNames.HIDE_ADS).contains("promoted pin removed 1"));
        assertTrue(report(), line(FamilyNames.FILTER_FEED).contains("video pin removed 1"));
    }

    @Test
    public void videosLeaveAModuleAndAModuleOfOnlyVideosGoes() {
        Settings.HIDE_ADS.save(false);
        Settings.HIDE_VIDEO_PINS.save(true);
        Module mixed = new Module("mixed", photo, marked, gif);
        Module videos = new Module("videos", streamOnly, idea);
        assertEquals("[mixed]", FeedFilter.filter(new ArrayList<>(Arrays.asList(mixed, videos))).toString());
        assertEquals("[photo, gif]", mixed.objects.toString());
        String filterFeed = line(FamilyNames.FILTER_FEED);
        assertTrue(report(), filterFeed.contains("video pin removed from a module 3"));
        assertTrue(report(), filterFeed.contains("module left empty removed 1"));
    }

    @Test
    public void pauseOrAMissingCapabilityKeepsEveryVideo() {
        Settings.HIDE_ADS.save(false);
        Settings.HIDE_VIDEO_PINS.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        List<Object> paused = page();
        assertSame(paused, FeedFilter.filter(paused));
        PauseForTests.resume();
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.FEED_ADS));
        List<Object> missing = page();
        assertSame(missing, FeedFilter.filter(missing));
    }
}
