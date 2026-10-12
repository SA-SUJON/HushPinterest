/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;

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

import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.PatchFamilyForTests;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;
import app.hushpinterest.extension.shared.settings.HushPinterestPause;
import app.hushpinterest.extension.shared.settings.PauseForTests;

/**
 * The list filter both feed patches share, against stand-in models whose fields carry their JSON
 * names the way Pinterest's obfuscated Gson annotation does: an annotation with a String value().
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FeedFilterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Json {
        String value();
    }

    /** Shaped like Pinterest's pin: boxed flags, a promotion id, an ad payload and the AI labels. */
    static final class Pin {
        @Json("id") String id;
        @Json("is_promoted") Boolean promoted;
        @Json("is_third_party_ad") Boolean thirdParty;
        @Json("pin_promotion_id") String promotionId;
        @Json("ad_data") Object adData;
        @Json("ai_disclosures") List<Integer> aiDisclosures;

        Pin(String id) {
            this.id = id;
        }

        @Override public String toString() {
            return id;
        }
    }

    /** A feed story, which is an ad when Pinterest says it's a shopping spotlight. */
    static final class Story {
        @Json("id") String id;
        @Json("story_type") String storyType;

        Story(String id, String storyType) {
            this.id = id;
            this.storyType = storyType;
        }

        @Override public String toString() {
            return id;
        }
    }

    /** Pinterest's typed story kinds, as one story model keeps them (an enum, not the JSON text). */
    enum StoryKind { SHOPPING_SPOTLIGHT, IDEAS_FOR_YOU, SOMETHING_NEWER }

    /** The story model whose story_type is Pinterest's enum. */
    static final class TypedStory {
        @Json("id") String id;
        @Json("story_type") StoryKind storyType;

        TypedStory(String id, StoryKind storyType) {
            this.id = id;
            this.storyType = storyType;
        }

        @Override public String toString() {
            return id;
        }
    }

    /**
     * A feed module like Pinterest's dynamic story: its pins in a list with no JSON name, which is
     * what its renderers read, and its preview image addresses in another.
     */
    static final class Module {
        @Json("id") String id;
        @Json("story_type") String storyType;
        List<Object> objects;
        ArrayList<String> images = new ArrayList<>(Arrays.asList("a.jpg", "b.jpg"));

        Module(String id, Object... objects) {
            this.id = id;
            this.storyType = "ideas_for_you";
            this.objects = Arrays.asList(objects);
        }

        @Override public String toString() {
            return id;
        }
    }

    /** A search filter chip like 14.39.0's q8: no JSON names, only text and lists. */
    static final class Chip {
        final String id = "chip";
        final String label = "Curly";
        final List<String> values = Arrays.asList("3a", "3b");
    }

    /** A dynamic story that also carries search chips (z5.V), which no renderer draws as the module's row. */
    static final class ChipModule {
        @Json("id") String id = "chips";
        @Json("story_type") String storyType = "search_results";
        List<Object> objects;
        ArrayList<Chip> chips = new ArrayList<>(Arrays.asList(new Chip()));

        ChipModule(Object... objects) {
            this.objects = Arrays.asList(objects);
        }

        @Override public String toString() {
            return id;
        }
    }

    /** The story model that names its list and wraps each entry, holding one model and no JSON names. */
    static final class Wrapper {
        final Pin pin;
        final Story story;
        final int position;

        Wrapper(Pin pin, Story story) {
            this.pin = pin;
            this.story = story;
            this.position = 3;
        }

        @Override public String toString() {
            return String.valueOf(pin != null ? pin : story);
        }
    }

    static final class WrappedModule {
        @Json("id") String id = "wrapped";
        @Json("story_type") StoryKind storyType = StoryKind.IDEAS_FOR_YOU;
        @Json("objects") List<Wrapper> objects;
        @Json("content_ids") List<String> contentIds = Arrays.asList("1", "2", "3");

        @Override public String toString() {
            return id;
        }
    }

    /** A module whose list can't be swapped for a copy. */
    static final class FixedModule {
        @Json("story_type") String storyType = "ideas_for_you";
        final List<Object> objects;

        FixedModule(Object... objects) {
            this.objects = Arrays.asList(objects);
        }

        @Override public String toString() {
            return "fixed";
        }
    }

    /** 14.39.0's pin, which keeps its promotion and campaign as reformatted ids. */
    static final class ReformattedPin {
        @Json("id") String id = "reformatted";
        @Json("pin_promotion_id_reformatted") String promotion;
        @Json("campaign_id_reformatted") String campaign;
    }

    private final Pin plain = new Pin("plain");
    private final Pin promoted = new Pin("promoted");
    private final Pin thirdParty = new Pin("thirdParty");
    private final Pin promotionId = new Pin("promotionId");
    private final Pin adData = new Pin("adData");
    private final Pin labeled = new Pin("labeled");
    private final Pin emptyLabels = new Pin("emptyLabels");
    private final Story spotlight = new Story("spotlight", "shopping_spotlight");
    private final Story ideas = new Story("ideas", "ideas_for_you");

    @Before
    public void shapeTheModels() {
        promoted.promoted = true;
        thirdParty.thirdParty = true;
        promotionId.promotionId = "123";
        adData.adData = new Object();
        labeled.aiDisclosures = Arrays.asList(1);
        emptyLabels.aiDisclosures = Collections.emptyList();
        plain.promoted = false;
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.FEED_ADS, PatchFamily.Capability.FEED_AI_PINS));
    }

    @After
    public void restore() {
        PatchFamilyForTests.capabilities(null);
        PauseForTests.resume();
        Settings.HIDE_ADS.resetToDefault();
        Settings.HIDE_AI_PINS.resetToDefault();
        Settings.HIDE_SHOPPING.resetToDefault();
        ModelFields.clearForTests();
        ModuleItems.clearForTests();
        HookStatus.clear();
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    private List<Object> page() {
        return new ArrayList<>(Arrays.asList(plain, promoted, labeled, thirdParty, promotionId, emptyLabels, adData,
                spotlight, ideas));
    }

    @Test
    public void hideAdsAloneTakesEveryKindOfAdAndKeepsTheOrder() {
        Settings.HIDE_AI_PINS.save(false);
        List<?> kept = FeedFilter.filter(page());
        assertEquals("[plain, labeled, emptyLabels, ideas]", kept.toString());
        assertTrue(String.join("\n", HookStatus.report()), String.join("\n", HookStatus.report()).contains("promoted pin removed"));
    }

    /**
     * A list that can't be read all the way through is shown as it was, so the report must not
     * count the ad the loop had already found: nothing was taken out of what Pinterest shows.
     */
    @Test
    public void aListThatFailsPartwayIsShownAsItWasAndCountsNoRemoval() {
        Settings.HIDE_AI_PINS.save(false);
        List<Object> page = new ArrayList<Object>(Arrays.asList(plain, promoted, labeled, ideas)) {
            @Override public Object get(int index) {
                if (index == 2) throw new IllegalStateException("unreadable");
                return super.get(index);
            }
        };
        assertSame(page, FeedFilter.filter(page));
        assertEquals("[plain, promoted, labeled, ideas]", new ArrayList<>(page).toString());
        assertFalse(report(), report().contains("promoted pin removed"));
        assertTrue(report(), report().contains("list filter"));
    }

    @Test
    public void bothSwitchesTakeTheLabeledPinsToo() {
        Settings.HIDE_AI_PINS.save(true);
        assertEquals("[plain, emptyLabels, ideas]", FeedFilter.filter(page()).toString());
    }

    @Test
    public void aiAloneLeavesTheAds() {
        Settings.HIDE_ADS.save(false);
        Settings.HIDE_AI_PINS.save(true);
        assertEquals("[plain, promoted, thirdParty, promotionId, emptyLabels, adData, spotlight, ideas]",
                FeedFilter.filter(page()).toString());
    }

    @Test
    public void aListWithNothingToTakeComesBackAsItself() {
        List<Object> clean = new ArrayList<>(Arrays.asList(plain, ideas, emptyLabels));
        assertSame(clean, FeedFilter.filter(clean));
        List<Object> empty = new ArrayList<>();
        assertSame(empty, FeedFilter.filter(empty));
        assertNull(FeedFilter.filter(null));
    }

    @Test
    public void anItemThatCantBeReadStaysAndTheRestOfThePageIsStillFiltered() {
        Settings.HIDE_AI_PINS.save(true);
        Pin broken = new Pin("broken");
        broken.aiDisclosures = new ArrayList<Integer>() {
            @Override public boolean isEmpty() {
                throw new IllegalStateException("host list changed while it was read");
            }
        };
        List<Object> page = new ArrayList<>(Arrays.asList(plain, promoted, broken, labeled, ideas));
        assertEquals("[plain, broken, ideas]", FeedFilter.filter(page).toString());
        assertTrue(report(), report().contains("item filter"));
        assertTrue(report(), report().contains("java.lang.IllegalStateException"));
    }

    @Test
    public void pauseLeavesEveryItem() {
        Settings.HIDE_AI_PINS.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        List<Object> page = page();
        assertSame(page, FeedFilter.filter(page));
    }

    @Test
    public void aCapabilityThisBuildLacksIsNeverApplied() {
        Settings.HIDE_AI_PINS.save(true);
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.FEED_AI_PINS));
        assertEquals("[plain, promoted, thirdParty, promotionId, emptyLabels, adData, spotlight, ideas]",
                FeedFilter.filter(page()).toString());
    }

    @Test
    public void itemsOfOtherShapesStay() {
        List<Object> mixed = new ArrayList<>(Arrays.asList("a string", 42, plain, promoted));
        assertEquals("[a string, 42, plain]", FeedFilter.filter(mixed).toString());
    }

    private List<Object> typedStories() {
        return new ArrayList<>(Arrays.asList(new TypedStory("typedSpotlight", StoryKind.SHOPPING_SPOTLIGHT),
                new TypedStory("typedIdeas", StoryKind.IDEAS_FOR_YOU), new TypedStory("typedNewer", StoryKind.SOMETHING_NEWER),
                new TypedStory("typedNone", null), spotlight, ideas));
    }

    @Test
    public void hideAdsAloneTakesATypedSpotlightLikeItsTextAndKeepsOtherTypedStories() {
        Settings.HIDE_AI_PINS.save(false);
        assertFalse(Settings.HIDE_SHOPPING.get());
        assertEquals("[typedIdeas, typedNewer, typedNone, ideas]", FeedFilter.filter(typedStories()).toString());
        assertTrue(Ads.isSpotlight(StoryKind.SHOPPING_SPOTLIGHT));
        assertTrue(Ads.isSpotlight("shopping_spotlight"));
        assertFalse(Ads.isSpotlight(StoryKind.IDEAS_FOR_YOU));
        assertFalse(Ads.isSpotlight(null));
    }

    @Test
    public void aTypedSpotlightStaysWithHideAdsOffOrPaused() {
        Settings.HIDE_ADS.save(false);
        List<Object> off = typedStories();
        assertSame(off, FeedFilter.filter(off));
        Settings.HIDE_ADS.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        List<Object> paused = typedStories();
        assertSame(paused, FeedFilter.filter(paused));
    }

    @Test
    public void hideShoppingStillTakesTheTypedSpotlightOnItsOwn() {
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.FEED_ADS, PatchFamily.Capability.FEED_SHOPPING));
        Settings.HIDE_ADS.save(false);
        Settings.HIDE_SHOPPING.save(true);
        List<Object> page = new ArrayList<>(Arrays.asList(new TypedStory("typedSpotlight", StoryKind.SHOPPING_SPOTLIGHT),
                new TypedStory("typedIdeas", StoryKind.IDEAS_FOR_YOU)));
        assertEquals("[typedIdeas]", FeedFilter.filter(page).toString());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("shopping placement removed"));
        assertFalse(report, report.contains("promoted pin removed"));
    }

    @Test
    public void anAdOnlyViewStaysGoneAndSizelessWhileTheSwitchIsOn() {
        assertEquals(View.GONE, Ads.adViewVisibility(View.VISIBLE));
        assertEquals(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.EXACTLY),
                Ads.adViewMeasureSpec(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST)));

        Settings.HIDE_ADS.save(false);
        assertEquals(View.VISIBLE, Ads.adViewVisibility(View.VISIBLE));
        int spec = View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST);
        assertEquals(spec, Ads.adViewMeasureSpec(spec));

        Settings.HIDE_ADS.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        assertEquals(View.INVISIBLE, Ads.adViewVisibility(View.INVISIBLE));
        assertEquals(spec, Ads.adViewMeasureSpec(spec));
    }

    @Test
    public void hideAdsTakesTheAdsOutOfAModuleThatStays() {
        Settings.HIDE_AI_PINS.save(false);
        Module module = new Module("module", plain, promoted, labeled, "a note", adData, promotionId);
        List<Object> page = new ArrayList<>(Arrays.asList(plain, module, ideas));
        assertSame(page, FeedFilter.filter(page));
        assertEquals("[plain, labeled, a note]", module.objects.toString());
        assertEquals("[a.jpg, b.jpg]", module.images.toString());
        assertTrue(report(), report().contains("promoted pin removed from a module"));
        assertFalse(report(), report().contains("module left empty removed"));
    }

    @Test
    public void aModuleOfNothingButAdsGoesWithThem() {
        Module module = new Module("allAds", promoted, thirdParty);
        assertEquals("[plain, ideas]", FeedFilter.filter(new ArrayList<>(Arrays.asList(plain, module, ideas))).toString());
        assertEquals("[]", module.objects.toString());
        assertTrue(report(), report().contains("module left empty removed"));
    }

    @Test
    public void searchChipsDontKeepAModuleWhosePinsAreGone() {
        ChipModule module = new ChipModule(promoted, thirdParty);
        assertEquals("[plain, ideas]", FeedFilter.filter(new ArrayList<>(Arrays.asList(plain, module, ideas))).toString());
        assertEquals(1, module.chips.size());
        assertTrue(report(), report().contains("module left empty removed"));
    }

    @Test
    public void bothSwitchesTakeLabeledPinsOutOfAModuleToo() {
        Settings.HIDE_AI_PINS.save(true);
        Module module = new Module("module", plain, labeled, promoted, emptyLabels);
        FeedFilter.filter(new ArrayList<>(Arrays.asList(module)));
        assertEquals("[plain, emptyLabels]", module.objects.toString());
        assertTrue(report(), report().contains("AI-labeled pin removed from a module"));
    }

    @Test
    public void aWrappedPinIsJudgedByThePinItHolds() {
        WrappedModule module = new WrappedModule();
        module.objects = Arrays.asList(new Wrapper(plain, null), new Wrapper(promoted, null), new Wrapper(null, spotlight),
                new Wrapper(null, ideas), new Wrapper(null, null));
        assertEquals("[wrapped]", FeedFilter.filter(new ArrayList<>(Arrays.asList(module))).toString());
        assertEquals("[plain, ideas, null]", module.objects.toString());
        assertEquals("[1, 2, 3]", module.contentIds.toString());
    }

    @Test
    public void aModuleInsideAModuleIsJudgedButNotOpened() {
        Module inner = new Module("inner", promoted);
        Module outer = new Module("outer", plain, inner);
        FeedFilter.filter(new ArrayList<>(Arrays.asList(outer)));
        assertEquals("[plain, inner]", outer.objects.toString());
        assertEquals("[promoted]", inner.objects.toString());
    }

    @Test
    public void aListThatCantBeSwappedStaysAndCountsNothing() {
        FixedModule module = new FixedModule(promoted, plain);
        assertEquals("[fixed]", FeedFilter.filter(new ArrayList<>(Arrays.asList(module))).toString());
        assertEquals("[promoted, plain]", module.objects.toString());
        assertFalse(report(), report().contains("from a module"));
    }

    @Test
    public void aModuleKeepsEverythingWithHideAdsOffOrPaused() {
        Settings.HIDE_ADS.save(false);
        Module off = new Module("off", plain, promoted);
        List<Object> page = new ArrayList<>(Arrays.asList(off));
        assertSame(page, FeedFilter.filter(page));
        assertEquals("[plain, promoted]", off.objects.toString());

        Settings.HIDE_ADS.save(true);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        Module paused = new Module("paused", plain, promoted);
        FeedFilter.filter(new ArrayList<>(Arrays.asList(paused)));
        assertEquals("[plain, promoted]", paused.objects.toString());
    }

    @Test
    public void theDebugLineSaysWhatAModuleHoldsAndWhichAdFieldsAreSet() {
        Module module = new Module("module", plain, promoted, "a note", adData);
        String line = ModuleItems.describe(module);
        assertTrue(line, line.startsWith("Module ideas_for_you; "));
        assertTrue(line, line.contains("objects 4: Pin Pin[is_promoted=true] (java.lang.String) Pin[ad_data=set]"));
        assertTrue(line, line.contains("images 2: (java.lang.String) (java.lang.String)"));
        assertFalse("an entry's own text stays out: " + line, line.contains("a note") || line.contains("a.jpg"));
        assertFalse("a promotion id's value stays out: " + line, ModuleItems.describe(new Module("ids", promotionId)).contains("123"));
    }

    @Test
    public void aReformattedPromotionOrCampaignIdMarksAnAd() {
        ReformattedPin pin = new ReformattedPin();
        assertFalse(Ads.isAd(pin));
        pin.promotion = " ";
        assertFalse(Ads.isAd(pin));
        pin.promotion = "1234";
        assertTrue(Ads.isAd(pin));
        pin.promotion = null;
        pin.campaign = "5678";
        assertTrue(Ads.isAd(pin));
    }
}
