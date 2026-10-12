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
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.HushDialogs;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/** One explicit, bounded selection from currently visible native cells. No adapter or network discovery. */
final class GridDownloads {
    private GridDownloads() {}
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    static ViewGroup grid(View origin) {
        if (origin == null || !origin.isAttachedToWindow()) return null;
        View current = origin;
        for (int depth = 0; current != null && depth < 64; depth++) {
            for (Class<?> type = current.getClass(); type != null; type = type.getSuperclass()) {
                if (type.getName().equals("androidx.recyclerview.widget.RecyclerView") && current instanceof ViewGroup)
                    return (ViewGroup) current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /** Native overflow data can omit originView. Match one visible grid by its already bound pin. */
    static ViewGroup grid(View origin, Object pin) {
        ViewGroup direct = grid(origin);
        String id = PinMedia.id(pin);
        if (id != null && direct != null) {
            for (Object visible : snapshot(direct)) if (id.equals(PinMedia.id(visible))) return direct;
            return null;
        }
        Activity activity = Utils.getActivity();
        if (id == null || activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(activity.getWindow().getDecorView());
        ViewGroup match = null;
        int examined = 0;
        while (!pending.isEmpty()) {
            if (++examined > 512) return null;
            View view = pending.removeFirst();
            if (!view.isAttachedToWindow() || !view.isShown()) continue;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                if (grid(group) == group) {
                    for (Object visible : snapshot(group)) if (id.equals(PinMedia.id(visible))) {
                        if (match != null && match != group) return null;
                        match = group;
                        break;
                    }
                }
                if (pending.size() + group.getChildCount() + examined > 512) return null;
                for (int i = 0; i < group.getChildCount(); i++) pending.addLast(group.getChildAt(i));
            }
        }
        return match;
    }

    static List<Object> snapshot(ViewGroup grid) {
        return snapshot(grid, null);
    }

    /** The visible pins, and into [cells] when it's given, the cell each one was read from. */
    static List<Object> snapshot(ViewGroup grid, Map<String, View> cells) {
        LinkedHashMap<String, Object> pins = new LinkedHashMap<>();
        Rect bounds = new Rect(), visible = new Rect();
        if (grid == null || !grid.isAttachedToWindow() || !grid.isShown() || !grid.getGlobalVisibleRect(bounds)) return new ArrayList<>();
        for (int i = 0; i < grid.getChildCount() && pins.size() < DownloadLedger.LIMIT; i++) {
            View cell = grid.getChildAt(i);
            if (!cell.isAttachedToWindow() || !cell.isShown() || !cell.getGlobalVisibleRect(visible) || !visible.intersect(bounds)) continue;
            Object pin = PinDownloads.cellPin(cell);
            String id = PinMedia.id(pin);
            if (id != null && pins.putIfAbsent(id, pin) == null && cells != null) cells.put(id, cell);
        }
        return new ArrayList<>(pins.values());
    }

    static boolean show(View origin) {
        if (!PinDownloads.active()) return false;
        try {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            if (RUNNING.get()) {
                Utils.showToastLong(L10n.t("A pin selection is still being saved."));
                return false;
            }
            Map<String, View> cells = new HashMap<>();
            List<Object> pins = snapshot(grid(origin), cells);
            if (pins.isEmpty()) {
                Utils.showToastLong(L10n.t("No visible pins. Open a pin menu in the grid and try again."));
                return false;
            }
            int box = dp(activity, THUMBNAIL_DP);
            PinChoices choices = new PinChoices();
            for (Object pin : pins) choices.add(pin, thumbnail(cells.get(PinMedia.id(pin)), box));
            choices.show(Filter.ALL);
            AlertDialog.Builder builder = HushDialogs.builder(activity);
            Context themed = builder.getContext();
            String heading = L10n.f("Select visible pins (up to %d)", DownloadLedger.LIMIT);
            // The filters and the count go above the list as the dialog's title. A list and a view of
            // its own both in the middle make AlertDialogLayout measure them one after the other, so
            // a long list pushed them and the buttons off the bottom. Up top they always show.
            LinearLayout controls = new LinearLayout(themed);
            controls.setOrientation(LinearLayout.VERTICAL);
            controls.setPaddingRelative(dp(activity, 24), dp(activity, 18), dp(activity, 24), 0);
            TextView title = new TextView(themed);
            title.setText(heading);
            title.setAccessibilityHeading(true);
            controls.addView(title);
            RadioGroup filters = new RadioGroup(themed);
            filters.setOrientation(LinearLayout.HORIZONTAL);
            for (Filter filter : Filter.values()) {
                RadioButton option = new RadioButton(themed);
                option.setId(View.generateViewId());
                option.setTag(filter);
                int count = choices.count(filter);
                option.setText(filter.label(count));
                option.setEnabled(filter == Filter.ALL || count > 0);
                filters.addView(option);
                if (filter == Filter.ALL) filters.check(option.getId());
            }
            // A row of filters scrolls rather than cutting off at large text sizes.
            HorizontalScrollView row = new HorizontalScrollView(themed);
            row.addView(filters);
            controls.addView(row);
            TextView count = new TextView(themed);
            count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            controls.addView(count);
            AlertDialog picker = builder
                    .setCustomTitle(controls)
                    .setAdapter(choices, null)
                    .setPositiveButton(L10n.t("Queue selected"), (dialog, which) -> {
                        List<Object> chosen = choices.chosen();
                        if (!chosen.isEmpty()) queue(activity, chosen, L10n.t("Download visible pins"), null, 0, true);
                    })
                    .setNeutralButton(L10n.t("Save list"),
                            (dialog, which) -> PinManifest.export(activity, PinManifest.entries(choices.chosen()), false))
                    .setNegativeButton(L10n.t("Cancel"), null).create();
            ListView list = picker.getListView();
            list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
            Runnable counted = () -> {
                count.setText(L10n.f("Selected: %1$d (unsupported: %2$d)", choices.selected(false), choices.selected(true)));
                for (int action : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL}) {
                    Button button = picker.getButton(action);
                    if (button != null) button.setEnabled(choices.selected(false) > 0);
                }
            };
            list.setOnItemClickListener((parent, view, position, id) -> {
                choices.select(position, list.isItemChecked(position));
                counted.run();
            });
            filters.setOnCheckedChangeListener((group, checked) -> {
                View option = group.findViewById(checked);
                if (option == null || !(option.getTag() instanceof Filter)) return;
                choices.show((Filter) option.getTag());
                // The rows changed, so their checks are set again from each pin's own choice.
                list.clearChoices();
                for (int position = 0; position < choices.getCount(); position++) {
                    list.setItemChecked(position, choices.selected(position));
                }
                counted.run();
            });
            counted.run();
            HushDialogs.prepareChoices(picker);
            // The window's title, which TalkBack reads; the shown one is the custom title.
            picker.setTitle(heading);
            HushDialogs.paintControls(controls);
            HushDialogs.paintTitle(title);
            picker.show();
            HushDialogs.paint(picker);
            picker.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            picker.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "select visible pins", failure);
            Utils.showToastLong(L10n.t("Couldn't open the pin selection. Try again from the grid."));
            return false;
        }
    }

    /** How big a row's picture is at most, on its longer side. */
    private static final int THUMBNAIL_DP = 48;

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** Which pins a batch chooser shows: all of them, or only those Pinterest supplied as images or as videos. */
    enum Filter {
        ALL, IMAGES, VIDEOS;

        boolean shows(PinMedia.Kind kind) {
            return this == ALL || kind == (this == IMAGES ? PinMedia.Kind.IMAGE : PinMedia.Kind.VIDEO);
        }

        String label(int count) {
            switch (this) {
                case IMAGES: return L10n.f("Images (%d)", count);
                case VIDEOS: return L10n.f("Videos (%d)", count);
                default: return L10n.f("All (%d)", count);
            }
        }
    }

    /**
     * A small copy of the largest picture an attached cell already shows, or null when it shows
     * none. Nothing is fetched. Pinterest's grid cells paint their picture themselves rather than
     * through an ImageView, so without one the cell's largest view is drawn into the copy. The
     * copy is the chooser's own, so the grid can reuse or release its bitmap while the chooser is open.
     */
    static Bitmap thumbnail(View cell, int box) {
        if (cell == null || box <= 0) return null;
        Drawable shown = null;
        View painter = null;
        int largest = 0, largestPainter = 0, examined = 0;
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(cell);
        while (!pending.isEmpty() && ++examined <= 64) {
            View view = pending.removeFirst();
            if (!view.isShown()) continue;
            Drawable picture = view instanceof ImageView ? ((ImageView) view).getDrawable() : null;
            int area = view.getWidth() * view.getHeight();
            if (picture != null && picture.getIntrinsicWidth() > 0 && picture.getIntrinsicHeight() > 0 && area > largest) {
                shown = picture;
                largest = area;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.addLast(group.getChildAt(i));
            } else if (area > largestPainter) {
                painter = view;
                largestPainter = area;
            }
        }
        if (shown == null) return painter == null ? null : drawn(painter, box);
        try {
            if (shown instanceof BitmapDrawable) {
                Bitmap source = ((BitmapDrawable) shown).getBitmap();
                if (source == null || source.isRecycled()) return null;
                float scale = Math.min(1f, (float) box / Math.max(source.getWidth(), source.getHeight()));
                // Scaling copies a hardware bitmap too, which a software canvas can't draw. At its
                // own size Android hands back the same bitmap, so that one is copied.
                Bitmap small = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)),
                        Math.max(1, Math.round(source.getHeight() * scale)), true);
                return small == source ? source.copy(source.getConfig(), false) : small;
            }
            Drawable.ConstantState state = shown.getConstantState();
            if (state == null) return null;
            float scale = Math.min(1f, (float) box / Math.max(shown.getIntrinsicWidth(), shown.getIntrinsicHeight()));
            int width = Math.max(1, Math.round(shown.getIntrinsicWidth() * scale));
            int height = Math.max(1, Math.round(shown.getIntrinsicHeight() * scale));
            Drawable copy = state.newDrawable(cell.getResources()).mutate();
            Bitmap small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            copy.setBounds(0, 0, width, height);
            copy.draw(new Canvas(small));
            return small;
        } catch (RuntimeException unreadable) {
            // A released bitmap, or one only the GPU can draw: the row keeps its text.
            return null;
        }
    }

    /**
     * The view drawn small, or null when it draws nothing. It's recorded first, so a picture only
     * the GPU can draw comes along too: Android renders such a recording on the GPU and reads it back.
     */
    private static Bitmap drawn(View view, int box) {
        int width = view.getWidth(), height = view.getHeight();
        if (width <= 0 || height <= 0) return null;
        try {
            Picture recorded = new Picture();
            view.draw(recorded.beginRecording(width, height));
            recorded.endRecording();
            float scale = Math.min(1f, (float) box / Math.max(width, height));
            Bitmap small = Bitmap.createBitmap(recorded, Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
            int[] pixels = new int[small.getWidth() * small.getHeight()];
            small.getPixels(pixels, 0, small.getWidth(), 0, 0, small.getWidth(), small.getHeight());
            for (int pixel : pixels) if (Color.alpha(pixel) != 0) return small;
            return null;
        } catch (RuntimeException undrawable) {
            return null;
        }
    }

    /** A row's text: the pin's title when it has one, its id, and what kind of media it is. */
    private static CharSequence label(Object pin, String id, PinMedia.Resolution media) {
        Object title = PinMedia.field(pin, "title");
        String text = title instanceof String ? ((String) title).replaceAll("[\\p{Cntrl}]", " ").trim() : "";
        if (text.length() > 120) text = text.substring(0, 120);
        boolean video = PinMedia.kind(media) == PinMedia.Kind.VIDEO;
        String kind = media != null && media.source != null ? (video ? L10n.t("Video") : L10n.t("Image"))
                : (video ? L10n.t("Unsupported video") : L10n.t("Unsupported image"));
        return (text.isEmpty() ? "" : L10n.isolate(text) + "\n") + L10n.f("Pin %s", L10n.isolate(id)) + "\n" + kind;
    }

    /**
     * The visible pins as checkable rows, filtered by kind. A row's id is its pin's place in the
     * snapshot, so a choice stays with its pin while the filter changes which rows show.
     */
    static final class PinChoices extends BaseAdapter {
        private static final class Choice {
            final int index;
            final Object pin;
            final String id;
            final PinMedia.Kind kind;
            final boolean supported;
            final CharSequence label;
            final Bitmap thumbnail;
            boolean selected;

            Choice(int index, Object pin, Bitmap thumbnail) {
                this.index = index;
                this.pin = pin;
                id = PinMedia.id(pin);
                PinMedia.Resolution media = PinMedia.resolve(pin);
                kind = PinMedia.kind(media);
                supported = media != null && media.source != null;
                label = label(pin, id, media);
                this.thumbnail = thumbnail;
            }
        }

        private final List<Choice> all = new ArrayList<>();
        private final List<Choice> shown = new ArrayList<>();

        void add(Object pin, Bitmap thumbnail) {
            all.add(new Choice(all.size(), pin, thumbnail));
        }

        void show(Filter filter) {
            shown.clear();
            for (Choice choice : all) if (filter.shows(choice.kind)) shown.add(choice);
            notifyDataSetChanged();
        }

        int count(Filter filter) {
            int count = 0;
            for (Choice choice : all) if (filter.shows(choice.kind)) count++;
            return count;
        }

        /** How many pins are chosen, whatever the filter shows, or only those that can't be saved. */
        int selected(boolean unsupported) {
            int count = 0;
            for (Choice choice : all) if (choice.selected && !(unsupported && choice.supported)) count++;
            return count;
        }

        boolean selected(int position) {
            return shown.get(position).selected;
        }

        void select(int position, boolean selected) {
            shown.get(position).selected = selected;
        }

        /** The chosen pins in grid order, each still the pin its row named. */
        List<Object> chosen() {
            List<Object> pins = new ArrayList<>();
            for (Choice choice : all) if (choice.selected && choice.id.equals(PinMedia.id(choice.pin))) pins.add(choice.pin);
            return pins;
        }

        @Override public int getCount() { return shown.size(); }
        @Override public CharSequence getItem(int position) { return shown.get(position).label; }
        @Override public long getItemId(int position) { return shown.get(position).index; }
        @Override public boolean hasStableIds() { return true; }

        /** Android's own checkbox row with the checkbox at its start and the pin's picture, if any, at its end. */
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Choice choice = shown.get(position);
            CheckedTextView row;
            if (recycled instanceof CheckedTextView) {
                row = (CheckedTextView) recycled;
            } else {
                row = new CheckedTextView(parent.getContext());
                row.setId(android.R.id.text1);
                row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                TypedArray theme = parent.getContext().obtainStyledAttributes(new int[]{android.R.attr.listChoiceIndicatorMultiple});
                try {
                    Drawable box = theme.getDrawable(0);
                    if (box != null) box.setBounds(0, 0, box.getIntrinsicWidth(), box.getIntrinsicHeight());
                    row.setCompoundDrawablesRelative(box, null, null, null);
                } finally {
                    theme.recycle();
                }
            }
            row.setText(choice.label);
            Drawable picture = null;
            if (choice.thumbnail != null) {
                picture = new BitmapDrawable(row.getResources(), choice.thumbnail);
                picture.setBounds(0, 0, choice.thumbnail.getWidth(), choice.thumbnail.getHeight());
            }
            row.setCompoundDrawablesRelative(row.getCompoundDrawablesRelative()[0], null, picture, null);
            return row;
        }
    }

    /** True while a batch, from the grid or a board, is still working through its pins. */
    static boolean running() {
        return RUNNING.get();
    }

    /**
     * Starts one batch through {@link PinDownloads} unless another one is running. [known] pins were
     * left out before the batch because Download history already has them: they count as skipped
     * and the summary says so. [note] is shown under the summary, or nothing when it's null. A batch
     * that doesn't [recordStops] leaves pins it never started out of Download history, so a long
     * board stopped early doesn't push real downloads out of the history's 32 entries.
     */
    static boolean queue(Activity activity, List<Object> pins, String title, String note, int known, boolean recordStops) {
        if (activity == null || pins.isEmpty() || !RUNNING.compareAndSet(false, true)) return false;
        new Batch(activity, pins, title, note, known, recordStops).begin();
        return true;
    }

    private static final class Batch {
        final WeakReference<Activity> activity;
        final Context app;
        final List<Object> pins;
        final String title, note;
        final int known;
        final boolean recordStops;
        AlertDialog progress;
        boolean stopped;
        int position, queued, skipped, unsupported, failed, untracked;

        Batch(Activity activity, List<Object> pins, String title, String note, int known, boolean recordStops) {
            this.activity = new WeakReference<>(activity);
            app = activity.getApplicationContext();
            this.pins = pins;
            this.title = title;
            this.note = note;
            this.known = known;
            this.recordStops = recordStops;
            skipped = known;
        }

        void begin() {
            try {
                progress = HushDialogs.builder(activity.get())
                        .setTitle(title)
                        .setMessage(summary())
                        .setNegativeButton(L10n.t("Stop selection"), (dialog, which) -> stopped = true).create();
                // Back still stops the batch. A stray tap beside the dialog shouldn't end a queue of pins.
                progress.setCanceledOnTouchOutside(false);
                progress.setOnCancelListener(dialog -> stopped = true);
                progress.show();
                HushDialogs.paint(progress);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "show batch progress", failure);
                stopped = true;
            }
            next();
        }

        String summary() {
            return counts() + details()
                    + "\n\n" + L10n.t("Stopping keeps downloads already started. Unstarted selections end when Pinterest closes.")
                    + (untracked == 0 ? "" : "\n\n" + untrackedNote());
        }

        /** What the batch says beside its counts: pins it skipped up front, and its [note]. */
        private String details() {
            return (known == 0 ? "" : "\n\n" + L10n.f("Skipped because they're already in Download history: %d", known))
                    + (note == null ? "" : "\n\n" + note);
        }

        private String counts() {
            return L10n.f("Queued: %d\nSkipped: %d\nUnsupported: %d\nFailed: %d", queued, skipped, unsupported, failed);
        }

        private String untrackedNote() {
            String folder = DownloadFolder.current();
            return folder != null
                    ? L10n.quantity(untracked, "Couldn't save history for one result. Check %2$s.",
                            "Couldn't save history for %1$d results. Check %2$s.", untracked, folder)
                    : L10n.quantity(untracked, "Couldn't save history for one result. Check Downloads.",
                            "Couldn't save history for %1$d results. Check Downloads.", untracked);
        }

        /** Goes on to the next pin. A failure ends the batch, so the next selection isn't refused as still running. */
        void next() {
            try {
                step();
            } catch (Throwable failure) {
                end("continue batch", failure);
            }
        }

        /** Ends the batch after a failure: clears the running flag and closes the progress dialog. */
        private void end(String what, Throwable failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, what, failure);
            RUNNING.set(false);
            AlertDialog shown = progress;
            progress = null;
            try {
                if (shown != null) shown.dismiss();
            } catch (RuntimeException gone) {
                // Its window already went with the screen.
            }
        }

        private void step() {
            if (progress != null && progress.isShowing()) progress.setMessage(summary());
            Activity host = activity.get();
            if ((host == null || host.isFinishing() || host.isDestroyed()) && progress != null) {
                progress.dismiss();
                progress = null;
            }
            if (position == pins.size()) {
                RUNNING.set(false);
                if (progress != null && progress.isShowing()) {
                    // The dialog keeps the result until it's closed, so nothing repeats it.
                    progress.setMessage(summary());
                    progress.getButton(AlertDialog.BUTTON_NEGATIVE).setText(L10n.t("Close"));
                } else {
                    // With the dialog gone, toasts are all that say how the batch went. Android 12 and
                    // later cut a toast at two lines, so the counts are one short sentence and each
                    // note gets its own toast.
                    String said = L10n.f("%1$s: queued %2$d, skipped %3$d, unsupported %4$d, failed %5$d.",
                            title, queued, skipped, unsupported, failed);
                    if (skipped + unsupported + failed == 0) Utils.showToastShort(said);
                    else Utils.showToastLong(said);
                    if (known > 0) Utils.showToastLong(L10n.f("Skipped because they're already in Download history: %d", known));
                    if (untracked > 0) Utils.showToastLong(untrackedNote());
                }
                return;
            }
            Object pin = pins.get(position);
            String id = PinMedia.id(pin);
            if (stopped || host == null || host.isFinishing() || host.isDestroyed()) {
                result(id, PinDownloads.Result.SKIPPED, recordStops);
            } else PinDownloads.start(pin, host, outcome -> result(id, outcome, true));
        }

        void result(String id, PinDownloads.Result outcome, boolean record) {
            try {
                switch (outcome) {
                    case QUEUED: queued++; break;
                    case QUEUED_UNTRACKED: queued++; untracked++; break;
                    case SKIPPED: skipped++; break;
                    case UNSUPPORTED: unsupported++; break;
                    default: failed++;
                }
                pins.set(position++, null);
                // next() refreshes the summary inside its own guard.
                Runnable advance = this::next;
                if (!record || outcome == PinDownloads.Result.QUEUED || outcome == PinDownloads.Result.QUEUED_UNTRACKED) {
                    Utils.runOnMainThread(advance);
                } else {
                    boolean scheduled = Utils.runOnBackgroundThread(() -> {
                        boolean recorded = DownloadLedger.recordResult(app, id, DownloadLedger.State.valueOf(outcome.name()));
                        Utils.runOnMainThread(() -> { if (!recorded) untracked++; advance.run(); });
                    });
                    if (!scheduled) {
                        untracked++;
                        Utils.runOnMainThread(advance);
                    }
                }
            } catch (Throwable failure) {
                end("record batch result", failure);
            }
        }
    }
}
