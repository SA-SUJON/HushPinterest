/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import static org.junit.Assert.*;

import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.SettingsContextRule;

/** #5: which folders Android's picker can hand back, and how a folder is kept and found again. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class DownloadFolderTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void reset() {
        DownloadFolder.PICKED.resetToDefault();
        Settings.SAVE_IN_PINTEREST_FOLDER.resetToDefault();
    }

    private static Uri tree(String authority, String id) {
        return DocumentsContract.buildTreeDocumentUri(authority, id);
    }

    private static Uri tree(String id) {
        return tree(DownloadFolder.STORAGE_AUTHORITY, id);
    }

    @Test public void aPickedTreeOnThePhonesOwnStorageIsAFolderInsideAStandardOne() {
        assertEquals("Pictures/Art", DownloadFolder.fromTree(tree("primary:Pictures/Art")));
        assertEquals("Pictures", DownloadFolder.fromTree(tree("primary:Pictures")));
        assertEquals("DCIM/Pins/2026", DownloadFolder.fromTree(tree("primary:DCIM/Pins/2026")));
        assertEquals("Download/Pins", DownloadFolder.fromTree(tree("primary:Download/Pins")));
        assertEquals("Documents/Mood boards", DownloadFolder.fromTree(tree("primary:Documents/Mood boards")));
        // The picker reports the folder's own spelling; Android's is what DownloadManager is handed.
        assertEquals("Pictures/Art", DownloadFolder.fromTree(tree("primary:pictures/Art/")));
        // The top of Download is Download itself.
        assertEquals(DownloadFolder.DOWNLOAD, DownloadFolder.fromTree(tree("primary:Download")));
        assertEquals(DownloadFolder.DOWNLOAD, DownloadFolder.fromTree(tree("primary:download/")));
    }

    @Test public void aTreeDownloadManagerCanNotWriteToIsRefused() {
        assertNull("storage root", DownloadFolder.fromTree(tree("primary:")));
        assertNull("a folder of its own at the top", DownloadFolder.fromTree(tree("primary:Pinterest")));
        assertNull("music can't hold pictures", DownloadFolder.fromTree(tree("primary:Music/Pins")));
        assertNull("movies can't hold pictures", DownloadFolder.fromTree(tree("primary:Movies/Pins")));
        assertNull("memory card", DownloadFolder.fromTree(tree("1234-ABCD:Pictures/Art")));
        assertNull("another app's tree", DownloadFolder.fromTree(tree("com.example.files", "primary:Pictures/Art")));
        assertNull("a document, not a tree", DownloadFolder.fromTree(DocumentsContract.buildDocumentUri(
                DownloadFolder.STORAGE_AUTHORITY, "primary:Pictures/Art")));
        assertNull(DownloadFolder.fromTree(null));
        assertNull(DownloadFolder.fromTree(Uri.parse("file:///storage/emulated/0/Pictures/Art")));
    }

    @Test public void onlyAPlainPathInsideAStandardFolderReadsAsOne() {
        assertNull(DownloadFolder.normalize(null));
        assertNull(DownloadFolder.normalize(""));
        assertNull(DownloadFolder.normalize("Download"));
        assertNull(DownloadFolder.normalize("Pictures/../Android/data"));
        assertNull(DownloadFolder.normalize("Pictures/./Art"));
        assertNull(DownloadFolder.normalize("Pictures//Art"));
        assertNull(DownloadFolder.normalize("Pictures/ Art"));
        assertNull(DownloadFolder.normalize("Pictures/Art\nx"));
        assertNull(DownloadFolder.normalize("Pictures/a:b"));
        assertNull(DownloadFolder.normalize("Pictures/a\\b"));
        assertNull(DownloadFolder.normalize("Pictures/a?b"));
        assertNull(DownloadFolder.normalize("Pictures/" + "x".repeat(DownloadFolder.MAX_LENGTH)));
        String longest = "Pictures/" + "x".repeat(DownloadFolder.MAX_LENGTH - "Pictures/".length());
        assertEquals(longest, DownloadFolder.normalize(longest));
        assertEquals("Pictures/Art, 100% mine", DownloadFolder.normalize("Pictures/Art, 100% mine"));
        assertEquals("Pictures/\u00dcn\u00efcode \u56f3", DownloadFolder.normalize("Pictures/\u00dcn\u00efcode \u56f3"));
    }

    @Test public void aFolderSplitsIntoWhatDownloadManagerIsHanded() {
        assertEquals(Environment.DIRECTORY_DOWNLOADS, DownloadFolder.directory(null));
        assertEquals("pin.jpg", DownloadFolder.subPath(null, "pin.jpg"));
        assertEquals(Environment.DIRECTORY_PICTURES, DownloadFolder.directory(DownloadFolder.PINTEREST));
        assertEquals("100PINT/Pins/pin.jpg", DownloadFolder.subPath(DownloadFolder.PINTEREST, "pin.jpg"));
        assertEquals(Environment.DIRECTORY_DCIM, DownloadFolder.directory("DCIM"));
        assertEquals("pin.jpg", DownloadFolder.subPath("DCIM", "pin.jpg"));
    }

    @Test public void aKeptFolderReadsBackExactlyAndNothingElseDoes() {
        for (String folder : new String[]{"Pictures/Art", "Pictures/a,b", "Pictures/100%", "Pictures/%2C", "Pictures/%25,%",
                DownloadFolder.PINTEREST}) {
            String kept = DownloadFolder.encode(folder);
            assertFalse(kept, kept.contains(","));
            assertEquals(folder, DownloadFolder.decode(kept));
        }
        assertNull(DownloadFolder.decode("Music/x"));
        assertNull(DownloadFolder.decode(""));
    }

    @Test public void theFolderARequestIsInComesFromItsLocalPath() {
        String storage = Environment.getExternalStorageDirectory().getPath();
        assertEquals("Pictures/Art", DownloadFolder.of(Uri.fromFile(new java.io.File(storage, "Pictures/Art/p.jpg")).toString()));
        assertNull(DownloadFolder.of(Uri.fromFile(new java.io.File(storage, "Download/p.jpg")).toString()));
        assertEquals("Download/Pins", DownloadFolder.of("file:///storage/emulated/0/Download/Pins/p.jpg"));
        assertEquals(DownloadFolder.PINTEREST, DownloadFolder.of("file:///storage/emulated/0/Pictures/100PINT/Pins/p.jpg"));
        assertNull(DownloadFolder.of("file:///storage/emulated/0/p.jpg"));
        assertNull(DownloadFolder.of("content://downloads/all_downloads/12"));
        assertNull(DownloadFolder.of(null));
    }

    @Test public void pinterestsFolderWinsThenThePickedOneThenDownload() {
        assertNull(DownloadFolder.current());
        DownloadFolder.PICKED.save("Pictures/Art");
        assertEquals("Pictures/Art", DownloadFolder.current());
        assertEquals("Pictures/Art", DownloadFolder.picked());
        Settings.SAVE_IN_PINTEREST_FOLDER.save(true);
        assertEquals(DownloadFolder.PINTEREST, DownloadFolder.current());
        assertEquals("Pictures/Art", DownloadFolder.picked());
        Settings.SAVE_IN_PINTEREST_FOLDER.save(false);
        DownloadFolder.PICKED.save("Pictures/../x");
        assertNull(DownloadFolder.current());
        assertNull(DownloadFolder.picked());
    }
}
