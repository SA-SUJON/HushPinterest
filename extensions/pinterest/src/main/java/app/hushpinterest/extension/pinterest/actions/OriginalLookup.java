/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import java.io.IOException;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/**
 * Asks Pinterest's media host whether it keeps the original behind a size Pinterest supplied.
 * Pinterest's app requests display sizes only, so most live pins reach a download this way. Each
 * question is a HEAD request to the supplied host that follows no redirect, and only a 200 with
 * the image type the address names counts. Worker threads only.
 */
final class OriginalLookup {
    private OriginalLookup() {}

    /** Tests replace it so they never reach the network. */
    static volatile PinTransfer.Connection connection = PinTransfer.NETWORK;

    /** What a lookup chose, and whether the media host's answers settled it. */
    static final class Found {
        final PinMedia.Source source;
        /** False when the host couldn't answer, so asking again later may find the original. */
        final boolean settled;

        Found(PinMedia.Source source, boolean settled) {
            this.source = source;
            this.settled = settled;
        }
    }

    /** The original image behind a stand-in size when the media host has one, otherwise the stand-in itself. */
    static PinMedia.Source find(PinMedia.Source standIn) {
        return look(standIn).source;
    }

    /** {@link #find}, saying too whether the answer would hold if asked again. */
    static Found look(PinMedia.Source standIn) {
        for (PinMedia.Source original : PinMedia.originals(standIn)) {
            try {
                if (PinTransfer.present(original, connection)) {
                    HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "original image found on media host");
                    return new Found(original, true);
                }
            } catch (IOException | RuntimeException unreachable) {
                // One failed question means the host can't answer now. Don't wait out the rest.
                HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "original image lookup unreachable");
                return new Found(standIn, false);
            }
        }
        HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "largest supplied size used");
        return new Found(standIn, true);
    }
}
