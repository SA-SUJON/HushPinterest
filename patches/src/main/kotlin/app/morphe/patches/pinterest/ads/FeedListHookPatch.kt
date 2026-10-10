/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.pinterest.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.pinterest.misc.extension.HOOK_TARGETS
import app.morphe.patches.pinterest.misc.extension.HookTarget
import app.morphe.patches.pinterest.misc.extension.handleTargets
import app.morphe.patches.pinterest.misc.extension.parameterRegister
import app.morphe.patches.pinterest.misc.extension.patchLog
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.recordTargets
import app.morphe.patches.pinterest.misc.extension.requireStub
import app.morphe.patches.pinterest.misc.settings.settingsPatch

internal const val FEED_FILTER = "$EXTENSION_PACKAGE/ads/FeedFilter;"

// The shared hook fails for whichever list patch was selected, so its messages name both.
private const val PATCH = "Feed list filter (Hide ads, Hide AI-labeled pins)"

private const val LIST = "Ljava/util/List;"

/** The [HOOK_TARGETS] stub that records each list holder this run looked for. */
internal const val FEED_LIST_TARGETS = "feedLists"

/** How many of the three list holders the last run hooked. The family patches read it after this one runs. */
internal var feedListHoldersHooked = 0
    private set

/**
 * Each list holder the last run looked for, hooked or not, with how many of its list constructors
 * went in. The same entries go into the extension's `HookTargets.feedLists`.
 */
internal var feedListTargets: List<HookTarget> = emptyList()
    private set

/**
 * One list holder: the fingerprint that finds it, its name in messages, the id the extension
 * knows it by, and how many constructors taking one list it has in 14.39.0.
 */
private class ListHolder(val fingerprint: Fingerprint, val what: String, val id: String, val constructors: Int)

/**
 * Passes the items each list holder is built with through `FeedFilter.filter`, first thing in its
 * constructor, so what a family drops never reaches Pinterest's adapters. A holder whose class
 * isn't in a build is warned about and skipped; a build with none of the three stops the patch.
 * A holder with fewer list constructors than 14.39.0 has is hooked where it can be and warned
 * about too, and each holder's result is recorded for the settings screen and the report.
 */
internal val feedListHookPatch = bytecodePatch {
    dependsOn(settingsPatch, pinterestExtensionPatch)

    execute {
        // A run that stops below must not leave the last run's count for the family patches.
        feedListHoldersHooked = 0
        feedListTargets = emptyList()
        requireStub(HOOK_TARGETS, FEED_LIST_TARGETS)
        val holders = listOf(
            ListHolder(FeedToStringFingerprint, "feed", "feedList", 2),
            ListHolder(PagedResponseToStringFingerprint, "paged response", "pagedResponse", 1),
            ListHolder(ModelListToStringFingerprint, "model list with bookmark", "modelList", 1),
        )
        val recorded = mutableListOf<HookTarget>()
        feedListHoldersHooked = handleTargets(PATCH, "list holders", holders) { holder ->
            val toString = holder.fingerprint.methodOrNull
            if (toString == null) {
                recorded += HookTarget(holder.id, 0, holder.constructors)
                return@handleTargets "no ${holder.what} class describes itself the way 14.39.0 does"
            }
            val constructors = mutableClassDefBy(toString.definingClass).methods.filter { method ->
                method.name == "<init>" && method.implementation != null &&
                    method.parameterTypes.count { it.toString() == LIST } == 1
            }
            if (constructors.isEmpty()) {
                recorded += HookTarget(holder.id, 0, holder.constructors, toString.definingClass)
                return@handleTargets "the ${holder.what} class ${toString.definingClass} has no constructor taking one list"
            }
            constructors.forEach { constructor ->
                val items = constructor.parameterRegister(constructor.parameterTypes.indexOfFirst { it.toString() == LIST })
                // Before the superclass constructor: only the list's own register is touched, and a
                // static call on it is allowed there.
                constructor.addInstructions(
                    0,
                    """
                        invoke-static/range { $items .. $items }, $FEED_FILTER->filter($LIST)$LIST
                        move-result-object $items
                    """,
                )
            }
            if (constructors.size < holder.constructors) {
                patchLog.warning(
                    "$PATCH: the ${holder.what} class ${toString.definingClass} has ${constructors.size} of the " +
                        "${holder.constructors} constructors taking one list that 14.39.0 has, so a list built " +
                        "another way isn't filtered",
                )
            }
            recorded += HookTarget(holder.id, constructors.size, maxOf(holder.constructors, constructors.size), toString.definingClass)
            null
        }
        feedListTargets = recorded.toList()
        recordTargets(FEED_LIST_TARGETS, feedListTargets)
    }
}
