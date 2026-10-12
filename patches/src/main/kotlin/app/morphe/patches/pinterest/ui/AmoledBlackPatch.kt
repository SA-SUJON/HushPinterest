/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ui

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * The bar's gray is Pinterest's elevation color, which menus, popovers and floating buttons share,
 * so the theme is left alone and only the bar's own drawables are recolored as it draws.
 */
@Suppress("unused")
val amoledBlackPatch = bytecodePatch(
    name = "AMOLED black",
    description = "Paints the bottom bar pure black in dark mode, so it matches Pinterest's black pages on OLED " +
        "screens. Light mode stays as it is. Starts off. Turn it on in HushPinterest settings > Interface.",
) {
    category("Interface")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())
    execute {
        requireStatusMethod("amoledBlack")
        requireStatusMethod("blackBottomBar")
        val bar = mutableClassDefBy(BOTTOM_BAR)
        // The extension paints the plain LinearLayout children, which is where the bar keeps its tabs.
        if (bar.fields.none { it.type == "Landroid/widget/LinearLayout;" }) {
            throw PatchException("Bottom bar no longer keeps its tabs in a plain row")
        }
        refreshOnDispatchDraw(bar, "$AMOLED_BLACK->bottomBar(Landroid/view/View;)V")
        enableCapability("blackBottomBar")
        enableStatus("amoledBlack")
    }
}
