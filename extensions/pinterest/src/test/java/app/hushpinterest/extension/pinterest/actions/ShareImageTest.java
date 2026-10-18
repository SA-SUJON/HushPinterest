/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import app.hushpinterest.extension.pinterest.settings.PatchFamily;
import app.hushpinterest.extension.pinterest.settings.PatchFamilyForTests;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.SettingsContextRule;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.WorkerPoolForTests;
import app.hushpinterest.extension.shared.settings.HushPinterestPause;
import app.hushpinterest.extension.shared.settings.PauseForTests;

/**
 * Share image (#11): the picture goes to Android's share menu as the image itself, with a read grant
 * on the provider's shared address, the folder keeps the newest few, and every failure says why and
 * opens nothing. The fetch is Copy image's, answered from a table, so nothing reaches the network.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ShareImageTest {
    private static final String ORIGINAL = "https://i.pinimg.com/originals/source.png";
    private static final Map<String, Object> IMAGE_PIN = Map.of("id", "123", "images", Map.of("orig", Map.of(
            "url", ORIGINAL, "width", 3000, "height", 2000)));
    private static final String TOAST_FAILED = "Couldn't share the image. Check your connection and try again.";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Application app;
    private ContentResolver resolver;
    private Table table;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private PinTransfer.Connection previousConnection;
    private java.util.function.LongSupplier previousClock;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        resolver = app.getContentResolver();
        ProviderInfo info = new ProviderInfo();
        info.authority = app.getPackageName() + ".hushpinterest.share";
        info.name = SharePreviewProvider.class.getName();
        info.packageName = app.getPackageName();
        info.grantUriPermissions = true;
        Shadows.shadowOf(app.getPackageManager()).addOrUpdateProvider(info);
        Robolectric.buildContentProvider(SharePreviewProvider.class).create(info);
        PauseForTests.resume();
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.PIN_DOWNLOADS, PatchFamily.Capability.SHARE_IMAGE));
        Settings.DOWNLOAD_PINS.save(true);
        Settings.SHARE_IMAGE.save(true);
        table = new Table();
        previousConnection = CopyImage.connection;
        previousClock = CopyImage.clock;
        CopyImage.connection = table;
        CopyImage.clock = now::get;
        ShadowToast.reset();
        for (File dir : new File[]{CopiedImages.sharedDir(app), CopiedImages.dir(app)}) {
            File[] left = dir == null ? null : dir.listFiles();
            if (left != null) for (File file : left) file.delete();
        }
    }

    @After public void tearDown() {
        CopyImage.connection = previousConnection;
        CopyImage.clock = previousClock;
        Settings.SHARE_IMAGE.save(false);
        Settings.DOWNLOAD_PINS.save(false);
        PatchFamilyForTests.capabilities(null);
        PauseForTests.resume();
    }

    @Test public void aTapSendsTheFullSizePictureItselfThroughAndroidsShareMenu() throws Exception {
        byte[] png = FileHostForTests.picture(40, 30, Bitmap.CompressFormat.PNG);
        table.picture(ORIGINAL, "image/png", png);
        clipboard().setPrimaryClip(ClipData.newPlainText("before", "kept text"));

        assertTrue(ShareImage.start(IMAGE_PIN, app));
        Utils.awaitBackgroundTasksForTests();
        assertNull("no share menu until the picture is in place", Shadows.shadowOf(app).getNextStartedActivity());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Utils.awaitBackgroundTasksForTests();

        Intent chooser = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull("the share menu opened", chooser);
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        assertEquals("Share image", String.valueOf(chooser.getCharSequenceExtra(Intent.EXTRA_TITLE)));
        assertNotEquals("started from the app, so it needs a task of its own", 0, chooser.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        assertNotEquals("the chooser passes the read grant on", 0, chooser.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("image/png", send.getType());
        assertNull("the picture, not a link", send.getStringExtra(Intent.EXTRA_TEXT));
        assertNull("any app may take it", send.getPackage());
        assertNotEquals(0, send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Uri uri = send.getParcelableExtra(Intent.EXTRA_STREAM);
        File shared = new File(CopiedImages.sharedDir(app), uri.getLastPathSegment());
        assertEquals("content://" + app.getPackageName() + ".hushpinterest.share/shared/" + shared.getName(), uri.toString());
        assertTrue(shared.getName(), shared.getName().matches("[0-9a-f]{32}\\.png"));
        assertEquals(1, send.getClipData().getItemCount());
        assertEquals("the grant rides on the clip", uri, send.getClipData().getItemAt(0).getUri());
        assertEquals(uri, chooser.getClipData().getItemAt(0).getUri());

        assertEquals("image/png", resolver.getType(uri));
        try (InputStream in = resolver.openInputStream(uri)) {
            assertArrayEquals(png, in.readAllBytes());
        }
        try (Cursor row = resolver.query(uri, null, null, null, null)) {
            assertTrue(row.moveToFirst());
            assertEquals(shared.getName(), row.getString(row.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
            assertEquals(png.length, row.getLong(row.getColumnIndexOrThrow(OpenableColumns.SIZE)));
            assertEquals("the share menu may ask for a preview", DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL,
                    row.getInt(row.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)));
        }
        try (Cursor typed = resolver.query(uri, new String[]{MediaStore.MediaColumns.MIME_TYPE}, null, null, null)) {
            assertTrue(typed.moveToFirst());
            assertEquals("the type when an app asks for it", "image/png", typed.getString(0));
        }

        assertEquals("the clipboard is Copy image's business", "kept text", copiedText());
        assertEquals("the share menu is the answer, no toast", 0, ShadowToast.shownToastCount());
        assertEquals("nothing lands in Copy image's folder", 0, CopiedImages.count(CopiedImages.dir(app)));
        assertEquals(1, ownedShared());
    }

    @Test public void theNewestThreeSharedPicturesStayAndNothingElseIsTouched() throws Exception {
        File dir = CopiedImages.sharedDir(app);
        File[] older = {
                new File(dir, "00000000000000000000000000000001.jpg"),
                new File(dir, "00000000000000000000000000000002.png"),
                new File(dir, "00000000000000000000000000000003.webp"),
                new File(dir, "00000000000000000000000000000004.gif"),
        };
        long base = 1_700_000_000_000L;
        for (int i = 0; i < older.length; i++) {
            Files.write(older[i].toPath(), new byte[]{1});
            assertTrue(older[i].setLastModified(base + i * 60_000L));
        }
        File stranger = new File(dir, "holiday.png");
        File stale = new File(dir, "0123456789abcdef0123456789abcdef.part");
        Files.write(stranger.toPath(), new byte[]{1});
        Files.write(stale.toPath(), new byte[]{1});
        File copied = new File(CopiedImages.dir(app), "fedcba9876543210fedcba9876543210.jpg");
        Files.write(copied.toPath(), new byte[]{1});

        table.picture(ORIGINAL, "image/png", FileHostForTests.picture(8, 8, Bitmap.CompressFormat.PNG));
        CopyImage.Fetch fetched = CopyImage.fetch(media(), dir);
        assertEquals(CopyImage.Result.COPIED, fetched.result);
        assertFalse("the fetch clears a part left by a dead one", stale.exists());
        // Settling sweeps parts too, not only the fetch.
        Files.write(stale.toPath(), new byte[]{1});
        assertTrue(ShareImage.publish(app, fetched));
        assertEquals("opening the share menu deletes nothing", 7, dir.listFiles().length);
        ShareImage.settle(fetched, true);

        assertTrue("the picture just shared stays", fetched.file.isFile());
        assertTrue("the newest older one stays", older[3].isFile());
        assertTrue("and the next newest", older[2].isFile());
        assertFalse(older[1].exists());
        assertFalse(older[0].exists());
        assertFalse("settling clears the part", stale.exists());
        assertTrue("a file that isn't ours stays", stranger.exists());
        assertTrue("Copy image's folder is its own", copied.exists());
        assertEquals(ShareImage.KEEP, ownedShared());
    }

    @Test public void eachFailureSaysWhyAndOpensNoShareMenu() throws Exception {
        String[][] cases = {
                {"TOO_LARGE", "That image is too big to share."},
                {"NOT_IMAGE", "Pinterest didn't send a usable image."},
                {"TIMED_OUT", "Getting the image took too long. Try again."},
                {"FAILED", TOAST_FAILED},
        };
        for (String[] each : cases) {
            ShadowToast.reset();
            CopyImage.Fetch refused = new CopyImage.Fetch(CopyImage.Result.valueOf(each[0]), null, null);
            assertFalse(each[0], ShareImage.publish(app, refused));
            ShareImage.settle(refused, false);
            assertEquals(each[0], each[1], ShadowToast.getTextOfLatestToast());
            assertNull(each[0], Shadows.shadowOf(app).getNextStartedActivity());
        }

        // A slow host, through the real fetch.
        ShadowToast.reset();
        table.answer(ORIGINAL, url -> new FileHostForTests.Answer(url, 200, new FileHostForTests.Body(new byte[0]))
                .failing(new SocketTimeoutException("slow")));
        CopyImage.Fetch slow = CopyImage.fetch(media(), CopiedImages.sharedDir(app));
        assertFalse(ShareImage.publish(app, slow));
        assertEquals("Getting the image took too long. Try again.", ShadowToast.getTextOfLatestToast());

        // And a refused one, through a tap.
        ShadowToast.reset();
        table.answer(ORIGINAL, url -> new FileHostForTests.Answer(url, 404, new FileHostForTests.Body(new byte[0])));
        assertTrue(ShareImage.start(IMAGE_PIN, app));
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Utils.awaitBackgroundTasksForTests();
        assertEquals(TOAST_FAILED, ShadowToast.getTextOfLatestToast());
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        assertEquals(0, ownedShared());
    }

    @Test public void switchedOffPausedOrUnpatchedMeansNoRowAndNoStart() {
        PinMedia.Resolution image = media();
        assertTrue(ShareImage.offered(image));
        Settings.SHARE_IMAGE.save(false);
        assertFalse(ShareImage.offered(image));
        assertFalse(ShareImage.start(IMAGE_PIN, app));
        Settings.SHARE_IMAGE.save(true);
        Settings.DOWNLOAD_PINS.save(false);
        assertFalse("Download pins off takes Share image with it", ShareImage.offered(image));
        Settings.DOWNLOAD_PINS.save(true);
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.PIN_DOWNLOADS, PatchFamily.Capability.COPY_IMAGE));
        assertFalse("a build without it has no Share image", ShareImage.offered(image));
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.SHARE_IMAGE));
        assertFalse("and Download pins must be patched in too", ShareImage.offered(image));
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.PIN_DOWNLOADS, PatchFamily.Capability.SHARE_IMAGE));
        PauseForTests.pause(HushPinterestPause.Reason.SWITCH);
        assertFalse(ShareImage.offered(image));
        assertFalse(ShareImage.start(IMAGE_PIN, app));
        PauseForTests.resume();
        assertTrue(ShareImage.offered(image));
        Settings.COPY_IMAGE.save(false);
        assertTrue("Copy image's switch has no say", ShareImage.offered(image));
        assertTrue("nothing was asked of the network", table.asked.isEmpty());
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test public void aVideoOrAnUnusablePinGetsNoRow() {
        PinMedia.Resolution video = PinMedia.resolve(Map.of("id", "123", "videos", Map.of("video_list", Map.of(
                "large", Map.of("url", "https://v.pinimg.com/large.mp4", "width", 1920, "height", 1080)))));
        assertNotNull(video.source);
        assertFalse(ShareImage.offered(video));
        assertFalse(ShareImage.offered(null));
        assertFalse(ShareImage.start(Map.of("id", "123"), app));
        assertTrue(table.asked.isEmpty());
    }

    @Test public void switchedOffWhileItFetchedOpensNothingAndDeletesThePicture() throws Exception {
        table.picture(ORIGINAL, "image/png", FileHostForTests.picture(8, 8, Bitmap.CompressFormat.PNG));
        CopyImage.Fetch fetched = CopyImage.fetch(media(), CopiedImages.sharedDir(app));
        Settings.SHARE_IMAGE.save(false);
        assertFalse(ShareImage.publish(app, fetched));
        ShareImage.settle(fetched, false);
        assertFalse(fetched.file.exists());
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test public void aSecondTapWhileAShareIsUnderwayIsToldSo() throws Exception {
        table.picture(ORIGINAL, "image/png", FileHostForTests.picture(40, 30, Bitmap.CompressFormat.PNG));
        assertTrue(ShareImage.start(IMAGE_PIN, app));
        assertFalse(ShareImage.start(IMAGE_PIN, app));
        assertEquals("Still getting the last image ready.", ShadowToast.getTextOfLatestToast());

        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Utils.awaitBackgroundTasksForTests();
        assertNotNull(Shadows.shadowOf(app).getNextStartedActivity());
        assertNull("one tap, one share menu", Shadows.shadowOf(app).getNextStartedActivity());
        assertEquals("one tap, one fetch", 1, table.asked.size());
        assertTrue("free again once the first share is tidied", ShareImage.start(IMAGE_PIN, app));
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Utils.awaitBackgroundTasksForTests();
        assertEquals("the first picture stays for the app that may still read it", 2, ownedShared());
    }

    @Test public void aFullWorkQueueNeverLeavesShareImageStuckBusy() throws Exception {
        table.picture(ORIGINAL, "image/png", FileHostForTests.picture(8, 8, Bitmap.CompressFormat.PNG));
        try (WorkerPoolForTests full = WorkerPoolForTests.fill()) {
            assertFalse(ShareImage.start(IMAGE_PIN, app));
            assertEquals(TOAST_FAILED, ShadowToast.getTextOfLatestToast());
        }
        assertTrue("nothing was fetched", table.asked.isEmpty());

        // The tidy-up can't be queued once the share menu opens.
        assertTrue(ShareImage.start(IMAGE_PIN, app));
        Utils.awaitBackgroundTasksForTests();
        try (WorkerPoolForTests full = WorkerPoolForTests.fill()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull("the share menu opened", Shadows.shadowOf(app).getNextStartedActivity());
        }
        assertTrue("free again though the tidy-up never ran", ShareImage.start(IMAGE_PIN, app));
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Utils.awaitBackgroundTasksForTests();
        assertNotNull(Shadows.shadowOf(app).getNextStartedActivity());
        assertEquals(2, table.asked.size());
    }

    @Test public void theProviderServesSharedPicturesReadOnlyAndOnlyFromTheirOwnFolder() throws Exception {
        File dir = CopiedImages.sharedDir(app);
        File picture = new File(dir, "0123456789abcdef0123456789abcdef.png");
        Files.write(picture.toPath(), FileHostForTests.picture(8, 8, Bitmap.CompressFormat.PNG));
        File clip = new File(CopiedImages.dir(app), "fedcba9876543210fedcba9876543210.png");
        Files.write(clip.toPath(), FileHostForTests.picture(8, 8, Bitmap.CompressFormat.PNG));
        File part = new File(dir, "00000000000000000000000000000000.part");
        Files.write(part.toPath(), new byte[]{1});
        File outside = new File(app.getCacheDir(), "secret.png");
        Files.write(outside.toPath(), new byte[]{1, 2, 3});
        String base = "content://" + app.getPackageName() + ".hushpinterest.share/";

        resolver.openInputStream(Uri.parse(base + "shared/" + picture.getName())).close();
        resolver.openInputStream(Uri.parse(base + "clip/" + clip.getName())).close();
        for (String bad : new String[]{"shared/" + clip.getName(), "clip/" + picture.getName(), "shared/secret.png",
                "shared/" + part.getName(), "shared/..%2Fsecret.png", "shared", "shared/" + picture.getName() + "/extra",
                "shared/0123456789ABCDEF0123456789ABCDEF.png"}) {
            Uri uri = Uri.parse(base + bad);
            assertNull(bad, resolver.getType(uri));
            try {
                resolver.openInputStream(uri).close();
                fail("served " + bad);
            } catch (FileNotFoundException | SecurityException expected) {
                // Refused.
            }
        }
        for (String mode : new String[]{"w", "rw", "wt", "rwt"}) {
            try {
                resolver.openFileDescriptor(Uri.parse(base + "shared/" + picture.getName()), mode).close();
                fail("opened for " + mode);
            } catch (SecurityException expected) {
                // Read-only.
            }
        }
        assertTrue(outside.exists());
        assertNull(SharePreviewProvider.sharedUri(app, "secret.png"));
        assertNull(SharePreviewProvider.sharedUri(app, part.getName()));
        assertEquals(base + "shared/" + picture.getName(), String.valueOf(SharePreviewProvider.sharedUri(app, picture.getName())));
    }

    private PinMedia.Resolution media() {
        PinMedia.Resolution media = PinMedia.resolve(IMAGE_PIN);
        assertNotNull(media);
        assertNotNull(media.source);
        return media;
    }

    private ClipboardManager clipboard() {
        return (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    private String copiedText() {
        ClipData clip = clipboard().getPrimaryClip();
        return clip == null ? null : String.valueOf(clip.getItemAt(0).getText());
    }

    private int ownedShared() {
        File dir = CopiedImages.sharedDir(app);
        return dir == null ? 0 : CopiedImages.count(dir);
    }

    /** Answers the fetch from a table. An address with no answer gets a 404. */
    private static final class Table implements PinTransfer.Connection {
        private final Map<String, Function<URL, FileHostForTests.Answer>> answers = new java.util.concurrent.ConcurrentHashMap<>();
        final List<FileHostForTests.Answer> asked = new CopyOnWriteArrayList<>();

        void answer(String url, Function<URL, FileHostForTests.Answer> answer) {
            answers.put(url, answer);
        }

        void picture(String url, String type, byte[] bytes) {
            answer(url, address -> new FileHostForTests.Answer(address, 200, new FileHostForTests.Body(bytes))
                    .header("Content-Type", type).header("Content-Length", String.valueOf(bytes.length)));
        }

        @Override public java.net.HttpURLConnection open(java.net.URI uri) throws IOException {
            Function<URL, FileHostForTests.Answer> answer = answers.get(uri.toString());
            FileHostForTests.Answer opened = answer == null
                    ? new FileHostForTests.Answer(uri.toURL(), 404, new FileHostForTests.Body(new byte[0]))
                    : answer.apply(uri.toURL());
            asked.add(opened);
            return opened;
        }
    }
}
