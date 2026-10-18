/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.HushDialogs;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;
import app.hushpinterest.extension.shared.settings.preference.LogBufferManager;

/**
 * Save list: the pins a batch chooser holds, written to a file picked through Android's file
 * picker, as a bookmarks file browsers and bookmark apps import or as a JSON list. Each pin is
 * its id, its pin address, its title when it has one, and the source link Pinterest supplied in
 * its {@code link} field when that link can be kept exactly as given. Nothing is fetched to fill
 * it in, and it holds no account, cookie or media address. It's separate from the settings file
 * and the diagnostic report.
 */
public final class PinManifest {
    private PinManifest() {}

    static final String MIME_TYPE = "application/json";

    /** The file a list is saved as, in the order the choice offers them. */
    enum Format {
        /** A Netscape bookmarks file: every pin a bookmark, its source link in the description. */
        BOOKMARKS("text/html", ".html"),
        JSON(MIME_TYPE, ".json");

        final String mime;
        final String extension;

        Format(String mime, String extension) {
            this.mime = mime;
            this.extension = extension;
        }
    }
    static final int REQUEST = 0x4850;
    static final String FORMAT = "hushpinterest-pin-list";
    private static final String TAG = "hushpinterest-pin-list";
    private static final String TOKEN = "token";
    /** When the picker opened, by the wall clock a document app stamps files with. Kept with the fragment, so it outlives the process. */
    private static final String OPENED = "opened";

    /** How a pin's source link went into the list. */
    enum LinkStatus {
        INCLUDED, MISSING, OMITTED;

        String code() { return name().toLowerCase(Locale.ROOT); }
    }

    /** One pin as the list holds it. */
    static final class Entry {
        final String id;
        final String title;
        final String link;
        final LinkStatus status;

        Entry(String id, String title, String link, LinkStatus status) {
            this.id = id;
            this.title = title;
            this.link = link;
            this.status = status;
        }
    }

    /** [pin]'s entry, read from its own fields, or null when it isn't a verified pin. */
    static Entry entry(Object pin) {
        String id = PinMedia.id(pin);
        if (id == null) return null;
        Object title = PinMedia.field(pin, "title");
        Object link = PinMedia.field(pin, "link");
        String kept = link instanceof String ? sourceLink((String) link) : null;
        LinkStatus status = kept != null ? LinkStatus.INCLUDED
                : link instanceof String && !((String) link).isEmpty() ? LinkStatus.OMITTED : LinkStatus.MISSING;
        return new Entry(id, title instanceof String ? title((String) title) : null, kept, status);
    }

    static List<Entry> entries(List<Object> pins) {
        List<Entry> entries = new ArrayList<>();
        for (Object pin : pins) {
            Entry entry = entry(pin);
            if (entry != null) entries.add(entry);
        }
        return entries;
    }

    private static String title(String title) {
        String text = title.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (text.length() > 500) text = text.substring(0, Character.isHighSurrogate(text.charAt(499)) ? 499 : 500);
        return text.isEmpty() ? null : text;
    }

    /** Names that only mean something on a private network or on this device. */
    private static final Pattern LOCAL_HOST = Pattern.compile(
            "(?:.+\\.)?(?:localhost|local|localdomain|internal|intranet|lan|home|corp|home\\.arpa"
                    + "|test|invalid|example|onion|alt)");

    /** A host made of numbers alone, in any of the forms an address can be written. */
    private static final Pattern NUMERIC_HOST = Pattern.compile("(?:(?:0x[0-9a-f]+|[0-9]+)\\.?)+");

    /**
     * [link] exactly as supplied, when the list can keep it: a web address with a public host
     * name, and nothing in it that can carry a session, a token or tracking. Null otherwise. A link
     * with a query or fragment is left out rather than shortened, since a part taken off could
     * change where it goes.
     */
    static String sourceLink(String link) {
        URI uri = PinMedia.webUri(link);
        // In an address that parses, '?' and '#' only start a query or fragment, empty ones included.
        if (uri == null || link.indexOf('?') >= 0 || link.indexOf('#') >= 0) return null;
        String path = uri.getRawPath();
        // A path parameter such as ;jsessionid= carries a session, and some sites decode %3B into one.
        if (path != null && hidesPathParameter(path)) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (host.startsWith("[") || !host.contains(".") || NUMERIC_HOST.matcher(host).matches()
                || LOCAL_HOST.matcher(host).matches()) return null;
        // Pinterest's media addresses are files, not sources, and they can stop working.
        if (PinMedia.mediaUri("https://" + host + "/") != null) return null;
        return link;
    }

