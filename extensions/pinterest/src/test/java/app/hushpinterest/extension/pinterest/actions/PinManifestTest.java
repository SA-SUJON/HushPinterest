/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.ListView;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.PatchFamilyForTests;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, shadows = GridDownloadsTest.Cells.class,
        instrumentedPackages = "app.hushpinterest.extension.pinterest.actions")
public class PinManifestTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private static final String PICKER = "hushpinterest-pin-list";
    private Activity activity;
    private RecyclerView grid;

    @Before public void prepare() {
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        Utils.setActivity(activity);
        PauseForTests.resume();
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.PIN_DOWNLOADS));
        Settings.DOWNLOAD_PINS.save(true);
        grid = new RecyclerView(activity);
        activity.setContentView(grid);
        grid.layout(0, 0, 400, 600);
        PinListProvider.install();
        ShadowToast.reset();
    }

    @After public void reset() throws Exception {
        if (ShadowAlertDialog.getLatestAlertDialog() != null) ShadowAlertDialog.getLatestAlertDialog().dismiss();
        Field running = GridDownloads.class.getDeclaredField("RUNNING");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(null)).set(false);
        settle();
        pending().set(null);
        Settings.DOWNLOAD_PINS.save(false);
        PatchFamilyForTests.capabilities(null);
        PauseForTests.resume();
        Utils.setActivity(null);
    }

    /** The list waiting for its file, as a new process would find it after this one ended. */
    @SuppressWarnings("unchecked")
    private static AtomicReference<Object> pending() throws Exception {
        Field field = PinManifest.class.getDeclaredField("PENDING");
        field.setAccessible(true);
        return (AtomicReference<Object>) field.get(null);
    }

    private static Map<String, Object> pin(String id, String title, Object link) {
        Map<String, Object> pin = new LinkedHashMap<>();
        pin.put("id", id);
        pin.put("images", Map.of("orig", Map.of("url", "https://i.pinimg.com/originals/" + id + ".jpg")));
        if (title != null) pin.put("title", title);
        if (link != null) pin.put("link", link);
        return pin;
    }

    private View cell(Object pin, int top) {
        View cell = new View(activity);
        cell.setTag(pin);
        grid.addView(cell);
        cell.layout(0, top, 200, top + 100);
        return cell;
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 10; i++) {
            Utils.awaitBackgroundTasksForTests();
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private static Set<String> keys(JSONObject object) {
        Set<String> keys = new HashSet<>();
        for (Iterator<String> names = object.keys(); names.hasNext(); ) keys.add(names.next());
        return keys;
    }

    private ShadowActivity.IntentForResult exportOne() {
        assertTrue(PinManifest.export(activity, PinManifest.entries(List.of(pin("101", "Rye bread", "https://example.com/rye"))), false));
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull("no file picker opened", started);
        return started;
    }

    private void answer(ShadowActivity.IntentForResult started, Uri file) throws Exception {
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(file));
        settle();
    }

    /** Taps [format]'s row in the Save list as choice, the dialog in front. */
    private static void pick(PinManifest.Format format) {
        ListView kinds = ShadowAlertDialog.getLatestAlertDialog().getListView();
        kinds.performItemClick(kinds.getChildAt(format.ordinal()), format.ordinal(), format.ordinal());
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static JSONObject read(Uri file) throws Exception {
        byte[] bytes = PinListProvider.get(file);
        assertNotNull("nothing was written", bytes);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    /** A source link goes in exactly as Pinterest supplied it, or not at all: nothing is cut off it to make it fit. */
    @Test public void aSourceLinkIsKeptExactlyAsGivenOrLeftOut() {
        for (String link : new String[]{"https://example.com/recipes/rye-bread", "http://blog.example.org/2026/10/a.html",
                "https://Example.COM:8443/Path/To", "https://shop.example.co.uk/a/b/", "https://example.com"}) {
            assertEquals(link, PinManifest.sourceLink(link));
        }
        for (String link : new String[]{
                // Not a web address.
                "javascript:alert(1)", "data:text/html,hello", "ftp://example.com/file", "file:///sdcard/a.jpg",
                "content://media/external/1", "intent://example.com/#Intent;scheme=https;end",
                // A private network or this device.
                "https://localhost/a", "https://localhost./a", "http://printer.local/", "https://nas.home.arpa/files",
                "https://wiki.corp/", "https://intranet/page", "http://192.168.1.10/admin", "http://127.1/",
                "http://0x7f000001/", "http://10.0.0.1:8080/", "http://[::1]/", "http://[fe80::1]/",
                // Names kept for testing, examples and networks of their own.
                "https://shop.test/a", "https://a.invalid/", "https://blog.example/post", "http://abc.onion/",
                "https://name.alt/",
                // A sign-in, a token, a session or tracking.
                "https://user:secret@example.com/", "https://token@example.com/", "https://example.com/a?utm_source=pinterest",
                "https://example.com/a?", "https://example.com/a#top", "https://example.com/a#", "https://example.com/a;jsessionid=ABC123",
                // Pinterest's own media files.
                "https://i.pinimg.com/originals/ab/cd.jpg", "https://pinimg.com/x",
                // Not as given.
                " https://example.com/a", "https://exa mple.com/"}) {
            assertNull(link, PinManifest.sourceLink(link));
        }
    }

    @Test public void eachPinSaysWhetherItsLinkIsIncludedMissingOrOmitted() {
        PinManifest.Entry included = PinManifest.entry(pin("1", "Rye bread", "https://example.com/rye"));
        assertEquals(PinManifest.LinkStatus.INCLUDED, included.status);
        assertEquals("https://example.com/rye", included.link);
        PinManifest.Entry omitted = PinManifest.entry(pin("2", null, "https://example.com/a?utm_source=pinterest"));
        assertEquals(PinManifest.LinkStatus.OMITTED, omitted.status);
        assertNull(omitted.link);
        assertEquals(PinManifest.LinkStatus.MISSING, PinManifest.entry(pin("3", null, null)).status);
        assertEquals(PinManifest.LinkStatus.MISSING, PinManifest.entry(pin("4", null, "")).status);
        assertEquals(PinManifest.LinkStatus.MISSING, PinManifest.entry(pin("5", null, Map.of("url", "https://example.com/"))).status);

        assertNull("a board isn't a pin", PinManifest.entry(Map.of("id", "6", "name", "Bread")));
        assertEquals(1, PinManifest.entries(Arrays.asList(pin("7", null, null), "a story, not a pin", null)).size());
    }

    @Test public void titlesLoseControlCharactersAndStayBounded() {
        assertEquals("Rye bread  loaf", PinManifest.entry(pin("1", "  Rye\nbread\u0000 loaf\t", null)).title);
        assertNull(PinManifest.entry(pin("2", " \n ", null)).title);
        assertEquals(500, PinManifest.entry(pin("3", "a".repeat(600), null)).title.length());
        String split = PinManifest.entry(pin("4", "a".repeat(499) + "😀" + "b".repeat(10), null)).title;
        assertEquals("a pair isn't cut in half", "a".repeat(499), split);
    }

    /** The list names what it holds and that it's partial, and holds nothing but ids, addresses, titles and checked links. */
    @Test public void theListHoldsOnlyItsOwnFieldsAndSaysItIsPartial() throws Exception {
        List<PinManifest.Entry> entries = PinManifest.entries(List.of(
                pin("1", "Say \"hi\" \\ there", "https://example.com/a"),
                pin("2", null, "https://example.com/b?utm_source=pinterest"),
                pin("3", "Third", null)));
        String text = PinManifest.json(entries, false, Instant.parse("2026-10-12T08:30:15.987Z").toEpochMilli());
        JSONObject list = new JSONObject(text);
        assertEquals(Set.of("format", "version", "created", "scope", "complete", "note", "links_note", "pins"), keys(list));
        assertEquals("hushpinterest-pin-list", list.getString("format"));
        assertEquals(1, list.getInt("version"));
        assertEquals("2026-10-12T08:30:15Z", list.getString("created"));
        assertEquals("chosen_visible_pins", list.getString("scope"));
        assertFalse(list.getBoolean("complete"));
        assertEquals(L10n.t("This list has only the pins chosen from those on screen when it was saved."), list.getString("note"));
        assertTrue(list.getString("links_note").contains("\"missing\""));

        JSONArray pins = list.getJSONArray("pins");
        assertEquals(3, pins.length());
        for (int i = 0; i < pins.length(); i++) {
            assertEquals(Set.of("id", "url", "title", "source_link", "source_link_status"), keys(pins.getJSONObject(i)));
        }
        JSONObject first = pins.getJSONObject(0);
        assertEquals("1", first.getString("id"));
        assertEquals("https://www.pinterest.com/pin/1/", first.getString("url"));
        assertEquals("Say \"hi\" \\ there", first.getString("title"));
        assertEquals("https://example.com/a", first.getString("source_link"));
        assertEquals("included", first.getString("source_link_status"));
        JSONObject second = pins.getJSONObject(1);
        assertTrue(second.isNull("title"));
        assertTrue(second.isNull("source_link"));
        assertEquals("omitted", second.getString("source_link_status"));
        assertEquals("missing", pins.getJSONObject(2).getString("source_link_status"));
        assertFalse("a media address got in", text.contains("pinimg"));
        assertFalse("an omitted link got in", text.contains("utm_source"));

        JSONObject board = new JSONObject(PinManifest.json(entries.subList(0, 0), true, 0));
        assertEquals("board_loaded_pins", board.getString("scope"));
        assertEquals(L10n.f("This list has only the pins Pinterest had loaded for the board when it was saved, up to %d, "
                + "of the kind chosen. Pins further down the board aren't in it.", BoardDownloads.PINS), board.getString("note"));
        assertEquals(0, board.getJSONArray("pins").length());
    }

    /** The bookmarks file has the JSON list's pins, link rules and notes, one bookmark a pin. */
    @Test public void theBookmarksFileHoldsTheSamePinsLinksAndNotes() {
        List<PinManifest.Entry> entries = PinManifest.entries(List.of(
                pin("1", "Rye & wheat", "https://example.com/a"),
                pin("2", null, "https://example.com/b?utm_source=pinterest"),
                pin("3", "Third", null)));
        Instant made = Instant.parse("2026-10-12T08:30:15.987Z");
        long added = made.getEpochSecond();
        String html = PinManifest.bookmarks(entries, false, made.toEpochMilli());
        assertTrue(html, html.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n"));
        assertTrue(html, html.contains("CONTENT=\"text/html; charset=UTF-8\""));
        assertTrue(html, html.contains("<DT><H3 ADD_DATE=\"" + added + "\">HushPinterest pins</H3>\n"));

        String[] lines = html.split("\n");
        List<String> marks = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("<DT><A ")) marks.add(lines[i].trim() + "|" + lines[i + 1].trim());
        }
        assertEquals(List.of(
                "<DT><A HREF=\"https://www.pinterest.com/pin/1/\" ADD_DATE=\"" + added + "\">Rye &amp; wheat</A>"
                        + "|<DD>Source: https://example.com/a",
                "<DT><A HREF=\"https://www.pinterest.com/pin/2/\" ADD_DATE=\"" + added + "\">https://www.pinterest.com/pin/2/</A>"
                        + "|<DD>Source link: omitted",
                "<DT><A HREF=\"https://www.pinterest.com/pin/3/\" ADD_DATE=\"" + added + "\">Third</A>"
                        + "|<DD>Source link: missing"), marks);
        assertTrue("the partial note is gone",
                html.contains(L10n.t("This list has only the pins chosen from those on screen when it was saved.")));
        assertTrue("the link note is gone", html.contains("&quot;omitted&quot;"));
        assertFalse("a media address got in", html.contains("pinimg"));
        assertFalse("an omitted link got in", html.contains("utm_source"));

        String board = PinManifest.bookmarks(entries.subList(0, 0), true, 0);
        assertTrue(board, board.contains("up to " + BoardDownloads.PINS + ","));
        assertFalse("an empty list still has a bookmark", board.contains("<A "));
    }

    /** A title or a link can't add markup, a script or a second bookmark of its own. */
    @Test public void aTitleWithMarkupInItStaysTextInTheBookmarksFile() {
        String title = "</A><script>alert(1)</script><A HREF=\"https://evil.example/\" onclick='x()'>&lt;";
        String html = PinManifest.bookmarks(List.of(new PinManifest.Entry("1", title,
                "https://example.com/it's\"><b>", PinManifest.LinkStatus.INCLUDED)), false, 0);
        assertTrue(html, html.contains(">&lt;/A&gt;&lt;script&gt;alert(1)&lt;/script&gt;&lt;A HREF=&quot;https://evil.example/&quot; "
                + "onclick=&#39;x()&#39;&gt;&amp;lt;</A>\n"));
        assertTrue(html, html.contains("<DD>Source: https://example.com/it&#39;s&quot;&gt;&lt;b&gt;\n"));
        assertEquals("one bookmark", 1, html.split("<A ", -1).length - 1);
        // With the file's own tags taken out, no bracket or quote is left to start another.
        String rest = html.replaceAll("</?(?:!DOCTYPE NETSCAPE-Bookmark-file-1"
                + "|META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\""
                + "|TITLE|H1|DL|p|DT|DD|H3 ADD_DATE=\"\\d+\"|H3"
                + "|A HREF=\"https://www\\.pinterest\\.com/pin/\\d+/\" ADD_DATE=\"\\d+\"|A)>", "");
        assertFalse(rest, rest.contains("<") || rest.contains(">") || rest.contains("\""));
    }

    /** Save list asks which kind of file to write, and the bookmarks kind goes to an HTML file. */
    @Test public void saveListAsksForTheKindOfFileAndWritesBookmarks() throws Exception {
        View origin = cell(pin("101", "Rye bread", "https://example.com/rye"), 0);
        cell(pin("102", null, "https://example.com/sourdough?utm_source=pinterest"), 100);
        assertTrue(GridDownloads.show(origin));
        AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
        ListView list = chooser.getListView();
        for (int position : new int[]{0, 1}) list.performItemClick(list.getChildAt(position), position, position);
        chooser.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertNull("the picker opened before a kind was chosen", shadowOf(activity).getNextStartedActivityForResult());
        AlertDialog kinds = ShadowAlertDialog.getLatestAlertDialog();
        assertNotSame(chooser, kinds);
        assertEquals(PinManifest.Format.values().length, kinds.getListView().getCount());
        pick(PinManifest.Format.BOOKMARKS);
        assertFalse("the choice stayed open", kinds.isShowing());

        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(started);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.getAction());
        assertEquals("text/html", started.intent.getType());
        assertTrue(started.intent.getStringExtra(Intent.EXTRA_TITLE),
                started.intent.getStringExtra(Intent.EXTRA_TITLE).matches("hushpinterest-pins-\\d{8}-\\d{6}\\.html"));

        Uri file = PinListProvider.put("pins.html", null);
        answer(started, file);
        assertEquals("Saved a list of 2 pins.", ShadowToast.getTextOfLatestToast());
        String html = new String(PinListProvider.get(file), StandardCharsets.UTF_8);
        assertTrue(html, html.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n"));
        assertTrue(html, html.contains("<A HREF=\"https://www.pinterest.com/pin/101/\""));
        assertTrue(html, html.contains("<DD>Source: https://example.com/rye\n"));
        assertTrue(html, html.contains("<A HREF=\"https://www.pinterest.com/pin/102/\""));
        assertEquals(0, shadowOf((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE)).getRequestCount());
        assertNull(activity.getFragmentManager().findFragmentByTag(PICKER));
        assertNull(pending().get());
    }

    /** Save list writes the chosen pins, in the chooser's order, to the file picked, and downloads nothing. */
    @Test public void saveListWritesTheChosenPinsToThePickedFile() throws Exception {
        View origin = cell(pin("101", "Rye bread", "https://example.com/rye"), 0);
        cell(pin("102", "Sourdough", "https://example.com/sourdough?utm_source=pinterest"), 100);
        cell(pin("103", "Left out", "https://example.com/left-out"), 200);
        assertTrue(GridDownloads.show(origin));
        AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
        assertFalse("nothing is chosen yet", chooser.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled());
        ListView list = chooser.getListView();
        for (int position : new int[]{0, 1}) list.performItemClick(list.getChildAt(position), position, position);
        assertTrue(chooser.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled());
        chooser.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        pick(PinManifest.Format.JSON);

        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(started);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.getAction());
        assertEquals("application/json", started.intent.getType());
        assertTrue(started.intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertTrue(started.intent.getStringExtra(Intent.EXTRA_TITLE),
                started.intent.getStringExtra(Intent.EXTRA_TITLE).matches("hushpinterest-pins-\\d{8}-\\d{6}\\.json"));
        assertNotNull("the list waits for its file", pending().get());

        Uri file = PinListProvider.put("pins.json", null);
        answer(started, file);
        assertEquals("Saved a list of 2 pins.", ShadowToast.getTextOfLatestToast());
        JSONArray pins = read(file).getJSONArray("pins");
        assertEquals(2, pins.length());
        assertEquals("101", pins.getJSONObject(0).getString("id"));
        assertEquals("https://example.com/rye", pins.getJSONObject(0).getString("source_link"));
        assertEquals("102", pins.getJSONObject(1).getString("id"));
        assertEquals("omitted", pins.getJSONObject(1).getString("source_link_status"));
        assertEquals(0, shadowOf((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE)).getRequestCount());
        assertNull(activity.getFragmentManager().findFragmentByTag(PICKER));
        assertNull(pending().get());
    }

    @Test public void oneLinkedPinIsCountedAsOne() throws Exception {
        Uri file = PinListProvider.put("one.json", null);
        answer(exportOne(), file);
        assertEquals("Saved a list of one pin.", ShadowToast.getTextOfLatestToast());
        assertEquals("Rye bread", read(file).getJSONArray("pins").getJSONObject(0).getString("title"));
    }

    /** Backing out of the picker writes nothing, says nothing and lets the list go. */
    @Test public void cancellingThePickerWritesNothingAndSaysNothing() throws Exception {
        ShadowActivity.IntentForResult started = exportOne();
        Uri file = PinListProvider.put("cancelled.json", null);
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, new Intent().setData(file));
        settle();
        assertNull(PinListProvider.get(file));
        assertNull(ShadowToast.getTextOfLatestToast());
        assertNull(pending().get());
        assertNull(activity.getFragmentManager().findFragmentByTag(PICKER));
    }

    /** The picker makes an empty file before the list is written; a list that isn't written takes it away. */
    @Test public void aFileTheAppWontWriteIsAFailureNotASave() throws Exception {
        PinListProvider.refusesWrite = true;
        Uri file = PinListProvider.put("refused.json", new byte[0]);
        answer(exportOne(), file);
        assertEquals("Couldn't save the pin list. Try again.", ShadowToast.getTextOfLatestToast());
        assertNull("the picker's empty file is gone", PinListProvider.get(file));
    }

    @Test public void anEmptyFileThatCantBeRemovedIsNamedInTheFailure() throws Exception {
        PinListProvider.refusesWrite = true;
        PinListProvider.refusesDelete = true;
        Uri file = PinListProvider.put("stuck.json", new byte[0]);
        ShadowToast.reset();
        answer(exportOne(), file);
        // Android 12 and later cut a toast at two lines, so the note is a toast of its own.
        assertEquals(2, ShadowToast.shownToastCount());
        assertTrue(ShadowToast.showedToast("Couldn't save the pin list. Try again."));
        assertEquals("Couldn't remove the empty file. Delete it in your files app.",
                ShadowToast.getTextOfLatestToast());
        assertEquals(0, PinListProvider.get(file).length);
    }

    /** A picker can hand back a file that's already there, picked to be written over. It's the user's, so it stays. */
    @Test public void aFilePickedToWriteOverStaysWhenTheListIsntWritten() throws Exception {
        byte[] mine = "{\"mine\":true}".getBytes(StandardCharsets.UTF_8);
        PinListProvider.refusesWrite = true;
        Uri file = PinListProvider.put("mine.json", mine);
        ShadowToast.reset();
        answer(exportOne(), file);
        assertEquals(1, ShadowToast.shownToastCount());
        assertEquals("Couldn't save the pin list. Try again.", ShadowToast.getTextOfLatestToast());
        assertArrayEquals(mine, PinListProvider.get(file));

        PinListProvider.refusesWrite = false;
        ShadowActivity.IntentForResult started = exportOne();
        pending().set(null);
        answer(started, file);
        assertArrayEquals("a list lost while the picker was open leaves it too", mine, PinListProvider.get(file));

        // A write that emptied the file before failing still leaves the file where it was.
        PinListProvider.truncatesThenRefuses = true;
        answer(exportOne(), file);
        assertEquals("Couldn't save the pin list. Try again.", ShadowToast.getTextOfLatestToast());
        assertNotNull("the file is still there", PinListProvider.get(file));
    }

    @Test public void aFileWhoseSizeCantBeReadIsLeftAlone() throws Exception {
        PinListProvider.refusesWrite = true;
        PinListProvider.hidesSize = true;
        Uri file = PinListProvider.put("unknown.json", new byte[0]);
        answer(exportOne(), file);
        assertTrue(ShadowToast.showedToast("Couldn't save the pin list. Try again."));
        assertEquals("File left alone. If it's empty, delete it in your files app.",
                ShadowToast.getTextOfLatestToast());
        assertNotNull("the file stays", PinListProvider.get(file));
    }

    @Test public void aFileThatCantBeReadBackIsSavedButNotCalledChecked() throws Exception {
        PinListProvider.refusesRead = true;
        Uri file = PinListProvider.put("unreadable.json", null);
        answer(exportOne(), file);
        assertEquals("Pin list saved, but the app holding it blocked the check.",
                ShadowToast.getTextOfLatestToast());
        assertEquals("101", read(file).getJSONArray("pins").getJSONObject(0).getString("id"));
    }

    /** An app that won't truncate leaves old bytes past the new end; the read back catches them instead of calling it saved. */
    @Test public void oldBytesLeftPastTheEndAreNotCalledSaved() throws Exception {
        PinListProvider.refusesTruncate = true;
        Uri file = PinListProvider.put("longer.json", "x".repeat(20_000).getBytes(StandardCharsets.UTF_8));
        answer(exportOne(), file);
        assertEquals("The saved list reads back wrong. Save it again as a new file.",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void aFileThatTakesTheWholeListIsSavedWhenTruncatingIsRefused() throws Exception {
        PinListProvider.refusesTruncate = true;
        Uri file = PinListProvider.put("new.json", null);
        answer(exportOne(), file);
        assertEquals("Saved a list of one pin.", ShadowToast.getTextOfLatestToast());
    }

    /** HushPinterest closed while the picker was open: the list went with it, so nothing is written. */
    @Test public void aListLostWhileThePickerWasOpenIsNotSaved() throws Exception {
        ShadowActivity.IntentForResult started = exportOne();
        pending().set(null);
        Uri file = PinListProvider.put("late.json", new byte[0]);
        answer(started, file);
        assertEquals("HushPinterest closed with the picker open. Pick and save again.",
                ShadowToast.getTextOfLatestToast());
        assertNull("the picker's empty file is gone", PinListProvider.get(file));
    }

    @Test public void switchedOffWhileThePickerWasOpenNothingIsWritten() throws Exception {
        ShadowActivity.IntentForResult started = exportOne();
        Settings.DOWNLOAD_PINS.save(false);
        Uri file = PinListProvider.put("off.json", new byte[0]);
        answer(started, file);
        assertEquals("Resume HushPinterest and turn on Download pins to use this.", ShadowToast.getTextOfLatestToast());
        assertNull("the picker's empty file is gone", PinListProvider.get(file));
        assertNull(pending().get());
    }

    @Test public void withNoFilePickerTheListIsDroppedAndThatIsSaid() throws Exception {
        shadowOf(activity.getApplication()).checkActivities(true);
        try {
            assertFalse(PinManifest.export(activity, PinManifest.entries(List.of(pin("101", null, null))), false));
        } finally {
            shadowOf(activity.getApplication()).checkActivities(false);
        }
        settle();
        assertEquals("This phone has no file picker, so a file can't be chosen.", ShadowToast.getTextOfLatestToast());
        assertNull(pending().get());
        assertNull(activity.getFragmentManager().findFragmentByTag(PICKER));
        assertFalse("an empty list opens nothing", PinManifest.export(activity, new ArrayList<>(), false));
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
    }

    /** A screen the system won't open the picker from, as a SecurityException does. */
    public static final class Refusing extends Activity {
        @Override
        public void startActivityFromFragment(android.app.Fragment fragment, Intent intent, int requestCode,
                                              android.os.Bundle options) {
            throw new SecurityException("Not allowed to start the picker");
        }
    }

    @Test public void aPickerTheSystemRefusesIsntLeftBehind() throws Exception {
        Activity refusing = Robolectric.buildActivity(Refusing.class).setup().visible().get();
        assertFalse(PinManifest.export(refusing, PinManifest.entries(List.of(pin("101", null, null))), false));
        settle();
        assertEquals("Couldn't open the file picker. Try again.", ShadowToast.getTextOfLatestToast());
        assertNull(pending().get());
        assertNull(refusing.getFragmentManager().findFragmentByTag(PICKER));
    }
}
