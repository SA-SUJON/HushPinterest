/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.settings;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushpinterest.extension.shared.SettingsContextRule;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class NativeControlsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test public void eachPathFallsBackToPinterestsEnglishWhenTheBuildHasNoSuchLabel() {
        Context context = RuntimeEnvironment.getApplication();
        List<String> paths = new ArrayList<>();
        for (NativeControls.Control control : NativeControls.controls()) {
            paths.add(control.title + ": " + NativeControls.path(context, control));
        }
        assertEquals(Arrays.asList(
                "App theme: Settings > Account management > App theme",
                "Privacy and data: Settings > Privacy and data",
                "Notifications: Settings > Notifications",
                "Show AI content: Settings > Refine your recommendations > AI content > Show AI content",
                "Autoplay videos on cellular data: Settings > Social permissions > Autoplay videos on cellular data"), paths);
        NativeControls.Control privacy = NativeControls.controls().get(1);
        assertEquals(privacy.covers + "\nIn Pinterest: ⁨Settings > Privacy and data⁩", NativeControls.summary(context, privacy));
    }

    @Test public void pinterestsOwnLabelWinsWhereTheBuildHasAUsableOne() {
        Map<String, String> strings = new HashMap<>();
        strings.put("settings_main_header_settings", " Einstellungen ");
        strings.put("settings_main_privacy_data", "Datenschutz und Daten");
        // Blank, overlong or several lines: not a menu label, so HushPinterest's copy stands in.
        strings.put("settings_main_account_management", "   ");
        strings.put("settings_account_management_app_theme_title", new String(new char[81]).replace('\0', 'x'));
        strings.put("settings_menu_notifications", "Benachrichtigungen\nund mehr");
        Context context = withStrings(RuntimeEnvironment.getApplication(), strings);
        List<NativeControls.Control> controls = NativeControls.controls();
        assertEquals("Einstellungen > Account management > App theme", NativeControls.path(context, controls.get(0)));
        assertEquals("Einstellungen > Datenschutz und Daten", NativeControls.path(context, controls.get(1)));
        assertEquals("Einstellungen > Notifications", NativeControls.path(context, controls.get(2)));
        assertEquals("Settings", NativeControls.label(null, new NativeControls.Step("settings_main_header_settings", "Settings")));
    }

    /** [base] with Pinterest's strings by name answering from [strings], and every other name missing. */
    private static Context withStrings(Context base, Map<String, String> strings) {
        List<String> names = new ArrayList<>(strings.keySet());
        Resources real = base.getResources();
        @SuppressWarnings("deprecation")
        Resources fake = new Resources(real.getAssets(), real.getDisplayMetrics(), real.getConfiguration()) {
            @Override
            public int getIdentifier(String name, String defType, String defPackage) {
                return "string".equals(defType) && names.contains(name) ? 0x7f990000 + names.indexOf(name) : 0;
            }

            @Override
            public String getString(int id) {
                int index = id - 0x7f990000;
                if (index < 0 || index >= names.size()) throw new Resources.NotFoundException("no string " + id);
                return strings.get(names.get(index));
            }
        };
        return new ContextWrapper(base) {
            @Override
            public Resources getResources() {
                return fake;
            }
        };
    }
}