    /** How many times a path is decoded looking for a ';'. One still encoded after that could be hiding one. */
    private static final int DECODE_PASSES = 3;

    /**
     * True when [path] has a ';', or decodes into one within {@link #DECODE_PASSES} passes, as
     * %3B, %253B or %25253B do. A path still encoded after the last pass, or with an escape that
     * doesn't decode, counts as hiding one too. Encoded spaces and letters (%20, %C3%A9) aren't.
     */
    static boolean hidesPathParameter(String path) {
        for (int pass = 0; ; pass++) {
            if (path.indexOf(';') >= 0) return true;
            if (path.indexOf('%') < 0) return false;
            if (pass == DECODE_PASSES) return true;
            path = decodedOnce(path);
            if (path == null) return true;
        }
    }

    /**
     * [path] with each %XX turned into its byte as one char, enough to find an ASCII ';' or '%'.
     * Null on a broken escape, or on the start of an overlong UTF-8 form, which some servers
     * still read as the ASCII character it spells out.
     */
    private static String decodedOnce(String path) {
        StringBuilder out = new StringBuilder(path.length());
        int previous = -1;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c != '%') {
                out.append(c);
                previous = -1;
                continue;
            }
            int high = i + 2 < path.length() ? hex(path.charAt(i + 1)) : -1;
            int low = high >= 0 ? hex(path.charAt(i + 2)) : -1;
            if (low < 0) return null;
            int value = high * 16 + low;
            if (overlong(previous, value)) return null;
            out.append((char) value);
            previous = value;
            i += 2;
        }
        return out.toString();
    }

    /** Whether escaped bytes [lead] then [next] open an overlong UTF-8 form, one too long for what it spells. */
    private static boolean overlong(int lead, int next) {
        if (next < 0x80 || next > 0xBF) return false;
        return lead == 0xC0 || lead == 0xC1 || (lead == 0xE0 && next < 0xA0) || (lead == 0xF0 && next < 0x90);
    }

    /** [c]'s value as an ASCII hex digit, either case, or -1. */
    private static int hex(char c) {
        return c < 128 ? Character.digit(c, 16) : -1;
    }

    /** The list as JSON, made at [now] from [entries]. [board] says whether they're a board's loaded pins. */
    static String json(List<Entry> entries, boolean board, long now) {
        StringBuilder out = new StringBuilder("{\n");
        out.append("  \"format\": ").append(quote(FORMAT)).append(",\n");
        out.append("  \"version\": 1,\n");
        out.append("  \"created\": ").append(quote(Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.SECONDS).toString())).append(",\n");
        out.append("  \"scope\": ").append(quote(board ? "board_loaded_pins" : "chosen_visible_pins")).append(",\n");
        out.append("  \"complete\": false,\n");
        out.append("  \"note\": ").append(quote(note(board))).append(",\n");
        out.append("  \"links_note\": ").append(quote(linksNote())).append(",\n");
        out.append("  \"pins\": [");
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            out.append(i == 0 ? "\n" : ",\n").append("    {")
                    .append("\"id\": ").append(quote(entry.id))
                    .append(", \"url\": ").append(quote("https://www.pinterest.com/pin/" + entry.id + "/"))
                    .append(", \"title\": ").append(quote(entry.title))
                    .append(", \"source_link\": ").append(quote(entry.link))
                    .append(", \"source_link_status\": ").append(quote(entry.status.code()))
                    .append('}');
        }
        return out.append(entries.isEmpty() ? "]\n}\n" : "\n  ]\n}\n").toString();
    }

    /** What the list leaves out, said in the file itself whichever kind it is. */
    private static String note(boolean board) {
        return board
                ? L10n.f("This list has only the pins Pinterest had loaded for the board when it was saved, up to %d, "
                        + "of the kind chosen. Pins further down the board aren't in it.", BoardDownloads.PINS)
                : L10n.t("This list has only the pins chosen from those on screen when it was saved.");
    }

    private static String linksNote() {
        return L10n.t("A source link is \"missing\" when the pin had none, and "
                + "\"omitted\" when it couldn't be kept exactly as given, such as a link with tracking details "
                + "or one to a private network.");
    }

    /**
     * The list as a Netscape bookmarks file, made at [now] from [entries]: one folder holding a
     * bookmark per pin, with the same pins and the same notes as {@link #json}. A pin's bookmark is
     * its pin address under its title, or under the address when it has none. Its description is
     * the source link when the list can keep it, as text rather than a second bookmark, or the
     * status word the JSON list uses. Everything that came from Pinterest is escaped, so a title
     * can't add markup of its own.
     */
    static String bookmarks(List<Entry> entries, boolean board, long now) {
        long added = now / 1000;
        StringBuilder out = new StringBuilder("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
                .append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
                .append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
                .append("    <DT><H3 ADD_DATE=\"").append(added).append("\">HushPinterest pins</H3>\n")
                .append("    <DD>").append(escape(note(board) + " " + linksNote())).append('\n')
                .append("    <DL><p>\n");
        for (Entry entry : entries) {
            String url = "https://www.pinterest.com/pin/" + entry.id + "/";
            out.append("        <DT><A HREF=\"").append(escape(url)).append("\" ADD_DATE=\"").append(added).append("\">")
                    .append(escape(entry.title != null ? entry.title : url)).append("</A>\n")
                    .append("        <DD>").append(escape(entry.status == LinkStatus.INCLUDED
                            ? "Source: " + entry.link : "Source link: " + entry.status.code())).append('\n');
        }
        return out.append("    </DL><p>\n</DL><p>\n").toString();
    }

    /** [text] as HTML text or a quoted attribute value, kept on one line. */
    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029 ? ' ' : c);
            }
        }
        return out.toString();
    }

    private static String quote(String text) {
        if (text == null) return "null";
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    /** The list waiting for its file. One at a time: a newer list replaces one never saved. */
    private static final class Pending {
        final String token;
        final byte[] bytes;
        final int count;

        Pending(String token, byte[] bytes, int count) {
            this.token = token;
            this.bytes = bytes;
            this.count = count;
        }
    }

    private static final AtomicReference<Pending> PENDING = new AtomicReference<>();

    /** Asks which kind of file [entries] should be saved as, then opens the picker for that kind. */
    static void choose(Activity activity, List<Entry> entries, boolean board) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || entries.isEmpty()) return;
        try {
            Format[] formats = Format.values();
            CharSequence[] names = new CharSequence[formats.length];
            names[Format.BOOKMARKS.ordinal()] = L10n.t("Bookmarks file (HTML)") + "\n"
                    + L10n.t("Imports into browsers and bookmark apps.");
            names[Format.JSON.ordinal()] = L10n.t("JSON file") + "\n" + L10n.t("Keeps every detail, for other tools.");
            AlertDialog kinds = HushDialogs.builder(activity)
                    .setTitle(L10n.t("Save list as"))
                    .setSingleChoiceItems(names, -1, (dialog, which) -> {
                        dialog.dismiss();
                        export(activity, entries, board, formats[which]);
                    })
                    .setNegativeButton(L10n.t("Cancel"), null).create();
            HushDialogs.prepareChoices(kinds);
            kinds.show();
            HushDialogs.paint(kinds);
        } catch (RuntimeException failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "ask how to save the pin list", failure);
        }
    }

    static boolean export(Activity activity, List<Entry> entries, boolean board) {
        return export(activity, entries, board, Format.JSON);
    }

    /**
     * Makes the list from [entries] now and opens Android's picker to save it as [format]. The
     * answer comes to a fragment of [activity]'s own, so Pinterest's screen needs no change.
     * Returns whether the picker opened.
     */
    static boolean export(Activity activity, List<Entry> entries, boolean board, Format format) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || entries.isEmpty()) return false;
        if (stillStalled()) return false;
        Picker picker = null;
        try {
            long now = System.currentTimeMillis();
            byte[] bytes = (format == Format.BOOKMARKS ? bookmarks(entries, board, now) : json(entries, board, now))
                    .getBytes(StandardCharsets.UTF_8);
            String token = UUID.randomUUID().toString();
            PENDING.set(new Pending(token, bytes, entries.size()));
            FragmentManager fragments = activity.getFragmentManager();
            Fragment old = fragments.findFragmentByTag(TAG);
            picker = new Picker();
            Bundle arguments = new Bundle();
            arguments.putString(TOKEN, token);
            arguments.putLong(OPENED, now);
            picker.setArguments(arguments);
            android.app.FragmentTransaction change = fragments.beginTransaction();
            if (old != null) change.remove(old);
            change.add(picker, TAG).commitNow();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(format.mime)
                    .putExtra(Intent.EXTRA_TITLE, "hushpinterest-pins-" + LogBufferManager.fileTimestamp() + format.extension);
            try {
                picker.startActivityForResult(intent, REQUEST);
                return true;
            } catch (ActivityNotFoundException missing) {
                PENDING.set(null);
                picker.leave();
                Utils.showToastLong(L10n.t("This phone has no file picker, so a file can't be chosen."));
                return false;
            }
        } catch (RuntimeException failure) {
            PENDING.set(null);
            // A picker the system turned down, such as with a SecurityException, mustn't stay behind.
            if (picker != null) picker.leave();
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "open the pin list picker", failure);
            Utils.showToastLong(L10n.t("Couldn't open the file picker. Try again."));
            return false;
        }
    }

    /**
     * Holds the list's place while the picker is open and writes the list to the file picked. If
     * HushPinterest was closed in between, the list is gone and nothing is written.
     */
    public static final class Picker extends Fragment {
        void leave() {
            try {
                FragmentManager fragments = getFragmentManager();
                if (fragments != null) fragments.beginTransaction().remove(this).commitAllowingStateLoss();
            } catch (RuntimeException gone) {
                // The screen already went; so did this fragment.
            }
        }

        @Override
        public void onActivityResult(int request, int result, Intent data) {
            if (request != REQUEST) return;
            Bundle arguments = getArguments();
            String token = arguments == null ? null : arguments.getString(TOKEN);
            long opened = arguments == null ? -1 : arguments.getLong(OPENED, -1);
            Pending pending = PENDING.get();
            if (pending != null && pending.token.equals(token)) PENDING.compareAndSet(pending, null);
            else pending = null;
            Activity host = getActivity();
            Context app = host != null ? host.getApplicationContext() : Utils.getContext();
            leave();
            Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (uri == null) return; // Cancelled: nothing written and nothing to say.
            if (pending == null || app == null) {
                discardThenSay(app, uri, opened, L10n.t("HushPinterest closed with the picker open. Pick and save again."));
                return;
            }
            if (!PinDownloads.active()) {
                discardThenSay(app, uri, opened, L10n.t("Resume HushPinterest and turn on Download pins to use this."));
                return;
            }
            Pending list = pending;
            String failed = L10n.t("Couldn't save the pin list. Try again.");
            onFileWorker(app, (resolver, run) -> {
                // Read before writing: a failed write can leave a file picked to write over empty.
                Picked before = picked(resolver, uri, opened, run);
                try {
                    return new String[]{save(resolver, uri, list.bytes, list.count, run)};
                } catch (IOException | RuntimeException failure) {
                    HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "save the pin list", failure);
                    return discard(resolver, uri, failed, before, run);
                }
            }, new String[]{L10n.t("The file's app is too slow. Check the file before you use it.")},
                    new String[]{failed, leftAlone()});
        }
    }

    /** How long the app holding a picked file gets for a whole save, first question to last close. A test sets its own. */
    static volatile long timeoutMs = 30_000L;

    /**
     * Every question to the app holding a picked file goes through this one worker, never the main
     * thread or the shared pool. {@link #fileWork} lets one run in at a time, so an app that never
     * answers holds only this worker, and while it's held the next file is turned away at once
     * rather than another worker started. The queue's one place lets a run in while the last one's
     * thread is still on its way out.
     */
    private static final ThreadPoolExecutor FILE_WORKER = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1), work -> {
                Thread thread = new Thread(work, "hushpinterest-pin-list");
                thread.setDaemon(true);
                return thread;
            });
    static {
        FILE_WORKER.allowCoreThreadTimeOut(true);
    }

    /** 1 while a run is on the file worker, from the moment it's let in until its answer is posted to the main thread. */
    private static final AtomicInteger fileWork = new AtomicInteger();

    /** One save or clean-up of a picked file, with what the wait needs to stop it. */
    private static final class Run {
        /** Reaches the file's app while it opens or looks up the file, for an app that honours it. */
        final CancellationSignal cancel = new CancellationSignal();
        /** The file the worker has open, closed when the wait runs out. */
        volatile Closeable open;
        /** Taken once, on the main thread, by the worker's answer or by the wait running out. */
        boolean settled;

        /** Keeps [file] where the wait can close it, or closes it now when the wait already ran out. */
        AssetFileDescriptor hold(AssetFileDescriptor file) throws IOException {
            if (file == null) throw new FileNotFoundException("No file");
            open = file;
            if (cancel.isCanceled()) {
                file.close();
                throw new OperationCanceledException();
            }
            return file;
        }
    }

    /** A run's work against the picked file. It answers with what to say, one or two toasts. */
    private interface FileWork {
        String[] run(ContentResolver resolver, Run run) throws Exception;
    }

    /**
     * Runs [work] on the file worker within one {@link #timeoutMs} wait, called on the main thread.
     * Only the first to finish speaks: the worker's answer, or [late] once the wait runs out, when
     * the open is cancelled and the file closed. An app can ignore both, so [late] never says it
     * stopped. With the worker still held nothing reaches the file's app, and [busy] is said.
     */
    private static void onFileWorker(Context app, FileWork work, String[] late, String[] busy) {
        if (!fileWork.compareAndSet(0, 1)) {
            HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "pin list file worker still held");
            say(busy);
            return;
        }
        ContentResolver resolver = app.getContentResolver();
        Run run = new Run();
        Runnable task = () -> {
            String[] said;
            try {
                said = work.run(resolver, run);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "pin list file", failure);
                said = new String[]{L10n.t("Couldn't save the pin list. Try again.")};
            }
            String[] answer = said;
            try {
                Utils.runOnMainThread(() -> {
                    if (run.settled) {
                        HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "pin list file answered after the wait");
                        return;
                    }
                    run.settled = true;
                    say(answer);
                });
            } finally {
                fileWork.set(0);
            }
        };
        try {
            // The wait is set first, so a worker that never starts can't leave a run without one.
            Utils.runOnMainThreadDelayed(() -> {
                if (run.settled) return;
                run.settled = true;
                run.cancel.cancel();
                Closeable open = run.open;
                if (open != null) {
                    // Closing can wait on the app too, so not on the main thread.
                    Thread closing = new Thread(() -> closeQuietly(open), "hushpinterest-pin-list-close");
                    closing.setDaemon(true);
                    closing.start();
                }
                HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "stopped waiting on a pin list file");
                say(late);
            }, timeoutMs);
            FILE_WORKER.execute(task);
        } catch (Throwable held) {
            // Nothing reached the file's app: the worker was full, or its thread couldn't start.
            // A thread that fails to start can leave the run queued, so it comes back out.
            FILE_WORKER.remove(task);
            run.settled = true;
            fileWork.set(0);
            if (held instanceof RejectedExecutionException) {
                HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "pin list file worker still held");
            } else {
                HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "pin list file worker", held);
            }
            say(busy);
        }
    }

    /** Says so, and answers true, while a run still holds the file worker, so a new list isn't offered a picker yet. */
    private static boolean stillStalled() {
        if (fileWork.get() == 0) return false;
        Utils.showToastLong(L10n.t("The app with the last pin list hasn't answered. Try later."));
        return true;
    }

    private static void say(String[] lines) {
        for (String line : lines) Utils.showToastLong(line);
    }

    private static void closeQuietly(Closeable open) {
        try {
            open.close();
        } catch (IOException | RuntimeException ignored) {
            // The app holding the file already let it go, or won't say.
        }
    }

    /** For tests: waits until the file worker has handed its answer to the main thread. */
    static void awaitFileWorkForTests() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fileWork.get() > 0) {
            if (System.nanoTime() > until) throw new IllegalStateException("Pin list file work didn't finish");
            Thread.sleep(5);
        }
    }

    /**
     * The picker made the file before the list had a chance to fill it, so a list that isn't written
     * takes its file away again on the file worker, and says [said]. The picker opened at [opened].
     */
    private static void discardThenSay(Context app, Uri uri, long opened, String said) {
        String[] alone = {said, leftAlone()};
        if (app == null) {
            say(alone);
            return;
        }
        onFileWorker(app, (resolver, run) -> discard(resolver, uri, said, picked(resolver, uri, opened, run), run),
                alone, alone);
    }

    /**
     * Says [said], then a second toast when the picked file stays behind, since Android 12 and later
     * cut a toast at two lines. A file is taken away only when it was [before] the picker's own,
     * and only while the wait still holds. One that held something was picked to be written over
     * and is the user's own, and an empty one that was already there could be too.
     */
    private static String[] discard(ContentResolver resolver, Uri uri, String said, Picked before, Run run) {
        if (before.size > 0) return new String[]{said};
        if (!before.pickersOwn() || run.cancel.isCanceled()) return new String[]{said, leftAlone()};
        boolean gone;
        try {
            gone = DocumentsContract.deleteDocument(resolver, uri);
        } catch (FileNotFoundException | RuntimeException refused) {
            gone = false;
        }
        return gone ? new String[]{said}
                : new String[]{said, L10n.t("Couldn't remove the empty file. Delete it in your files app.")};
    }

    /**
     * How far before the picker opened a new file's time may read, for a document app on a server
     * whose clock runs a little behind. An empty file changed earlier than that was already there.
     */
    static final long CLOCK_SLACK_MS = 60_000L;

    /** The picked file as the app holding it describes it, before anything is written. */
    private static final class Picked {
        /** Bytes in it, or -1 when the app won't say. */
        final long size;
        /** When it last changed, or -1 when the app won't say. */
        final long modified;
        /** When the picker opened, or -1 when that was lost. */
        final long opened;

        Picked(long size, long modified, long opened) {
            this.size = size;
            this.modified = modified;
            this.opened = opened;
        }

        /** Empty and changed since the picker opened, so the picker made it rather than the user choosing one there already. */
        boolean pickersOwn() {
            return size == 0 && modified >= 0 && opened > 0 && modified >= opened - CLOCK_SLACK_MS;
        }
    }

    /** What the app holding the file at [uri] says of it. A file it won't describe counts as one that may be the user's. */
    private static Picked picked(ContentResolver resolver, Uri uri, long opened, Run run) {
        String[] columns = {OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try (Cursor cursor = resolver.query(uri, columns, null, null, null, run.cancel)) {
            if (cursor != null && cursor.moveToFirst()) {
                return new Picked(number(cursor, OpenableColumns.SIZE),
                        number(cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED), opened);
            }
        } catch (RuntimeException unanswered) {
            // Treated as unknown, so the file stays.
        }
        return new Picked(-1, -1, opened);
    }

    /** [column]'s value in [cursor]'s row, or -1 when it's missing, empty or negative. */
    private static long number(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return -1;
        long value = cursor.getLong(index);
        return value < 0 ? -1 : value;
    }

    /** For a file left in place that might be one the user picked to write over. */
    private static String leftAlone() {
        return L10n.t("File left alone. If it's empty, delete it in your files app.");
    }

    /**
     * Writes over the file, reads it back and says how that went. "wt" truncates a file being
     * replaced; an app that turns the mode down gets "w", and the read back catches old bytes left
     * past the new end. Success is said only for a file that reads back as written.
     */
    private static String save(ContentResolver resolver, Uri uri, byte[] bytes, int count, Run run) throws IOException {
        AssetFileDescriptor file;
        try {
            file = resolver.openAssetFileDescriptor(uri, "wt", run.cancel);
        } catch (IllegalArgumentException | UnsupportedOperationException | FileNotFoundException unsupported) {
            file = resolver.openAssetFileDescriptor(uri, "w", run.cancel);
        }
        try (AssetFileDescriptor held = run.hold(file); OutputStream stream = held.createOutputStream()) {
            stream.write(bytes);
        }
        byte[] back;
        try (AssetFileDescriptor held = run.hold(resolver.openAssetFileDescriptor(uri, "r", run.cancel));
             InputStream in = held.createInputStream()) {
            ByteArrayOutputStream read = new ByteArrayOutputStream(bytes.length);
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1 && read.size() <= bytes.length; ) read.write(buffer, 0, n);
            back = read.toByteArray();
        } catch (IOException | RuntimeException unreadable) {
            return L10n.t("Pin list saved, but the app holding it blocked the check.");
        }
        if (!Arrays.equals(bytes, back)) {
            return L10n.t("The saved list reads back wrong. Save it again as a new file.");
        }
        return L10n.quantity(count, "Saved a list of one pin.", "Saved a list of %d pins.", count);
    }
}
