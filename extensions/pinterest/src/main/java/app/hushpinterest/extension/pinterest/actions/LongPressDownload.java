/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.Logger;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/**
 * A Download button in the circular menu a long-pressed pin opens.
 *
 * <p>Pinterest gives the menu its buttons first and shows it second, and only the show call says
 * what was long-pressed. So the hook runs at the head of the show call. For anything but a pin, or
 * with a switch off, it returns without touching the menu. For a pin it takes the buttons the menu
 * just laid out, adds Pinterest's own Download button after them and has the menu lay them out
 * again, so the arc, the entry animation and release-to-select all come from Pinterest.
 *
 * <p>Pinterest's buttons are taken out and put back for that, and a button taken out marks its
 * springs detached for good, so the hook clears that mark on each one it put back.
 *
 * <p>The menu's touch handling calls performClick() on the button under the finger before it
 * closes, so a plain click listener is enough. The pin is held weakly, and a click only downloads
 * while the menu still names that pin: Pinterest clears the name when the menu closes.
 *
 * <p>The same relayout leaves out the Pinterest buttons whose hide switch is on: Save, Send and
 * Search image, told apart by the id Pinterest's builder gives each, never by their localized
 * labels. A pin whose menu would be left with no button at all keeps Pinterest's whole menu.
 */
public final class LongPressDownload {
    private LongPressDownload() {}

    static final String ITEM_TAG = "hushpinterest_long_press_download";

    /** What {@link #buttonKind} answers: one of Pinterest's buttons the switches can hide, or anything else. */
    static final int OTHER = 0, SAVE = 1, SEND = 2, SEARCH = 3;

    /** Rewritten to return the long-pressed model the show event carries when it's a pin, else null. */
    private static Object eventPin(Object event) { return null; }

    /** Rewritten to read the id the menu keeps for the model it's showing, cleared when it closes. */
    private static String menuModel(Object menu) { return null; }

    /** Rewritten to read a model's id through the getter Pinterest fills the menu's id from. */
    private static String modelId(Object model) { return null; }

    /** Rewritten to return the menu's laid-out buttons: its own origin marker first, then each button in order. */
    private static ArrayList<Object> menuItems(Object menu) { return null; }

    /** Rewritten to hand the menu a new button list through its own layout method. */
    private static void layoutItems(Object menu, List<Object> buttons) {}

    /**
     * Rewritten to clear a menu button's detached mark and the spring driver its detach cancelled.
     * Taking a button out of the menu runs its detach handler, which marks it detached for good,
     * and a marked button never starts its springs again, so it would stay at the zero size it
     * starts at.
     */
    private static void springsReattached(Object button) {}

    /**
     * Rewritten to build Pinterest's own menu button with its Download icon and "Download" label,
     * styled the way Pinterest styles the rest.
     */
    private static View downloadItem(Context context) { return null; }

    /**
     * Rewritten to read the id Pinterest's builder gave a menu button, the one the menu's hover test
     * compares, and answer {@link #SAVE}, {@link #SEND} or {@link #SEARCH} for those three buttons.
     * Everything else is {@link #OTHER}: Download, and the buttons Pinterest builds without an id.
     */
    private static int buttonKind(Object button) { return OTHER; }

