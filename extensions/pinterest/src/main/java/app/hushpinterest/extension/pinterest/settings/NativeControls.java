/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.settings;

import android.content.Context;
import android.content.res.Resources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.ResourceType;
import app.hushpinterest.extension.shared.ResourceUtils;

/**
 * Pinterest's own controls next to HushPinterest's switches: its theme, privacy, notification, AI
 * content and cellular autoplay settings. HushPinterest changes none of them. Each row names the
 * path to one in Pinterest's settings, and takes each step's label from Pinterest's own strings, in
 * the language Pinterest shows, so the path reads the way its menus do. A label a build doesn't
 * have falls back to HushPinterest's copy of the 14.39.0 English.
 *
 * <p>Pinterest 14.39.0 has links for some of these screens (settings/privacy_data,
 * settings/edit_settings, push_settings), but which of its 80 or so link handlers takes a link
 * depends on their order, so the rows name the path rather than open a screen nobody has checked.
 */
final class NativeControls {
    private NativeControls() {}

    static final String SEPARATOR = " > ";
    /** Longer than any menu label: a string this long under a label's name isn't the label. */
    private static final int LONGEST_LABEL = 80;

    /** One step of a path: the name of Pinterest's string for it, and HushPinterest's copy. */
    static final class Step {
        final String resource;
        final String fallback;

        Step(String resource, String fallback) {
            this.resource = resource;
            this.fallback = fallback;
        }
    }

    /** One of Pinterest's controls: its row's title, what it covers, and the steps that reach it. */
    static final class Control {
        final String title;
        final String covers;
        final List<Step> path;

        Control(String title, String covers, Step... path) {
            this.title = title;
            this.covers = covers;
            this.path = Collections.unmodifiableList(Arrays.asList(path));
        }
    }

    /** Pinterest's controls in the order the rows show them, with 14.39.0's string names. */
    static List<Control> controls() {
        Step settings = new Step("settings_main_header_settings", L10n.t("Settings"));
        return Arrays.asList(
                new Control(L10n.t("App theme"), L10n.t("Pinterest's choice of a light or dark look, or the one your phone uses."),
                        settings, new Step("settings_main_account_management", L10n.t("Account management")),
                        new Step("settings_account_management_app_theme_title", L10n.t("App theme"))),
                new Control(L10n.t("Privacy and data"), L10n.t("Pinterest's ad personalization and data choices for your account. "
                        + "Disable analytics doesn't change them."),
                        settings, new Step("settings_main_privacy_data", L10n.t("Privacy and data"))),
                new Control(L10n.t("Notifications"), L10n.t("Which push, email and in-app notices Pinterest sends you."),
                        settings, new Step("settings_menu_notifications", L10n.t("Notifications"))),
                new Control(L10n.t("Show AI content"), L10n.t("Turned off, Pinterest shows less AI content in your feed. "
                        + "Hide AI-labeled pins only takes out pins that carry Pinterest's AI label."),
                        settings, new Step("settings_refine_your_recommendations", L10n.t("Refine your recommendations")),
                        new Step("homefeed_tuner_gen_ai_topics_tab", L10n.t("AI content")),
                        new Step("homefeed_tuner_gen_ai_topics_master_toggle_title", L10n.t("Show AI content"))),
                new Control(L10n.t("Autoplay videos on cellular data"), L10n.t("Turned off, videos don't start on their own on "
                        + "mobile data. Pinterest may still load some of a video ahead of time."),
                        settings, new Step("settings_main_social_permissions", L10n.t("Social permissions")),
                        new Step("settings_social_permissions_autoplay_cellular_title", L10n.t("Autoplay videos on cellular data"))));
    }

    /** The row's summary: what the control covers, then the path to it. */
    static String summary(Context context, Control control) {
        return control.covers + "\n" + L10n.f("In Pinterest: %1$s", L10n.isolate(path(context, control)));
    }

    /** [control]'s path on one line, each step in Pinterest's own words where this build has them. */
    static String path(Context context, Control control) {
        List<String> labels = new ArrayList<>();
        for (Step step : control.path) labels.add(label(context, step));
        return String.join(SEPARATOR, labels);
    }

    /** Pinterest's label for [step], or HushPinterest's copy when the build has no usable string by that name. */
    static String label(Context context, Step step) {
        int id = context == null ? 0 : ResourceUtils.getIdentifier(context, ResourceType.STRING, step.resource);
        if (id == 0) return step.fallback;
        try {
            String text = context.getString(id).trim();
            if (!text.isEmpty() && text.length() <= LONGEST_LABEL && text.indexOf('\n') < 0) return text;
        } catch (Resources.NotFoundException missing) {
            // A name with no value in this configuration: the build doesn't offer that label here.
        }
        return step.fallback;
    }
}
