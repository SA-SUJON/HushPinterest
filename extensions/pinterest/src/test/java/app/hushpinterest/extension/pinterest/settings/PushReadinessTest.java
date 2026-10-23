/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowToast;

import java.util.EnumSet;
import java.util.Set;

import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.settings.preference.LogBufferManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@SuppressWarnings("deprecation")
public class PushReadinessTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private Context context;
    private ComponentName service;
    private ComponentName receiver;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        service = new ComponentName(context, "private.account123.Messaging");
        receiver = new ComponentName(context, "private.account123.Receiver");
        PatchFamily.registerDiagnostics();
    }

    private void installComponents() throws Exception {
        ShadowPackageManager pm = Shadows.shadowOf(context.getPackageManager());
        pm.addServiceIfNotPresent(service).enabled = true;
        pm.addIntentFilterForService(service, new IntentFilter("com.google.firebase.MESSAGING_EVENT"));
        pm.addReceiverIfNotPresent(receiver).enabled = true;
        pm.addIntentFilterForReceiver(receiver, new IntentFilter("com.google.android.c2dm.intent.RECEIVE"));
        context.getPackageManager().getApplicationInfo(context.getPackageName(), 0).enabled = true;
    }

    @Test public void allPresentStillDoesNotClaimDeliveryOrExposeComponentNames() throws Exception {
        installComponents();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.setNotificationDelegate("com.google.android.gms");
        Bundle metadata = new Bundle();
        metadata.putBoolean("firebase_analytics_collection_deactivated", true);
        PackageInfo installed = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_META_DATA
                        | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS);
        installed.applicationInfo.metaData = metadata;
        Shadows.shadowOf(context.getPackageManager()).installPackage(installed);
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("[PUSH READINESS]"));
        assertTrue(report, report.contains("notification_permission: granted"));
        assertTrue(report, report.contains("notification_delegate: com.google.android.gms"));
        assertTrue(report, report.contains("messaging_services: present, enabled=1, disabled=0"));
        assertTrue(report, report.contains("messaging_receivers: present, enabled=1, disabled=0"));
        assertTrue(report, report.contains("firebase_analytics_collection_deactivated: true"));
        assertTrue(report, report.contains("live delivery not proven"));
        assertFalse(report, report.contains("private.account123"));
    }

    @Test public void missingComponentsAndDelegateAreExplicit() {
        String report = String.join("\n", PushReadiness.REPORT.lines());
        assertTrue(report, report.contains("messaging_services: missing"));
        assertTrue(report, report.contains("messaging_receivers: missing"));
        assertTrue(report, report.contains("notification_delegate: none (delegation is optional)"));
        assertTrue(report, report.contains("firebase_analytics_collection_deactivated: not declared"));
    }

    @Test public void deniedPermissionAndBlockedNotificationsStayDistinct() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Shadows.shadowOf(context.getSystemService(NotificationManager.class)).setNotificationsEnabled(false);
        String report = String.join("\n", PushReadiness.REPORT.lines());
        assertTrue(report, report.contains("notification_permission: denied"));
        assertTrue(report, report.contains("notifications: disabled"));
    }

    @Test public void disabledComponentsArePresentInsteadOfMissing() throws Exception {
        installComponents();
        context.getPackageManager().setComponentEnabledSetting(service,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        context.getPackageManager().setComponentEnabledSetting(receiver,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        String report = String.join("\n", PushReadiness.REPORT.lines());
        assertTrue(report, report.contains("messaging_services: present, enabled=0, disabled=1"));
        assertTrue(report, report.contains("messaging_receivers: present, enabled=0, disabled=1"));
    }

    @Test public void unrecognizedDelegateIsRedacted() {
        context.getSystemService(NotificationManager.class).setNotificationDelegate("private.account123.delegate");
        String report = String.join("\n", PushReadiness.REPORT.lines());
        assertTrue(report, report.contains("notification_delegate: other package (redacted)"));
        assertFalse(report, report.contains("account123"));
    }

    @Test public void withGmsCoreSupportTheReceiverListensForMicroGsActionAndMicroGIsChecked() throws Exception {
        ShadowPackageManager pm = Shadows.shadowOf(context.getPackageManager());
        pm.addReceiverIfNotPresent(receiver).enabled = true;
        pm.addIntentFilterForReceiver(receiver, new IntentFilter("app.revanced.android.c2dm.intent.RECEIVE"));
        context.getSystemService(NotificationManager.class).setNotificationDelegate("app.revanced.android.gms");
        PushReadiness.gmsCoreForTests = true;
        String report = String.join("\n", PushReadiness.REPORT.lines());
        assertTrue(report, report.contains("messaging_receivers: present, enabled=1, disabled=0"));
        assertTrue(report, report.contains("notification_delegate: app.revanced.android.gms"));
        assertTrue(report, report.contains("microg_re: missing"));

        PackageInfo microG = new PackageInfo();
        microG.packageName = "app.revanced.android.gms";
        pm.installPackage(microG);
        report = String.join("\n", PushReadiness.REPORT.lines());
        assertTrue(report, report.contains("microg_re: installed"));

        // A stock build listens for Google's action, so microG's alone counts as missing there.
        PushReadiness.gmsCoreForTests = false;
        report = String.join("\n", PushReadiness.REPORT.lines());
        assertTrue(report, report.contains("messaging_receivers: missing"));
        assertFalse(report, report.contains("microg_re"));
    }

    // ---- The Android notification settings row --------------------------------------------------

    private static final String KEY = HushPinterestPreferenceFragment.NOTIFICATION_SETTINGS;
    private static final String ON = "Android lets this app show notifications. That only means they can show, not that "
            + "push notifications reach this phone. Tap to change them in Android's settings.";
    private static final String OFF = "Notifications are off for this app in Android's settings, so none can show. "
            + "Tap to turn them on there.";

    private ActivityController<Activity> controller;

    @After public void restore() {
        if (controller != null) controller.close();
        PatchFamily.inBuildForTests = null;
        PushReadiness.gmsCoreForTests = null;
        Settings.FIX_PUSH_NOTIFICATIONS.resetToDefault();
        ShadowToast.reset();
    }

    /** The settings screen of a build with [build]. */
    private HushPinterestPreferenceFragment show(Set<PatchFamily> build) {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        HushPinterestPreferenceFragment page = new HushPinterestPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        return page;
    }

    private Preference row() {
        Preference row = show(EnumSet.of(PatchFamily.FIX_PUSH_NOTIFICATIONS)).findPreference(KEY);
        assertNotNull("no Android notification settings row", row);
        return row;
    }

    private static void notifications(boolean enabled) {
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getSystemService(NotificationManager.class))
                .setNotificationsEnabled(enabled);
    }

    /**
     * A tap opens the notification page of the package this runs as, which a patched build can
     * rename, and grants, switches and saves nothing itself.
     */
    @Test @Config(sdk = {29, 33, 36})
    public void aTapOpensThisPackagesNotificationSettingsAndChangesNothing() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        notifications(false);
        boolean fix = Settings.FIX_PUSH_NOTIFICATIONS.savedValue();
        Preference row = row();
        assertEquals("Notifications", String.valueOf(row.getParent().getTitle()));
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));

        Intent started = Shadows.shadowOf(controller.get()).getNextStartedActivity();
        assertNotNull("nothing opened", started);
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.getAction());
        String running = context.getPackageName();
        assertNotEquals("the control: the test app runs under a name of its own", "com.pinterest", running);
        assertEquals(running, started.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE));
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
        assertFalse(context.getSystemService(NotificationManager.class).areNotificationsEnabled());
        assertEquals(fix, Settings.FIX_PUSH_NOTIFICATIONS.savedValue());
    }

    /** A phone without that page gets App info for this app, which leads to the same switches. */
    @Test public void withoutTheNotificationPageAppInfoOpens() {
        Preference row = row();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        Intent details = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.getPackageName(), null));
        ResolveInfo settings = new ResolveInfo();
        settings.activityInfo = new ActivityInfo();
        settings.activityInfo.packageName = "com.android.settings";
        settings.activityInfo.name = "com.android.settings.AppInfo";
        Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(details, settings);
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));

        Intent started = Shadows.shadowOf(controller.get()).getNextStartedActivity();
        assertNotNull("nothing opened", started);
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.getAction());
        assertEquals("package:" + context.getPackageName(), started.getDataString());
    }

    /** With neither page, a tap says so instead of closing Pinterest. */
    @Test public void withNoSettingsPageATapSaysSo() {
        Preference row = row();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
        assertNull(Shadows.shadowOf(controller.get()).getNextStartedActivity());
        assertEquals("Couldn't show Notifications. It's in Pinterest's App info.",
                ShadowToast.getTextOfLatestToast());
        assertFalse(controller.get().isFinishing());
    }

    /** The row says what Android allows now, says it again on the way back, and never that a push arrives. */
    @Test public void theRowReadsAndroidsAnswerAgainOnTheWayBack() {
        notifications(false);
        Preference row = row();
        assertEquals(OFF, String.valueOf(row.getSummary()));
        notifications(true);
        controller.pause().resume();
        assertEquals(ON, String.valueOf(row.getSummary()));
        // An answer Android couldn't give is its own line, not a denial.
        assertEquals("Android didn't say whether this app can show notifications. Tap to check in Android's settings.",
                PushReadiness.summary(null));
    }

    /**
     * Only a build with Fix push notifications has the Notifications page and the row. Pinterest's own
     * controls stay lines of text that name a path and open nothing.
     */
    @Test public void onlyWithThePatchAndPinterestsOwnRowsStayText() {
        HushPinterestPreferenceFragment page = show(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.findPreference(KEY));
        PreferenceGroup pinterest = null;
        for (Preference section : page.sections()) {
            if ("Pinterest's own settings".contentEquals(section.getTitle())) pinterest = (PreferenceGroup) section;
        }
        assertNotNull(pinterest);
        assertEquals(NativeControls.controls().size() + 1, pinterest.getPreferenceCount());
        for (int i = 0; i < pinterest.getPreferenceCount(); i++) {
            Preference control = pinterest.getPreference(i);
            assertFalse(control.getTitle() + " can be tapped", control.isSelectable());
            assertNull(control.getTitle() + " opens something", control.getOnPreferenceClickListener());
        }
    }
}
