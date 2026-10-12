/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;
import app.hushpinterest.extension.shared.settings.preference.LogBufferManager;

/**
 * Save list: the pins a batch chooser holds, written as a JSON list to a file picked through
 * Android's file picker. Each pin is its id, its pin address, its title when it has one, and the
 * source link Pinterest supplied in its {@code link} field when that link can be kept exactly as
 * given. Nothing is fetched to fill it in, and it holds no account, cookie or media address. It's
 * separate from the settings file and the diagnostic report.
 */
public final class PinManifest {
    private PinManifest() {}

    static final String MIME_TYPE = "application/json";
    static final int REQUEST = 0x4850;
    static final String FORMAT = "hushpinterest-pin-list";
    private static final String TAG = "hushpinterest-pin-list";
    private static final String TOKEN = "token";

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
        // A path parameter such as ;jsessionid= carries a session.
        if (path != null && path.contains(";")) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (host.startsWith("[") || !host.contains(".") || NUMERIC_HOST.matcher(host).matches()
                || LOCAL_HOST.matcher(host).matches()) return null;
        // Pinterest's media addresses are files, not sources, and they can stop working.
        if (PinMedia.mediaUri("https://" + host + "/") != null) return null;
        return link;
    }

    /** The list as JSON, made at [now] from [entries]. [board] says whether they're a board's loaded pins. */
    static String json(List<Entry> entries, boolean board, long now) {
        StringBuilder out = new StringBuilder("{\n");
        out.append("  \"format\": ").append(quote(FORMAT)).append(",\n");
        out.append("  \"version\": 1,\n");
        out.append("  \"created\": ").append(quote(Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.SECONDS).toString())).append(",\n");
        out.append("  \"scope\": ").append(quote(board ? "board_loaded_pins" : "chosen_visible_pins")).append(",\n");
        out.append("  \"complete\": false,\n");
        out.append("  \"note\": ").append(quote(board
                ? L10n.f("This list has only the pins Pinterest had loaded for the board when it was saved, up to %d, "
                        + "of the kind chosen. Pins further down the board aren't in it.", BoardDownloads.PINS)
                : L10n.t("This list has only the pins chosen from those on screen when it was saved."))).append(",\n");
        out.append("  \"links_note\": ").append(quote(L10n.t("A source link is \"missing\" when the pin had none, and "
                + "\"omitted\" when it couldn't be kept exactly as given, such as a link with tracking details "
                + "or one to a private network."))).append(",\n");
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

    /**
     * Makes the list from [entries] now and opens Android's picker to save it. The answer comes to a
     * fragment of [activity]'s own, so Pinterest's screen needs no change. Returns whether the
     * picker opened.
     */
    static boolean export(Activity activity, List<Entry> entries, boolean board) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || entries.isEmpty()) return false;
        Picker picker = null;
        try {
            byte[] bytes = json(entries, board, System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
            String token = UUID.randomUUID().toString();
            PENDING.set(new Pending(token, bytes, entries.size()));
            FragmentManager fragments = activity.getFragmentManager();
            Fragment old = fragments.findFragmentByTag(TAG);
            picker = new Picker();
            Bundle arguments = new Bundle();
            arguments.putString(TOKEN, token);
            picker.setArguments(arguments);
            android.app.FragmentTransaction change = fragments.beginTransaction();
            if (old != null) change.remove(old);
            change.add(picker, TAG).commitNow();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(MIME_TYPE).putExtra(Intent.EXTRA_TITLE, "hushpinterest-pins-" + LogBufferManager.fileTimestamp() + ".json");
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
            Pending pending = PENDING.get();
            if (pending != null && pending.token.equals(token)) PENDING.compareAndSet(pending, null);
            else pending = null;
            Activity host = getActivity();
            Context app = host != null ? host.getApplicationContext() : Utils.getContext();
            leave();
            Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (uri == null) return; // Cancelled: nothing written and nothing to say.
            if (pending == null || app == null) {
                discardThenSay(app, uri, L10n.t("HushPinterest closed with the picker open. Pick and save again."));
                return;
            }
            if (!PinDownloads.active()) {
                discardThenSay(app, uri, L10n.t("Resume HushPinterest and turn on Download pins to use this."));
                return;
            }
            Pending list = pending;
            boolean started = Utils.runOnBackgroundThread(() -> {
                ContentResolver resolver = app.getContentResolver();
                // Read before writing: a failed write can leave a file picked to write over empty.
                long before = size(resolver, uri);
                try {
                    Utils.showToastLong(save(resolver, uri, list.bytes, list.count));
                } catch (IOException | RuntimeException failure) {
                    HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "save the pin list", failure);
                    discard(resolver, uri, L10n.t("Couldn't save the pin list. Try again."), before);
                }
            });
            if (!started) discardThenSay(app, uri, L10n.t("Couldn't save the pin list. Try again."));
        }
    }

    /**
     * The picker made the file before the list had a chance to fill it, so a list that isn't written
     * takes its file away again, off the main thread when a worker is free, and says [said].
     */
    private static void discardThenSay(Context app, Uri uri, String said) {
        if (app == null) {
            Utils.showToastLong(said);
            Utils.showToastLong(leftAlone());
            return;
        }
        ContentResolver resolver = app.getContentResolver();
        Runnable work = () -> discard(resolver, uri, said, size(resolver, uri));
        if (!Utils.runOnBackgroundThread(work)) work.run();
    }

    /**
     * Says [said], then a second toast when the picked file stays behind, since Android 12 and later
     * cut a toast at two lines. Only a file that was empty before anything was written, [size]
     * bytes, is taken away: one that held something was picked to be written over and is the
     * user's own.
     */
    private static void discard(ContentResolver resolver, Uri uri, String said, long size) {
        Utils.showToastLong(said);
        if (size > 0) return;
        if (size < 0) {
            Utils.showToastLong(leftAlone());
            return;
        }
        boolean gone;
        try {
            gone = DocumentsContract.deleteDocument(resolver, uri);
        } catch (FileNotFoundException | RuntimeException refused) {
            gone = false;
        }
        if (!gone) Utils.showToastLong(L10n.t("Couldn't remove the empty file. Delete it in your files app."));
    }

    /** How many bytes the picked file holds, or -1 when the app holding it won't say. */
    private static long size(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (RuntimeException unanswered) {
            // Treated as unknown, so the file stays.
        }
        return -1;
    }

    /** For a file whose size couldn't be read, which might be one the user picked to write over. */
    private static String leftAlone() {
        return L10n.t("File left alone. If it's empty, delete it in your files app.");
    }

    /**
     * Writes over the file, reads it back and says how that went. "wt" truncates a file being
     * replaced; an app that turns the mode down gets "w", and the read back catches old bytes left
     * past the new end. Success is said only for a file that reads back as written.
     */
    static String save(ContentResolver resolver, Uri uri, byte[] bytes, int count) throws IOException {
        OutputStream out;
        try {
            out = resolver.openOutputStream(uri, "wt");
        } catch (IllegalArgumentException | UnsupportedOperationException | FileNotFoundException unsupported) {
            out = resolver.openOutputStream(uri, "w");
        }
        if (out == null) throw new FileNotFoundException("No stream for the pin list");
        try (OutputStream stream = out) {
            stream.write(bytes);
        }
        byte[] back;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new FileNotFoundException("No stream to read the pin list back");
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
