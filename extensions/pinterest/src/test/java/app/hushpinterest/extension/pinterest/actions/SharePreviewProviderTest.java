/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Size;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SharePreviewProviderTest {
    private static final String VIDEO = "content://media/external_primary/downloads/248";
    private static final String IMAGE = "content://media/external_primary/downloads/249";
    /** A video another app saved. */
    private static final String OTHERS = "content://media/external_primary/video/media/250";
    /** A download MediaStore didn't file as a video, whatever the request said. */
    private static final String UNTYPED = "content://media/external_primary/downloads/251";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Application app;
    private ContentResolver resolver;
    private String authority;
    private SharePreviewProvider provider;
    private FakeMedia media;

    @Before public void setUp() throws IOException {
        app = RuntimeEnvironment.getApplication();
        resolver = app.getContentResolver();
        authority = app.getPackageName() + ".hushpinterest.share";
        ProviderInfo info = new ProviderInfo();
        info.authority = authority;
        info.name = SharePreviewProvider.class.getName();
        info.packageName = app.getPackageName();
        info.grantUriPermissions = true;
        Shadows.shadowOf(app.getPackageManager()).addOrUpdateProvider(info);
        provider = Robolectric.buildContentProvider(SharePreviewProvider.class).create(info).get();
        media = Robolectric.buildContentProvider(FakeMedia.class).create("media").get();
        media.video = temporary.newFile("clip.mp4");
        Files.write(media.video.toPath(), "the saved clip".getBytes(StandardCharsets.UTF_8));
        media.thumbnail = temporary.newFile("thumb.png");
        Bitmap picture = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        picture.eraseColor(Color.RED);
        try (OutputStream out = new FileOutputStream(media.thumbnail)) {
            picture.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        media.types.put(Uri.parse(VIDEO).getPath(), "video/mp4");
        media.types.put(Uri.parse(IMAGE).getPath(), "image/jpeg");
        media.types.put(Uri.parse(OTHERS).getPath(), "video/mp4");
        media.types.put(Uri.parse(UNTYPED).getPath(), "application/octet-stream");
        for (String own : new String[]{VIDEO, IMAGE, UNTYPED}) media.owners.put(Uri.parse(own).getPath(), app.getPackageName());
        media.owners.put(Uri.parse(OTHERS).getPath(), "com.example.camera");
    }

    /**
     * A video goes out under this provider's address, which reports the file's own name and size,
     * says it has a thumbnail, hands over the very file, and answers an image request with a frame
     * at the size the share sheet asked for.
     */
    @Test public void aSharedVideoKeepsItsFileAndNameAndGetsAFrame() throws Exception {
        Uri shared = SharePreviewProvider.wrap(app, Uri.parse(VIDEO), "video/mp4");
        assertEquals("content://" + authority + "/media/external_primary/downloads/248", shared.toString());

        assertEquals("video/mp4", resolver.getType(shared));
        assertEquals(Arrays.asList("video/mp4", "image/jpeg"), Arrays.asList(resolver.getStreamTypes(shared, "*/*")));
        assertEquals(Arrays.asList("image/jpeg"), Arrays.asList(resolver.getStreamTypes(shared, "image/*")));
        // No projection is how the share sheet asks on Android 11 to 13: it needs the flags column.
        try (Cursor named = resolver.query(shared, null, null, null, null)) {
            assertEquals(Arrays.asList(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_FLAGS),
                    Arrays.asList(named.getColumnNames()));
            assertTrue(named.moveToFirst());
            assertEquals("Pinterest_123_0.mp4", named.getString(0));
            assertEquals(14, named.getLong(1));
            assertEquals(DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL, named.getInt(2));
        }
        // The newer share sheet's own question: unknown columns are left out, the thumbnail flag is set.
        String[] sheet = {DocumentsContract.Document.COLUMN_FLAGS, "android.media.metadata.DISPLAY_ICON_URI",
                OpenableColumns.DISPLAY_NAME, "title"};
        try (Cursor asked = resolver.query(shared, sheet, null, null, null)) {
            assertEquals(Arrays.asList(DocumentsContract.Document.COLUMN_FLAGS, OpenableColumns.DISPLAY_NAME),
                    Arrays.asList(asked.getColumnNames()));
            assertTrue(asked.moveToFirst());
            assertEquals(DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL, asked.getInt(0));
        }
        try (InputStream in = resolver.openInputStream(shared)) {
            assertEquals("the saved clip", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        Bitmap frame = resolver.loadThumbnail(shared, new Size(64, 64), null);
        assertEquals(40, frame.getWidth());
        assertEquals(30, frame.getHeight());
        int middle = frame.getPixel(20, 15);
        assertTrue("the frame isn't the video's thumbnail: " + Integer.toHexString(middle),
                Color.red(middle) > 200 && Color.green(middle) < 60 && Color.blue(middle) < 60);
        assertEquals(new Point(64, 64), media.sizes.get(0));
    }

    /**
     * An image keeps its MediaStore address, which already previews, and so does anything that
     * isn't one entry, a video another app saved, and a download MediaStore didn't file as a video.
     */
    @Test public void onlyAMediaStoreVideoEntryIsWrapped() {
        assertEquals(IMAGE, SharePreviewProvider.wrap(app, Uri.parse(IMAGE), "image/jpeg").toString());
        assertEquals(IMAGE, SharePreviewProvider.wrap(app, Uri.parse(IMAGE), "video/mp4").toString());
        assertEquals(OTHERS, SharePreviewProvider.wrap(app, Uri.parse(OTHERS), "video/mp4").toString());
        assertEquals(UNTYPED, SharePreviewProvider.wrap(app, Uri.parse(UNTYPED), "video/mp4").toString());
        for (String other : new String[]{"content://media/external/video/media", "content://downloads/all_downloads/5",
                "content://media/external/video/media/12x", "file:///storage/emulated/0/Download/clip.mp4"}) {
            assertEquals(other, SharePreviewProvider.wrap(app, Uri.parse(other), "video/mp4").toString());
        }
        assertEquals(VIDEO, SharePreviewProvider.wrap(null, Uri.parse(VIDEO), "video/mp4").toString());
        assertNull(SharePreviewProvider.wrap(app, null, "video/mp4"));
    }

    /** A patched app without the manifest entry still shares, by the MediaStore address. */
    @Test public void withoutItsManifestEntryTheMediaStoreAddressGoesOut() {
        Shadows.shadowOf(app.getPackageManager()).removeProvider(new ComponentName(app, SharePreviewProvider.class));
        assertEquals(VIDEO, SharePreviewProvider.wrap(app, Uri.parse(VIDEO), "video/mp4").toString());
    }

    /**
     * Whatever address a grant holder tries here, only a single video entry this app saved answers:
     * an image row, another app's video, a row MediaStore doesn't call a video, a whole collection
     * or another provider's path gets nothing, nothing can be written, and a caller without a grant
     * can't even learn an address's type.
     */
    @Test public void nothingButOneVideoEntryIsServedOrWritten() throws Exception {
        for (String address : new String[]{"/media/external_primary/downloads/249", "/media/external_primary/video/media/250",
                "/media/external_primary/downloads/251", "/media/external/video/media",
                "/media/external_primary/downloads/12x", "/downloads/all_downloads/248", "/media/248"}) {
            Uri uri = Uri.parse("content://" + authority + address);
            assertNull(address, resolver.getType(uri));
            assertNull(address, resolver.query(uri, null, null, null, null));
            assertNull(address, resolver.getStreamTypes(uri, "*/*"));
            assertThrows(address, FileNotFoundException.class, () -> resolver.openInputStream(uri).close());
        }
        Uri shared = Uri.parse("content://" + authority + "/media/external_primary/downloads/248");
        assertEquals("video/mp4", resolver.getType(shared));
        assertNull(provider.getTypeAnonymous(shared));
        assertThrows(SecurityException.class, () -> provider.openFile(shared, "rw"));
        assertThrows(UnsupportedOperationException.class, () -> resolver.insert(shared, new ContentValues()));
        assertThrows(UnsupportedOperationException.class, () -> resolver.delete(shared, null, null));
        assertThrows(FileNotFoundException.class, () -> provider.openTypedAssetFile(shared, "text/plain", null));
        assertTrue(media.sizes.isEmpty());
    }

    /** MediaStore as these tests need it: typed and owned rows, the video's bytes and its thumbnail. */
    public static final class FakeMedia extends ContentProvider {
        final Map<String, String> types = new HashMap<>();
        final Map<String, String> owners = new HashMap<>();
        final List<Point> sizes = new ArrayList<>();
        File video;
        File thumbnail;

        @Override public boolean onCreate() {
            return true;
        }

        @Override public String getType(Uri uri) {
            return types.get(uri.getPath());
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            if (!types.containsKey(uri.getPath())) return null;
            String[] columns = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "Pinterest_123_0.mp4";
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = video == null ? null : video.length();
                else if (MediaStore.MediaColumns.OWNER_PACKAGE_NAME.equals(columns[i])) values[i] = owners.get(uri.getPath());
            }
            MatrixCursor row = new MatrixCursor(columns);
            row.addRow(values);
            return row;
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (!types.containsKey(uri.getPath())) throw new FileNotFoundException(uri.toString());
            return ParcelFileDescriptor.open(video, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        @SuppressWarnings("deprecation")
        @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String filter, Bundle opts) throws FileNotFoundException {
            // Like MediaStore, an image request gets the thumbnail and anything else the file.
            if (filter == null || !filter.startsWith("image/")) return super.openTypedAssetFile(uri, filter, opts);
            sizes.add(opts == null ? null : opts.getParcelable(ContentResolver.EXTRA_SIZE));
            return new AssetFileDescriptor(ParcelFileDescriptor.open(thumbnail, ParcelFileDescriptor.MODE_READ_ONLY), 0, thumbnail.length());
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            throw new UnsupportedOperationException();
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            throw new UnsupportedOperationException();
        }

        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            throw new UnsupportedOperationException();
        }
    }
}
