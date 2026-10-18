/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ads

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "Filter feed"

/**
 * Takes more out of the lists Hide ads works on, through the shared list hook ([feedListHookPatch]),
 * each kind of pin on its own switch. Video pins are a pin marked `is_video`, one whose `videos`
 * list has an entry, or an idea pin whose `story_pin_data` runs for more than zero seconds. Muted
 * words are a list of words and phrases matched whole against a pin's title, grid title and
 * description. The fields are read by JSON name at run time; a GIF is an image to Pinterest and stays.
 */
@Suppress("unused")
val filterFeedPatch = bytecodePatch(
    name = PATCH,
    description = "Lets you take video pins (photos and GIFs stay) and pins that mention words you mute out of your " +
        "home feed, search, related pins and boards. Starts off. Set it up in HushPinterest settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, pinterestExtensionPatch, feedListHookPatch)
    compatibleWith(*AppCompatibilities.pinterest())

    execute {
        requireStatusMethod("filterFeed")
        requireStatusMethod("feedVideoPins")
        requireStatusMethod("feedMutedWords")
        if (feedListHoldersHooked == 0) throw PatchException("$PATCH: no list holder was hooked")
        enableCapability("feedVideoPins")
        enableCapability("feedMutedWords")
        enableStatus("filterFeed")
    }
}
