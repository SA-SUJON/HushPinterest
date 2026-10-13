/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.HushDialogs;
import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/**
 * Download board: a row in a board's own menu that saves the pins Pinterest has already loaded for
 * that board.
 *
 * <p>The pins come from the lists {@code FeedFilter} already sees. A list whose pins nearly all
 * name one board is a page of that board, so its pins are kept here under that board's id, in
 * memory only, for a few boards and a bounded number of pins each. A kept pin is only its id, the
 * media resolved from it and its Save list entry (title and checked source link), never
 * Pinterest's model, so a full set of boards doesn't hold every
 * one of those models, and all they reach, in memory. Nothing asks Pinterest for more:
 * a board that wasn't scrolled to its end gives what loaded, and the result says so. Each pin goes
 * through {@link PinDownloads}, the path the grid selection uses, one batch at a time.
 */
public final class BoardDownloads {
    private BoardDownloads() {}

    /** How many boards keep their loaded pins. The least recently used one goes first. */
    static final int BOARDS = 4;

    /** How many loaded pins one board keeps, in the order Pinterest loaded them. */
    static final int PINS = 500;

    /** A list belongs to a board when at least this share of its pins, in tenths, names it. */
    private static final int SHARE_IN_TENTHS = 9;

    /** A kept pin: the media resolved from it, and its entry for Save list. */
    private static final class Kept {
        final PinMedia.Resolution media;
        final PinManifest.Entry entry;

        Kept(PinMedia.Resolution media, PinManifest.Entry entry) {
            this.media = media;
            this.entry = entry;
        }
    }

