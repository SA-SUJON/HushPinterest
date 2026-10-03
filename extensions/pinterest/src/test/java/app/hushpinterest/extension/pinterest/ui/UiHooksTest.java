/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;
import app.hushpinterest.extension.shared.settings.BooleanSetting;
import app.hushpinterest.extension.shared.settings.HushPinterestPause;
import app.hushpinterest.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class UiHooksTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();

    private enum Tab { HOME, SEARCH, CREATE, NOTIFICATIONS, PROFILE, UNKNOWN }

    private static final BooleanSetting[] SWITCHES = {
            Settings.HIDE_SCREENSHOT_SHARE, Settings.HIDE_SEARCH_HISTORY,
            Settings.HIDE_NAV_CREATE, Settings.HIDE_NAV_NOTIFICATIONS, Settings.HIDE_HEADER_BUTTONS,
            Settings.HIDE_PIN_MENU_COLLAGE, Settings.HIDE_PIN_MENU_VISUAL_SEARCH, Settings.HIDE_PIN_MENU_PIN_BOOST,
            Settings.HIDE_COMMENTS, Settings.QUIET_EMAIL_REMINDER, Settings.DISABLE_UPDATE_NAG
    };

    @After public void restore() {
        PauseForTests.resume();
        for (BooleanSetting setting : SWITCHES) setting.resetToDefault();
        HookStatus.clear();
    }

    @Test public void everyOptionalControlStartsOffAndLeavesHostValuesAlone() {
        for (BooleanSetting setting : SWITCHES) assertFalse(setting.key, setting.defaultValue);
        assertFalse(UiHooks.hideScreenshotShare());
        assertFalse(UiHooks.quietEmailReminder());
        assertFalse(UiHooks.disableUpdateNag());
        assertEquals(View.INVISIBLE, UiHooks.searchHistoryVisibility(View.INVISIBLE));
        assertEquals(312, UiHooks.searchHistoryMeasureSpec(312));
        assertEquals(View.VISIBLE, UiHooks.commentsVisibility(View.VISIBLE));
        assertTrue(UiHooks.commentsVisible(true));
    }

    @Test public void screenshotAndOptionalRemindersRestoreWhenPaused() {
        Settings.HIDE_SCREENSHOT_SHARE.save(true);
        Settings.QUIET_EMAIL_REMINDER.save(true);
        Settings.DISABLE_UPDATE_NAG.save(true);
        assertTrue(UiHooks.hideScreenshotShare());
        assertTrue(UiHooks.quietEmailReminder());
        assertTrue(UiHooks.disableUpdateNag());
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        assertFalse(UiHooks.hideScreenshotShare());
        assertFalse(UiHooks.quietEmailReminder());
        assertFalse(UiHooks.disableUpdateNag());
        assertTrue(Settings.HIDE_SCREENSHOT_SHARE.savedValue());
    }

    @Test public void searchHistoryAndCommentsFoldToZeroAndRestoreTheirRequestedSize() {
        int spec = View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST);
        Settings.HIDE_SEARCH_HISTORY.save(true);
        Settings.HIDE_COMMENTS.save(true);
        assertEquals(View.GONE, UiHooks.searchHistoryVisibility(View.VISIBLE));
        assertEquals(0, View.MeasureSpec.getSize(UiHooks.searchHistoryMeasureSpec(spec)));
        assertEquals(View.MeasureSpec.EXACTLY, View.MeasureSpec.getMode(UiHooks.searchHistoryMeasureSpec(spec)));
        assertEquals(View.GONE, UiHooks.commentsVisibility(View.VISIBLE));
        assertEquals(0, View.MeasureSpec.getSize(UiHooks.commentsMeasureSpec(spec)));
        assertFalse(UiHooks.commentsVisible(true));
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        assertEquals(View.INVISIBLE, UiHooks.searchHistoryVisibility(View.INVISIBLE));
        assertEquals(spec, UiHooks.searchHistoryMeasureSpec(spec));
        assertEquals(spec, UiHooks.commentsMeasureSpec(spec));
        assertTrue(UiHooks.commentsVisible(true));
    }

    @Test public void navigationSwitchesHideOnlyTheirNamedTabsAndRestoreTheHostVisibility() {
        Settings.HIDE_NAV_CREATE.save(true);
        Settings.HIDE_NAV_NOTIFICATIONS.save(false);
        assertTrue(InterfaceControls.hideNavigation("CREATE"));
        assertFalse(InterfaceControls.hideNavigation("NOTIFICATIONS"));
        assertFalse(InterfaceControls.hideNavigation("HOME"));
        assertFalse(InterfaceControls.hideNavigation("PROFILE"));
        assertFalse(InterfaceControls.hideNavigation("SEARCH"));
        assertFalse(InterfaceControls.hideNavigation("UNKNOWN"));
        LinearLayout root = new LinearLayout(RuntimeEnvironment.getApplication());
        LinearLayout nested = new LinearLayout(RuntimeEnvironment.getApplication());
        View create = new View(RuntimeEnvironment.getApplication());
        View profile = new View(RuntimeEnvironment.getApplication());
        create.setVisibility(View.INVISIBLE);
        nested.addView(create);
        nested.addView(profile);
        root.addView(nested);
        InterfaceControls.bindNavigation(create, Tab.CREATE);
        InterfaceControls.bindNavigation(profile, Tab.PROFILE);
        assertEquals(View.GONE, create.getVisibility());
        assertEquals(View.VISIBLE, profile.getVisibility());
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        InterfaceControls.refreshNavigation(root);
        assertEquals(View.INVISIBLE, create.getVisibility());
        assertEquals(View.VISIBLE, profile.getVisibility());
        assertEquals(2, nested.getChildCount());
    }

    @Test public void unknownNavigationModelsLeaveTheViewAlone() {
        Settings.HIDE_NAV_CREATE.save(true);
        View view = new View(RuntimeEnvironment.getApplication());
        view.setVisibility(View.INVISIBLE);
        InterfaceControls.bindNavigation(view, new Object());
        assertEquals(View.INVISIBLE, view.getVisibility());
    }

    @Test public void headerControlNamesExcludeLeadingActionsTextAndAvatar() {
        assertTrue(InterfaceControls.headerAction("end_container_icon_bt"));
        assertTrue(InterfaceControls.headerAction("end_container_icon_buttons"));
        assertFalse(InterfaceControls.headerAction("start_container_icon_bt"));
        assertFalse(InterfaceControls.headerAction("start_container_icon_buttons"));
        assertFalse(InterfaceControls.headerAction("end_container_text_button"));
        assertFalse(InterfaceControls.headerAction("end_container_avatar"));
        assertFalse(InterfaceControls.headerAction("header_static_search_bar"));
    }

    @Test public void menuChoicesAreIndependentAndKeepDownloadShareCopyReportAndSave() {
        Settings.HIDE_PIN_MENU_COLLAGE.save(true);
        assertTrue(InterfaceControls.hidePinMenuItem("overflow_menu_add_to_collage"));
        assertTrue(InterfaceControls.hidePinMenuItem("overflow_menu_remix_collage"));
        assertFalse(InterfaceControls.hidePinMenuItem("contextmenu_visual_search_image"));
        assertFalse(InterfaceControls.hidePinMenuItem("overflow_menu_pin_boost"));
        Settings.HIDE_PIN_MENU_VISUAL_SEARCH.save(true);
        Settings.HIDE_PIN_MENU_PIN_BOOST.save(true);
        assertTrue(InterfaceControls.hidePinMenuItem("contextmenu_visual_search_image"));
        assertTrue(InterfaceControls.hidePinMenuItem("overflow_menu_pin_boost"));
        for (String key : new String[] {"save_to_device", "overflow_menu_share", "copy_link",
                "grid_actions_report_pin", "save_pin", "overflow_menu_edit", "unknown"}) {
            assertFalse(key, InterfaceControls.hidePinMenuItem(key));
        }
        View row = new View(RuntimeEnvironment.getApplication());
        InterfaceControls.pinMenuItem(row, "overflow_menu_add_to_collage");
        assertEquals(View.GONE, row.getVisibility());
        Settings.HIDE_PIN_MENU_COLLAGE.save(false);
        InterfaceControls.pinMenuItem(row, "overflow_menu_add_to_collage");
        assertEquals(View.VISIBLE, row.getVisibility());
    }
}
