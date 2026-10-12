/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.freeLocalsAt
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Play Core's in-app update package, which the guarded branch calls to show the prompt. */
internal const val PLAY_CORE_UPDATES = "Lcom/google/android/play/core/appupdate/"

/**
 * Guards the update branch of the one coroutine that hands Play Core's in-app update manager its
 * prompt. Every declared build has it, so a build without the anchor refuses rather than claiming
 * a hook it never installed.
 */
@Suppress("unused")
val disableUpdateNagPatch = bytecodePatch(
    name = "Disable update nag",
    description = "Stops Pinterest's pop-ups asking you to update from the Play Store. You can still update " +
        "Pinterest yourself. Good if the reminders get annoying. Starts off. Turn it on in HushPinterest " +
        "settings > More settings > Updates.",
) {
    category("Updates")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())
    execute {
        requireStatusMethod("disableUpdateNag")
        requireStatusMethod("updateNag")
        val candidates = methodsWithString("inAppUpdateManager").filter {
            it.name == "invokeSuspend" && it.parameters() == listOf("Ljava/lang/Object;") && it.returnType == "Ljava/lang/Object;"
        }
        val target = mutable(candidates.one("In-app Play Store update prompt"))
        val at = target.instructions().indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "inAppUpdateManager"
        }
        // A build that reaches Play Core before it reads the update manager would prompt before the
        // hook runs, so the anchor has to come first.
        val prompt = target.instructions().indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass
                ?.startsWith(PLAY_CORE_UPDATES) == true
        }
        if (prompt < 0 || at > prompt) {
            throw PatchException("Disable update nag: the update manager's anchor no longer comes before Play Core's update call")
        }
        val unit = target.fields().filter { it.definingClass == "Lkotlin/Unit;" && it.type == "Lkotlin/Unit;" }
            .distinctBy { it.toString() }.one("Coroutine Unit result")
        val register = target.freeLocalsAt("Play Store update prompt", at, 1, highest = 255).single()
        // This coroutine also handles an unrelated home-feed task. Guard only its update branch.
        target.addInstructionsWithLabels(at, """
            invoke-static {}, $UI_HOOKS->disableUpdateNag()Z
            move-result v$register
            if-eqz v$register, :hush_keep_update
            sget-object v$register, $unit
            return-object v$register
        """, ExternalLabel("hush_keep_update", target.getInstruction(at)))
        enableCapability("updateNag")
        enableStatus("disableUpdateNag")
    }
}
