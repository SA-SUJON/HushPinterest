/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.privacy

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.pinterest.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.handleTargets
import app.morphe.patches.pinterest.misc.extension.patchLog
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "Strip link tracking"
private const val LINKS = "$EXTENSION_PACKAGE/privacy/LinkTracking;"

/** Framework boundaries catch both obfuscated share flows and copy-link callbacks. */
internal val OUTGOING_LINK_CALLS = mapOf(
    "Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;" to
        "$LINKS->putStringExtra(Landroid/content/Intent;Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;",
    "Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/CharSequence;)Landroid/content/Intent;" to
        "$LINKS->putTextExtra(Landroid/content/Intent;Ljava/lang/String;Ljava/lang/CharSequence;)Landroid/content/Intent;",
    "Landroid/content/ClipData;->newPlainText(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Landroid/content/ClipData;" to
        "$LINKS->newPlainText(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Landroid/content/ClipData;",
)

@Suppress("unused")
val stripLinkTrackingPatch = bytecodePatch(
    name = PATCH,
    description = "Removes tracking tags from links you copy or share from Pinterest. The link still goes to the " +
        "same place. Its Plain pin links switch also turns short pin.it links into the pin's own pinterest.com " +
        "link, so they don't show who shared them. Strip link tracking is on by default and Plain pin links " +
        "starts off. Both are in HushPinterest settings > Privacy.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())

    execute {
        requireStatusMethod("stripLinkTracking")
        requireStatusMethod("linkTracking")
        requireStatusMethod("plainPinLinks")
        val counts = redirectPrivacyCalls(OUTGOING_LINK_CALLS)
        val covered = handleTargets(PATCH, "outgoing link boundaries", listOf("shared text", "copied text")) { target ->
            val covered = counts.any { (call, count) -> count > 0 &&
                (if (target == "shared text") call.startsWith("Landroid/content/Intent;")
                else call.startsWith("Landroid/content/ClipData;")) }
            if (covered) null else "no $target boundary was found"
        }
        if (covered == 2) enableCapability("linkTracking")
        // Plain pin links has its own two hooks, found before either goes in. Without both shapes
        // this patch still strips tracking, and the switch stays off the settings screen.
        val plain = try {
            insertPlainPinLinks(findPinInvite(), findDirectShare())
            true
        } catch (missing: PatchException) {
            patchLog.warning("$PATCH: ${missing.message}. Plain pin links isn't in this build.")
            false
        }
        if (plain) enableCapability("plainPinLinks")
        enableStatus("stripLinkTracking")
    }
}
