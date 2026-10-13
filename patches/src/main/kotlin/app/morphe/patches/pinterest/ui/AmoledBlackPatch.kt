/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ui

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.localRegisterCount
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireFreeAt
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.extension.requireThisIntact
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "AMOLED black"

/** Pinterest's frosted glass. The bar's tabs and its search pill are made of it where the bar shows them as glass. */
internal const val BLUR_VIEW = "Lcom/pinterest/gestalt/design/blur/GestaltBlurView;"

/** Where a glass view washes its canvas: its draw method, the wash's index, and the registers holding the view and the color. */
internal data class GlassWash(val draw: Method, val index: Int, val view: Int, val color: Int)

/**
 * The bar's gray is Pinterest's elevation color, which menus, popovers and floating buttons share,
 * so the theme is left alone and only the bar's own drawables are recolored as it draws.
 */
@Suppress("unused")
val amoledBlackPatch = bytecodePatch(
    name = PATCH,
    description = "Paints the bottom bar pure black in dark mode, so it matches Pinterest's black pages on OLED " +
        "screens. Light mode stays as it is. Starts off. Turn it on in HushPinterest settings > Interface.",
) {
    category("Interface")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())
    execute {
        requireStatusMethod("amoledBlack")
        requireStatusMethod("blackBottomBar")
        val wash = glassWash()
        val bar = mutableClassDefBy(BOTTOM_BAR)
        // The extension paints the plain LinearLayout children, which is where the bar keeps its tabs.
        if (bar.fields.none { it.type == "Landroid/widget/LinearLayout;" }) {
            throw PatchException("Bottom bar no longer keeps its tabs in a plain row")
        }
        refreshOnDispatchDraw(bar, "$AMOLED_BLACK->bottomBar(Landroid/view/View;)V")
        // Glass has no fill to recolor. The extension answers the color each frame's wash uses instead.
        mutable(wash.draw).addInstructionsAtControlFlowLabel(wash.index, """
            invoke-static { v${wash.view}, v${wash.color} }, $AMOLED_BLACK->blurFill(Landroid/view/View;I)I
            move-result v${wash.color}
        """)
        enableCapability("blackBottomBar")
        enableStatus("amoledBlack")
    }
}

/**
 * The one place Pinterest's frosted glass washes its blurred backdrop with a color, checked so a
 * hook in front of it can read the view and put its answer where the color was. Nothing is changed here.
 */
internal fun BytecodePatchContext.glassWash(): GlassWash {
    val glass = classDefByOrNull(BLUR_VIEW) ?: throw PatchException("$PATCH: Pinterest's frosted glass view wasn't found")
    val draw = glass.methods.filter {
        it.name == "draw" && it.returnType == "V" && it.parameters() == listOf("Landroid/graphics/Canvas;")
    }.one("$PATCH: frosted glass draw method")
    val (index, wash) = draw.instructions().withIndex().filter { (_, instruction) ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
            it.definingClass == "Landroid/graphics/Canvas;" && it.name == "drawColor" && it.parameters() == listOf("I")
        } == true
    }.one("$PATCH: frosted glass wash")
    // A two-register call names the canvas and then the color, both in v15 or below.
    val color = (wash as? FiveRegisterInstruction)?.takeIf { it.registerCount == 2 }?.registerD
        ?: throw PatchException("$PATCH: the frosted glass wash isn't a plain call with a canvas and a color")
    val view = draw.localRegisterCount()
    if (view > 15) throw PatchException("$PATCH: the frosted glass view's own register is past v15")
    draw.requireThisIntact(PATCH, listOf(index))
    // The hook's answer replaces the color in its register, so nothing after the wash may read the old one.
    draw.requireFreeAt(PATCH, index + 1, listOf(color))
    return GlassWash(draw, index, view, color)
}
