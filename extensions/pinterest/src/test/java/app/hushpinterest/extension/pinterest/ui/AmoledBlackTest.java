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
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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

import java.util.Arrays;
import java.util.List;

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
        Settings.BLACK_COMMENTS.resetToDefault();
        Settings.BLACK_PIN_MENU.resetToDefault();
        HookStatus.clear();
        AmoledBlack.tabRoot = -1;
        AmoledBlack.searchText = -1;
        AmoledBlack.searchContainer = -1;
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

    @Test public void aTabBuiltAfterTheBarsLastDrawTurnsBlackOnTheNextLayout() {
        Settings.BLACK_BOTTOM_BAR.save(true);
        AmoledBlack.tabRoot = View.generateViewId();
        LinearLayout bar = bar(true);
        LinearLayout row = (LinearLayout) bar.getChildAt(0);
        AmoledBlack.bottomBar(bar);
        // Pinterest builds a tab on a navigation change. Only the row redraws, so the bar's draw hook doesn't run.
        View late = tab(row, ELEVATION);
        bar.layout(0, 0, 320, 64);
        assertEquals(Color.BLACK, late.getBackgroundTintList().getDefaultColor());
        Settings.BLACK_BOTTOM_BAR.save(false);
        bar.layout(0, 0, 320, 72);
        assertEquals("a layout pass with the switch off gives the tint back", ELEVATION, late.getBackgroundTintList().getDefaultColor());
    }

    @Test public void glassUnderTheBarWashesBlackAndOtherGlassKeepsItsColor() {
        Settings.BLACK_BOTTOM_BAR.save(true);
        int wash = 0x80242421;
        LinearLayout bar = bar(true);
        FrameLayout tab = new FrameLayout(bar.getContext());
        View glass = new View(bar.getContext());
        tab.addView(glass);
        ((LinearLayout) bar.getChildAt(0)).addView(tab);
        assertEquals("a bar that hasn't drawn yet isn't known", wash, AmoledBlack.blurFill(glass, wash));
        AmoledBlack.bottomBar(bar);
        assertEquals(Color.BLACK, AmoledBlack.blurFill(glass, wash));
        assertEquals("glass anywhere else keeps Pinterest's wash", wash, AmoledBlack.blurFill(new View(bar.getContext()), wash));
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        assertEquals("Pause gives the wash back on the next frame", wash, AmoledBlack.blurFill(glass, wash));
        PauseForTests.resume();
        LinearLayout light = bar(false);
        View lightGlass = new View(light.getContext());
        light.addView(lightGlass);
        AmoledBlack.bottomBar(light);
        assertEquals("light mode is left alone", wash, AmoledBlack.blurFill(lightGlass, wash));
        Settings.BLACK_BOTTOM_BAR.save(false);
        assertEquals("off gives the wash back on the next frame", wash, AmoledBlack.blurFill(glass, wash));
    }

    @Test public void aSearchTabsPillTurnsBlackWhereverItKeepsItsFill() {
        Settings.BLACK_BOTTOM_BAR.save(true);
        AmoledBlack.tabRoot = View.generateViewId();
        AmoledBlack.searchText = View.generateViewId();
        AmoledBlack.searchContainer = View.generateViewId();
        LinearLayout bar = bar(true);
        LinearLayout row = (LinearLayout) bar.getChildAt(0);
        GradientDrawable onPill = searchTab(row, false);
        GradientDrawable onContainer = searchTab(row, true);
        AmoledBlack.bottomBar(bar);
        assertEquals(Color.BLACK, onPill.getColor().getDefaultColor());
        assertEquals(Color.BLACK, onContainer.getColor().getDefaultColor());
        Settings.BLACK_BOTTOM_BAR.save(false);
        AmoledBlack.bottomBar(bar);
        assertEquals(ELEVATION, onPill.getColor().getDefaultColor());
        assertEquals(ELEVATION, onContainer.getColor().getDefaultColor());
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

    @Test public void aPinsMenuTurnsBlackAndTheNextMenuPinterestBuildsStartsGray() {
        Settings.BLACK_PIN_MENU.save(true);
        FrameLayout menu = sheet(true, "modal_header", "modal_container");
        View header = fill(menu, 1, card());
        View list = fill(menu, 2, new ColorDrawable(ELEVATION));
        // Android keeps the first card it loads and builds each later one from it.
        Drawable.ConstantState loaded = header.getBackground().getConstantState();
        AmoledBlack.pinMenu(menu);
        assertEquals(Color.BLACK, cardColor(header.getBackground()));
        assertEquals(Color.BLACK, ((ColorDrawable) list.getBackground()).getColor());
        assertEquals("the next menu's card comes out of Android's copy gray", ELEVATION, cardColor(loaded.newDrawable()));
        Settings.BLACK_PIN_MENU.save(false);
        menu.layout(0, 0, 320, 480);
        assertEquals("a layout pass with the switch off gives the gray back", ELEVATION, cardColor(header.getBackground()));
        assertEquals(ELEVATION, ((ColorDrawable) list.getBackground()).getColor());
    }

    @Test public void theCommentsScreenTurnsBlackAndAFillPinterestSwapsInLaterFollowsOnTheNextLayout() {
        Settings.BLACK_COMMENTS.save(true);
        FrameLayout screen = sheet(true, "comments_feed", "comment_bottom_bar", "floating_comment_view");
        View feed = fill(screen, 1, card());
        GradientDrawable replyBar = solid();
        fill(screen, 2, replyBar);
        View floating = fill(screen, 3, new ColorDrawable(ELEVATION));
        View photo = fill(screen, View.NO_ID, new ColorDrawable(ELEVATION));
        AmoledBlack.comments(screen);
        assertEquals(Color.BLACK, cardColor(feed.getBackground()));
        assertEquals(Color.BLACK, replyBar.getColor().getDefaultColor());
        assertEquals(Color.BLACK, ((ColorDrawable) floating.getBackground()).getColor());
        assertEquals("a view the screen isn't painted by keeps its color", ELEVATION, ((ColorDrawable) photo.getBackground()).getColor());
        // A full-height sheet gets a plain shape after the screen was handed over.
        GradientDrawable fullHeight = solid();
        feed.setBackground(fullHeight);
        screen.layout(0, 0, 320, 480);
        assertEquals(Color.BLACK, fullHeight.getColor().getDefaultColor());
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        screen.layout(0, 0, 320, 520);
        assertEquals("Pause gives the gray back on the next layout", ELEVATION, fullHeight.getColor().getDefaultColor());
        assertEquals(ELEVATION, replyBar.getColor().getDefaultColor());
        assertEquals(ELEVATION, ((ColorDrawable) floating.getBackground()).getColor());
    }

    @Test public void eachSheetFollowsItsOwnSwitchAndLightModeIsLeftAlone() {
        Settings.BLACK_COMMENTS.save(true);
        FrameLayout menu = sheet(true, "modal_header", "modal_container");
        View list = fill(menu, 2, new ColorDrawable(ELEVATION));
        AmoledBlack.pinMenu(menu);
        menu.layout(0, 0, 320, 480);
        assertEquals("the comments switch leaves a pin's menu alone", ELEVATION, ((ColorDrawable) list.getBackground()).getColor());
        FrameLayout light = sheet(false, "comments_feed", "comment_bottom_bar", "floating_comment_view");
        View floating = fill(light, 3, new ColorDrawable(ELEVATION));
        AmoledBlack.comments(light);
        light.layout(0, 0, 320, 480);
        assertEquals(ELEVATION, ((ColorDrawable) floating.getBackground()).getColor());
    }

    /**
     * A sheet like the ones Pinterest hands over, in dark mode or light. Its resources know the ids
     * its fills are found by, numbered from 1 in the order given.
     */
    private static FrameLayout sheet(boolean night, String... names) {
        Context base = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.uiMode = (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO)
                | (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK);
        Context themed = base.createConfigurationContext(configuration);
        Resources original = themed.getResources();
        List<String> known = Arrays.asList(names);
        Resources resources = new Resources(themed.getAssets(), original.getDisplayMetrics(), original.getConfiguration()) {
            @Override public int getIdentifier(String name, String type, String owner) {
                return known.indexOf(name) + 1;
            }
        };
        return new FrameLayout(themed) {
            @Override public Resources getResources() { return resources; }
        };
    }

    /** A view in the sheet with one of its ids and a background. */
    private static View fill(FrameLayout sheet, int id, Drawable background) {
        View view = new View(sheet.getContext());
        view.setId(id);
        view.setBackground(background);
        sheet.addView(view);
        return view;
    }

    /** A sheet's rounded card the way Pinterest's layer list builds it: a clear layer under the gray shape. */
    private static LayerDrawable card() {
        GradientDrawable clear = new GradientDrawable();
        clear.setColor(Color.TRANSPARENT);
        return new LayerDrawable(new Drawable[] {clear, solid()});
    }

    private static GradientDrawable solid() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ELEVATION);
        return shape;
    }

    private static int cardColor(Drawable card) {
        return ((GradientDrawable) ((LayerDrawable) card).getDrawable(1)).getColor().getDefaultColor();
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

    /**
     * A search tab like Pinterest's: a plain frame around the search pill, whose gray sits on the
     * pill itself or on the container inside it. Answers the shape that carries it.
     */
    private static GradientDrawable searchTab(LinearLayout row, boolean inContainer) {
        FrameLayout tab = new FrameLayout(row.getContext());
        FrameLayout pill = new FrameLayout(row.getContext());
        FrameLayout container = new FrameLayout(row.getContext());
        container.setId(AmoledBlack.searchContainer);
        View text = new View(row.getContext());
        text.setId(AmoledBlack.searchText);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(24f);
        shape.setColor(ELEVATION);
        (inContainer ? container : pill).setBackground(shape);
        container.addView(text);
        pill.addView(container);
        tab.addView(pill);
        row.addView(tab);
        return shape;
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
