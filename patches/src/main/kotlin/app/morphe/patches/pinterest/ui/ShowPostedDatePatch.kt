/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.pinterest.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Show posted date"

internal const val POSTED_DATE_HOOK = "$EXTENSION_PACKAGE/ui/PostedDate;->show(Landroid/view/View;Ljava/lang/Object;)V"

/** The base every closeup module extends. */
internal const val CLOSEUP_MODULE = "Lcom/pinterest/activity/pin/view/modules/PinCloseupBaseModule;"

/** The R field naming the chevron in the title row, which opens the pin's description. */
internal const val TITLE_CHEVRON_ID = "closeup_metadata_expand_chevron"

/** The experiment the title module checks as it takes each pin. */
internal const val HOLISTIC_METADATA = "android_closeup_holistic_metadata"

/** True when one of [type]'s constructors builds the title row and adds it to the module. */
private fun BytecodePatchContext.buildsTitleRow(type: String): Boolean {
    val owner = classDefByOrNull(type) ?: return false
    return owner.methods.filter { it.name == "<init>" }.any { constructor ->
        val body = constructor.instructions()
        body.any { (it as? ReferenceInstruction)?.reference.let { call -> call is MethodReference && call.name == "addView" } } &&
            body.filter { it.opcode == Opcode.NEW_INSTANCE }
                .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                .any { built -> readsChevronWhenBuilt(built) }
    }
}

private fun BytecodePatchContext.readsChevronWhenBuilt(type: String): Boolean {
    val built = classDefByOrNull(type) ?: return false
    return built.methods.filter { it.name == "<init>" }
        .any { constructor -> constructor.instructions().any { it.readsId(TITLE_CHEVRON_ID) } }
}

/**
 * The closeup's title module taking its pin: the one-pin `updatePin` of a closeup module that
 * checks the holistic metadata experiment, in a class whose constructor builds the title row (the
 * view whose own constructor finds the chevron) and adds it. The description module checks the
 * same experiment and holds the same row, but builds it later and among other views, so it's left
 * out. The module is a LinearLayout and the row is its only child, which the extension checks.
 */
internal fun BytecodePatchContext.titleModuleUpdate(): Method =
    methodsWithString(HOLISTIC_METADATA)
        .filter { it.name == "updatePin" && !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.size == 1 }
        .filter { classDefByOrNull(it.definingClass)?.superclass == CLOSEUP_MODULE }
        .filter { buildsTitleRow(it.definingClass) }
        .one("$PATCH: closeup title module")

@Suppress("unused")
val showPostedDatePatch = bytecodePatch(
    name = PATCH,
    description = "Shows the day a pin was posted under its title when you open it. Pinterest sends that date " +
        "with every pin but never shows it. Starts off. Turn it on in HushPinterest settings > Interface.",
) {
    category("Interface")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())

    execute {
        requireStatusMethod("showPostedDate")
        requireStatusMethod("postedDate")
        val update = titleModuleUpdate()
        // The module and the pin are the method's last two registers. A range call reaches them
        // however many registers the method has.
        mutable(update).addInstructions(0, "invoke-static/range { p0 .. p1 }, $POSTED_DATE_HOOK")
        enableCapability("postedDate")
        enableStatus("showPostedDate")
    }
}
