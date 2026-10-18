/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ads;

import java.lang.reflect.Field;
import java.util.Map;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/**
 * Hide video pins: pins that play a video leave the lists Pinterest shows, by what the pin itself
 * says, never by looking at its media.
 *
 * <p>A pin is a video when Pinterest marks it {@code is_video}, when its {@code videos} carry any
 * entry in {@code video_list} (a stream-only pin has nothing else, and is_video can be left out),
 * or when it's an idea pin whose {@code story_pin_data} gives a running time above zero, since an
 * idea pin keeps its video in its pages. A GIF is an image to Pinterest, so it stays, and so do
 * photos, idea pins of still pages and anything that isn't a pin.
 */
public final class VideoPins {
    private VideoPins() {}

    static final String IS_VIDEO = "is_video";
    static final String VIDEOS = "videos";
    static final String VIDEO_LIST = "video_list";
    static final String STORY = "story_pin_data";
    static final String STORY_DURATION = "total_video_duration";

    /** Whether the switch is on and the settings can be read; never throws. */
    static boolean active() {
        try {
            return Utils.settingsReady() && Settings.HIDE_VIDEO_PINS.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.FILTER_FEED, "switch read", t);
            return false;
        }
    }

    /** True when [item] is a pin that plays a video. */
    static boolean isVideo(Object item) {
        if (item == null) return false;
        Map<String, Field> fields = ModelFields.of(item.getClass());
        if (!PinWords.isPin(fields)) return false;
        if (ModelFields.isTrue(fields, item, IS_VIDEO)) return true;
        Object list = nested(ModelFields.read(fields, item, VIDEOS), VIDEO_LIST);
        if (list instanceof Map<?, ?> && !((Map<?, ?>) list).isEmpty()) return true;
        Object duration = nested(ModelFields.read(fields, item, STORY), STORY_DURATION);
        return duration instanceof String && runs((String) duration);
    }

    /** [model]'s field named [json], or null when it has none. */
    private static Object nested(Object model, String json) {
        return model == null ? null : ModelFields.read(ModelFields.of(model.getClass()), model, json);
    }

    /**
     * True for a running time above zero in whatever form it's written ("12.5", "0:13"), the rule
     * PinMedia uses for downloads: a digit from 1 to 9 anywhere. "0", "0.0", blank, words and a
     * negative time don't run.
     */
    static boolean runs(String duration) {
        String time = duration.trim();
        if (time.length() > 32 || time.startsWith("-")) return false;
        for (int i = 0; i < time.length(); i++) {
            char c = time.charAt(i);
            if (c >= '1' && c <= '9') return true;
        }
        return false;
    }
}
