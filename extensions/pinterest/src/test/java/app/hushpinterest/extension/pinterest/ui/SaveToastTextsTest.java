/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.Html;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SaveToastTextsTest {
    /** A wording with its values filled in, the way Pinterest fills them: a count, then a board's name. */
    private static String shown(Locale language, String format, String board) {
        return format.contains("%1$,d") ? String.format(language, format, 1234, board) : String.format(language, format, board);
    }

    private static void assertEverySaveMatches(Locale language, Map<String, String[]> texts, String board) {
        Context source = SaveStrings.context(language, texts);
        for (Map.Entry<String, String[]> text : texts.entrySet()) {
            for (String form : text.getValue()) {
                String shown = shown(language, form, board);
                assertTrue(text.getKey() + " as a toast model holds it: " + shown, SaveToastTexts.matches(shown, source));
                assertTrue(text.getKey() + " as an Android toast shows it: " + shown,
                        SaveToastTexts.matches(Html.fromHtml(shown, Html.FROM_HTML_MODE_LEGACY), source));
            }
        }
    }

    @Test public void everySaveConfirmationMatchesInItsOwnLanguage() {
        assertEverySaveMatches(Locale.ENGLISH, SaveStrings.english(), "Recipes");
        assertEverySaveMatches(Locale.GERMAN, SaveStrings.german(), "Rezepte");
        assertEverySaveMatches(Locale.JAPANESE, SaveStrings.japanese(), "レシピ");
    }

    @Test public void aBoardNameOfSeveralWordsOrAnotherScriptStillMatches() {
        Context source = SaveStrings.context(Locale.ENGLISH, SaveStrings.english());
        assertTrue(SaveToastTexts.matches("Saved to Sunday dinners, 2026", source));
        assertTrue(SaveToastTexts.matches("Saved to <b>夏の思い出</b> section!", source));
        assertTrue(SaveToastTexts.matches("Saved to <b>Dinner <3 ideas</b> section!", source));
        assertTrue("a line break in the wording reads as a space", SaveToastTexts.matches("Saved to\nRecipes", source));
    }

    @Test public void otherMessagesFromTheSameScreensNeverMatch() {
        Context english = SaveStrings.context(Locale.ENGLISH, SaveStrings.english());
        for (String other : new String[] {
                "Moved Pin to <b>Recipes</b> board!", "Moved Pins to <b>Recipes</b> section!", "Section <b>Recipes</b> created!",
                "Section added!", "Pin unsaved", "Psst! You already saved this Pin to Recipes", "Removed from Recipes",
                "Couldn't save this Pin. Try again.", "Saved", "Saved to", "saved to Recipes", ""}) {
            assertFalse(other, SaveToastTexts.matches(other, english));
        }
        Context german = SaveStrings.context(Locale.GERMAN, SaveStrings.german());
        for (String other : new String[] {
                "Pin auf Pinnwand <b>Rezepte</b> verschoben!", "Pins in Ordner <b>Rezepte</b> verschoben!",
                "Pinnwand-Ordner <b>Rezepte</b> erstellt!", "Pin nicht gespeichert",
                "Psst! Du hast dir diesen Pin bereits auf „Rezepte“ gemerkt.", "Saved to Rezepte"}) {
            assertFalse(other, SaveToastTexts.matches(other, german));
        }
        Context japanese = SaveStrings.context(Locale.JAPANESE, SaveStrings.japanese());
        for (String other : new String[] {
                "ピンをボード「<b>レシピ</b>」に移動しました！", "サブボード「<b>レシピ</b>」を作成しました！",
                "ピンを保存しませんでした", "このピンは既に「レシピ」に保存されています"}) {
            assertFalse(other, SaveToastTexts.matches(other, japanese));
        }
    }

    @Test public void aWordingThatIsOnlyAValueNeverMatches() {
        for (String thin : new String[] {"%1$s", "<b>%1$s</b>", "%1$s +%2$d", "%1$s!", " %1$s ", "%1$s ok", "%1$,d", ""}) {
            assertNull(thin, SaveToastTexts.wording(thin));
        }
        assertNotNull("three letters beside a value is the least that counts", SaveToastTexts.wording("%1$s に保存"));
        assertNotNull("a wording with no value in it is matched whole, however short", SaveToastTexts.wording("已收藏！"));
        assertEquals(3, SaveToastTexts.MIN_LETTERS);
        // A language whose "Saved to a board" came through as a bare value hides nothing by it,
        // and its other wordings still work.
        Map<String, String[]> texts = new LinkedHashMap<>(SaveStrings.english());
        texts.put("saved_onto_board", new String[] {"%1$s"});
        Context source = SaveStrings.context(Locale.ENGLISH, texts);
        for (String any : new String[] {"Recipes", "Moved Pin to Recipes board!", "Saved to Recipes", "x"}) {
            assertFalse(any, SaveToastTexts.matches(any, source));
        }
        assertTrue(SaveToastTexts.matches("Saved!", source));
    }

    @Test public void aTextIsAskedInTheLanguagesOfTheContextsGiven() {
        Context english = SaveStrings.context(Locale.ENGLISH, SaveStrings.english());
        Context german = SaveStrings.context(Locale.GERMAN, SaveStrings.german());
        assertFalse(SaveToastTexts.matches("Gemerkt auf „Rezepte“", english));
        assertTrue(SaveToastTexts.matches("Gemerkt auf „Rezepte“", null, english, german));
        assertFalse("no context, no wording", SaveToastTexts.matches("Saved!"));
        assertFalse(SaveToastTexts.matches("Saved!", (Context) null));
        assertFalse(SaveToastTexts.matches(null, english));
        Context bare = SaveStrings.context(Locale.FRENCH, new LinkedHashMap<>());
        assertFalse("an app without the strings hides nothing", SaveToastTexts.matches("Saved!", bare));
    }

    /** A toast like the one Pinterest hands a section save's text: the text sits in the class it extends, beside an image address. */
    static class Message {
        public final String text;
        public final String image;
        public final boolean again = true;

        Message(String text, String image) {
            this.text = text;
            this.image = image;
        }
    }

    static final class SectionMessage extends Message {
        SectionMessage(String text) {
            super(text, "https://i.pinimg.com/236x/aa/bb/cc.jpg");
        }
    }

    @Test public void aToastHandedItsTextIsReadFromItsFields() {
        Context source = SaveStrings.context(Locale.ENGLISH, SaveStrings.english());
        assertTrue(SaveToastTexts.holdsSave(new SectionMessage("Saved to <b>Recipes</b> section!"), source));
        assertFalse(SaveToastTexts.holdsSave(new SectionMessage("Moved Pin to <b>Recipes</b> section!"), source));
        assertFalse(SaveToastTexts.holdsSave(new SectionMessage(null), source));
        assertFalse(SaveToastTexts.holdsSave(new Object(), source));
        assertFalse(SaveToastTexts.holdsSave(null, source));
    }
}
