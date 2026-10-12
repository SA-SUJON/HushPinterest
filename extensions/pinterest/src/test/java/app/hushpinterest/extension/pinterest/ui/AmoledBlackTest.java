/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
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
import app.hushpinterest.extension.shared.settings.HushPinterestPause;
import app.hushpinterest.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AmoledBlackTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();

    /** Pinterest's sema_color_background_elevation at night, the bar's warm gray. */
    private static final int ELEVATION = 0xFF33332E;

    @After public void restore() {
        PauseForTests.resume();
        Settings.BLACK_BOTTOM_BAR.resetToDefault();
        HookStatus.clear();
        AmoledBlack.tabRoot = -1;
        AmoledBlack.gestureStrip = -1;
    }

    @Test public void separateTabsTintedGrayTurnBlackAndOffGivesTheTintBack() {
        Settings.BLACK_BOTTOM_BAR.save(true);
        AmoledBlack.tabRoot = View.generateViewId();
        LinearLayout bar = bar(true);
        LinearLayout row = (LinearLayout) bar.getChildAt(0);
        row.setBackground(null);
        View pill = tab(row, ELEVATION);
        View glass = tab(row, 0x8033332E);
        AmoledBlack.bottomBar(bar);
        assertEquals(Color.BLACK, pill.getBackgroundTintList().getDefaultColor());
        assertEquals("the rounded shape under the tint stays", Color.WHITE,
                ((GradientDrawable) pill.getBackground()).getColor().getDefaultColor());
        assertEquals("a see-through tab keeps its look", 0x8033332E, glass.getBackgroundTintList().getDefaultColor());
        Settings.BLACK_BOTTOM_BAR.save(false);
        AmoledBlack.bottomBar(bar);
        assertEquals(ELEVATION, pill.getBackgroundTintList().getDefaultColor());
    }

    @Test public void theStripUnderTheBarTurnsBlackAndOffGivesItsColorBack() {
        Settings.BLACK_BOTTOM_BAR.save(true);
        AmoledBlack.gestureStrip = View.generateViewId();
        LinearLayout bar = bar(true);
        FrameLayout window = new FrameLayout(bar.getContext());
        window.addView(bar);
        View strip = new View(bar.getContext());
        strip.setId(AmoledBlack.gestureStrip);
        strip.setBackgroundColor(0xFF222226);
        window.addView(strip);
        AmoledBlack.bottomBar(bar);
        assertEquals(Color.BLACK, ((ColorDrawable) strip.getBackground()).getColor());
        Settings.BLACK_BOTTOM_BAR.save(false);
        AmoledBlack.bottomBar(bar);
        assertEquals(0xFF222226, ((ColorDrawable) strip.getBackground()).getColor());
    }

    @Test public void aStripAddedAfterTheBarsFirstDrawStillTurnsBlack() {
        Settings.BLACK_BOTTOM_BAR.save(true);
        AmoledBlack.gestureStrip = View.generateViewId();
        LinearLayout bar = bar(true);
        FrameLayout window = new FrameLayout(bar.getContext());
        window.addView(bar);
        AmoledBlack.bottomBar(bar);
        View strip = new View(bar.getContext());
        strip.setId(AmoledBlack.gestureStrip);
        strip.setBackgroundColor(0xFF222226);
        window.addView(strip);
        AmoledBlack.bottomBar(bar);
        assertEquals(Color.BLACK, ((ColorDrawable) strip.getBackground()).getColor());
    }

    @Test public void offByDefaultTheBarKeepsPinterestsGray() {
        LinearLayout bar = bar(true);
        GradientDrawable row = rowFill(bar);
        AmoledBlack.bottomBar(bar);
        assertEquals(ELEVATION, row.getColor().getDefaultColor());
    }

    @Test public void onInDarkModeTheTabRowTurnsBlackAndOffGivesTheGrayBack() {
        Settings.BLACK_BOTTOM_BAR.save(true);
        LinearLayout bar = bar(true);
        GradientDrawable row = rowFill(bar);
        AmoledBlack.bottomBar(bar);
        assertEquals(Color.BLACK, row.getColor().getDefaultColor());
        assertSame("the row keeps its own drawable, so its rounded shape stays", row, bar.getChildAt(0).getBackground());
        AmoledBlack.bottomBar(bar);
        assertEquals(Color.BLACK, row.getColor().getDefaultColor());
        Settings.BLACK_BOTTOM_BAR.save(false);
        AmoledBlack.bottomBar(bar);
        assertEquals(ELEVATION, row.getColor().getDefaultColor());
    }

    @Test public void pauseBringsTheGrayBackOnTheNextFrame() {
        Settings.BLACK_BOTTOM_BAR.save(true);
        LinearLayout bar = bar(true);
        GradientDrawable row = rowFill(bar);
        AmoledBlack.bottomBar(bar);
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        AmoledBlack.bottomBar(bar);
        assertEquals(ELEVATION, row.getColor().getDefaultColor());
    }

    @Test public void lightModeIsLeftAlone() {
        Settings.BLACK_BOTTOM_BAR.save(true);
        LinearLayout bar = bar(false);
        GradientDrawable row = rowFill(bar);
        row.setColor(Color.WHITE);
        AmoledBlack.bottomBar(bar);
        assertEquals(Color.WHITE, row.getColor().getDefaultColor());
    }

    @Test public void theBarsOwnSolidFillTurnsBlackToo() {
        Settings.BLACK_BOTTOM_BAR.save(true);
        LinearLayout bar = bar(true);
        bar.setBackgroundColor(ELEVATION);
        AmoledBlack.bottomBar(bar);
        assertEquals(Color.BLACK, ((ColorDrawable) bar.getBackground()).getColor());
        Settings.BLACK_BOTTOM_BAR.save(false);
        AmoledBlack.bottomBar(bar);
        assertEquals(ELEVATION, ((ColorDrawable) bar.getBackground()).getColor());
    }

    @Test public void gradientsTranslucentFillsAndOtherViewsKeepTheirLook() {
        Settings.BLACK_BOTTOM_BAR.save(true);
        LinearLayout bar = bar(true);
        // The scrim behind a floating bar and the shadow strip above an anchored one are gradients.
        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x0A000000, 0});
        bar.setBackground(scrim);
        View shadow = new View(bar.getContext());
        GradientDrawable strip = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x0A000000, 0});
        shadow.setBackground(strip);
        bar.addView(shadow);
        FrameLayout upload = new FrameLayout(bar.getContext());
        upload.setBackgroundColor(ELEVATION);
        bar.addView(upload);
        LinearLayout veil = new LinearLayout(bar.getContext());
        veil.setBackgroundColor(0x8033332E);
        bar.addView(veil);
        AmoledBlack.bottomBar(bar);
        assertSame(scrim, bar.getBackground());
        assertEquals(null, scrim.getColor());
        assertEquals(null, strip.getColor());
        assertEquals(ELEVATION, ((ColorDrawable) upload.getBackground()).getColor());
        assertEquals(0x8033332E, ((ColorDrawable) veil.getBackground()).getColor());
    }

    @Test public void anotherDrawableFromTheSameResourceKeepsItsGray() {
        Settings.BLACK_BOTTOM_BAR.save(true);
        LinearLayout bar = bar(true);
        GradientDrawable row = rowFill(bar);
        Drawable menu = row.getConstantState().newDrawable();
        AmoledBlack.bottomBar(bar);
        assertEquals(Color.BLACK, row.getColor().getDefaultColor());
        assertEquals(ELEVATION, ((GradientDrawable) menu).getColor().getDefaultColor());
    }

    /** A bar like Pinterest's, holding a plain tab row with the gray rounded shape. */
    private static LinearLayout bar(boolean night) {
        Context base = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.uiMode = (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO)
                | (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK);
        LinearLayout bar = new LinearLayout(base.createConfigurationContext(configuration));
        LinearLayout tabs = new LinearLayout(bar.getContext());
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(48f);
        shape.setColor(ELEVATION);
        tabs.setBackground(shape);
        bar.addView(tabs);
        return bar;
    }

    private static GradientDrawable rowFill(LinearLayout bar) {
        return (GradientDrawable) bar.getChildAt(0).getBackground();
    }

    /** A tab like those of the bar that shows them apart: a white rounded shape under a tint. */
    private static View tab(LinearLayout row, int tint) {
        FrameLayout tab = new FrameLayout(row.getContext());
        FrameLayout root = new FrameLayout(row.getContext());
        root.setId(AmoledBlack.tabRoot);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(24f);
        shape.setColor(Color.WHITE);
        root.setBackground(shape);
        root.setBackgroundTintList(ColorStateList.valueOf(tint));
        tab.addView(root);
        row.addView(tab);
        return root;
    }
}