    static boolean active() {
        try {
            return Utils.settingsReady() && PatchFamily.Capability.LONG_PRESS_MENU.installed()
                    && Settings.LONG_PRESS_DOWNLOAD.get() && PinDownloads.active();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LONG_PRESS_DOWNLOAD, "switch read", failure);
            return false;
        }
    }

    /** Whether any of Pinterest's buttons is to go. Off, paused or a build without the ids, none does. */
    static boolean choosing() {
        try {
            return Utils.settingsReady() && PatchFamily.Capability.LONG_PRESS_CHOICES.installed()
                    && (Settings.LONG_PRESS_HIDE_SAVE.get() || Settings.LONG_PRESS_HIDE_SEND.get()
                        || Settings.LONG_PRESS_HIDE_SEARCH.get());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LONG_PRESS_DOWNLOAD, "hide switch read", failure);
            return false;
        }
    }

    private static boolean hidden(Object button) {
        switch (buttonKind(button)) {
            case SAVE: return Settings.LONG_PRESS_HIDE_SAVE.get();
            case SEND: return Settings.LONG_PRESS_HIDE_SEND.get();
            case SEARCH: return Settings.LONG_PRESS_HIDE_SEARCH.get();
            default: return false;
        }
    }

    /**
     * The menu is about to show for [event]. A long-pressed pin gets the Download button after
     * Pinterest's own and loses the ones switched off. Anything else keeps the menu exactly as
     * Pinterest laid it out.
     */
    public static void show(Object menu, Object event) {
        HookStatus.invoked(FamilyNames.LONG_PRESS_DOWNLOAD);
        boolean download = active(), choosing = choosing();
        if (!(download || choosing) || !(menu instanceof ViewGroup)) return;
        try {
            Object pin = eventPin(event);
            if (pin == null) return;
            String id = modelId(pin);
            ArrayList<Object> laidOut = menuItems(menu);
            ViewGroup group = (ViewGroup) menu;
            Logger.printDebug(() -> "Long-press menu before changes: " + (laidOut == null ? "no list" : laidOut.size() + " laid out")
                    + ", " + group.getChildCount() + " children");
            if (laidOut == null || laidOut.isEmpty()) return;
            for (Object laid : laidOut) {
                if (!(laid instanceof View) || ITEM_TAG.equals(((View) laid).getTag())) return;
            }
            List<Object> buttons = new ArrayList<>();
            int dropped = 0;
            for (Object laid : laidOut.subList(1, laidOut.size())) {
                if (choosing && hidden(laid)) dropped++;
                else buttons.add(laid);
            }
            int kept = buttons.size();
            View item = download && id != null ? downloadItem(group.getContext()) : null;
            if (item != null) {
                item.setTag(ITEM_TAG);
                WeakReference<Object> heldMenu = new WeakReference<>(menu);
                WeakReference<Object> heldPin = new WeakReference<>(pin);
                item.setOnClickListener(clicked -> download(clicked, heldMenu, heldPin, id));
                buttons.add(item);
            }
            if (item == null && dropped == 0) return;
            if (buttons.isEmpty()) {
                // A menu with no button would open on nothing, so this pin keeps Pinterest's.
                HookStatus.counted(FamilyNames.LONG_PRESS_DOWNLOAD, "long-press menu kept whole, every button was hidden");
                return;
            }
            // Everything that can fail has run. The menu's layout puts back an origin marker of its
            // own, so it starts from a menu with no buttons at all, as it did a moment ago.
            for (Object laid : laidOut) group.removeView((View) laid);
            laidOut.clear();
            layoutItems(menu, buttons);
            // Pinterest's buttons went out of the menu and back in, so they get their springs back
            // before the show method springs them in. Download was never out.
            for (int at = 0; at < kept; at++) springsReattached(buttons.get(at));
            Logger.printDebug(() -> "Long-press menu after changes: " + laidOut.size() + " laid out, " + group.getChildCount() + " children");
            if (item != null) HookStatus.counted(FamilyNames.LONG_PRESS_DOWNLOAD, "download button added to long-press menu");
            if (dropped > 0) HookStatus.counted(FamilyNames.LONG_PRESS_DOWNLOAD, "Pinterest buttons hidden in long-press menu");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LONG_PRESS_DOWNLOAD, "change long-press menu", failure);
        }
    }

    /** The finger let go on the button: the pin saves the way the pin menu's Download pin saves it. */
    private static void download(View clicked, WeakReference<Object> heldMenu, WeakReference<Object> heldPin, String id) {
        try {
            if (!active()) return;
            Object menu = heldMenu.get();
            Object pin = heldPin.get();
            if (menu == null || pin == null || !id.equals(menuModel(menu))) {
                HookStatus.counted(FamilyNames.LONG_PRESS_DOWNLOAD, "closed menu download refused");
                return;
            }
            if (PinDownloads.start(pin, clicked.getContext())) {
                HookStatus.counted(FamilyNames.LONG_PRESS_DOWNLOAD, "long-press download started");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LONG_PRESS_DOWNLOAD, "long-press download", failure);
        }
    }
}
