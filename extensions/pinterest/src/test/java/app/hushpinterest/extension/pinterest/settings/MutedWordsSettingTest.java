/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.settings.HushPinterestPause;
import app.hushpinterest.extension.shared.settings.PauseForTests;

/** The muted words list: how what's typed is cleaned and saved, and the Feed page row that edits it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class MutedWordsSettingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.MUTED_WORDS.resetToDefault();
        MutedWordsPreference.shown = null;
    }

    private static String run(String unit, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append(unit);
        return text.toString();
    }

    @Test
    public void commasAndLineBreaksSplitAndRepeatsInAnotherCaseAreOne() {
        MutedWordsSetting.Cleaned cleaned = MutedWordsSetting.clean(
                " cake ,Cake\n\n  wedding    dress ,\r\nCAKE،tarta、蛋糕，torta,,");
        assertEquals(Arrays.asList("cake", "wedding dress", "tarta", "蛋糕", "torta"), cleaned.entries);
        assertEquals("cake\nwedding dress\ntarta\n蛋糕\ntorta", cleaned.saved);
        assertEquals(0, cleaned.tooLong);
        assertEquals(0, cleaned.overLimit);
        assertEquals("a dotted and a dotless i are one word", 1, MutedWordsSetting.clean("Kırmızı\nKIRMIZI").entries.size());
        assertEquals("a curly and a straight apostrophe are one word", 1,
                MutedWordsSetting.clean("Mom’s kitchen\nmom's kitchen").entries.size());
    }

    /** Length is counted the way a person counts it, so 40 emoji fit and 41 letters don't. */
    @Test
    public void tooLongAndPastTheLimitAreLeftOutAndCountedApart() {
        String emoji = run("🎂", MutedWordsSetting.MAX_LENGTH);
        MutedWordsSetting.Cleaned lengths = MutedWordsSetting.clean(
                run("a", 40) + "\n" + run("b", 41) + "\n" + emoji + "\n" + run("B", 41));
        assertEquals(Arrays.asList(run("a", 40), emoji), lengths.entries);
        assertEquals("a long entry typed twice is one left out", 1, lengths.tooLong);

        List<String> typed = new ArrayList<>();
        for (int i = 0; i < 55; i++) typed.add("word" + i);
        typed.add("WORD0");
        MutedWordsSetting.Cleaned many = MutedWordsSetting.clean(String.join(",", typed));
        assertEquals(MutedWordsSetting.MAX_ENTRIES, many.entries.size());
        assertEquals("word49", many.entries.get(MutedWordsSetting.MAX_ENTRIES - 1));
        assertEquals("a repeat past the limit isn't one more that didn't fit", 5, many.overLimit);
    }

    @Test
    public void cleaningWhatWasCleanedChangesNothing() {
        for (String typed : new String[]{"", " , \n", "cake, Cake, pie", "a\r\nb\rc", run("x,", 70)}) {
            String once = MutedWordsSetting.clean(typed).saved;
            assertEquals(typed, once, MutedWordsSetting.clean(once).saved);
        }
    }

    @Test
    public void everySaveIsCleanedAndAPauseReadsAsEmptyWithoutLosingTheList() {
        Settings.MUTED_WORDS.save("cake, Cake,  pie ");
        assertEquals("cake\npie", Settings.MUTED_WORDS.savedValue());
        assertEquals("cake\npie", Settings.MUTED_WORDS.get());
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        assertEquals("", Settings.MUTED_WORDS.get());
        assertEquals("cake\npie", Settings.MUTED_WORDS.savedValue());
    }

    // ---- The row --------------------------------------------------------------------------------

    private interface Check {
        void run(Activity activity, MutedWordsPreference row) throws Exception;
    }

    private static void onRow(Check check) throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            check.run(activity, new MutedWordsPreference(activity));
        }
    }

    private static AlertDialog tap(MutedWordsPreference row) {
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowLooper.idleMainLooper();
        assertNotNull("no dialog opened", MutedWordsPreference.shown);
        return MutedWordsPreference.shown;
    }

    @Test
    public void theRowSaysWhatItDoesAndHowManyWordsItHolds() throws Exception {
        onRow((activity, row) -> {
            assertEquals("action_muted_words", row.getKey());
            assertEquals("Muted words", row.getTitle());
            assertEquals("Hides pins whose title or description has a word or phrase you add here. None yet.", row.getSummary());
            Settings.MUTED_WORDS.save("cake");
            assertEquals("Hiding pins that mention 1 word or phrase. Tap to change the list.", row.getSummary());
            Settings.MUTED_WORDS.save("cake, pie");
            assertEquals("Hiding pins that mention any of 2 words or phrases. Tap to change the list.", row.getSummary());
            PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
            assertEquals("a pause doesn't empty the row", "Hiding pins that mention any of 2 words or phrases. "
                    + "Tap to change the list.", row.getSummary());
        });
    }

    @Test
    public void saveKeepsWhatWasTypedCleanedAndSaysWhatWasLeftOut() throws Exception {
        onRow((activity, row) -> {
            AlertDialog dialog = tap(row);
            assertFalse("Clear shows with nothing to clear", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isShown());
            EditText field = dialog.findViewById(android.R.id.edit);
            assertEquals("", field.getText().toString());
            field.setText("cake, Cake\nwedding dress\n" + run("x", 41));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals("cake\nwedding dress", Settings.MUTED_WORDS.savedValue());
            assertEquals("2 muted words saved. 1 was longer than 40 characters, so it was left out.",
                    ShadowToast.getTextOfLatestToast());
            assertEquals("Hiding pins that mention any of 2 words or phrases. Tap to change the list.", row.getSummary());
        });
    }

    @Test
    public void theDialogOpensOnTheListAndClearEmptiesIt() throws Exception {
        Settings.MUTED_WORDS.save("cake, pie");
        onRow((activity, row) -> {
            AlertDialog dialog = tap(row);
            assertEquals("Muted words", String.valueOf(shadowOf(dialog).getTitle()));
            EditText field = dialog.findViewById(android.R.id.edit);
            assertEquals("cake\npie", field.getText().toString());
            assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isShown());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals("", Settings.MUTED_WORDS.savedValue());
            assertEquals("No muted words. Every pin shows again.", ShadowToast.getTextOfLatestToast());
            assertNull(MutedWordsPreference.shown);
        });
    }

    @Test
    public void aFullListSaysHowManyDidNotFit() {
        List<String> typed = new ArrayList<>();
        for (int i = 0; i < 52; i++) typed.add("word" + i);
        assertEquals("50 muted words saved. 2 more words didn't fit, since the list holds 50.",
                MutedWordsPreference.savedMessage(MutedWordsSetting.clean(String.join("\n", typed))));
        assertEquals("1 muted word saved.", MutedWordsPreference.savedMessage(MutedWordsSetting.clean("cake")));
        assertEquals("0 muted words saved. 1 was longer than 40 characters, so it was left out.",
                MutedWordsPreference.savedMessage(MutedWordsSetting.clean(run("x", 41))));
    }
}
