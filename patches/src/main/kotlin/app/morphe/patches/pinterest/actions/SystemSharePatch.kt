/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.actions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.pinterest.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.parameterRegister
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireLocals
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "System share sheet"

@Suppress("unused")
val systemSharePatch = bytecodePatch(
    name = PATCH,
    description = "Uses Android's share sheet when sharing a pin link. Screenshot and download actions keep their usual behavior. " +
        "Turn it off in HushPinterest settings at any time.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())
    execute {
        requireStatusMethod("systemShare")
        requireStatusMethod("pinShare")
        // Native shares from the menu and closeup converge here. The source enum distinguishes
        // screenshots/downloads, and the extension separately refuses boards, people and invites.
        val method = Fingerprint(
            returnType = "V",
            custom = { candidate, _ ->
                candidate.parameterTypes.size == 5 && candidate.parameterTypes[1].toString() == "I" &&
                    candidate.parameterTypes[3].toString() == "Z" && candidate.fields().map { it.name }.toSet().containsAll(
                        setOf("APP_LIST_AND_CONTACT_SUGGESTIONS_FOR_UPSELL", "SCREENSHOT", "DOWNLOAD"),
                    )
            },
        ).methodOrNull ?: throw PatchException("$PATCH: no native pin share chooser with screenshot/download source guards")
        method.requireLocals(PATCH, 2)
        val model = method.parameterRegister(0)
        val source = method.parameterRegister(2)
        method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, $model
                move-object/from16 v1, $source
                invoke-static { v0, v1 }, $EXTENSION_PACKAGE/actions/SystemShare;->open(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :hush_original_share
                return-void
            """,
            ExternalLabel("hush_original_share", method.getInstruction(0)),
        )
        enableCapability("pinShare")
        enableStatus("systemShare")
    }
}