    private static final LinkedHashMap<String, LinkedHashMap<String, Kept>> LOADED =
            new LinkedHashMap<String, LinkedHashMap<String, Kept>>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LinkedHashMap<String, Kept>> eldest) {
                    return size() > BOARDS;
                }
            };

    /** Rewritten by the patch to read the rows of Pinterest's menu model. */
    private static List<?> menuItems(Object menu) { return null; }

    /** Rewritten to read the menu's row handler, a Kotlin Function1 taking the row's index. */
    private static Object menuHandler(Object menu) { return null; }

    /** Rewritten to build a menu with the same label, the given rows and the given handler. */
    private static Object menuCopy(Object menu, List<?> items, Object handler) { return null; }

    /** Rewritten to build a row like Pinterest's own: the title resource with [text] put in it, at [index]. */
    private static Object menuRow(int title, int index, String text) { return null; }

    /** Rewritten to answer Pinterest's own "%1$s" string resource, which the row's title fills in. */
    private static int titleResource() { return 0; }

    /** Rewritten to read the board id the board screen was opened with. */
    private static String boardId(Object screen) { return null; }

    /** Rewritten to post Pinterest's own event that closes an open menu sheet. */
    private static void dismissMenu(Object screen) {}

    /** The menu row: its own switch, and Download pins on too, since every pin goes through it. */
    static boolean active() {
        return PinDownloads.active() && PatchFamily.Capability.BOARD_MENU.installed() && Settings.DOWNLOAD_BOARD.get();
    }

    /**
     * Keeping loaded pins needs only this switch, so pins a board loaded before Download pins was
     * turned on are there once it is.
     */
    static boolean keeping() {
        return Utils.settingsReady() && PatchFamily.Capability.BOARD_PINS.installed() && Settings.DOWNLOAD_BOARD.get();
    }

    /**
     * Injected right after a board screen builds its menu. Hands back the same menu, or a copy with
     * a Download board row at the end whose index no row of Pinterest's uses. The copy's handler
     * takes that index itself and passes every other one to Pinterest's handler unchanged.
     */
    public static Object menu(Object menu, Object screen) {
        HookStatus.invoked(FamilyNames.DOWNLOAD_BOARD);
        if (menu == null || !active()) return menu;
        try {
            String board = boardId(screen);
            if (board == null || !board.matches("[0-9]{1,30}")) return menu;
            List<?> items = menuItems(menu);
            Object handler = menuHandler(menu);
            int title = title();
            if (items == null || items.isEmpty() || handler == null || title == 0) return menu;
            int index = items.size();
            Object row = menuRow(title, index, L10n.t("Download board"));
            WeakReference<Object> owner = new WeakReference<>(screen);
            Object routed = row == null ? null : route(handler, index, () -> chosen(owner.get(), board));
            if (routed == null) return menu;
            List<Object> rows = new ArrayList<>(items);
            rows.add(row);
            Object copy = menuCopy(menu, rows, routed);
            if (copy == null) return menu;
            HookStatus.counted(FamilyNames.DOWNLOAD_BOARD, "download row added to board menu");
            return copy;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_BOARD, "add board menu row", failure);
            return menu;
        }
    }

    /**
     * Called by {@code FeedFilter} with each list Pinterest builds, after its own filtering. Keeps
     * the pins of a list that's a page of one board. Turning the switch off, or pausing, forgets
     * everything kept. Never changes the list and never throws, so the feed is filtered the same
     * way with or without it. Returns whether it kept any.
     */
    public static boolean record(List<?> items) {
        try {
            if (!PatchFamily.Capability.BOARD_PINS.installed()) return false;
            HookStatus.invoked(FamilyNames.DOWNLOAD_BOARD);
            if (!keeping()) {
                forget();
                return false;
            }
            if (items == null || items.isEmpty()) return false;
            Map<String, Integer> counts = new HashMap<>();
            int pins = 0;
            for (Object item : items) {
                String board = PinMedia.id(item) == null ? null : boardOf(item);
                if (board == null) continue;
                pins++;
                Integer seen = counts.get(board);
                counts.put(board, seen == null ? 1 : seen + 1);
            }
            String board = null;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() * 10 >= pins * SHARE_IN_TENTHS) board = entry.getKey();
            }
            if (board == null) return false;
            boolean kept = false;
            synchronized (LOADED) {
                LinkedHashMap<String, Kept> loaded = LOADED.get(board);
                if (loaded == null) {
                    loaded = new LinkedHashMap<>();
                    LOADED.put(board, loaded);
                }
                for (Object item : items) {
                    String id = PinMedia.id(item);
                    if (id == null || !board.equals(boardOf(item))) continue;
                    if (loaded.containsKey(id) || loaded.size() < PINS) {
                        // PinDownloads takes the resolved media in place of the pin.
                        PinMedia.Resolution media = PinMedia.resolve(item);
                        PinManifest.Entry entry = PinManifest.entry(item);
                        if (media == null || entry == null) continue;
                        loaded.put(id, new Kept(media, entry));
                        kept = true;
                    }
                }
            }
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_BOARD, "record loaded board pins", failure);
            return false;
        }
    }

    /** The pins kept for [board], oldest first, as their resolved media. */
    static List<Object> loaded(String board) {
        List<Object> pins = new ArrayList<>();
        synchronized (LOADED) {
            LinkedHashMap<String, Kept> loaded = board == null ? null : LOADED.get(board);
            if (loaded != null) for (Kept pin : loaded.values()) pins.add(pin.media);
        }
        return pins;
    }

    /** Save list's entries for the pins kept for [board] that [filter] shows, oldest first. */
    static List<PinManifest.Entry> listed(String board, GridDownloads.Filter filter) {
        List<PinManifest.Entry> entries = new ArrayList<>();
        synchronized (LOADED) {
            LinkedHashMap<String, Kept> loaded = board == null ? null : LOADED.get(board);
            if (loaded != null) for (Kept pin : loaded.values()) {
                if (filter.shows(PinMedia.kind(pin.media))) entries.add(pin.entry);
            }
        }
        return entries;
    }

    static void forget() {
        synchronized (LOADED) {
            LOADED.clear();
        }
    }

    /** The id of the board a pin names, or null when it names none. */
    static String boardOf(Object pin) {
        Object id = PinMedia.field(PinMedia.field(pin, "board"), "id");
        return id instanceof String && ((String) id).matches("[0-9]{1,30}") ? (String) id : null;
    }

    /** The title resource, but only while it still reads "%1$s", so the row shows exactly its own text. */
    private static int title() {
        int id = titleResource();
        Context context = Utils.getContext();
        if (id == 0 || context == null) return 0;
        try {
            return "%1$s".equals(context.getString(id)) ? id : 0;
        } catch (RuntimeException missing) {
            return 0;
        }
    }

    /**
     * Pinterest's handler behind a Function1 of the same interface: [index] runs [chosen], any
     * other row goes to Pinterest's handler as it was. Null when the handler isn't a Function1.
     */
    static Object route(Object handler, int index, Runnable chosen) {
        Class<?> face = function1(handler.getClass());
        if (face == null) return null;
        Object unit = unit(face.getClassLoader());
        return Proxy.newProxyInstance(face.getClassLoader(), new Class<?>[]{face}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    default: return "Download board row handler";
                }
            }
            if (args != null && args.length == 1 && args[0] instanceof Integer && (Integer) args[0] == index) {
                chosen.run();
                return unit;
            }
            try {
                return method.invoke(handler, args);
            } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
            }
        });
    }

    /** Kotlin's Function1 among [type]'s interfaces and its ancestors', found by its kept name. */
    private static Class<?> function1(Class<?> type) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            for (Class<?> face : at.getInterfaces()) {
                if (face.getName().equals("kotlin.jvm.functions.Function1")) return face;
            }
        }
        return null;
    }

    /**
     * Kotlin's Unit, read from its one static field of its own type, whatever that field is called.
     * The name is built at run time on purpose: R8 keeps any class a Class.forName literal names,
     * and a literal here copied the stdlib's Unit into the extension, on top of Pinterest's own.
     */
    private static Object unit(ClassLoader loader) {
        try {
            Class<?> type = Class.forName(String.join(".", "kotlin", "Unit"), false, loader);
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                    field.setAccessible(true);
                    return field.get(null);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException absent) {
            // The caller drops what a row handler answers; null is what it gets then.
        }
        return null;
    }

    private static void chosen(Object screen, String board) {
        try {
            if (screen != null) dismissMenu(screen);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_BOARD, "close board menu", failure);
        }
        start(board);
    }

    /** Offers every pin kept for [board] that Download history doesn't already have, by kind, then queues the choice. */
    static boolean start(String board) {
        if (!active()) return false;
        try {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            if (GridDownloads.running()) {
                Utils.showToastLong(L10n.t("A pin selection is still being saved."));
                return false;
            }
            List<Object> pins = loaded(board);
            if (pins.isEmpty()) {
                Utils.showToastLong(L10n.t("This board's pins haven't loaded yet. Scroll it and try again."));
                return false;
            }
            Context app = activity.getApplicationContext();
            WeakReference<Activity> host = new WeakReference<>(activity);
            // Download history is read from disk, so it's read off the main thread.
            boolean scheduled = Utils.runOnBackgroundThread(() -> {
                Set<String> history = DownloadLedger.downloadedPinIds(app);
                Utils.runOnMainThread(() -> offer(host.get(), board, pins, history));
            });
            if (!scheduled) failed(new IllegalStateException("Worker unavailable"));
            return scheduled;
        } catch (Throwable failure) {
            failed(failure);
            return false;
        }
    }

    private static String note() {
        return L10n.f("Only the pins Pinterest has loaded for this board so far are included, up to %d. "
                + "To include more, scroll further down the board first.", PINS);
    }

    /** Moves the [pins] [filter] shows that Download history doesn't have into [fresh], and answers how many it has. */
    private static int split(List<Object> pins, Set<String> history, GridDownloads.Filter filter, List<Object> fresh) {
        int known = 0;
        for (Object pin : pins) {
            if (!filter.shows(PinMedia.kind(PinMedia.resolve(pin)))) continue;
            if (history.contains(PinMedia.id(pin))) known++;
            else fresh.add(pin);
        }
        return known;
    }

    /**
     * Asks whether to queue every new pin, or only the images or only the videos, with how many
     * of each can't be saved. All is chosen to begin with, which is what the board saved before
     * there was a choice. With nothing new, Queue pins starts off and Save list still works.
     */
    private static void offer(Activity activity, String board, List<Object> pins, Set<String> history) {
        try {
            if (!active() || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            List<Object> fresh = new ArrayList<>();
            int known = split(pins, history, GridDownloads.Filter.ALL, fresh);
            GridDownloads.Filter[] filters = GridDownloads.Filter.values();
            int[] counts = new int[filters.length];
            CharSequence[] names = new CharSequence[filters.length];
            for (GridDownloads.Filter filter : filters) {
                int unsupported = 0;
                for (Object pin : fresh) {
                    PinMedia.Resolution media = PinMedia.resolve(pin);
                    if (!filter.shows(PinMedia.kind(media))) continue;
                    counts[filter.ordinal()]++;
                    if (media == null || media.source == null) unsupported++;
                }
                names[filter.ordinal()] = filter.label(counts[filter.ordinal()]) + "\n" + L10n.f("Unsupported: %d", unsupported);
            }
            AlertDialog.Builder builder = HushDialogs.builder(activity);
            String heading = L10n.t("Download board");
            LinearLayout header = new LinearLayout(builder.getContext());
            header.setOrientation(LinearLayout.VERTICAL);
            int side = Math.round(24 * activity.getResources().getDisplayMetrics().density);
            header.setPaddingRelative(side, side * 3 / 4, side, 0);
            TextView title = new TextView(builder.getContext());
            title.setText(heading);
            title.setAccessibilityHeading(true);
            header.addView(title);
            TextView details = new TextView(builder.getContext());
            details.setId(android.R.id.message);
            details.setPaddingRelative(0, side / 3, 0, 0);
            String skipped = fresh.isEmpty() ? L10n.t("Every pin loaded from this board is already in Download history.")
                    : known == 0 ? null : L10n.f("Skipped because they're already in Download history: %d", known);
            details.setText((skipped == null ? "" : skipped + "\n\n") + note());
            header.addView(details);
            ScrollView guidance = new ScrollView(builder.getContext()) {
                @Override protected void onMeasure(int width, int height) {
                    // AlertDialog measures a custom title before it reserves room for its list.
                    // Bound the whole header so large text still leaves choices and actions visible.
                    int limit = getResources().getDisplayMetrics().heightPixels / 3;
                    if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED) {
                        limit = Math.min(limit, MeasureSpec.getSize(height));
                    }
                    super.onMeasure(width, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
                }
            };
            guidance.setScrollbarFadingEnabled(false);
            guidance.addView(header);
            int[] chosen = {GridDownloads.Filter.ALL.ordinal()};
            AlertDialog picker = builder
                    // Keep the explanation above the native list so it can't take the list's space twice.
                    .setCustomTitle(guidance)
                    .setSingleChoiceItems(names, chosen[0], (dialog, which) -> {
                        chosen[0] = which;
                        ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(counts[which] > 0);
                        ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_NEUTRAL)
                                .setEnabled(!listed(board, filters[which]).isEmpty());
                    })
                    .setPositiveButton(L10n.t("Queue pins"), (dialog, which) -> confirm(activity, board, filters[chosen[0]]))
                    // The list has every loaded pin of the kind, Download history's too: it's a list, not a download.
                    .setNeutralButton(L10n.t("Save list"),
                            (dialog, which) -> PinManifest.export(activity, listed(board, filters[chosen[0]]), true))
                    .setNegativeButton(L10n.t("Cancel"), null).create();
            HushDialogs.prepareChoices(picker);
            picker.setTitle(heading);
            HushDialogs.paintControls(header);
            HushDialogs.paintTitle(title);
            picker.show();
            picker.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(counts[chosen[0]] > 0);
            HushDialogs.paint(picker);
        } catch (Throwable failure) {
            failed(failure);
        }
    }

    /** The board's kept pins and Download history are read again, since either can change while the choice is open. */
    private static void confirm(Activity activity, String board, GridDownloads.Filter filter) {
        Context app = activity.getApplicationContext();
        WeakReference<Activity> host = new WeakReference<>(activity);
        boolean scheduled = Utils.runOnBackgroundThread(() -> {
            Set<String> history = DownloadLedger.downloadedPinIds(app);
            List<Object> pins = loaded(board);
            Utils.runOnMainThread(() -> queue(host.get(), pins, history, filter));
        });
        if (!scheduled) failed(new IllegalStateException("Worker unavailable"));
    }

    private static void queue(Activity activity, List<Object> pins, Set<String> history, GridDownloads.Filter filter) {
        try {
            if (!active()) {
                Utils.showToastLong(L10n.t("Resume HushPinterest, turn on Download pins and Download board."));
                return;
            }
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            if (pins.isEmpty()) {
                Utils.showToastLong(L10n.t("This board's pins haven't loaded yet. Scroll it and try again."));
                return;
            }
            List<Object> fresh = new ArrayList<>();
            int known = split(pins, history, filter, fresh);
            if (fresh.isEmpty()) {
                Utils.showToastLong(L10n.t("Download history already has each pin chosen from this board."));
                return;
            }
            if (GridDownloads.queue(activity, fresh, L10n.t("Download board"), note(), known, false)) {
                HookStatus.counted(FamilyNames.DOWNLOAD_BOARD, "board download started");
            } else {
                Utils.showToastLong(L10n.t("A pin selection is still being saved."));
            }
        } catch (Throwable failure) {
            failed(failure);
        }
    }

    private static void failed(Throwable failure) {
        HookStatus.threw(FamilyNames.DOWNLOAD_BOARD, "start board download", failure);
        Utils.showToastLong(L10n.t("Couldn't start the board download. Try again from its menu."));
    }
}
